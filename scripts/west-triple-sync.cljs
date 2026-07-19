#!/usr/bin/env nbb
;; west-triple-sync.cljs — GitHub / local / west 三点同期オーケストレータ。
;;
;; ADR-2607173200 / manifest/west-triple-sync-workflow.edn
;;
;; 既定は plan（dry-run）。apply は明示フラグ必須。
;; wholesale gen-west-manifest commit / force-push / dirty overwrite はしない。
;;
;; 使い方:
;;   nbb scripts/west-triple-sync.cljs plan  [--scope blocking|managed|names] [--names a,b]
;;   nbb scripts/west-triple-sync.cljs apply [--scope ...] [--names ...] [--no-pin-advance]
;;   nbb scripts/west-triple-sync.cljs verify [--scope ...] [--names ...]
;;
;; exit 0: plan 成功 / apply+verify で scope 内 blocking 解消
;; exit 1: verify 失敗 or apply 中に fatal
;; exit 2: 引数エラー

(require '[scripts.nbb-compat :refer [slurp spit sh]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(def node-fs (js/require "node:fs"))
(def node-path (js/require "node:path"))

(def root
  (-> (sh "git" "rev-parse" "--show-toplevel") :out str/trim))

(defn exists? [p]
  (try (.existsSync node-fs p) (catch :default _ false)))

(defn is-dir? [p]
  (try (.isDirectory (.statSync node-fs p)) (catch :default _ false)))

(defn read-text [p]
  (when (exists? p) (try (slurp p) (catch :default _ nil))))

(defn sh-ok [& args]
  (let [{:keys [exit out err]} (apply sh args)]
    {:ok? (zero? exit)
     :exit exit
     :out (str/trim (or out ""))
     :err (str/trim (or err ""))}))

(defn die! [code msg]
  (binding [*out* *err*]
    (println msg))
  (.exit js/process code))

;; ---------- args ----------

(defn parse-args [argv]
  (let [argv (vec (or argv []))
        cmd (or (first argv) "plan")
        rest (rest argv)
        opts (loop [xs rest acc {:scope "blocking" :scope-set? false
                                 :names [] :no-pin-advance? false}]
               (if-not (seq xs)
                 acc
                 (let [[a b & more] xs]
                   (case a
                     "--scope" (recur more (assoc acc :scope b :scope-set? true))
                     "--names" (recur more (assoc acc :names
                                                  (->> (str/split (or b "") #",")
                                                       (map str/trim)
                                                       (remove str/blank?)
                                                       vec)))
                     "--no-pin-advance" (recur (cons b more)
                                               (assoc acc :no-pin-advance? true))
                     (die! 2 (str "unknown arg: " a))))))
        ;; --names alone implies scope=names unless --scope was explicit
        opts (if (and (seq (:names opts)) (not (:scope-set? opts)))
               (assoc opts :scope "names")
               opts)]
    (when-not (#{"plan" "apply" "verify"} cmd)
      (die! 2 (str "unknown command: " cmd " (plan|apply|verify)")))
    (when-not (#{"blocking" "managed" "names"} (:scope opts))
      (die! 2 (str "unknown scope: " (:scope opts))))
    (when (and (= (:scope opts) "names") (empty? (:names opts)))
      (die! 2 "--scope names requires --names a,b"))
    (assoc opts :cmd cmd :apply? (= cmd "apply"))))

;; ---------- west / remotes / overrides ----------

(defn west-entries
  "[{:name :path :revision} ...]"
  []
  (let [text (or (read-text (node-path.join root "manifest/west.yml")) "")
        lines (str/split-lines text)
        cur (atom nil)
        acc (atom [])]
    (doseq [l lines]
      (when-let [n (second (re-find #"^\s+- name:\s+(\S+)" l))]
        (when @cur (swap! acc conj @cur))
        (reset! cur {:name n}))
      (when @cur
        (when-let [p (second (re-find #"^\s+path:\s+(\S+)" l))]
          (swap! cur assoc :path p))
        (when-let [r (second (re-find #"^\s+revision:\s+(\S+)" l))]
          (swap! cur assoc :revision r))))
    (when @cur (swap! acc conj @cur))
    (vec (filter :path @acc))))

(defn west-by-path []
  (into {} (map (fn [e] [(:path e) e]) (west-entries))))

(defn west-by-name []
  (into {} (map (fn [e] [(:name e) e]) (west-entries))))

(defn remotes-map
  "org -> url-base"
  []
  (let [text (or (read-text (node-path.join root "manifest/repos.edn")) "")
        m (re-find #":manifest\.repos/remotes\s+\"(.*?)\",\s*:manifest" text)]
    (if-not m
      {}
      (let [raw (str/replace (second m) #"\\\"" "\"")
            pairs (re-seq #"\"([^\"]+)\"\s+\"([^\"]+)\"" raw)]
        (into {} (map (fn [[_ k v]] [k v]) pairs))))))

(defn path-overrides []
  (let [text (or (read-text (node-path.join root "manifest/repos.edn")) "")
        m (re-find #":manifest\.repos/path-overrides\s+\"(.*?)\",\s*:manifest" text)]
    (if-not m
      {}
      (let [raw (str/replace (second m) #"\\\"" "\"")]
        (into {} (for [[_ k v] (re-seq #"\"([^\"]+)\"\s+\"([^\"]+)\"" raw)]
                   [k v]))))))

(defn extra-projects-set []
  (let [text (or (read-text (node-path.join root "manifest/repos.edn")) "")
        m (re-find #":manifest\.repos/extra-projects\s+(\[)" text)]
    (if-not m
      #{}
      (let [start (second (re-find #":manifest\.repos/extra-projects\s+(\[)" text))
            ;; find vector by reading from key
            idx (.indexOf text ":manifest.repos/extra-projects")
            sub (subs text idx)
            open (.indexOf sub "[")
            depth (atom 0)
            end (atom nil)]
        (doseq [i (range open (count sub))
                :while (nil? @end)]
          (let [c (.charAt sub i)]
            (cond
              (= c \[) (swap! depth inc)
              (= c \]) (do (swap! depth dec)
                           (when (zero? @depth) (reset! end i))))))
        (if @end
          (->> (re-seq #"\"([^\"]+)\"" (subs sub open (inc @end)))
               (map second)
               set)
          #{})))))

;; ---------- path helpers ----------

(defn normalize-project
  "basename or orgs/org/name or org/name -> {:path :org :name}"
  [s west-path-index]
  (let [s (str/trim s)]
    (cond
      (str/starts-with? s "orgs/")
      (let [parts (str/split s #"/")]
        {:path s :org (nth parts 1) :name (nth parts 2)})

      (str/includes? s "/")
      (let [[org name] (str/split s #"/" 2)]
        {:path (str "orgs/" org "/" name) :org org :name name})

      :else
      (let [hits (filter (fn [[p _]] (str/ends-with? p (str "/" s))) west-path-index)]
        (if (= 1 (count hits))
          (let [p (ffirst hits)
                parts (str/split p #"/")]
            {:path p :org (nth parts 1) :name s})
          ;; default guess: search local orgs for unique basename
          (let [orgs-root (node-path.join root "orgs")
                found (atom [])]
            (when (is-dir? orgs-root)
              (doseq [org-name (.readdirSync node-fs orgs-root)
                      :let [org-path (node-path.join orgs-root org-name)
                            cand (node-path.join org-path s)]
                      :when (and (is-dir? org-path) (is-dir? cand))]
                (swap! found conj {:path (str "orgs/" org-name "/" s)
                                   :org org-name :name s})))
            (cond
              (= 1 (count @found)) (first @found)
              (seq @found) (die! 2 (str "ambiguous name " s ": " (pr-str (map :path @found))))
              :else
              ;; last resort: treat as kotoba-lang if GH check will validate
              {:path (str "orgs/kotoba-lang/" s) :org "kotoba-lang" :name s
               :guessed-org? true})))))))

(defn project-of-local-root
  "Resolve :local/root target project path under orgs/."
  [consumer-deps-path target-rel]
  (let [consumer-dir (node-path.dirname (node-path.join root consumer-deps-path))
        abs (node-path.resolve consumer-dir target-rel)
        abs-parts (vec (str/split abs #"[\\/]"))
        idx (.indexOf abs-parts "orgs")]
    (if (and (>= idx 0) (>= (count abs-parts) (+ idx 3)))
      (str/join "/" (subvec abs-parts idx (+ idx 3)))
      (let [rel (try (node-path.relative root abs) (catch :default _ abs))
            parts (str/split rel #"[\\/]")]
        (when (and (>= (count parts) 3) (= (first parts) "orgs"))
          (str/join "/" (take 3 parts)))))))

;; ---------- discover ----------

(defn run-orphan-edn []
  (let [{:keys [ok? out err]}
        (sh "nbb" (node-path.join root "scripts/west-orphan-audit.cljs") "--edn")]
    (when-not (or ok? (seq out))
      (die! 1 (str "west-orphan-audit failed: " err)))
    (try
      (edn/read-string out)
      (catch :default e
        (die! 1 (str "failed to parse orphan audit edn: " e "\n" (subs out 0 (min 500 (count out)))))))))

(defn local-west-projects [west-map]
  (->> west-map
       keys
       (filter #(is-dir? (node-path.join root %)))
       sort
       vec))

(defn gh-repo-exists? [org name]
  (let [{:keys [ok?]} (sh-ok "gh" "api" (str "repos/" org "/" name) "--jq" ".id")]
    ok?))

(defn gh-default-branch [org name]
  (let [{:keys [ok? out]} (sh-ok "gh" "api" (str "repos/" org "/" name)
                                 "--jq" ".default_branch")]
    (when ok? (not-empty out))))

(defn gh-head-sha [org name branch]
  (let [br (or branch "main")
        {:keys [ok? out]} (sh-ok "gh" "api"
                                 (str "repos/" org "/" name "/commits/" br)
                                 "--jq" ".sha")]
    (when ok? (not-empty out))))

(defn origin-url [path]
  (let [{:keys [ok? out]} (sh-ok "git" "-C" (node-path.join root path)
                                 "remote" "get-url" "origin")]
    (when ok? out)))

(defn local-head [path]
  (let [{:keys [ok? out]} (sh-ok "git" "-C" (node-path.join root path)
                                 "rev-parse" "HEAD")]
    (when ok? out)))

(defn dirty? [path]
  (let [{:keys [ok? out]} (sh-ok "git" "-C" (node-path.join root path)
                                 "status" "--porcelain")]
    (and ok? (not (str/blank? out)))))

(defn on-origin? [path sha]
  (let [{:keys [ok? out]} (sh-ok "git" "-C" (node-path.join root path)
                                 "branch" "-r" "--contains" sha)]
    (and ok? (not (str/blank? out)))))

(defn remote-url-for [org name remotes]
  (when-let [base (get remotes org)]
    (if (str/ends-with? base "/")
      (str base name ".git")
      (str base "/" name ".git"))))

(defn parse-org-name-from-url [url]
  (when url
    (when-let [m (re-find #"(?:github\.com[:/])([^/]+)/([^/.]+)" url)]
      [(nth m 1) (nth m 2)])))

;; ---------- managed set ----------

(defn managed-from-blocking [orphan-report overrides]
  (let [rows (:local-root-broken orphan-report)
        by-path (atom {})]
    (doseq [r rows]
      (let [proj (:project r)
            path (cond
                   (str/starts-with? proj "orgs/") proj
                   (str/includes? proj "/") (str "orgs/" proj)
                   :else nil)]
        (when path
          (let [parts (str/split path #"/")
                org (nth parts 1 nil)
                name (nth parts 2 nil)
                entry {:path path :org org :name name
                       :reason :local-root-broken
                       :consumers (:consumers r)
                       :dir-exists? (:dir-exists? r)
                       :in-west? (:in-west? r)
                       :override-target (get overrides path)}]
            (swap! by-path update path
                   (fn [prev]
                     (if prev
                       (update prev :consumers
                               #(vec (distinct (concat (or % []) (:consumers entry)))))
                       entry)))))))
    (->> (vals @by-path)
         (sort-by :path)
         vec)))

(defn managed-from-names [names west-map]
  (mapv #(normalize-project % west-map) names))

(defn managed-from-local-west [west-map]
  (mapv (fn [path]
          (let [parts (str/split path #"/")]
            {:path path :org (nth parts 1) :name (nth parts 2)
             :reason :local-west}))
        (local-west-projects west-map)))

(defn build-managed [opts orphan west-map overrides]
  (case (:scope opts)
    "blocking" (managed-from-blocking orphan overrides)
    "managed" (managed-from-local-west west-map)
    "names" (managed-from-names (:names opts) west-map)))

;; ---------- plan actions ----------

(defn successor-path
  "path-override または既知の -clj 改名先。登録禁止の旧 path 用。"
  [path name west-map overrides]
  (or (get overrides path)
      (when (and name (str/ends-with? name "-clj"))
        (let [base (subs name 0 (- (count name) 4))
              ;; common migration: com-junkawasaki/foo-clj -> kotoba-lang/foo
              candidates [(str "orgs/kotoba-lang/" base)
                          (str "orgs/kotoba-lang/" name)
                          (str "orgs/gftdcojp/" base)]]
          (first (filter #(contains? west-map %) candidates))))))

(defn plan-for-project [p west-map remotes extras overrides]
  (let [path (:path p)
        org (:org p)
        name (:name p)
        west (or (get west-map path)
                 ;; also match by name if path differs
                 (some (fn [[_ e]] (when (= (:name e) name) e)) west-map))
        in-west? (boolean (get west-map path))
        in-extra? (contains? extras path)
        dir? (is-dir? (node-path.join root path))
        override (successor-path path name west-map overrides)
        actions (atom [])
        reports (atom [])]
    (cond
      (and override (not= override path))
      (swap! reports conj {:op :report-retarget
                           :path path
                           :maps-to override
                           :note "successor exists — do not re-register old path; retarget :local/root consumers"})

      (nil? org)
      (swap! reports conj {:op :report-skip :path path :note "cannot parse org/name"})

      :else
      (let [url (or (when dir? (origin-url path))
                    (remote-url-for org name remotes))
            [uorg uname] (or (parse-org-name-from-url url) [org name])
            gh? (gh-repo-exists? uorg uname)
            west-name (or (:name west) name)]
        (when-not gh?
          (swap! reports conj {:op :report-skip
                               :path path
                               :note (str "GitHub repo missing: " uorg "/" uname
                                          " (use new-project-scaffold to create)")}))
        (when (and gh? (not dir?))
          (swap! actions conj {:op :clone :path path
                               :url (or url (remote-url-for uorg uname remotes))
                               :org uorg :name uname}))
        ;; register only when path not in west at all (missing from west map by path)
        (when (and gh? (not in-west?))
          (swap! actions conj {:op :register :path path :name west-name :org org}))
        (when (and gh? dir? in-west?)
          (let [head (local-head path)
                pin (:revision west)
                dirty (dirty? path)]
            (when dirty
              (swap! reports conj {:op :report-skip :path path
                                   :note "dirty working tree — skip fetch/ff/pin"}))
            (when (and (not dirty) head)
              (swap! actions conj {:op :fetch-ff :path path :org org :name west-name})
              (when (and pin (not= head pin))
                (swap! actions conj {:op :pin-entry :name west-name :path path
                                     :from pin :note "pin may advance after ff"})))))
        ;; west has pin but local missing — clone already queued; after clone pin is fine
        (when (and gh? (not dir?) in-west?)
          (swap! reports conj {:op :report-info :path path
                               :note "in west, missing local — clone only (no re-register)"}))))
    {:project p
     :actions @actions
     :reports @reports}))

(defn build-plan [opts]
  (let [orphan (run-orphan-edn)
        west-map (west-by-path)
        remotes (remotes-map)
        extras (extra-projects-set)
        overrides (path-overrides)
        managed (build-managed opts orphan west-map overrides)
        plans (mapv #(plan-for-project % west-map remotes extras overrides) managed)
        actions (->> plans
                     (mapcat :actions)
                     (reduce (fn [m a]
                               (assoc m [(:op a) (:path a) (:name a)] a))
                             {})
                     vals
                     (sort-by (fn [a]
                                [(case (:op a)
                                   :clone 0 :register 1 :fetch-ff 2 :pin-entry 3 9)
                                 (str (:path a))]))
                     vec)
        reports (vec (mapcat :reports plans))]
    {:scope (:scope opts)
     :managed-count (count managed)
     :managed managed
     :actions actions
     :reports reports
     :orphan-counts {:local-root-broken (count (:local-root-broken orphan))
                     :true-orphan-git (count (get-in orphan [:unregistered :true-orphan-git]))}
     :no-pin-advance? (:no-pin-advance? opts)}))

;; ---------- apply ----------

(defn ensure-extra-project! [path]
  (let [f (node-path.join root "manifest/repos.edn")
        text (slurp f)
        token (str "\"" path "\"")]
    (if (str/includes? text token)
      :already
      (let [key-idx (.indexOf text ":manifest.repos/extra-projects")
            _ (when (neg? key-idx) (die! 1 "extra-projects key not found in repos.edn"))
            sub (subs text key-idx)
            open (+ key-idx (.indexOf sub "["))
            depth (atom 0)
            end (atom nil)]
        (doseq [i (range open (count text))
                :while (nil? @end)]
          (let [c (.charAt text i)]
            (cond
              (= c \[) (swap! depth inc)
              (= c \]) (do (swap! depth dec)
                           (when (zero? @depth) (reset! end i))))))
        (when-not @end (die! 1 "could not find end of extra-projects vector"))
        ;; insert before closing ] with comma if vector non-empty
        (let [before (subs text open @end)
              nonempty? (boolean (re-find #"\"" before))
              insertion (if nonempty?
                          (str " " token)
                          token)
              new-text (str (subs text 0 @end) insertion (subs text @end))]
          (spit f new-text)
          :inserted)))))

(defn do-clone! [action]
  (let [path (:path action)
        url (:url action)
        abs (node-path.join root path)
        parent (node-path.dirname abs)]
    (when-not url (die! 1 (str "clone missing url for " path)))
    (when-not (exists? parent)
      (.mkdirSync node-fs parent #js {:recursive true}))
    (println (str "  clone " url " -> " path))
    (let [{:keys [ok? err out]} (sh-ok "git" "clone" "--depth" "1" url abs)]
      (if ok?
        {:ok? true :op :clone :path path}
        {:ok? false :op :clone :path path :err (or err out)}))))

(defn do-register! [action]
  (let [path (:path action)
        name (:name action)]
    (println (str "  register " path " (--entry " name ")"))
    (let [ins (ensure-extra-project! path)]
      (println (str "    extra-projects: " ins))
      (let [{:keys [ok? err out]}
            (sh-ok "nbb" (node-path.join root "scripts/gen-west-manifest.cljs")
                   "--entry" name)]
        (if ok?
          {:ok? true :op :register :path path :name name :out out}
          {:ok? false :op :register :path path :err (str err "\n" out)})))))

(defn do-fetch-ff! [action]
  (let [path (:path action)
        abs (node-path.join root path)]
    (if (dirty? path)
      (do (println (str "  skip fetch-ff (dirty): " path))
          {:ok? true :op :fetch-ff :path path :skipped true :reason :dirty})
      (do
        (println (str "  fetch-ff " path))
        (let [f (sh-ok "git" "-C" abs "fetch" "--depth" "1" "origin")]
          (if-not (:ok? f)
            {:ok? false :op :fetch-ff :path path :err (:err f)}
            (let [br (or (let [r (sh-ok "git" "-C" abs "rev-parse" "--abbrev-ref" "origin/HEAD")]
                           (when (:ok? r)
                             (second (re-find #"origin/(\S+)" (:out r)))))
                         "main")
                  m (sh-ok "git" "-C" abs "merge" "--ff-only" (str "origin/" br))]
              (if (:ok? m)
                {:ok? true :op :fetch-ff :path path :branch br}
                {:ok? true :op :fetch-ff :path path :branch br
                 :note (str (:err m) " " (:out m)) :ff? false}))))))))

(defn do-pin-entry! [action]
  (let [path (:path action)
        name (:name action)
        head (local-head path)]
    (cond
      (dirty? path)
      (do (println (str "  skip pin (dirty): " path))
          {:ok? true :op :pin-entry :skipped true :reason :dirty})

      (not head)
      {:ok? false :op :pin-entry :err "no HEAD"}

      (not (on-origin? path head))
      (do (println (str "  skip pin (HEAD not on any origin ref — unpushed?): " path " " head))
          {:ok? true :op :pin-entry :skipped true :reason :unpushed-head :head head})

      :else
      (do
        (println (str "  pin-entry " name " @ " (subs head 0 12)))
        (let [{:keys [ok? err out]}
              (sh-ok "nbb" (node-path.join root "scripts/gen-west-manifest.cljs")
                     "--entry" name)]
          (if ok?
            {:ok? true :op :pin-entry :name name :head head}
            {:ok? false :op :pin-entry :err (str err "\n" out)}))))))

(defn apply-actions! [plan opts]
  (let [actions (:actions plan)
        ;; order: clone, register, fetch-ff, pin-entry
        order {:clone 0 :register 1 :fetch-ff 2 :pin-entry 3}
        sorted (sort-by #(get order (:op %) 9) actions)
        results (atom [])]
    (doseq [a sorted]
      (let [res (case (:op a)
                  :clone (do-clone! a)
                  :register (do-register! a)
                  :fetch-ff (if (:no-pin-advance? opts)
                              ;; still allow fetch-ff for local freshness unless we want skip
                              (do-fetch-ff! a)
                              (do-fetch-ff! a))
                  :pin-entry (if (:no-pin-advance? opts)
                               (do (println (str "  skip pin (--no-pin-advance): " (:name a)))
                                   {:ok? true :op :pin-entry :skipped true})
                               (do-pin-entry! a))
                  {:ok? false :err (str "unknown op " (:op a))})]
        (swap! results conj res)
        (when-not (:ok? res)
          (println (str "  FAIL " (pr-str res))))))
    @results))

;; ---------- verify ----------

(defn verify! [opts]
  (println "=== verify: west-orphan-audit --blocking ===")
  (let [o (sh-ok "nbb" (node-path.join root "scripts/west-orphan-audit.cljs") "--blocking")
        ;; always print
        _ (when (seq (:out o)) (println (:out o)))
        _ (when (seq (:err o)) (binding [*out* *err*] (println (:err o))))
        names (cond
                (seq (:names opts)) (:names opts)
                (= (:scope opts) "blocking") []
                :else [])]
    (when (seq names)
      (println (str "=== verify: verify-west-pins --only " (str/join "," names) " ==="))
      (let [v (apply sh-ok "nbb" (node-path.join root "scripts/verify-west-pins.cljs")
                     "--only" (str/join "," names))]
        (when (seq (:out v)) (println (:out v)))
        (when (seq (:err v)) (binding [*out* *err*] (println (:err v))))
        (when-not (:ok? v)
          (println "pin verify failed")
          (.exit js/process 1))))
    (if (:ok? o)
      (do (println "verify OK (no blocking local-root edges)")
          (.exit js/process 0))
      (do (println "verify FAIL (blocking local-root edges remain)")
          (.exit js/process 1)))))

;; ---------- print plan ----------

(defn print-plan [plan]
  (println "=== west-triple-sync PLAN ===")
  (println (str "scope=" (:scope plan)
                " managed=" (:managed-count plan)
                " actions=" (count (:actions plan))
                " reports=" (count (:reports plan))))
  (println (str "orphan snapshot: "
                "local-root-broken=" (get-in plan [:orphan-counts :local-root-broken])
                " true-orphan-git=" (get-in plan [:orphan-counts :true-orphan-git])))
  (println)
  (println "## managed")
  (doseq [p (:managed plan)]
    (println (str "  " (:path p)
                  (when (:reason p) (str "  (" (name (:reason p)) ")"))
                  (when (:override-target p) (str "  OVERRIDE→" (:override-target p))))))
  (println)
  (println "## actions (would run on apply)")
  (if (empty? (:actions plan))
    (println "  (none)")
    (doseq [a (:actions plan)]
      (println (str "  " (pr-str a)))))
  (println)
  (println "## reports (manual / skip)")
  (if (empty? (:reports plan))
    (println "  (none)")
    (doseq [r (:reports plan)]
      (println (str "  " (pr-str r)))))
  (println)
  (println "Next: nbb scripts/west-triple-sync.cljs apply --scope"
           (:scope plan)
           (when (seq (keep :name (:managed plan)))
             (str "--names " (str/join "," (distinct (map :name (:managed plan))))))))

;; ---------- main ----------

(defn -main [& args]
  (let [opts (parse-args args)]
    (case (:cmd opts)
      "verify" (verify! opts)
      "plan" (print-plan (build-plan opts))
      "apply"
      (let [plan (build-plan opts)]
        (print-plan plan)
        (println)
        (println "=== APPLY ===")
        (let [results (apply-actions! plan opts)
              fails (filter (complement :ok?) results)]
          (println (str "done: " (count results) " steps, "
                        (count fails) " failures"))
          (when (seq fails)
            (doseq [f fails] (println (str "  FAIL " (pr-str f))))
            (.exit js/process 1))
          ;; re-verify blocking always after apply
          (verify! (assoc opts :cmd "verify")))))))

(apply -main *command-line-args*)
