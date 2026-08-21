(ns bto-jp-taxsale-model
  "murakumo AI agent server の日本国内販売 — 節税建て / fleet 参加 / 遊休 / GTM の
   採算計算器。nbb (ADR-2607173000: nbb-only script host)。

   ADR-2608153400（国内販売設計）と ADR-2607268000（MK-1 の価格建て）と
   scripts/mk1-costmodel.cljs を入力にして、**4 つのレーン**を突き合わせる:

     (1) 節税レーン  — どの税制が使えて、どの価格帯なら粗利が立つか
     (2) 参加レーン  — 余剰時間の供出は電気代を割るか
     (3) 遊休レーン  — 使い方の型ごとに『使っていないとき』はどれだけあるか
     (4) GTM レーン  — 損益分岐台数とチャネル

   **PoW マイニングは扱わない**（オーナー指示 2026-08-15: 『xrig mining はしません.
   ai agent sever のみ』）。実測でも系統 31 円/kWh では x86 BTO が年 −10,921 〜
   −23,932 円で負けていたので、放棄した機会は無い。計測の詳細は git 履歴。

   すべての入力に出所を付ける。FETCHED / MEASURED / ASSUMPTION を混ぜない。
   `nbb scripts/bto-jp-taxsale-model.cljs` で全節を再現する。")

;; ── 為替・電力 ──────────────────────────────────────────────────────────────
(def fx 159.30)         ; USD/JPY. FETCHED-DERIVED 2026-08-15: CoinGecko monero
                        ; usd 405.03 / jpy 64522 => 159.30。mk1-costmodel は 163.8
                        ; (2026-07-24 Fed H.10) を使っている。下で感度を出す。
(def fx-adr 163.80)     ; ADR-2607268000 / mk1-costmodel の基準

(def power-scenarios
  ;; ¥/kWh。従量電灯B 相当と、事業用・深夜・自家消費太陽光。
  [{:k :grid-jp   :yen 31.0 :src "JP 家庭用 1kWh 目安 2026 (mk1-costmodel と同値)"}
   {:k :biz-lowvo :yen 24.0 :src "ASSUMPTION 低圧事業用の実効単価帯 22-26"}
   {:k :night     :yen 20.0 :src "ASSUMPTION 夜間帯プラン"}
   {:k :pv-self   :yen 12.0 :src "ASSUMPTION 自家消費太陽光の LCOE 帯 10-15"}
   {:k :free      :yen  0.0 :src "余剰電力・熱利用と相殺した場合の上限ケース"}])

;; ── (1) 節税レーン ──────────────────────────────────────────────────────────
;; 税制の閾値は取得価額(税抜)で判定する。
(def tax-thresholds
  [{:k :ikkatsu   :n "一括償却資産（3年均等）"                 :max 200000
    :lend-out-ok false
    :src "20万円未満。令和4年度改正で『貸付け用（主要な事業として行うものを除く）』は対象外"}
   {:k :shougaku  :n "少額減価償却資産の特例（中小企業者等）"   :max 300000
    :lend-out-ok false
    :src "30万円未満・年間合計300万円まで・青色申告。同じく貸付け用は対象外"}
   {:k :keiei-kyoka :n "中小企業経営強化税制（器具備品）"       :min 400000
    :lend-out-ok false
    :src "ADR-2607268000: 令和8年度改正で器具備品の取得価額要件が40万円以上に引上げ／適用期限 令和10年3月末／B類型 投資利益率 年平均7%以上"}])

(def kouka-rate 0.336)  ; 法人実効税率。ADR-2607268000 の一般値。
                        ; ⚠ 2026-04-01 以後開始事業年度の防衛特別法人税（法人税額への付加）は
                        ;   この 33.6% に織り込んでいない。顧客ごとに税理士確認（未検証）。
(def tax-credit-rate 0.10) ; 取得価額の10%税額控除（資本金3,000万円超の法人は7%）

;; COGS は mk1-costmodel.cljs から引き継ぐ（USD、qty50 landed）。
(def cogs-usd
  {:lite 1714.5   ; MK-1 Lite: B70(1065) を Arc B580 12GB(ASSUMPTION 300) に置換
                  ;            parts 1500 + conversion 214.5
   :solo 2479.5   ; MK-1 Solo: mk1-costmodel の TOTAL COGS
   :ring 10069.0  ; MK-4 Ring: 同 4x Solo 構成
   :cpu-only 1414.5}) ; dGPU 無し 32GB 構成（少額特例 SKU が成立するかの検証用）

(def sku-ladder
  ;; 価格は税抜（取得価額）。閾値をまたぐ位置に置いてある。
  [{:k :lite :n "MK-1 Lite（12GB VRAM・入門）"      :cogs (:lite cogs-usd) :price-jpy  498000}
   {:k :solo :n "MK-1 Solo（32GB VRAM・本命）"      :cogs (:solo cogs-usd) :price-jpy  798000}
   {:k :ring :n "MK-4 Ring（128GB VRAM・4ヘッド）"  :cogs (:ring cogs-usd) :price-jpy 2480000}])

(defn yen [usd] (Math/round (* usd fx)))
(defn pct [x] (/ (Math/round (* 1000.0 x)) 10.0))

(defn sku-row [{:keys [n cogs price-jpy]}]
  (let [c (yen cogs) gm (- price-jpy c)]
    {:n n :cogs-jpy c :price price-jpy :gm gm :gm-pct (pct (/ gm price-jpy))
     :inc-tax (Math/round (* price-jpy 1.10))
     :>=40man (>= price-jpy 400000)
     :<30man  (<  price-jpy 300000)}))

(defn tax-effect
  "取得価額 p に対する 即時償却 と 10%税額控除 の初年度効果。"
  [p]
  (let [sokuji (Math/round (* p kouka-rate))
        kojo   (Math/round (* p tax-credit-rate))]
    {:price p
     :sokuji-cut sokuji          :sokuji-net (- p sokuji)   ; 繰延（4年で戻る）
     :credit-cut kojo            :credit-net (- p kojo)}))  ; 永久

;; ── (2) 参加レーン — 余剰時間の供出 ──────────────────────────────────────
(def spot-ref {:usd-h 2.99 :tok-s 2000.0})   ; local-murakumo cost.cljc の既定
(defn spot-yen-mtok [] (* (/ (* (:usd-h spot-ref) fx) (* (:tok-s spot-ref) 3600.0)) 1e6))

(def mk1-load-w 310.0)      ; mk1-costmodel の MK-1 想定負荷
(def gate-tok-s 39.2)       ; mk1-costmodel: 有償販売 gate = single-card decode
(def measured-tok-s nil)    ; ⚠ 未実測。ADR-2607267000/2607268000 決定10 の前提条件。

(defn supply-row
  "1日 h 時間 × 年 d 日を fleet に供出したときの credits 価値と電気代。"
  [tok-s h d yen-kwh]
  (let [mtok (/ (* tok-s 3600.0 h d) 1e6)
        rev  (* mtok (spot-yen-mtok))
        kwh  (/ (* mk1-load-w h d) 1000.0)
        cost (* kwh yen-kwh)]
    {:tok-s tok-s :mtok-yr (Math/round mtok)
     :credit-yen-yr (Math/round rev) :power-yen-yr (Math/round cost)
     :net-yen-yr (Math/round (- rev cost))
     :yen-per-mtok (/ (Math/round (* 10 (/ (* mk1-load-w yen-kwh) (* tok-s 3.6)))) 10.0)}))

(defn selfuse-benefit
  "『H100 spot を 8h×250日 借りる代わりに自社機で回す』置換便益（円/年）。
   ⚠ これは *マシン時間* の置換であってトークン単価の置換ではない。"
  [yen-kwh]
  (let [rent (* (:usd-h spot-ref) 8 250 fx)
        elec (* (/ (* mk1-load-w 8 250) 1000.0) yen-kwh)]
    {:rent-avoided (Math/round rent) :elec (Math/round elec)
     :net (Math/round (- rent elec))}))

;; ── (4) GTM レーン — 損益分岐台数とチャネル ────────────────────────────────
(def fixed-y1-usd
  ;; mk1-costmodel の fixed-y1 をそのまま引き継ぐ（USD）。
  [{:n "VCCI 入会（一時）"                :usd (/ 55000 163.8)}
   {:n "VCCI 正会員C 年会費"              :usd (/ 220000 163.8)}
   {:n "VCCI 適合届出 手数料"             :usd (/ 2750 163.8)}
   {:n "第三者 EMC 試験 Class B 1機種"    :usd (/ 600000 163.8)}
   {:n "PL 保険"                          :usd (/ 150000 163.8)}
   {:n "engineering（検証・イメージ・文書）" :usd 25000}])

(def gtm-fixed-jpy
  ;; 日本国内 GTM の追加固定費（ASSUMPTION、すべて年額・初年度）。
  [{:n "LP + 節税シミュレータ（DADS ベース、内製）" :jpy  600000}
   {:n "税理士・認定支援機関 向け共同セミナー 6回"   :jpy  900000}
   {:n "実測ベンチ公開・技術資料・撮影"             :jpy  400000}
   {:n "展示会 1本（中小企業／会計事務所向け）"     :jpy 1200000}
   {:n "特商法・契約書面・法務レビュー"             :jpy  800000}])

(def channels
  ;; 1 台あたり獲得コスト（ASSUMPTION）。税理士チャネルは紹介料ではなく
  ;; 共同マーケ費として建てる（税理士側の職業倫理・利益相反に触れないため）。
  [{:k :zeirishi :n "税理士・認定支援機関 提携"  :cac  80000 :share 0.55}
   {:k :direct   :n "直販（LP + シミュレータ）"  :cac  32000 :share 0.30}
   {:k :expo     :n "展示会・紹介"               :cac 150000 :share 0.15}])

(def support-jpy 7000)     ; 2h × 3,500円/h（mk1-costmodel と同じ前提）
(def pay-frac 0.036)       ; 決済手数料

(defn contribution-jpy [price cac]
  (let [cogs-of (fn [p] (some #(when (= p (:price-jpy %)) (yen (:cogs %))) sku-ladder))
        c (cogs-of price)
        below (+ (* price pay-frac) cac support-jpy)]
    {:price price :gross (- price c) :below (Math/round below)
     :contrib (Math/round (- price c below))}))

(defn blended-cac [] (reduce + 0 (map #(* (:cac %) (:share %)) channels)))

;; ── (3) 遊休レーン — 「使っていないとき」はどれだけあるか ──────────────────
;; 売るのは利回りではなく『遊休を出さないこと』なので、測る単位を円から時間に変える。
;; ⚠ **遊休は使い方で決まる。** agent server は 24h 回りうるので、業務時間前提の
;;    77% を全顧客に当てない。使い方の型を先に聞く（ADR-2608153400 決定13 の4問目）。
(def year-hours 8760.0)

(def usage-profiles
  [{:k :business-hours :n "業務時間のみ（8h × 250日）"           :h 2000
    :src "ADR-2607268000 と同じ前提。対話・補助用途"}
   {:k :night-batch    :n "日中 + 夜間バッチ（12h × 250日）"      :h 3000
    :src "ASSUMPTION。日中 8h 対話 + 夜間 4h の一括処理"}
   {:k :agents-extended :n "エージェント常時（16h × 313日）"      :h 5008
    :src "ASSUMPTION。平日夜も回るが週末は止まる"}
   {:k :always-on      :n "24時間365日"                           :h 8760
    :src "遊休ゼロ。参加モデルが成立しない上限ケース"}])

(defn idle-profile
  "使用時間 used-h の顧客の遊休と、そのうち供出できる分。"
  ([tok-s] (idle-profile tok-s 2000))
  ([tok-s used-h]
   (let [idle (- year-hours used-h)]
     {:used-h used-h :idle-h idle :idle-pct (pct (/ idle year-hours))
      ;; 遊休のうち実際に供出できる分（保守・停電・顧客が切る時間を 20% 見る）
      :offer-h (Math/round (* idle 0.8))
      :offer-mtok (Math/round (/ (* tok-s 3600.0 (* idle 0.8)) 1e6))})))

(defn fleet-capacity
  "参加台数 n が fleet に足す容量（Mtok/年）とノード時間。"
  ([n tok-s] (fleet-capacity n tok-s 2000))
  ([n tok-s used-h]
   (let [p (idle-profile tok-s used-h)]
     {:nodes n :mtok-yr (* n (:offer-mtok p)) :node-hours (* n (:offer-h p))})))

;; ── 出力 ────────────────────────────────────────────────────────────────────
;; cljs には clojure.core/format が無い（nbb 実測）。最小の整形子を自前で持つ。
(defn line [] (println (apply str (repeat 78 "-"))))
(defn h [s] (println) (println (str "=== " s)))
(defn rpad [s n] (let [s (str s)] (str s (apply str (repeat (max 0 (- n (count s))) " ")))))
(defn lpad [s n] (let [s (str s)] (str (apply str (repeat (max 0 (- n (count s))) " ")) s)))
(defn dec2 [x] (let [v (/ (Math/round (* 100.0 x)) 100.0)] (str v)))
(defn dec1 [x] (let [v (/ (Math/round (* 10.0 x)) 10.0)] (str v)))
(defn com [n] ;; 3桁区切り
  (let [neg (neg? n) s (str (Math/abs (Math/round n)))
        g (->> (reverse s) (partition-all 3) (map #(apply str (reverse %))) reverse (interpose ",") (apply str))]
    (str (when neg "-") g)))
(defn sign [n] (str (if (neg? n) "" "+") (com n)))

(defn -main []
  (println (str "fx USD/JPY = " fx " (FETCHED-DERIVED 2026-08-15) / ADR 基準 " fx-adr))

  (h "(1) 節税レーン — 閾値と SKU")
  (doseq [t tax-thresholds]
    (println (str (rpad (:n t) 34) " "
                  (rpad (if (:max t) (str "< " (com (:max t)) "円") (str ">= " (com (:min t)) "円")) 14)
                  " 貸付け用: " (if (:lend-out-ok t) "可" "**対象外**"))))
  (line)
  (doseq [s sku-ladder]
    (let [r (sku-row s)]
      (println (str (rpad (:n r) 30)
                    " COGS " (lpad (com (:cogs-jpy r)) 9)
                    "  税抜 " (lpad (com (:price r)) 9)
                    "  税込 " (lpad (com (:inc-tax r)) 9)
                    "  粗利 " (lpad (com (:gm r)) 9) " (" (:gm-pct r) "%)"
                    "  40万以上:" (if (:>=40man r) "○" "×")))))
  (line)
  (println "少額特例(30万円未満)SKU が成立するかの検証 — 価格 299,000円（税抜）:")
  (doseq [[k label] [[:cpu-only "dGPU 無し 32GB（AI サーバとは呼べない）"]
                     [:lite     "Arc B580 12GB 搭載"]]]
    (let [c (yen (k cogs-usd)) gm (- 299000 c)]
      (println (str "  " (rpad label 38) " COGS " (lpad (com c) 8)
                    "  粗利 " (lpad (com gm) 8) " (" (pct (/ gm 299000.0)) "%)"))))
  (println "  => GPU を積んだ瞬間に粗利 1 桁%。**少額特例レンジに AI サーバは作れない。**")

  (h "(1b) 節税効果 — 即時償却 vs 10%税額控除（実効税率 33.6%）")
  (doseq [s sku-ladder]
    (let [t (tax-effect (:price-jpy s))]
      (println (str (rpad (:n s) 30)
                    " 取得 " (lpad (com (:price t)) 9)
                    " | 即時償却 -" (lpad (com (:sokuji-cut t)) 8) " → 実質 " (lpad (com (:sokuji-net t)) 9) "（繰延）"
                    " | 10%控除 -" (lpad (com (:credit-cut t)) 7) " → 実質 " (lpad (com (:credit-net t)) 9) "（永久）"))))
  (println "  ※ 即時償却は 4 年分の損金を初年度に寄せる**繰延**。永久的な減税は 10% 控除側。選択適用。")

  (h "(2) 参加レーン — 余剰供出は電気代を割るか")
  (println (str "spot 参照 = $" (:usd-h spot-ref) "/h @ " (:tok-s spot-ref) " tok/s → "
                (dec1 (spot-yen-mtok)) " 円/Mtok（fx " fx "）"))
  (println (str "有償販売 gate = " gate-tok-s " tok/s（mk1-costmodel）。実測値: " (or measured-tok-s "**未実測**")))
  (line)
  (println "1日8時間 × 年250日 を fleet に供出したときの年間収支（電力 31 円/kWh）:")
  (doseq [t [gate-tok-s 50.0 70.09 100.0]]
    (let [r (supply-row t 8 250 31.0)]
      (println (str "   " (lpad (dec1 (:tok-s r)) 5) " tok/s → " (lpad (com (:mtok-yr r)) 4) " Mtok/年"
                    "  credits " (lpad (com (:credit-yen-yr r)) 8) " 円"
                    "  電気 " (lpad (com (:power-yen-yr r)) 8) " 円"
                    "  差引 " (lpad (sign (:net-yen-yr r)) 9) " 円  ("
                    (:yen-per-mtok r) " 円/Mtok)"))))
  (println "  => gate ちょうど(39.2)では **供出は電気代とほぼ同額**。参加の対価は収入ではない。")
  (line)
  (let [b (selfuse-benefit 31.0)]
    (println "自家利用の置換便益（H100 spot を 8h×250日 借りる代わりに自社機で回す）:")
    (println (str "   回避賃料 " (lpad (com (:rent-avoided b)) 9) " 円/年  −  電気 " (lpad (com (:elec b)) 7)
                  " 円/年  =  " (lpad (com (:net b)) 9) " 円/年"))
    (println "   ⚠ これは *マシン時間* の置換。トークンを 100万単位で買う顧客には成立しない")
    (println "     （トークン単価では gate 39.2 tok/s = spot と同値、つまり削減ゼロ）。")
    (line)
    (doseq [s sku-ladder]
      (let [t (tax-effect (:price-jpy s))]
        (println (str "   " (rpad (:n s) 30) " 実質(即時償却後) " (lpad (com (:sokuji-net t)) 9)
                      " 円 → 回収 " (dec2 (/ (:sokuji-net t) (double (:net b)))) " 年")))))

  (h "(4) GTM レーン — 損益分岐台数")
  (let [fx-fixed (reduce + 0 (map #(* (:usd %) fx) fixed-y1-usd))
        gtm-fixed (reduce + 0 (map :jpy gtm-fixed-jpy))
        total-fixed (+ fx-fixed gtm-fixed)
        bcac (blended-cac)]
    (doseq [r fixed-y1-usd] (println (str "   " (rpad (:n r) 34) (lpad (com (* (:usd r) fx)) 10) " 円")))
    (doseq [r gtm-fixed-jpy] (println (str "   " (rpad (:n r) 34) (lpad (com (:jpy r)) 10) " 円")))
    (println (str "   " (rpad "初年度 固定費 合計" 34) (lpad (com total-fixed) 10) " 円"))
    (line)
    (println "チャネル別 獲得コスト（ASSUMPTION）:")
    (doseq [c channels]
      (println (str "   " (rpad (:n c) 26) " CAC " (lpad (com (:cac c)) 8) " 円  構成比 "
                    (Math/round (* 100 (:share c))) "%")))
    (println (str "   加重平均 CAC = " (com bcac) " 円"))
    (line)
    (doseq [s sku-ladder]
      (let [c (contribution-jpy (:price-jpy s) bcac)]
        (println (str "   " (rpad (:n s) 30) " 粗利 " (lpad (com (:gross c)) 9)
                      " − below " (lpad (com (:below c)) 8)
                      " = 貢献利益 " (lpad (com (:contrib c)) 9) " 円/台"
                      "  → 単独での分岐 " (lpad (dec1 (/ total-fixed (:contrib c))) 5) " 台"))))
    (line)
    (println "3 波の販売計画（決算期従属。Solo 換算の台数と初年度 OP）:")
    (doseq [[wave n note] [["Wave 1  1-3月納品（3月決算）" 12 "税理士 10 事務所と提携、実測レポート付き"]
                           ["Wave 2  9月納品（9月決算・中間）" 10 "Wave 1 の実測を事例化して横展開"]
                           ["Wave 3  翌1-3月納品" 20 "Lite/Ring を含む 3 SKU 体制"]]]
      (let [c (:contrib (contribution-jpy 798000 bcac))]
        (println (str "   " (rpad wave 30) (lpad n 3) " 台  貢献 " (lpad (com (* n c)) 10) " 円   " note))))
    (let [c (:contrib (contribution-jpy 798000 bcac))
          n12 22
          eng (* 25000 fx)                    ; engineering は JP 専用ではない
          jp-only (- total-fixed eng)]
      (println (str "   初年度（Wave 1+2 = " n12 " 台）: 貢献 " (com (* n12 c))
                    " 円 − 固定 " (com total-fixed) " 円 = OP " (sign (- (* n12 c) total-fixed)) " 円"))
      (println "   ⚠ **22 台では初年度の固定費を回収できない。** Solo 単独の分岐は 30.5 台。")
      (line)
      (println "固定費の帰属を分ける（engineering は JP 専用ではなく JP/US/EU 共通、ADR-2607268000 決定4）:")
      (println (str "   engineering（プログラム共通）  " (lpad (com eng) 10) " 円"))
      (println (str "   JP 専用 固定費                " (lpad (com jp-only) 10) " 円"
                    "  → Solo 換算の分岐 " (dec1 (/ jp-only c)) " 台"))
      (println (str "   Wave 1+2（22 台）で JP 専用のみ回収する場合: OP "
                    (sign (- (* n12 c) jp-only)) " 円"))
      (println "   => **engineering を JP 単独で背負うか、地域按分するかで結論が反転する。**")
      (println "      按分するなら 22 台で黒、単独なら 31 台が必要。ここは会計方針の決定であって計算ではない。")))

  (h "(3) 遊休レーン — 遊休は『使い方』で決まる")
  (println "   ⚠ agent server は 24h 回りうる。業務時間前提の 77% を全顧客に当てない。")
  (line)
  (doseq [u usage-profiles]
    (let [p (idle-profile gate-tok-s (:h u))]
      (println (str "   " (rpad (:n u) 30)
                    " 使用 " (lpad (com (:used-h p)) 5) "h"
                    "  遊休 " (lpad (com (:idle-h p)) 5) "h (" (lpad (:idle-pct p) 5) "%)"
                    "  供出可 " (lpad (com (:offer-h p)) 5) "h"
                    "  = " (lpad (com (:offer-mtok p)) 4) " Mtok/年"))))
  (line)
  (println "参加台数が fleet に足す容量（業務時間型 = 供出可 5,408h/台 のとき）:")
  (doseq [n [22 42 100 500]]
    (let [c (fleet-capacity n gate-tok-s)]
      (println (str "   " (lpad n 4) " 台 → " (lpad (com (:mtok-yr c)) 8) " Mtok/年   "
                    (lpad (com (:node-hours c)) 9) " ノード時間/年"))))
  (println "   ⚠ 顧客側の取り分は年 −549 円（(2) 参照）。**これは収入の話ではなく容量の話。**")

  (h "感度: fx を ADR 基準 163.8 に戻した場合の COGS")
  (doseq [s sku-ladder]
    (println (str "   " (rpad (:n s) 30) " COGS " (lpad (com (yen (:cogs s))) 8) " 円（fx " fx "）→ "
                  (lpad (com (* (:cogs s) fx-adr)) 8) " 円（fx " fx-adr "）")))
  (println)
  (println "未検証（この計算器が答えていないもの）:")
  (println "  ① MK-1 の実測 decode tok/s — (2)(3) の前提。ADR-2607268000 決定10。")
  (println "  ② agent 同時実行数と time-to-first-token — gate は decode tok/s しか見ていない。")
  (println "  ③ Arc B580 の調達価格 — MK-1 Lite の COGS 前提。")
  (println "  ④ 防衛特別法人税を織り込んだ実効税率 — 33.6% は 2026-07 時点の一般値。")
  (println "  ⑤ 補助金（ものづくり/IT導入）と税額控除の重複適用可否。"))

(-main)
