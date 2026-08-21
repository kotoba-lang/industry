(ns probe-cam-collision-check
  (:require [kotoba.cam.toolpath :as tp] [kotoba.cam.stock :as stock] [kotoba.cam.tool :as tool]
            [kotoba.cam.vec3 :as v3]))
;; 工具が工作物に届く経路は 2 つある: **低いから届く**（先端の食い込み）と、
;; **太いから届く**（シャンク・ホルダ）。前者だけを見る検査は、正しい形を切りながら
;; 治具を壊すプログラムを通す。ここで測るのは両方が独立に効くこと:
;;   (1) 生成パスは弦公差内で自分の食い込み検査を通る
;;   (2) 床の脇に高さ 25 の壁があると、**先端は公差内のままホルダだけが**衝突し、
;;       違反にセクション名（:holder であって :shank ではない）が付く
;;   (3) 6mm 工具を 5mm 溝に向けるとシャンクで捕まる
;;   (4) 細分を切ると 0.4mm 以上食い込む（適応細分が効いている証拠）
;;   (5) 工具を省くと拒否（先端より上の包絡が検査の主題そのもので推測できない）
;;   (6) **まだモデル化していないもの**（治具・クランプ・機械包絡・未除去ストック）が
;;       出力のフィールドに名指しで出る
(try
  (let [tl {:id :bn6 :name "6mm" :tool-type :ball-nose :diameter 6.0 :flute-length 8.0
            :overall-length 40.0 :holder-diameter 12.0 :flute-count 2 :corner-radius 3.0
            :material :carbide}
        [lib _] (tool/add (tool/empty-library) tl)
        run (fn [t opts] (tp/generate-toolpath
                          (-> (tp/new-job (stock/stock (stock/block 60 60 30) (stock/aluminum-6061)) lib)
                              (tp/add-operation (merge {:op :surface-3d :tool-id :bn6 :stepover 4.0
                                                        :strategy :raster :feed-rate 1200.0
                                                        :target t} opts)))))
        flat {:positions [[-20 -20 0] [20 -20 0] [20 20 0] [-20 20 0]] :indices [0 1 2 0 2 3]}
        ;; 適応細分の証拠には **曲率のある** 形が要る。平板は線形移動でぴたり追従する
        ;; ので、細分を切っても食い込まない —— 検査の前提が成り立たない。
        plateau {:positions [[-20 -20 0] [20 -20 0] [20 20 0] [-20 20 0]
                             [-3 -3 4] [3 -3 4] [3 3 4] [-3 3 4]]
                 :indices [0 1 2 0 2 3 4 5 6 4 6 7]}
        wall {:positions [[-20 -20 0] [6 -20 0] [6 20 0] [-20 20 0]
                          [6 -20 25] [8 -20 25] [8 20 25] [6 20 25]]
              :indices [0 1 2 0 2 3 4 5 6 4 6 7]}
        slot {:positions [[-20 -20 10] [-2.5 -20 10] [-2.5 -20 0] [2.5 -20 0] [2.5 -20 10] [20 -20 10]
                          [-20 20 10] [-2.5 20 10] [-2.5 20 0] [2.5 20 0] [2.5 20 10] [20 20 10]]
              :indices [0 1 7 0 7 6  1 2 8 1 8 7  2 3 9 2 9 8  3 4 10 3 10 9  4 5 11 4 11 10]}
        ok (tp/collision-check (run flat {}) flat tl {:tolerance 0.02})
        raw (tp/gouge-check (run plateau {:max-bisections 0}) plateau {:tool-radius 3.0})
        w (tp/collision-check (run wall {}) wall tl {:tolerance 0.02})
        s (tp/collision-check (run slot {}) slot tl)
        no-tool (try (do (tp/collision-check (run flat {}) flat {}) :accepted)
                     (catch :default _ :refused))
        secs (set (map :section (get-in w [:above-tip :violations])))]
    (cond
      (zero? (get-in ok [:gouge :checked]))
      (println "PROBE cam-collision-check FAIL" "検査点 0 —— 何も見ていない")
      (not (:passed? ok))
      (println "PROBE cam-collision-check FAIL"
               (str "平板の仕上げが通らない: gouge " (get-in ok [:gouge :worst-depth])
                    " / 先端より上 " (count (get-in ok [:above-tip :violations]))))
      (< (:worst-depth raw) 0.4)
      (println "PROBE cam-collision-check FAIL"
               (str "細分を切っても食い込まない（" (:worst-depth raw) "）"))
      (not (get-in w [:gouge :passed?]))
      (println "PROBE cam-collision-check FAIL"
               (str "壁のある形で先端が公差を外れる（" (get-in w [:gouge :worst-depth])
                    "）—— ホルダ検査を切り分けられない"))
      (not= #{:holder} secs)
      (println "PROBE cam-collision-check FAIL"
               (str "高さ 25 の壁でホルダ衝突を切り分けられない: " (pr-str secs)))
      (not (contains? (set (map :section (get-in s [:above-tip :violations]))) :shank))
      (println "PROBE cam-collision-check FAIL" "6mm 工具の 5mm 溝をシャンクで捕まえない")
      (not= :refused no-tool)
      (println "PROBE cam-collision-check FAIL" "工具なしで検査を受理する")
      (not (and (= #{:gouge-into-target :shank-into-target :holder-into-target} (:checked-for ok))
                (= #{:fixture-collision :clamp-collision :machine-envelope :uncut-stock}
                   (:not-checked-for ok))))
      (println "PROBE cam-collision-check FAIL"
               (str "検査した範囲と未モデル化の範囲が出力で区別されない: "
                    (pr-str [(:checked-for ok) (:not-checked-for ok)])))
      :else (println "PROBE cam-collision-check PASS"
                     (str "平板は合格（食い込み " (.toFixed (get-in ok [:gouge :worst-depth]) 5)
                          "、細分なしなら " (.toFixed (:worst-depth raw) 3) "）/ 壁では先端公差内のまま "
                          (count (get-in w [:above-tip :violations])) " 点でホルダのみ衝突 / "
                          "5mm 溝はシャンクで検出 / ⚠ 治具・クランプ・機械包絡・未除去ストックは未モデル化"))))
  (catch :default ex (println "PROBE cam-collision-check UNMEASURABLE" (.-message ex))))
