---
id: adr-2607162700-cloud-itonami-isic-750-veterinary-coordination
title: "ADR-2607162700: cloud-itonami-isic-750 (Veterinary activities ⊣ Veterinary Care Governor) — :spec → :implemented promotion"
status: accepted
doc_type: adr
topic: veterinary-clinic-governance-isic750
authoritative: true
last_verified: 2026-07-15
authoritative_for:
  - cloud-itonami-isic-750 :spec -> :implemented 昇格根拠
  - registry.edn の ISIC 750 登録と technology-stack の確定
related:
  - orgs/cloud-itonami/cloud-itonami-isic-7500
  - orgs/cloud-itonami/cloud-itonami-isic-8610 (hospital coordination, clinical parallel)
  - ADR-2607152500 (Wave 4 rollout amendment, 対人サービス safety guards)
supersedes: []
superseded_by: []
---

# ADR-2607162700: cloud-itonami-isic-750 (Veterinary activities ⊣ Veterinary Care Governor) — :spec → :implemented promotion

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki
**Scope**: `cloud-itonami/cloud-itonami-isic-750` (new actor implementation),
`kotoba-lang/industry` registry.edn (ISIC 750 entry promotion)

## Problem

ISIC 750 (Veterinary activities) was registered in `kotoba-lang/industry/registry.edn` as `:spec` (specification tier placeholder) with no implementation repository. The specification placeholder covered:

- Veterinary clinic/hospital case intake, diagnosis assessment, treatment recommendation, and discharge/recovery supervision
- Per-jurisdiction veterinary-licensing evidence verification (analogous to hospital/clinic humans-licensing assessment)
- Clinician-credential screening for practicing veterinarians (license currency, species-competency)
- Treatment administration proposal (with mandatory human veterinarian sign-off; see **Actuation** below)
- Food-safety withdrawal-period sufficiency check (NEW: first `:proposed-only` operation in this fleet combining temporal-window verification + type-tag gate)

The user requested implementation of a Veterinary Care Governor matching the same shape `cloud-itonami-isic-8610`'s Clinical Oversight Governor and `cloud-itonami-isic-8620`'s Clinic Coordinator Governor established for human-health verticals, adapted for animal-health domain.

## Decision

### Decision 1: Scope — case intake through treatment administration, explicitly NOT clinical judgment

`cloud-itonami-isic-7500` models veterinary case lifecycle (intake, per-jurisdiction licensing assessment, clinician-credential screening, treatment-administration drafting, and discharge supervision) as governed operations. It deliberately excludes independent clinical judgment (diagnosis severity, treatment selection, species-appropriate dosage ratios — those remain the licensed veterinarian's domain, not the actor's), and any procedural/surgical expertise (the actor drafts, a human veterinarian executes).

Like `cloud-itonami-isic-8610` (hospital) and `cloud-itonami-isic-8620` (clinic), this follows the three-phase pattern: **untrusted advisor** (VetOps-LLM) proposes, **independent governor** (Veterinary Care Governor) checks compliance, **human approval** (licensed veterinarian) commits or rejects. This three-layer discipline seals off the most dangerous failure modes:

- A fabricated jurisdiction's veterinary-licensing requirement invented by the LLM
- A treatment that appears on the patient's own recorded contraindication list being silently overlooked
- A food-producing animal's planned-harvest timeline leaving insufficient time for a prescribed drug's withdrawal period to elapse (NEW domain check)
- A clinician's license having lapsed

### Decision 2: Five HARD governor checks, two actuation gates, Phase 0→3 with actuation permanently excluded

`veterinary.governor` enforces five non-overridable checks (spec-basis, evidence-incomplete, contraindicated, withdrawal-period-insufficient, credential-not-current) per the same discipline as all sibling clinical actors. `:treatment/administer` and `:discharge/supervise` proposals ALWAYS route through human approval; no phase ever permits auto-commit. See `veterinary.phase_test.clj`'s `treatment-administer-never-auto-at-any-phase` / `discharge-supervise-never-auto-at-any-phase`.

**Actuation is never autonomous, by construction.** The actor may draft, check, and recommend; a human licensed veterinarian always executes. Like `cloud-itonami-isic-8610`/`8620`/`6512`/`6622`/`6520`/`6530`/`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`/`9102`/`9103`/`8890`, this actor has TWO actuation gates.

### Decision 3: Food-safety withdrawal-period check — first temporal-window + type-tag gate combination

