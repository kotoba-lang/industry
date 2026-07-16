# ADR-2607142000: `cloud-itonami-isic-6493` deployed live to `factoring.murakumo.cloud`

- Status: Accepted (2026-07-14)
- Related: ADR-2607141700 (this superproject's own record of building
  `cloud-itonami-isic-6493`, the Factoring-LLM ⊣ Factoring Governor
  actor this ADR deploys, unchanged in its governed core);
  `cloud-itonami-isic-6493/docs/adr/0002-cloudflare-worker-deployment.md`
  (the authoritative repo-level deployment record -- this superproject
  ADR records the fleet-level context only); ADR-2606272330
  (`murakumo.cloud`'s own zone/registration history); `gftdcojp/
  cloud-murakumo-fleet` (the live production Cloudflare Worker at
  `api.murakumo.cloud` that served as this deployment's structural
  template, read in full before writing anything)

## Context

Following ADR-2607141700's registration of `cloud-itonami-isic-6493`
(Factoring activities, ISIC Rev.5 6493) as an `:implemented` actor, the
owner asked to deploy it to `murakumo.cloud` "so it actually runs as a
business." `murakumo.cloud` already hosts two live pieces: the apex/
`www` static SPA (the unrelated `cloud-murakumo` GPU-scheduler product,
untouched by this ADR) and `api.murakumo.cloud`, a real production
Cloudflare Worker (`gftdcojp/cloud-murakumo-fleet`) that served as the
template for this deployment's shape: a pure, JVM-testable `(store,
request) -> response` handler is the reference contract; an async
translation layer runs it in the Worker runtime.

## Decision

1. Added `worker/` inside `cloud-itonami-isic-6493` (not a split repo --
   no existing convention forces one), containing `routes.cljc`
   (pure sync handler, wraps `factoring.operation`'s existing langgraph-
   clj StateGraph UNCHANGED rather than reimplementing its business
   logic), `worker.cljs` (async Cloudflare translation: CORS, a real
   store-round-trip `/health` check, Bearer-token auth), `kv_store.cljs`
   (hydrate-compute-persist: the WHOLE book round-trips through one
   Cloudflare KV key as a `pr-str`'d EDN snapshot, a deliberate
   divergence from the template's per-key streaming op-map -- see the
   repo-level ADR-0002 for the full reasoning, in short: this actor's
   HARD checks aggregate across multiple stored records, and re-
   deriving that logic a second time as hand-written async code would
   risk drift from the JVM-tested version), `wrangler.toml` (a NEW,
   additive `factoring.murakumo.cloud` custom-domain route -- does not
   touch or modify the apex site or `api.murakumo.cloud`'s own
   Worker/zone config), `shadow-cljs.edn`/`package.json`/`deps.edn` (cljs
   -> ESM build), and `test/routes_test.clj` (JVM tests for the HTTP-API
   contract, run against `factoring.store/seed-db`).
2. `factoring.store` gained four small, additive functions
   (`snapshot`/`from-snapshot`/`empty-db`/`empty-state`) and one new
   protocol method (`register-funder!`, implemented on both `MemStore`
   and `DatomicStore`) -- the governed core's shape (the `Store`
   protocol, `factoring.governor`/`factoring.operation`/`factoring.
   phase`) is otherwise UNCHANGED from ADR-2607141700.
3. **Public transparency surface, unauthenticated by design**: `GET
   /health`, `/fee-schedule`, `/solvency/attestation(s)`, `/funders`
   need no credential -- gating them would defeat the whole anti-
   Zentoshin purpose ADR-2607141700 built (see that ADR's Context
   section for the 全東信/Zentoshin case). Every other route requires
   the `FACTORING_API_KEY` bearer token, and -- deliberately unlike
   `cloud-murakumo-fleet`'s own fail-OPEN precedent -- fails CLOSED
   (503) if that secret is not configured, given this actor's gated
   routes include actuation decision points.
4. **Human-in-the-loop over a single-round-trip HTTP API**:
   `factoring.operation`'s `interrupt-before` models human sign-off as
   a separate resume call; with no separate approval UI, an
   authenticated caller's own request to a gated actuation route IS
   that sign-off (`routes.cljc/run-op!` auto-resumes an `:escalate`
   disposition within the same call). **HARD violations still hold,
   un-overridably** -- this collapses only the SOFT confidence/
   actuation-approval gate, not the governor itself.
5. **Live-smoke-tested before landing, catching two real bugs**: an
   empty-POST-body crash (`.json()` on a bodyless action route), and a
   JSON-string-vs-Clojure-keyword mismatch on `:debtor-risk-tier` that
   silently broke the fee-schedule exact-match check forever (JSON has
   no keyword type; `js->clj :keywordize-keys true` only keywordizes
   map KEYS, not values). Both fixed, regression-tested (`worker/test/
   routes_test.clj`), and re-verified live against
   `https://factoring.murakumo.cloud` -- a full lifecycle (funder
   registration, intake, verify, underwrite, a stale-attestation HOLD,
   a solvency attestation publish, **actuation 1 [advance]**, collect,
   **actuation 2 [settle]**, a double-advance HOLD) was curled end-to-
   end and the resulting audit ledger (`GET /ledger`) read back and
   confirmed to match exactly. The test data (one funder, two
   receivables) was then deleted from the live KV
   (`wrangler kv key delete "book" --remote`), so this deployment
   starts from a genuinely empty book, not fixture data.
