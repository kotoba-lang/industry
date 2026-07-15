# ADR-2607151900: cloud-murakumo の business ReAct loop 計器配線 — :hyp/murakumo-tok-price 実測 validated

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (owner directive「cloud-itonami / itonami cli を使って cloud-murakumo などの business が business react loop を回せるように分析・設計・評価」)

## Context — 分析

cloud-murakumo は BMC 体系(ADR-2607021500 系)に登録済みで、`bmc-business-operate-daily`
routine が毎日 `react loop` を回していたが、実態は**回っているのに学習しない**状態だった:

1. **gate が blocked**: 唯一の riskiest 仮説 `:hyp/murakumo-tok-price`(consumer fleet の
   tok/s 単価が GPU spot に対して買い手のつく水準)は ADR-2607022200 で
   `[:cost :fleet-yen-per-mtok] <= [:cost :spot-yen-per-mtok]` の機械判定に配線済みだったが、
   **:cost を出す計器がどこにも存在しなかった**。cloud-murakumo PR #2 の `cost.cljc` は
   runs.edn を手渡しする read-only CLI で、「run ledger を誰が書くか」が未配線
   (`GET /infer/cost` は murakumo.cloud にも api.murakumo.cloud にも未デプロイ、
   collect.cljs の gate-emitters にも entry 無し)。
2. **funnel-spec 未定義**: `funnel show/analyze --product cloud-murakumo` は
   「funnel-spec 未定義」で動かない。
3. **loop 非収束バグ**(実行して発見): `gftd.gate/proposals` の `:validated` ケースが
   既に validated の仮説を毎 tick 再提案し、ledger に同一昇格イベントを積み続ける
   (loop が dry にならない)。
4. canvas の Problem block に毎時 signal 観測が約 70 行堆積(観測はあるが検証が動かない
   症状の現れ)。

なお指示中の「local-itonami cli」に該当する独立 CLI は存在しない(cloud-itonami repo の
`local/` はコンパイル済み web shell)。実在するのは (a) superproject の gftd BMC CLI 群
(`70-tools/bmc/bin/{gftd,itonami,murakumo,…}` — portfolio の business react loop の正本)と
(b) cloud-itonami repo の `bb business-tick`/`bb tick-loop`(itonami 自身の venture 運転)。
本 ADR は (a) を cloud-murakumo で実際に回せるようにする配線。

## Decision — 設計

**計器は実トラフィックが通る場所に置く**: run ledger の書き手を local-murakumo Worker
(api.murakumo.cloud)にした。murakumo fleet の推論はすべてここを通るので、計測が
副産物として自動的に溜まる(専用バッチ・手動 export 不要)。

1. **local-murakumo `aebd399`**(west pin 前進済み):
   - `/v1/messages` 非 streaming 応答の llama.cpp `timings` から実 run
     {node, tok-s, elapsed-s, tokens} を KV ring(`infer.cost/runs`、上限 200)へ記録。
     レスポンス返却前に await(Workers は返却後の未完 Promise を破棄するため —
     fire-and-forget では実際に消えることを確認済み)。timings の無い応答は記録しない。
   - `GET /infer/cost` が ¥/Mtok 集計を返す。電力係数 `:load-w 64.1` は **gad 実測**
     (2026-07-15、本番 600-token decode 60.48 tok/s 実行中に rocm-smi socket power を
     0.5s 間隔サンプリング: idle 5.06 W → 負荷ピーク 64.08 W)。`:yen-per-kwh 30` と
     spot 参照(H100 $2.99/h・2000 tok/s・¥157/$)は**明示 placeholder** — operator が
     KV `infer.cost/coeffs` で実値に上書きできる。runs が空なら fleet 側 nil
     (gate は unmeasurable のまま。0 を捏造しない)。
2. **collect.cljs**: gate-emitters に
   `:cloud-murakumo {:url "https://api.murakumo.cloud/infer/cost" :key :cost}` を追加 —
   毎時/毎日の collect が metrics/cloud-murakumo.edn に `:cost` を merge する。
3. **funnel.cljc**: cloud-murakumo の funnel-spec を追加
   (awareness = zone uniques → activation = 実推論 run 記録数 → revenue = Stripe active subs)。
   revenue 段は Stripe 未配線(canvas 2026-07-06 観測: STRIPE_SECRET_KEY 未設定で
   checkout 503)なので「未計測」として bottleneck 面に正しく出る。
