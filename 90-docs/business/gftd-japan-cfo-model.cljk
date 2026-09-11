(ns cfo-analysis
  "gftdcojp の実測データに Valueflows algorithms（cash-flow / critical-path）と
   OASIS XMILE（org-oasis-open-xmile 実行系）を当てる。

   捏造しない: 金額は m365-archive の invoice-terms.edn 由来のみ。
   未計測のものは quantity nil のまま渡し、ライブラリに :unmeasured として
   名指しさせる（ADR-2608136000 の evidence floor と同じ規律）。"
  (:require [valueflows.algorithms.cash-flow :as cf]
            [valueflows.algorithms.critical-path :as cp]
            [xmile.model :as xm]
            [xmile.execute :as xe]
            [xmile.xml :as xmlio]))

(def USDJPY 150) ;; 表示用の宣言レート。実勢ではない（明示の仮定）

;; 2025-01 を 0 とした月インデックス
(defn m [y mo] (+ (* 12 (- y 2025)) (dec mo)))
(defn label [p] (str (+ 2025 (quot p 12)) "-" (let [x (inc (rem p 12))] (if (< x 10) (str "0" x) x))))

(defn jpy [n] {:has-numerical-value n :has-unit :jpy})
(def GJ :gftd-japan)

;; ---------------------------------------------------------------------------
;; 1. commitments — archive の請求書（発行=入金予定、受領=支払予定）
;;    events は空。**確定した入出金の記録をこのワークスペースは持っていない。**
;; ---------------------------------------------------------------------------

(def ar ;; 我々が発行した請求書 = 受取（provider=顧客, receiver=GJ）
  [{:provider :riken       :receiver GJ :has-point-in-time (m 2025 3)  :resource-conforms-to :jpy :resource-quantity (jpy 990000)}
   {:provider :kagoshima-u :receiver GJ :has-point-in-time (m 2025 3)  :resource-conforms-to :jpy :resource-quantity (jpy 1309000)}
   {:provider :tbs         :receiver GJ :has-point-in-time (m 2025 3)  :resource-conforms-to :jpy :resource-quantity (jpy 16500)}
   {:provider :tbs         :receiver GJ :has-point-in-time (m 2025 7)  :resource-conforms-to :jpy :resource-quantity (jpy 22000)}
   {:provider :sakishima   :receiver GJ :has-point-in-time (m 2025 10) :resource-conforms-to :jpy :resource-quantity (jpy 5280000)}
   {:provider :limitx      :receiver GJ :has-point-in-time (m 2025 12) :resource-conforms-to :jpy :resource-quantity (jpy 1100000)}])

(defn ap-row [vendor p amount] {:provider GJ :receiver vendor :has-point-in-time p
                                :resource-conforms-to :jpy :resource-quantity (jpy amount)})

(def ap-recorded ;; 金額が archive に残っている支払側請求書
  (concat
   ;; HubSpot — 2025-01〜2026-06 の実額（CRM Suite + Marketing Hub、後期は統合プラン）
   (for [[p amt] [[(m 2025 1) 11732] [(m 2025 2) 11732] [(m 2025 3) 11732] [(m 2025 4) 11732]
                  [(m 2025 5) 11732] [(m 2025 6) 11732] [(m 2025 7) 11732] [(m 2025 8) 11732]
                  [(m 2025 10) 5132] [(m 2025 11) 4429] [(m 2026 1) 11730] [(m 2026 2) 11730]
                  [(m 2026 3) 11730] [(m 2026 4) 11730] [(m 2026 5) 11730] [(m 2026 6) 11730]]]
     (ap-row :hubspot p amt))
   [(ap-row :jc3 (m 2025 2) 250000)          ;; 第11期特定会員会費（1-2月）due 2025-02-28
    (ap-row :jc3 (m 2025 4) 250000)          ;; 同（3-4月）due 2025-04-30
    (ap-row :mf-kessai (m 2025 1) 200662)    ;; 2024年12月分 掛け払いまとめ
    (ap-row :mf-kessai (m 2025 2) 125420)    ;; 2025年1月分
    (ap-row :justco (m 2025 2) 154000)       ;; イベント会場費
    (ap-row :legalbright (m 2025 8) 55000)   ;; 求人広告 due 2025-08-31
    (ap-row :anthropic (m 2026 4) (* USDJPY 4560.51))  ;; API 2026年3月分 due 2026-04-15
    (ap-row :anthropic (m 2026 3) (* USDJPY 200))      ;; Max 20x
    (ap-row :anthropic (m 2026 5) (* USDJPY 220))
    (ap-row :neo4j (m 2025 11) (* USDJPY 21.15))
    (ap-row :neo4j (m 2025 12) (* USDJPY 122.74))
    (ap-row :vercel (m 2025 10) (* USDJPY 20))
    (ap-row :vercel (m 2025 11) (* USDJPY 20))
    (ap-row :camunda (m 2025 5) (* USDJPY 99))
    (ap-row :camunda (m 2025 6) (* USDJPY 99))
    (ap-row :camunda (m 2025 7) (* USDJPY 99))
    (ap-row :camunda (m 2025 8) (* USDJPY 99))
    (ap-row :resend (m 2025 9) (* USDJPY 20))]))

