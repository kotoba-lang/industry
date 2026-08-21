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
;; アドレスは両ローダ共通の正規化（trim + lowercase + サブアドレス +tag 除去、
;; ADR-0009/0010）を通す。subject は RFC 2047 デコード、date は ISO-8601 UTC に
;; 正規化して格納する。
;;
;; 対象: mail/messages/index.jsonl（Gmail ingest 済みメタデータ。本文は含まず
;; — git-annex 実体が無ければ warning を stderr に出して空で終わる。事前に
;; repo root から:
;;   bash orgs/personal/bin/gpg-unlock.sh && git annex get orgs/personal/mail/messages/index.jsonl）。
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
         '["path" :as node-path]
         '["datascript" :as ds-mod])

(def ds (.-default ds-mod))

(def base
  ;; script 自身の位置から orgs/personal を解決する（cwd 非依存）。cwd の
  ;; `git rev-parse --show-toplevel` に頼ると、west 子リポ内から実行した時に
  ;; その子リポを root と誤認し、黙って emails=0 になる。
  (if *file*
    (io/file (node-path/resolve (node-path/dirname (node-path/resolve *file*)) ".."))
    (io/file (str/trim (:out (shell/sh "git" "rev-parse" "--show-toplevel")))
             "orgs" "personal")))

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

;; ---------- 正規化 ----------

