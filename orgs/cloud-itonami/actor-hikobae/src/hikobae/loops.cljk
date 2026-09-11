(ns hikobae.loops
  "復旧の因果ループと、Meadows の leverage-point 採点。

   採点そのものは `kotoba-lang/dynamics` が持っている（ADR-2607203000）。
   ここが足すのは領域の中身だけで、採点式は書き直さない。

   ⚠ 採点は監査可能なヒューリスティクスであって物理量ではない
   （dynamics.core/meadows-bands の docstring）。順位を読むための道具で、
   スコアの絶対値には意味を持たせない。"
  (:require [dynamics.core :as dyn]))

(def loops
  "復旧を支配するループ。どれも実観測または記録された介入に紐づく。

   :kind    :reinforcing（自己強化）/ :balancing（均衡）
   :sign    :harmful（放置すると悪化）/ :helpful
   :evidence 何を見てこのループが在ると言っているか"
  [{:id :loop/certification-bottleneck
    :label "認定の待ち行列 → 下流全停止"
    :kind :balancing :sign :harmful
    :stocks [:assessment-queue :certificates-issued]
    :story "被害住家が一度に発生 → 認定調査の待ち行列 → 罹災証明が出ない →
            仮設入居・応急修理・公費解体・支援金・税減免・保険が全部止まる →
            避難所人口が下がらない → 職員が避難所運営に貼り付く →
            調査に回せる人手が減る → 待ち行列が伸びる。
            均衡ループの内側に自己強化ループが巻き付いている形。"
    :evidence "本 repo の業務依存グラフで、:op/damage-assessment は9つの下流業務を
               推移的にゲートしている。内閣府 2026-08-23 に『宇城市・八代市で
               オンライン申請を導入し、滞留を解消』の記録があり、滞留の存在自体が
               記録されている。"
    :model-hook "assessment_queue → certificate_rate → certificate_coverage"}

   {:id :loop/prolonged-displacement-mortality
    :label "避難の長期化 → 災害関連死"
    :kind :reinforcing :sign :harmful
    :stocks [:shelter-longterm :cumulative-indirect-deaths]
    :story "住まいが戻らない → 避難生活が延びる → 環境荷重（猛暑・寒冷・
            不衛生・不眠・服薬中断・不活動）が積算する → 健康を損ねる →
            災害関連死。直接死と違い、この量は**時間とともに増え続ける**。"
    :evidence "2016年熊本地震では災害関連死218人が直接死50人を上回った。
               2026年は真夏の発災で 8/23 予報38℃、熱中症警戒下にあり、
               台風18号が8/25〜27に影響する見込み（内閣府 2026-08-23）。"
    :model-hook "displaced_population × environment_risk_multiplier → indirect_death_rate"}

   {:id :loop/invisible-displaced
    :label "見えない避難者 → 支援が届かない → さらに見えない"
    :kind :reinforcing :sign :harmful
    :stocks [:displaced-outside]
    :story "車中泊・親族宅の避難者は避難所集計に入らない → 物資も保健の巡回も
            人数に基づいて配られる → 届かない → 避難所に来る理由がさらに減る →
            ますます数えられない。"
    :evidence "内閣府の日次速報が数えているのは避難所の人数のみ。避難所外避難者は
               本 repo の facts で :event/unmeasured に明示している。
               2016年熊本で車中泊が肺塞栓症の温床になった。"
    :model-hook "displaced_outside（観測されない stock）"
    :note "観測の欠落が、そのまま死亡率に効く数少ない例。計測の問題ではなく
           保健の問題である。"}

   {:id :loop/local-capacity-collapse
    :label "自治体capacityの底 × 需要の頂点"
    :kind :balancing :sign :harmful
    :stocks [:admin-capacity]
    :story "職員自身が被災し、庁舎も被災し、平時業務は止まらない。そこへ
            避難所運営・認定調査・証明交付・支援金の4本が同時に立ち上がる。
            capacity が最小になる時刻と需要が最大になる時刻が一致する。"
    :evidence "八代市庁舎の躯体（免震ダンパー）損傷。石川県・岩手県・URの
               建築系職員7名が県庁の応急住宅業務に入った（内閣府 2026-08-23）。"
    :model-hook "assessment_capacity（外から足さない限り増えない）"}

   {:id :loop/attention-decay
    :label "注目の減衰 × 必要量の持続"
    :kind :balancing :sign :harmful
    :stocks [:responder-capacity :donation-pool]
    :story "ボランティア・寄付・報道は注目の関数で、注目は次の出来事へ移る。
            必要量は物理的復旧の関数なので年単位で続く。差が『支援の谷』になる。"
    :evidence "中間支援組織 KVOAD が発災翌日から毎日の調整会議を回している
               （8/22 で第24回、現地約20団体・オンライン約35団体）。"
    :model-hook "未実装（現行モデルは応援capacityを定数として扱う）"
    :gap true}

   {:id :loop/outmigration-trap
    :label "復旧の遅さ → 人口流出 → 復旧能力の低下"
    :kind :reinforcing :sign :harmful
    :stocks [:population :tax-base :business-activity]
    :story "戻れる見込みが遠い → 転school・転職・住宅取得で移住が不可逆になる →
            税・労働力・需要が減る → 復旧と再建が遅れる → 見込みがさらに遠のく。"
    :evidence "構造的主張。本 repo では2026年熊本の実データでは未検証
               （発災26日では移住は現れない）。"
    :model-hook "未実装"
    :gap true}

   {:id :loop/lifeline-tail
    :label "ライフラインの尾（速い塊のあとに残る戸別の壊れ）"
    :kind :balancing :sign :neutral
    :stocks [:water-out-network :water-out-service]
    :story "本管が繋がると数万戸が塊で戻り、そのあとに引込管・漏水・井戸という
            戸別の作業が残る。前半は資材と重機、後半は**人手と探索**が律速で、
            必要な資源が途中で入れ替わる。"
    :evidence "実観測系列が day 13 付近で踊り場を作り、井戸水対応チーム設置
               （8/11）の後 day 17→20 で 27,800→8,400 に落ちている
               （内閣府/国土交通省 2026-08-23）。"
    :model-hook "water_out_network / water_out_service の2区画"}

   {:id :loop/land-contention
    :label "土地の取り合い"
    :kind :balancing :sign :harmful
    :stocks [:available-land]
    :story "応急仮設・災害廃棄物仮置場・資材ヤード・ヘリポート・避難所が
            同じ空き地を取り合う。土地は地震の後に増えない。"
    :evidence "2026年熊本では物資運搬等の拠点として都市公園を活用（内閣府）。
               公園を使うと、そこにあった別の機能が止まる。"
    :model-hook "未実装"
    :gap true}])

(def interventions
  "介入候補。`dynamics.core/leverage-score` の入力そのもの。

   :band は Meadows の階層（:band/E 定数 … :band/A パラダイム）。
   :tractability は 0〜1 で『この組織が実際に動かせるか』。
   どちらも判断であって測定値ではない —— 順位を議論するための明示的な前提として
   置いてあり、反論は band か tractability の値に対して行える。"
  [{:id :iv/online-certificate-application
    :label "罹災証明のオンライン申請（窓口の物理待ち行列を消す）"
    :band :band/B :tractability 0.9
    :rationale "情報の流れの構造を変える。調査員を1人も増やさずに下流を開ける。
                実際に宇城市・八代市が導入し『滞留を解消』と記録されている。"
    :observed true}

   {:id :iv/mutual-aid-surveyors
    :label "対口支援で被害認定調査員を増派する"
    :band :band/D :tractability 0.7
    :rationale "stock-flow 構造の律速（調査能力）を直接広げる。
                石川県・岩手県・UR が実際に職員を出している。"
    :observed true}

   {:id :iv/shelter-environment-standard
    :label "避難所の環境を最低基準（冷暖房・ベッド・間仕切り）で満たす"
    :band :band/B :tractability 0.8
    :rationale "『避難所は我慢する場所』という目標設定を『関連死を出さない場所』へ
                変える。プッシュ型でエアコン1,075台・段ボールベッド5,000個が
                既に届いており、実行可能性は高い。"
    :observed true}

   {:id :iv/count-the-invisible-displaced
    :label "避難所外避難者を数える（車中泊・親族宅）"
    :band :band/B :tractability 0.6
    :rationale "情報の流れそのものを作る。数えられない人には配れない。
                現状は観測が存在しない。"
    :observed false}

   {:id :iv/pre-agreed-wide-area-waste
    :label "災害廃棄物の広域処理協定を平時に結んでおく"
    :band :band/B :tractability 0.5
    :rationale "発災後に交渉から始めないための規則。事前でしか作れない。"
    :observed false}

   {:id :iv/pre-registered-rental-stock
    :label "みなし仮設に使える民間賃貸在庫を平時に登録しておく"
    :band :band/D :tractability 0.6
    :rationale "既存在庫を使うので建設より桁で速い。地震の後に在庫は増えない。"
    :observed false}

   {:id :iv/more-prefab-units
    :label "応急仮設住宅の建設戸数を増やす"
    :band :band/E :tractability 0.8
    :rationale "パラメータを大きくするだけの介入。効くが、上流の罹災証明が
                詰まっていれば着工そのものが立ち上がらない。
                **最も分かりやすく、最も leverage が低い**。"
    :observed true}

   {:id :iv/build-back-better-code
    :label "再建時の耐震基準を引き上げる（Build Back Better）"
    :band :band/A :tractability 0.3
    :rationale "次の地震の被害量そのものを決めるので最上位。ただし今回の復旧を
                速くはせず、むしろ遅くする。仙台防災枠組 Priority 4。"
    :observed false}])

(defn ranked-interventions
  "leverage の高い順。`dynamics.core/rank-interventions` に委譲する。"
  []
  (dyn/rank-interventions interventions))

(defn loops-by-sign []
  (group-by :sign loops))

(defn modelled-loops
  "現行 XMILE モデルが実際に表現しているループ。`:gap true` は表現できていない。"
  []
  (remove :gap loops))

(defn loop-gaps
  "構造としては在ると言えるが、モデルにまだ無いループ。
   **無いことを一覧で持つ** —— モデルが答えないことを、モデルの外に書いておく。"
  []
  (filter :gap loops))
