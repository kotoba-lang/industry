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
;;   nbb scripts/repo-maturity.cljs --paths-from paths.txt --merge-existing
;;                                                   ; ファイルに列挙した path のみ（位置でなく名前で指定）
;;   nbb scripts/repo-maturity.cljs --offset 800 --limit 800 --merge-existing
;;                                                   ; 801〜1600 番目だけ再スコアして既存とマージ
;;                                                   （長い --full を分割して完走させるため）
;;   nbb scripts/repo-maturity.cljs --batch-size 40  ; GraphQL バッチサイズ変更（既定 20）
;;   nbb scripts/repo-maturity.cljs --full           ; incremental を無効化し全 repo を再取得
;;
;; **既定は incremental**: 既存 manifest/repo-maturity.edn を読み、west pin が前回から
;; 動いていない repo は GraphQL を 1 回も叩かずに前回 entity を再利用する（pin が同じなら
;; repo の内容はバイト同一なので、内容由来の 4 軸は必ず同じ値になる）。再利用した entity は
;; :maturity/reused-at-pin true を持ち、:maturity/computed-at は前回のまま = いつの値か
;; 監査できる。pin と独立に動く activity 軸も据え置きになるので、全軸を取り直すなら --full。
(require '["fs" :as node-fs]
         '[scripts.nbb-compat :as io :refer [slurp spit format]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(defn- sh [& args]
  (try (let [{:keys [exit out err]} (apply io/sh args)]
         {:exit exit :out (or out "") :err (or err "")})
       (catch :default e {:exit -1 :out "" :err (str e)})))

;; ---- CLI args --------------------------------------------------------------

