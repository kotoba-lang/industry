---
name: closing
description: セッション終了・次 agent 引継ぎの標準手順（closing transaction）。ユーザーが「closing」「close this session」「wrap up」「終わりにして」「引き継ぎ」と言ったら発火。正本更新（ADR / gap ledger）→ default branch との差の監査 → 検証の固定 → PR 着地（push・PR・merge）→ 自分の作業物だけ cleanup → 自己完結した handoff、の 6 段を、要約で終わらせずに実行する。CLAUDE.md の `closing` 節から 2026-09-11 に切り出した正本（ADR-2609112300）。
---

# closing — 説明ではなく transaction

**CLAUDE.md の `closing` 節はここへ委譲している。** 完了条件は「説明を書いた」では
なく、正本更新・PR 処理・merge 確認・安全な cleanup・resume point 記録がすべて
終わったこと。cleanup の詳細は skill `git-cleanup-conflict`。

---

# CLAUDE.md に 2026-09-11 まで残っていた本文（逐語、ADR-2609112300）

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-11、ADR-2609112300。AGENTS.md の
読み込み上限 31,457 字に合わせて CLAUDE.md を不変条件だけに絞った）。CLAUDE.md 側には
skill を読まなくても効く規則だけが残っている。ここが理由・実測・罠の正本。

## `closing` — セッション終了・次 agent 引継ぎの標準手順

ユーザーが `closing`、`close this session`、`wrap up`、または同趣旨を指示したら、
単なる要約で終了せず、以下を一連の closing transaction として実行する。

1. **正本を更新する。** そのセッションの authoritative ADR / gap ledger に、
   実装済み、未実装、検証結果、default branch に着地済みか、resume point を記録する。
   branch 上で成功したことと `main` に存在することを混同しない。
2. **default branch との差を監査する。** 各対象 repo で remote default branch、
   merge-base、tree diff、既存PRを確認する。長期branchが現行機能を巻き戻す場合は
   mergeせず、最新 `origin/main` から fresh branch/worktree を作り、必要な差分だけを
   portする。rebase / force-pushは禁止。
3. **検証を固定する。** 実行したtest/check、その件数、失敗、未実行理由をADRとPRへ
   記載する。既知の別件failureは隠さず、今回の変更によるものかを区別する。
4. **PRを着地させる。** 対象変更だけを明示stageし、commit、push、ready PR作成、
   mergeability/check確認、mergeまで行う。conflicting/superseded PRは理由と後継を
   commentしてcloseする。外部CI障害などでmerge不能なら、PRを残しblockerを明記する。
5. **自分の作業物だけ cleanup する。** merge確認後、自分が作ったworktree、local
   branch、merged remote branchを削除する。他者のdirty/untracked/stash/branchは
   移動・削除せず、最終報告に残す。cleanup詳細は `git-cleanup-conflict` に従う。
6. **handoffを自己完結させる。** 最終報告には merged PR URL、default-branch commit、
   evidence commit、残存gapの優先順位、次の最初の具体的command/test、保全したowner WIP、
   open PR/worktree/stash/orphan監査結果を含める。次agentが会話履歴なしで再開できる
   粒度にする。

closing完了条件は「説明を書いた」ではなく、正本更新・PR処理・merge確認・安全な
cleanup・resume point記録がすべて終わったこと。未完項目があれば closing 自体を
完了扱いにせず、blockerとして明示する。

