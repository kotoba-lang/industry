(ns spotwork.proposal
  "単発シフト 1 件に対する **提案 1 行** の組み立てと検証。

  提案は台帳（append-only、1 行 1 EDN）に積まれる。台帳が持つべき性質は 3 つ:

  1. **提案と審査結果が同じ行に載る。** 別の行にすると「どの提案がどの verdict
     だったか」を後から突き合わせる仕事が発生し、突き合わせなかった行が
     『審査済み』の顔をする。
  2. **根拠（条文）が行に載る。** カタログが後で変わっても、その時に何を根拠に
     止めた／通したかが行から読める。
  3. **個人識別子が載らない。** cohort 集計だけを載せる。`validate` が拒否する。

  例外は投げない。`[:ok entry]` / `[:error reason detail]` を返す
  （CLAUDE.md の Result 規約）。"
  (:require [spotwork.governor :as gov]
            [spotwork.fingerprint :as fp]
            [spotwork.match :as match]
            [clojure.string :as str]))

(def schema-version "spotwork.proposal/v1")

(defn- iso-now []
  #?(:clj (str (java.time.Instant/now))
     :cljs (.toISOString (js/Date.))))

(defn build
  "offer + cohort 群 + オペレータ維持データ → 提案 1 行。

  `opts`: `{:weights … :phase … :at … :operator {…}}`。

  **verdict が `:pass` でなくても行は作る。** 止めた提案こそ台帳に残す価値が
  ある（何が止まっているかが見えなければ、規則は運用を説明できない）。"
  ([offer cohorts] (build offer cohorts {}))
  ([offer cohorts {:keys [weights phase at operator]
                   :or {phase :phase/advisory}}]
   (let [w (or weights match/default-weights)
         ranking (match/rank offer cohorts w)
         chosen (match/top ranking)
         cohort (first (filter #(= (:cohort/id %) (:cohort/id chosen)) cohorts))
         review (gov/review {:offer offer
                             :cohort cohort
                             :operator (or operator {})
                             :proposal {:offer/id (:offer/id offer)
                                        :ranking ranking}})]
     {:proposal/schema schema-version
      :proposal/id (str (:offer/id offer) "#" (or at (iso-now)))
      :proposal/at (or at (iso-now))
      :proposal/offer-id (:offer/id offer)
      ;; 求人の**内容**の指紋。id が同じまま賃金や時間が書き換わったら
      ;; 再審査が要る —— tick はこれを突き合わせて候補を決める。
      :proposal/offer-fingerprint (fp/of-offer offer)
      :proposal/employer-did (:offer/employer-did offer)
      :proposal/jurisdiction (:offer/jurisdiction offer)
      :proposal/phase phase
      ;; 提案の作動はいつも下書きまで。確定系は governor の never-auto が持つ。
      :proposal/actuation :actuation/draft-proposal
      :proposal/cohort-id (:cohort/id chosen)
      :proposal/score (:score chosen)
      :proposal/factors (:factors chosen)
      :proposal/ranking-counts (:counts ranking)
      :proposal/unscored-cohorts (mapv :cohort/id (:unscored ranking))
      :proposal/verdict (:verdict review)
      :proposal/verdict-counts (:counts review)
      :proposal/why (gov/why-not-pass review)
      :proposal/findings (vec (remove #(= :pass (:status %)) (:findings review)))
      :proposal/weights w})))

;; ---------------------------------------------------------------- 検証

(def required-keys
  #{:proposal/schema :proposal/id :proposal/at :proposal/offer-id
    :proposal/jurisdiction :proposal/actuation :proposal/verdict
    :proposal/offer-fingerprint
    :proposal/verdict-counts :proposal/findings})

(defn validate
  "台帳に積んでよい行か。`[:ok entry]` / `[:error :reason detail]`。

  拒否の理由は**必ず literal の keyword** で返す。真偽値で返すと、呼び手の
  truthy 判定が「空でない拒否」を成功として通す（CLAUDE.md が記録した
  `tls.cert` の事故と同じ形）。"
  [entry]
  (let [missing (vec (sort (remove #(contains? entry %) required-keys)))]
    (cond
      (not (map? entry))
      [:error :entry/not-a-map {:type (str (type entry))}]

      (seq missing)
      [:error :entry/missing-keys {:missing missing}]

      (not= schema-version (:proposal/schema entry))
      [:error :entry/schema-mismatch {:got (:proposal/schema entry) :want schema-version}]

      (contains? gov/never-auto (:proposal/actuation entry))
      [:error :entry/actuation-never-auto {:actuation (:proposal/actuation entry)}]

      (not (contains? #{:pass :hold :block} (:proposal/verdict entry)))
      [:error :entry/unknown-verdict {:verdict (:proposal/verdict entry)}]

      ;; verdict が :pass なのに未測定が残っている行は、通してはいけないものを
      ;; 通したことの証拠。governor 側が畳んでいないかをここでも突き合わせる。
      (and (= :pass (:proposal/verdict entry))
           (pos? (get-in entry [:proposal/verdict-counts :not-measured] 0)))
      [:error :entry/pass-with-unmeasured
       {:not-measured (get-in entry [:proposal/verdict-counts :not-measured])}]

      (and (= :pass (:proposal/verdict entry)) (nil? (:proposal/score entry)))
      [:error :entry/pass-without-score {}]

      :else
      (let [{:keys [hits]} (gov/pii-hits entry)]
        (if (seq hits)
          [:error :entry/pii-present {:hits (vec (take 8 hits))}]
          [:ok entry])))))

(defn ok?
  "Result の可否。`[:ok …]` だけが真。**戻り値そのものを truthy 判定しない
  ための唯一の入口。**"
  [result]
  (= :ok (first result)))

(defn line
  "台帳 1 行（改行なしの EDN 文字列）。"
  [entry]
  (pr-str entry))

(defn describe
  "人が読む 1 行。tick の出力と skill の報告が同じ整形を使う。"
  [entry]
  (str (:proposal/offer-id entry)
       " → " (or (:proposal/cohort-id entry) "(cohort なし)")
       " verdict=" (name (:proposal/verdict entry))
       " score=" (if-let [s (:proposal/score entry)]
                   (subs (str (+ 0.0005 s)) 0 (min 5 (count (str (+ 0.0005 s)))))
                   "not-measured")
       (when-let [w (:proposal/why entry)] (str " | " w))))

(defn counts-by-verdict
  "台帳の行群 → verdict ごとの件数。読めなかった行は `:unreadable` として
  数える —— 落とすと台帳が実際より綺麗に見える。"
  [entries]
  (reduce (fn [acc e]
            (let [v (if (map? e) (:proposal/verdict e) :unreadable)]
              (update acc (or v :unreadable) (fnil inc 0))))
          {} entries))

(defn summary-line [entries]
  (let [c (counts-by-verdict entries)]
    (str "proposals=" (count entries) " "
         (str/join " " (for [k [:pass :hold :block :unreadable]
                             :when (contains? c k)]
                         (str (name k) "=" (get c k)))))))
