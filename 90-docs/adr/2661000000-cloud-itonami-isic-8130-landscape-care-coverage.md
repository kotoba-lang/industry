# ADR-2661000000: cloud-itonami-isic-8130 — Landscape Care Operations Coordination

## Status

Accepted. `cloud-itonami-isic-8130` promoted from no `:maturity` key
(resolves to `:blueprint` via the `kotoba.industry/maturity-of`
fallback, since the entry already carries a `:repo`) to `:implemented`
in the `kotoba-lang/industry` registry. This was one of a 6-target
blueprint-tier cleanup sweep (siblings: 7911/7912/8121/8130/8219/8220).
Live re-verification after this promotion landed (see Consequences)
confirmed the sweep reached fleet-wide zero remaining nil-maturity/
blueprint-tier gaps in the registry.

## Context

ISIC Rev.5 8130 (Landscape care and maintenance service activities) was
independently verified against a fresh clone of `kotoba-lang/industry`
before any work began: the live `{:id "8130" ...}` entry's `:name` is
exactly "Landscape care and maintenance service activities" — no
mismatch.

**Not a fresh scaffold.** `cloud-itonami/cloud-itonami-isic-8130`
already existed as a legitimate `:blueprint`-tier repo from an earlier
bulk-scaffolding pass (verified via `gh api
repos/cloud-itonami/cloud-itonami-isic-8130` before any work began):
`CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `GOVERNANCE.md`, `LICENSE`,
`README.md`, `SECURITY.md`, `blueprint.edn`, and
`docs/{business-model,operator-guide}.md` — no `deps.edn`, no `src`, no
`test`. This ADR fills in the missing implementation on top of the
existing repo rather than recreating it. None of the existing
boilerplate docs were rewritten or removed; `README.md` gained a new
"Implementation" section (not a rewrite) and `blueprint.edn` gained
`:itonami.blueprint/maturity :implemented` plus an
`:itonami.blueprint/implemented-slice` narrative, appended to the
existing keys.

**Equipment-safety/pesticide-application dimension.** Landscape
maintenance has a direct equipment-safety dimension (power equipment —
chainsaws/pole-saws/heavy mowers — and pesticide/herbicide
application), so this actor's closed op allowlist NEVER includes any op
that directly finalizes an equipment-safety clearance or a
pesticide-application decision — that is always either a hard,
permanent block or, for the one "flag a concern" op, an always-escalate
op, never auto-commit-eligible in any rollout tier's `:auto` set.

**Scope of the actor implemented here**: OPERATIONS COORDINATION ONLY,
mirrored closely on the verified, independently re-tested sibling actor
`cloud-itonami-isic-0161` (Support activities for crop production,
already `:implemented`) — chosen as the reference because its
`chemical-application?`/non-chemical service-type split, safety-window
shape (license/calibration/interval/wind/buffer-zone), and pure-function
module list (facts/registry/store/governor/operation/phase/sim) mapped
directly onto landscape-care's own equipment-safety and
pesticide-application concerns. Domain-adapted for ongoing landscape
CARE AND MAINTENANCE of existing grounds (mowing, tree-trimming/
pruning, irrigation-zone monitoring, herbicide/pesticide treatment) —
explicitly NOT landscape architecture/design (a separately licensed
profession out of this repo's own pre-existing "Scope note", left
unchanged). This actor never dispatches robots or field equipment,
never finalizes an equipment-safety clearance, and never finalizes a
pesticide/herbicide-application decision — it only ever proposes, with
`:effect :propose`.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-service-record` — mowing/pruning/treatment-visit service-record data logging (always requires human sign-off — the one real actuation event this actor performs)
- `:schedule-maintenance-operation` — crew/equipment/site scheduling proposal
- `:flag-safety-concern` — surface an equipment-hazard or pesticide/herbicide-drift concern — **ALWAYS escalates, never in any rollout tier's `:auto` set**
- `:coordinate-supply-order` — equipment/chemical/plant-material procurement proposal (escalates above a $5,000 cost threshold)

### 2. Governor rules: fifteen HARD checks (permanent, un-overridable), plus escalation

`landscapecare.governor` mirrors `cropsupport.governor`'s shape: site
order not independently verified/registered (`:site-order-not-
registered`, applies to ALL FOUR ops), effect-not-`:propose`, no
jurisdiction citation, evidence-checklist incomplete, plus five
independently-verified physical/regulatory checks gated on the service
type's own flags rather than fabricated for service types that don't
need them:

