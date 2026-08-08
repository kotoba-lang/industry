#!/usr/bin/env nbb
;; gen-lake-manifest.cljs — columnar lake over a datom-plane dataset.
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/gen-lake-manifest.cljs \
;;       --input 90-docs/system-dynamics/itonami-maturity.datoms.edn \
;;       --dataset itonami-maturity \
;;       --out-dir 90-docs/lake \
;;       [--rows-per-chunk 512] [--check]
;;
;; ## なぜこれが要るか
;;
;; `manifest/edn-query.cljs` は 1 クエリのために datom 面の **全 dataset** を
;; DataScript に読み込む。実測 2026-08-07: ADR 1 件を id で引くだけの query が
;; 120 秒でタイムアウトした。範囲述語（「leverage score が X〜Y の repo」）は
;; その全ロードのあと全行を走査する。
;;
;; columnar lake は同じブロックの上に別の形を作る: 属性ごとに 1 列、値でソートし、
;; row-group に切り、各 chunk に [min max] の zone map を持たせる。範囲クエリは
;; manifest（小さい）だけ読んで、条件に掛かりうる chunk だけを開く。
;;
;; ## 何を datom 面に載せ、何を載せないか（ADR-2608039970）
;;
;; **載せる**: 列と chunk の *メタデータ* —— 属性名・行数・zone map・chunk の
;; path と sha256。これは小さく、join でき、他の dataset（`:repo/path` で
;; repo-taxonomy と）と結べる。
;;
;; **載せない**: chunk の中身（値と entity の並び）。これは bytes であって
;; datom 面の仕事ではない。chunk は `<out-dir>/<dataset>/` 配下の EDN ファイルで、
;; manifest からは sha256 で参照する。
;;
;; その分離のおかげで **manifest はそのまま `:canonical-edn-v1` loader に通る** ——
;; `manifest/projection-verify.cljs` の loader allowlist を広げる必要がない。
;;
;; ## chunk サイズ
;;
;; 既定 512 行。`kotoba-lang/kotobase` の `capability-bench/run-lake-chunk.cljs`
;; が実測した最適点（40,000 entities で bytes 最小が 512 行）。**最適点はデータ量の
;; 関数**なので、対象が桁で変わったら測り直すこと —— 4,000 entities では 64 行が
;; 最小だった。大きくしすぎると zone map の枝刈りが粗くなって読みすぎる。
;;
;; ## 決定性
;;
;; projection contract が sha256 を固定するので、出力は決定的でなければならない。
;; 時刻・乱数・ハッシュ順の map 反復を使わない。並びは値→entity-id の辞書順で
;; 全順序を与える（同値が複数 entity にあっても順序が決まる）。

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))
(def crypto (js/require "node:crypto"))

(def argv (vec (drop 2 (js->clj (.-argv js/process)))))
(defn arg [k d] (loop [xs argv]
                  (cond (empty? xs) d
                        (= (first xs) (str "--" k)) (or (second xs) d)
                        :else (recur (rest xs)))))
