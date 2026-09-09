#!/usr/bin/env nbb
;; scripts/itonami-maturity-scan.cljs — cloud-itonami 成熟度・依存関係の一次証拠スキャナ。
;;
;; ADR-2608052000。出力は manifest/itonami-maturity-evidence.edn（生成物・手編集禁止）。
;;
;;   nbb scripts/itonami-maturity-scan.cljs [--data-root <path>] [--out <path>] [--limit N]
;;
;; --data-root は orgs/ が実際に populate されている superproject 本体 checkout。
;; west 管理の orgs/ は gitignore されており fresh worktree には存在しないので、
;; worktree からスクリプトを走らせるときは必ず本体を指す
;; （90-docs/business/scripts/flagship-checklist-scan.cljs と同じ約束）。
;;
;; このスクリプトは「観測」だけを行い、スコアを一切計算しない。スコア化と
;; system dynamics は scripts/itonami-maturity-dynamics.cljs が担う。観測と評価を
;; 同じファイルに混ぜると、後から「この数字は測ったのか決めたのか」が読めなくなる。
;;
;; 測っていない項目は false/0 でなく nil を書く。nil は「未測定」であって「無い」ではない
;; （ADR-2607203000 の原則: 測っていないものを 0 と書かない）。

(ns itonami-maturity-scan
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["fs" :as fs]
            ["path" :as npath]
            ["child_process" :as cp]))

;; ---------------------------------------------------------------- args

(defn- parse-args [args]
  (loop [a args m {:data-root (.cwd js/process)
                   :out "manifest/itonami-maturity-evidence.edn"
                   :limit nil
                   :orgs ["cloud-itonami"]}]
    (if (empty? a)
      m
      (let [[k v & more] a]
        (case k
          "--data-root" (recur more (assoc m :data-root v))
          "--out"       (recur more (assoc m :out v))
          "--limit"     (recur more (assoc m :limit (js/parseInt v 10)))
          (recur (rest a) m))))))

(def opts (parse-args (vec (.slice (.-argv js/process) 2))))
(def data-root (:data-root opts))

(defn- exists? [p] (.existsSync fs p))
(defn- dr [& parts] (apply (.-join npath) data-root parts))

(defn- slurp* [p]
  (try (.toString (.readFileSync fs p) "utf8") (catch :default _ nil)))

;; ---------------------------------------------------------------- west.yml

