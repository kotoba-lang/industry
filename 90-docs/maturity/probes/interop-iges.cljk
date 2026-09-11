(ns probe-interop-iges (:require [iges.core :as i] [clojure.string :as str]))
;; IGES は **80 桁のカード形式**で、73 桁目が section、74-80 桁が連番 —— どちらも
;; 位置で読む。だから 1 文字短いカードは「少し違う行」ではなく、section が数字で
;; 連番がゴミの行になる。**自分の reader はそれでも読めてしまう**ので、往復では
;; なく桁幅そのものを見る。
;;
;; もう 1 つは区切り子。IGES は **区切り子をファイルの中で宣言する**（Global の
;; 最初の 2 フィールドが Hollerith）。`,` `;` と決め打ちした reader は、別の
;; 区切り子を選んだ系のファイルを**エラーを出さずに**間違った位置で分割する。
;; だから自分が一度も出さない `|` `#` のファイルを読ませる。
(try
  (let [ents [{:iges/type 110 :line/start [0.0 0.0 0.0] :line/end [10.0 0.0 0.0]}
              {:iges/type 116 :point/at [1.0 2.0 3.0]}
              {:iges/type 100 :arc/plane-z 0.0 :arc/center [0.0 0.0] :arc/start [5.0 0.0] :arc/end [0.0 5.0]}
              {:iges/type 106 :copious/interpretation 2
               :copious/points [[0.0 0.0 0.0] [1.0 1.0 0.0] [2.0 0.0 1.0]]}
              {:iges/type 124 :transform/rows [[1.0 0.0 0.0 5.0] [0.0 1.0 0.0 0.0] [0.0 0.0 1.0 0.0]]}]
        out (i/write ents {:units "MM"})
        ls (str/split-lines out)
        widths (sort (distinct (map count ls)))
        back (i/parse out)
        card (fn [body sec n]
               (str (apply str body (repeat (max 0 (- 72 (count body))) " ")) sec
                    (apply str (repeat (- 7 (count (str n))) " ")) n))
        foreign (str/join "\n"
                  [(card "From a system that chose its own delimiters" "S" 1)
                   (card "1H||1H#|6HFOREIGN|8Hpart.igs|5HTHEIRS|3H1.0|32|38|6|308|15|0H|1.0|2|" "G" 1)
                   (card "2HMM|1|0.0|15H20260101.000000|1.0E-7|0.0|0H|0H|11|0|15H20260101.000000#" "G" 2)
                   (card "     110       1       0       0       0       0       0       000000000" "D" 1)
                   (card "     110       0       0       1       0                               0" "D" 2)
                   (card "     128       2       0       0       0       0       0       000000000" "D" 3)
                   (card "     128       0       0       1       0                               0" "D" 4)
                   (card "     314       3       0       0       0       0       0       000000000" "D" 5)
                   (card "     314       0       0       1       0                               0" "D" 6)
                   (str (apply str "110|1.5|2.5|0.|4.5|2.5|0.#" (repeat (- 64 26) " ")) "       1P      1")
                   (str (apply str "128|1|1|1|1|0|0|0|0|0#" (repeat (- 64 22) " ")) "       3P      2")
                   (str (apply str "314|50.|50.|50.|4Hgrey#" (repeat (- 64 23) " ")) "       5P      3")
                   (card "S      1G      2D      6P      3" "T" 1)])
        r (i/parse foreign)
        line (first (:iges/entities r))
        refuses? (try (i/write [{:iges/type 128}]) false (catch :default _ true))]
    (cond
      (not= [80] widths)
      (println "PROBE interop-iges FAIL"
               (str "カード幅が 80 桁でない: " (pr-str widths)
                    " —— section も連番も位置で読まれるので、他系は開けない"))
      (not= #{"S" "G" "D" "P" "T"} (set (map #(subs % 72 73) ls)))
      (println "PROBE interop-iges FAIL"
               (str "section 文字が揃わない: " (pr-str (sort (distinct (map #(subs % 72 73) ls)))))) 
      (not= {110 1 116 1 100 1 106 1 124 1} (:iges/counts back))
      (println "PROBE interop-iges FAIL" (str "自分の出力を読み戻せない: " (pr-str (:iges/counts back))))
      (not= [0.0 0.0 0.0] (mapv double (get-in (vec (:iges/entities back)) [0 :line/start])))
      (println "PROBE interop-iges FAIL" "端点が往復しない")
      (not= ["|" "#"] (:iges/delimiters r))
      (println "PROBE interop-iges FAIL"
               (str "区切り子をファイルから読んでいない: " (pr-str (:iges/delimiters r))
                    " —— 別の区切り子の系のファイルを、エラー無しで誤って分割する"))
      (not= {110 1} (:iges/counts r))
      (println "PROBE interop-iges FAIL" (str "他系のファイルの entity が読めない: " (pr-str (:iges/counts r))))
      (not= [1.5 2.5 0.0] (mapv double (:line/start line)))
      (println "PROBE interop-iges FAIL" (str "他系の座標が誤って分割されている: " (pr-str line)))
      (not= {128 1 314 1} (:iges/unsupported r))
      (println "PROBE interop-iges FAIL"
               (str "未対応 entity が名前付きで報告されない: " (pr-str (:iges/unsupported r))))
      (not refuses?)
      (println "PROBE interop-iges FAIL" "書けない entity 型を黙って出力する")
      :else
      (println "PROBE interop-iges PASS"
               (str "全 " (count ls) " カードが 80 桁 / 5 型が往復 / 自作でない `|` `#` の"
                    "ファイルを解釈し未対応 128・314 を名指し / 書けない型は拒否"))))
  (catch :default ex (println "PROBE interop-iges UNMEASURABLE" (.-message ex))))
