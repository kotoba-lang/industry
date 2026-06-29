# ADR-2606272300: cloud-murakumo — Modal 等価の分散 GPU cloud を clj+kotoba+datomic で datom 化する

**Status**: closed
**Date**: 2026-06-27
**Closed**: 2026-06-28
**Scope**: `orgs/gftdcojp/cloud-murakumo`

## Context

gftdcojp は GPU 推論(vLLM serving / バッチ埋め込み / 定期ジョブ)を回す必要がある。
現状の正本は `gftdcojp/minimax-m2-modal` で、ここは既に「Modal の Python decorator を
EDN 値(GPU shape / volume / parser / api-key env)に落とす」data-first 方針を採っている。

一方、`com-junkawasaki/murakumo` は kotoba WASM mesh の制御面(≅ wadm/wash)として、
leaderless gossipsub auction で component を配置する。GPU host も同じ lattice 上の
ノードだが、**「GPU 付き serverless 関数」という Modal の製品モデル(App/Function/Image/
Volume/Secret/autoscale/scale-to-zero/endpoint/cron)はまだ語彙化されていない**。

つまり gap は 2 つ:

1. minimax-m2-modal は **単一モデルの serve コマンド生成**までで、複数 app・複数関数・
   autoscale・配置(placement)・課金を横断する **製品面**がない。
2. murakumo は WASM component の配置はできるが、**GPU class/個数を制約にした
   bin-packing auction** と **GPU 確保=財務副作用の承認 gate** を持たない。

## Decision

`cloud-murakumo` を **Modal 等価の分散 GPU cloud サービス**として、`cloud-itonami` と
同じ設計原則で作る。主体は gftdcojp(GPU を消費し課金される製品)なので配置は
`orgs/gftdcojp/cloud-murakumo`。

- `cloud-itonami` が manimani(business activity)を datom 化したのと対称に、
  cloud-murakumo は **Modal(GPU serverless)を datom 化**する。
- 配置は `com-junkawasaki/murakumo` の auction(≅ wadm reconcile)に **GPU 在庫を
  bid 対象として乗せる**。cloud-murakumo は murakumo の上に立つ製品面で、murakumo を
  置き換えない。
- `gftdcojp/minimax-m2-modal` は cloud-murakumo の **最初の実 app**(`:llm-serving`)
  として吸収する。serve コマンド生成ロジックは `cloud-murakumo.vllm` に一般化する。

正本は `resources/murakumo.edn`(SSoT)。実装正本は `.cljc` とし、kotoba/datom log に
保存する。Clojure/JVM、babashka、kotoba-clj/WASM のどこでも同じ配置結果を出す
(scheduler は純粋関数)。

## Core Vocabulary

datom 語彙は `cloud-murakumo.schema`(= kotoba-datomic schema)に置く。

- `:murakumo.app/*`: デプロイ単位(= Modal App)
- `:murakumo.fn/*`: GPU 付き serverless 関数(= Modal Function)。`:gpu/class` か
  `:gpu/min-vram` + `:gpu/count`、autoscale、image/volume/secret/endpoint/schedule
- `:murakumo.image/*`: container image as data(base/pip/env。Dockerfile decorator ではない)
- `:murakumo.node/*`: GPU host capacity(lattice の bid 対象)
- `:murakumo.placement/*`: fn replica → node 割当(auction 結果。as-of で履歴化)
- `:murakumo.run/*`: 関数実行 1 回(`:gpu-seconds` で課金)
- `:murakumo.effect/*`: 外部副作用。risk gate と承認状態を必ず持つ
- `:murakumo.audit/*`: 実行結果・課金イベント(Datomic as-of / Datalog 横断)

## Modal Equivalences