(defn- west-projects
  "manifest/west.yml から {name path} を拾う。west.yml は生成物なので
   YAML パーサを積まず、`- name:` / `path:` の行対で読む（gen-west-manifest.cljs が
   出す canonical 形に依存する）。"
  [text]
  (let [lines (str/split-lines text)]
    (loop [ls lines cur nil acc []]
      (if (empty? ls)
        (cond-> acc cur (conj cur))
        (let [l (first ls)]
          (cond
            (re-find #"^\s+- name:\s*(\S+)" l)
            (recur (rest ls) {:name (second (re-find #"^\s+- name:\s*(\S+)" l))}
                   (cond-> acc cur (conj cur)))

            (and cur (re-find #"^\s+path:\s*(\S+)" l))
            (recur (rest ls) (assoc cur :path (second (re-find #"^\s+path:\s*(\S+)" l))) acc)

            (and cur (re-find #"^\s+revision:\s*(\S+)" l))
            (recur (rest ls) (assoc cur :revision (second (re-find #"^\s+revision:\s*(\S+)" l))) acc)

            :else (recur (rest ls) cur acc)))))))

;; ---------------------------------------------------------------- fs walk

(def skip-dirs #{".git" "node_modules" "target" ".shadow-cljs" ".cpcache"
                 "dist" "build" ".datalad" ".next" "vendor" ".venv" ".clj-kondo"})

(defn- virtualenv?
  "Python の仮想環境か。名前ではなく `pyvenv.cfg` の実在で判定する。

   名前で弾くと取りこぼす: skip-dirs は完全一致なので `.venv` は消えるが
   `.venv-tts` は残る。実測 2026-08-11、newscaster の `.venv-tts` は 27,560 files
   あり、walk が src/ に着く前に 6000 の予算を使い切って **src=0 / test=0** と
   測った（実体は src 109KB / test 33KB）。名前の変種を足し続けるより、
   virtualenv が必ず持つ標準ファイルを見る方が漏れない。"
  [dir]
  (try (.existsSync fs (str dir "/pyvenv.cfg")) (catch :default _ false)))

(defn- walk-files
  "repo 相対 path の列。max-depth / max-entries で必ず打ち切る（暴走防止）。

   打ち切りは黙って起きてはならない —— 打ち切られた repo は src/test が 0 に
   潰れ、『実装が無い repo』と見分けが付かなくなる。呼び出し側は必ず
   :truncated? と :depth-pruned? の**両方**を報告すること。

   打ち切りの原因は 2 つあり、以前は片方しか報告されていなかった:
   max-entries は :truncated? を立てるが、**max-depth の枝刈りは黙っていた**。
   実測 2026-08-18（cloud-itonami/app-analytics の cljs 移行中）——
   SvelteKit の `svelte/src/routes/xrpc/[...path]/+server.ts` は深さ 7 に居り、
   この walk からは最初から見えていなかった。その repo の src は 0 と測られ、
   『実装が無い』と区別が付かなかった。docstring はこの約束を既に書いていたが、
   守られていたのは 2 経路のうち 1 つだけだった。"
  [root max-depth max-entries]
  (let [out (atom [])
        truncated? (atom false)
        depth-pruned? (atom false)]
    (letfn [(go [dir depth rel]
              (when-not @truncated?
                (if (> depth max-depth)
                  ;; ここに来た = max-depth より深い directory を walk しなかった。
                  ;; 中身は数えられていないので、そう言う。
                  (reset! depth-pruned? true)
                  (doseq [e (try (.readdirSync fs dir #js {:withFileTypes true})
                               (catch :default _ #js []))]
                  (when-not @truncated?
                    (let [nm (.-name e)
                          child (str dir "/" nm)
                          crel (if (= rel "") nm (str rel "/" nm))]
                      (if (.isDirectory e)
                        (when-not (or (contains? skip-dirs nm) (virtualenv? child))
                          (go child (inc depth) crel))
                        (do (swap! out conj crel)
                            (when (>= (count @out) max-entries) (reset! truncated? true))))))))))]
      (go root 0 ""))
    {:files @out :truncated? @truncated? :depth-pruned? @depth-pruned?}))

(defn- file-size [p] (try (.-size (.statSync fs p)) (catch :default _ 0)))

;; ---------------------------------------------------------------- deps.edn

(def local-root-re #":local/root\s+\"([^\"]+)\"")

(defn- normalize-dep
  "deps.edn の :local/root は repo からの相対 path（\"../../kotoba-lang/langchain\"）。
   orgs/<org>/<repo> 形へ正規化する。解決できないものは nil を返す（捏造しない）。"
  [repo-path rel]
  (let [abs (.normalize npath (.join npath (dr repo-path) rel))
        prefix (str (dr "orgs") "/")]
    (when (str/starts-with? abs prefix)
      (let [tail (subs abs (count prefix))
            segs (str/split tail #"/")]
        (when (= 2 (count segs)) (str "orgs/" tail))))))

(defn- parse-deps [repo-path deps-text]
  (when deps-text
    (let [locals (->> (re-seq local-root-re deps-text)
                      (map second)
                      distinct
                      (keep #(normalize-dep repo-path %))
                      vec)
          unresolved (->> (re-seq local-root-re deps-text)
                          (map second)
                          distinct
                          (remove #(normalize-dep repo-path %))
                          vec)
          mvn (count (re-seq #":mvn/version" deps-text))
          git (count (re-seq #":git/(?:sha|tag|url)" deps-text))]
      {:local locals :unresolved unresolved :mvn-count mvn :git-count git})))

;; ---------------------------------------------------------------- git

(defn- git-last-commit-iso [repo-abs]
  (try
    (-> (.execSync cp "git log -1 --format=%cI"
                   #js {:cwd repo-abs :encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]
                        :timeout 15000})
        str/trim
        (#(when-not (str/blank? %) %)))
    (catch :default _ nil)))

(defn- git-head-sha
  "The commit this row was measured FROM.

  Without it a row cannot be checked against the pin it is supposed to
  represent, and a checkout left behind its pin produces a confident row about
  a tree that no longer exists — the axes it reports are the axes of older
  work, so landed work reads as absent and the ranking sends the next round
  back to a repo that was already raised.

  The tick catches this today by comparing `:git/last-commit` against the
  checkout's current commit time, but only for the top candidates it ranks.
  Measured 2026-08-20: 26 of 1,835 cloud-itonami checkouts were behind their
  pin, and only the ones that happened to surface in the top 24 were caught.
  With the sha recorded, `evidence sha vs west pin` is one pass over the file."
  [repo-abs]
  (try
    (-> (.execSync cp "git rev-parse HEAD"
                   #js {:cwd repo-abs :encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]
                        :timeout 15000})
        str/trim
        (#(when-not (str/blank? %) %)))
    (catch :default _ nil)))

(defn- git-commit-count [repo-abs]
  (try
    (-> (.execSync cp "git rev-list --count HEAD"
                   #js {:cwd repo-abs :encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]
                        :timeout 15000})
        str/trim js/parseInt)
    (catch :default _ nil)))

;; ---------------------------------------------------------------- evidence

;; 計数対象の言語。**この集合の外で書かれた実装は、7 軸のどれにも現れない。**
(def src-ext #{"cljc" "cljs" "clj" "kotoba"})

;; プログラムのソースだと言い切れる拡張子（データ・文書・設定は入れない）。
;; **スコアには一切入らない。** これは「この instrument はこの repo を読めて
;; いない」と言うためだけの語彙で、:maturity/* はどれもこの集合を見ない。
(def foreign-code-ext
  #{"sol" "ts" "tsx" "js" "mjs" "cjs" "jsx" "py" "go" "rs" "rb" "java" "kt"
    "swift" "c" "h" "cpp" "hpp" "cc" "cs" "php" "ex" "exs" "erl" "scala" "hs"
    "lua" "zig"})

;; test だと名乗っている path。`uncounted-test` が使ってきた規約に、Foundry の
;; `Foo.t.sol`（test/ の外に置ける）を足したもの。
(def foreign-test-re
  #"(?i)(^|/)tests?/|_test\.|\.test\.|\.spec\.|\.t\.|(^|/)test_|(^|/)spec/")

(defn- ext-of [f] (let [i (.lastIndexOf f ".")] (when (pos? i) (subs f (inc i)))))
(defn- base-of [f] (last (str/split f #"/")))

;; actor discipline の構成要素。cloud-itonami の actor は langgraph StateGraph +
;; 独立 Governor + append-only ledger のパターン（skill build-actor）で、
;; そのどの部品が実在するかを file 名から測る。名前は itonami-fleet-audit.cljs の
;; :components と同じ語彙に合わせてある。
(def component-patterns
  {:operation  #"(?i)(^|/)operation[s]?\.(cljc|cljs|clj)$"
   :governor   #"(?i)(^|/)(governor|policy)\.(cljc|cljs|clj)$"
   :store      #"(?i)(^|/)store\.(cljc|cljs|clj)$"
   :phase      #"(?i)(^|/)phase\.(cljc|cljs|clj)$"
   :sim        #"(?i)(^|/)sim\.(cljc|cljs|clj)$"
   :facts      #"(?i)(^|/)facts\.(cljc|cljs|clj)$"
   :ledger     #"(?i)(^|/)ledger\.(cljc|cljs|clj)$"})

(defn- collect [org repo]
  (let [rel  (str "orgs/" org "/" repo)
        root (dr rel)]
    (if-not (exists? root)
      {:repo/path rel :repo/org org :repo/name repo :repo/present? false}
      (let [{:keys [files truncated? depth-pruned?]} (walk-files root 6 6000)
            ;; Source roots: the repo root and every first-level dir with its
            ;; own deps.edn (clj/src, lg-clj/src …). Same rule as
            ;; scripts/repo-taxonomy.cljs; measured 2026-08-23 on
            ;; ai-gftd-dougaka, whose 43 KB of src and 13 KB of tests under
            ;; clj/ scored substrate 0 / test 0 while :uncounted/* held them.
            roots       (into [""] (keep #(when-let [[_ d] (re-matches #"([^/]+)/deps\.edn" %)] (str d "/")) files))
            under?      (fn [sub] (let [ps (map #(str % sub) roots)]
                                    (fn [f] (boolean (some #(str/starts-with? f %) ps)))))
            src-files   (filterv #(and ((under? "src/") %) (src-ext (ext-of %))) files)
            test-files  (filterv #(and ((under? "test/") %) (src-ext (ext-of %))) files)
            kotoba-files (filterv #(= "kotoba" (ext-of %)) files)
            src-bytes   (reduce + 0 (map #(file-size (str root "/" %)) src-files))
            test-bytes  (reduce + 0 (map #(file-size (str root "/" %)) test-files))
            has-file?   (fn [nm] (boolean (some #(= (base-of %) nm) files)))
            has-path?   (fn [re] (boolean (some #(re-find re %) files)))
            components  (into #{} (keep (fn [[k re]] (when (some #(re-find re %) files) k))
                                        component-patterns))
            readme      (slurp* (str root "/README.md"))
            deps-text   (slurp* (str root "/deps.edn"))
            deps        (parse-deps rel deps-text)
            ;; facts.cljc / data/ 内の実 URL 引用数。「実データを引いているか」の直接証拠。
            fact-files  (filterv #(re-find #"(?i)(^|/)(facts|catalog|jurisdictions?)\.(cljc|cljs|clj|edn)$" %) files)
            data-files  (filterv #(str/starts-with? % "data/") files)
            citation-n  (reduce + 0
                                (map (fn [f]
                                       (let [t (slurp* (str root "/" f))]
                                         (if t (count (re-seq #"https?://" t)) 0)))
                                     (take 40 (concat fact-files (take 20 data-files)))))
            wf-files    (filterv #(re-find #"^\.github/workflows/" %) files)
            demo-files  (filterv #(re-find #"(?i)^(docs|samples|public|demo)/.*\.html$" %) files)
            adr-files   (filterv #(re-find #"(?i)^(docs/adr|90-docs/adr)/" %) files)
            ;; ── 見えていない内容（スコアには入れない。ADR-2608052000）─────────
            ;;
            ;; 上の 4 つは内容をファイル名の規約で同定している。fleet はその規約に
            ;; 従っていないので、実在するのに 0 と測られる repo が大量にある
            ;; （実測: src/test で 387、ingest で 648、README.edn で 316）。
            ;;
            ;; **ここで測るのは報告用の別キーで、:maturity/* は 1bp も動かさない。**
            ;; 測り方を変えれば数百 repo のスコアが一度に動き、mean-own と全 repo の
            ;; fleet-gain が変わる —— それはオーナー判断であって scan の判断ではない。
            ;; 一方「見えていない」ことを報告しないのは、未測定を 0 として蓄積する
            ;; ことなので、報告だけは今する。
            uncounted-src   (filterv #(and (not ((under? "src/") %))
                                           (not ((under? "test/") %))
                                           (re-find #"(^|/)src/" %)
                                           (src-ext (ext-of %)))
                                     files)
            uncounted-test  (filterv #(and (not ((under? "test/") %))
                                           (src-ext (ext-of %))
                                           (re-find #"(?i)(^|/)tests?/|_test\.|\.test\.|\.spec\.|(^|/)test_" %))
                                     files)
            ;; README.md 以外の README（.edn / .rst / 拡張子なし）
            uncounted-readme (filterv #(re-find #"(?i)^readme\.(edn|rst|txt|org)$" %) files)
            ;; ── 計数外の言語で書かれた実装（報告のみ）───────────────────────
            ;;
            ;; 上の uncounted-src / uncounted-test は **src-ext を要求する**。
            ;; だから Clojure を 1 行も持たない repo は「計数外の src も 0」と
            ;; 報告する —— 実装が空の repo と、この instrument が読めない言語で
            ;; 書かれた repo が、**counted も uncounted も全部 0** という同じ顔で
            ;; 並ぶ。CLAUDE.md 8 問の 1 問目そのもの（測れなかった検査が、測って
            ;; 問題が無かった検査と同じ値を返す）。
            ;;
            ;; 実測 2026-09-09: counted と uncounted の src/test が 4 つとも 0 で
            ;; ファイルが 10 件以上ある repo は 95 本あり、そのうち **74 本が実際に
            ;; 非 Clojure の実装を持ち、53 本は test まで持っていた**。tick は
            ;; その 53 本へ「axis-test 0bp → +2000bp の伸びしろ」と言い続けていた。
            ;;
            ;; **vendored な依存は数えない。** Foundry は `lib/` に、Go は
            ;; `vendor/` に依存を置く。`vendor` は walk が既に飛ばすが `lib` は
            ;; 飛ばさない —— そして `lib/` を名前で一律に飛ばすと Elixir と Ruby の
            ;; **一次ソース**が消える。だから名前ではなく `.gitmodules` が
            ;; submodule として宣言した path だけを外す。
            submodule-paths (let [t (slurp* (str root "/.gitmodules"))]
                              (if t
                                (vec (keep (fn [[_ p]] (when (seq p) (str (str/trim p) "/")))
                                           (re-seq #"(?m)^\s*path\s*=\s*(.+)$" t)))
                                []))
            vendored?   (fn [f] (boolean (some #(str/starts-with? f %) submodule-paths)))
            foreign-all (filterv #(and (foreign-code-ext (ext-of %)) (not (vendored? %))) files)
            foreign-test (filterv #(re-find foreign-test-re %) foreign-all)
            foreign-src  (filterv #(not (re-find foreign-test-re %)) foreign-all)
            ;; 計数対象外の宣言ファイル。URL は distinct で数える —— 同じ URL が
            ;; 20 回出てくるのは 20 の出典ではない。上限は既存の計数と同じ発想で
            ;; 25 ファイル（scan は 1,936 repo を歩くので、ここは安くなければならない）
            other-decl  (filterv #(and (re-find #"(?i)\.(jsonld|json|edn|md|ttl|yaml|yml)$" %)
                                       (not (str/starts-with? % "data/"))
                                       (not (re-find #"(?i)(^|/)(facts|catalog|jurisdictions?)\.(cljc|cljs|clj|edn)$" %))
                                       (not (re-find #"(?i)^(package|package-lock|tsconfig)" %)))
                                 files)
            uncounted-urls (count (into #{}
                                        (mapcat (fn [f]
                                                  (let [t (slurp* (str root "/" f))]
                                                    (if t (re-seq #"https?://[^\"'\s)>,]+" t) [])))
                                                (take 25 other-decl))))]
        {:repo/path rel
         :repo/org org
         :repo/name repo
         :repo/present? true
         :repo/files-truncated? truncated?
         ;; max-depth の枝刈り。src/test が 0 でも「無い」ではなく「見ていない」
         :repo/files-depth-pruned? depth-pruned?
         :repo/file-count (count files)
         ;; --- substrate
         :src/file-count (count src-files)
         :src/bytes src-bytes
         :test/file-count (count test-files)
         :test/bytes test-bytes
         :kotoba/file-count (count kotoba-files)
         ;; --- actor discipline
         :component/present (vec (sort components))
         :component/count (count components)
         ;; --- real-world ingest
         :ingest/citation-count citation-n
         ;; --- 見えていない内容（報告のみ。どの :maturity/* にも入らない）
         :uncounted/src-file-count (count uncounted-src)
         :uncounted/src-bytes (reduce + 0 (map #(file-size (str root "/" %)) uncounted-src))
         :uncounted/test-file-count (count uncounted-test)
         :uncounted/test-bytes (reduce + 0 (map #(file-size (str root "/" %)) uncounted-test))
         :uncounted/readme-file-count (count uncounted-readme)
         ;; 計数外の言語で書かれた実装。**報告のみ。どの :maturity/* も読まない。**
         ;; counted も uncounted も 0 の repo が「空」なのか「読めなかった」のかは、
         ;; この 2 つを見るまで区別が付かない。
         :uncounted/foreign-src-file-count (count foreign-src)
         :uncounted/foreign-src-bytes (reduce + 0 (map #(file-size (str root "/" %)) foreign-src))
         :uncounted/foreign-test-file-count (count foreign-test)
         :uncounted/foreign-test-bytes (reduce + 0 (map #(file-size (str root "/" %)) foreign-test))
         :uncounted/foreign-langs (into (sorted-map) (frequencies (keep ext-of foreign-all)))
         :uncounted/url-count uncounted-urls
         :ingest/fact-file-count (count fact-files)
         :ingest/data-file-count (count data-files)
         ;; --- docs
         :doc/readme-bytes (if readme (count readme) 0)
         :doc/adr-count (count adr-files)
         :doc/has-operator-quickstart? (has-path? #"(?i)operator-quickstart")
         :doc/has-business-model? (has-path? #"(?i)business-model")
         :doc/has-pricing? (has-path? #"(?i)pricing")
         ;; --- product surface
         :surface/demo-file-count (count demo-files)
         :surface/workflow-count (count wf-files)
         :surface/has-cron-workflow?
         (boolean (some (fn [f] (when-let [t (slurp* (str root "/" f))]
                                  (re-find #"(?m)^\s*schedule:" t)))
                        wf-files))
         ;; --- deps
         :deps/has-deps-edn? (some? deps-text)
         :deps/local (vec (:local deps))
         :deps/local-count (count (:local deps))
         :deps/unresolved (vec (:unresolved deps))
         :deps/mvn-count (or (:mvn-count deps) 0)
         :deps/git-count (or (:git-count deps) 0)
         ;; --- markers
         :repo/tombstone? (or (has-file? "NOT-MIGRATED") (has-file? "MOVED"))
         :repo/has-actor-edn? (has-file? "actor.edn")
         :repo/has-blueprint? (has-file? "blueprint.edn")
         ;; --- vcs
         :git/last-commit (git-last-commit-iso root)
         :git/head-sha (git-head-sha root)
         :git/commit-count (git-commit-count root)}))))

;; ---------------------------------------------------------------- render

(defn- render-val [v]
  (cond
    (nil? v) "nil"
    (string? v) (pr-str v)
    (boolean? v) (str v)
    (number? v) (str v)
    (keyword? v) (str v)
    (vector? v) (str "[" (str/join " " (map render-val v)) "]")
    :else (pr-str v)))

(defn- render-entity [m]
  (str "{" (str/join ", " (map (fn [[k v]] (str k " " (render-val v))) m)) "}"))

;; ---------------------------------------------------------------- main

(defn -main []
  (let [west-text (or (slurp* (dr "manifest" "west.yml"))
                      (throw (ex-info "west.yml not found" {:at (dr "manifest" "west.yml")})))
        projects (west-projects west-text)
        target-orgs (set (:orgs opts))
        primary (->> projects
                     (keep :path)
                     (filter #(some (fn [o] (str/starts-with? % (str "orgs/" o "/"))) target-orgs))
                     sort vec)
        primary (if (:limit opts) (vec (take (:limit opts) primary)) primary)
        _ (println (str "scan: " (count primary) " primary repos under " (pr-str (vec target-orgs))
                        " (west.yml has " (count projects) " projects)"))
        t0 (.now js/Date)
        prim-ev (vec (map-indexed
                      (fn [i p]
                        (when (zero? (mod i 200))
                          (println (str "  .. " i "/" (count primary) " " p)))
                        (let [[_ org repo] (str/split p #"/")]
                          (collect org repo)))
                      primary))
        ;; 依存先（多くは kotoba-lang の substrate library）も node として測る。
        ;; leverage の計算は substrate の成熟度が要るので、ここを落とすと
        ;; 「hub が何であるか」を測らずに hub を語ることになる。
        dep-targets (->> prim-ev
                         (mapcat :deps/local)
                         distinct
                         (remove (fn [p] (some (fn [o] (str/starts-with? p (str "orgs/" o "/"))) target-orgs)))
                         sort vec)
        _ (println (str "scan: " (count dep-targets) " cross-org dependency targets"))
        dep-ev (vec (map-indexed
                     (fn [i p]
                       (when (zero? (mod i 100)) (println (str "  dep .. " i "/" (count dep-targets))))
                       (let [[_ org repo] (str/split p #"/")]
                         (assoc (collect org repo) :repo/substrate-only? true)))
                     dep-targets))
        all (into prim-ev dep-ev)
        elapsed (- (.now js/Date) t0)
        out-path ((.-resolve npath) (.cwd js/process) (:out opts))
        header (str ";; manifest/itonami-maturity-evidence.edn — GENERATED, DO NOT HAND-EDIT.\n"
                    ";; Regenerate:\n"
                    ";;   nbb scripts/itonami-maturity-scan.cljs --data-root <superproject main checkout>\n"
                    ";;\n"
                    ";; ADR-2608052000。cloud-itonami 全 repo（+ その依存先 substrate repo）の\n"
                    ";; 成熟度・依存関係の一次証拠。**観測のみ**でスコアを含まない — スコア化と\n"
                    ";; system dynamics は scripts/itonami-maturity-dynamics.cljs が別途行う。\n"
                    ";;\n"
                    ";; 各 map は DataScript/Datomic に直接 transact できる（:db/id 無し = tempid）。\n"
                    ";; :repo/path で manifest/repo-taxonomy.edn（:source/dataset \"repo-taxonomy\"）と\n"
                    ";; join できる。\n"
                    ";;\n"
                    ";; nil は「未測定」であって「値が無い/0」ではない。\n"
                    ";;\n"
                    ";; Scanned: " (.toISOString (js/Date.)) "\n"
                    ";; data-root: " data-root "\n"
                    ";; primary=" (count prim-ev) " substrate=" (count dep-ev)
                    " elapsed-ms=" elapsed "\n\n")]
    (.writeFileSync fs out-path
                    (str header "[\n " (str/join "\n " (map render-entity all)) "\n]\n"))
    (println (str "wrote " out-path " (" (count all) " entities, " elapsed "ms)"))
    (println (str "  present=" (count (filter :repo/present? all))
                  " absent=" (count (remove :repo/present? all))
                  " with-src=" (count (filter #(pos? (:src/file-count % 0)) all))
                  " with-tests=" (count (filter #(pos? (:test/file-count % 0)) all))
                  " with-kotoba=" (count (filter #(pos? (:kotoba/file-count % 0)) all))))
    ;; 打ち切った repo は src/test が 0 に潰れており、『実装が無い repo』と
    ;; 区別が付かない。黙って通すと、その 0 がそのまま成熟度スコアになる。
    (let [tr (filter :repo/files-truncated? all)]
      (when (seq tr)
        (binding [*print-fn* *print-err-fn*]
          (println (str "WARNING: " (count tr)
                        " repo で file walk を打ち切った（entry 上限）—— これらの src/test は"
                        " 0 に潰れており、実測値ではない:"))
          (doseq [e tr]
            (println (str "  " (:repo/path e) " (files>=" (:repo/file-count e) ")"))))))
    ;; 深さの枝刈りは以前は黙っていた。深い tree（SvelteKit の
    ;; svelte/src/routes/xrpc/[...path]/ は深さ 7）を持つ repo は、その中身を
    ;; 一度も見られないまま 0 と測られる。件数だけでも出す。
    (let [dp (filter :repo/files-depth-pruned? all)]
      (when (seq dp)
        (binding [*print-fn* *print-err-fn*]
          (println (str "WARNING: " (count dp)
                        " repo で max-depth より深い directory を walk しなかった"
                        " —— そこにある src/test は 0 と数えられている（不在ではなく未観測）"))
          (doseq [e (take 20 dp)]
            (println (str "  " (:repo/path e))))
          (when (> (count dp) 20)
            (println (str "  … 他 " (- (count dp) 20) " 件"))))))))

(-main)
