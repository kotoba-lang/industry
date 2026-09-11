(ns mk1-de-fulfilment-model
  "ドイツ向けの供給方式 3 案 — IOR の決定は単独では決められない。

   問い(オーナー、2026-08-18): IOR のおすすめは? 組み立てを国ごとに行うのでもよい。

   IOR(輸入者)を誰にするかは、**供給方式を決めれば従属的に決まる**。逆は成り立たない。
   したがって比べるのは IOR ではなく供給方式:

     A 日本で組んで航空で都度出荷（ADR-2608180500 決定7 の現行案）
     B 日本で組んで**船便で Q4 前に独国内へ在庫**し、国内出荷
     C **ドイツの受託製造(CM)で現地組立**

   ■ 効かせる指標は per-unit 原価ではなく **Q4 の販売日数** である。
   ドイツの節税は 12/31 供用開始に収束するので、受注 Stichtag が遅いほど
   『最も購買意思の高い期間』を長く取れる。Stichtag は納期リードタイムが決める。"
  (:require [clojure.string :as str]))

(defn r0 [x] (js/Math.round x))
(defn r1 [x] (/ (js/Math.round (* 10 x)) 10))
(defn eur [x] (str "€" (.toLocaleString (r0 x) "en-US")))
(defn pad [s n] (.padEnd (str s) n))
(defn lp [s n] (.padStart (str s) n))

;; ADR-2608180500 / 2607267000 から
(def solo-netto 4490) (def solo-gm 2101)
(def jp-conversion 187)   ; 転換費 $214.5 ≒ €187（ADR-2607267000）

(def plans
  [{:id "A" :name "日本組立 + 航空 + 都度出荷"
    :adders [["航空輸送 JP→DE" 180] ["EU 通関手数料" 25] ["WEEE/BattG/VerpackG" 5]
             ["230V PSU 差額" 15]]
    :fixed  [["CE 試験" 12000 :one] ["AGB/AVV/Verfahrensdok" 3500 :one]
             ["EU AR" 1500 :yr] ["GPSR Responsible Person" 900 :yr]
             ["stiftung EAR" 600 :yr] ["LUCID + BattG" 400 :yr]
             ["独 VAT 登録 + 税務代理" 2000 :yr]]
    :lead [["部材確保" 14] ["組立・検査" 7] ["航空輸送" 5] ["通関" 3] ["国内配送" 2] ["据付余裕" 7]]
    :ior "murakumo（独 VAT 登録が要る）"
    :inv-units 0
    :risk "在庫リスクゼロ。ただし Q4 の締切が最も早い。"}

   {:id "B" :name "日本組立 + 船便 + 独国内に Q4 在庫"
    :adders [["船便 JP→Hamburg(LCL)" 45] ["EU 通関手数料" 25] ["3PL 保管・出荷" 25]
             ["WEEE/BattG/VerpackG" 5] ["230V PSU 差額" 15]]
    :fixed  [["CE 試験" 12000 :one] ["AGB/AVV/Verfahrensdok" 3500 :one]
             ["EU AR" 1500 :yr] ["GPSR Responsible Person" 900 :yr]
             ["stiftung EAR" 600 :yr] ["LUCID + BattG" 400 :yr]
             ["独 VAT 登録 + 税務代理" 2000 :yr] ["3PL 契約・初期" 1200 :yr]]
    :lead [["在庫から引当" 1] ["国内配送" 2] ["据付余裕" 7]]
    :ior "murakumo（独 VAT 登録が要る。輸入は Q3 に一括）"
    :inv-units 20
    :risk "Q4 需要を 8月に読む必要。売れ残りが在庫として残る。"}

   {:id "C" :name "ドイツ CM で現地組立"
    :adders [["CM 組立費(JP 転換費との差)" 93] ["WEEE/BattG/VerpackG" 5]]
    :fixed  [["CE 試験" 12000 :one] ["AGB/AVV/Verfahrensdok" 3500 :one]
             ["CM 立上げ・治具・受入試験の整備" 8000 :one]
             ["stiftung EAR" 600 :yr] ["LUCID + BattG" 400 :yr]]
    :lead [["部材確保(EU 調達)" 14] ["CM 組立・検査" 7] ["国内配送" 2] ["据付余裕" 7]]
    :ior "不要（murakumo は物を輸入しない。CM が国内で市場投入）"
    :inv-units 0
    :risk "無名の受託先だと B70 の割当が細り、年 10-50 台では真面目に取ってもらえない。品質分散。"}])

