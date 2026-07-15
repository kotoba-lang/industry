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
