(ns probe-toolpath-3axis-surface
  (:require [kotoba.cam.toolpath :as tp] [kotoba.cam.stock :as stock]
            [kotoba.cam.tool :as tool]))
;; `surface-strategies` は 4 戦略を名指しする。不変条件は「4 通り出ること」ではなく:
;;   (1) 実装済みの戦略は **実際に削る**（切削セグメントが出て、対象の高さに追従する）
;;   (2) ボールノーズの落とし込みが **エッジで解析解と一致する**。グリッド標本だけだと
;;       台のエッジで 1.768 のところ 1.000 を返す —— 0.77mm の食い込みで、平らな所では
;;       一致するので経路を眺めても出ない
;;   (3) 未実装の戦略は **名指しで拒否**（rapid 1 本を仕上げパスと呼ばない）
;;   (4) target の無い仕上げパスも拒否
;;   (5) target の footprint の外へ出ない（外では玉が縁から落ちて z が下がる）
(try
  (let [target {:positions [[-20 -20 0] [20 -20 0] [20 20 0] [-20 20 0]
                            [-3 -3 4] [3 -3 4] [3 3 4] [-3 3 4]]
                :indices [0 1 2 0 2 3 4 5 6 4 6 7]}
        tris {:tris (mapv (fn [[a b c]] [(nth (:positions target) a) (nth (:positions target) b)
                                         (nth (:positions target) c)])
                          (partition 3 (:indices target)))}
        [lib _] (tool/add (tool/empty-library)
                          {:id :bn6 :name "6mm ball" :tool-type :ball-nose :diameter 6.0
                           :flute-length 20.0 :overall-length 60.0 :flute-count 2
                           :corner-radius 3.0 :material :carbide})
        job (fn [op] (-> (tp/new-job (stock/stock (stock/block 60 60 20) (stock/aluminum-6061)) lib)
                         (tp/add-operation op)))
        segs (tp/generate-toolpath
              (job {:op :surface-3d :tool-id :bn6 :stepover 4.0 :strategy :raster
                    :feed-rate 1200.0 :spindle-rpm 12000 :target target}))
        cuts (filter #(= :linear (:segment-type %)) segs)
        zs (map #(:z (:end %)) cuts)
        drop-ok (every? (fn [[x e]] (< (Math/abs (- (tp/ball-nose-drop tris 3.0 x 0.0 12) e)) 0.002))
                        [[0.0 4.0] [4.0 3.828] [5.0 3.236] [5.9 1.768] [6.5 0.0]])
        refused (into {} (map (fn [s]
                                [s (try (do (tp/generate-toolpath
                                             (job {:op :surface-3d :tool-id :bn6 :stepover 4.0
                                                   :strategy s :feed-rate 1200.0 :target target}))
                                            :accepted)
                                        (catch :default _ :refused))])
                              (disj tp/surface-strategies :raster)))
        no-target (try (do (tp/generate-toolpath
                            (job {:op :surface-3d :tool-id :bn6 :stepover 4.0 :strategy :raster
                                  :feed-rate 1200.0})) :accepted)
                       (catch :default _ :refused))]
    (cond
      (< (count cuts) 20)
      (println "PROBE toolpath-3axis-surface FAIL"
               (str ":raster が切削セグメントを " (count cuts) " 本しか出さない"))
      (not (every? #(= 1200.0 (:feed-rate %)) cuts))
      (println "PROBE toolpath-3axis-surface FAIL" "切削に送り速度が乗っていない")
      (> (Math/abs (- (apply max zs) 4.0)) 1e-6)
      (println "PROBE toolpath-3axis-surface FAIL"
               (str "台の高さ 4.0 に届かない（最大 z " (apply max zs) "）"))
      (< (apply min zs) -1e-9)
      (println "PROBE toolpath-3axis-surface FAIL"
               (str "床より下に潜る（最小 z " (apply min zs) "）—— target の footprint で止めていない"))
      (not drop-ok)
      (println "PROBE toolpath-3axis-surface FAIL"
               "落とし込みがエッジで解析解と合わない —— グリッド標本だけになっていないか")
      (not (every? #(= :refused %) (vals refused)))
      (println "PROBE toolpath-3axis-surface FAIL" (str "未実装の戦略を受理する: " (pr-str refused)))
      (not= :refused no-target)
      (println "PROBE toolpath-3axis-surface FAIL" "target の無い仕上げパスを受理する")
      :else (println "PROBE toolpath-3axis-surface PASS"
                     (str "raster 切削 " (count cuts) " 本 / z 0〜4 で対象に追従 / "
                          "落とし込みはエッジでも解析解一致 / 未実装 " (count refused)
                          " 戦略と target 無しは拒否（⚠ 干渉検査は :absent —— "
                          "生成はするが検証はしない）"))))
  (catch :default ex (println "PROBE toolpath-3axis-surface UNMEASURABLE" (.-message ex))))
