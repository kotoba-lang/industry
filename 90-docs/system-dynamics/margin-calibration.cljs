;; calib.cljs — 実財務データからモデルパラメータを較正する。
;; 入力: cloud-murakumo-market-intel/data/company-facts.edn（SEC EDGAR XBRL、5,200 社）
;; 狙い: a_extract（創出価値のうち自分が取る割合）の実測代理 = 純利益 / 売上
(require '[clojure.string :as str] '[clojure.edn :as edn])
(def fs (js/require "fs"))
(def SECTIONS
  [["A" 1 3 "農林水産"] ["B" 5 9 "鉱業"] ["C" 10 33 "製造"] ["D" 35 35 "電気ガス"]
   ["E" 36 39 "水道廃棄物"] ["F" 41 43 "建設"] ["G" 45 47 "卸売小売"] ["H" 49 53 "運輸"]
   ["I" 55 56 "宿泊飲食"] ["J" 58 63 "情報通信"] ["K" 64 66 "金融保険"] ["L" 68 68 "不動産"]
   ["M" 69 75 "専門技術"] ["N" 77 82 "管理支援"] ["O" 84 84 "公務"] ["P" 85 85 "教育"]
   ["Q" 86 88 "保健社会"] ["R" 90 93 "芸術娯楽"] ["S" 94 96 "他サービス"]])
(defn sec-of [isic]
  (when-let [i (some-> isic str)]
    (when (>= (count i) 2)
      (let [d (js/parseInt (subs i 0 2))]
        (first (filter (fn [[_ lo hi _]] (and (>= d lo) (<= d hi))) SECTIONS))))))
(def rows
  (->> (edn/read-string (.readFileSync fs "/Users/junkawasaki/github/com-junkawasaki/orgs/network-awai/cloud-murakumo-market-intel/data/company-facts.edn" "utf8"))
       (filter map?)
       (keep (fn [m]
               (let [r (:company/revenue-usd m) n (:company/net-income-usd m)
                     a (:company/assets-usd m) isic (:company/isic m)]
                 (when (and r n (> r 1e6) isic)
                   {:name (:company/name m) :rev r :ni n :assets a :isic isic
                    :margin (/ n r) :sec (sec-of isic) :as-of (:company/as-of m)}))))
       (filter :sec) vec))
(println "使えた社:" (count rows) "（売上 >$1M かつ ISIC あり）")
(println "期間:" (first (sort (map :as-of rows))) "〜" (last (sort (map :as-of rows))))
(defn med [xs] (let [s (vec (sort xs)) n (count s)] (if (zero? n) nil (nth s (quot n 2)))))
(defn pad [s n] (.padEnd (str s) n))
(defn f3 [x] (if x (.toFixed (js/Number x) 3) "—"))

(println "\n═══ ISIC section 別の純利益率（= a_extract の実測代理）═══")
(println (str "  " (pad "section" 20) (pad "社数" 7) (pad "中央値" 10) (pad "第1四分位" 11) (pad "第3四分位" 11) "売上中央値(M$)"))
(def by-sec (group-by #(first (:sec %)) rows))
(doseq [[code _ _ ja] SECTIONS
        :let [g (get by-sec code)] :when (>= (count g) 8)]
  (let [ms (sort (map :margin g)) n (count ms)]
    (println (str "  " (pad (str code " " ja) 20) (pad n 7)
                  (pad (f3 (nth ms (quot n 2))) 10)
                  (pad (f3 (nth ms (quot n 4))) 11)
                  (pad (f3 (nth ms (quot (* 3 n) 4))) 11)
                  (.toFixed (/ (med (map :rev g)) 1e6) 0)))))

(println "\n═══ 規模と利益率の関係（規模の経済は実在するか）═══")
(let [sorted (sort-by :rev rows) n (count sorted) q (quot n 5)]
  (println (str "  " (pad "売上五分位" 14) (pad "社数" 7) (pad "売上中央値(M$)" 16) "純利益率 中央値"))
  (doseq [i (range 5)]
    (let [g (subvec (vec sorted) (* i q) (min n (* (inc i) q)))]
      (println (str "  " (pad (str "Q" (inc i)) 14) (pad (count g) 7)
                    (pad (.toFixed (/ (med (map :rev g)) 1e6) 0) 16)
                    (f3 (med (map :margin g))))))))

(println "\n═══ 全体 ═══")
(let [ms (map :margin rows)]
  (println "  純利益率 中央値:" (f3 (med ms))
           " / 負の社:" (count (filter neg? ms)) "(" (.toFixed (* 100.0 (/ (count (filter neg? ms)) (count ms))) 1) "%)"))
(println "  → モデルの a_extract 既定値 0.20 と比較すること")
