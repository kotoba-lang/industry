# Commercial Go / No-Go

**Observed:** 2026-08-08（前回 2026-07-24 から再実測。run 0021）
**Purpose:** 技術的に決済可能であることと、正当に販売可能であることを分離する。

`green` の証拠が揃うまで、agent は外部顧客への有料勧誘、live checkoutの公開、
実決済の受領を行わない。draft、推測、実装済みコードは承認の代用にならない。

**この表は現在値である。** 前版（2026-07-24）は cloud-itonami の 6 gate を red と
していたが、うち 4 つは run 0018/0019 と ADR-2607242600 で同日〜翌日に閉じており、
15 日間 red のまま放置されていた。停まっていたのは商流ではなく**この表**である。
その結果 SCORECARD の全 action が存在しない red を根拠に `blocked` を維持していた。

## Portfolio gate — cloud-itonami（2026-08-08 実測）

| Gate | Required evidence | Measured evidence (2026-08-08) | Status | Owner / next action |
|---|---|---|---|---|
| 契約主体 | 法人名、住所、連絡先、商品ごとのoperatorが公開文書で一致 | Terms §15 が live: AWAI Network, L.L.C.（Delaware file 10704996）が operator、Gftd Japan K.K. が infra supplier、contact `hello@gftd.co.jp`。`GET /legal/terms/` → 200 / 15,322 B, effective 2026-07-24 | **green** | — |
| Merchant / collection | 契約主体自身のPSP口座、または有効な収納代行契約 | ADR-2607242600 accepted (2026-07-24)。Terms §6.3 が Gftd Japan を**開示された限定 collection agent**、AWAI を supplier として公開明記。署名 counterpart は設計上 git 外（同 ADR §6） | **green**（governance level） | — |
| 日本での法人・税務 | 外国会社登記、PE、源泉・消費税の専門家判断 | Terms §6.4 は税抜 + reverse-charge の**立場**を公開しているが、`Customer is responsible for confirming its own filing position` と自認。ADR-2607242600 §7 が「foreign-company registration と counsel review は別 gate」と明示。**written advice は存在しない** | **red** | 日本の弁護士・税理士: written advice。AWAI の外国会社登記 |
| Terms / Privacy | placeholderなし、公開版、発効日、operator承認証跡 | owner 承認済み（run 0018、2026-07-24）。`/legal/terms/` 200 / 15,322 B、`/legal/privacy/` 200 / 16,902 B、いずれも effective 2026-07-24、DRAFT 表記なし。**これは owner 承認であって counsel review ではない**（この gate が要求するのは operator 承認証跡なので green は正しい。counsel review は下の Unblock proof が別途追跡しており未充足） | **green** | — |
| DPA / subprocessors | 実際のdata flowと一致するDPA・一覧 | `/legal/dpa/` 200 / 10,047 B。Terms §7.1 が incorporation by reference。Privacy §5 が subprocessor を実名列挙（Cloudflare, Inc. / net-kotobase ほか）、§6 に international transfer | **green** | — |
| 価格・税・返金 | 通貨、税込/税別、周期、解約時点、返金条件 | 通貨・周期 green（§6.1 JPY・月次後払い）。税 green（§6.4 税抜 + reverse charge）。**返金・解約条項が Terms に存在しない**（`refund` / `cancel` が全文に無い）。§6.1 が参照する "in-product pricing page" も**公開されていない**（`/pricing` は 35,552 B の cockpit fallback = 実ページ無し） | **yellow** | owner: 返金・解約条件を決定 → Terms へ追補 / 公開 pricing ページを立てる |
| Fulfillment | 署名検証済みpaymentからentitlementまでE2E証拠 | `GET /api/billing/status`: `mode:"live"`, `stripeConfigured:true`, `webhookReady:true`, `readyForLiveCheckout:true`, `readyForEntitlement:true`, `missing:[]`。**レールは 100% 完成**。ただし checkout が一度も走っていないため signed-webhook → entitlement は本番未実証。test-mode 証跡は `sk_test_…` 不在で取得不能（run 0016） | **yellow** | owner: Stripe dashboard で `sk_test_…` を発行 → test-mode E2E |
| Support / complaints | support窓口、応答方針、苦情・返金処理者 | `hello@gftd.co.jp` のみ。accountable owner 未指名 | yellow | operator: accountable ownerを指名 |
| Product safety | 年齢、content、privacy等のproduct固有gate | cloud-itonami は adult / age-assurance 対象外 | n/a | — |

