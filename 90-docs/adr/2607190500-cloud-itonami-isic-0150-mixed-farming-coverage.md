# ADR-2607190500: cloud-itonami-isic-0150 (Mixed farming) operations-coordination actor -- fresh scaffold, full implementation

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: cloud-itonami-isic-0141 (Raising of cattle and buffaloes --
the herd-side reference module shape this actor mirrors, independently
re-read in full before use), cloud-itonami-isic-0111 (Growing of cereals
except rice -- the crop-side reference module shape this actor mirrors,
independently re-read in full before use), the `kotoba-lang/industry`
registry's `"0150"` catalog entry (previously `:spec` with a placeholder
`gftdcojp/cloud-itonami-A0150` repo link that was never populated)

## Context

`kotoba-lang/industry`'s registry carried a `"0150"` entry at `:maturity
:spec` pointing at `https://github.com/gftdcojp/cloud-itonami-A0150` -- a
placeholder repo link with no actual implementation behind it (no source,
no tests; confirmed 404 via `gh api` before any work began). This is part
of an ongoing careful, smaller-batch rollout (one ISIC class per agent, a
capable model, mandatory verification) after a prior 18-agent haiku batch
produced a 61% defect rate on other ISIC classes; 54+ consecutive agents
on this stricter protocol had all succeeded before this one. This ADR
covers ISIC 0150 only. Before any work began, the registry entry's
identity (`{:id "0150" :name "Mixed farming"}`) was independently verified
against a fresh clone, per this fleet's caution against ID/name mismatches
(prior agents in this fleet had mislabeled 0892 as salt when it was
actually peat, and 0144 as swine when it was actually sheep/goats) --
confirmed correct, no mismatch. No prior `cloud-itonami-isic-0150` repo
existed under the `cloud-itonami` org (confirmed 404 via `gh api`).

ISIC Rev. 4 0150 ("Mixed farming") is structurally different from every
other agriculture ISIC class implemented so far in this fleet: it is
defined by combining crop growing AND animal raising on the same
operation where **neither activity accounts for >=66% of the operation's
standard gross margin** (crossing that threshold reclassifies the
operation under the dominant single-activity class instead -- 011x for
crop-dominant, 014x for livestock-dominant). A domain design that only
covers one side (as every prior single-activity ISIC 01xx actor in this
fleet does) would misrepresent the class.

## Decision

Scaffold `cloud-itonami/cloud-itonami-isic-0150` as a mixed-farming
OPERATIONS COORDINATION actor (not direct field/animal-handling equipment
operation or agronomic/veterinary-decision authority), mirroring
`cloud-itonami-isic-0141`'s (`cattleops.*`) and `cloud-itonami-isic-0111`'s
(`cerealops.*`) verified module shapes module-for-module (`facts`/
`registry`/`store`/`advisor`/`governor`/`phase`/`operation`/`sim`,
`deps.edn`/`blueprint.edn`/README/GOVERNANCE/CODE_OF_CONDUCT/CONTRIBUTING/
SECURITY), but with the domain and closed op set deliberately BROADENED
under a single `mixedfarmops` namespace to span both the crop-field side
and the herd side of one operation, rather than forking into two actors:

1. **`mixedfarmops.facts`** -- supply-category cost thresholds spanning
   BOTH crop inputs (seed, fertilizer) and livestock inputs (feed,
   veterinary-supply), plus shared equipment; representative crop and
   species classification catalogs (not exhaustive).
2. **`mixedfarmops.registry`** -- pure, independent verification
   predicates: `cost-exceeds-threshold?`, `yield-non-positive?` (crop
   side), `herd-count-non-positive?` (herd side), `confidence-below-
   floor?`. Two separate non-positive checks rather than one shared
   generic "quantity" check, because a mixed-farming record may carry
   either metric, both, or neither, and the Governor must be able to
   independently reject a bad yield without being blinded by a valid
   herd count (or vice versa) -- see the Governor design below.
3. **`mixedfarmops.store`** -- `Store` protocol + in-memory `MemStore`
   keyed on `farm-id` (the mixed-operation's own registered identity, not
   a "facility" or "field" -- deliberately renamed from both parent
   actors' terminology since a mixed farm is neither pure ranch nor pure
   field operation).