| Modal | cloud-murakumo |
|---|---|
| `@app.function(gpu="H100:4", image=..., schedule=...)` | `:murakumo.fn/*`(EDN datom。decorator ではなく値) |
| App / `modal deploy` | `:murakumo.app/*` + `clj -M:deploy`(承認 gate 経由) |
| Image(Dockerfile/`.pip_install`) | `:murakumo.image/*`(`:base`/`:pip`/`:env`) |
| Volume / Secret | `:murakumo.volume/*`(kotoba CID / B2)/ `:murakumo.secret/*`(参照のみ) |
| autoscale / `scaledown_window` / scale-to-zero | `cloud-murakumo.spec/desired-replicas`(target-concurrency, `[min max]` clamp) |
| container scheduler / region | `cloud-murakumo.scheduler`(murakumo lattice の bid auction) |
| 課金 / usage | `:murakumo.run/gpu-seconds` + `cloud-murakumo.scheduler/hourly-cost` |
| web_endpoint / `@web_endpoint` | `:murakumo.fn` の `:endpoint`(kotoba `on-http` trigger) |
| scheduled / `modal.Cron` | `:murakumo.fn` の `:schedule`(kotoba `on-tick` trigger) |

## Scheduler = murakumo lattice auction

`cloud-murakumo.scheduler/plan-placements` は **GPU-aware bin-packing auction**。

- 各 node が demand に **bid** する: `gpu単価 × 個数 + 断片化ペナルティ`(best-fit)。
- 最小入札 node に配置。同点は node 出現順で **決定的**に解決(再実行で同一結果)。
- `:gpu/class` は厳密一致、`:gpu/min-vram` は条件を満たす **最安 class** を選ぶ。
- 容量超過は静かに落とさず `:unscheduled` で返す(CLAUDE.md の「silent cap 禁止」と同型)。
- first-fit-decreasing(大きい GPU 要求から)で断片化を抑える。

これは murakumo の wadm 流 reconcile に GPU 制約を入れたもので、中央キュー無しの
leaderless auction という分散モデル(ADR-2606271600 §2)をそのまま継承する。

## Autoscale / scale-to-zero

`desired-replicas` は in-flight(処理待ち+処理中)を `target-concurrency` で割って ceil、
`[min max]` に clamp する。負荷ゼロは `min`(既定 0)まで縮退 = **scale-to-zero**。
serve 関数は idle-timeout(既定 300s)後に drain → cold へ戻す。

## Risk Gate

GPU 確保はコストなので Modal と違い **明示的に承認 gate を通す**(`cloud-itonami` の
approval inbox を継承)。`cloud-murakumo.schema/risk-of`:

- `:deploy` / `:scale-up` → `:financial`(承認必須。GPU を確保し課金が始まる)
- `:scale-down` → `:read-only`(自動)
- `:delete` → `:destructive`(承認必須)

`cloud-murakumo.runtime/reconcile` は desired vs current の差分を effect として propose
し、承認後にだけ murakumo 制御面 + kotoba XRPC へ transact する(fail-closed)。

## Transport / Placement reach

通信は ADR-2606271700(2 平面)に従う。serve endpoint の inference は **Live 面**
(libp2p QUIC, native fleet)、結果/モデル artifact の配布は **Read 面**(CID-over-HTTP)。
`:murakumo.node` の `:reach`(`:native` / `:http`)は murakumo `connect.edn` の reach 解決に
そのまま渡す(edge の `:l4` ノードは `:http` reach)。

## Implementation

正本ファイル(`orgs/gftdcojp/cloud-murakumo/`):

- `resources/murakumo.edn`: fleet / images / volumes / secrets / apps の正本(SSoT)
- `src/cloud_murakumo/schema.cljc`: datom 語彙 + risk gate
- `src/cloud_murakumo/spec.cljc`: EDN ロード、autoscale 展開、datom 射影
- `src/cloud_murakumo/scheduler.cljc`: GPU-aware auction(純粋・testable な中核)
- `src/cloud_murakumo/vllm.cljc`: serve 関数 → vLLM serve コマンド(minimax 等価)
- `src/cloud_murakumo/runtime.cljc`: placement 状態機械 + reconcile/承認 effect
- `src/cloud_murakumo/cli.clj`: plan / schedule / serve-cmd / deploy / doctor
- `test/cloud_murakumo/scheduler_test.cljc`

