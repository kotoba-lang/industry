# Commercial Go / No-Go

**Observed:** 2026-08-08（gate は run 0021 で再実測、ポートフォリオ数値は `90-docs/business/metrics/*.edn` の as-of 2026-08-08 実測）
**Purpose:** 技術的に決済可能であることと、正当に販売可能であることを分離する。

`green` の証拠が揃うまで、agent は外部顧客への有料勧誘、live checkoutの公開、
実決済の受領を行わない。draft、推測、実装済みコードは承認の代用にならない。

**この表は現在値である。** 前版（2026-07-24）は cloud-itonami の 6 gate を red と
していたが、うち 4 つは run 0018/0019 と ADR-2607242600 で同日〜翌日に閉じており、
15 日間 red のまま放置されていた。停まっていたのは商流ではなく**この表**である。
その結果 SCORECARD の全 action が存在しない red を根拠に `blocked` を維持していた。

## Portfolio revenue state（2026-08-08 実測）

**4 product すべて外部実入金ゼロ。** これは推定ではなく計測値である。

| Product | 外部実入金 | 実測ファネル | 決済状態 |
|---|---|---|---|
| net-kotobase (kotobase.net) | **0** | 訪問 2,453 → 登録 13 → checkout **0** | Stripe active subs 0 / x402 challenge 14・submission 3・settlement **0** |
| cloud-murakumo (murakumo.cloud) | **0** | federation active-demand-apps **0** | Stripe active subs 0、murakumo-paid-charges 0 |
| club-shinshi (shinshi.club) | **0** | 訪問 645 → chatter 1 → scene 0 → 課金 **0** | creator GMV 0（D1 実測）、ExoClick も直近窓 USD 0 |
| cloud-itonami (itonami.cloud) | **0** | 外部 tenant 5（全 free path）→ externalPaid **0** | live Payment Link あり・`readyForLiveCheckout: true` |

ポートフォリオ全体の Stripe は `charges-total 90` / `last-charge-epoch 1604224274`
= **最後の課金が 2020-11-01**。以後の実入金なし。account-wide active subs 2 は
この 4 product のいずれにも紐づかない（apex / kotobase / murakumo いずれも 0）。

## Portfolio gate — cloud-itonami（2026-08-08 実測）

| Gate | Required evidence | Measured evidence (2026-08-08) | Status | Owner / next action |
|---|---|---|---|---|
| 契約主体 | 法人名、住所、連絡先、商品ごとのoperatorが公開文書で一致 | Terms §15 が live: AWAI Network, L.L.C.（Delaware file 10704996）が operator、Gftd Japan K.K. が infra supplier、contact `hello@gftd.co.jp`。`GET /legal/terms/` → 200 / 15,322 B, effective 2026-07-24 | **green** | — |
| Merchant / collection | 契約主体自身のPSP口座、または有効な収納代行契約 | ADR-2607242600 accepted (2026-07-24)。Terms §6.3 が Gftd Japan を**開示された限定 collection agent**、AWAI を supplier として公開明記。署名 counterpart は設計上 git 外（同 ADR §6） | **green**（governance level） | — |
| 日本での法人・税務 | 外国会社登記、PE、源泉・消費税の専門家判断 | Terms §6.4 は税抜 + reverse-charge の**立場**を公開しているが、`Customer is responsible for confirming its own filing position` と自認。ADR-2607242600 §7 が「foreign-company registration と counsel review は別 gate」と明示。**written advice は存在しない** | **red** | 日本の弁護士・税理士: written advice。AWAI の外国会社登記 |
| Terms / Privacy | placeholderなし、公開版、発効日、operator承認証跡 | owner 承認済み（run 0018、2026-07-24）。`/legal/terms/` 200 / 15,322 B、`/legal/privacy/` 200 / 16,902 B、いずれも effective 2026-07-24、DRAFT 表記なし。**これは owner 承認であって counsel review ではない**（この gate が要求するのは operator 承認証跡なので green は正しい。counsel review は下の Unblock proof が別途追跡しており未充足） | **green** | — |
| DPA / subprocessors | 実際のdata flowと一致するDPA・一覧 | `/legal/dpa/` 200 / 10,047 B。Terms §7.1 が incorporation by reference。Privacy §5 が subprocessor を実名列挙（Cloudflare, Inc. / net-kotobase ほか）、§6 に international transfer | **green** | — |
| 価格・税・返金 | 通貨、税込/税別、周期、解約時点、返金条件 | 通貨・周期 green（§6.1 JPY・月次後払い）。税 green（§6.4 税抜 + reverse charge）。**解約時点・返金条件は ADR-2608080300 で決定済み**（2026-08-08、run 0023）— ただし Terms 本文への適用は未実施（`refund`/`cancel` は依然 Terms に無い）。価格は**単価 2 件のみ未確定**、他 4 項目は同 ADR 決定 4 で確定 | **yellow** | ①owner: Stripe Price の `unit_amount` 2 件を読んで転記（agent は live key を扱えない）②repo access を持つセッション: ADR-2608080300 の 1・2 を `legal/terms.md` へ追補、pricing ページ生成 |
| Fulfillment | 署名検証済みpaymentからentitlementまでE2E証拠 | `GET /api/billing/status`: `mode:"live"`, `stripeConfigured:true`, `webhookReady:true`, `readyForLiveCheckout:true`, `readyForEntitlement:true`, `missing:[]`。**構成要素としてのレールは欠落ゼロ**。ただし ①checkout が一度も走っていないため signed-webhook → entitlement は本番未実証 ②test-mode 証跡は `sk_test_…` 不在で取得不能（run 0016） ③**24h で 5xx 28%**。**構成が揃っていることは、有料導線に載せてよい品質であることを意味しない** | **yellow** | owner: Stripe dashboard で `sk_test_…` を発行 → test-mode E2E。並行して 5xx を潰す |
| Support / complaints | support窓口、応答方針、苦情・返金処理者 | **ADR-2608080300 決定 3 で確定**（2026-08-08）: 窓口 `hello@gftd.co.jp`、一次受付と返金**実行**は Gftd Japan K.K.、返金**承認**と苦情の最終責任は AWAI Network, L.L.C.、受領応答は 1 営業日目標（SLA ではない）、10 営業日で operator へエスカレーション。公開文言への反映は未実施 | **yellow** | repo access を持つセッション: support ページへ反映 |
| Product safety | 年齢、content、privacy等のproduct固有gate | cloud-itonami は adult / age-assurance 対象外 | n/a | — |

