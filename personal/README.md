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
│  └─ ingest-device.sh      ← 端末情報 ingest (env は名前のみ収集)
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

## 鍵の状態（鍵固め 完了）
- ✅ gpg 鍵に**強力なパスフレーズ付与済み**（空パスフレーズは拒否されることを検証）。
- ✅ パスフレーズを **macOS login Keychain** 項目 `gpg:personal-data` に保管（Touch ID/パスコードで保護）。
- ✅ 非対話運用は `bash personal/bin/gpg-unlock.sh`（Keychain → gpg-agent にキャッシュ）。
- ✅ **オフライン復旧鍵**を `~/personal-data-gpg-RECOVERY-YYYYMMDD.asc` にエクスポート済み（パスフレーズ保護）。

### 復旧鍵の取り扱い（あなたの手作業 — 重要）
```bash
# 紙に印刷 → 金庫 / KeePass に取り込み、その後ディスクから安全消去
lpr ~/personal-data-gpg-RECOVERY-*.asc        # 印刷
# KeePass に貼り付けたら:
rm -P ~/personal-data-gpg-RECOVERY-*.asc       # secure delete (上書き削除)
```

## まだ残る注意（任意の追加対策）
1. **iCloud Keychain での端末間同期**: `security` で入れた項目は既定でローカル(login Keychain)。Apple 端末間同期が必要なら iCloud Keychain を有効化し、項目を同期可能にする/または pinentry-mac の GUI 入力時に「Save in Keychain」。最も確実な多端末 custody はオフライン復旧鍵の保管。
2. ローカル annex オブジェクト(`.git/annex/objects`)は**平文**。端末全体の暗号化(FileVault)で別途保護推奨。

## 注意
- IPFS は CID を知る者が取得可能。暗号文のみ pin される設計だが、**pin 実行は明示操作時のみ**。
- `personal/` 配下の生データを GitHub に push しても annex ポインタのみ（平文は乗らない）。
