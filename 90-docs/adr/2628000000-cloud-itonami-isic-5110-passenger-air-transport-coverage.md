# ADR-2628000000: cloud-itonami-isic-5110 (passenger air transport) operations-coordination actor

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (cloud-itonami global ISIC/ISCO
reverse-toposort wave plan -- ISIC division 51 falls in Wave 2,
"流通・調整・労働市場" / distribution-coordination-labor-market,
divisions 49-53), skill `build-actor` (advisor/governor/StateGraph/
audit-ledger actor pattern). Mirrors `cloud-itonami-isic-4920`
(community freight transport), the closest already-`:implemented`
transport-sector sibling at build time (the nominal reference,
`cloud-itonami-isic-5011` sea-passenger-transport, was itself still
`:blueprint`-tier boilerplate-only when checked -- being built
concurrently by a sibling agent in the same batch, not yet a usable
reference).

## Context

`cloud-itonami/cloud-itonami-isic-5110` was **not** a fresh-scaffold
target. `gh api repos/cloud-itonami/cloud-itonami-isic-5110` and a
full-tree listing were checked before any work started: the repo
already existed with `:blueprint`-tier boilerplate (`README.md`,
`CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `GOVERNANCE.md`, `LICENSE`,
`SECURITY.md`, `blueprint.edn`, `docs/business-model.md`,
`docs/operator-guide.md`) but no `deps.edn`, `src`, or `test` -- an
early bulk-scaffolding-pass entry, published but never implemented.
This build adds the missing module set on top of the existing files
rather than recreating the repo; the pre-existing boilerplate docs are
left byte-for-byte unmodified except `blueprint.edn` (one field added,
see Decision 8).

The live `kotoba-lang/industry` registry entry for `"5110"` was
independently verified fresh (cloned from scratch, not assumed from
task premise) before any work started: `:name "Passenger air
transport"`, matching the task's premise exactly -- no mismatch, no
stop condition triggered. `:repo`/`:business-id`
(`https://github.com/cloud-itonami/cloud-itonami-isic-5110` /
`"cloud-itonami-5110"`) were also independently verified already
correct against the repo's own `blueprint.edn`
(`:itonami.blueprint/id "cloud-itonami-5110"`) -- no placeholder-value
fix was needed, unlike some prior sibling promotions in this fleet.

Passenger air transport has an obvious direct-passenger-safety
dimension. Per this batch's explicit guardrail, the closed op
allowlist must never include an op that directly finalizes a
flight-safety-authority decision (clearing an aircraft for departure
after a reported mechanical fault, overriding a weather/go-no-go
decision) -- those are always either a hard permanent block or an
always-escalate op, never auto-commit-eligible. Any "flag a concern"
op must always escalate to human sign-off and never appear in any
phase's `:auto` set.

This batch's sibling agents (and, per this fleet's own build-actor
convention, prior batches before it) have independently discovered and
fixed the SAME recurring bug class: a governor's own scope-exclusion
term list phrased as a bare noun (e.g. "safety", "weather", "go-no-go")
accidentally matches inside the mock advisor's own DEFAULT rationale/
disclaimer text for a legitimate, allowed proposal -- causing the
actor to self-block on its own happy path. This is a live risk here
because `:flag-flight-safety-concern`'s entire purpose is to talk
*about* mechanical-fault/weather/go-no-go topics using exactly the
bare nouns a naive term list would ban.

## Decision

### Decision 1: a passenger-airline OPERATIONS COORDINATION actor, not a flight-safety authority

Closed op-allowlist, all `:effect :propose`:

- `:log-flight-record` -- flight/passenger-manifest/on-time-performance
  data logging.
- `:schedule-flight-operation` -- gate/crew scheduling coordination
  proposal (NOT a runway-occupancy or departure-clearance
  authorization).
- `:flag-flight-safety-concern` -- surfaces a mechanical-fault/weather/
  go-no-go concern to a human. ALWAYS escalates.
- `:coordinate-maintenance` -- aircraft maintenance SCHEDULING
  coordination (NOT a maintenance RELEASE / return-to-service sign-off).

This actor never clears an aircraft for departure and never overrides
a weather/go-no-go hold -- those acts are structurally outside its
vocabulary, reinforced by a dedicated hard governor check (Decision 3).

