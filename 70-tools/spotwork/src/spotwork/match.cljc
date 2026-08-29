(ns spotwork.match
  "cohort-first の単発シフト突合。**決定論。モデルは居ない。**

  ## なぜ個人ではなく cohort を並べるのか

  この workspace の働き手側の正本は `cloud-itonami/talent`（自己主権の人材
  レジストリ）で、識別情報は E2E 暗号のまま、公開読みは **k-匿名 cohort 集計
  だけ**を出す。そこに個人を並べる突合を足すと、`talent` が構造で守っている
  ものを、それを消費する側が壊すことになる。

  したがってこの層が出すのは **cohort の順位**であって「この人」ではない。
  個人へ降ろすのは cohort を受け取ったオペレータの操作で、その個人情報は
  actor 側へ戻らない。**Timee との一番大きな設計差はここ**で、劣化ではなく
  別の位置取り（README の「境界」節を参照）。

  ## 測れなかった因子の扱い

  因子が 1 つでも測れなければ、**総合スコアは nil**（0 ではない）。0 にすると
  「情報が無い cohort」が「相性が悪い cohort」と同じ顔で最下位に並び、
  順位表からは区別が付かなくなる。`rank` は測れた cohort と測れなかった
  cohort を**別の列**で返す。"
  (:require [spotwork.time :as t]))

(def default-weights
  "重みは policy であってデータではないので、外から差し替えられる形で持つ。
  合計 1.0。`rank` の呼び手が `manifest/spotwork.edn` の値を渡してよい。"
  {:occupation 0.35
   :region 0.20
   :availability 0.25
   :reliability 0.10
   :supply 0.10})

;; ---------------------------------------------------------------- 因子

(defn isco-affinity
  "ISCO-08 の桁一致でみた職種の近さ。どちらか欠けたら nil。

  4 桁 unit group が一致 = 1.0、3 桁 minor group = 0.6、2 桁 sub-major = 0.3、
  1 桁 major = 0.1、それ以外 = 0.0。"
  [a b]
  (when (and (string? a) (string? b) (seq a) (seq b))
    (let [n (count (take-while true? (map = a b)))]
      (case n 4 1.0, 3 0.6, 2 0.3, 1 0.1, 0.0))))

(defn availability-coverage
  "シフト時間帯のうち、cohort が就業可能と申告している割合（0.0–1.0）。
  シフトが読めない / 申告が無い → nil。"
  [offer cohort]
  (let [shift (t/segments (:offer/start offer) (:offer/end offer))
        windows (:cohort/availability cohort)]
    (when (and shift (seq windows))
      (let [win-segs (vec (mapcat (fn [w] (or (t/segments (:from w) (:to w)) []))
                                  windows))
            total (reduce + 0 (map (fn [[a b]] (- b a)) shift))]
        (when (and (seq win-segs) (pos? total))
          (let [covered (t/overlap-minutes shift win-segs)]
            (when covered
              (double (/ (min covered total) total)))))))))

(defn reliability
  "1 − 無断欠勤率。申告が無い / 範囲外 → nil。"
  [cohort]
  (let [r (:cohort/no-show-rate cohort)]
    (when (and (number? r) (<= 0 r 1)) (double (- 1 r)))))

(defn supply-ratio
  "募集人数に対する cohort 人数（1.0 で頭打ち）。どちらか欠けたら nil。"
  [offer cohort]
  (let [need (:offer/headcount offer)
        have (:cohort/size cohort)]
    (when (and (number? need) (pos? need) (number? have))
      (double (min 1.0 (/ have need))))))

(defn region-affinity
  "地域一致。どちらか欠けたら nil。"
  [offer cohort]
  (let [a (:offer/region offer) b (:cohort/region cohort)]
    (when (and a b) (if (= a b) 1.0 0.0))))

;; ---------------------------------------------------------------- スコア

(defn factors
  "因子 → 値 or nil。"
  [offer cohort]
  {:occupation (isco-affinity (:offer/isco offer) (:cohort/isco cohort))
   :region (region-affinity offer cohort)
   :availability (availability-coverage offer cohort)
   :reliability (reliability cohort)
   :supply (supply-ratio offer cohort)})

(defn score
  "1 cohort の総合スコア。

  ```clojure
  {:cohort/id … :score 0.82 :factors {…} :not-measured []}      ; 測れた
  {:cohort/id … :score nil  :factors {…} :not-measured [:reliability]} ; 測れない
  ```

  **測れなかった因子が 1 つでもあれば `:score` は nil。** 欠けた因子を 0 と
  みなして計算を通すと、順位表は「情報が無い」を「相性が悪い」として出力し、
  読み手はその 2 つを区別できない。"
  ([offer cohort] (score offer cohort default-weights))
  ([offer cohort weights]
   (let [fs (factors offer cohort)
         missing (vec (sort (keys (into {} (filter (comp nil? val) fs)))))]
     (if (seq missing)
       {:cohort/id (:cohort/id cohort) :score nil :factors fs :not-measured missing}
       {:cohort/id (:cohort/id cohort)
        :score (double (reduce + 0 (map (fn [[k v]] (* v (get weights k 0.0))) fs)))
        :factors fs
        :not-measured []}))))

(defn rank
  "cohort 群を順位付ける。

  ```clojure
  {:ranked [{…} …]        ; :score 降順。同点は :cohort/id 昇順で決定論に
   :unscored [{…} …]      ; 測れなかったもの。**捨てない**
   :counts {:ranked n :unscored n}}
  ```

  `:unscored` を落とさないのは、落とすと「候補が無い」と「測れなかった」が
  同じ空の順位表として出てくるため。"
  ([offer cohorts] (rank offer cohorts default-weights))
  ([offer cohorts weights]
   (let [scored (mapv #(score offer % weights) cohorts)
         {ok true bad false} (group-by #(some? (:score %)) scored)
         ok (vec (or ok []))
         bad (vec (or bad []))]
     {:ranked (vec (sort-by (juxt (comp - :score) (comp str :cohort/id)) ok))
      :unscored (vec (sort-by (comp str :cohort/id) bad))
      :counts {:ranked (count ok) :unscored (count bad)}})))

(defn top
  "順位表の 1 位。測れた cohort が 1 つも無ければ nil。"
  [ranking]
  (first (:ranked ranking)))
