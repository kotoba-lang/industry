---
id: adr-2606291100-cleanup-worktree-branch-stash-pr-merge
title: "ADR-2606291100: cleanup runbook — worktree/branch/stash 調査 → PR → merge-2-main"
status: active
doc_type: adr
topic: git-operations
authoritative: true
last_verified: 2026-06-29
authoritative_for:
  - superproject + 子リポ群の cleanup 手順（worktree/branch/stash 調査 → PR 作成 → main merge）
  - scripts/cleanup.cljs の使い方と安全要件
related:
  - CLAUDE.md "## Git operations" / "## 標準作業の常時許可"
  - 90-docs/adr/2606241600-shallow-depth1-git-default.md
  - 90-docs/adr/2606272237-manifest-workflow-api-single-entry.md
supersedes: []
superseded_by: []
---

# ADR-2606291100: cleanup runbook — worktree/branch/stash 調査 → PR → merge-2-main

**Status**: accepted
**Date**: 2026-06-29
**Deciders**: Jun Kawasaki

## Context

オーナー指示:「subfolder 含め `git worktree list` / `branch` / `stash list` し、
PR/merge ができていないものは `pr create` / `merge 2 main`。これを **cleanup** という
メッセージで同じ処理をするように md で更新せよ。」

この処理を毎回手作業ではなく再利用可能な手順 + スクリプトに恒常化する。ただし本リポジトリは
superproject + 229 子リポ（west manifest）の巨大構成で、かつ CLAUDE.md のガードレール
（shallow ancestry 不信・force-push 禁止・manifest は API single-entry・オーナー未コミット WIP は
破棄せず stash 温存・main 常に同期優先）が効いている。よって「全子リポを自動 PR/merge」は
**ガードレール違反** になり得るため、処理は「survey（読取）→ 安全な close/merge のみ抽出 →
承認付きで実行」に構造化する。

## Decision

cleanup 処理を `scripts/cleanup.cljs`（babashka・読取専用 dry-run 既定）と本 runbook で恒常化する。

### スコープ原則（重要）

- **superproject のみ副作用を許す。** 子リポの未コミットは「要オーナー確認」として一覧化するだけ。
- 子リポの未コミット WIP を **自動 commit / push / PR / merge しない。** etzhayyim 系の
  `.well-known/did.json` 一斉変更・`chore/cljc-ssot-prune` 協調・`kotoba-code`/`manimani` の
  活発 WIP はすべてオーナー進行中の作業であり、自動化すればガードレール違反。
- **孤児 PR（head branch が origin から削除済みで mergeable=UNKNOWN/CONFLICTING）は close 対象。**
  merge 可能な PR の自動 merge は `--merge` 明示時のみ。
- **shallow な local の ancestry は不信。** ahead/behind・mergeable は GitHub API
  （server-side full history）で確定する（`gh api repos/<slug>/compare/main...<branch>`）。

### 手順

1. **dry survey**（既定・読取専用・副作用なし）:
   ```bash
   nbb scripts/cleanup.cljs
   ```
   出力: superproject の `worktree list` / `branch -a` / `stash list` → open PR の分類
   （`orphan` / `mergeable` / `conflict` / `unknown`）→ 子リポ「要オーナー確認」一覧。
2. **結果を読む**: `orphan` = close 候補（head branch 削除済み）。`mergeable` = merge 候補。
   `conflict/unknown` = 個別確認。子リポ一覧 = オーナーが個別に reconcile。
3. **安全処置の実行**（孤児 PR の close のみ）:
   ```bash
   nbb scripts/cleanup.cljs --apply
   ```
4. **merge の実行**（mergeable PR を server-side で。main 同期は git-push-main-sync-guard.cljs 別担保）:
   ```bash
   nbb scripts/cleanup.cljs --merge
   ```
5. **子リポ survey を省略**（superproject だけ速く見たい時）:
   ```bash
   nbb scripts/cleanup.cljs --subrepos
   ```

### ガードレール（必須）

- shallow ancestry で `git rev-list --count origin/main..HEAD` 等を信じない → server-side compare で確定。
- `git push --force` / `--force-with-lease` / `+refs` 禁止。`rebase` も基本禁止。FF できない乖離は
  最新 `origin/main` から clean branch/worktree を作り、必要 commit か patch だけを載せ直す。
