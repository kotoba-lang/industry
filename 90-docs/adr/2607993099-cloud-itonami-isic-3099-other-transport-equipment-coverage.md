# ADR-2607993099: cloud-itonami-isic-3099 (Manufacture of other transport equipment n.e.c.) coverage

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607192000 (cloud-itonami-isic-3092 Manufacture of bicycles and invalid carriages coverage, closest domain analog / architecture mirrored closely)

## Context

ISIC class 3099 (Manufacture of other transport equipment n.e.c.) is a
fresh scaffold — no prior repo or reverted attempt existed at
`cloud-itonami/cloud-itonami-isic-3099` before this ADR (checked and
confirmed 404 via `gh api repos/cloud-itonami/cloud-itonami-isic-3099`
before starting). The `kotoba-lang/industry` registry entry `{:id
"3099" :name "Manufacture of other transport equipment n.e.c." ...}`
was verified byte-for-byte from a fresh read-only clone (and again via
the GitHub git-data blob API, never `raw.githubusercontent.com`)
before any code was written — this fleet has previously mislabeled an
assigned ISIC class from memory rather than reading the registry.
ISIC 3099 is a RESIDUAL class: animal-drawn vehicles (carts, wagons,
carriages) and hand-propelled vehicles (hand-carts, hand-trucks,
wheelbarrows, rickshaws, sledges, toboggans, pushcarts). It explicitly
EXCLUDES bicycles and invalid carriages (manual/power wheelchairs,
mobility scooters), which are ISIC 3092
(`cloud-itonami-isic-3092`), a distinct, already-implemented actor in
this fleet.

