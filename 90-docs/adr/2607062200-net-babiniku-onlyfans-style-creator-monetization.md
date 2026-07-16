# ADR-2607062200: net-babiniku — LLM-driven VTuber creator monetization (livechat, oshikatsu, tips, subscription, OnlyFans-style operation)

## Status
Proposed

## Context

`gftdcojp/net-babiniku` (ADR-2607051800, ADR-2607051900) is at **Milestone 0**: a
ClojureScript/reagent VRM render loop + a flat `governor.cljc` (`review-turn`: blocked-terms /
allowed-emotions / allowed-motion-cues, atom-backed append-only ledger) + a `broadcast-button-view`
UI stub (`src/babiniku/ui/views.cljs:34-45`, `start-broadcast!`/`stop-broadcast!` are
`console.log` only). ADR-2607051800 explicitly scoped out "LLM provider(s), pricing/currency
model, and detailed moderation policy" as a follow-up. ADR-2607051900 (streaming integration,
Accepted) covers only the RTMP/WebRTC transport to Twitch/YouTube/OBS — no monetization.
`net-babiniku` has no entry yet in `90-docs/business/canvas-ledger.edn`.

User direction (2026-07-06): raise net-babiniku's maturity to a full **VTuber platform** where
the "talent" is an **LLM, not a human**, doing livechat/conversation/motion — with **livechat,
oshikatsu (推し活), 投げ銭 (tips), 月額課金 (subscription)**, run in the **same operating style
as OnlyFans**. Clarifying answers on file:
- **Content**: tiered — free SFW core chat stays free; a paid tier may include NSFW content.
- **Payment**: an adult-content-compatible PSP, integrated separately (not Stripe).
- **Scope for this turn**: ADR only, no code yet.

### Prior art already in this repo: `gftdcojp/club-shinshi` (`shinshi.club`, live)

This is not a green-field business-model question — `club-shinshi` already runs almost exactly
this model, live, with hard-won research behind every decision:

- **ADR-2606010100** (`orgs/gftdcojp/club-shinshi/90-docs/adr/2606010100-shinshi-ad-monetization-bmc.md.edn`),
  amendments (b) and (c): shinshi's AI characters are recast as **OnlyFans-style creators** —
  monthly subscription + PPV (単発課金) + tips (Shinshi Points), **80/20 creator take rate**
  (OnlyFans-canonical), core viewing/1:1-chat/search staying free+ad-supported (creator payment
  is an opt-in upsell, not a paywall on the whole product). **PSP gate**: Stripe is already
  ruled out for NSFW content org-wide; the paid creator layer is blocked until an
  **adult-friendly PSP (CCBill/Verotel/Epoch/Segpay)** is actually contracted — until then, all
  paid-looking UI renders "準備中" and **never fakes a working checkout** (honest-default
  pattern, ADR-0084). **Dual settlement rail** (amendment c): fiat rail (MoR = gftd, vendor-repo
  implementable per the standing "payment counterparty is always gftd" decision) vs. crypto/USDC
  rail, which is **out of bounds for any gftdcojp repo** — self-custody on-chain settlement must
  live in `etzhayyim/root` per the vendor/etzhayyim centralization-axis boundary
  (`orgs/etzhayyim/root/90-docs/adr/2605211950-vendor-centralized-etzhayyim-decentralized-substrate-axis.md`,
  `orgs/etzhayyim/root/90-docs/adr/2605172100-etzhayyim-payments-on-chain-only.md`). Legal:
  特定商取引法 disclosure required for the fiat tier at go-live; JP 刑法175 (obscenity,
  extraterritorial risk per FC2 precedent) + geo-block; UK AVSA / French LCEN / US KOSA regional
  NSFW controls apply to any gftdcojp adult-adjacent product, not just shinshi.
- **ADR-2606032100** (NSFW LLM serving): shinshi's uncensored 1:1 character chat is a
  **self-fine-tuned gemma-4-26B-A4B (bf16 LoRA) served on Modal/vLLM**, not a third-party
  provider — third-party LLM APIs categorically refuse in-character adult roleplay. **Hard
  invariants preserved even uncensored**: no minors, no real persons, no non-consent
  (`_hard_deny` categories), 3-stage 18+ age gate, mandatory AT self-label
  (`nsfw`/`nudity`/`sexual`). A **train/inference system-prompt byte-parity** invariant is
  non-negotiable (mismatched system prompts break in-character behavior).
