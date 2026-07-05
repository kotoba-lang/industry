# ADR-2607052100: club-shinshi creator payout × crypto settlement rail — regulatory/design review (investigation only, no implementation)

**Status**: proposed (investigation + recommendation only — no code, no decision to build)
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki

## Context

While evaluating how to raise portfolio revenue fastest (a prior session pass,
this superproject's business-loop work), a proposal surfaced: `club-shinshi`'s
creator monetization (subscriptions/PPV/tips) is frozen pending an
adult-friendly payment processor, while `local-murakumo` already operates a
live, working USDC/Gnosis-Safe crypto settlement rail (Base L2, Etherscan-
verified confirmations, real revenue collected). The proposal was to combine
the two — reuse `local-murakumo`'s working crypto rail to unfreeze shinshi's
creator payouts.

This ADR investigates whether that combination is sound, before any code is
written. It corrects and narrows the original proposal based on what the
portfolio's own existing ADRs already decided.

## What the existing ADRs already decided (re-confirmed, not re-litigated)

- **`club-shinshi`'s own ADR (`ADR-2606010100`, amendments 2026-06-02b/c)**
  already designed a two-rail plan for creator monetization: a **fiat rail**
  first (adult-friendly PSP — CCBill/Verotel/Epoch/Segpay; Stripe is excluded,
  NSFW-prohibited; merchant-of-record = gftd, per `ADR-2606011400`), and a
  **crypto rail** second, explicitly deferred until "etzhayyim側 settlement
  capability + consent 配線" is ready. The fiat rail is the stated
  faster path (blocked only on a vendor-side PSP contract, a business
  decision) — crypto was always the second priority, not a substitute.
- **The same ADR contains an explicit code boundary**: *"vendor repo で禁止:
  on-chain / USDC / ERC-4337 のコードを shinshi (この repo) に scaffold しない"* —
  on-chain payment code is deliberately kept out of the shinshi repo itself.
- **`ADR-2606011400`** (Consensys-pattern operating model) is the reason why:
  the portfolio's on-chain settlement pattern is specifically an
  **etzhayyim-resident** shape — user-held ERC-4337 smart wallets, USDC,
  self-custody, **gftd is never the merchant-of-record or settlement
  custodian**, gftd only supplies non-settlement infra plus an optional
  *disclosed* fiat→USDC on-ramp. This is a deliberate liability-containment
  design, not an arbitrary org boundary.

**`local-murakumo`'s crypto rail does not have this shape.** Its Treasury
Safe is a Gnosis multisig whose signers are gftd operators — a
centrally-operated custody model, not the etzhayyim pattern's user
self-custody. It works well for its own purpose (compute credit purchases,
where the counterparty relationship is gftd-to-buyer, one-directional).
Reusing it as-is for shinshi's creator **payouts** (gftd receiving from many
buyers, then paying out to a *different* party, the creator) is a materially
different, higher-risk shape than either of the two patterns the portfolio
had already designed for.

## New findings from this pass

1. **Commingling risk (the most concrete, previously undocumented finding).**
   USDC is a centrally issued, freezable stablecoin — Circle can and does
   freeze addresses tied to sanctioned parties or AUP violations. If
   `local-murakumo`'s existing treasury Safe were reused for shinshi creator
   payouts and that activity ever triggered a compliance freeze, **the same
   freeze would also lock murakumo's unrelated GPU-compute revenue sitting in
   the same address.** Two unrelated business lines sharing one treasury
   address means a compliance action against either one is a compliance
   action against both. This is a concrete architectural reason to keep them
   separate regardless of the regulatory analysis below.
2. **Crypto doesn't remove the "adult business" PSP problem — it moves it to
   the on-ramp.** A purely on-chain, self-custodied settlement leg (wallet to
   wallet) has a different regulatory footprint than a card processor. But
   the moment a *disclosed fiat on-ramp* is offered (per `ADR-2606011400`'s
   own money-flow diagram — fiat → USDC for a user without crypto already),
   that on-ramp provider's own KYB/AUP almost always excludes adult
   businesses, the same way Stripe does. So adding crypto does not remove the
   need to solve the adult-friendly-PSP problem `ADR-2606010100` already
   identified as the fiat rail's blocker — at best it adds an option for
   users who *already* hold USDC and never touch an on-ramp.
