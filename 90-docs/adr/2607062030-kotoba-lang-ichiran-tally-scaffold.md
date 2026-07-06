# ADR-2607062030: kotoba-lang/ichiran — 集計表生成 actor（雛形のみ）

**Status**: proposed(scaffold-only — governor/operation の中身は本 ADR の
範囲外。repo 自体は作るが実装は follow-up)
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki

## Context

ADR-2607062000（teian）と同じ依頼のうち「表」面。`kotoba-lang/sheets` は
workbook/tab/cell/formula/named-range/chart を pure EDN で保持するモデル
（実測確認済み — README の主張通り全フィールドがコードで裏付けられている）。
itonami ledger からの集計表自動生成は teian（資料作成）と生成的性質が近い
（LLM proposal → governor → 承認 → 配布）が、今回のスコープでは資料作成・
予定共有を優先実装し、表面は ADR 起票と repo 雛形のみに留める（オーナー確認
済み）。

## Decision

新規 repo `kotoba-lang/ichiran`（一覧）を起こすが、**本 ADR の実行範囲は
scaffold のみ**とする: README（将来の TallyGovernor 構想を明記）・
`deps.edn`（`kotoba-lang/sheets` への依存 + kekkai/tayori と同型の
langgraph/ed25519/ipns 3依存を先行登録）・`src/ichiran/` 配下の namespace
骨組み（`model.cljc`/`store.cljc`/`tallyport.cljc`/`governor.cljc`/
`phase.cljc`/`operation.cljc` は宣言のみ、docstring に
`"Status: proposed — 雛形のみ。governor/operation の実装は follow-up
（ADR-2607062030）"` と明記したstub）・最小1本の smoke テスト。

将来の実装（follow-up）で満たすべき設計方向だけ記録する:

- TallyGovernor の HARD 不変条件候補: teian と同型の `no-actuation` /
  `redaction-required`（財務集計は機微度が高い） / `tenant-isolation`。
- TallyPort protocol 候補: `fetch-workbook` `propose-revision!` `publish!`
  （承認後のみ）。

## Consequences

- (+) repo・manifest・deps.edn は先に確定するため、teian と対になる形で
  後から実装を差し込める。
- (−) governor/operation の実装が無いため、ichiran は現時点で cloud-itonami
  から呼び出せない（呼び出し配線もしない）。

## Execution

| 項目 | 状態 | 備考 |
|---|---|---|
| repo scaffold(README/deps.edn/namespace骨組み/smokeテスト) | ⏳ 未着手 | |
| `kotoba-lang/ichiran` GitHub repo 作成・push(public) | ⏳ 未着手 | |
| manifest 登録(`repos.edn` + `west.yml --entry ichiran`) | ⏳ 未着手 | |
| governor/operation 実装 | ⏸ 範囲外(follow-up) | 本 ADR の意図的な非対象 |

## References

- ADR-2607062000（teian）、ADR-2607062010（koyomi）、ADR-2607062020
  （shoko）— 同じ依頼から分岐
- `orgs/kotoba-lang/kekkai`、`orgs/kotoba-lang/tayori`（将来実装時の手本）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- 本 ADR とペアの .edn
