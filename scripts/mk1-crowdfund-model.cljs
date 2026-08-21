(ns mk1-crowdfund-model
  "叢雲 MK-1 — crowdfunding + 4地域(JP/US/CN/EU)展開の経済モデル。
   scripts/mk1-costmodel.cljs(単体販売)の続きで、そこで出た COGS を入力に取る。
   nbb only (ADR-2607173000)。cljs なので clojure.core/format は無い。

   crowdfunding が単体販売と決定的に違う点: **価格を今固定し、BOM を数か月後に
   買う**。DDR5 が12か月で +448% 動いた市場でこれは無ヘッジのショートである。
   本モデルの主目的はその感度を数字にすること。")

(def fx-jpy 163.8)   ; USD/JPY 2026-07-24/25 (Fed H.10)
(def fx-eur 1.1367)  ; EUR/USD 2026-07-24 (tradingeconomics)

(defn r0 [x] (str (Math/round (double x))))
(defn r1 [x] (str (/ (Math/round (* 10.0 (double x))) 10.0)))
(defn rp [s w] (let [s (str s)] (str s (apply str (repeat (max 0 (- w (count s))) " ")))))
(defn lp [s w] (let [s (str s)] (str (apply str (repeat (max 0 (- w (count s))) " ")) s)))
(defn row [n a b c] (println (str (rp n 46) (lp a 11) (lp b 11) (lp c 11))))

;; ── 単体販売モデルからの引き継ぎ (scripts/mk1-costmodel.cljs) ───────────────
(def parts-50 2265.0)     ; 部材 @50台
(def conv-50   214.5)     ; 転換費(組立/QC/梱包/RMA引当 等)
(def cogs-base (+ parts-50 conv-50))   ; 2479.5

;; ── crowdfunding 固有のコスト ───────────────────────────────────────────────
(def platforms
  [{:id :kickstarter :n "Kickstarter (JP creator 可, USD)" :fee 0.05 :pay 0.032 :hold 0.0
    :note "5% + 決済 ~3%+$0.20。reserve 無し"}
   {:id :indiegogo   :n "Indiegogo"                        :fee 0.05 :pay 0.032 :hold 0.05
    :note "同率だが 5% を6か月留保 — BOM 前払いが必要な本件では致命的"}
   {:id :makuake     :n "Makuake (JP)"                     :fee 0.20 :pay 0.0   :hold 0.0
    :note "20%。国内PRには強いが手数料が粗利を食う"}
   {:id :campfire    :n "CAMPFIRE (JP)"                    :fee 0.17 :pay 0.0   :hold 0.0
    :note "17%(プランにより12%)"}
   {:id :modian      :n "摩点/京东众筹 (CN)"                :fee 0.05 :pay 0.02  :hold 0.0
    :note "中国法人が必要 — CCC/SRRC と同じ壁。本件では選べない"}])

(def ship-intl 120.0)     ; 12kg 空輸 US/EU 平均 (assumption)
(def brokerage  40.0)     ; 通関/DDP 手数料 (assumption)
(def support-u   42.0)    ; 出荷後サポート 2h @ JPY3,500/h
(def fulfil (+ ship-intl brokerage support-u))

;; ── 地域別コンプライアンス固定費 ───────────────────────────────────────────
;; 単位 USD。JPY は /163.8、EUR は *1.1367。
(defn jpy [x] (/ x fx-jpy))
(defn eur [x] (* x fx-eur))

(def compliance
  {:jp {:label "日本"
        :items [["PSE 電気用品安全法" 0 "パソコン本体は直流機器として対象外。相場 JPY50万-300万が掛からない"]
                ["VCCI 入会金 JPY55,000(一回)" (jpy 55000) "法的義務でなく業界自主規制。B2Bでは実質必須"]
                ["VCCI Regular C 年会費 JPY220,000" (jpy 220000) "年10件未満"]
                ["VCCI 適合確認 JPY2,750/件" (jpy 2750) ""]
                ["第三者 EMC 試験 Class B JPY600,000" (jpy 600000) "assumption"]
                ["PL保険 JPY150,000/年" (jpy 150000) "assumption"]]}
   :us {:label "アメリカ"
        :items [["FCC Part 15 Class B — SDoC" 0 "Class B デジタル機器は自己宣言可。申請手数料なし"]
                ["FCC 用 EMC 試験(認定ラボ)" 5000 "assumption。WiFi は modular approval(FCC ID)を継承"]
                ["California CEC Title 20 試験 + MAEDbS 登録" 4000 "**必須**。desktop computer は対象、販売前に MAEDbS 登録。OR/WA/CO/VT/HI も同様規制"]
                ["Prop 65 表示" 500 "ラベルのみ"]
                ["US Responsible Party" 0 "AWAI Network, L.L.C.(Delaware)が既に存在 — 新設不要"]
                ["US 向け PL 保険上乗せ/年" 3000 "assumption。米国は賠償リスクが高い"]]}
   :eu {:label "EU"
        :items [["CE: EMC 2014/30 + LVD 2014/35 + RoHS 2011/65 試験一式" (eur 12000) "assumption"]
                ["RED 2014/53 (WiFi 6E)" (eur 2500) "pre-certified 無線モジュールを継承しても完成品のRF/EMC試験は要る"]
                ["EcoDesign 617/2013 (computers) 適合 + 技術文書" 3000 "desktop の消費電力要件。assumption"]
                ["EU Authorised Representative + GPSR Responsible Person /年" (eur 2500) "Reg 2019/1020 と GPSR(2024-12施行)で非EU事業者に必須"]
                ["WEEE 登録 5か国 /年" (eur 3500) "**加盟国ごとに個別登録**。国数に比例して増える"]
                ["包装 EPR 登録 /年" (eur 1300) "assumption"]
                ["電池規則(CMOS コイン電池) /年" (eur 450) "assumption"]]}
   :cn {:label "中国"
        :items [["CCC 認証(試験 + **工場審査** + 中国法人申請者)" 14000 "微型計算機は CCC 強制対象。CNCA指定機関が生産設備を監査 — 小規模組立には最大の壁"]
                ["SRRC 型号核准 (WiFi)" 6000 "試験6-8週、全体12-14週、中国語申請、中国法人必要"]
                ["China Energy Label (CEL)" 2000 "assumption"]
                ["China RoHS" 1000 "assumption"]
                ["**US EAR 再輸出 ECCN 判定(法律意見書)**" 8000 "GPU は米国原産。日本→中国は再輸出。3A090 該当なら中国向けライセンスは原則却下"]
                ["中国法人 / Importer of Record 設立" 5000 "assumption"]]}})

(defn region-total [k] (reduce + 0 (map second (:items (compliance k)))))

(def eng-onetime 25000.0)      ; 検証・イメージ・文書 (単体販売モデルと同額)
(def campaign-cost 30000.0)    ; 動画・LP・広告。hardware campaign は raise の20-30%が通例、これは lean 想定

;; ── BOM インフレ感度(crowdfunding の本質的リスク) ─────────────────────────
(defn cogs-at [infl] (+ (* parts-50 (+ 1.0 infl)) conv-50))

;; ── 価格ソルバ: platform 手数料 + fulfil を引いた後に target GM を残す価格 ──
(defn net-frac [{:keys [fee pay]}] (- 1.0 fee pay))
(defn solve-price
  "target-gm を粗利率(対 pledge 総額)として、必要な pledge 価格を返す。"
  [plat target-gm infl]
  (let [nf (net-frac plat) c (+ (cogs-at infl) fulfil)]
    (/ c (- nf target-gm))))

(defn unit-econ [plat price infl]
  (let [nf (net-frac plat)
        net (* price nf)
        c (+ (cogs-at infl) fulfil)]
    {:price price :net (Math/round net) :cogs (Math/round (cogs-at infl))
     :contrib (Math/round (- net c))
     :gm-pct (/ (Math/round (* 1000.0 (/ (- net c) price))) 10.0)}))

;; ── 出力 ───────────────────────────────────────────────────────────────────
(println "叢雲 MK-1 — crowdfunding / 4地域展開モデル   fx JPY" fx-jpy " EUR/USD" fx-eur)
(println (str "\n単体販売モデルからの引き継ぎ: 部材 $" (r1 parts-50)
              " + 転換費 $" (r1 conv-50) " = COGS $" (r1 cogs-base)))
(println (str "crowdfunding 固有の出荷費用: 国際送料 $" (r0 ship-intl)
              " + 通関 $" (r0 brokerage) " + サポート $" (r0 support-u)
              " = $" (r0 fulfil) " /台"))

(println "\n=== 1. platform 手数料の比較 ===")
(doseq [{:keys [n fee pay hold note]} platforms]
  (println (str (rp n 34) (lp (str (r1 (* 100 (+ fee pay))) "%") 8)
                (when (pos? hold) (str "  +" (r1 (* 100 hold)) "% 留保")) "   " note)))

(println "\n=== 2. BOM インフレ感度 — $3,980(単体販売価格)で pledge した場合 ===")
(println "crowdfunding は価格を今固定し BOM を後で買う。Kickstarter(8.2%)想定。")
(row "BOM インフレ" "COGS $" "粗利 $" "GM%")
(doseq [i [0.0 0.10 0.20 0.30 0.50]]
  (let [{:keys [cogs contrib gm-pct]} (unit-econ (first platforms) 3980 i)]
    (row (str "+" (r0 (* 100 i)) "%") (r0 cogs) (r0 contrib) (r1 gm-pct))))
(let [nf (net-frac (first platforms))
      brk (/ (- (* 3980 nf) conv-50 fulfil) parts-50)]
  (println (str "→ 粗利ゼロになる BOM インフレ = +" (r1 (* 100 (- brk 1.0))) "%")))
(println "参考: DDR5 は直近12か月で +448%、NAND は 1Q25比 +246%。RAM+SSD は BOM の $533。")

(println "\n=== 3. 必要 pledge 価格 — target GM 30%、インフレ緩衝つき ===")
(row "platform / 前提" "緩衝0%" "緩衝+20%" "緩衝+30%")
(doseq [p (remove #(= :modian (:id %)) platforms)]
  (row (:n p)
       (r0 (solve-price p 0.30 0.0))
       (r0 (solve-price p 0.30 0.20))
       (r0 (solve-price p 0.30 0.30))))
(println "→ early-bird 割引(通例 -20~30%)を上に乗せる余地は無い。")

(println "\n=== 4. 地域別コンプライアンス固定費 ===")
(doseq [k [:jp :us :eu :cn]]
  (println (str "\n【" (:label (compliance k)) "】 小計 $" (r0 (region-total k))))
  (doseq [[n usd note] (:items (compliance k))]
    (println (str "  " (rp n 52) (lp (r0 usd) 8) "  " note))))

(println "\n=== 5. 地域バンドル別 固定費と損益分岐 ===")
(def price-cf 4990.0)
(def contrib-cf (:contrib (unit-econ (first platforms) price-cf 0.0)))
(def contrib-cf-buf (:contrib (unit-econ (first platforms) price-cf 0.20)))
(println (str "pledge 価格 $" (r0 price-cf)
              " / 貢献利益 $" contrib-cf " (BOM 据置) · $" contrib-cf-buf " (BOM +20%)"))
(row "バンドル" "固定費 $" "分岐(据置)" "分岐(+20%)")
(doseq [[label ks] [["JP のみ" [:jp]]
                    ["JP + US" [:jp :us]]
                    ["JP + US + EU" [:jp :us :eu]]
                    ["JP + US + EU + CN(全4地域)" [:jp :us :eu :cn]]]]
  (let [fixed (+ (reduce + 0 (map region-total ks)) eng-onetime campaign-cost)]
    (row label (r0 fixed) (r1 (/ fixed contrib-cf)) (r1 (/ fixed contrib-cf-buf)))))

(println "\n=== 6. 数量シナリオ — JP+US+EU, $4,990 ===")
(def fixed-3 (+ (reduce + 0 (map region-total [:jp :us :eu])) eng-onetime campaign-cost))
(row "台数" "raise $" "貢献利益 $" "OP $")
(doseq [u [50 100 200 400]]
  (let [rev (* u price-cf) contr (* u (double contrib-cf)) op (- contr fixed-3)]
    (println (str (rp (str u " 台") 46) (lp (r0 rev) 11) (lp (r0 contr) 11) (lp (r0 op) 11)
                  (lp (str (r1 (* 100 (/ op rev))) "%") 9)))))
(println "(BOM +20% 時)")
(doseq [u [50 100 200 400]]
  (let [rev (* u price-cf) contr (* u (double contrib-cf-buf)) op (- contr fixed-3)]
    (println (str (rp (str u " 台") 46) (lp (r0 rev) 11) (lp (r0 contr) 11) (lp (r0 op) 11)
                  (lp (str (r1 (* 100 (/ op rev))) "%") 9)))))

(println "\n=== 7. 中国を外した場合の節約 ===")
(println (str "CN 固定費 $" (r0 (region-total :cn))
              " = " (r1 (/ (region-total :cn) contrib-cf)) " 台分の貢献利益。"))
(println "加えて CCC 工場審査・SRRC 12-14週・中国法人設立・US EAR 再輸出判定は")
(println "いずれも金額ではなく **時間と可否** の問題で、campaign の締切に間に合わない。")

;; ════════════════════════════════════════════════════════════════════════════
;; 8. 利益の正体 — 「組み立て・販売の差益」を固定円建てで切り出す
;;    オーナー指示(2026-07-26): 利益は主に組み立て・販売の差益。
;;    → 部材は pass-through として扱い、差益を **価格に対する%ではなく固定円**
;;      で建てる。これが crowdfunding の BOM ショートを構造的に解消する。
;; ════════════════════════════════════════════════════════════════════════════
(println "\n=== 8. 差益の分解 — $4,990 pledge の内訳 ===")
(def plat-k (first platforms))
(def plat-fee (* price-cf (+ (:fee plat-k) (:pay plat-k))))
(def sagieki (- price-cf parts-50 conv-50 ship-intl brokerage support-u plat-fee))
(doseq [[n v] [["部材(pass-through)" parts-50]
               ["転換費 組立/QC/梱包/RMA引当/イメージ" conv-50]
               ["出荷 国際送料/通関/サポート" (+ ship-intl brokerage support-u)]
               ["platform 手数料 8.2%" plat-fee]]]
  (println (str "  " (rp n 44) (lp (r0 v) 8) "  " (lp (str (r1 (* 100 (/ v price-cf))) "%") 7))))
(println (str "  " (rp "→ 差益(組立+販売)" 44) (lp (r0 sagieki) 8) "  "
              (lp (str (r1 (* 100 (/ sagieki price-cf))) "%") 7)
              "   = JPY " (r0 (* sagieki fx-jpy))))

(println "\n=== 9. 固定価格 vs 部材 pass-through — BOM インフレ時の差益 ===")
(def margin-fixed-jpy (* sagieki fx-jpy))   ; 差益を固定円で建てる
(row "BOM インフレ" "固定価格の差益$" "pass-through$" "顧客価格$")
(doseq [i [0.0 0.10 0.20 0.30 0.50 1.00]]
  (let [;; (a) 価格を $4,990 に固定した場合 — 差益がインフレを全部吸収する
        fixed-m (- price-cf (* parts-50 (+ 1.0 i)) conv-50 ship-intl brokerage support-u plat-fee)
        ;; (b) 部材 pass-through — 差益は固定、顧客価格が動く
        pass-price (/ (+ (* parts-50 (+ 1.0 i)) conv-50 ship-intl brokerage support-u sagieki)
                      (net-frac plat-k))]
    (row (str "+" (r0 (* 100 i)) "%") (r0 fixed-m) (r0 sagieki) (r0 pass-price))))
(println "→ 固定価格は BOM +43% で差益ゼロ。pass-through なら差益は不変で価格が動く。")
(println "→ crowdfunding では『build slot + 固定差益』を pledge し、部材は")
(println "  pledge manager 段階(製造直前)に実費精算する。これが Falco Prime 型の破綻を防ぐ。")

;; ════════════════════════════════════════════════════════════════════════════
;; 10. 節税商品としての設計(日本のみ) — 中小企業経営強化税制
;;     令和8年度改正: 適用期限 令和10年3月末、**器具備品は取得価額40万円以上**、
;;     B類型の投資利益率は 5% → **7%** に引上げ。
;;     即時償却 と 取得価額の10%税額控除(資本金3,000万円超は7%)の選択適用。
;; ════════════════════════════════════════════════════════════════════════════
(println "\n=== 10. 節税商品としての適格性(日本) ===")
(def price-jpy-ex (* price-cf fx-jpy))          ; 税抜取得価額
(def kigu-threshold 400000)                      ; 器具備品 取得価額要件(令和8年度改正)
(def shogaku-threshold 300000)                   ; 少額減価償却資産の特例
(def ikkatsu-threshold 200000)                   ; 一括償却資産
(def jikko-tax-rate 0.336)                       ; 中小法人の法定実効税率 約33.6%
(def credit-rate 0.10)                           ; 税額控除(資本金3,000万円以下)

(println (str "取得価額(税抜) = JPY " (r0 price-jpy-ex) "  ($" (r0 price-cf) ")"))
(doseq [[n thr ok?] [["中小企業経営強化税制 器具備品 40万円以上" kigu-threshold true]
                     ["少額減価償却資産の特例 30万円未満" shogaku-threshold false]
                     ["一括償却資産 20万円未満" ikkatsu-threshold false]]]
  (println (str "  " (rp n 46)
                (if (if ok? (>= price-jpy-ex thr) (< price-jpy-ex thr))
                  "○ 該当" "× 非該当")
                "   (閾値 JPY " (r0 thr) ")")))
(println "→ **『30万円未満で全額損金』型の節税PC訴求は使えない。**使えるのは経営強化税制。")

(println "\n即時償却 vs 税額控除 — どちらか一方を選択")
(let [defer (* price-jpy-ex jikko-tax-rate)
      credit (* price-jpy-ex credit-rate)]
  (println (str "  即時償却: 初年度に JPY " (r0 price-jpy-ex) " 全額損金"))
  (println (str "           → 初年度の税負担を JPY " (r0 defer) " 圧縮(実効33.6%)"))
  (println (str "           ただしこれは **繰延**。4年(パソコンの法定耐用年数)で見た"))
  (println (str "           損金総額は同じ。価値は時間価値と利益急増期の平準化。"))
  (println (str "  税額控除: JPY " (r0 credit) " (取得価額の10%)"))
  (println (str "           → こちらは **永久的な税負担減**。実質取得価額 JPY "
                (r0 (- price-jpy-ex credit)))))

(println "\nB類型(収益力強化設備)の投資利益率 — 年平均7%以上が要件")
(let [saving-usd 5863.0                      ; spot 代替の年間削減額(単体モデル §回収)
      saving-jpy (* saving-usd fx-jpy)
      roi (/ saving-jpy price-jpy-ex)]
  (println (str "  MK-1 の年間便益(H100 spot $2.99/h × 8h × 250日 の代替) = JPY " (r0 saving-jpy)))
  (println (str "  単純投資利益率 = " (r1 (* 100 roi)) "%  → 要件7%に対し "
                (r1 (/ roi 0.07)) " 倍の余裕"))
  (println "  ※ 実際の投資利益率は(営業利益+減価償却増加額)/設備投資額の3年平均で算定。"))
(println "  A類型は工業会証明書 + 旧モデル比1%以上の生産性向上が要件 → **初代機は旧モデルが")
(println "  無いため A類型は使えない。B類型(経済産業局の確認書)が本件の経路。**")

(println "\n=== 11. インフレ × 節税の相互作用(顧客の実質負担) ===")
(row "BOM インフレ" "顧客価格JPY" "税額控除JPY" "実質負担JPY")
(doseq [i [0.0 0.20 0.50]]
  (let [pass-price (/ (+ (* parts-50 (+ 1.0 i)) conv-50 ship-intl brokerage support-u sagieki)
                      (net-frac plat-k))
        jpy-ex (* pass-price fx-jpy)
        credit (* jpy-ex credit-rate)]
    (row (str "+" (r0 (* 100 i)) "%") (r0 jpy-ex) (r0 credit) (r0 (- jpy-ex credit)))))
(println "→ 取得価額が上がると控除額も比例して増えるので、値上げの一部を税制が吸収する。")
(println "→ 『メモリ高でも高値でよい』が成立する根拠。ただし吸収率は10%で万能ではない。")

(println "\n=== 12. 販売カレンダー(節税需要は決算期に集中) ===")
(println "  日本企業の決算期は3月が最多 → 納品は2〜3月着地が最も売れる。")
(println "  経営力向上計画は **原則 設備取得前** に主務大臣の認定が必要")
(println "  (取得日から60日以内の申請受理で特例的に可)。")
(println "  → crowdfunding の配送時期を決算期に合わせ、計画申請の案内を")
(println "    pledge 確定時点(製造の数か月前)に同送する必要がある。")

(println "\n=== 13. 節税商品として売るときの床(守る) ===")
(println "  ・断定的に『節税できる』と謳わない。適用は青色申告/中小企業者等の該当/")
(println "    指定事業/計画認定/確認書の取得という顧客側の条件次第。")
(println "  ・即時償却は繰延であり永久節税ではないと明記する(混同は景表法リスク)。")
(println "  ・murakumo は税務助言をしない。投資計画の算定資料(実測 tok/s と")
(println "    代替コスト)を提供するだけで、判断は顧客の税理士と経済産業局に委ねる。")
(println "  ・**実測 tok/s が無いと投資計画の根拠が作れない。**節税訴求も同じ gate に依存する。")