- `manifest/west.yml` の main 反映は `repos.edn :manifest-workflow` の API single-entry 正経路。
- オーナー未コミット WIP は破棄せず `git stash`（drop せず温存）。衝突は marker 手編集でなく再生成。
- 子リポ WIP の自動 PR 化はしない。main は常に最優先で同期。

## Consequences

- cleanup が「survey → 安全な close/merge のみ」に収束し、誤ってオーナー WIP を破壊しない。
- 孤児 PR（head branch 削除済み）が構造的に検出・close される。
- 子リポの「要確認」一覧が都度生成され、オーナーが個別 reconcile する判断材料になる。
- mergeable PR の merge は `--merge` 明示時のみで、dry-run が既定（誤 merge 防止）。

## 実行ログ: 2026-06-29（初回）

### superproject

- open PR は #159「cleanup」（head `prune-gftdcojp-cljc`）1件 → server-side で head branch が
  origin から **削除済み**（404 Branch not found）・mergeable=UNKNOWN → **孤児 PR**。
  本日 11:03 に #160「cleanup」（branch `cleanup-260629`）が merge 済みで実体はそちらに入ったため、
  #159 は **close**（オーナー指示により close 済み）。
- 現ブランチ `docs/kotoba-murakumo-positioning-transport` は server-side compare で
  **ahead=2 / behind=129**（local の「451 ahead」は shallow 誤判定）。2 commit は #154/#156 の
  merge-commit（既に main 入り）で未 merge 実作業は無し → PR/merge 処置不要。
  129 behind なので main 同期は別作業。
- superproject の `git stash list` は空。

### 子リポ（229、要オーナー確認のみ・触らず）

`nbb scripts/cleanup.cljs` の「子リポ survey」節を参照。主なもの:

- etzhayyim 系 約100リポ: `.well-known/did.json` 一斉変更（kotoba RAD did:web 協調 WIP）。
  多くが `chore/cljc-ssot-prune` / `feat/actor-runtime-lib` ローカル branch + stash=1 を保持。
- `orgs/com-junkawasaki/kotoba-code`: dirty=19（durable/transcript 等）・`reconcile/mainsync-20260623`。
- `orgs/com-junkawasaki/manimani`: dirty=64・codex/* branches。
- `orgs/com-junkawasaki/langchain-clj`: `feat/openai-model`。
- `orgs/com-junkawasaki/murakumo`: feat/* x3。
- etzhayyim の `chore/cljc-ssot-prune` チェックアウト中リポ群: SSoT prune 協調。

これらはすべてオーナー WIP。本 runbook は commit/push/PR/merge しない。

### 本 runbook + スクリプトの land（2026-06-29）

- superproject の日常ブランチにはオーナー WIP（`manifest/west.yml` の pin 推進等）が残るため、
  本 runbook と `cleanup.cljs` は **専用 worktree に最新 `origin/main` から clean branch
  (`chore/cleanup-runbook`) を切り** そこに乗せた。長生き docs ブランチ（129 behind）には積まない。
- セッション中にオーナー運用でブランチ削除/切替えが起き、初回 commit が一時 **孤児 commit**
  （どの branch にも属さない SHA のみ）になった。`git show <sha>` で実体を確認のうえ
  **cherry-pick で clean branch に回収**。git は到達不能 commit を約2週間保持するので即座に消えないが、
  land は速やかに行うのが安全。
- `origin/main` がオーナー運用で force-update されることがある。本 runbook の作業ブランチは
  **常に最新 `origin/main` を基底**とする（`git fetch --depth 1 origin main` 後に分岐）。

## Verification

- `nbb scripts/cleanup.cljs` が dry-run で survey + PR 分類 + 子リポ一覧を出力し、副作用無いこと。
- `--apply` 実行後、close 対象の孤児 PR が `gh pr view <n> --json state` = `CLOSED`。
- 子リポで `git status` に変化無いこと（触っていない）。
- `scripts/cleanup.cljs` の paren balance が 0（`python3 -c "s=open('scripts/cleanup.cljs').read();print(s.count('(')-s.count(')'))"`）。
