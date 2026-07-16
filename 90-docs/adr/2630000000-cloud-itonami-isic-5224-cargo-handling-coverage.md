# ADR-2630000000: cloud-itonami ISIC-5224 Cargo-Handling Operations-Coordination Actor — Blueprint Fill-In & Registry Promotion

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-5224` (pre-existing blueprint-tier repo, filled in), `orgs/kotoba-lang/industry` registry (ISIC-5224 promotion), Wave 2 coordination/logistics cluster

## Context

ISIC 5224 ("Cargo handling") is a terminal/stevedoring SERVICE vertical:
loading, unloading and handling cargo at ports, airports and rail yards on
behalf of multiple carriers — NOT the carriage itself. The
`cloud-itonami/cloud-itonami-isic-5224` repo already existed on GitHub
(created 2026-07-09) as a legitimate `:blueprint`-tier registry entry from
an earlier bulk-scaffolding pass: `blueprint.edn`, `README.md`,
`docs/business-model.md`, `docs/operator-guide.md`, `CODE_OF_CONDUCT.md`,
`CONTRIBUTING.md`, `GOVERNANCE.md`, `LICENSE`, `SECURITY.md` were present,
but no `deps.edn`/`src`/`test` — the repo was published but never
implemented. This ADR records filling that repo in, not creating it fresh
(confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-5224` before
any work began).

The pre-existing `blueprint.edn` already declared
`:itonami.blueprint/governor :cargo-handling-governor` and
`:itonami.blueprint/domain :transport/cargo-handling`; the pre-existing
`README.md`'s own "Scope note" section already distinguishes this repo
from the transport CARRIERS (`cloud-itonami-isic-5020` marine cargo/
tanker, `cloud-itonami-isic-4912` freight rail, `cloud-itonami-isic-4920`
road freight, `cloud-itonami-isic-5110` passenger air,
`cloud-itonami-isic-4911`/`cloud-itonami-isic-5011` passenger rail/water)
— this repo is the SEPARATE business of cargo handling: a fixed-location
terminal/stevedoring SERVICE, typically under its own independent
licensing regime (Japan's 港湾運送事業法 licenses stevedoring companies
separately from shipping lines; US longshoring is regulated under OSHA 29
CFR Parts 1917/1918 independently of vessel-operator safety rules; the
ISPS Code and ILO Convention 152 (Dock Work) both regulate the terminal/
dock-work side).

