(ns gftd.priority
  "意思決定の優先順位付け (情報設計の土台)。
   提案/更新リスク/不良債権/離反・有力リード/未読メール を 1 本のキューに統合し、
   WSJF で採点する。各項目に MCDA 内訳(影響/緊急/リスク低減/工数)と
   minimax の『見送り時の最悪ケース』を付与して意思決定を支援する。")

(defn- clamp10 [x] (max 0 (min 10 x)))
(defn- man [v] (js/Math.round (/ v 10000.0)))

;; ---- 各ソース → アクション項目 ----------------------------------------------

(defn- proposal->action [p]
  (let [role (:role p)
        impact (case role "ceo" 8 "sales" 7 "finance" 6 "eng" 6 "legal" 5 6)
        risk   (if (#{"finance" "legal"} role) 7 4)]
    {:id (str "prop-" (:id p)) :kind :proposal :badge (:role_label p)
     :title (:action p) :detail (:effect_hint p)
     :impact impact :urgency 5 :risk risk :effort 2
     :worst "見送り → 商談/対応が1四半期停滞・士気低下"
     :actions [{:label "承認" :ev [:decide (:id p) "approve"]}
               {:label "却下" :ev [:decide (:id p) "reject"]}]}))

(defn- renewal->action [r i]
  {:id (str "renew-" i) :kind :renewal :badge "更新リスク"
   :title (:subject r) :detail (:note r)
   :impact 7 :urgency 8 :risk 8 :effort 4
   :worst "契約失効 → 取引消滅・売上毀損" :actions []})

(defn- baddebt->action [b i]
  (let [amt (:amount_jpy b)]
    {:id (str "bd-" i) :kind :baddebt :badge "不良債権"
     :title (:subject b) :detail (str "売掛 " (man amt) "万円 回収要確認")
     :impact (clamp10 (/ amt 2.0e7)) :urgency 6 :risk 9 :effort 5
     :worst (str "全額焦付き → ¥" (man amt) "万 損失") :actions []}))

(defn- lead->action [l i]
  (let [churn? (= (:risk l) "churn")]
    {:id (str "lead-" i) :kind (if churn? :churn :lead)
     :badge (if churn? "離反リスク" "有力リード")
     :title (:subject l) :detail (str "確度 " (:confidence l) " / pw " (:path_weight l))
     :impact (clamp10 (/ (:path_weight l) 10.0)) :urgency (if churn? 7 4)
     :risk (if churn? 6 3) :effort 4
     :worst (if churn? "接触途絶 → 失注・関係消滅" "競合先行 → 機会損失") :actions []}))

(defn- inbox->action [unread]
  {:id "inbox" :kind :inbox :badge "受信トレイ"
   :title (str "未読 " unread " 件のトリアージ") :detail "重要メールの抽出と一次対応"
   :impact 5 :urgency 7 :risk 5 :effort 3
   :worst "重要連絡の見落とし → 信頼/商談毀損"
   :actions [{:label "トリアージ" :ev [:m365-triage]}]})

;; ---- WSJF スコア + 統合キュー ----------------------------------------------

(defn wsjf
  "WSJF = (影響 + 緊急 + リスク低減) / 工数。値が大きいほど優先。"
  [{:keys [impact urgency risk effort]}]
  (/ (+ impact urgency risk) (max 1 effort)))

(defn queue
  "全ソースを統合し WSJF 降順に並べた意思決定キューを返す。"
  [data]
  (let [it (:intel data)
        lm (:live_m365 data)
        props (->> (:proposals data) (filter #(= (:status %) "pending")) (mapv proposal->action))
        renews (map-indexed (fn [i r] (renewal->action r i)) (take 5 (:renewal_risks it)))
        bds (map-indexed (fn [i b] (baddebt->action b i)) (take 5 (:bad_debts it)))
        leads (map-indexed (fn [i l] (lead->action l i))
                           (take 6 (filter #(or (= (:risk %) "churn") (>= (or (:confidence %) 0) 85))
                                           (:latent_leads it))))
        inbox (when (and lm (> (or (:unread lm) 0) 20)) [(inbox->action (:unread lm))])]
    (->> (concat props renews bds leads inbox)
         (map #(assoc % :score (wsjf %)))
         (sort-by :score >)
         vec)))
