# ADR-0016: Android SMS 全量 ingest（babashka 化）＋ 要対応 triage ＋ 端末上整理の経路

- **Status**: Accepted（587通 ingest 済 / sms-triage.edn 14件 / yabai IOC 共有 PR #1707。端末上整理アプリは R0 設計）
- **Date**: 2026-06-13
- **Deciders**: 河崎純真 (jun784@gmail.com)
- **Context tags**: personal-warehouse, android, adb, sms, babashka, clojure, git-annex, backblaze-b2, smishing, yabai, on-device-llm, gemma-e4b, yoro, clojurescript
- **Related**: ADR-0003（暗号化 warehouse）, ADR-0009（mail-sync.bb / sh→babashka 系譜）, ADR-0010（life-graph EDN）, ADR-0011（no public-network pinning）
- **Implementation**: `orgs/personal/bin/ingest-android.bb`, `orgs/personal/facts/{sms-triage.edn,devices.edn,coverage.edn}`, `orgs/personal/device/android/`（annex）, etzhayyim/root `20-actors/yabai/data/sms-smishing-jp-2026h1.kotoba.edn`（PR #1707）

## Context

接続中の Android（Pixel 10 Pro Fold, `58241FDCG000QG`, Android 16, arm64-v8a, 16GB RAM）の
SMS を全量取り込み、warehouse 規約（ADR-0003: git-annex `encryption=hybrid` → B2 暗号文のみ /
GitHub はポインタのみ）で整理し、内容から要対応事項を抽出、迷惑 SMS は防御 CTI として
etzhayyim の yabai に共有したい。さらに「端末側のメッセージも整理（不要 SMS の削除/振り分け）」
したい、という要望があった。

着手時の状況：

1. 既存 `orgs/personal/bin/ingest-android.sh`（埋め込み python で content-query をパース）が存在。
   前回 06-12 06:55 に 583通取得済だが、annex lock 済の dangling symlink が残り再実行が
   ENOENT で失敗していた。
2. ユーザ要望により ingest を **sh → Clojure(babashka)** へ統一（ADR-0009 mail-sync.bb と同系譜）。

## Decision

### 1. ingest-android.bb（sh+python → babashka）

`ingest-android.sh` を削除し `orgs/personal/bin/ingest-android.bb` に置換。adb で getprop/dumpsys/
pm list/content query を実行し、`"Row: N k=v, k=v"` を Clojure でパースして
`sms.jsonl` / `call_log.jsonl` / `contacts.json` を生成。要点：

- 旧 snapshot の annex lock 済 symlink は **書込前に `Files/deleteIfExists`** で除去（再実行可能）。
- packages はロケール依存 `sort` を**決定的な ASCII 順**に変更（snapshot 差分を安定化）。
- 検証：sh 版と bb 版を実機で並走比較 — raw 3ファイルは byte 一致、JSON/JSONL はキー順のみの
  差で意味的に同一、packages は順序差のみ。

取得結果（2026-06-13 snapshot ≈ 06-12 09:13 実行分）：**SMS 587通**（受信576/送信11,
2025-10-24..2026-06-12）/ 通話392 / 連絡先3。devices.edn・coverage.edn の snapshot を更新、
annex → B2 暗号コピー済。

### 2. sms-triage.edn（要対応の機械可読台帳）

`orgs/personal/facts/sms-triage.edn`（annex 暗号化）に SMS 内容から **14件** を抽出。
`:triage/status`（:open/:in-progress/:verify/:stale/:watch）+ `:triage/priority`。主な urgent/high：
弁護士法人あらた受任通知（期限超過）、三菱UFJニコス（弁護士委任予告）、Casa 賃料（明渡裁判警告→対応中）、
ペイディ6ヶ月延滞、Revolut 電話番号変更（乗っ取り確認要）、Paysera パスポート期限切れ。

### 3. 迷惑 SMS → yabai CTI（被害者情報は除外）

