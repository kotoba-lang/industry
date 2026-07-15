# ADR-2607159000: cloud-itonami-isic-2740 — Electric Lighting Equipment Plant-Operations Coordination Actor

**Status**: ACCEPTED

**Context**

ISIC Rev.5 2740 ("Manufacture of electric lighting equipment") had a
`:spec`-only placeholder entry in `kotoba-lang/industry`'s registry
pointing at a never-created `gftdcojp/cloud-itonami-C2740` repo.
Confirmed via `gh api` 404 before any work began (per this fleet's
ID/name-mismatch caution) that no repo exists at either that stale
placeholder or the real `cloud-itonami` org — this is a fresh
scaffold, not a fix to an existing broken attempt. The registry
entry's live `:name` ("Manufacture of electric lighting equipment")
was independently re-verified against a fresh clone of
`kotoba-lang/industry` before any scaffolding began, per this fleet's
mandatory ID/name-mismatch caution.

`cloud-itonami-isic-2750` (Manufacture of domestic appliances) is the
closest verified sibling: both are back-office plant-operations
coordination actors for a fixed manufacturing plant with electronics/
electromechanical assembly/test equipment and a real physical safety
dimension, and share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure.

**Decision**

Scaffold and implement `cloud-itonami-isic-2740` (Manufacture of
electric lighting equipment) as a full actor, mirroring
`cloud-itonami-isic-2750`'s verified module shape module-for-module
(`eleclighting.*` in place of `domappl.*`), adapted to LED/lamp-
assembly, fixture-housing/optics-assembly, and photometric/electrical
testing lines producing finished LED lamps, LED luminaires, LED
drivers (control gear), downlight fixtures, and street-light
fixtures:

1. **Advisor** (`eleclighting.advisor`, `ElecLightingAdvisor`): drafts
   production-batch-log, maintenance-schedule, safety-concern-flag and
   shipment-coordination proposals (deterministic mock advisor,
   extensible to a real LLM via the same `Advisor` protocol seam every
   sibling actor uses).

2. **Governor** (`eleclighting.governor`, `Electric Lighting Plant
   Operations Governor`): twelve concrete checks elaborating four HARD
   invariants — request-level propose-only effect, closed op
   allowlist, closed proposal-effect allowlist (no direct assembly/
   test-bench-equipment control), permanent equipment-actuate block,
   permanent certification-authority block (never self-issues a UL/CE
   electric-lighting-equipment safety mark), independent
   equipment-verified/registered gate before maintenance scheduling,
   independent batch-verified/registered gate before shipment
   coordination, independent shipment-quantity recompute against the
   batch's own logged production quantity, double-schedule guard,
   product-type validation, dielectric (hipot/withstand)
   safety-test-voltage plausibility validation (0–4 kV, grounded in
   IEC 60598-1/IEC 61347-1), and defect-rate plausibility validation.

3. **Store** (`eleclighting.store`): single `MemStore` backend (atom
   of EDN) behind a `Store` protocol — batches/equipment/maintenance/
   shipments/safety-concerns/ledger, same shape every sibling actor's
   own MemStore uses.

4. **Registry** (`eleclighting.registry`): pure-function domain logic
   — equipment/batch verification, shipment-quantity recompute,
   product-type/dielectric-test-kv/defect-rate plausibility
   validation, draft maintenance-schedule/shipment-coordination record
   construction (unsigned certificates only).

5. **Phase** (`eleclighting.phase`): 0→3 rollout (read-only →
   assisted-intake → assisted-coordinate → supervised-auto).
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are NEVER in any phase's `:auto` set; only
   `:log-production-batch` may auto-commit at phase 3 when clean.

6. **Operation** (`eleclighting.operation`): the langgraph-clj
   StateGraph wiring advisor → governor → phase-gate →
   commit/hold/human-approval, invoked exclusively via
   `langgraph.graph/run*` (cljs-portable).

7. **Tests**: 77 tests / 210 assertions across governor contract (all
   twelve HARD checks, propose-only defense-in-depth, ledger
   discipline, approval rejection), phase invariants, registry
   validation/draft-record construction, store contract, operation
   smoke tests — the exact same test/assertion count as verified
   siblings `cloud-itonami-isic-2710` and `cloud-itonami-isic-2750`.

**Implementation details — deliberate differences from the 2750 reference**

- **Domain-specific safety-concern vocabulary**: `:flag-safety-concern`
  surfaces an "electrical-safety/photobiological-hazard/UL-CE-
  compliance concern" (photobiological hazard per IEC 62471 —
  blue-light/UV/IR emission risk to eyes/skin from LED/lamp sources)
  in place of 2750's "electrical-safety/refrigerant-leak/UL-CE-
  compliance concern" (electric lighting equipment has no refrigerant
  circuit).
- **Product-type closed set**: `#{:led-lamp :led-luminaire :led-driver
  :downlight-fixture :street-light-fixture}` in place of 2750's
  refrigerator/washing-machine/dishwasher/microwave-oven/vacuum-
  cleaner set.
- **Equipment kinds**: `:led-assembly-line` (verified/registered,
  schedulable) and `:photometric-test-bench` (unverified/unregistered,
  blocks scheduling) in place of 2750's `:assembly-line`/
  `:final-test-bench`.
- **Dielectric-test-kv ceiling justification**: grounded in IEC
  60598-1 (luminaires — general requirements and tests) and IEC
  61347-1 (lamp control gear — general and safety requirements)
  electric-strength test-voltage tables, which top out around 4 kV for
  double/reinforced-insulation (Class II) mains-connected lighting
  equipment, in place of 2750's IEC 60335-1 domestic-appliance table.
- **Fully portable `.cljc`, no JVM-only interop anywhere in `src/`**
  (mandatory for this task) — the actor graph is invoked exclusively
  via `langgraph.graph/run*`, never `.invoke`.
- The blueprint's `:itonami.blueprint/governor` keyword,
  `:electric-lighting-plant-operations-governor`, is grep-verified
  UNIQUE fleet-wide (`gh search code
  "electric-lighting-plant-operations-governor" --owner cloud-itonami`,
  zero hits before this repo was created).

**Consequences**

- Certified electric-lighting-equipment plant operators can fork and
  deploy independently, with auditable coordination records and hard
  safety gates (equipment-control bypass, equipment actuation,
  self-issued certification) that never permit direct assembly/
  test-bench-equipment control to slip through, even with human
  approval.
- Safety-concern flagging (electrical-safety/photobiological-hazard/
  UL-CE-compliance) is a circuit-breaker, not a threshold — it always
  escalates to a human plant supervisor regardless of confidence.
- `kotoba-lang/industry` registry entry `"2740"` promoted `:spec` →
  `:implemented`, `:repo`/`:business-id` corrected from the stale
  `gftdcojp/cloud-itonami-C2740` placeholder to
  `cloud-itonami/cloud-itonami-isic-2740`.
- Still a simulation/proposal layer, not a real plant-operations
  control system — equipment actuation, line operation, and
  certification issuance remain human-/institution-controlled via
  external channels; no integration with real plant-management
  databases (equipment telemetry, batch tracking, freight dispatch,
  certification-body APIs).

---

Repo: [`cloud-itonami/cloud-itonami-isic-2740`](https://github.com/cloud-itonami/cloud-itonami-isic-2740)
Registry: [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
