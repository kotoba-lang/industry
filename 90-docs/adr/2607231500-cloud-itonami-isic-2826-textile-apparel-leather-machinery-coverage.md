# ADR-2607231500: cloud-itonami-isic-2826 (Manufacture of machinery for textile, apparel and leather production) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607201000 (cloud-itonami-isic-2818 Manufacture of power-driven hand tools coverage, closest domain analog)

## Context

This task was assigned as ISIC class 2825, but per this fleet's
recurring ID/name-mismatch caution (this fleet has previously
mislabeled an assigned ISIC class from memory rather than reading the
registry — e.g. 0892 assumed salt, actually peat; 0144 assumed swine,
actually sheep-goats), the `kotoba-lang/industry` registry was checked
fresh (read-only clone, `../technology` sibling for `deps.edn`) before
any code was written. Both neighboring entries were read byte-for-byte:
`{:id "2825" :name "Manufacture of machinery for food, beverage and
tobacco pro..."}` and `{:id "2826" :name "Manufacture of machinery for
textile, apparel and leather p..."}` (both names are truncated with a
literal `...` in the registry data itself). The requested domain
("Manufacture of machinery for textile, apparel and leather
production") matches `"2826"`, not `"2825"` — this ADR and the
implementation below therefore target **2826**, not 2825, correcting
the task's initial ID.

ISIC class 2826 (Manufacture of machinery for textile, apparel and
leather production) is a fresh scaffold — no prior repo or reverted
attempt existed at `cloud-itonami/cloud-itonami-isic-2826` before this
ADR (checked and confirmed 404 via `gh api
repos/cloud-itonami/cloud-itonami-isic-2826` before starting). The
pre-existing registry entry's `:repo`/`:business-id` pointed at a
never-created `gftdcojp/cloud-itonami-C2826` placeholder, not a real
prior attempt.

The closest domain analog is `cloud-itonami-isic-2818` (Manufacture of
power-driven hand tools): both are back-office coordination actors for
a fixed manufacturing plant with electromechanically-assembled,
test-bench-verified finished-goods output and a real physical/worker
safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`), the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination), and the same permanent equipment-actuation
and certification-authority blocks. This build mirrors 2818's
architecture closely but adapts the hazard profile, equipment
vocabulary, and product taxonomy to the textile/apparel/leather
production-machinery plant: its finished goods are production
machinery FOR other manufacturers (weaving looms, industrial sewing
machines, leather-cutting machines, knitting machines, fabric-cutting
machines) rather than hand-held consumer power tools, so its equipment
kinds are `:loom-assembly-line` and `:sewing-machine-test-bench`
rather than 2818's motor-assembly line and housing-molding press, and
its routine test-bench field is `:no-load-run-speed-rpm`
(plausibility-checked 0-8000 rpm, informed by typical no-load/
running-in test speeds across this vertical's own equipment classes —
weaving looms approximately 200-800 rpm, industrial sewing machines up
to approximately 5,500 stitches/minute, leather-cutting-machine
blade-stroke rates typically below 1,000 strokes/minute — and by the
general mechanical-safety framework the ISO 11111 series [Textile
machinery — Safety requirements] establishes for this equipment class)
rather than 2818's `:hipot-test-kv` (electrical double-insulation
withstand test, plausibility-checked 0-15 kV against IEC 60745).
2826's shipment quantity is also tracked in finished-unit UNITS
(`:units`/`:quantity-units`/`:shipped-units`) rather than a bulk
weight, the same shape 2818 uses, since textile/apparel/leather
production machinery is likewise discrete counted units.

This vertical additionally shares 2818's DOMAIN-SPECIFIC permanent
block, adapted: manufacture of textile/apparel/leather production
machinery is subject to machinery safety certification regimes (e.g.
CE marking under the EU Machinery Directive 2006/42/EC, now Machinery
Regulation (EU) 2023/1230). This actor is never the certification
authority — any proposal (regardless of op) that declares
`:issue-certification? true` is a HARD, PERMANENT, unconditional block
(`texmachmfg.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture as the equipment-
actuation block.

This vertical is SELF-CONTAINED — no `kotoba-lang/texmachmfg` library
exists, so domain logic (equipment/batch verification, shipment-
quantity recompute, product-type validation, no-load-run-speed
plausibility validation, defect-rate plausibility validation) lives as
pure functions in `texmachmfg.registry` and is re-verified
independently by the governor, mirroring the discipline established by
`cloud-itonami-isic-2818`'s `powertoolmfg.registry` and every prior
sibling actor.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:textile-apparel-leather-machinery-plant-operations-governor`, is
grep-verified UNIQUE fleet-wide (`gh search code
"textile-apparel-leather-machinery-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

Build `cloud-itonami-isic-2826` from scratch as a governed-actor
implementation of the textile/apparel/leather-production-machinery
blueprint, following the langgraph StateGraph + independent Governor +
Phase 0->3 rollout architecture established across the fleet:

1. **TexMachAdvisor** (`texmachmfg.advisor`, sealed intelligence
   node): proposes plant-operations coordination actions only, never
   commits
   - `:log-production-batch` — assembly/test batch, output-quality/test-result data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — assembly/test-bench-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface a mechanical-safety/electrical-safety/CE-compliance concern (always escalates)
   - `:coordinate-shipment` — outbound product shipment coordination proposal

2. **Textile, Apparel and Leather Machinery Plant Operations Governor**
   (`texmachmfg.governor`, independent validation layer, never trusts
   the advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into twelve concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct assembly/test-bench-equipment control);
     `:actuate-equipment? true` on a maintenance schedule (directly
     actuating assembly/test-bench equipment) is a PERMANENT block;
     `:issue-certification? true` on ANY proposal (self-issuing a
     machinery safety certification mark) is a PERMANENT block; a
     shipment may not push a batch's own recorded shipped unit
     quantity past its own logged production quantity (independently
     recomputed); no double-scheduling the same maintenance record; no
     fabricated `:product-type` value; no physically implausible
     `:no-load-run-speed-rpm` value; no physically implausible
     `:defect-rate-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (critical, safety-critical domain — moving-part/
   pinch-point hazard on assembly/test-bench lines, machinery safety
   certification, downstream worker-safety consequence):
   - Does NOT control weaving-loom, sewing-machine, or leather-cutting-machine assembly/test-bench equipment directly
   - Does NOT make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
   - Does NOT actuate assembly/test-bench equipment (permanently blocked,
     not a rollout milestone still to come — see `texmachmfg.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - Does NOT self-issue a machinery safety certification mark (e.g. CE marking under the EU Machinery Directive — permanently blocked, unconditional)
   - All proposals are `:effect :propose`; actuation and certification are human-/institution-approval-gated

4. **Self-contained domain logic**: `texmachmfg.registry` pure
   functions (`equipment-ready?`, `batch-ready?`, `shipment-quantity-
   exceeded?`, `product-type-valid?`, `no-load-run-speed-rpm-valid?`,
   `defect-rate-valid?`) are re-verified independently by the
   governor, following the "ground truth, not self-report" discipline
   established by prior actors (most directly `cloud-itonami-isic-
   2818`'s `powertoolmfg.registry`).

5. **Store** (`texmachmfg.store`): a single `MemStore` backend behind
   a `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 2818,
   this build does NOT ship a second Datomic-backed store — a second
   backend can be added later behind the same protocol without
   changing any caller.

6. **Implementation**: `.cljc` portable source (ClojureScript/JVM/nbb
   compatible, no JVM-only interop), langgraph-clj StateGraph (invoked
   via `langgraph.graph/run*`, not `.invoke`), append-only audit
   ledger, full test coverage, demo driver. Full module set:
   `deps.edn`, `blueprint.edn`, `LICENSE` (AGPL-3.0-or-later),
   `README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
   `CONTRIBUTING.md`, `SECURITY.md`, `docs/adr/0001-architecture.md`.
   All source pushed to
   `github.com/cloud-itonami/cloud-itonami-isic-2826` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Textile/apparel/leather production-machinery plant-operations
back-office coordination is now genuinely implemented and tested (not
merely scaffolded). ISIC 2826 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation, equipment actuation, or certification self-issuance,
independently corroborated by `texmachmfg.phase`'s permanent exclusion
of `:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely textile/apparel/
leather-production-machinery-specific elaboration mirroring 2818's own
two-entity-kind gate — this domain has two distinct ground-truth
entity kinds a proposal can reference, and each is independently
re-derived from its own permanent record, never trusting the
proposal's self-report.

(+) The certification-authority-blocked check is directly adapted from
2818's own certification elaboration to this vertical's own CE
marking/EU Machinery Directive regulatory regime — the governor closes
that scope-creep vector explicitly rather than leaving it implicit in
the closed op-allowlist alone.

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 77 tests / 210 assertions
across 5 test namespaces (`texmachmfg.operation-test`,
`texmachmfg.governor-contract-test`, `texmachmfg.phase-test`,
`texmachmfg.store-contract-test`, `texmachmfg.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch/certification-body
systems — scope is deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

(-) The no-load-run-speed plausibility ceiling (0-8000 rpm) is a
generous, cross-equipment-class bound informed by typical published
speed ranges for weaving looms/industrial sewing machines/leather-
cutting machines and the general ISO 11111 safety-requirements
framework, not a per-product-type-specific regulatory limit (unlike
2818's single-product-class IEC 60745 hipot-test-kv ceiling) — a
follow-up could tighten this per `:product-type` if warranted.

## Verification

- `cloud-itonami-isic-2826` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-2826`, initial commit
  `ed4da5b9fcf24703a955957893d0d8aeb679a0ed` (confirmed via `gh api
  repos/cloud-itonami/cloud-itonami-isic-2826/compare/ed4da5b9...main`
  reporting `status: identical, ahead_by: 0, behind_by: 0`).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 77 tests containing 210 assertions. 0 failures, 0 errors.`**
  Re-verified identically from a brand-new, independent fresh clone
  (plus fresh `kotoba-lang/langgraph`/`kotoba-lang/langchain` sibling
  clones) after the push, with an identical raw output line.
- `clojure -M:lint`: `linting took 576ms, errors: 0, warnings: 0`.
- `clojure -M:dev:run` demo narrative exercises all four ops, every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified, shipment-quantity-
  exceeded, equipment-actuate-blocked, certification-authority-
  blocked, already-scheduled, invalid-product-type, invalid-no-load-
  run-speed, invalid-defect-rate), exit code 0, zero exception/error
  matches in the full output.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:textile-apparel-leather-
  machinery-plant-operations-governor` is grep-verified UNIQUE
  fleet-wide (`gh search code
  "textile-apparel-leather-machinery-plant-operations-governor"
  --owner cloud-itonami`, zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"2826"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "2826" ...}` block (no wholesale
  regeneration). Heavy concurrent fleet activity on this same shared
  file required re-deriving the edit against a moving tip twice (two
  `409 Merge conflict` responses from the GitHub merges API, base
  `:implemented` count observed live drifting 289 -> 291 -> 293 across
  the retries) before a third attempt landed cleanly on a freshly
  re-fetched blob SHA, server-side-merged via
  `gh api repos/kotoba-lang/industry/merges` (not a local merge/rebase)
  at commit `260dc686e466517044de29eb60a8768a4b8d9edb` (confirmed as
  the exact tip of `kotoba-lang/industry`'s `main` via
  `gh api repos/kotoba-lang/industry/compare/260dc686...main`
  reporting `status: identical, ahead_by: 0, behind_by: 0`). The live
  `:implemented` count was recomputed fresh via
  `(kotoba.industry/maturity-summary)` immediately before the landing
  edit (293 -> 294, not assumed) and `industry_test.clj`'s own
  assertion was bumped accordingly, re-run green before the push
  (`Ran 15 tests containing 974 assertions. 0 failures, 0 errors.`).
  Post-merge re-verification from a brand-new fresh clone (plus a
  fresh `../technology` sibling clone) re-ran the full suite
  (**`Ran 15 tests containing 974 assertions. 0 failures, 0 errors.`**)
  and confirmed `"2826"`'s own entry specifically (not merely the
  aggregate suite) reads `:maturity :implemented` with
  `:repo "https://github.com/cloud-itonami/cloud-itonami-isic-2826"` /
  `:business-id "cloud-itonami-isic-2826"`, `(maturity-summary)`
  reporting `{:total 648, :spec 329, :blueprint 25, :implemented 294}`,
  and zero UTF-8 mojibake (`grep -c "â"
  resources/kotoba/industry/registry.edn` = 0).
