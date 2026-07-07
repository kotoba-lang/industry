# ADR-2607071000: net-babiniku crypto payment rail — consuming etzhayyim's Base L2/USDC settlement, no new chain/wallet decision

## Status
Proposed (net-babiniku side only — the new vendor→etzhayyim payment-consumption crossover this ADR proposes needs etzhayyim-side review before implementation; see Consequences)

## Context

Following ADR-2607070800's LLM backend decision, the user asked to continue the pivot toward
big decisions needing user input — specifically **crypto/chain + wallet selection** for
`net-babiniku`'s subscribe/tip/PPV monetization rail (still HARD-held by
`babiniku.monetization/unprovisioned-capability` per ADR-2607062200's honest-default
principle, since no payment rail has ever existed).

Before designing anything, research confirmed the chain/asset/wallet-UX stack is **already
decided** at the `etzhayyim/root` level — there is no chain to pick. This ADR is therefore
narrower than its original framing: it defines how `net-babiniku`, as a `jk-luxury` **vendor**
app, is supposed to *consume* that already-decided rail through a consent-capability boundary,
per the standing centralization-axis rule, without ever scaffolding chain/wallet/settlement
code in `net-babiniku` itself.

### What's already decided (not re-litigated here)

`orgs/etzhayyim/root/90-docs/adr/2605172100-etzhayyim-payments-on-chain-only.md` (status:
proposed) — hard rule: every etzhayyim-substrate app handling value transfer must settle
on-chain via **Base L2**. Canonical stack: **USDC** (with EURC/JPYC also whitelisted per
`kawase-yui`), **ERC-4337 smart accounts via Coinbase Smart Wallet with WebAuthn passkey
signing** (no seed phrase, no browser extension — the payer's own device holds custody),
**Superfluid** for streaming subscriptions, **0xSplits** for creator/platform revenue splits,
**Gnosis Safe** for escrow, an etzhayyim-operated Paymaster. A custom token was explicitly
rejected. Fiat processors (Stripe et al.) are prohibited in etzhayyim-substrate code — this is
the same rail `junbi` (the reserve-treasury actor, ADR-2607021800) already holds USDC/EURC/JPYC
in, under Shamir-split custody keys.

`orgs/etzhayyim/root/90-docs/adr/2605211950-vendor-centralized-etzhayyim-decentralized-substrate-axis.md`
(status: active) — the dual-axis rule: decentralized primitives (Ethereum/Base L2/ERC-4337/
did:web/did:plc/etc.) are **etzhayyim-exclusive**; centralized substrate (fiat, Datomic,
operator storage, JWT) is **vendor-exclusive**. `net-babiniku` is squarely a vendor app
(Cloudflare Pages, Datomic-shaped app-db pattern, no chain code) — it must never hold a private
key or scaffold settlement logic itself. The ADR's one worked crossover direction is
**etzhayyim → vendor** (an etzhayyim app calling a vendor-hosted paid-tier fiat XRPC as
progressive enhancement) — the **opposite** direction from what `net-babiniku` needs (a vendor
app consuming etzhayyim's *crypto* settlement). No worked example of that direction exists yet.

### The existing consent-capability precedent (a template, not a drop-in fit)

`20-actors/karute/actor-manifest.jsonld` + `90-docs/adr/2605231401-karute-consent-capability-iryo-bridge.md`
implement `com.etzhayyim.consent.capability` — an Ed25519-signed, publicly-verifiable
delegation record: `{version, granterDid, granteeDid, purpose, scope, resourceUris?, issuedAt,
expiresAt, constraints{maxQueriesPerDay?, redactionLevel?, downstreamRedistribution?,
auditWebhookDid?}, delegationPath?, revokedAt?/revokedBy?/revocationReason?, signature{alg,
value, keyId}}`. This is health-data **access consent** (patient authorizes a biller to query
encounter records), not a **payment authorization** — the shape is a useful template (signed,
scoped, expiring, revocable delegation) but its fields (query scope, redaction level) don't map
directly onto "authorize a recurring USDC subscription." Notably, even here,
`signConsentCapability` — the actual signing call — **is not implemented**; the ADR itself
calls it "currently a stub in the actor manifest." Both `karute` and `iryo` are `etzhayyim/root`
actors (an intra-substrate handoff), not a worked vendor↔etzhayyim example.

### `@etzhayyim/sdk`'s actual implementation state (honest accounting)

`20-actors/etzhayyim-sdk/src/{index.ts,pay.ts}` exposes `pay()`, `payStream()`,
`payStreamStop()`, `escrowOpen()`, `escrowRelease()`, `splitDistribute()`, `verify()`. Of
these, **only `pay()` is actually implemented** — a one-off EOA USDC transfer plus an AT
record, returning `PaymentReceipt{txHash, blockNumber, recordUri, ...}`. Every other method is
a stub that throws with a TODO comment: `payStream` → "v0.2+: Superfluid.callAgreement
(CFAv1.createFlow)"; `splitDistribute` → 0xSplits `distributeERC20` TODO; `escrowOpen` → Gnosis
Safe 2-of-3 deploy TODO; **`verify()` throws with a 5-step TODO** (MST traversal → anchor
contract query → Merkle proof) and would return `VerifyResult{included, anchoredAt?,
merklePath?, reason?}` once built.

This matters directly for `net-babiniku`: recurring subscriptions need `payStream`, the 80/20
creator split (ADR-2607062200) needs `splitDistribute`, and **confirming any payment actually
settled before honoring an entitlement needs `verify()`** — all three are unimplemented stubs
today. Only a one-off `pay()` transfer with no verification path exists.

## Decision

1. **No new chain/wallet is chosen by `net-babiniku`.** It consumes etzhayyim's existing rail
   exactly as decided: Base L2, USDC (EURC/JPYC where relevant), ERC-4337 Coinbase Smart Wallet
   with passkey signing, via `@etzhayyim/sdk`. `net-babiniku` never imports `viem`, holds a key,
   or calls a chain RPC directly — every chain interaction goes through the SDK, per
   ADR-2605211950's substrate boundary.

2. **Signing happens on the payer's device, never on `net-babiniku`'s server.** Because the
   payer's Coinbase Smart Wallet is passkey-based (WebAuthn), the payment UserOperation must be
   signed client-side, in the viewer's browser — the same "no server-held private key"
   invariant `orgs/etzhayyim/root/CLAUDE.md`'s substrate boundary table states explicitly
   ("Any platform-held private key ... is Prohibited"). This means `net-babiniku`'s Cloudflare
   Pages Function (the same server boundary the LLM chat call uses, ADR-2607070800) has the
   **opposite** role here from the chat feature: for chat, the API key must stay server-side
   and never reach the browser; for payment, the signing capability must stay in the payer's
   browser/device and **never** be proxied through `net-babiniku`'s server. `net-babiniku`'s
   client embeds `@etzhayyim/sdk`'s payer-facing flow directly; the server's only role is to
   *receive and verify a settlement receipt* before honoring an entitlement.

3. **A new lexicon, not a reuse of `com.etzhayyim.consent.capability`.** Health-data access
   consent and payment authorization are different shapes. This ADR proposes
   `net.babiniku.monetization.capability`, following the same signed/scoped/expiring/revocable
   pattern as karute's precedent but with payment-shaped fields:

   ```
   {version: 1,
    payerDid: DID,                      // the viewer's did:key from their Smart Wallet
    payeeDid: DID,                      // the creator's (or platform's, for tips) did
    purpose: "subscribe" | "tip" | "ppv",
    scope: {characterId: string, tier?: keyword, sceneId?: string},  // mirrors
                                          // babiniku.monetization's existing proposal shape
    amount: {asset: "USDC"|"EURC"|"JPYC", value: string},  // string, not float — matches
                                                             // PaymentReceipt's bigint-safe pattern
    recurrence?: {intervalDays: int},   // present only for :subscribe (drives payStream)
    issuedAt: ISO-8601, expiresAt: ISO-8601,
    constraints: {maxTotalValue?: string, revocable: bool},
    signature: {alg: "ed25519", value: base64, keyId: DID-verification-method}}
   ```

   Mirrors `babiniku.monetization`'s existing `{:kind :subscribe/:tip/:ppv, :character-id, ...}`
   proposal shape 1:1, so `review-proposal`'s existing HARD-hold logic doesn't need to change
   shape when a real capability eventually replaces `unprovisioned-capability`.

4. **A new vendor→etzhayyim consumption pattern is proposed, not assumed.** ADR-2605211950
   only worked out the etzhayyim→vendor (fiat progressive-enhancement) crossover. This ADR
   proposes the reverse: a vendor app calls `@etzhayyim/sdk` client-side for payer-signed
   settlement, then sends only the resulting `{txHash, recordUri}` receipt to its own server
   for `Etzhayyim.verify()` before honoring an entitlement — no consent-capability record
   needs to reach `net-babiniku`'s own Datomic-shaped storage beyond that receipt and its
   verification result. **This crossover shape is new** and, per this ADR's Status, should be
   reviewed by whoever owns ADR-2605211950/2605172100 before being treated as settled
   architecture — `net-babiniku` is not the right place to unilaterally decide an
   etzhayyim-wide pattern.

5. **`SettlementCapability` stays `UnprovisionedCapability` for all three proposal kinds.**
   Per the honest-default principle already governing `babiniku.monetization`
   (ADR-2607062200), and given `payStream`/`splitDistribute`/`verify` are all unimplemented
   stubs today: `:subscribe` needs `payStream` (unbuilt), the 80/20 split needs
   `splitDistribute` (unbuilt), and **any** entitlement needs `verify()` (unbuilt, "not-yet-
   anchored" is a defined-but-unreachable outcome). Even `:tip` — which could in principle use
   the one real, working `pay()` call — cannot safely unlock an entitlement without `verify()`
   existing to confirm the transfer happened. This ADR's design does not change the HARD-hold
   status quo; it exists so that when etzhayyim's own primitives mature, `net-babiniku` has a
   concrete, reviewed integration plan to switch on rather than a redesign-from-scratch.

## Consequences

- **No code changes accompany this ADR** (by explicit user direction) — it is a design-only
  artifact. `babiniku.monetization/unprovisioned-capability` remains the only implementation;
  every subscribe/tip/PPV proposal continues to HARD-hold.
- **Blocked on etzhayyim-side work**, not `net-babiniku`-side work: `@etzhayyim/sdk`'s
  `payStream`/`splitDistribute`/`verify` need to move from stub to implementation before any
  of this can be built, regardless of anything `net-babiniku` does.
- **The vendor→etzhayyim crossover pattern proposed in Decision #4 needs review from
  whoever maintains ADR-2605211950/2605172100** before `net-babiniku` (or any other vendor
  app) implements against it — this ADR proposes a shape, it does not ratify one.
- **`net.babiniku.monetization.capability` is a proposed lexicon, unimplemented anywhere.**
  If accepted, it would need its own signing implementation (client-side, likely inside
  whatever wallet-connection UI wraps `@etzhayyim/sdk`'s payer flow) — this ADR does not
  build that either.
- Follow-up, once etzhayyim's primitives exist: verify `net.babiniku.monetization.capability`'s
  field shapes against whatever `@etzhayyim/sdk` actually ships (this ADR's shape is a
  proposal, not a contract etzhayyim has agreed to).

## Alternatives Considered

1. **Pick our own chain/wallet stack for `net-babiniku`, independent of etzhayyim.** Rejected
   — directly violates ADR-2605211950's substrate-axis rule (decentralized primitives are
   etzhayyim-exclusive); would also duplicate real engineering (Base L2 integration, Paymaster,
   custody) etzhayyim has already built.
2. **Proxy the payer's signing through `net-babiniku`'s Cloudflare Function.** Rejected — this
   would require the server to hold or relay signing material for the payer's passkey-based
   Smart Wallet, violating the explicit "no platform-held private key" prohibition in
   `etzhayyim/root/CLAUDE.md`'s substrate boundary table. Signing must stay client-side, on the
   payer's own device.
3. **Reuse `com.etzhayyim.consent.capability` as-is instead of a new lexicon.** Rejected — its
   fields (query scope, redaction level, audit webhook) are shaped for health-data access
   consent, not value-transfer authorization; forcing payment semantics into that shape would
   be a worse fit than a small new lexicon following the same signed/scoped/expiring/revocable
   pattern.
4. **Treat `pay()`'s one working code path as enough to unlock `:tip` now** (since a one-off
   USDC transfer is the one real primitive that exists). Rejected — without `verify()`,
   `net-babiniku` would have no way to confirm a claimed payment actually settled on-chain
   before granting an entitlement, which is worse than the current honest HARD-hold, not
   better.
5. **Silently assume the vendor→etzhayyim crossover direction is already covered by
   ADR-2605211950's existing crossover clause.** Rejected — that clause is directionally
   etzhayyim-calls-vendor (fiat progressive enhancement); assuming it also covers
   vendor-calls-etzhayyim (crypto settlement) would be inventing an architectural decision
   this ADR isn't authorized to make unilaterally. Surfaced explicitly instead (Decision #4,
   Consequences).

## References

- `orgs/etzhayyim/root/90-docs/adr/2605172100-etzhayyim-payments-on-chain-only.md`
- `orgs/etzhayyim/root/90-docs/adr/2605211950-vendor-centralized-etzhayyim-decentralized-substrate-axis.md`
- `orgs/etzhayyim/root/90-docs/adr/2605231401-karute-consent-capability-iryo-bridge.md`
- `orgs/etzhayyim/root/20-actors/karute/actor-manifest.jsonld` (`ConsentCapability` precedent)
- `orgs/etzhayyim/root/20-actors/etzhayyim-sdk/src/{index.ts,pay.ts}` (`pay`/`payStream`/
  `payStreamStop`/`escrowOpen`/`escrowRelease`/`splitDistribute`/`verify` — implementation
  states as documented above)
- `orgs/etzhayyim/root/90-docs/adr/2607021800-etzhayyim-junbi-reserve-basket.md` (`junbi`
  reserve actor, the USDC/EURC/JPYC custody precedent)
- `orgs/etzhayyim/root/CLAUDE.md` (substrate boundary table — "no platform-held private key")
- `90-docs/adr/2607062200-net-babiniku-onlyfans-style-creator-monetization.md`
  (`babiniku.monetization`'s existing proposal shape and honest-default `UnprovisionedCapability`)
- `90-docs/adr/2607070800-net-babiniku-llm-backend-claude-sonnet-5.md` (the Cloudflare
  Pages Function server boundary referenced in Decision #2, contrasted with its opposite role
  here)