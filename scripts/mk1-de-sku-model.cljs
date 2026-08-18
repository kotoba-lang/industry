(ns mk1-de-sku-model
  "MK-1 のドイツ向け SKU。JP 版(ADR-2608153400)との差分だけを計算する。

   問い(オーナー、2026-08-18): ドイツ向けも SKU を設計する。

   ■ JP と DE で構造的に違うのは 3 点だけ。それ以外は同じ製品である。
   1. **税制が価格の下限を作らない。** JP は経営強化税制が 40万円以上でしか使えず、
      棚札がその閾値に釘付けされていた。DE の BMF 2022-02-22 は
      『コンピュータハードウェアの耐用年数 1 年』を金額に無関係に認めるので、
      **税制由来の下限が消える。**（ただし COGS 由来の下限は残る — 下で確認する）
   2. **事前申請が無い。** JP の B類型は経営力向上計画の認定 + 経済産業大臣の確認書が
      原則『取得前』。DE は買って供用開始すれば終わり。**したがって JP SKU が抱えている
      申請伴走のサービス行が DE には要らない。**その粗利をどこへ回すかが設計の本体。
   3. **決算期が 12/31。** JP の 2〜3 月着地とは別の波になる(ADR-2608180400 決定15)。

   ■ 実測(2026-08-18 確認)
   - BMF 2022-02-22(IV C 3 - S 2190/21/10002 :025): computer hardware の耐用年数 1 年。
     対象に **Workstation が名指しで含まれる**。**これは §5 Abs.1 EStG の意味での
     Wahlrecht ではない**と 2022 年通達が明記(納税者は仮定から離れることもできる)。
   - GWG(geringwertige Wirtschaftsgüter)の閾値は **800 EUR netto**(2026 年も据え置き)。
     デジタル資産は金額に関係なく即時損金なので、この閾値は我々の価格帯に効かない。
   - EUR/JPY 2026-08 月中平均 **182.55**(高値 183.93 / 安値 179.40)。"
  (:require [clojure.string :as str]))

(defn r0 [x] (js/Math.round x))
(defn r1 [x] (/ (js/Math.round (* 10 x)) 10))
(defn yen [x] (str "¥" (.toLocaleString (r0 x) "en-US")))
(defn eur [x] (str "€" (.toLocaleString (r0 x) "en-US")))
(defn pct [x] (str (r1 (* 100 x)) "%"))
(defn pad [s n] (.padEnd (str s) n))
(defn lp [s n] (.padStart (str s) n))

;; ── レート・税率 ─────────────────────────────────────────────────────────────
(def fx-eur-jpy 182.55)   ; MEASURED 2026-08 月中平均
(def ust 0.19)            ; MEASURED ドイツ Umsatzsteuer 19%
(def de-rate 0.298)       ; 計算値 KSt 15% + SolZ 0.825% + GewSt 14%(Hebesatz 400%)
(def jp-rate 0.336)       ; ADR-2608153400 の実効税率

;; ── JP SKU（ADR-2608153400 の正本。ここでは入力）───────────────────────────
(def jp-skus
  [{:k :lite :name "MK-1 Lite"  :vram "12GB" :jpy 498000   :cogs-jpy 273120}
   {:k :solo :name "MK-1 Solo"  :vram "32GB" :jpy 798000   :cogs-jpy 394984}
   {:k :ring :name "MK-4 Ring"  :vram "128GB":jpy 2480000  :cogs-jpy 1603992}])

;; ── DE 固有の追加原価（per unit）──────────────────────────────────────────
;; ASSUMPTION。実見積を取るまで確定しない。
(def de-adders
  [["国際輸送 JP→DE(航空、~15kg)" 180]
   ["EU 通関手数料"                 25]
   ["WEEE(stiftung EAR)分担"         2]
   ["BattG + VerpackG(LUCID)"        3]
   ["230V/Schuko PSU 差額"          15]])
