(ns probe-cam-collision-check
  (:require [kotoba.cam.toolpath :as tp] [kotoba.cam.stock :as stock] [kotoba.cam.tool :as tool]
            [kotoba.cam.vec3 :as v3]))
;; ⚠ この軸は **部分実装**。対象への食い込み検査（gouge）だけが在り、ホルダ・シャンク・
;; 治具・機械包絡は無い。ここで測るのはその区別が **出力に出ている** ことも含む ——
;; 「検査した」と「検査していない」が同じ顔をするのが一番危ない。
;; 不変条件:
;;   (1) 生成した仕上げパスが自分の gouge 検査を通る（弦公差の範囲で）
;;   (2) 0.5mm 下げた経路は捕まり、深さが名指しされる（両方向）
;;   (3) 細分を切ると 0.4mm 以上食い込む —— 適応細分が効いている証拠
;;   (4) 検査点ゼロは合格ではない
;;   (5) 工具半径を省くと拒否（許容高さがそれに依存するので推測は危険）
;;   (6) :not-checked-for がホルダ・治具を名指しする
(try
  (let [target {:positions [[-20 -20 0] [20 -20 0] [20 20 0] [-20 20 0]
                            [-3 -3 4] [3 -3 4] [3 3 4] [-3 3 4]]
                :indices [0 1 2 0 2 3 4 5 6 4 6 7]}
        [lib _] (tool/add (tool/empty-library)
                          {:id :bn6 :name "6mm" :tool-type :ball-nose :diameter 6.0
                           :flute-length 20.0 :overall-length 60.0 :flute-count 2
                           :corner-radius 3.0 :material :carbide})
        run (fn [opts] (tp/generate-toolpath
                        (-> (tp/new-job (stock/stock (stock/block 60 60 20) (stock/aluminum-6061)) lib)
                            (tp/add-operation (merge {:op :surface-3d :tool-id :bn6 :stepover 4.0
                                                      :strategy :raster :feed-rate 1200.0
                                                      :target target} opts)))))
        segs (run {})
        ok (tp/gouge-check segs target {:tool-radius 3.0 :tolerance 0.02})
        raw (tp/gouge-check (run {:max-bisections 0}) target {:tool-radius 3.0})
        sunk (mapv (fn [s] (if (= :linear (:segment-type s))
                             (-> s (update :start #(v3/v3 (:x %) (:y %) (- (:z %) 0.5)))
                                 (update :end #(v3/v3 (:x %) (:y %) (- (:z %) 0.5))))
                             s)) segs)
        bad (tp/gouge-check sunk target {:tool-radius 3.0})
        no-radius (try (do (tp/gouge-check segs target {}) :accepted) (catch :default _ :refused))]
    (cond
      (zero? (:checked ok))
      (println "PROBE cam-collision-check FAIL" "検査点 0 —— 何も見ていない")
      (not (:passed? ok))
      (println "PROBE cam-collision-check FAIL"
               (str "生成した仕上げパスが自分の検査を通らない（最悪 " (:worst-depth ok) "）"))
      (> (:worst-depth ok) 0.01)
      (println "PROBE cam-collision-check FAIL"
               (str "弦公差 0.01 が守られていない（最悪 " (:worst-depth ok) "）"))
      (< (:worst-depth raw) 0.4)
      (println "PROBE cam-collision-check FAIL"
               (str "細分を切っても食い込まない（" (:worst-depth raw) "）—— 適応細分が効いている証拠が出ない"))
      (or (:passed? bad) (< (:worst-depth bad) 0.4) (empty? (:violations bad)))
      (println "PROBE cam-collision-check FAIL" "0.5mm 下げた経路を見逃す")
      (not (every? #(and (:segment %) (:allowed-z %) (:programmed-z %)) (:violations bad)))
      (println "PROBE cam-collision-check FAIL" "違反の場所と深さを名指ししない")
      (not= :refused no-radius)
      (println "PROBE cam-collision-check FAIL" "工具半径なしで検査を受理する")
      (not (and (= #{:gouge-into-target} (:checked-for ok))
                (contains? (:not-checked-for ok) :holder-collision)
                (contains? (:not-checked-for ok) :fixture-collision)))
      (println "PROBE cam-collision-check FAIL"
               "検査した範囲と検査していない範囲を出力で区別しない —— 部分実装が全部に見える")
      :else (println "PROBE cam-collision-check PASS"
                     (str "生成パスは検査点 " (:checked ok) " 点で合格（最悪 "
                          (.toFixed (:worst-depth ok) 5) " ≤ 弦公差 0.01）/ 細分なしなら "
                          (.toFixed (:worst-depth raw) 3) " 食い込む / 0.5mm 沈めた経路は "
                          (count (:violations bad)) " 件を名指しで検出 / "
                          "⚠ ホルダ・治具・機械包絡は :not-checked-for"))))
  (catch :default ex (println "PROBE cam-collision-check UNMEASURABLE" (.-message ex))))
