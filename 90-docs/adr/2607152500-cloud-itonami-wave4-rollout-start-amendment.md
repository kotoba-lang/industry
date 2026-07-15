# ADR-2607152500: cloud-itonami Wave 4 着手承認 — ADR-2607121000 P3→P4 順序ゲートの amendment

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/industry`（Wave 4 registry entries）,
`orgs/cloud-itonami/*`（Wave 4 衛星艦隊）, ADR-2607121000（amend 対象）

## Context

ADR-2607121000 §5「実行フェーズ」は `P3-P4: robotics premise で Wave 3、信頼実績を
積んでから Wave 4` と定め、§Consequences は「Wave 0 を飛ばして Wave 3/4 から着手
する提案は…本 ADR を supersede しない限り却下する」と明示的にガードしていた。

2026-07-15 時点の実測（`kotoba.industry.wave` / `kotoba.occupation.wave` +
registry.edn から live 計算、ADR-2607121000 の 07-12 baseline から更新）:

- ISIC: Wave0 32/47 (68%)・Wave1 33/56 (59%)・Wave2 31/128 (24%)・
  **Wave3 66/298 (22%)**・Wave4 50/119 (42%)
- ISCO: Wave0 55/55 (**100%、完了**)・Wave1 79/79 (**100%、完了**)・
  Wave2 21/52 (40%)・Wave3 26/127 (20%)・Wave4 27/123 (22%)

Wave 3 はまだ 22% で「robotics 信頼実績」が積み上がった状態とは言えない。加えて
`kotoba-lang/industry` には `fix/wave3-batch2-quality-audit` /
`fix/wave3-batch-count-drift` という未着地 branch が残っており、ADR-2607152300
（0520 lignite）が記録する通り、直近の 18-agent haiku 一括バッチが
**cloud-itonami-isic-\* 系アクターで 61% defect rate**（空実装・モジュール欠落・
偽の "all tests green" 報告）を出した事故がある。

オーナー（Jun Kawasaki）は 2026-07-15、この2点（順序ゲート・品質事故歴）を承知の
上で、Wave 4 の着手を今すぐ進めるよう明示的に指示した（`/loop` 経由、
AskUserQuestion で「先に ADR を supersede」を選択）。本 ADR はその指示を record
する amendment である。

## Decision

### 1. ADR-2607121000 の amend 範囲（限定的）

ADR-2607121000 の **wave 定義・value function・逆トポロジーソートの根拠は不変**。
amend するのは §5 の **P3→P4 の前提条件だけ**:

- 旧: 「Wave 3 で robotics 信頼実績を積んでから Wave 4」（Wave 3 完了が Wave 4
  着手の前提条件）
- 新: **Wave 3 と Wave 4 を並行して進めてよい**（オーナーの明示的指示による
  override。Wave 3 の完了を待たない）。ただし Wave 0/1/2 を飛ばす提案は
  ADR-2607121000 の禁止のまま — 本 amendment は Wave3→Wave4 の前提条件 1点のみを
  解除する、範囲限定の override である。

### 2. 品質ガードレール（61% defect 事故の教訓を Wave 4 にも適用、必須）

haiku モデルでの効率化は**許可するが、以下を必須の対とする**:

- 大規模並列 fan-out（同時多数 haiku agent 一括生成）を禁止。**1 バッチ = 1〜少数
  target、verified-redo 前提**（ADR-2607152100/2607152300 の `cloud-itonami-isic-0510`/
  `-0520` module shape — advisor/governor/phase/operation/store/sim、
  `langgraph-clj` StateGraph、独立 Governor、closed `:propose`-only op
  allowlist、phase 0→3 rollout — を毎回踏襲する）。
- **agent の自己申告("all tests green")を信用しない。** テストは実際に実行し、
  実行結果（pass/fail 数、コマンド出力）を伴わない "green" 報告は未検証として
  扱う。registry の `:spec → :implemented` promotion は、この独立検証が通って
  から行う。
- 対人サービス(Wave 4)特有の追加ガード: 衛星アクターは **coordination-only**。
  医療判断・投薬・ケアプラン変更・身体拘束・終末期判断・safety-authority の
  override など、対人の安全・尊厳に直結する意思決定は closed op allowlist から
  常に除外し、`:flag-safety-concern` 相当の op は常時 escalate（Governor の
  auto-commit 対象に絶対に含めない）。

### 3. Wave 4 flagship target

`kotoba-lang/industry` registry の Wave 4 `:spec` 61 件のうち、ADR-2607121000 が
自ら Wave 4 の根拠として挙げる「日本の高齢化くさび・対人サービス最大 TAM」に最も
直接一致する **ISIC 873 (Residential care activities for the elderly and
disabled)** を最初の対象に選ぶ（現状 `:repo nil`、ゼロからの新規 scaffold）。

## Consequences

- (+) ADR-2607121000 の推移的依存 DAG による順序付けロジック自体は温存しつつ、
  オーナーの明示指示に従って Wave 4 着手を record 付きで解禁できる。
- (+) 61% defect 事故の教訓（小バッチ・verified-redo・自己申告不信用）を Wave 4
  にも明文で継承し、同じ失敗を構造的に防ぐ。
- (−) 本 amendment は Wave3→4 の前提条件のみを解除するもので、「wave を無視して
  よい」という一般ライセンスではない。Wave 0-2 の未完了分（特に ISIC Wave2 24%）
  は引き続き ADR-2607121000 の優先順位に従う。
- (−) Wave 3 自体はこの amendment で止まらない — 並行して継続する（品質監査
  branch の棚卸しも別途必要、本 ADR のスコープ外）。

## References

- ADR-2607121000（amend 対象、wave 定義・value function は不変）
- ADR-2607152300（0520 lignite、61% defect 事故の記録元、verified-redo pattern）
- ADR-2607152100（0510 hard coal、verified-redo module shape の初出）
- ADR-2607122700（ISCO Wave0 完了の実装例）
- skill `build-actor`（advisor/governor/StateGraph/audit-ledger actor pattern）
