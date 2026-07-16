---
id: adr-2606241428-submodule-weight-shallow-b2-datalad
title: "ADR-2606241428: 肥大 submodule を shallow 運用し、大容量データは B2 + DataLad へ"
status: active
doc_type: adr
topic: submodule-weight
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - superproject の重い submodule を縮小する手段 (shallow 運用 / force-push 禁止)
  - 大容量バイナリ (モデル重み / wasm / 動画 / 画像データセット) の保管方針 (B2 + DataLad)
  - git-annex の Backblaze B2 (S3 互換) special remote 初期化手順 (scripts/datalad-b2-init.cljs)
  - clone/pull 高速化のためのローカル git 設定 (submodule.<name>.shallow / submodule.fetchJobs)
  - B2 creds の解決方式 (scripts/b2-creds.cljs / repos.edn :b2 :credentials。Keychain 単一アイテム b2:<bucket> 方式)
  - west annex-drop / annex-get による DataLad 実体の B2 退避・復元手順
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

実績（`.git` 全体: **35G → 5.0G**, 約86%削減）:

| submodule | 前 | 後 | 判定 |
|---|---:|---:|---|
| ai-gftd-apps-gftdcojp | 16G | **305M** | 履歴肥大 → shallow 効果大 |
| ghosthacker | 9.7G | **736M** | 履歴肥大 → 効果大 |
| spirit-in-physics | 1.1G | **62M** | 履歴肥大 → 効果大 |
| etzhayyim/root | 3.7G | **314M** | 履歴肥大 → 効果大（未コミット30件はバックアップ/復元で保全） |
| 260208-spirit-in-physics | 1.5G | 1.5G | 現行ツリーに画像同梱 → shallow 不可（force-push なしでは縮まない） |

注意点:
- `.git/modules` の格納キーは submodule の **パスではなく名前**（例: `projects/ghosthacker`）。
  削除時はこのパスを対象にする。
- `deinit -f` は作業ツリーの未コミット変更を破棄する。実行前に各 submodule の dirty を確認し、
  本物のローカル編集があるものは除外する（etzhayyim/root を除外したのはこのため）。
- `deinit` は `.git/config` の `submodule.<name>.*` を消すため、shallow 設定は再取得後に再付与する。
- worktree に **書き込み続ける稼働プロセス**がある submodule（etzhayyim/root は organism/vitals の
  生成データを吐くプロセスが動作中だった）では、`deinit` がツリーを消し切れず再 clone が
  「destination not empty」で失敗する。この場合は **干渉のない一時ディレクトリへ `git clone --depth 1`
  → `fetch --depth 1 <pin>` で superproject のピンに合わせる → その `.git` を `.git/modules/<name>` へ
  移設し core.worktree と gitlink を張り直す → `reset --hard <pin>` で実体化 → バックアップを復元**、
  という temp-clone 統合方式を使う（HTTPS リモートの etzhayyim/root もこれで 3.7G→314M に縮小、
  ローカル30件を保全して復旧できた）。

### 2. 今後の大容量データは B2 + DataLad（git に入れない）

モデル重み / wasm / 動画 / 画像データセット等の大きなバイナリを git 履歴に直接コミットしない。
**DataLad データセット + git-annex の Backblaze B2 (S3 互換) special remote** を使い、実体は B2 へ
push、git にはポインタ (annex キー) だけ残す。git-annex は B2 の S3 互換エンドポイントを
`type=S3 host=<endpoint> signature=v4` で扱える（外部ヘルパー・`b2` CLI 不要）。

初期化は `scripts/datalad-b2-init.cljs`（babashka。既存 `.cljs` フックと同じ `babashka.process`
スタイル）。**認証は環境変数のみで渡し、リポジトリに秘密情報を一切コミットしない**:

```bash
B2_KEY_ID=... B2_APP_KEY=... B2_BUCKET=... \
B2_ENDPOINT=s3.us-west-004.backblazeb2.com \
  scripts/datalad-b2-init.cljs <dataset-dir> [remote-name]
# 以後: datalad save → datalad push --to b2 → datalad drop / datalad get
```

現行ツリー自体が重いもの（260208-spirit-in-physics 等）は shallow では縮まないため、
将来的にこの B2 + DataLad へ移すのが望ましい。

## Consequences