4. **gate.cljc 収束バグ修正**: 既に `:validated` の仮説は再提案しない(回帰テスト追加)。

## 評価 — 実測結果(2026-07-15)

- **`:hyp/murakumo-tok-price` → validated(実測)**: fleet **¥9.40/Mtok** vs spot
  **¥65.20/Mtok**(ratio 0.144、実 run 4 件 / 1,580 tokens)。riskiest 仮説が
  ポートフォリオで manimani 以外初の機械測定 validated。
- スコア: BMC 成熟度 **64 → 84**、validation **0 → 5**(YC bench 51.7 は revenue=0 が律速のまま)。
- react loop: 修正後 1 tick で dry(収束)を確認。
- funnel: 訪問 418/7d → 実推論 run 4(転換 1% < 目標 2% — bottleneck)→ revenue 未計測。
  funnel analyze が GTM 提案(onboarding 摩擦削減・価格明確化)+ 計器提案
  (Stripe funnel emitter)を governor 経由で ledger に記録。

## 残作業(人間 / follow-up)

- **¥/kWh 実タリフ**と spot 参照(実勢 $/h・fx)の実値を KV `infer.cost/coeffs` へ
  (現 placeholder でも ratio 0.14 なので結論が覆る余地は小さいが、証拠の質を上げる)。
- **STRIPE_SECRET_KEY 配線**(revenue 段の計測開始 — funnel の未計測段解消。
  docs/stripe-go-live-checklist.md)。
- streaming 応答の run 記録(現状は非 streaming のみ。Claude Code 経由の大半は
  streaming なので coverage は部分的 — 記録される run は実在の run のみで偏りは
  「量が少ない」方向)。
- 毎時 signal の Problem block 堆積は別スコープ(canvas fold の保持ポリシー検討)。
- gemma-gad(:11434、May build)は timings 未確認 — 記録対象は現状 gad 8090 経路のみ。

## References

- ADR-2607021500/1600/2100/2200(BMC 体系・ReAct loop・gate 評価器・per-product 計器)
- gftdcojp/local-murakumo `43bce45`(cost emitter)+ `42b646b`(voice API 復元 —
  2026-07-13 から本番稼働していた未コミット WIP の verbatim コミット化)
- gftdcojp/cloud-murakumo PR #2(cost.cljc 純関数層 — 本計器の計算式の先行実装)

## Addendum 1 (2026-07-15) — revenue 段の計器も配線完了

funnel の最後の未計測段(revenue)を実計測化した。実は Stripe checkout 自体は
2026-07-06 から live だった(canvas の「STRIPE_SECRET_KEY 未設定」観測は同日中に
解消済み — docs/stripe-go-live-checklist.md 参照。実 cs_live_ セッション生成を
本日も再確認)。未計測の real gap は「どの charge が cloud-murakumo のものか」を
機械判定できないことだった:

- cloud-murakumo `fcd317d`(pin 前進済み): Checkout Session に
  `payment_intent_data[metadata]{product=cloud-murakumo, sku, country}` を付与
  (charge へ伝播)。
- collect.cljs: `:cloud-murakumo` に `:stripe true`、stripe-summary に
  `:murakumo-paid-charges` / `:murakumo-paid-amount-minor`(paid かつ非 refund、
  metadata.product 判定)。
- funnel revenue 段: `[:stripe :murakumo-paid-charges]`(単発 credits 購入 —
  subscription ではないので active-subscriptions は不適)。

実測(2026-07-15): 訪問 419 → 実推論 run 4(1%)→ **paid 0(実購入ゼロ、正直な 0
から計測開始)**。全 3 段が実データ化し「未計測段」は解消。実購入第 1 号は
checklist どおり owner の手動アクション。

## Addendum 2 (2026-07-15) — qwen3.6-35b-a3b 正式化と gap 台帳

### 決定: 常駐モデルを qwen3.6-35b-a3b (vision 付き) に正式化

2026-07-15 朝の障害復旧時に応急で Qwen-AgentWorld (text-only) を常駐させたが、
07-09 以降の本来の常駐は qwen3.6-35b-a3b (--mmproj vision) であり、
network-isekai の vision critic 等が degraded になっていた。オーナー指示により
qwen3.6-35b-a3b を正式常駐に切替 (`bb murakumo infer serve-standalone
qwen3.6-35b-a3b`、systemd unit murakumo-standalone)。

