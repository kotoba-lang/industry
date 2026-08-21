#!/usr/bin/env nbb
;; 仕訳が使っている税率を、chart が宣言しているか。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/verify-mf-tax-rate-coverage.cljs [--findings]
;;
;; ## 何が起きるとまずいのか
;;
;; 消費税法 第四十五条第一項 は「税率の異なるごとに区分した」課税標準額を要求し、
;; `bookkeeping.shohizei` は chart が税率を宣言しなければ `:rates-not-declared` を
;; 返す。**返すのは正しい。問題は、返さないのに足りていない場合である** ——
;; chart が {0, 0.1} を宣言していれば shohizei は満足するが、その chart で 0.08 の
;; 期を組み替えると、8% の仕入が**税率の区分から静かに落ちる**か、10% に混ざる。
;; どちらも貸借は合う。
;;
;; ## 分母は「マスタ」ではなく「記帳」
;;
;; 税区分マスタは 151 件、科目表が参照するのは 12 件、FY2026 の仕訳が使うのは 4 件。
;; **ここが見るのは 3 つ目**（`mf-journal-usage.edn`）。マスタを分母にすると、
;; 使っていない 5% 世代や簡易課税の一〜六種まで数え、使っている区分の抜けを隠す。
;;
;; ## 下界を完全と読まない
;;
;; `:usage/complete? false` の期は page 1 だけの下界なので、**そこに無い税率が
;; 無いことの証明にはならない**。だから「宣言されていない税率が見つかった」は
;; finding にするが、「見つからなかった」は完全な期についてしか主張しない。
(ns verify-mf-tax-rate-coverage
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]))

(def root (or (some-> js/process.env .-FLEET_ROOT) (.cwd js/process)))
(def argv (vec (drop 2 js/process.argv)))
(defn opt [f d] (let [i (.indexOf argv f)] (if (neg? i) d (nth argv (inc i) d))))
(def findings? (some #{"--findings"} argv))
(def usage-file (opt "--usage" (path/join root "90-docs" "accounting" "mf-journal-usage.edn")))
(def tax-file (opt "--taxes" (path/join root "90-docs" "accounting" "mf-tax-categories.edn")))
(def chart-file (opt "--chart" (path/join root "90-docs" "accounting" "gftd-japan-chart.edn")))

(def found (atom 0))
(defn finding! [sev k detail]
  (swap! found inc)
  (when findings? (println (str "FINDING\t" sev "\t" k "\t" detail))))

(defn- distinct-by [f coll]
  (->> coll (group-by f) vals (map first)))

(defn refuse! [why]
  (println (str "REFUSING to report rate coverage: " why))
  (println "exit 3 — could not answer. This is not a pass and not a gap.")
  (.exit js/process 3))

(defn slurp' [f] (when (fs/existsSync f) (fs/readFileSync f "utf8")))
(defn read' [f what]
  (let [t (or (slurp' f) (refuse! (str what " が無い: " f)))]
    (try (edn/read-string t)
         (catch :default e (refuse! (str what " が読めない: " (.-message e)))))))

(defn -main []
  (let [usage (read' usage-file "仕訳実測")
        taxes (read' tax-file "税区分表")
        chart (read' chart-file "chart")
        periods (filterv :usage/period usage)]
    (when (empty? periods) (refuse! "仕訳実測に期が 1 つも無い"))
    (when-not (map? taxes) (refuse! "税区分表が map ではない"))
    (when-not (and (map? chart) (seq chart)) (refuse! "chart が空、または map ではない"))

    ;; 略称 -> 税率。仕訳が返すのは `tax_name` の略称（"課仕 10%"）なので、
    ;; :abbreviation で引く。**:name で引くと 12 件中 1 件も一致せず、
    ;; 「宣言されている税率が 0 件」= 全部 finding になる**（実測 2026-08-20、
    ;; 最初の実装がそうだった。分かりやすく壊れたので気付けた）。
    (let [abbrev->rate (into {} (map (juxt :abbreviation :rate)) (vals taxes))
          declared (set (keep (fn [[_ v]] (:mf/tax-rate v)) chart))
          ;; 仕訳が使った略称すべて（期ごとに、完全かどうかを保持する）
          rows (for [p periods
                     [abbrev n] (:usage/tax-categories p)]
                 {:period (:usage/period p) :complete? (:usage/complete? p)
                  :abbrev abbrev :postings n :rate (get abbrev->rate abbrev ::unknown)})
          unknown (filterv #(= ::unknown (:rate %)) rows)
          used-rates (into (sorted-set) (keep #(when (number? (:rate %)) (:rate %)) rows))
          missing (remove declared used-rates)]

      (println (str "SCANNED\t" (count rows) " (period, tax-category) pairs across "
                    (count periods) " accounting period(s), "
                    (count (filter :usage/complete? periods)) " of them complete"))
      (println (str "DECLARED\t" (pr-str (vec (sort declared))) " — rates the chart carries"))
      (println (str "POSTED\t" (pr-str (vec used-rates)) " — rates the ledger actually used"))

      ;; 略称が税区分表に無い = 表が記帳に追いついていない。0% で埋めない。
      (doseq [{:keys [abbrev period postings]} (distinct-by :abbrev unknown)]
        (finding! "high" (str "tax-rate:unknown-category:" abbrev)
                  (str "仕訳が使っている税区分 " (pr-str abbrev) " が "
                       (path/basename tax-file) " に無い（FY" period "、" postings
                       " posting）。税率が引けないので、この区分は集計から静かに落ちる")))

      (doseq [r missing]
        (let [ps (filter #(= r (:rate %)) rows)
              periods' (str/join ", " (distinct (map #(str "FY" (:period %)) ps)))]
          (finding! "high" (str "tax-rate:not-declared:" r)
                    (str "元帳は税率 " r " で記帳しているが、chart のどの科目も"
                         " それを宣言していない（" periods' "）。消費税法 第四十五条"
                         "第一項 の税率別区分がこの期について出せない"))))

      (when (zero? @found)
        (let [complete (filter :usage/complete? periods)]
          (println (str "\nEvery rate the ledger posted is declared by the chart."
                        (if (= (count complete) (count periods))
                          ""
                          (str " ⚠ "
                               (- (count periods) (count complete))
                               " of the sampled periods are page-1 lower bounds, so this"
                               " is a statement about what was seen, not about those"
                               " periods as a whole."))))))
      (when (pos? @found)
        (println (str "\n" @found " finding(s)."))
        (.exit js/process 1)))))

(-main)
