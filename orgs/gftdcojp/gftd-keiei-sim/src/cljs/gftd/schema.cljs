(ns gftd.schema
  "re-frame app-db の形状スキーマ。
   :data はサーバ /api/state 由来のスナップショット (src/model.rs の Kpis / Proposal
   (serde) と server.rs state_payload に対応)。
   gftd.events の global interceptor が dev (goog.DEBUG) で毎イベント後に検証し、
   サーバとフロントの形状ズレを Svelte の props 型エラーのように即座に throw する。"
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

;; re-frame app-db 全体。:data は初回 fetch 前は nil。
(def AppDb
  [:map {:closed true}
   [:data     [:maybe AppData]]
   [:thinking :boolean]
   [:modal    [:maybe map?]]    ; レポート/要約モーダルの状態
   [:tab      keyword?]])        ; 表示中タブ

(defn- check! [schema label value]
  (when-not (m/validate schema value)
    (let [errs (me/humanize (m/explain schema value))]
      (js/console.error (str "❌ " label " schema violation:") (clj->js errs))
      (throw (ex-info (str label " schema violation") {:errors errs})))))

(defn validate-db!
  "re-frame app-db 全体を検証。global interceptor から goog.DEBUG ガードで呼ぶ。"
  [db]
  (check! AppDb "app-db" db))

(defn validate!
  "app-data (=:data) 単体を検証 (任意・テスト/直接利用向け)。"
  [data]
  (check! AppData "app-data" data))
