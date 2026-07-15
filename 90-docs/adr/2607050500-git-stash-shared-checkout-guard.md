# ADR-2607050500: 共有 checkout での `git stash` 事故防止ワークフロー

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki

## Context

`shinshi-growth-actor`（ADR-2607040900）の登録 PR 準備中、superproject 本体
checkout 上で以下の事故が発生した:

1. 別セッション/エージェントが「無関係な pre-existing drift（`app-aozora`/
   `kotobase-client` の pin 退行、自分のものではない）」を発見し、破棄せず
   `git stash`（ラベル付き）で退避していた。
2. 別の作業（`shinshi-growth-actor` の manifest 登録コミットが stale local
   base 上にあったため、fresh `origin/main` から新規 branch を切って
   cherry-pick する作業）の途中で、「west.yml の regen が canonical か」を
   確認する目的で、深く考えずに `git stash && ... && git stash pop` という
   チェックポイント目的の無条件シーケンスを実行した。
3. `git stash` 実行時、当該作業ツリーには実際には変更が無かったため
   "No local changes to save" で新規エントリは積まれなかった。しかし直後の
   `git stash pop` は**対象を明示していなかった**ため、スタックの先頭
   （＝他エージェントが退避した無関係な stash）を無条件に pop してしまった。

実害はゼロだった（pop 後の working tree diff が空で、当該 pin 退行が自分の
branch に混入することはなかった）。しかし `git stash pop` は成功すると
自動的に該当エントリを `git stash list` から drop するため、この操作の
直後、他エージェントの WIP は一時的に**スタックから消えた**状態になった
（`git fsck --unreachable` でオブジェクト自体は未 GC のまま残っていたため
`git stash store` で復元できたが、これは幸運であり、手順ではない）。

根本原因は二重:

- (a) **変更の有無を確認せずに `git stash` を「チェックポイント」目的で
  呼んだ**（変更が無ければ最初から stash する理由がない）。
- (b) **pop/apply/drop を位置参照（暗黙の `stash@{0}`）で、対象を明示せずに
  呼んだ**。`git stash` のスタックはリポジトリ単位でグローバル・共有の
  資源であり、CLAUDE.md が既に指摘している「共有 checkout を並行
  エージェントが触る」問題（west checkout の `git checkout` によるブランチ
  切替が他セッションの未コミット編集を巻き戻す事故、ADR-2607011345 で
  文書化済み）と同型のクラスのバグである。stash スタックも例外ではない。

なお、本事故対応の過程で、CLAUDE.md が「PreToolUse フックで強制される」と
記述している `.claude/hooks/git-push-main-sync-guard.cljs` /
`west-pin-verify-guard.cljs` が、本 checkout にも `~/.claude/settings.json`
（`hooks: {}`）にも実在しないことを確認した。これらは現時点では**文書化された
運用規約であって機械的な強制ではない**。本 ADR のスコープはこの既存 gap の
解消ではなく git-stash 事故防止に限定するが、事実として記録しておく。

## Decision

**振る舞いルール（即時適用、全エージェント/セッション共通）**:

1. `git stash` は実際に変更がある時のみ呼ぶ。呼ぶ前に必ず
   `git status --short` 等でダーティ状態を確認し、クリーンなら何もしない
   （「念のため stash」を禁止する）。
2. stash を積む時は必ずラベルを付ける: `git stash push -u -m "<目的>-<識別子>"`。
   無題の bare `git stash` を禁止する。
3. 積んだ直後に `git stash list` でそのエントリの正確な参照（`stash@{0}` の
   SHA、`git rev-parse` で取得)を確保し、以後の pop/apply/drop は**その
   SHA を明示して**行う。位置参照（暗黙 `stash@{0}`）だけでの pop/apply/drop
   を禁止する。
4. stash を操作する前に必ず `git stash list` を確認し、自分が積んだ覚えの
   ないエントリが存在する場合はそれに一切触れない（drop/apply/pop しない）。
   持ち主不明の stash は `manifest/cleanup-workflow.md` の Retirement 手順で
   別途棚卸しする。
5. 上記のチェックポイント目的の stash 往復が必要になること自体が、共有
   checkout で検証作業をしているサインである。可能な限り、検証・確認だけの
   目的なら superproject 外の一時 worktree（既存の worktree-per-agent 方針、
   ADR-2607011345）で完結させ、共有 checkout の stash スタックには触れない。

**機械的強制（follow-up、未実装）**: `.claude/hooks/` に
`git-stash-guard.cljs`（既存の `git-push-main-sync-guard.cljs` /
`west-pin-verify-guard.cljs` と同型の PreToolUse hook）を追加し、
`git stash pop` / `git stash apply` / `git stash drop` が明示的な
`stash@{N}` または SHA 引数なしで呼ばれた場合、および `git stash` /
`git stash push` が `-m` メッセージなしで呼ばれた場合をブロックする。
本 ADR ではこの hook の実装・`.claude/settings.json` への配線は行わない
（設定変更は別途オーナー確認の上で実施）。

## Consequences

- (+) 上記ルールに従う限り、位置参照による誤 pop クラスの事故は構造的に
  発生しなくなる（対象不明な pop/apply/drop がそもそも許可されないため）。
- (+) 事故は実害ゼロで検知・復元できた（`git fsck --unreachable` +
  `git stash store` によるサルベージ手順も本 ADR で記録し、同種事故が
  再発した場合の復旧手順として再利用可能）。
- (−) 振る舞いルールのみでは徹底を個々のセッションの注意力に依存する。
  機械的強制（`git-stash-guard.cljs`）が実装されるまでは、本 ADR は運用規約
  レベルの是正に留まる。
- (−) CLAUDE.md が記述する既存 2 guard hook（push-sync / pin-verify）も
  本 checkout には実在しないことが判明した。これは本 ADR のスコープ外の
  別 gap として記録するのみ（本 ADR では解消しない）。

## References

- ADR-2607011345（west checkout でのブランチ切替が他セッションの未コミット
  編集を巻き戻す事故、worktree-per-agent 方針の根拠）
- `manifest/cleanup-workflow.md`（stash/branch 棚卸し・Retirement 手順）
- ADR-2607040900（本事故が発生した作業対象: shinshi-growth-actor 登録PR）
- 事故発生・復旧の実測: 2026-07-04、PR #298 (`chore/shinshi-growth-actor-registration`)
  作成過程。復旧に使ったコミット: `435dfb0a9fb831f35c1da0c01cc2d08bb0955adb`
  （`git stash store` で `stash@{0}` として復元）。
