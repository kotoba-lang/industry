(ns probe-render-denoise (:require [kotoba.raytrace.denoise :as dn]))
;; デノイザは**絵では測れない**。ノイズを消すだけなら Gaussian でもできる。
;; 見るのは 3 つ:
;;   (1) ノイズ入り → 真値の RMSE が大きく下がる
;;   (2) 同じ台・同じ pass 数の Gaussian に勝つ
;;   (3) **その優位が平坦部よりエッジで大きい** —— ここだけが「エッジを見る
;;       フィルタ」と「よく調整されたぼかし」を分ける。単にぼかしが弱いだけの
;;       フィルタは、どこでも同じ倍率で勝つのでここで落ちる
;;   (4) 逆向き: 綺麗な画像に掛けても綺麗な画像のまま（全部平滑化して
;;       ノイズ指標だけ勝つフィルタを弾く）
(try
  (let [W 64 H 64
        px (for [y (range H) x (range W)]
             (let [left? (< x 32)
                   base (if left? 0.75 0.25)
                   shade (+ 0.6 (* 0.4 (/ y H)))]
               {:c [(* base shade) (* base shade 0.9) (* base shade 0.8)]
                :a (if left? [0.75 0.7 0.65] [0.25 0.2 0.18])
                :n (if left? [0.0 1.0 0.0] [1.0 0.0 0.0])
                :d [(+ 1.0 (* 0.02 y))]}))
        truth (dn/image W H (mapv :c px))
        albedo (dn/image W H (mapv :a px))
        normal (dn/image W H (mapv :n px))
        depth (dn/image W H (mapv :d px))
        state (atom 12345)
        nxt (fn [] (let [x (swap! state (fn [v] (let [v (bit-xor v (bit-shift-left v 13))
                                                      v (bit-xor v (unsigned-bit-shift-right v 17))]
                                                  (bit-or 0 (bit-xor v (bit-shift-left v 5))))))]
                     (- (* 2.0 (/ (unsigned-bit-shift-right x 0) 4294967296.0)) 1.0)))
        input (assoc truth :image/pixels
                     (mapv (fn [p] (mapv (fn [v] (max 0.0 (min 1.0 (+ v (* 0.18 (nxt)))))) p))
                           (:image/pixels truth)))
        [st out] (dn/denoise {:colour input :albedo albedo :normal normal :depth depth} {:passes 4})
        blur (dn/gaussian input 4)
        edge? (fn [i] (let [x (mod i W)] (< 29 x 35)))
        part (fn [a b pred]
               (let [pa (:image/pixels a) pb (:image/pixels b)
                     idx (filter pred (range (count pa)))]
                 (js/Math.sqrt (/ (reduce + (map (fn [i] (reduce + (map (fn [p q] (* (- p q) (- p q)))
                                                                       (nth pa i) (nth pb i)))) idx))
                                  (* 3.0 (count idx))))))
        flat-gain (/ (part blur truth (complement edge?)) (part out truth (complement edge?)))
        edge-gain (/ (part blur truth edge?) (part out truth edge?))
        [_ clean] (dn/denoise {:colour truth :albedo albedo :normal normal :depth depth} {:passes 4})
        r2 (fn [x] (/ (js/Math.round (* 100 x)) 100))
        refuses? (and (= :error (first (dn/denoise {:colour truth :albedo albedo} {:passes 0})))
                      (= :error (first (dn/denoise {:colour truth} {:passes 2}))))]
    (cond
      (not= :ok st)
      (println "PROBE render-denoise FAIL" (str "デノイズできない: " (pr-str out)))
      (> (dn/rmse out truth) (* 0.25 (dn/rmse input truth)))
      (println "PROBE render-denoise FAIL"
               (str "RMSE が 1/4 未満に下がらない: " (dn/rmse input truth) " → " (dn/rmse out truth)))
      (>= (dn/rmse out truth) (dn/rmse blur truth))
      (println "PROBE render-denoise FAIL"
               (str "同じ台の Gaussian に負けている: " (dn/rmse out truth) " vs " (dn/rmse blur truth)))
      (<= edge-gain (* 2.0 flat-gain))
      (println "PROBE render-denoise FAIL"
               (str "Gaussian への優位がエッジ " (r2 edge-gain) "倍 / 平坦部 " (r2 flat-gain)
                    "倍 —— エッジを見ておらず、単にぼかしが弱いだけ"))
      (> (dn/rmse clean truth) 0.01)
      (println "PROBE render-denoise FAIL"
               (str "綺麗な画像を壊している（RMSE " (dn/rmse clean truth) "）—— 全部平滑化して"
                    "ノイズ指標だけ勝つフィルタ"))
      (not refuses?)
      (println "PROBE render-denoise FAIL" "pass 数 0 や特徴バッファ無しを黙って受理している")
      :else
      (println "PROBE render-denoise PASS"
               (str "PSNR " (r2 (dn/psnr input truth)) "dB → " (r2 (dn/psnr out truth))
                    "dB（Gaussian は " (r2 (dn/psnr blur truth)) "dB）/ Gaussian への優位が"
                    "エッジ " (r2 edge-gain) "倍 vs 平坦部 " (r2 flat-gain)
                    "倍 / 綺麗な画像は壊さない / pass 0・特徴無しは拒否"))))
  (catch :default ex (println "PROBE render-denoise UNMEASURABLE" (.-message ex))))
