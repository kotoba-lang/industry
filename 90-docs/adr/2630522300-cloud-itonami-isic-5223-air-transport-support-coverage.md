# ADR-2630522300: cloud-itonami ISIC-5223 Airport-Ground-Handling Operations-Coordination Actor — Blueprint Fill-In & Registry Promotion

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-5223` (pre-existing blueprint-tier repo, filled in), `orgs/kotoba-lang/industry` registry (ISIC-5223 promotion), Wave 2 coordination/logistics cluster

## Context

ISIC 5223 ("Service activities incidental to air transportation") is an
airport-ground-handling SUPPORT-services vertical: de-icing, baggage
handling, ramp servicing, aircraft turnaround, ground-support-equipment
maintenance — performed on behalf of MULTIPLE airlines at an airport, NOT
the airline business itself. The `cloud-itonami/cloud-itonami-isic-5223`
repo already existed on GitHub (created 2026-07-09) as a legitimate
`:blueprint`-tier registry entry from an earlier bulk-scaffolding pass:
`blueprint.edn`, `README.md`, `docs/business-model.md`,
`docs/operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `LICENSE`, `SECURITY.md` were present, but no
`deps.edn`/`src`/`test` — the repo was published but never implemented.
This ADR records filling that repo in, not creating it fresh.

The pre-existing `blueprint.edn` already declared
`:itonami.blueprint/governor :airport-operations-governor` and the
pre-existing `README.md`/`docs/*.md` already established the domain
vocabulary this implementation follows: "Airport Operations Advisor" /
"Airport Operations Governor", "airfield/apron-safety scope",
"robotics-assisted runway/taxiway inspection", "baggage handling and
aircraft turnaround services performed on behalf of MULTIPLE airlines".
`README.md`'s own "Scope note" section already distinguishes this repo
from `cloud-itonami-isic-5110` ("Community Passenger Air Transport" — the
airline itself; ground handling there is an internal function the carrier
performs for its OWN flights, not a separately licensed business) and from
`cloud-itonami-isic-5224` (cargo handling, the sibling terminal-service
class being built in the same batch by another agent) — this repo is the
separate business of airport operation and independent/third-party ground
handling, infrastructure and services provided to MULTIPLE airlines under
their own independent licensing regime (ICAO Annex 14; US FAA Part 139;
EU Regulation 139/2014 + Directive 96/67/EC; Japan's 空港法; UK
Airports (Groundhandling) Regulations 1997).

### ID/name verification

Before starting, `kotoba-lang/industry` was cloned fresh and the live
`{:id "5223" ...}` entry's `:name` was confirmed to read exactly "Service
activities incidental to air transportation" — matching the task premise
and distinct from sibling `"5222"` ("...water transportation") and
`"5224"` ("Cargo handling").

### Domain Scope

Airport ground-handling operations coordination — NOT direct ramp-safety
authority or ground-equipment control:
- De-icing/baggage-handling/ramp-service data logging (administrative
  data logging only)
- Ramp/gate/de-icing scheduling proposals (proposal only, never a
  ramp-clearance authorization)
- Ramp-safety-concern flagging (a reported ramp hazard, FOD, or
  de-icing-fluid-holdover concern — ALWAYS escalates to human sign-off)
- Ground-support-equipment maintenance coordination (coordination only,
  never an equipment return-to-service release)

### Actor Pattern (Module Shape)

Mirrors `cloud-itonami-isic-5110` (passenger air transport) and
`cloud-itonami-isic-5222` (water transport support), both filled in
earlier in this same batch as pre-existing blueprint-tier repos:

- **Store** (`groundops.store`): SSoT with a `service` (ground-handling
  engagement) entity directory + append-only coordination-record log +
  audit ledger. `MemStore` (default, atom-backed) and `DatomicStore`
  (langchain.db-backed), proven to satisfy the same contract via
  `test/groundops/store_contract_test.clj`.