The registry entry `{:id "5224" ...}` was independently verified against
a fresh clone of `kotoba-lang/industry` before any work began: `:name
"Cargo handling"`, `:business-id "cloud-itonami-5224"`, `:repo
"https://github.com/cloud-itonami/cloud-itonami-isic-5224"` — matches the
pre-existing `blueprint.edn`'s own `:itonami.blueprint/id
"cloud-itonami-5224"`, no mismatch, no truncation.

### Reference repo

`cloud-itonami-isic-5222` (Service activities incidental to water
transportation, "Port/Harbor-Support-Services Operations-Coordination
Actor", ADR-2628071600) was used as the direct architecture reference —
same coordination-actor idiom (Store/Registry/Advisor/Governor/Operation/
Phase/Sim), same clearance/facility-style entity-pair shape (here:
shipment/facility), same `kotoba-lang/langchain-store` entity-store
adopter pattern for the Datomic-backed store half (ADR-2607141600, no
hand-rolled `enc`/`dec*` copy).

### Domain Scope

Cargo-handling operations coordination — NOT direct load-safety authority
or crane/handling-equipment control, and NOT the transport carrier
itself:
- Load/unload/manifest cargo-record logging (administrative data logging
  only)
- Crane/dock/warehouse handling-operation scheduling proposals (proposal
  only, never an equipment-dispatch authorization)
- Cargo-safety-concern flagging (weight-limit exceedance, hazmat-
  segregation issue, or load-securing failure — ALWAYS escalates to human
  sign-off)
- Crane/handling-equipment maintenance coordination (coordination only,
  never an equipment fitness certification)

### Actor Pattern (Module Shape)

- **Store** (`cargohandling.store`): SSoT with `shipment`/`facility`
  entity directories + four append-only logs (cargo/schedule/
  maintenance/concern) + audit ledger. `MemStore` (default) and
  `DatomicStore` (langchain.db-backed, using `kotoba-lang/langchain-store`
  as the shared entity-store adopter per ADR-2607141600)
- **Registry** (`cargohandling.registry`): pure-function draft-record
  construction (unsigned, `:kind :*-draft`) for the four record kinds
- **Advisor** (`cargohandling.advisor`): deterministic mock proposal
  generator for the four ops
- **Governor** (`cargohandling.governor`): four HARD, permanent,
  un-overridable checks
- **Operation** (`cargohandling.operation`): langgraph-clj StateGraph
  orchestration (intake → advise → govern → decide → commit | hold |
  request-approval)
- **Phase** (`cargohandling.phase`): rollout phases 0–3
- **Sim** (`cargohandling.sim`): deterministic demo runner
- **Tests**: `governor_contract_test.clj`, `phase_test.clj`,
  `registry_test.clj`, `store_contract_test.clj`,
  `scope_exclusion_test.clj` — 35 tests / 193 assertions, all passing

### Governor: Four HARD Checks (Un-overridable)

**Check 1: Shipment/facility unverified**
- Target `shipment` record (for `:log-cargo-record`,
  `:schedule-handling-operation`, `:flag-cargo-safety-concern`) must be
  independently `:registered?` AND `:verified?` in the store, re-derived
  from the REQUEST every time, never from the proposal's self-report
- `:coordinate-equipment-maintenance` is facility-level (the same
  "facility-level ops don't need per-shipment verification" exemption
  `cloud-itonami-isic-561`'s and `cloud-itonami-isic-5222`'s governors
  establish for their own non-per-target ops) — independently
  re-verifies the target `facility` is `:registered?` instead

**Check 2: Effect not `:propose`**
- The proposal's own `:effect` must be `:propose`, whatever the advisor
  claims elsewhere

**Check 3: Closed op-allowlist**
- The proposal's own `:operation` must be one of the four allowed ops;
  anything else (including a hallucinated op a real LLM advisor might
  propose) is a hard, permanent block

**Check 4: Finalize-load-safety scope exclusion**
- ANY proposal whose text tries to finalize a load-safety clearance,
  override a weight-limit rule, or waive a hazmat-segregation rule is a
  hard, permanent block. This territory does not exist as an op in this
  actor's allowlist at all (check 3 already makes it structurally
  unreachable) — this check is defense-in-depth against a real LLM
  advisor smuggling a finalization claim into an otherwise-legitimate
  proposal's own `:summary`/`:rationale` text

### Self-tripping-bug discipline (fleet-wide known pattern)

Multiple sibling `cloud-itonami-isic-*` actors in this fleet independently
discovered and fixed the SAME bug class: phrasing a governor's
scope-exclusion term list as a bare noun ("safety", "clearance",
"hazmat") makes it match inside the mock advisor's own DEFAULT
rationale/disclaimer text for a legitimate, allowed proposal — e.g. a
`:flag-cargo-safety-concern` proposal's own honest rationale legitimately
uses the words "cargo safety concern" and "hazmat" as NOUNS, so a
bare-noun exclusion list would self-block the actor's own happy path.
`cargohandling.governor/finalize-load-safety-patterns` is phrased as the
FINALIZATION/EXECUTION ACTION ("finalize the load-safety clearance",
"override the weight-limit rule", "waive the hazmat-segregation rule" —
never the bare noun), and `test/cargohandling/scope_exclusion_test.clj`
asserts directly that all eleven default-advisor proposal cases (across
all four ops and all shipment/facility fixtures, including the
deliberately-unverified/unregistered ones) never trip
`:finalize-load-safety-attempt`, plus a sanity check that the same
patterns DO catch a genuine finalization-action attempt (both EN and JA
phrasing).

### Wave 2 (coordination/logistics) invariant

Per this batch's wave-2 guidance: no op that would finalize a
cargo-handling-safety-clearance decision (e.g. clearing a load as safely
secured after a reported issue, overriding a weight-limit/hazmat-
segregation rule) exists ANYWHERE in this domain's op set (governor check
3 + check 4, belt-and-suspenders), and `:flag-cargo-safety-concern` is a
permanent structural absence from every phase's `:auto` set
(`cargohandling.phase`) as well as a permanent member of the governor's
own `high-stakes` set (`cargohandling.governor`) — two independent layers
agree that a cargo-safety concern always reaches a human, and no phase,
now or in any future entry, may ever auto-commit it.

## Decision

### 1. Module Identity

- **ID**: `cloud-itonami-isic-5224`
- **ISIC Code**: 5224 (Cargo handling)
- **Public Repo**: https://github.com/cloud-itonami/cloud-itonami-isic-5224
  (pre-existing, filled in — not newly created)
- **Business-ID**: `cloud-itonami-5224` (unchanged; matches the
  `cloud-itonami-<ISIC>` — no `-isic-` infix — pattern already used by
  every sibling entry in the 50xx–52xx transport-support ISIC block)
- **Governor**: `:cargo-handling-governor` (from the pre-existing
  `blueprint.edn`)

### 2. Operation Allowlist (Closed)

1. `:log-cargo-record` — Load/unload/manifest data logging
2. `:schedule-handling-operation` — Crane/dock/warehouse scheduling
   proposal
3. `:flag-cargo-safety-concern` — Cargo-safety-concern flag (ALWAYS
   escalates)
4. `:coordinate-equipment-maintenance` — Crane/handling-equipment
   maintenance coordination

Any operation outside this set is rejected (closed allowlist, check 3).

### 3. Phase Progression (0→3)

- **Phase 0** (read-only): all proposals held for human review
- **Phase 1** (assisted-logging): cargo-record logging +
  cargo-safety-concern flagging allowed, every write needs approval
- **Phase 2** (assisted-scheduling): + crane/dock/warehouse handling-
  operation scheduling proposal writes, still approval
- **Phase 3** (supervised-auto): `:log-cargo-record` (no capital risk, no
  load-safety determination) may auto-commit when governor-clean;
  `:schedule-handling-operation` and `:coordinate-equipment-maintenance`
  (real resource commitments) ALWAYS need human approval even when
  governor-clean; `:flag-cargo-safety-concern` ALWAYS escalates — never
  in `:auto` at any phase

### 4. Deliverables

**Files committed to `cloud-itonami-isic-5224` main** (on top of the
pre-existing boilerplate — `CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/
`GOVERNANCE.md`/`LICENSE`/`README.md`/`SECURITY.md`/`blueprint.edn`/
`docs/` were kept unchanged):