`veterinary.registry/withdrawal-period-insufficient?` is the first governor check in this fleet to combine:
- A per-animal-record **minimum-threshold recompute** (days remaining vs. required withdrawal period) — shape from `marketadmin.governor`/`registrar.governor`
- A **type-tag gate** (`:food-producing?` boolean) — shape from `accounting.governor`

For a food-producing animal, the check independently recomputes whether planned-harvest-date minus current-date falls below the drug's recorded withdrawal period. For non-food animals, the check is structurally inert (withdrawal period is irrelevant). This avoids silent oversights when a species-tag changes or a harvest timeline compresses.

### Decision 4: Registry-entry in-place promotion, technology-stack refinement

ISIC 750 entry in `kotoba-lang/industry/registry.edn` is edited in place — `:repo`/`:business-id` added, `:maturity :spec -> :implemented`, `:required-technologies` / `:optional-technologies` aligned to the actor's own `blueprint.edn`, and `:robotics` / `:cae` removed (this is a coordinator-governor actor, not a robotics-dependent dispatch system — any future robotic animal restraint would be optional, not required). Same in-place-promotion pattern `cloud-itonami-isic-6110`'s ADR established.

### Decision 5: MemStore only for R0 — documented coverage gap

Like `cloud-itonami-isic-6110`, this release ships `veterinary.store/MemStore` only. The `Store` protocol is written so a `DatomicStore` is additive later; shipping a second backend is deferred, explicitly recorded in the namespace docstring.

## Verification

- **Tests**: `clojure -M:dev:test` — 33 tests / 139 assertions, 0 failures. Governor contract, phase invariants (`:treatment/administer`/`:discharge/supervise` never auto-commit at any phase), store parity, registry conformance (ISIC 750 food-producing-animal withdrawal-period data present), jurisdiction facts coverage.
- **Demo**: `clojure -M:dev:run` — one clean lifecycle (case intake → jurisdiction assessment → credential screening → treatment administration proposal → human approval) + five HARD-hold cases (no-spec-basis jurisdiction, incomplete-evidence missing, treatment contraindicated, withdrawal-period insufficient for food animal, credential lapsed).
- **Lint**: `clojure -M:dev:lint` — 0 errors/warnings.
- **Repo**: pushed to `github.com/cloud-itonami/cloud-itonami-isic-7500` (public). Verified via GitHub git-tree endpoint: `LICENSE`/`CODE_OF_CONDUCT.md`/`CONTRIBUTING.md`/`GOVERNANCE.md`/`SECURITY.md`/`README.md` present, `src/veterinary/{store,registry,facts,vetopsllm,governor,phase,operation,sim}.cljc`, `test/veterinary/*_test.clj`.
- **Registry**: `kotoba-lang/industry` `clojure -M:test` — 7 tests / 118 assertions, 0 failures (`maturity-summary`'s hardcoded `:implemented` count updated).

## Consequences

- (+) Closes ISIC 750's specification gap: `cloud-itonami` now has a veterinary actor with real, tested code and an independent Veterinary Care Governor, not a placeholder.
- (+) Confirms food-safety domain extension: a new temporal-window + type-tag governor check opens the path for future animal-health verticals with domain-specific constraints (quarantine windows, breed-specific care protocols).
- (+) Demonstrates the clinical-domain actor pattern at a third vertical (hospital / clinic / veterinary), with conservative human-approval enforcement at the actuation seam.
- (−) MemStore-only scope (Decision 5) is a known, documented gap; a `DatomicStore` follow-up is additive.
- (−) Withdrawal-period model is simplified: single minimum threshold per drug, not procedure-by-procedure / species-by-species / route-of-administration-specific detailed tables. Honest simplification recorded in `registry/withdrawal-period-insufficient?` docstring.

## Alternatives considered

- **Including full drug-interaction/allergy cross-reference database.** Rejected for R0: deployment context (who operates the veterinary practice, which jurisdiction, which animal species it serves) determines which interactions are relevant; a generic cross-ref would be overfit to no one. Whoever deploys provides the practice's actual formulary.
- **Making treatment-administration autonomous under phase 3.** Rejected: `veterinary.phase_test.clj`'s `treatment-administer-never-auto-at-any-phase` ensures this never happens. Licensed veterinarians remain the sole actuation authority.

## References

- `orgs/cloud-itonami/cloud-itonami-isic-7500/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-7500/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-8610/README.md` (hospital coordination, parallel scope)
- `orgs/cloud-itonami/cloud-itonami-isic-8620/docs/adr/0001-architecture.md` (clinic coordination, parallel scope)
