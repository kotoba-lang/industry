#!/usr/bin/env nbb
;; 実データの財務諸表 —— MF が出した数字と、`kotoba.shohyo` が出す数字。
;;
;;   nbb --classpath "orgs/kotoba-lang/shohyo/src:.:scripts/nbb_compat" \
;;       scripts/verify-mf-statements-parity.cljs [--findings]
;;
;; ## これが最初の「数字の」parity 検査
;;
;; 既存の 2 本は**形**を見ている: `verify-moneyforward-parity` は財務諸表区分が
;; 定義されているか、`verify-mf-chart` は 712 科目が受理される chart かどうか。
;; **どちらも数字を 1 つも計算していない。** 区分が全部揃った chart が、
;; 貸借の合う間違った諸表を出すことは普通にありうる。
;;
;; ここは実際の残高を通して、MF が同じ元帳から出した小計と突き合わせる。
;; ADR-2608209200 が gap として名指した「この chart に statements を通した
;; 実データの財務諸表は、まだ 1 度も出していない」がこれ。
;;
;; ## 損失の符号は 2 つの正しい表現がある
;;
;; 会社計算規則 第八十九条第二項（と 第九十条〜第九十二条 の同文）は、
;; 売上総損益金額が零未満なら **零から減じた額を売上総損失金額として表示**せよと
;; 定める。つまり `-16,226,442 の営業利益` ではなく `16,226,442 の営業損失`。
;; MF は負の数で印字し、`kotoba.shohyo.jp` は正の大きさ + `:label` で返す。
;; **どちらも正しい。** だから突き合わせは |値| と loss/profit の別で行い、
;; 符号の食い違いをそのまま不一致にしない —— そこを雑にすると、規則どおりに
;; 実装した側が赤くなる。
;;
;; ## 答えられなかったときは 0 でも 1 でもない
;;
;; chart / 試算表が無い・読めない、shohyo が classpath に無い、は exit 3。
(ns verify-mf-statements-parity
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kotoba.shohyo :as shohyo]
            [kotoba.shohyo.jp :as jp]
            ["fs" :as fs]
            ["path" :as path]))

