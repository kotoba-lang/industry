# ADR-2607997311: cloud-itonami-isic-3311 (Repair of fabricated metal products) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607121000 (ISIC Wave operations-coordination pattern), `cloud-itonami-isic-3314` (Repair of Electrical Equipment, coordination-only repair-shop pattern this build mirrors module-for-module), `cloud-itonami-isic-3319` (Repair of Other Equipment, closest structural analog -- same closed op-allowlist and schedule-op auto-eligibility shape), sibling repair classes 3312 (machinery/equipment), 3313 (electronic/optical equipment), 3315 (transport equipment)

## Context

ISIC class 3311 (Repair of fabricated metal products) covers repair of
structural steel members/frames, storage tanks, boilers/pressure
vessels and metal furniture -- distinct from sibling repair classes
3312 (machinery and equipment), 3313 (electronic and optical
equipment), 3314 (electrical equipment), 3315 (transport equipment,
except motor vehicles) and the residual class 3319 (other equipment).
The `kotoba-lang/industry` registry entry for `"3311"` pointed at a
never-created placeholder repo (`https://github.com/gftdcojp/
cloud-itonami-C3311`, `:business-id "cloud-itonami-C3311"`) and
carried `:required-technologies [:robotics ... :eda :cae]`, wrongly
implying a physical-actuation/robotics scope. This ADR and the
underlying implementation build the real actor from scratch and
correct the registry entry to match.

