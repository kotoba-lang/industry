(ns probe-sculpt-texture-paint (:require [kami.paint :as p]))
;; 3D テクスチャペイントで難しいのは筆ではなく、**3D の筆圧を UV のテクセルへ
;; 移す**ところ。だから絵ではなく移送を測る:
;;   (a) UV 逆写像の誤差 —— ベイクした world position を、texel-centre を通さずに
;;       解析的に書いた期待値と比べる（両辺を同じ関数に通すと角ずれが打ち消える）
;;   (b) 被覆は円板か —— 塗ったテクセル数を πr² と比べ、r が大きいほど誤差が
;;       小さくなること（＝離散化誤差であること）まで見る
;;   (c) 層は非可換か —— multiply→add と add→multiply が違う絵になるか
;;   (d) 滲ませは効いているか —— 島の縁の外の双一次サンプルが、滲ませ前は背景、
;;       滲ませ後は塗った色。これがレンダリングの黒いシーム線そのもの
;;   (e) チャンネルは隔離されているか —— roughness に塗って base-colour が不変か
(try
  (let [quad {:positions [[0.0 0.0 0.0] [1.0 0.0 0.0] [1.0 1.0 0.0] [0.0 1.0 0.0]]
              :uvs [[0.0 0.0] [1.0 0.0] [1.0 1.0] [0.0 1.0]]
              :indices [0 1 2 0 2 3]}
        two {:positions [[0 0 0] [1 0 0] [1 1 0] [0 1 0] [3 0 0] [4 0 0] [4 1 0] [3 1 0]]
             :uvs [[0.05 0.05] [0.45 0.05] [0.45 0.45] [0.05 0.45]
                   [0.55 0.55] [0.95 0.55] [0.95 0.95] [0.55 0.95]]
             :indices [0 1 2 0 2 3 4 5 6 4 6 7]}
        n 16
        [bs baked] (p/bake-texture quad (p/texture n n) (fn [{:keys [point]}] point))
        uv-err (apply max (for [y (range n) x (range n)]
                            (apply max (map (fn [a b] (Math/abs (- a b)))
                                            [(/ (+ x 0.5) n) (/ (+ y 0.5) n) 0.0]
                                            (p/texel baked x y)))))
        m 64
        painted (fn [r] (let [[_ t] (p/paint-stroke quad {:centre [0.5 0.5 0.0] :radius r
                                                          :strength 1.0 :colour [1.0 1.0 1.0]
                                                          :falloff :constant}
                                                    (p/texture m m [0.0 0.0 0.0]))]
                          (count (filter #(pos? (first %)) (:texture/texels t)))))
        rel (fn [r] (let [want (* Math/PI r r m m)] (/ (Math/abs (- (painted r) want)) want)))
        e1 (rel 0.1) e3 (rel 0.3)
        half (p/texture 2 2 [0.5 0.5 0.5])
        L (fn [b] (p/material-layer {:id b :texture half :blend b}))
        [_ ma] (p/flatten-layers (p/texture 2 2 [0.5 0.5 0.5]) [(L :multiply) (L :add)])
        [_ am] (p/flatten-layers (p/texture 2 2 [0.5 0.5 0.5]) [(L :add) (L :multiply)])
        [_ seam] (p/paint-stroke two {:centre [0.5 0.5 0.0] :radius 2.0 :strength 1.0
                                      :colour [1.0 1.0 1.0] :falloff :constant}
                                 (p/texture 32 32 [0.0 0.0 0.0]))
        grown (p/dilate two seam 3)
        edge [0.47 0.25]
        before (p/sample-bilinear seam edge) after (p/sample-bilinear grown edge)
        mat (p/pbr-material {:base-colour (p/texture 8 8 [0.2 0.2 0.2])
                             :roughness (p/texture 8 8 [0.5])})
        [ms mat2] (p/paint-material quad {:centre [0.5 0.5 0.0] :radius 0.3 :strength 1.0
                                          :colour [0.9] :falloff :constant} mat :roughness)
        bad (first (p/paint-material quad {:centre [0 0 0] :radius 0.1 :strength 1.0}
                                     mat :emissive))
        no-uv (first (p/paint-stroke (dissoc quad :uvs)
                                     {:centre [0 0 0] :radius 0.1 :strength 1.0} (p/texture 4 4)))]
    (cond
      (not= :ok bs) (println "PROBE sculpt-texture-paint FAIL" "ベイクが通らない")
      (> uv-err 1e-12)
      (println "PROBE sculpt-texture-paint FAIL"
               (str "UV 逆写像がずれている（最大 " uv-err "）—— テクセル中心が角に"
                    "なっているか、重心座標の逆写像が近似になっている"))
      (> e3 0.01)
      (println "PROBE sculpt-texture-paint FAIL"
               (str "半径 0.3 の被覆が πr² と " (.toFixed (* 100 e3) 1) "% ずれる"))
      (not (> e1 e3))
      (println "PROBE sculpt-texture-paint FAIL"
               (str "誤差が半径とともに小さくならない（" e1 " → " e3 "）——"
                    " 離散化誤差ではなく形が違う"))
      (= (:texture/texels ma) (:texture/texels am))
      (println "PROBE sculpt-texture-paint FAIL"
               "層の合成が可換 —— 順序が絵を変えないなら層ではない")
      (not (every? zero? before))
      (println "PROBE sculpt-texture-paint FAIL"
               (str "滲ませる前からシームの外が背景でない: " (pr-str before)
                    " —— 比較の前提が崩れている"))
      (not (every? #(> % 0.99) after))
      (println "PROBE sculpt-texture-paint FAIL"
               (str "滲ませてもシームの外が塗った色にならない: " (pr-str after)))
      (not= :ok ms) (println "PROBE sculpt-texture-paint FAIL" "PBR チャンネルに塗れない")
      (not= (get-in mat [:material/channels :base-colour])
            (get-in mat2 [:material/channels :base-colour]))
      (println "PROBE sculpt-texture-paint FAIL"
               "roughness に塗ると base-colour まで変わる —— チャンネルが隔離されていない")
      (not= [0.9] (p/texel (get-in mat2 [:material/channels :roughness]) 4 4))
      (println "PROBE sculpt-texture-paint FAIL" "塗った値が入っていない")
      (not= :error bad)
      (println "PROBE sculpt-texture-paint FAIL" "PBR の語彙に無いチャンネルを受理する")
      (not= :error no-uv)
      (println "PROBE sculpt-texture-paint FAIL" "UV の無い網に塗ることを受理する")
      :else
      (println "PROBE sculpt-texture-paint PASS"
               (str "UV 逆写像 " (if (zero? uv-err) "厳密一致" (.toExponential uv-err 1))
                    " / 被覆 πr² 比 r=0.1 " (.toFixed (* 100 e1) 1) "% → r=0.3 "
                    (.toFixed (* 100 e3) 1) "% / 層は非可換 "
                    (pr-str (first (:texture/texels ma))) " vs "
                    (pr-str (first (:texture/texels am)))
                    " / シーム " (pr-str before) "→" (pr-str after)
                    " / チャンネル隔離"
                    "（⚠ 投影ペイント・tri-planar・ステンシル・筆テクスチャは未実装）"))))
  (catch :default ex (println "PROBE sculpt-texture-paint UNMEASURABLE" (.-message ex))))