- (+) `.git` 35G → 8.4G。clone/pull/`submodule update` が大幅に軽量化
- (+) force-push を一切行わないため、共有 org リポの履歴・他クローンに影響しない（main 乖離なし）
- (+) 今後の大容量データは B2 に外部化され、superproject が再肥大しない仕組みができた
- (−) shallow は履歴が浅くなるため、深い `git log`/`bisect` が必要なときは個別に unshallow が要る
- (−) 現行ツリーに画像同梱の 260208-spirit-in-physics は shallow 不可のまま（縮小には force-push を
  伴う履歴書き換え＝B2+DataLad 移行が必要で、本 ADR では見送り）。将来オーナー判断で対応する
- (−) B2 運用は資格情報（keyID/appKey/bucket/endpoint）の各マシン設定が前提。秘密の取り回しは
  環境変数 + セッション直実行（`! VAR=… scripts/datalad-b2-init.cljs …`）で会話・リポに残さない

## Update 2026-06-27: m365-archive の実 drop と B2 creds 解決の修正

ディスク逼迫（`/` が 98%、空き 25G）の棚卸しで、`orgs/gftdcojp/m365-archive`
(DataLad/git-annex) の実体が **145G ローカルに materialize されたまま**だったため、
本 ADR の方針通り `west annex-drop` で B2 へ退避（ローカル破棄）した。

- 実績: m365-archive **145G → 23G**（`datalad drop`、**117,652 ファイル**を B2 上の複製を
  検証してから破棄。data loss なし）。`/` 空きは 25G → 240G に回復。
- 復元は同 creds で `west annex-get`（必要時のみ）。
- 内訳の主因は `onedrive/` 133G（少数の大ファイル）。`mail/` は 3.6G だが小ファイル多数で、
  1 件ごとに B2 へ存在確認(HEAD)するため drop の所要時間を支配した。

### B2 creds 解決の不整合を修正（Keychain 単一アイテム方式を追加）

`west annex-drop` が当初 creds 未解決で skip した。原因は **`scripts/b2-creds.cljs` /
`manifest/repos.edn :b2 :credentials` の参照先がこのマシンの実体と不一致**だったこと:

- repos.edn は `:1password "op://Private/Backblaze B2/..."` を指すが、その item は実在しない
  （1Password 上の実体は vault `gftdcojp`）。
- repos.edn の Keychain 設定は「service `backblaze-b2` の下に key-id/app-key/bucket の
  3 account（各 password が値）」を前提にしていたが、**実際の Keychain は単一アイテム**
  （service=`b2:<bucket>`、account=KEY_ID、password=APP_KEY、bucket は service 名の `b2:` 以降）
  という別レイアウトだった。`-a <account> -w` が password しか返さない現行ロジックでは
  key-id/bucket を取り出せず解決に失敗していた。

対応:
- `scripts/b2-creds.cljs` の Keychain 解決に **`:combined true` モード**を追加。単一アイテムから
  `account`(=`security -g` の `"acct"`)→key-id、`password`(=`-w`)→app-key、service 名の
  `b2:` 以降→bucket を導く。従来の 3-account 方式とは後方互換。
- `manifest/repos.edn :b2 :credentials :keychain` を
  `{:service "b2:gftdcojp-m365-annex" :combined true}` に更新。`:1password` も
  vault `gftdcojp` の実 item パスへ。`env -u B2_*` でも keychain 経由で
  key-id/app-key/bucket が解決することを確認済み。
- 登録例: `security add-generic-password -s b2:gftdcojp-m365-annex -a <KEY_ID> -w <APP_KEY>`

教訓: **annex drop は B2 に複製が検証できたキーしかローカル削除しない**ため安全だが、その
検証には creds が必須。creds 参照先（op:// / keychain service）は秘密でないので repos.edn に
正しく書き、各マシンの保管レイアウトに合わせる（このマシンは `b2:<bucket>` 単一アイテム）。

## References

- 実装: `scripts/datalad-b2-init.cljs`（B2 + DataLad 初期化）/ `scripts/b2-creds.cljs`（creds 解決）
- 実装: `manifest/west_annex.py`（west annex-get / annex-drop）
- 方針: `CLAUDE.md` の「大容量バイナリの扱い（B2 + DataLad、最優先）」節
- git-annex S3 special remote（B2 は S3 互換 API で接続）
