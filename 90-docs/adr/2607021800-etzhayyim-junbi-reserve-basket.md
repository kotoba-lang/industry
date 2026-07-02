# ADR-2607021800: junbi (準備) reserve treasury actor + HAKARI (秤) basket unit of account

**Status**: accepted (R0 · R1 · R2 landed 2026-07-02, owner-ratified)
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki (owner directive 2026-07-02)

## Context

kotoba's internal value unit **EN (縁 / ENGI 縁起)** is a net-zero, non-minted
mutual-credit unit (`kotoba/docs/ADR-engi-mutual-credit-on-chain.md`, R0–R9
landed). By design EN never carries external value; the scarce,
irreversibly-settled asset lives **across the operating-entity boundary** —
until now pinned to a single asset, "USDC on Base L2, etzhayyim-exclusive".

Owner directive (2026-07-02): etzhayyim shall hold a **reserve of fiat-backed
currencies issued by trusted organizations** — explicitly **excluding USDT** —
**including central-bank digital currencies (CBDC) such as e-CNY (数字人民币)**,
and shall maintain a **balanced (basket) unit** over that reserve.

Existing constraints this design must not violate:

- **Charter ADR-2605172100 Alt C + Charter Rider §2(b)**: no custom token is
  ever minted; no speculative finance. These are constitutional (Lv7+ lock).
- **kawase-yui (ADR-2605282200)** already whitelists canonical Base L2
  stablecoins (USDC + EURC at R1, +JPYC at R2) under gates: mid-market
  Chainlink ±0.5% band (G4), no spread profit (G5), stable-only pool / no
  DeFi yield (G6), no fiat custody (G8).
- **mKOTO (ADR-2605282100)** is non-transferable compute metering; **moyai /
  social capital** is non-transferable decaying reputation. EN / mKOTO /
  social capital / reserve are four distinct units and stay unmixed.
- **kotoba is read+verify only** on-chain (EVM surface in `kotoba-auth`);
  tx signing, custody and on-chain origination are etzhayyim-exclusive.

## Decision

### D1 — new actor `junbi` (準備)

Create `etzhayyim/com-etzhayyim-junbi`, the **reserve treasury actor**, in the
standard actor shape (containment + independent governor + append-only audit
ledger; langgraph-clj StateGraph at R1). Namespace `com.etzhayyim.junbi.*`,
DID `did:web:etzhayyim.github.io:com-etzhayyim-junbi`.

### D2 — Tier-1 reserve whitelist (on-chain, Base L2)

Assets held in an etzhayyim ERC-4337 smart account / Safe on Base L2, keys
under `kotoba-custody` t-of-N Shamir:

| Asset | Issuer | Basis of trust |
|---|---|---|
| **USDC** | Circle Internet Financial | US-regulated, monthly third-party reserve attestations, Base-native |
| **EURC** | Circle Internet Financial | same regime as USDC |
| **JPYC** | JPYC株式会社 | 資金移動業登録 / 改正資金決済法(2023)「電子決済手段」 |

### D3 — Tier-2 reserve (CBDC, permissioned / off-chain)

CBDCs cannot sit in a Base smart account — they live on permissioned
central-bank rails. They form a **separate custody tier**, observed by kotoba
via **signed attestation datoms only** (never faked as on-chain reads):

| Asset | Issuer | Status |
|---|---|---|
| **e-CNY** (数字人民币) | 中国人民銀行 (PBOC) | R3 target; requires authorized-operator wallet + per-jurisdiction legal analysis |
| digital euro | European Central Bank | placeholder — not yet issued |
| digital yen | 日本銀行 (BoJ) | placeholder — pilot only |

A Tier-2 asset carries **weight 0 until Council Lv7+ unanimity activates it**
(same activation bar as kawase-yui G14). Basket math renormalizes over active
assets, so an unactivated / custody-unavailable CBDC never breaks NAV.

### D4 — USDT is excluded

Owner decision 2026-07-02. USDT's reserve-attestation regime does not meet the
whitelist bar, and it was never in the kawase-yui canonical set. The exclusion
is **structural** (hard-coded reject in params validation, gate J3), not a
default: readmission would need Council Lv7+ unanimity **and** an ADR
amendment.

### D5 — HAKARI (秤): basket **unit of account**, not a token

`1 HAKARI` is defined by Council-versioned weight params (same versioned-blob
pattern as the mKOTO tariff schedule and social-capital params):

```clojure
{:version "1.0.0"
 :numeraire :usd
 :band-bps 50              ; inherits kawase-yui G4 (±0.5% mid-market band)
 :weights {:usdc 0.40 :jpyc 0.40 :eurc 0.20}}   ; R1 example
 ;; R3 example after e-CNY activation: {:usdc 0.35 :jpyc 0.35 :eurc 0.20 :ecny 0.10}
```

NAV is computed from Chainlink mid-market rate attestations. HAKARI is
**SDR-style: a unit of account only** — it is never minted as an ERC-20, never
transferable, and redemption semantics are pro-rata payout of the underlying
Tier-1 assets. This keeps Alt C / Rider §2(b) fully intact and avoids
collective-investment-scheme / 電子決済手段発行 classification. A transferable
basket token (ERC-4626 style) is **out of scope** and would require charter
amendment + legal analysis.

### D6 — rebalancing

