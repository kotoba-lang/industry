(ns probe-dcc-subdivision (:require [kami.modeling :as m]))
;; **数では区別できない**のがこの軸の要点。`subdivide-mesh`（線形）と
;; `catmull-clark` は頂点数も面数も同じ 26/24 を返す。違うのは位置だけ:
;;   線形         立方体の角は (1,1,1) のまま
;;   Catmull-Clark 角は (5/9,5/9,5/9) へ動く —— 価数 3 の閉じた形
;; さらにクリースは「同じ呼び出しに違う答えを出す」ためにある: 全稜をクリースすると
;; 角は corner 規則で (1,1,1) に戻る。分数シャープネスは別の規則系なので拒否。
(try
  (let [c (m/cube 2)
        lin (m/subdivide-mesh c)
        cc (m/catmull-clark c)
        all (set (for [f (:mesh/faces c) [a b] (map vector f (concat (rest f) [(first f)]))]
                   (m/crease a b)))
        creased (m/catmull-clark c all)
        corner (fn [mm] (first (filter (fn [p] (every? #(> % 0.4) p)) (:mesh/vertices mm))))
        near (fn [p v] (every? #(< (Math/abs (- % v)) 1e-12) p))
        refused (fn [f] (try (do (f) :accepted) (catch :default _ :refused)))]
    (cond
      (not= [(count (:mesh/vertices lin)) (count (:mesh/faces lin))]
            [(count (:mesh/vertices cc)) (count (:mesh/faces cc))])
      (println "PROBE dcc-subdivision FAIL" "線形と CC で数が違う（この軸の前提が崩れている）")
      (not (near (corner lin) 1.0))
      (println "PROBE dcc-subdivision FAIL" (str "線形細分で角が動いた: " (pr-str (corner lin))))
      (not (near (corner cc) (/ 5.0 9.0)))
      (println "PROBE dcc-subdivision FAIL"
               (str "CC の角が " (pr-str (corner cc)) "（価数 3 の閉じた形 5/9 = 0.5556 を期待）"
                    (when (near (corner cc) 1.0) " —— 頂点規則が恒等になっていないか")))
      (not (near (corner creased) 1.0))
      (println "PROBE dcc-subdivision FAIL"
               (str "全稜クリースでも角が動く: " (pr-str (corner creased)) "（corner 規則が効いていない）"))
      (not (and (m/valid-mesh? cc) (m/valid-mesh? creased)))
      (println "PROBE dcc-subdivision FAIL" "細分結果が valid-mesh? でない")
      (not= :refused (refused #(m/catmull-clark c #{[0 1 3.5]})))
      (println "PROBE dcc-subdivision FAIL" "分数シャープネスを受理する（別の規則系なのに）")
      :else (println "PROBE dcc-subdivision PASS"
                     (str "線形と CC は同数 " [(count (:mesh/vertices cc)) (count (:mesh/faces cc))]
                          " で位置だけ違う / 角 1.0 → 5/9 / 全稜クリースで 1.0 へ戻る / "
                          "分数シャープネスは拒否"))))
  (catch :default ex (println "PROBE dcc-subdivision UNMEASURABLE" (.-message ex))))
