# Current Revenue Agent Loop Scorecard

**Observed at:** 2026-08-08（gate 再実測 run 0021。score 自体は 2026-07-24 の prior）
**Revenue state:** verified external revenue = 0（2026-08-08 実測で再確認）
**Mode:** Cash-first 60% / Profit-first 40%
**Important:** 数値は初期prior。外部conversion実績ではない。
**Capital state:** T1 released ceiling ¥300,000; committed ¥0; spent ¥0.
T2–T4 are held by ADR-2607246100.

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

5820のlive商品は¥80k/月なのに対し、¥20k trialは未承認かつ対応checkoutが
存在しない。したがって5820/854の従来green判定を撤回した。

**cloud-itonami の gate 根拠を 2026-08-08 に差し替えた（run 0021）。** 上表の
cloud-itonami 行が引いていた「Terms/PrivacyはDRAFT、operator・DPAが未確認」は
**もう事実ではない** — terms/privacy/DPA は owner 承認済みで live、operator と
収納代行の境界は ADR-2607242600 と Terms §6.3 で公開確定している。これらの行が
`blocked` である根拠は、いまや**登記・税務の counsel written advice が無いこと
1 点のみ**であり、それは有償勧誘に対する実在の法的制約なので blocked は維持する。
`no contact path`（75 点の最上位行）は gate ではなく単に「5 件の外部 tenant に
誰も声をかけていない」という状態で、これは counsel gate が閉じれば即座に実行可能に
なる。次に score を更新するときは、この行を再採点すること。

## Current selection

- **Selected (this session):** GLEIF joined-tier listing — correct Base custody
  note after 2026-08-14 RPC remeasure. Paid fulfill is blocked (funds floor +
  no solicitation). Catalog stays off.
- **Run:** `runs/0035-gleif-base-safe-custody-remeasure.edn`
- **Score:** 57/100 after custody remeasure (was 53). Code is not a payment.
- **Cash at risk:** ¥0
- **Queued:** net-kotobase named-accounts discovery. Beekle form
  (https://beekle.jp/contact) still needs human Chrome (Turnstile). Run 0025
  PR #2170 is on main. Do not expand to 100 contacts on 0 replies.

## Session note — 2026-08-14 (GLEIF)

run 0034 shipped the listing (402/404 live). Named next was a non-owner paid
read then nexus catalog. That action is **blocked**: the agent must not send
USDC, and counsel written-advice still forbids solicitation. Self-purchase
does not count. Selected instead: remeasure family payTo on Base. A Safe
proxy is deployed (threshold 1). Listing copy that said "no contract on Base"
is superseded. Facilitator junk settle → 402, not 500. Catalog still off.

## Known evidence

- club-shinshi: 2026-08-08実測は訪問645、chatter 1、scene 0、課金0、creator GMV 0。
  ExoClickは成熟度factsで「gftd唯一の:live収益」と現在形で書かれているが、
  ledgerの2026-07-09実測は7d再構成USD 0・imp 0。first $は史実だが
  **現在は産出していない**（`maturity-facts.edn`を2026-08-08に訂正済み）。
- cloud-itonami: **2026-08-08実測** — 外部tenant 5、externalPaid 0、
  activeSubscriptions 0、agentRuns7d 306（07-30の2,173→07-31の984から継続減、
  原因は未説明）。`/api/billing/status`は`mode:"live"` / `missing:[]` /
  `readyForLiveCheckout:true` / `readyForEntitlement:true`で**レールは完成**。
  legal site（terms/privacy/dpa）はlive 200。
  ただし**24hで5xx 28%**が出ており、レールが完成していることは
  有料導線に載せてよい品質であることを意味しない。
- net-babiniku: Base USDCのon-chain verified tip経路はlive。subscription/PPVは未提供、
  wallet必須、既定tip 5 USDC。
- 6399/6310/7810: live managed Payment Link。6399/6310はproduct score 5、
  7810はproduct score 4。
- net-kotobase: Gftd Japanがoperator。**Standardは2026-08-08にowner確定で¥2,980/mo**
  （旧記載¥980はsuperseded。詳細と検出経緯はCOMMERCIAL-GO-NO-GO.md「価格の確定」）。
  原価モデル上gross margin約88%。run 0031/0032 (2026-08-14): `/legal/terms/` `/legal/privacy/`
  `/legal/dpa` は live 200 だが **DRAFT / UNAPPROVED**。counsel packet は更新済み、**承認 return は無い**。
  残 `[CONFIRM:` は税（live ¥19,800）、CCPA、Art. 28、SCC、under-16。
  run 0033 (2026-08-14): live `/pricing` は AuraDB Free / Professional / VDC に
  対応し、課金 SKU は Secure Managed **¥19,800/mo**（Professional 2 GB 帯）。
  Business Critical は未提供。¥2,980 は live 価格として未掲載。Stripe Price ID は
  変更していない。Worker境界のmock E2Eはcheckout metadata、署名、
  entitlement、解約、5分replay制限、out-of-order eventを検証済み。ただし
  real Stripe test-modeおよび外部需要の証拠ではない。

次回は推定値より、chat activation、checkout start、wallet initiation、
qualified reply、実入金を優先して更新する。

## Session note — 2026-08-14 (net-kotobase)

run 0031 served the in-repo DRAFT legal pages from `net-kotobase/control-plane`
(not `network-awai/net-kotobase`). run 0032 filled owner decisions and measured
facts into those drafts and refreshed `legal/counsel-review-packet.md`.
**Counsel approval was not obtained.** run 0033 published the Aura-comparable
ladder on `/pricing` (Worker `62977810-2eb5-4dd1-85be-c5d486c91c48`); live
charge remains ¥19,800. **Commercial go stays no-go.** Score stays 58/100.

## Session note — 2026-08-08

`network-awai/net-kotobase` は com-junkawasaki スコープの session に attach
できない（`add_repo` が cross-tier add を構造的に拒否）。2026-08-14 時点では
apex Worker の正本は `net-kotobase/control-plane` で、draft legal の掲載はそこから
実行できた。Stripe test-mode E2E と counsel 承認は未了。
引き継ぎ入力は COMMERCIAL-GO-NO-GO.md「Session constraint」に固定した。
