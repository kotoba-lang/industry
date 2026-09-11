(ns probe-dcc-uv-unwrap (:require [kami.modeling :as m]))
;; 歪みは **UV レイアウトを見ても分からない**。軸投影は平行でない面を全部潰すが、
;; UV の絵はきれいに並ぶ。角度を曲面へ戻して比べて初めて出る:
;;   折れた帯で planar-unwrap は 0.49 rad（28 度）ずれ、LSCM は 2e-15。
;; さらに: (a) 平面パッチは自分自身に展開される (b) 2 島は **拒否**される
;;   （どちらをどこに置くかは展開ではなくレイアウトの決定）
(try
  (let [ang (fn [p q r] (let [u (mapv - q p) v (mapv - r p)
                              du (Math/sqrt (reduce + (map * u u)))
                              dv (Math/sqrt (reduce + (map * v v)))]
                          (Math/acos (max -1.0 (min 1.0 (/ (reduce + (map * u v)) (* du dv)))))))
        worst (fn [mesh uvs tris]
                (apply max (mapcat (fn [[a b c]]
                                     (let [V (:mesh/vertices mesh) uv #(conj (nth uvs %) 0.0)]
                                       [(Math/abs (- (ang (nth V a) (nth V b) (nth V c))
                                                     (ang (uv a) (uv b) (uv c))))
                                        (Math/abs (- (ang (nth V b) (nth V c) (nth V a))
                                                     (ang (uv b) (uv c) (uv a))))]))
                                   tris)))
        grid (m/mesh [[0 0 0] [1 0 0] [2 0 0] [0 1 0] [1 1 0] [2 1 0]] [[0 1 4 3] [1 2 5 4]])
        gtris [[0 1 4] [0 4 3] [1 2 5] [1 5 4]]
        fold (m/mesh [[0 0 0] [1 0 0] [0 1 0] [1 1 0] [0 2 1] [1 2 1]] [[0 1 3 2] [2 3 5 4]])
        ftris [[0 1 3] [0 3 2] [2 3 5] [2 5 4]]
        two (m/mesh [[0 0 0] [1 0 0] [0 1 0] [5 0 0] [6 0 0] [5 1 0]] [[0 1 2] [3 4 5]])
        flat-err (worst grid (:mesh/uvs (m/lscm-unwrap grid)) gtris)
        planar-err (worst fold (:mesh/uvs (m/planar-unwrap fold :z)) ftris)
        lscm-err (worst fold (:mesh/uvs (m/lscm-unwrap fold)) ftris)
        refused (try (do (m/lscm-unwrap two) :accepted) (catch :default _ :refused))]
    (cond
      (> flat-err 1e-12)
      (println "PROBE dcc-uv-unwrap FAIL" (str "平面パッチが自分自身に展開されない（角度差 " flat-err "）"))
      (not (> planar-err 0.4))
      (println "PROBE dcc-uv-unwrap FAIL"
               (str "軸投影が折れた帯を歪ませない（" planar-err "）—— 比較の前提が崩れている"))
      (> lscm-err 1e-12)
      (println "PROBE dcc-uv-unwrap FAIL"
               (str "LSCM が角度を保たない（" lscm-err "）—— 共形の重みが効いていないか"))
      (not= [[0] [1]] (m/uv-islands two))
      (println "PROBE dcc-uv-unwrap FAIL" (str "2 島を見つけない: " (pr-str (m/uv-islands two))))
      (not= 1 (count (m/uv-islands fold)))
      (println "PROBE dcc-uv-unwrap FAIL" "繋がったパッチを 1 島と数えない")
      (not= :refused refused)
      (println "PROBE dcc-uv-unwrap FAIL" "2 島の展開を受理する（レイアウトを勝手に決めている）")
      (not= (count (:mesh/vertices fold)) (count (:mesh/uvs (m/lscm-unwrap fold))))
      (println "PROBE dcc-uv-unwrap FAIL" "UV が全頂点に付かない")
      :else (println "PROBE dcc-uv-unwrap PASS"
                     (str "平面は自身へ / 折れた帯で planar " (.toFixed planar-err 3)
                          " rad vs LSCM " (.toExponential lscm-err 1)
                          " / 島 2 を検出し展開は拒否"
                          "（⚠ ABF は未実装・LSCM は密な正規方程式）"))))
  (catch :default ex (println "PROBE dcc-uv-unwrap UNMEASURABLE" (.-message ex))))
