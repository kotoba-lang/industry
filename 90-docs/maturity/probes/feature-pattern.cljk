(ns probe-feature-pattern (:require [brep.feature :as f] [clojure.string :as str]))
;; 不変条件: (1) bbox が (count-1)×spacing だけ方向に伸びる
;;          (2) 方向ベクトルの **長さが間隔を変えない**（正規化されている）
;;          (3) count<2 / 方向ゼロ / spacing<=0 は拒否
;;          (4) 分離できない :source-features は拒否（要求より多く並べない）
(try
  (let [sq (f/sketch-feature 1 (f/sketch-plane-xy)
             [(f/sketch-line [0 0] [4 0]) (f/sketch-line [4 0] [4 4])
              (f/sketch-line [4 4] [0 4]) (f/sketch-line [0 4] [0 0])])
        base (-> (f/feature-tree) (f/add-feature sq)
                 (f/add-feature (f/extrude-feature 2 1 [0 0 1] 3 :new)))
        [_ b] (f/evaluate-mesh base)
        xr (fn [m] [(apply min (map first (:positions m))) (apply max (map first (:positions m)))])
        [st m] (f/evaluate-mesh (f/add-feature base (f/pattern-feature 3 [2] [1 0 0] 3 10)))
        [_ scaled] (f/evaluate-mesh (f/add-feature base (f/pattern-feature 3 [2] [7 0 0] 3 10)))
        bad (fn [& args] (first (f/evaluate-mesh (f/add-feature base (apply f/pattern-feature args)))))
        sel (-> base (f/add-feature (f/sketch-feature 4 (f/sketch-plane-xy)
                                     [(f/sketch-line [0 0] [2 0]) (f/sketch-line [2 0] [2 2])
                                      (f/sketch-line [2 2] [0 2]) (f/sketch-line [0 2] [0 0])]))
                (f/add-feature (f/extrude-feature 5 4 [0 0 1] 9 :add))
                (f/add-feature (f/pattern-feature 6 [2] [1 0 0] 3 10)))
        [sst smsg] (f/evaluate-mesh sel)]
    (cond
      (not= :ok st) (println "PROBE feature-pattern FAIL" (str "評価が失敗: " (pr-str m)))
      (not= (+ (second (xr b)) 20.0) (double (second (xr m))))
      (println "PROBE feature-pattern FAIL"
               (str "x 上端が " (second (xr m)) "（" (+ (second (xr b)) 20.0) " を期待）"))
      (not= (xr m) (xr scaled))
      (println "PROBE feature-pattern FAIL"
               (str "方向ベクトルの長さが間隔を変えている: " (pr-str [(xr m) (xr scaled)])))
      (not (every? #(= :error %) [(bad 3 [2] [1 0 0] 1 10) (bad 3 [2] [0 0 0] 3 10)
                                  (bad 3 [2] [1 0 0] 3 0)]))
      (println "PROBE feature-pattern FAIL" "退化したパターンを拒否しない")
      (not (and (= :error sst) (str/includes? smsg "Selective patterning")))
      (println "PROBE feature-pattern FAIL" (str "分離できない source-features を拒否しない: " (pr-str smsg)))
      :else (println "PROBE feature-pattern PASS"
                     (str "x " (pr-str (xr b)) " → " (pr-str (xr m))
                          " / 正規化 ok / 退化と選択的パターンは拒否"))))
  (catch :default ex (println "PROBE feature-pattern UNMEASURABLE" (.-message ex))))