```
.gitignore
deps.edn
src/cargohandling/
  - store.cljc (SSoT: shipment/facility directories, MemStore + DatomicStore)
  - registry.cljc (pure record-draft construction)
  - advisor.cljc (deterministic mock proposal generator)
  - governor.cljc (four HARD checks)
  - operation.cljc (langgraph-clj StateGraph orchestration)
  - phase.cljc (rollout phases)
  - sim.cljc (demo runner)
test/cargohandling/
  - governor_contract_test.clj
  - phase_test.clj
  - registry_test.clj
  - store_contract_test.clj
  - scope_exclusion_test.clj (dedicated self-trip regression test)
```

### 5. Verification (Real Output)

**Tests via `clojure -M:test`** (raw, unedited final line):
```
Ran 35 tests containing 193 assertions.
0 failures, 0 errors.
```

**Push**: landed on `cloud-itonami/cloud-itonami-isic-5224` `main` at
commit `d39ba69513d19f060b13022dcfd42098c9747a94` (fast-forward from the
pre-existing tip `12e63cb`).

**Repository**: Public, all files present via GitHub API re-verification
after push (pre-existing boilerplate docs confirmed still intact).

### 6. Registry Update

**File**: `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`

**Entry before** (no `:maturity` key at all — resolves to `:blueprint` via
the `:repo`-set fallback in `kotoba.industry/maturity-of`):
```clojure
{:id "5224", :name "Cargo handling",
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-5224",
 :business-id "cloud-itonami-5224",
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics],
 :optional-technologies [:optimization],
 :operating-states [:intake :book :transit :deliver :reconcile :audit]}
```

