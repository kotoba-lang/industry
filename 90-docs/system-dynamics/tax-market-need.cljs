(ns tax-market-need
  "MK-1（叢雲）を『節税資産』として売るときの、地域別の実マーケットニーズ。

   問い（オーナー、2026-08-18）: 日本の節税ニーズを 1 としたとき各国は? そして
   『節税の行いやすさ』と『実際に AI を使う企業がどれだけ居るか』を計算に入れる。

   出力は OASIS XMILE 1.0（kotoba-lang/org-oasis-open-xmile）。Bass 拡散を国ごとに
   1 本ずつ持ち、税制レバーを外部係数 p に、決算期末を PULSE に入れる。

   ■ この模型が答えられること / 答えられないこと
   絶対台数は答えられない。murakumo の実測 funnel は訪問 549-728 → run 200 → **paid 0**
   （90-docs/system-dynamics/business.cljs）で、転換率には点推定が存在せず上界しかない。
   したがって p_base は『rule of three による上界』であって推定値ではなく、
   serviceable / q_imitation と同じく **全国共通** に置いてある。共通パラメータは
   国どうしの比では約分されるので、**答えられるのは順位と比だけ**。

   ■ 実測（出典・日付つき）と 仮定 を混ぜない
   各変数の <doc> に MEASURED / PROXY / ASSUMPTION を書いた。XMILE に埋めてあるので
   ファイル単体で出所が読める。最大の弱点は 2 つ、下の WARNING で毎回印字する。"
  (:require [xmile.model :as m]
            [xmile.validate :as v]
            [xmile.execute :as x]
            [xmile.xml :as xml]
            [dynamics.core :as d]
            [clojure.string :as str]
            ["fs" :as fs]))

(defn r [n x] (let [k (js/Math.pow 10 n)] (/ (js/Math.round (* k x)) k)))

;; ─────────────────────────────────────────────────────────────────────────────
;; 1. 共通パラメータ（全国同一 → 国どうしの比では約分される）
;; ─────────────────────────────────────────────────────────────────────────────

;; 実測 0 件からの上界。点推定ではない（dynamics.core/upper-bound-rate-from-zero-events）。
(def p-base-upper (d/upper-bound-rate-from-zero-events 200))
(def q-imitation 0.03)   ; ASSUMPTION: Bass 模倣係数の文献既定値(年 0.3-0.5)の月換算
(def serviceable 0.02)   ; ASSUMPTION: AI 利用企業のうち $5k のオンプレ箱を買いうる割合

;; ─────────────────────────────────────────────────────────────────────────────
;; 2. 国別の入力
;; ─────────────────────────────────────────────────────────────────────────────
;;
;; benefit  : 初年度税効果 / 日本(274,634円) = 1.00   ← 前段で税率から計算
;; ease     : 節税の行いやすさ。事前申請が要るほど下がる = 実際に控除に到達する確率
;; salience : その制度が『この箱を今買う理由』になるか。恒久・無上限・普遍だと下がる
;; urgency  : 決算期末 PULSE の増幅。制度の期限と税率改定が効く
;; access   : 市場アクセス（0 は物理的に売れない）