6. `FACTORING_API_KEY` set via `wrangler secret put` (never committed)
   and mirrored in 1Password (`gftdcojp` vault, item "cloud-itonami-
   isic-6493 FACTORING_API_KEY (factoring.murakumo.cloud)") for operator
   recovery.

## Honesty boundary -- non-negotiable, stated plainly

This deployment makes the GOVERNED DECISION software live, callable and
durable (a persistent, KV-backed audit ledger) at a real HTTPS URL. It
does **not**, and must **not**, wire any real bank transfer / payment
rail, real funder capital, real KYC/AML provider, or claim any real
money moves through it. `:advance/fund` and `:reserve/settle` remain
governed, audited **decision points**: an authorized caller hitting
those endpoints gets a real, audited, HARD-check-enforced commit/hold
disposition and ledger entry -- but no real currency moves anywhere,
because no real bank/payment integration is attached, and this
deployment does not fabricate one (no fake payment-processor call, no
pretend wire transfer, no invented funder API). The value delivered is
a live, production, governed decision+transparency service that a real
licensed operator could point real capital and real banking rails at --
the governance/audit/transparency machinery is fully live and real; the
money-movement backend is intentionally not attached and remains out of
scope. Every sibling actor's README already carries the equivalent
disclaimer ("does not itself hold a license... whoever deploys and
operates a live instance supplies the real KYC/banking integration");
this ADR states the same boundary plainly for the live deployment
specifically, per explicit instruction, because "deployed and live" is
exactly the point at which that boundary is easiest to blur by accident.

## Consequences

- (+) `cloud-itonami-isic-6493` is the first actor in this fleet's
  `cloud-itonami-isic-*` line (to this build's knowledge, verified only
  against `cloud-murakumo-fleet`'s own precedent, not audited against
  every sibling) deployed live to a real HTTPS URL with a durable,
  publicly-queryable transparency surface, rather than remaining a JVM-
  only demo.
- (+) Two real bugs were caught and fixed by actually exercising the
  live deployment end-to-end, not by code inspection alone -- direct
  evidence the live-smoke-test requirement was substantive, not
  performative.
- (+) `worker/test/routes_test.clj` (15 tests / 43 assertions) locks in
  both fixes and the whole HTTP-API contract on the JVM, against the
  identical `factoring.operation`/`factoring.governor` logic
  ADR-2607141700's own 68-test suite already covers.
- (-) The KV eventual-consistency race window documented in the repo-
  level ADR-0002 is accepted, not solved, for this R0 -- a real high-
  volume deployment needs a Durable Object/D1 writer.
- (-) No real LLM advisor is wired (the deployed advisor is the SAME
  deterministic mock the JVM suite exercises) -- correctness/safety
  comes from the independent governor recompute, not advisor
  determinism, so this is an honest, safe choice, not a governance
  shortcut; wiring a real advisor is separately-scoped future work.
- Repo-wide: 83 tests / 328 assertions (68/285 core actor unchanged +
  15/43 new worker HTTP-API suite), lint-clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Deploy to a new/different domain instead of `murakumo.cloud` | ❌ | Explicit instruction: `factoring.murakumo.cloud`, matching the `api.murakumo.cloud` naming convention already established there |
| Wire a real bank/payment rail so the actuation endpoints move real money | ❌ (will not build) | Explicit, non-negotiable honesty boundary -- see above |
| See `cloud-itonami-isic-6493/docs/adr/0002-cloudflare-worker-deployment.md` for build-level decisions | -- | (hydrate-compute-persist KV pattern, fail-closed auth, admin-vs-governed funder registration, etc.) |

## References

- ADR-2607141700 (`cloud-itonami-isic-6493`'s own build)
- `cloud-itonami-isic-6493/docs/adr/0002-cloudflare-worker-deployment.md`
  (the authoritative deployment record)
- `cloud-itonami-isic-6493/worker/README.md`
- Live URL: `https://factoring.murakumo.cloud`