(def root (or (some-> js/process.env .-FLEET_ROOT) (.cwd js/process)))
(def argv (vec (drop 2 js/process.argv)))
(defn opt [f d] (let [i (.indexOf argv f)] (if (neg? i) d (nth argv (inc i) d))))
(def findings? (some #{"--findings"} argv))
(def chart-file (opt "--chart" (path/join root "90-docs" "accounting" "gftd-japan-chart.edn")))
(def tb-file (opt "--trial-balance"
                  (path/join root "90-docs" "accounting" "gftd-japan-trial-balance-2026.edn")))

(def found (atom 0))
(defn finding! [sev k detail]
  (swap! found inc)
  (when findings? (println (str "FINDING\t" sev "\t" k "\t" detail))))

(defn refuse! [why]
  (println (str "REFUSING to compare statements: " why))
  (println "exit 3 — could not answer. This is not a pass and not a gap.")
  (.exit js/process 3))

(defn slurp' [f] (when (fs/existsSync f) (fs/readFileSync f "utf8")))
(defn read' [f what]
  (let [t (or (slurp' f) (refuse! (str what " が無い: " f)))]
    (try (edn/read-string t)
         (catch :default e (refuse! (str what " が読めない: " (.-message e)))))))

(defn- cmp!
  "1 つの数字を突き合わせる。`mf` は MF の表示（損失は負）、`ours` は
  `kotoba.shohyo` の値。`magnitude?` なら |値| で比べる。"
  [label mf ours {:keys [magnitude?]}]
  (let [a (if magnitude? (abs mf) mf)
        b (if magnitude? (abs ours) ours)
        ok? (= a b)]
    (println (str (if ok? "  OK  " "  ✗   ") (str/join "" (repeat (max 0 (- 24 (count label))) " "))
                  label "\tMF " mf "\tours " ours))
    (when-not ok?
      (finding! "high" (str "statements:mismatch:" label)
                (str "MF は " mf "、kotoba.shohyo は " ours "（差 " (- b a) "）")))
    ok?))

(defn -main []
  (let [chart (read' chart-file "chart")
        tb (read' tb-file "試算表")]
    (when-not (and (map? chart) (seq chart)) (refuse! "chart が空、または map ではない"))
    ;; shohyo が本物であることを値で確かめる。空の名前空間なら全部きれいに通る。
    (when (or (< (count jp/bs-sections) 10) (not (contains? jp/bs-sections :current-assets)))
      (refuse! (str "shohyo が読み込めていない（bs=" (count jp/bs-sections) "）"
                    " —— --classpath に orgs/kotoba-lang/shohyo/src を足す")))

    (let [cur (:tb/currency tb)
          mf (:tb/mf-reported tb)
          debit (:tb/debit-normal tb)
          credit (:tb/credit-normal tb)]
      (when-not (and (map? mf) (seq debit) (seq credit)) (refuse! "試算表の形が違う"))
      ;; 試算表が試算表であることを先に確かめる。合っていない入力で
      ;; 諸表を作れば、どこが壊れているのか区別できない。
      (let [d (reduce + 0 (vals debit)) c (reduce + 0 (vals credit))]
        (when-not (= d c)
          (refuse! (str "入力の試算表が貸借一致していない: 借方 " d " 貸方 " c
                        "（差 " (- d c) "）。写し間違いか、期首/期末の取り違え"))))

      ;; **符号の変換はここ 1 箇所。** MF は貸方科目を正の大きさで報告し、
      ;; kotoba.shohyo は借方正の試算表を受ける。
      (let [balances (into {}
                           (concat (map (fn [[a v]] [[a cur] {:balance v}]) debit)
                                   (map (fn [[a v]] [[a cur] {:balance (- v)}]) credit))
                           )
            r (shohyo/statements chart balances)
            by (get-in r [:shohyo/by-currency cur])
            totals (:totals by)
            eq (:equation by)]

        (println (str "SCANNED\t" (count balances) " accounts of the FY"
                      (:tb/fiscal-year tb) " trial balance through a "
                      (count chart) "-entry chart"))
        (println (str "COVERAGE\t" (:shohyo/coverage r)
                      (when (seq (:shohyo/unclassified r))
                        (str " — unclassified: "
                             (str/join ", " (take 5 (:shohyo/unclassified r)))))))

        (when (seq (:shohyo/unclassified r))
          (finding! "high" "statements:unclassified"
                    (str (count (:shohyo/unclassified r))
                         " 科目が chart に無い: "
                         (str/join ", " (:shohyo/unclassified r)))))

        (println "\n-- 貸借対照表 ------------------------------------------------")
        (cmp! "資産" (:assets mf) (:assets totals) {})
        ;; ⚠ `:totals` は **presented** の値である —— 貸方科目は
        ;;   `kotoba.shohyo` 側で既に符号が反転している（README「liabilities,
        ;;   equity and revenue are credit-normal and are presented negated」）。
        ;;   ここで更に反転させると、**正しい実装が赤くなる。** 実測 2026-08-20、
        ;;   最初の版がそうで、負債と純資産の 2 件を誤って mismatch と報告した。
        (cmp! "負債" (:liabilities mf) (:liabilities totals) {})
        (cmp! "純資産(期首基準)" (:net-assets-opening-basis mf) (:equity totals) {})

        ;; ⚠ 鍵は `:holds?`。`:balanced?` は存在しないので nil = falsy になり、
        ;;   **貸借が合っているのに out-of-balance を報告する**（同上）。
        (println (str "  equation\t" (if (:holds? eq) "balanced" (pr-str eq))))
        (when-not (:holds? eq)
          (finding! "high" "statements:out-of-balance"
                    (str "資産 = 負債 + 純資産 + 当期純利益 が成立しない: " (pr-str eq))))

        (println "\n-- 損益計算書（第八十八条〜第九十四条）-----------------------")
        ;; 段階利益は区分の小計から出す。この事業者は売上原価も特別損益も 0 で、
        ;; **MF は 0 の科目を返さない**ので、区分としては 0 を明示的に宣言する。
        ;; ⚠ ここで 0 を入れることは「無い区分を 0 と読む」ことではない ——
        ;;    MF の PL が 売上原価 0 / 特別利益 0 / 特別損失 0 を**行として報告して
        ;;    いる**ので、これは測定値である。
        (let [section-totals {:net-sales (:net-sales mf)
                              :cost-of-sales 0
                              :sga (:sga mf)
                              :non-operating-income (:non-operating-income mf)
                              :non-operating-expense (:non-operating-expense mf)
                              :extraordinary-income 0
                              :extraordinary-loss 0}
              ladder (jp/stage-profits section-totals)]
          (if (not= :checked (:shohyo.jp/coverage ladder))
            (do (println (str "  ladder: " (pr-str ladder)))
                (finding! "high" "statements:ladder-not-declared"
                          (str "段階利益が出せない: " (pr-str (:shohyo.jp/missing-sections ladder)))))
            (do
              (doseq [[label mf-key rung-key]
                      [["売上総利益" :gross-profit :shohyo.jp/gross]
                       ["営業損益" :operating-loss :shohyo.jp/operating]
                       ["経常損益" :ordinary-loss :shohyo.jp/ordinary]
                       ["税引前当期純損益" :pretax-loss :shohyo.jp/pretax]]]
                (let [rung (get ladder rung-key)]
                  (cmp! label (get mf mf-key) (:amount rung) {:magnitude? true})
                  ;; 第八十九条第二項: 零未満は「損失」という別の名前になる。
                  ;; 大きさが合っていても名前が逆なら、それは規則違反である。
                  (let [mf-loss? (neg? (get mf mf-key))
                        our-loss? (= :loss (:sign rung))]
                    (when (not= mf-loss? our-loss?)
                      (finding! "high" (str "statements:sign:" label)
                                (str "MF は " (if mf-loss? "損失" "利益")
                                     "、kotoba.shohyo は " (:label rung)))))))
              (println (str "  labels\t" (str/join " / " (map #(:label (get ladder %))
                                                             [:shohyo.jp/gross :shohyo.jp/operating
                                                              :shohyo.jp/ordinary :shohyo.jp/pretax])))))))

        (if (pos? @found)
          (do (println (str "\n" @found " finding(s)."))
              (.exit js/process 1))
          (println (str "\nEvery figure kotoba.shohyo produced from this ledger matches"
                        " the one MoneyForward produced from the same ledger.")))))))

(-main)