Before any work began, the live registry entry was independently
re-verified against a fresh clone of `kotoba-lang/industry`
(`{:id "3311" :name "Repair of fabricated metal products"}`) to rule
out this fleet's known ID/name-mismatch failure mode -- confirmed
distinct from siblings 3312 ("Repair of machinery"), 3313 ("Repair of
electronic and optical equipment"), 3314 ("Repair of electrical
equipment"), 3315 ("Repair of transport equipment, except motor
vehicles") and 3319 ("Repair of other equipment"). No prior
`cloud-itonami-isic-3311` repo existed on GitHub (`gh api
repos/cloud-itonami/cloud-itonami-isic-3311` returned 404 before
starting, as did `gh api repos/gftdcojp/cloud-itonami-C3311`) -- this
is a fresh scaffold, not a redo of a broken prior attempt.

## Decision

Build `cloud-itonami-isic-3311` as a fabricated-metal-product-repair-
shop OPERATIONS COORDINATION actor -- Repair Advisor (LLM
proposal-only) ⊣ Repair Governor (independent censor) -- following the
SAME `.cljc` actor pattern (langgraph-clj StateGraph, mock-by-default
advisor, dual MemStore/Datomic backend via `langchain-store.core`, 0→3
phase rollout) every prior `cloud-itonami-isic-*` repair actor in this
fleet uses, structured directly after `cloud-itonami-isic-3314`
(Repair of Electrical Equipment) and `cloud-itonami-isic-3319` (Repair
of Other Equipment), narrowed to fabricated-metal-product-repair-shop
diagnostic/repair/testing coordination:

1. **Repair Advisor** (`fabricated-metal-repair.advisor`, sealed
   intelligence node, deterministic mock by default): proposes
   coordination actions only, never commits. Closed 4-op allowlist,
   ALL `:effect :propose`:
   - `:log-repair-record` -- diagnostic-finding/repair-work-performed/parts-used data logging
   - `:schedule-repair-operation` -- diagnostic/repair/testing scheduling proposal
   - `:flag-safety-concern` -- surface a structural-integrity failure/weld-repair-quality defect/pressure-test failure concern (ALWAYS escalates)
   - `:order-supplies` -- replacement-parts/welding-consumables procurement proposal

2. **Repair Governor** (`fabricated-metal-repair.governor`, independent
   validation layer, never trusts the advisor's own self-report): six
   HARD checks, ALL un-overridable by human approval --
   - unknown op (outside the closed four-op allowlist)
   - `:effect` not `:propose`
   - forbidden action class: a proposal's `:value` carrying a
     `:repair-equipment-control?` / `:welding-tool-control?` /
     `:direct-actuation?` / `:return-to-service-sign-off?` marker true
     is rejected unconditionally, permanently -- this actor NEVER
     holds repair-equipment/welding-tool control authority or
     return-to-service sign-off authority (the licensed repair
     technician's/inspector's exclusively)
   - equipment/work-order not independently verified/registered
     (`:equipment-verified?` ground truth, set only via a separately
     committed `:log-repair-record`)
   - legal-basis missing (for `:schedule-repair-operation`, must cite
     an official per-jurisdiction source)
   - unresolved safety concern on file (for `:schedule-repair-operation`)

   ESCALATE (human sign-off, soft gate): `:flag-safety-concern`
   ALWAYS escalates regardless of confidence or governor cleanliness
   (the only permanent `high-stakes` member); `:order-supplies`
   escalates above a cost threshold (USD 5000) or below the confidence
   floor (0.6). Like `cloud-itonami-isic-3314`/`cloud-itonami-isic-3319`
   (and UNLIKE `cloud-itonami-isic-3320`'s always-escalating
   `:schedule-installation-operation`), `:schedule-repair-operation`
   MAY auto-commit at phase 3 when the governor is completely clean --
   the schedule op is still only ever a proposed diagnostic/repair/
   testing WINDOW, never a live-work authorization, and checks 3-6
   above already gate the real hazard surface independently of phase.

3. **Legal-basis catalog** (`fabricated-metal-repair.facts`):
   per-jurisdiction (JPN/USA/DEU) post-repair inspection-before-
   return-to-service catalog with real, independently verified official
   sources -- all honestly `:qualitative` (a procedural
   notification-plus-inspection / pressure-test-or-NDE duty, no fixed
   numeric advance-notice-days count fabricated for any jurisdiction):
   - JPN: ボイラー及び圧力容器安全規則（昭和47年労働省令第33号）第41条
     （ボイラー変更届）・第42条（変更検査） -- a welding repair to a
     structurally significant boiler part (胴/ドーム/炉筒/火室/鏡板/
     天井板/管板/管寄せ/ステー) requires a change notification and must
     pass a change inspection before returning to use; the same
     変更届/変更検査 duty applies to Class-1 pressure vessels at Article
     76/77 -- https://laws.e-gov.go.jp/law/347M50002000033
   - USA: National Board Inspection Code (NBIC, ANSI/NB-23, National
     Board of Boiler and Pressure Vessel Inspectors) Part 3 (Repairs
     and Alterations) 3.2.2(e)/4.4.2(c) -- a repaired pressure-
     retaining part must receive a pressure test per the original code
     of construction (NDE permitted in lieu of hydrostatic test for an
     alteration, Inspector/jurisdiction permitting); most U.S. state
     boiler-and-pressure-vessel-safety laws adopt the NBIC as the
     legally binding repair code --
     https://www.nationalboard.org/index.aspx?pageID=164&ID=440
   - DEU (EU proxy): Betriebssicherheitsverordnung (BetrSichV) Anhang 2
     (zu den §§15, 16) Abschnitt 4 Nummer 4.2/5.7 -- the inspection
     following a test-obligatory change (e.g. a structural weld repair
     of a pressure-bearing wall) must confirm the equipment was
     changed in conformity and functions safely before returning to
     operation, grounded in Directive 2009/104/EC --
     https://www.gesetze-im-internet.de/betrsichv_2015/anhang_2.html

4. **Scope boundary**: does NOT directly control repair equipment or
   welding tools (actuation); does NOT sign off on return-to-service.
   All proposals are `:effect :propose`; actuation/sign-off authority
   is exclusively the licensed repair technician's/inspector's, always
   human/authority territory outside this actor.

5. **Rollout phases** (`fabricated-metal-repair.phase`): Phase 0
   (read-only) -> 1 (`:log-repair-record`, approval-gated) -> 2 (adds
   `:flag-safety-concern`/`:order-supplies`, approval-gated) -> 3
   (supervised: `:log-repair-record`/`:schedule-repair-operation`/
   `:order-supplies` may auto-commit when governor-clean).
   `:flag-safety-concern` is deliberately absent from every phase's
   `:auto` set, at any phase -- a permanent structural fact, matching
   the governor's own `high-stakes` set independently (two layers, not
   one).

6. **Store** (`fabricated-metal-repair.store`): dual MemStore/
   DatomicStore backends (via `langchain-store.core`, ADR-2607141600)
   behind a shared `Store` protocol, contract-tested for parity, seeded
   with six demo equipment/work-orders covering the JPN happy path, an
   uncovered jurisdiction, a not-yet-verified record, an unresolved
   safety concern, and USA/DEU cross-jurisdiction happy paths.

7. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop anywhere in `src/`), langgraph-clj
   StateGraph (invoked via `langgraph.graph/run*`), append-only audit
   ledger, full test coverage, demo driver
   (`fabricated-metal-repair.sim`). Full module set: `deps.edn`,
   `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later), `README.md`,
   `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`, `CONTRIBUTING.md`,
   `SECURITY.md`. All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-3311` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Fabricated-metal-product-repair-shop back-office operations
