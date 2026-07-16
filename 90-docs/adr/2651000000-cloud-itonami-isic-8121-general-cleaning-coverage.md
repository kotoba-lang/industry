# ADR-2651000000: cloud-itonami-isic-8121 — General Cleaning of Buildings Operations Coordination

## Status

Accepted. `cloud-itonami-isic-8121` promoted from no `:maturity` key
(resolves to `:blueprint` via the `kotoba.industry/maturity-of` fallback,
since the entry already carries a `:repo`) to `:implemented` in the
`kotoba-lang/industry` registry. This is the last of a 6-target
blueprint-tier cleanup sweep — after landing this entry, the registry
has zero remaining nil-maturity/blueprint-tier gaps (see Consequences).

## Context

ISIC Rev.5 8121 (General cleaning of buildings) was originally published
as a fresh `:blueprint` by ADR-2607103900 (2026-07-10): boilerplate docs
only (`README.md`, `blueprint.edn`, `docs/business-model.md`,
`docs/operator-guide.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
`GOVERNANCE.md`, `SECURITY.md`, `LICENSE`) — deliberately no
`deps.edn`/`src`/`test`. Identity independently verified against a fresh
clone of `kotoba-lang/industry` before any work began: the live
`{:id "8121" ...}` entry's `:name` is exactly "General cleaning of
buildings" — no mismatch.

**Not a fresh scaffold.** `cloud-itonami/cloud-itonami-isic-8121` already
existed as a legitimate `:blueprint`-tier repo from that earlier
bulk-scaffolding pass. Its existing `README.md`/`blueprint.edn`/`docs/`
already frame the vertical on cloud-itonami's general robotics-premised
physical-work model (autonomous floor scrubbers/vacuums/supply-restocking
carts under a "Building Cleaning Governor") and are preserved as-is in
this ADR's changes — none of the existing boilerplate docs or `docs/`
were rewritten or removed, only added to (`README.md` gained a new
"Actor implementation" section; `blueprint.edn` gained
`:itonami.blueprint/maturity :implemented` and a `:db/id -1` /
vector-wrap, matching the two most recently landed sibling blueprints'
own post-implementation shape).

**Scope of the actor implemented here**: OPERATIONS COORDINATION ONLY,
mirrored closely on the verified, independently-re-tested sibling actors
`cloud-itonami-isic-873` (Residential Care, ADR-2607152700) and
`cloud-itonami-isic-5629` (Institutional Food Service, ADR-2616562900)
module shape (advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout, string-keyed
directory, append-only audit ledger) — `cloud-itonami-isic-5629`'s
cost-threshold-escalate `:coordinate-supply-order` gate was the closer
structural template. Domain-adapted for routine, general-purpose
janitorial/floor-care building cleaning (distinct from ISIC 8129
specialized/industrial cleaning and 8130 landscape care, per this repo's
own pre-existing README "Scope note"): cleaning-visit/task-completion
service-record logging, crew/site cleaning-operation scheduling,
cleaning-supply procurement coordination, and safety-concern flagging
(chemical-incompatibility, slip-hazard, biohazard) — never directly
finalizing a chemical-safety-clearance decision, overriding a
chemical-incompatibility warning, directly actuating robots/equipment, or
performing safety-authority enforcement. The robotics-dispatch capability
layer described in the pre-existing blueprint (`kotoba-lang/robotics` +
`kotoba-lang/labor`) is a separate, not-yet-wired concern this actor's
coordination proposals could eventually feed into, always gated the same
way — this ADR does not implement robot dispatch.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — cleaning-visit/task-completion data logging
- `:schedule-cleaning-operation` — crew/site scheduling proposal
- `:coordinate-supply-order` — cleaning-supply procurement proposal
- `:flag-safety-concern` — surface a chemical-incompatibility/slip-hazard/biohazard concern — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

Commercial cleaning has a direct chemical-handling-safety dimension, so
the closed op allowlist NEVER includes any op that directly finalizes a
chemical-safety-clearance decision or overrides a chemical-incompatibility
warning — that is always either a hard permanent block (see check 3) or,
for the one "flag a concern" op, an always-escalate op, never an
auto-commit-eligible op in any phase's `:auto` set.

1. **Site unverified** — the target site's client/site-contract record
   must exist in the store AND be independently `:registered?`/
   `:verified?` before any proposal for it may commit or even escalate.
   Re-derived from the site's own store record every time, never from
   the proposal's own `:site-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches directly finalizing a chemical-safety-clearance decision,
   overriding a chemical-incompatibility warning, directly actuating a
   robot/equipment, or safety-authority enforcement (OSHA-style
   citation, inspection clearance, license/permit suspension, compliance
   enforcement), is a permanent, un-overridable block. Evaluated
   **unconditionally** on every proposal via a lower-cased substring scan
   of the proposal's own content (English + Japanese term list) — never
   trusting the advisor's own framing.

   Per this fleet's known self-tripping bug class — a governor's own
   scope-exclusion term list phrased as a bare noun (e.g. "chemical")
   accidentally matching inside the mock advisor's own DEFAULT
   rationale/disclaimer text for a legitimate, allowed proposal — every
   term in `buildingcleaningops.governor/scope-excluded-terms` is phrased
   as the finalization/execution ACTION (e.g. "override the
   chemical-incompatibility warning", "finalize the chemical-safety
   clearance", "化学物質安全クリアランスを確定"), never a bare noun. A
   dedicated regression test,
   `buildingcleaningops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`,
   runs the default mock advisor's own proposal for every allowed op
   (including `:flag-safety-concern`, whose entire job is to talk about
   chemical-incompatibility/slip-hazard/biohazard concerns) through the
   governor and asserts none of them ever trip `:scope-excluded` or
   `:op-not-allowed`; a second test,
   `legitimate-safety-concern-is-not-scope-excluded`, exercises the same
   invariant directly against `governor/check`.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above a $500 estimated-cost threshold — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`buildingcleaningops.phase`'s 0→3 rollout table independently agrees:
`:flag-safety-concern` is never a member of any phase's `:auto` set, at
any phase — two layers, not one, enforce the same invariant. The
high-cost supply-order escalate gate requires no extra phase-layer code:
the governor's own `high-stakes?` already turns the base disposition into
`:escalate` before the phase gate runs, so phase 3's `:auto` membership
for `:coordinate-supply-order` never applies to an over-threshold order
(exercised by `high-cost-supply-order-always-escalates` /
`low-cost-supply-order-auto-commits`, and the corresponding
integration-level tests in `governor-contract-test`).

### 3. Module shape

`buildingcleaningops.store` (MemStore, string-keyed site directory),
`buildingcleaningops.advisor` (BuildingCleaningAdvisor, mock + a
real-LLM seam, plus an `:out-of-scope?` test hook that deliberately
drafts chemical-safety-clearance-finalization/incompatibility-override
scope content so the governor's scope scan can be exercised end to end),
`buildingcleaningops.governor` (BuildingCleaningGovernor),
`buildingcleaningops.phase` (0→3 rollout), `buildingcleaningops.operation`
(the `langgraph-clj` StateGraph: intake → advise → govern → decide →
commit | hold | request-approval), `buildingcleaningops.sim` (demo
driver, `clojure -M:run`).

## Consequences

- Actor repo `cloud-itonami/cloud-itonami-isic-8121` (pre-existing
  `:blueprint`-tier repo, NOT freshly scaffolded) filled in: `deps.edn`,
  `.gitignore`, full `src/buildingcleaningops/*.cljc` +
  `test/buildingcleaningops/*.clj` module set added on top of the
  existing boilerplate docs/blueprint.edn, which are preserved unchanged
  in content. README.md extended (not replaced) with a new "Actor
  implementation" section documenting the actual implementation, module
  list, and test suite, while keeping the pre-existing robotics-premise
  narrative intact. Committed and pushed directly to `main`
  (fast-forward from `b3d779a`, no divergence):
  `c622bd589a615ad4c92f08b94e649be41ef19d50`.
- Test suite, run directly by this session (not agent self-report):
  **`Ran 46 tests containing 130 assertions. 0 failures, 0 errors.`**
  (`clojure -M:dev:test`), independently re-verified against a fresh
  clone with the same result. `clojure -M:lint` also clean (0 errors, 0
  warnings).
- Registry entry updated in place (exact-text edit of the existing
  `{:id "8121" ...}` block only, not appended, not touching any other
  entry): `:maturity :implemented` added (previously absent — the entry
  resolved to `:blueprint` only via the `:repo`-present fallback in
  `kotoba.industry/maturity-of`). `:repo` and `:business-id` were already
  correct (`https://github.com/cloud-itonami/cloud-itonami-isic-8121` /
  `cloud-itonami-8121`, matching the pre-existing `blueprint.edn`'s own
  `:itonami.blueprint/id`) and were left unchanged, as were
  `:required-technologies`/`:optional-technologies`/`:operating-states`.
- This was the last target of a 6-repo blueprint-tier cleanup sweep. Live
  `kotoba.industry/maturity-summary` re-verified after merge: zero
  remaining nil-maturity/blueprint-tier gaps in the registry (see the
  companion `kotoba-lang/industry` PR/merge for the exact post-merge
  count).

## References

- `cloud-itonami-isic-5629/` (closer structural template — cost-threshold
  supply-order escalate gate, ADR-2616562900, verified working,
  independently re-tested Wave 4 food-service precedent)
- `cloud-itonami-isic-873/` (module-shape precedent, ADR-2607152700 —
  verified working, independently re-tested residential-care precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"8121"` entry
- `90-docs/adr/2607103900-cloud-itonami-buildingcleaning-8121-blueprint.md` (original blueprint ADR this ADR builds on)
