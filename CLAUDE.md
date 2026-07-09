# CLAUDE.md

## リポジトリ構成（west manifest が正）

このリポジトリは superproject だが、**子リポ群は git submodule ではなく
[west](https://docs.zephyrproject.org/latest/develop/west/) manifest
（`manifest/west.yml`）で管理する。** plain な submodule は廃止済み（gitlink は
撤去・`.gitmodules` は無い）。source of truth は **`manifest/repos.edn`**（ポリシー）
で、`manifest/west.yml` は `scripts/gen-west-manifest.bb` が生成する（手書き禁止）。

- 取得/同期は `git submodule update` ではなく **`west update`** を使う。
- 各 project は `manifest/west.yml` の `path:`（= 旧 submodule と同一パス
  `orgs/<org>/<repo>`）に展開される。topdir は superproject ルート。
- 大容量データの **DataLad dataset（`m365-archive`）だけは west project にしつつ
  git-annex + Backblaze B2 で実体を扱う**（`userdata.datalad: true` / `datalad`
  グループに隔離し既定では取得しない）。取得/破棄は `west annex-get` /
  `west annex-drop`。詳細は `manifest/README.md`。

```bash
# 初回
west init -l manifest
# 取得/同期（shallow 既定。zsh は変数を単語分割しないので複数指定は xargs）
west update --fetch smart
west list -f '{name}' | grep -v '^manifest$' | xargs west update --fetch smart
# DataLad の実体だけ別途（B2 creds は環境変数）
west update --group-filter +datalad m365-archive && west annex-get
# pin を進めたら manifest 再生成（手書き禁止 / CI は --check）
bb scripts/gen-west-manifest.bb
```

### agent 専用 worktree で west を動かすときの topdir 固定（重要）

**agent ごとの git worktree で `west update` を動かすときは、worktree を
superproject ルートの *外*（sibling path）に作り、worktree 内で `west init -l manifest`
をやり直して topdir をその worktree に固定せよ。** これをしないと west は
子リポジトリを agent の worktree でなく **superproject 本体の `orgs/` に展開**してしまい、
他 agent の WIP と衝突する・`west update` が dirty で skip される。

理由: west は cwd から上方向に `.west/` を探して topdir を決める。`git worktree add`
を superproject *配下*（既定の `.claude/worktrees/<name>` 等）で作ると、その path は
superproject のサブディレクトリなので上方向の探索が superproject 本体の `.west/`
に当たり、topdir = superproject 本体と誤認される。`WEST_TOPDIR` 環境変数での上書きも
効かない（`.west/` 発見が優先される）。superproject ルートの *外* に worktree を作れば
`.west/` に当たらず、`west init -l manifest` でその worktree 専用の `.west/` が生成され
topdir が固定される。実測検証: ADR-2607011300。

```bash
# ✅ 正: superproject の外に worktree を作り、topdir を固定
git worktree add -b <agent-branch> /tmp/root-<agent-name> origin/main
cd /tmp/root-<agent-name>
west init -l manifest                       # ← この worktree 専用の .west/ を生成
west update --fetch smart <必要な repo>     # ← worktree 内 orgs/ に独立 checkout
# ❌ 誤: .claude/worktrees/<name> 配下の worktree で west を動かす
#        （superproject 本体を topdir と誤認し、本体の orgs/ を書き換える）
```

注意:
- これでも防げないのは **上流の force-push 系**（`origin/main` の force-rewrite /
  子 repo remote の force-rewrite による pin 退行）。worktree 分離は作業 tree の
  WIP 衝突しか防ぐ。force-push は上流の運用で撲滅するしかない。
- 大容量 repo は worktree ごとに重複取得される。`--fetch smart` + shallow 既定で
  軽減、heavy は DataLad/B2 経路（`west annex-get`）。

## 標準作業の常時許可（standing authorization）

- **次の「新規 project を起こして登録する」一連の流れは、毎回の確認なしに実行してよい**
  （恒久承認。2026-06-28 オーナー指示）。ADR 起票 → 子リポの scaffold（`.cljc` 正本 +
  `deps.edn` + README + test）→ `git init` + 初期コミット → **GitHub リポ作成
  （visibility は org 既定 — **kotoba-lang / etzhayyim = public、gftdcojp / com-junkawasaki = private**。repos.edn `:orgs :visibility` が SSoT、ADR-2607021330）+ push** → manifest 登録 → ADR/manifest の
  superproject 反映、までを一気通貫で進める。実例: `ai-gftd-router`（ADR-2606272330）。

- 上記に含まれる個別操作で都度確認が不要なもの: 子リポの `gh repo create` + `git push`
  （子リポは plain-git。下記 `repos.edn :manifest-workflow :child-repos`）、
  `bb scripts/gen-west-manifest.bb` による west.yml 再生成、superproject への
  `chore(manifest)+docs(adr)` コミット、新規 ADR（md+edn ペア）の作成。

- **ただしガードレールは常に守る**（恒久承認は手順の省略であって安全策の省略ではない）:
  - **west.yml / manifest の main 反映は `repos.edn :manifest-workflow` の正経路
    （API single-entry。楽観ロック）で行う。** local の shallow 3-way merge を戦わない・
    conflict marker を手編集しない・`--force` push しない。
  - **オーナーの未コミット WIP は破棄しない。** ブロック時は `git stash`（drop せず温存）。
    衝突は marker 手編集でなく **west.yml 再生成**で解く。
  - コミットメッセージ末尾に `Co-Authored-By: Claude Opus 4.8 (1M context)`。
  - 破壊的・取り返しのつかない操作（履歴書き換え・force-push・他者ブランチへの push・
    公開リポ化など）は従来どおり**事前確認**する。

## Git operations

- **shallow（`--depth 1`）をデフォルトにする。** 巨大 superproject + 多数のネスト
  リポで全履歴を取得すると時間・帯域・ディスクを浪費するため、明示的に full 履歴が
  必要な場合（`git bisect` / 古いコミットへの `git blame` / 履歴を跨ぐ調査）を除き、
  常に `--depth 1` を付ける。west も clone-depth: 1 を既定にしてある:

  ```bash
  git fetch --depth 1 origin
  git pull --ff-only --depth 1
  west update --fetch smart        # 各 project を shallow 取得（旧 submodule update 相当）
  ```

  full 履歴が必要になったら、その時だけ対象を `git fetch --unshallow`（または
  `--depth=<n>` で深掘り）して深くする。詳細は
  `90-docs/adr/2606241600-shallow-depth1-git-default.md` を参照。

- **マージ / ancestry 判定をする時は、固定 depth を当て推量で増やさず
  「merge-base を狙い撃ちで取得」する。** shallow なリポでマージや
  `merge-base` / `--is-ancestor` / `rev-list --count` を行うと、共通祖先が
  graft 境界の外にある場合に **`no merge base` で失敗するだけでなく、ancestry を
  静かに誤判定する**（例: 純粋な前進を「系統分岐」と誤検出する）。`--depth 30`
  等の固定値は「当たれば速い／外れると誤答 or 失敗」の博打で、誤答は depth 1 の
  明示エラーより厄介。代わりに base を直接取る:

  ```bash
  # GitHub に full 履歴で merge-base を計算させ、その SHA だけピンポイント取得
  BASE=$(gh api repos/<org>/<repo>/compare/main...<branch> --jq .merge_base_commit.sha)
  git fetch --depth 1 origin "$BASE"      # 履歴が繋がり、判定が正しくなる
  # 足りなければ --deepen=<n> / --shallow-since=<date> / 対象 ref だけ --unshallow
  ```

  さらに、**manifest の pin 前進のような単純更新は、ローカルで shallow マージを
  戦うより GitHub API でサーバ側（full 履歴）に commit を起こす方が確実かつ安い**
  （merge-base も ancestry もサーバが計算するため shallow 問題に触れない）。
  実例: PR #61 / #62 / #86 は main の tree をベースにクリーン commit を API で
  作成してマージした（#86 は 31 リポの west 移行を regression なしで取り込み）。

- **`git fetch` の `(forced update)` 表示や `git merge` の
  `fatal: refusing to merge unrelated histories` は、それ単独では本物の
  force-push と断定しない。** shallow clone は `--depth 1` フェッチのたびに
  新しい shallow graft（親情報を持たない境界コミット）を作るため、upstream が
  **純粋な fast-forward で前進しただけ**でも、ローカルの祖先証明が古い graft の
  壁で止まり同じ症状（`(forced update)` 表示・`unrelated histories` エラー）が出る。
  実測（2026-07-01、`root` superproject）: ローカル HEAD が 6 commit 遅れていた
  だけの純前進で両症状が発生。**本物の force-push か判定するには GitHub API で
  比較する**（ローカルの ancestry 判定を信用しない）:

  ```bash
  gh api repos/<org>/<repo>/compare/<old-local-tip>...<new-origin-tip> \
    --jq '{status, ahead_by, behind_by, merge_base_commit: .merge_base_commit.sha}'
  # status:"ahead" かつ behind_by:0 かつ merge_base_commit == old-local-tip なら
  # 純粋な fast-forward（shallow の偽陽性）。diverged や merge_base が別物なら本物の force-push。
  ```

  偽陽性と判明したら `git fetch --deepen=<n>`（10〜30 程度）でローカルの祖先鎖を
  修復してから `git merge --ff-only` を再試行する（未コミット WIP がブロックする
  場合は上述の通り `git stash push -- <paths>` で退避、drop しない）。それでも
  `upload-pack: not our ref` で失敗する場合のみ、本物の force-push として下記
  「force-push は禁止」節の対応（ユーザーへの報告）に進む。

- **`manifest/west.yml` への変更（登録 / rename / pin 前進）は GitHub API の
  サーバ側 single-entry commit を「唯一の正経路」にする。** west.yml は生成物
  （`repos.edn` ＋ 各子repo HEAD → `gen-west-manifest.bb`、手書き禁止 / `--check`）
  なので、行指向 pin を textual 3-way merge するのはアンチパターンで、conflict
  marker の手編集は **pin を静かに壊す**。代わりに: tip の west.yml と blob SHA を
  取得（dir listing から SHA を採ると巨大 base64 を避けられる）→ **当該 entry の
  行だけ**編集 → blob SHA 一致で PUT（`branch=` `sha=`）。**tip がずれれば 409**
  で弾かれる（取得し直してリトライ）ので **conflict が構造的に発生しない**。
  commit 前に **pin == 子repo HEAD を検証**。API 手編集は生成器を通らないので、
  落ち着いたら `bb scripts/gen-west-manifest.bb --check` で canonical 一致を確認。
  やむを得ずローカル merge する場合のみ、west.yml の衝突は **marker 手編集でなく
  再生成で解決**: superset 側採用 → `west update` で子を目的 pin に揃える
  （⚠ 再生成はローカル working HEAD で pin するので、子が遅れていると黙って
  ロールバックする＝pin 退行の罠）→ `gen-west-manifest.bb` → `--check`。子repo
  自体は普通の git（branch/PR/push）。詳細は ADR-2606272237 / `repos.edn`
  `:manifest-workflow`。実例: PR #61/#62/#86、kenchi-actor→kenchi-clj rename
  （`34988dd`、diff は当該 entry のみ）。

- **west.yml の pin 変更はサーバ側 pin 検証を必ず通す（`scripts/verify-west-pins.bb`、
  ADR-2607022900）。** pin に許されるのは「上流 repo の default branch から到達可能な
  commit」だけ: ①存在（= push 済み。未 push のローカル HEAD の pin 化は禁止）、
  ②default branch 到達性（rewrite されうる未 merge branch 上の commit は不可）、
  ③旧 pin からの前進（behind = 静かな pin 退行 / diverged を弾く）。判定はすべて
  GitHub API（サーバ側 full 履歴）で行い、**ローカル shallow の ancestry を信用しない**。
  `gen-west-manifest.bb` は生成時に自動でこの検証を行い、失敗したら west.yml を
  書かない（緊急スキップ: `--no-verify-remote` / `WEST_PIN_VERIFY_SKIP=1`。使ったら
  理由を commit message に残す）。**登録・rename・pin 前進は `--entry <name>` で当該
  entry のみの最小 diff を生成する — wholesale 再生成 commit は禁止**（1件の登録の
  つもりが未 push HEAD 由来の壊れた pin を 44 件 main に流した実事故 `90852b86` の
  再発防止）。CI（`.github/workflows/west-pin-verify.yml`）と PreToolUse hook
  （`.claude/hooks/west-pin-verify-guard.bb`。`git push` と `gh api PUT` の両経路）が
  同じ検証を強制する。

- **`git push` / `git pull` / `west update` の前に、manifest の pin が upstream
  GitHub の最新から取り残されていないか（pin 鮮度）を必ず確認する。** `west update`
  は west.yml に**既に書かれている** pin へ checkout を合わせるだけで、GitHub 側の
  新しいコミットを pin に反映するコマンドではない（pin 自体の前進は別操作。
  「`west update` すれば GitHub 最新に追従する」と誤解しないこと）。実測
  （2026-07-03）: `bb scripts/gen-west-manifest.bb`（引数なし dry-run）で kotoba-lang
  org 配下の character / comfyui / kami-engine / kotoba / kotobase / murakumo 等
  多数の project で、ローカル checkout が **既存 pin より遅れている**状態を検出
  （気付かず push すると stale checkout や古い pin が他 clone / CI に伝播する）。
  対象 project を触る git 操作の前に:

  ```bash
  # 1) 対象 project の pin 鮮度を GitHub API で確認（ahead_by > 0 なら upstream が先行）
  gh api "repos/<org>/<repo>/compare/<pinned-sha>...<default-branch>" \
    --jq '{ahead_by, behind_by}'
  # 2) 先行していたら該当 project の checkout を最新化
  cd orgs/<org>/<repo> && git fetch origin && git merge --ff-only origin/<default-branch>
  # 3) manifest の pin を前進（当該 entry のみ最小 diff。wholesale 再生成は禁止）
  bb scripts/gen-west-manifest.bb --entry <repo-name>
  bb scripts/gen-west-manifest.bb --check
  ```

  これを終えてから本来の `git push` / `git pull` / `west update` を実行する。

- **常に `main` と同期し、乖離を作らない（最優先）。** 何らかの git 操作
  （pull / checkout / commit / branch 作業の開始など）を行う前に、上流 `main`
  に更新があれば必ず先に同期する。ローカルが `main` より遅れている状態
  （`git rev-list --left-right --count origin/main...HEAD` の左側が非ゼロ）で
  新しい作業を積み上げない。fast-forward 可能なら `--ff-only` で取り込む:

  ```bash
  git fetch --depth 1 origin
  git pull --ff-only --depth 1                       # 乖離していなければ FF で取り込む
  west update --fetch smart                          # project 群を pin に合わせて同期
  ```

- **`git push` の前に必ず `origin/main` との遅れを解消する。** push しようとする
  リポ（superproject / 各 project とも）が `origin/main`（既定ブランチ）より遅れて
  いる場合は、先に同期してから push する:

  ```bash
  git fetch origin
  git merge --ff-only origin/main      # FF 不可なら停止。rebase しない
  ```

  これは PreToolUse フック `.claude/hooks/git-push-main-sync-guard.bb`（babashka）で強制される
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
  ローカル shallow・push race に触れず、409(conflict/race) で再試行。実績: ADR-2606302300 の
  doc commit をこの経路で main 化（rebase も force-push も使わず）。

- **force-push は禁止（`git push --force` / `--force-with-lease` / `+refs` を使わない）。**
  共有リポ（superproject / 各 project）のいかなるブランチに対しても、履歴を書き換えて
  上流を上書きする push をしてはならない。force-push は他の clone・west pin・
  ancestry 判定を静かに壊し（shallow 環境では「前進」を「分岐」と誤検出する原因にも
  なる）、`upload-pack: not our ref` 由来の checkout 失敗を引き起こす。**逆に
  `(forced update)` 表示や `unrelated histories` エラーだけでは本物の force-push と
  断定できない**（shallow の偽陽性が多い。判別法は上述「マージ / ancestry 判定」節）。
  確度の高い実サインは `upload-pack: not our ref` によるチェックアウト失敗。乖離は
  **force-push ではなく fast-forward できる clean branch / clean commit** で解消し、
  それが不可能な場合（既に push 済みの履歴を変えたい等）は**勝手に強制せず必ずユーザーに報告**する。
  履歴書き換えが本当に必要なときも、shallow 化に伴う rewrite と同様に**行わない**
  （後述「大容量バイナリ」節と整合）。upstream を進めたいだけの単純更新は、ローカルで
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

- **`git push` / PR 作成・更新の前に、superproject と west の両方を最新化してから
  行う。** push や PR（`gh pr create`/`gh pr ready`/PR への追加 commit 等）の直前に、
  逐次・省略せず、以下を必ず実行してから push/PR する:

  ```bash
  git fetch --depth 1 origin                       # origin/main 他を取得
  git merge --ff-only origin/main                  # superproject を main に同期（FF 不可なら停止。rebase しない）
  west update --fetch smart                        # 子リポ群を manifest の pin に合わせて同期
  bb scripts/gen-west-manifest.bb --check          # west.yml が canonical か（生成器と一致か）確認
  ```

  これらを飛ばして push/PR すると、main 乖離・west.yml の pin 退行・子リポの
  checkout 不一致が他者 clone や CI に伝播する。`west.yml` は生成物（手書き禁止）
  なので、`--check` が STALE なら **ローカル pin 退行の罠**（`gen-west-manifest.bb`
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

  共有 checkout（west 管理パス）には直接 commit/push しない。worktree 経由で
  main に着地させたあと、共有 checkout 側は `git fetch` と（内容一致を `shasum`
  で確認した上での）重複ファイルの削除だけで追従させる。

## 並行エージェント運用（worktree-per-agent / stash を積まない）

複数セッション・エージェントが同時に走る前提の標準フロー。stash・branch・worktree の
無限増殖はこのフローからの逸脱の症状（実測: 2026-07-01→02 の一晩で、共有 checkout 上の
WIP を並行セッションが約40分間隔で退避し続け stash が20個堆積。棚卸しの結果、実質的な
未着地は2件だけで残り18件は着地済み/陳腐化だった）。

- **superproject 本体 checkout（このフォルダ）は「統合・閲覧専用」。** ここでは編集・
  commit・ブランチ切替をしない。やってよいのは `git fetch` / `--ff-only` pull /
  `west update` / 読み取りだけ。本体に未コミット編集が転がっていると、並行セッションの
  main 同期のたびに「他人の WIP を stash 温存」が発火して stash が堆積する。
- **作業は 1 task = 1 branch = 1 worktree（superproject の外、sibling path）。**
  `git worktree add -b <branch> /tmp/root-<name> origin/main`。superproject の
  full checkout は重い（2分超）ので、触るパスが少ない作業は `--no-checkout` +
  `git sparse-checkout set --no-cone <paths>` で部分 checkout にする。worktree 内で
  west を使う場合は前節のとおり `west init -l manifest` で topdir を固定する。
- **WIP の退避は stash でなく session branch への commit。** commit は名前・履歴・
  所有者が付き branch 単位で棚卸しできるが、stash は無名の共有スタックで誰のものか
  追えなくなる。stash を使ってよいのは「共有 checkout で見つけた他人の未コミット WIP を
  消さないための緊急退避」だけで、積んだら cleanup で必ず棚卸しする。
- **着地後の後片付けまでがタスクの完了条件。** push → サーバ側マージ
  （`gh api .../merges`）→ `git worktree remove` → `git branch -D <branch>` →
  マージ済み remote branch の削除。「マージしたのに branch/worktree が残っている」
  状態を作らない。
- **stash / branch の棚卸し（retirement）は Skill `git-cleanup-conflict` を使う**
  （手順の正本は `manifest/cleanup-workflow.edn` の `:retirement`、readable 版は
  `manifest/cleanup-workflow.md` の Retirement 節）: 着地判定（追加行が現 main に
  含まれるかの content-containment。生成物 `manifest/west.yml` は判定から除外）→
  **drop/削除の前に必ず** `.git/stash-archive-<date>/` へパッチを退避（「landed だと
  確信している」は archive 省略の理由にならない）→ drop / 削除。並行セッションが
  stash index をずらすので、drop は SHA を控えて毎回 index を再解決してから行う。

## 大容量バイナリの扱い（B2 + DataLad、最優先）

- **モデル重み / wasm / 動画 / 画像データセット等の大きなバイナリを git 履歴に
  直接コミットしない。** これらは clone/pull を重くする最大要因（過去に
  ai-gftd-apps=16G, ghosthacker=9.7G 等まで肥大）。新規に大容量データを置く必要が
  あるときは **DataLad データセット + git-annex の Backblaze B2 (S3 互換) special
  remote** を使う。実体は B2 へ push し、git にはポインタ(annex キー)だけ残す。

  ```bash
  B2_KEY_ID=... B2_APP_KEY=... B2_BUCKET=... \
  B2_ENDPOINT=s3.us-west-004.backblazeb2.com \
    scripts/datalad-b2-init.bb <dataset-dir> [remote-name]
  # 以後: datalad save → datalad push --to b2 → datalad drop / datalad get
  ```

  B2 認証は **`scripts/b2-creds.bb`** が解決する（既定の順 env→1Password→Keychain。
  参照先は `manifest/repos.edn` の `:b2 :credentials`）。`op`(1Password CLI) /
  `security`(Keychain) / 環境変数のどれでも同じコマンドで動く。**秘密情報は
  リポジトリに一切コミットしない**（EDN に置くのは `op://` パスや Keychain service 名
  といった非機密の参照先だけ）。CI では `B2_KEY_ID/B2_APP_KEY/B2_BUCKET` を env で渡す。

- **DataLad dataset は west に統合してある。** `manifest/repos.edn` の `:datalad`
  に登録した project は `manifest/west.yml` で `userdata.datalad: true` + `datalad`
  グループ（既定 `group-filter` の `-datalad` で off）になる。git/annex スケルトンの
  取得は `west update --group-filter +datalad <name>`、実体の取得/破棄は west 拡張
  コマンド `west annex-get` / `west annex-drop`（B2 special remote を環境変数の
  creds で有効化して get/drop）。実装は `manifest/west_annex.py`。

- **既存の重い project は shallow（clone-depth: 1）で運用する。** west は
  clone-depth: 1 を既定にしてある（実績: ai-gftd-apps 16G→305M, ghosthacker
  9.7G→736M, spirit-in-physics 1.1G→62M）。現行ツリー自体が重いもの（画像同梱の
  260208-spirit-in-physics 等）は shallow では縮まないため、将来的に上記 B2+DataLad
  へ移すのが望ましい。なお shallow 化に伴う履歴書き換え＋force-push は**行わない**
  （main 乖離・共有リポへの影響を避けるため、shallow 運用で対処する）。

## 秘密情報の保管場所マップ（1Password / Keychain — 値は書かない、参照先だけ）

**このファイルに秘密情報の値そのものを書いてはいけない。** 書いてよいのは
「どの vault のどの item に何が入っているか」という参照先だけ（`op://` パスや
Keychain の service 名と同じ扱い）。実値は `op read` / `bin/kagi get` /
`security find-generic-password` で都度取得する。このセクションは「B2 の鍵は
どこ？」を毎回 1Password 内を検索し直す手間を省くための索引 — 見つけたら
追記していく（網羅は目指さない、都度育てる）。

- **Backblaze B2（複数の鍵が並存 — 用途で使い分ける。同じ鍵を使い回さない）**:
  - `com-junkawasaki.b2/annex`（1Password `gftdcojp` vault）— `manifest/repos.edn`
    の `:b2 :credentials` が参照する M365 archive 用（bucket:
    `gftdcojp-m365-annex`）。Keychain 側ミラーは `security add-generic-password
    -s b2:gftdcojp-m365-annex`（`scripts/b2-creds.bb` が解決）。
  - `gftd.b2/*`（1Password `gftdcojp` vault、フィールド分割: `BUCKET_NAME` /
    `ENDPOINT` / `ENDPOINT_URL` / `REGION` / `APPLICATION_KEY_ID` /
    `ACCESS_KEY_ID` / `SECRET_ACCESS_KEY`）— bucket `ai-gftd-cdn` 専用。
  - **`BACKBLAZE 260225 application keys`（1Password `gftdcojp` vault）— 1
    item に複数バケット分のスコープ付きキー + アカウント全体の
    Master Application Key が同居**:
    - `BACKBLAZE_CDN_BUCKET_KEY_ID`/`_KEY`/`_KEY_NAME` — `260225-ai-gftd-cdn`
    - `BACKBLAZE_QUICKWIT_BUCKET_KEY_ID`/`_KEY`/`_KEY_NAME` —
      `260225-ai-gftd-quickwit`
    - `BACKBLAZE_NATS_BUCKET_KEY_ID`/`_KEY`/`_KEY_NAME` +
      `BACKBLAZE_NATS_BUCKET_NAME`/`_S3_REGION`/`_S3_ENDPOINT` —
      `ai-gftd-nats`
    - **`260421-BACKBLAZE_MASTER_KEY_ID`/`260421-BACKBLAZE_MASTER_KEY`**
      （フィールド名先頭の `260421-` が item 内の識別プレフィックス）—
      **アカウント全体の Master Application Key**。`b2_create_bucket`/
      `b2_create_key`（新しい bucket や、その bucket だけにスコープした
      application key を作る）に使えるのはこれだけ — 上記の他のキーは全部
      特定 bucket にスコープ済みで、新規 bucket/key の発行はできない。
      新しい用途（新規プロジェクトの testnet 等）向けに B2 リソースを
      プログラム的に用意したい時は、まずこの Master Key の所在を確認する
      （毎回 1Password 内を探索し直さない）。
  - `Backblaze`（1Password `Private` vault、2 件）— 個人用途、org のプロジェクト
    には使わない。
- **Cloudflare**: `wrangler` の OAuth ログインセッション（ブラウザ認証、
  `npx wrangler login`）が実質の認証手段 — このセッションで R2 バケット/KV
  namespace/Worker デプロイ/Custom Domain 追加まで一括して操作できる（1Password
  越しの API トークンではなく、ローカルの wrangler セッションで完結）。
  `CLOUDFLARE_API_TOKEN`（Zone Analytics Read 等の狭いスコープ）は用途別に
  `wrangler secret put` で個別プロジェクトへ投入するもので、これとは別物。
- **kagi（`kotoba-lang/kagi`）**: net-kotobase / kotoba-lang 系の新規プロジェクト
  向け secrets は、1Password ではなく **こちらを正**にしていく方針（自己主権
  vault、ADR-2606272330）。`bin/kagi ls`（vault: `./.kagi/`、gitignore 済み）
  で一覧、`bin/kagi get <name>` で取得。1Password から個別 item を持ち込みたい
  時は `bin/kagi import onepassword <file.1pux>`。

## Actors（langgraph-clj StateGraph アクター）

ドメインを「actor」として作るときは、既存3例の同型パターンに揃える:
**robotaxi-actor**（AR1 ⊣ SafetyGovernor）/ **gftd-talent-actor**（HR-LLM ⊣
PolicyGovernor）/ **cloud-itonami**（ops-LLM ⊣ CertGovernor）。

> 2026-07-04 追記: 上記3例目は当初 standalone repo `gftdcojp/ai-gftd-itonami` として
> 計画されていたが、実際にはその repo は作成されず（GitHub 上に実在しない・ローカルの
> 空 placeholder checkout も削除済み）、実装は `gftdcojp/cloud-itonami` 本体の
> `itonami`/`cloud_itonami.edge.*` namespace にそのまま統合された。以下の CACAO 手本
> パスも `cloud-itonami` 側を参照する。

- **封じ込め + 独立 governor + 不変台帳。** 知能ノード（LLM/研究モデル）を1ノードに
  封じ込め *proposal のみ* 返させ、別系統の Governor が検閲して 可決/拒否/人間承認 に
  振る。単一不変条件「**governor が拒否する 書込/開示/作動/認証 を actor は決して
  行わない**」。全 commit/hold を append-only の監査台帳に積む（台帳＝データ主権/
  トレーサビリティの核）。
- **langgraph-clj StateGraph。** 1 run = 1 操作（無限内部ループ無し）。`interrupt-before`
  を human-in-the-loop（承認/テレオペ/耐空性サインオフ）に転用。checkpoint で監査可能。
  長期耐久 loop が必要な kotoba code / Claude Code 型 agent は、StateGraph 内で回さず
  **durable outer loop**（lease / tick / budget / governor / crash recovery）で有界 run を
  反復する。継続状態は `:checkpoint/*` と `:agent.loop/*` / `:agent.tick/*` /
  `:agent.lease/*` / `:agent.budget/*` / `:agent.event/*` datom に分離して積む。
- **注入境界（swap）。** Store（`MemStore` ‖ `DatomicStore`）/ Advisor（mock ‖ 実LLM=
  `langchain.model`）/ Phase（0→3 段階導入）を注入で差し替え、コアは不変。
- **Store は `:db-api` 駆動。** backend へは langchain.db の `{:q :transact! :db :pull
  :entid}` マップ越しにのみ喋る。`langchain.db/api`（in-process）と
  `langchain.kotoba-db/kotoba-api`（kotoba-server XRPC）が同マップを実装するので、
  同一 record が in-mem / 実 Datomic / kotoba pod を選ばず動く（contract test で
  `MemStore ≡ DatomicStore` を保証）。直呼びせず必ず `:db-api` を介す。
- **deps / lint / test。** `io.github.com-junkawasaki/langgraph-clj
  {:local/root "../../com-junkawasaki/langgraph-clj"}` ＋ `:dev` で langchain-clj を
  override（3 actor 同形の deps.edn）。`clojure -M:lint`（clj-kondo・errors fail）/
  `clojure -M:dev:test`。`.cljc` は `edn`/`Exception` を `#?(:clj/:cljs)` 条件化して
  JVM/cljs/WASM 可搬に保つ。
- **west / RAD 登録。** 新 actor workflow は `20-actors/{name}` に実装を置くだけで完了
  しない。actor 単位 repo `etzhayyim/com-etzhayyim-{name}` を作り、
  `orgs/etzhayyim/com-etzhayyim-{name}` として west に登録し、RAD identity 台帳にも
  同じ actor identity を登録するまでを完了条件にする。west は `manifest/repos.edn` を
  SSoT とし、GitHub API の単一 entry クリーン commit で登録 / pin 前進する
  （`manifest/west.yml` は生成物、手書き禁止）。diff は当該 entry のみ、
  `bb scripts/gen-west-manifest.bb --check` と **pin == repo HEAD** を確認。RAD は
  etzhayyim/root の `80-data/kotoba-rad/{name}.identity.journal.edn`（または同等の
  RAD identity ledger）に `:rad/repo "github.com/etzhayyim/com-etzhayyim-{name}"`、
  `:rad/did-web "did:web:etzhayyim.github.io:com-etzhayyim-{name}"`、署名 /
  attestation 参照を積む。`20-actors/{name}` だけに存在する actor は **未分離** と扱い、
  child repo 作成 → west entry → RAD identity の follow-up を残す。

### kotoba-server（kotobase.net）= actor が自分の鍵で CACAO を自己発行

- 認証は **CACAO**（SIWE/EIP-4361 を Ed25519 did:key で署名、kotoba-auth
  DelegationChain）。**actor ごとに鍵を発行**し、その**鍵由来 IPNS 名がその actor の
  graph**（`kotoba/write.cljs`: *AUTHORITY は鍵由来 IPNS 名への署名であってサーバでは
  ない*）。actor は鍵を持つことで自分の graph の owner → depth-1 の自己 mint が
  構造的に authorized。**owner hand-off も共有 token も要らない**（「token をもらう／
  owner が grant する」前提は誤り）。
- 手本は `cloud-itonami/src/cloud_itonami/edge/cacao.cljc`（旧 `ai-gftd-itonami/src/itonami/cacao.clj` 参照は廃止。2026-07-04）: did:key(0xED01+base58btc →
  `z6Mk…`)、鍵由来 IPNS(`ipns-name` → `k51qzi5uqu5d…`)、SIWE/wire は `kotoba.cacao` の
  byte-exact 純関数を移植、署名は JDK Ed25519、最小 CBOR。`load-or-create-identity!`
  で actor 鍵を 初回生成→永続→再読込。**秘密鍵は `.<actor>/identity.edn` に置き
  gitignore（git に絶対コミットしない）**。`kotoba-store {:identity me}` で graph 既定
  ＝鍵由来 IPNS ＋ 自己 mint。設定参照は `manifest/repos.edn` の `:kotoba`。

## `.cljc` / `.kotoba` ランタイム優先順位（2026-07-10 改訂。2026-07-07 改訂・初版は2026-07-06）

- **repo wide のルール: app の互換性と「第一の runtime」の順序は
  `kotoba wasm runtime` > `clojurewasm` > `ClojureScript` > `nbb` とし、
  `JVM` と `bb`（babashka）はその下に降格する（どちらも最後の手段。
  2026-07-10 オーナー指示）。** 新しく書く app / library / `.cljc` /
  `.kotoba` は、この順で「どの runtime を第一級に据えるか」を決め、
  reader-conditional 分岐・依存選定・テストの正本もこの順序に合わせる。
  上位 runtime で動くものを JVM / bb 前提で書かない。JVM / bb にしか無い
  経路（Chicory テストハーネス、既存 JVM 専用 lib への互換層など）は
  「互換 (compat) 層」として明示的に隔離し、設計の前提にしない。
  実例（この規則の模範実装）: `kotoba.kami-host`（ADR-2607100030
  addendum 2）— ECS core を portable `.cljc` に置き、第一の実行経路は
  ClojureScript（browser ESM / nbb ネイティブ WebAssembly）、`:clj`/
  Chicory 層は互換スイート専用と docstring に明記。
- **`bb` の降格は「app の runtime として」の話。** リポジトリ運用ツール
  （`scripts/*.bb`・`.claude/hooks/*.bb`・west 拡張等）は app ではなく
  インフラ tooling で、現状 bb が正本 — これらを一斉移行はしない（移行
  するなら対象を決めて ADR 化。個別の新規スクリプトは nbb で書けるなら
  nbb を優先）。2026-07-06 初版の「kototama > cljs > nbb > jvm」→
  2026-07-07 改訂に続く 3 度目の改訂で、変更点は (1) app 互換性の順序と
  しての明文化、(2) `bb` を JVM と並ぶ最下位に明示、の 2 点。
- **`kotoba wasm`** — `.kotoba` 拡張子（kotoba 言語の極小サブセット —
  `def`/`defn`/`ns`/`if`/`when`/`let`/`do`/算術/比較/`and`/`or`/`not`/
  文字列基本操作 + 再帰のみ、Java/JS interop 一切なし、サードパーティ lib
  不可）を `kotoba wasm emit` で WASM にコンパイルし、`kototama` の
  `actor:host` ABI（`kototama.contract`/`kototama.tender`, ADR-2607062330/
  2607062400）でホストする経路。**2026-07-06 版と異なり、これは今や実在し
  E2E で動作確認済み**（ADR-2607062330 addendum 5、2026-07-06〜07）:
  `kotoba-core-contracts` の閉じたホストインポート表に `.kotoba` から呼べる
  capability を登録し、`kotoba wasm emit` が実際に出力した（手書き WAT では
  ない）`.wasm` が `kototama.tender`（JVM/Chicory）にリンクして正しく実行
  することを確認済み（`kotoba-lang/kototama` の `test/kototama/fixtures/`
  に実バイナリとして checked in）。ホストは JVM/Chicory 経路
  （`kototama.tender`）と ブラウザネイティブ経路
  （`wasm-webcomponent` の `actor-host.js`、ADR-2607062400）の両方が実在。
- **`clojurewasm`** — `.kotoba` の極小サブセットではなく**フルの Clojure**
  を書きたい場合の次点。外部プロジェクト
  [`clojurewasm/ClojureWasm`](https://github.com/clojurewasm/ClojureWasm)
  （通称 `cljw`）: Zig で書かれた JVM フリーの Clojure ランタイムで、
  WebAssembly を FFI として呼べる（`(wasm/load "mod.wasm")` /
  `(:require ["comp.wasm" :as c])` で WASM component を名前空間のように
  require できる）。2026-02 発足、2026-07 時点で v1.0.0 安定版・実働デモ
  （cw-playground, cw-serverless-demo, cw-arcade）あり、157 stars、直近まで
  push されている活発なプロジェクト（確認日 2026-07-07）。**ただし
  Issues/PR は現在受け付けていない**（小規模チームのため。EPL-2.0）ので、
  このリポジトリへの直接貢献はできず「利用する」側の依存としてのみ扱う。
  **2026-07-10 時点でもこのモノレポ内に `clojurewasm`/`cljw` の利用例・
  ビルド・統合は無い**（既存 `.cljc` を勝手に `cljw` 前提に書き換えない —
  導入する場合は対象を決めてから着手する）。2026-07-10 に実適用を検討した
  実測: cljw v1.0.1 の FFI は `wasm/load`+`wasm/call`（import-free module
  専用）で、**Clojure 製 host import の提供は upstream 自身が「Phase-16 の
  fuller FFI surface」として将来に明示**（`docs/examples/wasm/README.md`）。
  host import を要する guest（例: kami-survivors の 12 imports）は現状
  ホストできないため ClojureScript に落ちる（ADR-2607100030 addendum 2）。
  Phase-16 が landed したら再評価する。
- **`ClojureScript`（cljs）** — ブラウザ/Node 向け。次点。
- **`nbb`** — ClojureScript-on-Node の高速スクリプティング。静的サイト生成
  など軽量タスク向け（実例: `kototama/web/generate.cljs`）。
- **JVM 単体と bb は最後の手段（app runtime として）。** 既存の JVM(`:clj`)
  専用ライブラリ（`kotoba-lang/ed25519`・`kotoba-lang/cacao`・`kotoba-lang/
  tech-ipfs-specs-ipns` 等）は、実装当時「唯一動く経路が JVM だった」という
  正しい判断の結果なので、上位の選択肢が実在するようになった今も
  リトロアクティブに書き直さない（移行する場合は対象を決めて ADR 化して
  から着手する）。bb も同様: 既存の repo 運用スクリプト群は温存し、app を
  bb 前提で新規に書かない。
- `#?(:kototama ...)` / `#?(:clojurewasm ...)` という reader-conditional は
  **コードベース全体を検索してゼロ**——Clojure 標準は `:clj`/`:cljs`/
  `:cljr`/`:default` しか認識せず、これらを feature として認識させるカスタム
  reader/ビルドステップは存在しない。**存在しないものとしてこれらの
  reader-conditional を書かない**（無言でどちらの分岐も評価されない dead
  branch になる）。
- 新しい `.cljc`/`.kotoba` を書く／既存の `:clj`/`:cljs` 分岐を拡張する判断に
  迷ったら、上記の順序（kotoba wasm → clojurewasm → cljs → nbb →（降格:
  jvm / bb））で「今実際に動く経路はどれか」を確認してから選ぶ——ただし
  目の前のタスクを止めてまで存在しない統合（例: `clojurewasm` の新規導入）を
  今から作ることはしない（別スコープの ADR とプロジェクトとして切り出す）。
