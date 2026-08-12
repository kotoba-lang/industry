#!/usr/bin/env nbb
;; survey-tracked-deploy-artifacts.cljs
;;
;; ADR-2608134600 が club-shinshi-app で見つけた罠を fleet 全体で数える。
;;
;; 罠の形: wrangler の `main` / `assets.directory` / `pages_build_output_dir` が
;; **git に tracked な build 成果物**を指していると、build を挟まない `wrangler deploy`
;; が「commit されている古い物」を exit 0 で出荷する。gitignore された build dir は
;; 免疫がある（存在しないので wrangler が exit 1 で落ちる）。
;;
;; 直接形と間接形の 2 つを見る:
;;   direct   — artefact path 自体が tracked かつ generated dir 配下
;;   indirect — main は手書き source（tracked で当然）だが、その中の relative import が
;;              tracked な generated dir を読む（club-shinshi-app がこれだった）
;;
;; 既定では **local の origin/<default> tree** を読む（working tree ではない）。
;; --fetch を付けると対象 repo だけ `git fetch origin` してから読む。
;;
;;   nbb scripts/survey-tracked-deploy-artifacts.cljs --list <file-of-config-paths> --out <edn>
;;   nbb scripts/survey-tracked-deploy-artifacts.cljs --scan orgs --out <edn>
;;   nbb scripts/survey-tracked-deploy-artifacts.cljs --list ... --only network-awai/club-shinshi-app

(ns survey-tracked-deploy-artifacts
  (:require [clojure.string :as str]
            [cljs.pprint]
            ["fs" :as fs]
            ["path" :as npath]
            ["child_process" :as cp]))

;; ---------------------------------------------------------------- shell

