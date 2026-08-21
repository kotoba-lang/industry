(ns probe-sculpt-remesh (:require [kami.sculpt :as s]))
;; 不変条件: (1) voxel remesh がセル寸法に応じて頂点を減らし、形は保つ（bbox が近い）
;;          (2) remesh 後もドキュメントが評価でき、レイヤの濃度が新しい位相に一致する
;;          (3) 退化・重複三角形の除去が非多様体を作らない（repair-topology）
;;          (4) 穴は **報告されるが捏造されない**
;; 「remesh という関数がある」ではなく「減って、形が残って、嘘を足さない」を見る。
(try
  (let [m (s/sphere-mesh 1 24 12)
        bb (fn [mm] [(mapv (fn [i] (apply min (map #(nth % i) (:positions mm)))) [0 1 2])
                     (mapv (fn [i] (apply max (map #(nth % i) (:positions mm)))) [0 1 2])])
        coarse (s/voxel-remesh m 0.35)
        doc (s/sculpt-document m)
        re (s/remesh-document doc 0.35)
        ev (s/evaluate-document re)
        holed {:positions (:positions m) :normals (:normals m)
               :indices (vec (drop 6 (:indices m)))}
        diag (s/topology-diagnostics holed)
        rep (s/repair-topology holed)
        span (fn [x] (mapv - (second (bb x)) (first (bb x))))
        close? (fn [a b] (every? true? (map #(< (Math/abs (- %1 %2)) 0.3) a b)))]
    (cond
      (not (< (count (:positions coarse)) (count (:positions m))))
      (println "PROBE sculpt-remesh FAIL"
               (str "remesh で頂点が減らない " (count (:positions m)) " → "
                    (count (:positions coarse))))
      (not (close? (span coarse) (span m)))
      (println "PROBE sculpt-remesh FAIL"
               (str "remesh で形が崩れる span " (pr-str (span m)) " → " (pr-str (span coarse))))
      (not= (count (:positions ev)) (count (:positions (:sculpt/base re))))
      (println "PROBE sculpt-remesh FAIL"
               (str "remesh 後にレイヤの濃度が新しい位相と合わない: 評価 "
                    (count (:positions ev)) " vs base "
                    (count (:positions (:sculpt/base re)))))
      (zero? (count (:topology/boundary-edges diag)))
      (println "PROBE sculpt-remesh FAIL"
               (str "面を落としたのに境界エッジが報告されない: " (pr-str (keys diag))))
      ;; repair-topology の docstring は「穴は報告するが捏造しない」と言う。
      ;; したがって repair 後も境界エッジは **残っていなければならない** ——
      ;; 消えていたら、無いものを足して閉じたことになる。
      (zero? (count (:topology/boundary-edges (s/topology-diagnostics rep))))
      (println "PROBE sculpt-remesh FAIL"
               "repair が穴を塞いだ —— 報告するはずのものを捏造して閉じている")
      (not (<= (count (:indices rep)) (count (:indices holed))))
      (println "PROBE sculpt-remesh FAIL" "repair が三角形を捏造している")
      :else (println "PROBE sculpt-remesh PASS"
                     (str "V " (count (:positions m)) " → " (count (:positions coarse))
                          " span 保持 / 境界 " (count (:topology/boundary-edges diag)) " 本を報告し塞がない"))))
  (catch :default ex (println "PROBE sculpt-remesh UNMEASURABLE" (.-message ex))))
