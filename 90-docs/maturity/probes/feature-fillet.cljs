(ns probe-feature-fillet (:require [brep.feature :as f] [brep.topology :as t] [clojure.string :as str]))
;; フィレットは面取りと違って**平面切断ではない**。それでも除去量には閉じた式が
;; ある —— 断面は r×r の正方形から四分円を抜いた形なので、長さ L の稜 1 本で
;; ちょうど L·r²(1−π/4)。弧は内接なので実際はわずかに**多く**削れ、分割数の
;; 2 乗で収束する。
;;
;; そして **boolean を信用しない**ことを見る。`brep.mesh-csg` は接に近い多角形の
;; 群に耐えないことがあり、開いた曲面（= 体積が逆方向に間違った「立体」）を
;; 返す。閉包を検査して**拒否している**ことまでが実装。
(try
  (let [vol (fn [m] (Math/abs (/ (reduce + (map (fn [[a b c]]
                    (let [p (nth (:positions m) a) q (nth (:positions m) b) r (nth (:positions m) c)]
                      (reduce + (map * p [(- (* (q 1) (r 2)) (* (q 2) (r 1)))
                                          (- (* (q 2) (r 0)) (* (q 0) (r 2)))
                                          (- (* (q 0) (r 1)) (* (q 1) (r 0)))]))))
                    (partition 3 (:indices m)))) 6.0)))
        base (-> (f/feature-tree)
                 (f/add-feature (f/sketch-feature 1 (f/sketch-plane-xy)
                   [(f/sketch-line [0 0] [10 0]) (f/sketch-line [10 0] [10 10])
                    (f/sketch-line [10 10] [0 10]) (f/sketch-line [0 10] [0 0])]))
                 (f/add-feature (f/extrude-feature 2 1 [0 0 1] 6 :new)))
        [_ m0] (f/evaluate-mesh base)
        tp0 (t/topology m0)
        edge-at (fn [pred] (first (filter (fn [[i j]] (and (pred (nth (:vertices tp0) i))
                                                           (pred (nth (:vertices tp0) j))))
                                          (t/sharp-edges tp0 :convex 0.2))))
        e (edge-at (fn [p] (and (zero? (double (p 1))) (zero? (double (p 2))))))
        e2 (edge-at (fn [p] (and (= 10.0 (double (p 1))) (= 6.0 (double (p 2))))))
        exact (* 10.0 1.0 1.0 (- 1.0 (/ js/Math.PI 4.0)))
        rel (fn [segs]
              (let [[st m] (f/evaluate-mesh (f/add-feature base {:kind :fillet :id 3 :edges [e]
                                                                 :radius 1.0 :segments segs}))]
                (when (= :ok st)
                  {:rel (/ (- (- 600.0 (vol m)) exact) exact)
                   :open (count (t/boundary-edges (t/topology m)))
                   :angles (frequencies (map #(js/Math.round (* 180.0 (/ (:dihedral %) js/Math.PI)))
                                             (filter #(= :manifold (:kind %))
                                                     (vals (:edges (t/topology m))))))})))
        r8 (rel 8) r16 (rel 16) r32 (rel 32)
        chamfer? (fn [a] (pos? (get a 45 0)))
        open-case (f/evaluate-mesh (f/add-feature base {:kind :fillet :id 3 :edges [e e2]
                                                        :radius 0.5 :segments 16}))
        big (f/evaluate-mesh (f/add-feature base {:kind :fillet :id 3 :edges [e] :radius 6.0 :segments 8}))
        one-seg (f/evaluate-mesh (f/add-feature base {:kind :fillet :id 3 :edges [e] :radius 1.0 :segments 1}))
        e3 (fn [x] (/ (js/Math.round (* 1e5 x)) 1e5))]
    (cond
      (not (contains? (f/supported-feature-kinds) :fillet))
      (println "PROBE feature-fillet FAIL" "registry に :fillet が無い")
      (or (nil? r8) (nil? r16) (nil? r32))
      (println "PROBE feature-fillet FAIL" "8/16/32 分割のどれかが評価できない")
      (some pos? (map :open [r8 r16 r32]))
      (println "PROBE feature-fillet FAIL"
               (str "閉じた立体が返らない: 境界 " (pr-str (mapv :open [r8 r16 r32]))))
      (not (every? pos? (map :rel [r8 r16 r32])))
      (println "PROBE feature-fillet FAIL"
               (str "内接弧なら解析解より多く削れるはず: " (pr-str (mapv :rel [r8 r16 r32]))))
      (or (> (js/Math.abs (- (/ (:rel r8) (:rel r16)) 4.0)) 0.5)
          (> (js/Math.abs (- (/ (:rel r16) (:rel r32)) 4.0)) 0.5))
      (println "PROBE feature-fillet FAIL"
               (str "分割数の 2 乗で収束しない: 比 " (e3 (/ (:rel r8) (:rel r16))) " と "
                    (e3 (/ (:rel r16) (:rel r32)))))
      (not= 15 (get (:angles r16) 6 0))
      (println "PROBE feature-fillet FAIL"
               (str "弧が 90/分割 度の面で構成されていない: " (pr-str (into (sorted-map) (:angles r16)))))
      (chamfer? (:angles r16))
      (println "PROBE feature-fillet FAIL" "45 度の稜がある —— これは面取りであってフィレットではない")
      (or (not= :error (first open-case)) (not (str/includes? (second open-case) "boundary edge")))
      (println "PROBE feature-fillet FAIL"
               "開いた曲面を返す組み合わせ（2 稜 16 分割）を :ok で返している —— 体積が逆方向に間違った「立体」")
      (or (not= :error (first big)) (not (str/includes? (second big) "no closure check would reject")))
      (println "PROBE feature-fillet FAIL" "稜長の半分以上の半径を受理している")
      (or (not= :error (first one-seg)) (not (str/includes? (second one-seg) "is a chamfer")))
      (println "PROBE feature-fillet FAIL" "1 分割（= 面取り）を受理している")
      :else
      (println "PROBE feature-fillet PASS"
               (str "除去量が L·r²(1−π/4) に 2 次収束（相対 " (e3 (:rel r8)) "→" (e3 (:rel r16))
                    "→" (e3 (:rel r32)) "）/ 弧は 90/16 度の面 15 枚・45 度稜なし / "
                    "開いた結果・過大半径・1 分割は拒否"))))
  (catch :default ex (println "PROBE feature-fillet UNMEASURABLE" (.-message ex))))
