#!/usr/bin/env nbb
;; verify-capital-pools — capital-pools.edn が budget-supply.edn から**今も**
;; 導かれることを、committed 済みの値を読み直すのではなく再導出して照合する。
;;
;; なぜ projection-verify ではないか。`:canonical-edn-v1` ローダは contract の
;; :projection/inputs に並んだ tx-data を正規化してハッシュを比べる。それが捕まえる
;; のは「capital-pools.edn の手編集」だけで、**budget-supply.edn だけが動いた場合を
;; 捕まえない** —— 生成物は pin どおりのまま、入力との関係だけが壊れる。実際に
;; 起きうるのは後者の方で、T2 を release する編集は budget-supply.edn 側にしか
;; 現れない。加えて budget-supply.edn は tx-data ではない（entity の vector では
;; なく 1 個の map）ので、そもそも project-edn の入力になれない。
;;
;; なぜ「全再生成の bytes 一致」ではないか。`gftd allocate pools md` の demand は
;; maturity-scores.edn ではなく canvas index からの live scoring で、canvas ledger は
;; 毎日動く。bytes 一致にすると翌日に赤くなり、以後ずっと赤のままになる
;; （root-training-corpus gate が同じ理由で全再生成を避けている）。
;;
;; そこで **demand に依存しない不変条件だけ**を再導出する。これは妥協ではない ——
;; capital-pools.edn が答えている問い（今いくら配れて、それはどこへ出せるか）は
;; 構造的に demand に依存しない: 配分先を決めるのは ADR が宣言した eligibility で
;; あって需要ではなく、demand が効くのは連結成分内の按分だけだからである。
;;
;;   1. pool の :pool/total-amount == Σ :tranche/max
;;   2. pool の :pool/use-envelope の合計 == :pool/total-amount
;;   3. allocatable == Σ released (max - committed - spent)
;;   4. held == Σ held の :tranche/max
;;   5. 上の 3 と 4 と declared が capital-pools.edn の本文に**その数値で**現れる
;;   6. budget-supply.edn の全 tranche が本文に 1 行ずつ現れる
;;   7. :tranche/eligible-products が nil / 空 vector でない
;;      （:undetermined は明示的な「未宣言」なので可。nil は「書き忘れ」と
;;        区別できず、行き先無しの金を静かに作る）
;;   8. :pool/contested-by を持つ pool は本文で警告されている
;;
;; usage: nbb scripts/verify-capital-pools.cljs [--root DIR]

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path (js/require "node:path"))

(def supply-rel "90-docs/business/budget-supply.edn")
(def pools-rel "90-docs/business/capital-pools.edn")

(defn read-edn [p]
  (when-not (.existsSync fs p)
    (println (str "FAIL " p " が無い")) (js/process.exit 1))
  (edn/read-string (.readFileSync fs p "utf8")))

(defn- n [x] (double (or x 0)))

(defn tranche-mass
  "released tranche の配分可能質量。gftd.allocate/tranche-mass と同じ定義を
   ここに置いているのは意図的 —— gate が検査対象の実装を require すると、
   実装が壊れたときに gate も同じように壊れて通ってしまう（同語反復）。"
  [t]
  (if (= :released (:tranche/state t))
    (max 0.0 (- (n (:tranche/max t)) (n (:tranche/committed t)) (n (:tranche/spent t))))
    0.0))

(defn fmt0 [x] (str (Math/round (double x))))

