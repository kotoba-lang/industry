# personal/ — 個人データ ウェアハウス (DataLad + git-annex + 暗号化 IPFS)

この端末・Gmail・アカウント等の個人データを集約し、分析できるよう整理した DataLad データセットの一部。

## 設計（合意済み）
- **保存**: この repo (`com-junkawasaki`, GitHub private) 内に直接。
- **暗号**: gpg + git-annex `encryption=hybrid`。鍵 = `09EE841334482F5A0F5C4958A70BB2C220DE88CA`（暗号副鍵 `D1CA341CF2327694`）。
- **鍵保管**: gpg-agent → **pinentry-mac**（→ iCloud Keychain 同期、Touch ID 解錠）。
- **遠隔**: `ipfs` special remote（external, `encryption=hybrid`）。**IPFS には gpg 暗号文だけ**が pin される。
- **GitHub には平文が出ない**: `origin` は annex-ignore 済み。データは annex 管理（git にはポインタのみ）。

## レイアウト
```
personal/
├─ README.md                ← これ (git 平文)
├─ .gitattributes           ← personal/** を annex 化 (データ), bin/*.md は git 平文
├─ bin/
│  ├─ git-annex-remote-ipfs ← 自作 IPFS external special remote (暗号文を ipfs add)
│  ├─ registry              ← accounts/registry.edn の query helper (babashka, bash 向け TSV 出力)
│  ├─ takeout-watcher.sh    ← Takeout zip の自動 ingest (registry 駆動; extract→annex→b2→drop)
│  ├─ takeout-status.sh     ← ingest 進捗表示 (registry 駆動)
│  └─ ingest-device.sh      ← 端末情報 ingest (env は名前のみ収集)
├─ accounts/registry.edn    ← 全アカウント(5)の宣言的レジストリ (git 平文, secrets なし)
│                              Takeout job prefix→account のルーティングもここに追記
├─ device/    端末: system/hardware/packages(brew,pip,npm,cargo)/dotfiles/disk/env名
├─ mail/      Gmail: labels.json(107 ラベル分類), recent-activity-30d.jsonl
├─ calendar/  Google Calendar: events.json
├─ drive/     Google Drive: files-inventory.json (メタデータのみ, 50件)
├─ accounts/  GitHub: user/orgs/repos/ssh-keys/token-scopes (トークン値は非保存)
└─ analysis/  observations.md (初期分析)
```

## データの暗号化 → IPFS pin（暗号文のみ送出）
```bash
# 1) データを annex に取り込み（symlink 化）
git annex add personal/

# 2) gpg 暗号文として IPFS に pin（CID は git-annex branch に記録）
git annex copy personal/ --to ipfs

# 3) 確認（[ipfs] にコピーがあること）
git annex whereis personal/mail/labels.json
```
> 検証済み: IPFS 上のオブジェクトは OpenPGP 暗号パケット(`8c0d0409…`)で、平文は出現しない。

## 復号・取得
```bash
git annex get personal/<path>          # ローカルに無ければ ipfs から取得し gpg 復号
cat personal/mail/labels.json
```
gpg-agent が pinentry-mac 経由でパスフレーズを要求（現状この鍵は未パスフレーズ＝下記「後で」）。

## 再 ingest
```bash
bash personal/bin/ingest-device.sh     # 端末情報を更新
# Gmail/Calendar/Drive/GitHub は Claude(MCP)/gh 経由で再取得
git annex add personal/ && git annex copy personal/ --to ipfs
```

## メール定期 ingest（launchd 毎日 07:30）
registry の `mail.sync=true` な全アカウントを `bin/mail-sync.sh` が差分 ingest:
token mint (Keychain refresh token) → `ingest-gmail-batch.py --account <slug>
newer_than:<window>`（message id / 内容ハッシュで冪等）→ annex add → b2 copy → commit。
ログ: `~/.mail-sync/sync.log`。手動実行: `bash personal/bin/mail-sync.sh`。

**Auth bootstrap（1回だけ・ブラウザ必要）**
```bash
# 1) GCP (com-junkawasaki-sip): Gmail API 有効化, OAuth 同意画面=External/Testing,
#    4 アカウントを test user に追加, 「デスクトップアプリ」クライアント作成 → JSON DL
python3 personal/bin/google-auth.py client-set ~/Downloads/client_secret_*.json  # → Keychain, json は削除
# 2) アカウント毎にブラウザで同意（refresh token → Keychain）
python3 personal/bin/google-auth.py login jun784       # 他: jk-luxury / gftd-group / junkawasaki-com
python3 personal/bin/google-auth.py status             # 確認
```
gftd-co-jp (M365) は未実装（Graph API + Entra アプリ登録が必要 — registry に TODO）。

## 鍵の状態（鍵固め 完了）
- ✅ gpg 鍵に**強力なパスフレーズ付与済み**（空パスフレーズは拒否されることを検証）。
- ✅ パスフレーズを **macOS login Keychain** 項目 `gpg:personal-data` に保管（Touch ID/パスコードで保護）。
- ✅ 非対話運用は `bash personal/bin/gpg-unlock.sh`（Keychain → gpg-agent にキャッシュ）。
- ✅ **復旧鍵を 1Password に保管済み**: Private vault / アイテム「GPG: personal-data warehouse recovery (jun784)」。armored 秘密鍵(.asc)＋パスフレーズ＋keyid を格納。ディスク上の `.asc` は安全消去済み。

### 復旧手順（鍵を失った/別端末で復元する時）
```bash
# 1Password から armored 秘密鍵を取り出して import
op read "op://Private/GPG: personal-data warehouse recovery (jun784)/recovery_key.asc" \
  | gpg --import
# パスフレーズも同アイテムの passphrase フィールドにある
op item get "GPG: personal-data warehouse recovery (jun784)" --fields passphrase --reveal
# あとは git annex get personal/<path> で IPFS から復号取得
```

## まだ残る注意（任意の追加対策）
1. **多端末 custody は 1Password に集約済み**（復旧鍵＋パスフレーズ）。macOS Keychain 項目 `gpg:personal-data` は当該 Mac の日常解錠用（既定でローカル）。別 Apple 端末で日常的に使うなら iCloud Keychain 同期 or 各端末で `op` から passphrase を取得。
2. ローカル annex オブジェクト(`.git/annex/objects`)は**平文**。端末全体の暗号化(FileVault)で別途保護推奨。

## 注意
- IPFS は CID を知る者が取得可能。暗号文のみ pin される設計だが、**pin 実行は明示操作時のみ**。
- `personal/` 配下の生データを GitHub に push しても annex ポインタのみ（平文は乗らない）。
