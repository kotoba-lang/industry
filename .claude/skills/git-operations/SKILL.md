---
name: git-operations
description: この superproject と west 管理の子リポで git を触るときの正本 — shallow を使わない理由と unshallow の確かめ方、ancestry / merge-base 判定が嘘をつく条件、`(forced update)` と `unrelated histories` が force-push を意味しない理由、west.yml を安全に変える唯一の経路（GitHub API single-entry commit）、pin 検証と pin 鮮度、main 同期・rebase 禁止・force-push 禁止・本番 deploy の包含条件、未コミット変更で同期がブロックされたときの安全な順序、worktree が object store を隔離しない話。「shallow」「unshallow」「merge-base がおかしい」「forced update」「force push していいか」「pull して」「main に同期」「deploy 前の確認」「worktree」で発火。CLAUDE.md の Git operations 節から切り出した正本。
---

# Git operations（詳細）

**CLAUDE.md の「Git operations」節はここへ委譲している。** CLAUDE.md 側には
skill を読まなくても効く禁止・手順だけが残っており、理由・実測・罠はこの文書が正本。
pin 前進の操作面は skill `west-pin-advance`、stash / branch / PR の棚卸しは
skill `git-cleanup-conflict`。

以下は CLAUDE.md から**逐語で**移した本文である（2026-09-08、ADR-2609081000）。

## Git operations

- **shallow（`--depth 1`）は使わない。full 履歴がデフォルト**（2026-07-21、
  ADR-2607211600。ADR-2606241600/2606302100 の shallow 既定を reverse）。
  west が `clone-depth` を fetch のたびに再適用し、触るたびに新しい shallow
  graft（親情報を持たない境界コミット）を作り続けていたことが、下記の
  「forced update」偽陽性・pin 到達失敗を繰り返し引き起こす根本原因だった
  （実測: superproject `.git` が 226 shallow boundary / 18GB に肥大していたのに
  reachable commit はわずか2件）。

  ```bash
  git fetch origin
  git pull --ff-only
  west update --fetch smart        # 各 project を full 履歴で取得
  ```

  大容量バイナリを含む heavy project（旧 `manifest/repos.edn` `:heavy`）も
  含め、2026-07-21 にオーナー判断で全 unshallow する決定をした。disk/帯域コストより
  ancestry の正しさを優先する。恒久的な disk 対策は shallow ではなく
  B2 + DataLad への移行（skill `large-binary-datalad`）。

  **ただしその unshallow は完了していない**（ADR-2608124400。この節は
  2026-08-12 まで「全 unshallow 済み」と完了形で書いていたが、事実ではなかった）。
  shallow のまま残っている子リポがあり、**superproject root 自身も retirement の
  後に ad-hoc な `--depth` fetch で shallow 化されていた**（実行者は特定できて
  いない。west ではないことは実測済み）。**shallow clone の ancestry 回答は
  間違っていて、しかも権威があるように見える** — 実測では「その commit は stale な
  side branch からしか到達できない」と答えたが、実際は `main` の 643 commit 手前に
  在った。したがって下記「マージ / ancestry 判定」がローカル解決を勧めるのは
  **full 履歴が実在する repo でだけ**正しい。判定を出す前に確かめる:

  ```bash
  git rev-parse --is-shallow-repository   # true なら、その repo の ancestry 判定を信用しない
  git fetch --unshallow                   # 直す
  ```

  ⚠ **`git fetch` の `--dry-run` は preview ではない** — ref 更新を飛ばすだけで
  fetch 自体は実行される（`--dry-run --unshallow` が実際に unshallow を完了させた）。

- **マージ / ancestry 判定（full 履歴なら通常は素直に解決する）。**
  `merge-base` / `--is-ancestor` / `rev-list --count` はローカルでそのまま
  正しく解決する（旧 shallow 既定では graft 境界の外に共通祖先があると
  誤判定した）。外部から持ち込まれた一時的な shallow clone と比較する必要が
  生じた時だけ、その場で GitHub 側に計算させる:

  ```bash
  BASE=$(gh api repos/<org>/<repo>/compare/main...<branch> --jq .merge_base_commit.sha)
  git fetch origin "$BASE"      # full 履歴なのでそのまま繋がる
  ```

  さらに、**manifest の pin 前進のような単純更新は、ローカルでマージを戦うより
  GitHub API でサーバ側にクリーン commit を起こす方が確実かつ安い**（optimistic
  lock で conflict が構造的に発生しない）。実例: PR #61 / #62 / #86 は main の
  tree をベースにクリーン commit を API で作成してマージした（#86 は 31 リポの
  west 移行を regression なしで取り込み）。

