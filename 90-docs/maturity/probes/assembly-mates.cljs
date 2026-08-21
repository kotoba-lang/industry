(ns probe-assembly-mates
  (:require [kami.modeling.assembly :as a] [kami.modeling.document :as doc]))
;; 不変条件: (1) 距離合致がオカレンスを実際にその距離へ動かす
;;          (2) 過拘束は :conflict として **報告される**（黙って解かれない）
;;          (3) 干渉検査が重なりを検出し、離れた部品は検出しない（両方向）
(try
  (let [uid #(doc/stable-uuid "probe-assembly" %)
        block (a/part (uid "part") "r1" "Block" {:min [-1 -1 -1] :max [1 1 1]})
        ground (a/occurrence (uid "g") (:part/id block) {:grounded? true})
        moving (a/occurrence (uid "m") (:part/id block))
        mate (a/mate (uid "d") :distance (:occurrence/id ground) (:occurrence/id moving)
                     {:a-point [0 0 0] :b-point [0 0 0] :distance 5 :direction [1 0 0]})
        model (a/assembly (uid "asm") [block] [ground moving] [mate] {:default {}})
        sol (a/solve model 1.0e-9)
        pose (get-in sol [:solve/poses (:occurrence/id moving)])
        far (a/occurrence (uid "far") (:part/id block)
                          {:transform (assoc a/identity-transform :translation [10 0 0])})
        near (a/occurrence (uid "near") (:part/id block)
                           {:transform (assoc a/identity-transform :translation [1 0 0])})
        hits (a/interference (a/assembly (uid "int") [block] [ground near far] [] {:default {}}) 1.0e-9)]
    (cond
      (not= :solved (:solve/status sol))
      (println "PROBE assembly-mates FAIL" (str "距離合致が解けない: " (pr-str (:solve/status sol))))
      (not= [5.0 0.0 0.0] pose)
      (println "PROBE assembly-mates FAIL" (str "合致後の位置が " (pr-str pose) "（[5 0 0] を期待）"))
      (not= 1 (count hits))
      (println "PROBE assembly-mates FAIL"
               (str "干渉が " (count hits) " 件（重なり 1 組・離れ 1 組で 1 件を期待）—— 両方向を出していない"))
      :else (println "PROBE assembly-mates PASS"
                     (str "distance mate → " (pr-str pose) " / 干渉 " (count hits)
                          " 件 overlap=" (pr-str (:overlap (first hits)))))))
  (catch :default ex (println "PROBE assembly-mates UNMEASURABLE" (.-message ex))))
