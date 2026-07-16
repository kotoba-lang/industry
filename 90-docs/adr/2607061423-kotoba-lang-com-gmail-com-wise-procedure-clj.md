---
id: adr-2607061423-kotoba-lang-com-gmail-com-wise-procedure-clj
title: "ADR-2607061423: kotoba-lang に com-gmail / com-wise ポータブル .cljc API client と、多段階の外部手続き(KYC/アカウント設定等)を追跡する kotoba-procedure-clj を追加する"
status: accepted
doc_type: adr
topic: agent-loop
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - Gmail / Wise 向けに com-cloudflare と同型の portable .cljc API client を新設する判断
  - 複数ステップ・締切・相手ターンの往復がある「手続き(procedure)」を、単発の
    propose/approve/merge ゲート(kotoba-issue-clj)とは別形の状態機械として
    kotoba-procedure-clj に切り出す判断(kotoba-issue-clj への統合を却下)
  - 3ライブラリとも属性 namespace は強制統一せず、呼び出し側の既存スキーマを
    維持し境界でアダプトする方針(2607061600 と同じ原則)
related:
  - orgs/kotoba-lang/com-gmail          # 新規: Gmail API v1 の portable client
  - orgs/kotoba-lang/com-wise           # 新規: Wise Platform API の portable client
  - orgs/kotoba-lang/kotoba-procedure-clj # 新規: 手続き(複数ステップ+締切)の状態機械
  - orgs/kotoba-lang/com-cloudflare     # 前例: portable .cljc vendor API client の型
  - orgs/kotoba-lang/kotoba-issue-clj   # 前例: gate(propose/approve/merge)の型。procedureとは形が違う
  - orgs/kotoba-lang/kotoba-ledger-clj  # procedure の履歴も同じ event 語彙(JSONL)で投影できる(コード依存なし)
  - orgs/gftdcojp/local-manimani        # 発端: Wise手続きメールをmanimaniのtodoで処理しようとした際に発覚した欠落
  - 90-docs/adr/2607061600-kotoba-issue-ledger-shared-libs.md  # gate/ledger分割の前例、同じ判断原則を踏襲
  - 90-docs/adr/2606272330-cae-shared-libs-and-seeds.md        # 「目的別分割・grab-bag禁止」の前例
supersedes: []
superseded_by: []
---

# ADR-2607061423: com-gmail / com-wise / kotoba-procedure-clj

- Status: accepted(2026-07-06)。Phase A(3ライブラリの新規構築 + west 登録)完了。
  Phase B(local-manimani 側の配線)は未着手。

## 課題

オーナーの jun784@gmail に届いた「Wise法人アカウント開設(7日以内にログインして
完了しないと送金が返金される)」という手続きメールを、既存の local-manimani の
triage(todo policy)で処理しようとしたところ、3つの欠落が判明した:

1. **Gmail 書き込みの手段がない**: このセッションの Gmail 連携は read-only
   スコープで、`label_thread` 相当の書き込みが `insufficient authentication
   scopes` で拒否された。
2. **local-manimani 自体も実 Gmail に未接続**: `MANIMANI_SOURCE=mock` かつ
   `credentials.json`/`token.json` が存在せず、README が謳う
   「受信(Gmail)→ラベル付与/下書き作成」の自動処理は現状動いていない。
3. **「手続き」を追跡する共通の型がない**: 「7日以内にログインして開設完了」
   という締切・相手ターン(Wiseからの返信待ち⇄自分の対応待ち)を持つ複数ステップ
   の外部プロセスは、`kotoba-issue-clj` の gate(1回の propose→approve→merge)
   とは形が違い、既存ライブラリのどこにも居場所がなかった。決断ログ
   (`data/decisions.jsonl`)へ手作業で1行足す以外に手段がなかった。

`com-cloudflare` が「ad hoc curl の積み重ねに居場所を与えた」のと同じ構図で、
Gmail/Wise はどちらも今後も繰り返し使う vendor API であり、「手続き」は
KYC・口座開設・契約更新など今後も繰り返し現れる形である。

## 決定

### 1. `kotoba-lang/com-gmail` — Gmail API v1 の portable .cljc client

`com-cloudflare` と同型: 認証(Bearer OAuth2 access token)+ 差し替え可能な
`:http-fn` を注入する薄い client、機能別 namespace、テストは stub http-fn で
実アカウントに触れない。

```
gmail.client   -- 認証 + HTTP(JVM既定 java.net.http)+ base URL
gmail.threads  -- list/get/modify(ラベル追加削除)/archive
gmail.labels   -- list/create/find-or-create
gmail.drafts   -- 返信下書きの作成(RFC2822/base64url組み立て)
```

OAuth2 のトークン取得フロー(初回同意・リフレッシュ)自体はスコープ外 —
呼び出し側が用意した access token を渡すだけ。これにより、Claude の Gmail
連携が read-only スコープでも、`com-gmail` 側で別途 modify スコープの token を
用意すれば書き込み(ラベル変更・下書き作成)が可能になる。