- **`git fetch` の `(forced update)` 表示や `git merge` の
  `fatal: refusing to merge unrelated histories` は、それ単独では本物の
  force-push と断定しない。** 旧 shallow 既定では `--depth 1` フェッチのたびに
  新しい shallow graft ができ、upstream が**純粋な fast-forward で前進しただけ**
  でも同じ症状（`(forced update)` 表示・`unrelated histories` エラー）が出て
  いた（実測 2026-07-01、`root` superproject: 6 commit 遅れの純前進で両症状が
  発生。これが ADR-2607211600 で shallow 既定を撤回した主因）。full 履歴の今は
  この graft 由来の偽陽性は構造的に起きないが、判定に迷ったら GitHub API で
  比較する:

  ```bash
  gh api repos/<org>/<repo>/compare/<old-local-tip>...<new-origin-tip> \
    --jq '{status, ahead_by, behind_by, merge_base_commit: .merge_base_commit.sha}'
  # status:"ahead" かつ behind_by:0 かつ merge_base_commit == old-local-tip なら
  # 純粋な fast-forward。diverged や merge_base が別物なら本物の force-push。
  ```

  本物の force-push と判明した場合は、下記「force-push は禁止」節の対応
  （ユーザーへの報告）に進む。

- **`manifest/west.yml` への変更（登録 / rename / pin 前進）は GitHub API の
  サーバ側 single-entry commit を「唯一の正経路」にする。** west.yml は生成物
  （手書き禁止）なので、行指向 pin の textual 3-way merge はアンチパターンで、
  conflict marker の手編集は **pin を静かに壊す**。**登録・rename・pin 前進は
  `--entry <name>` で当該 entry のみの最小 diff を生成する — wholesale 再生成
  commit は禁止**（1 件の登録のつもりが未 push HEAD 由来の壊れた pin を 44 件
  main に流した実事故 `90852b86` の再発防止）。

- **west.yml の pin 変更はサーバ側 pin 検証を必ず通す**（`scripts/verify-west-pins.cljs`、
  ADR-2607022900）。pin に許されるのは「上流 repo の default branch から到達可能な
  commit」だけ — ①存在（未 push のローカル HEAD の pin 化は禁止）②default branch
  到達性 ③旧 pin からの前進（behind = 静かな pin 退行）。判定は GitHub API で行い、
  **ローカルの ancestry 判定だけに頼らない**。強制するのは PreToolUse hook
  `.claude/hooks/west-pin-verify-guard.cljs` と murakumo fleet の
  `root-west-pin-policy` gate。

- **`git push` / `git pull` / `west update` の前に、manifest の pin が upstream
  GitHub の最新から取り残されていないか（pin 鮮度）を必ず確認する。** `west update`
  は pin へ checkout を合わせるだけで、GitHub 側の新しいコミットを pin に反映する
  コマンドではない。

  **上記 3 点の手順・コマンド・実測済みの罠は skill `west-pin-advance`。**

### pin の既定状態は「upstream default branch の tip」（repo-wide mandatory、2026-08-20）

**オーナー指示（2026-08-20）「west pull, remote pull また基本的に pin を最新に進める
運用となるように」。** pin が upstream の default branch より遅れているのは、放置して
よい平常状態ではなく**是正対象**である。

- **`git pull` / `west update` /「pull して」の類を指示されたら、checkout を pin に
  合わせるだけで終わらせない。** pin 鮮度まで見て、遅れているものは前進させる。
  「pull」は 3 つの別物を含む: (1) superproject を origin/main に合わせる
  (2) pin を各 repo の default branch tip に進める (3) checkout を pin に合わせる。
  (2) を落とすと、(1) と (3) をいくら回しても workspace は古いまま止まる。
- **前進の経路は変わらない** —— `scripts/west-pin-put.cljs <entry> HEAD`（1 件）か
  `scripts/west-pin-put-batch.cljs`（多件、1 commit に束ねる）。どちらも
  (1) default branch 到達性 (2) 旧 pin からの前進 (3) blob SHA precondition を
  **entry ごとに**検査する。速いから検査を省く、はしない。
- **repo の中の pin も同じ規則に従う。** `deps.edn` の `:git/sha`、lock ファイル、
  `resources/*.edn` に焼いた sha —— どれも「upstream の default branch から到達
  可能」でなければならない。**west pin には `verify-west-pins` という gate があるが、
  `deps.edn` の pin には無い。** 実測 2026-08-20: `kotoba-native` の deps.edn は
  `kotoba-codegen` を `c85088b` に固定していたが、その commit は codegen の main に
  無く、未 merge branch `agent/aarch64-madd-mc` にしかなかった（main はそこから
  5 commit 遅れ）。branch が消えるか force-update された時点で production の依存が
  壊れる。**未 merge branch 上の commit を pin にしない。**
