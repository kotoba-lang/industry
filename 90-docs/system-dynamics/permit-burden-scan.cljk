;; gov.cljs — 政府側の整合度。ADR-2608082100 の枠を公権力に当てる。
;;
;; 企業に対する問いは「顧客は正統性が落ちたとき離れられるか」だった。
;; 政府に対しては **退出は定義上不可能**（許認可は独占）なので、その問いは無意味になる。
;; 代わりに測れるのは「離れられないと分かっている相手に、どれだけの負荷を課しているか」:
;;
;;   人的手順数 / 標準処理日数 / 手数料 / 有効年数（更新のたびに人質に戻る） /
;;   要求証拠の数 / 法的根拠と出典が辿れるか
;;
;; 入力: 90-docs/regulatory/permits.datoms.edn（3,328 entity、ISIC・管轄・出典つき）

(require '[clojure.string :as str] '[clojure.edn :as edn])
(def fs (js/require "fs"))

(def SECTIONS
  [["A" 1 3 "農林水産"] ["B" 5 9 "鉱業・採石"] ["C" 10 33 "製造"] ["D" 35 35 "電気・ガス"]
   ["E" 36 39 "水道・廃棄物"] ["F" 41 43 "建設"] ["G" 45 47 "卸売・小売"] ["H" 49 53 "運輸・保管"]
   ["I" 55 56 "宿泊・飲食"] ["J" 58 63 "情報通信"] ["K" 64 66 "金融・保険"] ["L" 68 68 "不動産"]
   ["M" 69 75 "専門・技術"] ["N" 77 82 "管理支援"] ["O" 84 84 "公務・国防"]
   ["P" 85 85 "教育"] ["Q" 86 88 "保健・社会"] ["R" 90 93 "芸術・娯楽"]
   ["S" 94 96 "その他サービス"] ["T" 97 98 "家事"] ["U" 99 99 "国際機関"]])

(defn sec-of [isic]
  (when-let [i (some-> isic str)]
    (when (>= (count i) 2)
      (let [d (js/parseInt (subs i 0 2))]
        (first (filter (fn [[_ lo hi _]] (and (>= d lo) (<= d hi))) SECTIONS))))))

(def raw (edn/read-string (.readFileSync fs "/Users/junkawasaki/github/com-junkawasaki/90-docs/regulatory/permits.datoms.edn" "utf8")))
(def ents (filter map? raw))
(println "permits datoms:" (count raw) " / map entity:" (count ents))

