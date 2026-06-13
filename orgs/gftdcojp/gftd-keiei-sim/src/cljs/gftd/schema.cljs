(ns gftd.schema
  "サーバ /api 由来の app-data の形状スキーマ。
   re-frame を使っていないため app-db の代わりにこの単一マップを検証対象とする。
   dev (goog.DEBUG=true、:simple/:none ビルド) でのみ検証し、サーバとフロントの
   形状ズレを Svelte の props 型エラーのように即座に throw して顕在化させる。
   shape は src/model.rs の Kpis / Proposal (serde) と server.rs state_payload に対応。"
  (:require [malli.core :as m]
            [malli.error :as me]))

;; JSON 由来の数値は JS number。大きな i64 を誤検出しないよう number? を使う。
(def Kpis
  [:map {:closed false}
   [:turn              number?]
   [:cash_jpy          number?]
   [:burn_jpy          number?]
   [:headcount         number?]
   [:morale            number?]
   [:revenue_total_jpy number?]
   [:pipeline_jpy      number?]
   [:runway_months     number?]
   [:status            :string]])

(def Proposal
  [:map {:closed false}
   [:id         :string]
   [:role       :string]
   [:role_label :string]
   [:action     :string]
   [:status     :string]
   [:effect_hint :string]])

(def AppData
  [:map {:closed false}                     ; company / pipeline / recent_activity 等の追加キーは許容
   [:kpis               Kpis]
   [:proposals          [:sequential Proposal]]
   [:llm_live           :boolean]
   [:issued_total_jpy   number?]
   [:received_total_jpy number?]
   [:ledger             [:sequential :map]]
   [:intel {:optional true} [:maybe :map]]])

(defn validate!
  "app-data を検証。形状違反なら console.error で humanize した差分を出して throw。
   呼び出し側で goog.DEBUG ガードして dev のみ実行すること。"
  [data]
  (when-not (m/validate AppData data)
    (let [errs (me/humanize (m/explain AppData data))]
      (js/console.error "❌ app-data schema violation:" (clj->js errs))
      (throw (ex-info "app-data schema violation" {:errors errs})))))