(defn sh
  "Run argv. Never throws. Returns {:ok bool :out str :err str}."
  [argv & [opts]]
  (try
    {:ok true
     :out (str (cp/execFileSync (first argv) (clj->js (vec (rest argv)))
                                (clj->js (merge {:encoding "utf8"
                                                 :maxBuffer (* 128 1024 1024)
                                                 :stdio #js ["ignore" "pipe" "pipe"]}
                                                opts))))
     :err ""}
    (catch :default e
      {:ok false
       :out (str (or (some-> (.-stdout e)) ""))
       :err (str (or (some-> (.-stderr e)) (.-message e)))})))

(defn git [repo & args]
  (sh (into ["git" "-C" repo] args)))

;; ---------------------------------------------------------------- jsonc

(defn strip-jsonc
  "Remove // and /* */ comments and trailing commas, string-aware."
  [s]
  (let [n (count s)
        sb (array)]
    (loop [i 0 in-str? false esc? false]
      (when (< i n)
        (let [c (nth s i)
              c2 (when (< (inc i) n) (nth s (inc i)))]
          (cond
            in-str?
            (do (.push sb c)
                (cond esc?      (recur (inc i) true false)
                      (= c \\)  (recur (inc i) true true)
                      (= c \")  (recur (inc i) false false)
                      :else     (recur (inc i) true false)))

            (= c \") (do (.push sb c) (recur (inc i) true false))

            (and (= c \/) (= c2 \/))
            (recur (loop [k i] (if (or (>= k n) (= (nth s k) \newline)) k (recur (inc k))))
                   false false)

            (and (= c \/) (= c2 \*))
            (recur (loop [k (+ i 2)]
                     (cond (>= k n) k
                           (and (= (nth s k) \*)
                                (= (when (< (inc k) n) (nth s (inc k))) \/)) (+ k 2)
                           :else (recur (inc k))))
                   false false)

            :else (do (.push sb c) (recur (inc i) false false))))))
    ;; trailing commas: safe enough post-hoc, and we always keep a regex fallback
    (-> (.join sb "")
        (str/replace #",(\s*[}\]])" "$1"))))

(defn parse-jsonc [s]
  (try (js->clj (js/JSON.parse (strip-jsonc s)) :keywordize-keys false)
       (catch :default _ nil)))

(defn toml-get [s k]
  (some-> (re-find (re-pattern (str "(?m)^\\s*" k "\\s*=\\s*[\"']([^\"']+)[\"']")) s) second))

(defn toml-section-get
  "Value of `k` inside [section] (or any table whose header ends with .section)."
  [s section k]
  (let [lines (str/split-lines s)]
    (loop [ls lines cur nil]
      (if (empty? ls)
        nil
        (let [l (first ls)
              h (second (re-find #"^\s*\[+\s*([^\]]+?)\s*\]+\s*$" l))
              cur (if h h cur)
              m (when (and cur (= cur section))
                  (second (re-find (re-pattern (str "^\\s*" k "\\s*=\\s*[\"']([^\"']+)[\"']")) l)))]
          (or m (recur (rest ls) cur)))))))

(defn regex-fallback
  "Last-resort field extraction when the config will not parse."
  [s]
  (cond-> {}
    (re-find #"\"main\"\s*:" s)
    (assoc :main (second (re-find #"\"main\"\s*:\s*\"([^\"]+)\"" s)))
    (re-find #"\"directory\"\s*:" s)
    (assoc :assets-dir (second (re-find #"\"directory\"\s*:\s*\"([^\"]+)\"" s)))
    (re-find #"\"pages_build_output_dir\"\s*:" s)
    (assoc :pages-dir (second (re-find #"\"pages_build_output_dir\"\s*:\s*\"([^\"]+)\"" s)))))

(defn extract-fields
  "-> {:main s :assets-dir s :pages-dir s :site-bucket s :parsed? bool}"
  [cfg-path content]
  (if (str/ends-with? cfg-path ".toml")
    {:parsed? true
     :main (toml-get content "main")
     :pages-dir (toml-get content "pages_build_output_dir")
     :assets-dir (or (toml-section-get content "assets" "directory")
                     (toml-get content "directory"))
     :site-bucket (or (toml-section-get content "site" "bucket")
                      (toml-get content "bucket"))}
    (if-let [m (parse-jsonc content)]
      (let [assets (get m "assets")]
        {:parsed? true
         :main (get m "main")
         :pages-dir (get m "pages_build_output_dir")
         :assets-dir (cond (string? assets) assets
                           (map? assets) (get assets "directory")
                           :else nil)
         :site-bucket (get-in m ["site" "bucket"])})
      (assoc (regex-fallback content) :parsed? false))))

;; ---------------------------------------------------------------- paths

(defn norm-rel
  "Resolve a wrangler field (relative to the config's dir) to a repo-relative path."
  [cfg-dir v]
  (when (and v (string? v) (seq v))
    (let [p (npath/normalize (npath/join cfg-dir v))
          p (str/replace p #"^\./" "")
          p (str/replace p #"/$" "")]
      (if (= p ".") "" p))))

(def generated-seg
  #{"dist" "build" "out" "output" ".output" ".svelte-kit" ".next" ".nuxt"
    ".vercel" "_site" "target" "release" "public_html" ".wrangler" "_worker.js"
    "compiled" "generated" "gen" "bundle" "bundles"})

(defn generated-path?
  "Does any segment look like a build-output directory?"
  [p]
  (boolean (some generated-seg (str/split (or p "") #"/"))))

;; ---------------------------------------------------------------- repo state

(defn remotes
  "west names the remote after the org, not `origin` (ADR-2608134400). Never
   assume `origin` exists — enumerate, preferring origin when it does."
  [repo]
  (let [r (git repo "remote")]
    (if-not (:ok r)
      []
      (let [rs (remove str/blank? (str/split-lines (:out r)))]
        (concat (filter #(= % "origin") rs) (remove #(= % "origin") rs))))))

(defn default-ref
  "The upstream default branch ref of this repo, whatever the remote is called."
  [repo]
  (or (some (fn [rm]
              (let [h (git repo "symbolic-ref" "--quiet" (str "refs/remotes/" rm "/HEAD"))]
                (when (:ok h) (str/trim (:out h)))))
            (remotes repo))
      (some (fn [rm]
              (some (fn [b]
                      (let [ref (str "refs/remotes/" rm "/" b)]
                        (when (:ok (git repo "rev-parse" "--verify" "--quiet" ref)) ref)))
                    ["main" "master"]))
            (remotes repo))))

(defn tree-files
  "All paths in <ref>. Returns a set, or nil on failure."
  [repo ref]
  (let [r (git repo "ls-tree" "-r" "--name-only" "-z" ref)]
    (when (:ok r)
      (into #{} (remove str/blank?) (str/split (:out r) #"\u0000")))))

(defn under
  "Files in `files` at exactly `p` or under `p/`."
  [files p]
  (cond
    (str/blank? p) #{}
    :else (let [pref (str p "/")]
            (into #{} (filter #(or (= % p) (str/starts-with? % pref))) files))))

(defn blob [repo ref p]
  (let [r (git repo "show" (str ref ":" p))]
    (when (:ok r) (:out r))))

;; ---------------------------------------------------------------- indirect

(def import-re #"(?:from|require\(|import\(|import)\s*[\"']([\.][^\"']+)[\"']")

(defn indirect-hits
  "main is hand-written source: does it read a tracked generated path?"
  [files main-path src]
  (when (and src (< (count src) 200000))
    (let [dir (npath/dirname main-path)]
      (->> (re-seq import-re src)
           (map second)
           distinct
           (keep (fn [spec]
                   (let [base (str/replace (npath/normalize (npath/join dir spec)) #"^\./" "")
                         cands (concat [base]
                                       (map #(str base %) [".js" ".mjs" ".cjs" ".ts"])
                                       (map #(str base "/index" %) [".js" ".mjs" ".cjs" ".ts"]))
                         hit (first (filter files cands))]
                     (when (and hit (generated-path? hit))
                       {:spec spec :resolves-to hit}))))
           vec))))

;; ---------------------------------------------------------------- staleness

(defn last-commit [repo ref p]
  (let [r (git repo "log" "-1" "--format=%H %cs" ref "--" p)]
    (when (and (:ok r) (seq (str/trim (:out r))))
      (let [[h d] (str/split (str/trim (:out r)) #"\s+")]
        {:sha h :date d}))))

(defn staleness
  "How far behind its own sources is the committed artefact?

   A commit count is only a pointer, never proof — a deterministic build can
   differ by identifier assignment alone. Report the count and the changed
   source files, and let the ADR say what was actually rebuilt."
  [repo ref cfg-dir art-paths]
  (let [scope (if (str/blank? cfg-dir) "." cfg-dir)
        last-touched (->> art-paths
                          (keep #(last-commit repo ref %))
                          (sort-by :date)
                          first)]
    (when last-touched
      (let [ps (into [scope] (map #(str ":(exclude)" %)) art-paths)
            cnt (let [r (apply git repo (concat ["rev-list" "--count"
                                                 (str (:sha last-touched) ".." ref) "--"] ps))]
                  (when (:ok r) (js/parseInt (str/trim (:out r)) 10)))
            files (let [r (apply git repo (concat ["diff" "--name-only"
                                                   (str (:sha last-touched) ".." ref) "--"] ps))]
                    (when (:ok r) (remove str/blank? (str/split-lines (:out r)))))]
        {:artefact-last-commit last-touched
         :head (str/trim (:out (git repo "rev-parse" "--short" ref)))
         :source-commits-since cnt
         :source-files-changed (count files)
         :changed (vec (take 25 files))}))))

;; ---------------------------------------------------------------- blob shape

(defn shape-of-blob
  "Is this tracked file actually a build artefact? Ask the bytes."
  [repo ref p]
  (when-let [src (blob repo ref p)]
    (let [lines (str/split-lines src)
          nlines (max 1 (count lines))
          longest (reduce max 0 (map count lines))]
      {:bytes (count src)
       :lines (count lines)
       :longest-line longest
       ;; Only structural signals decide the verdict. A file that merely
       ;; MENTIONS shadow-cljs in a comment is source, and treating that as
       ;; evidence produced 8 false positives when measured.
       :minified? (boolean (or (> longest 1500)
                               (and (> (count src) 200000)
                                    (> (/ (count src) nlines) 150))))
       :generated-banner?
       (boolean (re-find #"(?i)\b(do not edit|auto-?generated|generated by)\b"
                         (subs src 0 (min 4000 (count src)))))})))

;; ---------------------------------------------------------------- per config

(defn survey-config [repo-root repo-name cfg-abs ref files]
  (let [rel (str/replace (npath/relative repo-root cfg-abs) #"^\./" "")
        content (or (blob repo-root ref rel)
                    (try (str (fs/readFileSync cfg-abs "utf8")) (catch :default _ nil)))]
    (if-not content
      {:repo repo-name :config rel :verdict :unreadable}
      (let [f (extract-fields rel content)
            cfg-dir (npath/dirname rel)
            cfg-dir (if (= cfg-dir ".") "" cfg-dir)
            targets (->> [[:main (:main f)] [:assets-dir (:assets-dir f)]
                          [:pages-dir (:pages-dir f)] [:site-bucket (:site-bucket f)]]
                         (keep (fn [[k v]]
                                 (when-let [p (norm-rel cfg-dir v)]
                                   {:field k :raw v :path p}))))
            targets (mapv (fn [{:keys [path] :as t}]
                            (let [hits (under files path)]
                              (assoc t
                                     :tracked-files (count hits)
                                     :tracked? (pos? (count hits))
                                     :generated-dir? (generated-path? path))))
                          targets)
            main-t (first (filter #(= :main (:field %)) targets))
            ;; A tracked `main` outside a build-named dir is usually hand-written
            ;; source — but not always. `js/auth-worker.js` is a shadow-cljs
            ;; release bundle in a dir named nothing like a build dir. Ask the
            ;; file, not the path.
            main-shape (when (and main-t (:tracked? main-t) (not (:generated-dir? main-t)))
                         (shape-of-blob repo-root ref (:path main-t)))
            minified-main (when (:minified? main-shape)
                            [(merge main-t (select-keys main-shape
                                                        [:bytes :lines :longest-line]))])
            indirect (when (and main-t (:tracked? main-t) (not (:generated-dir? main-t))
                                (not (:minified? main-shape)))
                       (indirect-hits files (:path main-t) (blob repo-root ref (:path main-t))))
            direct (into (filterv #(and (:tracked? %) (:generated-dir? %)) targets)
                         (or minified-main []))
            has-artifact-field? (boolean (seq targets))
            verdict (cond (seq direct) :trap-direct
                          (seq indirect) :trap-indirect
                          (not has-artifact-field?) :no-artifact-field
                          :else :clean)
            trap? (#{:trap-direct :trap-indirect} verdict)
            art-paths (mapv :path (concat direct (map #(hash-map :path (:resolves-to %)) indirect)))]
        (cond-> {:repo repo-name
                 :config rel
                 :ref ref
                 :parsed? (:parsed? f)
                 :targets targets
                 :direct direct
                 :indirect (vec indirect)
                 :minified-main (boolean (seq minified-main))
                 :verdict verdict}
          trap? (assoc :staleness (staleness repo-root ref cfg-dir art-paths)))))))

;; ---------------------------------------------------------------- second pass
;;
;; The generated-dir heuristic only sees artefacts that live under a dir NAMED
;; like a build output. A bundle committed as `worker.js` at the repo root would
;; read as hand-written source. So: re-measure every tracked `main` that the
;; first pass called clean, and ask the file itself whether it is minified.

(defn audit-mains
  "Given survey records, re-measure tracked non-generated-dir `main` files."
  [records root-of]
  (->> records
       (keep (fn [r]
               (when-let [m (first (filter #(and (= :main (:field %)) (:tracked? %)
                                                 (not (:generated-dir? %)))
                                           (:targets r)))]
                 (when-let [root (root-of (:repo r))]
                   (let [sh (shape-of-blob root (:ref r) (:path m))]
                     (when (:minified? sh)
                       (merge {:repo (:repo r) :config (:config r) :path (:path m)} sh)))))))
       vec))

;; ---------------------------------------------------------------- driver

(defn repo-root-of [cfg-abs]
  (loop [d (npath/dirname cfg-abs)]
    (cond
      (or (= d "/") (str/blank? d)) nil
      (fs/existsSync (npath/join d ".git")) d
      :else (recur (npath/dirname d)))))

(defn -main [& argv]
  (let [args (vec argv)
        opt (fn [k] (let [i (.indexOf args k)] (when (>= i 0) (nth args (inc i) nil))))
        flag (fn [k] (>= (.indexOf args k) 0))
        report-in (opt "--report")
        _ (when report-in
            (let [recs (cljs.reader/read-string (str (fs/readFileSync report-in "utf8")))
                  tracked-any (filter (fn [r] (some :tracked? (:targets r))) recs)
                  by-field (fn [f] (count (filter (fn [r] (some #(and (= f (:field %)) (:tracked? %))
                                                                (:targets r))) recs)))]
              (println "configs               :" (count recs))
              (println "repos                 :" (count (distinct (map :repo recs))))
              (println "verdicts              :" (pr-str (frequencies (map :verdict recs))))
              (println "ref-source            :" (pr-str (frequencies (map :ref-source recs))))
              (println "any tracked target    :" (count tracked-any))
              (println "  tracked main        :" (by-field :main))
              (println "  tracked assets-dir  :" (by-field :assets-dir))
              (println "  tracked pages-dir   :" (by-field :pages-dir))
              (println "  tracked site-bucket :" (by-field :site-bucket))
              (println "unparsed configs      :" (count (remove :parsed? recs)))
              (println "\n-- tracked assets.directory (hand-authored public/ is fine; a built one is not) --")
              (doseq [r recs
                      t (:targets r)
                      :when (and (#{:assets-dir :pages-dir} (:field t)) (:tracked? t))]
                (println " " (:repo r) (:config r) (name (:field t)) "->" (:path t)
                         (str "files=" (:tracked-files t))))
              (js/process.exit 0)))
        audit-in (opt "--audit-mains")
        _ (when audit-in
            (let [recs (cljs.reader/read-string (str (fs/readFileSync audit-in "utf8")))
                  roots (into {} (for [f (->> (str (fs/readFileSync (opt "--list") "utf8"))
                                              str/split-lines (remove str/blank?))
                                       :let [root (repo-root-of (npath/resolve f))]
                                       :when root]
                                   [(str/join "/" (take-last 2 (str/split root #"/"))) root]))
                  hits (audit-mains recs roots)]
              (doseq [h hits] (println (:repo h) (:path h)
                                       (str "bytes=" (:bytes h))
                                       (str "longest-line=" (:longest-line h))))
              (println "second-pass suspicious mains:" (count hits))
              (js/process.exit 0)))
        list-file (opt "--list")
        scan-dir (opt "--scan")
        out (opt "--out")
        only (opt "--only")
        fetch? (flag "--fetch")
        cfgs (cond
               list-file (->> (str (fs/readFileSync list-file "utf8"))
                              str/split-lines (remove str/blank?) vec)
               scan-dir (->> (:out (sh ["find" scan-dir "-maxdepth" "7"
                                        "(" "-name" "node_modules" "-o" "-name" ".git"
                                        "-o" "-name" ".cpcache" "-o" "-name" "target" ")"
                                        "-prune" "-o" "-type" "f"
                                        "(" "-name" "wrangler.jsonc" "-o" "-name" "wrangler.toml"
                                        "-o" "-name" "wrangler.json" ")" "-print"]))
                              str/split-lines (remove str/blank?) vec)
               :else (do (println "need --list or --scan") (js/process.exit 2)))
        cfgs (map #(npath/resolve %) cfgs)
        by-repo (group-by repo-root-of cfgs)
        by-repo (if only
                  (into {} (filter (fn [[k _]] (and k (str/includes? k only))) by-repo))
                  by-repo)
        total (count by-repo)]
    (loop [rs (seq (sort-by first by-repo)) i 0 acc []]
      (if-not rs
        (let [edn (with-out-str (cljs.pprint/pprint acc))]
          (when out (fs/writeFileSync out edn))
          (let [freq (frequencies (map :verdict acc))]
            (println "\n== verdicts ==")
            (doseq [[k v] (sort-by (comp - val) freq)] (println (str "  " k " " v)))
            (println "configs:" (count acc) " repos:" total)
            (doseq [r (filter #(#{:trap-direct :trap-indirect} (:verdict %)) acc)]
              (println "TRAP" (:verdict r) (:repo r) (:config r)))))
        (let [[root cs] (first rs)]
          (if-not root
            (recur (next rs) (inc i) acc)
            (let [repo-name (str/join "/" (take-last 2 (str/split root #"/")))
                  ;; NEVER pass --depth here: a worktree shares .git/shallow with
                  ;; its parent, and a shallow fetch has corrupted a sibling's
                  ;; object store in this workspace before.
                  _ (when fetch?
                      (doseq [rm (take 1 (remotes root))]
                        (git root "fetch" "--no-tags" rm
                             (str "+refs/heads/*:refs/remotes/" rm "/*"))))
                  upstream (or (opt "--ref") (default-ref root))
                  ;; no remote ref locally: fall back to local HEAD rather than
                  ;; dropping the repo from the survey, and say so in the record.
                  ref (or upstream
                          (when (:ok (git root "rev-parse" "--verify" "--quiet" "HEAD")) "HEAD"))
                  files (when ref (tree-files root ref))]
              (when (zero? (mod i 25))
                (.write js/process.stderr (str "[" i "/" total "] " repo-name "\n")))
              (recur (next rs) (inc i)
                     (into acc
                           (if (nil? files)
                             [{:repo repo-name :verdict :no-ref :configs (count cs)}]
                             (mapv #(assoc (survey-config root repo-name % ref files)
                                           :ref-source (if upstream :remote :local-head))
                                   cs)))))))))))

(apply -main (drop 3 (vec (js/Array.from js/process.argv))))
