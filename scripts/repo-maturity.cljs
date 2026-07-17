#!/usr/bin/env nbb
;; scripts/repo-maturity.cljs
;;
;; west-registered repo ごとに「成熟度」を 4 軸で数値評価し、DataScript/Datomic に
;; そのまま transact! できる EAVT entity-map のベクタとして manifest/repo-maturity.edn
;; へ書き出す。
;;
;; 軸（すべて 0.0〜1.0、算出できない場合は nil — 捏造ゼロ。存在しない値を
;; 0.5 などのデフォルトで埋めない）:
;;   :maturity/stage-score       README.md / CLAUDE.md 中の既存 stage 表記
;;                                （Status: R0〜R5 / :spec→:implemented /
;;                                🟢landed・🟡proposed・⏳blocked 等）を機械的に
;;                                パースして数値化したもの。新規の実装判定はしない。
;;   :maturity/structural-score  README・LICENSE・test(s)/ dir・CLAUDE.md の
;;                                有無から算出する構造シグナル。
;;   :maturity/activity-score    pushedAt の新しさ + デフォルトブランチの
;;                                commit 数（対数スケール）。
;;   :maturity/impl-score        languages() の総バイト数（対数スケール、実装量の
;;                                代理指標）から、README/CLAUDE.md 中の
;;                                scaffold/TODO/not-implemented 系マーカーで減点した
;;                                ヒューリスティック。**これはコードを実際に読んだ
;;                                目視判定ではない**（1472 repo 規模では非現実的）。
;;                                サイズ+テキストマーカーの代理指標である旨を
;;                                :maturity/impl-score-method に明記する。
;;   :maturity/coverage-score    README/CLAUDE.md の coverage 宣言を優先、なければ REST
;;                                git/trees で src/test ファイル比。Phase 1 ヒューリスティック。
;;                                Phase 2 で cloverage 実カバレッジを別軸で追加予定。
;;   :maturity/composite          非nil軸の重み付き平均
;;                                （stage 0.30 / structural 0.15 / activity 0.15 / impl 0.20 / coverage 0.20、
;;                                 nil軸があれば残りで再正規化）。
;;
;; データ源: manifest/west.yml（生成物、simple line-based parse — repos.edn の
;; path-overrides/文字列内EDN 二重ネストを避けるため、既に解決済みの west.yml を読む）。
;; :datalad グループは除外（コードrepoでなくデータセット）。
;;
;; GitHub API: `gh api graphql` を shell out（scripts.nbb-compat/sh、verify-west-pins.cljs
;; と同じパターン）。1バッチ 20 repo を alias 付き単一 query にまとめ、
;; --jq でレスポンスを事前整形してから js/JSON.parse する（cljs 側に重い JSON walker を
;; 書かない）。private org（gh 認証で見える権限内）は素通り、見えないものは
;; そのバッチのみ WARN で全軸 nil。
;;
;; 使い方:
;;   nbb scripts/repo-maturity.cljs                 ; 全 repo を評価して manifest/repo-maturity.edn を書く
;;   nbb scripts/repo-maturity.cljs --limit 20       ; 先頭 N repo だけ（動作確認用）
;;   nbb scripts/repo-maturity.cljs --batch-size 10  ; GraphQL バッチサイズ変更（既定 20）
(require '[scripts.nbb-compat :as io :refer [slurp spit format]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(defn- sh [& args]
  (try (let [{:keys [exit out err]} (apply io/sh args)]
         {:exit exit :out (or out "") :err (or err "")})
       (catch :default e {:exit -1 :out "" :err (str e)})))

;; ---- CLI args --------------------------------------------------------------

(defn- parse-args [args]
  (loop [args args opts {:batch-size 20}]
    (if-let [[k & more] (seq args)]
      (case k
        "--limit"      (recur (rest more) (assoc opts :limit (js/parseInt (first more))))
        "--batch-size" (recur (rest more) (assoc opts :batch-size (js/parseInt (first more))))
        "--out"        (recur (rest more) (assoc opts :out (first more)))
        "--only"       (recur (rest more) (assoc opts :only (first more)))
        "--self-test"  (recur more (assoc opts :self-test true))
        "--merge-existing" (recur more (assoc opts :merge-existing true))
        (recur more opts))
      opts)))

(def opts (parse-args *command-line-args*))

;; ---- gh preflight -----------------------------------------------------------