This is narrower than the originally-published `README.md`/
`docs/business-model.md` text (a broader "booking/dispatch/
reconciliation" vision, `:operating-states [:intake :book :transit
:deliver :reconcile :audit]` in the registry). Per this fleet's own
"extending coverage is additive, scope down for R0" convention (see
`cloud-itonami-isic-4920`'s own ADR-0001 Decision 10), this build
implements the operations-coordination slice now; booking/
reconciliation is a follow-up. The original docs are left UNMODIFIED
(task instructions: keep existing boilerplate docs, do not recreate
the repo); the child-repo's own `docs/adr/0001-architecture.md` is the
authoritative record of what this R0 build actually covers.

### Decision 2: `:effect` is structurally ALWAYS `:propose`

Every proposal this actor's advisor can produce carries a literal
`:effect :propose` -- it never performs a real-world actuation of any
kind, only ever drafts and appends a coordination record.
`airlineops.governor`'s `effect-not-propose-violations` independently
re-verifies this on every proposal (defense in depth beyond the
advisor's own honesty), and `airlineops.store/commit-record!` itself
only recognizes `:propose`.

### Decision 3: `finalize-authority-scope-violations` -- HARD, PERMANENT, phrased as the finalization ACTION

`airlineops.governor/finalize-authority-phrases` is phrased as the
finalization/execution ACTION ("finalize the go-no-go decision",
"clear the aircraft for departure", "override the weather hold
decision", "sign off the maintenance release") rather than a bare
topic noun. `airlineops.airlineopsllm/propose-flag-flight-safety-
concern` legitimately talks about mechanical faults and weather as the
CONTENT of a concern being flagged -- a bare-noun term list would have
self-tripped on exactly this op's own happy path.
`test/airlineops/governor_contract_test.clj`'s
`default-advisor-proposals-never-self-trip-finalize-authority-scope`
asserts directly, for all four ops on a clean flight, that the default
mock advisor's own proposals never trip this check. A second test,
`finalize-authority-scope-violation-not-overridable-through-the-full-
graph`, proves a hand-built advisor that DOES drift into finalization
language HOLDs through the full `OperationActor` graph and never
reaches `:request-approval` -- i.e. this is not merely a soft signal a
human could wave through, it structurally never offers the human that
choice.

### Decision 4: `:flag-flight-safety-concern` is doubly enforced to never auto-commit

Two independent layers agree: `airlineops.governor`'s `high-stakes`
gate (keyed on the proposal's own `:stake :coordination/flag-safety-
concern`) always forces `:escalate?` true regardless of confidence or
other checks being clean, AND `airlineops.phase`'s phase table never
adds `:flag-flight-safety-concern` to any phase's `:auto` set,
including phase 3 -- a permanent structural fact, not a rollout
milestone still to come (mirroring `cloud-itonami-isic-4920`'s own
dual-actuation-never-auto pattern for `:shipment/dispatch`/
`:consignment/settle`). `test/airlineops/phase_test.clj`'s
`flag-flight-safety-concern-never-auto-at-any-phase` pins this down
structurally across every phase-table entry, present and future.

### Decision 5: `certification-unverified-violations` -- ground truth this actor CONSUMES, never MINTS

`:certification-verified?` represents a flight's own aircraft/Air-
Operator-Certificate record, independently verified and registered by
a real civil aviation authority OUTSIDE this actor's own closed
op-allowlist. None of the four ops ever sets it -- there is no
`:jurisdiction/assess`-style op in this actor's vocabulary (unlike
`cloud-itonami-isic-4920`'s own `:jurisdiction/assess`). Evaluated
UNCONDITIONALLY across all four ops: no coordination proposal may
proceed for a flight whose own certification has not been
independently verified and registered.

### Decision 6: `open-safety-concern-blocks-op` -- exempting the flag op itself

An unresolved flight-safety concern already on file
(`:safety-concern-raised? true` AND `:safety-concern-resolved? false`)
blocks `:log-flight-record`/`:schedule-flight-operation`/`:coordinate-
maintenance` on that flight, but deliberately NOT `:flag-flight-
safety-concern` itself -- the safety-reporting channel must always stay
open, including to report further detail on an already-open concern.
Resolving a concern (`:safety-concern-resolved? true`) is likewise
OUTSIDE this actor's own op-allowlist -- a real flight-safety
authority's call, mirroring Decision 5's "consume, never mint"
discipline.

### Decision 7: self-contained, no bespoke domain capability library

There is no `kotoba-lang/aviation` bespoke domain capability library to
delegate airworthiness/AOC validation to (checked: `kotoba-lang` org
has no aviation-domain package -- `com-ge-aviation`/`com-ads-b-aviation`/
`com-airbus-analytx` etc. are external-company placeholder repos, not
domain libraries). Like `cloud-itonami-isic-5020` (marine tanker) and
`cloud-itonami-isic-5210` (warehousing), this R0 build is
self-contained: `airlineops.facts`/`airlineops.registry`/
`airlineops.governor` implement the domain logic as pure functions.
`airlineops.facts/catalog` seeds four real jurisdictions (JPN/USA/GBR/
DEU) with an official civil-aviation-authority citation each (JCAB /
FAA 14 C.F.R. Part 121 / UK CAA Air Navigation Order 2016 / German
LBA-LuftVG), reported honestly (4 of ~194 jurisdictions, not a global
coverage claim).

### Decision 8: `blueprint.edn` maturity flip, no other field-sync fix needed

`blueprint.edn`'s `:required-technologies`/`:optional-technologies`
already correctly matched the `kotoba-lang/industry` registry entry
(`[:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics]` /
`[:optimization]`), so no field-sync fix was needed there (unlike some
prior sibling promotions). The single change: added
`:itonami.blueprint/maturity :implemented`, matching the registry's
own `:maturity :implemented` flip.

## Scope exclusions (hardcoded in governor checks, not just prose)

- Clearing an aircraft for departure -- always a hard, permanent block
  (`finalize-authority-scope-violations`), structurally absent from the
  op allowlist.
- Overriding a weather or mechanical go/no-go hold -- same.
- Signing off a maintenance release back to airworthy service -- same;
  `:coordinate-maintenance` only ever schedules/coordinates, never
  releases.
- Resolving a flight-safety concern once flagged -- outside the closed
  op allowlist entirely (Decision 6); this actor can surface a concern
  but never clear one.

## Alternatives considered

- **Matching the originally-published booking/dispatch/reconciliation
  vision literally, op-for-op.** Rejected for R0 (Decision 1) -- the
  task specifies a narrower, explicit four-op operations-coordination
  vocabulary with a clear non-actuation invariant.
- **A bare-noun scope-exclusion term list** (`#{"safety" "weather"
  "go-no-go"}`). Rejected: the exact fleet-wide known bug class
  (Decision 3) -- would have self-tripped on
  `propose-flag-flight-safety-concern`'s own legitimate happy path.
- **Rewriting the existing README/business-model/operator-guide docs to
  match the R0 scope exactly.** Rejected: task instructions direct
  keeping the existing boilerplate docs rather than recreating the
  repo; the child repo's own ADR-0001 is the authoritative gap record
  instead.

## Consequences

- (+) ISIC 5110 passenger-air-transport-operations-coordination is
  genuinely implemented and fully tested, promoted from `:blueprint`
  boilerplate-only to `:implemented`.
- (+) The flight-safety-authority-finalization exclusion is hardcoded
  in a governor check (`finalize-authority-scope-violations`), not just
  README prose, and proven un-overridable through the full graph, not
  merely at the raw `governor/check` level.
- (+) The self-tripping bug class this fleet has repeatedly hit is
  fixed by construction (action-phrased terms) AND covered by a
  dedicated regression test asserting the default advisor's own
  proposals for all four ops never trip it.
- (+) `:flag-flight-safety-concern` always-escalate is a two-layer
  invariant (governor `high-stakes` + phase's permanent absence from
  every `:auto` set), matching this fleet's established dual-actuation
  discipline.
- (+) Portable `.cljc`, zero JVM-only constructs; `clojure -M:lint` is
  0 errors / 0 warnings.
- (-) Real persistent store (Datomic/kotoba-server) is a follow-up;
  tests use in-memory `MemStore` + a `langchain.db`-backed
  `DatomicStore`, proven to satisfy the same contract, matching every
  sibling actor's current maturity.
- (-) `airlineops.airlineopsllm`'s `mock-advisor` is deterministic, not
  a real LLM call; the `Advisor` protocol seam is ready for that swap
  but not wired in this build.
- (-) Booking/reservation/reconciliation (the originally-published
  broader vision) remains a follow-up slice, not this R0.

## Verification

- `cloud-itonami-isic-5110`: `clojure -M:dev:test` -> "Ran 36 tests
  containing 164 assertions. 0 failures, 0 errors." `clojure -M:lint`
  -> 0 errors, 0 warnings. `clojure -M:dev:run` demo runs end-to-end:
  a clean `:log-flight-record` auto-commits at phase 3;
  `:schedule-flight-operation`/`:coordinate-maintenance` escalate then
  commit after approval; `:flag-flight-safety-concern` always
  escalates then commits after approval and flips
  `:safety-concern-raised?` true (never `:safety-concern-resolved?`);
  a flight with an open unresolved concern then HARD-holds the other
  three ops while `:flag-flight-safety-concern` itself stays reachable;
  an unknown jurisdiction and an unverified certification both
  HARD-hold as designed.
- Commit `1452151420e3cf8b65aeab4642991d3754005799` pushed to
  `cloud-itonami/cloud-itonami-isic-5110`'s `main` (on top of the
  pre-existing `90b1b53` blueprint-tier boilerplate commit --
  independently re-verified present on `origin/main` via
  `gh api repos/cloud-itonami/cloud-itonami-isic-5110/commits/main`
  before this ADR was written).

## Verification addendum: registry promotion (Step 7, landed)

- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`:
  `"5110"` entry's exact block edited in place (`:repo`/`:business-id`
  already correct, no other field changed) to add
  `:maturity :implemented`. Commit `e69cba6319f6c487c15c0a1e6d85b9e80509701e`,
  landed on the first Contents-API single-file PUT attempt (sha-checked
  optimistic concurrency against a freshly re-fetched blob taken
  immediately before the write). Verified via a byte-exact
  prefix/suffix isolation check (everything outside the target block's
  exact insertion point byte-identical to the immediately-prior fresh
  fetch) before and after the PUT.
