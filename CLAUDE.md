# CLAUDE.md

## Git operations

This repository is a superproject with many (and nested) submodules.

- **常に `main` と同期し、乖離を作らない（最優先）。** 何らかの git 操作
  （pull / checkout / commit / branch 作業の開始など）を行う前に、上流 `main`
  に更新があれば必ず先に同期する。ローカルが `main` より遅れている状態
  （`git rev-list --left-right --count origin/main...HEAD` の左側が非ゼロ）で
  新しい作業を積み上げない。fast-forward 可能なら `--ff-only` で取り込む:

  ```bash
  git fetch origin
  git pull --ff-only           # 乖離していなければ FF で取り込む
  git submodule update --init --recursive
  ```

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
