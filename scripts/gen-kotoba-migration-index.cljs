#!/usr/bin/env nbb
;; `.kotoba` を持つ repo が今どの段階に居るかの索引を生成する。
;;
;; ## なぜ要るのか
;;
;; ADR-2608120200 は 23 repo を手で測って「production で core を実行しているのは
;; 4 つ」と書いた。**同じ日に数えたら、`.kotoba` を持つ checkout 済み repo は
;; 1,183 あった。** 記録は移行面の 2% しか覆っておらず、しかも散文なので
;; query もできず、次に誰かが測り直すまで古びていく。
;;
;; 手で書いた索引は必ず腐る、というのは concept / surface 索引が既に学んだことで、
;; ここも同じ結論になる。**移行の状態は観測から生成する。**
;;
;; ## 何を観測するか（内容ではなく、在り方）
;;
;; この索引は gate の質を採点しない。答えるのは 3 つだけ:
;;
;;   1. この repo の `.kotoba` はどういう役割か（決定核 / schema 宣言 / fixture）
;;   2. コンパイル済みの成果物を checked in しているか
;;   3. **その成果物が source から出たことを、何かが検査しているか**
;;
;; 3 は今日この workspace で 3 回続けて見つかった欠陥の形そのもので
;; （com-cloudflare の出荷 KIR 9 本中 1 本、provider の registry 142 行中 8 行、
;; inga の provenance record 2 本中 0 本）、**成果物を checked in する repo だけが
;; 持つ**。索引はこれを一覧にする。
;;
;; ## カバレッジを偽らない
;;
;; checkout されていない west project は見えない。**その数を数えて記録する** ——
;; 索引に無いことが「移行していない」の証拠に使われるので。
;;
;; 再生成: nbb scripts/gen-kotoba-migration-index.cljs
;;         nbb scripts/gen-kotoba-migration-index.cljs --check   (差分があれば非0)

(ns gen-kotoba-migration-index
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def root (.cwd js/process))

