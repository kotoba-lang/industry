(ns probe-bom
  (:require [kami.modeling.assembly :as a] [kami.modeling.document :as doc]
            [kami.modeling.drawing :as dr]))
;; 不変条件: BOM の数量が実際のオカレンス数を数え、抑制されたものを除く。
;; 「BOM がある」ではなく「数が合う」を検査する —— 数が合わない BOM は発注を壊す。
(try
  (let [uid #(doc/stable-uuid "probe-bom" %)
        block (a/part (uid "p") "r7" "Bracket" {:min [0 0 0] :max [1 1 1]})
        occs [(a/occurrence (uid "o1") (:part/id block))
              (a/occurrence (uid "o2") (:part/id block))
              (a/occurrence (uid "o3") (:part/id block) {:suppressed? true})]
        model (a/assembly (uid "asm") [block] occs [] {:default {}})
        bom (dr/bill-of-materials (:assembly/occurrences model) (:assembly/parts model))
        row (first bom)
        ;; part-mass-properties は [part density]
        mass (a/part-mass-properties block 2700.0)]
    (cond
      (not= 1 (count bom)) (println "PROBE bom FAIL" (str "行数 " (count bom) "（1 を期待）"))
      (not= 2 (:bom/quantity row))
      (println "PROBE bom FAIL" (str "数量 " (:bom/quantity row) "（抑制 1 を除いた 2 を期待）"))
      (not= "r7" (:bom/part-revision row))
      (println "PROBE bom FAIL" "リビジョンが BOM に載らない")
      (not (and (map? mass) (pos? (or (:mass mass) (:mass/value mass) 0))))
      (println "PROBE bom FAIL" (str "質量特性が出ない: " (pr-str mass)))
      :else (println "PROBE bom PASS"
                     (str "qty=" (:bom/quantity row) " rev=" (:bom/part-revision row)
                          " mass=" (pr-str (select-keys mass [:mass :mass/value :volume]))))))
  (catch :default ex (println "PROBE bom UNMEASURABLE" (.-message ex))))