- **Registry** (`groundops.registry`): pure-function draft-record
  construction (unsigned, `:kind :*-draft`) for the four record kinds
- **GroundOps-LLM** (`groundops.groundopsllm`): deterministic mock
  proposal generator for the four ops (+ an `llm-advisor` seam for a real
  `langchain.model/ChatModel`)
- **Governor** (`groundops.governor`): six checks, all HARD/unconditional
- **Operation** (`groundops.operation`): langgraph-clj StateGraph
  orchestration (intake → advise → govern → decide → commit | hold |
  request-approval)
- **Phase** (`groundops.phase`): rollout phases 0–3
- **Facts** (`groundops.facts`): per-jurisdiction airport/ground-handling
  spec-basis catalog (JPN/USA/GBR/DEU seeded, honest coverage reporting)
- **Sim** (`groundops.sim`): deterministic demo runner
- **Tests**: `facts_test.clj`, `governor_contract_test.clj`,
  `phase_test.clj`, `registry_test.clj`, `store_contract_test.clj` — 36
  tests / 164 assertions, all passing

### Governor: Six Checks (All HARD, Unconditional)

1. **Op not allowed** — closed allowlist is the actor's only vocabulary
2. **Effect not `:propose`** — this actor never actuates directly
3. **Finalize-clearance scope violation** — a HARD, PERMANENT,
   un-overridable block on any proposal whose own summary/rationale/cites
   text drifts toward finalizing an airport/ground-safety clearance
   decision (clearing a ramp as safe, overriding a de-icing protocol) —
   see the CRITICAL Wave-2 requirement below
4. **No spec-basis** — every proposal must cite an official
   airport/ground-handling-authority source for the engagement's
   jurisdiction (`groundops.facts`), never an invented one
5. **Facility unverified** — the engagement's own `:facility-verified?`
   ground truth (airport-facility permit / ground-handling-operator
   license, independently registered elsewhere) must be true before ANY
   of the four ops may proceed — ground truth this actor CONSUMES, never
   MINTS
6. **Open ramp-hazard blocks op** — an unresolved ramp-safety concern
   already on file blocks the other three ops on that engagement, but
   NOT `:flag-ramp-safety-concern` itself (the reporting channel must
   always stay open)

### Self-tripping-bug discipline (fleet-wide known pattern)

Multiple sibling `cloud-itonami-isic-*` actors in this fleet independently
discovered and fixed the SAME bug class: phrasing a governor's
scope-exclusion term list as a bare noun ("safety", "ramp", "de-icing")
makes it match inside the mock advisor's own DEFAULT rationale/disclaimer
text for a legitimate, allowed proposal — e.g. `:flag-ramp-safety-concern`'s
own honest rationale legitimately talks about ramp hazards, FOD, and
de-icing-fluid holdover time as the CONTENT of the concern being flagged,
so a bare-noun exclusion list would self-block the actor's own happy path.
`groundops.governor/finalize-clearance-phrases` is phrased as the
FINALIZATION/EXECUTION ACTION ("finalize the ramp-safety clearance",
"clear the ramp as safe", "override the de-icing protocol" — never the
bare noun), and
`test/groundops/governor_contract_test.clj`'s
`default-advisor-proposals-never-self-trip-finalize-clearance-scope`
asserts directly, for all four ops on a clean engagement, that the
default mock advisor's own proposals never trip this check — plus
separate tests confirming the SAME phrase set DOES catch a genuine
finalization attempt when constructed directly
(`finalize-clearance-scope-violation-is-hard-and-permanent`,
`finalize-clearance-scope-violation-not-overridable-through-the-full-graph`).

### Wave 2 (coordination/logistics) invariant

Per this batch's wave-2 guidance: any op finalizing an airport/
ground-safety-clearance decision (e.g. clearing a ramp area as safe after
a reported hazard, overriding a de-icing protocol) is a hard, permanent
block, never auto-commit-eligible — check 3 above enforces this
unconditionally and un-overridably by a human approver, on top of the
closed op-allowlist already making such an op structurally unreachable.
`:flag-ramp-safety-concern` is a permanent structural absence from every
phase's `:auto` set (`groundops.phase`) as well as a permanent member of
the governor's own `high-stakes` set (`groundops.governor`) — two
independent layers agree that a ramp-safety concern always reaches a
human, and no phase, now or in any future entry, may ever auto-commit it.

