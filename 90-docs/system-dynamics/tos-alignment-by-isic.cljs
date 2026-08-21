;; sector.cljs — ToS 整合度観測を ISIC section で切る。
;; 入力: tos-alignment（LEI → 検出機序）× market-intel（LEI → ISIC）
;; 目的: 不動産(L/68)・金融保険(K/64-66)・公務(O/84) を含む全 section の比較

(require '[clojure.string :as str] '[clojure.edn :as edn])
(def fs (js/require "fs"))
(def SP "/private/tmp/claude-501/-Users-junkawasaki-github-com-junkawasaki/4601d432-1579-42ab-8bc2-cd06733e1d56/scratchpad")

(def SECTIONS
  [["A" 1 3 "農林水産"] ["B" 5 9 "鉱業・採石"] ["C" 10 33 "製造"] ["D" 35 35 "電気・ガス"]
   ["E" 36 39 "水道・廃棄物"] ["F" 41 43 "建設"] ["G" 45 47 "卸売・小売"] ["H" 49 53 "運輸・保管"]
   ["I" 55 56 "宿泊・飲食"] ["J" 58 63 "情報通信"] ["K" 64 66 "金融・保険"] ["L" 68 68 "不動産"]
   ["M" 69 75 "専門・技術サービス"] ["N" 77 82 "管理支援サービス"] ["O" 84 84 "公務・国防"]
   ["P" 85 85 "教育"] ["Q" 86 88 "保健・社会事業"] ["R" 90 93 "芸術・娯楽"]
   ["S" 94 96 "その他サービス"] ["T" 97 98 "家事サービス"] ["U" 99 99 "国際機関"]])

(defn section-of [isic]
  (when (and isic (>= (count (str isic)) 2))
    (let [d (js/parseInt (subs (str isic) 0 2))]
      (first (filter (fn [[_ lo hi _]] (and (>= d lo) (<= d hi))) SECTIONS)))))

;; ── market-intel: LEI → ISIC / 名前 / 収益 ────────────────────────────
(def mi
  ;; company-facts.edn は #:company{...} の名前空間付き map の vector
  (let [v (edn/read-string (.readFileSync fs "/Users/junkawasaki/github/com-junkawasaki/orgs/network-awai/cloud-murakumo-market-intel/data/company-facts.edn" "utf8"))]
    (->> v
         (filter map?)
         (keep (fn [m]
                 (when-let [lei (:company/lei m)]
                   [lei {:isic (:company/isic m) :sic (:company/sic m)
                         :name (:company/name m) :rev (:company/revenue-usd m)}])))
         (into {}))))

(println "market-intel: LEI 付き" (count mi) "社、うち ISIC あり"
         (count (filter (comp :isic val) mi)))

;; ── ToS 観測 ──────────────────────────────────────────────────────────
(def tos (edn/read-string (.readFileSync fs (str SP "/tos-scored.edn") "utf8")))
(def names (edn/read-string (.readFileSync fs (str SP "/tos-lei-names.edn") "utf8")))
(println "ToS 観測:" (count tos) "社")

(def joined
  (for [t tos :let [m (get mi (:lei t))]]
    (assoc t :isic (:isic m) :sic (:sic m)
             :nm (or (get names (:lei t)) (:name m) (:lei t))
             :sec (section-of (:isic m)))))

(def with-isic (filter :sec joined))
(println "ISIC が付いた社:" (count with-isic) "/" (count joined)
         (str "(" (.toFixed (* 100.0 (/ (count with-isic) (count joined))) 1) "%)"))

;; ── section 別 ────────────────────────────────────────────────────────
(defn pad [s n] (.padEnd (str s) n))
(println "\n═══ ISIC section 別の逆行シグナル ═══")
(println (str "  " (pad "section" 24) (pad "社数" 6) (pad "平均 net" 10) (pad "退出" 6)
              (pad "一方的" 8) (pad "第三者" 8) (pad "非同意" 8) (pad "紛争" 6) (pad "＋" 5) "無検出"))
(doseq [[code lo hi ja] SECTIONS
        :let [g (filter #(= code (first (:sec %))) with-isic)]
        :when (seq g)]
  (let [n (count g)
        avg (/ (reduce + (map :net g)) n)
        cnt (fn [k] (count (filter #(get (:hits %) k) g)))]
    (println (str "  " (pad (str code " " ja) 24) (pad n 6)
                  (pad (.toFixed avg 2) 10)
                  (pad (cnt :lockin) 6) (pad (cnt :unilateral) 8) (pad (cnt :mismatch) 8)
                  (pad (cnt :nonconsent) 8) (pad (cnt :asym-power) 6)
                  (pad (+ (cnt :portability) (cnt :exit-right)) 5)
                  (count (filter #(empty? (:hits %)) g))))))

(println "\n═══ 不動産 (L/68) ═══")
(doseq [e (sort-by :net (filter #(= "L" (first (:sec %))) with-isic))]
  (println (str "  net " (.padStart (str (:net e)) 3) "  ISIC " (:isic e) "  " (pad (:nm e) 40)
                (str/join " " (map name (keys (:hits e)))))))

(println "\n═══ 金融・保険 (K/64-66) ═══")
(doseq [e (sort-by :net (filter #(= "K" (first (:sec %))) with-isic))]
  (println (str "  net " (.padStart (str (:net e)) 3) "  ISIC " (:isic e) "  " (pad (:nm e) 40)
                (str/join " " (map name (keys (:hits e)))))))

(println "\n═══ 公務・国防 (O/84) ═══")
(let [g (filter #(= "O" (first (:sec %))) with-isic)]
  (if (seq g)
    (doseq [e g] (println (str "  " (:nm e) " " (:isic e))))
    (println "  0 社 —— LEI/SEC の枠は法人を覆う。政府そのものは入っていない。")))

(println "\n═══ 国有・準国有として ToS を持つ社（名前から特定、ISIC 非依存）═══")
(let [soe ["kepco" "ratp" "sonatrach" "tenaga" "sbs transit" "japan post" "petrobras"
           "statoil" "equinor" "enel" "engie" "sncf" "deutsche bahn" "china " "korea "]]
  (doseq [e (sort-by :net joined)
          :when (some #(str/includes? (str/lower-case (str (:nm e))) %) soe)]
    (println (str "  net " (.padStart (str (:net e)) 3) "  " (pad (:nm e) 44)
                  (if (empty? (:hits e)) "（無検出）" (str/join " " (map name (keys (:hits e)))))))))