## 価格の確定（2026-08-08）

**net-kotobase Standard = ¥2,980/月**（owner 判断、`net-kotobase-graphdb-baas-execution-plan-2026-07-24.md`
の Developer Standard を正とする）。

2026-08-08 の監査で、この repo 内の 2 文書が同一日付で異なる Standard 価格を
記載していたことを検出した。

| 文書 | 記載 |
|---|---|
| `SCORECARD.md`（2026-07-24） | ¥980/mo、pricing ADR は proposed（未 ratify） |
| `net-kotobase-graphdb-baas-execution-plan-2026-07-24.md` | Developer Standard **¥2,980/mo** |

同時に、本文書の「Smallest path」項目 1–2 は取り消し線で完了扱いになっていた。
**3 つは同時に真になり得ず、`Unblock proof` の「ratified price」は実際には
満たされていなかった。** owner 判断で ¥2,980/月 を正として解消した。
¥980 は superseded。base target（39 顧客・月次 GP 約 ¥314,600）は ¥2,980 前提で
組まれているため、こちらが整合する。

**未了**: この価格に対応する Stripe Product/Price の照合、および pricing ADR の
ratify 記録は net-kotobase repo 側にあり未検証（下記「Session constraint」）。

## Product decisions

| Product | Contracting boundary | Product-specific boundary | Decision |
|---|---|---|---|
| cloud-itonami | AWAI/Gftd Japan の収納代行は ADR + 公開 Terms で確定。**登記・税務のみ赤** | terms/privacy/DPA は承認済み・公開済み。ただし 24h で 5xx 28%（有料導線に載せる品質ではない） | **conditional**（red 1 + yellow 3 + 品質。前版 no-go から前進） |
| net-kotobase | Gftd Japan K.K.（Corporate Number 1011101086505、国内法人）が operator。**外国会社登記の論点は構造的に無い** | **課金中の live 価格は Secure Managed ¥19,800/月**（run 0033、Aura Professional 2 GB 帯）。owner 確定の Developer ¥2,980/月は **未公開のまま**。run 0031/0032: `/legal/terms/` `/legal/privacy/` `/legal/dpa` は live 200 だが **DRAFT / UNAPPROVED TEMPLATE**。`noindex`、sitemap 非掲載、`[CONFIRM:` マーカー残。Stripe Price ID は 0033 でも未変更。commercial go は動かない | **no-go** |
| cloud-murakumo | AWAI Network, L.L.C.（Delaware）が operator と公開文書で一致。**cloud-itonami と同じ登記・税務の red を継承** | Terms/Privacy は live だが **DRAFT**（`[CONFIRM:` terms 5 / privacy 11、privacy は準拠法自体が未確定）。特商法表記は live だが **3 項目（所在地・電話・代表者）が未確定**で、`purchasable?` が物理 SKU の checkout をコードで止めている。同意の版管理と返金条件は実装済み（この product の強い側）。価格は `/#studio` にあるが hash fragment の先で server 側に無い | **no-go**（2026-08-17 初評価、上記 gate 表） |
| club-shinshi | operator・決済条件の確定が必要 | adult specialist review、age assurance、refund/tax/payoutが赤 | **no-go**（SPA のため legal surface は HTTP で再実測不能 — run 0022） |
| net-babiniku | PSP/crypto railの契約証跡なし | monetization proposal自体がhard hold | **no-go**（未再実測） |

