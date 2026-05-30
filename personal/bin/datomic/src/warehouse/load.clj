(ns warehouse.load
  "Normalize the file-based personal data warehouse into a Datomic (Local) DB.
   The annexed JSONL/JSON files remain the source of truth; this DB is a
   rebuildable, queryable, NORMALIZED index (entities: org / person / account /
   txn / loan / event / action, linked by refs). Run:
     clojure -M -m warehouse.load
   Uses an in-memory Datomic Local DB (no transactor, ephemeral, rebuilt on demand)."
  (:require [datomic.client.api :as d]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def base "/Users/junkawasaki/github/com-junkawasaki/personal")

;; ---------- readers ----------
(defn rd-jsonl [rel]
  (let [f (io/file base rel)]
    (when (.exists f)
      (with-open [r (io/reader f)]
        (doall (->> (line-seq r)
                    (remove str/blank?)
                    (keep #(try (json/read-str % :key-fn keyword) (catch Exception _ nil)))))))))

(defn rd-json [rel]
  (let [f (io/file base rel)]
    (when (.exists f) (json/read-str (slurp f) :key-fn keyword))))

;; ---------- helpers ----------
(defn month [d] (when (and d (>= (count d) 7)) (subs d 0 7)))

(defn parse-amount
  "Best-effort JPY amount from a structured field or free-text detail."
  [rec]
  (or (when-let [a (:amount_jpy rec)] (when (number? a) (long a)))
      (let [s (str (:detail rec) " " (:subject rec))
            m (or (re-find #"[¥￥]\s*([0-9][0-9,]{2,})" s)
                  (re-find #"([0-9][0-9,]{3,})\s*円" s))]
        (when m (try (Long/parseLong (str/replace (second m) "," "")) (catch Exception _ nil))))))

(defn clean [s] (when s (str/trim (str s))))

;; ---------- schema ----------
(def schema
  [{:db/ident :org/name        :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :org/type        :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :person/email    :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :person/name     :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :person/role     :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :txn/id          :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :txn/date        :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :txn/month       :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :txn/amount-jpy  :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
   {:db/ident :txn/kind        :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :txn/detail      :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :txn/vendor      :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
   {:db/ident :txn/source      :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :loan/id         :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :loan/date       :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :loan/amount-jpy :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}
   {:db/ident :event/id        :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :event/summary   :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/start     :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/category  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :action/id       :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :action/title    :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :action/priority :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :action/category :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])

;; ---------- normalization (file records -> datoms) ----------
(def finance-files
  ["mail/recent-activity-30d.jsonl" "mail/finance-history-202604.jsonl"
   "mail/finance-history-senders.jsonl" "mail/finance-history-2.jsonl"
   "mail/finance-history-3.jsonl" "mail/finance-history-4.jsonl"
   "mail/finance-history-5.jsonl" "mail/finance-history-6.jsonl"])

(defn txn-tx []
  (apply concat
    (for [src finance-files]
      (keep-indexed
        (fn [i rec]
          (let [vendor (clean (or (:vendor rec) (:from rec)))
                date   (clean (:date rec))]
            (when (and vendor date (:date rec))   ; skip note lines
              (let [amt (parse-amount rec)]
                (cond-> {:txn/id     (str src "#" i)
                         :txn/date   date
                         :txn/kind   (clean (or (:kind rec) "unknown"))
                         :txn/detail (clean (:detail rec))
                         :txn/source src
                         :txn/vendor {:org/name vendor}}
                  (month date) (assoc :txn/month (month date))
                  amt          (assoc :txn/amount-jpy amt))))))
        (rd-jsonl src)))))

(defn person-tx []
  (keep (fn [p]
          (let [email (clean (or (:name_or_email p) (:email p)))
                nm    (clean (:name_or_email p))]
            (when email
              (cond-> {:person/email email}
                nm                  (assoc :person/name nm)
                (:role p)           (assoc :person/role (clean (:role p)))
                (:relation p)       (assoc :person/role (clean (:relation p)))))))
        (:people (rd-json "analysis/entities.json"))))

(defn org-tx []
  (keep (fn [o]
          (when-let [nm (clean (:name o))]
            (cond-> {:org/name nm}
              (:type o) (assoc :org/type (clean (:type o))))))
        (:organizations (rd-json "analysis/entities.json"))))

(defn loan-tx []
  (keep-indexed
    (fn [i a]
      (when (:amount_jpy a)
        (cond-> {:loan/id (str "loan#" i "_" (:date a) "_" (:amount_jpy a))
                 :loan/amount-jpy (long (:amount_jpy a))}
          (:date a) (assoc :loan/date (clean (:date a))))))
    (:agreements (rd-json "analysis/loan-ledger.json"))))

(defn action-tx []
  (keep (fn [a]
          (when (:id a)
            {:action/id (clean (:id a))
             :action/title (clean (:title a))
             :action/priority (clean (:priority a))
             :action/category (clean (:category a))}))
        (:actions (rd-json "analysis/action-register.json"))))

(defn event-tx []
  (apply concat
    (for [f ["calendar/events.json" "calendar/events-2026q1.json" "calendar/events-2025h2.json"
             "calendar/events-2025h1.json" "calendar/events-2024.json" "calendar/events-2023.json"]
          :let [j (rd-json f)] :when j]
      (apply concat
        (for [cat [:travel :meetings :leisure :recurring :events]
              :let [items (get j cat)] :when (sequential? items)]
          (keep-indexed
            (fn [i e]
              (let [summ (clean (or (:summary e) (:detail e)))]
                (when summ
                  (cond-> {:event/id (str f "#" (name cat) "#" i)
                           :event/summary summ
                           :event/category (name cat)}
                    (or (:start e) (:date e) (:dates e))
                    (assoc :event/start (clean (or (:start e) (:date e) (:dates e))))))))
            items))))))

;; ---------- main ----------
(defn -main [& _]
  (let [client (d/client {:server-type :datomic-local :storage-dir :mem :system "personal-warehouse"})]
    (d/create-database client {:db-name "warehouse"})
    (let [conn (d/connect client {:db-name "warehouse"})]
      (d/transact conn {:tx-data schema})
      ;; reference data first (orgs/persons), then facts
      (doseq [[label tx] [["orgs" (org-tx)] ["persons" (person-tx)]
                          ["txns" (txn-tx)] ["loans" (loan-tx)]
                          ["actions" (action-tx)] ["events" (event-tx)]]]
        (when (seq tx)
          (d/transact conn {:tx-data (vec tx)})
          (println (format "  loaded %-8s %d" label (count tx)))))
      (let [db (d/db conn)
            n  (fn [q] (or (ffirst (d/q q db)) 0))
            sum (fn [q] (or (first (first (d/q q db))) 0))]
        (println "\n=== Datomic 正規化サマリ (datomic:local mem) ===")
        (println "entities:")
        (println "  orgs   " (n '[:find (count ?e) :where [?e :org/name]]))
        (println "  persons" (n '[:find (count ?e) :where [?e :person/email]]))
        (println "  txns   " (n '[:find (count ?e) :where [?e :txn/id]]))
        (println "  loans  " (n '[:find (count ?e) :where [?e :loan/id]]))
        (println "  events " (n '[:find (count ?e) :where [?e :event/id]]))
        (println "  actions" (n '[:find (count ?e) :where [?e :action/id]]))
        (println "\ntxn amount coverage:")
        (println "  txns with parsed amount:" (n '[:find (count ?t) :where [?t :txn/amount-jpy]]))
        (println "  sum of parsed amounts ¥:" (sum '[:find (sum ?a) :where [?t :txn/amount-jpy ?a]]))
        (println "\n[Datalog] 月次アウトフロー上位 (parsed amounts):")
        (doseq [[m s] (->> (d/q '[:find ?m (sum ?a) :where [?t :txn/month ?m] [?t :txn/amount-jpy ?a]] db)
                           (sort-by second >) (take 6))]
          (println (format "  %s  ¥%,d" m (long s))))
        (println "\n[Datalog] ベンダー別合計 上位 (org でリンク済):")
        (doseq [[nm s] (->> (d/q '[:find ?nm (sum ?a)
                                   :where [?t :txn/vendor ?o] [?o :org/name ?nm] [?t :txn/amount-jpy ?a]] db)
                            (sort-by second >) (take 8))]
          (println (format "  %-28s ¥%,d" nm (long s))))
        (println "\n[Datalog] 借入台帳: 件数/総額:")
        (println (format "  loans=%d  total=¥%,d"
                         (n '[:find (count ?l) :where [?l :loan/id]])
                         (long (sum '[:find (sum ?a) :where [?l :loan/amount-jpy ?a]]))))
        (println "\n[Datalog] P0 アクション:")
        (doseq [[t] (d/q '[:find ?t :where [?a :action/priority "P0_urgent"] [?a :action/title ?t]] db)]
          (println "  -" t))
        (println "\nOK: normalized into Datomic. Rebuild anytime: clojure -M -m warehouse.load")))))