- **例外は「進めない理由を書いた」ときだけ。** 上流の tip が壊れている、API が
  互換性を壊した、意図的に古い挙動に留めている —— どれも正当だが、pin の隣か
  commit message にそう書く。**黙って遅れているのと、理由があって留めているのは、
  出力から区別できなければならない。**
- ⚠ **これは「引数なしの `west update` を回せ」という意味ではない**（上記の罠 2 の
  とおり全 project を歩く）。進めるのは**遅れている pin だけ**で、その集合は
  `gh api repos/<org>/<repo>/compare/<pin>...<default>` の `ahead_by` で決まる。

- **常に `main` と同期し、乖離を作らない（最優先）。** 何らかの git 操作
  （pull / checkout / commit / branch 作業の開始など）を行う前に、上流 `main`
  に更新があれば必ず先に同期する。ローカルが `main` より遅れている状態
  （`git rev-list --left-right --count origin/main...HEAD` の左側が非ゼロ）で
  新しい作業を積み上げない。fast-forward 可能なら `--ff-only` で取り込む:

  ```bash
  git fetch origin
  git pull --ff-only                                 # 乖離していなければ FF で取り込む
  west update --fetch smart                          # project 群を pin に合わせて同期
  ```

  **これは prose instruction だけに頼らず、SessionStart hook
  （`.claude/hooks/session-start-branch-sync-check.cljs`、`.claude/settings.json`
  に登録済み）で毎セッション開始時に自動チェックする。** 実測インシデント
  （2026-07-20）: `agent/pin-docs-edn-only` ブランチが誰も気づかないまま
  `origin/main` から 848 commits ahead / 1607 commits behind まで積み上がった
  （592 ファイル・56万行超の diff）。agent が都度思い出して確認する運用は
  機能しなかったため、hook で ahead/behind を強制的に可視化する
  （閾値超過時は `systemMessage` + `additionalContext` で警告、閾値内でも
  非ゼロなら軽量に表示、失敗時は fail-open でセッション開始をブロックしない）。
  乖離を見つけたら rebase せず、この節の手順か `git-cleanup-conflict` skill
  （848 commits 級の乖離は content-containment 判定 → 新しい clean branch を
  origin/main から切って必要な差分だけ移植、が正解）で解消する。この実インシデントの
  詳細（`projects/` 旧 submodule クローン削除・各リポの actor 外部化検証・
  848 commits 乖離の解消経緯）は `90-docs/adr/2607206700-west-multirepo-monorepo-era-cleanup-audit.edn`
  に記録している。

- **`git push` の前に必ず `origin/main` との遅れを解消する。** push しようとする
  リポ（superproject / 各 project とも）が `origin/main`（既定ブランチ）より遅れて
  いる場合は、先に同期してから push する:

  ```bash
  git fetch origin
  git merge --ff-only origin/main      # FF 不可なら停止。rebase しない
  ```

  これは PreToolUse フック `.claude/hooks/git-push-main-sync-guard.cljs`（nbb）で強制される
  （遅れた状態の `git push` は deny され、同期を促すメッセージが返る）。フックは
  破壊的な自動マージはしない（判定と指示のみ、fail-open）。

- **rebase は基本禁止。** `git rebase` / `git pull --rebase` / rebase での乖離解消を
  標準手順にしない。FF できない stale branch は、最新 `origin/main` から clean branch /
  一時 worktree を作り、必要な小差分だけを `cherry-pick` または patch として載せ直す。
  `manifest/west.yml` の pin 前進は、ローカル rebase で解かず GitHub API single-entry
  commit（または最新 main ベースの clean worktree で当該 entry のみ commit）にする。
  既に rebase を開始して競合した場合は `git rebase --abort` し、marker 手編集で続行しない。
  fleet 活動中など `origin/main` が逐次前進して `git push main` が race する時は、変更を
  feature branch に push し（push 同期ガードは非-main を許可）、`gh api repos/<org>/<repo>/merges
  -f base=main -f head=<branch> -f commit_message=...` で **サーバ側マージ commit** を作る。
  push race に触れず、409(conflict/race) で再試行。実績: ADR-2606302300（org 分類
  そのものは**その後 superseded**。ここで引いているのは当時この経路で着地させたという
  記録であって、現行の org 分類の根拠ではない）の doc commit をこの経路で main 化
  （rebase も force-push も使わず）。

