(ns spotwork.facts
  "スキマバイト（単発シフト）の governor が引く**法域別の根拠カタログ**。

  ## この名前空間が持ってよいもの / 持ってはいけないもの

  持つ: **法令の条文そのものに書かれている数値**（1 日 8 時間・1 週 40 時間、
  6 時間超で 45 分・8 時間超で 60 分の休憩、深夜業の 22:00–05:00、割増賃金の
  2 割 5 分、最低年齢）。これらは改正が無い限り動かず、条文を引けば確認できる。

  持たない: **告示・行政が毎年動かす数値**。筆頭が地域別最低賃金額で、これは
  最低賃金法が「効力」を定めているだけで**額は条文に無い**（毎年度の改定を
  地域別に告示する）。したがって額は常に**オペレータ維持データ**として外から
  渡す。渡されなければ `:not-measured` であって `:pass` ではない。
  同じ切り方を `cloud-itonami-isic-7820` が採っている（README:「the actual
  current numeric wage-floor rate is always operator-maintained data, never
  hardcoded」）—— 揃えてある。

  ## URL を書いていない理由

  `:basis/url` は**意図的に nil**で、`:basis/url-status :not-recorded` を隣に
  置いている。法令名・公布番号・条番号は確認できるが、e-Gov の法令 ID を
  記憶から書けば、それは**根拠として引用できる形をした未検証の値**になる。
  記録が無いことは UNVERIFIED であって CONFORMANT ではない（CLAUDE.md）。
  オペレータが URL を入れるときは `with-basis-urls` を通す。

  ## 法域を足すとき

  `catalog` に 1 entry 足す。**実在する条文を引くこと。** 引けない規則は
  足さない —— 引けない根拠を持つ block は、governor が何を守っているかを
  説明できなくする。"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------- 根拠

(defn- basis
  "法令の 1 条を根拠として表す。URL は記録されるまで `:not-recorded`。"
  [law promulgation article note]
  {:basis/law law
   :basis/promulgation promulgation
   :basis/article article
   :basis/note note
   :basis/url nil
   :basis/url-status :not-recorded})

(def ^:private roudou-kijun "労働基準法")
(def ^:private roudou-kijun-no "昭和二十二年法律第四十九号")

;; ---------------------------------------------------------------- 日本

(def jp-rules
  "日本の単発シフトに効く規則。`:rule/severity` は 2 値だけ:

   - `:block` — 法令に反する提案。governor は絶対に通さない
   - `:hold`  — 法令ではなく運用上の床。人間の判断へ回す

  `:rule/threshold` に入っているのは**条文に書かれた数値だけ**。"
  {:hours/over-daily-cap-without-36
   {:rule/id :hours/over-daily-cap-without-36
    :rule/severity :block
    :rule/label "1 日 8 時間超に 36 協定の裏付けが無い"
    :rule/threshold {:daily-minutes 480 :weekly-minutes 2400}
    :rule/basis [(basis roudou-kijun roudou-kijun-no "第三十二条"
                        "休憩時間を除き一週間について四十時間、一日について八時間を超えて労働させてはならない")
                 (basis roudou-kijun roudou-kijun-no "第三十六条"
                        "労使協定の締結・届出により時間外労働をさせることができる")]}

   :hours/overtime-premium-missing
   {:rule/id :hours/overtime-premium-missing
    :rule/severity :block
    :rule/label "時間外労働に割増賃金が付いていない"
    :rule/threshold {:overtime-premium-rate 1.25}
    :rule/basis [(basis roudou-kijun roudou-kijun-no "第三十七条"
                        "時間外労働については通常の労働時間の賃金の二割五分以上の率で計算した割増賃金を支払わなければならない")]}

   :hours/night-premium-missing
   {:rule/id :hours/night-premium-missing
    :rule/severity :block
    :rule/label "深夜（22:00–05:00）に割増賃金が付いていない"
    :rule/threshold {:night-from 1320 :night-to 300 :night-premium-rate 1.25}
    :rule/basis [(basis roudou-kijun roudou-kijun-no "第三十七条"
                        "午後十時から午前五時までの間において労働させた場合においては二割五分以上の率で計算した割増賃金を支払わなければならない")]}

   :break/insufficient
   {:rule/id :break/insufficient
    :rule/severity :block
    :rule/label "法定の休憩がシフトに含まれていない"
    :rule/threshold {:over-360-needs 45 :over-480-needs 60}
    :rule/basis [(basis roudou-kijun roudou-kijun-no "第三十四条"
                        "労働時間が六時間を超える場合においては少くとも四十五分、八時間を超える場合においては少くとも一時間の休憩時間を労働時間の途中に与えなければならない")]}

   :minor/night-work
   {:rule/id :minor/night-work
    :rule/severity :block
    :rule/label "満 18 歳未満に深夜のシフトを充てている"
    :rule/threshold {:age 18 :night-from 1320 :night-to 300}
    :rule/basis [(basis roudou-kijun roudou-kijun-no "第六十一条"
                        "満十八才に満たない者を午後十時から午前五時までの間において使用してはならない")]}

   :minor/under-minimum-age
   {:rule/id :minor/under-minimum-age
    :rule/severity :block
    :rule/label "最低年齢に満たない者を対象にしている"
    :rule/threshold {:age 15}
    :rule/basis [(basis roudou-kijun roudou-kijun-no "第五十六条"
                        "児童が満十五歳に達した日以後の最初の三月三十一日が終了するまで、これを使用してはならない")]}

   :wage/below-minimum
   {:rule/id :wage/below-minimum
    :rule/severity :block
    :rule/label "提示時給が地域別最低賃金を下回る"
    ;; 額は条文に無い。オペレータ維持データを渡さない限り :not-measured。
    :rule/threshold {:source :operator-maintained}
    :rule/basis [(basis "最低賃金法" "昭和三十四年法律第百三十七号" "第四条"
                        "使用者は、最低賃金の適用を受ける労働者に対し、その最低賃金額以上の賃金を支払わなければならない")]}

   :wage/deduction-from-pay
   {:rule/id :wage/deduction-from-pay
    :rule/severity :block
    :rule/label "キャンセル料等を賃金から控除している"
    :rule/threshold nil
    :rule/basis [(basis roudou-kijun roudou-kijun-no "第二十四条"
                        "賃金は、通貨で、直接労働者に、その全額を支払わなければならない")]}

   :wage/penalty-predetermined
   {:rule/id :wage/penalty-predetermined
    :rule/severity :block
    :rule/label "違約金・損害賠償額をあらかじめ定めている"
    :rule/threshold nil
    :rule/basis [(basis roudou-kijun roudou-kijun-no "第十六条"
                        "使用者は、労働契約の不履行について違約金を定め、又は損害賠償額を予定する契約をしてはならない")]}

   :fee/charged-to-jobseeker
   {:rule/id :fee/charged-to-jobseeker
    :rule/severity :block
    :rule/label "求職者から手数料を徴収している"
    :rule/threshold nil
    :rule/basis [(basis "職業安定法" "昭和二十二年法律第百四十一号" "第三十二条の三"
                        "有料職業紹介事業者は、原則として求職者から手数料を徴収してはならない")]}

   :authorization/unverified
   {:rule/id :authorization/unverified
    :rule/severity :block
    :rule/label "就労資格の確認が済んでいない"
    :rule/threshold nil
    :rule/basis [(basis "出入国管理及び難民認定法" "昭和二十六年政令第三百十九号" "第十九条"
                        "在留資格に応じた活動の範囲を超える就労は資格外活動の許可を要する")]}

   :privacy/pii-in-proposal
   {:rule/id :privacy/pii-in-proposal
    :rule/severity :block
    :rule/label "提案に個人識別情報が載っている"
    :rule/threshold nil
    :rule/basis [(basis "個人情報の保護に関する法律" "平成十五年法律第五十七号" "第二十七条"
                        "個人データを第三者に提供するには、原則としてあらかじめ本人の同意を得なければならない")]}

   :supply/insufficient-cohort
   {:rule/id :supply/insufficient-cohort
    :rule/severity :hold
    :rule/label "cohort の人数が募集人数に足りない"
    :rule/threshold nil
    :rule/basis []}

   :cohort/below-k-anonymity
   {:rule/id :cohort/below-k-anonymity
    :rule/severity :block
    :rule/label "cohort が k 未満で、集計が個人を指しうる"
    :rule/threshold {:k 5}
    :rule/basis [(basis "個人情報の保護に関する法律" "平成十五年法律第五十七号" "第二十七条"
                        "k 未満の集団の集計値は個人を特定しうるため、第三者提供の制限が及ぶ")]}})

(def catalog
  "法域 → 規則表。**seed は日本のみ。** 他法域は 1 entry を実在の条文で足す。"
  {:jp {:jurisdiction/id :jp
        :jurisdiction/name "日本"
        :jurisdiction/rules jp-rules}})

;; ---------------------------------------------------------------- 参照

(defn rules
  "その法域の規則表。未収載の法域は nil（空 map ではない —— 空 map は
  「規則が 0 件＝何も違反しない」として通ってしまう）。"
  [jurisdiction]
  (get-in catalog [jurisdiction :jurisdiction/rules]))

(defn rule
  "1 規則。未収載なら nil。"
  [jurisdiction rule-id]
  (get (rules jurisdiction) rule-id))

(defn threshold
  "条文由来の閾値を 1 つ引く。無ければ nil。"
  [jurisdiction rule-id k]
  (get-in (rule jurisdiction rule-id) [:rule/threshold k]))

(defn with-basis-urls
  "オペレータが確認した URL を根拠に載せる。`urls` は
  `{[法令名 条番号] \"https://…\"}`。載った根拠だけ
  `:basis/url-status :recorded` に変わる。**推測で埋めない**ための唯一の入口。"
  [jurisdiction-rules urls]
  (reduce-kv
   (fn [acc rid r]
     (assoc acc rid
            (update r :rule/basis
                    (fn [bs]
                      (mapv (fn [b]
                              (if-let [u (get urls [(:basis/law b) (:basis/article b)])]
                                (assoc b :basis/url u :basis/url-status :recorded)
                                b))
                            bs)))))
   {} jurisdiction-rules))

(defn unrecorded-basis-count
  "URL が未記録の根拠の数。0 を目標にしない —— **数えられることが目的**で、
  埋めるのはオペレータの仕事。"
  [jurisdiction]
  (->> (vals (or (rules jurisdiction) {}))
       (mapcat :rule/basis)
       (filter #(= :not-recorded (:basis/url-status %)))
       count))

(defn describe
  "規則 1 件を人が読める 1 行に。ledger と提案本文の両方がこれを使う
  （2 箇所で別々に整形すると、片方だけが根拠を落とす）。"
  [r]
  (let [b (first (:rule/basis r))]
    (str (name (:rule/id r)) " — " (:rule/label r)
         (when b (str "（" (:basis/law b) " " (:basis/article b) "）")))))

(defn jurisdictions [] (set (keys catalog)))

(defn known-jurisdiction? [j] (contains? (jurisdictions) j))

(defn rule-ids [jurisdiction] (set (keys (or (rules jurisdiction) {}))))

(defn blocking-rule-ids [jurisdiction]
  (->> (rules jurisdiction) vals (filter #(= :block (:rule/severity %))) (map :rule/id) set))

(defn summary-line
  "法域 1 つの現在地。tick と README が同じ文字列を使う。"
  [jurisdiction]
  (str (name jurisdiction)
       " rules=" (count (rules jurisdiction))
       " blocking=" (count (blocking-rule-ids jurisdiction))
       " unrecorded-basis-urls=" (unrecorded-basis-count jurisdiction)))

(defn assert-catalog
  "カタログ自身の床。`[:ok n]` か `[:error reason detail]`。

  例外を投げない（`.kotoba` へ移す前提の `.cljc` なので Result で返す。
  CLAUDE.md「`throw` / `try` / `catch` を使わず `[:result T E]` を返す」）。"
  []
  (let [problems
        (for [[j jd] catalog
              [rid r] (:jurisdiction/rules jd)
              :let [sev (:rule/severity r)]
              :when (or (not= rid (:rule/id r))
                        (not (contains? #{:block :hold} sev))
                        (and (= :block sev) (empty? (:rule/basis r)))
                        (some (fn [b] (or (str/blank? (str (:basis/law b)))
                                          (str/blank? (str (:basis/article b)))))
                              (:rule/basis r)))]
          {:jurisdiction j :rule rid})]
    (if (seq problems)
      [:error :catalog/invalid (vec problems)]
      [:ok (reduce + 0 (map (comp count :jurisdiction/rules) (vals catalog)))])))
