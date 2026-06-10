# ADR-0008: Google Takeout 全サービス export を personal warehouse に取り込む

- **Status**: Accepted（pipeline 稼働中。残 part は手動 download 待ち＝外部依存）
- **Date**: 2026-06-10
- **Deciders**: 河崎純真 (jun784@gmail.com)
- **Context tags**: personal-warehouse, google-takeout, git-annex, backblaze-b2, passkey, gpg, disk-capacity, jun784, jk-luxury

## Context

2 アカウントの Google Takeout（全サービス）を warehouse に保全する：

| account | 経路 | サイズ | parts | 備考 |
|---|---|---|---|---|
| `jun784`    | `/u/0` | 388.34 GB | 61 | **part 41 = 261.7 GB の単一ファイル**（part 跨ぎ分割不可）、part 37=15.19GB、part 61=5.54GB |
| `jk-luxury` (root@jk.luxury) | `/u/1` | 164.78 GB | 85 | 全 part ≈ 2 GB |

取り込み着手時に判明した制約：

1. **内蔵ディスク空き ~27–29 GB のみ**（460GB 中 405GB 使用）。553GB 全量はもちろん、
   part 41（261.7GB 単一）は streaming でも不可。
2. **Takeout download endpoint は part ごとに passkey 再認証を要求**。manage ページの
   `rapt` トークンを download endpoint が継承せず、`rapt` 付き URL でも challenge に飛ぶ。
   さらに**自動（プログラム）クリックは passkey 後 manage に戻り download されない**——
   ユーザ自身のクリック→passkey フローでのみ download が完走する（Chrome の
   multiple-automatic-downloads guard も programmatic click を阻む）。
3. **curl 直 download 不可**：署名付き download URL は harness が cookie/query を遮断し読めない。
4. **B2 encryption=hybrid の暗号化に personal gpg 鍵の unlock が必要**。pinentry-mac は
   独自 Keychain 項目しか自動参照せず、custody 項目 `gpg:personal-data` を読まないため
   pinentry が誤入力ループ（2/3）に陥った。

## Decision

1. **ディスク掃除**：B2 に在る annex content をローカル drop（`git annex drop --in b2`）。
   sole-copy だった litigation xlsx 1 件のみ先に B2 へ push して保護。41GB→1.2MB、
   空き 27→**68 GB**。warehouse の定常状態（pointer-only ローカル, 実体は B2）に回帰。
2. **part 単位 streaming pipeline**（peak disk ≈ 1 part）：
   `~/Downloads の takeout-*.zip → ditto 展開(Unicode安全) → git annex add(MD5E)
   → git annex copy --to b2 -J8(hybrid暗号) → git annex drop`。
3. **watcher 常駐**（`personal/bin/takeout-watcher.sh`）：job-timestamp prefix で
   account 振り分け（registry 駆動 `personal/bin/registry takeout-jobs`、毎パス再読込）、
   `~/.takeout-watcher/processed.log` で冪等、`(1)` 重複は正規化して skip、
   毎パス Keychain から gpg passphrase を gpg-agent に preset（2h cache 対策）。
   進捗は `personal/bin/takeout-status.sh`。
4. **gpg passphrase 非対話 unlock**：`security find-generic-password -s gpg:personal-data`
   → `gpg-preset-passphrase`（agent の `allow-preset-passphrase` 利用）。GUI へ打鍵しない。
5. **役割分担**：download（passkey 認証込み）はユーザがブラウザで実施、後段は watcher が自動処理。
6. **part 41（261.7GB）は外付けドライブ保留**（≥300GB を staging に充ててから annex→B2→drop）。
7. **配置**：`personal/takeout/<account>/<YYYY-MM-DD>/Takeout/`。`personal/.gitattributes`
   により全データ annex+暗号、平文は GitHub に載らない（ADR-0003/0004/0005 準拠）。

## Consequences

- (+) warehouse 既存の B2/gpg/datalad パターンを再利用。peak disk が 1 part 分に有界。冪等で再開可能。
- (+) 両 account の経路を実証：jun784 part1=502 files、jk-luxury part1=881 files が B2 在・ローカル drop 済。
- (−) 残り 144 part は **1 part ＝ 1 passkey** の手動 download。低速・ユーザ依存。archive 有効期限 **2026-06-16**。
- (−) **part 41（261.7GB）は外付けドライブが来るまで復元不能**（既知の保留）。
- (−) 小ファイル多数 → per-file B2 overhead（`-J8` 並列で緩和。part1 jun784=233MB/502files で ~9分）。

## Status / Next

- 完了：ディスク掃除(40GB)、pipeline 実装・両 account 実証、watcher/status/registry scripts、gpg/Keychain 配線。
- 待ち：ユーザによる残 part の手動 download（watcher が自動取り込み）。
- 次：(a) 残 part download → 自動 ingest → `takeout-status.sh` で 61/61・85/85 を確認、
  (b) 外付けドライブで part 41 を別途取り込み、(c) 期限 6/16 前に全 part 完了。

## Alternatives considered

- **Takeout → Google Drive 配信 → Drive API/MCP で取得**（passkey 不要）：~553GB の Drive 空き容量と
  再 export（数時間）が必要 → 保留（容量がネック）。
- **Google API 直接（OAuth, personal-warehouse-jk）**：passkey 無しで Gmail/Drive/Calendar/Photos 等を取得可。
  ただし Keep / Chat 履歴等 Takeout 固有データは API 制約あり → 補完手段として併用余地。
- **ブラウザ download 完全自動化**：passkey/部品 + Chrome multi-download guard で不可 → 却下。
- **curl + 抽出 URL**：署名付き URL を harness が遮断 → 不可。
