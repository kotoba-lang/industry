(ns probe-interop-dxf-read
  (:require [kami.modeling.drawing :as d] [kami.modeling.document :as doc]))
;; 不変条件: 読み手は「自分の書き手のこだま」であってはならない。
;;   (1) 自分が書いた DXF を読み戻せる（LINE 本数・端点・単位）
;;   (2) **自分が一度も出さない entity**（CIRCLE / ARC / LWPOLYLINE）を読める
;;       —— ここを外すと、round-trip は緑のまま他社の図面を 1 枚も開けない
;;   (3) 知らない entity を **名前付きで報告する**（黙って捨てない）
(try
  (let [uid #(doc/stable-uuid "probe-dxf-read" %)
        geom (d/box-view-geometry {:min [0 0 0] :max [20 10 8]} :front)
        view (d/view (uid "v") :front geom {:origin [10 10] :scale 1})
        sheet (-> (d/sheet (uid "s") "r1" {:paper :A3 :units :mm}) (d/add-view view))
        out (d/export-dxf sheet)
        back (d/read-dxf out)
        foreign (str "0\nSECTION\n2\nHEADER\n9\n$INSUNITS\n70\n1\n0\nENDSEC\n"
                     "0\nSECTION\n2\nENTITIES\n"
                     "0\nCIRCLE\n8\nHOLES\n10\n5.0\n20\n7.5\n40\n2.5\n"
                     "0\nARC\n8\nHOLES\n10\n0\n20\n0\n40\n10\n50\n0\n51\n90\n"
                     "0\nLWPOLYLINE\n8\nOUT\n90\n3\n70\n1\n10\n0\n20\n0\n10\n5\n20\n0\n10\n5\n20\n5\n"
                     "0\nSPLINE\n8\nX\n"
                     "0\nENDSEC\n0\nEOF\n")
        r (d/read-dxf foreign)
        n-written (count (re-seq #"(?m)^LINE$" out))
        n-read (get-in back [:dxf/counts :line] 0)
        circle (first (filter #(= :circle (:entity/kind %)) (:dxf/entities r)))]
    (cond
      (not= :mm (:dxf/units back))
      (println "PROBE interop-dxf-read FAIL"
               (str "$INSUNITS を読み戻せない（" (pr-str (:dxf/units back)) "）—— 縮尺の無い座標が返る"))
      (or (zero? n-read) (not= n-read n-written))
      (println "PROBE interop-dxf-read FAIL"
               (str "書いた LINE " n-written " 本に対し読めたのは " n-read " 本"))
      (not= [10.0 10.0] (mapv double (:entity/start (first (:dxf/entities back)))))
      (println "PROBE interop-dxf-read FAIL"
               (str "端点が往復しない: " (pr-str (:entity/start (first (:dxf/entities back))))))
      (not= {:circle 1 :arc 1 :lwpolyline 1} (:dxf/counts r))
      (println "PROBE interop-dxf-read FAIL"
               (str "自分が書かない entity を読めない: " (pr-str (:dxf/counts r))
                    " —— 書き手のこだまであって読み手ではない"))
      (not= [[5.0 7.5] 2.5] [(mapv double (:entity/center circle)) (double (:entity/radius circle))])
      (println "PROBE interop-dxf-read FAIL"
               (str "CIRCLE の中心/半径が復元されない: " (pr-str circle)))
      (not= {"SPLINE" 1} (:dxf/unsupported r))
      (println "PROBE interop-dxf-read FAIL"
               (str "知らない entity が報告されない: " (pr-str (:dxf/unsupported r))
                    " —— 黙って捨てると受け手は欠落に気付けない"))
      :else
      (println "PROBE interop-dxf-read PASS"
               (str "往復 " n-read " LINE + $INSUNITS=:mm / 自作でない CIRCLE・ARC・LWPOLYLINE を解釈 / "
                    "未知の SPLINE を名前付きで報告"))))
  (catch :default ex (println "PROBE interop-dxf-read UNMEASURABLE" (.-message ex))))
