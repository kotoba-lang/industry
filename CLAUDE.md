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
  git merge --ff-only origin/main      # FF 不可なら merge / rebase で乖離を解消
  ```

  これは PreToolUse フック `.claude/hooks/git-push-main-sync-guard.bb`（babashka）で強制される
  （遅れた状態の `git push` は deny され、同期を促すメッセージが返る）。フックは
  破壊的な自動マージはしない（判定と指示のみ、fail-open）。

- **force-push は禁止（`git push --force` / `--force-with-lease` / `+refs` を使わない）。**
  共有リポ（superproject / 各 project）のいかなるブランチに対しても、履歴を書き換えて
  上流を上書きする push をしてはならない。force-push は他の clone・west pin・
  ancestry 判定を静かに壊し（shallow 環境では「前進」を「分岐」と誤検出する原因にも
  なる）、`upload-pack: not our ref` 由来の checkout 失敗を引き起こす。乖離は
  **force-push ではなく merge / rebase してから通常 push** で解消し、それが不可能な
  場合（既に push 済みの履歴を変えたい等）は**勝手に強制せず必ずユーザーに報告**する。
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

- **west project の checkout が「ローカルの未コミット変更」で失敗（衝突）した場合**、
  勝手に `west update --force` 等で破棄しないこと。`west` は既定で破壊的更新を
  しない（衝突時は当該 project を skip）。ユーザーに確認するか、まず差分を提示する。
  ローカル作業が残る project は manifest の pin を進める前に reconcile（commit &
  push）する。

- **project の checkout が「リモートに存在しない ref」（`upload-pack: not our ref`）**
  で失敗した場合は、上流で force-push された可能性が高い。`manifest/west.yml` の
  当該 pin（= repos.edn 経由）の見直しが必要なので、ユーザーに報告する。

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
