(ns probe-modifier-stack (:require [kami.modeling :as m]))
;; 不変条件: modifier スタックが **非破壊** であること。
;;   (1) modifier を積んでも元オブジェクトのメッシュは変わらない
;;   (2) 順序が結果を変える（scale→array と array→scale は別物）
;;   (3) enabled? false は評価から外れる
;;   (4) 登録されていない kind は名指しで拒否される
;; 「modifier という語がある」ではなく「積んで、並べ替えて、外せる」を検査する。
(try
  (let [quad (m/mesh [[0 0 0] [1 0 0] [1 1 0] [0 1 0]] [[0 1 2 3]])
        obj (m/object (random-uuid) "probe" quad)   ;; [id name mesh]
        with (fn [o & mods] (reduce m/add-modifier o mods))
        ev (fn [o] (m/evaluated-object-mesh o))
        sub (m/modifier :subdivision {:levels 1})
        base (ev obj)
        subd (ev (with obj sub))
        ;; 順序の検査には **可換でない** 組を使う。subdivision と array は
        ;; 頂点数が両順序で 27 になり（4→9→27 と 4→12→27）、数では区別できない。
        ;; translate→mirror は x が -(x+1)、mirror→translate は -x+1 で座標が違う。
        tr (m/modifier :translate {:offset [1 0 0]})
        mir (m/modifier :mirror {:axis :x})
        both-a (ev (with obj tr mir))
        both-b (ev (with obj mir tr))
        off (ev (with obj (assoc sub :modifier/enabled? false)))]
    (cond
      (not= (count (:mesh/vertices quad)) (count (:mesh/vertices base)))
      (println "PROBE modifier-stack FAIL" "modifier 無しで既にメッシュが変わっている")
      (= (count (:mesh/vertices base)) (count (:mesh/vertices subd)))
      (println "PROBE modifier-stack FAIL" "subdivision を積んでも頂点が増えない")
      (not= (:object/mesh (with obj sub)) quad)
      (println "PROBE modifier-stack FAIL" "modifier が元メッシュを破壊している（非破壊でない）")
      (= (:mesh/vertices both-a) (:mesh/vertices both-b))
      (println "PROBE modifier-stack FAIL"
               (str "可換でない 2 つの modifier の順序が結果を変えない: "
                    (pr-str (first (:mesh/vertices both-a)))
                    " —— スタックではなく集合として扱っている疑い"))
      (not= (count (:mesh/vertices base)) (count (:mesh/vertices off)))
      (println "PROBE modifier-stack FAIL" ":modifier/enabled? false が効かない")
      (not (try (m/validate-modifier (m/modifier :no-such-kind)) false
                (catch :default _ true)))
      (println "PROBE modifier-stack FAIL" "未登録の modifier kind を拒否しない")
      :else (println "PROBE modifier-stack PASS"
                     (str "V " (count (:mesh/vertices base)) " → subdiv "
                          (count (:mesh/vertices subd))
                          " / 順序で "
                          (count (filter false? (map = (:mesh/vertices both-a)
                                                     (:mesh/vertices both-b))))
                          "/" (count (:mesh/vertices both-a)) " 頂点が相違"
                          " / 非破壊・無効化・未登録拒否 ok"))))
  (catch :default ex (println "PROBE modifier-stack UNMEASURABLE" (.-message ex))))
