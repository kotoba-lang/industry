# ADR-2614000000: cloud-itonami ISIC 4290 (Construction of other civil engineering projects) actor coverage

## Status

Accepted. `cloud-itonami-isic-4290` scaffolded fresh (no prior repo
existed — confirmed via `gh api repos/cloud-itonami/cloud-itonami-isic-4290`
404 before this work began) and landed at
`https://github.com/cloud-itonami/cloud-itonami-isic-4290`, following
the verified fresh-scaffold protocol established across this fleet
(closest architectural siblings: `cloud-itonami-isic-4329`, other
construction installation, and `cloud-itonami-isic-4390`, other
specialized construction activities — both coordination-only actors).
This is the final target of the final batch of Wave 3 — after this
batch, only the two deliberately-scoped-out sensitive classes (2520
weapons/ammunition, 3040 military fighting vehicles) remain
unimplemented in the entire wave.

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
carried `{:id "4290", :name "Construction of other civil engineering
projects", ...}` at `:maturity :spec` with a stale placeholder
`:repo`/`:business-id` (`https://github.com/gftdcojp/cloud-itonami-F4290`
/ `cloud-itonami-F4290`) predating the current `cloud-itonami-isic-<id>`
naming convention. The live `:name` was independently re-verified
against a fresh clone before any work began, per this fleet's ID/name-
mismatch caution, and confirmed to match "Construction of other civil
engineering projects" exactly. This work implements the blueprint as a
real, tested `cloud-itonami-isic-<id>` actor repo and corrects the
registry entry's `:repo`/`:business-id` fields to match.

ISIC 4290 is the RESIDUAL civil-engineering category — it covers
industrial-plant civil works, pipeline construction, power-line/
electric-transmission-line construction, and outdoor sports facilities,
distinct from siblings 4211 (building construction), 4212 (civil
engineering for roads and railways) and 4220 (construction of utility
projects), all already `:implemented` in this fleet. Unlike those
siblings' single coherent asset class, 4290's hazard profile is
DISTINCT from the building-installation-trade fall-hazard/materials-
hazard profile `cloud-itonami-isic-4329`/`cloud-itonami-isic-4390`
established: heavy trenching/excavation adjacent to buried gas/water/
electrical/telecom utilities and excavation-collapse risk. This build
follows those two siblings' coordination-only architectural pattern
closely (same four-op shape, same langgraph-clj StateGraph + independent
Governor + Phase 0→3 rollout pattern, same jurisdiction-scoped
legal-basis-catalog discipline) but retargets the hazard profile and
legal-basis catalog to this domain's own real regulatory landscape
(pre-work buried-utility survey + excavation-shoring depth triggers,
independently verified against laws.e-gov.go.jp / osha.gov / dguv.de
before being written — see `civilworks.facts` ns docstring in the actor
repo for the full citation set and honesty discipline).

## Decision

Scaffold `cloud-itonami-isic-4290` mirroring the `cloud-itonami-isic-4329`
(other construction installation) / `cloud-itonami-isic-4390` (other
specialized construction activities) coordination-only architecture
closely — same four-op shape (`:log-site-record`/`:schedule-
construction-operation`/`:flag-safety-concern`/`:order-supplies`, all
`:effect :propose` only), same langgraph-clj StateGraph + independent
Governor + Phase 0→3 rollout pattern, same single-entity (site/permit)
independently-verified/registered gate structure — but retargeted to
the other-civil-engineering domain's own hazard profile and legal-basis
vocabulary:

- Namespace `civilworks` (`src/civilworks/{advisor,governor,phase,
  registry,store,notify,operation,sim}.cljc`).
- Governor keyword `:civilworks-governor` (`blueprint.edn`
  `:itonami.blueprint/governor`).
- Project-type field (informational, not a closed-set gate in this
  build): `:industrial-plant`/`:pipeline`/`:power-line`/`:outdoor-
  sports-facility`.
- Two independent legal bases per jurisdiction (mirroring 4329's
  hazmat-survey/fall-protection two-basis shape), retargeted to this
  domain's real hazard: `:utility-survey-basis` (pre-work buried-
  utility/ground-condition investigation duty) and `:excavation-
  shoring-basis` (trench/excavation-collapse protection duty above a
  jurisdiction-specific trigger depth). JPN (labor safety and health
  ordinance Art.355 pre-work survey / Art.356 slope-gradient table,
  2.0m quantitative trigger for the ordinary-ground row) and USA (OSHA
  29 CFR 1926.651(b) utility-location duty / 1926.652 protective-system
  duty, 1.5m/5ft quantitative trigger) are `:quantitative`; DEU/EU
  (§16 DGUV Vorschrift 38/39 utility-inquiry duty / DGUV Regel 101-604
  + DIN 4124 soil-classification-dependent shoring duty) is honestly
  `:qualitative` — no single fixed EU-wide numeric trigger exists, so
  none is fabricated (same honesty discipline `installation.facts`/
  `demolition.facts` established for their own domains).
- Site ground-truth fields: `:site-verified?`, `:utility-survey-
  completed?`, `:utility-strike-detected?`, `:safety-concern-
  unresolved?`, `:excavation-depth-m`, `:shoring-installed?`.
- Eight HARD governor checks (unknown op / effect-not-propose /
  forbidden-action-class / site-not-verified / no-legal-basis /
  utility-survey-incomplete / excavation-shoring-noncompliant /
  unresolved-safety-concern), all un-overridable by human approval —
  same count and same priority-ordering discipline as `installation.
  governor`.
