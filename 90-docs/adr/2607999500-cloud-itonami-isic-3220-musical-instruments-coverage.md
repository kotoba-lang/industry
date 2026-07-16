# ADR-2607999500: cloud-itonami ISIC 3220 (Manufacture of musical instruments) coverage

## Status

Accepted. `cloud-itonami-isic-3220` scaffolded and promoted from
`:spec` to `:implemented` in the `kotoba-lang/industry` registry.

## Context

cloud-itonami codifies every ISIC industry class as an autonomous
"actor" (LLM/advisor behind an independent Governor, langgraph-clj
StateGraph, append-only audit ledger). This ADR records the fresh
scaffold of ISIC class **3220 (Manufacture of musical instruments)**,
part of the ongoing careful, smaller-batch rollout following a prior
18-agent haiku batch that had a 61% defect rate; this build follows
the stricter capable-model + mandatory-verification protocol.

Identity was independently verified against a fresh clone of
`kotoba-lang/industry`'s `resources/kotoba/industry/registry.edn`
before any work began: the `{:id "3220" ...}` entry's live `:name` is
`"Manufacture of musical instruments"`, matching the assigned class —
no mismatch. Its `:repo` field pointed at a stale, never-created
`gftdcojp/cloud-itonami-C3220` placeholder; the real `cloud-itonami`
org target name (`cloud-itonami/cloud-itonami-isic-3220`) was
independently confirmed 404 via `gh api
repos/cloud-itonami/cloud-itonami-isic-3220` (and the legacy
placeholder name also confirmed 404) before scaffolding began — this
was a genuinely fresh scaffold, no existing repo.

The closest domain analog and mirrored reference is
`cloud-itonami-isic-3211` (Manufacture of jewellery and related
articles, 82 tests / 222 assertions): both are back-office
coordination actors for a fixed processing PLANT/WORKSHOP with
precision equipment and a real safety/consumer-protection dimension,
sharing the same four-op shape and the same two-entity
verified/registered gate structure. See
`orgs/cloud-itonami/cloud-itonami-isic-3220/docs/adr/0001-architecture.md`
(child-repo ADR) for the full architecture record and the detailed
domain adaptation (instrument-family / pitch-accuracy-cents /
wood-moisture-content-percent / defect-rate-percent in place of
metal-type / purity-permille / weight-grams / defect-rate-percent; no
hallmark/purity-assay-authority analog block, since musical-instrument
manufacture has no single universally recognized certification
authority of that kind).

## Decision

Scaffold `cloud-itonami-isic-3220` as a governed actor
(`InstrumentAdvisor` ⊣ Musical Instrument Workshop Plant Operations
Governor), mirroring the `cloud-itonami-isic-3211` architecture:

- **Ops (closed allowlist, all `:effect :propose`)**:
  `:log-production-batch` (crafting/assembly/tonal-test batch,
  output-quality data logging), `:schedule-maintenance`
  (crafting/assembly-equipment maintenance scheduling proposal),
  `:flag-safety-concern` (materials-safety wood-dust/finish-chemical
  or equipment-safety concern, ALWAYS escalates),
  `:coordinate-shipment` (outbound product shipment coordination).
- **HARD invariants (always `:hold`, no override)**, elaborated into
  twelve concrete governor checks in `musicinstrmfg.governor`:
  1. Workshop/batch record (equipment for maintenance, batch for
     shipment) must be independently verified/registered before any
     action is taken against it, and a shipment's quantity must
     independently recompute within the batch's own logged production
     quantity.
  2. The request's own `:effect` must be `:propose` only.
  3. Direct crafting/assembly-line-equipment control (closed proposal-
     effect allowlist) or equipment actuation
     (`:actuate-equipment? true`) is permanently, unconditionally
     blocked.
  4. The op allowlist is closed to the four ops above.
- **ESCALATE (always human sign-off)**: `:flag-safety-concern` always
  escalates regardless of confidence; low-confidence proposals
  escalate.
- **Phase 0->3 rollout**: `:schedule-maintenance`/
  `:flag-safety-concern`/`:coordinate-shipment` are NEVER in any
  phase's `:auto` set; only `:log-production-batch` may auto-commit at
  phase 3 when governor-clean.
- Single `MemStore` backend behind a `Store` protocol (no external
  musical-instrument-manufacturing capability library exists to wrap,
  verified via GitHub code/repo search).
- All source `.cljc` — cljs-first / portable, no JVM-only interop; the
  actor graph is invoked exclusively via `langgraph.graph/run*`.

## Consequences

(+) Musical-instrument-workshop plant operations back-office now has a
documented, governed, auditable coordination layer.

(+) Scope is bounded and verifiable: four HARD invariants (twelve
concrete checks) block scope creep into unauthorized equipment
operation or actuation. Safety-concern flagging is a circuit-breaker,
never auto-decided.

(-) Still a simulation/proposal layer, not a real workshop-operations
control system — equipment actuation and line operation remain
human-controlled via external channels.

## Verification

- `cloud-itonami-isic-3220` repo:
  `https://github.com/cloud-itonami/cloud-itonami-isic-3220`
  (public, AGPL-3.0-or-later).
- `clojure -M:test` from a fresh clone (with `kotoba-lang/langgraph`
  and `kotoba-lang/langchain` as `../../kotoba-lang/*` siblings): `Ran
  81 tests containing 219 assertions. 0 failures, 0 errors.`
- `clojure -M:lint` (clj-kondo): 0 errors, 0 warnings.
- `clojure -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, equipment-not-verified, batch-not-verified,
  shipment-quantity-exceeded, equipment-actuate-blocked,
  already-scheduled, invalid-instrument-family,
  invalid-pitch-accuracy, invalid-wood-moisture, invalid-defect-rate).
- `kotoba-lang/industry` registry entry `{:id "3220" ...}` updated
  `:maturity :spec` -> `:implemented`, `:repo` and `:business-id`
  corrected to the real `cloud-itonami/cloud-itonami-isic-3220`
  target, with an ADR-reference comment — landed via server-side merge
  (see registry PR/merge SHA in the commit trail); `industry_test.clj`
  assertion recomputed from the live `kotoba.industry/maturity-summary`
  count and re-verified green post-merge from an independent fresh
  clone.