Band-triggered: when actual reserve weights drift ≥ trigger-bps from target,
the actor emits a **rebalance proposal** (proposal only). Execution requires
human approval (`interrupt-before`), settles at the oracle mid-market rate
within the ±0.5% band. **No AMM, no spread, no yield** (kawase-yui G5/G6
inherited).

### D7 — accounting & custody

Every reserve movement is a **balanced double-entry posting**
(`kotoba-lang/banking`, R1 wiring) and an append-only audit datom. Annual
cross-reference into toritate accounting. On-chain reads via
`kotoba-lang/base-l2` + `kotoba-auth` EVM surface; **kotoba never signs** —
signing/custody stays etzhayyim-exclusive.

### D8 — relation to EN

EN stays untouched: net-zero, non-minted, Σ balances ≡ 0. HAKARI generalizes
the ENGI ADR's cross-boundary settlement asset from "USDC (single)" to "the
junbi basket", and may denominate EN **credit-limit sizing** (reputation
collateral valuation). HAKARI never mints or burns EN.

## Gates (CI-greppable invariants) J1..J12

- **J1** no token mint — HAKARI is a unit of account; no ERC-20 deploy path exists.
- **J2** issuer whitelist — only D2 assets + Council-activated D3 CBDCs pass params validation.
- **J3** USDT structurally rejected (hard-coded excluded set).
- **J4** Σ weights = 1.0 (within ε), all weights ≥ 0.
- **J5** NAV rate band ±0.5% mid-market (`band-bps 50`, = `KAWASE_MAX_BAND_BPS`).
- **J6** no yield / no DeFi / no lending of reserve (no swap/LP/stake entry points).
- **J7** no fiat custody — stablecoin ERC-20 + authorized CBDC wallet only.
- **J8** double-entry balanced postings only (unbalanced posting rejected).
- **J9** Tier-2 CBDC is attestation-observed; weight 0 until Council activation.
- **J10** rebalance is proposal-only; execution requires human approval.
- **J11** no spread profit — execution locked to mid-market rate.
- **J12** EN net-zero untouched — basket code has no EN mint/burn surface.

## Phase ladder

| Phase | Scope | State |
|---|---|---|
| **R0** | this ADR — pure `.cljc` core (params validation J2–J5, NAV, drift, rebalance proposal J10, governor J1–J12) + tests; repo + west + RAD registration | **landed 2026-07-02** |
| **R1** | langgraph-clj StateGraph TreasuryActor (`interrupt-before` human approval, J10); `banking` double-entry wiring (J8); `base-l2` read-only Base observation — USDC + EURC readable at attested addresses, **JPYC read unlocks on Council address attestation** (never faked); audit datoms over `:db-api` | **landed 2026-07-02** (owner-ratified "r1") |
| **R2** | Chainlink attestation feed over `ITransport` (USDC/USD `0x7e86…bc6B` + EURC/USD `0xDAe3…8250`, verified vs the Chainlink reference data directory; **no JPY/USD feed exists on Base** — JPYC rate unattested until Council attests an alternative); rebalance-proposer cell (1 tick = 1 bounded run); `toritate.ledgerEntry` cross-ref (G3/G4 + G12 mirrored; EURC/JPYC → `nativeAsset "n-a"` pending toritate enum extension); HAKARI-denominated EN credit-limit sizing (J12 intact) | **landed 2026-07-02** (owner-ratified "r2") |
| **R3** | +e-CNY Tier-2 (per-jurisdiction legal analysis + Council Lv7+ unanimity; authorized-operator wallet; attestation feed); digital euro / digital yen remain placeholders | post-R2 |

## Honesty (R0)

- e-CNY custody for a foreign entity is **not currently establishable** at
  scale (pilot corridors only); that is exactly why Tier-2 exists with weight 0
  and attestation-only observation. No e-CNY integration is faked at R0.
- The child repo was created private (standing-authorization default) and
  flipped **public with owner approval (2026-07-02)**; the static did:web
  document is served from the repo's GitHub Pages root
  (`/.well-known/did.json`, `.nojekyll`), cross-linking the RAD identity.
- R0 core is pure `.cljc` with no on-chain I/O — banking / base-l2 / StateGraph
  wiring is R1, not pretended at R0.
- **R1 honesty**: chain reads go through base-l2's injected `ITransport`
  (unit-tested against a mock JSON-RPC transport); the production Safe /
  ERC-4337 smart-account provisioning and funding is an etzhayyim ops act,
  not code, and remains to be executed. JPYC has no Council-attested Base
  address yet (`:address nil` — reads throw rather than guess). Rates are
  injected attestations at R1; the live Chainlink feed cell is R2.

## Related

- `orgs/kotoba-lang/kotoba/docs/ADR-engi-mutual-credit-on-chain.md` (EN/ENGI)
- ADR-2605282200 kawase-yui (multi-stable pool precedent, gates G4/G5/G6/G8/G14)
- ADR-2605282100 mKOTO economy / ADR-2605172100 Alt C (no custom token)
- `orgs/kotoba-lang/banking` (double-entry), `orgs/kotoba-lang/base-l2`
  (ERC-4337 / anchor), kotoba-custody (Shamir)
- `90-docs/adr/2606302300-org-taxonomy-4-orgs.md` (etzhayyim = agent + 公益)