- **`gftd-talent-actor`** (`orgs/gftdcojp/gftd-talent-actor/src/talent/`): `policy.cljc`
  implements a 6-tier **HARD/SOFT** violation model (RBAC → purpose limitation → fairness →
  minimal disclosure are HARD, non-overridable; confidence floor → high-stakes gate are SOFT,
  human-approval-eligible) — materially richer than net-babiniku's flat allow/hold check.
  `store.cljc` defines a `Store` protocol (`MemStore` / `DatomicStore`, same contract) for the
  append-only ledger. `operation.cljc` is a langgraph-clj StateGraph with
  `interrupt-before #{:request-approval}` for human-in-the-loop escalation.
- **`net-kotobase/clj-edge/src/kotobase/billing.cljc`**: proven webhook → pure function → KV
  persistence pattern for subscription-tier state (`stripe-event->tenant-state`), and
  **`kotoba-lang/com-stripe/src/stripe/main.cljc`**: a clean-room Stripe entity schema
  (Customer/Subscription/PaymentIntent/Charge/Refund/Invoice/Product/Price) usable as the
  *internal* domain schema regardless of which PSP actually settles funds.

## Decision

1. **Content tiering** (per user direction): the existing free SFW conversational-VTuber
   experience (Milestone 0's scope) is unchanged and stays free. A **paid tier** is added on top
   and may include NSFW content — same two-tier shape as club-shinshi's
   core-free/creator-paid split, not a full paywall.

2. **Three monetization primitives**, identical in shape to club-shinshi's proven set (do not
   invent a fourth): **① 月額課金 (subscription)**, per character, tiered (Free / Sub / VIP,
   mirroring shinshi's `OnlineSalon.svelte` redesign); **② PPV** (単発の限定コンテンツ/シーン
   購入); **③ 投げ銭/tips** (points-style micro-payment, surfaced during livechat — this is
   "oshikatsu" in product terms). **Creator take rate 80/20**, OnlyFans-canonical, matching
   club-shinshi.

3. **Payment rail: fiat only, via an adult-friendly PSP, vendor(gftdcojp)-implementable.**
   Per user direction, Stripe is not used (NSFW ToS conflict, already established org-wide).
   Concrete vendor selection (CCBill vs. Verotel vs. Epoch vs. Segpay) is a **business/legal
   contracting decision** — out of scope for this ADR and not resolvable in code. **Crypto/USDC
   settlement is explicitly out of scope for `net-babiniku`** (a `gftdcojp` repo): if wanted
   later, self-custody on-chain settlement must be implemented in `etzhayyim/root` and consumed
   via a consent-capability boundary, per the standing centralization-axis decision — this repo
   may not scaffold on-chain code.

4. **Honest default, no fake checkout** (ADR-0084 pattern, inherited unchanged): until an actual
   PSP contract exists, every paid-looking control (subscribe / tip / PPV) renders disabled /
   "準備中". This ADR does not authorize wiring a checkout UI that appears to charge money before
   a real PSP integration exists.

