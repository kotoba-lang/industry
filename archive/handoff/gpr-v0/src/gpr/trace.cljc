(ns gpr.trace
  "A-scan（1 本のトレース）と B-scan（測線に沿ったトレース列）の値。

  **この repo は機材とファイル形式を知らない。** 入り口はここに書いた素の
  データ構造だけで、ベンダ形式（SEG-Y / DZT 等）の読み取りは origin 面の
  別 repo が担う（ADR-2608750000 D3。出所ドメインを DNS で実測してから
  命名するので、この repo に推測で持ち込まない）。"
  (:require [gpr.result :as r]))

(defn a-scan
  "1 本のトレース。`samples` は振幅の列、`dt-ns` はサンプル間隔 [ns]、
  `x-m` は測線に沿った位置 [m]。"
  [samples dt-ns x-m]
  {:gpr/samples (vec samples) :gpr/dt-ns (double dt-ns) :gpr/x-m (double x-m)})

(defn b-scan
  "同じ `dt-ns` を共有するトレース列。x の昇順に並べ替えて保持する。"
  [traces]
  {:gpr/traces (vec (sort-by :gpr/x-m traces))})

(defn traces [bs] (:gpr/traces bs))
(defn n-traces [bs] (count (:gpr/traces bs)))
(defn dt-ns [bs] (:gpr/dt-ns (first (:gpr/traces bs))))
(defn n-samples [bs] (count (:gpr/samples (first (:gpr/traces bs)))))
(defn sample-time-ns [bs i] (* i (dt-ns bs)))

(defn validate
  "B-scan の形を検査して `[:ok bs]` か `[:err ...]` を返す。

  検査するのは 4 つ: トレースが 1 本以上あること / 全トレースが同じサンプル数を
  持つこと / `dt-ns` が全トレースで一致すること / x が狭義単調増加であること。
  **空の B-scan を「問題なし」にしない** —— 入力が無いときに pass を返す検査は
  それ自体が欠陥（ADR-2608136000）。"
  [bs]
  (let [ts (traces bs)]
    (cond
      (empty? ts)
      (r/err :trace/empty "B-scan にトレースが 1 本も無い")

      (not (apply = (map #(count (:gpr/samples %)) ts)))
      (r/err :trace/ragged "トレースごとにサンプル数が違う"
             {:counts (vec (distinct (map #(count (:gpr/samples %)) ts)))})

      (not (apply = (map :gpr/dt-ns ts)))
      (r/err :trace/dt-mismatch "トレースごとに dt-ns が違う"
             {:dts (vec (distinct (map :gpr/dt-ns ts)))})

      (not (apply < (map :gpr/x-m ts)))
      (r/err :trace/x-not-monotonic "x が狭義単調増加でない（重複位置を含む）")

      :else (r/ok bs))))

(defn map-samples
  "全トレースの samples に f を当てる（f: vector -> vector）。"
  [bs f]
  (update bs :gpr/traces (fn [ts] (mapv #(update % :gpr/samples f) ts))))
