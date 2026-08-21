(ns leverage
  "価格ループへの介入を Meadows leverage-point で順位づけする（dynamics.core）。"
  (:require [dynamics.core :as d]))

(def interventions
  [{:id :measure-h100-wall-time
    :what "H100 で 1 出力秒あたり何 GPU 秒かかるかを 1 回測る"
    :band :band/B          ; 情報フロー構造 — 存在しない測定を作る
    :tractability 1.0      ; H100 を 1 時間借りる = $2.39
    :note "最適価格が 2〜10cr/秒 の 5 倍に散らばる原因はこの 1 数値。測れば散らばりが消える"}
   {:id :never-rent-above-buy
    :what "予約 GPU の実効単価が fal 従量を超えるなら借りない（調達の床）"
    :band :band/B          ; ルール
    :tractability 0.9
    :note "この規則の有無で Modal×低速シナリオの寄与率が -20.9% → +45.6% に変わる"}
   {:id :flat-credit-price
    :what "全プラン同一単価（volume discount を置かない）"
    :band :band/B
    :tractability 1.0
    :note "寄与率が plan/利用率/超過に依存しなくなる = 構造で赤字を排除する"}
   {:id :tune-list-price
    :what "掲示価格を 10 → 8cr/秒 に下げる"
    :band :band/E          ; パラメタ
    :tractability 1.0}
   {:id :shorten-capacity-lag
    :what "容量調整の遅れ 7 日を短くする"
    :band :band/D          ; 遅れ = stock-flow 構造
    :tractability 0.6}
   {:id :byok-frontier
    :what "frontier を BYOK にして原価をゼロにする"
    :band :band/C          ; ループの利得
    :tractability 0.7}
   {:id :acquire-customers
    :what "広告で顧客を獲得する"
    :band :band/E
    :tractability 0.8
    :pool-size 4000        ; 参照価格での到達可能市場（SCENARIO）
    :conversion-rate nil}])  ; ★未計測 → :uncomputable-until-measured

(println "介入の Meadows leverage 順位（band-weight × tractability）\n")
(doseq [i (d/rank-interventions interventions)]
  (println (str "  " (:base-score i) "\t" (name (:band i)) "\t" (:id i)
                "\n\t\t" (:what i)
                (when-let [y (:expected-yield i)] (str "\n\t\t期待収穫: " y))
                (when-let [n (:note i)] (str "\n\t\t" n))
                "\n")))