- Permanent forbidden-action-class block on any proposal carrying
  `:heavy-equipment-control?`/`:direct-actuation?`/`:finalizes-
  structural-completion-sign-off?` markers true — this actor NEVER
  controls heavy equipment and NEVER finalizes a structural-completion
  sign-off (that remains the site supervisor / building official's
  exclusive authority), matching the CLAUDE.md constraint that this
  actor coordinate potential crew/equipment dispatch without directly
  actuating (the same "robotics premise: false, coordination-only"
  framing `cloud-itonami-isic-4211`/`4210`/`4220`/`4311`/`4329`/`4330`/
  `4390` share in this fleet).
- `:flag-safety-concern` UNCONDITIONALLY escalates to a human at every
  phase (permanently absent from every phase's `:auto` set — a
  structural fact, not a rollout milestone). `:order-supplies` escalates
  above a $5000 cost threshold or below the confidence floor.
  `:schedule-construction-operation` is deliberately NOT a permanent
  `high-stakes` member — mirroring `installation.governor`/`finishing.
  governor`'s deliberate choice (as opposed to `demolition.governor`/
  `roadrail.governor`'s schedule ops, which ARE unconditional high-
  stakes members) — it MAY auto-commit at phase 3 once all eight HARD
  checks are clean and confidence clears the floor.
- No new Rust code, no robot-control code, no JVM interop anywhere in
  `src/` — fully portable `.cljc`, `civilworks.notify`'s real-transport
  seam is a caller-injected plain-function seam (`fn-notifier`), not an
  embedded platform HTTP client.

Full rule-by-rule detail lives in the actor repo's own
`src/civilworks/governor.cljc` and `src/civilworks/facts.cljc` ns
docstrings — not duplicated here.

## Verification

Actor repo, built from a uniquely-named scratch dir
(`.../scratchpad/4290-work/build/orgs/cloud-itonami/cloud-itonami-isic-4290`,
mirroring the workspace's `orgs/cloud-itonami/<repo>` layout, sibling to
freshly-cloned `orgs/kotoba-lang/{langgraph,langchain,langchain-store}`,
so `deps.edn`'s `:local/root` paths resolve exactly as they would in the
monorepo checkout):

```
$ clojure -M:test
Ran 69 tests containing 247 assertions.
0 failures, 0 errors.

$ clojure -M:lint
linting took 685ms, errors: 0, warnings: 0

$ clojure -M:dev:run
(full demo narrative: happy-path auto-commit/escalate/approve for all
four ops across JPN/USA/DEU jurisdictions, then all six HARD-hold
scenarios exercised directly -- uncovered jurisdiction, site not
verified, utility survey incomplete, excavation-shoring noncompliant,
unresolved safety concern, op outside the closed allowlist -- no
exceptions)
```

Pushed to `main` at commit `408da9b688ed97cc01334cc8de0893785dcfc559`
(`cloud-itonami/cloud-itonami-isic-4290`), independently confirmed via
`gh api repos/cloud-itonami/cloud-itonami-isic-4290/commits/main`.
Re-verified from an INDEPENDENT fresh clone (same sibling layout) — see
the task's final report for the exact re-verification `clojure -M:test`
output.

`kotoba-lang/industry` registry: `"4290"` entry promoted `:spec` ->
`:implemented`, `:repo`/`:business-id` corrected from the stale
`gftdcojp/cloud-itonami-F4290` placeholder to
`https://github.com/cloud-itonami/cloud-itonami-isic-4290` /
`cloud-itonami-isic-4290`, exact in-place edit of only the `"4290"`
entry's block (no other entry touched). `test/kotoba/industry_test.clj`
maturity-count assertion bumped to the freshly recomputed true
`:implemented` count (via `kotoba.industry/maturity-summary`, not
`grep -c`) at edit time — see the task's final report for the exact
before/after counts and `clojure -M:test` output landed via `gh api
repos/kotoba-lang/industry/merges` (or Contents-API fallback).

## Consequences

(+) ISIC 4290 now has a documented, governed, auditable other-civil-
engineering-project-operations-coordination actor, following this
fleet's established pattern, cleanly scoped to the RESIDUAL civil-
engineering category (industrial-plant civil works, pipeline
construction, power-line construction, outdoor sports facilities)
distinct from its already-`:implemented` siblings 4211/4212/4220.

(+) The "coordination, not control" boundary (no heavy-equipment
control, no structural-completion sign-off) is a hard, permanent,
unconditional governor block plus real-law-grounded HARD checks — not
just asserted in prose.

(+) Safety-concern escalation (buried-utility-strike, excavation-
collapse, structural) is a core design invariant; the actor structurally
cannot auto-decide a safety concern at any confidence or phase.

(+) The registry's stale pre-`cloud-itonami-isic-<id>`-convention
placeholder repo/business-id for this entry is corrected as part of
this promotion.

(+) Portable `.cljc`, zero JVM-only constructs, zero new Rust/robot-
control code, clj-kondo 0 errors / 0 warnings.

(+) This is the final target of the final batch of Wave 3 — after this
batch, only the two deliberately-scoped-out sensitive classes (2520
weapons/ammunition, 3040 military fighting vehicles) remain
unimplemented in the entire wave.

(-) Still a simulation/proposal layer -- single `MemStore`/`DatomicStore`
(in-process `langchain.db`) backend, mock advisor, no real plant/permit-
management database integration. Same limitation as every sibling actor
in this fleet at this stage.

(-) The per-jurisdiction legal-basis catalog (`civilworks.facts`) seeds
only JPN/USA/DEU, a starting catalog rather than a survey of all ~194
jurisdictions — documented plainly as a scope limitation in the ns
docstring, extending coverage is additive and requires a real citable
source per jurisdiction, never fabricated.
