# ADR-2607152400: cloud-itonami-isic-0230 (Gathering of non-wood forest products) coverage

## Status

Accepted. `cloud-itonami-isic-0230` built as a fresh scaffold (no prior
repo existed) and registered `:implemented` in the `kotoba-lang/industry`
registry.

## Context

ISIC 0230 (Gathering of non-wood forest products -- resin, cork, bark,
nuts, wild mushrooms, medicinal plants) sat at `:spec` in `kotoba-lang/
industry`'s registry with a stale `gftdcojp/cloud-itonami-A0230` repo
reference that was never published (verified: the repo does not exist on
GitHub). This is part of a smaller, stricter re-run of the ISIC fleet
build after a prior 18-agent haiku batch had a measured 61% defect rate
(empty implementations, missing modules, false "all green" test claims).
This build used a single agent, a more capable model, and mandatory
independent re-verification (fresh clone, raw `clojure -M:test` output)
before any promotion claim.

`cloud-itonami-isic-0210` (Silviculture and other forestry activities) is
this fleet's closest domain analog (forestry operations coordination,
itself independently re-verified at 64 tests / 160 assertions green) and
was mirrored closely: same module shape (`registry`/`store`/`advisor`/
`governor`/`phase`/`operation`/`sim`), same langgraph-clj StateGraph
wiring, same "ground truth, not self-report" governor discipline, same
Phase 0->3 rollout shape, adapted to the non-wood-forest-product-gathering
domain instead of silviculture stand/harvest management.

## Decision

### Decision 1: Non-wood-forest-product gathering OPERATIONS COORDINATION, not direct field-crew dispatch or land-use/permit authority

