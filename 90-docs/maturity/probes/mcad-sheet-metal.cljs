(ns probe-mcad-sheet-metal
  (:require [kami.modeling.sheet-metal :as sm] [clojure.string :as str]))
;; 板金は **教科書の閉じた式**があるので、そこに当てる。
;;   BA = θ(R + K·T) / OSSB = (R+T)tan(θ/2) / BD = 2·OSSB − BA / flat = ΣOML − ΣBD
;;
;; ただし「unfold と fold が互いに逆」は証拠にならない —— **同じ誤った式を共有する
;; 2 つの関数は完璧に逆になる**。だから見るのは 2 つの**別の導出**の一致:
;; unfold は控除式で長さを出し、fold は中立線を歩いて直線 + r·θ を足す。
;;
;; もう 1 つは拒否。フランジが自分の setback より短ければ曲げが重なるが、
;; そのとき返る「展開長」はただの数で、シャーは何も知らずに切る。
(try
  (let [close? (fn [a b tol] (< (Math/abs (- (double a) (double b))) tol))
        b {:angle 90 :radius 1.0 :thickness 1.0 :k-factor 0.44}
        L {:thickness 1.0 :k-factor 0.44 :flanges [10.0 10.0] :bends [{:angle 90 :radius 1.0}]}
        parts [L
               {:thickness 2.0 :k-factor 0.42 :flanges [40.0 25.0 40.0]
                :bends [{:angle 90 :radius 3.0} {:angle 90 :radius 3.0}]}
               {:thickness 1.5 :flanges [30.0 20.0 30.0 20.0]
                :bends [{:angle 60 :radius 2.0}
                        {:angle 120 :radius 2.0 :direction :down}
                        {:angle 45 :radius 1.0}]}]
        disagree (for [p parts
                       :let [[_ u] (sm/unfold p) [_ f] (sm/fold p 4096)]
                       :when (not (close? (:flat/length u) (:fold/length f) 1.0e-9))]
                   [(:flat/length u) (:fold/length f)])
        [_ u] (sm/unfold L)
        segs (:flat/segments u)
        tiles? (every? true? (map (fn [a c] (close? (:segment/to a) (:segment/from c) 1.0e-12))
                                  segs (rest segs)))
        [k-st k-back] (sm/k-factor-from-flat L (second (sm/flat-length L)))
        overlap (sm/unfold (assoc L :flanges [1.0 10.0]))
        branch (sm/unfold (assoc L :flanges [10.0 10.0 10.0]))]
    (cond
      (not (close? (sm/bend-allowance b) (* (/ js/Math.PI 2.0) 1.44) 1.0e-12))
      (println "PROBE mcad-sheet-metal FAIL"
               (str "BA が θ(R+K·T) でない: " (sm/bend-allowance b)))
      (not (close? (sm/outside-setback b) 2.0 1.0e-12))
      (println "PROBE mcad-sheet-metal FAIL" (str "OSSB が (R+T)tan(θ/2) でない: " (sm/outside-setback b)))
      (not (close? (sm/bend-deduction b) 1.7380532894 1.0e-9))
      (println "PROBE mcad-sheet-metal FAIL" (str "BD が 2·OSSB−BA でない: " (sm/bend-deduction b)))
      (not (close? (second (sm/flat-length L)) 18.2619467106 1.0e-9))
      (println "PROBE mcad-sheet-metal FAIL"
               (str "展開長が 20−BD にならない: " (second (sm/flat-length L))))
      (seq disagree)
      (println "PROBE mcad-sheet-metal FAIL"
               (str "控除式と中立線走査が一致しない（" (count disagree) " 件、例 "
                    (pr-str (first disagree)) "）—— どちらかの導出が誤っている"))
      (not tiles?)
      (println "PROBE mcad-sheet-metal FAIL" "展開図のセグメントに隙間か重なりがある")
      (or (not= :ok k-st) (not (close? k-back 0.44 1.0e-12)))
      (println "PROBE mcad-sheet-metal FAIL"
               (str "実測ブランクから K を逆算できない: " (pr-str [k-st k-back])))
      (or (not= :error (first overlap)) (not (str/includes? (second overlap) "overlap")))
      (println "PROBE mcad-sheet-metal FAIL"
               "曲げが重なるフランジに展開長を返している —— シャーはそれを切る")
      (not= :error (first branch))
      (println "PROBE mcad-sheet-metal FAIL" "フランジ数と曲げ数が合わない部品を受理している")
      (not= :error (first (sm/unfold (assoc L :k-factor 0.7))))
      (println "PROBE mcad-sheet-metal FAIL" "K が [0,0.5] の外でも受理している（中立線が材料の外）")
      :else
      (println "PROBE mcad-sheet-metal PASS"
               (str "BA/OSSB/BD が教科書式と 1e-9 一致 / L 展開 18.26195 / "
                    (count parts) " 部品で控除式と中立線走査が一致 / K 逆算 0.44 / "
                    "重なり・分岐・K 範囲外は拒否"))))
  (catch :default ex (println "PROBE mcad-sheet-metal UNMEASURABLE" (.-message ex))))