(def payroll-unmeasured
  "給与明細は 2025-01〜2026-03 支給分まで毎月発行されている（MF クラウド給与の
   発行通知で確認）。**金額は archive に無い。** quantity を渡さず、ライブラリに
   :unmeasured として数えさせる。ゼロとして扱わない。"
  (for [p (range (m 2025 1) (inc (m 2026 3)))]
    {:provider GJ :receiver :staff :has-point-in-time p :resource-conforms-to :jpy}))

;; ---------------------------------------------------------------------------

(defn show-timeline [title tl]
  (println (str "\n### " title))
  (if-not (:ok? tl)
    (println "  REFUSED:" (:insufficient tl) (pr-str (:detail tl)))
    (do
      (println (str "  期間数 " (:periods-with-flow tl)
                    " / 計上 " (:counted tl)
                    " / complete? " (:complete? tl)
                    " / 未計測 " (count (:unmeasured tl))))
      (println "  月      | 予定 in    | 予定 out   | 純額       | 累計")
      (doseq [r (:rows tl)]
        (println (str "  " (label (:period r))
                      " | " (.padStart (str (Math/round (get-in r [:forecast :in]))) 10)
                      " | " (.padStart (str (Math/round (get-in r [:forecast :out]))) 10)
                      " | " (.padStart (str (Math/round (:net r))) 10)
                      " | " (.padStart (str (Math/round (:cumulative r))) 11))))
      (println "  観測(events) 合計:" (:observed-total tl) " / 予定(commitments) 合計:" (Math/round (:forecast-total tl)))
      (println "  期末残:" (Math/round (:closing-balance tl))))))

(println "==========================================================")
(println " A. Valueflows cash-flow — 誰の帳簿かを指定した資金繰り")
(println "==========================================================")

(def tl-no-payroll
  (cf/timeline {:events [] :commitments (concat ar ap-recorded)}
               {:agent GJ :resource :jpy}))
(show-timeline "A-1 給与を除いた記録だけ（＝archive に金額が在るものだけ）" tl-no-payroll)
(println "  runway:" (pr-str (cf/runway tl-no-payroll)))

(def tl-with-payroll
  (cf/timeline {:events [] :commitments (concat ar ap-recorded payroll-unmeasured)}
               {:agent GJ :resource :jpy}))
(show-timeline "A-2 給与（金額未計測）を入れた場合" tl-with-payroll)
(println "  runway:" (pr-str (cf/runway tl-with-payroll)))
(println "  未計測として名指しされた件数:" (count (:unmeasured tl-with-payroll)))

(println "\n  観測列が空であることの意味:")
(println "  events=[] は「入出金が無かった」ではなく「確定した入出金の記録を我々が")
(println "  持っていない」。上の全額は commitments（約束）であって事実ではない。")

;; ---------------------------------------------------------------------------
(println "\n==========================================================")
(println " B. Valueflows critical-path — 立て直しの順序を計算で出す")
(println "==========================================================")

(defn res [r] {:resource-conforms-to r :action :consume :quantity {:has-numerical-value 1 :has-unit :each}})
(defn out [r] {:resource-conforms-to r :action :produce :quantity {:has-numerical-value 1 :has-unit :each}})

(def recovery
  {:recipe/processes
   [{:id :refresh-archive        :duration 1  :inputs []                          :outputs [(out :current-facts)]}
    {:id :measure-wage-arrears   :duration 1  :inputs [(res :current-facts)]      :outputs [(out :wage-figure)]}
    {:id :check-ar-settlement    :duration 1  :inputs [(res :current-facts)]      :outputs [(out :ar-status)]}
    {:id :check-roudou-case      :duration 1  :inputs [(res :current-facts)]      :outputs [(out :case-status)]}
    {:id :collect-ar             :duration 20 :inputs [(res :ar-status)]          :outputs [(out :cash)]}
    {:id :pay-wages              :duration 1  :inputs [(res :wage-figure) (res :cash) (res :case-status)] :outputs [(out :wage-cleared)]}
    {:id :saas-inventory         :duration 2  :inputs [(res :current-facts)]      :outputs [(out :saas-list)]}
    {:id :cancel-saas            :duration 3  :inputs [(res :saas-list)]          :outputs [(out :opex-cut)]}
    {:id :justco-decide          :duration 3  :inputs [(res :current-facts)]      :outputs [(out :office-decision)]}
    {:id :justco-execute         :duration 30 :inputs [(res :office-decision)]    :outputs [(out :office-cleared)]}
    {:id :card-topup             :duration 1  :inputs [(res :cash) (res :opex-cut)] :outputs [(out :credit-restored)]}
    {:id :reopen-sales           :duration 10 :inputs [(res :credit-restored) (res :wage-cleared) (res :office-cleared)] :outputs [(out :invoice-2026)]}]})

