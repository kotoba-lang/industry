#!/usr/bin/env nbb
;; scripts/fleet-sync-probe.cljs — measure the fleet's synchronisation state and
;; emit it as the seed EDN that kotoba-lang/loop-system-dynamics'
;; `fleet-sync-topology` model consumes (ADR-2608040100).
;;
;; **なぜ script なのか。** ADR-2608040100 の初回 snapshot は手で叩いた ad hoc probe
;; だった。あの ADR 自身が「支配ループの driving quantity に standing check が無い」
;; ことを問題として数えているのに、その測定自体が one-off だったのでは同じ穴に落ちる。
;; **一度しか走らない測定は standing check ではない。** この script が存在することで、
;; 12 個の数字は「誰かが見に行った日」ではなく「いつでも再現できる観測」になる。
;;
;; 測るもの（すべて GitHub API 不要・ローカル git のみ。ネットワークに依存しない）:
;;   - west.yml の entry 数 / pin の形 / rad-rid coverage
;;   - 実体化 checkout 数、HEAD == pin か（ahead / behind / **関係を判定できない**）
;;   - pin がローカル既知の origin より遅れている数（比較可能な母数つき）
;;   - 未コミット WIP（tracked / untracked を分けて）、stash、未 push commit
;;   - FETCH_HEAD の age 分布（= sync loop の観測周期そのもの）
;;   - radicle canonical が git 面のどこと一致するか（node 停止でも測れる）
;;   - fleet-db と west.yml の drift（`fleet reconcile --check` と同じ問い）
;;
;; usage:
;;   nbb scripts/fleet-sync-probe.cljs                    ;; 人間向けサマリを stdout
;;   nbb scripts/fleet-sync-probe.cljs --edn <path>       ;; seed EDN を書く
;;   nbb scripts/fleet-sync-probe.cljs --rad-sample 500   ;; radicle 抽出数（既定 500、0 で skip）
;;   nbb scripts/fleet-sync-probe.cljs --jobs 8
;;
;; 出さない数値は書かない: 比較できなかった repo は分母から外し、その分母を一緒に出す。
;; ここが崩れると「pin は 77 件しか遅れていない」が「3,600 件中 77 件」に読めてしまう。

(ns fleet-sync-probe
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.string :as str]))

(def ^:private root (or (.-FLEET_ROOT js/process.env) (js/process.cwd)))

(defn- args->map [argv]
  (loop [a (vec argv) m {}]
    (if (empty? a) m
        (let [[k & r] a]
          (cond
            (= k "--edn")        (recur (rest r) (assoc m :edn (first r)))
            (= k "--jobs")       (recur (rest r) (assoc m :jobs (js/parseInt (first r) 10)))
            (= k "--rad-sample") (recur (rest r) (assoc m :rad-sample (js/parseInt (first r) 10)))
            :else (recur (vec r) m))))))

(defn- git
  "Run git in `dir`; nil on non-zero exit. Never throws — a broken checkout is
   data (it is exactly the :not-relatable class), not a crash."
  [dir & a]
  (try
    (str/trim (.toString (cp/execFileSync "git" (clj->js (concat ["-C" dir] a))
                                          #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]
                                               :maxBuffer (* 8 1024 1024)})))
    (catch :default _ nil)))