迷惑/フィッシング SMS は personal 側には保持せず、**攻撃者インフラのみ**を etzhayyim/root の
yabai に IOC 化（`20-actors/yabai/data/sms-smishing-jp-2026h1.kotoba.edn`, PR #1707）。
WhatsApp 偽装タイポスクワット16ドメイン + Mastercard/楽天/iCloud 偽装 + 偽装 sender-id 17 + 送信元電話2。
本人の電話番号等は G6/G10 に従い redact、TLP:CLEAR。詳細は etzhayyim ADR-2606131350。

### 4. 端末側の整理 = 既定SMSアプリ役割が必須（adb 不可を実証）

`adb shell content delete --uri content://sms` は **exit 0 を返すが実際には削除されない**
（削除前後とも587通で実証）。Android 16（非root）は SMS の書込/削除権限を **既定の SMS アプリ
役割（`RoleManager.ROLE_SMS`）保持者のみ**に与え、adb shell（UID 2000)はこれを持たないため。
現在の既定は Google メッセージ。

→ **結論**：端末上で SMS を削除/振り分けるには「既定 SMS アプリの役割を持つアプリ」が要る。
これは下記 on-device 整理アプリの設計に直結する（read-only の adb ingest は warehouse 用、
端末整理は別経路）。

### 5. on-device Gemma E4B QAT 整理アプリ（R0 設計、yoro 拡張）

端末上で SMS を分類・整理するアプリは、新規ではなく **yoro の拡張**が筋（etzhayyim ADR-2606131350
に R0 設計）。yoro Android は既に `AndroidDataImportPlugin`（`Telephony` + `READ_SMS`）で SMS を
ネイティブ読取済。追加するのは (a) `ROLE_SMS` 取得 → 書込/削除解禁、(b) MediaPipe LLM Inference /
LiteRT による **Gemma 3n E4B QAT int4（実効4B・約4.4GB）** の端末内推論プラグイン。本機は
16GB/arm64-v8a/Android16 で Baien edge-target（ADR-2605241900: Android 4GB ベースライン）を
大きく上回り余裕で動作。現状 Murakumo が `gemma4:e4b` をサーバ側で動かしている枠を端末へ降ろす形。

**ClojureScript 可否**：UI 層は cljs で書ける（yoro-ui に shadow-cljs + Reagent/re-frame 移行が
進行中＝実証済）。ただし「既定SMSアプリ役割」と「LiteRT による QAT 推論」はネイティブ(Kotlin)
プラグインが必須（WebView では `.task`/`.litertlm` は動かない）。よって構成は
**Capacitor + ClojureScript UI + ネイティブ Kotlin プラグイン2本**。

## Consequences

- warehouse の Android SMS は babashka 単一系譜（mail-sync.bb と統一）。点in時間 snapshot、継続同期ではない。
- 要対応は sms-triage.edn として追跡可能（life-graph と同型の EDN fact 層、ADR-0010）。
- 迷惑 SMS は防御 CTI として org 横断で再利用（personal に PII を溜めない）。
- 端末上の自動整理は yoro on-device アプリ実装まで保留（read-only ingest と分離）。

## Alternatives Considered

- **adb で直接削除**：不可（既定SMSアプリ役割が必須、実証済）。root 化は非対象。
- **sh 版 ingest 継続**：ユーザ要望により babashka へ統一（ADR-0009 整合）。
- **独立 SMS アプリ新規開発**：yoro が既に SMS 読取・fastlane・cljs 基盤を持つため拡張が低コスト。
- **サーバ側 E4B で整理**：SMS 本文を外部送出することになり PII/warehouse 原則に反する → on-device 必須。

## References

- ADR-0009（mail-sync.bb / sh→babashka）, ADR-0003（暗号化 warehouse）, ADR-0010（life-graph EDN）
- etzhayyim/root ADR-2606131350（yabai SMS smishing IOC ＋ yoro on-device E4B 整理アプリ R0）, PR #1707
- ADR-2605241900（Baien edge-target invariant）, ADR-2605215000（Murakumo-only inference）
