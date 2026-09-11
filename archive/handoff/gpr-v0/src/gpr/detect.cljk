(ns gpr.detect
  "包絡 B-scan → 反射のピック → 双曲線フィット → 埋設物候補の**特徴量**。

  この namespace は候補を**報告しない**。出すのは測った数値（apex 位置・TWT・
  速度・比誘電率・深度・当てはまりの良さ・使ったピック数・振幅比）までで、
  「報告する / 掘ってよい」の判断は `kotoba/candidate_core.kotoba` にある
  （ADR-2608750000 D4。同じ判断を 2 実装が持つ mirror を作らない）。"
  (:require [gpr.math :as m]
            [gpr.result :as r]
            [gpr.trace :as t]
            [gpr.velocity :as vel]))

(defn picks
  "各トレースから、時間ゲート内で包絡が最大になる点を 1 つ拾う。

  `gate` は `{:from-ns .. :to-ns ..}`。`min-ratio` は「そのトレースの最大包絡が
  B-scan 全体の最大包絡の何倍以上なら拾うか」。拾えなかったトレースは
  **黙って飛ばさず結果から落ちる** ので、後段は必ず `:n` を見ること。"
  [bs gate min-ratio]
  (let [ts (t/traces bs)
        dt (t/dt-ns bs)
        n (t/n-samples bs)
        i0 (max 0 (int (/ (:from-ns gate) dt)))
        i1 (min n (inc (int (/ (:to-ns gate) dt))))
        global (reduce max 0.0 (mapcat #(map m/abs* (:gpr/samples %)) ts))]
    (if (or (zero? global) (>= i0 i1))
      []
      (vec
       (keep (fn [tr]
               (let [seg (subvec (vec (:gpr/samples tr)) i0 i1)
                     amp (reduce max 0.0 (map m/abs* seg))
                     idx (first (keep-indexed (fn [i x] (when (= (m/abs* x) amp) i)) seg))]
                 (when (and idx (>= amp (* min-ratio global)))
                   {:x-m (:gpr/x-m tr)
                    :twt-ns (* (+ i0 idx) dt)
                    :amplitude amp
                    :amplitude-ratio (/ amp global)})))
             ts)))))

(defn fit-at
  "apex 位置 `x0` を固定して双曲線を最小二乗で当てる。

      t^2 = t0^2 + (4/v^2)(x-x0)^2

  を `u = (x-x0)^2` に対する 1 次回帰として解く。傾き a から `v = 2/sqrt(a)`、
  切片 b から `t0 = sqrt(b)`。a か b が非正なら双曲線ではないので err。"
  [ps x0]
  (let [n (count ps)]
    (if (< n 5)
      (r/err :detect/too-few-picks "双曲線を決めるにはピックが足りない" {:n n :minimum 5})
      (let [us (mapv (fn [p] (let [d (- (:x-m p) x0)] (* d d))) ps)
            ys (mapv (fn [p] (let [tt (:twt-ns p)] (* tt tt))) ps)
            ubar (m/mean us) ybar (m/mean ys)
            sxx (reduce + 0.0 (map (fn [u] (let [d (- u ubar)] (* d d))) us))
            sxy (reduce + 0.0 (map (fn [u y] (* (- u ubar) (- y ybar))) us ys))]
        (if (zero? sxx)
          (r/err :detect/degenerate "全ピックが同じ x にある" {:x0 x0})
          (let [a (/ sxy sxx)
                b (- ybar (* a ubar))]
            (if (or (<= a 0.0) (<= b 0.0))
              (r/err :detect/not-a-hyperbola "傾きか切片が非正（下に凸の双曲線でない）"
                     {:slope a :intercept b :x0 x0})
              (let [v (/ 2.0 (m/sqrt a))
                    t0 (m/sqrt b)
                    ss-res (reduce + 0.0 (map (fn [u y] (let [e (- y (+ b (* a u)))] (* e e))) us ys))
                    ss-tot (reduce + 0.0 (map (fn [y] (let [d (- y ybar)] (* d d))) ys))
                    r2 (if (zero? ss-tot) 0.0 (- 1.0 (/ ss-res ss-tot)))]
                (r/ok {:apex-x-m x0
                       :twt-ns t0
                       :velocity-m-per-ns v
                       :r2 r2
                       :n-picks n})))))))))

(defn fit-best
  "apex 位置を候補の中から選んで最良の当てはまりを返す。

  候補はピックのある x とその中点。**速度が物理的にありえない解は捨てる**
  （`gpr.velocity/plausible-velocity?`）—— r2 だけで選ぶと、空気より速い
  「よく当てはまる」解を掴む。"
  [ps]
  (let [xs (mapv :x-m ps)
        mids (mapv (fn [a b] (/ (+ a b) 2.0)) (butlast xs) (rest xs))
        cands (distinct (concat xs mids))
        fits (->> cands
                  (map #(fit-at ps %))
                  (filter r/ok?)
                  (map r/value)
                  (filter #(vel/plausible-velocity? (:velocity-m-per-ns %))))]
    (if (empty? fits)
      (r/err :detect/no-plausible-fit
             "物理的にありえる速度の解が 1 つも無い（ピック数・ゲート・前処理を疑う）"
             {:n-picks (count ps) :n-candidates (count cands)})
      (r/ok (apply max-key :r2 fits)))))

(defn features
  "B-scan（**包絡に変換済み**であること）から埋設物候補 1 件の特徴量を出す。

  v0 は測線上の対象を 1 つと仮定する —— 複数の双曲線が重なる場合の分離は
  未実装で、その場合ここは最も強い 1 本に引っ張られる。**「候補が 1 件」は
  「対象が 1 つ」の証拠ではない。**"
  [bs gate min-ratio]
  (r/bind
   (t/validate bs)
   (fn [_]
     (let [ps (picks bs gate min-ratio)]
       (r/bind
        (fit-best ps)
        (fn [fit]
          (let [v (:velocity-m-per-ns fit)]
            (r/fmap
             (vel/permittivity-from-velocity v)
             (fn [er]
               (assoc fit
                      :permittivity er
                      :depth-m (vel/depth-m (:twt-ns fit) v)
                      :peak-amplitude-ratio (reduce max 0.0 (map :amplitude-ratio ps))
                      :aperture-m (if (seq ps)
                                    (- (apply max (map :x-m ps)) (apply min (map :x-m ps)))
                                    0.0))))))))))) 