;; ── C2 は上の 3 案と別モデル（粗利ではなく per-unit ロイヤリティ）─────────────
;; オーナー指摘(2026-08-18): 日本ならドスパラ(サードウェーブ)。
;; **それなら『国ごとに組む』は新戦略ではなく、同じ型の横展開である。**
;; ドイツの構造的同型は WORTMANN AG(TERRA、Hüllhorst NRW、国内組立・自社ブランド・
;; **BTO/CTO 提供**・Systemhaus 経由の B2B 流通)。ほかに bluechip / Thomas-Krenn /
;; Hyrican / exone。この型では murakumo は箱の粗利を取らず、仕様とロイヤリティを取る。
(def c2
  {:id "C2" :name "独 BTO パートナー（仕様供与 + ロイヤリティ）"
   :royalty 600          ; ASSUMPTION: 仕様 + fleet image + 受入 gate + fleet 加入の対価
   :fixed [["統合・受入 gate の整備" 8000 :one]]
   :lead [["パートナー在庫から構成" 7] ["組立・検査" 5] ["国内配送" 2] ["据付余裕" 7]]
   :ior "不要。murakumo は物を輸入しない（役務の越境提供 = reverse charge）"
   :ce "**パートナー・ブランドなら CE の製造者責任もパートナー**（€12,000 が消える）。murakumo ブランドを保つなら残る。"
   :owns "murakumo が持ち続けるのは BOM 仕様 / fleet イメージ / 受入 gate(39.2 tok/s + Pidle) の 3 つだけ。"})

(println "=== ドイツ向け供給方式 3 案 ===\n")
(println "⚠ per-unit 原価も固定費も**全て ASSUMPTION**。実見積で置き換える。")
(println "  順位が入れ替わるかどうかを見るための模型であって、金額の予測ではない。\n")

(println "--- 1. per-unit 追加原価 ---")
(doseq [{:keys [id name adders]} plans]
  (println (str "  " id " " name))
  (doseq [[l v] adders] (println (str "      " (pad l 30) (lp (eur v) 8))))
  (println (str "      " (pad "合計" 30) (lp (eur (reduce + (map second adders))) 8))))