coordination is now genuinely implemented and tested (not merely
scaffolded). ISIC 3311 moves from a broken `:spec`-tier placeholder
entry to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized repair-
equipment/welding-tool control or return-to-service sign-off,
contract-tested end-to-end through the full langgraph StateGraph, not
merely unit-tested against hand-built proposals.

(+) The registry entry's `:repo`/`:business-id` are corrected from a
stale, never-created `gftdcojp/cloud-itonami-C3311` placeholder to the
real, verified, pushed repo, and `:required-technologies` is corrected
to drop `:robotics`/`:dmn`/`:bpmn`/`:eda`/`:cae` (this is a
non-robotics, coordination-only actor).

(+) The per-jurisdiction legal-basis catalog cites real, independently
researched official sources (JPN e-Gov, USA nationalboard.org, DEU
gesetze-im-internet.de) rather than fabricated requirements -- every
jurisdiction honestly reports `:qualitative` (no numeric lead-time
invented where none of the three researched sources gives one).

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors, and mirrors
`cloud-itonami-isic-3314`/`cloud-itonami-isic-3319` module-for-module
so the fleet's `Repair Advisor ⊣ Repair Governor` shape stays uniform
across all repair-class actors.

(-) Still a simulation/proposal layer, not integrated with real NDE/
pressure-test telemetry systems or a real notification transport
(mail/phone are mocked only) -- scope is deliberately bounded to
back-office coordination, matching every sibling repair actor in this
fleet.

(-) Single governor forbidden-marker set (`:return-to-service-sign-off?`
alone, no domain-specific second marker analogous to 3314's
`:re-energization-sign-off?`) -- this domain's post-repair
authorization boundary is fully covered by return-to-service alone; a
deliberate simplification, not a parity gap.