(def countries
  [{:id "JP" :name "日本"
    :firms 3360000  :firms-src "MEASURED 中小企業白書2025(METI): 中小企業 336万社 = 99.7%"
    :firms-total 3360000 :firms-total-src "MEASURED 同左。中小企業白書の 336万社は実質的に全事業者ベース"
    :ai 0.051       :ai-src "MEASURED 総務省 情報通信白書: 中小企業の AI 導入率 5.1%(2024)"
    :ai-harmonized 0.427 :ai-h-src "MEASURED 総務省 令和7年版: 生成AI活用方針の策定率 42.7%"
    :benefit 1.00 :benefit-src "計算値: 817,362円 x 実効33.6% = 274,634円(即時償却)"
    :ease 0.35 :ease-src "経営力向上計画の認定 + 経済産業大臣の確認書が原則『取得前』。投資利益率7%"
    :salience 0.90 :salience-src "申請制で誰でも使えるわけではない = 差別化になる"
    :urgency 0.85 :urgency-src "令和10年3月末で期限 + 3月決算が最多"
    :access 1.0 :access-src "—"
    :fy 3 :fy-src "3月決算が最多"}
   {:id "US" :name "米国"
    :firms 6100000  :firms-src "ASSUMPTION 雇用主企業 約610万社(SBA 通説値。本セッションでは未検証)"
    :firms-total 36200000 :firms-total-src "MEASURED 全小規模事業者 3,620万社(2025-06、非雇用主含む)"
    :ai 0.088       :ai-src "MEASURED Census BTOS 2025-08: 小規模事業者(<250人)の生産利用 8.8%"
    :ai-harmonized 0.90 :ai-h-src "MEASURED 総務省 令和7年版の国際比較: 生成AI活用方針 90%超"
    :benefit 0.85 :benefit-src "計算値: 100% bonus x 実効約32%(pass-through) / 25%(C-corp) の中間"
    :ease 1.00 :ease-src "OBBBA で恒久・無上限。申請も証明書も無い"
    :salience 0.25 :salience-src "恒久・無上限・全機材に効く = 全ベンダーが同じことを言う"
    :urgency 0.35 :urgency-src "期限は決算期末のみ。制度自体に期限が無い"
    :access 1.0 :access-src "FCC SDoC + California CEC Title 20 の MAEDbS 登録"
    :fy 12 :fy-src "暦年決算が最多"}
   {:id "DE" :name "ドイツ"
    :firms 3200000  :firms-src "MEASURED Eurostat 2023: active enterprises 320万(非雇用主含む)"
    :firms-total 3200000 :firms-total-src "MEASURED 同左(Eurostat の active enterprises は既に全事業者ベース)"
    :ai 0.200       :ai-src "PROXY Eurostat 2025 EU平均 20.0%(10人以上)。DE 個別値は未公表"
    :ai-harmonized 0.90 :ai-h-src "MEASURED 総務省 令和7年版の国際比較: 生成AI活用方針 90%超"
    :benefit 0.89 :benefit-src "計算値: 817,362円 x 29.8%(KSt15+SolZ+GewSt14) = 243,575円"
    :ease 1.00 :ease-src "BMF 2022-02-22 の耐用年数1年ルール。金額上限なし・申請不要・恒久"
    :salience 0.45 :salience-src "恒久だが認知が低い。日本ほどではないが差別化余地が残る"
    :urgency 0.60 :urgency-src "30%逓減償却が2027末 + 2028からKSt引下げ = 前倒しが構造的に有利"
    :access 1.0 :access-src "CE(EMC/LVD/RoHS/RED) + WEEE 国別登録 + GPSR"
    :fy 12 :fy-src "暦年決算が最多"}
   {:id "IT" :name "イタリア"
    :firms 4600000  :firms-src "MEASURED Eurostat 2023: active enterprises 460万(非雇用主含む)"
    :firms-total 4600000 :firms-total-src "MEASURED 同左"
    :ai 0.200       :ai-src "PROXY Eurostat 2025 EU平均 20.0%。IT 個別値は未公表"
    :ai-harmonized 0.60 :ai-h-src "ASSUMPTION 国際比較の対象外。EU平均と US の中間に置いた"
    :benefit 1.29 :benefit-src "計算値: 180%割増 x IRES24% = 353,100円(**永久控除**、繰延ではない)"
    :ease 0.30 :ease-src "GSE 申請 + Industria 4.0 の相互接続要件。単体WSの適合可否が二値"
    :salience 0.85 :salience-src "申請制かつ 180% は日本の即時償却より強い"
    :urgency 0.70 :urgency-src "投資期間 2026-01-01〜2028-09-30"
    :access 1.0 :access-src "CE + WEEE 国別登録"
    :fy 12 :fy-src "暦年決算が最多"}
   {:id "UK" :name "英国"
    :firms 1420000  :firms-src "MEASURED GOV.UK BPE 2025: 雇用主企業 142万(全570万のうち)"
    :firms-total 5700000 :firms-total-src "MEASURED GOV.UK BPE 2025: 民間事業者 570万(うち雇用主142万)"
    :ai 0.250       :ai-src "MEASURED ONS 2025-12: 英国企業の AI 利用 25%"
    :ai-harmonized 0.75 :ai-h-src "ASSUMPTION 国際比較の対象外。EU平均と US の間に置いた"
    :benefit 0.74 :benefit-src "計算値: 817,362円 x CT25% = 204,341円(full expensing、無上限)"
    :ease 1.00 :ease-src "恒久・無上限・申請不要。法人のみ(個人事業は AIA 100万ポンド)"
    :salience 0.25 :salience-src "米国と同型。恒久・普遍で差別化にならない"
    :urgency 0.35 :urgency-src "期限は決算期末のみ"
    :access 1.0 :access-src "UKCA/CE + UK WEEE"
    :fy 12 :fy-src "4月決算も多いが暦年で近似"}
   {:id "CN" :name "中国"
    :firms 52000000 :firms-src "ASSUMPTION 中小微企業 5,200万社。access=0 のため結果に影響しない"
    :firms-total 52000000 :firms-total-src "ASSUMPTION。access=0 のため結果に影響しない"
    :ai 0.200       :ai-src "ASSUMPTION。access=0 のため結果に影響しない"
    :ai-harmonized 0.90 :ai-h-src "MEASURED 総務省 令和7年版の国際比較: 生成AI活用方針 90%超"
    :benefit 0.74 :benefit-src "計算値: 一般企业25%。小型微利(実効5%)なら 0.15 まで落ちる"
    :ease 0.95 :ease-src "500万元以下は留存备查のみ。2027-12-31 まで"
    :salience 0.15 :salience-src "誰でも使える上限。差別化にならない"
    :urgency 0.40 :urgency-src "2027年末で期限"
    :access 0.0 :access-src "CCC工場審査 / SRRC 12-14週 / ECCN 3A090 該当性(ADR-2607268000 決定5)"
    :fy 12 :fy-src "暦年決算"}
   {:id "FR" :name "フランス"
    :firms 5300000  :firms-src "MEASURED Eurostat 2023: active enterprises 530万(非雇用主含む)"
    :firms-total 5300000 :firms-total-src "MEASURED 同左"
    :ai 0.200       :ai-src "PROXY Eurostat 2025 EU平均 20.0%。FR 個別値は未公表"
    :ai-harmonized 0.70 :ai-h-src "ASSUMPTION 国際比較の対象外"
    :benefit 0.05 :benefit-src "計算値: 一般的な即時償却制度が無い。CIR(研究税額控除)経由のみ"
    :ease 0.60 :ease-src "CIR は申告制だが対象が研究開発に限られる"
    :salience 0.30 :salience-src "節税ではなく研究税額控除という別の売り方になる"
    :urgency 0.20 :urgency-src "期限らしい期限が無い"
    :access 1.0 :access-src "CE + WEEE 国別登録"
    :fy 12 :fy-src "暦年決算が最多"}])