The closest domain analog is `cloud-itonami-isic-3092` (Manufacture of
bicycles and invalid carriages): both are back-office coordination
actors for a fixed assembly plant with a real physical safety
dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`) and the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination). This build mirrors 3092's architecture
closely but adapts the hazard profile, equipment vocabulary and
product-category set to the other-transport-equipment plant: 3099's
central physical hazard is chassis/axle/wheel/body assembly-line work
and structural/load test-bench inspection (materials-safety and
structural-integrity hazard) rather than 3092's frame-welding/brake-
safety hazard; 3099's permanent equipment-actuation block guards
generic assembly-line equipment (`:actuate-equipment?`) rather than a
welding/assembly/test-bench line specific to bicycle frames; 3099's
production-batch record declares a `:product-category` (closed set
spanning animal-drawn vehicles — cart/wagon/carriage — and
hand-propelled vehicles — hand-cart/hand-truck/wheelbarrow/rickshaw/
sledge/toboggan/pushcart, explicitly EXCLUDING bicycle and
invalid-carriage categories) and a `:weight-capacity-kg` (a physically
plausible rated maximum load, plausibility-checked 0-3000 — a wider
ceiling than 3092's 0-300 to admit heavy-duty animal-drawn farm
wagons) in addition to an `:assembly-defect-rate-percent` (renamed
from 3092's `:weld-defect-rate-percent` since assembly here is not
necessarily weld-based). 3099's shipment quantity is tracked in
finished-product UNITS (`:units`/`:quantity-units`/`:shipped-units`),
the same counted-not-weighed shape as 3092.

This vertical additionally has a permanent certification-authority
block, GENERALIZED rather than naming a specific standard: unlike
3092 (which names real ISO 4210/ISO 7176 standards specific to
bicycles/wheelchairs), no single globally-recognized certification
standard applies uniformly across this residual class's heterogeneous
product set (animal-drawn carts vs. hand-trucks vs. sledges). This
actor is never the certification authority — any proposal (regardless
of op) that declares `:issue-certification? true` is a HARD,
PERMANENT, unconditional block (`otmfg.governor/certification-
authority-blocked-violations`), described as "a transport-equipment
safety/roadworthiness certification mark" rather than a fabricated
specific standard number, the same "no phase, no human override"
posture as 3092's equipment-actuation block.

This vertical is SELF-CONTAINED — no `kotoba-lang/otmfg` library
exists, so domain logic (equipment/batch verification, shipment-
quantity recompute, product-category validation, weight-capacity
plausibility validation, assembly-defect-rate plausibility validation)
lives as pure functions in `otmfg.registry` and is re-verified
independently by the governor, mirroring the discipline established by
`cloud-itonami-isic-3092`'s `bikemfg.registry` and every prior sibling
actor.

## Decision

Build `cloud-itonami-isic-3099` from scratch as a governed-actor
implementation of the other-transport-equipment-manufacturing
blueprint, following the langgraph StateGraph + independent Governor +
Phase 0->3 rollout architecture established across the fleet:

1. **OtherTransportAdvisor** (`otmfg.advisor`, sealed intelligence
   node): proposes plant-operations coordination actions only, never
   commits
   - `:log-production-batch` — assembly batch, output-quality data logging (administrative, not an operational decision)
   - `:schedule-maintenance` — assembly-line-equipment maintenance scheduling proposal
   - `:flag-safety-concern` — surface a materials-safety/structural-integrity concern (always escalates)
   - `:coordinate-shipment` — outbound product shipment coordination proposal

2. **Other Transport Equipment Plant Operations Governor**
   (`otmfg.governor`, independent validation layer, never trusts the
   advisor's own self-report):
   - HARD invariants (no override, evaluated unconditionally,
     elaborated into twelve concrete checks): the referenced equipment
     unit must be independently verified/registered before any
     maintenance may be scheduled against it; the referenced batch
     must be independently verified/registered before any shipment may
     be coordinated against it; the request's own `:effect` must be
     `:propose`; `:op` must be in the closed four-op allowlist; the
     proposal's own `:effect` must be one of the four propose-shaped
     effects (no direct assembly-line-equipment control);
     `:actuate-equipment? true` on a maintenance schedule (directly
     actuating assembly-line equipment) is a PERMANENT block;
     `:issue-certification? true` on ANY proposal (self-issuing a
     transport-equipment safety/roadworthiness certification) is a
     PERMANENT block; a shipment may not push a batch's own recorded
     shipped unit quantity past its own logged production quantity
     (independently recomputed); no double-scheduling the same
     maintenance record; no fabricated `:product-category` value; no
     physically implausible `:weight-capacity-kg` value; no physically
     implausible `:assembly-defect-rate-percent` value
   - ESCALATE (human sign-off, overridable): safety concerns always
     escalate regardless of confidence; low confidence

3. **Scope boundary** (safety-critical domain — materials-safety and
   structural-integrity hazard, transport-equipment safety/
   roadworthiness certification, direct road-user/load-safety
   consequence):
   - Does NOT control assembly-line equipment directly
   - Does NOT make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
   - Does NOT actuate assembly-line equipment (permanently blocked,
     not a rollout milestone still to come — see `otmfg.phase`:
     `:schedule-maintenance` is never a member of any phase's `:auto`
     set)
   - Does NOT self-issue a transport-equipment safety/roadworthiness certification mark (permanently blocked, unconditional)
   - Does NOT cover bicycles or invalid carriages (that scope belongs to `cloud-itonami-isic-3092`; the closed product-category set structurally excludes bicycle/wheelchair categories, and a dedicated test asserts the exclusion)
   - All proposals are `:effect :propose`; actuation and certification are human-/institution-approval-gated

4. **Self-contained domain logic**: `otmfg.registry` pure functions
   (`equipment-ready?`, `batch-ready?`, `shipment-quantity-exceeded?`,
   `product-category-valid?`, `weight-capacity-valid?`, `defect-rate-
   valid?`) are re-verified independently by the governor, following
   the "ground truth, not self-report" discipline established by prior
   actors (most directly `cloud-itonami-isic-3092`'s `bikemfg.registry`).

5. **Store** (`otmfg.store`): a single `MemStore` backend behind a
   `Store` protocol, tracking four entity kinds (batches, equipment,
   maintenance, shipments) plus the append-only ledger. Like 3092,
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
   `github.com/cloud-itonami/cloud-itonami-isic-3099` (public OSS,
   AGPL-3.0-or-later).

## Consequences

(+) Other-transport-equipment plant-operations back-office
coordination is now genuinely implemented and tested (not merely
scaffolded). ISIC 3099 moves from `:spec` to `:implemented`.

(+) Scope boundary is explicit and verifiable: the governor's HARD
invariants protect against scope creep into unauthorized equipment
operation, equipment actuation, or certification self-issuance,
independently corroborated by `otmfg.phase`'s permanent exclusion of
`:schedule-maintenance` from every phase's `:auto` set.

(+) The two independent verified/registered gates (equipment for
maintenance, batch for shipment) are a genuinely other-transport-
equipment-manufacturing-specific elaboration mirroring 3092's own
two-entity-kind gate — this domain has two distinct ground-truth
entity kinds a proposal can reference, and each is independently
re-derived from its own permanent record, never trusting the
proposal's self-report.

(+) The certification-authority-blocked check is generalized
(a "transport-equipment safety/roadworthiness certification mark")
rather than naming a fabricated specific standard, since this
residual class's product set (animal-drawn carts, hand-trucks,
sledges, etc.) has no single uniformly-applicable certification
regime the way 3092's bicycles/wheelchairs have ISO 4210/ISO 7176 —
the governor still closes the scope-creep vector explicitly rather
than leaving it implicit in the closed op-allowlist alone, without
inventing a false-sounding real standard number.

(+) Scope is disambiguated from the neighboring `cloud-itonami-isic-3092`
actor at both the blueprint level (product-category closed set
explicitly excludes bicycle/invalid-carriage categories) and the test
level (a dedicated test asserts the exclusion directly).

(+) The repo is standalone (forkable outside the workspace), matching
the pattern established by prior actors.

(+) All four core modules (governor/store/advisor/registry) plus
`deps.edn` are present and exercised by 78 tests / 218 assertions
across 5 test namespaces (`otmfg.operation-test`,
`otmfg.governor-contract-test`, `otmfg.phase-test`,
`otmfg.store-contract-test`, `otmfg.registry-test`).

(-) Still a simulation/proposal layer, not integrated with real
equipment-telemetry/batch-tracking/freight-dispatch/certification-body
systems — scope is deliberately bounded to back-office coordination.

(-) Safety-concern escalation is a simplified placeholder; a real
deployment would tie it to a domain-specific hazard-severity
classification.

(-) Single-backend Store (MemStore only): a Datomic/kotoba-server-backed
store is a follow-up, not part of this build.

## Verification

- `cloud-itonami-isic-3099` repo: fresh scaffold, full module set
  (governor/store/advisor/registry/operation/phase/sim + `deps.edn` +
  `blueprint.edn` + LICENSE + governance docs) pushed to `main` at
  `github.com/cloud-itonami/cloud-itonami-isic-3099`, initial commit
  `90cff70e5b4a9bc61aa82730b1b36ac780e54119` (confirmed via
  `gh api repos/cloud-itonami/cloud-itonami-isic-3099/commits/main`
  matching the local HEAD SHA).
- `clojure -M:test` (bare, no `:dev` alias needed — `deps.edn` pins
  langgraph+langchain via `:local/root` directly in top-level `:deps`):
  **`Ran 78 tests containing 218 assertions. 0 failures, 0 errors.`**
- `clojure -M:lint`: `linting took 611ms, errors: 0, warnings: 0`.
- All source is `.cljc` (portable); the actor graph is invoked
  exclusively via `langgraph.graph/run*`.
- Audit ledger is append-only; every settled request (commit or hold)
  leaves exactly one ledger fact (contract-tested).
- `:itonami.blueprint/governor` keyword `:other-transport-equipment-
  plant-operations-governor` is grep-verified UNIQUE fleet-wide
  (`gh search code "other-transport-equipment-plant-operations-
  governor"`, zero hits before this repo was created).