- `test/kotoba/industry_test.clj`: this file is extremely hot
  (concurrent sibling fleet agents editing it continuously). Three
  separate PUTs were needed, each built against a freshly re-fetched
  blob sha immediately before the write:
  1. Commit `4f877634ed3eb04ac477a80017e7e528105472a2` -- added a
     dedicated per-id corroboration `testing` block for `"5110"`, and
     bumped the live-recomputed `:blueprint` (22->21) and
     `:implemented` (395->396) counts, both taken via
     `(kotoba.industry/maturity-summary)` against the freshly
     re-fetched `registry.edn` immediately before this edit.
  2. Commit `e911a573f50c358606cfb21d45831eb51124ed65` -- an EARLIER
     "live-state corroboration" spot-check elsewhere in the same file
     (predating this promotion, added by a prior sibling agent) still
     asserted `(is (= :blueprint (industry/maturity "5110")))`
     ("freshly published, is also `:blueprint`") -- now false after
     this promotion. Fixed to assert `:implemented`, found by actually
     running `clojure -M:test` (not assumed) and reading the failure.
  3. Commit `9c1c5f6d4ae34dcc9c1e57a6e3ff214aca6da8b3` -- while
     re-verifying from yet another fresh clone, three MORE
     "freshly published, is also `:blueprint`" spot-checks for
     UNRELATED sibling entries (`"5011"`/`"4912"`/`"5222"`, none of
     them this promotion's own work) had gone stale from concurrent
     sibling promotions landing in the same window and were failing
     `clojure -M:test`. Fixed the same way (verified each one's true
     live maturity via `(kotoba.industry/maturity ...)` against the
     current registry before touching its assertion, not assumed) to
     keep this shared hot test file green for the rest of the fleet,
     consistent with this file's own established "keep this hot shared
     test file truthful" convention (see e.g. commit `10c0b87`,
     `cloud-itonami-isic-4911`'s own concurrent "re-sync hot
     maturity-tier counts" commit in the same window). Scope note: this
     is a narrow, mechanical, verified-not-assumed correction of stale
     test assertions for OTHER entries' already-true live state, not a
     change to those entries' own registry data or actor code -- ISIC
     5110 remains this ADR's only substantive scope.
  4. A CONCURRENT sibling agent's own commit `10c0b87` (`cloud-itonami-
     isic-4911`'s "add dedicated corroboration + re-sync hot
     maturity-tier counts") landed in the same window and independently
     re-synced the `:blueprint`/`:implemented` counts to 18/399 (three
     further sibling promotions beyond this ADR's own +1) before this
     ADR's own step 3 fix above was written -- corroborated, not
     duplicated.
- Final post-merge re-verification, from a BRAND-NEW fresh clone of
  `kotoba-lang/industry` `main` (commit `9c1c5f6d4ae34dcc9c1e57a6e3ff214aca6da8b3`,
  plus a fresh `../technology` sibling clone):
  `clojure -M:test` -> **"Ran 15 tests containing 1060 assertions. 0
  failures, 0 errors."** `clojure -M:lint` -> 0 errors, 0 warnings. No
  mojibake in `registry.edn` (valid UTF-8, zero U+FFFD replacement
  characters). `"5110"`'s own entry
  (`:maturity :implemented`, `:repo`/`:business-id` unchanged) and
  sample sibling entries `"3512"`/`"4920"`/`"5210"`/`"5011"` all
  independently re-confirmed intact and byte-consistent.
- True `:implemented` count immediately after this promotion's own
  edits (before the concurrent isic-4911 agent's further promotions):
  396 (was 395). Live count at final re-verification time: 399 (396 +
  3 further concurrent sibling promotions in the same window, not this
  ADR's own drift) -- recomputed via
  `(kotoba.industry/maturity-summary)` each time, never assumed or
  grepped.
