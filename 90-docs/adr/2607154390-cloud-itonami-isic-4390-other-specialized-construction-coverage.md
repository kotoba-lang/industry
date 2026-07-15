# ADR-2607154390: cloud-itonami ISIC 4390 (Other Specialized Construction Activities) coverage

## Status

Accepted, implemented.

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn` catalogs
every ISIC Rev.5 class as a cloud-itonami "actor" -- an LLM/advisor node
sealed behind an independent Governor, exposed as a langgraph-clj
StateGraph with an append-only audit ledger. ISIC class `4390` ("Other
specialized construction activities") was `:spec` (design-only, no repo)
prior to this ADR:

```clojure
{:id "4390"
 :name "Other specialized construction activities"
 :repo "https://github.com/gftdcojp/cloud-itonami-F4390"
 :business-id "cloud-itonami-F4390"
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :cae]
 :optional-technologies []
 :maturity :spec
 :operating-states [:intake :design :permit :build :inspect :audit]}
```

4390 is ISIC's residual specialized-construction-trade class: scaffolding
erection, foundation-piling work, waterproofing, and other specialty
trade work not elsewhere classified -- outside 4321 (electrical), 4322
(plumbing/heat/air-conditioning), 4329 (other construction installation)
and 4330 (building completion and finishing). This is part of the
ongoing careful, smaller-batch cloud-itonami fleet rollout (stricter
protocol: capable model + mandatory `clojure -M:test` verification +
registry exact-block-edit discipline) that replaced an earlier 18-agent
haiku batch with a 61% defect rate; 108+ consecutive agents on this
stricter protocol had succeeded before this one.

Before starting: verified the live registry `:name` for `{:id "4390"
...}` is exactly "Other specialized construction activities" (matching
the assigned premise), and confirmed via `gh api
repos/cloud-itonami/cloud-itonami-isic-4390` (404) that no repo existed
yet.

## Decision

Implement `cloud-itonami-isic-4390` as a coordination-only actor,
mirroring the structural pattern of
[`cloud-itonami-isic-4329`](https://github.com/cloud-itonami/cloud-itonami-isic-4329)
(other construction installation) /
[`cloud-itonami-isic-4330`](https://github.com/cloud-itonami/cloud-itonami-isic-4330)
(building completion and finishing), the closest siblings in this fleet:

- **Specialized Trade Advisor** (LLM proposal-only, `src/specialized/
  advisor.cljc`) sealed behind an independent **Specialized Trade
  Governor** (`src/specialized/governor.cljc`).
- Closed 4-op allowlist, every op `:effect :propose` only:
  `:log-site-record`, `:schedule-specialized-operation`, `:flag-
  safety-concern` (ALWAYS escalates to a human, every phase), `:order-
  supplies` (escalates above a cost threshold or below the confidence
  floor). Named `:schedule-specialized-operation`, deliberately NOT
  reusing `cloud-itonami-isic-4330`'s `:schedule-finishing-operation`
  name -- 4390's residual scaffolding/piling/waterproofing scope is not
  building-completion/finishing work, and this fleet's own mislabeling
  discipline argues against borrowing a domain-mismatched op name from a
  sibling actor's different ISIC class.
- Eight HARD, human-unoverridable governor checks: unknown op, effect
  not `:propose`, forbidden action class (trade-equipment-control /
  direct-actuation / structural-completion-sign-off-finalization
  markers), site/permit not independently verified/registered,
  legal-basis missing, scaffold-inspection incomplete, pile-driving-
  vibration noncompliant (quantitative jurisdictions only), unresolved
  safety concern on file.
- Per-jurisdiction (JPN/USA/DEU) legal-basis catalog
  (`src/specialized/facts.cljc`) with independently verified official
  citations (via WebSearch/WebFetch against each authority before being
  written):
  - Scaffold-inspection basis: JPN 労働安全衛生規則第567条（点検）/ USA
    29 CFR 1926.451 (general scaffold requirements + competent-person
    inspection) / DEU TRBS 2121 Teil 1 + DGUV Vorschrift 38 §12 (2.00 m
    side-protection trigger).
  - Pile-driving-vibration basis (this domain's OWN independently-
    recheckable numeric hazard, distinct from `installation.facts`'s
    at-height fall-protection trigger): JPN 振動規制法（昭和51年法律第64
    号）-- 75 dB site-boundary vibration LEVEL trigger; DEU DIN
    4150-3:2016-12 -- 5 mm/s PEAK PARTICLE VELOCITY (row-2 residential/
    mixed-use Anhaltswert); USA honestly `:qualitative` -- OSHA has no
    federal numeric construction-vibration standard, only the OSH Act
    §5(a)(1) General Duty Clause. JPN's dB and DEU's mm/s are genuinely
    different physical quantities -- `specialized.facts` never fabricates
    a shared cross-jurisdiction unit or conversion factor between them.
- 0->3 phase rollout (`src/specialized/phase.cljc`): UNLIKE
  `cloud-itonami-isic-4329`/`cloud-itonami-isic-4330`, and LIKE
  `cloud-itonami-isic-4311`/`cloud-itonami-isic-4210`,
  `:schedule-specialized-operation` here IS a permanent `high-stakes`
  member -- it NEVER auto-commits, at any phase, even when the governor
  is completely clean. Rationale: scaffold collapse and pile-driving-
  induced ground vibration damaging a neighboring structure are
  genuinely closer in public-safety stakes to demolition/heavy-earthwork
  scheduling than to 4329/4330's insulation/finishing domain.
- Dual `MemStore`/`DatomicStore` (`langchain-store.core`) backend, same
  `Store` protocol contract test pattern as every sibling actor.
- `blueprint.edn`: `:itonami.blueprint/robotics false` -- honest, no
  trade-equipment-control or structural-completion-sign-off authority.
  No new Rust code, no robot-control code (per this workspace's CLAUDE.md
  `.cljc`/`.kotoba` runtime priority rule) -- this actor only ever
  coordinates *potential* trade-crew/equipment dispatch via `:propose`-
  only proposals, matching the coordination-only premise of
  `cloud-itonami-isic-4211`/`4210`/`4220`/`4311`/`4329`/`4330`.
- Fully portable `.cljc`, no JVM interop in `src/` -- `specialized.
  notify`'s real transport is a caller-injected function seam
  (`fn-notifier`), not an embedded platform HTTP client.

Repo: [`cloud-itonami/cloud-itonami-isic-4390`](https://github.com/cloud-itonami/cloud-itonami-isic-4390),
commit `2ec7413ad9de3e285a998374d96a307786ff7724` on `main`.

## Verification

```
Ran 70 tests containing 256 assertions.
0 failures, 0 errors.
```
(`clojure -M:test`, run from a temp checkout structured as
`orgs/cloud-itonami/cloud-itonami-isic-4390` with `orgs/kotoba-lang/
{langgraph,langchain-store,langchain}` siblings so the repo's relative
`:local/root` deps resolve.)

`clojure -M:lint` (clj-kondo): `errors: 0, warnings: 0`.

`clojure -M:dev:run` demo driver exercises all 17 scenarios (auto-
commits, escalations that commit after approval, all eight HARD-hold
failure modes including an uncovered jurisdiction, a not-independently-
verified site, an incomplete scaffold inspection, a vibration violation,
an unresolved safety concern and an op outside the closed allowlist,
plus cross-jurisdiction USA/DEU schedule walkthroughs) without exception.

Post-push re-verification (fresh clone into a new temp dir, re-cloning
`kotoba-lang/langgraph`/`langchain-store`/`langchain` and reconstructing
the `orgs/kotoba-lang/*` sibling structure): re-ran `clojure -M:test`,
same green result; confirmed via `git rev-parse` that the pushed commit
SHA on `origin/main` for `cloud-itonami/cloud-itonami-isic-4390` matches
the local commit that was pushed.

## Registry follow-up

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
`{:id "4390" ...}` block updated in place (exact-text edit, no
whole-file reserialization): `:maturity :spec` -> `:implemented`,
`:repo` -> `https://github.com/cloud-itonami/cloud-itonami-isic-4390`,
`:business-id` -> `"cloud-itonami-isic-4390"`, `:required-technologies`
narrowed to drop `:robotics`/`:dmn`/`:bpmn`/`:cae` (this actor is
coordination-only, no CAE/robotics/BPMN dependency) to
`[:identity :forms :audit-ledger :notifications]` matching the
implemented slice (no `:adr` field added -- confirmed the live registry
schema does not carry one on any entry, including already-implemented
siblings, despite an earlier sibling ADR's aspirational text). Landed
after recomputing the true `:implemented` count from the live file via
`kotoba.industry/maturity-summary` (not `grep -c`) and bumping
`industry_test.clj`'s assertion to match; full suite re-verified green
before push.

## Consequences

- `cloud-itonami-isic-4390` joins the fleet as the residual specialized-
  construction-trade actor, structurally close to `4329`/`4330` but
  deliberately stricter on `:schedule-specialized-operation` auto-commit
  eligibility (permanent `high-stakes`, like `4311`/`4210`) given the
  scaffold-collapse/pile-driving-vibration public-safety stakes of its
  domain.
- Introduces this fleet's first per-jurisdiction numeric hazard check
  that deliberately does NOT normalize units across jurisdictions (JPN
  dB vibration level vs. DEU mm/s peak particle velocity) -- a precedent
  worth reusing when a future ISIC class's hazard genuinely has different
  native units per jurisdiction rather than one comparable quantity
  (unlike `installation.facts`/`finishing.facts`'s shared-meters
  fall-protection height trigger).
- Establishes the "do not borrow a domain-mismatched op name from a
  structurally similar sibling" precedent explicitly (`:schedule-
  specialized-operation`, not `:schedule-finishing-operation`) --
  relevant given this fleet's own repeated mislabeling incidents.
- `kotoba-lang/industry`'s implemented-actor count increases by one;
  `industry_test.clj`'s aggregate assertion was recomputed from the live
  file via `kotoba.industry/maturity-summary`, not hand-incremented.
