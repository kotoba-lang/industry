#!/usr/bin/env nbb
;; orgs/personal/bin/mail-datascript.cljs — jun784 Gmail アーカイブ
;; (mail/messages/index.jsonl) を実 DataScript (npm `datascript`) にロードして
;; query する軽量ツール。属性変換方針は manifest/edn-query.cljs と同一
;; （datascript.js は attribute を keyword でなく裸文字列で扱うため、この
;; ローダも文字列化する）。
;;
;; Datomic 側 (bin/datomic/src/warehouse/load.clj, JVM `clojure -M -m
;; warehouse.load`) は email/from・to・cc を :person/email への ref として
;; グラフ結合するが、このローダは意図的にフラットにする（email/from・to・cc は
;; 単なるアドレス文字列 — datascript.js の transact データで lookup-ref を
;; 組み立てる複雑さを避けるための単純化。名前解決したければ facts/people.edn
;; 由来の person entity を "person/emails" で別途 join query する）。
;;
;; 対象: mail/messages/index.jsonl（Gmail ingest 済みメタデータ。本文は含まず
;; — git-annex 実体が無ければ空で終わる。事前に
;; `bash orgs/personal/bin/gpg-unlock.sh && git annex get mail/messages/index.jsonl`）。
;; facts/people.edn があれば email → 表示名解決用の person entity も足す
;; （無くても mail query は動く）。
;;
;; 使い方 (repo root から):
;;   npx nbb orgs/personal/bin/mail-datascript.cljs count
;;   npx nbb orgs/personal/bin/mail-datascript.cljs q \
;;     '[:find ?subj ?date :where [?e "email/from" "no-reply@mercari.jp"]
;;                                [?e "email/subject" ?subj] [?e "email/date" ?date]]'

(require '[scripts.nbb-compat :refer [slurp format]]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '["datascript" :as ds-mod])

(def ds (.-default ds-mod))

(def root (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel"))))
(def base (io/file root "orgs" "personal"))

;; ---------- attr/value 変換 (manifest/edn-query.cljs と同一方針) ----------

(defn kw->attr [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (string? k) k
    :else (str k)))

(defn ->ds-value [v]
  (cond
    (map? v) (pr-str v)
    (vector? v) (into-array v)
    (nil? v) ""
    :else v))

(defn entity->js [m]
  (let [obj (js-obj)]
    (doseq [[k v] m]
      (if (= k :db/id)
        (aset obj ":db/id" v)
        (aset obj (kw->attr k) (->ds-value v))))
    obj))

;; ---------- mail/messages/index.jsonl ----------

(defn index-file [] (io/file base "mail" "messages" "index.jsonl"))

(defn mail-records []
  (let [f (index-file)]
    (if (.exists f)
      (->> (str/split-lines (slurp f))
           (remove str/blank?)
           (keep (fn [line] (try (json/parse-string line true) (catch :default _ nil)))))
      [])))

(defn- addr
  "\"Name <addr@x>\" -> \"addr@x\"。角括弧が無ければそのまま返す。"
  [s]
  (when s (let [m (re-find #"<([^>]+)>" (str s))] (if m (second m) s))))

(defn email-entities [next-tempid!]
  (keep
   (fn [rec]
     (when-let [cid (:cid rec)]
       (cond-> {:db/id (next-tempid!)
                :email/cid cid
                :email/message-id (or (:message_id rec) cid)}
         (:thread_id rec)    (assoc :email/thread-id (:thread_id rec))
         (:date rec)         (assoc :email/date (:date rec))
         (:subject rec)      (assoc :email/subject (:subject rec))
         (seq (:labels rec)) (assoc :email/labels (vec (:labels rec)))
         (:from rec)         (assoc :email/from (addr (:from rec)))
         (seq (:to rec))     (assoc :email/to (vec (keep addr (:to rec))))
         (seq (:cc rec))     (assoc :email/cc (vec (keep addr (:cc rec))))
         (:size rec)         (assoc :email/size (:size rec))
         (:eml_path rec)     (assoc :email/eml-path (:eml_path rec)))))
   (mail-records)))

;; ---------- facts/people.edn (任意: email -> 表示名) ----------

(defn people-file [] (io/file base "facts" "people.edn"))

(defn person-entities [next-tempid!]
  (let [f (people-file)]
    (if (.exists f)
      (try
        (keep (fn [p]
                (when (seq (:emails p))
                  {:db/id (next-tempid!)
                   :person/id (:person/id p)
                   :person/name (:person/name p)
                   :person/emails (vec (:emails p))}))
              (edn/read-string (slurp f)))
        (catch :default _ []))
      [])))

;; ---------- schema ----------

(defn ds-schema []
  (let [obj (js-obj)]
    (doseq [a ["email/labels" "email/to" "email/cc" "person/emails"]]
      (aset obj a (js-obj ":db/cardinality" ":db.cardinality/many")))
    obj))

;; ---------- build + query ----------

(defn build-conn []
  (let [conn (.create_conn ds (ds-schema))
        tempid (atom 0)
        next-tempid! (fn [] (swap! tempid dec))
        email-tx (email-entities next-tempid!)
        person-tx (person-entities next-tempid!)
        all-tx (into-array (map entity->js (concat email-tx person-tx)))]
    (.transact ds conn all-tx)
    {:conn conn :email-count (count email-tx) :person-count (count person-tx)}))

(defn -main [& args]
  (let [[mode query-str] args
        {:keys [conn email-count person-count]} (build-conn)]
    (case mode
      "count"
      (println (format "emails=%s people=%s" email-count person-count))

      "q"
      (println (pr-str (js->clj (.q ds query-str (.db ds conn)))))

      (do (println "usage: npx nbb orgs/personal/bin/mail-datascript.cljs [count | q '<datalog-query>']")
          (scripts.nbb-compat/exit 1)))))

(apply -main *command-line-args*)
