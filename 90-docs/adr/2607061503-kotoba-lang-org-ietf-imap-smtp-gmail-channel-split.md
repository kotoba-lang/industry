---
id: adr-2607061503-kotoba-lang-org-ietf-imap-smtp-gmail-channel-split
title: "ADR-2607061503: IMAP(RFC 3501)/SMTP(RFC 5321)を org-ietf-imap / org-ietf-smtp として汎用プロトコルライブラリに切り出し、Gmail REST API チャネル(com-gmail)とは別チャネルとして local-manimani に共存させる"
status: accepted
doc_type: adr
topic: agent-loop
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - IMAP/SMTP をこの org の `org-<standards-body>-<spec>` 命名規約
    (org-ietf-turn/org-ietf-ical/org-ietf-oauth2 と同型)に従い
    `org-ietf-imap`/`org-ietf-smtp` として汎用プロトコルライブラリに切り出す判断
  - local-manimani の Email チャネルは「Gmail channel(com-gmail, REST API,
    OAuth2)」と「IMAP channel(org-ietf-imap/org-ietf-smtp, 任意のIMAPアカウント)」
    の2つを**別チャネルとして共存**させ、`manimani.source/channel-item` の
    共通正規化で束ねる(統合・二者択一ではない)という判断
  - local-manimani の `docs/adr/0022-email-sms-channels-mobile.md`
    (curlベース・依存ゼロのIMAP/SMTP)は、curl shell-out の部分のみ
    `org-ietf-imap`/`org-ietf-smtp` 呼び出しに置き換えて supersede する
    (Channel抽象・account scope・dedupなど0022の設計自体は維持)
related:
  - orgs/kotoba-lang/org-ietf-imap    # 新規: IMAP4rev1 (RFC 3501) client
  - orgs/kotoba-lang/org-ietf-smtp    # 新規: SMTP (RFC 5321) client
  - orgs/kotoba-lang/com-gmail        # 既存(本日新設): Gmail REST API v1 client — Gmail専用channelとして残す
  - orgs/kotoba-lang/org-ietf-turn    # 命名規約の前例(org-<standards-body>-<spec>)
  - orgs/kotoba-lang/org-w3-aria      # 命名規約の説明が書かれている前例README
  - orgs/gftdcojp/local-manimani      # Phase B: channels/email.clj の書き換え対象
  - 90-docs/adr/2607061423-kotoba-lang-com-gmail-com-wise-procedure-clj.md  # 前回ADR。Phase Bをこちらで上書き
supersedes: []
superseded_by: []
---

# ADR-2607061503: org-ietf-imap / org-ietf-smtp / Gmailチャネルとの共存

- Status: accepted(2026-07-06)。前ADR(2607061423)の「Phase B」を、オーナー指示
  により本ADRの内容に差し替える。

## 課題

前ADR(2607061423)は Phase B として「local-manimani の gmail.ts/triage.cljs から
com-gmail を使うよう配線」を想定していた。しかし local-manimani には既に
**`docs/adr/0022-email-sms-channels-mobile.md`(Closed)** という決定済み設計が
あり、そこでは意図的に「in-process の Gmail API クライアントは無い」「curl
ベース・依存ゼロ」の IMAP(ingress)/SMTP(egress)を選んでいた。com-gmail
(OAuth2 + Gmail REST API v1)をそのまま持ち込むと、この既存決定と衝突し、
Gmail アクセス経路が2系統(REST API と IMAP/SMTP)並存する上に、どちらも
場当たり的([curl shell-out] vs [新規ライブラリ])という状態になる。

オーナー指示: IMAP(など、SMTPも含む)は Gmail 専用にせず、この org の
`org-<standards-body>-<spec>` 命名規約(`org-ietf-turn`=RFC 8656、
`org-ietf-ical`、`org-ietf-oauth2` 等)に従って汎用プロトコルライブラリに
切り出し、Gmail channel(com-gmail)とは別の channel として接続する。
両者は `manimani.source/channel-item` の正規化(ADR-0021)で共通化されている
ため、"別チャネル・共通正規化" は既存設計とも整合する。

## 決定

### 1. `kotoba-lang/org-ietf-imap` — IMAP4rev1(RFC 3501)の汎用 client

curl shell-out(`curl-imap-fetch`)を、TCP+TLS を注入可能な transport 越しに
IMAP コマンド/レスポンスを直接話す実装に置き換える。プロトコルの
コマンド構築・レスポンス parse は純粋 `.cljc`、実ソケットは JVM-only 既定。

