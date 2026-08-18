#!/usr/bin/env nbb
;; deps-local-root-census.cljs — どの repo が fleet node 上で実際に build/test できるか。
;;
;; **問い**: murakumo fleet が node に配るのは対象 repo の tree 1 本だけ（`git archive`
;; ないし `git bundle`）で、`orgs/<org>/<repo>` の兄弟 checkout は 1 つも無い。したがって
;; `deps.edn` が `:local/root "../../<org>/<repo>"` を持つ alias は node で classpath を
;; 組めない。`ship-git-deps!`（scripts/fleet-ci/tick.cljs）が運ぶのは `:git/sha` 依存
;; （`io.github.<org>/<repo>` 形）だけで、`:local/root` は運ばない。
;;
;; **なぜ EDN を読むだけで足りないか**: 読めるのは仮説まで。実際の判定は
;; `git archive` した bare tree の上で `clojure -Spath -M:<alias>` を回して確かめる
;; （ADR-2608136400 の bare-tree 検証節）。この census はその候補を絞るためのもの。
;;
;; **alias 単位で判定する**: base `:deps` の `:local/root` は、その alias が base を
;; 解決するときだけ効く。`:replace-deps` を持つ alias は base を使わないので、
;; base に 117 件の local root があっても、その alias だけは node で走りうる。
;;
;; 使い方:
;;   nbb --classpath ".:scripts/nbb_compat" scripts/deps-local-root-census.cljs \
;;     --root /path/to/superproject [--out census.edn] [--json] [--limit N]
;;
;; 既定の root は「この script が居る repo の 1 つ上」ではなく **cwd**。west checkout
;; を持つ superproject の root で回す（worktree には orgs/ が無い）。

(ns deps-local-root-census
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- argval [args flag] (second (drop-while #(not= % flag) args)))
(defn- alias-name [a] (if (keyword? a) (name a) (str a)))

(defn- argflag [args flag] (boolean (some #(= % flag) args)))

;; ---------------------------------------------------------------- reading

(defn- parse-deps [txt]
  (try {:text txt :deps (edn/read-string {:default (fn [_tag v] v)} txt)}
       (catch :default e {:text txt :parse-error (ex-message e)})))

(defn- git-show
  "`git -C dir show <ref>:deps.edn` の本文、無ければ nil。**読み取りのみ**
  （このスクリプトは `orgs/` に一切書き込まない）。"
  [dir ref]
  (try
    (let [r (cp/spawnSync "git" #js ["-C" dir "show" (str ref ":deps.edn")]
                          #js {:encoding "utf8" :timeout 30000
                               :maxBuffer (* 8 1024 1024)})]
      (when (zero? (.-status r)) (.-stdout r)))
    (catch :default _ nil)))

(defn- default-branch-refs
  "west checkout の既定ブランチ ref の候補。**`origin/main` だけを見てはいけない** ——
  west は remote を **org 名**で作るので、多くの checkout に `origin/*` は存在しない
  （ADR-2608136000 が deploy guard で測った 64% 無検査の原因と同じ形）。"
  [org]
  [(str "origin/main") (str org "/main") "origin/master" (str org "/master")])

(defn- read-deps
  "deps.edn を読む。既定ブランチ ref が引ければそちらを正本にし、worktree との差分を
  `:drift?` として記録する。

  **なぜ worktree を正本にしないか**: west checkout は pin に合わせて動くので
  `origin/main` より遅れていることがある。実測 2026-08-13、`kotoba-lang/kagitaba`
  の worktree は `security` を `:local/root` で持っていたが、既定ブランチは
  2026-07-19 の `c9256af9`（pin shared security dependency）で既に `:git/sha` へ
  移していた —— **worktree だけを見た census は、既に直っている repo を
  『壊れている』と報告する**。

  **remote ref は最後に fetch した時点の値**であって GitHub の今ではない。
  順位付けの上位は `gh api` で読み直すこと（この census の boundary）。"
  [dir f org use-refs?]
  (let [wt (try (fs/readFileSync f "utf8") (catch :default _ nil))
        hit (when use-refs?
              (first (keep (fn [r] (when-let [t (git-show dir r)] [r t]))
                           (default-branch-refs org))))
        [ref-name rt] hit
        chosen (or rt wt)]
    (merge (parse-deps (or chosen ""))
           {:source (cond rt :ref wt :worktree-no-remote-ref :else :missing)
            :ref-used ref-name
            :drift? (boolean (and rt wt (not= rt wt)))})))

(defn- local-roots
  "coord map（lib -> coord）から `:local/root` を持つ lib の集合。"
  [coords]
  (when (map? coords)
    (into #{} (keep (fn [[lib coord]]
                      (when (and (map? coord) (contains? coord :local/root)) lib))
                    coords))))

(defn- git-libs [coords]
  (when (map? coords)
    (into #{} (keep (fn [[lib coord]]
                      (when (and (map? coord) (contains? coord :git/sha)) lib))
                    coords))))

(defn- mvn-libs [coords]
  (when (map? coords)
    (into #{} (keep (fn [[lib coord]]
                      (when (and (map? coord) (contains? coord :mvn/version)) lib))
                    coords))))