(when (not (:self-test opts))
  (when (not= 0 (:exit (sh "gh" "--version")))
    (println "repo-maturity: gh CLI が見つかりません。中止。")
    (io/exit 3))
  (when (not= 0 (:exit (sh "gh" "auth" "status")))
    (println "repo-maturity: gh 未認証。中止。")
    (io/exit 3)))

;; ---- manifest/west.yml parse (simple line-based; generator output is regular) ----

(defn- parse-west-yml [text]
  (let [lines (str/split-lines text)]
    (loop [lines lines
           section nil
           remotes {}
           cur-remote nil
           projects []
           cur nil]
      (if-let [[line & more] (seq lines)]
        (let [trimmed (str/trim line)]
          (cond
            (str/starts-with? line "  remotes:") (recur more :remotes remotes cur-remote projects cur)
            (str/starts-with? line "  defaults:") (recur more :defaults remotes cur-remote (cond-> projects cur (conj cur)) nil)
            (str/starts-with? line "  group-filter:") (recur more :other remotes cur-remote (cond-> projects cur (conj cur)) nil)
            (str/starts-with? line "  projects:") (recur more :projects remotes cur-remote (cond-> projects cur (conj cur)) nil)

            (and (= section :remotes) (str/starts-with? trimmed "- name:"))
            (recur more section remotes {:name (str/trim (subs trimmed 7))} projects cur)

            (and (= section :remotes) (str/starts-with? trimmed "url-base:"))
            (recur more section (assoc remotes (:name cur-remote) (str/trim (subs trimmed 9))) nil projects cur)

            (and (= section :projects) (str/starts-with? trimmed "- name:"))
            (recur more section remotes cur-remote (cond-> projects cur (conj cur))
                   {:name (str/trim (subs trimmed 7))})

            (and (= section :projects) cur (str/starts-with? trimmed "remote:"))
            (recur more section remotes cur-remote projects (assoc cur :remote (str/trim (subs trimmed 7))))

            ;; repo-path: overrides the GitHub repo name when the west `name:` (must be
            ;; west-unique) collides across orgs -- e.g. two different orgs each having a
            ;; "kotodama" repo get west names "com-junkawasaki-kotodama" / "kotoba-lang-kotodama"
            ;; with repo-path: kotodama holding the REAL GitHub repo name for both.
            (and (= section :projects) cur (str/starts-with? trimmed "repo-path:"))
            (recur more section remotes cur-remote projects (assoc cur :repo-path (str/trim (subs trimmed 10))))

            (and (= section :projects) cur (str/starts-with? trimmed "revision:"))
            (recur more section remotes cur-remote projects (assoc cur :revision (str/trim (subs trimmed 9))))

            (and (= section :projects) cur (str/starts-with? trimmed "path:"))
            (recur more section remotes cur-remote projects (assoc cur :path (str/trim (subs trimmed 5))))

            (and (= section :projects) cur (str/starts-with? trimmed "groups:"))
            (recur more section remotes cur-remote projects
                   (assoc cur :groups (str/trim (subs trimmed 7))))

            :else (recur more section remotes cur-remote projects cur)))
        {:remotes remotes :projects (cond-> projects cur (conj cur))}))))

(def west (parse-west-yml (slurp "manifest/west.yml")))

