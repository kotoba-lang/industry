# ADR-0007: 外部 repo 群の orgs/ レイアウト再配置と Backblaze B2 永続化

- **Status**: Implemented（Phase 1/2/3/4 完了・main push 済み。2026-06-09）
- **Date**: 2026-06-08（実装完了 2026-06-09）
- **Deciders**: 河崎純真 (jun784@gmail.com)
- **Context tags**: submodule, datalad, git-annex, backblaze-b2, storage-policy, repo-reorg, orgs-layout, litigation-evidence

> **Update (2026-06-23, ADR-0020)**: 本 ADR は配置キーを *GitHub owner* とした。ADR-0020 が
> *概念タクソノミ*（etzhayyim=agent中心 / gftdcojp=企業app+契約 / com-junkawasaki=lib+個人）を
> 上位基準として追加し、owner と概念が食い違う場合は概念を「あるべき配置」の正本とする。
> これに伴い `ai-gftd-lf-case-lingling`（本 ADR では `orgs/com-junkawasaki/` に配置）は
> 2026-06-23 に **gftdcojp org へ完全移送**され `orgs/gftdcojp/ai-gftd-lf-case-lingling` へ移動した。
> 下表の同 repo 行はこの移送以前の歴史的記録。

## Context

ローカルに散在する 2 つの作業ディレクトリ配下の repo 群を `com-junkawasaki`
ワークスペースに整理・再配置したい。

- `/Users/junkawasaki/gftdcojp/` — 7 repo
- `/Users/junkawasaki/github/` — 6 repo（+ `com-junkawasaki` 自身）

### 調査結果（2026-06-08）

**全 13 repo が git 管理済み**。「git 未管理だから取り込む」ケースは 0 件。
代わりに **remote が存在しない**（GitHub に push 先が無い）repo が 4 件あった。

| 分類 | repo | size | branch | remote | 既存submodule? |
|---|---|---|---|---|---|
| A | ai-gftd-apps-gftdcojp | — | `260507-shinshi` | gftdcojp/ai-gftd-apps-gftdcojp | ✓ projects/ + orgs/ |
| A | archive_ai-gftd-lf-case-lingling | — | main | com-junkawasaki/ai-gftd-lf-case-lingling | ✓ projects/ |
| A | etzhayyim-root | — | main(16 dirty) | etzhayyim/root | ✓ projects/ + orgs/ |
| A | 260208-spirit-in-physics | — | main | com-junkawasaki/260208-spirit-in-physics | ✓ projects/ |
| A | ghosthacker | — | `260330-svelte` | com-junkawasaki/ghosthacker | ✓ projects/ |
| A | spirit-in-physics | — | main | com-junkawasaki/spirit-in-physics | ✓ projects/ |
| A | webmaster | — | main | com-junkawasaki/webmaster | ✓ projects/ |
| B | moex | 1.8M | main | com-junkawasaki/moex | ✗ |
| B | kotoba-topology | 5.6M | main | com-junkawasaki/kotoba-topology | ✗ |
| C | systemofsystem | 16M | main | **なし** | ✗ |
| C | mangaka-ghosthacker-assets | 1.2G | main | **なし** | ✗ |
| C | yukkuri-assets-nist-csf-cis-scs | 36M | main | **なし** | ✗ |
| C | jk-luxury-drive-archive | **41G** | main | **なし** | ✗ |

- **A群** = 既に `com-junkawasaki` の submodule。外部 dir のものは重複ローカル clone。
  submodule のピンは branch でなく commit のため、feature branch
  (`260507-shinshi` / `260330-svelte`) のままで問題なし（各 upstream と同期済み）。
- **C群の `jk-luxury-drive-archive`(41G)** は OUCHI 訴状準備・SQL dump・Telegram export
  等の**訴訟証拠**を含む（ADR-0005 の保全対象）。

### 既存構造の問題

`projects/<repo>`（用途別）と `orgs/<org>/<repo>`（owner 別）に**同一 repo が二重登録**
されており（例: etzhayyim/root, gftdcojp/ai-gftd-apps）、SSoT が割れている。

## Decision

### 1. レイアウトは `orgs/<org>/<repo>` に統一

GitHub の owner(org) を一次キーとする。`projects/` の重複 submodule は廃し `orgs/` へ集約。

| 配置先 | repo |
|---|---|
| `orgs/com-junkawasaki/` | ghosthacker, ai-gftd-lf-case-lingling, 260208-spirit-in-physics, spirit-in-physics, webmaster, **moex**(新), **kotoba-topology**(新) |
| `orgs/etzhayyim/` | root |
| `orgs/gftdcojp/` | ai-gftd-apps-gftdcojp |
| `orgs/jun784/` | researches-yamato-ll1（旧 apps/yamato-ll1） |

- **2604-linde**（Lean4, `submodule=false` の素ディレクトリ）は `orgs/com-junkawasaki/2604-linde`
  として素のまま維持。

