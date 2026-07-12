#!/usr/bin/env nbb
;; manifest/edn-query.cljs — ADR ledger + RAD identity journal を実 DataScript
;; (npm `datascript` パッケージ) にロードして query するツール。
;;
;; 対象:
;;   - 90-docs/adr/*.edn（manifest/edn-datomize.cljs で tx-data 化済み。
;;     [{:db/id -1 ...}] 形式の1エンティティ）
;;   - orgs/etzhayyim/root/80-data/kotoba-rad/*.identity.journal.edn
;;     （生 datom タプル [e a v tx :add|:retract] の羅列。DataScript には
;;     Datomic のような transaction history API が無いため、tx 順に replay して
;;     「現在値」の entity map に圧縮してから読み込む — 履歴クエリは対象外、
;;     現在状態のみ）。orgs/etzhayyim/root が未 checkout の場合は黙ってスキップする。
;;
;; 属性の型変換について: npm `datascript` パッケージは datascript.js 名前空間
;; （JS 向けインターフェース）をそのまま公開しており、属性は keyword ではなく
;; **裸の文字列**（例 "adr/id"、コロン無し）として扱う。:db/id だけ特別扱いで
;; コロン付き文字列キー ":db/id"。このため元の edn の keyword 値もこのローダでは
;; 文字列化する（"was keyword" という型情報は失われる — MVP の既知の制約。
;; 必要になったら別属性に :value/type "keyword" のようなマーカーを足す）。
;;
;; 使い方:
;;   nbb manifest/edn-query.cljs count
;;   nbb manifest/edn-query.cljs q '[:find ?id ?status :where
;;                                   [?e "adr/id" ?id] [?e "adr/status" ?status]]'

(require '[scripts.nbb-compat :refer [slurp file-seq format]]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '["datascript" :as ds-mod])

(def ds (.-default ds-mod))

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))

(defn slurp-edn [path] (edn/read-string (slurp path)))

(defn slurp-edn-lines
  "RAD identity journal 用: 1行1トップレベルEDNフォームの疑似JSONL形式を読む
   （journal はレコードが逐次1行ずつ追記される想定で、単一の妥当な edn
   ベクタとしては書かれていない）。"
  [path]
  (->> (str/split-lines (slurp path))
       (remove str/blank?)
       (mapv edn/read-string)))

;; ---------- attr/value 変換（cljs keyword <-> datascript.js の裸文字列） ----------

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

(defn ->ds-value [v]
  (if (or (vector? v) (seq? v) (list? v))
    (into-array (map ->ds-scalar v))
    (->ds-scalar v)))

(defn entity->js
  "cljのentity map（{:db/id -N, :ns/attr val, ...}）を datascript.js 向け
   JS object に変換。:db/id はコロン付き文字列キー \":db/id\"、他の属性は
   裸文字列キー（namespace/name、コロン無し）にする。"
  [m]
  (let [obj (js-obj)]
    (doseq [[k v] m]
      (if (= k :db/id)
        (aset obj ":db/id" v)
        (aset obj (kw->attr k) (->ds-value v))))
    obj))

;; ---------- ADR ledger (90-docs/adr/*.edn) ----------

(defn adr-files []
  (->> (file-seq (io/file root "90-docs" "adr"))
       (filter #(str/ends-with? (str %) ".edn"))
       (sort-by str)))

(defn adr-entity
  "既に tx-data 化済み（[{:db/id -1 ...}]）な ADR edn を読み、中身の1 entity map
   を返す。tx-data 化前（トップレベル :frontmatter map 等）や parse エラーの
   ファイル（manifest/edn-datomize.cljs が pre-existing data issue として
   報告済みのもの）は nil でスキップする。"
  [f]
  (try
    (let [content (slurp-edn f)]
      (when (and (vector? content) (seq content) (map? (first content)))
        (first content)))
    (catch :default _ nil)))

;; ---------- RAD identity journal ----------

(defn rad-dir [] (io/file root "orgs" "etzhayyim" "root" "80-data" "kotoba-rad"))

(defn rad-files []
  (let [dir (rad-dir)]
    (if (.exists dir)
      (->> (file-seq dir)
           (filter #(str/ends-with? (str %) ".identity.journal.edn"))
           (sort-by str))
      [])))

(defn replay-journal
  "[e a v tx op] タプル列を tx 順に replay し {e {a v ...}} に圧縮する
   （:add は上書き、:retract は attr 削除。RAD の属性は実データ上すべて
   単一値の概念 — :rad/type :rad/name :rad/did-web 等が tx を跨いで
   書き換わる形 — なので cardinality-one 前提の「最新値」replay で
   current state を再構成できる）。"
  [tuples]
  (reduce
   (fn [acc [e a v _tx op]]
     (case op
       :add (assoc-in acc [e a] v)
       :retract (update acc e dissoc a)
       acc))
   {}
   (sort-by #(nth % 3) tuples)))

(defn rad-entities [next-tempid!]
  (mapcat
   (fn [f]
     (let [compacted (replay-journal (slurp-edn-lines f))]
       (for [[cid attrs] compacted]
         (assoc attrs
                :db/id (next-tempid!)
                :rad/cid cid
                :source/file (str f)))))
   (rad-files)))

;; ---------- schema (manifest/schema.edn -> datascript createConn schema) ----------

(defn schema-path [] (io/file root "manifest" "schema.edn"))

(defn ds-schema []
  (let [attrs (if (.exists (schema-path)) (slurp-edn (schema-path)) [])
        obj (js-obj)]
    (doseq [attr attrs]
      (when (= (:db/cardinality attr) :db.cardinality/many)
        (aset obj (kw->attr (:db/ident attr)) (js-obj ":db/cardinality" ":db.cardinality/many"))))
    (aset obj "rad/cid" (js-obj ":db/unique" ":db.unique/identity"))
    obj))

;; ---------- build + query ----------

(defn build-conn []
  (let [conn (.create_conn ds (ds-schema))
        tempid (atom 0)
        next-tempid! (fn [] (swap! tempid dec))
        adr-tx (keep (fn [f]
                        (when-let [e (adr-entity f)]
                          (assoc e :db/id (next-tempid!) :source/file (str f))))
                      (adr-files))
        rad-tx (rad-entities next-tempid!)
        all-tx (into-array (map entity->js (concat adr-tx rad-tx)))]
    (.transact ds conn all-tx)
    {:conn conn :adr-count (count adr-tx) :rad-count (count rad-tx)}))

(defn -main [& args]
  (let [[mode query-str] args
        {:keys [conn adr-count rad-count]} (build-conn)]
    (case mode
      "count"
      (println (format "adr=%s rad=%s total=%s" adr-count rad-count (+ adr-count rad-count)))

      "q"
      (println (pr-str (js->clj (.q ds query-str (.db ds conn)))))

      (do (println "usage: nbb manifest/edn-query.cljs [count | q '<datalog-query>']")
          (scripts.nbb-compat/exit 1)))))

(apply -main *command-line-args*)
