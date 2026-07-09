(ns ingest.bank
  "pl/bank-txn-ingest — 銀行取引通知メール → txn 構造化 (facts/pipelines.edn の実装)。
   langgraph-clj StateGraph: fetch → parse → classify → reconcile → transact。
   Source = mail/messages/*.eml (mail-sync 済ローカル; 銀行ログインなし ADR-0015)。
   Output = mail/bank-txns.jsonl (derived, 決定的ソート; warehouse.load が txn datoms 化)。
   Run: clojure -M -m ingest.bank"
  (:require [langgraph.graph :as g]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [jakarta.mail Session Part]
           [jakarta.mail.internet MimeMessage]
           [java.util Properties]))

(def base "/Users/junkawasaki/github/com-junkawasaki/orgs/personal")
(def out-file "mail/bank-txns.jsonl")

;; ---------- host capabilities (I/O は library 外 = langchain-clj 設計) ----------
(defn- rd-jsonl [rel]
  (let [f (io/file base rel)]
    (when (.exists f)
      (with-open [r (io/reader f)]
        (doall (->> (line-seq r) (remove str/blank?)
                    (keep #(try (json/read-str % :key-fn keyword) (catch Exception _ nil)))))))))

(defn- mime-text
  "part から text/plain を深さ優先で取り出す (なければ html をタグ除去)。"
  [^Part part want]
  (try
    (cond
      (.isMimeType part "text/plain") (when (= want :plain) (str (.getContent part)))
      (.isMimeType part "text/html")  (when (= want :html) (str (.getContent part)))
      (.isMimeType part "multipart/*")
      (let [mp (.getContent part)]
        (some identity (for [i (range (.getCount mp))] (mime-text (.getBodyPart mp i) want))))
      :else nil)
    (catch Exception _ nil)))

(defn- html->text [s]
  (-> s (str/replace #"(?is)<(style|script).*?</\1>" " ")
        (str/replace #"<[^>]+>" " ")
        (str/replace #"&nbsp;|&#160;" " ")))

(defn- read-eml [cid]
  (let [f (io/file base "mail/messages" (str cid ".eml"))]
    (when (.exists f)
      (let [sess (Session/getDefaultInstance (Properties.))
            msg  (with-open [in (io/input-stream f)] (MimeMessage. sess in))]
        (or (mime-text msg :plain)
            (some-> (mime-text msg :html) html->text)
            "")))))

;; ---------- 宣言テーブル (bank-sources.edn の :email-notify 行に対応) ----------
(def sender->bank
  [{:re #"paypay-bank\.co\.jp" :bank "bank/paypay-personal"}   ; jk.luxury宛は下で biz に振替
   {:re #"bitflyer"            :bank "ex/bitflyer"}
   {:re #"rakuten-bank"        :bank "bank/rakuten"}
   {:re #"netbk\.co\.jp"       :bank "bank/sbi"}
   {:re #"binance"             :bank "ex/binance"}
   {:re #"vpass|smbc-card"     :bank "card/smbc"}
   {:re #"coincheck"           :bank "ex/coincheck"}
   {:re #"americanexpress|aexp" :bank "card/amex"}])

(def amount-re  #"(ご利用金額|お引落金額|請求金額|積立金額|お振込金額|振込金額|お借入金額|ご返金金額|ご返済金額|入金金額|お引き出し金額)[:：]\s*([0-9][0-9,]*)\s*円")
(def date-re    #"(引落日時|お引落日時|取扱日時|お借入日時|入金日時|出金日時|ご利用日時|取引日時|ご返済日)[:：]\s*(\d{4})[/年](\d{1,2})[/月](\d{1,2})")
(def vendor-re  #"(収納企業|ご利用先|お振込先|振込依頼人)[:：]\s*([^\s 、。]+)")
(def ref-re     #"取引明細番号[:：]\s*([0-9A-Za-z]+)")

(def kind-rules  ; subject → {:kind :dir} (:dir :out :in :failed :info)
  [[#"できませんでした|残高不足"        {:kind "振替失敗" :dir :failed}]
   [#"Ｖｉｓａデビット.*返金|ご返金"     {:kind "返金"     :dir :in}]
   [#"Ｖｉｓａデビット"                 {:kind "デビット" :dir :out}]
   [#"口座振替|お引き落とし|お引落"      {:kind "口座振替" :dir :out}]
   [#"カードローン.*借り入れ"            {:kind "借入"     :dir :in}]
   [#"カードローン.*返済"               {:kind "返済"     :dir :out}]
   [#"振込入金|振り?込みのご確認|利息入金" {:kind "入金"    :dir :in}]
   [#"お引き出し|ＡＴＭ"                {:kind "ATM出金"  :dir :out}]
   [#"積立.*購入結果"                   {:kind "積立"     :dir :out}]
   [#"出金"                            {:kind "出金"     :dir :out}]
   [#"お預入"                          {:kind "入金"     :dir :in}]])

(defn- iso-date [y m d] (format "%s-%02d-%02d" y (Long/parseLong m) (Long/parseLong d)))

(def ^:private en-months
  {"Jan" 1 "Feb" 2 "Mar" 3 "Apr" 4 "May" 5 "Jun" 6
   "Jul" 7 "Aug" 8 "Sep" 9 "Oct" 10 "Nov" 11 "Dec" 12})

(defn- norm-mail-date
  "index.jsonl の :date (ISO or RFC2822) → YYYY-MM-DD。"
  [s]
  (or (re-find #"^\d{4}-\d{2}-\d{2}" (str s))
      (when-let [[_ d mo y] (re-find #"(\d{1,2})\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+(\d{4})" (str s))]
        (format "%s-%02d-%02d" y (en-months mo) (Long/parseLong d)))))

;; ---------- nodes (state → partial update map; langgraph が reducer で fold) ----------
(defn fetch-node
  "index.jsonl → 銀行通知メールに .eml 本文を付けて :emails へ。"
  [_state]
  (let [emails
        (vec (for [r (rd-jsonl "mail/messages/index.jsonl")
                   :let [from (str/lower-case (str (:from r)))
                         hit  (some #(when (re-find (:re %) from) (:bank %)) sender->bank)]
                   :when hit
                   :let [to (str/lower-case (str/join " " (map str (:to r))))
                         bank (if (and (= hit "bank/paypay-personal") (str/includes? to "jk.luxury"))
                                "bank/paypay-biz" hit)
                         text (read-eml (:cid r))]
                   :when (seq text)]
               {:cid (:cid r) :bank bank :subject (str (:subject r))
                :mail-date (norm-mail-date (:date r)) :text text}))]
    {:emails emails :report {:fetched (count emails)}}))

(defn parse-node
  "本文から金額/日付/vendor を正規表現抽出 → :txns。bitFlyer積立は通貨ごとに1txn。"
  [{:keys [emails]}]
  (let [txns
        (vec
         (mapcat
          (fn [{:keys [cid bank subject mail-date text]}]
            (let [date (or (when-let [[_ _ y m d] (re-find date-re text)] (iso-date y m d))
                           mail-date)
                  vendor (nth (re-find vendor-re text) 2 nil)
                  ref    (second (re-find ref-re text))
                  ;; bitFlyer 積立: ・積立通貨:X …・積立金額:N円 のペアを順に対応付け
                  tsumitate (when (str/includes? subject "積立")
                              (let [curs (mapv second (re-seq #"積立通貨[:：]\s*([^\s ・]+)" text))
                                    amts (mapv #(nth % 2) (re-seq amount-re text))]
                                (mapv (fn [i a] {:vendor (get curs i) :amount a})
                                      (range (count amts)) amts)))]
              (if (seq tsumitate)
                (for [[i {:keys [vendor amount]}] (map-indexed vector tsumitate)]
                  {:cid cid :seq i :bank bank :subject subject :date date
                   :vendor vendor :amount (Long/parseLong (str/replace amount "," ""))})
                [(cond-> {:cid cid :seq 0 :bank bank :subject subject :date date}
                   vendor (assoc :vendor vendor)
                   ref    (assoc :ref ref)
                   (re-find amount-re text)
                   (assoc :amount (Long/parseLong (str/replace (nth (re-find amount-re text) 2) "," ""))))])))
          emails))]
    {:txns txns :report {:parsed (count txns)}}))

(defn classify-node
  "subject → kind/direction 付与 (kind-rules)。:failed は金額を detail 扱い (非移動)。"
  [{:keys [txns]}]
  (let [classified
        (mapv (fn [t]
                (let [{:keys [kind dir]} (or (some (fn [[re kd]] (when (re-find re (:subject t)) kd)) kind-rules)
                                             {:kind "その他" :dir :info})
                      t (assoc t :kind kind :dir (name dir))]
                  (if (and (= dir :failed) (:amount t))
                    (-> t (assoc :failed-amount (:amount t)) (dissoc :amount))  ; 残高不足=金銭未移動
                    t)))
              txns)]
    {:txns classified :report {:classified (count classified)}}))

(defn reconcile-node
  "①ストリーム内 dedup (date,vendor,amount,bank[,ref]) ②finance-history 由来 txn と
   (date,amount) 突合して既存分を除外 (warehouse での二重計上防止)。"
  [{:keys [txns]}]
  (let [existing (set (for [src ["mail/recent-activity-30d.jsonl" "mail/finance-history-202604.jsonl"
                                 "mail/finance-history-2.jsonl" "mail/finance-history-3.jsonl"
                                 "mail/finance-history-4.jsonl" "mail/finance-history-5.jsonl"
                                 "mail/finance-history-6.jsonl" "mail/finance-history-senders.jsonl"]
                            rec (or (rd-jsonl src) [])
                            :let [a (:amount_jpy rec)] :when (and (:date rec) (number? a))]
                        [(subs (str (:date rec)) 0 (min 10 (count (str (:date rec))))) (long a)]))
        in-stream (vals (reduce (fn [m t]
                                  (let [k [(:date t) (:vendor t) (:amount t) (:bank t) (:ref t) (:kind t)]]
                                    (if (contains? m k) m (assoc m k t))))
                                {} txns))
        [dups uniq] [(- (count txns) (count in-stream))
                     (remove #(and (:amount %) (contains? existing [(:date %) (:amount %)])) in-stream)]]
    {:unique (vec (sort-by (juxt :date :cid :seq) uniq))
     :report {:in-stream-dups dups
              :already-in-jsonl (- (count in-stream) (count uniq))
              :unique (count uniq)}}))

(defn transact-node
  "決定的ソートで mail/bank-txns.jsonl に書き出し (warehouse.load が読む)。"
  [{:keys [unique]}]
  (let [f (io/file base out-file)]
    (with-open [w (io/writer f)]
      (doseq [t unique]
        (.write w (str (json/write-str (into (sorted-map) t)) "\n"))))
    {:report {:written (count unique) :out out-file}}))

;; ---------- graph ----------
(defn build-graph []
  (-> (g/state-graph {:channels {:emails {:default []}
                                 :txns   {:default []}
                                 :unique {:default []}
                                 :report {:reducer merge :default {}}}})
      (g/add-node :fetch fetch-node)
      (g/add-node :parse parse-node)
      (g/add-node :classify classify-node)
      (g/add-node :reconcile reconcile-node)
      (g/add-node :transact transact-node)
      (g/add-edge :fetch :parse)
      (g/add-edge :parse :classify)
      (g/add-edge :classify :reconcile)
      (g/add-edge :reconcile :transact)
      (g/set-entry-point :fetch)
      (g/set-finish-point :transact)))

(defn -main [& _]
  (let [cg (g/compile-graph (build-graph))
        {:keys [report unique]} (g/invoke cg {})]
    (println "=== pl/bank-txn-ingest (langgraph-clj) ===")
    (doseq [[k v] report] (println (format "  %-18s %s" (name k) v)))
    (println "\n  口座別 (新規取込分):")
    (doseq [[bank ts] (sort-by key (group-by :bank unique))]
      (println (format "  %-22s ×%-3d ¥%,d" bank (count ts)
                       (reduce + 0 (keep :amount ts)))))))
