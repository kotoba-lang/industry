# ADR-0009: 5 アカウントのメールを定期 ingest（registry 駆動 + per-account OAuth）

- **Status**: Accepted（jun784 / jk-luxury 稼働・ingest 済み。残 3 アカウントは認証待ち＝外部依存）
- **Date**: 2026-06-10
- **Deciders**: 河崎純真 (jun784@gmail.com)
- **Context tags**: personal-warehouse, multi-account, gmail-api, msgraph, oauth, keychain, launchd, git-annex, backblaze-b2, registry-driven
- **Related**: ADR-0003（暗号化 IPFS warehouse）, ADR-0004（account scope & file body）, ADR-0008（Google Takeout 取り込み）
- **Implementation**: `orgs/personal/accounts/registry.edn`, `orgs/personal/bin/{registry,google-auth.py,msgraph-auth.py,ingest-gmail-batch.py,ingest-graph-mail.py,mail-sync.sh}`, `orgs/personal/bin/launchd/com.junkawasaki.mail-sync.plist`

## Context

保有する 5 アカウント（4 Google + 1 Microsoft 365）のメールを、warehouse
（ADR-0003: git-annex `encryption=hybrid` → B2 は暗号文のみ・GitHub はポインタのみ）へ
**継続的に**取り込み、コードベースで管理したい。

| slug | email | provider |
|---|---|---|
| jun784 | jun784@gmail.com | google |
| jk-luxury | root@jk.luxury | google |
| gftd-group | jun@gftd.group | google (Workspace) |
| junkawasaki-com | root@junkawasaki.com | google |
| gftd-co-jp | j.kawasaki@gftd.co.jp | microsoft (M365) |

ADR-0008 の Takeout は**一括バックフィル**には適すが、(a) part ごとの passkey 再認証で
重く、(b) 定期差分には向かない。継続運用には API ベースの差分 ingest が要る。

着手時の制約：

1. **gcloud の既定 ADC client は Gmail restricted scope をブロック**（「このアプリは
   ブロックされます」）。`gcloud auth application-default login --scopes=...gmail.readonly`
   は通らない（ADR-0008 でも既知）。→ 自前 OAuth client が必須。
2. **新しい Google OAuth client は secret 全文の再表示・JSON download が廃止**された。
   作成直後ダイアログの一度きり、または「Add secret」で新規発行時のみ全文が出る。
3. **新規 OAuth client は consent 画面が External/Testing**。テストユーザー未登録の
   アカウントは認可不可。
4. **同意フローは未確認アプリ警告 → スコープ選択 → 続行のクリックを要する**。
   `login_hint` を付けても、当該アカウントが Chrome にサインイン済みでなければ
   Google の**パスワード入力**に落ちる（自動化不可・安全ルール上も不可）。
5. **M365 は Takeout 相当が無い**。Graph API（delegated, device-code）で別途取得が要る。
6. 秘匿情報（client secret / refresh token）を**ディスク・git に残さない**こと（ADR-0003 方針）。

## Decision

### 1. アカウントレジストリをコード化（宣言的・単一の真実源）

`orgs/personal/accounts/registry.edn`（git 平文・**secret 無し**）に 5 アカウントと
Takeout job ルーティング、`[accounts.<slug>.mail]`（`sync` / `window` / `authorized`）を宣言。
`orgs/personal/bin/registry`（python3/tomllib）が bash 向け TSV で供給
（`accounts` / `mail-accounts` / `email` / `takeout-jobs` …）。watcher・mail-sync・auth
スクリプトは全てここを読む。ADR-0008 の watcher も registry 駆動に一般化済み（毎パス再読込）。

### 2. per-account OAuth、token は Keychain のみ

- **Google**: `orgs/personal/bin/google-auth.py`。GCP project `personal-warehouse-jk`
  （Gmail/Calendar/Drive API 有効化、consent=External/Testing、4 Google を test user 登録）、
  **デスクトップ client**。`client-set` で client(JSON) を Keychain
  `google-oauth-client` へ取り込み JSON は安全削除。`login <slug>` は **loopback
  (127.0.0.1) authorization-code flow**（access_type=offline, prompt=consent）で
  refresh token を `google-oauth:<slug>` へ格納。`token <slug>` が都度 access token を mint。
- **Microsoft**: `orgs/personal/bin/msgraph-auth.py`。Entra **public client** の
  **device-code flow**（secret 無し）。MS は refresh token を毎回ローテートするため
  都度 `msgraph-oauth:<slug>` を更新。
- scope は read-only（gmail/calendar/drive.metadata、Graph は Mail.Read/Calendars.Read/Files.Read/User.Read）。
  client も token も**ディスク・git に出さない**（Keychain 一元管理。custody は 1Password/iCloud Keychain）。

### 3. content-addressed・冪等な ingest（全 provider 共通の CAS に合流）

- `ingest-gmail-batch.py --account <slug>`（Gmail REST, format=RAW）と
  `ingest-graph-mail.py --account <slug>`（Graph `/messages/{id}/$value` MIME）は、
  どちらも `mail/messages/<cid>.eml`（cid=sha256(RFC822)）+ `index.jsonl` に書く。
- **冪等**: message id と内容ハッシュで重複排除。同一メールを複数アカウントで受信
  しても **.eml は 1 つ**、index には (account, message_id) の sighting を別途記録。

### 4. 定期実行と封緘（launchd 日次）

