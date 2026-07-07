# ADR-2607062030: kotoba-lang/ichiran — 集計表生成 actor（tally-LLM ⊣ TallyGovernor）

**Status**: proposed(2026-07-06 の scaffold-only 決定を撤回し、フル実装へ
昇格。teian と同等の完成度を目指す — 2026-07-07 オーナー指示)
**Date**: 2026-07-06
**Amended**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

ADR-2607062000（teian）と同じ依頼のうち「表」面。`kotoba-lang/sheets` は
workbook/tab/cell/formula/named-range/chart を pure EDN で保持するモデル
（実測確認済み — README の主張通り全フィールドがコードで裏付けられている）。
itonami ledger からの集計表自動生成は teian（資料作成）と生成的性質が近い
（LLM proposal → governor → 承認 → 配布）。当初 scaffold-only としたが、
2026-07-07 にフル実装へ昇格。

teian/koyomi の実装 + 独立レビューから得られた教訓をあらかじめ織り込む:

- koyomi の confirmed bug #2（governor に「subject 存在」チェックが無く
  rogue tenant が素通り）— ichiran は `missing-workbook-violations`/
  `missing-activity-violations` を independent な hard check として最初から
  実装する。
- teian の confirmed bug #2（`:deck/publish` が draft 時のみ検証し publish
  時に再検証しない）— ichiran の `:tally/publish` も、配布の直前に
  `redaction-violations`/`tenant-violations` を再検証する。
- teian の confirmed bug #1（`MemStore.seed!` が per-id upsert 契約に違反）
  — ichiran の `MemStore.seed!` は最初から `DatomicStore.seed!` と同じ
  per-id upsert（`record-datom!` へ委譲）で実装する。

## Decision

**新規 repo `kotoba-lang/ichiran`（一覧）を起こし、tally-LLM ⊣
TallyGovernor 型の集計表生成 actor として実装する。** teian（資料作成）と
ほぼ同型 — content が slides の deck ではなく sheets の workbook である点が
主な違い。

1. **TallyTarget protocol（`ichiran.tallyport`）**: `fetch-workbook`
   `propose-revision!`（下書き commit）`publish!`（配布 — 承認後のみ）。
   既定は `mock-tallyport`。content は `kotoba-lang/sheets` の
   `sheets.model`（`workbook`/`tab`/`cell`/`named-range`/`chart` EDN）を
   そのまま保持する — ichiran は独自の集計表現を作らない。
2. **統一データモデル（`ichiran.model`）**: `draft`（activity-id/
   workbook-id/content(sheets EDN)/confidence/cites/redactions/status）—
   teian の `draft` と同型。
3. **二流路の StateGraph（`ichiran.operation`）** — teian と同型:
   - ingest（常時 ON・LLM 無し）: `:artifact/register`（既存集計表の登録）。
   - assess: `:tally/draft`（tally-LLM proposal: sheets EDN content +
     confidence + cites + redactions、effect は `:draft` 固定）→
     `:govern` → `:decide` → commit|escalate|hold。`:tally/publish`
     （配布）は **常に人間承認**。
4. **TallyGovernor（`ichiran.governor`）の HARD 不変条件**:
   - **no-actuation** — `:tally/draft` proposal の effect は `:draft` のみ。
   - **missing-subject**（独立・無条件）— 参照する workbook/activity が
     store に存在しない場合、必ず hard violation。
   - **redaction-required** — 財務集計は機微区分（財務/法務/人事）の
     citation が `:redactions` 無しなら hard violation。
   - **tenant-isolation** — draft の `:tenant`/`:repo` が activity の
     `:itonami.activity/repo` と不一致なら hard violation。
   - SOFT: confidence floor → escalate。`:tally/publish` は常に
     high-stakes（常に人間）で、**配布直前に redaction/tenant を再検証**
     （teian の publish-time re-check 教訓を最初から実装）。
5. **Phase 0→3**: teian と同型構成。
6. **注入 port（swap）**: Store（`MemStore` ‖ `DatomicStore`、per-id
   upsert の `seed!` を最初から実装）/ Advisor（mock ‖ `langchain.model`）/
   TallyTarget（mock ‖ 実 export + 実 Distributor、承認後のみ呼ばれる）。
7. **CACAO 自己発行**（`ichiran.cacao`）: `.ichiran/identity.edn`
   （gitignore）。
8. **台帳 = 集計表生成監査台帳（append-only）**。

### cloud-itonami からの利用

`deps.edn` に `io.github.kotoba-lang/ichiran {:local/root
"../../kotoba-lang/ichiran"}` は既に先行登録済み。`cloud_itonami.workspace`
への実配線（`:tally/publish` effect ハンドラ）は本 ADR の範囲外（別
follow-up）。

## Consequences

- (+) repo・manifest・deps.edn は先に確定しており、teian と対になる形で
  実装を差し込める。
- (+) teian/koyomi のレビューで見つかったバグクラスを設計段階で回避。
- (−) 実 Distributor・実 export の live 結合は未検証。既定は
  mock-tallyport による決定的 sim で動く。
- (−) cloud-itonami 側の実配線・UI は本 ADR の範囲外（別 PR）。

## Execution

| 項目 | 状態 | 備考 |
|---|---|---|
| repo scaffold(README/deps.edn/namespace骨組み/smokeテスト) | ✅ 完了 | 2026-07-06、scaffold-only 版として |
| フル実装への昇格(TallyTarget port・統一データモデル・StateGraph・governor・phase・store・CACAO 自己発行) | ⏳ 進行中 | |
| 独立レビュー | ⏳ 未着手 | |
| manifest pin 前進 | ⏳ 未着手 | |
| superproject `main` 反映 | ⏳ 未着手 | |

## References

- ADR-2607062000（teian）、ADR-2607062010（koyomi）、ADR-2607062020
  （shoko）— 同じ依頼から分岐、レビューで見つかったバグクラスの教訓元
- `orgs/kotoba-lang/kekkai`、`orgs/kotoba-lang/tayori`（実装時の手本）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- 本 ADR とペアの .edn
