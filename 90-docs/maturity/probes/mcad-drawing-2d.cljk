(ns probe-mcad-drawing-2d
  (:require [kami.modeling.drawing :as d] [kami.modeling.document :as doc]
            [clojure.string :as str]))
;; 不変条件: (1) 断面図・詳細図が **別種の view** として作られ、断面はハッチングを持つ
;;          (2) SVG に用紙寸法と実線が出る（幾何が図面になっている）
;;          (3) 寸法がモデルのリビジョンに紐づき、モデルが変われば stale と分かる
;;          (4) 再生成で寸法値が追従する —— **図面が模型に追随する**ことが 2D 図面の要点で、
;;              静止画を出力できることではない
(try
  (let [uid #(doc/stable-uuid "probe-drawing" %)
        node (uid "feature/width")
        doc0 (doc/document (uid "doc") :mm 0.001)
        docv (doc/transact doc0 "did:probe" {:command :feature/add}
                           #(doc/add-node % node {:node/kind :parameter :parameter/value 40} true))
        geom (d/box-view-geometry {:min [0 0 0] :max [40 20 10]} :front)
        front (d/view (uid "view/front") :front geom {:origin [20 20] :scale 2})
        dim (d/dimension (uid "dim/w") (:view/id front) :horizontal [node node]
                         (doc/length 40 :mm 0.01)
                         {:source-path [:document/nodes node :parameter/value]})
        section (d/section-view (uid "view/sec") (:view/id front) [[10 0] [10 8]] geom
                                {:direction :front :origin [80 20] :scale 1})
        sheet (-> (d/sheet (uid "sheet") (:document/revision docv)
                           {:paper :A4 :projection :first-angle :units :mm :title "Probe"})
                  (d/add-view front) (d/add-view section) (d/add-dimension dim))
        svg (d/export-svg sheet)
        changed (doc/transact docv "did:probe" {:command :parameter/set}
                              #(assoc-in % [:document/nodes node :parameter/value] 45))
        status (d/regeneration-status sheet changed)
        regen (d/regenerate sheet changed)
        svg2 (d/export-svg (:regeneration/sheet regen))]
    (cond
      (not= :section (:view/kind section))
      (println "PROBE mcad-drawing-2d FAIL" "断面図が :section にならない")
      (nil? (get-in section [:view/hatch :pattern]))
      (println "PROBE mcad-drawing-2d FAIL" "断面にハッチングが無い")
      (not (and (str/includes? svg "297mm") (str/includes? svg "<line ")))
      (println "PROBE mcad-drawing-2d FAIL" "SVG に A4 の用紙寸法か実線が出ない")
      (not (str/includes? svg "40 mm"))
      (println "PROBE mcad-drawing-2d FAIL" "寸法値が図面に出ない")
      (not (d/current? sheet docv))
      (println "PROBE mcad-drawing-2d FAIL" "作った直後の図面が current? でない")
      (not= :stale (:status status))
      (println "PROBE mcad-drawing-2d FAIL"
               (str "モデルを変えても図面が stale にならない: " (pr-str (:status status))
                    " —— 図面が模型に紐づいていない"))
      (not (str/includes? svg2 "45 mm"))
      (println "PROBE mcad-drawing-2d FAIL" "再生成しても寸法値が追従しない")
      :else (println "PROBE mcad-drawing-2d PASS"
                     (str "断面(ハッチ有)+詳細 / A4 SVG に実線と寸法 / "
                          "モデル変更で stale → 再生成で 40mm→45mm 追従"))))
  (catch :default ex (println "PROBE mcad-drawing-2d UNMEASURABLE" (.-message ex))))
