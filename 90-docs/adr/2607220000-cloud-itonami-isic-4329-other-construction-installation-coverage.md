# ADR-2607220000: cloud-itonami ISIC 4329 (Other Construction Installation) coverage

## Status

Accepted, implemented.

## Context

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn` catalogs
every ISIC Rev.4 class as a cloud-itonami "actor" -- an LLM/advisor node
sealed behind an independent Governor, exposed as a langgraph-clj
StateGraph with an append-only audit ledger. ISIC class `4329` ("Other
construction installation") was `:spec` (design-only, no repo) prior to
this ADR:

```clojure
{:id "4329"
 :name "Other construction installation"
 :repo "https://github.com/gftdcojp/cloud-itonami-F4329"
 :business-id "cloud-itonami-F4329"
 :required-technologies [:robotics :identity :forms :dmn :bpmn :audit-ledger :cae]
 :optional-technologies []
 :maturity :spec
 :operating-states [:intake :design :permit :build :inspect :audit]}
```

4329 covers thermal/acoustic insulation, sound-proofing, elevator/
escalator installation, and other specialty building installation work
not elsewhere classified -- distinct from 4321 (electrical installation)
and 4322 (plumbing, heat and air-conditioning installation). This is part
of the ongoing careful, smaller-batch cloud-itonami fleet rollout
(stricter protocol: capable model + mandatory `clojure -M:test`
verification + registry exact-block-edit discipline) that replaced an
earlier 18-agent haiku batch with a 61% defect rate.

Before starting: verified the live registry `:name` for `{:id "4329"
...}` is exactly "Other construction installation" (matching the
assigned premise, distinct from siblings 4321/4322), and confirmed via
`gh api repos/cloud-itonami/cloud-itonami-isic-4329` (404) that no repo
existed yet.

## Decision

Implement `cloud-itonami-isic-4329` as a coordination-only actor,
mirroring the structural pattern of
[`cloud-itonami-isic-4330`](https://github.com/cloud-itonami/cloud-itonami-isic-4330)
(building completion and finishing), the closest sibling in this fleet:

- **Installation Advisor** (LLM proposal-only, `src/installation/
  advisor.cljc`) sealed behind an independent **Installation Governor**
  (`src/installation/governor.cljc`).
- Closed 4-op allowlist, every op `:effect :propose` only:
  `:log-site-record`, `:schedule-installation-operation`, `:flag-
  safety-concern` (ALWAYS escalates to a human, every phase), `:order-
  supplies` (escalates above a cost threshold or below the confidence
  floor).
- Eight HARD, human-unoverridable governor checks: unknown op, effect
  not `:propose`, forbidden action class (trade-equipment-control /
  direct-actuation / installation-completion-sign-off-finalization
  markers), site not independently verified/registered, legal-basis
  missing, pre-work hazmat-survey incomplete, fall-protection
  noncompliant (quantitative jurisdictions only), unresolved safety
  concern on file.
- Per-jurisdiction (JPN/USA/DEU) legal-basis catalog
  (`src/installation/facts.cljc`) with independently verified official
  citations:
  - JPN: 石綿障害予防規則第3条 (pre-work hazmat survey) +
    労働安全衛生規則第518条 (2m fall-protection trigger) -- reused from
    `finishing.facts`, since both are generic OSH regulations that apply
    across building trades, not domain-specific to finishing.
  - USA: **29 CFR 1926.1101** (OSHA Asbestos standard, Construction --
    independently confirmed via WebFetch against osha.gov to require a
    competent person's initial exposure assessment immediately before or
    at the initiation of any operation that may disturb asbestos-
    containing material) + 29 CFR 1926.501 (6ft/1.8m fall-protection
    trigger, reused). This deliberately DIFFERS from `finishing.facts`'s
    USA hazmat citation (EPA's Lead RRP surface-coating rule) -- legacy
    asbestos-containing insulation (vermiculite attic fill, pipe/duct
    lagging) is the domain-appropriate hazmat concern for installation/
    retrofit work, not a surface-coating concern.
  - DEU (EU proxy): Directive 2009/148/EC + TRGS 519 (hazmat, reused) +
    TRBS 2121 (qualitative fall-protection, reused).
- 0->3 phase rollout (`src/installation/phase.cljc`):
  `:schedule-installation-operation` is deliberately NOT a permanent
  `high-stakes`/non-auto op (unlike `cloud-itonami-isic-4311`/`cloud-
  itonami-isic-4210`'s schedule ops) -- it MAY auto-commit at phase 3
  once the eight HARD checks are clean and confidence is high, the same
  structural choice `cloud-itonami-isic-4330` makes, reflecting building-
  installation trade work's materially lower risk profile relative to
  demolition or heavy-civil earthwork scheduling.
- Dual `MemStore`/`DatomicStore` (`langchain-store.core`) backend, same
  `Store` protocol contract test pattern as every sibling actor.
- `blueprint.edn`: `:itonami.blueprint/robotics false` -- honest, no
  trade-equipment-control or installation-completion-sign-off authority.
  No new Rust code, no robot-control code (per this workspace's CLAUDE.md
  `.cljc`/`.kotoba` runtime priority rule) -- this actor only ever
  coordinates *potential* trade-crew/equipment dispatch via `:propose`-
  only proposals, matching the coordination-only premise of
  `cloud-itonami-isic-4211`/`4210`/`4220`/`4311`/`4330`.
- Fully portable `.cljc`, no JVM interop in `src/` -- `installation.
  notify`'s real transport is a caller-injected function seam
  (`fn-notifier`), not an embedded platform HTTP client.

Repo: [`cloud-itonami/cloud-itonami-isic-4329`](https://github.com/cloud-itonami/cloud-itonami-isic-4329),
commit `395bd46bd87622006082ff17b783391adffe141e` on `main`.

## Verification

```
Ran 69 tests containing 248 assertions.
0 failures, 0 errors.
```
(`clojure -M:dev:test`, run from a temp checkout structured as
`orgs/cloud-itonami/cloud-itonami-isic-4329` with `orgs/kotoba-lang/
{langgraph,langchain-store,langchain}` siblings so the repo's relative
`:local/root` deps resolve -- exactly matching the reference
`cloud-itonami-isic-4330`'s scale of 69 tests / 248 assertions.)

`clojure -M:lint` (clj-kondo): `errors: 0, warnings: 0`.

`clojure -M:dev:run` demo driver exercises all 14 scenarios (happy-path
auto-commits, escalations, all eight HARD-hold failure modes, cross-
jurisdiction USA/DEU walkthroughs) without exception.

Post-push re-verification (fresh clone into a new temp dir, re-cloning
`kotoba-lang/technology` and reconstructing the `orgs/kotoba-lang/*`
sibling structure): re-ran `clojure -M:dev:test`, same green result;
confirmed via `git merge-base --is-ancestor` that the pushed commit is
an ancestor of `origin/main` on `cloud-itonami/cloud-itonami-isic-4329`.

## Registry follow-up

`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
`{:id "4329" ...}` block updated in place (exact-text edit, no
whole-file reserialization): `:maturity :spec` -> `:implemented`,
`:repo` -> `https://github.com/cloud-itonami/cloud-itonami-isic-4329`,
`:business-id` -> `"cloud-itonami-isic-4329"`, `:required-technologies`
narrowed to drop `:robotics`/`:dmn`/`:bpmn`/`:cae` (this actor is
coordination-only, no CAE/robotics/BPMN dependency) to
`[:identity :forms :audit-ledger :notifications]` matching the
implemented slice, plus an `:adr` pointer to this document. Landed via
`kotoba.industry/maturity-summary`-recomputed `industry_test.clj`
assertion bump, full suite re-verified green before push.

## Consequences

- `cloud-itonami-isic-4329` joins the fleet as another coordination-only,
  non-actuating building-trade actor, following the `4330` pattern
  exactly (module-for-module parity: `advisor`/`facts`/`governor`/
  `notify`/`operation`/`phase`/`registry`/`sim`/`store`).
- The USA hazmat-survey citation choice (OSHA asbestos standard vs.
  finishing's EPA lead-paint rule) is a precedent worth reusing when
  future ISIC classes in this fleet need a domain-appropriate hazmat
  legal basis rather than defaulting to whatever a structurally similar
  sibling actor already cites.
- `kotoba-lang/industry`'s implemented-actor count increases by one;
  `industry_test.clj`'s aggregate assertion was recomputed from the live
  file via `kotoba.industry/maturity-summary`, not hand-incremented.
