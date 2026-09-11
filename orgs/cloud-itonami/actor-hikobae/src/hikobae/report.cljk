(ns hikobae.report
  "シナリオ比較を人が読む形で出す。

   **絶対値ではなく差を読ませる**ための整形。間接死の係数は :assumed なので、
   ベースラインとの差分と比率だけを出し、生の絶対値は括弧に落とす。"
  (:require [hikobae.kumamoto :as kumamoto]
            [hikobae.facts :as facts]
            [hikobae.loops :as loops]
            [hikobae.model :as model]))

(defn- pct [base v]
  (if (and (number? base) (number? v) (pos? (double base)))
    (* 100.0 (/ (- (double v) (double base)) (double base)))
    ##NaN))

#?(:clj
   (defn -main [& _]
     (let [f (facts/kumamoto-2026)
           rep (kumamoto/report f)
           base-params (:params rep)
           prov (facts/check-provenance base-params model/parameter-provenance)
           cmp (kumamoto/compare-scenarios base-params)
           base (:baseline cmp)]

       (println "═══ hikobae — 令和8年熊本地震 復旧ダイナミクス ═══")
       (println "day 0 =" (:event/day-zero f) " / 観測の最終時点 = 2026-08-23 10:00 (内閣府)")
       (println)

       (println "── パラメータの出所 ──")
       (println "  " (pr-str (:counts prov)) " undeclared=" (count (:undeclared prov)))
       (println "  ⚠ :assumed のパラメータは出典を持たない。絶対値ではなくシナリオ間の差を読むこと。")
       (println)

       (println "── 実観測への当てはまり ──")
       (doseq [[k v] (:fit rep)]
         (println (format "   %-10s nRMSE=%.3f  (n=%d)" (str k) (double (:nrmse v)) (:n v))))
       (println "   ⚠ 上水の残差は初期の塊（本管1本で数万戸が同時に戻る）に集中する。")
       (println "     連続レートのモデルは塊を再現しない —— 合わせているのは形であって日ごとの値ではない。")
       (println)

       (println "── シナリオ比較（ベースラインとの差）──")
       (println (format "   %-46s %14s %14s" "シナリオ" "避難者・日" "関連死(相対)"))
       (doseq [[k v] (sort-by (fn [[_ v]] (:person-days-displaced v)) cmp)]
         (println (format "   %-46s %13.0f %+13.1f%%"
                          (subs (:label v) 0 (min 44 (count (:label v))))
                          (double (:person-days-displaced v))
                          (pct (:indirect-deaths base) (:indirect-deaths v)))))
       (println)

       (println "── leverage 順（Meadows）──")
       (doseq [i (loops/ranked-interventions)]
         (println (format "   %5.1f  %-8s %s%s"
                          (double (:base-score i)) (str (:band i)) (:label i)
                          (if (:observed i) "  [実際に観測された介入]" ""))))
       (println)

       (println "── モデルが表現していないループ ──")
       (doseq [l (loops/loop-gaps)]
         (println (format "   %-34s %s" (str (:id l)) (:label l))))
       (println)

       (println "── 測れていない量 ──")
       (doseq [u (:event/unmeasured f)]
         (println (format "   %-42s %s" (str (:id u)) (subs (:why u) 0 (min 60 (count (:why u)))))))
       (println))))
