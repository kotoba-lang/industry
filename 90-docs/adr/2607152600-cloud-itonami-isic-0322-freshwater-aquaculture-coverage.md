# ADR-2607152600: cloud-itonami-isic-0322 (Freshwater aquaculture) operations-coordination actor

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-0321 (Marine aquaculture -- the near-identical
domain analog, reference implementation this actor mirrors),
cloud-itonami-isic-0311 (Marine fishing -- documents the same
`g/compile` vs `g/compile-graph` naming bug found again independently
here), ADR-2607011000 (ISIC section coverage), the `kotoba-lang/industry`
registry's `"0322"` catalog entry

## Context

`cloud-itonami/cloud-itonami-isic-0322` did not exist prior to this ADR
(verified via `gh repo view` before starting -- no failed-prior-attempt
artifacts to reconcile, unlike the `0311` incident this fleet is still
recovering from). ISIC Rev.5 0322 (Freshwater aquaculture) is the
inland/land-based counterpart to `0321` (Marine aquaculture): pond, tank
and raceway facilities rather than net-pens and maritime zones. This ADR
implements it following the same careful, smaller-batch, capable-model +
mandatory-verification protocol as the other actors in this rollout.

## Decision

Implement `cloud-itonami-isic-0322` as a freshwater-aquaculture-farm
OPERATIONS COORDINATION actor, mirroring `cloud-itonami-isic-0321`'s
verified pattern in shape but with pond/tank/raceway and water-quality
concepts replacing marine net-pen and maritime-zone concepts:

1. **`freshwater.governor`** -- independent compliance layer, hard rules:
   - pond/facility must be verified and `:status :active` before any
     action (`:pond-not-found` / `:pond-inactive`)
   - proposal `:effect` must match the op's one legitimate effect
     (`:effect-mismatch`)
   - all effects must be `:propose` only (`:non-propose-effect`)
   - a CLOSED op allowlist (`freshwater.governor/allowed-ops`) is the
     only legitimate set of proposals: `:log-pond-record`,
     `:schedule-farm-operation`, `:flag-health-concern`,
     `:order-supplies` -- this is enforced by an explicit
     `unknown-op-violations` hard check (`:unknown-operation`), not left
     as an implicit gap in the `op->effect` lookup the way the `0321`
     reference leaves it (there, an op absent from `op->effect` silently
     skips the effect-mismatch check rather than being rejected)
   - `:administer-treatment`, `:control-feeding-equipment` and
     `:control-harvest-equipment` are permanently, structurally blocked
     (`:blocked-operation`), no human override -- direct treatment
     administration or direct feeding/harvest-equipment actuation is
     never a legitimate proposal
   - `:pond-id`, `:registration-number`, `:pond-status` are forbidden
     patch fields (`:forbidden-field`)
   - soft escalations (human sign-off required, not a rejection):
     `:flag-health-concern` always escalates immediately; supply orders
     >= 10,000 units cost escalate; advisor confidence < 0.6 escalates
2. **`freshwater.store`** -- `Store` protocol + `MemStore`: pond lookup,
   `log-pond-record!`, `schedule-farm-operation!`, `flag-health-concern!`,
   `order-supply!`, `audit-log` -- append-only per-pond audit trail.
3. **`freshwater.llm-advisor`** -- `Advisor` protocol + mock impl; no
   external service calls in the base module.
4. **`freshwater.operation`** -- langgraph-clj `OperationActor` StateGraph:
   `intake -> advise -> govern -> decide -> finalize`, one graph run =
   one auditable operation, checkpointed, no unbounded loop.
5. **`freshwater.sim`** -- demo driver that actually invokes the compiled
   graph end-to-end across four scenarios (commit / escalate / hold x2),
   not just a printed summary.

### What this actor does NOT do

Explicitly documented (README, ADR, `freshwater.operation` docstring): no
direct feeding-equipment operation, no direct harvest-equipment operation,
no treatment administration, no facility identity/registration edits.
These remain the veterinarian's / farm operator's exclusive human
authority, permanently, with no actor or human-approval override path.

### Two real bugs found and fixed relative to the 0321 reference

While porting `aquaculture.operation`, I independently re-confirmed the
`g/compile` vs `g/compile-graph` naming bug already documented in
ADR-2607151700 (`cloud-itonami-isic-0311`) -- `kotoba-lang/langgraph`'s
actual public fn is `compile-graph`, and `0321`'s own `operation.cljc`
still calls the nonexistent `compile`.

I additionally found a second, previously undocumented latent bug: `0321`
(and `0311`, which ported the same shape) never call `g/set-entry-point`,
so the compiled graph has no edge from `:langgraph/start` to `:intake`.
Invoking such a graph throws `"No entry point — call set-entry-point"`.
This was never caught in either prior repo because their `sim.cljc` only
prints a static summary and never calls `g/invoke`. `freshwater.operation`
adds the missing `(g/set-entry-point :intake)` call, and
`freshwater.sim` actually drives the compiled graph end-to-end (verified
by running `clojure -M:dev:run` and observing all four scenarios resolve
to the expected disposition), plus a new `freshwater.operation-test`
integration-test namespace invokes the graph under `clojure -M:test` so
this path has real, non-manual coverage going forward.

## Verification

- `cloud-itonami-isic-0322`: `clojure -M:test` -- raw final line:
  `Ran 23 tests containing 34 assertions.` / `0 failures, 0 errors.`
  (governor contract: pond verification x2, effect integrity,
  propose-only, all three blocked ops individually, unknown-op
  closed-allowlist rejection, forbidden fields, both escalation triggers,
  low-confidence escalation, clean-proposal-passes for two different ops;
  store contract: pond lookup, record logging, audit trail, operation
  scheduling, supply ordering; operation integration: commit / escalate /
  hold x2 driven through the actual compiled graph).
- `clojure -M:lint` -- 0 errors, 0 warnings.
- `clojure -M:dev:run` -- runs cleanly end-to-end through all four
  scenarios via `g/invoke`, printing the expected disposition for each.
- Independently re-verified: fresh `git clone --depth 1` into a new
  scratch directory after push (with `kotoba-lang/langgraph` and
  `kotoba-lang/langchain` cloned fresh as `../../kotoba-lang/*` siblings),
  re-ran `clojure -M:test` against the clean clone -- same green result,
  `git merge-base --is-ancestor` confirmed the pushed commit is an
  ancestor of `origin/main` for the actor repo.
- Commit `d9e3f7dd7b5db8c0dfb1adec8ac5aee57ebea948` pushed directly to
  `cloud-itonami-isic-0322`'s newly-created `main` (initial commit;
  `gh repo create --source=. --push`).

## Consequences

(+) `cloud-itonami-isic-0322` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor.
(+) The closed op-allowlist is enforced by an explicit governor rule
(`:unknown-operation`) rather than relying on the implicit
`op->effect`-lookup gap the `0321` reference has -- a strictly stronger
invariant than the reference it mirrors.
(+) The `g/set-entry-point` gap is fixed and covered by a real
integration test (`freshwater.operation-test`) that actually invokes the
graph, closing a blind spot both `0321` and `0311` still carry (their own
`sim.cljc` still only prints a summary; fixing those is out of this ADR's
scope).
(+) `kotoba-lang/industry` registry `"0322"` entry updated to
`:maturity :implemented` (see registry commit referenced in that repo's
own history).
(-) No jurisdiction-specific regulatory-fact catalog (permit/registration
authority citations) is implemented; this is explicitly out of scope for
the coordination-actor shape this ADR follows, matching `0321`/`0311`.
