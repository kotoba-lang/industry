# CLAUDE.md

## Git operations

This repository is a superproject with many (and nested) submodules.

- **`git pull` / `git submodule update --recursive` は shallow（`--depth 1`）を
  デフォルトにする。** 巨大 superproject + 多数のネスト submodule で全履歴を取得
  すると時間・帯域・ディスクを浪費するため、明示的に full 履歴が必要な場合
  （`git bisect` / 古いコミットへの `git blame` / 履歴を跨ぐ調査）を除き、常に
  `--depth 1` を付ける。fetch も `--depth 1` に揃える:

  ```bash
  git fetch --depth 1 origin
  git pull --ff-only --depth 1
  git submodule update --init --recursive --depth 1
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

  さらに、**submodule ポインタの前進のような単純更新は、ローカルで shallow マージ
  を戦うより GitHub API でサーバ側（full 履歴）に commit を起こす方が確実かつ安い**
  （merge-base も ancestry もサーバが計算するため shallow 問題に触れない）。
  実例: PR #61 / #62 は main の tree をベースに gitlink だけ差し替えた
  クリーン commit を API で作成してマージした。

- **常に `main` と同期し、乖離を作らない（最優先）。** 何らかの git 操作
  （pull / checkout / commit / branch 作業の開始など）を行う前に、上流 `main`
  に更新があれば必ず先に同期する。ローカルが `main` より遅れている状態
  （`git rev-list --left-right --count origin/main...HEAD` の左側が非ゼロ）で
  新しい作業を積み上げない。fast-forward 可能なら `--ff-only` で取り込む:

  ```bash
  git fetch --depth 1 origin
  git pull --ff-only --depth 1                       # 乖離していなければ FF で取り込む
  git submodule update --init --recursive --depth 1
  ```

- **`git push` の前に必ず `origin/main` との遅れを解消する。** push しようとする
  リポ（superproject / submodule とも）が `origin/main`（既定ブランチ）より遅れて
  いる場合は、先に同期してから push する:

  ```bash
  git fetch origin
  git merge --ff-only origin/main      # FF 不可なら merge / rebase で乖離を解消
  ```

  これは PreToolUse フック `.claude/hooks/git-push-main-sync-guard.bb`（babashka）で強制される
  （遅れた状態の `git push` は deny され、同期を促すメッセージが返る）。フックは
  破壊的な自動マージはしない（判定と指示のみ、fail-open）。

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

- ユーザーが「git pull」とだけ指示した場合も、上記の main 同期 + submodule
  update まで含めて実行する（プルだけで終わらせない）。

- submodule の checkout が **ローカルの未コミット変更** で失敗した場合は、
  勝手に `--force` で破棄しないこと。ユーザーに確認するか、まず差分を提示する。

- submodule の checkout が **リモートに存在しない ref**（例:
  `upload-pack: not our ref`）で失敗した場合は、上流で force-push された可能性が
  高い。該当 submodule のピン先 commit の見直しが必要なので、ユーザーに報告する。

- submodule の clone が **`remote: Not Found`**（例: `url = ./...` の相対 URL を
  持つ DataLad / git-annex のローカル専用データセット submodule）で失敗するのは
  **想定内・無害**。これらは per-machine のローカルデータで origin から clone
  できない。`--force` で消したり報告だけで止めたりせず、他の submodule 更新を
  完遂させる（該当パスは未初期化のまま放置でよい）。

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

  B2 認証は環境変数のみで渡し、**リポジトリには秘密情報を一切コミットしない**。

- **既存の重い submodule は shallow で運用する。** 履歴肥大が原因のものは
  `submodule.<name>.shallow true`（ローカル `.git/config`）＋ `--depth 1` 再取得で
  大幅に縮む（実績: ai-gftd-apps 16G→305M, ghosthacker 9.7G→736M,
  spirit-in-physics 1.1G→62M）。現行ツリー自体が重いもの（画像同梱の
  260208-spirit-in-physics 等）は shallow では縮まないため、将来的に上記
  B2+DataLad へ移すのが望ましい。`submodule.fetchJobs 8` で update を並列化する。
  なお shallow 化に伴う履歴書き換え＋force-push は**行わない**（main 乖離・共有
  リポへの影響を避けるため、shallow 運用で対処する）。
