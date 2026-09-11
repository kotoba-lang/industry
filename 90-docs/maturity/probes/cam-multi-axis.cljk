(ns probe-cam-multi-axis
  (:require [kotoba.cam.toolpath :as tp] [kotoba.cam.stock :as stock] [kotoba.cam.tool :as tool]))
;; 3 軸の生成器は全部「工具は +Z に立つ」を前提にしていた。急斜面では垂直な玉は
;; **側面で削る**ことになり、有効切削速度が先端中心でゼロになる —— 一番仕事を
;; している所で仕上げが一番悪い。5 軸はそのためにある。
;; 検査するのは、傾けたことが **数で効いている** ことと、**検証も一緒に一般化された**こと:
;;   (1) :surface-normal が実際に法線（45 度斜面で 0,-0.7071,0.7071）
;;   (2) :lead-lag が頼んだ角度ぶん法線から傾く
;;   (3) 法線に沿った玉は **先端で接触**するので、生成点が曲面上（z=y）に乗る
;;   (4) 同じ点で **垂直に立てると食い込む** —— 傾ける意味が clearance の符号で出る
;;   (5) 検査が標本であることを出力が申告する（:sampled?）
;;   (6) :vertical は :surface-3d へ差し戻される（別の演算だから）
(try
  (let [ramp {:positions [[-10 0 0] [10 0 0] [10 10 10] [-10 10 10]] :indices [0 1 2 0 2 3]}
        tris {:tris (mapv (fn [[a b c]] [(nth (:positions ramp) a) (nth (:positions ramp) b)
                                         (nth (:positions ramp) c)])
                          (partition 3 (:indices ramp)))}
        tl {:id :bn6 :name "6mm" :tool-type :ball-nose :diameter 6.0 :flute-length 8.0
            :overall-length 40.0 :holder-diameter 12.0 :flute-count 2 :corner-radius 3.0
            :material :carbide}
        [lib _] (tool/add (tool/empty-library) tl)
        job (fn [op] (-> (tp/new-job (stock/stock (stock/block 40 40 20) (stock/aluminum-6061)) lib)
                         (tp/add-operation op)))
        segs (tp/generate-toolpath
              (job {:op :surface-5axis :tool-id :bn6 :stepover 4.0 :strategy :raster
                    :axis-strategy :surface-normal :feed-rate 1200.0 :target ramp}))
        cuts (filter #(= :linear (:segment-type %)) segs)
        root2 (/ 1.0 (Math/sqrt 2.0))
        n (tp/tool-axis tris :surface-normal {:x 0 :y 5})
        lead (tp/tool-axis tris :lead-lag {:x 0 :y 5 :feed [1 0 0] :lead 0.2618})
        between (Math/acos (reduce + (map * n lead)))
        along (tp/multi-axis-clearance tris tl [0.0 5.0 5.0] [0.0 (- root2) root2])
        upright (tp/multi-axis-clearance tris tl [0.0 5.0 5.0] [0.0 0.0 1.0])
        refused (fn [f] (try (do (f) :accepted) (catch :default _ :refused)))]
    (cond
      (> (+ (Math/abs (- (nth n 1) (- root2))) (Math/abs (- (nth n 2) root2))) 1e-12)
      (println "PROBE cam-multi-axis FAIL" (str ":surface-normal が法線でない: " (pr-str n)))
      (> (Math/abs (- between 0.2618)) 1e-9)
      (println "PROBE cam-multi-axis FAIL" (str ":lead-lag の傾きが " between "（0.2618 を期待）"))
      (< (count cuts) 10)
      (println "PROBE cam-multi-axis FAIL" (str "5 軸パスの切削が " (count cuts) " 本"))
      (not (every? #(< (Math/abs (- (:z (:end %)) (:y (:end %)))) 1e-9) cuts))
      (println "PROBE cam-multi-axis FAIL"
               "生成点が曲面上に乗らない —— 法線に沿った玉は先端で接触するはず")
      (not (every? :tool-axis cuts))
      (println "PROBE cam-multi-axis FAIL" "切削移動が工具軸を運ばない（post が上向きと未指定を区別できない）")
      (seq (:violations along))
      (println "PROBE cam-multi-axis FAIL"
               (str "法線に沿った工具が食い込む（clearance " (:clearance along) "）"))
      (or (empty? (:violations upright)) (> (:clearance upright) -0.1))
      (println "PROBE cam-multi-axis FAIL"
               (str "同じ点で垂直に立てても食い込まない（clearance " (:clearance upright)
                    "）—— 傾ける意味が出ていない。検査が垂直前提のままではないか"))
      (not (:sampled? along))
      (println "PROBE cam-multi-axis FAIL" "標本検査であることを申告しない")
      (not= :refused (refused #(tp/generate-toolpath
                                (job {:op :surface-5axis :tool-id :bn6 :stepover 4.0
                                      :strategy :raster :axis-strategy :vertical
                                      :feed-rate 1200.0 :target ramp}))))
      (println "PROBE cam-multi-axis FAIL" ":vertical を 5 軸演算として受理する")
      (not= :refused (refused #(tp/tool-axis tris :swarf {:x 0 :y 5})))
      (println "PROBE cam-multi-axis FAIL" "未知の工具軸戦略を受理する")
      :else (println "PROBE cam-multi-axis PASS"
                     (str "法線 " (pr-str (mapv #(.toFixed % 4) n)) " / lead "
                          (.toFixed between 4) " rad / 切削 " (count cuts)
                          " 本すべて曲面上・軸つき / 同一点で法線沿い clearance "
                          (.toFixed (:clearance along) 4) " vs 垂直 "
                          (.toFixed (:clearance upright) 4)
                          "（⚠ 標本検査・治具/機械包絡は未モデル化）"))))
  (catch :default ex (println "PROBE cam-multi-axis UNMEASURABLE" (.-message ex))))