;; ---------------------------------------------------------- alias model
;;
;; tools.deps: `-M:a` が解決する依存集合は
;;   `:replace-deps` があれば **base :deps を捨てて** それだけ
;;   無ければ base :deps ∪ `:extra-deps`（`:override-deps` は座標だけ差し替え）
;; したがって「base に local root があるから全滅」は誤り —— :replace-deps を持つ
;; alias は base を見ない。cloud-itonami の `:kernels` が誤ったのは、
;; :extra-deps しか持たず base の 117 件を引きずっていたため。

(defn- alias-effective
  [base-deps alias-map]
  (cond
    (map? (:replace-deps alias-map)) {:base? false :coords (:replace-deps alias-map)}
    :else {:base? true
           :coords (merge (or base-deps {})
                          (or (:extra-deps alias-map) {})
                          (or (:override-deps alias-map) {}))}))

;; ------------------------------------------------------------- claims
;;
;; `:kernels` は落ちただけではなく **走ると主張していた**。同じ形（コメントが
;; git-only 解決 / fleet 実行可能 / CI-ready を宣言している）を機械的に拾う。

;; **狭く取る。** 最初の版は `#"(gate|ci).{0,40}(ready|able)"` を持っていて
;; 「PRIMARY gate: the portable suite under ClojureScript」を 25 件拾った ——
;; あれは cljs で回す宣言であって「兄弟なしで解決する」という主張ではない。
;; ここが拾うべきなのは **sibling checkout 無しで classpath が組めると言っている行**
;; だけで、それ以外は census の数字を薄めるだけの雑音になる。
(def claim-patterns
  [[:resolves-from-git   #"(?i)resolve[sd]?[^\n]{0,50}\b(from|via|over)\s+(git|the\s+network|network)"]
   [:everything-git      #"(?i)everything\s+(below|here|in\s+this[^\n]{0,20})\s+resolves"]
   [:bare-clone          #"(?i)bare\s+clone"]
   [:outside-monorepo    #"(?i)(outside|without)\s+(the\s+)?(monorepo|workspace|west)"]
   [:no-sibling-checkout #"(?i)no\s+(sibling\s+checkout|monorepo/west\s+context|west\s+context|:?local/root)"]
   [:ci-runnable         #"(?i)(ci[- ]runnable|runs?\s+in\s+CI|resolves?\s+in\s+(github\s+actions|CI))"]
   [:fleet-node          #"(?i)(fleet|murakumo)[^\n]{0,60}(runnable|can\s+run|resolve)"]
   [:git-only            #"(?i)git[- ]only"]
   [:one-tree            #"(?i)(ships?|receives?)\s+one\s+(repository|repo)'?s?\s+tree"]])

(defn- claim-lines
  "deps.edn の **コメント行**から、node 実行可能性を主張していそうな行を拾う。"
  [txt]
  (let [lines (str/split-lines (or txt ""))]
    (vec
     (for [[i ln] (map-indexed vector lines)
           :let [t (str/trim ln)]
           :when (str/starts-with? t ";")
           [k re] claim-patterns
           :when (re-find re t)]
       {:line (inc i) :kind k :text t}))))

;; ------------------------------------------------------------- per repo

(defn- survey-one [root org repo use-refs?]
  (let [dir (path/join root "orgs" org repo)
        f (path/join dir "deps.edn")
        {:keys [text deps parse-error source ref-used drift?]} (read-deps dir f org use-refs?)]
    (if (or parse-error (not (map? deps)))
      {:org org :repo repo :path (str "orgs/" org "/" repo)
       :source source :ref-used ref-used :drift? drift?
       :parse-error (or parse-error "deps.edn is not a map")
       :claims (claim-lines text)}
      (let [base (:deps deps)
            base-lr (or (local-roots base) #{})
            aliases (:aliases deps)
            alias-rows
            (vec
             (for [[a am] (when (map? aliases) aliases)
                   :when (map? am)
                   :let [{:keys [base? coords]} (alias-effective base am)
                         own (merge (or (:extra-deps am) {})
                                    (or (:replace-deps am) {})
                                    (or (:override-deps am) {}))
                         eff-lr (or (local-roots coords) #{})]]
               {:alias a
                :uses-base? base?
                :own-local-roots (count (or (local-roots own) #{}))
                :effective-local-roots (count eff-lr)
                :effective-local-root-libs (vec (sort (map str eff-lr)))
                :node-runnable? (zero? (count eff-lr))}))]
        {:org org :repo repo :path (str "orgs/" org "/" repo)
         :source source :ref-used ref-used :drift? drift?
         :base-deps (count (or base {}))
         :base-local-roots (count base-lr)
         :base-local-root-libs (vec (sort (map str base-lr)))
         :base-git-deps (count (or (git-libs base) #{}))
         :base-mvn-deps (count (or (mvn-libs base) #{}))
         :aliases alias-rows
         :alias-count (count alias-rows)
         :runnable-aliases (vec (sort (map (comp alias-name :alias)
                                           (filter :node-runnable? alias-rows))))
         :blocked-aliases (vec (sort (map (comp alias-name :alias)
                                          (remove :node-runnable? alias-rows))))
         ;; repo 面の分類。**alias 面の判定が本体**で、これは要約でしかない。
         :class (cond
                  (and (zero? (count base-lr))
                       (every? :node-runnable? alias-rows)) :node-runnable
                  (zero? (count base-lr))                   :node-runnable-base
                  (some :node-runnable? alias-rows)          :blocked-base-some-alias-ok
                  :else                                      :blocked)
         :claims (claim-lines text)}))))

;; --------------------------------------------------------------- gates

(defn- gate-repos [root]
  (let [f (path/join root "scripts/fleet-ci/gates.edn")]
    (if-not (fs/existsSync f)
      []
      (let [g (edn/read-string (fs/readFileSync f "utf8"))]
        (vec (for [r (:repos g)]
               {:name (:name r) :id (:id r) :gate (:gate r)
                :alias (or (:alias r) "test") :org (:org r)}))))))

;; ----------------------------------------------------------------- main

(defn -main [& args]
  (let [root (or (argval args "--root") (.cwd js/process))
        limit (some-> (argval args "--limit") js/parseInt)
        out (argval args "--out")
        ;; **既定は `origin/main`。** worktree だけを見た census は west pin の
        ;; 遅れをそのまま「local root がある」と誤報する（read-deps の docstring）。
        ;; `--worktree` を渡すと手元の tree を見る（drift そのものを測りたい時用）。
        use-refs? (not (argflag args "--worktree"))
        orgs-dir (path/join root "orgs")]
    (when-not (fs/existsSync orgs-dir)
      (println "no orgs/ under" root "— run from a superproject root with a west checkout")
      (.exit js/process 2))
    (let [pairs (vec (for [org (sort (fs/readdirSync orgs-dir))
                           :when (.isDirectory (fs/statSync (path/join orgs-dir org)))
                           repo (sort (fs/readdirSync (path/join orgs-dir org)))
                           :when (fs/existsSync (path/join orgs-dir org repo "deps.edn"))]
                       [org repo]))
          pairs (if limit (vec (take limit pairs)) pairs)
          rows (mapv (fn [[o r]] (survey-one root o r use-refs?)) pairs)
          ok (remove :parse-error rows)
          by-class (frequencies (map :class ok))
          claims (vec (for [r rows :when (seq (:claims r))]
                        {:path (:path r) :class (:class r)
                         :base-local-roots (:base-local-roots r)
                         :claims (:claims r)}))
          gates (gate-repos root)
          gate-index (into {} (map (juxt :repo identity)) ok)
          ;; **`:jvm-test` だけを突き合わせる。** `:nbb-script` gate は deps.edn の
          ;; alias を一切使わないので、そこに既定の "test" を当てて数えると
          ;; blocked が水増しされる（最初の版がそれで 46 と誤報した）。
          gates (filter #(= :jvm-test (:gate %)) gates)
          gate-rows (vec (for [g gates
                               :let [row (get gate-index (:name g))]
                               :when row]
                           (let [al (first (filter #(= (alias-name (:alias %)) (alias-name (:alias g)))
                                                   (:aliases row)))]
                             {:gate (or (:id g) (:name g)) :kind (:gate g)
                              :repo (:name g) :alias (:alias g)
                              :alias-present? (boolean al)
                              :base-local-roots (:base-local-roots row)
                              :alias-local-roots (:effective-local-roots al)
                              :node-runnable? (:node-runnable? al)})))
          summary {:generated-by "scripts/deps-local-root-census.cljs"
                   :root root
                   :ref (if use-refs? :default-branch :worktree)
                   :read-from (frequencies (map :source rows))
                   :worktree-drift (count (filter :drift? rows))
                   :repos-with-deps-edn (count rows)
                   :parse-errors (count (filter :parse-error rows))
                   :by-class by-class
                   :total-base-local-roots (reduce + 0 (keep :base-local-roots ok))
                   :repos-with-any-base-local-root
                   (count (filter #(pos? (or (:base-local-roots %) 0)) ok))
                   :alias-count (reduce + 0 (keep :alias-count ok))
                   :aliases-node-runnable
                   (reduce + 0 (map #(count (:runnable-aliases %)) ok))
                   :aliases-blocked
                   (reduce + 0 (map #(count (:blocked-aliases %)) ok))
                   :test-alias-repos
                   (count (filter (fn [r] (some #(= (alias-name (:alias %)) "test") (:aliases r))) ok))
                   :test-alias-node-runnable
                   (count (filter (fn [r] (some #(and (= (alias-name (:alias %)) "test")
                                                      (:node-runnable? %))
                                                (:aliases r))) ok))
                   :claim-repos (count claims)
                   :jvm-gate-rows (count gate-rows)
                   :jvm-gate-blocked (count (remove :node-runnable? gate-rows))}]
      (println (pr-str summary))
      (when out
        (fs/writeFileSync out (pr-str {:summary summary
                                       :repos rows
                                       :claims claims
                                       :gate-cross-reference gate-rows}))
        (println "wrote" out)))))

(apply -main *command-line-args*)
