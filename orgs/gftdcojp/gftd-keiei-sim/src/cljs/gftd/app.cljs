(ns gftd.app
  "gftd 経営シム ダッシュボード (reagent + re-frame)。
   Gather.town 風の 2D オフィスで LLM エージェント社員が歩き・働く様子を描画し、
   kotoba-datomic 由来の intel (確度/離反・更新リスク/依存) を関係グラフ等で見せる。
   サーバ権威 (/api) + SSE。状態は re-frame app-db、ビューは reagent。"
  (:require [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [gftd.events]          ; reg-event-* / reg-fx / global-interceptor 登録
            [gftd.subs]            ; reg-sub 登録
            [gftd.views :as views]
            [gftd.anim :as anim]))

(defn- mount! []
  (rdom/render [views/dashboard] (.getElementById js/document "app")))

(defn start! []
  (rf/dispatch-sync [:init])          ; app-db 初期化 (同期)
  (mount!)                            ; reagent マウント
  (anim/start!)                       ; 歩行アニメ rAF ループ
  ;; SSE: サーバ側の更新で状態を取り直す
  (let [es (js/EventSource. "/api/events")]
    (set! (.-onmessage es) (fn [_] (rf/dispatch [:refresh]))))
  (rf/dispatch [:refresh]))           ; 初回ロード

(start!)