4. **`mixedfarmops.advisor`** -- `MixedFarmAdvisor`, a `MockAdvisor`
   proposing all four closed-allowlist ops. For `:log-farm-record`, the
   advisor only copies `:yield`/`:count` onto the proposal's top level
   (where the Governor independently re-verifies them) when the
   *request* actually supplied that metric -- so a herd-only submission
   never carries a spurious `nil` `:yield` that would otherwise trip a
   `nil`-unsafe comparison in the Governor's independent check.
5. **`mixedfarmops.governor`** -- `MixedFarmingOperationsGovernor`. Closed
   proposal-op allowlist (all `:effect :propose`):
   - `:log-farm-record` -- combined crop-field AND herd data logging
     (yield, herd count, weight)
   - `:schedule-farm-operation` -- combined field-operation AND
     veterinary-visit scheduling proposal
   - `:flag-health-concern` -- surfaces EITHER a crop-health OR an
     animal-health concern; ALWAYS escalates
   - `:order-supplies` -- seed/feed/fertilizer/veterinary-supply
     procurement proposal

   Hard invariants (always `:hold`, permanent, no override):
   1. `:farm-not-registered` -- the request's `farm-id` must resolve to a
      registered farm in the Store before any proposal can proceed
   2. `:no-execution` -- every proposal's `:effect` must be `:propose`
   3. `:equipment-pesticide-or-slaughter-blocked` -- any proposal to
      directly control field/animal-handling equipment
      (`:operate-field-equipment`, `:operate-animal-handling-equipment`)
      or to make a pesticide-application (`:finalize-pesticide-
      application`) or slaughter/culling (`:order-slaughter`) decision is
      a hard, permanent block -- four distinct blocked ops (two equipment
      classes + two irreversible-decision classes) rather than the two
      each parent actor has, since a mixed farm has both equipment
      categories AND both irreversible-decision categories in play
      simultaneously
   4. `:op-not-allowed` -- closed op-allowlist enforced independently of
      the advisor's claim
   5. `:farm-record-invalid` -- for `:log-farm-record`, whichever of
      `:yield`/`:count` is present on the proposal is checked
      INDEPENDENTLY for non-positivity; a valid herd count never masks an
      invalid yield, and a valid yield never masks an invalid herd count
      (governor_test.cljc's `hard-violations-mixed-record-one-side-invalid`
      tests exactly this cross-contamination risk in both directions)

   Escalation (always human sign-off, regardless of confidence):
   - `:flag-health-concern` always escalates
   - `:order-supplies` above its category cost threshold (default 500,
     equipment 1000, matching both parent actors' figures)
   - low confidence (< 0.7 floor, matching both parent actors)
6. **`mixedfarmops.phase`** -- 0->3 rollout phase gate, structurally
   identical to both parent actors' phase gates (no separate
   request-level `:stake` -- the request's `:op` IS the stake, since
   every op this actor may propose is already coordination-only).
7. **`mixedfarmops.operation`** -- composes advisor -> governor -> phase
   into one synchronous operation run (langgraph-clj StateGraph wiring
   deferred, same stub shape as both parent actors).
8. **`deps.edn` / `blueprint.edn` / docs** -- mirror both parent actors'
   shape (`:test`/`:lint`/`:run` aliases, `itonami.blueprint/*` metadata,
   scope/design/testing README sections, `docs/business-model.md` +
   `docs/operator-guide.md`).

### What this actor does NOT do

Direct field-equipment operation, direct animal-handling-equipment
operation, pesticide-application decisions, veterinary treatment
decisions, and slaughter/culling decisions all remain exclusive to the
farmer/agronomist/veterinarian, permanently, with no actor or
human-approval override path -- enforced structurally by the closed
operation allowlist and the four-way blocked-ops set, not just
documented.

## Verification

- `cloud-itonami-isic-0150`: `clojure -M:test` -- raw final line: `Ran 39
  tests containing 120 assertions.` / `0 failures, 0 errors.`
- `clojure -M:lint` -- 0 errors, 0 warnings.
- All source is `.cljc` with no JVM-only interop (no direct dependency on
  `java.*`/`clojure.lang.*` outside portable core forms) -- cljs-portable
  from day one.
- Repo created fresh (`gh repo create` + push), commit `2ee745e050e0bec1
  c41a86dc83b899690b69d1ed` on `cloud-itonami-isic-0150`'s `main` (initial
  commit, no prior history), confirmed via `git rev-parse HEAD` ==
  `gh api repos/cloud-itonami/cloud-itonami-isic-0150/commits/main --jq
  .sha`. Independently re-verified against a brand-new fresh clone: `Ran
  39 tests containing 120 assertions.` / `0 failures, 0 errors.`
