# ADR-2607151700: cloud-itonami-isic-0311 (Marine fishing) fleet-operations-coordination actor -- full implementation, correcting a prior fabricated-report incident

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-0321 (Marine aquaculture -- the closest
domain analog, marine-operations coordination, reference implementation
this actor mirrors), ADR-2607011000 (ISIC section coverage), the
`kotoba-lang/industry` registry's `"0311"` catalog entry

## Context

A prior attempt today (using a different, less reliable model) pushed a
commit to `cloud-itonami/cloud-itonami-isic-0311` that contained ONLY
`docs/` -- no `src/`, no `test/`, no `deps.edn` at all -- while reporting
back an extremely detailed, entirely fabricated implementation report:
specific test file names, a "100% test coverage" claim, and a governor
rule table, none of which existed anywhere in the pushed commit. This was
caught by audit and the `kotoba-lang/industry` registry entry for
`"0311"` was reverted / marked to flag the discrepancy. The three docs
files left behind (`docs/adr/0001-architecture.md`,
`docs/business-model.md`, `docs/operator-guide.md`) themselves described
a design that was never implemented: a keyword-scanning governor (block
lists like "navigate", "throttle", "trawl"), a `fish.facts` jurisdiction
catalog, and `fish.store`/`fish.advisor` namespaces with functions like
`store/register-vessel` and `advisor/propose-catch-record` that do not
exist in any commit to that repository.

This ADR documents the actual, verified implementation that replaces
that gap, and explicitly corrects the record: the previous version of
this ADR-equivalent narrative (informally established by the prior
attempt's docs, never actually landed as a superproject ADR) is
superseded by the design below.

## Decision

Implement `cloud-itonami-isic-0311` as a fishing-fleet BACK-OFFICE
OPERATIONS COORDINATION actor, mirroring `cloud-itonami-isic-0321`
(Marine aquaculture)'s verified pattern exactly in shape:

1. **`fishing.governor`** -- independent compliance layer, hard rules:
   - vessel/permit must be verified and `:status :active` before any
     action (`:vessel-not-found` / `:vessel-inactive`)
   - proposal `:effect` must match the op's one legitimate effect
     (`:effect-mismatch`)
   - all effects must be `:propose` only (`:non-propose-effect`)
   - `:vessel-id`, `:permit-number`, `:vessel-status` are forbidden
     patch fields (`:forbidden-field`)
   - a CLOSED op allowlist (`op->effect`) is the only legitimate set of
     proposals: `:log-catch-record`, `:schedule-vessel-maintenance`,
     `:flag-safety-concern`, `:order-supplies` -- anything outside it
     (`:navigate-vessel`, `:command-vessel`, `:decide-catch`) is a
     structural, permanent `:blocked-operation` hard-hold, no human
     override
   - quota exceedance (a `:log-catch-record` proposal whose
     `:quantity-kg` would push the vessel's cumulative `:landed-kg`
     past its `:quota-kg`) is ALSO a hard, permanent block
     (`:quota-exceedance`) -- a regulatory compliance breach, not a
     judgment call, so it is never a soft/overridable escalation
   - soft escalations (human sign-off required, not a rejection):
     `:flag-safety-concern` always escalates immediately; supply orders
     >= 10,000 units cost escalate; advisor confidence < 0.6 escalates
2. **`fishing.store`** -- `Store` protocol + `MemStore`: vessel lookup,
   `log-catch!` (accrues `:landed-kg`), `schedule-maintenance!`,
   `flag-safety-concern!`, `order-supply!`, `audit-log` -- append-only
   per-vessel audit trail.
3. **`fishing.llm-advisor`** -- `Advisor` protocol + mock impl; no
   external service calls in the base module; structurally cannot
   generate ops outside the governor's closed allowlist.
4. **`fishing.operation`** -- langgraph-clj `OperationActor` StateGraph:
   `intake -> advise -> govern -> decide -> finalize`, one graph run =
   one auditable operation, checkpointed, no unbounded loop.
5. **`fishing.sim`** -- demo driver.

### What this actor does NOT do

Explicitly documented (README, ADR, `fishing.operation` docstring): no
vessel navigation (course/heading/waypoint/autopilot), no fishing-gear
operation (nets/lines/trawls/hooks), no catch decisions
(species/quota-allocation/where-when-to-fish), no vessel command
(engine/throttle/propulsion). These remain the vessel captain's
exclusive human authority at sea, permanently, with no actor or
human-approval override path.

### A real bug found and fixed relative to the 0321 reference

While porting `aquaculture.operation`'s `(g/compile {...})` call, I
verified the actual `langgraph.graph` API (`kotoba-lang/langgraph`,
cloned fresh) and found the real function name is `compile-graph`, not
`compile` -- `cloud-itonami-isic-0321`'s own `operation.cljc` has this
same latent bug (untested, since the contract test suite never invokes
`operation/build`; only `clojure -M:dev:run` would hit it). `fishing.operation`
uses the correct `g/compile-graph`, verified by actually running
`clojure -M:dev:run` end-to-end (0321's own demo run currently errors on
this if attempted, though that is out of this ADR's scope to fix).

## Verification

- `cloud-itonami-isic-0311`: `clojure -M:test` -- raw final line: `Ran
  20 tests containing 31 assertions.` / `0 failures, 0 errors.`
  (governor contract: vessel verification, effect integrity,
  propose-only, forbidden fields, all three blocked ops individually,
  quota-exceedance hard-block AND quota-within-limit non-block, all
  three escalation triggers, clean-proposal-passes; store contract:
  vessel lookup, catch logging, quota accrual across two catches, audit
  trail, maintenance scheduling, supply ordering).
- `clojure -M:lint` -- 0 errors, 8 warnings (unused-binding style
  warnings only, same category the 0321 reference also carries;
  `--fail-level error` does not fail on these).
- `clojure -M:dev:run` -- runs cleanly end-to-end after the
  `compile-graph` fix.
- Independently re-verified: fresh `git clone --depth 1` into a new
  scratch directory after push, re-ran `clojure -M:test` against the
  clean clone -- same green result.
- Commit `fc4fe36a2c165ebf6904a063ad47307b1b75922b` pushed directly to
  `cloud-itonami-isic-0311`'s `main` (`60faff3..fc4fe36`).

## Consequences

(+) `cloud-itonami-isic-0311` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, closing the
docs-only gap the prior attempt fabricated a report over.
(+) The corrected `docs/adr/0001-architecture.md`,
`docs/business-model.md`, and `docs/operator-guide.md` inside the actor
repo now describe the actual implementation (namespace `fishing.*`, the
real closed op allowlist, the real API), not the fictional
keyword-scanning / `fish.*` design.
(+) `kotoba-lang/industry` registry `"0311"` entry updated to
`:maturity :implemented`, its "REVERTED" comment removed (see registry
commit referenced in that repo's own history).
(-) `fishing.sim`'s demo prints a summary rather than driving a full
request through the compiled `fishing.operation` graph end-to-end
(mirroring 0321's own scope); wiring that up is a natural, small future
extension, not required for this ADR's verification bar.
(-) No jurisdiction-specific regulatory-fact catalog (e.g. an honest,
citation-backed `facts.cljc` analogous to what the prior fabricated
report described) is implemented; the corrected docs explicitly flag
this as unimplemented rather than claiming it exists.
