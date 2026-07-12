# ADR-2607121900: Shippify置き換え — cloud-itonami-isic-5320（CourierOps-LLM ⊣ Courier Governor）+ kotoba-lang/omise + kotoba-lang/okaimono

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

「Shippify の置き換えとなる kotoba-lang / cloud-itonami の repo は何か、成熟度は」
を横断調査した結果:

- **`shippify` を名指しした repo・compat actor は存在しない**（ワークスペース全文
  検索・GitHub org 検索・compat-actor batch 候補118件のいずれにも無い）。
- 近傍の既存資産は shipping SaaS の clean-room compat 3件
  （`kotoba-lang/com-shippo`/`com-shipstation`/`com-shipbob` — L4 CRUD surface、
  live engine 未接続）、capability library `kotoba-lang/logistics`（25 assertions、
  cloud-itonami-4920 向け）、governed actor `cloud-itonami-isic-4920`
  （Community Freight Transport、`:implemented`）。
- **ラストマイル配送（courier）の ISIC Rev.5 5320 は fleet 全体で唯一の
  logistics 系空白**だった（Division 53 の blueprint はゼロ）。Shippify のコア
  （店舗→顧客の当日配送 orchestration: 集荷・配車・追跡・POD・精算）に正面から
  対応する blueprint が無い。

オーナー指示「では設計実装, omise, okaimono」により、置き換えを
**お店（omise）× お買い物（okaimono）× 配送（courier）** の3層で新規実装した。

## Decision

### 1. `kotoba-lang/omise` — 店舗 capability（Apache-2.0、west 登録）

pure `.cljc`（JVM/cljs/SCI/GraalVM 可搬、clock アクセス無し — 判定時刻は常に
呼び出し側が渡す）: 店舗レコード（:active/:suspended/:closed）、週次営業時間
window と `open-at?`（開始含む・終了含まず）、**`pickup-available?`**（= :active
∧ :pickup-ready? ∧ open — Courier Governor が配車前に問う唯一の質問）、
pickup-point、haversine `distance-km`。ui（read-only operator console、
html+css）/ export（RFC-4180 CSV / JSON）付き。**52 assertions 全緑**。

### 2. `kotoba-lang/okaimono` — 注文 capability（Apache-2.0、west 登録）

pure `.cljc`: 明細 `line`（qty 正整数・単価非負を構築時検証）、注文レコード、
`total`/`item-count`、**明示的な遷移表**
`:placed → :confirmed → :packed → :handed-over → :delivered`（:cancelled は
hand-over まで。courier に渡った荷物はキャンセル不可）、**`dispatchable?`**
（= :packed のみ — Governor が配車前に問う唯一の質問）。ui / export 付き。
**45 assertions 全緑**。

### 3. `cloud-itonami-isic-5320` — Community Last-Mile Courier（AGPL-3.0、fleet 慣例で west 非登録）

isic-4920 と同型の governed actor（langgraph StateGraph + 独立 Governor +
Phase 0→3 + append-only ledger + MemStore ≡ DatomicStore contract）:
**CourierOps-LLM ⊣ Courier Governor**（`:courier-governor`）。entity は
`delivery` 1つに dispatch → settle が順次適用（4920 の shipment 型。
`:dispatched?`/`:settled?` 専用 boolean、:status 値でのガード禁止の規律を踏襲）。

**fleet 初の 3-capability-library 統合**（retailops/4711・freightops/4920 の
1-library 統合を拡張）。Governor の新規チェックは再実装でなく委譲:

| check | 委譲先 | HOLD 条件 |
|---|---|---|
| `:tracking-number-invalid` | `kotoba.logistics/tracking-valid?` | 追跡番号が構造検証に失敗 |
| `:store-pickup-unavailable`（fleet 初の店舗側 pickup-window check） | `kotoba.omise/pickup-available?` | 集荷時刻に店舗が閉店/停止/集荷不可 |
| `:order-not-dispatchable`（fleet 初の注文 lifecycle check） | `kotoba.okaimono/dispatchable?` | 注文が :packed でない |
| `:pod-unconfirmed` | — | POD 未確認での精算 |

これに spec-basis / evidence-incomplete / delivery-exception-unresolved /
already-dispatched / already-settled / confidence+actuation gate（4920 同型）。
`:delivery/dispatch`/`:delivery/settle` はどの phase でも auto-commit しない
（phase 表と high-stakes gate の 2 層で独立に強制）。

facts R0 は JPN/USA/GBR/DEU の courier 特化引用（JPN: 貨物自動車運送事業法
第36条 貨物軽自動車運送事業の届出 + 標準宅配便運送約款 第25条 責任限度額;
GBR/DEU は軽車両の免許適用除外を entry 内に正直に記載）。未登録法域は HOLD。

**40 tests / 200 assertions 全緑**（facts/registry/phase/governor contract/
store contract 両バックエンド）、`clojure -M:dev:run` で clean walk + 全
HARD-hold シナリオが E2E 動作、clj-kondo errors 0。blueprint.edn は
`:itonami.blueprint/maturity :implemented`（起票時から implemented — blueprint
先行ではなく実装同時公開）。

### 4. 境界（reimplement しない非対象）

- `com-shippo`/`com-shipstation`/`com-shipbob` — 外部 shipping SaaS の
  clean-room API 互換層。5320 は API 互換ではなく open business の置き換え。
  併存（互換が要る移行経路では compat、運営は 5320）。
- `cloud-itonami-isic-4920` — 地域貨物（B2B 混載/多区間 POD chain）。5320 は
  店舗→顧客のラストマイル。tracking 契約は logistics を共用。
- 経路最適化・実配車 fleet/決済統合は R0 非対象（follow-up slice）。

## Consequences / follow-ups

- omise/okaimono は west 登録（repos.edn → gen-west-manifest --entry の最小
  diff）。isic-5320 は cloud-itonami org の fleet 慣例（west 非登録、
  orgs/cloud-itonami/ 配下の standalone checkout）に従う。
- follow-up: ISIC 5310（郵便）は引き続き空白。route optimization
  （blueprint `:optional-technologies`）、実 courier fleet 統合、
  kotoba-server SSoT 切替（langchain.kotoba-db seam は実装済み）。
- 実装 repo: `kotoba-lang/omise` / `kotoba-lang/okaimono` /
  `cloud-itonami/cloud-itonami-isic-5320`（詳細設計は同 repo
  `docs/adr/0001-architecture.md`）。
