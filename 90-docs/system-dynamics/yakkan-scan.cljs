;; ins_scan.cljs — 保険約款に対する整合度の検出器。ADR-2608082100。
;; ToS 用の語彙（binding arbitration 等）は約款には出てこない。約款固有の語で測る。
;; 文書長が 36KB〜1.1MB と 30 倍違うので、有無ではなく **1 万字あたりの密度** で測る。
(require '[clojure.string :as str])
(def fs (js/require "fs"))
(def dir (or (nth (js->clj (.-argv js/process)) 3 nil)
             "/private/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/4601d432-1579-42ab-8bc2-cd06733e1d56/scratchpad/ins2"))

(def SIGNALS
  [{:mech :surrender :dir :neg :ja "解約時の控除"
    :pats [#"解約返戻金" #"解約控除" #"未経過期間" #"短期率" #"払いもどし金はありません" #"返還しません"]}
   {:mech :waiting :dir :neg :ja "免責・待機期間"
    :pats [#"免責期間" #"待機期間" #"不担保" #"責任開始日?からその日を含めて" #"支払いの対象となりません"]}
   {:mech :insurer-discretion :dir :neg :ja "査定の非対称（当会社が認め…）"
    :pats [#"当会社が認めた" #"当会社の指定する医師" #"当会社が必要と認め" #"当会社の判断" #"当会社の定める"]}
   {:mech :unilateral :dir :neg :ja "一方的変更"
    :pats [#"保険料を改定" #"約款を変更" #"予告なく" #"変更することができます"]}
   {:mech :disclosure :dir :neg :ja "告知義務違反による解除"
    :pats [#"告知義務違反" #"告知が事実と相違" #"契約を解除することができます"]}
   {:mech :autorenew :dir :neg :ja "自動更新"
    :pats [#"自動的に更新" #"自動更新" #"更新後の保険料"]}
   {:mech :exclusion :dir :neg :ja "免責事由の広さ"
    :pats [#"保険金を支払わない" #"てん補しない" #"免責事由" #"保険金をお支払いできない"]}
   {:mech :coolingoff :dir :pos :ja "クーリング・オフ（法定）"
    :pats [#"クーリング" #"申込みの撤回" #"8日以内"]}
   {:mech :exit-right :dir :pos :ja "解約の自由"
    :pats [#"いつでも.{0,10}解約" #"解約することができます" #"全額を返還"]}])

(defn count-all [text p]
  (count (re-seq (js/RegExp. (.-source p) "g") text)))

(def files (sort (filter #(str/ends-with? % ".txt") (.readdirSync fs dir))))
(def docs
  (for [f files
        :let [t (.readFileSync fs (str dir "/" f) "utf8")
              n (count t)
              d (into {} (for [s SIGNALS]
                           [(:mech s) (reduce + (map #(count-all t %) (:pats s)))]))]]
    {:nm (str/replace f ".txt" "") :chars n :raw d
     :dens (into {} (for [[k v] d] [k (* 10000.0 (/ v n))]))}))

(defn pad [s n] (.padEnd (str s) n))
(defn f2 [x] (.toFixed (js/Number x) 2))
(defn kind [d] (if (str/includes? (:nm d) "jyusetsu") :setsumei :yakkan))
(defn neg* [d] (reduce + (for [s SIGNALS :when (= :neg (:dir s))] (get (:dens d) (:mech s)))))
(defn pos* [d] (reduce + (for [s SIGNALS :when (= :pos (:dir s))] (get (:dens d) (:mech s)))))

(println "保険約款コーパス:" (count docs) "本（損保ジャパン）")
(println "  約款（拘束する文書）      :" (count (filter #(= :yakkan (kind %)) docs)) "本  平均"
         (.toFixed (/ (reduce + (map :chars (filter #(= :yakkan (kind %)) docs)))
                      (max 1 (count (filter #(= :yakkan (kind %)) docs))) 1000.0) 0) "千字")
(println "  重要事項説明書（読ませる文書）:" (count (filter #(= :setsumei (kind %)) docs)) "本  平均"
         (.toFixed (/ (reduce + (map :chars (filter #(= :setsumei (kind %)) docs)))
                      (max 1 (count (filter #(= :setsumei (kind %)) docs))) 1000.0) 0) "千字")

(println "\n═══ 文書種別ごとの密度（1 万字あたり）═══")
(println (str "  " (pad "機序" 28) (pad "約款" 12) (pad "重要事項説明書" 14) "差"))
(doseq [s SIGNALS]
  (let [y (filter #(= :yakkan (kind %)) docs) j (filter #(= :setsumei (kind %)) docs)
        ay (/ (reduce + (map #(get (:dens %) (:mech s)) y)) (max 1 (count y)))
        aj (/ (reduce + (map #(get (:dens %) (:mech s)) j)) (max 1 (count j)))]
    (println (str "  " (if (= :neg (:dir s)) "−" "＋") " " (pad (:ja s) 26)
                  (pad (f2 ay) 12) (pad (f2 aj) 14)
                  (cond (and (pos? ay) (zero? aj)) "← 約款にだけ書いてある"
                        (and (zero? ay) (pos? aj)) "→ 説明書にだけ書いてある"
                        :else "")))))

(println "\n═══ 負 vs 正 ═══")
(doseq [[lab g*] [["約款" (filter #(= :yakkan (kind %)) docs)]
                  ["重要事項説明書" (filter #(= :setsumei (kind %)) docs)]]]
  (println (str "  " (pad lab 14) "負 " (pad (f2 (/ (reduce + (map neg* g*)) (count g*))) 8)
                "正 " (pad (f2 (/ (reduce + (map pos* g*)) (count g*))) 8)
                "比 " (f2 (/ (reduce + (map neg* g*)) (max 1e-9 (reduce + (map pos* g*))))) " : 1")))

(println "\n═══ 同一商品の版を追う —— 団体医療 6 版（2019→2025）═══")
(println (str "  " (pad "版" 24) (pad "査定の非対称" 14) (pad "告知義務違反" 14) (pad "解約控除" 12) "免責事由"))
(doseq [d (sort-by :nm (filter #(str/includes? (:nm %) "dantaiiryo") docs))]
  (println (str "  " (pad (:nm d) 24)
                (pad (f2 (get (:dens d) :insurer-discretion)) 14)
                (pad (f2 (get (:dens d) :disclosure)) 14)
                (pad (f2 (get (:dens d) :surrender)) 12)
                (f2 (get (:dens d) :exclusion)))))