## Smallest path to one green product

**前版の推奨（net-kotobase Standard）を撤回する。** 当時 net-kotobase を選んだ理由は
「AWAI の法人間・外国会社論点を持たない」ことで、**それ自体は今も正しい**（operator は
国内法人 Gftd Japan K.K.）。しかし 2026-08-08 に実測したところ（run 0022）、net-kotobase は
**Terms が依然 DRAFT で、privacy と DPA は製品ドメインに無く、価格も未公開**だった。
run 0031 (2026-08-14) で drafts は `/legal/*` に載ったが、バナーは DRAFT / UNAPPROVED
のままなので counsel 前の公開版ではない。cloud-itonami の red 1 件を回避する代わりに、
counsel 未了の DRAFT を販売面に出すことになる。**両方とも counsel は要る。owner 承認済みの
公開版があるのは cloud-itonami だけ。**

現行の最小経路は **cloud-itonami**。残りは 2 red/yellow + 1 需要である。

1. **owner**: 日本の弁護士・税理士から written advice を取り、AWAI の外国会社登記の
   要否を確定する（唯一の red）。
2. **owner**: Stripe dashboard で `sk_test_…` を発行する。run 0016 が金庫を確認済みで、
   test secret は存在しない。**live key の流用は禁止**（同 run の決定）。発行後の
   test-mode E2E 証跡取得は機械的作業として agent が実行できる。
3. ~~**owner**: 返金・解約条件を決め、公開 pricing ページを立てる。~~ → **決定は完了**
   （ADR-2608080300、2026-08-08）。残るのは (a) owner が Stripe Price の `unit_amount`
   2 件を読んで転記する 1 手 — **agent は live secret key を扱えないので代行しない** —
   と (b) 決定済み文言の Terms / pricing / support ページへの適用（判断は残っていない
   機械的作業。`gftdcojp/cloud-itonami` への access が要る）。
4. ~~**owner**: support / 苦情の accountable owner を指名する。~~ → **決定は完了**
   （同 ADR 決定 3）。既存の決定（ADR-2607242600 の代理人境界）から導出したもので、
   新しい人の割当ては発生していない。
5. **需要**: 非 owner の実決済 1 件。これは 1–4 が全て green になっても**誰も代行できない**
   （下記）。

## この gate が閉じても残るもの

commercial gate は「売ってよいか」を決めるだけで、「売れたか」は決めない。
ADR-2607246100 の T1 exit は **非 owner による検証済み実決済 1 件**であり、
2026-08-08 実測で `externalTotal:5 / externalPaid:0 / activeSubscriptions:0`。
itonami.cloud 自身が `bottleneck: "run Stripe checkout via /isco-1212/"` と申告している。

**agent はこれを代行してはならない。** owner の payment method で checkout を通すと
externalPaid は 1 になるが、それは owner 決済であって T1 が要求する非 owner 決済ではない。
release 条件を満たさないまま満たしたように見える数値を作る行為であり、
ADR-2608062400 が防いでいる捏造そのものである。

## Portfolio gate — cloud-murakumo（2026-08-17 実測。この表に初めて載る）

前版までこの product には**行が無かった**。無かったのは「評価して問題なし」ではなく
**評価していない**という意味で、両者が同じ空欄として出ていた（ADR-2608136000 の形）。
ADR-2608170700 で実測して埋めた。**この節だけ as-of 2026-08-17。他節は 2026-08-08。**

