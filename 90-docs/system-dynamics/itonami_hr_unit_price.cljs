(ns itonami-hr-unit-price
  "cloud-itonami `/isco-1212/`（kaonavi 型 HR 管理）の従量単価を XMILE stock-flow で解く。

   ★ 何を解いているか: `/isco-1212/` は『HR-advisor proposal 1 件あたり』と
     『保管 1 GiB・月あたり』で課金すると宣言しているが、**単価がどこにも公開されて
     いない**（2026-08-08 実測、ADR-2608080300）。ここで解くのはその 2 つの単価。

   ★ 構造が murakumo（90-docs/system-dynamics/pricing.cljs）と違う点:
     murakumo は plan 制で『価格を下げても売上は増えず原価だけ増える』。
     こちらは**純従量**なので価格が売上に直接効く。代わりに競合が per-seat
     （従業員数課金）なので、**請求の伸び方の軸が違う** —— itonami は proposal 数で
     伸び、競合は頭数で伸びる。両者の交点は『従業員 1 人あたり月何件 proposal を
     出すか』(r) で決まり、**この r がこのモデルで最も効く未計測の 1 数値**。

   ループ:
     R1 (+) 低単価 → 競合比の請求額↓ → 価値比↑ → 到達市場↑ → テナント↑ → 売上↑
     B1 (−) 低単価 → テナントあたり寄与↓ → 限界費用へ漸近
     B2 (−) 利用が増える → 請求額↑ → 価値比↓ → 到達市場↓
            （per-seat 競合には無い自己抑制。従量課金に固有）"
  (:require [xmile.model :as m]
            [xmile.execute :as execute]))

;; ---- MEASURED（2026-08-08。出典付き。推定値を混ぜない）----------------------

(def jpy-per-usd 150.0)   ; 換算レート。ledger の既存 entry と同じ ~¥150/$ を踏襲

(def competitor
  "日本の HR 管理 SaaS の実価格。2026-08-08 に取得。
   ★ 4 社中 2 社は価格を 1 円も公開していない（sales-gated）。これは
     pricing-intelligence-ledger が他 vertical でも繰り返し記録している不透明パターン。"
  {:jinjer        {:jpy-per-employee-month 800.0
                   :tier :published
                   :note "人事労務管理 ¥800/ユーザー/月、最低 12ヵ月、初期費用 要問合せ。機能制限版 ¥500/名。公式サイトは ¥300/人/月〜 と表示"
                   :source "https://saas.imitsu.jp/cate-labor-management/service/1016/price（2025-08-25 更新）"}
   :jinjer-limited {:jpy-per-employee-month 500.0 :tier :published :note "機能制限時" :source "同上"}
   :kaonavi       {:jpy-per-employee-month nil
                   :tier :opaque
                   :note "価格を 1 円も公開していない。『登録人数によって毎月の金額が変わります』として見積フォームへ誘導"
                   :source "https://www.kaonavi.jp/price/（2026-08-08 取得）"}
   :hrbrain       {:jpy-per-employee-month nil
                   :tier :opaque
                   :note "同じく非公開。『ご利用人数に合わせて金額が変動します』のみ"
                   :source "https://www.hrbrain.jp/price（2026-08-08 取得）"}
   :smarthr       {:jpy-per-employee-month nil
                   :tier :ambiguous
                   :note "3 プランとも要見積もり。集約サイトに ¥5,980/月 / ¥59,760/年 の記載があるが『従業員1名あたり』が総額か単価か読めない。読み方を選ばず ambiguous として扱う"
                   :source "https://www.itreview.jp/products/smarthr/price ほか（2026-08-08 検索）"}})

;; 比較の基準に使うのは、**公開されている中で最も高い実価格** = jinjer ¥800/人/月。
;; opaque な 2 社を『たぶんもっと高い』と仮定して基準を上げない（それは捏造になる）。
(def competitor-jpy-per-employee-month 800.0)

(def marginal
  "限界費用。両方とも公開価格に基づく。"
  {:proposal-jpy (* 0.01 jpy-per-usd)     ; murakumo /x402/v1/messages $0.01/message
   :proposal-src "murakumo .well-known/x402 → /x402/v1/messages $0.01（自 fleet の公開価格）"
   :storage-jpy-per-gib-month (* 0.015 jpy-per-usd)  ; Cloudflare R2 Standard
   :storage-src "https://developers.cloudflare.com/r2/pricing/ → $0.015/GB-month（2026-08-08 取得）"})

(def stripe-fee 0.036)  ; Stripe JP の標準カード手数料

(def observed-usage
  "現行テナントの実利用。★ agent run は HR-advisor proposal と同一ではないので
   上界の代理として使う（proposal は agent run の部分集合）。"
  {:agent-runs-7d 306 :tenants 5 :as-of "2026-08-08"
   :per-tenant-month (/ (* 306.0 (/ 30.0 7.0)) 5.0)   ; ≈ 262
   :trend "2,173 (07-30) → 984 (07-31) → 306 (08-08)。減少中、原因は未説明"})

