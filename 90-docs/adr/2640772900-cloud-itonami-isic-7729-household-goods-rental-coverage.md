# ADR-2640772900: cloud-itonami ISIC-7729 Household-Goods-Rental Operations-Coordination Actor — Blueprint Fill-In & Registry Promotion

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-7729` (pre-existing blueprint-tier repo, filled in), `orgs/kotoba-lang/industry` registry (ISIC-7729 promotion), Wave 4 (person-facing-service) cluster

## Context

ISIC 7729 ("Renting and leasing of other personal and household goods")
covers furniture, appliances and party/event-equipment rental to
consumers — a residual category distinct from `cloud-itonami-isic-7721`
(recreational and sports goods, built by a sibling agent in this same
batch) and `cloud-itonami-isic-7710` (motor vehicles). The `cloud-itonami/
cloud-itonami-isic-7729` repo already existed on GitHub (created
2026-07-10) as a legitimate `:blueprint`-tier registry entry from an
earlier bulk-scaffolding pass: `blueprint.edn`, `README.md`,
`docs/business-model.md`, `docs/operator-guide.md`, `CODE_OF_CONDUCT.md`,
`CONTRIBUTING.md`, `GOVERNANCE.md`, `LICENSE`, `SECURITY.md` were present,
but no `deps.edn`/`src`/`test` — the repo was published but never
implemented. This ADR records filling that repo in, not creating it
fresh; the pre-existing boilerplate docs were kept unchanged.

The pre-existing `README.md`/`docs/*.md` sketch a broader rent-to-own /
disclosure-compliance business vision than what this implementation
builds. This ADR deliberately implements the narrower, explicitly-scoped
operations-coordination actor requested for this batch (mirroring the
sibling `cloud-itonami-isic-7730` machinery/equipment-rental actor's
architecture) rather than the fuller rent-to-own vision the boilerplate
docs describe — a scope note worth flagging for any future work that
extends this repo toward disclosure/rental-purchase-agreement management.

### Reference repo

`cloud-itonami-isic-7730` (Renting and leasing of other machinery,
equipment and tangible goods n.e.c.) was cloned and read in full as the
architecture reference — confirmed `:maturity :implemented` in the
registry, with real `deps.edn` + `src/equiprentalops/*.cljc` +
`test/equiprentalops/*.clj` and a green `clojure -M:dev:test`. The sibling
`cloud-itonami-isic-7721` (recreational/sports goods, expected to be
built by a sibling agent in this same batch) was checked and confirmed
still blueprint-tier-only at the time of this work, so it was not usable
as a code reference.

### Domain Scope

Household-goods-rental (furniture, appliances, party/event equipment)
operations coordination — NOT direct equipment-safety-clearance
authority:
- Rental-record logging (checkout/return/inspection-note data)
- Fleet-operation scheduling (equipment-availability/delivery scheduling
  proposal)
- Equipment-safety-concern flagging (defect/damage/electrical-hazard —
  ALWAYS escalates)
- Fleet-restock coordination (equipment procurement/replacement
  coordination)

### Actor Pattern (Module Shape)

- **Store** (`hgrentalops.store`): SSoT, `units` directory keyed by
  string `:asset-id`, append-only `ledger` + `coordination-log`.
  `MemStore` (default, in-memory)
- **Advisor** (`hgrentalops.advisor`): deterministic mock proposal
  generator for the four ops (real-LLM seam via the `Advisor` protocol)
- **Governor** (`hgrentalops.governor`): three HARD, permanent,
  un-overridable checks + one soft escalate gate
- **Operation** (`hgrentalops.operation`): langgraph-clj StateGraph
  orchestration (intake → advise → govern → decide → commit | hold |
  request-approval)
- **Phase** (`hgrentalops.phase`): rollout phases 0–3
- **Sim** (`hgrentalops.sim`): deterministic demo runner
- **Tests**: `advisor_test.clj`, `governor_test.clj`,
  `governor_contract_test.clj`, `phase_test.clj`,
  `store_contract_test.clj` — 49 tests / 160 assertions, all passing

### Governor: Three HARD Checks (Un-overridable)

**Check 1: Asset unverified**
- The target rental-account/equipment record must exist AND be
  independently `:registered?` AND `:verified?` in the store, re-derived
  from the store every time, never trusting the proposal's own
  `:asset-id` claim.

**Check 2: Effect not `:propose`**
- The proposal's own `:effect` must be `:propose`; any other value is a
  claim to directly actuate/commit outside governance.

**Check 3: Scope exclusion (closed allowlist + finalize-clearance scan)**
- An op outside the closed four-op allowlist, OR text (op/summary/
  rationale/cites/value) that touches directly finalizing an
  equipment-safety-clearance decision or overriding an
  equipment-safety-authority decision, is a hard, permanent block. This
  territory does not exist as an op in this actor's allowlist at all
  (structurally unreachable) — this check is defense-in-depth against a
  real LLM advisor smuggling a finalization claim into an otherwise-
  legitimate proposal's own text.

### Wave 4 person-facing-service safety guardrail (ADR-2607152500)

Household-goods rental (furniture, appliances, party/event equipment)
has a modest but real user-safety dimension: electrical-appliance
defects, party-equipment structural issues. Per this batch's Wave 4
guidance, the closed op-allowlist NEVER includes any op that directly
finalizes an equipment-safety-clearance decision — every op is `:effect
:propose` only, and `:flag-equipment-safety-concern` is a permanent
structural absence from every phase's `:auto` set
(`hgrentalops.phase/phases`) as well as a permanent member of the
governor's own `always-escalate-ops` set (`hgrentalops.governor`) — two
independent layers agree that an equipment-safety concern always reaches
a human, at every phase, with no override path.

### Self-tripping-bug discipline (fleet-wide known pattern)

Multiple sibling `cloud-itonami-isic-*` actors in this fleet independently
discovered and fixed the SAME bug class: phrasing a governor's
scope-exclusion term list as a bare noun ("safety", "clearance") makes it
match inside the mock advisor's own DEFAULT rationale/disclaimer text for
a legitimate, allowed proposal — e.g. a `:flag-equipment-safety-concern`
proposal's own honest rationale legitimately uses the words "safety" and
"再貸出" as nouns, so a bare-noun exclusion list would self-block the
actor's own happy path. `hgrentalops.governor/scope-excluded-terms` is
phrased as the FINALIZATION/EXECUTION ACTION ("finalize the
equipment-safety clearance", "certify safe to re-rent without
inspection", "override the equipment safety authority" — never the bare
noun), and `test/hgrentalops/advisor_test.clj`'s
`default-mock-advisor-proposals-never-self-trip-scope-exclusion` (plus
`test/hgrentalops/governor_test.clj`'s
`guest-facing-default-advisor-proposals-never-scope-excluded-end-to-end`
and `legitimate-equipment-safety-concern-is-not-scope-excluded`) assert
directly that every default-advisor proposal, across all four ops and a
variety of realistic patches (including electrical-hazard and
frame/structural-defect concerns), never trips `:scope-excluded`, while
`reclear-without-inspection-is-hard-and-permanent` /
`finalize-safety-clearance-content-is-hard` /
`override-equipment-safety-authority-content-is-hard` confirm the same
patterns DO catch a genuine finalization-action attempt.

## Decision

### 1. Module Identity

- **ID**: `cloud-itonami-isic-7729`
- **ISIC Code**: 7729 (Renting and leasing of other personal and
  household goods)
- **Public Repo**: https://github.com/cloud-itonami/cloud-itonami-isic-7729
  (pre-existing, filled in — not newly created)
- **Business-ID**: `cloud-itonami-7729` (unchanged; matches the
  `cloud-itonami-<ISIC>` — no `-isic-` infix — pattern already used by
  the sibling `cloud-itonami-7721` entry)

### 2. Operation Allowlist (Closed)

1. `:log-rental-record` — checkout/return/inspection-note data logging
2. `:schedule-fleet-operation` — equipment-availability/delivery
   scheduling proposal
3. `:flag-equipment-safety-concern` — defect/damage/electrical-hazard
   concern flag (ALWAYS escalates)
4. `:coordinate-fleet-restock` — equipment procurement/replacement
   coordination

Any operation outside this set is rejected (Check 3, `:op-not-allowed`).

### 3. Phase Progression (0→3)

- **Phase 0** (read-only): all proposals held for human review
- **Phase 1** (assisted-logging): rental-record logging only, every
  write needs approval
- **Phase 2** (assisted-coordination): + fleet-operation scheduling,
  fleet-restock coordination, still approval
- **Phase 3** (supervised-auto): `:log-rental-record`/
  `:schedule-fleet-operation`/`:coordinate-fleet-restock` may auto-commit
  when governor-clean and high-confidence; `:flag-equipment-safety-
  concern` ALWAYS escalates — never in `:auto` at any phase. A
  `:coordinate-fleet-restock` proposal above the $2000 cost threshold
  likewise always escalates regardless of phase or confidence.

### 4. Deliverables

**Files committed to `cloud-itonami-isic-7729` main** (on top of the
pre-existing boilerplate — `CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/
`GOVERNANCE.md`/`LICENSE`/`README.md`/`SECURITY.md`/`blueprint.edn`/
`docs/` were kept unchanged):

```
deps.edn
src/hgrentalops/
  - store.cljc (SSoT: MemStore, string-keyed asset directory, append-only ledger)
  - advisor.cljc (deterministic mock proposal generator, real-LLM seam)
  - governor.cljc (three HARD checks + soft escalate gate)
  - operation.cljc (langgraph-clj StateGraph orchestration)
  - phase.cljc (rollout phases)
  - sim.cljc (demo runner)
test/hgrentalops/
  - advisor_test.clj (proposal shape + dedicated self-trip regression test)
  - governor_test.clj (unit tests of governor/check, incl. scope-exclusion regression)
  - governor_contract_test.clj (full-graph integration, audit trail)
  - phase_test.clj (rollout phase logic)
  - store_contract_test.clj (Store protocol / MemStore)
```

### 5. Verification (Real Output)

**Tests via `clojure -M:dev:test`** (raw, unedited final line):
```
Ran 49 tests containing 160 assertions.
0 failures, 0 errors.
```

**Lint via `clojure -M:lint`**: `linting took 505ms, errors: 0, warnings: 0`

**Demo via `clojure -M:dev:run`**: all scenarios run to completion offline
— clean `:log-rental-record` escalates at phase 1 then commits on
approval, auto-commits at phase 3; `:schedule-fleet-operation`/
`:coordinate-fleet-restock` auto-commit clean at phase 3;
over-cost-threshold `:coordinate-fleet-restock` and
`:flag-equipment-safety-concern` escalate then commit after simulated
human approval; unregistered asset / registered-but-unverified asset /
non-`:propose` effect / scope-excluded content each HOLD independently
and permanently.

**Push**: landed on `cloud-itonami/cloud-itonami-isic-7729` `main` at
commit `6cd65196bbdc18b958dfdc4d0e8f60c73f1e65b4` (fast-forward from the
pre-existing tip `272cec5`).

**Post-push re-verification**: fresh clone of `main` re-run
(`clojure -M:dev:test`) — same green result, confirming nothing was lost
in the push.

### 6. Registry Update

**File**: `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`

**Entry before** (no `:maturity` key at all — resolves to `:blueprint` via
the `:repo`-set fallback in `kotoba.industry/maturity-of`):
```clojure
{:id "7729", :name "Renting and leasing of other personal and household goods",
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-7729",
 :business-id "cloud-itonami-7729",
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :labor],
 :optional-technologies [:optimization],
 :operating-states [:intake :register :match :dispatch :follow-up :audit]}
```

**Entry after** (`:maturity :implemented` added explicitly, `:repo`/
`:business-id` unchanged — already correct):
```clojure
{:id "7729", :name "Renting and leasing of other personal and household goods",
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-7729",
 :business-id "cloud-itonami-7729",
 :maturity :implemented,
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :labor],
 :optional-technologies [:optimization],
 :operating-states [:intake :register :match :dispatch :follow-up :audit]}
```

**Registry validated**: `kotoba.industry/maturity-summary`'s
`:implemented` count recomputed from the live file (not `grep -c`) and
`test/kotoba/industry_test.clj`'s assertion bumped to match; full suite
re-run green post-merge (see final report for raw output).

## Consequences

### Positive

- ISIC-7729 Wave 4 (person-facing-service) actor now complete, filling
  in a legitimate pre-existing blueprint-tier registry entry rather than
  leaving it permanently unimplemented
- Coordination-only actor pattern (Governor + closed allowlist + hard
  checks + phase gate) reused a fourth time in this exact fleet family
  (7730 → 7729), keeping the shared idiom consistent across siblings
- No equipment-safety-clearance authority anywhere in the actor —
  structurally unreachable (closed allowlist) and defended in depth
  (text-scan check), not merely policy
- Dedicated regression tests lock in the fleet-wide self-tripping-bug
  fix for this repo specifically

### Risks & Mitigations

- **Boilerplate/implementation scope gap**: the pre-existing README/docs
  describe a broader rent-to-own / disclosure-compliance business vision
  than this narrower operations-coordination implementation covers. A
  future extension that wants the fuller disclosure/rental-purchase-
  agreement scope described in the boilerplate docs should treat this as
  a separate follow-up, not assume it is already covered.
- **Escalation-infrastructure dependency**: `:flag-equipment-safety-
  concern` always escalates to human sign-off — deployment must ensure
  human-review infrastructure exists and is monitored with appropriate
  urgency for safety-relevant signals.

## References

- ADR-2607121000 (wave definition, value function, inverse topological
  sort)
- ADR-2607152500 (Wave 4 person-facing-service safety guardrail)
- Skill `build-actor` (actor pattern, langgraph-clj StateGraph, Governor,
  audit ledger)

---

**Draft & verification completed**: 2026-07-16
**Co-Author**: Claude Sonnet 5