**先に訂正**: 「`/pricing` が 404 なので公開価格が無い」は**誤読**だった。murakumo.cloud は
**hash route の SPA**（ADR-2608080100 の推奨形）で、価格は `/#studio`、ストアフロントは
`/#store`、signup は `/#signup` にある。curl の 404 は不在の証明にならない。
配信中の `js/main.js`（284,845 bytes）から実 route を読んで確認した:
`#landing` `#landing-try` `#landing-how` `#store` `#store/` `#studio` `#signup` `#console`。

**訂正（同日、この節を書いた直後）**: 初版はこの表で『特商法表記が見つからない』と
書いた。**誤り。** `/legal/tokushoho.md` は実在して live である。なぜ外したかを残す ——
配信バンドルを grep して `特定商取引` / `tokushoho` が 0 件だったこと、および
バンドルが名指ししていた `/legal/terms.md` と `/legal/privacy.md` の 2 本だけを
probe したことを根拠にした。**静的な `.md` 資産は SPA バンドルの中に無い**ので、
バンドル grep は最初からこの問いに答えられない。repo の `legal/` を `ls` すれば
1 コマンドで出た（`company.md` `privacy.md` `terms.md` `tokushoho.md`）。
**『2 箇所を探して無かった』と正直に書いても、その 2 箇所が両方とも答えを持たない
場所なら、出てくる結論は same-faced な false negative になる。**

**この読み方は club-shinshi にも効く。** 上の表が run 0022 以来
「SPA のため legal surface は HTTP で再実測不能」としている項目は、
配信バンドルの文字列を読めば測れる（規約文言・operator 名・返金条件・同意ゲートは
すべてバンドルに入っている）。**測れないのではなく、測り方が HTTP status だけだった。**

| Gate | Required evidence | Measured evidence (2026-08-17) | Status |
|---|---|---|---|
| 契約主体 | 法人名・住所・連絡先・operator が公開文書で一致 | バンドルが `murakumo.cloud · operated by AWAI Network, L.L.C.` を表示。`/legal/terms.md` が Service operator: AWAI Network, L.L.C.（Delaware LLC）、準拠法 Delaware と明記 | **green** |
| Terms / Privacy | placeholder 無し・公開版・発効日・operator 承認証跡 | `/legal/terms.md` 200 / 23,446 B（Last updated 2026-08-04）、`/legal/privacy.md` 200 / 9,837 B（同 2026-07-23）。**両方とも冒頭が DRAFT** で、`[CONFIRM:` が terms 5 件 / privacy 11 件。privacy は準拠法自体が `[COUNSEL CONFIRMATION REQUIRED]`。store 側も『**DRAFT:** … still draft pending review by qualified counsel. They are not final legal documents.』と自ら表示 | **red** |
| 日本での法人・税務 | 外国会社登記・PE・源泉/消費税の専門家判断 | **cloud-itonami と同一の red を継承する** —— operator が同じ AWAI Network, L.L.C. であり、written advice は存在しない | **red** |
| 返金・解約条件 | 公開された条件 | バンドルが明示: クレジット購入は**原則返金不可**（強行法規が要求する場合を除く）、ハードウェアノード購入は §6A で fulfillment が手動 | **yellow**（条件は在るが DRAFT の Terms 内） |
| 購入前の同意取得 | チェックアウト前に規約同意、版管理 | 実装済み。`accept the Terms … before checkout` + **版が変わったら再同意**（`2026-08-04` を版として保持）。この product で一番よくできている部分 | **green** |
| PSP / collection | 契約主体の PSP 口座または有効な収納代行 | Stripe Checkout（カードのみ、BNPL/分割なし）。`Pay with crypto — coming soon`。cloud-itonami と同じ AWAI/Gftd Japan の収納代行境界に載る | **green**（governance level） |
| 日本向け表示義務 | JP 消費者に売るなら特定商取引法に基づく表記 | **存在し、live**（`/legal/tokushoho.md` 200 / 5,735 B、Last updated 2026-08-04）。ただし自ら『未完成 — このページは公開できる状態にありません』と宣言し、**所在地・電話番号・代表者／販売責任者の 3 項目が `[未確定 — 公開前に必須]`**。**しかもこれはコードで強制されている** —— `cloud_murakumo/storefront.cljc` の `purchasable?` が物理出荷 SKU に対して `commercial-disclosure-complete?` を AND しており、`:seller-address` `:seller-phone` `:seller-representative` が `nil` である限り **murakumo Node 24 は checkout に出ない**。同ファイルは『prose の申し送りは 2026-08-02 の「課金だけされて何も届かない」出荷を止められなかった』からコードで止めた、と理由まで書いている | **red**（3 項目は owner 固有の事実。ただし**未表示のまま売られてはいない**） |
| 需要 | 外部の実需 | federation `active-demand-apps: 0`、Stripe active subs 0 / paid charges 0。24h トラフィックはほぼ内部 API（fleet promotions 17,156 / scanner 17,103） | **red** |