`orgs/personal/bin/mail-sync.sh`：registry の `mail.sync=true` を走査 → token mint →
`newer_than:<window>` のスライディング窓で差分 ingest（窓重複は冪等で無害）→
`git annex add` → `git annex copy --to b2`（hybrid 暗号）→ pointer を commit。
annex 管理の `index.jsonl` は get→unlock→追記→re-lock のラウンドトリップで扱う。
gpg passphrase は ADR-0008 同様 Keychain `gpg:personal-data` から gpg-agent に preset。
`launchd com.junkawasaki.mail-sync`（毎日 07:30）。token 無しアカウントは警告 skip。

### 5. 役割分担（自動化の境界＝安全ルール）

- **自動（私/エージェント）**: GCP/Entra セットアップ、registry/コード、Keychain への
  client/token 格納、サインイン**済み**アカウントの OAuth 同意クリック、ingest 全段。
- **人間（ユーザ）**: パスワードを伴う**サインインそのもの**（認証）。
  `login_hint` を付けても未サインインのアカウントは Google のパスワード入力に落ち、
  これは安全ルール上エージェントが行わない（computer use / 1Password 経由でも不可）。
  → 当該アカウントを Chrome にサインインしてもらってから同意を駆動する。

## Status / Rollout（2026-06-12 更新）

| slug | auth | ingest |
|---|---|---|
| jun784 | ✅ Keychain | ✅ 稼働（mail-sync.bb 15分毎） |
| jk-luxury | ✅ Keychain | ✅ 稼働（同上） |
| gftd-group | ⏳ | Chrome 未サインイン → `google-auth.py login gftd-group` |
| junkawasaki-com | ✗ **blocked** | **Gmail ServiceNotAllowed**（下記）。registry `sync=false` |
| gftd-co-jp | ⏳ | Entra app 登録 → `msgraph-auth.py client-set <id>` → `login gftd-co-jp` |

### 実装更新（2026-06-12）

- **実行系を babashka(Clojure) に統合**: `mail-sync.sh`+`ingest-gmail-batch.py` →
  `orgs/personal/bin/mail-sync.bb` 1 本（index.jsonl は byte 互換）。launchd を **15 分間隔**
  + bb 実行 + `EnvironmentVariables PATH` 修正（旧 07:30 cron は launchd 最小 PATH で
  git-annex 解決失敗＝0 件同期だった）。書込パス 2 バグ修正（① index が annex lock の
  まま append が無に帰す → unlock 検証を追加、② 新着 .eml が既存 locked symlink で
  Permission denied → 存在チェックで上書き回避）。

### junkawasaki-com の認可不能（実検証 2026-06-12）

`login junkawasaki-com` を試行 → OAuth 同意が `access.workspace.google.com/ServiceNotAllowed`
（「Gmail へのアクセス権がありません」）で弾かれ、**認可コードが発行されない**。

- **token 実体は jun784 に落ちる**: `login_hint=root@junkawasaki.com` は強制力が無く、
  Chrome のアクティブ垢（jun784=u/0）が承認してしまう。検証で token の `emailAddress`=
  jun784・messagesTotal=65,062 が jun784 と一致。`authuser=2`（root@junkawasaki.com の
  Chrome index）強制でも ServiceNotAllowed の壁は同じ。誤 token は削除・registry `sync=false`。
- **原因は admin で Gmail サービス無効**: Workspace 支払いは生きている（本人確認 2026-06-12）
  ため未払い停止ではない。`admin.google.com`（junkawasaki.com）→ Apps → Workspace →
  **Gmail を ON** が唯一の前提。有効化後は Chrome u/2 サインイン済み＋authuser 強制で
  1 回開通できる（認証基盤は整備済み）。
- 制約 #4（login_hint 非強制）に **#7 を追加**: 多垢サインイン時は `authuser=<index>` を
  AUTH_URL に付与して対象垢を強制する（`NO_BROWSER_OPEN=1` でエージェント駆動）。

`authorized` は INTENT、実際の token 有無は `google-auth.py status` が正本、
サービス可否は Gmail `/users/me/profile` の応答（200 か ServiceNotAllowed）が正本。

## Consequences

**Positive**
- 5 アカウント横断のメールが単一 CAS（`mail/messages`）に冪等合流。重複排除済み。
- 秘匿情報がディスル・git に出ない（Keychain 一元、warehouse は暗号文のみ B2）。
- registry が単一の真実源。アカウント/job 追加は宣言の 1 ブロックで watcher/mail-sync に反映。
- Google/Microsoft を同じ index スキーマに正規化。Calendar/Drive も同 token で将来拡張可。

**Negative / リスク**
- consent=Testing のため refresh token は**約 7 日で失効しうる**（未確認アプリ）。
  恒久運用には consent を本番公開（または内部アプリ化）が要る。→ 別 ADR 候補。
- 初回 `login` はアカウントが Chrome にサインイン済みである必要（人手依存）。
- M365 経路（`ingest-graph-mail.py`）は未実走（コードのみ）。Entra 登録後に検証要。
- Takeout（ADR-0008）と API ingest が併存。content-addressed なので重複は出ないが、
  同一メールが Takeout mbox 由来と API 由来で別 source として両方 sighting に載りうる。

## Alternatives considered

- **Takeout の定期エクスポート（2 か月毎×1 年）のみで回す**: 差分が粗く、メールの鮮度が
  低い。却下（バックフィルは Takeout、差分は API の併用に）。
- **gcloud ADC を使う**: Gmail restricted scope がブロックされ不可（ADR-0008 既知）。
- **client secret を git/ファイルに置く**: ADR-0003 方針に反する。Keychain 一元に。
- **M365 を .pst エクスポート → readpst → ingest-mbox.py**: 退避路として有効だが一括向き。
  継続差分は Graph API を主とする。
