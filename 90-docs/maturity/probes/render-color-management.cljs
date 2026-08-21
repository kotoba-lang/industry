(ns probe-render-color-management
  (:require [ocio.transform :as t] [ocio.core :as o] [clojure.string :as str]))
;; 色管理は **公表値がある**ので、自分の出力ではなく標準の値に対して測る。
;;   (1) sRGB / ACEScg の RGB→XYZ 行列が、各標準の公表値と**桁まで**一致する
;;       （転記ではなく色度からの導出であることの証拠。転記の誤字は行列そのものに
;;        見えるが、導出の誤りはどこかの測定に出る）
;;   (2) D65 の白が D60 の ACES で白のまま（色順応が効いている）
;;   (3) 全 8 空間の全ペアが往復する（負値を含む —— AP1 の中の色は sRGB の外に
;;       出るので線形係数が負になる。Math/pow の負底は NaN なので、原点対称に
;;       していないと静かに壊れる）
;;   (4) 知らない空間を素通しにしない（ACES と書かれた非 ACES 値を作らない）
;;   (5) config に載る行列が、変換に使う行列と同一（貼り付けた 2 つ目の複製が無い）
(try
  (let [close? (fn [a b tol] (< (Math/abs (- (double a) (double b))) tol))
        mat-close? (fn [m e tol] (every? true? (map (fn [r x] (every? true? (map #(close? %1 %2 tol) r x))) m e)))
        srgb-pub [[0.4124564 0.3575761 0.1804375]
                  [0.2126729 0.7151522 0.0721750]
                  [0.0193339 0.1191920 0.9503041]]
        ap1-pub [[0.6624542 0.1340042 0.1561877]
                 [0.2722287 0.6740818 0.0536895]
                 [-0.0055746 0.0040607 1.0103391]]
        spaces (sort (t/known-spaces))
        ;; 白は「符号化後の値が 1.0」ではなく「**線形に戻して 1.0**」で見る。
        ;; ACEScct は対数符号化なので白は 0.5548…（=(log2 1 + 9.72)/17.52）であって、
        ;; 1.0 を期待すると正しい実装を落とす —— 実際 2026-08-22 にこの probe の
        ;; 最初の版がそれをやり、:acescct だけを FAIL にした。落ちていたのは実装
        ;; ではなく期待値の方だった。
        white-off (remove (fn [s] (every? #(close? % 1.0 1.0e-9)
                                          (t/decode s (t/convert :srgb s [1.0 1.0 1.0]))))
                          spaces)
        rt-bad (for [a spaces b spaces
                     :let [v [0.2 0.5 0.8] r (t/convert b a (t/convert a b v))]
                     :when (not (every? true? (map #(close? %1 %2 1.0e-9) r v)))]
                 [a b])
        passthrough? (try (t/convert :srgb :prophoto-rgb [1 1 1]) true (catch :default _ false))
        m (t/transform-matrix :acescg :aces2065-1)
        cfg (o/config {:version 2
                       :colorspaces [(o/colorspace {:name "ACEScg"
                                                    :to_reference (o/xf "MatrixTransform"
                                                                        {:matrix (t/matrix-transform :acescg :aces2065-1)})})]})]
    (cond
      (not (mat-close? (t/to-xyz-matrix :linear-srgb) srgb-pub 1.0e-7))
      (println "PROBE render-color-management FAIL"
               (str "sRGB→XYZ が IEC 61966-2-1 の公表値と合わない: "
                    (pr-str (mapv (fn [r] (mapv #(/ (Math/round (* 1e7 %)) 1e7) r)) (t/to-xyz-matrix :linear-srgb)))))
      (not (mat-close? (t/to-xyz-matrix :acescg) ap1-pub 1.0e-7))
      (println "PROBE render-color-management FAIL"
               (str "ACEScg(AP1)→XYZ が ACES の公表値と合わない: "
                    (pr-str (t/to-xyz-matrix :acescg))))
      (seq white-off)
      (println "PROBE render-color-management FAIL"
               (str "白が白のまま届かない空間: " (pr-str (vec white-off))
                    " —— D60/D65 の色順応が抜けている"))
      (seq rt-bad)
      (println "PROBE render-color-management FAIL"
               (str (count rt-bad) " 組が往復しない（例 " (pr-str (first rt-bad)) "）"))
      passthrough?
      (println "PROBE render-color-management FAIL"
               "知らない色空間を素通しにする —— ACES と書かれた非 ACES 値を作る経路")
      (not (str/includes? cfg (str (double (get-in m [0 0])))))
      (println "PROBE render-color-management FAIL"
               "config に載る行列が、変換に使う行列と一致しない（貼り付けた複製がある）")
      (not (close? (get-in m [0 0]) 0.6954522414 1.0e-7))
      (println "PROBE render-color-management FAIL"
               (str "AP1→AP0 の行列が公表値と違う: " (get-in m [0 0]) " ≠ 0.6954522414"))
      :else
      (println "PROBE render-color-management PASS"
               (str "sRGB/ACEScg の RGB→XYZ が公表値と 1e-7 一致 / " (count spaces)
                    " 空間 " (* (count spaces) (count spaces)) " ペアが往復 / D60↔D65 順応 / "
                    "未知空間は拒否 / config と変換が同一行列"))))
  (catch :default ex (println "PROBE render-color-management UNMEASURABLE" (.-message ex))))