- `kotoba-lang/industry` registry `"0150"` entry updated in place
  (`:repo`/`:business-id` corrected from the never-populated
  `gftdcojp/cloud-itonami-A0150` placeholder to
  `cloud-itonami/cloud-itonami-isic-0150`, `:required-technologies`
  narrowed from the stale placeholder's `[:robotics :identity :forms :dmn
  :bpmn :audit-ledger :telemetry]` to the implemented-tier `[:robotics
  :identity :forms :audit-ledger]` matching 0141's own shape,
  `:operating-states` corrected to `[:intake :advise :govern :decide
  :commit :hold]`, `:maturity` `:spec` -> `:implemented`);
  `test/kotoba/industry_test.clj`'s `maturity-summary` assertion bumped
  (live-recomputed via `(industry/maturity-summary)` against a freshly
  re-fetched `origin/main` immediately before each edit -- not assumed).
  First attempt (243 -> 244) was made against a clone that went stale
  before push (origin/main advanced 243 -> 244 from a concurrent
  promotion, `cloud-itonami-isic-0125`, in the interim) -- rather than
  fight a textual 3-way merge on the shared registry/test files, that
  branch was discarded without pushing, `main` was re-fetched fresh into
  a brand-new clone directory, the same targeted edit was redone against
  the new tip (244 -> 245, confirmed via the test runner's own failure
  diff: `expected: (= 244 (:implemented m)) actual: (not (= 244 245))`),
  and pushed on a fresh branch. That re-fetch also surfaced that the
  concurrent `cloud-itonami-isic-0125` promotion had reserved this same
  ADR's originally-planned timestamp (`2607181600`) for its own coverage
  ADR before this one landed, so this ADR was renumbered to
  `2607190500` (re-checked unused via `gh api` immediately before use,
  both in the registry comment and here) to avoid a filename collision.
  Landed via GitHub API server-side merge (`gh api
  repos/kotoba-lang/industry/merges`) on the first attempt against the
  re-fetched tip, merge commit
  `d6e426c3e37d3f5eaf267c8590534630056912ce`. Full `kotoba-lang/industry`
  suite re-run green post-edit (pre-merge, on the branch): `Ran 15 tests
  containing 950 assertions.` / `0 failures, 0 errors.`
- Post-merge re-verification: freshly re-fetched `origin/main`, re-cloned
  into a brand-new scratch directory at merge commit
  `d6e426c3e37d3f5eaf267c8590534630056912ce` (plus a fresh
  `kotoba-lang/technology` sibling clone for `deps.edn` resolution),
  re-ran `clojure -M:test` against the clean post-merge clone: `Ran 15
  tests containing 950 assertions.` / `0 failures, 0 errors.` `grep -c
  "â" resources/kotoba/industry/registry.edn` returned `0` (no file-wide
  UTF-8 mojibake).

## Consequences

(+) `cloud-itonami-isic-0150` now has a real, tested implementation
matching the shape of every other cloud-itonami ISIC actor, replacing a
placeholder registry entry that pointed at a repo that never existed.
(+) `kotoba-lang/industry` registry `"0150"` entry promoted to `:maturity
:implemented`, count 244 -> 245.
(+) The `mixedfarmops.governor`/`mixedfarmops.registry` split-metric
independent-verification pattern (`yield-non-positive?` and
`herd-count-non-positive?` checked separately on whichever of `:yield`/
`:count` is present, never assuming both) is a reusable pattern for any
future ISIC class in this fleet whose domain genuinely combines two
independently-observable record types in one proposal, as distinct from
every prior single-activity 01xx actor's single-metric record check.
(+) The stale-clone-discard-and-redo-on-fresh-tip recovery in this ADR's
own Verification section (including a timestamp-collision renumbering
caught only by re-checking `gh api` immediately before use) is a concrete
worked example of this fleet's "retry relentlessly" guidance for the
shared `kotoba-lang/industry` registry/test files and the superproject
ADR namespace under genuine concurrent-agent load.
(-) `mixedfarmops.operation` remains a synchronous stub (langgraph-clj
StateGraph wiring with real `interrupt-before`/checkpoint-based
human-in-the-loop resume for escalated operations is deferred, matching
both `cloud-itonami-isic-0141` and `cloud-itonami-isic-0111`'s own
current shape) -- a natural, contained future extension, not required for
this ADR's verification bar.
