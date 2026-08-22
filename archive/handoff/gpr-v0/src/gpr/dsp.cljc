(ns gpr.dsp
  "A-scan / B-scan の前処理。すべて純関数で、入力を破壊しない。

  ここに置くのは**判断を含まない機構**だけ —— 「この反射は埋設物候補か」は
  `kotoba/candidate_core.kotoba` の decision core が答える（ADR-2608750000 D4）。
  閾値を取る関数は閾値を引数で受け、既定値をここに焼かない。"
  (:require [gpr.fft :as fft]
            [gpr.math :as m]
            [gpr.result :as r]
            [gpr.trace :as t]))

(defn dewow
  "低周波のうねり（wow）を移動平均の差し引きで抜く。`window` はサンプル数。

  window は「主周波数の 1 周期ぶん」を目安に呼び出し側が決める —— 支配周波数を
  この関数が推定して既定値にすると、推定が外れたときに黙って信号を削る。"
  [samples window]
  (let [v (vec samples)
        n (count v)
        half (quot window 2)]
    (mapv (fn [i]
            (let [lo (max 0 (- i half))
                  hi (min n (+ i half 1))]
              (- (nth v i) (m/mean (subvec v lo hi)))))
          (range n))))

(defn remove-background
  "全トレープの平均トレースを引いて、水平に伸びる成分（直達波・リンギング）を落とす。

  **点状散乱体の双曲線は水平でないので残る**が、測線が短く対象が 1 つだと
  その対象自身が平均に効いて振幅が削れる。トレース数が少ないときは使わない。"
  [bs]
  (let [ts (t/traces bs)
        n (count ts)
        ns* (count (:gpr/samples (first ts)))
        avg (mapv (fn [i] (m/mean (map #(nth (:gpr/samples %) i) ts))) (range ns*))]
    (update bs :gpr/traces
            (fn [ts] (mapv (fn [tr]
                             (update tr :gpr/samples
                                     (fn [s] (mapv - s avg))))
                           ts)))))

(defn first-break-index
  "|振幅| が最大値の `ratio` 倍を最初に超えるサンプル番号。無ければ nil。

  time-zero（送信の瞬間）合わせに使う。`ratio` を渡さない既定値は置かない。"
  [samples ratio]
  (let [v (vec samples)
        peak (reduce max 0.0 (map m/abs* v))]
    (when (pos? peak)
      (first (keep-indexed (fn [i x] (when (>= (m/abs* x) (* ratio peak)) i)) v)))))

(defn shift
  "サンプル列を `k` だけ前へ詰める（time-zero 補正）。末尾は 0 で埋める。"
  [samples k]
  (let [v (vec samples) n (count v)]
    (if (<= k 0)
      v
      (into (subvec v (min k n)) (repeat (min k n) 0.0)))))

(defn sec-gain
  "球面拡散 + 減衰の補正 g(t) = t^p · exp(a·t)。`dt-ns` はサンプル間隔。

  p と a は媒質と機材で変わるので引数。**利得は振幅の絶対値の意味を壊す**ので、
  この後に振幅そのものを閾値にしないこと（比で見る）。"
  [samples dt-ns p a]
  (let [v (vec samples)]
    (mapv (fn [i x]
            (let [tt (* i dt-ns)]
              (* x (m/pow tt p) (m/exp (* a tt)))))
          (range (count v)) v)))

(defn agc
  "窓内の RMS で正規化する自動利得。`window` はサンプル数。

  可視化には効くが、**振幅の相対関係を壊すので検出の入力にはしない**。"
  [samples window]
  (let [v (vec samples)
        n (count v)
        half (quot window 2)]
    (mapv (fn [i]
            (let [lo (max 0 (- i half))
                  hi (min n (+ i half 1))
                  seg (subvec v lo hi)
                  rms (m/sqrt (m/mean (map #(* % %) seg)))]
              (if (pos? rms) (/ (nth v i) rms) 0.0)))
          (range n))))

(defn envelope-trace
  "1 トレースの包絡。長さが 2 の冪でなければ err（黙ってゼロ埋めしない）。"
  [samples] (fft/envelope (vec samples)))

(defn envelope-b-scan
  "B-scan の全トレースを包絡に置き換える。1 本でも失敗したら全体を err にする
  —— 一部だけ変換された B-scan は、変換済みと未変換が同じ形で混ざるので危険。"
  [bs]
  (let [results (mapv #(envelope-trace (:gpr/samples %)) (t/traces bs))]
    (if-let [bad (first (filter r/err? results))]
      bad
      (r/ok (update bs :gpr/traces
                    (fn [ts] (mapv (fn [tr res] (assoc tr :gpr/samples (r/value res)))
                                   ts results)))))))
