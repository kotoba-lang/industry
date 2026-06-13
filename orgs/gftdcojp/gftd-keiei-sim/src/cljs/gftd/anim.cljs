(ns gftd.anim
  "2Dオフィスの歩行アニメーション。
   60fps の座標更新を re-frame app-db に流すと再計算が走りすぎるため、
   位置だけは reagent の ratom に隔離し、rAF ループで直接更新する。
   ビュー側 (gftd.views) はこの positions を deref して inline style を引く。
   会議集合の目標切替に必要な :thinking のみ app-db から読み取る。"
  (:require [reagent.core :as r]
            [re-frame.db :as rdb]
            [gftd.db :as db]))

;; key(role/id) -> {:x % :y % :left % :top % :moving bool}
(defonce positions (r/atom {}))

(defn- ease!
  "1体を目標(tx,ty)へ ease 移動 + sine ゆらぎ。moving は移動中フラグ(歩行アニメ用)。"
  [key tx ty ph t]
  (let [pos (get @positions key {:x tx :y ty})
        nx  (+ (:x pos) (* 0.05 (- tx (:x pos))))
        ny  (+ (:y pos) (* 0.05 (- ty (:y pos))))
        wob (* 1.3 (js/Math.sin (+ (/ t 600.0) ph)))]
    (swap! positions assoc key
           {:x nx :y ny
            :left (+ nx wob) :top ny
            :moving (> (js/Math.abs (- tx nx)) 0.6)})))

(defn- tick! [t]
  ;; app-db を直接読む (subscribe ではない): アニメループからの read-only 参照。
  (let [thinking (:thinking @rdb/app-db)]
    (doseq [w db/workers]
      (ease! (:role w)
             (if thinking (:mx w) (:hx w))
             (if thinking (:my w) (:hy w))
             (:ph w) t))
    (doseq [a db/ambient]
      (ease! (:id a) (:hx a) (:hy a) (:ph a) t)))
  (js/requestAnimationFrame tick!))

(defonce ^:private started? (atom false))

(defn start! []
  (when-not @started?
    (reset! started? true)
    (js/requestAnimationFrame tick!)))
