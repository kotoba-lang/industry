---
name: large-binary-datalad
description: How to handle large binaries (model weights, wasm, video, image datasets) in this superproject via DataLad + git-annex + Backblaze B2, instead of committing them to git history, and why shallow clones were retired in favour of it. Use when adding, reasoning about, or asked about large binary/dataset data in this workspace.
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
    scripts/datalad-b2-init.cljk <dataset-dir> [remote-name]
  # 以後: datalad save → datalad push --to b2 → datalad drop / datalad get
  ```

  B2 認証は **`scripts/b2-creds.cljk`** が解決する（既定の順 env→1Password→Keychain。
  参照先は `manifest/repos.edn` の `:b2 :credentials`）。**実行には
  `orgs/kotoba-lang/secret-resolve/src` を classpath に足す**（無いと
  `Could not find namespace: secret-resolve.resolver` で落ちる。2026-08-05 実測）:
  `nbb --classpath ".:scripts/nbb_compat:orgs/kotoba-lang/secret-resolve/src" scripts/b2-creds.cljk`。`op`(1Password CLI) /
  `security`(Keychain) / 環境変数のどれでも同じコマンドで動く。**秘密情報は
  リポジトリに一切コミットしない**（EDN に置くのは `op://` パスや Keychain service 名
  といった非機密の参照先だけ）。CI では `B2_KEY_ID/B2_APP_KEY/B2_BUCKET` を env で渡す。

- **DataLad dataset は west に統合してある。** `manifest/repos.edn` の `:datalad`
  に登録した project は `manifest/west.yml` で `userdata.datalad: true` + `datalad`
  グループ（既定 `group-filter` の `-datalad` で off）になる。git/annex スケルトンの
  取得は `west update --group-filter +datalad <name>`、実体の取得/破棄は west 拡張
  コマンド `nbb manifest/west_annex.cljk annex-get` / `nbb manifest/west_annex.cljk annex-drop`（B2 special remote を環境変数の
  creds で有効化して get/drop）。実装は `manifest/west_annex.cljk`。

- ⚠ **shallow（clone-depth: 1）はもう使わない。2026-07-21 に撤回済み**
  （ADR-2607211600、CLAUDE.md「Git operations」）。この節はかつて「重い project は
  shallow で運用する」と書いていたが、**west が fetch のたびに clone-depth を再適用して
  新しい shallow graft を作り続けることが、`(forced update)` 偽陽性・pin 到達失敗の
  根本原因だった**（superproject の `.git` が 226 graft / 18GB に肥大したのに reachable
  commit は 2 件）。全 project は unshallow 済みで、**full 履歴が既定**。
  disk/帯域の恒久対策は shallow ではなく**この skill の B2 + DataLad 経路**である
  （それがこの skill の存在理由）。

- **numcopies を 2 にするなら off-machine の remote は 2 本要る**（2026-08-05 実測、
  ADR-2800003200 Phase 4）。remote が 1 本だと `datalad drop` が
  「Could only verify the existence of 1 out of 2 necessary copies」で拒否され、
  **手元のバイトを落として clone を軽くするというこの経路の目的が成立しない**。
  numcopies を 1 に下げれば drop は通るが、それは保証を弱めて通しただけ。
  `scripts/datalad-b2-init.cljk <dir> <remote-name>` を別 bucket / 別鍵の env で
  2 回実行して 2 本目を足すのが正しい（同一プロバイダでも bucket 削除と鍵 1 本の
  侵害には耐える。**プロバイダ障害には耐えない**ので、そう書く）。

- **「コピーが 2 本」と「プロバイダが 2 社」は別の主張で、いまは機械が区別する。**
  `annex-custody-verify` は git-annex branch の `remote.log` から各 remote の
  **provider**（S3 なら `host=`、external なら `externaltype=`）を引き、
  copy 数とは別に provider 数を印字する。`b2` と `b2-backup` が同じ host を指して
  いれば **1 社**に畳まれる —— remote 名を数えれば 2 に見えるが、そのプロバイダが
  落ちれば両方消えるので、それは 2 ではない。

  ```bash
  nbb --classpath ".:scripts/nbb_compat" scripts/annex-custody-verify.cljk --sample 0
  #   … providers=1 (s3.us-west-004.backblazeb2.com)  ← 既定は報告のみ
  nbb … scripts/annex-custody-verify.cljk --require-providers 2   # 満たさなければ exit 1
  ```

  **既定を fail にしていない**のは、今の構成（同一プロバイダの 2 bucket）は
  壊れているのではなく「プロバイダ障害には耐えない」という別の状態だから。
  2 社目を足した側が `--require-providers 2` を CI に入れて、足したことを機械に
  守らせる。2 本目は**別プロバイダの S3 互換**なら新規コード 0 行で足せる
  （`ANNEX_ENDPOINT` / `ANNEX_BUCKET` / `ANNEX_KEY_ID` / `ANNEX_APP_KEY` を渡して
  `datalad-b2-init.cljs` をもう一度）。Storj の Gateway-MT は署名付き S3 なので
  `type=S3` のまま入る（`kotoba-lang/io-storj` 参照）。

- **共有 bucket を使うときは `B2_FILEPREFIX` を必ず渡す。** legislation 4 件と
  tsukuru-manufacturing-artifacts はいずれも `gftdcojp-m365-annex` を prefix で
  分けて共有している（tsukuru はさらに 2 本目として `ai-gftd-datasets` にも
  同じ prefix で置いている）。

- **custody は主張ではなく検査で示す**:
  `nbb --classpath ".:scripts/nbb_compat" scripts/annex-custody-verify.cljk --names <dataset>`。
  off-machine コピーの無い annex ファイルがあれば exit 1、標本を remote から fsck する。
  ADR-2607252000（「バックアップがあると書いてあったのに実体が無かった」）の再発防止。
