# ADR-2607023000: 内需フライホイール — 消費 product を platform の org tenant にする

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607021500 のフライホイール設計「全 state は kotobase、全推論は murakumo …
内需が各レイヤーの floor 収益」を**実体化**する。オーナー指示（2026-07-02）:
apex / manimani / itonami / club-shinshi が net-kotobase・cloud-murakumo を使うので、
**org tenant としてアカウント登録し、実際に使う（実 Stripe subscription で内部課金）**。

これは sales/marketing funnel（ADR-2607022600）の「signup 0」問題を構造的に解く:
**内部 product が最初の実 paying tenant** になり、funnel/gate に実データ（実 revenue）が
乗る。捏造ゼロを保ったまま dogfooding で cold-start を回避する。

## Decision — 消費 ↔ platform マトリクス（正）

| 消費 product | platform | 用途 | kotobase plan | murakumo |
|---|---|---|---|---|
| ai-gftd-apex (L1 app) | murakumo(推論) + kotobase(state) | privacy 推論を fleet に移管 / ephemeral state | Pro | 従量(¥/Mtok) |
| cloud-manimani (L5) | kotobase(Decision Ledger/sync) + murakumo(agent 推論) | 個人 OS の state 同期 + agent loop | Standard | 従量 |
| cloud-itonami (L3) | kotobase(業務 state/graph) + murakumo(agent-run 推論) | activity→audit 台帳 + proposal 推論 | Pro | 従量 |
| club-shinshi (L4) | kotobase(content-addressed blob) + murakumo(moderation 推論) | メディア blob + タグ/モデレーション | Pro | 従量 |

- **tenant 識別 = DID**（kotobase の tenant-state は tenant_did keyed）。内部 tenant DID =
  `did:web:apex.gftd.ai` / `did:web:manimani.gftd.ai` / `did:web:itonami.gftd.ai` /
  `did:web:shinshi.gftd.ai`（各 product の canonical actor DID を用いる。確定値は各
  product README/DID document を正とし、subagent が配線時に確認する）。
- **kotobase price**（既存、ADR-2607022200/PR#249）: Standard=Developer `price_1TVVI7BcblPoapUJivZq5PUa`
  / Pro=Business `price_1TVVI7BcblPoapUJe9950Vcr`。
- **murakumo price**: 従量（¥/Mtok）。murakumo は未 deploy・billing 未整備のため
  **本 ADR では kotobase の内部課金を先行**し、murakumo 課金は murakumo deploy 後の
  follow-up（プレースホルダ: 内部移管 tok を run ledger で計測 → 後日 price 化）。

## Decision — 実装方針（誰が何を）

1. **tenant provisioning（私）**: 各消費 product の DID を kotobase の内部 tenant として
   認識できるよう配線側で設定（DID document / tenant 設定）。KV tenant-state の
   **事前手書きはしない** — 実 subscription の webhook が正規に書く（合成 paid を避ける）。
2. **内部課金（オーナー）**: 各消費 product 用に kotobase price で Stripe subscription を
   作成する（customer + subscription、`metadata.tenant_did` = 消費 product の DID）。
   **課金・口座作成は AI が踏まない一線**。修正済み webhook（ADR-2607022500）が
   `checkout.session.completed`/`customer.subscription.*` を検証 → tenant-state に paid を
   書き、collect stripe-summary（kotobase price 限定）が active-subscriptions として数え、
   funnel/gate が内部 revenue として反映する。手順は本 ADR とペアで別途提示。
3. **コード配線（並列 subagent）**: 各消費 repo に kotobase/murakumo client を配線し、
   実際に呼ぶ（apex→murakumo 推論、manimani→kotobase sync、itonami→kotobase graph +
   murakumo、club-shinshi→kotobase blob）。各 repo は build 再現性（ADR-2607022400）を
   満たすこと。tenant 認証は各 product の DID + kotobase の CACAO/XRPC。
4. **BMC 反映**: funnel（ADR-2607022600）は Stripe active-subscriptions を既に読むため、
   内部 subscription が立った時点で net-kotobase の funnel revenue 段が実データ化する
   （追加コード不要）。canvas に「内需 tenant」関係を note で記録。

## Consequences

- (+) kotobase の初 paying tenant = 内部 4 product。funnel「signup 0」が実データへ。
  gate `kotobase-graph-arpu`（active-subscriptions>=1）が内部課金で正規に validate。
- (+) 各レイヤーの floor 収益（内需）が測定可能になり、外部獲得の前に unit economics を
  dogfooding で検証できる。
- (−) murakumo billing は未整備 → kotobase 課金を先行、murakumo は deploy 後 follow-up。
- (−) 各消費 repo の client 配線は build/deploy を伴う（repo ごとの build 再現性が前提）。
- (−) 内部課金は「自社→自社」なので会計上の相殺が必要（gftdcojp 内部取引）。実 revenue
  の gate 反映と会計処理は別（BMC は demand/使用の実測が目的）。

## References

- ADR-2607021500（7 レイヤー / フライホイール）/ ADR-2607022200（gate emitter）
- ADR-2607022500（kotobase webhook 修正 = 内部 subscription の fulfillment 前提）
- ADR-2607022600（funnel — 内部 subscription で revenue 段が実データ化）
- ADR-2607022400（build 再現性 — 各消費 repo 配線の前提）