(defn check-pool [p]
  (let [tranches (:pool/tranches p)
        declared (n (:pool/total-amount p))
        tranche-sum (reduce + 0.0 (map #(n (:tranche/max %)) tranches))
        envelope (:pool/use-envelope p)
        envelope-sum (reduce + 0.0 (map n (vals envelope)))]
    (cond-> []
      (not= declared tranche-sum)
      (conj {:kind :total-vs-tranches
             :detail (str (name (:pool/id p)) ": :pool/total-amount " (fmt0 declared)
                          " ≠ Σ :tranche/max " (fmt0 tranche-sum))})

      (and (seq envelope) (not= declared envelope-sum))
      (conj {:kind :envelope-sum
             :detail (str (name (:pool/id p)) ": :pool/use-envelope の合計 " (fmt0 envelope-sum)
                          " ≠ :pool/total-amount " (fmt0 declared))})

      :always
      (into (for [t tranches
                  :let [e (:tranche/eligible-products t)]
                  :when (or (nil? e) (and (coll? e) (empty? e)))]
              {:kind :eligibility-unstated
               :detail (str (name (:pool/id p)) "/" (name (:tranche/id t))
                            ": :tranche/eligible-products が "
                            (if (nil? e) "未記載" "空")
                            " —— 書き忘れと『未定と決めた』を区別できない。"
                            ":undetermined と明示すること")})))))

(defn -main [& args]
  (let [flags (apply hash-map (map str args))
        root (or (get flags "--root") (.cwd js/process))
        supply (read-edn (.join path root supply-rel))
        pools-doc (read-edn (.join path root pools-rel))
        body (or (:doc/body (first pools-doc)) "")
        pools (vec (:supply/pools supply))
        _ (when (empty? pools)
            (println "FAIL budget-supply.edn に :supply/pools が無い —— 検査対象ゼロを合格にしない")
            (js/process.exit 1))
        allocatable (reduce + 0.0 (for [p pools t (:pool/tranches p)] (tranche-mass t)))
        held (reduce + 0.0 (for [p pools t (:pool/tranches p)
                                 :when (= :held (:tranche/state t))]
                             (n (:tranche/max t))))
        declared (reduce + 0.0 (map #(n (:pool/total-amount %)) pools))
        structural (vec (mapcat check-pool pools))
        ;; 本文に数値が現れるか
        stated (for [[label v] [["配分可能 (released)" allocatable] ["held" held]
                                ["宣言済 pool 総額" declared]]
                     :when (not (str/includes? body (str label ": " (fmt0 v))))]
                 {:kind :body-figure-missing
                  :detail (str "本文に「" label ": " (fmt0 v) "」が無い —— "
                               "budget-supply.edn を編集して capital-pools.edn を"
                               "再生成し忘れていないか（`gftd allocate pools md`）")})
        ;; 全 tranche が 1 行ずつ現れるか
        rows (for [p pools t (:pool/tranches p)
                   :let [k (str (name (:pool/id p)) "/" (name (:tranche/id t)))]
                   :when (not (str/includes? body k))]
               {:kind :tranche-row-missing
                :detail (str "本文に tranche 行「" k "」が無い")})
        ;; 係争が本文に出ているか
        contested (for [p pools
                        :when (and (:pool/contested-by p)
                                   (not (str/includes? body "係争中の pool")))]
                    {:kind :contested-not-surfaced
                     :detail (str (name (:pool/id p)) " は :pool/contested-by を持つのに"
                                  "本文が係争を報告していない")})
        found (concat structural stated rows contested)
        tranche-count (count (for [p pools t (:pool/tranches p)] t))]
    (println (str "verify-capital-pools: pool " (count pools) " / tranche " tranche-count
                  " | allocatable=" (fmt0 allocatable)
                  " held=" (fmt0 held) " declared=" (fmt0 declared)))
    (if (seq found)
      (do (println (str "\nFAIL " (count found) " 件 —— capital-pools.edn が "
                        "budget-supply.edn から導かれていない:"))
          (doseq [{:keys [kind detail]} found]
            (println (str "  ✗ " (name kind) "\n      " detail)))
          (println "\n直し方: budget-supply.edn を正した上で `gftd allocate pools md` を回し、"
                   "両方を同じ commit に載せる。")
          (js/process.exit 1))
      (println "OK — 宣言された pool 算術と capital-pools.edn の本文が一致している"))))

(apply -main (drop 3 (js->clj js/process.argv)))