- `:applicator-license-expired` / `:equipment-calibration-overdue` / `:restricted-entry-interval-violated` / `:wind-speed-exceeded` / `:buffer-zone-violated` — only evaluated for `:chemical-application?` service types (herbicide/pesticide treatment)
- `:equipment-safety-clearance-expired` — only evaluated for service types whose `:equipment-safety-clearance-required?` flag is true (e.g. chainsaw/pole-saw tree-trimming) — an INDEPENDENT dimension from chemical application, never conflated
- `:safety-concern-flag-unresolved` / `:already-logged` — double-commit and unresolved-concern guards

**Two independent, defense-in-depth scope-exclusion layers**, both
permanent HARD blocks, both evaluated unconditionally on every op:

1. `:equipment-safety-or-pesticide-decision-blocked` — a structural
   boolean-flag check on the proposal's own declared `:value`
   (`:finalize-equipment-safety-clearance?` /
   `:finalize-pesticide-application-decision?`).
2. `:scope-exclusion-text` — a free-text regex scan (EN + JA) over the
   proposal's `:value` and `:reasoning`.

Per this fleet's known self-tripping bug class — a governor's own
scope-exclusion term list phrased as a bare noun (e.g. "pesticide",
"equipment", "safety") can accidentally match inside the mock advisor's
own DEFAULT rationale/disclaimer text for a legitimate, allowed
proposal, causing the actor to self-block its own happy path — every
pattern in `landscapecare.governor/scope-exclusion-patterns` is phrased
as the finalization/execution ACTION ("finalize the equipment-safety
clearance", "finalize the pesticide-application decision",
"設備安全…最終…承認", "農薬散布…最終…決定"), never a bare topic noun. A
dedicated regression test,
`landscapecare.advisor-test/mock-advisor-defaults-never-self-trip-scope-exclusion-test`,
runs the default mock advisor's own proposal for every allowed op
(including `:flag-safety-concern`, whose entire job is to talk about
equipment-hazard/pesticide-drift concerns, and `:log-service-record`,
whose rationale explicitly and correctly mentions "safety and
compliance parameters") through the live Governor and asserts none of
them ever trip either scope-exclusion rule.

Escalation (SOFT, always human sign-off, only reached when the Governor
is otherwise clean):

- `:log-service-record` — always, the one real actuation event this actor performs.
- `:flag-safety-concern` — always, regardless of confidence.
- `:coordinate-supply-order` above `governor/supply-order-cost-threshold-usd` (5000 USD).
- Low advisor confidence (`< governor/confidence-floor`, 0.6).

**Rollout-tier `:auto`-set discipline, as a second independent layer.**
`landscapecare.rollout` adds an explicit 0→3 gradual-autonomy tier
table (`:writes`/`:auto` per tier), orthogonal to
`landscapecare.phase`'s workflow state machine
(`:intake→:register→:match→:dispatch→:follow-up→:audit`, matching the
registry's own `:operating-states` for `"8130"` exactly). `:flag-
safety-concern` and `:log-service-record` are never members of ANY
tier's `:auto` set, at any tier — cross-checked against
`governor/always-escalate-ops` by `rollout/rollout-consistent?` and
`test/landscapecare/rollout_test.cljc`, so the two enforcement layers
(governor escalation and rollout-tier `:auto` membership) cannot drift
apart silently.

### 3. Module shape

`landscapecare.facts` (service-type/jurisdiction reference data + pure
positive-sense predicates), `landscapecare.registry` (pure
negative-sense verification predicates, no host-clock calls),
`landscapecare.store` (plain-data site-order directory + append-only
audit ledger), `landscapecare.governor` (LandscapeCareGovernor, 15 hard
checks), `landscapecare.advisor` (LandscapeCareAdvisor, deterministic
mock advisor for demo/tests, one `advise-*` fn per allowlisted op plus
`default-proposals` used by the self-trip regression test),
`landscapecare.rollout` (0→3 rollout-tier `:auto`-set table),
`landscapecare.operation` (pure-function proposal-through-Governor
driver), `landscapecare.phase` (workflow state machine matching the
registry's `:operating-states`), `landscapecare.sim` (demo driver,
`clojure -M:run`, walks all four default proposals through the live
Governor and prints each verdict).

## Consequences

- Actor repo `cloud-itonami/cloud-itonami-isic-8130` (pre-existing
  `:blueprint`-tier repo, NOT freshly scaffolded) filled in: `.gitignore`,
  `deps.edn`, full `src/landscapecare/*.cljc` + `test/landscapecare/
  *.cljc` module set added on top of the existing boilerplate docs/
  blueprint.edn, which are preserved unchanged in content.
  `README.md` extended (not replaced) with new "Implementation",
  "Scope-exclusion phrasing", "Operations", "Rollout tiers", "Testing",
  and "Standalone Use" sections. Committed and pushed directly to
  `main` in two commits: `0c7ad009f9b6630111a85027aff506648046ca66`
  (core module set) and `5819aa37f9ef04cc3700c0d87642d7a58dc2f4b2`
  (rollout-tier addition).
- Test suite, run directly by this session (not agent self-report),
  both immediately after each push AND again against a completely
  fresh clone: **`Ran 61 tests containing 223 assertions. 0 failures,
  0 errors.`** (`clojure -M:test`). `clojure -M:lint`: 0 errors, 0
  warnings. `clojure -M:run` (`landscapecare.sim` demo) walked all four
  default mock-advisor proposals (one commit, one commit, two escalate)
  without error.
- Registry entry updated in place via GitHub Contents API single-file
  PUT (exact-text edit of the existing `{:id "8130" ...}` block only,
  verified as the sole occurrence via an exact substring-count check
  before editing, prefix/suffix byte-identical to the rest of the file
  verified after): `:maturity :implemented` added (previously absent).
  `:repo` (`https://github.com/cloud-itonami/cloud-itonami-isic-8130`)
  and `:business-id` (`cloud-itonami-8130`, matching the pre-existing
  `blueprint.edn`'s own `:itonami.blueprint/id`) were already correct
  and left unchanged, as were `:required-technologies`/
  `:optional-technologies`/`:operating-states`. Registry PUT commit:
  `86d3f8e262152c282773df1926ee1e9efdc79a1a`. Validated with a real EDN
  parser (`clojure.edn/read-string`) before and after the edit (649
  industries, no parse error, no mojibake).
- `test/kotoba/industry_test.clj` (a very hot, actively-contended
  shared file — four other sibling agents in the same 6-target batch
  landed their own registry promotions in the narrow windows around
  this ADR's own edits): this promotion's own `"8130"` spot-check
  corrected from `:blueprint` to `:implemented`
  (`5a2837e77bb8da99d02e5671ba8b4a5fae611d7f`); the two hardcoded
  whole-suite tier-count assertions were live-recomputed via
  `(kotoba.industry/maturity-summary)` against a freshly re-fetched
  `origin/main` immediately before each PUT and updated. A second PUT
  (`6e7618cd0a99e7386717b85894140123efafcf65`) mechanically corrected
  three OTHER entries' spot-checks (`"7912"`/`"8121"`/`"8220"`) that had
  gone stale between this promotion's first and second registry-test
  edits, when three concurrent sibling agents in the same batch landed
  their own promotions in that window — corrected without touching any
  other entry's `registry.edn` data, per this fleet's single-entry-edit
  discipline, purely to keep the shared suite green. By the time this
  session re-verified against a completely fresh clone a third time, a
  fourth concurrent sibling agent (the `cloud-itonami-isic-7912`
  promotion, ADR-2660000000) had already landed the matching mechanical
  fix for the sixth and final batch target (`"8219"`) — this session's
  own third planned mechanical-fix PUT was superseded by that
  already-landed fix and was not needed.
- **Final live re-verification, fresh clone (`kotoba-lang/industry` +
  `kotoba-lang/technology` sibling), run directly by this session:**
  `Ran 15 tests containing 1063 assertions. 0 failures, 0 errors.`
  Live `(kotoba.industry/maturity-summary)`: `{:total 649, :spec 232,
  :blueprint 0, :implemented 417}`. **Zero remaining nil-maturity/
  blueprint-tier gaps** — this 6-target sweep (7911/7912/8121/8130/
  8219/8220) is complete fleet-wide as of this snapshot.

## References

- `cloud-itonami-isic-0161/` (module-shape and governor-shape
  precedent — chemical-application/non-chemical service-type split,
  safety-window pattern, ADR reference for Support activities for crop
  production, verified working, independently re-tested)
- `cloud-itonami-isic-8121/`, `cloud-itonami-isic-920/` (scope-exclusion
  self-tripping-bug precedent and phrasing discipline, same fleet)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn` `"8130"` entry
- ADR-2660000000 (`cloud-itonami-isic-7912`, the sixth and final target
  of this same 6-repo blueprint-tier cleanup sweep, landed concurrently)
- ADR-2651000000 (`cloud-itonami-isic-8121`, same sweep, same
  pre-existing-blueprint-repo pattern)