;; ─────────────────────────────────────────────────────────────────────────────
;; 3. XMILE 模型を組む
;; ─────────────────────────────────────────────────────────────────────────────

(defn const [nm val doc] (m/aux nm (str val) {:xmile/doc doc}))

(defn build-model
  "scenario = :self（各国の自国調査そのまま） | :harmonized（生成AI活用方針の国際比較）"
  [scenario]
  (let [base (-> (m/model (str "tax_market_need_" (name scenario)))
                 (m/set-sim-specs (m/sim-specs 0 36 {:xmile/dt 1.0
                                                     :xmile/time-units "month"
                                                     :xmile/method :euler}))
                 (m/add-variable (const "p_base" (r 5 p-base-upper)
                                        "UPPER BOUND ONLY. paid 0/200 の rule of three。点推定ではない。全国共通なので国間比では約分される"))
                 (m/add-variable (const "q_imitation" q-imitation
                                        "ASSUMPTION Bass 模倣係数(文献既定 年0.3-0.5 の月換算)。全国共通"))
                 (m/add-variable (const "serviceable" serviceable
                                        "ASSUMPTION AI 利用企業のうち $5k オンプレ箱を買いうる割合。全国共通")))]
    (reduce
     (fn [mdl {:keys [id firms firms-total ai ai-harmonized benefit ease salience urgency access fy
                      firms-src firms-total-src ai-src ai-h-src benefit-src ease-src salience-src
                      urgency-src access-src fy-src]}]
       (let [rate (if (= scenario :harmonized) ai-harmonized ai)
             rate-src (if (= scenario :harmonized) ai-h-src ai-src)
             firms (if (= scenario :total-base) firms-total firms)
             firms-src (if (= scenario :total-base) firms-total-src firms-src)
             P (str "pool_" id) A (str "adopters_" id)]
         (-> mdl
             (m/add-variable (const (str "firms_" id) firms firms-src))
             (m/add-variable (const (str "ai_" id) rate rate-src))
             (m/add-variable (const (str "benefit_" id) benefit benefit-src))
             (m/add-variable (const (str "ease_" id) ease ease-src))
             (m/add-variable (const (str "salience_" id) salience salience-src))
             (m/add-variable (const (str "urgency_" id) urgency urgency-src))
             (m/add-variable (const (str "access_" id) access access-src))
             (m/add-variable (const (str "fy_" id) fy fy-src))
             (m/add-variable
              (m/aux (str "pool0_" id)
                     (str "firms_" id " * ai_" id " * serviceable")
                     {:xmile/doc "初期プール = 企業数 x AI利用率 x 購買可能割合"}))
             (m/add-variable
              (m/aux (str "pull_" id)
                     (str "benefit_" id " * ease_" id " * salience_" id " * access_" id)
                     {:xmile/doc "節税が購入を引く力 = 金額 x 行いやすさ x 希少性 x アクセス"}))
             ;; XMILE の stock 初期値式は aux を参照できない(execute/initial-stocks の
             ;; docstring どおり、stock か定数のみ)。ここだけ数値を焼く。
             (m/add-variable
              (m/stock P (str (r 6 (* firms rate serviceable)))
                       {:xmile/outflows #{(str "adopt_" id)}
                        :xmile/non-negative? true
                        :xmile/doc "未購入の到達可能企業"}))
             (m/add-variable
              (m/stock A "0"
                       {:xmile/inflows #{(str "adopt_" id)}
                        :xmile/non-negative? true
                        :xmile/doc "累積購入企業"}))
             (m/add-variable
              (m/flow (str "adopt_" id)
                      (str P " * ( p_base * pull_" id
                           " * (1 + urgency_" id " * PULSE(1, fy_" id ", 12))"
                           " + q_imitation * " A " / (pool0_" id " + 1) )")
                      {:xmile/doc "Bass 拡散。税制レバーは外部係数 p 側に入り、決算期末に PULSE で増幅する"})))))
     base countries)))