検証: /v1/models = Qwen3.6-35B-A3B-Q4_K_M / 実マゼンタ PNG の vision 判定
「Pink」(本物の画像認識) / club-shinshi chat 3/3 クリーン返信。

切替で露見した 2 つの回帰も同時修復:
1. **間欠空返信**: Qwen3.6 の thinking が max_tokens 900 を食い潰すと text
   不到達 → 空返信。Anthropic bridge に chat_template_kwargs pass-through を
   追加し (local-murakumo 77f1dc7)、companion chat は enable_thinking false を
   復元 (club-shinshi 21f9487 — OpenAI 形式時代の挙動の復元)。
2. **孤児 </think> 漏れ**: thinking off の Qwen3.6 が応答冒頭に閉じタグだけ
   漏らす (実測 3 回中 2 回)。strip-think を強化。

### gap 台帳 (as-of 2026-07-15、live 確認済み)

cloud-murakumo loop:
- [owner 手動] 実購入ゼロ — funnel revenue 段は計測稼働、値は正直な 0。
  第 1 号購入は実カードでの owner アクション (checklist 方針)。
- [未着手] funnel bottleneck: 訪問→実推論 run 転換 1% < 2%。GTM 提案
  (onboarding 摩擦削減・価格/tier 明確化) は ledger 済み、サイト実施が未。
- [owner 判断] 次仮説が空 — riskiest validated 後の検証対象なし。候補
  「外部推論需要者が credits に払う」。base datoms 登録は人間レビュー要。
- [任意] spot 参照 throughput 2000 tok/s は仮定 (不利側なので結論は頑健)。
- [自動解消] gate evidence 文字列の旧数値 → 次回 daily routine で更新。

fleet/infra:
- [解消済 → 本 addendum] 常駐モデル text-only 問題。
- [方針待ち] 画像生成経路 down (gateway.gftd.ai 404 / /v1/images/generations
  502)。main-2 gateway 復旧 vs gad ComfyUI (:8188 稼働中) への向け直しが未決。
  club-shinshi requestScene がこの経路に依存。
- [未着手] RPC ring / worker ノードのプロセス管理は旧来 nohup (systemd 化は
  standalone のみ)。
- [owner 方針] mining と推論の GPU 配分。

ポートフォリオ (gate --all 実測): validated は cloud-murakumo と nexus-x402
(adoption) のみ。blocked 6 件は本 ADR の emitter→collect→gate パターン横展開
候補 — apex (Stripe 配線+転換テレメトリ) / aozora (engagement) / itonami
(per-seat billing) / club-shinshi (creator billing、PSP 凍結は owner 判断) /
yoro (repo 分離) / etzhayyim (RAD hook)。measuring 4 件 (yukkuri/manimani/
kotobase/isekai) は計器あり・実績待ち。

housekeeping: 他セッション WIP 棚卸し (local-murakumo voice stash retire 可 /
cloud-murakumo generation WIP / kotoba-lang/murakumo 07-10 stash×2 /
orgs/kotoba-lang/industry 未コミット) は git-cleanup-conflict で。wrangler CLI
token の kv スコープ根治は POST /infer/cost/coeffs 経路で回避済みのため低優先。

## Addendum 3 (2026-07-15) — 画像生成経路の復旧 (gap 台帳「方針待ち」の解消)

addendum 2 で [方針待ち] とした画像生成経路 down は、調査の結果「main-2
gateway か gad ComfyUI かの二者択一」ではなく、両者を繋ぐ 3 つの独立した
不具合の重なりだった (方針決定は不要だった):

1. **tunnel ingress 消失**: main-2 の ~/.cloudflared/config.yml から
   gateway.gftd.ai の rule が消えていた (07-13 の voice 追加編集時)。
   gateway プロセス自体は :8790 で健在 — edge の catch-all 404 が実体。復元済み。
2. **gad が live-fleet から漏れ**: fleet.edn の :host "gad" が前提とする
   ~/.ssh/config alias が main-2 に無く、SSH probe 失敗 → 9x 速い専用 GPU が
   選定対象外になり、画像 dispatch が Mac mini (MPS) に向いて 240s+ hang。
   alias 追加で gad が第一候補に。
3. **checkpoint 名の誤誘導エラー**: caller の model id ("animagine-xl-4.0")
   を ComfyUI は on-disk 名 (.safetensors) で検証して却下、gateway は
   『node became unreachable mid-render』と誤報告。normalize-ckpt を追加
   (kotoba-lang/murakumo f770f2c、195 tests green)。