## Consequences

- gftdcojp の GPU serverless は単一の operating surface(`murakumo.edn` + `clj -M:*`)で
  扱える。Modal の decorator を覚える代わりに EDN datom を編集する。
- minimax-m2-modal は閉じず、cloud-murakumo の最初の app として継続利用できる
  (serve コマンドは `cloud-murakumo.vllm` が後方互換生成)。
- 配置・課金・autoscale が全て datom になるので、Datomic as-of / Datalog で
  「いつ・どの node に・いくら GPU を確保したか」を横断照会できる。
- GPU 確保が承認 gate を通るため、暴走 scale-up による課金事故を fail-closed で防ぐ。
- murakumo(制御面)は GPU を意識せず汎用のまま。GPU 制約は cloud-murakumo の
  scheduler に閉じ込める(関心の分離)。

## Verification Notes

2026-06-27 の実装検証(babashka v1.12.218 / clojure 1.12):

- `bb -cp src:resources:test ... cloud-murakumo.scheduler-test`: **7 tests, 17 assertions,
  0 failures, 0 errors**。厳密 class / min-vram 最安選択 / 容量超過の unscheduled /
  決定的 tie-break / hourly-cost / scale-to-zero / financial gate を確認
- `clj -M:doctor`(無負荷): `{:apps 3, :functions 4, :fleet/nodes 5, :fleet/total-gpus 36,
  :datoms 9, :placeable 0, :unscheduled 0, :ready? true}`。serve/batch は scale-to-zero で
  idle 時 replica 0 = `:placeable 0` が正
- `clj -M:schedule minimax-m27=80 kimi-k27=16 embed=20`:
  - minimax-m27 = ceil(80/32)=3 を max 2 に clamp → h100×4 を asagi に 2 replica(asagi 満杯)
  - kimi-k27 = 1 replica → h200×8 を midori(満杯)
  - embed = ceil(20/8)=3 → min-vram 24 で最安 l4 を sora に 3(util 0.75)
  - utilization `{:asagi 1.0, :kurenai 0.0, :midori 1.0, :ai 0.0, :sora 0.75}`、
    **estimated $86.72/h**(承認 gate へ)
- `clj -M:serve minimax-m27`:
  `vllm serve MiniMaxAI/MiniMax-M2.7 --served-model-name MiniMaxAI/MiniMax-M2.7
  --trust-remote-code --tensor-parallel-size 4 --max-model-len 65536
  --gpu-memory-utilization 0.92 --host 0.0.0.0 --port 8000 --tool-call-parser minimax_m2
  --reasoning-parser minimax_m2_append_think --api-key $LLM_KEY --enable-auto-tool-choice
  --enforce-eager`(minimax-m2-modal の生成と等価)
- `cloud-murakumo.runtime/reconcile`: scale-up が全て `:financial` / `:proposed` で
  返ることを確認(GPU 確保前に承認 inbox 経由)

実 GPU fleet・実 kotoba mesh への transact は `murakumo` 制御面 + `KOTOBA_URL`/`KOTOBA_GRAPH`
設定後に行う。本 checkout では純データ計算(plan/schedule/reconcile)までを検証済み。

## Closure

2026-06-28 closing。決定した基盤(Modal 等価 GPU serverless の datom 化)は実装・検証済み。

- 正本 API: `cloud-murakumo.{spec,scheduler,runtime,vllm,gen,ledger,gateway}` + CLI
  (`plan/schedule/serve-cmd/deploy/doctor/models/gen/bill`)。SSoT は `resources/murakumo.edn`。
- `:gen`(生成スタジオ backend)は ADR-2606272330 で本 ADR の上に拡張済み(schema/scheduler 無改造)。
- 公開面 murakumo.cloud(CLJS/Reagent/re-frame on Cloudflare)は ADR-2606272330(public-site)で closing。
- 残: 実 GPU fleet / kotoba mesh への transact、engine ワーカ実装、west manifest 登録は
  本 ADR の決定範囲外の運用フォローアップ。