- **force-push は禁止（`git push --force` / `--force-with-lease` / `+refs` を使わない）。**
  共有リポ（superproject / 各 project）のいかなるブランチに対しても、履歴を書き換えて
  上流を上書きする push をしてはならない。force-push は他の clone・west pin・
  ancestry 判定を静かに壊し、`upload-pack: not our ref` 由来の checkout 失敗を引き起こす。
  **逆に `(forced update)` 表示や `unrelated histories` エラーだけでは本物の force-push と
  断定できない**（判別法は上述「マージ / ancestry 判定」節）。
  確度の高い実サインは `upload-pack: not our ref` によるチェックアウト失敗。乖離は
  **force-push ではなく fast-forward できる clean branch / clean commit** で解消し、
  それが不可能な場合（既に push 済みの履歴を変えたい等）は**勝手に強制せず必ずユーザーに報告**する。
  履歴書き換えが本当に必要なときも**行わない**。upstream を進めたいだけの単純更新は、ローカルで
  戦うより GitHub API でサーバ側にクリーン commit を起こす（PR #61/#62/#86 の実績）。

- **`main` への同期が未コミット/未追跡のローカル変更でブロックされた場合**、
  勝手に破棄しない。次の順で安全に同期する:
  1. ブロック原因の未追跡ファイルが **incoming とバイト同一** なら（origin に
     既に存在する掃き出しファイル）削除して安全。`shasum` で確認してから消す。
  2. 本物のローカル編集（incoming に未含有）は `git stash push -- <paths>` で
     退避してから pull する。**stash は drop せず温存**して owner が後で
     reconcile できるようにする。
  3. stash pop で衝突したら、本リポジトリの方針として **upstream(`main`) 側を
     採用**して解決し（`git checkout --ours -- <file>`）、ローカル差分は stash
     と未追跡実体として残す。乖離より main 同期を優先する。

- ユーザーが「git pull」とだけ指示した場合も、上記の main 同期 + `west update`
  まで含めて実行する（プルだけで終わらせない）。

- **本番デプロイは `origin/main` を包含した checkout からのみ行う。** デプロイは
  push と違って fast-forward 検査を持たない——**最後に実行した人が勝つ**ので、
  main より古い checkout から出荷すると、その間に他セッションが入れた変更を
  黙って巻き戻す。実インシデント（2026-07-25）: kotobase.net の signup funnel が
  404 だったのを直して 07:01 に deploy した11分後、別セッションが**その変更を
  含まない古い checkout** から同じ Worker を deploy し、funnel が 404 に戻った
  （誰も気付かなかった）。デプロイ前に:

  ```bash
  git fetch origin && git merge --ff-only origin/main   # FF 不可なら乖離。rebase しない
  ```

  これは PreToolUse フック `.claude/hooks/wrangler-deploy-main-sync-guard.cljs`
  （nbb、`.claude/settings.json` に登録済み）で強制される。`wrangler deploy` /
  `wrangler versions deploy` / `npm|pnpm|yarn run deploy` を対象に、checkout が
  `origin/main` より遅れていれば deny する。**隔離環境（`--env <name>`：
  staging / testnet / b2 等）と `--dry-run` はブロックしない**——feature branch を
  隔離環境で検証するのは正常な作業であり、そこを塞ぐと検証自体ができなくなる。
  フックは破壊的な自動同期をしない（判定と指示のみ、fail-open）。

- **`git push` / PR 作成・更新の前に、superproject と west の両方を最新化してから
  行う。** push や PR（`gh pr create`/`gh pr ready`/PR への追加 commit 等）の直前に、
  逐次・省略せず、以下を必ず実行してから push/PR する:

  ```bash
  git fetch origin                                 # origin/main 他を取得
  git merge --ff-only origin/main                  # superproject を main に同期（FF 不可なら停止。rebase しない）
  west update --fetch smart                        # 子リポ群を manifest の pin に合わせて同期
  nbb scripts/gen-west-manifest.cljs --check          # west.yml が canonical か（生成器と一致か）確認
  ```

  これらを飛ばして push/PR すると、main 乖離・west.yml の pin 退行・子リポの
  checkout 不一致が他者 clone や CI に伝播する。`west.yml` は生成物（手書き禁止）
  なので、`--check` が STALE なら **ローカル pin 退行の罠**（`gen-west-manifest.cljs`
  はローカル working HEAD で pin する＝子が遅れていると黙ってロールバック）に注意しつつ
  再生成し、`--check` が通ってから push/PR する。子リポ単位の push/PR も同様に、
  その子リポの `origin/<default-branch>` との遅れを解消してから行う。