(defn- count-of [dir & a]
  (let [s (apply git dir a)] (when (and s (re-matches #"\d+" s)) (js/parseInt s 10))))

(defn- pool
  "Run (f item) over items with at most `n` in flight, returning a Promise of a
   vector in input order. The fleet is ~3,600 repos x ~7 git invocations; doing
   that serially takes long enough that nobody runs it, which is the exact
   failure this script exists to prevent — so the concurrency is load-bearing,
   not an optimisation."
  [n items f]
  (js/Promise.
   (fn [resolve _]
     (let [v (vec items)
           total (count v)
           out (js/Array. total)
           next-i (atom 0)
           done (atom 0)]
       (if (zero? total)
         (resolve [])
         (letfn [(spawn! []
                   (let [i @next-i]
                     (when (< i total)
                       (swap! next-i inc)
                       (-> (js/Promise.resolve (f (nth v i)))
                           (.then (fn [r]
                                    (aset out i r)
                                    (swap! done inc)
                                    (when (zero? (mod @done 500))
                                      (js/console.error (str "  " @done "/" total)))
                                    (if (= @done total)
                                      (resolve (vec out))
                                      (spawn!))))))))]
           (dotimes [_ (min n total)] (spawn!))))))))

(defn- git-async
  "Promise of stdout (trimmed) or nil. Same contract as `git`, non-blocking."
  [dir & a]
  (js/Promise.
   (fn [resolve _]
     (cp/execFile "git" (clj->js (concat ["-C" dir] a))
                  #js {:encoding "utf8" :maxBuffer (* 8 1024 1024)}
                  (fn [err stdout _] (resolve (when-not err (str/trim (str stdout)))))))))

(defn- count-async [dir & a]
  (.then (apply git-async dir a)
         (fn [s] (when (and s (re-matches #"\d+" s)) (js/parseInt s 10)))))

;; ---------------------------------------------------------------------------
;; west.yml
;; ---------------------------------------------------------------------------

(defn parse-west
  "Minimal, deliberately dumb reader: west.yml is a generated file with a fixed
   shape, and a real YAML dependency would be a heavier promise than this needs."
  [yml]
  (->> (str/split yml #"\n    - name: ")
       rest
       (map (fn [chunk]
              (let [nm (first (str/split chunk #"\n"))
                    grab (fn [k] (second (re-find (re-pattern (str "\\n\\s+" k ":\\s*(\\S+)")) (str "\n" chunk))))]
                {:name (str/trim nm) :revision (grab "revision") :path (grab "path")
                 :rad-rid (grab "rad-rid")})))))

;; ---------------------------------------------------------------------------
;; per-repo probe
;; ---------------------------------------------------------------------------

(defn probe-repo
  "Promise of one repo's synchronisation facts. Returns data for every failure
   mode rather than throwing: a checkout that cannot relate HEAD to its pin is
   the single most common real state (it does not hold the pinned object), and
   collapsing it into an error would erase the finding."
  [{:keys [name revision path]}]
  (let [abs (when path (str root "/" path))]
    (if-not (and abs (fs/existsSync (str abs "/.git")))
      (js/Promise.resolve {:name name :present? false})
      (-> (js/Promise.all
           #js [(git-async abs "rev-parse" "HEAD")
                (git-async abs "rev-parse" "--abbrev-ref" "HEAD")
                (git-async abs "status" "--porcelain" "--untracked-files=normal")
                (git-async abs "stash" "list")
                (git-async abs "rev-parse" "origin/HEAD")])
          (.then
           (fn [[head branch status stash origin-head]]
             (let [lines (remove str/blank? (str/split-lines (or status "")))
                   tracked (remove #(str/starts-with? % "??") lines)
                   fetch-h (str abs "/.git/FETCH_HEAD")
                   age-d (when (fs/existsSync fetch-h)
                           (/ (- (.now js/Date) (.getTime (.-mtime (fs/statSync fetch-h)))) 86400000.0))
                   base {:name name :present? true :branch branch :head head
                         :tracked-dirty (count tracked)
                         :untracked (- (count lines) (count tracked))
                         :stashes (count (remove str/blank? (str/split-lines (or stash ""))))
                         :fetch-age-days age-d}]
               (-> (js/Promise.all
                    #js [(if (or (nil? head) (nil? revision))
                           (js/Promise.resolve :unknown)
                           (if (= head revision)
                             (js/Promise.resolve :equal)
                             (-> (js/Promise.all
                                  #js [(count-async abs "rev-list" "--count" (str revision ".." head))
                                       (count-async abs "rev-list" "--count" (str head ".." revision))])
                                 (.then (fn [[a b]]
                                          (cond (nil? a) :not-relatable
                                                (pos? a) :local-ahead
                                                (and b (pos? b)) :local-behind
                                                :else :not-relatable))))))
                         (if origin-head
                           (count-async abs "rev-list" "--count" (str revision ".." origin-head))
                           (-> (git-async abs "rev-parse" "origin/main")
                               (.then (fn [om]
                                        (if (and om revision)
                                          (count-async abs "rev-list" "--count" (str revision ".." om))
                                          nil)))))
                         (if (and branch (not= branch "HEAD"))
                           (count-async abs "rev-list" "--count" (str "origin/" branch "..HEAD"))
                           (js/Promise.resolve nil))])
                   (.then (fn [[vs-pin pin-behind unpushed]]
                            (assoc base :vs-pin vs-pin :pin-behind pin-behind
                                   :unpushed unpushed))))))))
      )))

;; ---------------------------------------------------------------------------
;; radicle plane (readable with the node stopped — that is the point)
;; ---------------------------------------------------------------------------

(defn probe-rad [n]
  (let [store (str (os/homedir) "/.radicle/storage")]
    (when (and (pos? n) (fs/existsSync store))
      (let [all (vec (fs/readdirSync store))
            step (max 1 (js/Math.floor (/ (count all) n)))
            pick (take n (take-nth step all))]      ; deterministic stride, not RNG
        {:total (count all)
         :sampled (count pick)
         :heads (into {} (keep (fn [rid]
                                 (when-let [h (git (str store "/" rid) "rev-parse" "refs/heads/main")]
                                   [rid h]))
                               pick))}))))

;; ---------------------------------------------------------------------------
;; aggregate
;; ---------------------------------------------------------------------------

(defn- pct [n d] (if (pos? d) (/ (js/Math.round (* 10000 (/ n d))) 100.0) nil))

(defn- quantile [sorted q]
  (when (seq sorted) (nth sorted (min (dec (count sorted)) (js/Math.floor (* q (count sorted)))))))

(defn aggregate [entries results rad]
  (let [present (filter :present? results)
        vs (frequencies (map :vs-pin present))
        comparable (filter #(number? (:pin-behind %)) present)
        stale (filter #(pos? (:pin-behind %)) comparable)
        ages (sort (keep :fetch-age-days present))
        heads (set (keep :head present))
        pins  (set (keep :revision entries))
        rad-cls (when rad
                  (reduce (fn [acc [_ h]]
                            (update acc (cond (contains? heads h) :matches-local-head
                                              (contains? pins h)  :matches-pin-only
                                              :else               :matches-neither)
                                    (fnil inc 0)))
                          {} (:heads rad)))]
    {:west-entries (count entries)
     :pinned-entries (count (filter :revision entries))
     :rad-rid-entries (count (filter :rad-rid entries))
     :materialised (count present)
     :unmaterialised (- (count (filter :revision entries)) (count present))
     :head-vs-pin {:equal (get vs :equal 0)
                   :local-ahead (get vs :local-ahead 0)
                   :local-behind (get vs :local-behind 0)
                   :not-relatable (get vs :not-relatable 0)
                   :diverged-total (- (count present) (get vs :equal 0))
                   :diverged-pct (pct (- (count present) (get vs :equal 0)) (count present))}
     :pin-behind-origin {:repos (count stale)
                         :of-comparable (count comparable)
                         :commits (reduce + 0 (map :pin-behind stale))
                         :median (quantile (sort (map :pin-behind stale)) 0.5)
                         :p90 (quantile (sort (map :pin-behind stale)) 0.9)
                         :max (reduce max 0 (map :pin-behind stale))}
     :wip {:tracked-dirty-files (reduce + 0 (map :tracked-dirty present))
           :tracked-dirty-repos (count (filter #(pos? (:tracked-dirty %)) present))
           :untracked-repos (count (filter #(pos? (:untracked %)) present))
           :stashes (reduce + 0 (map :stashes present))
           :unpushed-commits (reduce + 0 (keep :unpushed present))
           :unpushed-repos (count (filter #(pos? (or (:unpushed %) 0)) present))}
     :fetch-staleness {:with-fetch-head (count ages)
                       :never-fetched (- (count present) (count ages))
                       :median-days (some-> (quantile ages 0.5) (.toFixed 2) js/parseFloat)
                       :p90-days (some-> (quantile ages 0.9) (.toFixed 2) js/parseFloat)
                       :max-days (some-> (last ages) (.toFixed 2) js/parseFloat)
                       :fetched-last-24h (count (filter #(< % 1.0) ages))}
     :radicle (when rad
                (merge {:total (:total rad) :sampled (:sampled rad)
                        :readable (count (:heads rad))}
                       rad-cls
                       {:divergence-pct (pct (get rad-cls :matches-neither 0) (count (:heads rad)))}))}))

;; ---------------------------------------------------------------------------
;; main
;; ---------------------------------------------------------------------------

(defn -main [& argv]
  (let [{:keys [edn rad-sample jobs]} (args->map argv)
        rad-n (if (some? rad-sample) rad-sample 500)
        n-jobs (or jobs (max 2 (min 8 (- (count (os/cpus)) 2))))
        yml (str root "/manifest/west.yml")
        entries (parse-west (str (fs/readFileSync yml "utf8")))]
    (js/console.error (str "probing " (count entries) " west entries with " n-jobs " jobs ..."))
    (-> (pool n-jobs entries probe-repo)
        (.then
         (fn [results]
           (let [rad (probe-rad rad-n)
                 agg (assoc (aggregate entries results rad)
                            :as-of (subs (.toISOString (js/Date.)) 0 10)
                            :probe "scripts/fleet-sync-probe.cljs"
                            :host-note "single workstation; a second machine has its own drift and is not measured here")]
             (println (pr-str agg))
             (when edn
               (fs/writeFileSync edn (str ";; generated by scripts/fleet-sync-probe.cljs — do not hand-edit\n"
                                          (pr-str agg) "\n"))
               (js/console.error (str "wrote " edn)))))))))

(apply -main (drop 2 (vec js/process.argv)))
