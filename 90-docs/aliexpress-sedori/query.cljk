#!/usr/bin/env nbb
;; 90-docs/aliexpress-sedori/query.cljs — load observations-ledger.edn into a
;; real DataScript (npm `datascript`) db and query it. Same conversion
;; approach as manifest/edn-query.cljs (datascript.js uses bare-string attrs,
;; not keywords — see that file's header comment for why), kept
;; self-contained here since this dataset's ledger shape/paths are its own.
;;
;; Usage:
;;   nbb --classpath ".:scripts/nbb_compat" 90-docs/aliexpress-sedori/query.cljs count
;;   nbb --classpath ".:scripts/nbb_compat" 90-docs/aliexpress-sedori/query.cljs latest
;;   nbb --classpath ".:scripts/nbb_compat" 90-docs/aliexpress-sedori/query.cljs candidates
;;   nbb --classpath ".:scripts/nbb_compat" 90-docs/aliexpress-sedori/query.cljs q '[:find ?t :where [?e "product/title" ?t]]'

(require '[scripts.nbb-compat :refer [slurp file]]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '["datascript" :as ds-mod])

(def ds (.-default ds-mod))

(def ledger-path "90-docs/aliexpress-sedori/observations-ledger.edn")

(defn slurp-edn-lines [path]
  (->> (str/split-lines (slurp path))
       (remove str/blank?)
       (remove #(str/starts-with? (str/trim %) ";"))
       (keep (fn [line]
               (try (edn/read-string {:default (fn [_tag v] v)} line)
                    (catch :default _ nil))))
       vec))

(defn events []
  (if (.isFile (file ledger-path))
    (slurp-edn-lines ledger-path)
    []))

;; ---------- cljs keyword <-> datascript.js bare-string attr ----------

(defn kw->attr [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (string? k) k
    :else (str k)))

(defn ->ds-scalar [v]
  (cond
    (keyword? v) (kw->attr v)
    (nil? v) ""
    :else v))

(defn entity->js [i m]
  (let [obj (js-obj)]
    (aset obj ":db/id" (- (inc i)))
    (doseq [[k v] m :when (not= k :event/seq)]
      (aset obj (kw->attr k) (->ds-scalar v)))
    ;; keep :event/seq as a real number attr for sorting/filtering
    (when-let [sq (:event/seq m)]
      (aset obj "event/seq" sq))
    obj))

(defn build-db []
  (let [schema (js-obj)
        conn (.create_conn ds schema)
        entities (into-array (map-indexed entity->js (events)))]
    (.transact ds conn entities)
    (.db ds conn)))

(defn q [db query-str]
  (js->clj (.q ds query-str db)))

;; ---------- commands ----------

(defn cmd-count []
  (let [evs (events)
        products (set (keep :product/id (filter #(= :product-observed (:event/type %)) evs)))
        sellers (set (keep :seller/name (filter #(= :seller-observed (:event/type %)) evs)))]
    (println (str "events=" (count evs)
                   " products=" (count products)
                   " sellers-known=" (count sellers)))))

(defn- latest-by-product [evs]
  (->> evs
       (filter #(= :product-observed (:event/type %)))
       (group-by :product/id)
       (map (fn [[pid group]] (apply max-key :event/seq group)))))

(defn- seller-for [evs pid]
  (->> evs
       (filter #(and (= :seller-observed (:event/type %)) (= pid (:product/id %))))
       (sort-by :event/seq)
       last
       :seller/name))

(defn- resale-for [evs pid]
  (->> evs
       (filter #(and (= :resale-price-observed (:event/type %)) (= pid (:product/id %))))
       (sort-by :event/seq)
       last))

(defn- purchasable
  "仕入れ値として使う観測。詳細ページ(:detail-browse)を優先する — 一覧ページの
   価格は複数出品者を集約した面の最安バリアントや期間限定promoを表示していることが
   あり、実際にその出品者から買える金額とは限らない(実測 2026-07-25: 11商品中6件で
   一覧価格 < 詳細価格、最大2.5倍差)。安い方を採ると利ざやを過大評価するので、
   保守的に詳細ページ価格を使う。

   **すべての商品単位コマンド(candidates/margin)はこの1つの規則だけを使う。**
   かつて cmd-candidates が latest-by-product(seq最新)を使い cmd-margin が
   こちらを使っていたため、同じ ledger から両者の対象集合が食い違っていた
   (実測: 魚の鱗取り 1005007038640140 が margin に出て candidates に出ない)。"
  [evs pid]
  (let [obs (filter #(and (= :product-observed (:event/type %)) (= pid (:product/id %))) evs)]
    (or (->> obs (filter #(= :detail-browse (:event/source %))) (sort-by :event/seq) last)
        (->> obs (sort-by :event/seq) last))))

(defn- discount-pct-of
  "割引率。AliExpress が `-83%` と % 表示している時はその観測値を使い、
   `374円 お得` のように**円引き**で出している時は % 属性ごと欠落するので
   原価と現価から導出する(切り捨て — AliExpress 自身の丸めに合わせた。
   実測: 165/985 = 83.25% を AliExpress は -83% と表示)。

   導出しないと「% が無い」というだけで候補から静かに落ちる: ピーラー
   1005005643444241 は 970円→150円(実質84%off)・5万点販売・評価4.4 なのに
   両観測とも % 属性を持たず、修正前は candidates に一度も現れなかった。"
  [{:keys [price/discount-pct price/amount price/orig-amount]}]
  (or discount-pct
      (when (and amount orig-amount (pos? orig-amount) (< amount orig-amount))
        (Math/floor (* 100 (- 1 (/ amount orig-amount)))))))

(defn cmd-latest []
  (let [evs (events)
        latest (sort-by :event/seq > (latest-by-product evs))]
    (doseq [{:keys [product/id product/title price/amount price/currency
                     price/discount-pct rating/value sold/label event/category]} latest]
      (println (str "[" id "] " title
                     " | " amount currency
                     (when discount-pct (str " (-" discount-pct "%)"))
                     " | rating=" value
                     " | sold=" label
                     " | cat=" category
                     " | seller=" (or (seller-for evs id) "?"))))
    (println (str "-- " (count latest) " product(s)"))))

(defn cmd-candidates
  "素朴なヒューリスティック絞り込み(捏造スコアなし): 観測された販売数/割引率/評価
   だけで足切りし、販売数降順で並べる。転売可否・利益率は AliExpress 側の
   データだけでは判定できないため計算しない(system-dynamics rule と同じ
   'uncomputable-until-measured' 方針 — 出典の無い数値を作らない)。"
  []
  (let [evs (events)
        pids (distinct (map :product/id (filter #(= :product-observed (:event/type %)) evs)))
        ;; margin と同じ purchasable 規則。ここだけ latest-by-product を使うと
        ;; 同じ ledger なのに2コマンドの対象集合がズレる(修正前の実バグ)。
        current (map #(purchasable evs %) pids)
        worthy (->> current
                    (filter (fn [{:keys [sold/count-min rating/value] :as p}]
                              (let [d (discount-pct-of p)]
                                (and count-min (>= count-min 1000)
                                     value (>= value 4.5)
                                     d (>= d 50)))))
                    (sort-by :sold/count-min >))]
    (doseq [{:keys [product/id product/title price/amount price/currency
                     sold/count-min rating/value event/category] :as p} worthy]
      (println (str "[" id "] " title
                     " | " amount currency " (-" (discount-pct-of p) "%)"
                     (when-not (:price/discount-pct p) "[導出]")
                     " | rating=" value
                     " | sold>=" count-min
                     " | cat=" category
                     " | seller=" (or (seller-for evs id) "unknown (not yet detail-enriched)"))))
    (println (str "-- " (count worthy) " candidate(s) of " (count current) " known product(s)"))))

(defn cmd-margin
  "仕入れ値(AliExpress 詳細ページ価格 + 送料)と、国内相場の中央値を並べる。

   **これは概算の目安であって実際の利ざやではない。** :resale/* は「同一商品の
   売値」ではなく、Yahoo!ショッピングで近いキーワードを検索した結果の
   **カテゴリ相場の分布**（中央値・最小・最大）。同一商品とは限らず、ブランド品・
   電動版・日本製など明らかに別物が母集団に混ざる（例: 魚うろこ取りの母集団には
   電動6,000円台が含まれる）。さらに以下は一切含んでいない: 関税・輸入消費税・
   国内発送料・販売手数料・返品率・在庫回転。したがって出るのは
   `resale-median / ali-cost` の粗い倍率だけで、これを利益率と読んではいけない。"
  []
  (let [evs (events)
        rows (->> (latest-by-product evs)
                  (keep (fn [{:keys [product/id product/title]}]
                          (let [p (purchasable evs id)
                                r (resale-for evs id)]
                            (when (and r (:price/amount p))
                              (let [cost (+ (:price/amount p) (or (:price/shipping-amount p) 0))
                                    med (:resale/price-median r)]
                                {:id id :title title :cost cost :med med
                                 :ratio (/ med cost)
                                 :query (:resale/query r)
                                 :n (:resale/sample-size r)})))))
                  (sort-by :ratio >))]
    (doseq [{:keys [id title cost med ratio query n]} rows]
      (println (str "[" id "] " (subs title 0 (min 34 (count title)))
                     " | 仕入 " cost "円 → 国内中央値 " med "円"
                     " | 倍率 x" (.toFixed ratio 1)
                     " | 相場元 \"" query "\" n=" n)))
    (println (str "-- " (count rows) " 件（倍率は概算の目安。関税/手数料/送料/同一商品保証なし）"))))

(defn cmd-q [query-str]
  (let [db (build-db)]
    (println (pr-str (q db query-str)))))

(defn -main [& [cmd & rest-args]]
  (case cmd
    "count" (cmd-count)
    "latest" (cmd-latest)
    "candidates" (cmd-candidates)
    "margin" (cmd-margin)
    "q" (cmd-q (first rest-args))
    (do (println "usage: nbb 90-docs/aliexpress-sedori/query.cljs <count|latest|candidates|margin|q '<datalog>'>")
        (.exit js/process 1))))

(apply -main *command-line-args*)
