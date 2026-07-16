# ADR-2628071600: cloud-itonami ISIC-5222 Port/Harbor-Support-Services Operations-Coordination Actor — Blueprint Fill-In & Registry Promotion

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-5222` (pre-existing blueprint-tier repo, filled in), `orgs/kotoba-lang/industry` registry (ISIC-5222 promotion), Wave 2 coordination/logistics cluster

## Context

ISIC 5222 ("Service activities incidental to water transportation") is a
port/harbor SUPPORT-services vertical: pilotage, towage, port-facility
operation, navigation-aid maintenance — NOT water transport itself. The
`cloud-itonami/cloud-itonami-isic-5222` repo already existed on GitHub
(created 2026-07-09) as a legitimate `:blueprint`-tier registry entry from
an earlier bulk-scaffolding pass: `blueprint.edn`, `README.md`,
`docs/business-model.md`, `docs/operator-guide.md`, `CODE_OF_CONDUCT.md`,
`CONTRIBUTING.md`, `GOVERNANCE.md`, `LICENSE`, `SECURITY.md` were present,
but no `deps.edn`/`src`/`test` — the repo was published but never
implemented. This ADR records filling that repo in, not creating it fresh.

The pre-existing `blueprint.edn` already declared
`:itonami.blueprint/governor :port-authority-governor` and the pre-existing
`README.md`/`docs/*.md` already established the domain vocabulary this
implementation follows: "Port Authority Advisor" / "Port Authority
Governor", "navigation-safety scope", "pilotage fitness-check",
"vessel-movement booking", "berth/anchorage monitoring", "salvage
coordination", "reconciliation record". `README.md`'s own "Scope note"
section already distinguishes this repo from `cloud-itonami-isic-5011`
(passenger ferry, a CARRIER) and `cloud-itonami-isic-5020` (marine
cargo/tanker, a CARRIER) and `cloud-itonami-isic-5224` (cargo handling, a
terminal SERVICE) — this repo is the separate business of port authority /
harbor operations infrastructure and navigation-safety authority,
independent of any single vessel operator or cargo terminal.

### Reference-repo correction

The build task's reference pointer (`cloud-itonami-isic-5011`, described as
"VERIFIED working, independently re-tested") was checked before use and
found to be ALSO blueprint-tier only (no `deps.edn`/`src`/`test` on
`main`, single commit, no other branches) — the premise did not match
reality. `cloud-itonami-isic-5020` (Water freight transport / tanker,
`:maturity :implemented` in the registry, real `deps.edn` +
`src/tanker/*.cljc` + `test/tanker/*.clj` + green `clojure -M:test`) was
used as the actual architecture reference instead, plus
`cloud-itonami-isic-561` (Restaurant Operations Coordination) for the
coordination-actor / scope-exclusion-check idiom, since 5222 is a
coordination actor like 561, not an actuation actor like the tanker.

### Domain Scope

Port/harbor-support-services operations coordination — NOT direct
navigation-safety authority or vessel/facility control:
- Pilotage/towage/berth-assignment service-record logging (administrative
  data logging only)
- Berth/tug/pilot scheduling proposals (proposal only, never a vessel-
  movement authorization)
- Navigation-safety-concern flagging (a reported berth hazard or
  navigation-aid fault — ALWAYS escalates to human sign-off)
- Navigation-aid/facility maintenance coordination (coordination only,
  never a navigation-aid fitness certification)

### Actor Pattern (Module Shape)

- **Store** (`portauthority.store`): SSoT with `clearance`/`facility`
  entity directories + four append-only logs (service/schedule/
  maintenance/concern) + audit ledger. `MemStore` (default) and
  `DatomicStore` (langchain.db-backed, using `kotoba-lang/langchain-store`
  as the shared entity-store adopter per ADR-2607141600 — no hand-rolled
  `enc`/`dec*` copy)
- **Registry** (`portauthority.registry`): pure-function draft-record
  construction (unsigned, `:kind :*-draft`) for the four record kinds
- **Advisor** (`portauthority.advisor`): deterministic mock proposal
  generator for the four ops
- **Governor** (`portauthority.governor`): four HARD, permanent,
  un-overridable checks
- **Operation** (`portauthority.operation`): langgraph-clj StateGraph
  orchestration (intake → advise → govern → decide → commit | hold |
  request-approval)
- **Phase** (`portauthority.phase`): rollout phases 0–3
- **Sim** (`portauthority.sim`): deterministic demo runner
- **Tests**: `governor_contract_test.clj`, `phase_test.clj`,
  `registry_test.clj`, `store_contract_test.clj`,
  `scope_exclusion_test.clj` — 34 tests / 190 assertions, all passing

### Governor: Four HARD Checks (Un-overridable)

**Check 1: Clearance/facility unverified**
- Target `clearance` record (for `:log-service-record`,
  `:schedule-port-operation`, `:flag-navigation-safety-concern`) must be
  independently `:registered?` AND `:verified?` in the store, re-derived
  from the REQUEST every time, never from the proposal's self-report
- `:coordinate-maintenance` is facility-level (the same "facility-level
  ops don't need per-vessel verification" exemption
  `cloud-itonami-isic-561`'s governor establishes for its own
  non-reservation ops) — independently re-verifies the target `facility`
  is `:registered?` instead

**Check 2: Effect not `:propose`**
- The proposal's own `:effect` must be `:propose`, whatever the advisor
  claims elsewhere

**Check 3: Closed op-allowlist**
- The proposal's own `:operation` must be one of the four allowed ops;
  anything else (including a hallucinated op a real LLM advisor might
  propose) is a hard, permanent block

**Check 4: Finalize-clearance scope exclusion**
- ANY proposal whose text tries to finalize a berth/navigation-safety
  clearance, waive a pilotage requirement, or authorize a vessel movement
  is a hard, permanent block. This territory does not exist as an op in
  this actor's allowlist at all (check 3 already makes it structurally
  unreachable) — this check is defense-in-depth against a real LLM
  advisor smuggling a finalization claim into an otherwise-legitimate
  proposal's own `:summary`/`:rationale` text

### Self-tripping-bug discipline (fleet-wide known pattern)

Multiple sibling `cloud-itonami-isic-*` actors in this fleet independently
discovered and fixed the SAME bug class: phrasing a governor's
scope-exclusion term list as a bare noun ("safety", "clearance",
"pilotage") makes it match inside the mock advisor's own DEFAULT
rationale/disclaimer text for a legitimate, allowed proposal — e.g. a
`:flag-navigation-safety-concern` proposal's own honest rationale
legitimately uses the words "navigation safety concern" and "pilotage" as
NOUNS, so a bare-noun exclusion list would self-block the actor's own
happy path. `portauthority.governor/finalize-clearance-patterns` is
phrased as the FINALIZATION/EXECUTION ACTION ("finalize the berth-safety
clearance", "waive the pilotage requirement", "authorize the vessel
movement" — never the bare noun), and
`test/portauthority/scope_exclusion_test.clj` asserts directly that all
ten default-advisor proposal cases (across all four ops and all
clearance/facility fixtures, including the deliberately-unverified/
unregistered ones) never trip `:finalize-clearance-attempt`, plus a
sanity check that the same patterns DO catch a genuine finalization-action
attempt (both EN and JA phrasing).

### Wave 2 (coordination/logistics) invariant

Per this batch's wave-2 guidance: no op that would finalize a
port/navigation-safety-clearance decision exists ANYWHERE in this
domain's op set (governor check 3 + check 4, belt-and-suspenders), and
`:flag-navigation-safety-concern` is a permanent structural absence from
every phase's `:auto` set (`portauthority.phase`) as well as a permanent
member of the governor's own `high-stakes` set (`portauthority.governor`)
— two independent layers agree that a navigation-safety concern always
reaches a human, and no phase, now or in any future entry, may ever
auto-commit it.

## Decision

### 1. Module Identity

- **ID**: `cloud-itonami-isic-5222`
- **ISIC Code**: 5222 (Service activities incidental to water
  transportation)
- **Public Repo**: https://github.com/cloud-itonami/cloud-itonami-isic-5222
  (pre-existing, filled in — not newly created)
- **Business-ID**: `cloud-itonami-5222` (unchanged; matches the
  `cloud-itonami-<ISIC>` — no `-isic-` infix — pattern already used by
  every sibling entry in the 50xx–52xx transport-support ISIC block)
- **Governor**: `:port-authority-governor` (from the pre-existing
  `blueprint.edn`)

### 2. Operation Allowlist (Closed)

1. `:log-service-record` — Pilotage/towage/berth-assignment data logging
2. `:schedule-port-operation` — Berth/tug/pilot scheduling proposal
3. `:flag-navigation-safety-concern` — Navigation-safety-concern flag
   (ALWAYS escalates)
4. `:coordinate-maintenance` — Navigation-aid/facility maintenance
   coordination

Any operation outside this set is rejected (closed allowlist, check 3).

### 3. Phase Progression (0→3)

- **Phase 0** (read-only): all proposals held for human review
- **Phase 1** (assisted-logging): service-record logging +
  navigation-safety-concern flagging allowed, every write needs approval
- **Phase 2** (assisted-scheduling): + berth/tug/pilot scheduling
  proposal writes, still approval
- **Phase 3** (supervised-auto): `:log-service-record` (no capital risk,
  no navigation-safety determination) may auto-commit when
  governor-clean; `:schedule-port-operation` and `:coordinate-maintenance`
  (real resource commitments) ALWAYS need human approval even when
  governor-clean; `:flag-navigation-safety-concern` ALWAYS escalates —
  never in `:auto` at any phase

### 4. Deliverables

**Files committed to `cloud-itonami-isic-5222` main** (on top of the
pre-existing boilerplate — `CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/
`GOVERNANCE.md`/`LICENSE`/`README.md`/`SECURITY.md`/`blueprint.edn`/
`docs/` were kept unchanged):

```
.gitignore
deps.edn
src/portauthority/
  - store.cljc (SSoT: clearance/facility directories, MemStore + DatomicStore)
  - registry.cljc (pure record-draft construction)
  - advisor.cljc (deterministic mock proposal generator)
  - governor.cljc (four HARD checks)
  - operation.cljc (langgraph-clj StateGraph orchestration)
  - phase.cljc (rollout phases)
  - sim.cljc (demo runner)
test/portauthority/
  - governor_contract_test.clj
  - phase_test.clj
  - registry_test.clj
  - store_contract_test.clj
  - scope_exclusion_test.clj (dedicated self-trip regression test)
```

### 5. Verification (Real Output)

**Tests via `clojure -M:dev:test`** (raw, unedited final line):
```
Ran 34 tests containing 190 assertions.
0 failures, 0 errors.
```

**Lint via `clojure -M:lint`**: `linting took 580ms, errors: 0, warnings: 0`

**Demo via `clojure -M:dev:run`**: all scenarios run to completion offline
— clean `:log-service-record` auto-commits at phase 3;
`:schedule-port-operation`/`:coordinate-maintenance`/
`:flag-navigation-safety-concern` escalate then commit after simulated
human approval; unverified clearance / unregistered clearance /
unregistered facility each HOLD independently; the defense-in-depth
`governor/check` calls directly demonstrate a hallucinated op
(`:op-not-allowed`) and a smuggled finalize-clearance phrase
(`:finalize-clearance-attempt`) both HARD-hold.

**Push**: landed on `cloud-itonami/cloud-itonami-isic-5222` `main` at
commit `9d2ede4c852712b76bbeafdde75781eada7f851f` (fast-forward from the
pre-existing tip `d014e0d`).

**Repository**: Public, all files present via GitHub API re-verification
after push.

### 6. Registry Update

**File**: `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`

**Entry before** (no `:maturity` key at all — resolves to `:blueprint` via
the `:repo`-set fallback in `kotoba.industry/maturity-of`):
```clojure
{:id "5222", :name "Service activities incidental to water transportation",
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-5222",
 :business-id "cloud-itonami-5222",
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics],
 :optional-technologies [:optimization],
 :operating-states [:intake :book :transit :deliver :reconcile :audit]}
```

**Entry after** (`:maturity :implemented` added explicitly, `:repo`/
`:business-id` unchanged — already correct):
```clojure
{:id "5222", :name "Service activities incidental to water transportation",
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-5222",
 :business-id "cloud-itonami-5222",
 :maturity :implemented,
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics],
 :optional-technologies [:optimization],
 :operating-states [:intake :book :transit :deliver :reconcile :audit]}
```

**Registry validated**: `kotoba.industry/maturity-summary`'s
`:implemented` count recomputed from the live file (not `grep -c`) and
`test/kotoba/industry_test.clj`'s assertion bumped to match; full suite
re-run green post-merge (see final report for raw output).

## Consequences

### Positive

- ISIC-5222 Wave 2 coordination/logistics-cluster actor now complete,
  filling in a legitimate pre-existing blueprint-tier registry entry
  rather than leaving it permanently unimplemented
- Coordination-only actor pattern (Governor + closed allowlist + hard
  checks + phase gate) proven reusable a third time in this exact family
  (561 → 5222), with a fourth check (facility-level exemption + scope-
  exclusion phrased as action-not-noun) added on top
- No navigation-safety-clearance authority anywhere in the actor —
  structurally unreachable (closed allowlist) and defended in depth
  (text-scan check), not merely policy
- Dedicated regression test (`scope_exclusion_test.clj`) locks in the
  fleet-wide self-tripping-bug fix for this repo specifically

### Risks & Mitigations

- **Regulation variance**: port authority / harbor safety regimes vary by
  jurisdiction (IMO SOLAS Ch. V, Japan's 港湾法, UK Harbours Act 1964 /
  Port Marine Safety Code, US Ports and Waterways Safety Act, UK Pilotage
  Act 1987 — see the pre-existing `README.md`). This actor handles
  **administrative coordination only** — deployment requires local
  compliance review and a real navigation-safety authority (harbor
  master / Captain of the Port) retaining all clearance/pilotage-waiver/
  vessel-movement-authorization decisions.
- **Escalation-infrastructure dependency**: `:flag-navigation-safety-
  concern` always escalates to human sign-off — deployment must ensure
  human-review infrastructure exists and is monitored with appropriate
  urgency for safety-relevant signals.

## References

- ADR-2607121000 (wave definition, value function, inverse topological
  sort)
- ADR-2607155100 (isic-561 restaurant coordination, sibling coordination-
  actor pattern + scope-exclusion idiom)
- ADR-2607141600 (`kotoba-lang/langchain-store` entity-store adopter
  pattern)
- Skill `build-actor` (actor pattern, langgraph-clj StateGraph, Governor,
  audit ledger)

---

**Draft & verification completed**: 2026-07-16
**Co-Author**: Claude Sonnet 5
