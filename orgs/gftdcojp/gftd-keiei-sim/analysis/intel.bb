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
         '[clojure.string :as str])

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

;; ---- 潜在リード datom ----
(defn latent-leads []
  (let [orgs (->> (read-objs "crm.edn")
                  (remove #(noise? (:org_domain %)))
                  (sort-by #(- (or (:message_count %) 0)))
                  (take 12))]
    (map-indexed
     (fn [i o]
       {:db/id (str "lead" i)
        :gftd.intel/kind :latent-lead
        :gftd.intel/subject (:org_domain o)
        :gftd.intel/score (or (:message_count o) 0)
        :gftd.intel/confidence (confidence o)
        :gftd.intel/people (count (or (:people o) []))
        :gftd.intel/risk (if (churn? o) :churn :none)
        :gftd.intel/stage :new})
     orgs)))

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
(let [all (vec (concat (latent-leads) (revivals) (renewal-risks) (deps)))]
  (println (pr-str all)))