(defn out-file
  "既定は cwd 配下。`--out <dir>` は superproject を走査しつつ別の checkout
  （worktree）へ書きたいときに使う —— 共有 checkout に生成物を落とさないため。"
  [args]
  (let [base (or (second (drop-while #(not= "--out" %) args)) root)]
    (path/join base "90-docs" "kotoba-migration" "kotoba-migration.datoms.edn")))

(defn- read-safe [f]
  (try (fs/readFileSync f "utf8") (catch :default _ nil)))

(defn- git
  "`git -C dir args...` の stdout。失敗は nil（repo が壊れていても索引は続ける）。"
  [dir & args]
  (try
    (let [r (cp/spawnSync "git" (clj->js (concat ["-C" dir] args))
                          #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)})]
      (when (zero? (.-status r)) (.-stdout r)))
    (catch :default _ nil)))

(defn- flag [args name default]
  (or (second (drop-while #(not= name %) args)) default))

(defn vocabulary
  "語彙が読めなければ**止まる**。空 map で続けると `:unclassified` だらけの索引が
  生成され、それが『分類できなかった』ではなく『そういう状態だ』として読まれる。"
  [args]
  (let [f (path/join (flag args "--vocab" root) "manifest"
                     "kotoba-migration-vocabulary.edn")
        raw (read-safe f)]
    (when-not raw
      (println "vocabulary not found:" f)
      (js/process.exit 2))
    (edn/read-string raw)))

(defn west-pins
  "west.yml の path → revision。

   **索引は checkout ではなく pin を見る。** ローカルの checkout は pin より
   遅れていることがあり（実測 2026-08-12: inga の checkout は同じ日に main へ
   着地した commit を持っていなかった）、そこを見た索引は fleet の状態ではなく
   このマシンの状態を語ることになる。pin が手元に無い repo だけ checkout に
   落ち、その事実を entity に残す。"
  []
  (let [raw (or (read-safe (path/join root "manifest" "west.yml")) "")]
    (into {}
          (map (fn [[_ rev p]] [p rev]))
          (re-seq #"(?m)^\s+revision:\s+(\S+)\n\s+path:\s+(\S+)\s*$" raw))))

(defn- dirs [p]
  (try (->> (fs/readdirSync p #js {:withFileTypes true})
            (filter #(.isDirectory %))
            (map #(.-name %))
            (remove #(str/starts-with? % ".")))
       (catch :default _ [])))

(defn repo-dirs
  "orgs/<org>/<repo> のうち git checkout があるもの。"
  []
  (let [orgs-dir (path/join root "orgs")]
    (for [org (sort (dirs orgs-dir))
          repo (sort (dirs (path/join orgs-dir org)))
          :let [rel (str "orgs/" org "/" repo)]
          :when (fs/existsSync (path/join root rel ".git"))]
      {:rel rel :org org :name repo :abs (path/join root rel)})))

(defn tracked-files
  "pin の tree。pin が手元に無ければ checkout。どちらを見たかを返す。"
  [abs rev]
  (let [at-pin (when rev (git abs "ls-tree" "-r" "--name-only" rev))]
    (if at-pin
      {:files (->> at-pin str/split-lines (remove str/blank?)) :observed :pin}
      {:files (->> (or (git abs "ls-files") "") str/split-lines (remove str/blank?))
       :observed :checkout})))

(defn- top-dir [p]
  (let [i (str/index-of p "/")]
    (if i (subs p 0 i) "")))

(defn- artifact-siblings
  "`<base>.kotoba` の隣に在る、コンパイル済み成果物として認める tracked file。

   同じディレクトリ・同じ base 名だけを見る。別ディレクトリに同名の成果物が
   在る形（provider の `src/x.kotoba` → `../x-v1.wasm`）はここでは数えない ——
   索引が推測を始めると、数えたものが何なのか誰にも言えなくなる。"
  [files exts]
  (let [by-dir-base (set files)]
    (for [f files
          :when (str/ends-with? f ".kotoba")
          :let [base (subs f 0 (- (count f) (count ".kotoba")))]
          ext exts
          :when (contains? by-dir-base (str base ext))]
      (str base ext))))

(def ^:private execution-re
  ;; src から core を実行する経路。`kotoba.kir` / KIR インタプリタ / kototama /
  ;; ブラウザ・node の WebAssembly instantiate / compiler 直呼び。
  #"kotoba\.kir|ir/execute|kototama\.tender|instantiateKotoba|compile-project|compile-source|WebAssembly\.instantiate")

(defn- exec-hits
  "実行経路に触れている tracked file を test/ 配下とそれ以外に分ける。

   1 repo につき git grep 1 回。内容は読まず、当たったファイル名だけを使う。"
  [abs rev observed code-exts]
  (let [code? (fn [f] (some #(str/ends-with? f %) code-exts))
        out (or (if (= :pin observed)
                  (git abs "grep" "-lIE" (.-source execution-re) rev "--" ".")
                  (git abs "grep" "-lIE" (.-source execution-re) "--" "."))
                "")
        strip #(let [i (str/index-of % ":")]
                 (if (and i (= :pin observed)) (subs % (inc i)) %))
        ;; 散文は実行ではない。README が `compile-source` と書いているだけの repo を
        ;; 「host が core を実行している」と数えない。
        hit (->> (str/split-lines out) (remove str/blank?) (map strip) (filter code?))
        test? #(or (str/starts-with? % "test/") (str/includes? % "/test/")
                   (str/includes? % "_test.") (str/includes? % "-test."))]
    {:src (remove test? hit) :test (filter test? hit)}))

(defn- provenance-covered
  "provenance record を持つ成果物の数。

   **repo に record が 1 本でもあれば全部検査されている、とは数えない。** 実測
   2026-08-12: aiueos は `.o` を 57 本 checked in していて `.o.provenance.edn` は
   1 本だった。repo 単位の真偽値はそれを『検査済み』と読ませてしまう。"
  [artifacts files suffix]
  (let [present (set files)]
    (count (filter #(contains? present (str % suffix)) artifacts))))

(defn- test-name-signal?
  "`drift` / `provenance` / `digest` / `registry` を名前に持つ test file が在るか。

   **在ることしか言えない。** その test が何を検査しているかは読まない —— 索引は
   所在を答えるものであって gate の質を採点するものではない。"
  [files pattern]
  (let [pat (re-pattern pattern)]
    (boolean (some #(and (re-find pat %) (str/includes? % "test")) files))))

(defn- role-of [files vocab]
  (let [roles (->> files
                   (filter #(str/ends-with? % ".kotoba"))
                   (map #(get-in vocab [:role-by-dir (top-dir %) :role]))
                   (remove nil?)
                   set)]
    (cond
      (empty? roles) :unclassified
      ;; 決定核が 1 本でもあれば repo はその面を持つ。fixture だけの repo と
      ;; 混ぜない。
      (contains? roles :decision-core) :decision-core
      (= 1 (count roles)) (first roles)
      :else :mixed)))

(defn- stage-of [{:keys [artifacts src-exec test-exec verified?]}]
  (cond
    (seq src-exec) :stage/host-executes
    (and (pos? artifacts) verified?) :stage/artifact-verified
    (pos? artifacts) :stage/artifact-unverified
    (seq test-exec) :stage/gated-in-test
    :else :stage/source-only))

(defn scan [args]
  (let [vocab (vocabulary args)
        pins (west-pins)
        exts (:artifact-extensions vocab)
        repos (repo-dirs)
        rows (for [{:keys [rel org name abs]} repos
                   :let [rev (get pins rel)
                         {:keys [files observed]} (tracked-files abs rev)
                         cores (filter #(str/ends-with? % ".kotoba") files)]
                   :when (seq cores)]
               (let [artifacts (distinct (artifact-siblings files exts))
                     ;; 同じディレクトリの兄弟だけを数える規則は、出荷物を別の
                     ;; ディレクトリに置く形（com-cloudflare の
                     ;; `resources/cloudflare/oracle/*.kir.edn`、provider の
                     ;; `wasm-packages/*.wasm`）を 0 と数える。stage の判定は
                     ;; 厳しい方を使い、緩い方の数も残す —— **0 と出た repo が
                     ;; 成果物を持たないとは限らない**ことを索引の中に置くため。
                     anywhere (count (filter (fn [f] (some #(str/ends-with? f %) exts))
                                             files))
                     {:keys [src test]} (exec-hits abs rev observed
                                                   (:code-extensions vocab))
                     prov-suffix (get-in vocab [:artifact-verification-signals
                                                :provenance-record :suffix])
                     covered (provenance-covered artifacts files prov-suffix)
                     named-test? (test-name-signal?
                                  files (get-in vocab [:artifact-verification-signals
                                                       :test-name-match :pattern]))
                     ;; 全部の成果物が record を持つか、名前で分かる gate が在るか。
                     verified? (or (and (pos? (count artifacts))
                                        (= covered (count artifacts)))
                                   named-test?)]
                 {:repo rel :org org :name name
                  :registered? (some? rev)
                  :observed observed
                  :role (role-of files vocab)
                  :cores (count cores)
                  :artifacts (count artifacts)
                  :artifacts-anywhere anywhere
                  :artifacts-with-provenance covered
                  :named-gate? named-test?
                  :src-exec (count src)
                  :test-exec (count test)
                  :stage (stage-of {:artifacts (count artifacts)
                                    :src-exec src :test-exec test
                                    :verified? verified?})}))]
    {:rows (vec rows)
     :repos-checked-out (count repos)
     :west-projects (count pins)}))

(defn ->datoms [{:keys [rows repos-checked-out west-projects]}]
  (conj
   (vec (map-indexed
         (fn [i r]
           {:db/id (- (inc i))
            :kotoba/repo (:repo r)
            :kotoba/org (:org r)
            :kotoba/name (:name r)
            :kotoba/registered? (:registered? r)
            :kotoba/observed-at (:observed r)
            :kotoba/role (:role r)
            :kotoba/cores (:cores r)
            :kotoba/artifacts (:artifacts r)
            :kotoba/artifacts-anywhere (:artifacts-anywhere r)
            :kotoba/artifacts-with-provenance (:artifacts-with-provenance r)
            :kotoba/named-artifact-gate? (:named-gate? r)
            :kotoba/src-execution-files (:src-exec r)
            :kotoba/test-execution-files (:test-exec r)
            :kotoba/stage (:stage r)
            :source/dataset "kotoba-migration"})
         rows))
   ;; カバレッジは entity として持つ。header コメントは query した人に届かない。
   {:db/id (- (inc (count rows)))
    :kotoba/coverage "kotoba-migration-index"
    :kotoba/west-projects west-projects
    :kotoba/repos-checked-out repos-checked-out
    :kotoba/repos-not-checked-out (max 0 (- west-projects repos-checked-out))
    :kotoba/repos-with-kotoba (count rows)
    :source/dataset "kotoba-migration"}))

(defn render [{:keys [rows]} datoms]
  (let [by (fn [k] (frequencies (map k rows)))
        unverified (filter #(= :stage/artifact-unverified (:stage %)) rows)]
    (str ";; .kotoba 移行の索引 —— **生成物。手で編集しない**\n"
         ";; 再生成: nbb scripts/gen-kotoba-migration-index.cljs\n"
         ";; 語彙:   manifest/kotoba-migration-vocabulary.edn（こちらは手書き）\n"
         ";; 設計:   ADR-2608120200 / ADR-2608121500\n;;\n"
         ";; 2026-08-12、ADR-2608120200 は 23 repo を手で測った。同じ日に数えたら\n"
         ";; `.kotoba` を持つ checkout 済み repo は " (count rows) " あった。\n"
         ";; **手で書いた移行状況は、移行が進むより速く古びる。**\n;;\n"
         ";; role: " (pr-str (into (sorted-map) (map (fn [[k v]] [k v]) (by :role)))) "\n"
         ";; stage: " (pr-str (into (sorted-map) (map (fn [[k v]] [(str k) v]) (by :stage)))) "\n;;\n"
         ";; ⚠ :stage/artifact-unverified = コンパイル済み成果物を checked in して\n"
         ";;   いるのに、それが source から出たことを検査するものが無い repo。\n"
         ";;   現在 " (count unverified) " 件。この形は今日 3 回続けて実際の欠陥だった。\n;;\n"
         ";; ⚠ カバレッジ: checkout のある repo だけを見る（:kotoba/coverage entity）。\n"
         ";;   **索引に無いことは移行していないことの証拠にならない。**\n"
         (with-out-str (pp/pprint datoms)))))

(defn -main [& args]
  (let [scanned (scan args)
        datoms (->datoms scanned)
        text (render scanned datoms)
        out-file (out-file args)]
    (if (some #{"--check"} args)
      (let [current (read-safe out-file)]
        (if (= current text)
          (println "kotoba-migration index: FRESH")
          (do (println "kotoba-migration index: STALE — run nbb scripts/gen-kotoba-migration-index.cljs")
              (js/process.exit 1))))
      (do (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
          (fs/writeFileSync out-file text)
          (println "wrote" out-file
                   "—" (count (:rows scanned)) "repos,"
                   (count (filter #(= :stage/artifact-unverified (:stage %)) (:rows scanned)))
                   "with an unverified artifact")))))

(apply -main *command-line-args*)