(defn g [e k] (or (get e k) (get e (keyword "permit" (name k)))))
(def permits (filter #(get % :permit/subject-key) ents))
(def fees    (filter #(get % :fee-observation/id) ents))
(def procs   (filter #(get % :procedure/id) ents))
(println "許認可 entry:" (count permits) " / 手数料実測:" (count fees) " / 手続:" (count procs))

;; ── カバレッジ ────────────────────────────────────────────────────────
;; ⚠ dataset 自身の caveat: 法域別の行数は制度の数ではない。iso3166 の marketentry
;;   catalog が各国 repo に USA/DEU/GBR の比較行を再掲するため。制度を数えるには
;;   :permit/repo で自国行に絞る必要がある。
;; ⚠ dataset 自身の caveat: 法域別の行数は制度の数ではない。iso3166 の marketentry
;;   catalog が各国 repo に USA/DEU/GBR の比較行を再掲する（545 行中 自国行 185）。
;;   :permit/repo で絞る必要があるが、repo 名から自国を判定する素朴な方法では
;;   1,535 行中 187 しか拾えず順位として成立しなかった。よって **管轄別の順位は出さない**。
(println "\n═══ 管轄のカバレッジ ═══")
(println "  管轄数:" (count (distinct (keep :permit/jurisdiction permits))))
(println "  行数:" (count permits) "（再掲を含む。制度数ではない）")

(defn pct [a b] (str (.toFixed (* 100.0 (/ a (max 1 b))) 1) "%"))
(println "\n═══ 辿れるか（透明性）═══")
(println "  法的根拠あり  :" (count (keep :permit/legal-basis permits)) "/" (count permits)
         (pct (count (keep :permit/legal-basis permits)) (count permits)))
(println "  出典引用リストあり:" (count (keep :permit/citations permits)) "/" (count permits)
         (pct (count (keep :permit/citations permits)) (count permits)))
(println "  所管庁あり    :" (count (keep :permit/authority permits)) "/" (count permits)
         (pct (count (keep :permit/authority permits)) (count permits)))
(println "  ISIC あり     :" (count (keep :permit/isic permits)) "/" (count permits)
         (pct (count (keep :permit/isic permits)) (count permits)))

;; ── ISIC section 別の許認可密度 ────────────────────────────────────────
(println "\n═══ ISIC section 別 —— どの産業が最も許認可に縛られているか ═══")
(println (str "  " (.padEnd "section" 22) (.padEnd "許認可数" 10) (.padEnd "根拠あり" 10) "証拠要求の平均数"))
(let [by-sec (group-by #(first (sec-of (:permit/isic %))) (filter :permit/isic permits))]
  (doseq [[code _ _ ja] SECTIONS
          :let [g* (get by-sec code)] :when (seq g*)]
    (let [ev (keep #(let [v (:permit/required-evidence %)]
                      (cond (vector? v) (count v) (string? v) 1 :else nil)) g*)]
      (println (str "  " (.padEnd (str code " " ja) 22)
                    (.padEnd (str (count g*)) 10)
                    (.padEnd (str (count (keep :permit/legal-basis g*))) 10)
                    (if (seq ev) (.toFixed (/ (reduce + ev) (count ev)) 1) "—"))))))

;; ── 手数料の実測（出典つき）────────────────────────────────────────────
;; ⚠ dataset 自身の caveat: 手数料は標本であって制度の幅ではない。min/max を幅として
;;   引用しない。比較は licence-type と stage を揃えたときだけ意味を持つ。
;;   fee-year を持つ行はごく一部で、残りは「年度不明」であって今年度ではない。
(println "\n═══ 手数料の実測 —— licence-type を揃えた群でのみ比較する ═══")
(let [f (filter :fee-observation/amount fees)]
  (println "  実測件数:" (count f) " / 出典 URL あり:" (count (keep :fee-observation/source-url f))
           " / fee-year あり:" (count (keep :fee-observation/fee-year f)))
  (doseq [[lt g*] (reverse (sort-by #(count (second %)) (group-by :fee-observation/licence-type f)))
          :when (>= (count g*) 2)]
    (let [amts (map :fee-observation/amount g*)
          curs (distinct (map :fee-observation/currency g*))]
      (println (str "  ── " lt "  n=" (count g*) "  通貨 " (str/join "/" curs)))
      (println (str "     " (if (= 1 (count curs))
                              (str "min " (apply min amts) " / max " (apply max amts)
                                   " / 中央 " (nth (sort amts) (quot (count amts) 2)))
                              "通貨が混在 —— 数値を比較しない"))
               )
      (doseq [x (take 3 (reverse (sort-by :fee-observation/amount g*)))]
        (println (str "       " (.padEnd (str (:fee-observation/authority x)) 26)
                      (.padStart (str (:fee-observation/amount x)) 9) " "
                      (:fee-observation/currency x) " stage=" (:fee-observation/stage x)
                      " as-of " (:fee-observation/as-of x)))))))

;; ── 手続の負荷 ────────────────────────────────────────────────────────
(println "\n═══ 手続の負荷（人的手順数・処理日数・有効年数）═══")
(doseq [p (reverse (sort-by #(or (:procedure/human-step-count %) 0) procs))]
  (println (str "  " (.padEnd (str (:procedure/name p)) 40)
                " 手順 " (.padStart (str (or (:procedure/step-count p) "—")) 4)
                " / 人的 " (.padStart (str (or (:procedure/human-step-count p) "—")) 4)
                " / 日数 " (.padStart (str (or (:procedure/standard-period-days p) "—")) 5)
                " / 有効 " (.padStart (str (or (:procedure/valid-years p) "—")) 4) "年"
                "  " (:procedure/jurisdiction p))))

;; ── 未解決の法的論点（誠実さの指標）────────────────────────────────────
(println "\n═══ 未解決の法的論点を申告している手続 ═══")
(doseq [p procs :when (:procedure/open-legal-questions p)]
  (let [q (:procedure/open-legal-questions p)]
    (println (str "  " (:procedure/name p) " — "
                  (if (vector? q) (str (count q) " 件") (str q))))))

;; ── 自己申告のカバレッジ限界 ──────────────────────────────────────────
(println "\n═══ データセット自身が申告している限界 ═══")
(doseq [c (filter :coverage/rows ents)]
  (doseq [k [:coverage/rows :coverage/rows-with-citation :coverage/rows-without-citation
             :coverage/jurisdictions :coverage/fee-observations :coverage/procedures
             :coverage/caveat :coverage/fee-observation-caveat :coverage/excluded]]
    (when-let [v (get c k)]
      (println (str "  " (.padEnd (name k) 30) (if (vector? v) (str/join ", " (take 6 v)) v))))))