## Decision

### 1. Module Identity

- **ID**: `cloud-itonami-isic-5223`
- **ISIC Code**: 5223 (Service activities incidental to air
  transportation)
- **Public Repo**: https://github.com/cloud-itonami/cloud-itonami-isic-5223
  (pre-existing, filled in — not newly created)
- **Business-ID**: `cloud-itonami-5223` (unchanged; matches the
  `cloud-itonami-<ISIC>` — no `-isic-` infix — pattern already used by
  every sibling entry in the 50xx–52xx transport-support ISIC block)
- **Governor**: `:airport-operations-governor` (from the pre-existing
  `blueprint.edn`)

### 2. Operation Allowlist (Closed)

1. `:log-service-record` — De-icing/baggage-handling/ramp-service data
   logging
2. `:schedule-ground-operation` — Ramp/gate/de-icing scheduling proposal
3. `:flag-ramp-safety-concern` — Ramp-safety-concern flag (ALWAYS
   escalates)
4. `:coordinate-equipment-maintenance` — Ground-support-equipment
   maintenance coordination

Any operation outside this set is rejected (closed allowlist, check 1).

### 3. Phase Progression (0→3)

- **Phase 0** (read-only): all proposals held for human review
- **Phase 1** (assisted-logging): service-record logging +
  ramp-safety-concern flagging allowed, every write needs approval
- **Phase 2** (assisted-coord): + ramp/gate/de-icing scheduling and
  equipment-maintenance-coordination writes, still approval
- **Phase 3** (supervised-auto): `:log-service-record` (no capital risk,
  no ramp-safety determination) may auto-commit when governor-clean;
  `:schedule-ground-operation` and `:coordinate-equipment-maintenance`
  (real resource commitments) ALWAYS need human approval even when
  governor-clean; `:flag-ramp-safety-concern` ALWAYS escalates — never in
  `:auto` at any phase

### 4. Deliverables

**Files committed to `cloud-itonami-isic-5223` main** (on top of the
pre-existing boilerplate — `CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/
`GOVERNANCE.md`/`LICENSE`/`README.md`/`SECURITY.md`/`blueprint.edn`(field
addition only)/`docs/business-model.md`/`docs/operator-guide.md` were
kept unchanged):

```
.gitignore
deps.edn
docs/adr/0001-architecture.md
src/groundops/
  - facts.cljc (per-jurisdiction spec-basis catalog)
  - store.cljc (SSoT: service directory, MemStore + DatomicStore)
  - registry.cljc (pure record-draft construction)
  - groundopsllm.cljc (deterministic mock proposal generator)
  - governor.cljc (six HARD checks)
  - operation.cljc (langgraph-clj StateGraph orchestration)
  - phase.cljc (rollout phases)
  - sim.cljc (demo runner)
test/groundops/
  - facts_test.clj
  - governor_contract_test.clj (includes the dedicated self-trip
    regression test)
  - phase_test.clj
  - registry_test.clj
  - store_contract_test.clj
```

### 5. Verification (Real Output)

**Tests via `clojure -M:test`** (raw, unedited final line):
```
Ran 36 tests containing 164 assertions.
0 failures, 0 errors.
```

**Lint via `clojure -M:lint`**: `linting took 392ms, errors: 0, warnings: 0`

**Demo via `clojure -M:dev:run`**: all scenarios run to completion offline
— clean `:log-service-record` auto-commits at phase 3;
`:schedule-ground-operation`/`:coordinate-equipment-maintenance`/
`:flag-ramp-safety-concern` escalate then commit after simulated human
approval; no-spec-basis jurisdiction / unverified facility / already-open
ramp-hazard each HOLD independently while `:flag-ramp-safety-concern`
stays reachable on the open-hazard engagement.

**Push**: landed on `cloud-itonami/cloud-itonami-isic-5223` `main` at
commit `6f97734cefb2401f8430f51f4eaf1cfa78a671f0` (fast-forward from the
pre-existing tip `5debf49`).

**Repository**: Public, all files present via GitHub API re-verification
after push; a fresh independent re-clone into a new scratch directory
re-ran `clojure -M:test` and reproduced the same `36 tests / 164
assertions / 0 failures / 0 errors` result.

### 6. Registry Update

**File**: `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`

**Entry before** (no `:maturity` key at all — resolves to `:blueprint` via
the `:repo`-set fallback in `kotoba.industry/maturity-of`):
```clojure
{:id "5223", :name "Service activities incidental to air transportation",
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-5223",
 :business-id "cloud-itonami-5223",
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics],
 :optional-technologies [:optimization],
 :operating-states [:intake :book :transit :deliver :reconcile :audit]}
