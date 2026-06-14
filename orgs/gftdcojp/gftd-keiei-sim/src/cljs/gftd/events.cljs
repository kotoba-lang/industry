(ns gftd.events
  "re-frame イベントと副作用(fetch)。
   全イベント後に malli で app-db を検証する global interceptor を dev で登録。"
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
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

;; JSON ボディ付き POST (チャット相談用)。
(rf/reg-fx :http-post-json
  (fn [[url body ev]]
    (-> (fetch-json url {:method "POST"
                         :headers {"Content-Type" "application/json"}
                         :body (js/JSON.stringify (clj->js body))})
        (.then #(rf/dispatch [ev %])))))

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

;; Arbor Decide: 仮説ノードを prune(打ち切り)/continue(次ターン再探索)する。
;; 戻りは HTR ツリーのみ(:set-data ではなく) → 全体 state は別途 :refresh で同期。
(rf/reg-event-fx :htr-decide
  (fn [_ [_ id what]]
    {:http-post-cb [(str "/api/htr/" id "/" what) :refresh-after]}))

(rf/reg-event-fx :refresh-after
  (fn [_ _] {:http-get "/api/state"}))

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
(rf/reg-event-db :set-tab (fn [db [_ t]] (assoc db :tab t)))

;; ---- 意思決定項目のチャット相談 (承認/却下の二択でなく対話) ------------------

(rf/reg-event-db :open-chat
  (fn [db [_ item]]
    (assoc db :modal {:title (str "💬 相談: " (:title item))
                      :ctx (str (:badge item) " / " (:title item) " / " (:detail item))
                      :chat [] :loading false})))

(rf/reg-event-fx :send-chat
  (fn [{:keys [db]} [_ msg]]
    (let [m (:modal db)
          chat (conj (vec (:chat m)) {:role "user" :text msg})
          history (->> chat (map #(str (if (= "user" (:role %)) "CEO: " "参謀: ") (:text %))) (str/join "\n"))]
      {:db (assoc db :modal (assoc m :chat chat :loading true))
       :http-post-json ["/api/chat" {:context (:ctx m) :message msg :history history} :recv-chat]})))

(rf/reg-event-db :recv-chat
  (fn [db [_ raw]]
    (let [reply (:reply (js->clj raw :keywordize-keys true))
          m (:modal db)]
      (assoc db :modal (assoc m :loading false
                              :chat (conj (vec (:chat m)) {:role "ai" :text reply}))))))

;; 実 M365 (Outlook) ライブ同期
(rf/reg-event-fx :m365-sync (fn [_ _] {:http-post "/api/m365/sync"}))

;; メールトリアージ (#2) / 会議準備サマリ (#3) — gemma4 生成をモーダル表示
(rf/reg-event-fx :m365-triage
  (fn [{:keys [db]} _]
    {:db (assoc db :modal {:title "📨 メールトリアージ" :loading true})
     :http-post-cb ["/api/m365/triage" :set-triage]}))
(rf/reg-event-db :set-triage
  (fn [db [_ raw]]
    (assoc db :modal {:title "📨 メールトリアージ" :loading false
                      :body (:triage (js->clj raw :keywordize-keys true))})))

(rf/reg-event-fx :m365-prep
  (fn [{:keys [db]} _]
    {:db (assoc db :modal {:title "📅 会議準備サマリ" :loading true})
     :http-post-cb ["/api/m365/meeting-prep" :set-prep]}))
(rf/reg-event-db :set-prep
  (fn [db [_ raw]]
    (assoc db :modal {:title "📅 会議準備サマリ" :loading false
                      :body (:prep (js->clj raw :keywordize-keys true))})))
