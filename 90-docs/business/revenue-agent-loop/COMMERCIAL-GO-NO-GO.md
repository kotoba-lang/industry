# Commercial Go / No-Go

**Observed:** 2026-07-24
**Purpose:** 技術的に決済可能であることと、正当に販売可能であることを分離する。

`green` の証拠が揃うまで、agent は外部顧客への有料勧誘、live checkoutの公開、
実決済の受領を行わない。draft、推測、実装済みコードは承認の代用にならない。

## Portfolio gate

| Gate | Required evidence | Current evidence | Status | Owner / next action |
|---|---|---|---|---|
| 契約主体 | 法人名、住所、連絡先、商品ごとのoperatorが公開文書で一致 | cloud-itonamiはAWAI Network。住所と専用連絡先が未確定 | yellow | owner: principal addressと正式contactを確定 |
| Merchant / collection | 契約主体自身のPSP口座、または有効な収納代行契約 | StripeはGftd Japan、AWAIとの収納代行契約は署名なしdraft | **red** | 両社authorized signatory: PSPを移すか契約へ署名 |
| 日本での法人・税務 | 外国会社登記、PE、源泉・消費税の専門家判断 | 要検討と記載されているが、専門家結論なし | **red** | 日本の弁護士・税理士: written advice |
| Terms / Privacy | placeholderなし、公開版、発効日、operator承認証跡 | cloud-itonamiはDRAFT、税・APPI等が未確認 | **red** | counsel + owner: final review and approval |
| DPA / subprocessors | 実際のdata flowと一致するDPA・一覧 | termsは「executed後に提供」。公開済み証拠なし | **red** | product owner: data map、counsel: DPA確定 |
| 価格・税・返金 | 通貨、税込/税別、周期、解約時点、返金条件 | 商品別に未確定事項あり | **red** | owner: commercial policyを決定 |
| Fulfillment | 署名検証済みpaymentからentitlementまでE2E証拠 | net-kotobase billing 4 tests/16 assertions、site 20/110、bundle route 6 tests pass。本物のStripe/KV E2Eは未検証 | yellow | product owner: nonowner本番前のtest-mode E2E証跡 |
| Support / complaints | support窓口、応答方針、苦情・返金処理者 | 一部で共通mailのみ | yellow | operator: accountable ownerを指名 |
| Product safety | 年齢、content、privacy等のproduct固有gate | club-shinshi / net-babinikuは未解決 | **red** | product owner + specialist counsel |

## Product decisions

| Product | Contracting boundary | Product-specific boundary | Decision |
|---|---|---|---|
| cloud-itonami | AWAI / Gftd Japan collection関係、登記・税務が赤 | terms/privacy/DPAがdraft | **no-go** |
| club-shinshi | operator・決済条件の確定が必要 | adult specialist review、age assurance、refund/tax/payoutが赤 | **no-go** |
| net-babiniku | PSP/crypto railの契約証跡なし | monetization proposal自体がhard hold | **no-go** |
| net-kotobase | Gftd Japanがoperatorで主体は明示済み | Standard価格・解約・返金等はowner決定済み。privacy未確定、counsel/E2E未完了 | **no-go** |

## Smallest path to one green product

`net-kotobase Standard` を最小候補にする。AWAIの法人間・外国会社論点を持たず、
Gftd Japanをoperatorとして既に特定しているためである。ただし次の全項目が必要。

1. ~~ownerが月額、税込/税別、billing cycle、解約時点、返金方針を決定する。~~
2. ~~proposed pricing ADRをratifyする。~~ 実装・landing・Stripe Priceの一致は未検証。
3. termsのminimum age、tax、liability cap、変更通知を確定する。
4. privacyの収集data、subprocessor、transfer、retention、cookies、未成年対応を
   実装事実から確定する。
5. counselがterms/privacyを承認し、ownerが公開版のcommitを承認する。
6. Stripe test modeで checkout → signed webhook → tenant entitlement →
   cancellation/downgrade を証跡化する。
7. liveへ切り替えるowner承認と、support/refund担当者を記録する。

## Unblock proof

green判定は次の参照が一つのrunに揃った時だけ行う。

- 最終版Terms / Privacy / DPAのcommit
- counsel reviewの日時・対象version（助言内容そのものは機密でよい）
- ratified price、Stripe Product/Priceとの照合結果
- test-mode E2E event IDsを秘匿化した検証記録
- ownerのlive販売承認、support/refund担当
