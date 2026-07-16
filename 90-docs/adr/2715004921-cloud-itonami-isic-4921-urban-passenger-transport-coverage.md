# ADR-2715004921: cloud-itonami-isic-4921 (Urban and suburban passenger land transport) coverage

- Status: accepted
- Date: 2026-07-16
- Wave: Wave 2 (coordination/logistics/trade, ADR-2607121000), TRANSPORT batch

## Context

`kotoba-lang/industry`'s registry carries ISIC Rev.5 class `4921`
("Urban and suburban passenger land transport" -- city bus, tram,
light rail, taxi/rideshare dispatch) at `:maturity :spec`, pointing at
a stale, non-existent placeholder (`gftdcojp/cloud-itonami-H4921`,
`:business-id "cloud-itonami-H4921"`). No repository existed at either
that placeholder or the real `cloud-itonami` org target
(`gh api repos/cloud-itonami/cloud-itonami-isic-4921` confirmed 404
before any work began) -- a fresh scaffold, not a promotion of
existing work.

The registry's own live `:name` for `"4921"` was verified against a
fresh clone before any work began and reads unambiguously (no
truncation, unlike the ~10% pre-existing seed-data bug this fleet has
independently found and fixed on several sibling entries): `"Urban and
suburban passenger land transport"`. A separate, coarser-granularity
3-digit group entry `{:id "492" ... :superseded-by ["4920"]}` exists in
the same registry and was intentionally left untouched.

This is a TRANSPORT vertical with a direct passenger-safety dimension
(city buses, trams, urban rail, taxi dispatch carry passengers), unlike
the preceding retail/wholesale-trade batch. Sibling ISIC 4911
(interurban passenger rail, already `:implemented`) established the
precedent that a passenger-transport actor in this fleet must be
scoped to scheduling/dispatch-logistics coordination only, never
vehicle operation or safety-clearance authority -- this build follows
the same discipline, adapted for the urban/suburban (bus/tram/light-
rail/taxi) sub-domain rather than interurban rail.

## Decision

Publish `cloud-itonami/cloud-itonami-isic-4921` as a governed actor:
**TransitDispatchAdvisor ⊣ UrbanTransitDispatchGovernor**, a
`transitops.*` namespace (portable `.cljc`, no JVM-only interop
anywhere in `src/`) implementing a SCHEDULING/DISPATCH LOGISTICS
COORDINATION actor for urban/suburban passenger land transport. This
actor coordinates dispatch logistics only -- it never directly
operates a vehicle, never overrides a driver's or dispatcher's safety
judgment, never finalizes a dispatch-safety-clearance decision, and
never determines driver fitness-to-drive.

### Closed proposal-op allowlist (all `:effect :propose`)

- `:log-service-record` -- trip/ridership/incident-report data logging
- `:schedule-dispatch-operation` -- vehicle/route/timetable dispatch
  scheduling proposal
- `:coordinate-maintenance-order` -- fleet maintenance procurement
  proposal
- `:flag-safety-concern` -- surface a vehicle-defect/driver-fitness/
  route-hazard concern; ALWAYS escalates, never a member of any
  phase's `:auto` set at any phase

### Five HARD governor checks (permanent, un-overridable by any human approval)

1. **Route unverified** -- the target route (bus/tram/light-rail line
   or taxi/rideshare dispatch zone) must exist AND be independently
   `:registered?`/`:verified?` in the store before ANY proposal
   referencing it may commit or even escalate. Checked UNCONDITIONALLY
   on all four ops, re-derived from the store every time, never from
   proposal self-report.
2. **Vehicle unverified** -- for `:schedule-dispatch-operation` ONLY,
   the named `:vehicle-id` must resolve to an independently
   `:registered?`/`:verified?` vehicle record (current roadworthiness
   inspection).
3. **Operator unverified** -- for `:schedule-dispatch-operation` ONLY,
   the named `:operator-id` must resolve to an independently
   `:registered?`/`:verified?` operator/driver record (a current,
   valid operator/driver license -- fitness-to-drive already
   independently determined by the licensing authority, never by this
   actor).
4. **Effect not `:propose`** -- any other `:effect` value is a HARD
   block.
5. **Scope exclusion** (folds in the closed-op-allowlist check) --
   permanently blocks any proposal touching directly finalizing a
   dispatch-safety-clearance decision or a driver-fitness-to-drive
   determination, or an op outside the closed four-op allowlist.
   Evaluated unconditionally on every proposal.

### ESCALATE (soft, human sign-off required)

- `:flag-safety-concern` always escalates, regardless of confidence or
  phase -- two independent layers agree
  (`transitops.governor/always-escalate-ops` AND
  `transitops.phase`'s own phase table never puts it in any `:auto`
  set).