(defn- remote->org [remote]
  ;; url-base の最後の path segment が GitHub org 名
  ;; ("git@github.com:kotoba-lang" -> "kotoba-lang", "https://github.com/etzhayyim" -> "etzhayyim")
  (when-let [url-base (get (:remotes west) remote)]
    (last (str/split url-base #"[:/]"))))

(def all-repos
  (->> (:projects west)
       (remove #(and (:groups %) (str/includes? (:groups %) "datalad")))
       (remove #(and (:groups %) (str/includes? (:groups %) "archived")))
       (keep (fn [{:keys [name remote revision path repo-path] :as p}]
               (when-let [org (remote->org remote)]
                 ;; repo-path: (when present) is the real GitHub repo name; `name` is only
                 ;; the west-unique project id and may be org-prefixed to avoid collisions.
                 {:name (or repo-path name) :west-name name :org org :remote remote
                  :revision revision :path path})))
       (distinct)))

(def target-repos
  (cond->> all-repos
    (:only opts) (filter #(str/includes? (:path %) (:only opts)))
    (:limit opts) (take (:limit opts))))

(println (str "repo-maturity: " (count all-repos) " repos in west.yml (excl. datalad/archived), "
              "scoring " (count target-repos) "."))

;; ---- GraphQL batch fetch ----------------------------------------------------

(defn- gql-alias [i] (str "r" i))

(defn- gql-fragment [i {:keys [org name]}]
  (str (gql-alias i) ": repository(owner: " (pr-str org) ", name: " (pr-str name) ") {\n"
       "  pushedAt createdAt diskUsage isArchived isEmpty licenseInfo { key }\n"
       "  defaultBranchRef { name target { ... on Commit { history { totalCount } } } }\n"
       "  languages(first: 8, orderBy: {field: SIZE, direction: DESC}) { edges { size node { name } } }\n"
       "  readme: object(expression: \"HEAD:README.md\") { ... on Blob { text } }\n"
       "  claudemd: object(expression: \"HEAD:CLAUDE.md\") { ... on Blob { text } }\n"
       "  testsDir: object(expression: \"HEAD:tests\") { ... on Tree { oid } }\n"
       "  testDir: object(expression: \"HEAD:test\") { ... on Tree { oid } }\n"
       "}\n"))

(defn- fetch-batch
  "gh api graphql の --jq '.data' は PARTIAL FAILURE（一部 repo が 404 等で GraphQL
  errors[] を返す）だと jq を適用せず生の {data,errors} envelope をそのまま吐く
  （exit=1 になる）— 実測確認済み。exit code に関係なく out を JSON.parse し、
  トップレベルに \"data\" キーがあれば envelope（そこから抽出）、無ければ既に
  jq 適用済みの data そのもの、として扱う。壊れているのは失敗した alias だけで、
  同バッチの他の repo のデータは失わない。"
  [batch]
  (let [query (str "query {\n"
                    (str/join (map-indexed gql-fragment batch))
                    "}\n")
        {:keys [exit out err]} (sh "gh" "api" "graphql" "-f" (str "query=" query) "--jq" ".data")
        trimmed (str/trim (or out ""))]
    (if (empty? trimmed)
      (do (println (str "  WARN: batch gh api 空応答 (exit=" exit "): "
                        (subs err 0 (min 200 (count err)))))
          {})
      (try
        (let [parsed (js->clj (js/JSON.parse trimmed) :keywordize-keys true)]
          (if (contains? parsed :data) (:data parsed) parsed))
        (catch :default e
          (println (str "  WARN: batch JSON parse 失敗 (" (.-message e) "), skipping " (count batch) " repos"))
          {})))))

;; ---- scoring -----------------------------------------------------------------

(defn- days-since [iso-str]
  (when iso-str
    (/ (- (.getTime (js/Date.)) (.getTime (js/Date. iso-str))) 86400000.0)))

;; markdown noise (**bold**, whitespace, an optional colon) commonly sits between a
;; label and its value -- e.g. "**Status**: R0" -- so labels tolerate up to 10
;; non-alphanumeric filler chars rather than a strict "label: value" match.
(def stage-patterns
  ;; [regex score source-tag] — 最初にマッチしたものを採用。順序が優先度。
  [[#"(?i)status[^a-zA-Z0-9]{0,10}R5\b" 1.0 :status-r5]
   [#"(?i)status[^a-zA-Z0-9]{0,10}R4\b" 0.8 :status-r4]
   [#"(?i)status[^a-zA-Z0-9]{0,10}R3\b" 0.6 :status-r3]
   [#"(?i)status[^a-zA-Z0-9]{0,10}R2\b" 0.4 :status-r2]
   [#"(?i)status[^a-zA-Z0-9]{0,10}R1\b" 0.25 :status-r1]
   [#"(?i)status[^a-zA-Z0-9]{0,10}R0\b" 0.1 :status-r0]
   [#":implemented\b" 0.8 :spec-implemented]
   [#":spec\b" 0.2 :spec-only]
   [#"✅|shipped|🟢|landed(?!\?)" 0.9 :landed-marker]
   [#"🟡|proposed|scaffold" 0.3 :proposed-marker]
   [#"⏳|blocked" 0.15 :blocked-marker]])

(defn- stage-score [text]
  (when (seq (str/trim (or text "")))
    (some (fn [[re score tag]]
            (when (re-find re text) [score tag]))
          stage-patterns)))

(defn- clamp01 [x] (max 0.0 (min 1.0 x)))

(defn- structural-score [{:keys [readme claudemd licenseInfo testsDir testDir]}]
  (let [has-readme (some? (:text readme))
        has-license (some? licenseInfo)
        has-tests (or (some? testsDir) (some? testDir))
        has-claude (some? (:text claudemd))]
    [(clamp01 (+ (if has-readme 0.25 0)
                 (if has-license 0.15 0)
                 (if has-tests 0.35 0)
                 (if has-claude 0.25 0)))
     {:readme has-readme :license has-license :tests has-tests :claude-md has-claude}]))

(defn- activity-score [{:keys [pushedAt defaultBranchRef]}]
  (let [d (days-since pushedAt)
        recency (when d (clamp01 (- 1.0 (/ d 365.0))))
        commits (get-in defaultBranchRef [:target :history :totalCount])
        commit-score (when commits (clamp01 (/ (js/Math.log10 (+ commits 1)) 3.0)))]
    (when (or recency commit-score)
      (/ (+ (or recency 0.0) (or commit-score 0.0))
         (+ (if recency 1 0) (if commit-score 1 0))))))

(def scaffold-markers
  #"(?i)RuntimeError|not\s+implemented|not-implemented|TODO|scaffold|\braises\b|R0 scaffold|placeholder")

(defn- impl-score [{:keys [languages diskUsage readme claudemd]}]
  (let [total-bytes (reduce + 0 (map :size (:edges languages)))
        size-score (when (pos? total-bytes) (clamp01 (/ (js/Math.log10 (+ total-bytes 1)) 6.0)))
        text (str (:text readme) " " (:text claudemd))
        marker-hits (count (re-seq scaffold-markers text))
        penalty (clamp01 (* marker-hits 0.08))]
    (when size-score
      [(clamp01 (- size-score penalty)) {:total-bytes total-bytes :scaffold-marker-hits marker-hits}])))

;; ---- coverage-score (heuristic, Phase 1) ------------------------------------
;; 3-layer fallback, zero fabrication:
;;   1. README/CLAUDE.md の明示的 coverage 宣言 ("Coverage: 85%", cloverage
;;      --fail-threshold N) をパースした実値（high 信頼）。
;;   2. REST git/trees/HEAD?recursive=1 で取った src/ と test(s)/ のファイル数比
;;      （mid 信頼）。GraphQL Tree.entries は直下のみで Clojure の src/<ns>/file
;;      構造を辿れないため、REST recursive tree を使う（実測: crdt 6/6 等）。
;;   3. いずれも取れなければ nil（tests-dir binary は structural-score が既に
;;      見ているのでここでは出さない — 重複回避）。
;; Phase 2 で cloverage 実カバレッジを別軸 :maturity/coverage-actual で上書き予定。

(defn- parse-declared-coverage [text]
  (when (seq (str/trim (or text "")))
    (let [pct-m (re-find #"(?i)coverage[^0-9]{0,15}([0-9]{1,3}(?:\.[0-9]+)?)\s*%" text)
          ft-m  (re-find #"(?i)fail-threshold[\"'\s:=]+([0-9]{1,3})" text)]
      (cond
        (and pct-m (second pct-m))
        (let [pct (js/parseFloat (second pct-m))]
          (when (<= 0 pct 100) {:pct (/ pct 100.0) :source :declared-coverage-pct :raw (second pct-m)}))
        (and ft-m (second ft-m))
        (let [pct (js/parseFloat (second ft-m))]
          (when (<= 0 pct 100) {:pct (/ pct 100.0) :source :declared-fail-threshold :raw (second ft-m)}))
        :else nil))))

(defn- fetch-tree [org name]
  ;; REST git/trees/HEAD?recursive=1 で src/ と test(s)/ のファイル数を数える。
  ;; HEAD は default branch の commit に解決される（実測）。truncated=true の大規模
  ;; repo ではファイル数が不完全になり得るが、Phase 1 ヒューリスティックでは許容
  ;; （Phase 2 の cloverage 実計測で正確化）。失敗（404/空/rate-limit）は nil。
  (let [{:keys [exit out]} (sh "gh" "api"
                                (str "repos/" org "/" name "/git/trees/HEAD?recursive=1")
                                "--jq" ".tree[].path")]
    (when (= 0 exit)
      (let [paths (str/split-lines (str/trim (or out "")))]
        {:src-files  (count (filter #(str/starts-with? % "src/") paths))
         :test-files (count (filter #(re-find #"^tests?/" %) paths))}))))

(defn- coverage-score-from-data [gql tree]
  ;; 純関数: gql + tree -> [score detail] or nil（--self-test 対象）。
  (let [text (str (get-in gql [:readme :text]) " " (get-in gql [:claudemd :text]))
        declared (parse-declared-coverage text)]
    (cond
      declared [(:pct declared) {:method :declared :source (:source declared) :raw-pct (:raw declared)}]
      (and tree (pos? (+ (:src-files tree) (:test-files tree))))
      (let [sf (:src-files tree) tf (:test-files tree)]
        [(clamp01 (/ tf (+ sf tf))) {:method :file-ratio :src-files sf :test-files tf}])
      :else nil)))

(defn- coverage-score [org name gql]
  ;; 副作用ラッパ: REST で tree を取得して coverage-score-from-data に渡す。
  (coverage-score-from-data gql (fetch-tree org name)))

(defn- composite [stage structural activity impl coverage]
  ;; 5 軸化（coverage 追加）。stage を 0.40->0.30 に減らし、structural/activity を
  ;; 0.20->0.15 に。impl 0.20 は維持、coverage 0.20 新設。nil 軸は残りで再正規化。
  ;; 注意: 既存の :maturity/composite 値は全エントリで再計算される（時系列比較は
  ;; :maturity/computed-at で区別）。
  (let [weighted [[stage 0.30] [structural 0.15] [activity 0.15] [impl 0.20] [coverage 0.20]]
        present (filter (comp some? first) weighted)]
    (when (seq present)
      (/ (reduce + (map (fn [[v w]] (* v w)) present))
         (reduce + (map second present))))))

(defn- score-repo [{:keys [org name path revision] :as repo} gql]
  (when gql
    (let [text (str (get-in gql [:readme :text]) " " (get-in gql [:claudemd :text]))
          [stage stage-tag] (or (stage-score text) [nil nil])
          [structural structural-detail] (structural-score gql)
          activity (activity-score gql)
          [impl impl-detail] (or (impl-score gql) [nil nil])
          [coverage coverage-detail] (or (coverage-score org name gql) [nil nil])
          comp (composite stage structural activity impl coverage)]
      (cond-> {:repo/path path
               :repo/org org
               :repo/name name
               :repo/pinned-revision revision
               :maturity/structural-score structural
               :maturity/structural-detail structural-detail
               :maturity/activity-score activity
               :maturity/impl-score-method :size-and-scaffold-marker-heuristic
               :maturity/computed-at (.toISOString (js/Date.))}
        stage        (assoc :maturity/stage-score stage :maturity/stage-source stage-tag)
        impl         (assoc :maturity/impl-score impl :maturity/impl-detail impl-detail)
        coverage     (assoc :maturity/coverage-score coverage :maturity/coverage-detail coverage-detail
                            :maturity/coverage-score-method (:method coverage-detail))
        comp         (assoc :maturity/composite comp)
        (:isArchived gql) (assoc :repo/archived? true)
        (:isEmpty gql)    (assoc :repo/empty? true)))))

;; ---- main loop ---------------------------------------------------------------

;; ---- self-test (pure-function checks; no gh/network) ------------------------

(defn- run-self-test []
  (let [cases
        [["parse-declared-coverage: Coverage: 85%"
          (:pct (parse-declared-coverage "Coverage: 85%")) 0.85]
         ["parse-declared-coverage: fail-threshold 90"
          (:pct (parse-declared-coverage "cloverage --fail-threshold 90")) 0.90]
         ["parse-declared-coverage: none"
          (parse-declared-coverage "no coverage mention here") nil]
         ["coverage-score-from-data: declared wins over file-ratio"
          (first (coverage-score-from-data {:readme {:text "Coverage: 70%"}}
                                            {:src-files 10 :test-files 1})) 0.70]
         ["coverage-score-from-data: file-ratio"
          (first (coverage-score-from-data {:readme {:text "no mention"}}
                                            {:src-files 6 :test-files 6})) 0.50]
         ["coverage-score-from-data: nil when no signal"
          (coverage-score-from-data {:readme {:text ""}} nil) nil]
         ["composite: all-1.0 -> 1.0"
          (composite 1.0 1.0 1.0 1.0 1.0) 1.0]
         ["composite: nil coverage renormalizes to 1.0"
          (composite 1.0 1.0 1.0 1.0 nil) 1.0]]
        failures (for [[label actual expected] cases
                       :when (not= actual expected)]
                   (str "  FAIL " label ": expected " expected ", got " actual))]
    (if (seq failures)
      (do (println "repo-maturity self-test FAILED:")
          (run! println failures)
          (io/exit 1))
      (do (println "repo-maturity self-test OK (" (count cases) " cases)")
          (io/exit 0)))))

(when (:self-test opts) (run-self-test))

(def batch-size (:batch-size opts))
(def batches (partition-all batch-size target-repos))
(def n-batches (count batches))

(def results
  (loop [bs batches idx 0 acc []]
    (if-let [[batch & more] (seq bs)]
      (let [_ (println (str "  batch " (inc idx) "/" n-batches " (" (count batch) " repos)..."))
            data (fetch-batch batch)
            scored (keep-indexed (fn [i r]
                                    (score-repo r (get data (keyword (gql-alias i)))))
                                  batch)]
        (recur more (inc idx) (into acc scored)))
      acc)))

(println (str "repo-maturity: scored " (count results) "/" (count target-repos) " repos "
              "(" (- (count target-repos) (count results)) " skipped — repo not found / no API access / batch failure)."))

(def out-path (or (:out opts) "manifest/repo-maturity.edn"))

;; --merge-existing: 今回スコア化できなかった repo は既存 edn の古い値を保持。
;; CI cron で GraphQL rate limit に阻まれた分を翌日以降に持ち越すため（ADR-2607171030 Z）。
(def existing-entities
  (when (and (:merge-existing opts) (not (:self-test opts)))
    (try (edn/read-string (slurp out-path))
         (catch :default _ nil))))

(def results-by-path (into {} (map (juxt :repo/path identity) results)))

(def merged
  (if (and (:merge-existing opts) (seq existing-entities))
    (let [kept (remove #(contains? results-by-path (:repo/path %)) existing-entities)]
      (vec (concat results kept)))
    results))

(when (and (:merge-existing opts) (seq existing-entities))
  (println (str "repo-maturity: merge-existing kept " (- (count merged) (count results))
                " repos from prior run (merged total " (count merged) "/"
                (count all-repos) ").")))

(def header
  (str ";; manifest/repo-maturity.edn — generated by scripts/repo-maturity.cljs. DO NOT EDIT BY HAND.\n"
       ";; Regenerate: nbb scripts/repo-maturity.cljs\n"
       ";;\n"
       ";; A vector of DataScript/Datomic-transactable entity-maps, one per west-registered\n"
       ";; repo. Each map is directly usable as a tx-data entry:\n"
       ";;\n"
       ";;   (require '[datascript.core :as d])\n"
       ";;   (def schema {:repo/path {:db/unique :db.unique/identity}})\n"
       ";;   (def conn (d/create-conn schema))\n"
       ";;   (d/transact! conn (clojure.edn/read-string (slurp \"manifest/repo-maturity.edn\")))\n"
       ";;   (d/q '[:find ?path ?score :where [?e :repo/path ?path] [?e :maturity/composite ?score]] @conn)\n"
       ";;\n"
       ";; Axes (0.0-1.0, nil when not computable -- no fabricated defaults):\n"
       ";;   :maturity/stage-score     parsed from existing README/CLAUDE.md stage markers only\n"
       ";;                             (Status: R0-R5, :spec/:implemented, landed/proposed/blocked emoji).\n"
       ";;                             No new judgment is made where no marker exists.\n"
       ";;   :maturity/structural-score  README + LICENSE + tests-dir + CLAUDE.md presence.\n"
       ";;   :maturity/activity-score    pushedAt recency + default-branch commit count (log-scaled).\n"
       ";;   :maturity/impl-score        HEURISTIC PROXY (languages() byte size, log-scaled, minus a\n"
       ";;                               penalty for scaffold/TODO/not-implemented text markers) --\n"
       ";;                               NOT a manual code read at this scale (1472 repos). See\n"
       ";;                               :maturity/impl-score-method on every entity.\n"
       ";;   :maturity/composite          weighted mean of the present axes (stage .3/structural .15/\n"
       ";;                                activity .15/impl .2/coverage .2, renormalized when an axis is nil).\n"
       ";;\n"
       ";; Generated: " (.toISOString (js/Date.)) "\n"
       ";; Coverage: " (count merged) "/" (if (:merge-existing opts) (count all-repos) (count target-repos)) " scored"
       (if (:limit opts) (str " (--limit " (:limit opts) " run)") "") "\n\n"))

(def body
  (str "[\n" (str/join "\n" (map #(str " " (pr-str %)) merged)) "\n]\n"))

(spit out-path (str header body))
(println (str "repo-maturity: wrote " out-path))