;; ---- SCENARIO（未計測。実測値として引用してはならない）----------------------

;; TAM（MEASURED、上限として使う）: 日本の中小企業 3,364,891 社
;; 出典 2024年版中小企業白書（全企業の 99.7%）。https://www.chusho.meti.go.jp/koukai/chousa/chu_kigyocnt/
;; ★ これは「上限」であって到達可能市場ではない。大半は数名規模で HR SaaS の対象外。
(def japan-sme-count 3364891.0)

(def sc
  {:p 0.008 :q 0.35            ; Bass 係数 — cloud-itonami では未計測（conversion 0/5）
   :addressable-frac 0.01      ; TAM のうち 100 名規模で HR SaaS を買いうる割合。SCENARIO
   :employees-per-tenant 100.0 ; 対象 SMB の従業員数
   :gib-per-employee 0.05      ; 1 従業員あたり保管量（人事記録）
   :half-sat 1.0               ; 価値比がこの値で到達可能市場の半分。1.0 = 競合と同額
   :elasticity 1.5})

;; ★ このモデルで最も効く未計測の 1 数値。
(def proposals-per-employee-month-scenarios [0.5 1.0 2.0 5.0 10.0])

;; ---- モデル -----------------------------------------------------------------

(defn build
  [{:keys [p-proposal p-storage r elasticity days demand-model]}]
  (let [{:keys [p q addressable-frac half-sat employees-per-tenant gib-per-employee]} sc
        addressable (* japan-sme-count addressable-frac)
        emp employees-per-tenant
        proposals-month (* emp r)
        gib (* emp gib-per-employee)
        bill-month (+ (* p-proposal proposals-month) (* p-storage gib))
        comp-month (* competitor-jpy-per-employee-month emp)
        cost-month (+ (* (:proposal-jpy marginal) proposals-month)
                      (* (:storage-jpy-per-gib-month marginal) gib))
        value-ratio (/ comp-month (max bill-month 1.0))]
    (-> (m/model "itonami-hr-unit-price")
        (m/set-sim-specs (m/sim-specs 0 days {:xmile/dt 1.0 :xmile/method :euler}))
        ;; ★ 飽和する応答（Hill 型）。到達市場は addressable を超えない。
        ;; 前版は base * vr^1.5 という上限の無い冪で、vr=40 のとき 759,000 テナント
        ;; ・2 年寄与 ¥334 億という発散解を出した（= 常に最安値が最適という退化）。
        ;; 「競合の 40 分の 1 の価格なら市場が 253 倍になる」は構造として偽。
        (m/add-variable (m/aux "value_ratio" (str value-ratio)))
        (m/add-variable
         (m/aux "market_size"
                (if (= demand-model :power)
                  ;; 上限の無い冪。前版はこれで vr=40 → 759,000 テナント・¥334 億という
                  ;; 発散解を出した。残してあるのは「同じ実測値から反対の答えが出る」ことを
                  ;; 示すため。単独で出荷してはならない。
                  (str 3000.0 " * (value_ratio) ^ " elasticity)
                  ;; 飽和（Hill）。到達市場は addressable を超えない。
                  (str addressable " * (value_ratio ^ " elasticity ")"
                       " / (value_ratio ^ " elasticity " + "
                       (js/Math.pow half-sat elasticity) ")"))))
        (m/add-variable (m/stock "Tenants" "0" {:xmile/inflows #{"adoption"}}))
        (m/add-variable (m/flow "adoption"
                                (str "(" p " + " q " * Tenants / market_size)"
                                     " * MAX(0, market_size - Tenants)")))
        (m/add-variable (m/aux "bill_month" (str bill-month)))
        (m/add-variable (m/aux "cost_month" (str cost-month)))
        (m/add-variable (m/aux "revenue_day" (str "Tenants * " (/ bill-month 30.0))))
        (m/add-variable (m/aux "cogs_day" (str "Tenants * " (/ cost-month 30.0))))
        (m/add-variable (m/flow "contribution"
                                (str "revenue_day * (1 - " stripe-fee ") - cogs_day")))
        (m/add-variable (m/stock "Cum_Contribution" "0"
                                 {:xmile/inflows #{"contribution"}})))))

(defn- lastv [s k] (peek (get s k)))

(defn run-one [opts]
  (let [{:xmile/keys [series]} (execute/run (build opts))
        rev (lastv series "revenue_day")
        con (lastv series "contribution")]
    {:p-proposal (:p-proposal opts)
     :r (:r opts)
     :tenants (lastv series "Tenants")
     :value-ratio (lastv series "value_ratio")
     :bill-month (lastv series "bill_month")
     :margin (if (pos? rev) (/ con rev) 0.0)
     :cum (lastv series "Cum_Contribution")}))

(defn fmt [x] (/ (js/Math.round (* 10 x)) 10))
(defn yen [x] (str "¥" (js/Math.round x)))
(defn pct [x] (str (/ (js/Math.round (* 1000 x)) 10) "%"))

(def prices [5 10 20 40 80 160 320 640])
(def days 730)

;; ---- 代替境界（★ 未計測の需要曲線を一切使わず、実測値だけで解ける部分）--------

(defn substitution-ceiling
  "同じ組織にとって itonami の請求が競合と同額になる proposal 単価。
   実測値（競合の公開価格・R2・従業員あたり保管量）だけで決まり、
   Bass 係数も弾力性も需要曲線も使わない。**ここが答えの硬い部分。**"
  [r p-storage]
  (let [storage-per-emp (* p-storage (:gib-per-employee sc))]
    (/ (- competitor-jpy-per-employee-month storage-per-emp) r)))

(defn boundary-report []
  (println "\n=== 代替境界（実測のみ。需要曲線を使わない）===")
  (println (str "  競合 ¥" competitor-jpy-per-employee-month "/従業員/月（jinjer 公開価格）"))
  (println (str "  限界費用 " (yen (:proposal-jpy marginal)) "/proposal（murakumo x402 $0.01）"))
  (println "\n  r（proposal/従業員/月）\t同額になる ¥/proposal\t限界費用の何倍か")
  (doseq [r proposals-per-employee-month-scenarios]
    (let [c (substitution-ceiling r 300.0)]
      (println (str "  r=" r "\t\t\t" (yen c) "\t\t\t"
                    (fmt (/ c (:proposal-jpy marginal))) "x"))))
  (let [cs (mapv #(substitution-ceiling % 300.0) proposals-per-employee-month-scenarios)]
    (println (str "\n  ★ 境界の散らばり " (yen (apply min cs)) " 〜 " (yen (apply max cs))
                  "（" (fmt (/ (apply max cs) (apply min cs)))
                  " 倍）— すべて未計測の r だけで動く"))))

(defn best-for [r demand-model]
  (let [rs (mapv #(run-one {:p-proposal % :p-storage 300.0 :r r
                            :elasticity (:elasticity sc) :days days
                            :demand-model demand-model}) prices)]
    (first (sort-by :cum > rs))))

(defn identifiability-report []
  (println "\n=== 動的最適値は同定できるか — 同じ実測値に 2 つの需要曲線を当てる ===")
  (println "  どちらの曲線も測定されていない。片方は上限の無い冪、もう片方は飽和(Hill)。")
  (println "\n  r\t冪モデルの最適\tHill モデルの最適\t食い違い")
  (let [rows (mapv (fn [r]
                     (let [a (best-for r :power) b (best-for r :hill)]
                       {:r r :a (:p-proposal a) :b (:p-proposal b)}))
                   proposals-per-employee-month-scenarios)]
    (doseq [x rows]
      (println (str "  r=" (:r x) "\t" (yen (:a x)) "\t\t" (yen (:b x))
                    "\t\t" (fmt (/ (max (:a x) (:b x)) (min (:a x) (:b x)))) " 倍")))
    (println "\n  ★ どちらも sweep の端点に張り付く（内点最適が存在しない）。")
    (println "    需要曲線の形だけで答えが反転する以上、**このモデルは価格を選べない。**")
    (println "    選べるのは境界だけであり、内側のどこを取るかは測定が要る。")
    rows))

(defn -main []
  (boundary-report)
  (println (str "\ncloud-itonami /isco-1212/ 従量単価 — XMILE stock-flow"
                "（org-oasis-open-xmile、Euler dt=1、730 日）"))
  (println (str "競合基準: jinjer ¥" competitor-jpy-per-employee-month
                "/人/月（公開）。kaonavi / HRBrain は価格非公開、SmartHR は読解不能"))
  (println (str "限界費用: proposal " (yen (:proposal-jpy marginal))
                "（murakumo x402 $0.01）、保管 "
                (yen (:storage-jpy-per-gib-month marginal)) "/GiB\u00b7月（R2 $0.015）"))
  (println (str "TAM 上限: 日本の中小企業 " (js/Math.round japan-sme-count)
                " 社（2024年版中小企業白書）× addressable " (:addressable-frac sc)
                " = " (js/Math.round (* japan-sme-count (:addressable-frac sc))) " 社（SCENARIO）"))
  (identifiability-report)
  (println "\n=== 結論 ===")
  (println (str "  硬い部分（実測のみ）: 単価は [" (yen (:proposal-jpy marginal))
                ", " (yen (substitution-ceiling 1.0 300.0)) "/r] に入る。"))
  (println "  柔らかい部分（未計測）: その内側のどこか。需要曲線が要り、需要曲線には")
  (println "    最低 1 件の conversion が要る。現在の実測は 0/5。")
  (println (str "  ★ 測るべき 1 数値: r = 従業員 1 人あたり月間 proposal 数。"
                "これだけで境界が 20 倍動く。")))

(-main)
