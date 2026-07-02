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
  （`gftdcojp/<name>` 等、既定 private）+ push** → manifest 登録 → ADR/manifest の
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
  整理を依頼した場合は、`manifest/cleanup-workflow.md` を読む。機械可読の正本は
  `manifest/cleanup-workflow.edn`、Codex skill は `$git-cleanup-conflict`。
  superproject と `orgs/` 配下などの子リポを含め、WIP を破棄せず、`cleanup`
  メッセージで PR を作り、merge 可なら main へ merge し、残った stash/未追跡 repo を報告する。

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
- **stash / branch の棚卸し（retirement）手順は `manifest/cleanup-workflow.md` の
  Retirement 節**: 着地判定（追加行が現 main に含まれるかの content-containment。
  生成物 `manifest/west.yml` は判定から除外）→ `.git/stash-archive-<date>/` に
  パッチを退避 → drop / 削除。並行セッションが stash index をずらすので、drop は
  SHA を控えて毎回 index を再解決してから行う。

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

## Actors（langgraph-clj StateGraph アクター）

ドメインを「actor」として作るときは、既存3例の同型パターンに揃える:
**robotaxi-actor**（AR1 ⊣ SafetyGovernor）/ **gftd-talent-actor**（HR-LLM ⊣
PolicyGovernor）/ **ai-gftd-itonami**（ops-LLM ⊣ CertGovernor）。

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
- 手本は `ai-gftd-itonami/src/itonami/cacao.clj`（JVM）: did:key(0xED01+base58btc →
  `z6Mk…`)、鍵由来 IPNS(`ipns-name` → `k51qzi5uqu5d…`)、SIWE/wire は `kotoba.cacao` の
  byte-exact 純関数を移植、署名は JDK Ed25519、最小 CBOR。`load-or-create-identity!`
  で actor 鍵を 初回生成→永続→再読込。**秘密鍵は `.<actor>/identity.edn` に置き
  gitignore（git に絶対コミットしない）**。`kotoba-store {:identity me}` で graph 既定
  ＝鍵由来 IPNS ＋ 自己 mint。設定参照は `manifest/repos.edn` の `:kotoba`。