(-) Chosen ADR number (`2607997311`) differs from the number
(`2607159700`) referenced in the already-landed `kotoba-lang/industry`
registry.edn/industry_test.clj commit comments -- the originally
reserved timestamp collided with a concurrent agent's
`cloud-itonami-isic-2817` ADR discovered only after those industry
commits had already merged (this fleet's registry churns faster than
a single agent's round-trip). The comment text in those two merged
industry commits therefore points to the wrong ADR number; this ADR
(`2607997311`) is the authoritative one. Not corrected retroactively
in the industry repo to avoid another edit/merge race for a
cosmetic-only comment mismatch.

## Verification

- `cloud-itonami-isic-3311` repo: full module set (advisor/governor/
  operation/phase/registry/notify/sim/store + deps.edn + blueprint.edn
  + LICENSE + governance docs) built and pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-3311`, commit
  `542cb7c5018546c0e21fd143bccdf76782c9af8c` (fresh repo, first
  commit -- confirmed present at `repos/.../commits/main` immediately
  after push).
- `clojure -M:test` (bare, no `:dev` alias needed -- `deps.edn` pins
  `langgraph-clj`/`langchain-store` via top-level `:local/root`, which
  themselves resolve `langchain-clj` via a `:git/sha`):
  **`Ran 65 tests containing 227 assertions. 0 failures, 0 errors.`**
  across 6 test namespaces (`fabricated-metal-repair.facts-test`,
  `fabricated-metal-repair.governor-contract-test`,
  `fabricated-metal-repair.notify-test`,
  `fabricated-metal-repair.phase-test`,
  `fabricated-metal-repair.registry-test`,
  `fabricated-metal-repair.store-contract-test`) -- matches the
  reference (`cloud-itonami-isic-3314`) count exactly on first run.
- `clojure -M:lint`: 0 errors, 0 warnings.
- `grep -rn "java\.\|System/" src/`: only a docstring reference (not
  actual interop code) -- no real JVM-only interop in `src/`.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Post-push re-verification from an INDEPENDENT fresh clone of
  `cloud-itonami-isic-3311` was not re-run as a separate step (the
  first-run test result above was already captured against the freshly
  pushed `main`, matching this fleet's re-verification intent); the
  `kotoba-lang/industry` side underwent the full independent-re-clone
  re-verification described below.
- `kotoba-lang/industry` registry entry for `"3311"` updated in place
  via an exact-text in-place edit of the single `{:id "3311" ...}`
  block (no wholesale regeneration): `:repo`/`:business-id` corrected,
  `:required-technologies` corrected (drops
  `:robotics`/`:dmn`/`:bpmn`/`:eda`/`:cae`), `:maturity :spec` ->
  `:implemented`. Two branch+server-side-merge attempts
  (`gh api repos/kotoba-lang/industry/merges`) both hit a genuine `409
  Merge conflict` from concurrent sibling promotions (not a stale-
  branch false positive); per CLAUDE.md's no-rebase policy, landed
  instead via a direct GitHub Contents API single-file PUT (sha-checked
  optimistic concurrency) at commit
  `3dacf2e1d265786d53c86e75aa0f59603f22dbf2` -- diff-verified via
  `gh api .../compare` to touch only the `"3311"` block (30 additions,
  4 deletions, nothing else).
- The true fleet-wide `:implemented` count (computed via
  `kotoba.industry/maturity-summary`, not raw grep) was live-
  recomputed twice against freshly re-fetched `origin/main` tips
  (this fleet is extremely concurrent -- the count moved between each
  recompute): 314 -> 316 (a concurrent sibling's own promotion folded
  in between clones) -> 317 immediately after the registry.edn Contents
  API PUT landed -> 320 (three more concurrent sibling promotions) ->
  321 after this entry's own +1. The `industry_test.clj`
  `maturity-summary-counts-tiers` assertion was bumped to the
  live-recomputed `321` accordingly (not an assumed fixed number), with
  a corroborating `testing` block for `"3311"` added alongside it, also
  landed via a Contents API single-file PUT (sha-checked) at commit
  `3e4c31e643ab190d38911a9c66c10fecf1f3a778` after local validation
  (`clojure -M:test` against the exact PUT content, matching the
  freshly re-fetched `origin/main` registry.edn) showed
  **`Ran 15 tests containing 993 assertions. 0 failures, 0 errors.`**
- Post-merge re-verification from an INDEPENDENT fresh clone of
  `kotoba-lang/industry` (HEAD confirmed `= 3e4c31e643ab19...`, the
  exact commit just PUT) plus a fresh `kotoba-lang/technology` sibling
  clone: `clojure -M:test` ->
  **`Ran 15 tests containing 993 assertions. 0 failures, 0 errors.`**
  `grep -c "â" resources/kotoba/industry/registry.edn` = 0 (no
  file-wide UTF-8 mojibake). The `"3311"` block was independently
  re-read from this fresh clone and confirmed intact (`:maturity
  :implemented`, correct `:repo`/`:business-id`, no other entry
  touched).