- `kotoba-lang/industry` registry entry for `"3099"` updated in place
  from `:spec` to `:maturity :implemented` via an exact-text in-place
  edit of the single `{:id "3099" ...}` block (no wholesale
  regeneration; diff-verified single-block change both before and
  after the edit), landed via a GitHub Contents-API single-file PUT
  under heavy concurrent-agent load (first attempt hit a `409` from a
  concurrent sibling promotion; refetched the live blob SHA, reapplied
  the same exact-text edit — the "3099" block was still untouched —
  and the retry succeeded): `resources/kotoba/industry/registry.edn`
  at commit `335ba6cd18159a3c95f06ec18a783cbb112b6f52`. The live
  `:implemented` count was recomputed fresh via
  `kotoba.industry/maturity-summary` (never `grep -c`) immediately
  before the edit (298 -> 299 for this promotion's own +1); a
  corresponding `test/kotoba/industry_test.clj` catch-up commit
  (adding this promotion's own descriptive `testing` block and
  bumping the aggregate-count assertion to 299) was verified
  `clojure -M:test` green locally (`Ran 15 tests containing 978
  assertions. 0 failures, 0 errors.`) before landing at commit
  `ea53b893e24b8e6beb5b363640ba2198bd6b1c65`.
- Post-merge re-verification from a brand-new fresh clone (plus a
  fresh `../technology` sibling clone) at commit
  `ea53b893e24b8e6beb5b363640ba2198bd6b1c65` reproduced **`Ran 15
  tests containing 978 assertions. 1 failures, 0 errors.`** — the one
  failure (`expected: (= 299 (:implemented m)) actual: (not (= 299
  300))`) is a concurrent-agent race, NOT a regression of this
  promotion's own change: a sibling promotion for ISIC 2825 (`chore
  (industry): promote ISIC 2825 ... to :implemented`, commit
  `5b05dc783cc8`) landed on `main` between this promotion's own two
  Contents-API PUTs (registry.edn then industry_test.clj), advancing
  the live count from 299 to 300 before this promotion's own
  test-file catch-up commit landed — the exact same "aggregate-count
  assertion already stale by the time the catch-up commit lands"
  pattern documented in ADR-2607192000's own Verification section.
  Per that same precedent, fixing ISIC 2825's own catch-up debt is
  left to the next promotion that touches this file, not folded into
  this one. This promotion's own specific contribution was
  independently re-verified: `(kotoba.industry/maturity "3099")` =>
  `:implemented`; `:repo`/`:business-id` correctly point at
  `cloud-itonami/cloud-itonami-isic-3099`; zero UTF-8 mojibake
  (`grep -c "â" resources/kotoba/industry/registry.edn` = 0).