- A `:coordinate-maintenance-order` above a 2000.0 USD-equivalent cost
  threshold always escalates, independently recomputed from the
  proposal's own drafted `:estimated-cost`.
- LLM confidence below the floor (0.6) also escalates.

### Known fleet self-tripping bug class -- avoided from day one

This fleet has repeatedly discovered the same bug class: a governor's
scope-exclusion term list phrased as a bare noun (e.g. "safety",
"dispatch", "fitness") accidentally matches inside the mock advisor's
own default rationale/disclaimer text for a legitimate proposal,
self-blocking the happy path. `transitops.governor/scope-excluded-terms`
is phrased exclusively as finalization/execution ACTION phrases (e.g.
"finalized the dispatch-safety clearance", "determined driver fitness
to drive"), never a bare noun. A dedicated regression test,
`transitops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`,
asserts every one of the four allowed ops' own default mock-advisor
proposal never self-trips `:scope-excluded` or `:op-not-allowed`.

Before finalizing, `transitops.governor`'s
`:urban-transit-dispatch-governor` blueprint keyword was checked for
collisions via `gh api search/code -f
q="urban-transit-dispatch-governor org:cloud-itonami"` (0 hits) and
against sibling ISIC 4911's own published `:rail-safety-governor` (not
a collision) -- distinct, independent governor identity confirmed.

### Architecture

Real `langgraph-clj` StateGraph
(`intake -> advise -> govern -> decide -> commit | hold |
request-approval`) with `interrupt-before #{:request-approval}` for
human-in-the-loop resume, mirroring `cloud-itonami-isic-4719`'s
verified module shape module-for-module (`transitops.*` in place of
`merchandiseops.*`, route/vehicle/operator three-entity verification in
place of store/vendor two-entity verification). `MemStore` (atom of
EDN, string-keyed `routes`/`vehicles`/`operators` directories,
append-only ledger); fully portable `.cljc` with no JVM-only interop
anywhere in `src/` (mock-only advisor).

## Verification

- `clojure -M:test`: **65 tests / 192 assertions, 0 failures, 0
  errors** -- independently re-verified against a fresh clone
  (`git clone --depth 1` into a new temp dir, then `clojure -M:test`
  again with the same result).
- `clojure -M:lint` (clj-kondo): 0 errors, 0 warnings.
- `clojure -M:run` demo driver: all 12 scenarios (phase-1 approval,
  phase-3 auto-commit x3, high-cost maintenance-order escalate,
  safety-concern escalate, unregistered-route hold, unverified-route
  hold, unverified-vehicle hold, unverified-operator hold,
  non-`:propose`-effect hold, scope-excluded-content hold) exercised
  end-to-end with no exceptions; each HARD-hold scenario's `:violations`
  carried exactly the expected `:rule` (`:route-unverified` x2,
  `:vehicle-unverified`, `:operator-unverified`, `:effect-not-propose`,
  `:scope-excluded`).

## Registry change

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
`{:id "4921" ...}` block: `:repo`/`:business-id` corrected from the
stale `gftdcojp/cloud-itonami-H4921` placeholder to
`https://github.com/cloud-itonami/cloud-itonami-isic-4921` /
`cloud-itonami-isic-4921`; `:maturity :spec` -> `:implemented`;
`:operating-states` updated from the leftover freight/logistics-shaped
`:spec` placeholder (`[:intake :book :transit :deliver :reconcile
:audit]`) to the actor's own langgraph-clj node sequence (`[:intake
:advise :govern :approve :commit :audit]`), matching the same
convention every other `:implemented` `cloud-itonami-isic-*` entry
uses (e.g. sibling 4719/4721/4753); `:required-technologies`/
`:optional-technologies` left unchanged. Exact-block edit only (no
other entry touched, including the separate `{:id "492" ...}` 3-digit
group entry). `test/kotoba/industry_test.clj` gained a dedicated
`(is (= :implemented (industry/maturity "4921")))` regression assertion
plus a corrected `maturity-summary-counts-tiers` `:implemented` count,
recomputed live via `(kotoba.industry/maturity-summary)` against a
freshly re-fetched `origin/main` `registry.edn` immediately before the
edit, not assumed.

## Consequences

- ISIC 4921 moves from `:spec` to `:implemented` in the fleet-wide
  maturity roadmap.
- Establishes the urban/suburban (bus/tram/light-rail/taxi)
  passenger-transport pattern (route + vehicle + operator three-entity
  verification chain) as a reusable reference alongside sibling ISIC
  4911's interurban-rail pattern (route/schedule single-entity
  verification) for any future urban-mobility-adjacent actor in this
  fleet (e.g. ISIC 4922 "Other passenger land transport", still
  `:spec`).
- No change to `manifest/west.yml` or any other repo's registry entry.