(def sched (cp/schedule recovery {}))
(if-not (:ok? sched)
  (println "  REFUSED:" (:insufficient sched) (pr-str (:detail sched)))
  (do
    (println "  プロジェクト全体の所要:" (:project-duration sched) "日")
    (println "  クリティカルパス:" (pr-str (:critical-path sched)))
    (println "  ボトルネック(後続を複数待たせる critical 工程):" (pr-str (:bottlenecks sched)))
    (println "\n  工程                     | 開始 | 終了 | 余裕 |")
    (doseq [[id n] (sort-by (comp :es val) (:nodes sched))]
      (println (str "  " (.padEnd (str id) 24)
                    " | " (.padStart (str (:es n)) 4)
                    " | " (.padStart (str (:ef n)) 4)
                    " | " (.padStart (str (:slack n)) 4)
                    " | " (if (:critical? n) "← critical path" ""))))))

;; ---------------------------------------------------------------------------
(println "\n==========================================================")
(println " C. XMILE — 信用侵食ループ（org-oasis-open-xmile で実行）")
(println "==========================================================")

(defn erosion-model
  "月次。3 stock:
     Cash            現金（初期値は未計測なので scenario パラメータ）
     VendorCredit    ベンダ信用（1.0 = 全サービス生存、0 = 全停止）
     Capacity        納品能力（1.0 = 2025年10月時点、0 = 請求書を出せない）

   測定済み: Opex_Recorded（archive の請求書実額の月平均）、
             Invoice_2025（2025年の発行額）
   仮定（scenario）: 侵食係数・回復係数・初期現金。**測っていない。**"
  [{:keys [name months cash0 collect opex payroll erosion recovery-rate]}]
  (-> (xm/model name {:xmile/sim-specs (xm/sim-specs 0.0 (double months) {:xmile/dt 0.25 :xmile/method :rk4})})
      (xm/add-variable (xm/aux "Opex" (str (double opex))))
      (xm/add-variable (xm/aux "Payroll" (str (double payroll))))
      (xm/add-variable (xm/aux "Erosion_K" (str (double erosion))))
      (xm/add-variable (xm/aux "Recovery_K" (str (double recovery-rate))))
      (xm/add-variable (xm/aux "Collect" (str (double collect))))
      ;; 支払えているか = 現金が当月の支出を賄えるか（0..1 の連続近似）
      (xm/add-variable (xm/aux "Solvency" "MAX(0, MIN(1, Cash / (Opex + Payroll + 1)))"))
      (xm/add-variable (xm/flow "Inflow"  "Collect * Capacity"))
      (xm/add-variable (xm/flow "Outflow" "Opex * VendorCredit + Payroll"))
      (xm/add-variable (xm/flow "Credit_Loss" "Erosion_K * (1 - Solvency) * VendorCredit"))
      (xm/add-variable (xm/flow "Credit_Gain" "Recovery_K * Solvency * (1 - VendorCredit)"))
      (xm/add-variable (xm/flow "Capacity_Loss" "Erosion_K * (1 - Solvency) * Capacity"))
      (xm/add-variable (xm/flow "Capacity_Gain" "Recovery_K * Solvency * VendorCredit * (1 - Capacity)"))
      (xm/add-variable (xm/stock "Cash" (str (double cash0)) {:xmile/inflows #{"Inflow"} :xmile/outflows #{"Outflow"}}))
      (xm/add-variable (xm/stock "VendorCredit" "1.0" {:xmile/inflows #{"Credit_Gain"} :xmile/outflows #{"Credit_Loss"}}))
      (xm/add-variable (xm/stock "Capacity" "1.0" {:xmile/inflows #{"Capacity_Gain"} :xmile/outflows #{"Capacity_Loss"}}))))

(defn run-scn [label params]
  (let [r (xe/run (erosion-model (assoc params :name label)))
        ts (:xmile/times r) s (:xmile/series r)
        at (fn [nm i] (nth (get s nm) i))
        idx (fn [mo] (min (dec (count ts)) (int (/ mo 0.25))))]
    (println (str "\n### " label))
    (println "  月 |       Cash | VendorCredit | Capacity")
    (doseq [mo [0 3 6 9 12 18 24]]
      (let [i (idx mo)]
        (println (str "  " (.padStart (str mo) 2)
                      " | " (.padStart (str (Math/round (at "Cash" i))) 10)
                      " | " (.padStart (.toFixed (at "VendorCredit" i) 3) 12)
                      " | " (.padStart (.toFixed (at "Capacity" i) 3) 8)))))
    {:label label :cash-24 (at "Cash" (idx 24)) :cap-24 (at "Capacity" (idx 24))}))

;; 測定済みの入力
;;   opex: ap-recorded の月平均（archive に金額が在る分だけ = 下界）
(def opex-measured
  (let [tot (reduce + 0 (map #(get-in % [:resource-quantity :has-numerical-value]) ap-recorded))
        span (inc (- (m 2026 6) (m 2025 1)))]
    (/ tot span)))
;;   collect: 2025 年の発行額 ÷ 12（＝2025 年ペースを維持できた場合の月次）
(def invoice-2025 (reduce + 0 (map #(get-in % [:resource-quantity :has-numerical-value]) ar)))

(println (str "\n  測定済み入力"))
(println (str "   archive 記録の opex 月平均 = " (Math/round opex-measured) " 円/月（請求書が見つかった分だけ = 下界）"))
(println (str "   2025年 発行請求（文書から）= " invoice-2025 " 円 → 月あたり " (Math/round (/ invoice-2025 12))))
(println "   2025年3月 試算表（Gftd Japan 単体、confidence medium）:")
(println "     現金 69,740 / 総資産 95,635,017 / 純資産 -23,613,473 → 負債 ≈ 119,248,490")
(println "     売上 6,947,752 / 営業損失 -9,070,236")
(println "   2024年1-6月 月次推移（confidence high）: GJ+GW 売上 47,615,459")
(println "\n  仮定（未測定・scenario）: 給与 月100万 / 侵食係数 0.25 / 回復係数 0.35")
(println "  ※ 初期現金はもう仮定ではない —— 2025年3月の実測 69,740 を使う。")

(def CASH0 69740)
(def base {:months 24 :cash0 CASH0 :opex opex-measured :payroll 1000000
           :erosion 0.25 :recovery-rate 0.35 :collect (/ invoice-2025 12)})

(def r1 (run-scn "C-1 現状維持（2025年ペースの請求が続く前提）" base))
(def r2 (run-scn "C-2 請求が止まった（2026年の実態: 発行 0 件）" (assoc base :collect 0)))
(def r3 (run-scn "C-3 介入（売掛868万を回収 + opex 半減）"
                 (assoc base :cash0 (+ CASH0 8679000) :opex (* 0.5 opex-measured))))

(println "\n  24ヶ月後の比較")
(doseq [r [r1 r2 r3]]
  (println (str "   " (.padEnd (:label r) 46)
                " Cash " (.padStart (str (Math/round (:cash-24 r))) 12)
                "  Capacity " (.toFixed (:cap-24 r) 3))))

(println "\n==========================================================")
(println " D. 損益分岐 — 人件費はいくらまで許されるか")
(println "==========================================================")
(println "  月次の収支 = 請求ペース - opex - 人件費。opex は archive 記録分（下界）。")
(doseq [[nm collect opex] [["2025年ペース維持 / opex 現状" (/ invoice-2025 12) opex-measured]
                           ["2025年ペース維持 / opex 半減" (/ invoice-2025 12) (* 0.5 opex-measured)]
                           ["2024年H1ペース(GJ+GW) / opex 現状" (/ 47615459 6) opex-measured]
                           ["2026年の実態(請求ゼロ)" 0 opex-measured]]]
  (println (str "   " (.padEnd nm 36) " → 人件費の上限 "
                (.padStart (.toLocaleString (js/Number (Math/round (- collect opex))) "ja-JP") 12) " 円/月")))
(println "\n  ※ この上限は「現金が減らない」水準であって、負債 1.19 億の返済原資は含まない。")

(println "\n==========================================================")
(println " 完了。測定済みと仮定を混ぜていない。")
(println "==========================================================")

;; --- .xmile として書き出す（Stella / Vensim で開ける OASIS XMILE 1.0）---
(let [xx (js/require "fs")
      doc {:xmile/header {:xmile/vendor "kotoba-lang/org-oasis-open-xmile"
                          :xmile/product "gftd-japan-cfo-model"
                          :xmile/name "Gftd Japan credit-erosion loop"}
           :xmile/sim-specs (xm/sim-specs 0.0 24.0 {:xmile/dt 0.25 :xmile/method :rk4
                                                     :xmile/time-units "month"})
           :xmile/models [(erosion-model (assoc base :name "credit_erosion"))]}]
  (.writeFileSync xx "90-docs/business/gftd-japan-credit-erosion.xmile" (xmlio/emit-string doc))
  (println "\n  .xmile を書き出した: 90-docs/business/gftd-japan-credit-erosion.xmile"))
