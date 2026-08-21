(ns probe-boolean-solid (:require [brep.feature :as f] [brep.topology :as t]))
;; ⚠ この probe は 2026-08-21 に**強められた**。以前は「三角形数が変わったか」だけを
;; 見ており、それは「何かが起きた」であって「正しいものが起きた」ではなかった。
;; boolean の結果は **閉じたソリッド**でなければならない —— 体積も、STEP 書き出しも、
;; CAM の除去量も、3D 印刷も、閉じていることに依存する。
;;
;; 不変条件: (1) 出発点の押し出しが閉じている（配管のせいではないことの確認）
;;          (2) cut / union の結果も閉じている（境界エッジ 0、Euler 偶数、manifold）
;;
;; ⚠ **軸を「切らない boolean」と「切る boolean」に割らないこと。** 2026-08-21 の
;; 巻き方向修正で、交差しない union・接する union・当たらない difference・
;; 交差の intersection は閉じるようになった。そこだけ別軸にすれば点は増えるが、
;; 利用者が求めるのは「穴を開ける」「重なった体を合わせる」であって、そこが
;; 開いている限りこの能力は届いていない。**点を取りに軸を割らない。**
;; 進捗は FAIL の本文に出す —— 数字ではなく文章として。
(try
  (let [sq (fn [id x0 y0 x1 y1]
             (f/sketch-feature id (f/sketch-plane-xy)
               [(f/sketch-line [x0 y0] [x1 y0]) (f/sketch-line [x1 y0] [x1 y1])
                (f/sketch-line [x1 y1] [x0 y1]) (f/sketch-line [x0 y1] [x0 y0])]))
        base (-> (f/feature-tree) (f/add-feature (sq 1 0 0 10 10))
                 (f/add-feature (f/extrude-feature 2 1 [0 0 1] 4 :new)))
        topo-of (fn [tree] (let [[st m] (f/evaluate-mesh tree)]
                             (when (= :ok st) (t/topology m))))
        b (topo-of base)
        cut (topo-of (-> base (f/add-feature (sq 3 3 3 7 7))
                         (f/add-feature (f/extrude-feature 4 3 [0 0 1] 9 :cut))))
        uni (topo-of (-> (f/feature-tree) (f/add-feature (sq 1 0 0 4 4))
                         (f/add-feature (f/extrude-feature 2 1 [0 0 1] 2 :new))
                         (f/add-feature (sq 3 20 20 24 24))
                         (f/add-feature (f/extrude-feature 4 3 [0 0 1] 2 :add))))
        open (fn [x] (count (t/boundary-edges x)))]
    (cond
      (nil? b) (println "PROBE boolean-solid UNMEASURABLE" "出発点の押し出しが評価できない")
      (not (:manifold? b))
      (println "PROBE boolean-solid UNMEASURABLE"
               "押し出し自体が閉じていない —— boolean 以前の配管の問題")
      (or (nil? cut) (nil? uni))
      (println "PROBE boolean-solid FAIL" "cut / union が評価できない")
      (pos? (open cut))
      (println "PROBE boolean-solid FAIL"
               (str "貫通穴の結果に境界エッジが " (open cut) " 本（Euler "
                    (t/euler-characteristic cut) "）—— 閉じたソリッドではない。"
                    "押し出し単体は閉じているので配管ではなく mesh-boolean 側。"
                    " ただし**切らない** boolean は 2026-08-21 の巻き方向修正で閉じた"
                    "（交差しない union の境界エッジ " (open uni) " 本）"))
      (pos? (open uni))
      (println "PROBE boolean-solid FAIL"
               (str "**交差していない**箱 2 つの union に境界エッジが " (open uni)
                    " 本 —— 交わるものが無いのに漏れている"))
      :else (println "PROBE boolean-solid PASS"
                     (str "cut Euler=" (t/euler-characteristic cut)
                          " union Euler=" (t/euler-characteristic uni) " どちらも閉"))))
  (catch :default ex (println "PROBE boolean-solid UNMEASURABLE" (.-message ex))))
