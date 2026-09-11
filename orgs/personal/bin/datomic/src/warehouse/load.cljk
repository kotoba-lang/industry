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

(def base "/Users/junkawasaki/github/com-junkawasaki/orgs/personal")

;; ---------- readers ----------
;; annex 状態の判定は2形態を区別する:
;;  - locked (symlink) mode: 未 fetch = broken symlink → (.exists f) が false
;;  - unlocked (pointer) mode: working tree の実体は "/annex/objects/..." 1行の
;;    小ファイル → parse は失敗するが、それは「未 fetch」であってデータ破損ではない
;; どちらも warn+nil で load を続行する。それ以外の parse 失敗（fetch 済み・
;; 通常ファイルの本物の EDN/JSON 構文エラー）は従来どおり fail-fast で abort する
;; — 黙って registry が消えたり、離れた場所の lookup-ref transact エラーになるより、
;; 壊れたファイルを指して止まる方が安全。

(defn- broken-symlink? [^java.io.File f]
  (and (java.nio.file.Files/isSymbolicLink (.toPath f)) (not (.exists f))))

(defn- annex-pointer? [^java.io.File f]
  (and (.isFile f) (< (.length f) 1024)
       (str/starts-with? (slurp f) "/annex/objects/")))

(defn- warn-unfetched [f]
  (println (format "  [skip] %s: unfetched annex content — run: git annex get %s"
                   (.getPath f) (.getPath f))))

(defn- read-checked
  "Read f with parse-fn. Unfetched annex content (broken locked symlink or
   unlocked pointer file) → warn + nil. Missing plain file → nil. Genuine parse
   error in fetched content → print the real error and rethrow (fail fast)."
  [^java.io.File f parse-fn]
  (cond
    (broken-symlink? f) (do (warn-unfetched f) nil)
    (not (.exists f))   nil
    (annex-pointer? f)  (do (warn-unfetched f) nil)
    :else (try (parse-fn (slurp f))
               (catch Exception e
                 (println (format "  [error] %s: %s" (.getPath f) (.getMessage e)))
                 (throw e)))))

(defn rd-jsonl [rel]
  (let [f (io/file base rel)]
    (cond
      (broken-symlink? f) (do (warn-unfetched f) nil)
      (not (.exists f))   nil
      (annex-pointer? f)  (do (warn-unfetched f) nil)
      :else
      (with-open [r (io/reader f)]
        (let [lines  (into [] (remove str/blank?) (line-seq r))
              parsed (into [] (keep #(try (json/read-str % :key-fn keyword)
                                          (catch Exception _ nil)))
                           lines)
              dropped (- (count lines) (count parsed))]
          (when (pos? dropped)
            (println (format "  [warn] %s: %d/%d lines failed JSON parse"
                             (.getPath f) dropped (count lines))))
          parsed)))))

(defn- warn-unreadable [f e]
  (println (format "  [skip] %s: %s (unfetched annex content? run: git annex get %s)"
                    (.getPath f) (.getMessage e) (.getPath f))))

