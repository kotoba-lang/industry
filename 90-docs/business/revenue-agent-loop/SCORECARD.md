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

- **Selected:** net-kotobase — AI agent / GraphRAG durable-memory セグメントの
  named accounts。有料勧誘はしない。30分の課題ヒアリング + 14日無償 alpha のみ
- **Run:** `runs/0025-net-kotobase-named-accounts-discovery.edn`
- **Timebox:** 14日または20人時
- **Cash at risk:** ¥0。広告費は¥0
- **Offer hypothesis:** DESIGN-PARTNER-OFFER の first message（販売ではない）
- **T1 evidence gate:** 100 named accounts が目標。本 run は検証済み公開経路 10 件
- **Score:** 56/100（discovery のみ。paid pitch は red のまま）
- **Operator:** Gftd Japan K.K.
- **Next:** Beekle 公式フォーム（https://beekle.jp/contact、種別=開発パートナー・協業）を
  人間の Chrome で 1 通送信。Turnstile が自動化 Chrome では描画されず未送信。
  返信 0 のまま 100 件に拡げない。Laboro/Enison は Beekle 送信成功後に限る。
  2026-08-14 session close: PR #2170 で run 0025 一式は main 着地済み。
  Automations の next-form reminder はエディタに載せた。Gmail 未接続。

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
  原価モデル上gross margin約88%。run 0031 (2026-08-14): `/legal/terms/` `/legal/privacy/`
  `/legal/dpa` は live 200 だが **DRAFT / UNAPPROVED**（counsel 前、commercial no-go のまま）。
  `[CONFIRM:` 残。pricing ADRのratifyとStripe Product/Price照合は未了。Worker境界のmock E2Eはcheckout metadata、署名、
  entitlement、解約、5分replay制限、out-of-order eventを検証済み。ただし
  real Stripe test-modeおよび外部需要の証拠ではない。

次回は推定値より、chat activation、checkout start、wallet initiation、
qualified reply、実入金を優先して更新する。

## Session note — 2026-08-14

run 0031 served the in-repo DRAFT legal pages from `net-kotobase/control-plane`
(not `network-awai/net-kotobase`). Live 200 does not mean privacy is counsel-final
or that Stripe test-mode E2E is done. Commercial go stays no-go.

## Session note — 2026-08-08

`network-awai/net-kotobase` は com-junkawasaki スコープの session に attach
できない（`add_repo` が cross-tier add を構造的に拒否）。2026-08-14 時点では
apex Worker の正本は `net-kotobase/control-plane` で、draft legal の掲載はそこから
実行できた。Stripe test-mode E2E と counsel 承認は未了。
引き継ぎ入力は COMMERCIAL-GO-NO-GO.md「Session constraint」に固定した。