```
imap.transport -- Transport プロトコル(write!/read-line!/read-n!/close!) + JVM SSLSocket実装
imap.protocol  -- コマンド構築(pure) + レスポンス parse(pure): tagged/untagged, literal, FETCH/SEARCH
imap.client    -- login!/select!/search!/fetch-headers!/store!/logout! (transport越しの手続き)
```

curl 版になかった **STORE(+FLAGS \Seen 等)** を持たせ、ADR-0022 が
「gap」として明記していた「決定後の Seen 化/移動が未配線」を埋める。

### 2. `kotoba-lang/org-ietf-smtp` — SMTP(RFC 5321)の汎用 client

curl shell-out(`curl-smtp-send!`)を同じ設計方針で置き換える。

```
smtp.transport -- 同上 Transport(imap.transport と同じ形、コード共有はしない: プロトコルが別なので)
smtp.protocol  -- コマンド構築(pure): EHLO/AUTH/MAIL FROM/RCPT TO/DATA(dot-stuffing)/QUIT
smtp.client    -- send! (transport越しの手続き、AUTH LOGIN/PLAIN)
```

### 3. local-manimani: Gmail channel と IMAP channel を別々に共存させる

- **Gmail channel(新規, com-gmail 経由)**: OAuth2 access token を持つ Gmail
  アカウント向け。REST API なのでラベル操作(`addLabelIds`/`removeLabelIds`)が
  IMAP の非標準拡張(X-GM-LABELS)なしに正規にできる。
- **IMAP channel(既存 ADR-0022 の後継, org-ietf-imap/org-ietf-smtp 経由)**:
  任意の IMAP/SMTP アカウント(Gmail をアプリパスワードで使う場合も含め)向け。
  Gmail 固有の拡張には依存しない、素の RFC 3501/5321 実装。
- 両チャネルとも `manimani.source/channel-item`(ADR-0021)で同じ item 形へ
  正規化され、`thread-key` でチャネル横断 dedup される -- ここが「共通化」の
  実体で、チャネル実装そのものを1つに統合するのではない。
- `docs/adr/0022-email-sms-channels-mobile.md` は **supersede**(0022の
  Channel抽象・account scope・dedup 等の設計は維持し、curl shell-out の
  実装部分だけを org-ietf-imap/org-ietf-smtp 呼び出しに置き換える)。

## 却下案

- **com-gmail に一本化し IMAP/SMTP 経路を廃止**: 当初オーナーが検討したが、
  「IMAP は Gmail 専用にせず汎用化して別チャネルにする」という指示により
  却下。Gmail 以外の IMAP アカウント(仕事用メール等)を扱う道が残る。
- **IMAP/SMTP を local-manimani 内だけの private 実装にする**: この org の
  他プロジェクトが将来 IMAP/SMTP を要る場面(例: 他の actor が受信箱を持つ)
  で再度 curl shell-out を re-derive することになる。`com-cloudflare`/
  `com-gmail` と同じ理由でポータブルライブラリとして切り出す。
- **imap.transport / smtp.transport を1つの共有ライブラリにまとめる**:
  どちらも「行指向テキストプロトコル over TCP+TLS」という形は似ているが、
  `2606272330` の grab-bag 禁止と同じ判断で、プロトコルごとに独立させる
  (コマンド語彙・状態遷移が別物で、共有すると却って抽象が漏れる)。

## 実装フェーズ

- **Phase B-1(本ADRで実施)**: `org-ietf-imap`・`org-ietf-smtp` を新規
  scaffold・テスト green・push・manifest登録。
- **Phase B-2(本ADRで実施)**: local-manimani に Gmail channel(com-gmail)を
  新設、`channels/email.clj` を org-ietf-imap/org-ietf-smtp 呼び出しへ
  書き換え、local-manimani 側 ADR で 0022 を supersede。今回の Wise
  procedure を `kotoba-procedure-clj` で実データ登録。
- **Phase C(未着手)**: heartbeat/gateway 側の実運用切り替え(実際の
  OAuth token / IMAP app password を用意してのライブ稼働)はオーナー対応。

## 検証

```
cd orgs/kotoba-lang/org-ietf-imap && clojure -M:test
cd orgs/kotoba-lang/org-ietf-smtp && clojure -M:test
nbb scripts/gen-west-manifest.cljs --entry org-ietf-imap,org-ietf-smtp
nbb scripts/gen-west-manifest.cljs --check
```