(defn rd-json [rel]
  (read-checked (io/file base rel) #(json/read-str % :key-fn keyword)))

(defn- safe-edn
  "Read+parse an edn file via read-checked: unfetched annex content is skipped
   with a warning; genuine syntax errors abort the load (fail fast)."
  [f]
  (read-checked f edn/read-string))

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
               (edn/read-string (slurp (io/file base "bin/datomic/schema-life.edn")))
               (edn/read-string (slurp (io/file base "bin/datomic/schema-sources.edn")))
               (edn/read-string (slurp (io/file base "bin/datomic/schema-facebook.edn"))))))

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
(defn- normalize-email
  "trim + lowercase + RFC サブアドレス +tag 除去（ADR-0009/0010: ingest 時に正規化。
   jun784+<tag>@gmail.com / JUN784@GMAIL.COM を canonical jun784@gmail.com に揃え、
   case/+tag 違いの spurious person entity を作らない）。"
  [s]
  (when-let [s (clean s)]
    (-> (str/lower-case s) (str/replace #"\+[^@]*@" "@"))))

(defn- addr [s] (when s (let [m (re-find #"<([^>]+)>" (str s))] (normalize-email (if m (second m) s)))))

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
  (safe-edn (io/file base "../kawasakijun/goals.edn")))

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
  (safe-edn (io/file base "facts/people.edn")))

(defn people-tx []
  (vec (for [p people-edn]
         (cond-> (dissoc p :emails)
           (seq (:emails p)) (assoc :person/email (normalize-email (first (:emails p))))))))

;; dyads.edn: power-dynamics edges + falsifiable hypotheses (ADR-0012).
(def dyads-edn
  (safe-edn (io/file base "facts/dyads.edn")))

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
  (safe-edn (io/file base rel)))

(defn channel-tx []
  (vec (rd-edn-facts "facts/channels.edn")))

(defn finitem-tx []
  (vec (rd-edn-facts "facts/corp-finance.edn")))

(defn coverage-tx []
  (vec (rd-edn-facts "facts/coverage.edn")))

;; mail-triage.edn: triage ledger with rule ref + depends-on edges
(defn triage-tx []
  (vec (for [t (rd-edn-facts "facts/mail-triage.edn")]
         (cond-> {:triage/id (:triage/id t)}
           (:from t)       (assoc :triage/from (:from t))
           (:subject t)    (assoc :triage/subject (:subject t))
           (:summary t)    (assoc :triage/summary (:summary t))
           (:class t)      (assoc :triage/class (:class t))
           (:action t)     (assoc :triage/action (:action t))
           (:note t)       (assoc :triage/note (:note t))
           (:rule t)       (assoc :triage/rule [:rule/id (:rule t)])
           (:depends-on t) (assoc :triage/depends-on (vec (:depends-on t)))))))

;; mail-rules.edn: flatten :rule/match map -> match-from/subject/not multi attrs
(defn mailrule-tx []
  (vec (for [r (:rules (rd-edn-facts "facts/mail-rules.edn"))]
         (let [m (:rule/match r)]
           (cond-> (dissoc r :rule/match)
             (seq (:from m))        (assoc :rule/match-from (vec (:from m)))
             (seq (:subject-has m)) (assoc :rule/match-subject (vec (:subject-has m)))
             (seq (:not m))         (assoc :rule/match-not (vec (:not m))))))))

(defn curated-org-tx []
  ;; :org/repo-taxonomy はネスト構造 (ADR-0020)、schema は string → string 以外は形を
  ;; 問わず文字列化（map? 限定だと vector-of-maps が素通りして transact で落ちる）
  (vec (for [o (rd-edn-facts "facts/orgs.edn")
             :let [v (:org/repo-taxonomy o)]]
         (cond-> o (and (some? v) (not (string? v))) (update :org/repo-taxonomy pr-str)))))

(defn account2-tx []
  ;; refs are already lookup-refs; :account/blocker はネスト map → Datomic 用に文字列化
  (vec (for [a (rd-edn-facts "facts/accounts.edn")]
         (cond-> a (map? (:account/blocker a)) (update :account/blocker pr-str)))))

(defn contract-tx []
  (vec (for [c (rd-edn-facts "facts/contracts.edn")]
         (cond-> c
           (:contract/case c) (assoc :contract/case {:case/id (:contract/case c)})))))

;; bank-sources.edn: 金融口座/カード/取引所の収集ソース registry (refs は lookup-ref 形)
(defn bank-tx []
  (vec (rd-edn-facts "facts/bank-sources.edn")))

;; bank-txns.jsonl (pl/bank-txn-ingest: 通知メール→txn) + mf-txns.jsonl (pl/mf-csv-ingest)。
;; MF は全口座集約で最濃 → 両方ある場合は (date,amount) 一致の bank-txn を落とす (二重計上防止)。
(defn bank-txn-tx []
  (let [mf   (or (rd-jsonl "mail/mf-txns.jsonl") [])
        mfk  (set (map (juxt :date :amount) mf))
        bank (->> (or (rd-jsonl "mail/bank-txns.jsonl") [])
                  (remove #(and (:amount %) (contains? mfk [(:date %) (:amount %)]))))
        ->tx (fn [prefix i rec]
               (when (:date rec)
                 (cond-> {:txn/id     (str prefix "/" (or (:cid rec) (:mf-id rec) i)
                                           (when (:seq rec) (str "#" (:seq rec))))
                          :txn/date   (:date rec)
                          :txn/month  (month (:date rec))
                          :txn/kind   (clean (or (:kind rec) (:cat1 rec) "unknown"))
                          :txn/source (or (:source rec) "pl/bank-txn-ingest")}
                   (:amount rec)  (assoc :txn/amount-jpy (long (:amount rec)))
                   (:dir rec)     (assoc :txn/dir (:dir rec))
                   (:bank rec)    (assoc :txn/bank [:bank/id (:bank rec)])
                   (:cid rec)     (assoc :txn/email [:email/cid (:cid rec)])
                   (clean (or (:vendor rec) (:detail rec)))
                   (assoc :txn/detail (clean (str (or (:vendor rec) "") " " (or (:detail rec) (:subject rec) ""))))
                   (clean (:vendor rec)) (assoc :txn/vendor {:org/name (clean (:vendor rec))}))))]
    (vec (concat (keep-indexed (partial ->tx "bank") bank)
                 (keep-indexed (partial ->tx "mf") mf)))))

;; ---------- データソース統一registry (:datasrc) + 連絡先連携 (schema-sources.edn) ----------
;; この session で取り込んだ全源を1グラフに連携し people/dyads と接続 (ADR-0010)。
(def imessage-edn  (rd-edn-facts "facts/imessage.edn"))
(def notes-edn     (rd-edn-facts "facts/notes.edn"))
(def photos-edn    (rd-edn-facts "facts/photos-library.edn"))
(def downloads-edn (rd-edn-facts "facts/downloads-inventory.edn"))

;; ---------- Facebook (DYI HTML export) — social/facebook/<acct>/<date>/index/*.jsonl ----------
;; 正本HTML/メディアは git-annex→B2(暗号化)。索引JSONLを fb* グラフへ正規化し、
;; sender/friend 名を people.edn に name 一致で連携 (ADR-0010, ingest-facebook.bb 生成)。
(def facebook-edn (rd-edn-facts "facts/facebook.edn"))

(def ^:private fb-index-rel
  (when facebook-edn (str/replace (:facebook/index-path facebook-edn) #"^orgs/personal/" "")))

(defn- fb-jsonl [cat]
  (when fb-index-rel (or (rd-jsonl (str fb-index-rel "/" cat ".jsonl")) [])))

(defn- norm-name [s] (when s (-> (str s) (str/replace "﨑" "崎") (str/replace #"\s+" ""))))

(def ^:private fbname->pid
  ;; index both :person/name と curated :person/fb-name エイリアス (norm-name 正規化)
  (into {} (concat
             (for [p people-edn :when (:person/name p)]
               [(norm-name (:person/name p)) (:person/id p)])
             (for [p people-edn, fbn (:person/fb-name p)]
               [(norm-name fbn) (:person/id p)]))))

(defn- fb-acct [] (:facebook/account facebook-edn "jun784"))

(defn facebook-profile-tx []
  (vec (for [r (fb-jsonl "profile") :when (:field r)]
         {:fbprofile/id      (str "fb/" (fb-acct) "/profile/" (:field r))
          :fbprofile/account (fb-acct)
          :fbprofile/field   (:field r)
          :fbprofile/value   (str (:value r))})))

(defn facebook-friend-tx []
  (vec (for [[i r] (map-indexed vector (fb-jsonl "friends")) :when (:name r)]
         (let [pid (fbname->pid (norm-name (:name r)))]
           (cond-> {:fbfriend/id      (str "fb/" (fb-acct) "/friend/" (:rel r) "/" i)
                    :fbfriend/account (fb-acct)
                    :fbfriend/name    (:name r)
                    :fbfriend/rel     (keyword (:rel r))}
             (:ts r) (assoc :fbfriend/ts (:ts r))
             pid     (assoc :fbfriend/person [:person/id pid]))))))

(defn facebook-thread-tx []
  (vec (for [r (fb-jsonl "threads")]
         (cond-> {:fbthread/id            (str "fb/" (fb-acct) "/thread/" (:thread r))
                  :fbthread/account       (fb-acct)
                  :fbthread/message-count (long (or (:messages r) 0))}
           (:box r)                (assoc :fbthread/box (keyword (:box r)))
           (:title r)              (assoc :fbthread/title (:title r))
           (seq (:participants r)) (assoc :fbthread/participants (vec (:participants r)))
           (:from r)               (assoc :fbthread/from (:from r))
           (:to r)                 (assoc :fbthread/to (:to r))
           (:path r)               (assoc :fbthread/path (:path r))))))

(defn facebook-message-tx []
  (->> (fb-jsonl "messages")
       (map-indexed
         (fn [i r]
           (let [pid (fbname->pid (norm-name (:sender r)))]
             (cond-> {:fbmsg/id     (str "fb/" (:thread r) "/" i)
                      :fbmsg/thread [:fbthread/id (str "fb/" (fb-acct) "/thread/" (:thread r))]}
               (:box r)    (assoc :fbmsg/box (keyword (:box r)))
               (:sender r) (assoc :fbmsg/sender (:sender r))
               (:text r)   (assoc :fbmsg/text (:text r))
               (:ts r)     (assoc :fbmsg/ts (:ts r))
               (:media r)  (assoc :fbmsg/media (long (:media r)))
               pid         (assoc :fbmsg/person [:person/id pid])))))
       vec))

(defn facebook-post-tx []
  (vec (for [[i r] (map-indexed vector (fb-jsonl "posts"))]
         (cond-> {:fbpost/id      (str "fb/" (fb-acct) "/post/" i)
                  :fbpost/account (fb-acct)}
           (:ts r)          (assoc :fbpost/ts (:ts r))
           (:text r)        (assoc :fbpost/text (:text r))
           (seq (:links r)) (assoc :fbpost/links (vec (:links r)))
           (:file r)        (assoc :fbpost/file (:file r))))))

(defn facebook-activity-tx []
  (vec (for [[i r] (map-indexed vector (fb-jsonl "activity"))]
         (cond-> {:fbact/id       (str "fb/" (fb-acct) "/act/" i)
                  :fbact/account  (fb-acct)
                  :fbact/category (keyword (:category r))}
           (:ts r)   (assoc :fbact/ts (:ts r))
           (:name r) (assoc :fbact/name (:name r))
           (:text r) (assoc :fbact/text (:text r))
           (:file r) (assoc :fbact/file (:file r))))))

(defn datasrc-tx []
  (->> [(when imessage-edn
          {:datasrc/id "src/imessage" :datasrc/name "iMessage / SMS" :datasrc/kind :comms
           :datasrc/status :ingested :datasrc/backed-up true
           :datasrc/count (:imessage/messages imessage-edn)
           :datasrc/from (get-in imessage-edn [:imessage/date-range :from])
           :datasrc/to   (get-in imessage-edn [:imessage/date-range :to])
           :datasrc/path "orgs/personal/comms/imessage/index.jsonl"
           :datasrc/note "本文は attributedBody デコード。people 連携=contact"})
        (when notes-edn
          {:datasrc/id "src/notes" :datasrc/name "Apple Notes" :datasrc/kind :notes
           :datasrc/status :ingested :datasrc/backed-up true
           :datasrc/count (:notes/count notes-edn)
           :datasrc/from (get-in notes-edn [:notes/date-range :from])
           :datasrc/to   (get-in notes-edn [:notes/date-range :to])
           :datasrc/path "orgs/personal/notes/index.jsonl"})
        (when photos-edn
          {:datasrc/id "src/photos" :datasrc/name "Apple Photos" :datasrc/kind :photos
           :datasrc/status (:photos/ingest-policy photos-edn)
           :datasrc/count (:photos/assets photos-edn) :datasrc/size-gb (:photos/originals-gb photos-edn)
           :datasrc/from (get-in photos-edn [:photos/year-range :from])
           :datasrc/to   (get-in photos-edn [:photos/year-range :to])
           :datasrc/path "orgs/personal/photos/index.jsonl" :datasrc/backed-up true
           :datasrc/note "索引のみ取込。原本270GBはiCloud退避 (:metadata-only)"})
        (when downloads-edn
          {:datasrc/id "src/downloads" :datasrc/name "~/Downloads 整理" :datasrc/kind :files
           :datasrc/status :ingested :datasrc/backed-up true
           :datasrc/count (get-in downloads-edn [:inventory/total :items])
           :datasrc/size-gb (double (get-in downloads-edn [:inventory/dedup :new-gb] 0))
           :datasrc/path "facts/downloads-inventory.edn"
           :datasrc/note "29.4GB→1.2GB。cid-dedup後 org/project へ再配置"})
        {:datasrc/id "src/mail" :datasrc/name "Gmail (jun784 x3)" :datasrc/kind :mail
         :datasrc/status :ingested :datasrc/count (count (or (rd-jsonl "mail/messages/index.jsonl") []))
         :datasrc/path "mail/messages/index.jsonl" :datasrc/backed-up true
         :datasrc/note "mail-sync 日次"}
        {:datasrc/id "src/gdrive" :datasrc/name "Google Drive" :datasrc/kind :files
         :datasrc/status :covered :datasrc/count 57565
         :datasrc/path "orgs/personal/takeout/jun784/2026-06-10/Takeout/ドライブ" :datasrc/backed-up true
         :datasrc/note "Takeout 2026-06-10 で取込済 (訴訟ドラフト含む)。ライブ同期は冗長"}
        {:datasrc/id "src/tanabe-3d" :datasrc/name "tanabe-3d (3Dパイプライン)" :datasrc/kind :files
         :datasrc/status :ingested :datasrc/path "orgs/com-junkawasaki/tanabe-3d" :datasrc/backed-up true
         :datasrc/note "野良プロジェクトをmonorepo収録。output 4.2G除外"}
        (when facebook-edn
          {:datasrc/id "src/facebook"
           :datasrc/name (str "Facebook DYI (" (fb-acct) ")")
           :datasrc/kind :comms :datasrc/status :ingested :datasrc/backed-up true
           :datasrc/count (get-in facebook-edn [:facebook/counts :messages])
           :datasrc/size-gb 0.358
           :datasrc/from (get-in facebook-edn [:facebook/messages-date-range :from])
           :datasrc/to   (get-in facebook-edn [:facebook/messages-date-range :to])
           :datasrc/path (:facebook/index-path facebook-edn)
           :datasrc/note (str "HTML export 全取込。threads="
                              (get-in facebook-edn [:facebook/counts :threads])
                              " friends=" (get-in facebook-edn [:facebook/counts :friends])
                              " posts=" (get-in facebook-edn [:facebook/counts :posts])
                              " activity=" (get-in facebook-edn [:facebook/counts :activity])
                              "。生HTML/メディアはB2(annex)、ローカルにも保持")})]
       (keep identity) vec))

;; people.edn から email/名前 → person-id の解決表
(def email->pid
  (into {} (for [p people-edn, e (:emails p)] [(normalize-email e) (:person/id p)])))
(def name->pid
  (into {} (for [p people-edn :when (:person/name p)]
             [(first (str/split (:person/name p) #"\s|\(")) (:person/id p)])))

(defn contact-tx []
  ;; iMessage top-contacts → :contact。email一致で person 解決 (電話番号は people.edn に無く未解決)
  (vec (for [[handle cnt] (:imessage/top-contacts imessage-edn)
             :let [pid (email->pid (normalize-email (str handle)))]]
         (cond-> {:contact/id (str "imsg/" handle) :contact/handle handle
                  :contact/msg-count cnt :contact/source :imessage}
           pid (assoc :contact/person [:person/id pid])))))

(defn person-media-link-tx []
  ;; Photos top-persons の登場枚数を person に連携 (名前一致)
  (vec (for [[nm cnt] (:photos/top-persons photos-edn)
             :let [pid (or (name->pid nm) (name->pid (first (str/split (str nm) #"\s"))))]
             :when pid]
         {:person/id pid :person/photo-count cnt})))

;; processes.edn: handoff state machine + capability policy (ADR-0015)
(def processes-edn
  (safe-edn (io/file base "facts/processes.edn")))

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
  (vec (for [e (safe-edn (io/file base "facts/engi.edn"))]
         (if-let [pid (:engi/person e)]
           (assoc e :engi/person [:person/id pid])
           e))))

;; thread_id -> latest cid (for obligation source resolution via index.jsonl)
(defn thread->cid []
  (->> (rd-jsonl "mail/messages/index.jsonl")
       (sort-by #(str (:date %)))
       (reduce (fn [m r] (if (:thread_id r) (assoc m (:thread_id r) (:cid r)) m)) {})))

(defn people-alias-tx []
  ;; 正規化で primary と同一になった alias（+tag 違い等）は除外 — 自己参照
  ;; :person/canonical を canonical entity に upsert しないため
  (vec (for [p people-edn
             :let [primary (normalize-email (first (:emails p)))]
             alias (->> (rest (:emails p))
                        (keep normalize-email)
                        (remove #{primary})
                        distinct)]
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
                  (seq (:blocked_by o)) (assoc :obligation/blocked-by (vec (:blocked_by o)))
                  (:funded_by o) (assoc :obligation/funded-by (:funded_by o))
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
                          ["banks" (bank-tx)] ["bank-txns" (bank-txn-tx)]
                          ["datasrc" (datasrc-tx)] ["contacts" (contact-tx)]
                          ["person-media" (person-media-link-tx)]
                          ["accounts" (account2-tx)] ["contracts" (contract-tx)] ["finitems" (finitem-tx)]
                          ["mailrules" (mailrule-tx)] ["triage" (triage-tx)] ["coverage" (coverage-tx)]
                          ["goals" (goal-tx)] ["goal-deps" (goal-edge-tx)]
                          ["obligations" (obligation-tx)]
                          ["dyads" (dyad-tx)] ["hypotheses" (hypothesis-tx)]
                          ["engi" (engi-tx)] ["kpi" (kpi-tx)]
                          ["capabilities" (capability-tx)] ["processes" (process-tx)]
                          ["steps" (step-tx)] ["step-deps" (step-needs-tx)]
                          ;; Facebook: threads before messages (lookup-ref dependency)
                          ["fb-profile" (facebook-profile-tx)] ["fb-friends" (facebook-friend-tx)]
                          ["fb-threads" (facebook-thread-tx)] ["fb-messages" (facebook-message-tx)]
                          ["fb-posts" (facebook-post-tx)] ["fb-activity" (facebook-activity-tx)]]]
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
        (println "\n=== 銀行口座 (bank-sources.edn + pl/bank-txn-ingest) ===")
        (println "[Datalog] bank/coverage — 口座別 取込txn件数/金額 (dir=out|in; failed除外):")
        (doseq [[bid st cnt amt] (->> (d/q '[:find ?bid ?st (count ?t) (sum ?a)
                                             :where [?b :bank/id ?bid] [?b :bank/status ?st]
                                                    [?t :txn/bank ?b]
                                                    (or-join [?t ?a]
                                                      [?t :txn/amount-jpy ?a]
                                                      (and [(missing? $ ?t :txn/amount-jpy)] [(ground 0) ?a]))] db)
                                      (sort-by (fn [[_ _ _ a]] (- a))))]
          (println (format "  %-22s %-9s ×%-4d ¥%,d" bid (name st) cnt (long amt))))
        (let [dark (d/q '[:find ?bid ?nm :where [?b :bank/status :dark] [?b :bank/id ?bid] [?b :bank/name ?nm]] db)]
          (when (seq dark)
            (println "  通知なし=dark (MF CSV で埋まる):")
            (doseq [[bid nm] (sort dark)] (println (format "    %-20s %s" bid nm)))))
        (println "[Datalog] bank/failed — 残高不足等の失敗イベント (要対応シグナル):")
        (doseq [[d* k det] (->> (d/q '[:find ?d ?k ?det
                                       :where [?t :txn/dir "failed"] [?t :txn/date ?d] [?t :txn/kind ?k]
                                              (or-join [?t ?det]
                                                [?t :txn/detail ?det]
                                                (and [(missing? $ ?t :txn/detail)] [(ground "") ?det]))] db)
                                (sort-by first) reverse (take 6))]
          (println (format "  %s %-8s %s" d* k (subs det 0 (min 40 (count det))))))
        (println "\n=== 情報収集カバレッジ (coverage.edn) ===")
        (doseq [[st cnt] (->> (d/q '[:find ?st (count ?s) :where [?s :source/status ?st]] db)
                              (sort-by (fn [[s _]] ({:ingested 0 :archived 1 :partial 2 :dark 3 :n-a 4} s 9))))]
          (println (format "  %-9s ×%d" (name st) cnt)))
        (println "  次に取るべき源 (dark, agent別):")
        (doseq [[id ag nt] (->> (d/q '[:find ?id ?ag ?nt
                                       :where [?s :source/status :dark] [?s :source/id ?id]
                                              [?s :source/agent ?ag] [?s :source/note ?nt]] db)
                               (sort-by (comp name second)))]
          (println (format "    [%s] %-26s %s" (name ag) id (subs nt 0 (min 40 (count nt))))))
        (println "\n=== データソース統一registry (schema-sources.edn; 全取込源を1グラフに) ===")
        (println "[Datalog] datasrc/inventory — 種別・状態・件数・B2バックアップ:")
        (doseq [[id knd st cnt bu] (->> (d/q '[:find ?id ?knd ?st ?cnt ?bu
                                               :where [?d :datasrc/id ?id] [?d :datasrc/kind ?knd]
                                                      [?d :datasrc/status ?st]
                                                      (or-join [?d ?cnt] [?d :datasrc/count ?cnt]
                                                        (and [(missing? $ ?d :datasrc/count)] [(ground 0) ?cnt]))
                                                      (or-join [?d ?bu] [?d :datasrc/backed-up ?bu]
                                                        (and [(missing? $ ?d :datasrc/backed-up)] [(ground false) ?bu]))] db)
                                        (sort-by (fn [[_ k _ c _]] [(name k) (- c)])))]
          (println (format "  %-14s %-9s %-13s %7d件 %s" id (name knd) (name st) cnt (if bu "B2✓" ""))))
        (println "[Datalog] comms/contacts → person 連携 (iMessage上位; 解決済のみ):")
        (doseq [[h c nm] (->> (d/q '[:find ?h ?c ?nm
                                     :where [?ct :contact/handle ?h] [?ct :contact/msg-count ?c]
                                            [?ct :contact/person ?p] [?p :person/name ?nm]] db)
                              (sort-by (fn [[_ c _]] (- c))))]
          (println (format "  %-28s %5d件 → %s" h c nm)))
        (let [unres (or (ffirst (d/q '[:find (count ?ct) :where [?ct :contact/id]
                                       [(missing? $ ?ct :contact/person)]] db)) 0)]
          (println (format "  (未解決 contact %d 件 = 電話番号中心; people.edn に番号追加で連携可)" unres)))
        (println "[Datalog] person/media-presence — Photos/iMessage 出現量 (人物グラフ連携):")
        (doseq [[nm pc] (->> (d/q '[:find ?nm ?pc :where [?p :person/photo-count ?pc] [?p :person/name ?nm]] db)
                             (sort-by (fn [[_ pc]] (- pc))))]
          (println (format "  %-30s 写真 %d枚" nm pc)))
        (when (pos? (n '[:find (count ?t) :where [?t :fbthread/id]]))
          (println "\n=== Facebook (DYI export; fb* グラフ) ===")
          (println (format "  threads %d  messages %d  friends %d  posts %d  activity %d  profile %d"
                           (n '[:find (count ?e) :where [?e :fbthread/id]])
                           (n '[:find (count ?e) :where [?e :fbmsg/id]])
                           (n '[:find (count ?e) :where [?e :fbfriend/id]])
                           (n '[:find (count ?e) :where [?e :fbpost/id]])
                           (n '[:find (count ?e) :where [?e :fbact/id]])
                           (n '[:find (count ?e) :where [?e :fbprofile/id]])))
          (println "[Datalog] fbthread by volume (タイトル・件数・期間):")
          (doseq [[t c f to] (->> (d/q '[:find ?t ?c ?f ?to
                                         :where [?e :fbthread/message-count ?c] [?e :fbthread/from ?f]
                                                [?e :fbthread/to ?to]
                                                (or-join [?e ?t] [?e :fbthread/title ?t]
                                                  (and [(missing? $ ?e :fbthread/title)] [(ground "(無題)") ?t]))] db)
                                  (sort-by (fn [[_ c _ _]] (- c))) (take 12))]
            (println (format "  %5d件 %-32s %s〜%s" c (subs t 0 (min 32 (count t))) (subs f 0 10) (subs to 0 10))))
          (println "[Datalog] fbmsg sender → person 連携 (people.edn 解決済 上位):")
          (doseq [[nm c] (->> (d/q '[:find ?nm (count ?m)
                                     :where [?m :fbmsg/person ?p] [?p :person/name ?nm]] db)
                              (sort-by (fn [[_ c]] (- c))) (take 10))]
            (println (format "  %-24s %d通" nm c)))
          (println "[Datalog] fbfriend → person 連携件数 / 未解決:")
          (let [r (n '[:find (count ?f) :where [?f :fbfriend/person _]])
                u (n '[:find (count ?f) :where [?f :fbfriend/id] [(missing? $ ?f :fbfriend/person)]])]
            (println (format "  解決 %d名 / 未解決 %d名 (people.edn に名前追加で連携可)" r u))))
        (println "\n=== メール分類ルール / triage 依存 (mail-rules.edn + mail-triage.edn) ===")
        (println "  rules   " (n '[:find (count ?r) :where [?r :rule/id]])
                 " triage  " (n '[:find (count ?t) :where [?t :triage/id]]))
        (println "[Datalog] rule by action (私が自動実行可=agent):")
        (doseq [[act ag cnt] (->> (d/q '[:find ?act ?ag (count ?r)
                                         :where [?r :rule/action ?act] [?r :rule/agent ?ag]] db)
                                  (sort-by (comp name first)))]
          (println (format "  %-12s agent=%-4s ×%d" (name act) (name ag) cnt)))
        (println "[Datalog] triage → 依存先 (メール処理が繋がる finitem/obligation):")
        (doseq [[tid dep] (->> (d/q '[:find ?tid ?dep
                                      :where [?t :triage/id ?tid] [?t :triage/depends-on ?dep]] db)
                              (sort-by first))]
          (println (format "  %-22s → %s" tid dep)))
        (println "\n=== JK法人財務 triage (corp-finance.edn) ===")
        (let [pay (or (first (first (d/q '[:find (sum ?a) :with ?f
                                           :where [?f :finitem/direction :payable] [?f :finitem/amount-jpy ?a]] db))) 0)]
          (println (format "  支払債務 (判明額合計): ¥%,d / 受領債権: NOT A HOTEL半期精算(プラス・入金確認要)" (long pay))))
        (println "[Datalog] finitem by action (払う/減らす/切る/相殺/直す/監視):")
        (doseq [[act t amt st] (->> (d/q '[:find ?act ?t ?amt ?st
                                           :where [?f :finitem/action ?act] [?f :finitem/title ?t]
                                                  [?f :finitem/status ?st]
                                                  (or-join [?f ?amt]
                                                    [?f :finitem/amount-jpy ?amt]
                                                    (and [(missing? $ ?f :finitem/amount-jpy)] [(ground 0) ?amt]))] db)
                                    (sort-by (fn [[a _ amt _]] [(name a) (- amt)])))]
          (println (format "  %-8s ¥%-9s %-16s %s" (name act) (if (pos? amt) (format "%,d" amt) "-") (name st) t)))
        (println "[Datalog] obligation 依存チェーン (原資/前提に縛られた義務):")
        (doseq [[oid t bb] (d/q '[:find ?oid ?t ?bb
                                  :where [?o :obligation/id ?oid] [?o :obligation/title ?t]
                                         [?o :obligation/blocked-by ?bb]] db)]
          (let [fb (or (ffirst (d/q '[:find ?fb :in $ ?oid
                                      :where [?o :obligation/id ?oid] [?o :obligation/funded-by ?fb]] db oid)) "-")]
            (println (format "  %s『%s…』 blocked-by=%s funded-by=%s" oid (subs t 0 (min 24 (count t))) bb fb))))
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