(defn- parse-args [args]
  (loop [args args opts {:batch-size 20 :jobs 8}]
    (if-let [[k & more] (seq args)]
      (case k
        "--limit"      (recur (rest more) (assoc opts :limit (js/parseInt (first more))))
        "--offset"     (recur (rest more) (assoc opts :offset (js/parseInt (first more))))
        "--paths-from" (recur (rest more) (assoc opts :paths-from (first more)))
        "--batch-size" (recur (rest more) (assoc opts :batch-size (js/parseInt (first more))))
        "--out"        (recur (rest more) (assoc opts :out (first more)))
        "--only"       (recur (rest more) (assoc opts :only (first more)))
        "--self-test"  (recur more (assoc opts :self-test true))
        "--merge-existing" (recur more (assoc opts :merge-existing true))
        "--full"           (recur more (assoc opts :full true))
        "--jobs"           (recur (rest more) (assoc opts :jobs (js/parseInt (first more))))
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
  ;; --offset exists so the stage-rule sweep can be finished in slices. A single
  ;; --full pass over ~3,950 repos is long enough that it kept being interrupted
  ;; (once at batch 44 of 198), and --limit alone can only ever rescore the same
  ;; front of the list again. Offset first, then limit: `--offset 800 --limit 800`
  ;; is the second slice.
  (cond->> all-repos
    (:only opts) (filter #(str/includes? (:path %) (:only opts)))
    ;; --paths-from names repos EXACTLY, which --offset (positional, over a list other
    ;; sessions change underneath) cannot.
    ;;
    ;; It was added chasing a remainder that turned out not to exist, and the chase is
    ;; worth recording because two plausible explanations were both wrong. After the
    ;; five slices, 170 ledger entries still held the old coverage method. First story:
    ;; "shifting offsets skipped 170 repos." Measured: only FOUR of the 170 are still in
    ;; west.yml at all; 166 are orphans, paths no longer in the manifest. Second story:
    ;; "so those four were skipped." Measured: all four are in the `archived` group,
    ;; which all-repos removes on purpose.
    ;;
    ;; THE SWEEP SKIPPED NOTHING. Every non-archived repo in west.yml was rescored. The
    ;; 170 remnants describe repos this script deliberately never visits, and no flag
    ;; changes that. The flag stays because addressing a subset by name is useful in its
    ;; own right -- it is what --only was being used for, one repo at a time.
    ;; NOTE the shape: `filter` must be the OUTER form, because cond->> threads the
    ;; collection in as the last argument. Written as (let [...] (filter ...)) the
    ;; collection lands as a third body form of the `let` and the filter's result is
    ;; discarded -- the run then scores every repo while looking like it filtered.
    ;; Which is exactly what happened: it printed "scoring 3956" instead of 4.
    (:paths-from opts)
    (filter (let [wanted (->> (str/split-lines (slurp (:paths-from opts)))
                              (map str/trim)
                              (remove str/blank?)
                              set)]
              #(contains? wanted (:path %))))
    (:offset opts) (drop (:offset opts))
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
       "  srcDir: object(expression: \"HEAD:src\") { ... on Tree { oid } }\n"
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
;; A CODE SAMPLE IS NOT A STATUS DECLARATION, and this cost a real score.
;;
;; kotoba-lang/card's composite fell 0.73 -> 0.53 the moment its README documented
;; the card lifecycle, because `:blocked` -- a CARD STATE, appearing inside fenced
;; code blocks and an ASCII state diagram -- matched the `blocked` marker and was
;; read as "this repository is blocked" (0.15, the lowest tier). io-stripe-issuing
;; scored the same way off its state-mapping table. Documenting a domain that
;; happens to contain the word made the repo look abandoned.
;;
;; So: strip fenced blocks and inline code before looking for markers, and require
;; the WORD markers to sit in a status-ish context the way the R-tiers already do.
;; Emoji stay bare -- ✅ / 🟡 / ⏳ are markers by nature and nobody writes them as
;; domain vocabulary.
(defn- strip-code
  "Remove fenced code blocks and inline code spans. What is left is prose, which is
  the only place a repository declares its own stage."
  [text]
  (-> (or text "")
      (str/replace #"(?s)```.*?```" " ")
      (str/replace #"(?s)~~~.*?~~~" " ")
      (str/replace #"`[^`\n]*`" " ")))

;; The window a word marker must share with a status-ish label, mirroring the
;; R-tier patterns' own tolerance for markdown noise ("**Status**: blocked").
(def ^:private status-label "(?:status|stage|maturity|state of (?:this|the) (?:repo|project)|進捗|状態)")

(defn- labelled
  "A regex matching `word` only when a status-ish label precedes it closely, or when
  a list/table marker introduces it (`- blocked`, `| blocked |`).

  The leading marker character is MANDATORY. Allowing a bare line start looked
  reasonable and was wrong: these READMEs are hard-wrapped at ~72 columns, so any
  word can land at the start of a line. cloud-itonami-card-issuing scored
  :proposed-marker off the word `scaffold` opening a wrapped line in the middle of a
  sentence about a compliance scaffold -- prose, not a status."
  [word]
  (re-pattern (str "(?im)(?:"
                   ;; a status-ish label introduces it
                   status-label "[^a-zA-Z0-9]{0,14}" word "\\b"
                   ;; a list item is exactly this marker
                   "|^[ \\t]*[-*+>][ \\t]*" word "\\b"
                   ;; a table cell STARTS with it (`| blocked |`, `| blocked on … |`)
                   "|\\|[ \\t]*" word "\\b[^|\\n]*\\|"
                   ")")))

(def stage-patterns
  ;; [regex score source-tag scope] — 最初にマッチしたものを採用。順序が優先度。
  ;;
  ;; SCOPE MATTERS, and getting it wrong the first time cost 552 repos. Stripping code
  ;; from ALL patterns removed :implemented / :spec on 552 of the first 800 repos
  ;; rescored, because those markers are EDN KEYWORDS and this workspace writes them
  ;; exactly where you would expect a keyword: inside a fenced EDN snippet. Mean
  ;; composite fell 0.04 with 562 repos down and 43 up -- so the "fix" was, on balance,
  ;; a bigger error than the bug. Caught by diffing a bulk rescore against the ledger
  ;; before landing it.
  ;;
  ;;   :full  -- match the whole document. For patterns that are already unambiguous:
  ;;             a keyword form (:implemented) or a label-anchored one (Status: R3).
  ;;   :prose -- match only outside code. For bare words, which mean nothing without
  ;;             context and collide with domain vocabulary (`:blocked` the CARD STATE).
  [[#"(?i)status[^a-zA-Z0-9]{0,10}R5\b" 1.0 :status-r5 :full]
   [#"(?i)status[^a-zA-Z0-9]{0,10}R4\b" 0.8 :status-r4 :full]
   [#"(?i)status[^a-zA-Z0-9]{0,10}R3\b" 0.6 :status-r3 :full]
   [#"(?i)status[^a-zA-Z0-9]{0,10}R2\b" 0.4 :status-r2 :full]
   [#"(?i)status[^a-zA-Z0-9]{0,10}R1\b" 0.25 :status-r1 :full]
   [#"(?i)status[^a-zA-Z0-9]{0,10}R0\b" 0.1 :status-r0 :full]
   [#":implemented\b" 0.8 :spec-implemented :full]
   [#":spec\b" 0.2 :spec-only :full]
   [#"✅|🟢" 0.9 :landed-marker :prose]
   [(labelled "(?:shipped|landed)") 0.9 :landed-marker :prose]
   [#"🟡" 0.3 :proposed-marker :prose]
   [(labelled "(?:proposed|scaffold)") 0.3 :proposed-marker :prose]
   [#"⏳" 0.15 :blocked-marker :prose]
   [(labelled "blocked") 0.15 :blocked-marker :prose]])

(def stage-score-method
  "Recorded on every entity so a reader can tell which entries were scored with the
  code-stripped, label-anchored rules and which still carry the older bare-word
  ones. Entries without this key predate the fix and are only rescored when their
  west pin moves (or on a --full run)."
  :anchored-markers-code-stripped)

(defn- stage-score [text]
  (let [full (or text "")
        prose (strip-code full)]
    (when (seq (str/trim full))
      (some (fn [[re score tag scope]]
              (let [haystack (if (= :prose scope) prose full)]
                (when (re-find re haystack) [score tag])))
            stage-patterns))))

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

(def ^:private child-process (js/require "node:child_process"))

(defn- sh-async
  "spawn ベースの非同期 shell-out。resolve のみ（reject しない）ので
   Promise.all が 1 件の失敗で全体を落とさない。"
  [cmd args]
  (js/Promise.
   (fn [resolve _reject]
     (let [proc (.spawn child-process cmd (clj->js args))
           out  (atom "")]
       (.on (.-stdout proc) "data" (fn [d] (swap! out str d)))
       (.on proc "error" (fn [_] (resolve {:exit -1 :out ""})))
       (.on proc "close" (fn [code] (resolve {:exit code :out @out})))))))

(defn- p-map
  "items を最大 concurrency 本で並列に f にかけ、入力順の vector を resolve する。
   f は Promise を返すこと。個別の失敗は nil になる（全体は落とさない）。"
  [f items concurrency]
  (let [items (vec items) n (count items)]
    (if (zero? n)
      (js/Promise.resolve [])
      (js/Promise.
       (fn [resolve _reject]
         (let [results (atom (vec (repeat n nil)))
               next-idx (atom 0)
               done (atom 0)]
           (letfn [(start! []
                     (let [i @next-idx]
                       (when (< i n)
                         (swap! next-idx inc)
                         (-> (f (nth items i))
                             (.then (fn [v] (swap! results assoc i v)))
                             (.catch (fn [_] nil))
                             (.then (fn [_]
                                      (swap! done inc)
                                      (if (= @done n) (resolve @results) (start!))))))))]
             (dotimes [_ (min concurrency n)] (start!)))))))))

(defn- fetch-tree-async
  "fetch-tree の非同期版。実測でこの REST が全体の律速だったので並列化する。
   数え方は同期版と同一（src/ と test(s)/ 配下のファイル数）。"
  [org name]
  (-> (sh-async "gh" ["api" (str "repos/" org "/" name "/git/trees/HEAD?recursive=1")
                      "--jq" ".tree[].path"])
      (.then (fn [{:keys [exit out]}]
               (when (= 0 exit)
                 (let [paths (str/split-lines (str/trim (or out "")))]
                   {:src-files  (count (filter #(str/starts-with? % "src/") paths))
                    :test-files (count (filter #(re-find #"^tests?/" %) paths))}))))))

(defn- coverage-score-from-data [gql tree]
  ;; 純関数: gql + tree -> [score detail] or nil（--self-test 対象）。
  (let [text (str (get-in gql [:readme :text]) " " (get-in gql [:claudemd :text]))
        declared (parse-declared-coverage text)]
    (cond
      declared [(:pct declared) {:method :declared :source (:source declared) :raw-pct (:raw declared)}]
      ;; TESTS PER SOURCE FILE, not tests as a share of all files.
      ;;
      ;; The previous formula was tf/(sf+tf), which has two measured problems:
      ;;
      ;;   - a repo with ONE TEST FILE PER SOURCE FILE -- this workspace's own
      ;;     convention, and the best a file-level proxy can observe -- scored 0.5,
      ;;     i.e. read as "half covered". 1,452 of the 3,042 repos scored by this
      ;;     method were at 1:1 or better and every one of them was pinned near 0.5.
      ;;     Nothing could exceed 0.5 without MORE test files than source files, which
      ;;     nobody aims for, so the axis systematically understated the whole fleet.
      ;;   - 8 repos with ZERO source files and one or two test files scored 1.0:
      ;;     nothing to cover read as perfectly covered.
      ;;
      ;; So: tf/sf clamped to 1.0, and no source files is NOT COMPUTABLE rather than
      ;; perfect -- the same "nil when not computable, never a fabricated default"
      ;; rule the other axes follow. Method tag is :file-ratio-v2 so entries scored
      ;; under either formula stay tellable apart.
      (and tree (pos? (:src-files tree 0)))
      (let [sf (:src-files tree) tf (:test-files tree)]
        [(clamp01 (/ tf sf))
         {:method :file-ratio-v2 :src-files sf :test-files tf}])
      :else nil)))

(defn- needs-tree?
  "REST git/trees を叩く必要があるか。**叩かなくても答が確定する 2 ケース**は false:
     (a) README/CLAUDE.md に coverage 宣言がある -> coverage-score-from-data は
         tree を見ずに :declared を返す
     (b) HEAD に src/ も test/ も tests/ も無い -> 数え上げは 0/0 になり
         :file-ratio-v2 分岐の (pos? (:src-files ...)) が偽 -> nil
   どちらも短絡してもスコアの値は変わらない。実測でこの REST が repo あたり ~0.6s、
   フルラン時間の大半だった (GraphQL 本体は 20 repo ~1.5-3.4s = ~0.1s/repo)。"
  [gql]
  (let [text (str (get-in gql [:readme :text]) " " (get-in gql [:claudemd :text]))]
    (and (nil? (parse-declared-coverage text))
         (or (some? (:srcDir gql)) (some? (:testsDir gql)) (some? (:testDir gql))))))

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

(defn- score-repo [{:keys [org name path revision] :as repo} gql tree]
  (when gql
    (let [text (str (get-in gql [:readme :text]) " " (get-in gql [:claudemd :text]))
          [stage stage-tag] (or (stage-score text) [nil nil])
          [structural structural-detail] (structural-score gql)
          activity (activity-score gql)
          [impl impl-detail] (or (impl-score gql) [nil nil])
          [coverage coverage-detail] (or (coverage-score-from-data gql tree) [nil nil])
          comp (composite stage structural activity impl coverage)]
      (cond-> {:repo/path path
               :repo/org org
               :repo/name name
               :repo/pinned-revision revision
               :maturity/structural-score structural
               :maturity/structural-detail structural-detail
               :maturity/activity-score activity
               :maturity/impl-score-method :size-and-scaffold-marker-heuristic
               ;; Unconditional, like :maturity/impl-score-method: it describes HOW
               ;; the axis was computed, which matters just as much when the answer
               ;; is nil. Inside the `stage` branch it would be absent on exactly the
               ;; entries whose nil needs explaining -- "no marker found by the fixed
               ;; rules" would be indistinguishable from "scored before the fix".
               :maturity/stage-score-method stage-score-method
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
         ;; Was 0.50 under tf/(sf+tf). One test file per source file is the best a
         ;; file-level proxy can see, so it is 1.0 now -- see the comment on
         ;; coverage-score-from-data for the 1,452 repos this was understating.
         ["coverage-score-from-data: one test per source file is full file-level coverage"
          (first (coverage-score-from-data {:readme {:text "no mention"}}
                                            {:src-files 6 :test-files 6})) 1.0]
         ["coverage-score-from-data: half the source files covered"
          (first (coverage-score-from-data {:readme {:text "no mention"}}
                                            {:src-files 10 :test-files 5})) 0.5]
         ["coverage-score-from-data: more test files than source does not exceed 1.0"
          (first (coverage-score-from-data {:readme {:text "no mention"}}
                                            {:src-files 4 :test-files 9})) 1.0]
         ["coverage-score-from-data: nothing to cover is not computable, not perfect"
          (coverage-score-from-data {:readme {:text "no mention"}}
                                     {:src-files 0 :test-files 2}) nil]
         ["coverage-score-from-data: source files with no tests is 0.0, not nil"
          (first (coverage-score-from-data {:readme {:text "no mention"}}
                                           {:src-files 7 :test-files 0})) 0.0]
         ["coverage-score-from-data: a declared figure still wins over the ratio"
          (first (coverage-score-from-data {:readme {:text "Coverage: 70%"}}
                                           {:src-files 6 :test-files 6})) 0.70]
         ["coverage-score-from-data: nil when no signal"
          (coverage-score-from-data {:readme {:text ""}} nil) nil]
         ["composite: all-1.0 -> 1.0"
          (composite 1.0 1.0 1.0 1.0 1.0) 1.0]
         ["composite: nil coverage renormalizes to 1.0"
          (composite 1.0 1.0 1.0 1.0 nil) 1.0]

         ;; The regression this fix exists for: a card lifecycle documented in a
         ;; code fence must not read as a blocked project. This is kotoba-lang/card's
         ;; actual README shape, reduced.
         ["stage-score: :blocked inside a fence is not a blocked project"
          (stage-score "# card\n\nStatus: R2\n\n```clojure\n(lc/reachable? :blocked :reissue)\n```\n")
          [0.4 :status-r2]]
         ["stage-score: a state diagram naming blocked scores nothing on its own"
          (stage-score ":issued --activate--> :active --block--> :blocked\n")
          nil]
         ["stage-score: inline `:blocked` in prose does not count"
          (stage-score "one is a card that was working and was `blocked` by a human.\n")
          nil]
         ["stage-score: a real status line still counts"
          (stage-score "**Status**: blocked on a vendor decision\n") [0.15 :blocked-marker]]
         ["stage-score: a list marker still counts"
          (stage-score "- blocked: waiting on the scheme\n") [0.15 :blocked-marker]]
         ["stage-score: an emoji marker still counts bare"
          (stage-score "| feature | ⏳ |\n") [0.15 :blocked-marker]]
         ["stage-score: 'shipped' in prose about someone else does not count"
          (stage-score "The vendor shipped their SDK in 2024, which is not our status.\n")
          nil]
         ;; The second false positive: hard-wrapped prose puts an ordinary word at a
         ;; line start. This is cloud-itonami-card-issuing's actual README, reduced.
         ["stage-score: a wrapped line beginning with 'scaffold' is prose"
          (stage-score (str "the software supplies the governed, spec-cited, audited execution\n"
                            "scaffold so that operator does not have to build the compliance\n"))
          nil]
         ["stage-score: a table cell still counts"
          (stage-score "| write paths | blocked |\n") [0.15 :blocked-marker]]
         ;; The regression the first version of this fix caused, on 552 repos: an EDN
         ;; keyword marker belongs inside a code fence, and stripping code lost it.
         [":implemented inside a fence still counts"
          (stage-score "# x\n\n```edn\n{:status :implemented}\n```\n") [0.8 :spec-implemented]]
         [":spec inside a fence still counts"
          (stage-score "```edn\n{:status :spec}\n```\n") [0.2 :spec-only]]
         ["but a bare word inside a fence still does not"
          (stage-score "```clojure\n(def x :blocked)\n```\n") nil]
         ["a Status: line inside a fence is still a status"
          (stage-score "```\nStatus: R3\n```\n") [0.6 :status-r3]]
         ["stage-score: R-tier wins over a later word marker"
          (stage-score "Status: R4\n\n- proposed follow-ups below\n") [0.8 :status-r4]]
         ["strip-code: fenced and inline code both go"
          (str/includes? (strip-code "a ```x :blocked x``` b `:blocked` c") "blocked") false]]
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

;; ---- incremental: west pin が動いていない repo は API を叩かない ---------------
;;
;; `:repo/pinned-revision` は生成時の west pin。**pin が同一なら repo の内容は
;; バイト単位で同一**なので、内容から出る 4 軸 (stage / structural / impl /
;; coverage) は再計算しても必ず同じ値になる。よって GraphQL を 1 回も叩かずに
;; 前回の entity をそのまま再利用してよい。判定材料は west.yml だけで、
;; ネットワークアクセスは発生しない。
;;
;; 唯一 pin と独立に動くのは `:maturity/activity-score`（pushedAt の新しさ +
;; commit 数）。既定ではこれも据え置き、`:maturity/reused-at-pin true` と
;; 元の `:maturity/computed-at` を残して「いつの値か」を監査可能にする。
;; 全軸を取り直したいときは `--full`。
(def existing-by-path
  (when-not (:self-test opts)
    (try (into {} (map (juxt :repo/path identity)
                       (edn/read-string (slurp (or (:out opts) "manifest/repo-maturity.edn")))))
         (catch :default _ {}))))

(defn- reusable? [{:keys [path revision]}]
  (when-let [prev (get existing-by-path path)]
    (and revision
         (= revision (:repo/pinned-revision prev))
         ;; スコアが 1 つも無い entity は再利用しない（前回失敗分は取り直す）
         (some? (:maturity/composite prev)))))

(def reused
  (if (:full opts)
    []
    (->> target-repos
         (filter reusable?)
         (mapv (fn [{:keys [path]}]
                 (assoc (get existing-by-path path) :maturity/reused-at-pin true))))))

(def reused-paths (set (map :repo/path reused)))
(def fetch-repos (vec (remove #(contains? reused-paths (:path %)) target-repos)))

(println (str "repo-maturity: incremental — pin 不変で再利用 " (count reused)
              " / GraphQL 対象 " (count fetch-repos)
              (if (:full opts) "  (--full: 再利用を無効化)" "")))

(def batch-size (:batch-size opts))
(def batches (partition-all batch-size fetch-repos))
(def n-batches (count batches))

(def jobs (:jobs opts))

(defn- fetch-data-by-path
  "fetch-batch を **repo path キー**で返す。alias (r0, r1...) はクエリ内の位置なので、
   分割再試行すると番号が振り直される — path に正規化してから合成する。

   バッチ全体が空応答だったら **半分に割って再試行する**。実測 (2026-07-28):
   --batch-size 40 では 5 バッチ中 4 バッチが GitHub から JSON でなく HTML を返し
   ('invalid character '<'' / 'unexpected end of JSON input')、1 バッチ = 40 repo が
   黙って欠落していた。分割再試行にすれば、大きすぎるクエリも一時障害も
   小さい単位まで落として拾い直せる。"
  [batch]
  (let [data (fetch-batch batch)
        got  (into {} (keep-indexed
                       (fn [i r] (when-let [d (get data (keyword (gql-alias i)))]
                                   [(:path r) d]))
                       batch))]
    (if (and (empty? got) (> (count batch) 4))
      (let [half (quot (count batch) 2)]
        (println (str "    retry: 空応答のため " (count batch) " -> " half " + " (- (count batch) half) " に分割"))
        (merge (fetch-data-by-path (vec (take half batch)))
               (fetch-data-by-path (vec (drop half batch)))))
      got)))

(defn- process-batch
  "1 バッチ: GraphQL は 1 リクエスト（同期）、その後 tree が要る repo だけを
   最大 --jobs 本で並列 REST 取得してからスコア化する。Promise を返す。"
  [idx batch]
  (println (str "  batch " (inc idx) "/" n-batches " (" (count batch) " repos)..."))
  (let [data    (fetch-data-by-path batch)
        indexed (vec (map (fn [r] [r (get data (:path r))]) batch))
        want    (vec (filter (fn [[_ gql]] (and gql (needs-tree? gql))) indexed))]
    (-> (p-map (fn [[r _]] (fetch-tree-async (:org r) (:name r))) want jobs)
        (.then (fn [trees]
                 (let [tree-by-path (into {} (map (fn [[[r _] t]] [(:path r) t])
                                                  (map vector want trees)))]
                   (vec (keep (fn [[r gql]]
                                (score-repo r gql (get tree-by-path (:path r))))
                              indexed))))))))

(defn- write-artifact!
  "results を既存 artifact にマージして書き出す。checkpoint と最終書き出しの共通経路。
   keep-existing? が true なら今回スコア化していない repo の前回値を残す
   (checkpoint では必須 — 残さないと途中経過で全体が消える)。"
  [results keep-existing?]
  (let [out-path (or (:out opts) "manifest/repo-maturity.edn")

        ;; 既存 edn の値を保持する条件（ADR-2607171030 Z: CI cron が rate limit に
        ;; 阻まれた分を翌日に持ち越す / checkpoint が途中経過で全体を消さない）。
        existing-entities (when (and keep-existing? (not (:self-test opts)))
                            (try (edn/read-string (slurp out-path))
                                 (catch :default _ nil)))
        ;; 今回の結果 + pin 不変で再利用した分。--merge-existing はさらに、今回
        ;; スコア化できなかった repo の前回値を保持する（rate limit の持ち越し）。
        scored-now   (vec (concat results reused))
        scored-paths (set (map :repo/path scored-now))
        merged (if (seq existing-entities)
                 (vec (concat scored-now
                              (remove #(contains? scored-paths (:repo/path %)) existing-entities)))
                 scored-now)
        _ (when (seq existing-entities)
            (println (str "repo-maturity: merge-existing kept " (- (count merged) (count scored-now))
                          " repos from prior run (merged total " (count merged) "/"
                          (count all-repos) ").")))
        ;; REFUSE TO SHRINK THE LEDGER BY ACCIDENT.
        ;;
        ;; A filtered run (--only / --limit / --offset) without --merge-existing writes
        ;; ONLY the repos it scored, silently replacing every other entry with nothing.
        ;; That is right for `--out somewhere-else.edn` and almost never right for the
        ;; ledger. Measured the hard way: `--only kotoba-lang/card --limit 1`, meant as a
        ;; one-repo sanity check, cut manifest/repo-maturity.edn from 3,899 entities to 1,
        ;; and nothing said so -- it was only noticed because a later comparison joined
        ;; against it and matched zero rows.
        ;;
        ;; So a filtered run that would shrink an existing file now refuses. The escape
        ;; hatch is to say which you meant: --merge-existing to keep the rest, or --out
        ;; to write somewhere that is not the ledger.
        _ (let [filtered? (or (:only opts) (:limit opts) (:offset opts) (:paths-from opts))
                prior (when-not (:self-test opts)
                        (try (count (edn/read-string (slurp out-path)))
                             (catch :default _ 0)))]
            (when (and filtered? (not (:merge-existing opts))
                       prior (> prior (count merged)))
              (println (str "repo-maturity: REFUSED. This is a filtered run (--only/--limit/"
                            "--offset) without --merge-existing, and it would shrink "
                            out-path " from " prior " entities to " (count merged) "."))
              (println (str "  Add --merge-existing to keep the " (- prior (count merged))
                            " entries this run did not score, or --out <path> to write "
                            "somewhere that is not the ledger."))
              (io/exit 4)))
        header
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
       ;; Say how many entries describe repos this script does not visit, so the entity
       ;; count cannot be read as fleet coverage. Measured 2026-07-30: 4,180 entities vs
       ;; 3,956 repos, the difference being orphans (path gone from west.yml) plus
       ;; archived-group repos whose entries predate their archiving.
       ";; Not in west.yml (orphaned or archived): "
       (let [live (set (map :path all-repos))]
         (count (remove #(contains? live (:repo/path %)) merged)))
       " of " (count merged) "\n"
       ";; Coverage: " (count merged) "/" (if (:limit opts) (count target-repos) (count all-repos)) " scored"
       (str " (" (count reused) " reused at unchanged west pin)")
       (if (:limit opts) (str " (--limit " (:limit opts) " run)") "") "\n\n")

        body (str "[\n" (str/join "\n" (map #(str " " (pr-str %)) merged)) "\n]\n")]
    ;; ATOMIC: write a sibling temp file and rename over the target.
    ;;
    ;; A plain spit truncates first, so anything reading the ledger during a run sees
    ;; a partial file or none at all. Hit twice while sweeping in slices -- once a
    ;; comparison script read 1 entity mid-write, once it failed outright with ENOENT
    ;; on a path that certainly existed. A rename on the same filesystem is atomic, so
    ;; a reader sees either the old ledger or the new one.
    (let [tmp (str out-path ".tmp")]
      (spit tmp (str header body))
      (node-fs/renameSync tmp out-path))
    (println (str "repo-maturity: wrote " out-path " (" (count merged) " entities)"))
    :done))

(defn- finish! [results]
  (println (str "repo-maturity: scored " (count results) "/" (count fetch-repos) " repos "
                "(" (- (count fetch-repos) (count results)) " skipped — repo not found / no API access / batch failure)."))
  (write-artifact! results (:merge-existing opts)))

;; バッチは順番に、バッチ内の REST だけ並列。最後の top-level 式が Promise なので
;; nbb がこれを await してからプロセスを終了する（実測確認済み）。
;; **20 バッチごとに checkpoint を書く。** 途中で kill されても、次回起動時に
;; incremental (west pin 一致) がその分をそのまま再利用するので、再開できる。
;; 実測 (2026-07-28): checkpoint 無しでフルランが batch 113/176 で kill され、
;; 全 batch 分の結果がメモリごと失われた。
(def checkpoint-every 20)

(.then (reduce (fn [pacc [idx batch]]
                 (.then pacc (fn [acc]
                               (.then (process-batch idx batch)
                                      (fn [s]
                                        (let [acc' (into acc s)]
                                          (when (zero? (mod (inc idx) checkpoint-every))
                                            (println (str "  checkpoint: " (count acc') " scored so far"))
                                            (write-artifact! acc' true))
                                          acc'))))))
               (js/Promise.resolve [])
               (map-indexed vector batches))
       finish!)