```

**Entry after** (`:maturity :implemented` added explicitly, `:repo`/
`:business-id` unchanged — already correct):
```clojure
{:id "5223", :name "Service activities incidental to air transportation",
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-5223",
 :business-id "cloud-itonami-5223",
 :maturity :implemented,
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics],
 :optional-technologies [:optimization],
 :operating-states [:intake :book :transit :deliver :reconcile :audit]}
```

**Registry validated**: `kotoba.industry/maturity-summary`'s
`:implemented` count recomputed from the live file (not `grep -c`) and
`test/kotoba/industry_test.clj`'s assertion bumped to match; full suite
re-run green post-merge (raw output in the final task report).

## Consequences

### Positive

- ISIC-5223 Wave 2 coordination/logistics-cluster actor now complete,
  filling in a legitimate pre-existing blueprint-tier registry entry
  rather than leaving it permanently unimplemented
- Coordination-only actor pattern (Governor + closed allowlist + hard
  checks + phase gate) proven reusable again in this exact family,
  distinguishing an airport ground-handling INFRASTRUCTURE/SERVICE
  business from both the airline carrier (5110) and the cargo-handling
  sibling (5224) built in parallel in this same batch
- No ramp/ground-safety-clearance finalization authority anywhere in the
  actor — structurally unreachable (closed allowlist) and defended in
  depth (text-scan check), not merely policy
- Dedicated regression test locks in the fleet-wide self-tripping-bug fix
  for this repo specifically

### Risks & Mitigations

- **Regulation variance**: airport/ground-handling safety regimes vary by
  jurisdiction (ICAO Annex 14, US FAA Part 139, EU Regulation 139/2014 +
  Directive 96/67/EC, Japan's 空港法, UK Airports (Groundhandling)
  Regulations 1997 — see the pre-existing `README.md`). This actor
  handles **administrative coordination only** — deployment requires
  local compliance review and a real airport/ground-safety authority
  retaining all ramp-clearance/de-icing-protocol/equipment-release
  decisions.
- **Escalation-infrastructure dependency**: `:flag-ramp-safety-concern`
  always escalates to human sign-off — deployment must ensure
  human-review infrastructure exists and is monitored with appropriate
  urgency for ramp-safety-relevant signals.

## References

- ADR-2607121000 (wave definition, value function, inverse topological
  sort)
- ADR-2628000000 (`cloud-itonami-isic-5110`, passenger air transport —
  sibling blueprint-fill-in this build mirrors most closely, same
  known-self-tripping-bug-class fix)
- ADR-2628071600 (`cloud-itonami-isic-5222`, water transport support —
  sibling coordination-actor pattern in the same 50xx–52xx block, built
  earlier in this same batch)
- Skill `build-actor` (actor pattern, langgraph-clj StateGraph, Governor,
  audit ledger)

---

**Draft & verification completed**: 2026-07-16
**Co-Author**: Claude Sonnet 5
