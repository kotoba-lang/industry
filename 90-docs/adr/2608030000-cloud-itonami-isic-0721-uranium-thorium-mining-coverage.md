# ADR-2608030000: cloud-itonami-isic-0721 — Uranium and Thorium Ore Mining Operations Coordination

## Status

Accepted. `cloud-itonami-isic-0721` promoted from `:spec` (stale
placeholder `gftdcojp/cloud-itonami-B0721` entry, never scaffolded) to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 0721 (Mining of uranium and thorium ores) had no prior
actor implementation — the registry entry pointed at a placeholder
repo (`https://github.com/gftdcojp/cloud-itonami-B0721`) that was
never created. This is a fresh, from-scratch scaffold (not a redo of
a broken prior attempt), built as part of a smaller, more carefully
verified batch after an earlier 18-agent haiku batch produced a 61%
defect rate across several `cloud-itonami-isic-*` actors that day.
This session independently verified the live registry against a
fresh clone of `kotoba-lang/industry` before proceeding:
`resources/kotoba/industry/registry.edn` reads `{:id "0721" :name
"Mining of uranium and thorium ores" :repo
"https://github.com/gftdcojp/cloud-itonami-B0721" :business-id
"cloud-itonami-B0721" :maturity :spec ...}` — confirmed the id/name
match before writing anything.

**Scope note**: this is a legitimate civilian nuclear-fuel-cycle
*raw-material mining* classification — radiation-safety-regulated
like any other radioactive-ore extraction activity, **not**
weapons-related, and distinct from the deliberately-excluded ISIC
2520/3040 weapons classes this fleet does not cover.

