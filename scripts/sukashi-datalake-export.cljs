#!/usr/bin/env nbb
;; sukashi-datalake-export.cljs — 広告サプライチェーンの観測グラフを datalake に
;; 載せられる表に落とす。
;;
;; ## 何を読むか
;;
;; 読むのは **正本の EDN**（`cloud-itonami/sukashi` の
;; `data/sukashi.datoms.kotoba.edn`、observatory が書く append-only な EAVT 取引ログ）。
;; この script は何も取りに行かない —— 取得と合成は observatory の仕事で、ここは
;; 形を変えるだけ。表を全部消してもこれで作り直せる（ADR-2608039000 の削除・再構築
;; テスト）。
;;
;; ## この射影が守らなければならない不変条件は 1 つ
;;
;; **すべての行が出所の印を運ぶ。** 実測（2026-08-29）:
;;
;;   :adtech/sourcing        :representative 10,773 / :synthesized 1,710 / :authoritative 171
;;   :adfraud.signal/sourcing :synthesized 2,052（全件）+ :non-adjudicating true（全件）
;;   :adauth.edge/sourcing   :authoritative 4,446 / :synthesized 342
;;
;; さらに **ログは同じ 3 トランザクションの追記の繰り返し**である（171 tx / 3 distinct
;; :tx/id、datom 集合は同一、as-of は 20260609–20260611）。observatory は毎周走って
;; 出力を伸ばすが、伸びているのは複製であって観測ではない —— 名簿の
;; `:produces-datoms` はその複製で満たされる。`distinct_transactions` /
;; `duplicate_transactions` を manifest に出すのはこのため。
;;
;; つまりこのグラフの**大半は実測ではなく、境界を切った例示のスライス**であり、
;; fraud signal は 1 件残らず合成で、しかも自ら「裁定しない」と宣言している。
;; `sourcing` / `derived` / `non_adjudicating` の列を落とした射影は、例示を
;; 市場の実測として、合成信号を観測として読ませる。**だから印を持たない行が
;; 1 つでもある表は export しない**（exit 2）—— 印が無いのは「印が要らない」では
;; なく「測っていない」。
;;
;; ## append-only ログの畳み方
;;
;; 171 tx / 164,502 datom に対して entity は 164 しかない —— 同じ entity が tx ごとに
;; 述べ直されている。tx 順で後勝ち（`:db/add` のみ。retract は実測で 0 件だが、
;; 現れたら落とす —— 黙って add として扱わない）。
;;
;; ## 表は attribute の名前空間で割れる
;;
;; 実測: 164 entity のうち **2 つ以上の名前空間の attribute を持つものは 0**。
;; だから名前空間ごとに 1 表にでき、列は attribute 名から導出できる（手で列を
;; 並べた表は、上流が attribute を足した日から静かに古くなる）。
;;
;;     nbb scripts/sukashi-datalake-export.cljs [--repo <path>] [--out-dir <dir>]
;;     python3 scripts/datalake-sync.py --spec /tmp/sukashi.spec.json --in-dir /tmp
;;
;; exit: 0 載せられる JSON を書いた / 2 REFUSED（読めなかった・印が無かった）

(ns sukashi-datalake-export
  (:require [cljs.reader :as edn]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            ["node:child_process" :as cp]))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn- flag [n] (let [i (.indexOf (clj->js argv) n)]
                  (when (and (>= i 0) (< (inc i) (count argv))) (nth argv (inc i)))))

(def repo (or (flag "--repo")
              "/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-itonami/sukashi"))
(def out-dir (or (flag "--out-dir") "/tmp"))
(def log-path (path/join repo "data" "sukashi.datoms.kotoba.edn"))

(defn die! [code msg] (js/console.error msg) (.exit js/process code))

(def table-of
  "Attribute namespace -> table. A namespace absent here is a namespace this
  projection has never seen; the export refuses rather than dropping it,
  because a silently unprojected namespace is indistinguishable from an
  upstream that stopped producing one."
  {"adtech" "sukashi_adtech"
   "adauth.edge" "sukashi_auth_edge"
   "adsupply" "sukashi_supply"
   "adfraud.signal" "sukashi_fraud_signal"
   "adcreative" "sukashi_creative"
   "addelivery.edge" "sukashi_delivery_edge"
   "adfraud" "sukashi_fraud_cluster"})

