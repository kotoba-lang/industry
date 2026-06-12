(ns ingest.mf
  "pl/mf-csv-ingest — MoneyForward ME 入出金CSV → txn 構造化 (facts/pipelines.edn alt-source)。
   本人手番: MF管理画面 → 家計簿/入出金 → CSVエクスポート → personal/inbox/mf-cashflow.csv
   (認証は本人 = :no 境界。私はファイルが置かれたら取込むだけ)。
   Run: clojure -M -m ingest.mf [csv-path]
   Output: mail/mf-txns.jsonl (derived; warehouse.load が txn datoms 化, bank-txns より優先)"
  (:require [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def base "/Users/junkawasaki/github/com-junkawasaki/personal")
(def default-csv "inbox/mf-cashflow.csv")
(def out-file "mail/mf-txns.jsonl")

;; MF「保有金融機関」表示名 → bank-sources.edn の :bank/id
(def institution->bank
  [[#"PayPay銀行"            "bank/paypay-personal"]
   [#"楽天銀行"               "bank/rakuten"]
   [#"住信SBI|SBIネット|NEOBANK" "bank/sbi"]
   [#"ゆうちょ"               "bank/yucho"]
   [#"みずほ"                 "bank/mizuho"]
   [#"アメリカン・?エキスプレス|AMEX|Amex" "card/amex"]
   [#"三井住友カード"          "card/smbc"]
   [#"楽天カード"              "card/rakuten"]
   [#"ダイナース"              "card/diners-smtc"]
   [#"(?i)coincheck"          "ex/coincheck"]
   [#"(?i)bitflyer"           "ex/bitflyer"]
   [#"(?i)binance"            "ex/binance"]])

(defn- inst->bank [s]
  (some (fn [[re id]] (when (re-find re (str s)) id)) institution->bank))

(defn- parse-long* [s]
  (try (long (Double/parseDouble (str/replace (str s) #"[,¥\"\s]" ""))) (catch Exception _ nil)))

(defn- norm-date [s]  ; "2026/06/01" | "2026-06-01" → ISO
  (when-let [[_ y m d] (re-find #"(\d{4})[/-](\d{1,2})[/-](\d{1,2})" (str s))]
    (format "%s-%02d-%02d" y (Long/parseLong m) (Long/parseLong d))))

(defn- header-index
  "MF CSV ヘッダ行 → {列キー idx}。列名ゆらぎ (ME/クラウド) を吸収。"
  [header]
  (into {}
        (keep (fn [[i h]]
                (condp #(str/includes? %2 %1) h
                  "日付"        [:date i]
                  "内容"        [:detail i]
                  "金額"        [:amount i]
                  "保有金融機関" [:institution i]
                  "大項目"      [:cat1 i]
                  "中項目"      [:cat2 i]
                  "振替"        [:transfer i]
                  "計算対象"    [:target i]
                  "ID"          [:id i]
                  nil))
              (map-indexed vector header))))

(defn -main [& [csv-path]]
  (let [f (io/file (or csv-path (str base "/" default-csv)))]
    (if-not (.exists f)
      (do (println (str "CSV がありません: " f))
          (println "本人手番: MoneyForward → 入出金 → CSVエクスポート → personal/inbox/mf-cashflow.csv")
          (println "(facts/obligations.jsonl ob/mf-csv-export 参照)"))
      (let [rows (with-open [r (io/reader f)] (doall (csv/read-csv r)))
            idx  (header-index (first rows))
            txns (vec (for [row (rest rows)
                            :let [g #(when-let [i (idx %)] (nth row i nil))
                                  date (norm-date (g :date))
                                  amt  (parse-long* (g :amount))]
                            :when (and date amt
                                       (not= (g :target) "0")      ; 計算対象外は除外
                                       (not= (g :transfer) "1"))]  ; 口座間振替は二重計上になるので除外
                        (let [bank (inst->bank (g :institution))]
                          (cond-> {:date date :detail (g :detail)
                                   :amount (Math/abs amt)
                                   :dir (if (neg? amt) "out" "in")
                                   :institution (g :institution)
                                   :source "pl/mf-csv-ingest"}
                            bank        (assoc :bank bank)
                            (g :cat1)   (assoc :cat1 (g :cat1))
                            (g :cat2)   (assoc :cat2 (g :cat2))
                            (g :id)     (assoc :mf-id (g :id))))))]
        (with-open [w (io/writer (io/file base out-file))]
          (doseq [t (sort-by (juxt :date :mf-id) txns)]
            (.write w (str (json/write-str (into (sorted-map) t)) "\n"))))
        (println (format "=== pl/mf-csv-ingest: %d txns → %s ===" (count txns) out-file))
        (doseq [[bank ts] (sort-by key (group-by #(or (:bank %) (str "?" (:institution %))) txns))]
          (println (format "  %-26s ×%-4d out ¥%,d / in ¥%,d" bank (count ts)
                           (reduce + 0 (keep #(when (= "out" (:dir %)) (:amount %)) ts))
                           (reduce + 0 (keep #(when (= "in" (:dir %)) (:amount %)) ts)))))))))
