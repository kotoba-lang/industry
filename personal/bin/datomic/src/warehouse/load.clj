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
            [clojure.edn :as edn]
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

;; ---------- schema (declarative, loaded from edn) ----------
;; Relationships/refs live in bin/datomic/schema.edn (graph summary: model.edn).
;; ADR-0010: schema-life.edn adds goal/obligation/decision/kpi/prov + person aliases.
(def schema
  (vec (concat (edn/read-string (slurp (io/file base "bin/datomic/schema.edn")))
               (edn/read-string (slurp (io/file base "bin/datomic/schema-life.edn"))))))

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

;; ---------- email (cid/eml) + case ----------
(defn- addr [s] (when s (let [m (re-find #"<([^>]+)>" (str s))] (clean (if m (second m) s)))))

(defn- email->case [rec]
  (let [hay (str (:subject rec) " " (str/join " " (:labels rec)))]
    (when (re-find #"(?i)lingling|訴訟" hay) {:case/id "lingling"})))

(defn email-tx []
  (keep
    (fn [rec]
      (when-let [cid (:cid rec)]
        (cond-> {:email/cid        cid
                 :email/message-id (or (clean (:message_id rec)) cid)
                 :email/blob       (cond-> {:blob/cid cid}
                                     (:eml_path rec) (assoc :blob/path (clean (:eml_path rec)))
                                     (:size rec)     (assoc :blob/size (long (:size rec))))}
          (:thread_id rec) (assoc :email/thread-id (clean (:thread_id rec)))
          (:date rec)      (assoc :email/date (clean (:date rec)))
          (:subject rec)   (assoc :email/subject (clean (:subject rec)))
          (seq (:labels rec)) (assoc :email/labels (vec (keep clean (:labels rec))))
          (addr (:from rec))  (assoc :email/from {:person/email (addr (:from rec))})
          (seq (:to rec))  (assoc :email/to (vec (for [t (:to rec) :let [a (addr t)] :when a] {:person/email a})))
          (seq (:cc rec))  (assoc :email/cc (vec (for [t (:cc rec) :let [a (addr t)] :when a] {:person/email a})))
          (email->case rec) (assoc :email/case (email->case rec)))))
    (rd-jsonl "mail/messages/index.jsonl")))

(defn case-tx []
  (when (seq (email-tx))
    [{:case/id "lingling" :case/name "LingLing訴訟" :case/kind "litigation"}]))

;; ---------- life graph (ADR-0010: goals.edn + facts/obligations.jsonl) ----------
(def goals-edn
  (let [f (io/file base "../kawasakijun/goals.edn")]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn goal-tx []
  (vec (:goals goals-edn)))

(defn goal-edge-tx []
  ;; separate tx: lookup refs need goals already transacted
  (vec (for [[pre dep] (:edges goals-edn)]
         {:db/id [:goal/id dep] :goal/depends-on [[:goal/id pre]]})))

(defn- iso->inst [s]
  (java.util.Date/from (.toInstant (java.time.OffsetDateTime/parse s))))

;; people.edn: curated person registry. Pass 1 = canonical entities
;; (primary email keeps :person/email identity so email-tx stubs upsert into
;; the same entity). Pass 2 = alias addresses as thin entities pointing to
;; canonical via :person/canonical (entity resolution, ADR-0010).
(def people-edn
  (let [f (io/file base "facts/people.edn")]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn people-tx []
  (vec (for [p people-edn]
         (cond-> (dissoc p :emails)
           (seq (:emails p)) (assoc :person/email (first (:emails p)))))))

;; dyads.edn: power-dynamics edges + falsifiable hypotheses (ADR-0012).
(def dyads-edn
  (let [f (io/file base "facts/dyads.edn")]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn dyad-tx []
  (vec (for [d (:dyads dyads-edn)]
         (assoc d :dyad/with [:person/id (:dyad/with d)]))))

(defn hypothesis-tx []
  ;; :evidence = vector of email cids (mail/messages/<cid>.eml) -> lookup refs.
  (vec (for [h (:hypotheses dyads-edn)]
         (cond-> (-> h
                     (assoc :hypothesis/about (vec (for [pid (:about h)] [:person/id pid])))
                     (dissoc :about :evidence))
           (seq (:evidence h))
           (assoc :hypothesis/evidence (vec (for [c (:evidence h)] [:email/cid c])))))))

;; kpi.jsonl: sensor/self-report time-series (ADR-0014 felt-sense, wellness-8 etc.)
(defn kpi-tx []
  (keep (fn [k]
          (when (and (:metric k) (:at k) (number? (:value k)))
            {:kpi/id     (str (:metric k) "/" (:at k))
             :kpi/metric (keyword (:metric k))
             :kpi/value  (double (:value k))
             :kpi/at     (iso->inst (:at k))}))
        (rd-jsonl "facts/kpi.jsonl")))

;; orgs.edn / accounts.edn / contracts.edn — curated entity registries (ADR-0015 + 整理)
(defn- rd-edn-facts [rel]
  (let [f (io/file base rel)] (when (.exists f) (edn/read-string (slurp f)))))

(defn channel-tx []
  (vec (rd-edn-facts "facts/channels.edn")))

(defn curated-org-tx []
  (vec (rd-edn-facts "facts/orgs.edn")))

(defn account2-tx []
  (vec (for [a (rd-edn-facts "facts/accounts.edn")] a)))  ; refs already in lookup-ref form

(defn contract-tx []
  (vec (for [c (rd-edn-facts "facts/contracts.edn")]
         (cond-> c
           (:contract/case c) (assoc :contract/case {:case/id (:contract/case c)})))))

;; processes.edn: handoff state machine + capability policy (ADR-0015)
(def processes-edn
  (let [f (io/file base "facts/processes.edn")]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn capability-tx [] (vec (:capabilities processes-edn)))

(defn process-tx []
  (vec (for [p (:processes processes-edn)]
         (cond-> (dissoc p :steps)
           (:process/goal p) (assoc :process/goal [:goal/id (:process/goal p)])))))

(defn step-tx []
  ;; pass 1: steps without :step/needs (so the unique :step/id exists in db)
  (vec (for [p (:processes processes-edn)
             s (:steps p)]
         (cond-> (-> s (dissoc :step/needs) (assoc :step/process [:process/id (:process/id p)]))
           (:step/capability s) (assoc :step/capability [:capability/id (:step/capability s)])))))

(defn step-needs-tx []
  ;; pass 2: add prerequisite edges via lookup ref (steps now exist)
  (vec (for [p (:processes processes-edn)
             s (:steps p) :when (seq (:step/needs s))]
         {:step/id (:step/id s)
          :step/needs (vec (for [n (:step/needs s)] [:step/id n]))})))

;; engi.edn: tie-release evaluations (ADR-0013)
(defn engi-tx []
  (let [f (io/file base "facts/engi.edn")]
    (when (.exists f)
      (vec (for [e (edn/read-string (slurp f))]
             (if-let [pid (:engi/person e)]
               (assoc e :engi/person [:person/id pid])
               e))))))

;; thread_id -> latest cid (for obligation source resolution via index.jsonl)
(defn thread->cid []
  (->> (rd-jsonl "mail/messages/index.jsonl")
       (sort-by #(str (:date %)))
       (reduce (fn [m r] (if (:thread_id r) (assoc m (:thread_id r) (:cid r)) m)) {})))

(defn people-alias-tx []
  (vec (for [p people-edn
             alias (rest (:emails p))]
         (cond-> {:person/email alias
                  :person/canonical [:person/id (:person/id p)]}
           (:person/relation p)        (assoc :person/relation (:person/relation p))
           (:person/attention-class p) (assoc :person/attention-class (:person/attention-class p))))))

(defn obligation-tx []
  (let [t->c (thread->cid)]
    (keep (fn [o]
            (when (and (:id o) (:due o))
              (let [src (or (:source_cid o) (t->c (:source_thread o)))]
                (cond-> {:obligation/id       (clean (:id o))
                         :obligation/title    (clean (:title o))
                         :obligation/due      (iso->inst (:due o))
                         :obligation/severity (keyword (or (:severity o) "normal"))
                         :obligation/status   (keyword (or (:status o) "open"))}
                  (:case o) (assoc :obligation/case {:case/id (clean (:case o))})
                  (:goal o) (assoc :obligation/goal [:goal/id (clean (:goal o))])
                  src       (assoc :obligation/source [:email/cid src])))))
          (rd-jsonl "facts/obligations.jsonl"))))

;; ---------- main ----------
(defn -main [& _]
  (let [client (d/client {:server-type :datomic-local :storage-dir :mem :system "personal-warehouse"})]
    (d/create-database client {:db-name "warehouse"})
    (let [conn (d/connect client {:db-name "warehouse"})]
      (d/transact conn {:tx-data schema})
      ;; reference data first (orgs/persons), then facts
      (doseq [[label tx] [["orgs" (org-tx)] ["persons" (person-tx)]
                          ["txns" (txn-tx)] ["loans" (loan-tx)]
                          ["actions" (action-tx)] ["events" (event-tx)]
                          ["cases" (case-tx)] ["emails" (email-tx)]
                          ["people" (people-tx)] ["people-aliases" (people-alias-tx)]
                          ["channels" (channel-tx)] ["orgs(curated)" (curated-org-tx)]
                          ["accounts" (account2-tx)] ["contracts" (contract-tx)]
                          ["goals" (goal-tx)] ["goal-deps" (goal-edge-tx)]
                          ["obligations" (obligation-tx)]
                          ["dyads" (dyad-tx)] ["hypotheses" (hypothesis-tx)]
                          ["engi" (engi-tx)] ["kpi" (kpi-tx)]
                          ["capabilities" (capability-tx)] ["processes" (process-tx)]
                          ["steps" (step-tx)] ["step-deps" (step-needs-tx)]]]
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
        (println "  emails " (n '[:find (count ?e) :where [?e :email/cid]]))
        (println "  cases  " (n '[:find (count ?e) :where [?e :case/id]]))
        (println "\n[Datalog] email -> case (cid/eml evidence linked by ref):")
        (doseq [[subj cnm cid] (d/q '[:find ?subj ?cnm ?cid
                                      :where [?e :email/case ?c] [?c :case/name ?cnm]
                                             [?e :email/subject ?subj] [?e :email/cid ?cid]] db)]
          (println (format "  [%s] %s  (cid %s…)" cnm subj (subs cid 0 12))))
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
        (println "\n=== Life graph (ADR-0010) ===")
        (println "  people     " (n '[:find (count ?p) :where [?p :person/id]])
                 " aliases" (n '[:find (count ?p) :where [?p :person/canonical]]))
        (println "\n[Datalog] 人物レジストリ (relation / attention-class):")
        (doseq [[rel ps] (->> (d/q '[:find ?rel ?nm ?cls
                                     :where [?p :person/id] [?p :person/relation ?rel]
                                            [?p :person/name ?nm]
                                            [?p :person/attention-class ?cls]] db)
                              (group-by first) (sort-by key))]
          (println (format "  %s:" (name rel)))
          (doseq [[_ nm cls] (sort-by second ps)]
            (println (format "    %-38s [%s]" nm (name cls)))))
        (println "  goals      " (n '[:find (count ?g) :where [?g :goal/id]]))
        (println "  dag-edges  " (n '[:find (count ?d) :with ?g :where [?g :goal/depends-on ?d]]))
        (println "  obligations" (n '[:find (count ?o) :where [?o :obligation/id]]))
        (println "\n[Datalog] attention/queue — open obligations (ADR-0014: tier→期限の辞書式順):")
        (doseq [[title due sev tier] (->> (d/q '[:find ?title ?due ?sev ?tier
                                                 :where [?o :obligation/status :open]
                                                        [?o :obligation/title ?title]
                                                        [?o :obligation/due ?due]
                                                        [?o :obligation/severity ?sev]
                                                        (or-join [?o ?tier]
                                                          (and [?o :obligation/goal ?g]
                                                               [(get-else $ ?g :goal/tier 2) ?tier])
                                                          (and [(missing? $ ?o :obligation/goal)]
                                                               [(ground 2) ?tier]))] db)
                                          (sort-by (fn [[_ due _ tier]] [tier due])))]
          (println (format "  T%d %-8s %tF  %s" tier (name sev) due title)))
        (let [fs (d/q '[:find ?at ?v
                        :where [?k :kpi/metric :wellbecoming.felt-sense]
                               [?k :kpi/at ?at] [?k :kpi/value ?v]] db)]
          (if (seq fs)
            (println (format "\n  [Tier0] felt-sense 床 (全期間min): %.1f / 直近: %s"
                             (apply min (map second fs))
                             (second (last (sort-by first fs)))))
            (println "\n  [Tier0] felt-sense 未計測 — facts/kpi.jsonl に日次1行 {\"metric\":\"wellbecoming.felt-sense\",\"value\":1-5,\"at\":...} (ADR-0014)")))
        (println "\n[Datalog] goal/blocked-critical-path — open obligation に塞がれた active goal:")
        (doseq [[g t] (d/q '[:find ?gt ?ot
                             :where [?g :goal/status :active] [?g :goal/title ?gt]
                                    [?o :obligation/goal ?g] [?o :obligation/status :open]
                                    [?o :obligation/title ?ot]] db)]
          (println (format "  %s\n    └─ %s" g t)))
        (println "\n=== Power dynamics (ADR-0012) ===")
        (println "[Datalog] power/balance — 露出順 (balance = their-dep − self-dep):")
        (doseq [[nm sd td cost] (->> (d/q '[:find ?name ?sd ?td ?cost
                                            :where [?d :dyad/with ?p] [?p :person/name ?name]
                                                   [?d :dyad/self-dependence ?sd]
                                                   [?d :dyad/their-dependence ?td]
                                                   [?d :dyad/switching-cost ?cost]] db)
                                     (sort-by (fn [[_ sd td _]] (- td sd))))]
          (println (format "  %+.2f  %-30s self=%.2f their=%.2f switch=%s"
                           (- td sd) nm sd td (name cost))))
        (println "\n[Datalog] power/risk-dyads — 高依存×低一致 (minimax 重点):")
        (doseq [[nm sd al worst] (d/q '[:find ?name ?sd ?al ?worst
                                        :where [?d :dyad/self-dependence ?sd] [(>= ?sd 0.5)]
                                               [?d :dyad/alignment ?al] [(<= ?al 0.55)]
                                               [?d :dyad/with ?p] [?p :person/name ?name]
                                               [?d :dyad/worst-case ?worst]] db)]
          (println (format "  %-22s dep=%.2f align=%.2f\n    ⚠ %s" nm sd al worst)))
        (println "\n[Datalog] power/test-agenda — open 仮説 (|conf−0.5| 小 = 情報利得大):")
        (doseq [[id _ conf fals] (->> (d/q '[:find ?id ?text ?conf ?fals
                                             :where [?h :hypothesis/status :open]
                                                    [?h :hypothesis/id ?id] [?h :hypothesis/text ?text]
                                                    [?h :hypothesis/confidence ?conf]
                                                    [?h :hypothesis/falsifier ?fals]] db)
                                      (sort-by (fn [[_ _ c _]] (Math/abs (- c 0.5)))))]
          (println (format "  [%.2f] %s\n    → %s" conf id fals)))
        (println "\n=== Engi 縁の手放し (ADR-0013) ===")
        (println "[Datalog] engi/sever-queue — 実行可能キュー (月額降順; export-first=✉は保全が前提):")
        (doseq [[t dec cost exp] (->> (d/q '[:find ?target ?decision ?cost ?export
                                             :where [?e :engi/legal-hold false]
                                                    [?e :engi/decision ?decision]
                                                    [(contains? #{:sever :archive :transfer :reduce} ?decision)]
                                                    [?e :engi/target ?target]
                                                    [?e :engi/monthly-cost-jpy ?cost]
                                                    [?e :engi/export-first ?export]] db)
                                      (sort-by (fn [[_ _ c _]] (- c))))]
          (println (format "  %-9s ¥%,7d %s %s" (name dec) cost (if exp "✉" " ") t)))
        (println "\n[Datalog] engi/legal-holds — 係争終結まで操作禁止:")
        (doseq [[t] (d/q '[:find ?target :where [?e :engi/legal-hold true] [?e :engi/target ?target]] db)]
          (println "  🔒" t))
        (let [sv (or (ffirst (d/q '[:find (sum ?cost) :with ?e
                                    :where [?e :engi/legal-hold false] [?e :engi/decision ?d]
                                           [(contains? #{:sever :archive :transfer} ?d)]
                                           [?e :engi/monthly-cost-jpy ?cost]] db)) 0)
              rd (or (ffirst (d/q '[:find (sum ?cost) :with ?e
                                    :where [?e :engi/legal-hold false] [?e :engi/decision :reduce]
                                           [?e :engi/monthly-cost-jpy ?cost]] db)) 0)]
          (println (format "\n  個人負担の削減見込み: 確定系 (sever/archive/transfer) ¥%,d/月 + 縮小余地 (reduce対象) 最大 ¥%,d/月" (long sv) (long rd)))
          (println "  goal 29 KPI: ¥319k → 目標 ¥220k (要実測の概算を含む)"))
        (println "\n=== 組織・アカウント・契約 (entity整理) ===")
        (println "  orgs    " (n '[:find (count ?o) :where [?o :org/id]])
                 " accounts" (n '[:find (count ?a) :where [?a :account/id] [?a :account/reach]])
                 " contracts" (n '[:find (count ?c) :where [?c :contract/id]]))
        (println "[Datalog] account/reachability + 到達チャネル (pending = ingest プロセス対象):")
        (doseq [[id reach st] (->> (d/q '[:find ?id ?reach ?st
                                          :where [?a :account/reach ?reach] [?a :account/id ?id] [?a :account/status ?st]] db)
                                   (sort-by (fn [[_ _ s]] (name s))))]
          (let [chs (->> (d/q '[:find ?cid :in $ ?id
                                :where [?a :account/id ?id] [?a :account/channels ?c] [?c :channel/id ?cid]] db id)
                         (map (comp name first)) sort (clojure.string/join ","))]
            (println (format "  %-24s reach=%-8s %-10s via[%s]" id (name reach) (name st) chs))))
        (println "[Datalog] channels — 実行リソース (agent=私が駆動可か):")
        (doseq [[cid k ag stt] (->> (d/q '[:find ?cid ?k ?ag ?stt
                                           :where [?c :channel/id ?cid] [?c :channel/kind ?k]
                                                  [?c :channel/agent ?ag] [?c :channel/status ?stt]] db)
                                    (sort-by (comp name first)))]
          (println (format "  %-16s %-10s agent=%-4s %s" (name cid) (name k) (name ag) (name stt))))
        (println "[Datalog] org/by-role (own-corp/equity/counterparty 抜粋):")
        (doseq [[role nm] (->> (d/q '[:find ?role ?nm
                                      :where [?o :org/role ?role] [(contains? #{:own-corp :equity :employer :counterparty} ?role)]
                                             [?o :org/name ?nm]] db)
                              (sort-by (comp name first)))]
          (println (format "  %-12s %s" (name role) nm)))
        (println "\n=== Processes / ハンドオフ状態機械 (ADR-0015) ===")
        (println "[Datalog] process/next-human — 本人の手番で止まっている (私が依頼する対象):")
        (doseq [[pt sd cap] (d/q '[:find ?pt ?sd ?cap
                                   :where [?s :step/status :blocked-on-human] [?s :step/actor :jun]
                                          [?s :step/desc ?sd] [?s :step/capability ?c] [?c :capability/id ?cap]
                                          [?s :step/process ?p] [?p :process/title ?pt]] db)]
          (println (format "  ⏳[%s] %s\n      (%s)" pt sd cap)))
        (println "\n[Datalog] process/claude-ready — 前提充足で私が自動実行できる step:")
        (doseq [[pt o sd cap] (->> (d/q '[:find ?pt ?o ?sd ?cap
                                          :where [?s :step/actor :claude]
                                                 [?s :step/status ?st] [(contains? #{:pending :ready} ?st)]
                                                 [?s :step/order ?o] [?s :step/desc ?sd]
                                                 [?s :step/capability ?c] [?c :capability/id ?cap]
                                                 [?s :step/process ?p] [?p :process/title ?pt]
                                                 (not-join [?s] [?s :step/needs ?n] [?n :step/status ?nst] [(not= ?nst :done)])] db)
                                   (sort-by (juxt first second)))]
          (println (format "  ▶[%s] #%d %s (%s)" pt o sd cap)))
        (println "\n[Datalog] policy/agent-prohibited — 機械が代行不可 (安全境界の監査ファクト):")
        (doseq [[cap r] (d/q '[:find ?cap ?r :where [?c :capability/agent :no] [?c :capability/id ?cap] [?c :capability/reason ?r]] db)]
          (println (format "  🔒%-18s %s" cap r)))
        (println "\nOK: normalized into Datomic. Rebuild anytime: clojure -M -m warehouse.load")))))