### 2. remote 無し C 群 → 当面「素のフォルダ」で取り込み

GitHub remote が無い repo は、いきなり submodule 化せず `.git` を除去して
`com-junkawasaki`（DataLad superdataset）に通常コンテンツとして取り込む。
後日 remote を作成した時点で submodule へ昇格できる。

| repo | 取り込み先 | 大容量機構 |
|---|---|---|
| systemofsystem (16M) | `orgs/com-junkawasaki/systemofsystem` | git 直管理 |
| yukkuri-assets-nist-csf-cis-scs (36M) | `orgs/com-junkawasaki/yukkuri-assets-nist-csf-cis-scs` | 大物は annex→B2 |
| mangaka-ghosthacker-assets (1.2G) | `orgs/com-junkawasaki/mangaka-ghosthacker-assets` | **annex→B2**（git は pointer のみ） |

superdataset が DataLad（`.datalad/config` id=9a0395b2…）のため、素フォルダでも
`.gitattributes` の annex ルートに乗る大容量ファイルは自動で git-annex 管理になる。

### 3. `jk-luxury-drive-archive`(41G) → `personal/` 暗号化 warehouse

訴訟証拠であり ADR-0003/0005 の保全ポリシー（係争保全・SHA-256 + OpenTimestamps 封緘・
gpg-hybrid 暗号・暗号文のみ外部 remote）に統合する。
`personal/drive/jk-luxury-archive/` 配下の DataLad annex とし、実体は暗号化して B2 へ。
git 本体・GitHub には pointer のみ（平文は決して push しない）。

### 4. Backblaze B2 を git-annex **S3互換** special remote として全 dataset に追加

既存方針 `[personal.storage]`（vcs=datalad / content=git-annex / remote=ipfs /
encryption=hybrid, key `09EE841334482F5A0F5C4958A70BB2C220DE88CA`）に **B2 を追加**。

> **実装メモ**: 現行 git-annex 10.20251215 には built-in `type=B2` が**無い**
> （remote types に B2 不在）。B2 の **S3 互換 API** を使い `type=S3` で接続する。

```bash
# 各 DataLad dataset で一度だけ initremote（superdataset / personal / 大容量サブ）
export AWS_ACCESS_KEY_ID=<b2 keyID>          # B2 application keyID
export AWS_SECRET_ACCESS_KEY=<b2 appKey>     # B2 applicationKey
git annex initremote b2 type=S3 \
    host=s3.us-west-004.backblazeb2.com port=443 \
    bucket=com-junkawasaki-annex \
    signature=v4 chunk=50MiB \
    encryption=hybrid keyid=09EE841334482F5A0F5C4958A70BB2C220DE88CA
datalad push --to b2          # 実体を B2 へ、git には pointer のみ
```

- **アカウント/エンドポイント**: gftdcojp B2（region `us-west-004`,
  endpoint `s3.us-west-004.backblazeb2.com`）。
- **バケット**: 専用 `com-junkawasaki-annex` を新規作成（master key で bucket + scoped key
  を発行し、訴訟証拠を gftd 資産と分離）。発行した scoped key は 1Password に格納。
- **認証**: S3 互換のため `AWS_ACCESS_KEY_ID`=keyID / `AWS_SECRET_ACCESS_KEY`=appKey を
  環境変数で渡す（macOS Keychain / 1Password 保管、値は git に入れない）。
- **暗号**: `encryption=hybrid` で既存 GPG 鍵を再利用 → personal warehouse と同一封緘。
- **チャンク**: `chunk=50MiB` で 41G archive を分割し再開可能アップロードにする。
- **IPFS**: 副系として残置（B2=主系 / IPFS=冗長）。完全一本化はしない。

`[personal.storage]` を更新:
```toml
[personal.storage]
vcs = "datalad"
content = "git-annex (annex-ignore on origin)"
remote_primary = "backblaze-b2 (git-annex type=S3, S3-compatible, encryption=hybrid)"
remote_secondary = "ipfs (external special remote, encryption=hybrid)"
b2_account = "gftdcojp"
b2_endpoint = "s3.us-west-004.backblazeb2.com"
bucket = "com-junkawasaki-annex"
credential_custody = "1Password (gftdcojp vault); env AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY"
```

## Consequences

- (+) SSoT が `orgs/<org>/` に一本化、重複 submodule が解消。
- (+) 大容量（mangaka 1.2G / jk-archive 41G）が git から annex→B2 に逃げ、リポジトリが軽量。
- (+) 訴訟証拠が既存保全ポリシー（暗号化・封緘）下で永続化。
- (+) B2 は予算/帯域が GitHub LFS と独立 → ADR-0006 の購入ロック問題を迂回。
- (−) B2 バケット・application key の作成が前提（未実施＝外部依存）。
- (−) 41G の初回アップロードは時間とエグレス/ストレージ費用が発生。
- (−) `projects/` 廃止に伴い deps.edn の path 群と既存リンクの追従が必要。
- (−) ADR-0006（spirit-in-physics LFS→annex）は GitHub 購入ロックで依然ブロック。

