---
name: large-binary-datalad
description: How to handle large binaries (model weights, wasm, video, image datasets) in this superproject via DataLad + git-annex + Backblaze B2, instead of committing them to git history, and how existing heavy west projects stay shallow. Use when adding, reasoning about, or asked about large binary/dataset data in this workspace.
---

## 大容量バイナリの扱い（B2 + DataLad、最優先）

- **モデル重み / wasm / 動画 / 画像データセット等の大きなバイナリを git 履歴に
  直接コミットしない。** これらは clone/pull を重くする最大要因（過去に
  ai-gftd-apps=16G, ghosthacker=9.7G 等まで肥大）。新規に大容量データを置く必要が
  あるときは **DataLad データセット + git-annex の Backblaze B2 (S3 互換) special
  remote** を使う。実体は B2 へ push し、git にはポインタ(annex キー)だけ残す。

  ```bash
  B2_KEY_ID=... B2_APP_KEY=... B2_BUCKET=... \
  B2_ENDPOINT=s3.us-west-004.backblazeb2.com \
    scripts/datalad-b2-init.cljs <dataset-dir> [remote-name]
  # 以後: datalad save → datalad push --to b2 → datalad drop / datalad get
  ```

  B2 認証は **`scripts/b2-creds.cljs`** が解決する（既定の順 env→1Password→Keychain。
  参照先は `manifest/repos.edn` の `:b2 :credentials`）。`op`(1Password CLI) /
  `security`(Keychain) / 環境変数のどれでも同じコマンドで動く。**秘密情報は
  リポジトリに一切コミットしない**（EDN に置くのは `op://` パスや Keychain service 名
  といった非機密の参照先だけ）。CI では `B2_KEY_ID/B2_APP_KEY/B2_BUCKET` を env で渡す。

- **DataLad dataset は west に統合してある。** `manifest/repos.edn` の `:datalad`
  に登録した project は `manifest/west.yml` で `userdata.datalad: true` + `datalad`
  グループ（既定 `group-filter` の `-datalad` で off）になる。git/annex スケルトンの
  取得は `west update --group-filter +datalad <name>`、実体の取得/破棄は west 拡張
  コマンド `nbb manifest/west_annex.cljs annex-get` / `nbb manifest/west_annex.cljs annex-drop`（B2 special remote を環境変数の
  creds で有効化して get/drop）。実装は `manifest/west_annex.cljs`。

- **既存の重い project は shallow（clone-depth: 1）で運用する。** west は
  clone-depth: 1 を既定にしてある（実績: ai-gftd-apps 16G→305M, ghosthacker
  9.7G→736M, spirit-in-physics 1.1G→62M）。現行ツリー自体が重いもの（画像同梱の
  260208-spirit-in-physics 等）は shallow では縮まないため、将来的に上記 B2+DataLad
  へ移すのが望ましい。なお shallow 化に伴う履歴書き換え＋force-push は**行わない**
  （main 乖離・共有リポへの影響を避けるため、shallow 運用で対処する）。
