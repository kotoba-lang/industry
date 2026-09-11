;; re_scan.cljs — 不動産の契約文書に対する整合度の検出器。ADR-2608082100。
;; 語彙は約款とも ToS とも違う（中途解約違約金・原状回復・更新料・敷引・定期借家）。
;; 文書長が 5〜408 千字と 80 倍違うので 1 万字あたりの密度で測る。
;;
;; ⚠ 対象は国交省の **標準契約書とガイドライン**であって、市場で実際に使われている
;;   契約書ではない。標準契約書は均衡を意図して作られているので、ここで良い値が出ても
;;   「市場が整合している」ことにはならない。これは **基準線** である。
(require '[clojure.string :as str])
(def fs (js/require "fs"))
(def dir (or (nth (js->clj (.-argv js/process)) 3 nil)
             "/private/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/4601d432-1579-42ab-8bc2-cd06733e1d56/scratchpad/re"))

(def SIGNALS
  [{:mech :midterm :dir :neg :ja "中途解約の制限・違約金"
    :pats [#"中途解約" #"違約金" #"解約予告" #"予告期間" #"相当額を支払"]}
   {:mech :restore :dir :neg :ja "原状回復の借主負担"
    :pats [#"原状回復" #"通常損耗" #"経年変化" #"善管注意義務違反"]}
   {:mech :deposit :dir :neg :ja "敷金の償却・敷引"
    :pats [#"敷引" #"償却" #"敷金.{0,12}返還しない" #"保証金.{0,10}控除"]}
   {:mech :renewal :dir :neg :ja "更新料・再契約料"
    :pats [#"更新料" #"再契約料" #"更新事務手数料"]}
   {:mech :rentchange :dir :neg :ja "賃料の一方的改定"
    :pats [#"賃料.{0,6}改定" #"増額" #"減額を請求" #"賃料等を改定"]}
   {:mech :teiki :dir :neg :ja "定期借家（更新がない）"
    :pats [#"定期建物賃貸借" #"更新がなく" #"期間の満了により.{0,6}終了" #"再契約"]}
   {:mech :guarantor :dir :neg :ja "連帯保証・家賃債務保証"
    :pats [#"連帯保証" #"家賃債務保証" #"保証会社" #"極度額"]}
   {:mech :landlord-right :dir :neg :ja "貸主の一方的権利"
    :pats [#"貸主は.{0,20}できる" #"催告.{0,6}なく" #"直ちに.{0,8}解除"]}
   {:mech :restore-limit :dir :pos :ja "原状回復は貸主負担と明示"
    ;; 標準契約書 第15条「通常の使用に伴い生じた…損耗及び…経年変化を除き」
    :pats [#"経年変化を除き" #"通常の使用に伴い生じた" #"貸主が負担すべき費用"
           #"原状回復を要しない" #"借主が負担すべきものではない"]}
   {:mech :deposit-return :dir :pos :ja "敷金の返還を明示"
    :pats [#"敷金.{0,20}返還" #"残額を.{0,10}返還" #"速やかに.{0,8}返還"]}
   {:mech :tenant-exit :dir :pos :ja "借主の解約権を明示"
    ;; 法文は「申入れ」（「申し入れ」ではない）。実文で確認済み。
    :pats [#"乙からの解約" #"解約の申入れ" #"解約することができる" #"随時に本契約を解約"]}])

;; PDF 抽出は縦組み・字送りの日本語に文字間スペースを入れる
;; （実測: 「通常損 耗 ） に つ い て は 、 貸主」）。距離制限つきパターンが
;; これで全滅するので、照合前に空白を全部落とす。
(defn norm [t] (str/replace t #"[\s\u3000]+" ""))
(defn cnt [text p] (count (re-seq (js/RegExp. (.-source p) "g") text)))
(def files (sort (filter #(str/ends-with? % ".txt") (.readdirSync fs dir))))
(def docs
  (for [f files
        :let [raw (.readFileSync fs (str dir "/" f) "utf8")
              t (norm raw) n (count t)
              ;; 条文契約書だけを対象にする（様式・別表・Q&A は契約ではない）
              jo (count (re-seq #"第[０-９0-9一二三四五六七八九十]+条" t))
              ko (count (re-seq #"[甲乙]は" t))]
        :when (and (>= jo 10) (>= ko 5))]
    {:nm (str/replace f ".txt" "") :chars n
     :dens (into {} (for [s SIGNALS]
                      [(:mech s) (* 10000.0 (/ (reduce + (map #(cnt t %) (:pats s))) n))]))}))
(defn pad [s n] (.padEnd (str s) n))
(defn f2 [x] (.toFixed (js/Number x) 2))
(defn neg* [d] (reduce + (for [s SIGNALS :when (= :neg (:dir s))] (get (:dens d) (:mech s)))))
(defn pos* [d] (reduce + (for [s SIGNALS :when (= :pos (:dir s))] (get (:dens d) (:mech s)))))

(println "不動産 文書" (count docs) "本（国交省 標準契約書・ガイドライン）\n")
(println (str "  " (pad "文書ID" 12) (pad "千字" 7) (pad "負" 8) (pad "正" 8) "比"))
(doseq [d (reverse (sort-by neg* docs))]
  (println (str "  " (pad (:nm d) 12) (pad (.toFixed (/ (:chars d) 1000.0) 0) 7)
                (pad (f2 (neg* d)) 8) (pad (f2 (pos* d)) 8)
                (if (pos? (pos* d)) (str (f2 (/ (neg* d) (pos* d))) " : 1") "正=0"))))

(println "\n═══ 機序ごとの平均密度（1 万字あたり）═══")
(doseq [s SIGNALS]
  (let [vs (map #(get (:dens %) (:mech s)) docs)]
    (println (str "  " (if (= :neg (:dir s)) "−" "＋") " " (pad (:ja s) 26)
                  "密度 " (pad (f2 (/ (reduce + vs) (count vs))) 8)
                  " / " (count (filter pos? vs)) "/" (count docs) " 本"))))

(println "\n═══ 総計 ═══")
(println (str "  負 " (f2 (/ (reduce + (map neg* docs)) (count docs)))
              " / 正 " (f2 (/ (reduce + (map pos* docs)) (count docs)))
              " / 比 " (f2 (/ (reduce + (map neg* docs)) (max 1e-9 (reduce + (map pos* docs))))) " : 1"))
