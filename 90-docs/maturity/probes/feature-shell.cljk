(ns probe-feature-shell (:require [brep.feature :as f] [brep.topology :as t] [clojure.string :as str]))
;; 薄肉化は「内側オフセット + 差分」なので、残る体積は閉じた形で書ける。
;; 10x10x6 を t=1 で中空にすると 600 - 8*8*4 = 344、開口を +Z に付けると
;; 600 - 8*8*5 = 280。位相も別れる（閉なら空洞が 2 枚目のシェルで Euler 4、
;; 開口ありなら Euler 2）。
;;
;; ここで見るべきは **失敗が「閉じた・もっともらしい大きさのソリッド」として
;; 返る**こと。厚すぎる t ではオフセット面が交差し、intersection は空ではなく
;; 巨大な薄片を返すので、差分しても 1000 中 990 が残り、閉じていて Euler 2 の
;; 「shell」ができる —— 元のブロックと見分けが付かない。だから拒否も測る。
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
        run (fn [feat] (f/evaluate-mesh (f/add-feature base feat)))
        [st-c m-c] (run (f/shell-feature 3 nil 1.0))
        [st-o m-o] (run (f/shell-feature 3 [[0 0 1]] 1.0))
        [st-t msg-t] (run (f/shell-feature 3 nil 9.0))
        ;; 凹の body（角を切り欠いたブロック）—— 平面交差のオフセットは
        ;; reflex 稜で材料の外に出るので、拒否が正解
        notched (-> base
                    (f/add-feature (f/sketch-feature 3 (f/sketch-plane-xy)
                      [(f/sketch-line [6 6] [12 6]) (f/sketch-line [12 6] [12 12])
                       (f/sketch-line [12 12] [6 12]) (f/sketch-line [6 12] [6 6])]))
                    (f/add-feature (f/extrude-feature 4 3 [0 0 1] 6 :cut)))
        [st-n msg-n] (f/evaluate-mesh (f/add-feature notched (f/shell-feature 5 nil 1.0)))
        tp (fn [m] (t/topology (t/welded-oriented m)))]
    (cond
      (not (contains? (f/supported-feature-kinds) :shell))
      (println "PROBE feature-shell FAIL" "registry に :shell が無い —— feature tree は評価できない")
      (not= :ok st-c)
      (println "PROBE feature-shell FAIL" (str "閉じた薄肉化が評価できない: " (pr-str m-c)))
      (> (Math/abs (- (vol m-c) 344.0)) 1.0e-6)
      (println "PROBE feature-shell FAIL"
               (str "閉じた薄肉化の体積 " (vol m-c) " ≠ 解析解 344（600 - 8*8*4）"))
      (or (seq (t/boundary-edges (tp m-c))) (not= 4 (t/euler-characteristic (tp m-c))))
      (println "PROBE feature-shell FAIL"
               (str "空洞が 2 枚目のシェルになっていない: 境界 "
                    (count (t/boundary-edges (tp m-c))) " / Euler "
                    (t/euler-characteristic (tp m-c)) "（期待 0 / 4）"))
      (not= :ok st-o)
      (println "PROBE feature-shell FAIL" (str "開口付きが評価できない: " (pr-str m-o)))
      (> (Math/abs (- (vol m-o) 280.0)) 1.0e-6)
      (println "PROBE feature-shell FAIL"
               (str ":removed-faces [[0 0 1]] の体積 " (vol m-o) " ≠ 解析解 280 —— 開口が効いていない"))
      (not= 2 (t/euler-characteristic (tp m-o)))
      (println "PROBE feature-shell FAIL"
               (str "開口があるのに Euler " (t/euler-characteristic (tp m-o)) "（期待 2）"))
      (not= :error st-t)
      (println "PROBE feature-shell FAIL"
               (str "厚すぎる t=9 が成功として返る（体積 " (vol msg-t)
                    "）—— 閉じたもっともらしいソリッドで返る失敗を拒否できていない"))
      (not (str/includes? msg-t "offset walls cross"))
      (println "PROBE feature-shell FAIL" (str "t=9 の拒否理由が交差を名指ししない: " msg-t))
      (not= :error st-n)
      (println "PROBE feature-shell FAIL"
               "凹の body に答えを返している —— 平面交差のオフセットは reflex 稜で壁を食う")
      (not (str/includes? msg-n "offset-surface"))
      (println "PROBE feature-shell FAIL" (str "凹の拒否が欠けている表現を名指ししない: " msg-n))
      :else
      (println "PROBE feature-shell PASS"
               (str "閉 344 / 開口 280（解析解一致）/ Euler 4 と 2 / "
                    "厚すぎ t=9 と凹 body は理由付きで拒否"))))
  (catch :default ex (println "PROBE feature-shell UNMEASURABLE" (.-message ex))))