(println "\n--- 2. Stichtag（12/31 供用開始を保証する受注締切）---")
(println (str (pad "案" 34) (lp "リード日" 9) (lp "Stichtag" 11) (lp "安全側" 10) (lp "Q4販売日数" 12)))
(def dec31 365)  ; 12/31 を通日 365 とする（非閏年）
(def q4-start 274) ; 10/01
(def rows
  (for [{:keys [id name lead adders fixed inv-units risk ior]} plans]
    (let [days (reduce + (map second lead))
          stichtag-doy (- dec31 days)
          safe-doy (- stichtag-doy 8)              ; 安全側に 8 日引く
          q4-days (max 0 (- safe-doy q4-start))
          per-unit (reduce + (map second adders))
          one (reduce + (map second (filter #(= :one (nth % 2)) fixed)))
          yr  (reduce + (map second (filter #(= :yr (nth % 2)) fixed)))]
      {:id id :name name :days days :stichtag safe-doy :q4-days q4-days
       :per-unit per-unit :one one :yr yr :yr1 (+ one yr)
       :inv-units inv-units :risk risk :ior ior
       :gm (- solo-gm (- per-unit 225))})))   ; 225 = 決定3 の現行 per-unit
(defn doy->md [d]
  (let [m [31 28 31 30 31 30 31 31 30 31 30 31]]
    (loop [i 0 rest d]
      (if (<= rest (nth m i)) (str (inc i) "月" rest "日")
          (recur (inc i) (- rest (nth m i)))))))
(doseq [{:keys [id name days stichtag q4-days]} rows]
  (println (str (pad (str id " " name) 34) (lp days 9)
                (lp (doy->md (+ stichtag 8)) 11) (lp (doy->md stichtag) 10)
                (lp (str q4-days " 日") 12))))
(println "  ※ Q4販売日数 = 10/01 から安全側 Stichtag までの日数。ドイツの節税訴求が効く窓。")

(println "\n--- 3. 原価・固定費・粗利 ---")
(println (str (pad "案" 34) (lp "per-unit" 10) (lp "Solo 粗利" 11) (lp "一時費" 9)
              (lp "年次費" 9) (lp "初年度" 9) (lp "分岐台数" 10)))
(doseq [{:keys [id name per-unit gm one yr yr1]} rows]
  (println (str (pad (str id " " name) 34) (lp (eur per-unit) 10) (lp (eur gm) 11)
                (lp (eur one) 9) (lp (eur yr) 9) (lp (eur yr1) 9)
                (lp (str (r1 (/ yr1 gm)) " 台") 10))))

(println "\n--- 4. 運転資金（B のみ）---")
(let [b (first (filter #(= "B" (:id %)) rows))
      cogs-parts 2164]  ; ADR-2608180500 の DE COGS から DE adder を除いた部材相当
  (println (str "  B は Q4 前に " (:inv-units b) " 台ぶんの部材を先に買う: "
                (eur (* (:inv-units b) cogs-parts)) " が Q3 に寝る。"))
  (println (str "  売れ残り 1 台あたりの機会損失は粗利 " (eur (:gm b))
                " ではなく**部材 " (eur cogs-parts) " の滞留**（陳腐化するまで在庫は残る）。"))
  (println "  BOM が上昇局面(DDR5 +448% / NAND +246%)なら、**先に買うことは損ではなく利**である。"))

(println "\n--- 5. IOR は誰か（供給方式が決めれば従属的に決まる）---")
(doseq [{:keys [id name ior]} rows] (println (str "  " id " " (pad name 34) ior)))
(println "\n--- 6. 各案の主リスク ---")
(doseq [{:keys [id risk]} rows] (println (str "  " id ": " risk)))

(println "\n--- 7. C2: 独 BTO パートナー（別モデル。粗利ではなくロイヤリティ）---")
(let [days (reduce + (map second (:lead c2)))
      stich (- dec31 days) safe (- stich 8) q4 (- safe q4-start)
      one (reduce + (map second (:fixed c2)))]
  (println (str "  リード " days " 日 → 安全側 Stichtag " (doy->md safe)
                " → Q4販売日数 " q4 " 日"))
  (println (str "  murakumo の per-unit 取り分: " (eur (:royalty c2))
                "（A/B/C の粗利 " (eur solo-gm) " ではない）"))
  (println (str "  murakumo の固定費: 一時 " (eur one) " / 年次 €0"
                "  → 分岐 " (r1 (/ one (:royalty c2))) " 台"))
  (println (str "  IOR: " (:ior c2)))
  (println (str "  CE : " (:ce c2)))
  (println (str "  資産: " (:owns c2)))
  (println "  **模型が値段を付けられないもの**: パートナーの Systemhaus 流通網。")
  (println "  murakumo の実測 funnel は 訪問 549-728 → run 200 → **paid 0**（一度も売れていない）。")
  (println "  A/B/C は『粗利は大きいが自分で売る』、C2 は『取り分は小さいが売る人が居る』。")
  (println (str "  分岐台数は A の " (r1 (/ 20900 2101)) " 台に対し C2 は " (r1 (/ one (:royalty c2)))
                " 台で、**C2 の方が遅い**。"))
  (let [n-eq (/ (- 20900 one) (- solo-gm (:royalty c2)))]
    (println (str "  同じ台数なら A が常に有利（交点 " (r1 n-eq)
                  " 台は両方が赤字の領域にある）。**murakumo 単独の P&L では C2 は決して勝たない。**")))
  (println (str "  C2 が勝つ条件はただ一つ: **パートナーが murakumo 単独の "
                (r1 (/ solo-gm (:royalty c2))) " 倍以上を売ること**（漸近値。"
                (eur solo-gm) " / " (eur (:royalty c2)) "）。"))
  (println "  そして murakumo が単独で売った実績は **0 台**である。")
  (println "  → **これは物流の判断ではなく事業モデルの判断**。3.5 倍を『実績ある販売体制の 3.5 倍』と読むか")
  (println "     『ゼロの 3.5 倍』と読むかで答えが変わる。"))

(println "\n--- 8. A→B の差分だけを見る ---")
(let [a (first (filter #(= "A" (:id %)) rows))
      b (first (filter #(= "B" (:id %)) rows))]
  (println (str "  per-unit  " (eur (:per-unit a)) " → " (eur (:per-unit b))
                "  (" (eur (- (:per-unit b) (:per-unit a))) ")"))
  (println (str "  Q4販売日数 " (:q4-days a) " 日 → " (:q4-days b) " 日  (+"
                (- (:q4-days b) (:q4-days a)) " 日 = " 
                (r1 (* 100 (/ (- (:q4-days b) (:q4-days a)) (max 1 (:q4-days a))))) "%)"))
  (println (str "  初年度固定費 " (eur (:yr1 a)) " → " (eur (:yr1 b))
                "  (" (eur (- (:yr1 b) (:yr1 a))) ")"))
  (println (str "  分岐台数   " (r1 (/ (:yr1 a) (:gm a))) " → " (r1 (/ (:yr1 b) (:gm b))) " 台")))
