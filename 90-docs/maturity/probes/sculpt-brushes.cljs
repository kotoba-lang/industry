(ns probe-sculpt-brushes (:require [kami.sculpt :as s]))
;; 不変条件: (1) ブラシが頂点を動かす (2) 対称を付けると動く頂点が増える
;;          (3) マスクした頂点は保護される —— 3 つ目が無いブラシは塗り絵である。
(try
  (let [m (s/sphere-mesh 1 16 8)
        b (s/brush [1 0 0] 0.8 0.25 :inflate)
        one (s/apply-stroke m b nil)
        both (s/apply-stroke m b :x)
        moved (fn [a bb] (count (filter false? (map = (:positions a) (:positions bb)))))
        mask-b (s/brush [1 0 0] 0.7 1 :mask)
        masked (s/apply-stroke m mask-b nil)
        prot (s/apply-stroke masked (s/brush [1 0 0] 0.7 0.5 :inflate))
        plain (s/apply-stroke m (s/brush [1 0 0] 0.7 0.5 :inflate))
        idx (apply max-key #(nth (:masks masked) %) (range (count (:masks masked))))]
    (cond
      (zero? (moved m one)) (println "PROBE sculpt-brushes FAIL" "ブラシが頂点を 1 つも動かさない")
      (not (> (moved m both) (moved m one)))
      (println "PROBE sculpt-brushes FAIL" "対称を付けても動く頂点が増えない")
      (= (nth (:positions plain) idx) (nth (:positions prot) idx))
      (println "PROBE sculpt-brushes FAIL" "マスクが頂点を保護しない")
      :else (println "PROBE sculpt-brushes PASS"
                     (str "moved=" (moved m one) " 対称=" (moved m both) " mask 保護 ok"))))
  (catch :default ex (println "PROBE sculpt-brushes UNMEASURABLE" (.-message ex))))
