# ADR-2607141600: Store seam の共通化 — `kotoba-lang/langchain-store` 抽出 + 段階移行計画

**Status**: accepted（増分1 実施済み。190 repo の全面移行は増分2以降、段階的）
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki
**Scope**: 新規 `orgs/kotoba-lang/langchain-store`、`manifest/{west.yml,repos.edn}`（登録）、
`orgs/cloud-itonami/cloud-itonami-isic-6611-cryptoexchange`（reference adopter）

## Context

ADR-2607141400 で「actor Store seam（MemStore ≡ DatomicStore over `langchain.db`）が
263 repo で複製、共有先 lib なし」を構造課題として記録し、別 ADR + 段階 rollout に
委ねた。本 ADR がそれ。

定量調査（2026-07-14）:
- **`(defn- enc [v] (pr-str v))` と `(defn- dec* [s] (when s (edn/read-string s)))` が
  190 の `store.cljc` に完全同一で複製**（`grep -c` で 190 件、byte-identical）。
- あわせて各 DatomicStore は `:db.unique/identity` schema、`langchain.db` の
  `q`/`transact!`/`pull`、EDN-blob 変換（compound を string 化して langchain.db に
  sub-entity 展開させない慣例）を反復している。
- ただし **pull↔tx の field 列は domain 固有**（application/party/... vs
  ledger-event/order-event）。共通なのは「機構」、可変なのは「field spec」。

## Decision

1. **共有 lib `kotoba-lang/langchain-store` を抽出**（public, AGPL, portable `.cljc`）。
   確実に共通な機構だけを収める:
   - `enc`/`dec*` — EDN-blob コーデック（190 完全複製の実体）。pure・zero-dep。
   - `identity-schema` — `:db.unique/identity` schema builder。pure。
   - `read-stream`/`append-blob!` — seq-keyed EDN-blob event-log の read/append
     （event-sourced store 用。`langchain.db` 依存）。
   CLJS(primary)+JVM(compat) 4 tests / 13 assertions green。

2. **domain 側は自分の `Store` protocol と field/pull 整形を保持**。この lib は
   「下の substrate」であって framework ではない（各 store の domain wiring は残す）。

3. **reference adopter = `cryptoexchange.store`**。ローカルの
   enc/dec*/schema/read-stream と `clojure.edn`/`cljs.reader` require を撤去して
   lib に委譲。挙動不変（store contract の MemStore≡DatomicStore fold 一致含め
   CLJS 71 / JVM 87 tests green）。

4. **west 登録**: west.yml entry（pin `9fe115d` == main tip）+ repos.edn
   `:extra-projects`。fresh origin/main worktree で 2 ファイル最小編集 →
   server-side merge（`049ae17`）。pin 検証通過。

5. **190 repo の全面移行はこの ADR では行わない（段階的）**。理由: ①同時改変は
   fleet regression リスク、②各 store は現に稼働中、③entity-store の pull/tx
   boilerplate 削減には field-spec ヘルパー（増分2）が要る。移行順:
   - **増分1（本 ADR）**: lib 発行 + codec/schema/event 機構 + cryptoexchange 採用。
   - **増分2**: `pull->map`/`map->tx` を field-spec 駆動で generalize（entity-store の
     反復削減）。設計が固まったら別 ADR。
   - **増分3**: 新規 actor は最初から lib 採用（build-actor skill を更新）、既存は
     触るついでに漸進採用（drift scan で未採用を可視化）。「190 を一括で書き換える」は
     しない。

## Consequences

- (+) fleet で最も複製された store コーデックに単一の監査済み実装ができ、以後
  複製が増えない。新規 actor は最初から共有できる。
- (+) cryptoexchange.store が実証。event-sourced store の機構は lib に集約済み。
- (+) `langchain.db` の `:db-api` swap（kotoba-server pod 化）も lib 側の 1 箇所で効く。
- (−) 190 の既存複製は未移行（意図的・段階的）。codec 以外（entity pull/tx）の
  共通化は増分2 の field-spec ヘルパー待ち。
- (−) lib は薄い（機構のみ）。domain-shaped な部分は本質的に共通化できず、
  「seam は domain 形状」という事実は変わらない — 共有できるのは substrate まで。

## Artifacts

- https://github.com/kotoba-lang/langchain-store （initial `9fe115d`）
- superproject west.yml/repos.edn `049ae17`（登録）
- 衛星 `23f20c1`（cryptoexchange.store 採用）
- 本 ADR とペアの `.edn`

## Addendum 1（2026-07-14）: 増分2 実施 + entity-store 初移行（6511）

増分2（field-spec 駆動の entity ヘルパー）を実装し、entity-store を初めて実移行した。

- **lib に field-spec ヘルパー追加**（`langchain-store` main `0a5298d`、west pin 前進
  `f8acaab`）: `map->tx` / `pull->map` / `pull-pattern`。field-spec
  `{logical-key {:attr :ns/attr :blob? :default :coerce}}` で entity の
  `x->tx`/`pull->x`/`x-pull` の三つ組をデータ駆動に畳む。hand-written store の意味論を
  厳密保存（some? gate = false も present、nil identity → nil、blob は default 付き
  decode、`:coerce` は read 時変換）。6511 の application/party を写した spec で実
  `langchain.db` conn 往復テスト。CLJS+JVM 8 tests / 22 assertions green。
- **6511（underwriting.store）を reference entity-store adopter として移行**
  （6511 main `952b098`、増分3 の「触るついでに漸進移行」の第一号）: schema →
  `identity-schema`、enc/dec* → lib 委譲、app/party の三つ組 → field-spec + generic
  呼び出し。kyc/assessment/ledger/binding/sequence の custom-query 部は自前 wiring
  維持（共有 enc/dec* は利用）。**挙動不変**（CLJS primary + JVM とも 37 tests /
  462 assertions green、MemStore≡DatomicStore store contract の app/party 往復含む）、
  clj-kondo 0。store.cljc の entity boilerplate が spec に集約。
- これで増分2 は完了。増分3（新規 actor デフォルト採用 = build-actor skill 更新、
  既存の on-touch 漸進移行、drift scan で未採用可視化）は継続タスク。6511 は
  cloud-itonami actor で west 非登録のため west pin 前進は不要（GitHub main へ直接）。

## Addendum 2（2026-07-14）: 増分3 可視化 — adoption scan + baseline

増分3（漸進移行）の進捗を追跡する scan を追加した。

- **`scripts/langchain-store-adoption-scan.cljs`**（nbb、read-only）: `store.cljc` を
  走査し `:adopted`（`langchain-store.core` を require）/ `:hand-rolled`（自前の
  `enc`/`dec*` コーデックが残存＝移行 backlog）/ `:other`（MemStore-only 等、要目視）に
  分類。summary + hand-rolled backlog リスト（= 移行対象）を出力。`npx nbb` で実行確認。
- **baseline スナップショット**（`orgs/cloud-itonami`、2026-07-14）: total 334 /
  **adopted 1**（6511）/ **hand-rolled 221** / other 112。この 221 が increment-3 の
  「触るついでに漸進移行」backlog で、scan を回すたびに adopted が増え hand-rolled が
  減るのを追える（一括書き換えはしない方針は不変）。cryptoexchange.store も adopter
  だが `orgs/cloud-itonami` 配下に checkout されていないため local scan には出ない。

## References

- ADR-2607141400（Store seam 複製所見 — 本 ADR の出典）
- ADR-2607141200（cryptoexchange — reference adopter の親）
- skill `build-actor`（Store seam 複製規約 — 増分3 で更新対象）