(def de-adder-total (reduce + (map second de-adders)))
;; HS 8471(自動データ処理機械)の EU 関税は ITA により 0%。→ 関税行は無い。

;; ── DE 固定費（per year / one-time）────────────────────────────────────────
;; ADR-2607268000 の EU 5か国 $28,292 から 1 か国ぶんに引き直した $25,108 に対し、
;; JP から売るために追加で要るものを EUR で積む。ASSUMPTION。
(def de-fixed
  [["CE 試験(EMC/LVD/RED)"              12000 :one-time]
   ["EU Authorised Representative"       1500 :per-year]
   ["GPSR Responsible Person"             900 :per-year]
   ["stiftung EAR(WEEE)登録"              600 :per-year]
   ["LUCID(VerpackG) + BattG 登録"        400 :per-year]
   ["ドイツ VAT 登録 + 税務代理"           2000 :per-year]
   ["AGB / AVV / Verfahrensdokumentation" 3500 :one-time]])

;; ── DE 棚札（設計値）──────────────────────────────────────────────────────
;; JP の円建て棚札を fx で引き直したうえで、DE 固有原価を吸収して丸めた netto。
;; ADR-2608153400 決定2 のとおり **現地通貨で固定する**（USD/JPY を翻訳し続けない）。
(def de-price {:lite 2790 :solo 4490 :ring 13900})

(println "=== MK-1 ドイツ向け SKU 設計 ===\n")
(println (str "EUR/JPY " fx-eur-jpy "（MEASURED 2026-08 月中平均）"
              " / USt " (pct ust) " / DE 実効税率 " (pct de-rate)
              " / JP 実効税率 " (pct jp-rate) "\n"))

(println "--- 1. DE 固有の per-unit 追加原価（ASSUMPTION、実見積で置き換える）---")
(doseq [[label v] de-adders] (println (str "  " (pad label 32) (lp (eur v) 8))))
(println (str "  " (pad "合計" 32) (lp (eur de-adder-total) 8)))
(println "  ※ HS 8471 の EU 関税は ITA により 0%。関税行は無い。")

(println "\n--- 2. SKU 台帳（netto = 取得価額。brutto は消費者表示用）---")
(println (str (pad "SKU" 14) (lp "VRAM" 7) (lp "netto €" 10) (lp "brutto €" 10)
              (lp "COGS €" 9) (lp "粗利 €" 9) (lp "粗利%" 8) (lp "JP棚札換算€" 13)))
(def de-rows
  (for [{:keys [k name vram jpy cogs-jpy]} jp-skus]
    (let [netto (get de-price k)
          cogs  (+ (/ cogs-jpy fx-eur-jpy) de-adder-total)
          gm    (- netto cogs)]
      {:k k :name name :vram vram :netto netto :brutto (* netto (+ 1 ust))
       :cogs cogs :gm gm :gm-pct (/ gm netto)
       :jp-equiv (/ jpy fx-eur-jpy) :jpy jpy :jp-cogs-jpy cogs-jpy})))
(doseq [{:keys [name vram netto brutto cogs gm gm-pct jp-equiv]} de-rows]
  (println (str (pad name 14) (lp vram 7) (lp (eur netto) 10) (lp (eur brutto) 10)
                (lp (eur cogs) 9) (lp (eur gm) 9) (lp (pct gm-pct) 8) (lp (eur jp-equiv) 13))))

(println "\n--- 3. 税制の効き方 — JP と DE を同じ物差しで ---")
(println "  『期待値』= 初年度税効果 x ease（実際に控除へ到達する確率。ADR-2608180400 決定2）")
(println (str (pad "SKU" 14) (lp "DE 初年度€" 12) (lp "DE 期待値€" 12)
              (lp "JP 初年度¥" 14) (lp "JP 期待値¥" 14)))
