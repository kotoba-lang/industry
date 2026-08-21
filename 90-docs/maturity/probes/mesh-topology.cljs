(ns probe-mesh-topology (:require [brep.topology :as t] [brep.feature :as f]))
;; 不変条件は「位相が正しく読めること」。押し出した箱で:
;;   V=8 / F=12 / E=18 / Euler=2 / manifold / orientable
;;   convex 12・flat 6（面を割った対角線）・concave 0 —— **箱に凹エッジは無い**
;; 面の巻き方向を揃えないと 8/4/6 になる（法線の符号が形と無関係にずれる）。
;; さらに、境界エッジには二面角も凹凸も **付けない**（穴の上で面取りしないため）。
(try
  (let [sq (f/sketch-feature 1 (f/sketch-plane-xy)
             [(f/sketch-line [0 0] [4 0]) (f/sketch-line [4 0] [4 4])
              (f/sketch-line [4 4] [0 4]) (f/sketch-line [0 4] [0 0])])
        [_ box] (f/evaluate-mesh (-> (f/feature-tree) (f/add-feature sq)
                                     (f/add-feature (f/extrude-feature 2 1 [0 0 1] 3 :new))))
        topo (t/topology box)
        by (fn [c] (count (t/edges-where topo #(= c (:convexity %)))))
        tri (t/topology {:positions [[0 0 0] [1 0 0] [0 1 0]] :indices [0 1 2]})
        w (t/weld-mesh {:positions [[0 0 0] [0 0 0] [1 0 0]] :indices [0 1 2]})]
    (cond
      (not= [8 12 18] [(count (:vertices topo)) (count (:faces topo)) (count (:edges topo))])
      (println "PROBE mesh-topology FAIL"
               (str "V/F/E が " (pr-str [(count (:vertices topo)) (count (:faces topo))
                                         (count (:edges topo))]) "（[8 12 18] を期待）"))
      (not= 2 (t/euler-characteristic topo))
      (println "PROBE mesh-topology FAIL" (str "Euler 標数が " (t/euler-characteristic topo)))
      (not (and (:manifold? topo) (:orientable? topo)))
      (println "PROBE mesh-topology FAIL" "箱が manifold / orientable と判定されない")
      (not= [12 6 0] [(by :convex) (by :flat) (by :concave)])
      (println "PROBE mesh-topology FAIL"
               (str "凹凸の内訳が convex/flat/concave = "
                    (pr-str [(by :convex) (by :flat) (by :concave)])
                    "（[12 6 0] を期待）—— 面の巻き方向が揃っていない疑い"))
      (not= 3 (count (t/boundary-edges tri)))
      (println "PROBE mesh-topology FAIL" "単独三角形の境界エッジを検出しない")
      (some :convexity (vals (:edges tri)))
      (println "PROBE mesh-topology FAIL" "境界エッジに凹凸を付けている（穴の上で面取りする）")
      (not= [0 1] [(count (:indices w)) (:degenerate w)])
      (println "PROBE mesh-topology FAIL"
               (str "溶接で潰れた三角形を報告しない: " (pr-str (select-keys w [:degenerate]))))
      :else (println "PROBE mesh-topology PASS"
                     (str "箱 V8/F12/E18 Euler2 manifold+orientable / "
                          "convex12 flat6 concave0 / 境界と退化を報告"))))
  (catch :default ex (println "PROBE mesh-topology UNMEASURABLE" (.-message ex))))
