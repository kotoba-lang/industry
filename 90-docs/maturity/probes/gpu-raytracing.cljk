(ns probe-gpu-raytracing
  (:require [kotoba.raytrace.bvh :as bvh] [kotoba.raytrace.wgsl :as wgsl]))
;; 不変条件: (1) BVH が正しい三角形に当たる (2) 外した光線は nil (3) 距離が正しい
;;          (4) WGSL の ray-query ソースが出る（GPU 側の実体）
(try
  (let [tri (fn [id a b c] {:id id :v0 a :v1 b :v2 c})
        tris [(tri 0 [-1 -1 5] [1 -1 5] [0 1 5])
              (tri 1 [-1 -1 20] [1 -1 20] [0 1 20])]
        b (bvh/build tris)
        hit (bvh/traverse b [0 0 0] [0 0 1])
        miss (bvh/traverse b [0 0 0] [0 1 0])
        src (try (wgsl/ray-query-source) (catch :default _ nil))]
    (cond
      (nil? hit) (println "PROBE gpu-raytracing FAIL" "正面の三角形に当たらない")
      (not= 0 (:tri-id hit)) (println "PROBE gpu-raytracing FAIL"
                                      (str "手前でなく tri-id=" (:tri-id hit) " に当たる"))
      (> (Math/abs (- (:t hit) 5.0)) 1e-4)
      (println "PROBE gpu-raytracing FAIL" (str "交点距離 t=" (:t hit) "（5.0 を期待）"))
      (some? miss) (println "PROBE gpu-raytracing FAIL" "外れた光線が hit を返す")
      (not (and src (re-find #"rayQuery|ray_query" src)))
      (println "PROBE gpu-raytracing FAIL" "WGSL の ray-query ソースが出ない（CPU BVH のみ）")
      :else (println "PROBE gpu-raytracing PASS"
                     (str "nearest tri=" (:tri-id hit) " t=" (:t hit) " wgsl=" (count src) " chars"))))
  (catch :default ex (println "PROBE gpu-raytracing UNMEASURABLE" (.-message ex))))
