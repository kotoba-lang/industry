(ns gftd.events
  "re-frame イベントと副作用(fetch)。
   全イベント後に malli で app-db を検証する global interceptor を dev で登録。"
  (:require [re-frame.core :as rf]
            [gftd.db :as db]
            [gftd.schema :as schema]))

;; ---- 副作用 (fetch) ----------------------------------------------------------

(defn- fetch-json [url opts]
  (-> (js/fetch url (clj->js opts)) (.then #(.json %))))

;; サーバ応答を受けたら :set-data へ。POST 系も同じ形を返す。
(rf/reg-fx :http-get
  (fn [url] (-> (fetch-json url {}) (.then #(rf/dispatch [:set-data %])))))

(rf/reg-fx :http-post
  (fn [url] (-> (fetch-json url {:method "POST"}) (.then #(rf/dispatch [:set-data %])))))

;; 任意のイベントへ結果を渡す POST (レポート/要約用; :set-data とは別経路)。
(rf/reg-fx :http-post-cb
  (fn [[url ev]] (-> (fetch-json url {:method "POST"}) (.then #(rf/dispatch [ev %])))))

;; ---- インターセプタ: app-db 形状検証 (Svelte の props 型エラー相当) -----------

(def check-db
  (rf/after (fn [db _event]
              (when ^boolean js/goog.DEBUG (schema/validate-db! db)))))

(rf/reg-global-interceptor check-db)

;; ---- イベント ----------------------------------------------------------------

(rf/reg-event-db :init (fn [_ _] db/default-db))

(rf/reg-event-db
 :set-data
 (fn [d [_ raw]]
   (assoc d :data (js->clj raw :keywordize-keys true) :thinking false)))

(rf/reg-event-fx :refresh
  (fn [_ _] {:http-get "/api/state"}))

(rf/reg-event-fx :advance
  (fn [{:keys [db]} _]
    {:db (assoc db :thinking true)            ; 社員を会議卓へ集合させる
     :http-post "/api/turn/advance"}))

(rf/reg-event-fx :decide
  (fn [_ [_ id what]]
    {:http-post (str "/api/proposal/" id "/" what)}))

;; ---- #4 経営レポート / #2 商談要約 (モーダル表示) -----------------------------

(rf/reg-event-fx :gen-report
  (fn [{:keys [db]} _]
    {:db (assoc db :modal {:title "📊 経営レポート" :loading true})
     :http-post-cb ["/api/report" :set-report]}))

(rf/reg-event-db :set-report
  (fn [db [_ raw]]
    (assoc db :modal {:title "📊 経営レポート" :loading false
                      :body (:report (js->clj raw :keywordize-keys true))})))

(rf/reg-event-fx :summarize
  (fn [{:keys [db]} [_ org]]
    {:db (assoc db :modal {:title (str "商談要約: " org) :loading true})
     :http-post-cb [(str "/api/summarize/" (js/encodeURIComponent org)) :set-summary]}))

(rf/reg-event-db :set-summary
  (fn [db [_ raw]]
    (let [m (js->clj raw :keywordize-keys true)]
      (assoc db :modal {:title (str "商談要約: " (:org m)) :loading false :body (:summary m)}))))

(rf/reg-event-db :close-modal (fn [db _] (assoc db :modal nil)))
