# ADR-2628080000: cloud-itonami-isic-4912 (freight rail transport) filled in and promoted to `:implemented`

## Status

Accepted

## Related

- ADR-2607091700 (`cloud-itonami-isic-4920`, freightops — community
  road freight, the nearest sibling in the transport family, mirrored
  closely for architecture)
- ADR-2607102200 (`cloud-itonami-isic-4911`, passenger rail — the
  companion blueprint published alongside this one; promoted to
  `:implemented` by a concurrent sibling fleet agent around the same
  time as this build; see this ADR's own `Concurrency` section)
- `cloud-itonami-isic-6511`'s own `docs/adr/0001-architecture.md`
  (origin of the general governed-actor architecture pattern)
- the full `:adr/related` chain in the companion `.edn` file

## Context

`cloud-itonami-isic-4912` ("Freight rail transport") was, unlike a
fresh scaffold, an **UNUSUAL case**: `gh api repos/cloud-itonami/
cloud-itonami-isic-4912` confirmed a PRE-EXISTING repository already
published at `:blueprint` tier from an earlier bulk-scaffolding pass
— `CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/`GOVERNANCE.md`/`LICENSE`/
`README.md`/`SECURITY.md`/`blueprint.edn`/`docs/` only, no
`deps.edn`/`src`/`test`. This build ADDED the missing implementation
module set on top of that existing boilerplate rather than
re-scaffolding the repository.

Before any work began, the registry's own live `:name` for `{:id
"4912" ...}` was independently re-verified against a fresh clone of
`kotoba-lang/industry` and confirmed to read exactly "Freight rail
transport" (no truncation, no mismatch) — this fleet's own
ID/name-mismatch caution.

The pre-existing `docs/business-model.md`/`docs/operator-guide.md`
describe a broader aspirational business (safety-management-system
scope, hazmat-transport scope, robotics-assisted inspection,
booking/reconciliation records). This build deliberately implements a
NARROWER, explicitly-scoped slice: a freight-rail OPERATIONS
COORDINATION actor, distinct from `cloud-itonami-isic-4911` (passenger
rail — a different regulator, no hazmat-by-rail regime) and
`cloud-itonami-isic-4920` (road freight — already `:implemented`,
general parcel/tracking-number domain) per this blueprint's own README
`Scope note`.

## Decision

Build `railfreight` (RailFreight-LLM ⊣ Rail Freight Governor)
implementing the same langgraph StateGraph + independent Governor +
Phase 0→3 rollout pattern as `freightops`/4920, closed to a
four-member op allowlist:

- `:log-shipment-record` — consist/cargo-manifest/routing data logging
- `:schedule-service-operation` — train-consist/routing scheduling proposal
- `:flag-track-safety-concern` — surfaces a track-fault/hazmat-
  handling/derailment-risk concern; ALWAYS escalates
- `:coordinate-maintenance` — rolling-stock/track maintenance coordination

This actor is explicitly NOT the dispatcher, NOT the track-safety
authority, and NOT rolling-stock/locomotive control.

### Decision 1: TWO independent layers block any track/dispatch-safety-authority-finalizing proposal

Every proposal carries a literal `:effect :propose` (never an
actuation) and an `:action` drawn from a four-member closed allowlist
(`railfreight.governor/allowed-actions`). A proposal to directly
finalize a track/dispatch-safety-authority decision (clearing a train
for departure after a reported track fault, overriding a
hazmat-handling protocol) cannot be represented in this allowlist at
all — `action-allowlist-violations`/`op-allowlist-violations` hard-
block structurally. A SECOND, independent layer,
`scope-exclusion-violations`, text-scans the proposal's own rationale/
summary for the same class of forbidden finalization ACTION phrases,
catching a proposal that merely names a forbidden act in prose without
a matching `:action`. Both are HARD, permanent, un-overridable blocks
(HOLD never reaches human approval).

### Decision 2: scope-exclusion terms phrased as ACTIONS, never bare nouns — this fleet's known self-tripping bug class, guarded by construction AND by a dedicated test

Multiple sibling agents in this fleet have independently discovered
and fixed the SAME bug: a governor's own scope-exclusion term list
phrased as a bare noun (e.g. "safety", "dispatch", "clearance")
accidentally matches inside the mock advisor's OWN default rationale/
disclaimer text for a legitimate, allowed proposal — causing the actor
to self-block on its own happy path. `railfreight.governor/scope-
exclusion-actions` is phrased as full finalization ACTION phrases
("clear this consist for departure", "override the hazmat-handling
protocol", "finalize the track-safety override") rather than bare
nouns. `test/railfreight/governor_self_trip_test.clj` is the actual
guarantee, not wording care alone: it runs the default mock advisor's
`infer` across every op and every seeded demo consist (including the
hazmat/open-concern/already-open/no-spec-basis branches) and asserts
none of the resulting proposals trip the scope-exclusion check, plus a
belt-and-suspenders direct literal-substring check.

### Decision 3: "record must be independently verified/registered before ANY action" applies to all three non-registration ops

`record-not-verified-violations` gates `:schedule-service-operation`,
`:flag-track-safety-concern`, AND `:coordinate-maintenance` alike on
the subject consist's own `:registered?` fact (set only by a committed
`:log-shipment-record` with a valid spec-basis citation) — a stricter
reading than some sibling actors' own single-op gate, matching this
blueprint's own hard invariant text literally.

### Decision 4: `:flag-track-safety-concern` always escalates — TWO independent layers

`railfreight.governor/high-stakes` includes `:coordination/flag-
track-safety-concern` (confidence/actuation gate always escalates),
AND `railfreight.phase/phases` never includes `:flag-track-safety-
concern` in any phase's `:auto` set (structural). Both `railfreight.
phase-test` and `railfreight.governor-contract-test` assert this
independently. `:schedule-service-operation`/`:coordinate-maintenance`
get the same double-guard, for the same reason this actor coordinates
but never authorizes.

### Decision 5: hand-rolled EDN-blob codec, not `kotoba-lang/langchain-store`

`kotoba-lang/langchain-store` (ADR-2607141600) is the newer shared
substrate for this pattern and the preferred path for NEW stores. This
build instead mirrors `freightops`/4920's own hand-rolled `enc`/`dec*`
exactly, to avoid combining two different `langchain`/`langchain-clj`
coordinate families on one classpath while this actor's own `-M:test`
path (no `:dev` override) only resolves `kotoba-lang/langgraph`'s
transitive `langchain` via `:git/sha`. A reasonable, low-risk
migration follow-up once touched again, per this workspace's own
"touched, migrate incrementally" policy.

### Decision 6: minimal registry.edn diff — `:maturity` only

Unlike sibling `cloud-itonami-isic-5221`'s own additional
`:required-technologies`/`:operating-states` trim, this promotion adds
ONLY `:maturity :implemented` to the registry entry. `:repo`/
`:business-id` were already correct (`cloud-itonami-4912`, matching
the sibling `4911` entry's own bare, non-`-isic-` business-id
convention — confirmed via a fleet-wide scan that this bare form has
direct precedent among `:implemented` entries, e.g. `cloud-itonami-
isic-4211`, not a bug to fix) and were left untouched, as were
`:required-technologies`/`:operating-states` — a deliberately minimal,
exact-block diff.

## Concurrency

`cloud-itonami-isic-4911` (passenger rail, the companion blueprint
published in the same original bulk-scaffolding pass) was promoted to
`:implemented` by a CONCURRENT sibling fleet agent around the same
time as this build, independently. `kotoba-lang/industry`'s own
`test/kotoba/industry_test.clj` — a very hot, shared, append-only file
— was edited by both sessions in the same fast-moving window: this
session's own first PUT (adding a detailed "4912" testing block and
bumping the tail `:implemented` count) landed cleanly, but by the time
of this session's OWN post-verification re-fetch, the 4911 session had
already landed a SECOND edit on top that included its own minimal
"4912, promoted... by a CONCURRENT sibling fleet agent" stub
(correctly asserting `:implemented`, but without this build's own
detailed narrative) plus a re-synced tail count. This session replaced
that minimal stub with its own detailed, authoritative "4912" testing
block (since this session did the actual 4912 work) via a THIRD,
freshly-re-fetched sha-checked PUT, leaving the 4911 session's own
detailed "4911" testing block and re-synced counts untouched. Both
sessions' final live-recomputed counts independently agreed:
`{:total 649, :spec 232, :blueprint 18, :implemented 399}`.

## Consequences

- `cloud-itonami-isic-4912` promoted from `:blueprint` to
  `:implemented`. Fleet maturity before this promotion (as
  independently observed by this session, before either this
  promotion or the concurrent 4911 promotion landed):
  `{:total 649, :spec 232, :blueprint 19, :implemented 398}`. After
  BOTH this promotion and the concurrent 4911 promotion:
  `{:total 649, :spec 232, :blueprint 18, :implemented 399}` — a net
  `:blueprint -2`/`:implemented +2` matching two blueprint-tier
  entries promoted, live-recomputed via
  `(kotoba.industry/maturity-summary)` against a freshly re-fetched
  `origin/main`, not assumed.
- 45 tests / 365 assertions pass in the actor repo; clj-kondo clean;
  the demo (`clojure -M:dev:run`) walks one clean record-log +
  schedule + maintenance-coordination + concern-flag lifecycle, plus
  seven HARD-hold scenarios, end-to-end, with no exceptions.
- `kotoba-lang/industry`'s own full test suite (15 tests / 1060
  assertions) re-run clean from a brand-new fresh clone after this
  promotion's own final PUT, with no mojibake in `registry.edn`.
- Establishes, for the freight-rail domain specifically, the same
  "operations coordination, not safety authority" pattern several
  other Wave-4-adjacent transport/logistics siblings in this fleet
  (e.g. `cloud-itonami-isic-5221`) have independently converged on:
  closed four-op allowlist, literal `:effect :propose`, scope-
  exclusion phrased as finalization ACTIONS never bare nouns, a
  dedicated self-trip regression test, and a "flag a concern" op that
  is always escalate-only, never auto-eligible, at any phase.

## Alternatives considered

- **Wrapping `kotoba-lang/robotics`/`kotoba-lang/logistics`
  immediately**, per the pre-existing README `Capability layer`
  section. Deferred: none of this actor's four ops touch a validated
  external format the way `freightops`/4920's own parcel tracking
  number does; documented as an explicit follow-up in the child repo's
  own `docs/adr/0001-architecture.md`.
- **Adopting `kotoba-lang/langchain-store` immediately.** Deferred per
  Decision 5 above — a reasonable near-term follow-up, not a
  rejection.
- **Adding `:required-technologies`/`:operating-states` trims to the
  registry entry**, matching sibling `5221`'s own convention.
  Rejected in favor of the minimal, exact-block `:maturity`-only diff
  this task's own instructions called for.

## References

- `cloud-itonami-isic-4912/docs/adr/0001-architecture.md` (child-repo
  ADR, full decision structure)
- `cloud-itonami-isic-4920/docs/adr/0001-architecture.md` (nearest
  sibling in the same ISIC-49xx transport family; mirrored closely)
- `kotoba-lang/langchain-store` (ADR-2607141600; deferred adoption)
- Federal Railroad Administration, 49 C.F.R. Parts 200-299 (US)
- Pipeline and Hazardous Materials Safety Administration, 49 C.F.R.
  Parts 171-180 (US hazmat)
- Railways and Other Guided Transport Systems (Safety) Regulations
  2006 / Carriage of Dangerous Goods and Use of Transportable Pressure
  Equipment Regulations 2009 (UK)
- Allgemeines Eisenbahngesetz / Gefahrgutverordnung Eisenbahn und
  Binnenschifffahrt (Germany)
- 鉄道事業法 / 鉄道による危険物の運送に関する技術上の基準を定める省令 (Japan)