(def doc-of
  (fn [scenario]
    {:xmile/header {:xmile/vendor "com-junkawasaki/root"
                    :xmile/product {:xmile/name "tax-market-need" :xmile/version "1"}
                    :xmile/name (str "MK-1 tax-asset market need by region (" (name scenario) ")")}
     :xmile/sim-specs (m/sim-specs 0 36 {:xmile/dt 1.0 :xmile/time-units "month" :xmile/method :euler})
     :xmile/models [(build-model scenario)]}))

;; ─────────────────────────────────────────────────────────────────────────────
;; 4. 走らせる
;; ─────────────────────────────────────────────────────────────────────────────

(defn run-scenario [scenario]
  (let [mdl (build-model scenario)
        problems (v/validate mdl)
        errs (v/errors problems)]
    (when (seq errs)
      (println "XMILE VALIDATION ERRORS:")
      (doseq [e errs] (println "  " (pr-str e)))
      (throw (ex-info "invalid xmile model" {:errors errs})))
    (let [{:keys [xmile/series]} (x/run mdl)]
      (into {} (for [{:keys [id]} countries]
                 [id (last (get series (str "adopters_" id)))])))))

(println "=== MK-1『節税資産』の地域別マーケットニーズ — XMILE Bass 拡散 ===\n")

(println "⚠ WARNING 1 — 絶対値は答えていない。")
(println "  murakumo の実測 funnel は 訪問 549-728 → run 200 → **paid 0**。転換率に点推定は")
(println (str "  無く、p_base = " (r 5 p-base-upper) " は rule of three の**上界**である。"))
(println "  serviceable / q_imitation も未計測。3 つとも全国共通なので比では約分される。")
(println "  → 読んでよいのは順位と比だけ。台数として引用しない。\n")