## Product decisions

| Product | Contracting boundary | Product-specific boundary | Decision |
|---|---|---|---|
| cloud-itonami | AWAI/Gftd Japan の収納代行は ADR + 公開 Terms で確定。**登記・税務のみ赤** | terms/privacy/DPA は承認済み・公開済み | **conditional**（red 1 + yellow 3。前版 no-go から前進） |
| net-kotobase | Gftd Japan K.K.（Corporate Number 1011101086505、国内法人）が operator。**外国会社登記の論点は構造的に無い** | **2026-08-08 実測（run 0022）**: `terms.md` は**依然 DRAFT**（1 行目に「counsel review required before publication」）かつ製品ドメインに無く GitHub blob リンクのみ（`kotobase.net/legal/terms/` は実 404）。**privacy.md / dpa.md はどちらも 404 = 存在しない**。`/pricing` は live だが**価格が 1 つも書かれていない**（¥表記ゼロ。¥980/mo は未公開の提案値）。`[CONFIRM: …]` が 5 件未解決（最低年齢・価格・請求周期・税・**返金**）。`/api/billing/status` 無し | **no-go** |
| club-shinshi | operator・決済条件の確定が必要 | adult specialist review、age assurance、refund/tax/payoutが赤 | **no-go**（2026-07-24 値。SPA のため HTTP では再実測不能 — run 0022） |
| net-babiniku | PSP/crypto railの契約証跡なし | monetization proposal自体がhard hold | **no-go**（同上、未再実測） |

## Smallest path to one green product

**前版の推奨（net-kotobase Standard）を撤回する。** 当時 net-kotobase を選んだ理由は
「AWAI の法人間・外国会社論点を持たない」ことで、**それ自体は今も正しい**（operator は
国内法人 Gftd Japan K.K.）。しかし 2026-08-08 に実測したところ（run 0022）、net-kotobase は
**Terms が依然 DRAFT で、privacy と DPA は存在せず、価格も未公開**だった。cloud-itonami の
red 1 件を回避する代わりに、「公開前に counsel review が要る」と自ら宣言している DRAFT と、
ゼロから書く privacy・DPA を引き受けることになる。**両方とも counsel は要る。文書が
完成・公開済みなのは cloud-itonami だけ。**

現行の最小経路は **cloud-itonami**。残りは 2 red/yellow + 1 需要である。

1. **owner**: 日本の弁護士・税理士から written advice を取り、AWAI の外国会社登記の
   要否を確定する（唯一の red）。
2. **owner**: Stripe dashboard で `sk_test_…` を発行する。run 0016 が金庫を確認済みで、
   test secret は存在しない。**live key の流用は禁止**（同 run の決定）。発行後の
   test-mode E2E 証跡取得は機械的作業として agent が実行できる。
3. **owner**: 返金・解約条件を決め、公開 pricing ページを立てる（yellow、小）。
4. **owner**: support / 苦情の accountable owner を指名する（yellow、小）。
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

## Unblock proof

green判定は次の参照が一つのrunに揃った時だけ行う。

- 最終版Terms / Privacy / DPAのcommit — **充足**（run 0018/0019、live 200 で確認）
- counsel reviewの日時・対象version（助言内容そのものは機密でよい）— **未充足**
- ratified price、Stripe Product/Priceとの照合結果 — **未充足**（公開 pricing ページ無し）
- test-mode E2E event IDsを秘匿化した検証記録 — **未充足**（`sk_test_…` 不在）
- ownerのlive販売承認、support/refund担当 — **未充足**