(def jp-ease 0.35) (def de-ease 1.00)
(doseq [{:keys [name netto jpy]} de-rows]
  (let [de-rel (* netto de-rate) jp-rel (* jpy jp-rate)]
    (println (str (pad name 14) (lp (eur de-rel) 12) (lp (eur (* de-rel de-ease)) 12)
                  (lp (yen jp-rel) 14) (lp (yen (* jp-rel jp-ease)) 14)))))
(println "  → DE は申請が無いので初年度効果がそのまま期待値になる。JP は 0.35 が掛かる。")
(println "  → **どちらも繰延であって永久節税ではない**（ADR-2608180400 決定13）。")
(println "     DE に JP の 10%税額控除に相当する『永久』の枠は無い。混同して訴求しない。")

(println "\n--- 4. 税制由来の価格下限は DE で消えるか ---")
(println "  JP: 経営強化税制が 40万円以上 → 棚札が閾値に釘付け。")
(println "  DE: BMF 1年ルールは金額無関係。GWG 800€ は我々の価格帯の下。→ **税制の下限は無い。**")
(println "  では安い SKU を作れるか — COGS 由来の下限を実測する:")
(let [b580-cogs (+ (/ 273120 fx-eur-jpy) de-adder-total)]
  (doseq [p [1490 1790 1990 2290 2790]]
    (let [gm (- p b580-cogs)]
      (println (str "    netto " (lp (eur p) 8) " → 粗利 " (lp (eur gm) 8)
                    " = " (lp (pct (/ gm p)) 7)
                    (if (< (/ gm p) 0.30) "   ✗ 30% を割る" "   ○"))))))
(println "  → **税制の下限は消えるが COGS の下限は残り、しかもそちらの方が高い。**")
(println "     DE 専用の廉価 SKU は作らない。ladder の形は JP と同じで、理由だけが違う。")

(println "\n--- 5. DE 固定費と損益分岐台数 ---")
(println (str (pad "項目" 36) (lp "€" 9) (lp "区分" 10)))
(doseq [[label v kind] de-fixed]
  (println (str (pad label 36) (lp (eur v) 9) (lp (name kind) 10))))
(let [one-time (reduce + (map second (filter #(= :one-time (nth % 2)) de-fixed)))
      per-year (reduce + (map second (filter #(= :per-year (nth % 2)) de-fixed)))
      yr1 (+ one-time per-year)
      solo-gm (:gm (first (filter #(= :solo (:k %)) de-rows)))]
  (println (str "  一時費 " (eur one-time) " + 年次 " (eur per-year)
                " = 初年度 " (eur yr1)))
  (println (str "  MK-1 Solo の粗利 " (eur solo-gm) " で割ると **初年度 "
                (r1 (/ yr1 solo-gm)) " 台**、2年目以降 " (r1 (/ per-year solo-gm)) " 台/年。"))
  (println (str "  ADR-2607268000 の EU 1か国 $25,108(≒" (eur (/ 25108 1.15)) " @1.15) と同じ桁。")))

(println "\n--- 6. 12/31 供用開始から逆算した Stichtag ---")
(println "  DE の節税は『買って供用開始する』だけなので、**唯一の時計は納期**である。")
(println "  JP と違い申請リードタイムが無いぶん、締切をぎりぎりまで引ける。")
(def lead [["受注確定 → 部材確保" 14] ["組立・検査" 7] ["JP→DE 航空輸送" 5]
           ["通関" 3] ["国内配送" 2] ["据付・供用開始の余裕" 7]])
(let [total (reduce + (map second lead))]
  (doseq [[l d] lead] (println (str "    " (pad l 26) (lp (str d " 日") 8))))
  (println (str "    " (pad "合計" 26) (lp (str total " 日") 8)))
  (println (str "  → 12/31 供用開始を保証する受注 Stichtag = **12/31 から " total " 日前 = "
                "11月" (- 30 (- total 31)) "日ごろ**。安全側に **11月15日** を公表 Stichtag にする。"))
  (println "  ※ 部材確保 14 日は DDR5/NAND の逼迫下では楽観。実測で置き換える。"))
