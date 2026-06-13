# ADR-0011: gftdcojp M365 テナント全量アーカイブ(m365-archive データセット)

- **Status**: Accepted / Implemented(初回フルバックアップ完了・全量 B2 検証済み・日次運用中)
- **Date**: 2026-06-11
- **Deciders**: 河崎純真 (j.kawasaki@gftd.co.jp)
- **Context tags**: gftdcojp, m365, outlook, onedrive, msgraph, datalad, git-annex, backblaze-b2, gpg-hybrid, launchd, entra-app
- **Related**: ADR-0007(orgs レイアウト + B2 永続化パターン)、ADR-0009(multi-account mail ingest — gftd-co-jp の**個人 warehouse 向け**メール取り込み)
- **Implementation**: `orgs/gftdcojp/m365-archive/`(bin/{setup-auth.sh,run-backup.sh,ingest-mail.py,ingest-calendar.py,ingest-drive.sh,gpg-unlock.sh})、`~/Library/LaunchAgents/jp.co.gftd.m365-archive-backup.plist`、deps.edn `:gftdcojp :m365-archive`

## Context

gftdcojp テナント (gftd.co.jp) の Outlook / M365 データ全量(メール・カレンダー・
連絡先・OneDrive・SharePoint)を、GitHub repo に紐づく形で DataLad + B2 に保存したい。
ADR-0009 は個人 warehouse への**メール差分 ingest**(JSONL)だが、本件は**組織スコープの
全量アーカイブ**(MIME 原文 + ファイル実体)であり、custody も org に分離すべき。

## Decision

ADR-0007 の B2 永続化パターンを org 専用リソースで複製する:

1. **データセット**: `orgs/gftdcojp/m365-archive`(DataLad subdataset, id `4caeaa74-1a31-4c9c-bb71-97c16afb8793`)。
   git 履歴は **github.com/gftdcojp/m365-archive**(private, annex-ignore)、
   データ本体は annex → B2。`.gitattributes` で bin/ と *.md 以外は全 annex。
2. **B2**: 専用バケット `gftdcojp-m365-annex`(allPrivate, us-west-004,
   id `ed796ff64ca0abfa91e20518`)。special remote は `type=S3 signature=v4
   chunk=50MiB encryption=hybrid embedcreds=yes`(uuid `3336a5f2-298d-457d-9087-9fc81c653998`)。
   バケット限定 scoped key `004d9f6c0ba12580000000009`。
3. **暗号鍵**: org 専用 GPG 鍵 `EC1710FCE6FEB745B74D122FBDAE6794050EDB62`
   (gftdcojp M365 Archive)。個人鍵 (ADR-0003) とは分離。パスフレーズは
   Keychain `gpg:gftd-m365`、リカバリ(.asc + パスフレーズ + B2 key)は
   1Password gftdcojp vault『GPG: gftdcojp M365 archive recovery (EC1710FC)』。
4. **取得経路**:
   - メール/カレンダー/連絡先: Microsoft Graph(自前 Entra public-client アプリ
     `gftd-m365-archive` appId `0ca452ab-2650-4c4e-b19d-32f81d26d36b`、delegated
     Mail/Calendars/Contacts/Files/Sites read + admin consent)。トークンは
     CLI for Microsoft 365 (`m365 login --appId …`) が保持・自動更新。
     メールは全フォルダ再帰で **MIME 原文 .eml**(`mail/<folder>/<date>_<hash>.eml`、冪等)。
   - OneDrive/SharePoint: rclone `gftd-onedrive`(drive_id 固定で非対話化)。
5. **運用**: launchd `jp.co.gftd.m365-archive-backup` が毎日 02:00 に
   `run-backup.sh`(ingest → datalad save → B2 push → GitHub push)。
   単一実行ロック(`.backup.lock` mkdir)をスクリプト本体に内蔵。
   `annex.addunlocked=true` + `annex.thin=true`(rclone の in-place 差分更新と
   ディスク二重持ち回避)、`annex.jobs=8`。

## Outcome(初回フルバックアップ 2026-06-10〜11)

- メール **103,662 通**(全フォルダ、Teams 会話履歴含む)、カレンダー 6,495 イベント、
  連絡先 3 件、OneDrive **~131 GiB** → 合計 annex 131.4 GB。
- `git annex find --not --in b2` = **0 件**(全量 B2 到達を検証)。
- 教訓1: 単一ジョブの B2 push は 3.8 GB/h(暗号化↔転送が直列 + 高 RTT 単一 TCP)。
  `annex.jobs=8` で **31 GB/h(8.3 倍)**。日本→us-west-004 では並列必須。
- 教訓2: Graph 大量取得後の rclone OneDrive sync はスロットリング(429 backoff)で
  長時間無通信になる。スクリプトは冪等なので kill → 次回定期実行に委ねるのが正解。
- 教訓3: 実行中の bash スクリプトの編集は禁止(バイトオフセット実行)。
  ロック追加は走行完了後に実施した。
- 教訓4: rclone 標準アプリの consent は SP 作成レースで AADSTS650051 を出すことがある。
  SP 生成後の再試行で解消。

## Consequences

- (+) テナント喪失・アカウント凍結に対し、メール原文と全ファイルの暗号化コピーを
  自社管理(B2 + GitHub + ローカル)で保持。リストアは clone → 鍵 import → `datalad get`。
- (+) 差分日次なので 2 回目以降は数分〜数十分。
- (−) SharePoint サイトは未ミラー(rclone `gftd-sharepoint` 未設定。必要時に追加)。
- (−) Teams チャット本体・Planner 等 Graph の他ワークロードは対象外(メールに残る
  会話履歴のみ)。必要なら ingest スクリプト追加で拡張。
- (−) ローカル作業ツリーが 131 GiB を占有(thin で単一保持)。逼迫時は B2 を正として
  `datalad drop` 可能。
