# ADR-2607999700: cloud-itonami ISIC 1080 (Manufacture of prepared animal feeds) coverage

## Status

Accepted. `cloud-itonami-isic-1080` scaffolded and promoted from
`:spec` to `:implemented` in the `kotoba-lang/industry` registry.

## Context

cloud-itonami codifies every ISIC industry class as an autonomous
"actor" (LLM/advisor behind an independent Governor, langgraph-clj
StateGraph, append-only audit ledger). This ADR records the fresh
scaffold of ISIC class **1080 (Manufacture of prepared animal
feeds)**, part of the ongoing careful, smaller-batch rollout following
a prior 18-agent haiku batch that had a 61% defect rate; this build
follows the stricter capable-model + mandatory-verification protocol.

Identity was independently verified against a fresh clone of
`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
before any work began: the `{:id "1080" ...}` entry's live `:name` is
`"Manufacture of prepared animal feeds"`, matching the assigned class
— no mismatch. Its `:repo` field pointed at a stale, never-created
`gftdcojp/cloud-itonami-C1080` placeholder; the real `cloud-itonami`
org target name (`cloud-itonami/cloud-itonami-isic-1080`) was
independently confirmed 404 via `gh api
repos/cloud-itonami/cloud-itonami-isic-1080` before scaffolding began
— this was a genuinely fresh scaffold, no existing repo.

The closest domain analog and mirrored reference is
`cloud-itonami-isic-1075` (Manufacture of prepared meals and dishes,
56 tests / 179 assertions): both are plant-operations coordination
actors for a food/feed-safety-critical processing line with real
critical-control-points (a thermal/conditioning lethality step, a
post-process cooling window, and storage-condition/shelf-life
constraints), sharing the same four-op shape, the same
batch-registration-before-any-action gate applied across all ops (not
only shipment coordination), and the same escalation invariants.

## Decision

Scaffold `cloud-itonami-isic-1080` as a governed actor
(`FeedOpsAdvisor` ⊣ `feedops.governor` Governor), mirroring the
`cloud-itonami-isic-1075` architecture, namespace `feedops.*`:

- **Phase machine**: `:intake -> :mixing -> :pelletizing -> :cooling
  -> :package -> :inspect -> :audit -> :archived` (raw-material
  intake, batch mixing to a homogeneous mash, steam-conditioning +
  die-extrusion pelletizing, counter-flow cooling, bagging/bulk
  loading, metal-detector/packaging inspection, compliance audit,
  terminal archive).
- **Ops (closed allowlist, all `:effect :propose`)**:
  - `:log-production-batch` — mixing/pelletizing batch,
    nutrient-content data logging (always requires human sign-off)
  - `:schedule-maintenance` — mixing/pelletizing-equipment
    maintenance scheduling proposal (routine, low risk)
  - `:flag-food-safety-concern` — surface a food-safety concern (e.g.
    mycotoxin/aflatoxin contamination, medicated-feed cross-contact);
    ALWAYS escalates
  - `:coordinate-shipment` — outbound feed shipment coordination
    (always requires human sign-off)
- **HARD invariants (always `:hold`, no override)** — 20 independently-
  verified governor checks in `feedops.governor`: unknown op
  (`:op-not-allowed`), non-`:propose` effect (`:effect-not-propose`),
  plant/batch record not independently verified/registered before ANY
  proposal — applies to every op, not only shipment coordination
  (`:batch-not-registered`), no jurisdiction citation
  (`:no-spec-basis`), evidence checklist incomplete
  (`:evidence-incomplete`), steam-conditioning temperature below the
  product's minimum (`:conditioning-temp-below-minimum`, this actor's
  lethality-step CCP analog to 1075's `:core-cook-temp-below-minimum`),
  post-pellet cooling time exceeding the product's maximum
  (`:cooling-time-exceeds-max`, analog to 1075's
  `:chill-time-exceeds-max`), moisture content exceeding the maximum
  (`:moisture-content-exceeds-max`), mycotoxin/aflatoxin contamination
  exceeding a species/class-specific ceiling
  (`:mycotoxin-level-exceeds-max` — dairy and aquaculture rations use
  a materially stricter ceiling than finishing-swine/broiler rations,
  reflecting aflatoxin M1 milk carry-through and species sensitivity),
  guaranteed-analysis nutrient deviation exceeding the maximum
  (`:nutrient-deviation-exceeds-max`), foreign material detected
  (`:foreign-material-detected`), metal-detector calibration overdue
  (`:metal-detector-calibration-overdue`, 24-hour interval), weight
  variance excessive (`:weight-variance-excessive`), medicated-feed
  cross-contact declaration mismatch
  (`:medicated-feed-cross-contact` — this actor's domain-specific
  analog to 1075's allergen-label-mismatch check, covering drug
  carryover such as ionophores toxic to non-target species),
  insufficient plant sanitation score
  (`:sanitation-score-insufficient`), compromised packaging integrity
  (`:packaging-integrity-compromised`), shelf life exceeded
  (`:shelf-life-exceeded`), unresolved food-safety flag
  (`:food-safety-flag-unresolved`), and double-commit guards
  (`:already-processed`, `:already-shipment-finalized`). Any proposal
  touching mixing/pelletizing/cooling/bagging-line control or
  food-safety certification is structurally excluded by the closed op
  allowlist alone (`:op-not-allowed`) — there is no equipment-actuate
  or certification-authority op in the allowlist to even attempt.
- **ESCALATE (always human sign-off)**: `:flag-food-safety-concern`
  always escalates regardless of confidence (never auto-resolved by
  advisor confidence alone); low-confidence proposals (below
  `governor/confidence-floor`, 0.6) escalate; the two real-actuation
  ops (`:log-production-batch`, `:coordinate-shipment`) always require
  human sign-off even when the Governor is otherwise clean.
- Facts catalog (`feedops.facts`) covers four product types
  (`:feed/poultry-broiler-pellet`, `:feed/swine-grower-pellet`,
  `:feed/dairy-cattle-tmr-pellet`, `:feed/aquaculture-pellet`) each
  with its own conditioning-temperature / cooling-time / moisture /
  mycotoxin / nutrient-deviation / shelf-life window, and three
  jurisdictions (`:us/fda-cvm` citing 21 CFR 507 FSMA Preventive
  Controls for Animal Food and 21 CFR 225-226 Medicated Feed GMP,
  `:eu/feed-hygiene` citing EC 183/2005 and EC 767/2009, `:jp/maff`
  citing 飼料の安全性の確保及び品質の改善に関する法律).
- Single in-memory `Store` (plain-data map behind a pure-function
  surface, no external animal-feed-manufacturing capability library
  exists to wrap).
- All source `.cljc` — cljs-first / portable, no JVM-only interop;
  `feedops.phase`'s `index-of` uses a portable `keep-indexed`-based
  helper rather than JVM-only `.indexOf`.

## Consequences

(+) Prepared-animal-feed plant operations back-office now has a
documented, governed, auditable coordination layer with a genuinely
new independently-verified physical check for this fleet's
food-manufacturing cluster (species/class-specific mycotoxin ceiling)
and a domain-specific cross-contact check (medicated-feed drug
carryover) distinct from 1075's allergen-label-mismatch check.

(+) Scope is bounded and verifiable: the closed four-op allowlist
alone structurally excludes any equipment-control or
certification-authority proposal — there is no such op to propose in
the first place, reinforced by the unconditional `:op-not-allowed`
hold for anything outside the allowlist. Food-safety-concern flagging
is a circuit-breaker, never auto-decided.

(-) Still a simulation/proposal layer, not a real feed-mill-operations
control system — equipment actuation and line operation remain
human-controlled via external channels.

## Verification

- `cloud-itonami-isic-1080` repo:
  `https://github.com/cloud-itonami/cloud-itonami-isic-1080` (public,
  AGPL-3.0-or-later).
- `clojure -M:test` from a fresh clone: `Ran 56 tests containing 182
  assertions. 0 failures, 0 errors.`
- `clojure -M:lint` (clj-kondo): 0 errors, 0 warnings.
- `kotoba-lang/industry` registry entry `{:id "1080" ...}` updated
  `:maturity :spec` -> `:implemented`, `:repo` and `:business-id`
  corrected from the stale `gftdcojp/cloud-itonami-C1080` placeholder
  to the real `cloud-itonami/cloud-itonami-isic-1080` target, with an
  ADR-reference comment — landed via server-side merge (see registry
  PR/merge SHA in the commit trail); `industry_test.clj` assertion
  recomputed from the live `kotoba.industry/maturity-summary` count
  and re-verified green post-merge from an independent fresh clone.