**Entry after** (`:maturity :implemented` added explicitly, `:repo`/
`:business-id` unchanged — already correct):
```clojure
{:id "5224", :name "Cargo handling",
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-5224",
 :business-id "cloud-itonami-5224",
 :maturity :implemented,
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics],
 :optional-technologies [:optimization],
 :operating-states [:intake :book :transit :deliver :reconcile :audit]}
```

Landed via a Contents-API single-file PUT (sha-checked optimistic
concurrency, immediately re-fetched fresh content before the PUT per this
fleet's hot-contention discipline, exact-block edit only, verified via a
prefix/suffix diff showing a single contiguous 24-character insertion —
exactly `, :maturity :implemented` — sample-verified against 5222/5223/
4911/561 entries also intact, no mojibake detected), commit
`669aa0dd5464892f5ab28430d5a46683f5da9946`.

**Registry validated**: `kotoba.industry/maturity-summary` recomputed
live from the freshly-landed file (not `grep -c`): `{:total 649, :spec
232, :blueprint 17, :implemented 400}` (`:implemented` 399 → 400,
`:blueprint` 18 → 17). `test/kotoba/industry_test.clj`'s
`maturity-summary-counts-tiers` assertions bumped to match (399 → 400,
18 → 17), plus a stale pre-existing "cloud-itonami-isic-5224 is
:blueprint (live-state corroboration)" testing block removed and a
detailed corroboration block for `"5224"` added. The full edit was
validated locally (fresh `kotoba-lang/industry` + `kotoba-lang/
technology` sibling clone, `clojure -M:test` green) BEFORE being applied
against a freshly-refetched blob from `origin/main` and landed via
Contents-API single-file PUT, commit
`49528653b545da177bda6a153e39204c13ada37c`.

**Post-merge re-verification** (fresh clone into a new temp dir, plus
fresh `kotoba-lang/technology` sibling):
```
Ran 15 tests containing 1062 assertions.
0 failures, 0 errors.
```
`"5224"` entry, sample sibling entries (5222/561), and
`kotoba.industry/maturity-summary` (`{:total 649, :spec 232, :blueprint
17, :implemented 400}`) all independently re-confirmed against this
fresh clone. No mojibake detected.

## Consequences

### Positive

- ISIC-5224 Wave 2 coordination/logistics-cluster actor now complete,
  filling in a legitimate pre-existing blueprint-tier registry entry
  rather than leaving it permanently unimplemented
- Coordination-only actor pattern (Governor + closed allowlist + hard
  checks + phase gate) proven reusable again in this exact family
  (561 → 5222 → 5224), same shipment/facility-style entity-pair shape
- No load-safety-clearance authority anywhere in the actor —
  structurally unreachable (closed allowlist) and defended in depth
  (text-scan check), not merely policy
- Dedicated regression test (`scope_exclusion_test.clj`) locks in the
  fleet-wide self-tripping-bug fix for this repo specifically

### Risks & Mitigations

- **Regulation variance**: cargo-handling/stevedoring safety regimes vary
  by jurisdiction (Japan's 港湾運送事業法, US OSHA 29 CFR Parts 1917/1918,
  the ISPS Code, ILO Convention 152 — see the pre-existing `README.md`).
  This actor handles **administrative coordination only** — deployment
  requires local compliance review and a real load-safety authority
  (terminal safety officer / stevedoring supervisor) retaining all
  load-safety-clearance/weight-limit-override/hazmat-segregation-waiver
  decisions.
- **Escalation-infrastructure dependency**: `:flag-cargo-safety-concern`
  always escalates to human sign-off — deployment must ensure
  human-review infrastructure exists and is monitored with appropriate
  urgency for safety-relevant signals.

## References

- ADR-2607121000 (wave definition, value function, inverse topological
  sort)
- ADR-2628071600 (cloud-itonami-isic-5222 port/harbor-support-services,
  direct architecture reference for this build)
- ADR-2607155100 (isic-561 restaurant coordination, sibling coordination-
  actor pattern + scope-exclusion idiom origin)
- ADR-2607141600 (`kotoba-lang/langchain-store` entity-store adopter
  pattern)
- Skill `build-actor` (actor pattern, langgraph-clj StateGraph, Governor,
  audit ledger)

---

**Draft & verification completed**: 2026-07-16
**Co-Author**: Claude Sonnet 5
