(ns spotwork.fixtures
  "テスト用の固定データ。**実在の求人ではない。** 事業者 DID も架空。

  `lawful-offer` は「全部の申告が揃っていて、どの規則にも触れない」1 件で、
  ここから 1 箇所だけ壊して各規則が**その規則の名前で**拒否することを確かめる
  （壊したものと報告されたものが一致することを見る）。")

(def lawful-offer
  {:offer/id "of-fixture-lawful-0001"
   :offer/employer-did "did:web:example.invalid"
   :offer/jurisdiction :jp
   :offer/region "JP-13"
   :offer/isco "5246"
   :offer/date "2026-08-30"
   :offer/start "10:00"
   :offer/end "19:00"
   :offer/tz "Asia/Tokyo"
   :offer/break-minutes 60
   :offer/hourly-wage 1300
   :offer/currency "JPY"
   :offer/overtime-premium-rate 1.25
   :offer/night-premium-rate 1.25
   :offer/agreement-36? true
   :offer/headcount 2
   :offer/fee {:fee/payer :employer :fee/rate 0.30}
   :offer/wage-deductions []
   :offer/penalty-predetermined? false
   :offer/min-age 18
   :offer/work-authorization-required? true})

(def cohort-a
  {:cohort/id "ch-jp13-5246-day"
   :cohort/isco "5246"
   :cohort/region "JP-13"
   :cohort/size 40
   :cohort/availability [{:from "09:00" :to "22:00"}]
   :cohort/no-show-rate 0.05
   :cohort/work-authorization-verified? true})

(def cohort-b
  {:cohort/id "ch-jp13-5120-eve"
   :cohort/isco "5120"
   :cohort/region "JP-13"
   :cohort/size 12
   :cohort/availability [{:from "17:00" :to "23:00"}]
   :cohort/no-show-rate 0.11
   :cohort/work-authorization-verified? true})

(def cohort-unmeasured
  "無断欠勤率が未申告。**スコアが付かない**ことを確かめるための cohort。
  0 点として最下位に並ぶのではなく、順位表の外（`:unscored`）に出るのが正。"
  (-> cohort-a
      (assoc :cohort/id "ch-jp13-5246-unknown")
      (dissoc :cohort/no-show-rate)))

(def operator
  "オペレータ維持データ。地域別最低賃金の額は条文に無いので、repo ではなく
  ここ（テスト）と運用側が持つ。値はテスト用であり、実際の告示額ではない。"
  {:minimum-wage {"JP-13" 1163}})
