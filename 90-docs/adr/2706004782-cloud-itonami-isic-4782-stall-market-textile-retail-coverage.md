# ADR-2706004782: cloud-itonami ISIC 4782 stall/market textile, clothing and footwear retail coverage

- Status: accepted
- Date: 2026-07-16
- Wave: 2 (coordination/logistics/trade, ADR-2607121000)

## Context

`kotoba-lang/industry`'s `registry.edn` carries an ISIC Rev.5 class-level
entry `{:id "4782" ...}` -- "Retail sale via stalls and markets of
textiles, clothing and footwear" -- at `:maturity :spec` with a stale
placeholder `:repo`/`:business-id`
(`https://github.com/gftdcojp/cloud-itonami-G4782` /
`cloud-itonami-G4782`). Independently confirmed via `gh api` that
neither that stale placeholder repo nor the real target
(`cloud-itonami/cloud-itonami-isic-4782`) existed before this work --
this is a fresh scaffold, not an extension of prior work.

The live registry `:name` string for this entry was truncated (a known
~10% pre-existing seed-data bug in this registry, already observed and
fixed in several sibling entries, e.g. ISIC 4753's own de-truncation in
ADR-2695004753 and ISIC 4771's in ADR-2700004771): `"Retail sale via
stalls and markets of textiles, clothing an..."`. This ADR's own
registry edit de-truncates it to the full ISIC class name as part of the
same exact-block edit.

A separate, coarser-granularity `{:id "478" ...}` 3-digit group entry
(`"Retail sale via stalls and markets"`) already exists in the registry
as a distinct, redundant registry artifact and is deliberately left
untouched by this work.

ISIC 4782 is distinct from sibling apparel-adjacent retail classes
already implemented in this fleet: ISIC 4771 (Retail sale of clothing,
footwear and leather articles in *specialized stores* -- a fixed
storefront format, not a stall/market pitch) and ISIC 4781 (Retail sale
via stalls and markets of *food, beverages and tobacco* -- a different
product category on the same stall/market distribution channel).

## Decision

Publish `cloud-itonami/cloud-itonami-isic-4782` as a fresh OSS actor
repository mirroring the verified `cloud-itonami-isic-4771` module shape
module-for-module (`stallops.*` namespace in place of `apparelops.*`,
`stall-id` in place of `store-id`):

- **StallMarketAdvisor ⊣ StallMarketGovernor** -- a market-stall/
  street-market textile, clothing and footwear vending
  OPERATIONS-COORDINATION actor, built on `kotoba-lang/langgraph`
  (portable `.cljc`, langgraph-clj StateGraph, `intake -> advise ->
  govern -> decide -> commit | hold | request-approval`, real
  `interrupt-before #{:request-approval}` human-in-the-loop, not a stub).
- Closed four-op proposal allowlist, all `:effect :propose`:
  `:log-sales-record` (inventory/sale data logging),
  `:schedule-stall-operation` (stall placement/staffing scheduling),
  `:coordinate-supply-order` (textile/clothing/footwear
  inventory-procurement coordination), `:flag-quality-concern`
  (suspected-counterfeit item / defective-goods item / stall-market-
  permit concern flagging).
- Four HARD, permanent, un-overridable-by-human-approval governor
  checks: (1) **stall-unverified** -- the target market stall's
  registration must exist AND be independently `:registered?`/
  `:verified?` (a stall/market permit on file, independently confirmed)
  in the store before any proposal for it may commit or even escalate;
  (2) **vendor-unverified** -- for `:coordinate-supply-order` only, the
  proposal's own drafted `:vendor-id` must resolve to an independently
  registered/verified vendor record, a supply-chain
  counterparty-verification gate shared with sibling 47xx retail actors
  (mirroring ISIC 4771/4751/4719's own); (3) **effect-not-propose** --
  any `:effect` other than `:propose` is a hard block; (4)
  **scope-exclusion** (folds in op-not-allowed) -- permanently blocks any
  proposal touching directly finalizing a quality-dispute resolution
  (issuing a refund or replacement, voiding a sale, charging back a
  vendor, revoking or terminating a vendor's registration/contract) OR
  directly finalizing a counterfeit-authenticity determination (declaring
  an item counterfeit, certifying an item as genuine, resolving an
  authenticity claim) -- mirroring ISIC 4771's own counterfeit-
  authenticity-determination scope-exclusion, equally salient for a
  market-stall textile/clothing/footwear channel.
- Two ESCALATE (SOFT) gates, either forces human sign-off:
  `:flag-quality-concern` ALWAYS escalates (never a member of any
  phase's `:auto` set, at any phase -- two independent layers agree: the
  governor's own `always-escalate-ops` AND `stallops.phase`'s own phase
  table); a `:coordinate-supply-order` above a $500 domain-illustrative
  cost threshold also always escalates (set lower than ISIC 4771's $1000
  threshold -- market-stall vendors typically run smaller order volumes
  than a specialized storefront); low confidence also escalates.
- **This fleet's own known self-tripping bug class** -- a governor
  scope-exclusion term phrased as a bare noun (e.g. "refund", "dispute",
  "counterfeit", "permit") accidentally matching inside the mock
  advisor's own legitimate default proposal text and self-blocking the
  happy path -- is avoided from the start: every `scope-excluded-terms`
  entry in `stallops.governor` is phrased as the finalization/execution
  ACTION ("issue the refund", "declare the item counterfeit", "certify
  the item as genuine"), never a bare noun, in both English and Japanese.
  A dedicated regression test,
  `stallops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`,
  asserts all four default proposal generators (including
  `:flag-quality-concern`, whose own `:op` keyword literally contains
  the substring "quality-concern" and whose default rationale
  legitimately mentions the market-permit concern as a bare descriptive
  observation, never a finalization action) never trip
  `:scope-excluded` or `:op-not-allowed`.
- Staged rollout Phase 0 (read-only) -> Phase 1 (sales-record logging
  only, approval-gated) -> Phase 2 (+ stall-operation/supply-order
  proposals, approval-gated) -> Phase 3 (governor-clean, high-confidence,
  low-cost proposals auto-commit; quality concerns and high-cost supply
  orders always escalate regardless of phase).
- Append-only audit ledger for every log/schedule/order/flag decision
  (commit, hold, and approval-requested/granted/rejected facts).
- Fully portable `.cljc` with no JVM-only interop anywhere in `src/`
  (mock-only advisor; a real LLM would be swapped in behind the same
  `Advisor` protocol).

Full module set published: `deps.edn`, `blueprint.edn`
(`:itonami.blueprint/governor :stall-market-governor` -- distinct,
independently-named from sibling 47xx governors, e.g. ISIC 4771's
`:apparel-retail-governor` and ISIC 4751's `:textile-retail-governor`),
`LICENSE` (AGPL-3.0-or-later), `README.md`, `GOVERNANCE.md`,
`CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `SECURITY.md`,
`docs/business-model.md`, `docs/operator-guide.md`,
`src/stallops/{store,advisor,governor,phase,operation,sim}.cljc`,
`test/stallops/{advisor_test,governor_test,governor_contract_test,
phase_test,store_contract_test}.clj`.

58 tests / 170 assertions green (`clojure -M:test`), independently
re-verified against a fresh clone of `origin/main` after push
(commit `2d7d9a812eb2469190832f3c483244aaec86207a`); clj-kondo
`--fail-level error` 0 errors / 0 warnings; `clojure -M:run` demo driver
exercises the full happy path (phase-1 approval-gated commit, phase-3
auto-commits, a high-cost supply-order escalation, a quality-concern
escalation) plus all 5 HARD-hold scenarios (unregistered stall,
registered-but-unverified stall, unverified supply-order vendor, a
direct-actuation `:effect :commit` attempt, and a proposal drifted into
the permanently-excluded quality-dispute/counterfeit-determination
scope) end-to-end against the real langgraph-clj StateGraph, not a stub.

`kotoba-lang/industry`'s `registry.edn` `"4782"` entry is promoted from
`:spec` to `:implemented`: `:name` de-truncated to the full ISIC class
name, `:repo`/`:business-id` de-placeholdered from
`gftdcojp/cloud-itonami-G4782` -> `cloud-itonami/cloud-itonami-isic-4782`
/ `cloud-itonami-isic-4782`, `:operating-states` updated to
`[:intake :advise :govern :approve :commit :audit]` to match the actor
state machine (mirroring ISIC 4771/4781's own operating-states
convention for an approval-gated coordination actor); `:required-
technologies`/`:optional-technologies` left unchanged (already correct
in the pre-existing entry). The separate `{:id "478" ...}` 3-digit group
entry is left untouched.

## Consequences

- An independent market-stall textile/clothing/footwear vendor gets an
  auditable, forkable operations-coordination platform instead of
  surrendering sales/staffing/supply-order/quality-concern data to a
  closed back-office SaaS.
- The actor structurally cannot finalize a refund/replacement/chargeback/
  vendor-registration-revocation OR a counterfeit-authenticity
  determination on its own initiative -- those remain human-owned
  decisions, permanently, not a rollout milestone still to come.
- `kotoba.industry/maturity-summary`'s fleet-wide `:implemented` count
  advances by one from this promotion (recomputed live via
  `(kotoba.industry/maturity-summary)` against a freshly re-fetched
  `origin/main` `registry.edn` immediately before the `industry_test.clj`
  edit, not assumed -- see that test file's own dedicated
  `cloud-itonami-isic-4782-is-implemented` regression entry for the
  exact before/after count).
- Extending coverage (e.g. a stall-relocation-intake op or a
  shrinkage-observation check) is additive: add the op to the closed
  allowlist with its own HARD checks and tests, following the same
  independent-governor-re-verifies-against-the-actor's-own-records
  pattern this repo's four flagship checks already establish.

## References

- Repository: https://github.com/cloud-itonami/cloud-itonami-isic-4782
  (commit `2d7d9a812eb2469190832f3c483244aaec86207a` on `main`)
- Reference mirror: https://github.com/cloud-itonami/cloud-itonami-isic-4771
- Wave 2 plan: ADR-2607121000
  (`90-docs/adr/2607121000-cloud-itonami-global-isic-isco-reverse-toposort-plan.md`)
- Sibling de-truncation precedent: ADR-2695004753 (ISIC 4753), ADR-2700004771 (ISIC 4771)
- Sibling stall/market-channel precedent: ADR-2700004781 (ISIC 4781, stall/market food retail)
- Registry: `kotoba-lang/industry`, `resources/kotoba/industry/registry.edn`
  `"4782"` entry; test corroboration in `test/kotoba/industry_test.clj`