(defn flag? [k] (some #{(str "--" k)} argv))

(def input (arg "input" "90-docs/system-dynamics/itonami-maturity.datoms.edn"))
(def dataset (arg "dataset" "itonami-maturity"))
(def out-dir (arg "out-dir" "90-docs/lake"))
(def rows-per-chunk (js/parseInt (arg "rows-per-chunk" "512") 10))
(def check? (flag? "check"))

(defn sha256-hex [s]
  (-> (.createHash crypto "sha256") (.update s "utf8") (.digest "hex")))

(defn attr-slug [a]
  (-> (str a) (str/replace #"^:" "") (str/replace #"[^A-Za-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

;; identity は entity-id 属性を持つ dataset なら何でもよい。無い dataset では
;; :db/id も無いので、入力順の index を使う（決定的）。
(defn entity-ident [e i]
  (or (some #(get e %) [:repo/path :corpus/entity-id :adr/id :permit/id])
      (str "idx-" i)))

(defn -main []
  (let [raw (.readFileSync fs input "utf8")
        parsed (edn/read-string raw)
        entities (vec (filter map? parsed))
        _ (when (empty? entities)
            (println "FAIL no entity maps in" input) (js/process.exit 1))
        idents (mapv entity-ident entities (range))
        ;; 順序付き数値属性だけが lake の対象。文字列やコレクションは zone map で
        ;; 枝刈りできないので載せない（載せても skip が効かず、読む量が増えるだけ）。
        numeric-attrs
        (->> entities
             (mapcat (fn [e] (for [[k v] e :when (number? v)] k)))
             frequencies
             (filter (fn [[_ n]] (>= n (max 32 (quot (count entities) 4)))))
             (map first)
             (sort-by str)
             vec)
        _ (when (empty? numeric-attrs)
            (println "FAIL no numeric attribute is present on enough entities in" input)
            (js/process.exit 1))
        chunk-dir (.join path out-dir dataset)
        _ (.mkdirSync fs chunk-dir #js {:recursive true})
        results
        (vec (for [a numeric-attrs]
               (let [rows (->> (map vector idents entities)
                               (keep (fn [[id e]] (when-let [v (get e a)]
                                                    (when (number? v) [v id]))))
                               ;; 値 → entity-id の辞書順。同値でも全順序になる
                               (sort-by (fn [[v id]] [v id]))
                               vec)
                     chunks (vec (partition-all rows-per-chunk rows))
                     slug (attr-slug a)
                     chunk-meta
                     (vec (map-indexed
                           (fn [i chunk]
                             (let [body (pr-str {:lake/attribute (str a)
                                                 :lake/index i
                                                 :lake/rows (mapv (fn [[v id]] [v id]) chunk)})
                                   fname (str slug "-" i ".edn")
                                   fpath (.join path chunk-dir fname)]
                               (when-not check? (.writeFileSync fs fpath body))
                               {:index i
                                :rows (count chunk)
                                :min (first (first chunk))
                                :max (first (last chunk))
                                :path (str out-dir "/" dataset "/" fname)
                                :sha256 (sha256-hex body)}))
                           chunks))]
                 {:attribute a :slug slug :rows (count rows) :chunk-meta chunk-meta})))
        ;; `:db/id` は loader が必須にしている。tempid は**出現順の負整数**で
        ;; 与える —— 決定的でなければ projection contract の sha256 が固定できない
        ;; （gensym や時刻由来は使わない）。
        manifest-entities
        (vec (map-indexed
              (fn [i e] (assoc e :db/id (- (inc i))))
              (concat
              (for [{:keys [attribute slug rows chunk-meta]} results]
                {:lake/entity-id (str dataset "/column/" slug)
                 :lake/kind :column
                 :lake/dataset dataset
                 :lake/attribute (str attribute)
                 :lake/rows rows
                 :lake/chunk-count (count chunk-meta)
                 :lake/rows-per-chunk rows-per-chunk})
              (for [{:keys [attribute slug chunk-meta]} results
                    c chunk-meta]
                {:lake/entity-id (str dataset "/chunk/" slug "/" (:index c))
                 :lake/kind :chunk
                 :lake/dataset dataset
                 :lake/attribute (str attribute)
                 :lake/index (:index c)
                 :lake/rows (:rows c)
                 :lake/min (:min c)
                 :lake/max (:max c)
                 :lake/path (:path c)
                 :lake/sha256 (:sha256 c)}))))
        header (str ";; " out-dir "/" dataset "-lake.datoms.edn — GENERATED, DO NOT HAND-EDIT.\n"
                    ";; Regenerate: nbb --classpath \".:scripts/nbb_compat\" scripts/gen-lake-manifest.cljs \\\n"
                    ";;   --input " input " --dataset " dataset "\n"
                    ";;\n"
                    ";; columnar lake の manifest。**値は入っていない** —— 値は\n"
                    ";; " out-dir "/" dataset "/ の chunk EDN にあり、ここからは sha256 で参照する\n"
                    ";; （ADR-2608039970「分けるのは bytes」/ ADR-2608070400 D8）。\n"
                    ";;\n"
                    ";; 範囲クエリは :lake/min :lake/max で chunk を枝刈りしてから\n"
                    ";; :lake/path の chunk だけを開く。manifest 自体は datom 面に載るので\n"
                    ";; :lake/dataset で他の面と join できる。\n")
        out-file (str out-dir "/" dataset "-lake.datoms.edn")
        body (str header (pr-str manifest-entities) "\n")]
    (if check?
      (let [existing (when (.existsSync fs out-file) (.readFileSync fs out-file "utf8"))]
        (if (= existing body)
          (println "lake manifest is canonical:" out-file)
          (do (println "STALE:" out-file "— run without --check to regenerate")
              (js/process.exit 1))))
      (do (.writeFileSync fs out-file body)
          (println (str "wrote " out-file))))
    (println (str "  dataset=" dataset "  entities=" (count entities)
                  "  columns=" (count numeric-attrs)
                  "  chunks=" (reduce + (map (comp count :chunk-meta) results))
                  "  rows/chunk=" rows-per-chunk))
    (println (str "  manifest entities=" (count manifest-entities)
                  "  (columns + chunks; values live in " out-dir "/" dataset "/)"))))

(-main)
