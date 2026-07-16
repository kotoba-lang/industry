# ADR-2640000000: cloud-itonami-isic-8030 — Investigation Activities Case-Coordination

## Status

Accepted. `cloud-itonami-isic-8030` promoted from `:blueprint` (repo
published, docs only, no code) to `:implemented` in the
`kotoba-lang/industry` registry.

## Context

ISIC Rev.5 8030 (Investigation activities — private investigation,
background checks, and case research provided under contract; distinct
from sibling 8010 guard/patrol services and 8020 security-systems
service activities) already had a published repo
(`cloud-itonami/cloud-itonami-isic-8030`) from an early bulk-scaffolding
pass: `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`, `GOVERNANCE.md`,
`LICENSE`, `README.md`, `SECURITY.md`, `blueprint.edn`, and
`docs/{business-model,operator-guide}.md` — no `deps.edn`, no `src`, no
`test`. This was independently verified via `gh api
repos/cloud-itonami/cloud-itonami-isic-8030` before any work began: a
legitimate `:blueprint`-tier registry entry, not a fresh 404 target.
This ADR fills in the missing implementation on top of the existing
repo rather than recreating it. The registry `:name` for `{:id "8030"
...}` was independently verified as "Investigation activities" against
a fresh clone of `kotoba-lang/industry` before proceeding, per this
fleet's ID/name-mismatch caution.

**This is a genuinely high-stakes domain**: private investigation
directly implicates privacy law and legal-compliance exposure —
determining someone's guilt/liability, or authorizing covert
surveillance without an independently verified legal basis, are both
consequential real-world actions this actor must never itself take. The
reference repo nominated for this batch,
`cloud-itonami/cloud-itonami-isic-8020` (Security systems), was found
on inspection to still be blueprint-only itself (its own sibling agent
had not yet landed an implementation), so this build instead mirrored
`cloud-itonami/cloud-itonami-isic-8299` (Other business support service
activities, `:maturity :implemented`) — chosen because its
`:operating-states` in the registry (`[:intake :register :match
:dispatch :follow-up :audit]`) exactly matched `"8030"`'s own, making it
the closest verified structural match actually available at build time.