(println "⚠ WARNING 2 — AI 利用率の定義が国ごとに違う。")
(println "  JP 5.1%(中小企業のAI導入率) / US 8.8%(生産利用・250人未満) / EU 20.0%(10人以上・任意用途)")
(println "  / UK 25%(任意用途)。同じ物差しではない。EU は 10人未満を母数から外すので高く出る。")
(println "  → だから同一調査の国際比較(生成AI活用方針の策定率)で harmonized も回して幅で示す。\n")

(println "=== 節税レバーの分解（税制側のみ。市場規模を含まない）===")
(println (str (.padEnd "国" 8) (.padStart "benefit" 9) (.padStart "ease" 7)
              (.padStart "salience" 10) (.padStart "access" 8) (.padStart "pull" 8)
              (.padStart "vs JP" 8)))
(let [jp-pull (let [{:keys [benefit ease salience access]} (first (filter #(= "JP" (:id %)) countries))]
                (* benefit ease salience access))]
  (doseq [{:keys [id name benefit ease salience access]} countries]
    (let [pull (* benefit ease salience access)]
      (println (str (.padEnd (str id " " name) 10)
                    (.padStart (str (r 2 benefit)) 7)
                    (.padStart (str (r 2 ease)) 7)
                    (.padStart (str (r 2 salience)) 10)
                    (.padStart (str (r 2 access)) 8)
                    (.padStart (str (r 3 pull)) 8)
                    (.padStart (str (r 2 (/ pull jp-pull))) 8))))))

(println "\n=== 到達可能プール（市場側のみ。税制を含まない）===")
(println (str (.padEnd "国" 10) (.padStart "企業数" 12) (.padStart "AI率" 8)
              (.padStart "AI率(harm)" 12) (.padStart "pool" 10) (.padStart "vs JP" 8)))
(let [jp (first (filter #(= "JP" (:id %)) countries))
      jp-pool (* (:firms jp) (:ai jp) serviceable)]
  (doseq [{:keys [id name firms ai ai-harmonized]} countries]
    (let [pool (* firms ai serviceable)]
      (println (str (.padEnd (str id " " name) 10)
                    (.padStart (str firms) 12)
                    (.padStart (str (r 3 ai)) 8)
                    (.padStart (str (r 3 ai-harmonized)) 12)
                    (.padStart (str (js/Math.round pool)) 10)
                    (.padStart (str (r 2 (/ pool jp-pool))) 8))))))

(def res-self (run-scenario :self))
(def res-harm (run-scenario :harmonized))
(def res-base (run-scenario :total-base))

(println "\n=== 36か月後の累積採用（XMILE シミュレーション、日本 = 1.00）===")
(println "  自国調査 = 各国の自国調査そのまま / harmonized = 生成AI活用方針の国際比較")
(println "  total-base = 母数を全事業者ベースに揃える(US 3,620万・UK 570万。未検証値を排除)")
(println (str (.padEnd "国" 10) (.padStart "自国調査" 12) (.padStart "harmonized" 14)
              (.padStart "total-base" 14) (.padStart "幅" 16)))
(let [jp-s (get res-self "JP") jp-h (get res-harm "JP") jp-b (get res-base "JP")]
  (doseq [{:keys [id name]} countries]
    (let [s (/ (get res-self id) jp-s)
          h (/ (get res-harm id) jp-h)
          b (/ (get res-base id) jp-b)
          lo (apply min [s h b]) hi (apply max [s h b])]
      (println (str (.padEnd (str id " " name) 10)
                    (.padStart (str (r 2 s)) 12)
                    (.padStart (str (r 2 h)) 14)
                    (.padStart (str (r 2 b)) 14)
                    (.padStart (str (r 2 lo) " 〜 " (r 2 hi)) 16))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; 5. XMILE を書き出す
;; ─────────────────────────────────────────────────────────────────────────────

(doseq [scenario [:self :harmonized :total-base]]
  (let [path (str "90-docs/system-dynamics/tax-market-need-" (name scenario) ".xmile")]
    (fs/writeFileSync path (xml/emit-string (doc-of scenario)))
    (println (str "\nwrote " path))))

(println "\n再現: nbb --classpath \"orgs/kotoba-lang/org-oasis-open-xmile/src:orgs/kotoba-lang/dsl-core/src:orgs/kotoba-lang/dynamics/src\" 90-docs/system-dynamics/tax-market-need.cljs")
