# ADR-2607062020: kotoba-lang/shoko — 保管ガバナンス actor（雛形のみ）

**Status**: proposed(scaffold-only — governor/operation の中身は本 ADR の
範囲外。repo 自体は作るが実装は follow-up)
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki

## Context

ADR-2607062000（teian）と同じ依頼のうち「保管」面。`kotoba-lang/drive` は
file/folder を pure EDN で保持するモデルだが、アクセス制御・共有・監査の概念が
一切無い（実測確認済み — `acl|access-control|permission|share` を grep して
ゼロヒット、`validate.cljc` は構造検証のみ）。teian（資料）・koyomi（予定）が
生成する成果物（デッキ・ICS 等）を外部に共有する前段の「誰が何を見られるか」を
governor で強制する層が将来必要になるが、今回のスコープでは**資料作成・予定
共有を優先実装**し、保管面は ADR 起票と repo 雛形のみに留める（オーナー確認
済み）。

## Decision

新規 repo `kotoba-lang/shoko`（書庫）を起こすが、**本 ADR の実行範囲は
scaffold のみ**とする: README（将来の ArchiveGovernor 構想を明記）・
`deps.edn`（`kotoba-lang/drive` への依存 + kekkai/tayori と同型の
langgraph/ed25519/ipns 3依存を先行登録）・`src/shoko/` 配下の namespace
骨組み（`model.cljc`/`store.cljc`/`archiveport.cljc`/`governor.cljc`/
`phase.cljc`/`operation.cljc` は宣言のみ、docstring に
`"Status: proposed — 雛形のみ。governor/operation の実装は follow-up
（ADR-2607062020）"` と明記したstub）・最小1本の smoke テスト
（namespace が load できることのみ確認）。

将来の実装（follow-up、別 ADR または本 ADR への追記）で満たすべき設計方向
だけ記録する:

- ArchiveGovernor の HARD 不変条件候補: `no-actuation`（proposal は
  `:draft` のみ）、`share-requires-acl`（`drive.model` 自体に無い ACL 概念を
  shoko 側で持ち、未許可の共有を hard violation にする）、`tenant-isolation`。
- ArchivePort protocol 候補: `fetch-file` `propose-revision!` `share!`
  （承認後のみ）。

## Consequences

- (+) repo・manifest 登録・deps.edn の依存関係は先に確定するため、
  cloud-itonami 側の deps.edn にも `io.github.kotoba-lang/shoko` を
  先行登録でき、後で実装を差し込むだけで済む。
- (−) governor/operation の実装が無いため、shoko は現時点で cloud-itonami
  から呼び出せない（呼び出し配線もしない）。

## Execution

| 項目 | 状態 | 備考 |
|---|---|---|
| repo scaffold(README/deps.edn/namespace骨組み/smokeテスト) | ⏳ 未着手 | |
| `kotoba-lang/shoko` GitHub repo 作成・push(public) | ⏳ 未着手 | |
| manifest 登録(`repos.edn` + `west.yml --entry shoko`) | ⏳ 未着手 | |
| governor/operation 実装 | ⏸ 範囲外(follow-up) | 本 ADR の意図的な非対象 |

## References

- ADR-2607062000（teian）、ADR-2607062010（koyomi）— 同じ依頼から分岐
- `orgs/kotoba-lang/kekkai`、`orgs/kotoba-lang/tayori`（将来実装時の手本）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- 本 ADR とペアの .edn