検証: POST api.murakumo.cloud/v1/images/generations (model 指定あり/なし両方)
→ 200、実 PNG 生成 8.9〜19.4s、node=gad。voice 経路の非退行も確認 (200)。
club-shinshi requestScene の依存経路が復活した。

## Addendum 4 (2026-07-15) — GTM 実施: landing に first-value 経路を配線

funnel bottleneck (訪問→実推論 activation 1% < 2%) への GTM を実施した。
調査で判明した最大の摩擦は「訪問者が実推論に到達する経路がサイト上に存在
しない」こと — 旧『Live Playground』はスケジューラの browser シミュレーション
で推論を一切呼ばず、実行可能な curl 例も無く、価格の即購入導線も無く、課金
しても API token 発行は operator の手動 CLI のみだった。

実装 (3 層):
1. **local-murakumo `c6323dd`**: 公開 `POST /v1/chat/completions` verbatim
   passthrough — 無認証 (murakumo.cloud/api/v1 の現行姿勢と同一)、max_tokens
   2048 cap、非 streaming は run 記録付き。試用トラフィックが activation
   計器にそのまま乗る。
2. **cloud-murakumo `c7e7e77`**: landing に try-it セクション (本物の推論
   ボックス: qwen3.6-35b-a3b、enable_thinking off + <think> strip、Enter 送信)
   + OpenAI 互換 curl 例 + credits-strip (storefront SKU SSoT から Starter
   $10 / Growth $50 / Scale $200、#store 直リンク、実測原価 ~¥13/Mtok の透明性
   訴求)。hero の primary CTA を try-it へ。CSS は hig token のみ (kotoba-uiux
   準拠、raw hex ゼロ)。
3. **計測経路化**: `MURAKUMO_OPENAI_URL` を infer.murakumo.cloud (計測外) →
   api.murakumo.cloud/v1 (計測付き) に変更。landing の try-it / curl の
   1 送信が BMC funnel の activation として実計測される。

live 検証: upstream 反映・landing proxy 経由の実推論 200・run 記録・GTM 要素
の配信を確認。残 gap: Stripe 購入→API token 自動発行 (fulfillment) は未配線
のまま — 有償転換 (activation→revenue) の次の摩擦であり、webhook + token
mint + 配布 UX の設計を要する (owner 判断込みの別スコープ)。

## Addendum 5 (2026-07-15) — パターン横展開 #1: ai-gftd-apex の conversion 計器配線

本 ADR の emitter→collect→gate パターンをポートフォリオ最大トラフィックの
ai-gftd-apex (gftd.ai、342K req/7d・4,764 uniques/7d) に横展開した。apex も
murakumo と同型の「実装済み・未配線」だった: ai-gftd-apex PR #2 (2026-07-02
MERGED) が conversion 計算の純関数層 + read-only CLI を実装済みだが、emit が
CLI/file 型のまま誰にも実行されず、gate :hyp/apex-privacy-premium は blocked。

配線 (collect.cljs):
- stripe-summary に apex-active-subscriptions (Gftd AI Pro
  price_1RiEU5BcblPoapUJY3PfDspR、PAID invoice 判定つき — kotobase と同じ手口)
- ai-gftd-apex per-product 導出で :conversion {:pct} / :subscription
  {:free :plus} を PR #2 の定義どおり付与 (pct = plus/(free+plus)、base 空 0.0)。
  free = 0 は現時点の真実 — apex は Privacy Contract (ADR-2606301220) により
  アカウント主キーを持たず「登録済み Free ユーザー」の母集団がまだ存在しない。

実測結果: gate **blocked → measuring** (「Free→Plus 転換率 = 0 (gate 未到達)」、
Stripe 実クエリで apex 課金者 0 を確認 — 既存 active 4 件は全て kotobase の
未払いテスト sub)。loop は gate 距離を観測に昇格して収束。

残 gap (apex 側、次スコープ): ①Plus checkout が存在しない (訪問者は払いたくても
払えない — Payment Link + UI が最短だが、購入を opaque actor id に束ねる設計
(ADR-2606301220) を要するため owner 判断込み) ②free 母集団の実数化 (records 経由、
checkout/actor-binding 着地後)。なお同日、別セッションが cloud-murakumo 側で
Stripe webhook → itonami credits topup (ADR-2607995000 系) を実装中 — apex の
checkout 設計はそちらの着地を待って揃えるのが良い。
