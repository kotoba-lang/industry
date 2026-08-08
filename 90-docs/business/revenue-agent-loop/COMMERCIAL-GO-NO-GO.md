# Commercial Go / No-Go

**Observed:** 2026-08-08（前回 2026-07-24 から更新。数値は `90-docs/business/metrics/*.edn` の as-of 2026-08-08 実測）
**Purpose:** 技術的に決済可能であることと、正当に販売可能であることを分離する。

`green` の証拠が揃うまで、agent は外部顧客への有料勧誘、live checkoutの公開、
実決済の受領を行わない。draft、推測、実装済みコードは承認の代用にならない。

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

## Portfolio gate

| Gate | Required evidence | Current evidence | Status | Owner / next action |
|---|---|---|---|---|
| 契約主体 | 法人名、住所、連絡先、商品ごとのoperatorが公開文書で一致 | cloud-itonamiはAWAI Network。住所と専用連絡先が未確定 | yellow | owner: principal addressと正式contactを確定 |
| Merchant / collection | 契約主体自身のPSP口座、または有効な収納代行契約 | StripeはGftd Japan、AWAIとの収納代行契約は署名なしdraft | **red** | 両社authorized signatory: PSPを移すか契約へ署名 |
| 日本での法人・税務 | 外国会社登記、PE、源泉・消費税の専門家判断 | 要検討と記載されているが、専門家結論なし | **red** | 日本の弁護士・税理士: written advice |
| Terms / Privacy | placeholderなし、公開版、発効日、operator承認証跡 | cloud-itonamiはDRAFT、税・APPI等が未確認。net-kotobaseはprivacy未確定 | **red** | counsel + owner: final review and approval |
| DPA / subprocessors | 実際のdata flowと一致するDPA・一覧 | termsは「executed後に提供」。公開済み証拠なし | **red** | product owner: data map、counsel: DPA確定 |
| 価格・税・返金 | 通貨、税込/税別、周期、解約時点、返金条件 | **net-kotobase Standard は 2026-08-08 に owner が ¥2,980/月 で確定**（下記「価格の確定」）。税込/税別・解約時点・返金条件は未確定。他商品は未確定 | **red** | owner: 税区分・解約時点・返金条件を決定 |
| Fulfillment | 署名検証済みpaymentからentitlementまでE2E証拠 | net-kotobase billing 4 tests/16 assertions、site 20/110、bundle route 6 tests pass。本物のStripe/KV E2Eは未検証 | yellow | product owner: nonowner本番前のtest-mode E2E証跡 |
| Support / complaints | support窓口、応答方針、苦情・返金処理者 | 一部で共通mailのみ | yellow | operator: accountable ownerを指名 |
| Product safety | 年齢、content、privacy等のproduct固有gate | club-shinshi / net-babinikuは未解決 | **red** | product owner + specialist counsel |

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
| cloud-itonami | AWAI / Gftd Japan collection関係、登記・税務が赤 | terms/privacy/DPAがdraft | **no-go** |
| club-shinshi | operator・決済条件の確定が必要 | adult specialist review、age assurance、refund/tax/payoutが赤 | **no-go** |
| net-babiniku | PSP/crypto railの契約証跡なし | monetization proposal自体がhard hold | **no-go** |
| net-kotobase | Gftd Japanがoperatorで主体は明示済み | **価格は確定（¥2,980/月）**。privacy未確定、counsel/E2E未完了 | **no-go** |

## Smallest path to one green product

`net-kotobase Standard` を最小候補にする。AWAIの法人間・外国会社論点を持たず、
Gftd Japanをoperatorとして既に特定しているためである。ただし次の全項目が必要。

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

- 最終版Terms / Privacy / DPAのcommit
- counsel reviewの日時・対象version（助言内容そのものは機密でよい）
- ratified price、Stripe Product/Priceとの照合結果
- test-mode E2E event IDsを秘匿化した検証記録
- ownerのlive販売承認、support/refund担当
