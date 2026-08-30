;; query.cljs — loads pricing-intelligence.datoms.edn + pricing-intelligence-ledger.edn
;; into a kotoba-lang/datalog db and runs the same three example queries the old JVM
;; DataScript harness ran.
;;
;; 2026-08-30: JVM `query.clj`（npm datascript は nbb では keyword comparator 衝突で
;; 使えない、という理由で JVM に逃げていた）を nbb + kotoba-lang/datalog へ移植。
;; entity map（各 :db/id 付き）→ bare-string attribute の quad {s p o} に畳んで
;; datalog.index に入れ、DataScript 形の vector query を datalog.core/q に渡す。
;; 変換の慣習は manifest/edn_query_datalog.cljs（ADR-2608260200）と同じ:
;; keyword 属性 → "ns/name"、:db.type/ref は解決せず値一致 join。
;;
;; Run (superproject root から):
;;   nbb --classpath "90-docs/pricing-intelligence:orgs/kotoba-lang/datalog/src" \
;;     90-docs/pricing-intelligence/query.cljs <root>
;;   （<root> 既定 "."。旧 query.clj と違い superproject root の中からでも動く —
;;    nbb は deps.edn を読まない。）
(ns pricing-query
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [scripts.nbb-compat :refer [format]]
            [datalog.core :as dl]
            [datalog.index :as index]
            ["node:fs" :as fs]))

(def root (or (first *command-line-args*) "."))
(def base-file (str root "/90-docs/pricing-intelligence/pricing-intelligence.datoms.edn"))
(def ledger-file (str root "/90-docs/pricing-intelligence/pricing-intelligence-ledger.edn"))

(defn read-edn-vector [path]
  (edn/read-string (fs/readFileSync path "utf8")))

