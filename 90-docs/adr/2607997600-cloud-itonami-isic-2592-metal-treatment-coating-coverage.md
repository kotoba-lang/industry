# ADR-2607997600: cloud-itonami-isic-2592 — Treatment and coating of metals; machining

## Status

Accepted. `cloud-itonami-isic-2592` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

ISIC Rev.4 class 2592 ("Treatment and coating of metals; machining")
had no repository (verified via `gh api repos/cloud-itonami/
cloud-itonami-isic-2592` returning 404 before this work started; the
registry's own `:repo` field pointed at a placeholder
`gftdcojp/cloud-itonami-C2592` URL that likewise does not exist). Per
the cloud-itonami fleet's ongoing careful, smaller-batch rollout
protocol (capable model + mandatory verification, following a prior
18-agent haiku batch with a 61% defect rate), this ADR records a fresh
from-scratch scaffold: `cloud-itonami/cloud-itonami-isic-2592`.

The `kotoba-lang/industry` registry's own `:name` field for `"2592"`
reads "Treatment and coating of metals" — a truncated label consistent
with its sibling entries in the same ISIC 259 group (`"2591"` also
reads "Forging, pressing, stamping and roll-forming of metal" without
its own official "; powder metallurgy" suffix). The full official
ISIC Rev.4 title for class 2592 is "Treatment and coating of metals;
machining" — confirmed as the assigned class for this build; the
domain design below (electroplating/anodizing/heat-treatment coating
services PLUS CNC machining job-shop operations) covers both halves of
that official title.

Reference/mirror: `cloud-itonami/cloud-itonami-isic-2593`
(Manufacture of cutlery, hand tools and general hardware) — the
closest architectural sibling in the same ISIC 259 group, 71 tests /
195 assertions, independently re-verified working before use as the
template for this build.

## Decision

Scaffolded `cloud-itonami/cloud-itonami-isic-2592` as a governed-actor
repo mirroring the `cloud-itonami-isic-2593` architecture
(`hardwaremfg.*` → `metaltreatmfg.*`), adapted to the metal-treatment/
coating/machining job-shop domain:

- **MetalTreatmentAdvisor** ⊣ **Metal Treatment, Coating & Machining
  Plant Operations Governor**, langgraph-clj StateGraph, append-only
  audit ledger.
- Closed four-op allowlist, all `:effect :propose` only:
  - `:log-production-batch` — plating/coating/machining batch,
    output-quality data logging
  - `:schedule-maintenance` — plating/machining-equipment maintenance
    scheduling proposal
  - `:flag-safety-concern` — surface a chemical-hazard
    (electroplating-bath toxicity — cyanide/hexavalent-chromium/acid
    baths)/CNC-machining-swarf/equipment-safety concern; ALWAYS
    escalates
  - `:coordinate-shipment` — outbound product shipment coordination
- HARD invariants (always `:hold`, no override): plant/batch record
  must be independently verified/registered before any action; the
  request's own `:effect` must be `:propose`; any proposal touching
  plating/machining-line-equipment control
  (`:actuate-plating-machining-line? true`) is a hard, permanent
  block; the op allowlist is closed. Elaborated into ten concrete
  governor checks (propose-only, unknown-op, effect-allowlist,
  actuate-block, equipment-not-verified, already-scheduled,
  batch-not-verified, shipment-weight-exceeded,
  invalid-product-category, invalid-defect-rate) — the same shape
  `cloud-itonami-isic-2593`'s own ten checks establish.
- ESCALATE (always human sign-off): `:flag-safety-concern` always
  escalates regardless of confidence; low-confidence proposals.
  `:schedule-maintenance` and `:coordinate-shipment` are never in any
  phase's `:auto` set either — only `:log-production-batch` may
  auto-commit at phase 3 when governor-clean.
- All source `.cljc` — no JVM-only interop; the actor graph runs via
  `langgraph.graph/run*` only (portable to ClojureScript/nbb).
- Full module set: `advisor.cljc` / `governor.cljc` / `operation.cljc`
  / `phase.cljc` / `registry.cljc` / `sim.cljc` / `store.cljc`, plus
  `deps.edn`, `blueprint.edn`, `docs/adr/0001-architecture.md`,
  `README.md`, `GOVERNANCE.md`, `CODE_OF_CONDUCT.md`,
  `CONTRIBUTING.md`, `SECURITY.md`, `LICENSE` (AGPL-3.0-or-later).
- Governor keyword
  `:metal-treatment-coating-machining-plant-operations-governor` and
  namespace prefix `metaltreatmfg` grep-verified UNIQUE fleet-wide
  (`gh search code ... --owner cloud-itonami`, zero hits before this
  repo was created).

## Verification

Pushed to `cloud-itonami/cloud-itonami-isic-2592` main
(`c87e8195fbc9a700c4484dab4718e96b1588feb6`), verified landed via
`git merge-base --is-ancestor`.

First run, fresh scaffold (before push):

```
Ran 71 tests containing 195 assertions.
0 failures, 0 errors.
```

`clojure -M:lint`: `linting took 779ms, errors: 0, warnings: 0`.
`clojure -M:dev:run` demo exercises the full happy-path narrative plus
every HARD-hold scenario directly (not-propose-effect, unknown-op,
equipment-not-verified, batch-not-verified, shipment-weight-exceeded,
plating-machining-line-actuate-blocked — permanent, never reaches a
human — already-scheduled, invalid-product-category,
invalid-defect-rate).

Independent re-verification, fresh clone into a new temp directory
after the push (also fresh `kotoba-lang/langgraph` +
`kotoba-lang/langchain` sibling clones):

```
Ran 71 tests containing 195 assertions.
0 failures, 0 errors.
```

`kotoba-lang/industry` registry: `"2592"` entry edited in place
(`:maturity :spec` → `:implemented`, `:repo`/`:business-id` corrected
to the real repo, ADR reference added), landed via server-side merge;
`resources/kotoba/industry/registry.edn` re-verified 0 mojibake
(`grep -c "â"` → 0) and the `"2592"` entry confirmed present after
merge. See commit/merge SHAs and the recomputed `:implemented` count
in the registry entry itself and its own commit message.