## 実行フェーズ

1. **Phase 0（前提）**: B2 バケット `com-junkawasaki-annex` + application key を作成し
   `B2_ACCOUNT_ID`/`B2_APP_KEY` を Keychain/1Password に格納。← **ユーザ作業**
2. **Phase 1（可逆・低リスク）**: `orgs/` レイアウト統一。`projects/` 重複 submodule を
   `git rm` し orgs/ の gitlink に集約。moex / kotoba-topology を
   `orgs/com-junkawasaki/` に `submodule add`。
3. **Phase 2**: C 群 3 repo（systemofsystem / yukkuri / mangaka）の `.git` を除去し
   `orgs/com-junkawasaki/` に素フォルダ取り込み。`.gitattributes` に asset 拡張子の
   annex ルートを追加。`datalad save`。
4. **Phase 3**: `jk-luxury-drive-archive` を `personal/drive/jk-luxury-archive/` に
   DataLad annex 取り込み（平文を git に載せない）。
5. **Phase 4**: 全 dataset で `git annex initremote b2 …` → `datalad push --to b2`。
   `:personal :storage` 更新、deps.edn の path 群を orgs/ に追従。

## Status / Next（2026-06-09 更新）

進捗ログ:

- **Phase 1 完了**（commit `4495a2e0`）: `projects/` → `orgs/<org>/` 統一。重複 submodule
  (ai-gftd-apps-gftdcojp) 削除、stale 2 件除去、moex / kotoba-topology 追加、
  ghosthacker の worktree path 修復、2604-linde 移設。remote の kotoba 追加を rebase で統合。
- **Phase 4 完了**（commit `26c2ea4f`）: B2 バケット `com-junkawasaki-annex`
  (allPrivate, us-west-004, id `8d596f46ac50ab9a91e20518`) + scoped key
  (`004d9f6c…008`, 1Password `com-junkawasaki.b2/annex`) を master key で作成。
  `git annex initremote b2 type=S3`（signature=v4, chunk=50MiB, **encryption=hybrid**,
  **embedcreds=yes**）。`testremote --fast` 125/125 合格（env 有無とも）。
  GPG passphrase は Keychain `gpg:personal-data` から agent にプリセット
  （keygrip 9B98…D091, max-cache 2h）。
- **Phase 2 完了**（commit `35a3da8d`）: remote 無し C 群を `orgs/com-junkawasaki/` に
  素フォルダ取り込み。systemofsystem(18→git)、yukkuri-assets(wav/png annex)、
  mangaka-ghosthacker-assets(768 png + pdf annex)。元 repo は git-annex のため
  symlink を実体化(`rsync -L`)し superdataset annex(MD5E) に再登録。
  **813 キー(≈890M)を B2 へ copy 完了**（欠損 0、B2 から fsck OK）。
- **ディスク確保完了**: 冗長な外部 clone `gftdcojp/ai-gftd-apps-gftdcojp`(44G) と
  `github/ghosthacker`(23G)（全コミット remote 済・固有作業ゼロ）を削除し
  **26G → 89G** に回復。両者は com-junkawasaki 内 submodule から復元可。
- **Phase 3 完了**（commit `a170183a`）: `jk-luxury-drive-archive` 41G を
  `personal/drive/jk-luxury-archive/` に copy → 全ファイル annex(MD5E)。
  archive 内 nested `.gitignore` 由来 484 ファイルも `--no-check-gitignore --force-large`
  で強制保全（.DS_Store 17 のみ除外、tracked 26,135）。annex-add の dedup で ~8G 回収。
  **全 28,327 キー(≈33G unique) を B2 へ copy 完了**（欠損 0、`fsck --from b2` で
  証拠 PDF まで実検証 OK）。ローカル + B2 の 2 コピー確認後、元の単独 clone を削除
  （**89G → 97G**）。

- 完了: **全 Phase（1/2/3/4）完了・main へ push 済み**。全大容量コンテンツ
  （C 群 890M + jk-archive 33G）が `com-junkawasaki-annex` バケットに encryption=hybrid で永続化。
- 残（任意）: (a) 他の冗長外部 clone 整理（260208 4.7G / etzhayyim-root 3.1G dirty 等）
  (b) `git submodule update --init --force` での worktree 復元（spirit-in-physics/webmaster
  は ADR-0006 の GitHub 購入ロック解除後）。

## Alternatives considered

- **projects/ フラット統一**: deps.edn 既存記述と整合的だが owner 情報が失われる → 却下。
- **二重構造維持**: SSoT が割れ続ける → 却下。
- **C 群を即 GitHub private + submodule 化**: 作業量大かつ 41G/1.2G を GitHub に載せる
  必要が生じる → 「とりあえず素フォルダ + B2」を優先。
- **IPFS 一本化（B2 不使用）**: ピン維持コストと取得性で B2 が運用容易 → B2 を主系に。