`local-manimani/server/src/gmail.ts` の直接の cljc 移植ではなく独立の汎用
client として作る(TS実装は当面現状維持、移行は別ADR)。

### 2. `kotoba-lang/com-wise` — Wise Platform API の portable .cljc client

同じ設計原則:

```
wise.client     -- 認証(APIトークン Bearer)+ HTTP + base URL(sandbox切替可)
wise.profiles   -- list(個人/法人プロフィール)
wise.transfers  -- list/get(送金状態の参照)
wise.quotes     -- create(見積作成)
wise.recipients -- list(受取人口座の参照)
wise.balances   -- list(多通貨残高の参照)
```

送金実行や解約のような不可逆・対外的な書き込みは実装しない(read中心 +
quote作成まで)。将来そうした操作を追加する場合は ADR-0019 の risk gate
(`:financial`/`:destructive` は常に人間承認)を経由させる設計とし、本ADRの
スコープ外とする。

### 3. `kotoba-lang/kotoba-procedure-clj` — 締切・相手ターンのある多段階手続き

`kotoba-issue-clj` の gate は「1回の propose→approve/reject/request-changes→
merge」という単発の型で、締切や「相手のターン/自分のターン」という往復の概念を
持たない。KYC再提出・法人アカウント開設・契約更新のような「手続き」は形が違う
ため、既存 gate への統合はせず新規ライブラリに切り出す
(`2606272330-cae-shared-libs-and-seeds.md` の grab-bag 禁止と同じ判断)。

```
procedure.store  -- IProcedureStore(get/put/list-entities, append-audit!) + mem-store
procedure.gate   -- open!/advance-step!/block!/cancel!/note!、pure predicate: expired?/next-action
```

データ形は `:kotoba.procedure/*` を既定語彙とし、`kind` は呼び出し側が選ぶ
partition keyword(`:wise/corporate-account-setup` 等)として name-agnostic に
設計 — `kotoba-issue-clj` の `IssueStore` と同じ name-agnostic 方針
(2607061600)。履歴の投影は `kotoba-ledger-clj` と同じ event 語彙(フィールド形)
に揃えるが、コード依存はしない(2607061600 の「ゲートと台帳は読み手・生存期間が
違う」という判断をここでも踏襲)。

## 却下案

- **com-gmail と com-wise を1つの repo にまとめる**: 既存の `com-<vendor>` =
  1vendor=1repo という規約(`com-cloudflare`、`com-stripe` 等)に反する。対象
  読者・APIライフサイクルが違う vendor を1つの grab-bag にしない。
- **kotoba-procedure-clj を kotoba-issue-clj に統合**: 上記の通り、gate(単発
  propose/approve/merge)と procedure(複数ステップ・締切・相手ターンの往復)は
  形が違う。`2606272330` の前例と同じ理由で却下。
- **Gmail/Wise の OAuth トークン取得フローまで com-gmail/com-wise に含める**:
  トークン管理は呼び出し側(manimani の `secrets.cljc`、または各ホストの
  keychain/env)の責務とし、client 自体は「token を渡せば呼べる」薄い境界に
  留める。フロー自体を持たせるとホストごとの認可方式差異(desktop OAuth loop
  vs. サーバサイド refresh token vs. サービスアカウント)を抱え込み、
  `com-cloudflare` が `CLOUDFLARE_API_TOKEN` を注入させるだけに留めている
  設計と非対称になる。

## 実装フェーズ

- **Phase A(完了)**: `com-gmail`・`com-wise`・`kotoba-procedure-clj` を新規
  scaffold・テスト green・push、`manifest/repos.edn` の `:extra-projects` に
  登録、`nbb scripts/gen-west-manifest.cljs --entry com-gmail,com-wise,kotoba-procedure-clj`
  で最小 diff 生成、pin 検証通過。
- **Phase B(未着手)**: `local-manimani` の `gmail.ts`(TS)/`triage.cljs` 側で
  実際に `com-gmail`/`kotoba-procedure-clj` を使うよう配線し、今回の Wise
  手続きを procedure として実データ登録する。`MANIMANI_SOURCE=mock` を実
  Gmail 接続へ切り替える(OAuth credentials.json/token.json の用意)は別途
  オーナー対応が必要。
- **Phase C(未着手)**: 本ADRを `closed` へ更新。

## 検証

```
cd orgs/kotoba-lang/com-gmail            && clojure -M:test
cd orgs/kotoba-lang/com-wise             && clojure -M:test
cd orgs/kotoba-lang/kotoba-procedure-clj && clojure -M:test
nbb scripts/gen-west-manifest.cljs --entry com-gmail,com-wise,kotoba-procedure-clj
nbb scripts/gen-west-manifest.cljs --check
```