- ユーザーが「cleanup」とだけ指示した場合、または PR/merge/stash/merge conflict の
  整理を依頼した場合、あるいは自分から `git stash drop` / `git branch -D` をしようと
  している場合は、**Skill ツールで `git-cleanup-conflict` を呼ぶ**
  （`.claude/skills/git-cleanup-conflict/SKILL.md`。Codex 側の同名 skill
  `$git-cleanup-conflict` と同じ runbook を共有）。手順の正本は
  `manifest/cleanup-workflow.edn`（readable 版が `manifest/cleanup-workflow.md`）—
  **trigger した節だけでなく edn 全体（`:retirement`/`:stash-pop`/`:west-conflict`
  含む）を読む**。superproject と `orgs/` 配下などの子リポを含め、WIP を破棄せず、
  `cleanup` メッセージで PR を作り、merge 可なら main へ merge し、残った stash/
  未追跡 repo を報告する。**stash/branch を drop/削除する前は「もう landed だと
  確信していても」必ず `.git/stash-archive-<date>/` へ退避してから**（実際に
  2026-07-04、確信を理由に archive を省略して drop した事例あり — 幸い
  `git fsck --unreachable` で拾えたが、運に頼らない）。

- **west project の checkout が「ローカルの未コミット変更」で失敗（衝突）した場合**、
  勝手に `west update --force` 等で破棄しないこと。`west` は既定で破壊的更新を
  しない（衝突時は当該 project を skip）。ユーザーに確認するか、まず差分を提示する。
  ローカル作業が残る project は manifest の pin を進める前に reconcile（commit &
  push）する。

- **project の checkout が「リモートに存在しない ref」（`upload-pack: not our ref`）**
  で失敗した場合は、上流で force-push された可能性が高い。`manifest/west.yml` の
  当該 pin（= repos.edn 経由）の見直しが必要なので、ユーザーに報告する。

- **複数セッション/エージェントが並行作業する可能性がある時は、共有の west checkout
  （`orgs/<org>/<repo>`）を直接編集せず、セッションごとに `git worktree` を切る。**
  west が管理するパスは1つの共有 working tree なので、別セッションがそこで
  `git checkout`（ブランチ切替）すると、自分がまだコミットしていない編集が
  working tree 上で**黙って巻き戻される**（実例: 2026-07-01、`orgs/kotoba-lang/
  kami-engine` で `sip.render`/`sip.world` への未コミット編集が、並行していた
  別セッションの `kami-isaac-sim-wasm` 作業のブランチ切替で失われかけた）。
  作業前に一時 worktree を切って、そこで完結させる:

  ```bash
  git worktree add -B <session-branch> <path> origin/main   # 独立 working tree
  # ... <path> で編集 / commit / test ...
  git push origin <session-branch>
  gh api repos/<org>/<repo>/merges -f base=main -f head=<session-branch> \
    -f commit_message="..."                                 # サーバ側マージ（ローカル merge/rebase を戦わない）
  git worktree remove <path>                                 # 使い終わったら片付ける
  ```

  **`<path>` は superproject ルートの外（例: scratchpad / `/tmp` 配下）にする。**
  `.claude/worktrees/` 等 superproject 内側に worktree を作ると、west は `.west/`
  を親ディレクトリへ辿って発見するため topdir が superproject ルートのままになり、
  worktree 内で `west update` しても実際には共有の `orgs/` を操作してしまう
  （false isolation。`WEST_TOPDIR` 環境変数でも直らない）。west コマンドを worktree
  内で使う必要がある場合は、外側に作った上でさらに `west init -l manifest` を
  worktree 内で実行し、worktree ローカルな `.west/` を作って topdir を固定する。
  詳細は ADR-2607011345。plain git（commit/push、west 不使用）だけなら
  superproject 内側の worktree でも問題ない。

  **worktree が隔離するのは working tree であって object store ではない。**
  linked worktree は `$GIT_COMMON_DIR` を元リポジトリと共有するので、
  **`/tmp` に作った「使い捨て」worktree の中で `--depth` 付き fetch をすると、
  superproject 本体が shallow になる**（`.git/shallow` は共有される）。
  実測 2026-08-12: root が shallow になっていた最有力経路がこれで、
  痕跡はどのログにも残っていなかった（**ref を動かさない depth fetch は
  reflog に entry を書かない**ため）。**worktree は `.git` に書くものに対する
  sandbox ではない。** 詳細は ADR-2608124400。

  共有 checkout（west 管理パス）には直接 commit/push しない。worktree 経由で
  main に着地させたあと、共有 checkout 側は `git fetch` と（内容一致を `shasum`
  で確認した上での）重複ファイルの削除だけで追従させる。

