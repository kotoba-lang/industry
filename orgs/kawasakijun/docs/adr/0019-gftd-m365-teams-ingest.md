# ADR-0019: gftdcojp M365 Teams チャット/チャネル ingest (delegated Graph)

- **Status**: Accepted / Implemented(初回フル取得完了・B2 全量到達検証済み・日次運用に組込み)
- **Date**: 2026-06-17
- **Deciders**: 河崎純真 (j.kawasaki@gftd.co.jp)
- **Context tags**: gftdcojp, m365, teams, msgraph, delegated-scope, entra-app, datalad, git-annex
- **Related**: ADR-0011(gftdcojp M365 全量アーカイブ — 本 ADR はその「Teams 対象外」を解消する拡張)、ADR-0012(M365 EDN fact layer)
- **Implementation**: `orgs/gftdcojp/m365-archive/bin/{ingest-teams.py,setup-auth.bb}`、`run-backup.sh`/`run-backup.bb` に teams ステップ追記、1Password gftdcojp vault『gftd.m365/TEAMS_ARCHIVE_CONFIG』

## Context

ADR-0011 は gftdcojp テナントの mail/calendar/contacts/OneDrive/SharePoint を
アーカイブするが、**Teams チャット本体・チャネルメッセージは対象外**(メールに残る
会話履歴のみ)と明記し、「必要なら ingest スクリプト追加で拡張」としていた。
Teams 上の意思決定・やり取りを一次資料として保全したいため、この拡張を実施する。

Microsoft Graph で Teams メッセージを取る経路は 2 つあり、課金が決定的に異なる:

| 方式 | 取得範囲 | 課金 |
|---|---|---|
| **delegated**(サインインユーザー文脈) | 当人の 1:1/グループチャット + 参加チームのチャネル | **追加課金なし** |
| application(app-only `getAllMessages`) | テナント全社 | **Teams 保護 API の従量課金(Model A/B)が必須** |

個人/役員 1 名分の保全が目的であり、テナント全社を app-only で舐める必要はない。

## Decision

**delegated 専用**で、ADR-0011 と同じ Entra public-client アプリ
`gftd-m365-archive`(appId `0ca452ab-2650-4c4e-b19d-32f81d26d36b`)・同じ
`m365 login` トークン経路を再利用して取得する。application 方式(従量課金)は採らない。

1. **スコープ追加**(delegated, GUID は Graph SP から動的解決):
   - `Chat.Read` = `f501c180-9344-439a-bca0-6cbf209fd270`
   - `ChannelMessage.Read.All` = `767156cb-16ae-4d10-8f8b-41b657c8c8c8`
   - `Channel.ReadBasic.All` = `9d8982ae-4365-4f57-95e9-d6032a4c0b87`
   - `Team.ReadBasic.All` = `485be79e-c497-4b35-9400-0e3fa7f2a5d4`
   `bin/setup-auth.bb`(babashka 版 setup-auth) が冪等に追加 → admin-consent。
2. **取得経路** (`bin/ingest-teams.py`、delegated token):
   - チャット: `/me/chats?$expand=members` → 各 `/chats/{id}/messages`。
   - チャネル: `/me/joinedTeams` → `/teams/{id}/channels` →
     `/teams/{id}/channels/{ch}/messages?$expand=replies`(返信をインライン展開、
     N+1 回避)。展開が打ち切られた長スレッドのみ `/messages/{id}/replies` で補完。
3. **レイアウト**(全 annex 管理 = B2 へ gpg 暗号化、平文は git に載らない):
   ```
   teams/chats.json                          チャット roster
   teams/teams.json                          参加チーム + チャネル索引
   teams/chats/<名前>_<hash>.jsonl           1:1/グループチャット (Graph 原文 JSONL)
   teams/channels/<チーム>/<チャネル>.jsonl  チャネルメッセージ + 返信 (Graph 原文)
   ```
4. **運用**: `run-backup.{sh,bb}` の calendar の後に teams ingest を追加
   (失敗は non-fatal)。日次 launchd 運用(ADR-0011)に自動的に乗る。冪等(全上書き、
   annex が MD5E で dedup)。

## Outcome(初回フル取得 2026-06-17)

- チャット **992 件 / 46,033 メッセージ**、チャネル **165 チーム・461 チャネル /
  14,775 メッセージ**(返信インライン)。合計 **約 60,800 メッセージ / teams/ ~210 MB**。
- `git annex find --include='teams/*' --not --in b2` = **0 件**(全量 B2 到達を検証)。
- 実発言の送信者名・タイムスタンプ・本文・チャネルのネスト返信まで取得を確認。

## 教訓(再現・保守のための注意)

1. **consent と grant は別物**: `az ad app permission add` はアプリの
   `requiredResourceAccess` を更新するだけ。`az ad app permission admin-consent` が
   既存の `oauth2PermissionGrant` に新スコープを**追記しないことがある**(CLI の癖)。
   その場合は grant を直接 PATCH する:
   `az rest --method PATCH --url .../oauth2PermissionGrants/{id} --body '{"scope":"<全スコープ空白区切り>"}'`。
   付与状況は `oauth2PermissionGrants?$filter=clientId eq '<SP id>'` の `scope` で検証。
2. **トークンは再ログインで反映**: grant 更新後も `m365 util accesstoken get --new` は
   旧スコープのキャッシュ refresh token を使う。`m365 logout && m365 login` が必須。
3. **device-code がループする場合は browser auth**: `m365 login` 既定の device code が
   ブラウザの既存 MS アカウント競合で同じページにリダイレクトし続けることがある。
   `m365 login --authType browser`(localhost リダイレクト、アプリに `http://localhost`
   登録済み)で回避。
4. **`$top` 非対応エンドポイント**: `/me/joinedTeams` は `$top` を付けると 400。
   `/me/chats`・channel messages・replies は `$top` 可。
5. **古い `@thread.skype` チャネルは 2 ページ目で 504**: 一部の旧チャネルは nextLink
   継続ページが `$expand` の有無に関わらずサーバ側 504(UnknownError)を返す(Graph の
   制限、クライアントからは回避不能)。`ingest-teams.py` は 5xx を 3 回で即 give-up し
   `skip … partial` をログ、最新 1 ページのみ保存して全体は継続する。403(アクセス権
   なし)も同様にスキップ継続。初回は 30 件が 403/504 でスキップ。
6. **delegated の射程**: サインインユーザー自身のチャットと**参加している**チームの
   チャネルのみ。退出済み/未参加のチームは取得不可(app-only=従量課金が必要)。

## Consequences

- (+) ADR-0011 の積み残し(Teams 本体)を、追加課金ゼロ・既存認証基盤の再利用で解消。
- (+) Teams 上の意思決定・連絡を Graph 原文 JSONL で保全。fact layer(ADR-0012)へ
  取り込む素地ができた(将来 `extract-facts.py` 拡張で people/threads に統合可能)。
- (−) 504 で 2 ページ目以降が取れない旧チャネルは最新 50 件のみ(サーバ制限)。
- (−) 返信を root メッセージ内にネストする Graph 形状のまま保存(downstream で
  flatten が必要)。
- (−) 全上書き ingest のため、メッセージ削除/編集の履歴差分は保持しない(各回の
  最新状態のスナップショット)。