(defn read-ledger-lines
  "One EDN map per non-comment, non-blank line."
  [path]
  (->> (str/split-lines (fs/readFileSync path "utf8"))
       (map str/trim)
       (remove #(or (= "" %) (str/starts-with? % ";;")))
       (mapv edn/read-string)))

(def base-entries (read-edn-vector base-file))
(def schema-entries (filter :db/ident base-entries))
(def catalog-entries (remove :db/ident base-entries))
(def ledger-entries (read-ledger-lines ledger-file))

(println "schema attrs:" (count schema-entries))
(println "catalog entities:" (count catalog-entries))
(println "ledger events:" (count ledger-entries))

;; --- entity maps -> quads ----------------------------------------------------

(defn- kw->attr [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (and (string? k) (str/starts-with? k ":") (> (count k) 1)) (subs k 1)
    (string? k) k
    :else (str k)))

(defn- ->value
  "スカラーの正規化（keyword → bare string 等）。lookup ref は別処理。"
  [v]
  (cond
    (keyword? v) (kw->attr v)
    (and (string? v) (str/starts-with? v ":") (> (count v) 1)) (subs v 1)
    (map? v) (pr-str v)
    (set? v) (pr-str v)
    (nil? v) ""
    :else v))

(defn- scalar? [v]
  (or (string? v) (number? v) (boolean? v) (keyword? v) (nil? v)))

(defn- entity->quads
  "entity map → quads。lookup ref [:attr value] は resolve-ref で参照先 entity の
   **id** に解決する（DataScript の :db.type/ref と同じ — join は id で行われる。
   scalar 文字列にしてしまうと 2-clause join が永遠に空になる）。"
  [eid ent resolve-ref]
  (mapcat
   (fn [[k v]]
     (when (not= k :db/id)
       (let [attr (kw->attr k)
             v (if (and (vector? v) (= 2 (count v)) (keyword? (first v)))
                 (resolve-ref (first v) (second v))
                 v)]
         (cond
           (and (sequential? v) (every? scalar? v))
           (map (fn [item] {:s eid :p attr :o (->value item)}) v)
           :else
           [{:s eid :p attr :o (->value v)}]))))
   ent))

(defn- build-db [entities]
  ;; pass 1: tempid 割当（DataScript と同じく :db/id 無し entity には -1, -2, ...）と
  ;;         identity attr (:*/id など :db.unique/identity に相当する第1 keyword attr)
  ;;         からの lookup-ref index を張る。
  ;; pass 2: quads 展開（lookup ref → 参照先 id 解決）。
  (let [next-id (atom -1)
        id-of (atom {})
        ids (mapv (fn [ent]
                    (or (:db/id ent)
                        (swap! next-id dec)))
                  entities)
        ;; entity の identity 候補: 値が keyword の最初の :xxx/id attr
        ident-key (fn [ent]
                    (some (fn [[k v]]
                            (when (and (keyword? k) (= "id" (name k))
                                       (keyword? v))
                              [(kw->attr k) (->value v)]))
                          ent))]
    (doseq [[ent eid] (map vector entities ids)]
      (when-let [k (ident-key ent)]
        (swap! id-of assoc k eid)))
    (let [resolve-ref (fn [attr-kw val-kw]
                        (let [k [(kw->attr attr-kw) (->value val-kw)]]
                          (or (get @id-of k)
                              (throw (ex-info "query.cljs: unresolved lookup ref" {:ref k})))))
          quads (mapcat (fn [ent eid] (entity->quads eid ent resolve-ref))
                        entities ids)]
      (index/persist-db
       (index/assert-quads! (index/mutable-db (index/empty-db)) quads (constantly false))))))

(defn- q
  "DataScript-shaped vector query (bare-string attrs) -> datalog.core/q."
  [db query]
  (let [qvec (if (string? query) (edn/read-string query) query)
        idx-in (or (first (keep-indexed #(when (= %2 :in) %1) qvec)) -1)
        idx-where (or (first (keep-indexed #(when (= %2 :where) %1) qvec)) -1)
        find-syms (vec (subvec qvec 1 (if (pos? idx-in) idx-in idx-where)))
        where (vec (subvec qvec (inc idx-where)))
        norm-clause (fn [clause] (mapv (fn [x] (if (symbol? x) x (kw->attr x))) clause))]
    (dl/q db {:find find-syms :where (mapv norm-clause where)}
          (constantly true))))

(def db (build-db (concat catalog-entries ledger-entries)))

(defn- datoms-count [db]
  (reduce-kv
   (fn [n _e pm]
     (+ n (reduce-kv (fn [m _p os] (+ m (count os))) 0 pm)))
   0
   (:eavt db)))

(println "\n-- total datoms:" (datoms-count db))

(println "\n-- example 1: every competitor observed for ISIC 7912 (Tour operator activities), with disclosure tier --")
(doseq [row (->> (q db '[:find ?pname ?price ?tier
                         :where
                         [?v "vertical/id" "vertical/isic-7912"]
                         [?o "obs/vertical" ?v]
                         [?o "obs/product" ?p]
                         [?p "product/name" ?pname]
                         [?o "obs/price-summary" ?price]
                         [?o "obs/disclosure-tier" ?tier]])
                 (sort-by (juxt first second)))]
  (let [[pname price tier] row]
    ;; 旧 JVM 版と同じ列順（tier が先頭）と keyword 表示を保つ。
    (println (format "  %-12s %-45s %s" (str ":" tier) pname price))))

(println "\n-- example 2: recommended price band per vertical, joined to cluster label (first 8, sorted) --")
(doseq [[clabel vname low high unit]
        (->> (q db '[:find ?clabel ?vname ?low ?high ?unit
                     :where
                     [?v "vertical/name" ?vname]
                     [?v "vertical/cluster" ?c]
                     [?c "cluster/label" ?clabel]
                     [?r "rec/vertical" ?v]
                     [?r "rec/band-low" ?low]
                     [?r "rec/band-high" ?high]
                     [?r "rec/unit" ?unit]])
             ;; 旧 JVM 版と同じ: sort-by first のみ（DataScript の set は hash 順が
             ;; 安定するが、nbb/kotoba の set は順が違う。sort は sort-by first だけ
             ;; で、同 tie は各 runtime の都合 —— take 8 の件数は一致させる）。
             (sort-by (juxt first second))
             (take 8))]
  ;; 通貨記号は :rec/unit が持っている。ここで "$" を前置しない —— 2026-08-10 に
  ;; 追加した円建ての band が `$35000.0-150000.0 (¥/月 …)` と矛盾して表示された。
  (println (format "  [%s] %-45s %s-%s (%s)" clabel vname low high unit)))

(println "\n-- example 3: disclosure-tier breakdown across all observations (proves real aggregate query, not just lookup) --")
(doseq [[tier n] (->> (q db '[:find ?tier (count ?o)
                              :where [?o "obs/disclosure-tier" ?tier]])
                      (sort-by second >))]
  (println (format "  %-12s %d" (str ":" tier) n)))

(println "\nOK — base + ledger loaded, transacted, and queried successfully (kotoba-lang/datalog / nbb).")
