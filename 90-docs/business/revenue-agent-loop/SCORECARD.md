# Current Revenue Agent Loop Scorecard

**Observed at:** 2026-07-24
**Revenue state:** verified external revenue = 0
**Mode:** Cash-first 60% / Profit-first 40%
**Important:** 数値は初期prior。外部conversion実績ではない。

## Ranked actions

| Rank | Product/action | P30 | Speed | Demand | Pay | GP12 | Repeat | Margin | Learn | Spill | Conf | Total | Gate |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| blocked | cloud-itonami 6399/6310: 既存外部free tenant 4件へpaid pilotのyes/noを得る | 2 | 1 | 4 | 4 | 5 | 5 | 4 | 5 | 5 | 5 | **75** | no contact path |
| blocked | club-shinshi: active companion/premium pathへ$0.50 x402 offerを置き非owner決済を観測 | 3 | 5 | 4 | 1 | 1 | 3 | 4 | 5 | 4 | 4 | **62** | **red** |
| blocked | net-babiniku: verified tipを1 USDC defaultで露出し非owner tipを観測 | 2 | 4 | 2 | 1 | 1 | 2 | 4 | 5 | 4 | 3 | **48** | **red** |
| blocked | cloud-itonami 7810: owner-operated placement agency 10社へpaid pilot提示 | 2 | 3 | 3 | 1 | 5 | 5 | 4 | 4 | 4 | 4 | **65** | **red** |
| blocked | cloud-itonami 5820: RevOps/CFO向け¥20k validation sprintを商用確定 | 1 | 2 | 1 | 4 | 5 | 5 | 4 | 5 | 5 | 2 | **61** | **red** |
| blocked | cloud-itonami 854: private training operatorへ¥20k pilot提示 | 1 | 2 | 1 | 4 | 3 | 4 | 4 | 4 | 4 | 2 | **51** | **red** |

`P30`等は0–5。TotalはREADMEの100点weightで算出。

## Hard-gate override

初期priorではclub-shinshiをCash-first 1位としたが、Run 0001 preflightで
現行terms/privacyがcreator billingをmeasurement-onlyとし、paid launch前に
専門法務、age assurance、refund/tax/payout条件を要求していることを確認した。
技術的なx402 resourceの存在は、このproduct-specific gateを上書きしない。
scoreにかかわらずblockedとし、greenになるまで実決済の勧誘・導線追加を行わない。

cloud-itonami共通Terms/PrivacyはDRAFTで、operator、税務、DPAに未確認事項が
残る。さらに5820のlive商品は¥80k/月なのに対し、¥20k trialは未承認かつ
対応checkoutが存在しない。したがって5820/854の従来green判定を撤回した。

## Current selection

- **Selected:** cloud-itonami shared commercial closure
- **Run:** `runs/0019-cloud-itonami-legal-site.md`
- **Timebox:** 14日または20人時
- **Score:** 5820 action 61/100、commercial hard gateは現在red
- **Operator:** AWAI Network, L.L.C.（owner confirmed）
- **Next:** provision authorized Stripe test secret → exact one-time ¥20k test checkout → E2E → counsel/tax release

## Known evidence

- club-shinshi: 2026-07-23のledgerは訪問1,145、creator GMV 0、登録数は不明。
- cloud-itonami: 外部free tenant 4、externalPaid 0、Stripe Payment Link live。
- net-babiniku: Base USDCのon-chain verified tip経路はlive。subscription/PPVは未提供、
  wallet必須、既定tip 5 USDC。
- 6399/6310/7810: live managed Payment Link。6399/6310はproduct score 5、
  7810はproduct score 4。
- net-kotobase: Gftd Japanがoperator。Standardは提案値¥980/mo、原価モデル上
  gross margin約88%。ただしTerms/PrivacyはDRAFTかつ`CONFIRM`が残り、
  pricing ADRもproposed。Worker境界のmock E2Eはcheckout metadata、署名、
  entitlement、解約、5分replay制限、out-of-order eventを検証済み。ただし
  real Stripe test-modeおよび外部需要の証拠ではない。

次回は推定値より、chat activation、checkout start、wallet initiation、
qualified reply、実入金を優先して更新する。