**判定: no-go。** red 4（Terms/Privacy の DRAFT・日本の税務/登記・特商法の 3 項目・需要）。

**赤の性質が product ごとに違うことを潰さない。** murakumo の特商法 red は
『表示が無いまま売っている』ではなく『**表示が揃うまで売れないようコードで止めてある**』で、
残っているのは owner しか知らない 3 つの事実（所在地・電話・代表者）である。
同じ red でも、cloud-itonami の税務 red（専門家の判断が要る）とは必要な仕事が違う。

**ただし kotobase より近い。** 同意取得の版管理と返金条件は既に実装・公開されており、
kotobase 側に無いものが murakumo には在る。逆に kotobase に在って murakumo に無いのは
**owner が確定した価格**である。

**この product 固有の distribution 上の問題**（gate ではないが、需要 red の原因側）:
価格・ストア・signup が**すべて hash fragment の先**にあるので、
サーバ側に価格ページが存在せず、**検索エンジンにもリンク共有にも載らない**。
SPA 規約（ADR-2608080100）は fragment を正しい既定としているが、同時に
「SSR/OG が必要な公開ページは例外」とも書いている。**価格ページはその例外に当たる。**

## 並行トラック — net-kotobase（価格は確定、公開が未了）

cloud-itonami が最小経路だが、net-kotobase は**価格だけ先に確定した**ので手順を残す。
下記「Session constraint」はこのトラックの項目 2・3・4・6 を指す。

1. ~~ownerが月額を決定する。~~ **完了 2026-08-08: ¥2,980/月。**
   税込/税別、billing cycle、解約時点、返金方針は**未決定**。
2. proposed pricing ADRをratifyし、実装・landing・Stripe Priceが ¥2,980 で
   一致することを検証する。**未了**（ADR は net-kotobase repo 側）。
3. termsのminimum age、tax、liability cap、変更通知を確定する。
4. privacyの収集data、subprocessor、transfer、retention、cookies、未成年対応を
   実装事実から確定する。
5. counselがterms/privacyを承認し、ownerが公開版のcommitを承認する。
6. Stripe test modeで checkout → signed webhook → tenant entitlement →
   cancellation/downgrade を証跡化する。
7. liveへ切り替えるowner承認と、support/refund担当者を記録する。

項目 3・4・6 は net-kotobase repo の実装事実を要する。項目 5・7 は owner / counsel
固有で、agent が代行してはならない。

## Session constraint（2026-08-08）

**`network-awai/net-kotobase` は com-junkawasaki スコープの session に attach できない。**
`add_repo` が構造的に拒否する。

```text
cross-tier adds are not supported in v1: requested "network-awai/net-kotobase"
but session already has repos from owner(s) [com-junkawasaki]
```

したがって項目 2・3・4・6 は本 repo からは実行不能。実行するには
**`network-awai/net-kotobase` を initial source にした新規 session** が要る。
その session が会話履歴なしで再開できるよう、必要な入力を下記に固定する。

- 確定価格: **Standard ¥2,980/月**（¥980 は superseded）
- 検証対象: Stripe Product/Price が ¥2,980 と一致するか、landing 表示と一致するか
- 未決定のまま渡す項目: 税込/税別、billing cycle、解約時点、返金条件
- E2E 要件: checkout → signed webhook → tenant entitlement → cancellation/downgrade、
  test mode、event ID は秘匿化して記録
- 禁止事項: live 切替、外部顧客への勧誘、owner 承認の代行

## Unblock proof

green判定は次の参照が一つのrunに揃った時だけ行う。

- 最終版Terms / Privacy / DPAのcommit — **充足**（run 0018/0019、live 200 で確認）
- counsel reviewの日時・対象version（助言内容そのものは機密でよい）— **未充足**（run 0032: `legal/counsel-review-packet.md` を 2026-08-14 に更新し live DRAFT に owner 決定と実装事実を書いた。**承認の return evidence は無い。agent は counsel を代行しない**）
- ratified price、Stripe Product/Priceとの照合結果 — **未充足**（公開 pricing ページ無し）
- test-mode E2E event IDsを秘匿化した検証記録 — **未充足**（`sk_test_…` 不在）
- ownerのlive販売承認、support/refund担当 — **未充足**