**Scope**: COORDINATION ONLY. This actor never renders an investigative
conclusion (guilt/liability/fault determination) and never authorizes a
surveillance method itself — neither is a proposal op its closed
allowlist recognizes at all. It coordinates case-scheduling and
evidence-logging: evidence chain-of-custody log entries,
investigator-to-case field-work scheduling proposals, legal/compliance
concern flagging (always escalates), and report-delivery LOGISTICS
coordination (recipient channel/timing — never the report's own
content).

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-case-record` — evidence chain-of-custody DATA logging (already-observed events only, never a rendered finding)
- `:schedule-investigation-operation` — investigator↔case field-work scheduling PROPOSAL only, never a final binding assignment
- `:flag-legal-compliance-concern` — surfaces a legal-basis/privacy-compliance concern — **ALWAYS escalates**
- `:coordinate-report-delivery` — report delivery-LOGISTICS coordination (recipient channel, scheduled time) — never the report's own content

Every proposal's `:effect` is always literally `:propose` — this actor
never claims to directly finalize/mutate a determination; a
governor-clean commit only ever writes a `:status :proposed`/`:logged`
SSoT entry.

### 2. Governor rules: eight HARD checks (permanent, un-overridable), plus escalation

1. **RBAC** — does the actor-role hold permission for this op?
2. **Op-allowlist gate** — is the op one of the four above, at all? (defense-in-depth alongside RBAC, catching a misconfigured role grant)
3. **Effect-invariant gate** — is `:effect` literally `:propose`? Any other value is an unconditional hard rejection.
4. **Authorization gate** — does the target case carry an independently verified/registered client-authorization record on file? Re-derived from the case's own store record every time, never from the proposal's own claim. **Exempts `:flag-legal-compliance-concern`** — a compliance concern must remain raisable even about a case whose own authorization is missing/unverified; gating the flag itself behind the very problem it might be flagging would suppress exactly the report this actor most needs to surface (it already always escalates to a human via check 9, so it needs no additional precondition to be safe).
5. **Clearance-tier gate** (`:schedule-investigation-operation` only) — does the proposed investigator hold every qualification the case requires, drawn only from the closed R0 catalog (`investigation.facts`, five real, citable licensing/certification categories — state PI license, process-server cert, FCRA background-screening cert, records-research cert, insurance SIU cert — deliberately excluding anything that would function as a surveillance-method authorization)?
6. **Capacity gate** (`:schedule-investigation-operation` only) — would this scheduling push the investigator's committed hours past weekly capacity?
7. **Structural scope gate** — does the proposal's `:value` carry a schema-excluded field (`:conclusion`/`:verdict`/`:guilt-determination`/`:liability-determination`/`:fault-determination`/`:surveillance-method`/`:surveillance-authorization`/`:covert-surveillance-method`/`:report-content`/`:raw-report-content`)? There is no such field anywhere in `investigation.store`'s schema at all.
8. **Scope-exclusion gate** — does the proposal's `:summary`/`:rationale` TEXT contain a finalization-of-investigation-conclusion or surveillance-method-authorization ACTION phrase (e.g. `"finalize the investigation conclusion"`, `"authorize covert surveillance"`)? **Permanent, non-overridable, evaluated unconditionally on every proposal.**

   Any proposal to directly finalize an investigation conclusion or
   authorize a surveillance method is always either this permanent HARD
   block or (for the one op that legitimately surfaces such concerns for
   a human) an always-escalate op — never an op eligible for auto-commit
   at any rollout phase, matching this fleet's cross-cutting privacy/
   legal-compliance guardrail for this domain.

**Self-trip discipline (a known bug pattern in this actor family)**:
multiple sibling actors in this fleet have independently discovered and
fixed the same bug class — a governor's scope-exclusion term list phrased
as a bare noun (e.g. `"conclusion"`, `"surveillance"`) can accidentally
match inside the mock advisor's own DEFAULT rationale/disclaimer text for
a legitimate, allowed proposal, causing the actor to self-block its own
happy path. This is a real, not merely hypothetical, risk here
specifically: a `:schedule-investigation-operation` proposal's rationale
routinely and legitimately mentions "surveillance operation" as a
field-work TYPE descriptor, and case-lifecycle language routinely
mentions reaching a "conclusion" of an unrelated project-management
phase. This build's implementation applies two mitigations: (a) every
`investigation.policy/scope-exclusion-phrases` entry is phrased as the
finalization/execution ACTION (`"finalize the investigation
conclusion"`, `"authorize covert surveillance"`), never a bare topic
noun; (b) a dedicated regression test,
`default-mock-advisor-proposals-never-self-trip-scope-exclusion` in
`test/investigation/scope_exclusion_test.clj`, runs every default
mock-advisor proposal for every allowlisted op — including one whose
`:window` text deliberately names "surveillance operation" as a
field-work type, the exact false-positive shape a bare-noun list would
have tripped on — through `policy/scope-exclusion-violations` directly
and asserts zero hits, alongside a companion test proving the same gate
still catches a real finalization/authorization action phrase.

Escalation (SOFT, always human sign-off, only reached when the governor
is otherwise clean):

- `:flag-legal-compliance-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

`investigation.phase`'s 0→3 rollout table independently agrees:
`:flag-legal-compliance-concern` is never a member of any phase's `:auto`
set, at any phase — two layers, not one, enforce the same invariant.

### 3. Module shape

`investigation.store` (MemStore + DatomicStore, both satisfying the same
`Store` protocol contract; DatomicStore uses `langchain-store.core`'s
shared codec/schema/entity field-spec machinery, ADR-2607141600, rather
than hand-rolling `enc`/`dec*` — this is a new store written after that
lib landed), `investigation.facts` (R0 investigator-qualification
catalog), `investigation.llm` (CaseCoordinator-LLM advisor, mock + a
real-LLM seam via `langchain.model`), `investigation.policy`
(RoutingGovernor), `investigation.phase` (0→3 rollout),
`investigation.operation` (the `langgraph-clj` StateGraph: intake →
advise → govern → decide → commit | hold | request-approval),
`investigation.sim` (demo driver, `clojure -M:dev:run`).

### 4. Documentation addition (not correction)

Unlike some sibling promotions in this batch, the pre-existing
boilerplate `README.md`/`docs/business-model.md` for 8030 already
mentioned governor-gated human sign-off for high-stakes actions rather
than claiming direct actuation, so it was not internally contradicted by
the narrower coordination-only scope actually implemented — it was left
in place as the broader business-blueprint vision, and a new "Actor
implementation" section was ADDED to `README.md` (not a rewrite)
explicitly scoping the shipped `investigation.*` code to the narrower
case-scheduling/evidence-logging coordination actor described above, so
a reader is not misled into thinking the full robotics-surveillance
business vision is what ships in `src/`. `CODE_OF_CONDUCT.md`/
`CONTRIBUTING.md`/`LICENSE`/`SECURITY.md`/`blueprint.edn`/`GOVERNANCE.md`
(generic or already-accurate) were left untouched.

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "8030" ...}` block only, verified as the sole occurrence before
  editing, never a whole-file parse/reserialize): `:maturity` added
  (`:implemented`, previously absent — resolved to `:blueprint` via the
  `:repo`-present fallback in `kotoba.industry/maturity-of`);
  `:business-id` corrected from the stale `"cloud-itonami-8030"` to the
  standard `"cloud-itonami-isic-8030"` convention (independently
  confirmed as the dominant pattern — 343 of 382 `:implemented` entries
  in the live registry use the `cloud-itonami-isic-XXXX` form vs. 39
  using the plain form). `:repo`/`:required-technologies`/
  `:operating-states` were already correct and left unchanged.
