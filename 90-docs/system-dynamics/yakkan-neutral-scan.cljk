;; neutral_scan.cljs — 株式会社保険と共済を同じ物差しで測る。ADR-2608082100。
;; 語彙が違う（保険金/共済金、当会社/組合、解約返戻金/払いもどし金）ので、
;; 主体と対象を中立化しないと共済が語彙不一致だけで不当に良く出る。実測で確認済み:
;;   JA共済 約款に「当会社」0 回・「解約返戻金」0 回。主体は「組合」。
(require '[clojure.string :as str])
(def fs (js/require "fs"))
(def A "(?:当会社|当社|組合|当会|当連合会|当共済|引受保険会社)")   ; 主体
(defn P [& xs] (mapv #(js/RegExp. % "g") xs))

(def SIGNALS
  [{:mech :surrender :dir :neg :ja "解約時の控除・払戻の制限"
    :pats (P "解約返戻金" "払いもどし金" "払戻金" "解約控除" "未経過期間" "短期率" "責任準備金")}
   {:mech :waiting :dir :neg :ja "免責・待機・不担保期間"
    :pats (P "免責期間" "待機期間" "不担保" "責任開始" "支払いの対象となりません")}
   {:mech :discretion :dir :neg :ja "引受側の裁量"
    :pats (P (str A "が認め") (str A "の定める") (str A "の指定") (str A "所定")
             (str A "が必要と認め") (str A "の判断"))}
   {:mech :unilateral :dir :neg :ja "一方的変更"
    :pats (P "変更することができ" "改定" "予告なく")}
   {:mech :disclosure :dir :neg :ja "告知義務違反による解除"
    :pats (P "告知義務違反" "告知が事実と相違" "解除することができ")}
   {:mech :exclusion :dir :neg :ja "免責事由の広さ"
    :pats (P "(?:保険金|共済金)(?:等)?を支払わない" "支払わない場合" "てん補しない" "免責事由")}
   {:mech :coolingoff :dir :pos :ja "クーリング・オフ（法定）"
    :pats (P "クーリング" "申込みの撤回" "8日以内")}
   {:mech :exit-right :dir :pos :ja "解約の自由"
    :pats (P "いつでも.{0,10}解約" "解約することができ" "全額を返還")}])

(defn norm [t] (str/replace t #"[\s　]+" ""))
(defn cnt [t p] (count (re-seq p t)))
(defn scan [dir label]
  (for [f (sort (filter #(str/ends-with? % ".txt") (.readdirSync fs dir)))
        :let [t (norm (.readFileSync fs (str dir "/" f) "utf8")) n (count t)]
        ;; 約款だけ（重要事項説明書・抜粋を除く）: 10 万字以上
        :when (> n 100000)]
    {:org label :nm f :chars n
     :dens (into {} (for [s SIGNALS]
                      [(:mech s) (* 10000.0 (/ (reduce + (map #(cnt t %) (:pats s))) n))]))}))

(def docs (concat (scan "/private/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/4601d432-1579-42ab-8bc2-cd06733e1d56/scratchpad/ins2" "株式会社(損保ジャパン)")
                  (scan "/private/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/4601d432-1579-42ab-8bc2-cd06733e1d56/scratchpad/kyosai" "共済(JA共済)")))
(defn pad [s n] (.padEnd (str s) n))
(defn f2 [x] (.toFixed (js/Number x) 2))
(defn neg* [d] (reduce + (for [s SIGNALS :when (= :neg (:dir s))] (get (:dens d) (:mech s)))))
(defn pos* [d] (reduce + (for [s SIGNALS :when (= :pos (:dir s))] (get (:dens d) (:mech s)))))

(println "同一の物差しでの比較（約款のみ、10 万字以上）\n")
(doseq [[org g*] (group-by :org docs)]
  (println (str "  " org "  n=" (count g*) "  平均 " (.toFixed (/ (reduce + (map :chars g*)) (count g*) 1000.0) 0) " 千字")))
(println (str "\n  " (pad "機序" 26) (pad "株式会社" 12) (pad "共済" 12) "比"))
(doseq [s SIGNALS]
  (let [k (group-by :org docs)
        a (let [g* (get k "株式会社(損保ジャパン)")] (/ (reduce + (map #(get (:dens %) (:mech s)) g*)) (count g*)))
        b (let [g* (get k "共済(JA共済)")] (/ (reduce + (map #(get (:dens %) (:mech s)) g*)) (count g*)))]
    (println (str "  " (if (= :neg (:dir s)) "−" "＋") " " (pad (:ja s) 24)
                  (pad (f2 a) 12) (pad (f2 b) 12)
                  (if (pos? b) (str (f2 (/ a b)) " : 1") "共済=0")))))
(println "\n═══ 総計 ═══")
(doseq [[org g*] (group-by :org docs)]
  (let [nn (/ (reduce + (map neg* g*)) (count g*)) pp (/ (reduce + (map pos* g*)) (count g*))]
    (println (str "  " (pad org 24) "負 " (pad (f2 nn) 8) "正 " (pad (f2 pp) 8)
                  "比 " (if (pos? pp) (str (f2 (/ nn pp)) " : 1") "正=0")))))
