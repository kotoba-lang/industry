# ADR-2629600000: cloud-itonami ISIC-5229 Freight-Forwarding/Customs-Brokerage Operations-Coordination Actor — Blueprint Fill-In & Registry Promotion

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-5229` (pre-existing blueprint-tier repo, filled in), `orgs/kotoba-lang/industry` registry (ISIC-5229 promotion), Wave 2 coordination/logistics cluster

## Context

ISIC 5229 ("Other transportation support activities") is the residual
transportation-support category: freight forwarding, customs brokerage,
and similar logistics-intermediary services — not any specific mode's
own support services (already covered by siblings 5221–5224). The
`cloud-itonami/cloud-itonami-isic-5229` repo already existed on GitHub
(pushed 2026-07-09) as a legitimate `:blueprint`-tier registry entry from
an earlier bulk-scaffolding pass: `blueprint.edn`, `README.md`,
`docs/business-model.md`, `docs/operator-guide.md`, `CODE_OF_CONDUCT.md`,
`CONTRIBUTING.md`, `GOVERNANCE.md`, `LICENSE`, `SECURITY.md` were
present, but no `deps.edn`/`src`/`test` — the repo was published but
never implemented. This ADR records filling that repo in, not creating
it fresh.

The pre-existing `blueprint.edn` already declared
`:itonami.blueprint/governor :freight-forwarding-governor` and the
pre-existing `README.md`/`docs/*.md` already established the domain
vocabulary this implementation follows: "Freight Forwarding Advisor" /
"Freight Forwarding Governor", "customs-compliance scope",
"multi-carrier routing", "consignment booking", "reconciliation record".
`README.md`'s own "Scope note" section already distinguishes this repo
from the carrier verticals (`cloud-itonami-isic-4911`/`4912`/`4920`/
`5110`/`5011`/`5020`, which move goods/people aboard their own
vehicle/vessel) and from `cloud-itonami-isic-5224` (cargo handling, a
terminal SERVICE) — this repo is the separate business of freight
forwarding and customs brokerage: an intermediary that arranges carriage
across multiple carriers/modes on a shipper's behalf and clears goods
through customs, without owning transport assets or operating a
terminal.

### Reference-repo note

The build task's primary reference pointer
(`cloud-itonami-isic-5224`, Cargo handling, "expected to be built by a
sibling agent in this same batch") was checked before use and found to
be, at clone time, ALSO blueprint-tier only (no `deps.edn`/`src`/`test`
on `main`) — the sibling agent's build had not yet landed. The named
fallback, `cloud-itonami-isic-5222` (Water transport support /
`portauthority.*`, `:maturity :implemented`, real `deps.edn` +
`src/portauthority/*.cljc` + `test/portauthority/*.clj` + green
`clojure -M:test`), was used as the actual architecture reference
instead — its two-entity (`clearance`/`facility`) Store pattern, four-op
closed allowlist, and facility-level-verification-exemption idiom map
directly onto this domain's `shipment`/`carrier` split.

### Domain Scope

Freight-forwarding/customs-brokerage operations coordination — NOT
direct customs-clearance authority or shipment-release control:
- Freight-forwarding/customs-documentation data logging (administrative
  data logging only)
- Routing/consolidation scheduling proposals (proposal only, never a
  shipment-release authorization)
- Customs-documentation/regulatory-compliance-concern flagging (a
  reported documentation discrepancy or compliance gap — ALWAYS
  escalates to human sign-off)
- Carrier-booking coordination (coordination only, never a carrier-
  contract commitment or shipment-release authorization)

### Actor Pattern (Module Shape)

- **Store** (`freightforwarding.store`): SSoT with `shipment`/`carrier`
  entity directories + four append-only logs (shipment/schedule/
  carrier/concern) + audit ledger. `MemStore` (default) and
  `DatomicStore` (langchain.db-backed, using `kotoba-lang/langchain-store`
  as the shared entity-store adopter per ADR-2607141600 — no hand-rolled
  `enc`/`dec*` copy)
- **Registry** (`freightforwarding.registry`): pure-function draft-record
  construction (unsigned, `:kind :*-draft`) for the four record kinds
- **Advisor** (`freightforwarding.advisor`): deterministic mock proposal
  generator for the four ops
- **Governor** (`freightforwarding.governor`): four HARD, permanent,
  un-overridable checks
- **Operation** (`freightforwarding.operation`): langgraph-clj StateGraph
  orchestration (intake → advise → govern → decide → commit | hold |
  request-approval)
- **Phase** (`freightforwarding.phase`): rollout phases 0–3
- **Sim** (`freightforwarding.sim`): deterministic demo runner
- **Tests**: `governor_contract_test.clj`, `phase_test.clj`,
  `registry_test.clj`, `store_contract_test.clj`,
  `scope_exclusion_test.clj` — 34 tests / 190 assertions, all passing

### Governor: Four HARD Checks (Un-overridable)

**Check 1: Shipment/carrier record unverified**
- Target `shipment` record (for `:log-shipment-record`,
  `:schedule-logistics-operation`, `:flag-compliance-concern`) must be
  independently `:registered?` AND `:verified?` in the store, re-derived
  from the REQUEST every time, never from the proposal's self-report
- `:coordinate-carrier-booking` is carrier-level (the same
  "facility-level ops don't need per-target verification" exemption
  `cloud-itonami-isic-561`'s governor establishes for its own
  non-reservation ops, also reused by `cloud-itonami-isic-5222`) —
  independently re-verifies the target `carrier` is `:registered?`
  instead

**Check 2: Effect not `:propose`**
- The proposal's own `:effect` must be `:propose`, whatever the advisor
  claims elsewhere

**Check 3: Closed op-allowlist**
- The proposal's own `:operation` must be one of the four allowed ops;
  anything else (including a hallucinated op a real LLM advisor might
  propose) is a hard, permanent block

**Check 4: Finalize-clearance scope exclusion**
- ANY proposal whose text tries to finalize a customs-clearance
  decision, waive a customs-inspection requirement, or authorize a
  shipment release is a hard, permanent block. This territory does not
  exist as an op in this actor's allowlist at all (check 3 already
  makes it structurally unreachable) — this check is defense-in-depth
  against a real LLM advisor smuggling a finalization claim into an
  otherwise-legitimate proposal's own `:summary`/`:rationale` text.
  Satisfies the Wave-2 requirement that any op finalizing a
  customs-clearance or shipment-release-authority decision must be a
  hard permanent block: no such op exists in the closed allowlist, and
  this check catches any smuggled attempt to reach that outcome via
  text alone.

### Self-tripping-bug discipline (fleet-wide known pattern)

Multiple sibling `cloud-itonami-isic-*` actors in this fleet
independently discovered and fixed the SAME bug class: phrasing a
governor's scope-exclusion term list as a bare noun ("clearance",
"customs", "release") makes it match inside the mock advisor's own
DEFAULT rationale/disclaimer text for a legitimate, allowed proposal —
e.g. a `:flag-compliance-concern` proposal's own honest rationale
legitimately uses the words "customs-clearance" and "shipment-release"
as NOUNS, so a bare-noun exclusion list would self-block the actor's
own happy path.
`freightforwarding.governor/finalize-clearance-patterns` is phrased as
the FINALIZATION/EXECUTION ACTION ("finalize the customs clearance",
"authorize the shipment release", "waive the customs inspection" —
never the bare noun), and
`test/freightforwarding/scope_exclusion_test.clj` asserts directly that
all ten default-advisor proposal cases (across all four ops and all
shipment/carrier fixtures, including the deliberately-unverified/
unregistered ones) never trip `:finalize-clearance-attempt`, plus a
sanity check that the same patterns DO catch a genuine
finalization-action attempt (both EN and JA phrasing).

### Wave 2 (coordination/logistics) invariant

Per this batch's wave-2 guidance: no op that would finalize a
customs-clearance or shipment-release-authority decision exists
ANYWHERE in this domain's op set (governor check 3 + check 4,
belt-and-suspenders), and `:flag-compliance-concern` is a permanent
structural absence from every phase's `:auto` set
(`freightforwarding.phase`) as well as a permanent member of the
governor's own `high-stakes` set (`freightforwarding.governor`) — two
independent layers agree that a compliance concern always reaches a
human, and no phase, now or in any future entry, may ever auto-commit
it.

## Decision

### 1. Module Identity

- **ID**: `cloud-itonami-isic-5229`
- **ISIC Code**: 5229 (Other transportation support activities)
- **Public Repo**: https://github.com/cloud-itonami/cloud-itonami-isic-5229
  (pre-existing, filled in — not newly created)
- **Business-ID**: `cloud-itonami-5229` (unchanged; matches the
  `cloud-itonami-<ISIC>` — no `-isic-` infix — pattern already used by
  every sibling entry in the 50xx–52xx transport-support ISIC block)
- **Governor**: `:freight-forwarding-governor` (from the pre-existing
  `blueprint.edn`)

### 2. Operation Allowlist (Closed)

1. `:log-shipment-record` — Freight-forwarding/customs-documentation
   data logging
2. `:schedule-logistics-operation` — Routing/consolidation scheduling
   proposal
3. `:flag-compliance-concern` — Customs-documentation/regulatory-
   compliance-concern flag (ALWAYS escalates)
4. `:coordinate-carrier-booking` — Carrier-booking coordination

Any operation outside this set is rejected (closed allowlist, check 3).

### 3. Phase Progression (0→3)

- **Phase 0** (read-only): all proposals held for human review
- **Phase 1** (assisted-logging): shipment-record logging +
  compliance-concern flagging allowed, every write needs approval
- **Phase 2** (assisted-scheduling): + routing/consolidation scheduling
  proposal writes, still approval
- **Phase 3** (supervised-auto): `:log-shipment-record` (no capital
  risk, no customs-clearance determination) may auto-commit when
  governor-clean; `:schedule-logistics-operation` and
  `:coordinate-carrier-booking` (real resource commitments) ALWAYS need
  human approval even when governor-clean; `:flag-compliance-concern`
  ALWAYS escalates — never in `:auto` at any phase

### 4. Deliverables

**Files committed to `cloud-itonami-isic-5229` main** (on top of the
pre-existing boilerplate — `CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/
`GOVERNANCE.md`/`LICENSE`/`README.md`/`SECURITY.md`/`blueprint.edn`/
`docs/` were kept unchanged):

```
deps.edn
src/freightforwarding/
  - store.cljc (SSoT: shipment/carrier directories, MemStore + DatomicStore)
  - registry.cljc (pure record-draft construction)
  - advisor.cljc (deterministic mock proposal generator)
  - governor.cljc (four HARD checks)
  - operation.cljc (langgraph-clj StateGraph orchestration)
  - phase.cljc (rollout phases)
  - sim.cljc (demo runner)
test/freightforwarding/
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

**Lint via `clojure -M:lint`**: `linting took 496ms, errors: 0, warnings: 0`

**Demo via `clojure -M:dev:run`**: all scenarios run to completion
offline — clean `:log-shipment-record` auto-commits at phase 3;
`:schedule-logistics-operation`/`:coordinate-carrier-booking`/
`:flag-compliance-concern` escalate then commit after simulated human
approval; unverified shipment / unregistered shipment / unregistered
carrier each HOLD independently; the defense-in-depth `governor/check`
calls directly demonstrate a hallucinated op (`:op-not-allowed`) and a
smuggled finalize-clearance phrase (`:finalize-clearance-attempt`) both
HARD-hold.

**Push**: landed on `cloud-itonami/cloud-itonami-isic-5229` `main` at
commit `e5961f04538d520d43826bce40c44212c0c6a9ab` (fast-forward from the
pre-existing tip `3e42350`).

**Repository**: Public, all files present via GitHub API re-verification
after push.

### 6. Registry Update

**File**: `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn`

**Entry before** (no `:maturity` key at all — resolves to `:blueprint` via
the `:repo`-set fallback in `kotoba.industry/maturity-of`):
```clojure
{:id "5229", :name "Other transportation support activities",
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-5229",
 :business-id "cloud-itonami-5229",
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :logistics],
 :optional-technologies [:optimization],
 :operating-states [:intake :book :transit :deliver :reconcile :audit]}
```

**Entry after** (`:maturity :implemented` added explicitly, `:repo`/
`:business-id` unchanged — already correct):
```clojure
{:id "5229", :name "Other transportation support activities",
 :repo "https://github.com/cloud-itonami/cloud-itonami-isic-5229",
 :business-id "cloud-itonami-5229",
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

- ISIC-5229 Wave 2 coordination/logistics-cluster actor now complete,
  filling in a legitimate pre-existing blueprint-tier registry entry
  rather than leaving it permanently unimplemented
- Coordination-only actor pattern (Governor + closed allowlist + hard
  checks + phase gate) proven reusable again in this exact family
  (561 → 5222 → 5229), with the facility-level-exemption + scope-
  exclusion-phrased-as-action idiom carried through unchanged
- No customs-clearance/shipment-release authority anywhere in the actor
  — structurally unreachable (closed allowlist) and defended in depth
  (text-scan check), not merely policy
- Dedicated regression test (`scope_exclusion_test.clj`) locks in the
  fleet-wide self-tripping-bug fix for this repo specifically

### Risks & Mitigations

- **Regulation variance**: customs-brokerage/freight-forwarding
  licensing regimes vary by jurisdiction (US Customs Broker License
  under 19 CFR Part 111, Japan's 通関業法, EU Union Customs Code AEO/
  customs-representative status, FIATA-model forwarder liability terms —
  see the pre-existing `README.md`). This actor handles **administrative
  coordination only** — deployment requires local compliance review and
  a real licensed customs broker / freight forwarder retaining all
  customs-declaration/shipment-release decisions.
- **Escalation-infrastructure dependency**: `:flag-compliance-concern`
  always escalates to human sign-off — deployment must ensure
  human-review infrastructure exists and is monitored with appropriate
  urgency for compliance-relevant signals.

## References

- ADR-2607121000 (wave definition, value function, inverse topological
  sort)
- ADR-2628071600 (`cloud-itonami-isic-5222` port/harbor-support-services
  coordination actor, direct architecture reference for this ADR)
- ADR-2607141600 (`kotoba-lang/langchain-store` entity-store adopter
  pattern)
- Skill `build-actor` (actor pattern, langgraph-clj StateGraph, Governor,
  audit ledger)

---

**Draft & verification completed**: 2026-07-16
**Co-Author**: Claude Sonnet 5