3. **The receiving-many/paying-different-party shape reads as money
   transmission, not peer-to-peer.** Wallet-to-wallet USDC transfer between
   two parties who already know each other is a comparatively low-AML-risk
   shape. A treasury that receives USDC from many unrelated buyers and then
   pays out to a different set of parties (creators) — the exact shape
   subscriptions/PPV/tips need — is structurally closer to a payment
   intermediary. `ADR-2606010100` already flagged this correctly (資金決済法/
   AML territory for the crypto leg, 特商法 for the fiat leg); this pass
   doesn't find a way around that, only confirms it applies with more force
   to a shared, reused treasury.
4. **Ad-network policy is not the constraint here.** ExoClick (shinshi's
   live, working ad revenue) is itself an adult-content ad network, so
   combining it with a crypto payment option is not a live conflict. The
   actual constraint is payment/on-ramp KYB policy, not advertising policy —
   worth stating explicitly since the original framing ("ad network policy")
   was imprecise.

## Recommendation (not a decision to implement)

1. **Do not pause or deprioritize the adult-friendly fiat PSP search
   (CCBill/Verotel/Epoch/Segpay) in favor of a crypto shortcut.** Per
   `ADR-2606010100`'s own sequencing, fiat is still the shorter, simpler path
   to shinshi's first real creator-monetization dollar, and nothing in this
   pass changes that.
2. **If a crypto rail for shinshi creator payouts is pursued at all, do not
   reuse `local-murakumo`'s existing treasury Safe.** Any shinshi-specific
   crypto settlement should use its own, separate treasury address, so a
   compliance action on one business line can't freeze the other's unrelated
   revenue.
3. **Follow the already-decided pattern rather than inventing a third one**:
   either (a) route shinshi's eventual crypto option through the
   etzhayyim-resident on-chain pattern (`ADR-2606011400`) as originally
   specified, keeping gftd out of the settlement/custody path entirely, or
   (b) if the owner wants gftd to operate shinshi's own crypto settlement
   directly (closer to `local-murakumo`'s shape but on its own, separate
   treasury), that is a new, explicit business/legal decision — not a default
   — because it puts gftd back into a settlement-custodian role the
   portfolio's own architecture has otherwise deliberately avoided.
4. **Selecting an adult-friendly PSP and/or a fiat on-ramp provider is a
   business and legal decision, not an engineering one** — this ADR
   identifies the regulatory shape of the problem; it does not recommend a
   specific vendor or certify compliance in any jurisdiction.

## Non-goals

- No code changes in this pass (per the scoped ask: investigation + ADR
  only).
- No vendor selection (PSP, on-ramp) — business decision, out of scope.
- No claim of legal compliance in any jurisdiction — a real compliance review
  (資金決済法/AML, 特定商取引法, Circle's AUP, any on-ramp's KYB terms) needs
  actual counsel, not this ADR.

## Consequences

- (+) Corrects a prior-session proposal (reuse `local-murakumo`'s rail
  wholesale) before any code was written on it, using the portfolio's own
  already-decided architecture (`ADR-2606010100`, `ADR-2606011400`) instead
  of re-deriving a design from scratch.
- (+) Surfaces a concrete, previously undocumented risk (treasury
  commingling across unrelated business lines under one freezable-asset
  custodian) that applies regardless of which crypto rail shape is eventually
  chosen.
- (+) Keeps `local-murakumo`'s working rail and revenue untouched — no change
  proposed to it in this pass.
- (−) Does not unblock shinshi's frozen creator monetization by itself — the
  fiat PSP search (already identified as the shorter path) remains a
  business decision the owner has to make outside this ADR.

## References

- `orgs/gftdcojp/club-shinshi/90-docs/adr/2606010100-shinshi-ad-monetization-bmc.md.edn`
  (fiat-first/crypto-second sequencing, the on-chain-code boundary, PSP gate)
- `orgs/gftdcojp/ai-gftd-apps-gftdcojp/90-docs/adr/2606011400-consensys-pattern-etzhayyim-product-gftd-infra-vendor.md.edn`
  (etzhayyim-resident on-chain settlement pattern, gftd-never-MoR rule,
  disclosed fiat on-ramp shape)
- `orgs/gftdcojp/local-murakumo/docs/business.md` (the working USDC/Gnosis
  Safe rail this ADR recommends not reusing as-is)
- `90-docs/business/maturity-scores.md`, `cloud-itonami` ADR-0010/0011
  (portfolio revenue context that prompted this investigation)
