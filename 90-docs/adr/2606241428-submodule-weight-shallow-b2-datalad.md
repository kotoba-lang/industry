---
id: adr-2606241428-submodule-weight-shallow-b2-datalad
title: "ADR-2606241428: 肥大 submodule を shallow 運用し、大容量データは B2 + DataLad へ"
status: active
doc_type: adr
topic: submodule-weight
authoritative: true
last_verified: 2026-06-24
authoritative_for:
  - superproject の重い submodule を縮小する手段 (shallow 運用 / force-push 禁止)
  - 大容量バイナリ (モデル重み / wasm / 動画 / 画像データセット) の保管方針 (B2 + DataLad)
  - git-annex の Backblaze B2 (S3 互換) special remote 初期化手順 (scripts/datalad-b2-init.bb)
  - clone/pull 高速化のためのローカル git 設定 (submodule.<name>.shallow / submodule.fetchJobs)
related: []
supersedes: []
superseded_by: []
---

# ADR-2606241428: 肥大 submodule を shallow 運用し、大容量データは B2 + DataLad へ

**Status**: accepted
**Date**: 2026-06-24
**Deciders**: Jun Kawasaki

## Context

superproject は多数（一部ネスト）の submodule を抱える。`git submodule update --recursive` の一部が
著しく重く、`.git` 全体が **35G** に達していた。内訳調査（`.git/modules` の pack サイズ）で、
少数の submodule が巨大バイナリを git 履歴に直接コミットしていることが原因と判明した:

| submodule | サイズ | 原因 |
|---|---:|---|
| ai-gftd-apps-gftdcojp | 16G | `.wasm` 6,684 オブジェクト（ビルド成果物を履歴に積み続け）+ `.safetensors` 等 |
| ghosthacker | 9.7G | 過去コミットの動画 (`.webm`) データセット |
| etzhayyim/root | 4.7G | （未コミット変更30件あり。今回は除外） |
| 260208-spirit-in-physics | 1.5G | 現行コミットのツリーに画像同梱 |
| spirit-in-physics | 1.1G | 履歴肥大（LFS 設定済みだが実体混在） |

LFS 移行（履歴書き換え + force-push）は根本解だが、共有 org リポの `main` を書き換え、
CLAUDE.md の「常に main と同期・乖離を作らない」方針と衝突し、他コラボレーターへ影響する。

## Decision

### 1. 肥大 submodule は shallow で運用する（force-push しない）

履歴肥大が原因のものは、ローカル `.git/config` の `submodule.<name>.shallow true` ＋
`git submodule update --init --depth 1` での再取得で縮小する。`submodule.fetchJobs 8` で
update を並列化する。**履歴書き換え/force-push は行わない**（main 乖離・共有リポへの影響を回避）。

実績（`.git` 全体: **35G → 8.4G**, 約77%削減）:

| submodule | 前 | 後 | 判定 |
|---|---:|---:|---|
| ai-gftd-apps-gftdcojp | 16G | **305M** | 履歴肥大 → shallow 効果大 |
| ghosthacker | 9.7G | **736M** | 履歴肥大 → 効果大 |
| spirit-in-physics | 1.1G | **62M** | 履歴肥大 → 効果大 |
| 260208-spirit-in-physics | 1.5G | 1.5G | 現行ツリーに画像同梱 → shallow 不可 |
| etzhayyim/root | 4.7G | 4.7G | 未コミット変更あり → 安全のため除外 |

注意点:
- `.git/modules` の格納キーは submodule の **パスではなく名前**（例: `projects/ghosthacker`）。
  削除時はこのパスを対象にする。
- `deinit -f` は作業ツリーの未コミット変更を破棄する。実行前に各 submodule の dirty を確認し、
  本物のローカル編集があるものは除外する（etzhayyim/root を除外したのはこのため）。
- `deinit` は `.git/config` の `submodule.<name>.*` を消すため、shallow 設定は再取得後に再付与する。

### 2. 今後の大容量データは B2 + DataLad（git に入れない）

モデル重み / wasm / 動画 / 画像データセット等の大きなバイナリを git 履歴に直接コミットしない。
**DataLad データセット + git-annex の Backblaze B2 (S3 互換) special remote** を使い、実体は B2 へ
push、git にはポインタ (annex キー) だけ残す。git-annex は B2 の S3 互換エンドポイントを
`type=S3 host=<endpoint> signature=v4` で扱える（外部ヘルパー・`b2` CLI 不要）。

初期化は `scripts/datalad-b2-init.bb`（babashka。既存 `.bb` フックと同じ `babashka.process`
スタイル）。**認証は環境変数のみで渡し、リポジトリに秘密情報を一切コミットしない**:

```bash
B2_KEY_ID=... B2_APP_KEY=... B2_BUCKET=... \
B2_ENDPOINT=s3.us-west-004.backblazeb2.com \
  scripts/datalad-b2-init.bb <dataset-dir> [remote-name]
# 以後: datalad save → datalad push --to b2 → datalad drop / datalad get
```

現行ツリー自体が重いもの（260208-spirit-in-physics 等）は shallow では縮まないため、
将来的にこの B2 + DataLad へ移すのが望ましい。

## Consequences

- (+) `.git` 35G → 8.4G。clone/pull/`submodule update` が大幅に軽量化
- (+) force-push を一切行わないため、共有 org リポの履歴・他クローンに影響しない（main 乖離なし）
- (+) 今後の大容量データは B2 に外部化され、superproject が再肥大しない仕組みができた
- (−) shallow は履歴が浅くなるため、深い `git log`/`bisect` が必要なときは個別に unshallow が要る
- (−) 現行ツリーに画像同梱の 260208-spirit-in-physics、未コミット変更を持つ etzhayyim/root は未対処
- (−) B2 運用は資格情報（keyID/appKey/bucket/endpoint）の各マシン設定が前提。秘密の取り回しは
  環境変数 + セッション直実行（`! VAR=… scripts/datalad-b2-init.bb …`）で会話・リポに残さない

## References

- 実装: `scripts/datalad-b2-init.bb`（B2 + DataLad 初期化）
- 方針: `CLAUDE.md` の「大容量バイナリの扱い（B2 + DataLad、最優先）」節
- git-annex S3 special remote（B2 は S3 互換 API で接続）