`cloud-itonami-isic-0230` is `nwfp.*` (`src/nwfp/`): a `NwfpAdvisor`
(sealed intelligence node) proposes only; an independent `NWFP Gathering
Coordination Governor` (`nwfp.governor`) validates every proposal against
domain rules re-derived from `nwfp.registry`'s pure functions and `nwfp.
store`'s SSoT before anything commits. The actor NEVER dispatches field
crews, NEVER physically gathers a product, and NEVER authorizes gathering
beyond a site's own independently-verified permitted harvest quota --
those remain the exclusive authority of the human gathering-operations
manager / permit-issuing authority.

Closed four-op allowlist (all `:effect :propose`):
- `:log-harvest-record` -- quantity/species/location gathering data logging
- `:schedule-field-operation` -- gathering-trip scheduling proposal
- `:flag-sustainability-concern` -- surface an over-harvest/protected-
  species concern; ALWAYS escalates
- `:order-supplies` -- equipment/permit-fee procurement proposal

### Decision 2: HARD invariants (no override)

1. **site-not-verified** -- a gathering-site/permit record must be
   independently verified/registered in the SSoT (`nwfp.registry/site-
   verified?`) before a field operation may be scheduled against it --
   never trust the advisor's own rationale about verification status.
2. **not-propose-effect** -- the request's own `:effect` must always be
   `:propose`; any other value is a mis-wired/compromised caller trying
   to bypass proposal-only mode -- HARD, unconditional, evaluated first.
3. **dispatch-blocked** -- the proposal's own `:effect` must be one of
   the four closed propose-shaped effects
   (`#{:site/upsert :field-operation/schedule :sustainability-concern/
   flag :supply-order/propose}`); anything else (a hallucinated
   `:crew/dispatch` or `:harvest/execute`) is a permanent, unconditional
   block -- the "direct field-crew dispatch" scope boundary.
4. **harvest-quota-exceeded** -- for `:schedule-field-operation`,
   independently recompute (from the site's own permanent
   `:harvest-quota-kg`/`:quota-used-kg` fields, never the proposal's own
   claim) whether the proposed `:quantity-kg` would push the site's
   cumulative harvest past its own permitted quota -- a permanent,
   unconditional block, mirroring `cloud-itonami-isic-0210`'s own
   `harvest-finalize-blocked`/`stand-immature-for-harvest` HARD checks
   in shape.

Plus the closed op-allowlist (`unknown-op`), the double-schedule guard
(`already-scheduled`), the closed product-category set
(`invalid-product-category`), and the independent supply-order total
recompute (`order-total-mismatch`) -- all HARD, all re-derived from
ground truth, never from the proposal's own self-report.

### Decision 3: Dual-escalation shape

`:flag-sustainability-concern` ALWAYS escalates to a human, regardless of
confidence (two independent layers agree: it is never a member of any
`nwfp.phase` phase's `:auto` set, and `nwfp.governor`'s high-stakes set
always includes `:coordination/sustainability-concern`). `:order-supplies`
whose independently-recomputed total exceeds `nwfp.registry/supply-order-
cost-threshold` (5000.0) also escalates rather than auto-commits, even
when otherwise clean.

### Decision 4: Phase 0->3 rollout, `:log-harvest-record` is the only auto-eligible op

Mirrors `cloud-itonami-isic-0210`'s own phase table exactly in shape:
phase 3 (`supervised-auto`) is the only phase where any op may
auto-commit, and `:log-harvest-record` (no physical/financial risk) is
the ONLY op ever in a phase's `:auto` set. `:schedule-field-operation` is
deliberately absent from every phase's `:auto` set, including phase 3 --
a permanent structural fact, not a rollout milestone still to come.

## Consequences

(+) ISIC 0230 gains a real, independently-tested governed-actor coverage
entry instead of a stale unpublished-repo reference.

(+) The build closely mirrors the fleet's most recently independently
re-verified domain analog (`cloud-itonami-isic-0210`), reducing the risk
of the missing-module / false-green-claim defect pattern that motivated
this smaller, stricter batch.

(+) Four HARD invariants (site-not-verified, not-propose-effect,
dispatch-blocked, harvest-quota-exceeded) bound scope against direct
field-crew dispatch and over-quota harvesting, matching the domain's own
"coordination, not control" premise.

(-) Still a simulation/proposal layer -- no integration with real
permitting/land-management databases (GIS, species-population models,
regulatory reporting). Field-crew dispatch and physical gathering remain
human-controlled via external channels.

(-) Single `MemStore` backend only; a Datomic/kotoba-server-backed `Store`
is deferred (no jurisdiction-scoped parity requirement currently drives a
second backend, per `nwfp.store`'s own ns docstring).

## Verification

- Fresh scaffold at `github.com/cloud-itonami/cloud-itonami-isic-0230`,
  built and pushed from a scratch clone (never the shared `orgs/kotoba-
  lang/industry` or `orgs/cloud-itonami/*` checkouts).
- `clojure -M:test` from an independent, freshly-cloned checkout (with
  `kotoba-lang/langgraph` and `kotoba-lang/langchain` as sibling
  `:local/root` checkouts) at commit `db0a590`:

  ```
  Ran 66 tests containing 161 assertions.
  0 failures, 0 errors.
  ```

- `clojure -M:lint` (clj-kondo): 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-effect,
  unknown-op, site-not-verified, harvest-quota-exceeded, already-
  scheduled, invalid-product-category, order-total-mismatch) plus the
  over-threshold `order-supplies` ESCALATE (not HOLD) case -- confirmed
  by inspecting the raw demo output, not merely trusting a summary.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*`.
- `kotoba-lang/industry`'s `"0230"` registry entry promoted `:spec` ->
  `:implemented` via an exact-text in-place edit of the literal registry
  block (never a wholesale reserialize), landed via GitHub API
  server-side merge; `industry_test.clj`'s `:implemented` count assertion
  bumped to match the live recomputed count; full `clojure -M:test`
  suite re-run green post-edit and again from an independent post-merge
  fresh clone (see PR/merge commit referenced in this repo's own commit
  history for the exact SHAs).