(defn- normalize-email
  "trim + lowercase + RFC サブアドレス +tag 除去（ADR-0009/0010: ingest 時に正規化。
   jun784+<tag>@gmail.com / JUN784@GMAIL.COM を canonical jun784@gmail.com に揃える）。"
  [s]
  (when s
    (-> (str/trim (str s))
        (str/lower-case)
        (str/replace #"\+[^@]*@" "@"))))

(defn- addr
  "\"Name <addr@x>\" -> 正規化済み \"addr@x\"。角括弧が無ければ全体を正規化して返す。"
  [s]
  (when s
    (let [m (re-find #"<([^>]+)>" (str s))]
      (normalize-email (if m (second m) s)))))

(defn- b64-bytes [data]
  (js/Buffer.from data "base64"))

(defn- q-bytes [data]
  (let [s (str/replace data "_" " ")
        n (count s)
        out #js []]
    (loop [i 0]
      (when (< i n)
        (if (and (= "=" (subs s i (inc i))) (<= (+ i 3) n))
          (do (.push out (js/parseInt (subs s (inc i) (+ i 3)) 16))
              (recur (+ i 3)))
          (do (.push out (.charCodeAt s i))
              (recur (inc i))))))
    (js/Uint8Array. out)))

(def ^:private rfc2047-re #"=\?([^?]+)\?([bBqQ])\?([^?]*)\?=")

(defn- decode-rfc2047
  "RFC 2047 encoded-word をデコード（B/Q。charset は TextDecoder 任せ —
   UTF-8 / ISO-2022-JP 等）。隣接 encoded-word 間の空白は除去（RFC 2047 §6.2）。
   デコードできない word は原文のまま残す。実データの 197/2324 subject が対象。"
  [s]
  (-> (str s)
      (str/replace #"(?<=\?=)[ \t\r\n]+(?==\?)" "")
      (str/replace rfc2047-re
                   (fn [[whole charset enc data]]
                     (or (try
                           (.decode (js/TextDecoder. (str/lower-case charset))
                                    (if (or (= enc "b") (= enc "B"))
                                      (b64-bytes data)
                                      (q-bytes data)))
                           (catch :default _ nil))
                         whole)))))

(defn- norm-date
  "index.jsonl の date は ISO-8601 と RFC 2822 が混在（実測 1387/937）。
   文字列比較の range query が壊れるので ISO-8601 UTC に正規化。parse 不能なら原文。"
  [s]
  (let [t (js/Date. (str s))]
    (if (js/isNaN (.getTime t)) s (.toISOString t))))

;; ---------- mail/messages/index.jsonl ----------

(defn index-file [] (io/file base "mail" "messages" "index.jsonl"))

(def ^:private annex-hint
  "run (repo root): bash orgs/personal/bin/gpg-unlock.sh && git annex get orgs/personal/mail/messages/index.jsonl")

(defn mail-records []
  (let [f (index-file)]
    (if (.exists f)
      (let [lines (into [] (remove str/blank?) (str/split-lines (slurp f)))
            recs (into [] (keep (fn [line]
                                  (try (json/parse-string line true)
                                       (catch :default _ nil))))
                       lines)]
        (when (and (seq lines) (empty? recs))
          (js/console.error
           (str "warning: index.jsonl parsed 0/" (count lines)
                " lines — unfetched annex pointer? " annex-hint)))
        recs)
      (do (js/console.error
           (str "warning: index.jsonl not found — broken annex symlink? " annex-hint))
          []))))

(defn- email-entity [rec next-tempid!]
  (let [cid (:cid rec)]
    (cond-> {:db/id (next-tempid!)
             :email/cid cid
             :email/message-id (or (:message_id rec) cid)}
      (:thread_id rec)    (assoc :email/thread-id (:thread_id rec))
      (:date rec)         (assoc :email/date (norm-date (:date rec)))
      (:subject rec)      (assoc :email/subject (decode-rfc2047 (:subject rec)))
      (seq (:labels rec)) (assoc :email/labels (vec (:labels rec)))
      (:from rec)         (assoc :email/from (addr (:from rec)))
      (seq (:to rec))     (assoc :email/to (vec (keep addr (:to rec))))
      (seq (:cc rec))     (assoc :email/cc (vec (keep addr (:cc rec))))
      (:size rec)         (assoc :email/size (:size rec))
      (:eml_path rec)     (assoc :email/eml-path (:eml_path rec)))))

(defn email-entities [next-tempid!]
  ;; 同一 cid の重複 index 行（multi-account sighting: ingest-gmail-batch.py /
  ;; ingest-graph-mail.py / mail-sync.bb は message_id 単位でしか skip しない）を
  ;; 先勝ちで dedup — Datomic 側の :email/cid :db.unique/identity upsert と同じ集約。
  (second
   (reduce (fn [[seen out] rec]
             (let [cid (:cid rec)]
               (if (or (nil? cid) (contains? seen cid))
                 [seen out]
                 [(conj seen cid) (conj out (email-entity rec next-tempid!))])))
           [#{} []]
           (mail-records))))

;; ---------- facts/people.edn (任意: email -> 表示名) ----------

(defn people-file [] (io/file base "facts" "people.edn"))

(defn person-entities [next-tempid!]
  (let [f (people-file)]
    (if (.exists f)
      (try
        ;; into [] で try 内で実現を強制する（lazy seq を返すと realize 時の例外が
        ;; catch の外＝build-conn 側へ漏れて mail-only query まで巻き込んで落ちる）。
        (into []
              (keep (fn [p]
                      (let [emails (:emails p)]
                        ;; sequential? guard: 文字列 :emails は seqable なので
                        ;; (seq ...) だけだと per-character に分解されてしまう
                        (when (and (sequential? emails) (seq emails))
                          {:db/id (next-tempid!)
                           :person/id (:person/id p)
                           :person/name (:person/name p)
                           :person/emails (vec (keep normalize-email emails))}))))
              (edn/read-string (slurp f)))
        (catch :default e
          (js/console.error (str "warning: people.edn unreadable ("
                                 (or (.-message e) e) ") — mail-only mode"))
          []))
      [])))

;; ---------- schema ----------

(defn ds-schema []
  (let [obj (js-obj)]
    (doseq [a ["email/labels" "email/to" "email/cc" "person/emails"]]
      (aset obj a (js-obj ":db/cardinality" ":db.cardinality/many")))
    ;; email-entities 側でも cid dedup するが、再 transact 時の upsert 保証として宣言
    (aset obj "email/cid" (js-obj ":db/unique" ":db.unique/identity"))
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

(defn- usage-exit! []
  (println "usage: npx nbb orgs/personal/bin/mail-datascript.cljs [count | q '<datalog-query>']")
  (scripts.nbb-compat/exit 1))

(defn -main [& args]
  (let [[mode query-str] args]
    (case mode
      ;; count は entity 数だけ数えれば足りる — conn 構築 (transact) は不要
      "count"
      (let [tempid (atom 0)
            next-tempid! (fn [] (swap! tempid dec))]
        (println (format "emails=%s people=%s"
                         (count (email-entities next-tempid!))
                         (count (person-entities next-tempid!)))))

      "q"
      (if (str/blank? query-str)
        (usage-exit!)
        (let [{:keys [conn]} (build-conn)]
          (println (pr-str (js->clj (.q ds query-str (.db ds conn)))))))

      (usage-exit!))))

(apply -main *command-line-args*)
