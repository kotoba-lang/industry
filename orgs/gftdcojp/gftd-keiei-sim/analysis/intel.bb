#!/usr/bin/env bb
;; intel.bb — gftd 経営シムのインテリジェンス導出 (Clojure / babashka)。
;;
;; 実 facts (m365-archive/facts) を読み、会社のインテリジェンスを導出して
;; datomic 取り込み用の EDN datom ベクタを stdout に出力する。Rust 側はこの
;; 出力を kotoba-datomic に transact するだけ (分析ロジックは全てここ=Clojure)。
;;
;;   bb analysis/intel.bb <facts-dir>
;;
;; 導出するもの:
;;   :gftd.intel/kind :latent-lead   — 潜在リード(接触量上位・非ベンダ) + 確度/離反リスク
;;   :gftd.intel/kind :revival       — 休眠プロジェクト再生候補
;;   :gftd.intel/kind :renewal-risk  — 契約更新リスク
;;   :gftd.dep/*                     — 売上集中(依存)エッジ

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def facts-dir (or (first *command-line-args*) "../m365-archive/facts"))

;; ---- facts 読み込み: 1行1マップ ({...}) を拾う (ベクタ形/EDN-lines 両対応) ----
(defn read-objs [name]
  (let [path (str facts-dir "/" name)]
    (if (.exists (clojure.java.io/file path))
      (->> (str/split-lines (slurp path))
           (map str/trim)
           (filter #(str/starts-with? % "{"))
           (keep #(try (edn/read-string %) (catch Exception _ nil))))
      [])))

;; ---- 基準日 2026-06 からの経過月数 ----
(defn recency-months [s]
  (if (and (string? s) (>= (count s) 7))
    (let [y (parse-long (subs s 0 4))
          m (parse-long (subs s 5 7))]
      (if (and y m) (max 0 (- (+ (* 2026 12) 6) (+ (* y 12) m))) 99))
    99))

;; ---- 営業リードとして無意味なインフラ/ベンダ/通知ドメインを除外 ----
(def noise #{"vercel" "google" "microsoft" "amazonaws" "github" "openai" "slack"
             "notion" "figma" "stripe" "pandadoc" "docusign" "zoom" "atlassian"
             "cloudflare" "sendgrid" "mailchimp" "apple" "adobe" "163.com" "gmail"
             "outlook" "noreply" "no-reply" "2checkout" "paypal"})
(defn noise? [dom] (let [d (str/lower-case (or dom ""))] (some #(str/includes? d %) noise)))

;; ---- 商談確度スコア 0-100 (接触量 + 直近性 + 関与人数) ----
(defn confidence [{:keys [message_count last_contact people]}]
  (let [vol (min 40.0 (/ (or message_count 0) 50.0))
        rm  (recency-months last_contact)
        rec (cond (<= rm 2) 35.0 (<= rm 6) 25.0 (<= rm 12) 12.0 :else 3.0)
        br  (min 25.0 (* 2.0 (count (or people []))))]
    (long (Math/round (max 0.0 (min 100.0 (+ vol rec br)))))))

;; ---- 離反リスク: かつて活発だが直近接触が途絶 ----
(defn churn? [{:keys [message_count last_contact]}]
  (and (>= (or message_count 0) 300) (>= (recency-months last_contact) 9)))

(defn edn-str [s] (pr-str (str s)))

;; ============================================================================
;; 関係グラフ用の多角的 intel: メール/threads/invoice/契約/contact/市場分析から
;; ノードの score / rel-type / market、エッジの path-weight を計算する。
;; ============================================================================

(defn lower [s] (str/lower-case (str s)))
(defn root-token [dom] (first (str/split (str dom) #"\.")))   ; "moneyforward.com"→"moneyforward"

;; 市場分析: ドメインから市場セグメントを推定
(defn market-of [dom]
  (let [d (lower dom)]
    (cond
      (re-find #"exchange|crypto|web3|dao|chain|bitcoin|coin|nft|defi|blockchain" d) :web3
      (re-find #"bank|finance|capital|securit|invest|fund|insur|shinkin|信金|保証|政策金融" d) :finance
      (re-find #"\.go\.jp|\.lg\.jp|\.or\.jp" d) :public
      (re-find #"\.ac\.jp|univ|\.edu" d) :academia
      (re-find #"\.co\.jp|\.jp$|\.ne\.jp" d) :jp-corp
      (re-find #"\.com$|\.io$|\.app$|\.net$" d) :global
      :else :other)))

;; 金額インデックス (一度だけ読む)
(def contracts-idx
  (delay (->> (read-objs "contract-terms.edn")
              (keep (fn [c] (when (and (number? (:amount_jpy c)) (pos? (:amount_jpy c)) (seq (str (:party_b c))))
                              [(lower (:party_b c)) (:amount_jpy c)])))
              vec)))
(def invoices-idx
  (delay (->> (read-objs "invoice-terms.edn")
              (keep (fn [v] (when (and (number? (:amount_jpy v)) (pos? (:amount_jpy v)))
                              [(lower (:issuer v)) (lower (:billed_to v)) (:amount_jpy v)])))
              vec)))
(defn gftd? [s] (let [l (lower s)] (or (str/includes? l "gftd") (str/includes? (str s) "ギフテ"))))

;; ドメイン語幹で契約/請求を緩くマッチし、商流の金額を算出する。
;;   :in  = gftd が相手に発行(=売上)  :out = 相手が gftd に発行(=コスト)  :contract = 契約額
(defn money-for [dom]
  (let [tok (lower (root-token dom))]
    (if (< (count tok) 3)
      {:in 0 :out 0 :contract 0}
      {:contract (reduce + 0 (for [[party amt] @contracts-idx :when (str/includes? party tok)] amt))
       :in  (reduce + 0 (for [[iss bil amt] @invoices-idx :when (and (gftd? iss) (str/includes? bil tok))] amt))
       :out (reduce + 0 (for [[iss bil amt] @invoices-idx :when (and (str/includes? iss tok) (gftd? bil))] amt))})))

(defn rel-type [{:keys [in out contract]}]
  (cond (pos? in) :customer (pos? out) :vendor (pos? contract) :partner :else :lead))

;; エッジ path-weight 0-100: 接触量 + モメンタム + 直近性 + 商流金額 の合成
(defn path-weight [o open money]
  (let [contact (min 40.0 (/ (or (:message_count o) 0) 50.0))
        mom (min 20.0 (* 4.0 open))
        rm (recency-months (:last_contact o))
        rec (cond (<= rm 2) 15 (<= rm 6) 10 (<= rm 12) 5 :else 0)
        mny (min 25.0 (/ money 2000000.0))]
    (long (Math/round (min 100.0 (+ contact mom rec mny))))))

;; ---- ③ messages/threads からの確度精緻化 ----
;; threads.edn を「リード企業ドメインを含む行」だけ部分パースして(44MB全読みを回避)、
;; 各ドメインの「スレッド数 / 未返信スレッド数(=商談モメンタム)」を集計する。
(defn thread-signals [domains]
  (let [path (str facts-dir "/threads.edn")
        doms (set domains)]
    (if (and (seq doms) (.exists (io/file path)))
      (with-open [r (io/reader path)]
        (reduce
         (fn [acc line]
           (if (some #(str/includes? line %) doms)
             (if-let [t (try (edn/read-string line) (catch Exception _ nil))]
               (reduce (fn [a p]
                         (let [dom (last (str/split (str p) #"@"))]
                           (if (contains? doms dom)
                             (-> a
                                 (update-in [dom :threads] (fnil inc 0))
                                 (update-in [dom :open] (fnil + 0) (if (:needs_reply t) 1 0)))
                             a)))
                       acc (:participants t))
               acc)
             acc))
         {} (line-seq r)))
      {})))

;; ---- 潜在リード datom: メール/threads/invoice/契約/市場 から多角的に算出 ----
(defn latent-leads []
  (let [orgs (->> (read-objs "crm.edn")
                  (remove #(noise? (:org_domain %)))
                  (sort-by #(- (or (:message_count %) 0)))
                  (take 12))
        sig  (thread-signals (map :org_domain orgs))]
    (map-indexed
     (fn [i o]
       (let [dom   (:org_domain o)
             open  (get-in sig [dom :open] 0)
             conf  (min 100 (+ (confidence o) (min 15 (* 3 open))))
             m     (money-for dom)
             money (+ (:in m) (:out m) (:contract m))
             pw    (path-weight o open money)]
         {:db/id (str "lead" i)
          :gftd.intel/kind :latent-lead
          :gftd.intel/subject dom
          :gftd.intel/score (or (:message_count o) 0)
          :gftd.intel/confidence conf
          :gftd.intel/people (count (or (:people o) []))
          :gftd.intel/open-threads open
          :gftd.intel/path-weight pw
          :gftd.intel/market (market-of dom)
          :gftd.intel/rel-type (rel-type m)
          :gftd.intel/money-jpy money
          :gftd.intel/risk (if (churn? o) :churn :none)
          :gftd.intel/stage :new}))
     orgs)))

;; ---- 人物ノード: 上位リードの担当者(people)を org に紐づけて datom 化 (関係グラフ用) ----
(defn people-nodes []
  (let [orgs (->> (read-objs "crm.edn")
                  (remove #(noise? (:org_domain %)))
                  (sort-by #(- (or (:message_count %) 0)))
                  (take 6))]
    (apply concat
           (map-indexed
            (fn [i o]
              (map-indexed
               (fn [j email]
                 {:db/id (str "ppl" i "-" j)
                  :gftd.person/org (:org_domain o)
                  :gftd.person/email (str email)})
               (take 3 (:people o))))
            orgs))))

;; ---- 不良債権(売掛金)分析: gftd発行請求のうち支払期限を大きく超過したもの ----
;; 支払状況は facts に無いため、期限(:due)が基準(1年超前)を過ぎた発行請求を
;; 「回収懸念=不良債権候補」として債務先(:billed_to)別に集計・整理する。
(defn before-month? [d ym]
  (and (string? d) (>= (count d) 7) (neg? (compare (subs d 0 7) ym))))

(defn bad-debts []
  (let [m (->> (read-objs "invoice-terms.edn")
               (filter #(and (number? (:amount_jpy %)) (pos? (:amount_jpy %))
                             (gftd? (str (:issuer %)))               ; gftd が発行=売掛
                             (string? (:billed_to %)) (seq (:billed_to %))
                             (not (gftd? (str (:billed_to %))))
                             (before-month? (:due %) "2025-06")))    ; 1年以上 期限超過
               (reduce (fn [acc v] (update acc (:billed_to v) (fnil + 0) (:amount_jpy v))) {})
               (sort-by (comp - val))
               (take 8))]
    (map-indexed
     (fn [i [debtor amt]]
       {:db/id (str "bd" i)
        :gftd.intel/kind :bad-debt
        :gftd.intel/subject debtor
        :gftd.intel/score amt
        :gftd.intel/note "売掛金(支払期限1年超)・回収要確認"
        :gftd.intel/stage :new})
     m)))

;; ---- 市場分析: 接触上位 org をセグメント別に集計 ----
(defn markets []
  (let [orgs (->> (read-objs "crm.edn") (remove #(noise? (:org_domain %))) (take 80))
        g (group-by #(market-of (:org_domain %)) orgs)]
    (map-indexed
     (fn [i [seg os]]
       {:db/id (str "mkt" i)
        :gftd.market/segment seg
        :gftd.market/orgs (count os)
        :gftd.market/messages (reduce + 0 (map #(or (:message_count %) 0) os))})
     g)))

;; ---- 休眠プロジェクト再生候補 datom ----
(defn revivals []
  (let [ds (->> (read-objs "projects.edn")
                (filter #(= :dormant (:gftd.project/status %)))
                (sort-by #(- (or (:gftd.project/file-count %) 0)))
                (take 6))]
    (map-indexed
     (fn [i p]
       {:db/id (str "rev" i)
        :gftd.intel/kind :revival
        :gftd.intel/subject (:gftd.project/name p)
        :gftd.intel/score (or (:gftd.project/file-count p) 0)
        :gftd.intel/stage :new})
     ds)))

;; ---- 契約更新リスク datom (auto_renew=false もしくは契約満了が近い) ----
(defn renewal-risks []
  (let [cs (->> (read-objs "contract-terms.edn")
                (filter #(and (seq (str (:party_b %)))
                              (or (false? (:auto_renew %))
                                  (and (string? (:term_end %)) (seq (:term_end %))))))
                (take 8))]
    (map-indexed
     (fn [i c]
       {:db/id (str "renew" i)
        :gftd.intel/kind :renewal-risk
        :gftd.intel/subject (:party_b c)
        :gftd.intel/score (or (:amount_jpy c) 0)
        :gftd.intel/note (str "更新確認: " (:contract_type c)
                              (when (:term_end c) (str " (満了 " (:term_end c) ")")))
        :gftd.intel/stage :new})
     cs)))

;; ---- 売上集中(依存)エッジ: gftd → 実契約相手 (金額あり・外部) ----
(defn deps []
  (let [parties (->> (read-objs "contract-terms.edn")
                     (filter #(and (seq (str (:party_b %)))
                                   (number? (:amount_jpy %))
                                   (pos? (:amount_jpy %))
                                   (not (str/includes? (str/lower-case (:party_b %)) "gftd"))
                                   (not (str/includes? (:party_b %) "ギフテ"))))
                     (reduce (fn [m c] (if (m (:party_b c)) m (assoc m (:party_b c) (:amount_jpy c)))) {})
                     (sort-by (comp - val))
                     (take 10))]
    (map-indexed
     (fn [i [party amt]]
       {:db/id (str "dep" i)
        :gftd.dep/from "gftd"
        :gftd.dep/to party
        :gftd.dep/value-jpy amt
        :gftd.dep/kind :revenue})
     parties)))

;; ---- 出力: 全 intel datom を 1 ベクタで ----
(let [all (vec (concat (latent-leads) (revivals) (renewal-risks) (deps)
                       (markets) (people-nodes) (bad-debts)))]
  (println (pr-str all)))
