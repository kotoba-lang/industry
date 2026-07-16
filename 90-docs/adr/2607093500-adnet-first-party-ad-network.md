# ADR-2607093500: 自前広告ネットワーク — kotoba-lang/adnet コア + x402 USDC 課金 + cloud-itonami advertiser 運用

- **Status**: accepted（2026-07-09 オーナー指示:「r1, r2、また必要であれば adnetwork,
  adverse なども設計、実装, cloud-itonami , kotoba-lang で連携、実装」）。`adnet` コアは
  実装・landed・repo 作成済み。ad-server Worker / shinshi 配線 / cloud-itonami 統合は follow-up。
- **Related**: ADR-2607093100（x402 統合）、ADR-2607093300（nexus-x402 facilitator）、
  club-shinshi の R1 広告フライホイール修正（ExoClick、PR #22/#23）、
  `kotoba-lang/senden`（自社マーケ分析、別ドメイン）
- **参照**: club-shinshi ads_config.cljs / ad_slot.cljs（ExoClick 統合）

## Context

R1（広告フライホイール）は現状 **ExoClick（第三者広告ネットワーク）依存**で、収益は他社の
fill 率・payout ポリシー・支払いサイクルに従属する。R2（x402 creator 決済）は自前スタックで
垂直統合済み。オーナーから「広告も同様に自前ネットワークとして設計・実装し、cloud-itonami と
kotoba-lang で連携せよ」との指示を受けた。

`kotoba-lang/senden`（宣伝）は既存だが、**自社マーケティングの campaign/funnel/attribution
分析**であり、**第三者広告を配信して課金する広告ネットワーク/アドサーバ**とは別ドメイン。
新規に `kotoba-lang/adnet` を起こす。

## Decision

1. **広告配信の純ロジックを `kotoba-lang/adnet`**（public、Apache-2.0、pure `.cljc`、
   zero-dep/zero-I/O、landed、7 tests/30 assertions）に置く。x402 スタックの広告版:
   - `adnet.core`: campaign モデル（`{:creative :bid{:cpm|:cpc} :targeting :budget :flight
     :status}`）+ `eligible?`（tier/format/placement/geo/flight/budget 判定）+ `ecpm-micros`
     /`select`（eCPM オークション、CPC は想定 CTR で正規化）+ `serve`（落札 or **house ad
     backfill** = x402 catalog + creator 応援を宣伝）+ `apply-spend`/`reset-daily`（budget/
     pacing の純遷移、枯渇で自動 pause）
   - `adnet.billing`: impression/click → USDC 課金（CPM=bid/1000/impr、CPC=bid/click）+
     prepaid 会計（advertiser が USDC を前払いデポジット、impr/click で draw-down、低残高で
     x402 top-up を促す。per-event on-chain tx は gas が $0.002 impression を上回るため不可 →
     周期的 accrual 決済）+ ad-event append-only 記録

2. **advertiser の課金は x402 USDC レール**（`kotoba-lang/pay` + `treasury`）で行う。
   advertiser は campaign を USDC 前払いデポジット（`treasury/receipt->onchain` で検証）し、
   配信で残高を消費、低残高で x402 top-up。これで**広告 income が自社通貨（USDC）で自前
   facilitator を通じて流れる**（第三者 payout に従属しない）。

3. **cloud-itonami が advertiser 運用**を担う。cloud-itonami（ops-LLM ⊣ CertGovernor、
   langgraph-clj StateGraph、propose→govern→commit）の1業種 vertical として広告 ops を提供:
   ops-LLM が campaign 作成/停止/targeting を *proposal のみ* 返し、CertGovernor が広告ポリシー
   （tier 分離、禁止コンテンツ、予算上限）を検閲、可決を不変台帳に commit。`adnet.core` の純
   ロジックを advertiser 側の control plane が再利用する。

4. **publisher 側**（shinshi の `ad_slot`、将来 isekai 等）は third-party network の前段/代替
   として `adnet/serve` を呼び、house/first-party 広告を配信。ExoClick は当面併存（fill を
   埋める補完）、adnet の paid campaign が育てば first-party 優先に切替可能。

## Consequences

- **`adnet` コアは landed**（配信・課金の純ロジック）。follow-up:
  - **ad-server Worker**（`serve` を placement ごとに実行、impression/click を `accrue` で記録、
    x402 top-up を検証）。nexus-x402 と同型の Cloudflare Worker
  - **shinshi `ad_slot` 配線**: house ad を静的から adnet 動的配信へ（house-ad が既に x402
    catalog を宣伝するので R1↔R2 のクロス導線になる）
  - **cloud-itonami advertiser ops**: 広告 vertical の actor（propose→govern→commit）+ RAD 登録
- **R1↔R2 の統合**: adnet の house-ad が x402 catalog + creator 応援を宣伝するため、広告在庫
  （R1）が x402 需要（R2）への導線を兼ねる。両 income ループが相互強化する system dynamics。
- prepaid/accrual 設計は per-impression の on-chain tx を避ける（gas 経済性）。周期 settlement の
  sweep 実装は ad-server Worker 側の follow-up。
- manifest 登録: `orgs/kotoba-lang/adnet` を repos.edn に追加、`--entry adnet` 最小 diff。

## Alternatives Considered

1. **ExoClick 等の第三者広告ネットワーク依存を続ける（現状 R1）**。却下（自前化の指示）— fill
   率・payout・支払いサイクルが他社従属。x402 で決済を自前化したのと同じ理由で広告も自前化。
   ただし ExoClick は fill 補完として当面併存（adnet paid が育つまで）。
2. **`kotoba-lang/senden` を拡張して広告配信を入れる**。却下 — senden は自社マーケ分析
   （campaign/funnel/attribution、chobo.ledger/shitsuke 依存）で、第三者広告の配信/オークション
   /課金とは別ドメイン。混ぜると両方の凝集度が落ちる。
3. **広告課金を独自トークン/クレジットで行う**。却下 — x402/USDC レールが既にあり、advertiser
   も同じ USDC で払える方が摩擦が少なく、R2 と決済インフラを共有できる。
4. **per-impression で on-chain 決済**。却下 — $0.002 の impression に Base gas を払うのは
   不経済。prepaid デポジット + accrual + 周期 sweep（x402 top-up）が正しい粒度。