(def provenance-columns
  "One of these must be present on every row of a table. `sourcing` says where
  a fact came from; `derived` says it was computed from other rows here."
  #{"sourcing" "derived"})

(defn column [attr] (str/replace (name attr) "-" "_"))

(defn scalar [v]
  (cond (nil? v) nil
        (keyword? v) (name v)
        (boolean? v) (str v)
        (number? v) v
        (string? v) v
        ;; Never pr-str an unexpected shape into a column: it would land looking
        ;; like data and read as a bug forever.
        :else ::unsupported))

(defn read-log []
  (when-not (fs/existsSync log-path)
    (die! 2 (str "REFUSED: no datom log at " log-path
                 "\nThe observatory writes this file to a gitignored path, so an absent log"
                 "\nmeans it has not run on this machine — not that the ad graph is empty.")))
  (let [lines (->> (str/split-lines (fs/readFileSync log-path "utf8"))
                   (remove #(or (str/blank? %) (str/starts-with? (str/trim %) ";;"))))]
    (when (empty? lines)
      (die! 2 "REFUSED: the datom log holds no transactions. Zero transactions is an unread log."))
    (mapv edn/read-string lines)))

(defn fold
  "tx-ordered last-write-wins -> {entity {attr value}}. Refuses a retract
  rather than treating it as an assert."
  [txs]
  (reduce (fn [acc tx]
            (reduce (fn [m [op e a v]]
                      (when-not (= :db/add op)
                        (die! 2 (str "REFUSED: unsupported op " op " on " e "/" a
                                     " — this projection folds asserts only, and treating a"
                                     " retract as an assert would republish a withdrawn fact.")))
                      (assoc-in m [e a] v))
                    acc
                    (:tx/datoms tx)))
          {} (sort-by :tx/id txs)))

(defn rows-by-table [folded]
  (reduce
   (fn [acc [e attrs]]
     (let [nss (distinct (map namespace (keys attrs)))]
       (when (> (count nss) 1)
         (die! 2 (str "REFUSED: entity " e " spans namespaces " (pr-str nss)
                      " — this projection puts one namespace in one table, and an entity"
                      " that spans two would be split or duplicated without saying so.")))
       (let [ns (first nss)
             table (or (table-of ns)
                       (die! 2 (str "REFUSED: attribute namespace " ns " (entity " e
                                    ") has no table. An unprojected namespace looks exactly"
                                    " like an upstream that stopped producing one.")))
             row (reduce (fn [r [a v]]
                           (let [val (scalar v)]
                             (when (= ::unsupported val)
                               (die! 2 (str "REFUSED: " e "/" a " holds a value this projection"
                                            " cannot put in a column: " (pr-str v))))
                             (assoc r (column a) val)))
                         {"id" e} attrs)]
         (update acc table (fnil conj []) row))))
   {} folded))

(defn complete-rows
  "Every row in a table gets every column the table has, nil where absent, so
  the Iceberg schema does not depend on which row the loader saw first."
  [rows]
  (let [cols (into #{} (mapcat keys) rows)]
    (mapv (fn [r] (reduce #(update %1 %2 identity) (merge (zipmap cols (repeat nil)) r) cols)) rows)))

(defn check-provenance! [table rows]
  (let [missing (remove #(some (fn [c] (some? (get % c))) provenance-columns) rows)]
    (when (seq missing)
      (die! 2 (str "REFUSED: " (count missing) " row(s) in " table
                   " carry neither `sourcing` nor `derived`."
                   "\nMost of this graph is a representative slice and every fraud signal is"
                   "\nsynthesized; a row published without its provenance marker reads as"
                   "\nmeasured market intelligence. Unmarked is unmeasured, not authoritative."
                   "\nFirst: " (pr-str (get (first missing) "id")))))))

(defn numeric-columns
  "Which columns are ints and which are floats, DERIVED from the values rather
  than listed by hand — a hand-kept list stops matching the day upstream adds
  a column, and nothing reports it."
  [rows]
  (let [cols (into #{} (mapcat keys) rows)]
    (reduce (fn [acc c]
              (let [vals (remove nil? (map #(get % c) rows))]
                (cond
                  (empty? vals) acc
                  (not (every? number? vals)) acc
                  (every? #(== % (js/Math.floor %)) vals) (update acc :int conj c)
                  :else (update acc :float conj c))))
            {:int [] :float []} (sort cols))))

(defn write-json! [name rows]
  (fs/mkdirSync out-dir #js {:recursive true})
  (let [p (path/join out-dir name)]
    (fs/writeFileSync p (js/JSON.stringify (clj->js rows)))
    (println (str "WROTE\t" (count rows) "\t" p))))

(defn git [& args]
  (let [r (cp/spawnSync "git" (clj->js (concat ["-C" repo] args))
                        #js {:encoding "utf8" :timeout 60000})]
    (str/trim (str (or (.-stdout r) "")))))

(defn -main []
  (let [txs (read-log)
        folded (fold txs)
        by-table (rows-by-table folded)
        datom-count (reduce + (map #(count (:tx/datoms %)) txs))]
    (println (str "SCANNED\t" (count txs) "\ttransactions\t" datom-count "\tdatoms\t"
                  (count folded) "\tentities"))
    (when (empty? by-table)
      (die! 2 "REFUSED: folded the log and found no entities."))
    (let [tables (into {} (map (fn [[t rows]] [t (complete-rows rows)])) by-table)
          _ (doseq [[t rows] (sort tables)] (check-provenance! t rows))
          sourcing-counts (frequencies (keep #(get % "sourcing") (mapcat val tables)))
          manifest [{"log_sha256" (.digest (.update (crypto/createHash "sha256")
                                                    (fs/readFileSync log-path)) "hex")
                     "repo_commit" (git "rev-parse" "HEAD")
                     "transactions" (count txs)
                     ;; Measured 2026-08-29: the log holds 171 transactions but
                     ;; only 3 distinct :tx/id, each re-appended 57 times with
                     ;; an identical datom set. The observatory RE-ASSERTS a
                     ;; fixed seed graph every run; it does not observe new ad
                     ;; supply chain state. The registry's :produces-datoms
                     ;; expectation is satisfied by those duplicates, so the
                     ;; actor is green while its subject has not moved since
                     ;; :as_of_last. Without these two columns a reader would
                     ;; have to infer that from `transactions` vs `entities`.
                     "distinct_transactions" (count (distinct (map :tx/id txs)))
                     "duplicate_transactions" (- (count txs) (count (distinct (map :tx/id txs))))
                     "datoms" datom-count
                     "entities" (count folded)
                     "as_of_first" (apply min (map :tx/as-of txs))
                     "as_of_last" (apply max (map :tx/as-of txs))
                     ;; The headline number of this projection. A reader who
                     ;; joins these tables to dns_resolution or eigyo_lead must
                     ;; be able to see, in one row, that most of what they just
                     ;; joined is illustrative.
                     "rows_representative" (get sourcing-counts "representative" 0)
                     "rows_synthesized" (get sourcing-counts "synthesized" 0)
                     "rows_authoritative" (get sourcing-counts "authoritative" 0)
                     "rows_derived" (count (filter #(some? (get % "derived")) (mapcat val tables)))
                     "generated_at" (.toISOString (js/Date.))
                     "repo" repo}]
          all (assoc tables "sukashi_manifest" manifest)]
      (doseq [[t rows] (sort all)]
        (write-json! (str t ".json") rows))
      (fs/writeFileSync
       (path/join out-dir "sukashi.spec.json")
       (js/JSON.stringify
        (clj->js {"namespace" "cloud_itonami"
                  "tables" (vec (for [[t rows] (sort all)
                                      :let [{:keys [int float]} (numeric-columns rows)]]
                                  (cond-> {"file" (str t ".json") "table" t
                                           "int_columns" (vec int)}
                                    (seq float) (assoc "float_columns" (vec float)))))})
        nil 2))
      (println (str "SPEC\t" (path/join out-dir "sukashi.spec.json")))
      (println (str "PROVENANCE\trepresentative=" (get sourcing-counts "representative" 0)
                    "\tsynthesized=" (get sourcing-counts "synthesized" 0)
                    "\tauthoritative=" (get sourcing-counts "authoritative" 0))))))

(-main)