**Scope**: COORDINATION ONLY, mirrored closely on the sibling
`cloud-itonami-isic-0893` (Extraction of salt)'s verified module shape
(advisor/governor/phase/operation/store/sim, `langgraph-clj`
StateGraph, independent Governor, phase 0→3 rollout, string-keyed site
directory to avoid the string-vs-keyword map-key bug class
`cloud-itonami-isic-0510`'s own prior scaffold attempt hit). Domain-
adapted for uranium/thorium mining: ISIC 0721 covers more than one
extraction method (conventional open-pit/underground hard-rock
mining, and in-situ recovery/ISR wellfields) and both ores the class
covers (uranium and thorium), so this actor's demo/test site
directory seeds one site of each method deliberately, plus a thorium
site, rather than coupling the design to a single method or ore.
Coordination surface: extraction-record logging (ore tonnage/grade
assay), mining/haulage-operation scheduling, radiological-concern
flagging (radiation exposure, tailings-containment integrity), and
outbound ore/concentrate-shipment coordination — never direct
mining-equipment control (drill-and-blast sequencing, haul-truck/
shaft-hoist dispatch, continuous-miner operation, wellfield injection/
extraction well-pump control, ion-exchange/elution circuit control, or
the downstream mill circuit: leach/solvent-extraction control,
yellowcake precipitation/drying/calcining control) or
radiation-safety-certification-authority decisions.

## Decision

### 1. Closed proposal-op allowlist, all `:effect :propose`

- `:log-extraction-record` — ore tonnage/grade-assay data logging
- `:schedule-mining-operation` — extraction/haulage-operation
  scheduling proposal
- `:flag-radiological-concern` — surface a radiation-exposure/
  tailings-containment concern — **ALWAYS escalates**
- `:coordinate-shipment` — outbound ore/concentrate-shipment
  coordination — **ALWAYS escalates**

An op outside this closed set is treated as the SAME failure mode as a
proposal that drifts into forbidden scope (see check 3 below), not a
separate "unknown op" carve-out.

Unlike most sibling shipment-coordination ops in this fleet (e.g.
`cloud-itonami-isic-0893`'s `:coordinate-shipment`, which does NOT
always escalate), this actor's `:coordinate-shipment` is treated as
high-stakes and ALWAYS escalates: uranium/thorium ore and concentrate
leaving a mine site is IAEA-safeguarded nuclear material, not ordinary
industrial freight.

### 2. Governor rules: three HARD checks (permanent, un-overridable), plus escalation

1. **Mine-site/permit unverified** — the target site record must exist
   in the store AND be independently `:registered?`/
   `:permit-verified?` before any proposal for it may commit or even
   escalate. Re-derived from the site's own store record every time,
   never from the proposal's own `:site-id` claim.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance. HARD, not merely low-confidence.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/draft value
   touches mining-equipment-control (either method family:
   drill-and-blast/haul-truck-dispatch/shaft-hoist-control/continuous-
   miner for conventional mining, or wellfield-injection/wellfield-
   extraction-well/ion-exchange-circuit-control/elution-circuit-
   control for in-situ recovery, plus the shared downstream mill
   circuit: leach-circuit-control/solvent-extraction-control/
   yellowcake-precipitation/calciner-control/mill-circuit-control) or
   radiation-safety(-certification-authority) territory (exposure-
   limit-override, tailings-containment-override, ventilation/
   shielding-control-decision, radioactive-material license issuance,
   license suspension, compliance enforcement), is a permanent,
   un-overridable block. Evaluated **unconditionally** on every
   proposal via a lower-cased substring scan of the proposal's own
   content (English + Japanese term list) — never trusting the
   advisor's own framing. The exposure/tailings-containment EXCLUSION
   terms are deliberately qualified ("...override", "...control
   decision") rather than bare keywords, so this HARD block never
   collides with the actor's own core valid use case — legitimately
   flagging an observed radiation-exposure/tailings-containment
   concern via `:flag-radiological-concern` — a failure mode this
   ADR's own governor test suite exercises directly
   (`legitimate-radiological-concern-flag-is-not-scope-excluded`).

Escalation (SOFT, always human sign-off, only reached when the
governor is otherwise clean):

- `:flag-radiological-concern` — always, regardless of confidence.
- `:coordinate-shipment` — always, regardless of confidence
  (IAEA-safeguarded material).
- Low advisor confidence (`< 0.6`).

`uraniumops.phase`'s 0→3 rollout table independently agrees: neither
`:flag-radiological-concern` nor `:coordinate-shipment` is ever a
member of any phase's `:auto` set, at any phase — two layers, not
one, enforce the same invariant.

### 3. Module shape

`uraniumops.store` (MemStore, string-keyed site directory seeded with
a conventional uranium mine, an in-situ-recovery uranium wellfield,
and a thorium mine with an unverified permit), `uraniumops.advisor`
(UraniumOpsAdvisor, mock + a real-LLM seam via `langchain.model`, plus
an `:out-of-scope?` test hook that deliberately drafts wellfield-
injection-pump-control/drill-and-blast scope content so the governor's
scope scan can be exercised end to end), `uraniumops.governor`
(UraniumThoriumMiningGovernor), `uraniumops.phase` (0→3 rollout),
`uraniumops.operation` (the `langgraph-clj` StateGraph: intake →
advise → govern → decide → commit | hold | request-approval),
`uraniumops.sim` (demo driver, `clojure -M:run`).

## Consequences

- Registry entry corrected in place (exact-text edit of the existing
  `{:id "0721" ...}` block, not appended): `:repo`/`:business-id`
  updated from the stale `gftdcojp/cloud-itonami-B0721` placeholder to
  `cloud-itonami/cloud-itonami-isic-0721`, `:maturity :implemented`,
  `:required-technologies`/`:operating-states` corrected to match the
  coordination-only (no `:robotics`/`:telemetry`) shape, mirroring
  `cloud-itonami-isic-0893`'s own correction.
- Actor repo `cloud-itonami/cloud-itonami-isic-0721` scaffolded and
  pushed to `main` (`d6699658718df71cf938957d70a147083fe27106`).
- Test suite, run directly by this session (not agent self-report):
  **`Ran 48 tests containing 152 assertions. 0 failures, 0 errors.`**
  (`clojure -M:test`, both at push time and again from a fresh
  post-merge clone). `clojure -M:lint`: 0 errors, 0 warnings.

## References

- `cloud-itonami-isic-0893/src/saltops/` (module-shape precedent — the
  closest existing sibling: coordination-only actor, dual extraction
  methods, one always-escalate op)
- `cloud-itonami-isic-0891/src/chemmineops/` (original governed-actor
  discipline precedent)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  `"0721"` entry
- ADR-2607121000 (ISIC Wave rollout plan — 0721 is ISIC division 07,
  Wave 3 production/mining)