- `test/kotoba/industry_test.clj` (a very hot, actively-contended shared
  file — a concurrent sibling agent PUT a `cloud-itonami-isic-8010`
  registry promotion to `main` in the narrow window between this ADR's
  own registry PUT and its test-file PUT): the pre-existing
  `"cloud-itonami-isic-8030, freshly published, is also :blueprint"`
  assertion was corrected in place to `:implemented`; the two
  hardcoded whole-suite tier-count assertions (`:blueprint`/
  `:implemented`) were recomputed live via
  `(kotoba.industry/maturity-summary)` against a freshly re-fetched
  `origin/main` immediately before each PUT and updated
  (`:blueprint` 15→12, `:implemented` 402→405) — that combined delta
  reflects this promotion's own `cloud-itonami-isic-8030` plus two
  concurrent sibling fleet agents' own `cloud-itonami-isic-8010`/`-8020`
  promotions landed in the same fast-moving window, not solely this
  promotion's own work (documented honestly rather than
  mis-attributed). Two pre-existing, out-of-scope failures remain in
  that shared file after this ADR's own edits — the individual
  `maturity-tier` assertions for `"8010"`/`"8020"` still expect
  `:blueprint` even though their own registries were already promoted
  to `:implemented` by their respective concurrent agents — left
  untouched deliberately (their own agents' responsibility, not this
  ADR's ISIC-8030-only scope; touching another entry's assertion would
  violate this fleet's single-entry-edit discipline).
- Actor repo `cloud-itonami/cloud-itonami-isic-8030` filled in (existing
  blueprint-tier repo, not recreated) and pushed to `main`
  (`7aea02f`, on top of the pre-existing `a4ac443` blueprint-publish
  commit).
- Test suite, run directly by this session (not agent self-report), both
  immediately after the initial push AND again against a completely
  fresh clone (plus fresh `kotoba-lang/{langgraph,langchain,
  langchain-store}` siblings):
  **`Ran 42 tests containing 176 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`). `clojure -M:lint`: 0 errors, 0 warnings.
  `clojure -M:dev:run` (`investigation.sim` demo) walked all eight
  representative scenarios (two governor-clean commits, four
  independent HARD-hold reasons — clearance-tier, capacity,
  structural-scope [× 2 shapes], authorization — and one
  always-escalating compliance-concern flag through approve→commit)
  without error.
- `kotoba-lang/industry`'s own full suite, re-run from a fresh clone
  after all of the above landed: `Ran 15 tests containing 1062
  assertions. 2 failures, 0 errors.` — both failures are the
  pre-existing, out-of-scope `"8010"`/`"8020"` items noted above, not
  attributable to this ADR's own `"8030"` work (whose own assertions
  all pass).

## References

- `cloud-itonami-isic-8299/` (module-shape mirror — chosen over the
  originally-nominated `cloud-itonami-isic-8020` reference, which was
  found still blueprint-only at build time; `:operating-states` verified
  as an exact structural match before adoption)
- `kotoba-lang/langchain-store` (`langchain-store.core`, ADR-2607141600
  — DatomicStore codec/schema/field-spec machinery)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"8030"` entry
- ADR-2607121000 (Wave definition)
- ADR-2630801000 (`cloud-itonami-isic-8010`, closest sibling promotion
  landed concurrently with this one in the same fleet batch — same
  documentation-correction/coordination-only pattern lineage)