5. **Governor upgrade** — replace `babiniku.governor/review-turn`'s flat check with a
   HARD/SOFT-tiered policy modeled directly on `talent.policy`:
   - **HARD** (non-overridable, immediate hold): existing blocked-terms/emotion/motion-cue
     checks, **plus** age-gate bypass attempts, and any attempt to render a paid-content
     interaction when no PSP is provisioned (enforces #4 structurally, not just in the UI).
   - **SOFT** (human-approval-eligible): borderline content-tier ambiguity (e.g. is this turn
     SFW or does it cross into the paid NSFW tier) — escalates via an `interrupt-before`-style
     pause, per `talent.operation`'s StateGraph shape, rather than a hard reject.
   - **Ledger**: migrate from a bare atom to the `Store` protocol shape (`MemStore` for
     dev/test, a Datomic-backed store for production), per `talent.store`.

6. **NSFW LLM serving, if/when the paid NSFW tier is greenlit**: reuse club-shinshi's proven
   pipeline — a self-fine-tuned model served on Modal/vLLM, not a third-party API, with the
   same hard-deny categories and train/inference system-prompt parity invariant. This ADR does
   not re-litigate LLM vendor/infra choice; it adopts shinshi's already-validated approach.

7. **Livechat / oshikatsu presentation**: the `broadcast-button-view` stub
   (`src/babiniku/ui/views.cljs:34-45`) becomes the literal livechat entry point once
   ADR-2607051900's transport is implemented; tip/PPV prompts surface during an active livechat
   session. "Oshikatsu" is the *combination* of subscription + tipping + livechat presence — an
   operating pattern, not new technology.

8. **Business ledger registration** (follow-up, not done by this ADR): once accepted, register
   `net-babiniku.*` entries in `90-docs/business/canvas-ledger.edn` via the normal
   `70-tools/bmc` advisor/gate tooling (not hand-edited — that file is an append-only event log
   produced by that tool), including a PSP-gate entry shaped like club-shinshi's frozen
   PSP/crypto-rail gate.

9. **Legal/compliance, inherited unchanged from club-shinshi**: 特定商取引法 disclosure required
   at go-live for the fiat paid tier; mandatory 18+ age gate for any NSFW-tier content; JP 刑法
   175 extraterritorial obscenity risk (same legal entity as shinshi.club) + geo-block posture;
   UK AVSA / French LCEN / US KOSA regional NSFW controls apply identically.

## Consequences

- This ADR does not unlock revenue by itself — actual PSP contracting is a business/legal step,
  not a code change, exactly as it currently stands for club-shinshi.
- The governor becomes materially more complex (HARD/SOFT tiers, human-approval escalation,
  durable `Store`) — real implementation work, not covered here.
- `net-babiniku` and `club-shinshi` now share a monetization *pattern* (not code). A future ADR
  could ask whether to extract a shared PSP-adapter/billing library (e.g. into `kotoba-lang`)
  instead of duplicating the pattern per product — left open, not decided here.
- Crypto/USDC settlement remains deferred indefinitely, pending an `etzhayyim`-side decision;
  not this ADR's call to make.
- Follow-up: concrete adult PSP vendor selection, exact subscription/PPV/tip price points, and
  the NSFW-tier go/no-go decision itself are product/business decisions out of scope for this
  architecture ADR (same "out of scope" framing ADR-2607051800 already used for LLM
  provider/pricing).

## Alternatives Considered

1. **Design NSFW-chat/monetization from scratch for net-babiniku, independent of club-shinshi.**
   Rejected — club-shinshi already has a live, hard-won precedent (PSP research, LLM fine-tune,
   legal disclosure requirements); duplicating that research wastes effort and risks an
   inconsistent compliance posture across two `gftdcojp` adult-adjacent products.
2. **Use Stripe for the paid tier.** Rejected — Stripe's ToS prohibits adult content; already
   established precedent in this repo (club-shinshi's ADR-2606010100 §Goal).
3. **Implement crypto/USDC settlement directly in `net-babiniku`.** Rejected — violates the
   standing vendor/etzhayyim centralization-axis boundary (ADR-2605211950, ADR-2605172100);
   `gftdcojp` repos may not implement on-chain settlement.
4. **Skip the HARD/SOFT governor upgrade, keep the flat `review-turn` check.** Rejected —
   monetized NSFW content raises containment stakes past what a flat allow/hold check can
   safely arbitrate (age-gate bypass, payment-adjacent prompts need non-overridable HARD holds);
   `talent.policy`'s pattern is proven and directly reusable.

## References

- `90-docs/adr/2607051800-net-babiniku-vrm-vtuber-design.md` (architecture; explicitly scoped
  out pricing/moderation detail)
- `90-docs/adr/2607051900-network-isekai-net-babiniku-broadcasting-integration.md` (streaming
  transport only, no monetization)
- `orgs/gftdcojp/club-shinshi/90-docs/adr/2606010100-shinshi-ad-monetization-bmc.md.edn`
  (OnlyFans-style creator monetization, PSP gate, dual-rail settlement, legal posture)
- `orgs/gftdcojp/club-shinshi/90-docs/adr/2606032100-shinshi-nsfw-chat-gemma4-modal-finetune.md.edn`
  (NSFW LLM serving pattern)
- `orgs/gftdcojp/app-aozora/90-docs/adr/0084-yoro-ads-integration.md` (honest-default,
  no-fake-checkout pattern)
- `orgs/etzhayyim/root/90-docs/adr/2605211950-vendor-centralized-etzhayyim-decentralized-substrate-axis.md`
- `orgs/etzhayyim/root/90-docs/adr/2605172100-etzhayyim-payments-on-chain-only.md`
- `orgs/gftdcojp/gftd-talent-actor/src/talent/{policy,store,operation}.cljc`
  (HARD/SOFT governor, Store protocol, interrupt-before escalation)
- `orgs/gftdcojp/net-kotobase/clj-edge/src/kotobase/billing.cljc`,
  `orgs/kotoba-lang/com-stripe/src/stripe/main.cljc` (billing/subscription schema precedent)
- `src/babiniku/{governor,ui/views}.cljs` in `orgs/gftdcojp/net-babiniku` (current Milestone 0
  implementation this ADR extends)
- `90-docs/business/canvas-ledger.edn` (business model event log; `net-babiniku` not yet
  registered; `club-shinshi.*` entries are the template to follow)
