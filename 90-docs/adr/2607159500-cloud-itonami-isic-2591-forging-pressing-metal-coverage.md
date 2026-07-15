# ADR-2607159500: cloud-itonami-isic-2591 — Forging, Pressing, Stamping and Roll-Forming of Metal; Powder Metallurgy Plant-Operations Coordination Actor

**Status**: ACCEPTED

**Context**

ISIC Rev.5 2591 ("Forging, pressing, stamping and roll-forming of
metal; powder metallurgy") had a `:spec`-only placeholder entry in
`kotoba-lang/industry`'s registry pointing at a never-created
`gftdcojp/cloud-itonami-C2591` repo. Confirmed via `gh api` 404 before
any work began (per this fleet's ID/name-mismatch caution) that no
repo exists at either that stale placeholder or the real
`cloud-itonami` org — this is a fresh scaffold, not a fix to an
existing broken attempt. The registry entry's live `:name` ("Forging,
pressing, stamping and roll-forming of metal") was independently
re-verified against a fresh clone of `kotoba-lang/industry` before any
scaffolding began, per this fleet's mandatory ID/name-mismatch
caution.

`cloud-itonami-isic-2593` (Manufacture of cutlery, hand tools and
general hardware) is the closest verified sibling: both are
back-office plant-operations coordination actors for a fixed
manufacturing plant with forging/pressing-class equipment and a real
physical safety dimension, and share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same two-entity
verified/registered gate structure. The two verticals occupy distinct
positions in ISIC group 259, however: 2591 is the PRIMARY metal-
forming PROCESS shop (a job shop/subcontractor turning raw metal stock
into near-net-shape forged/pressed/stamped/roll-formed/sintered parts
for downstream manufacturers, including 2593's own shop), while 2593
is the specific downstream shop that finishes stock into a named
consumer product family (cutlery, hand tools, general hardware).

**Decision**

Scaffold and implement `cloud-itonami-isic-2591` (Forging, pressing,
stamping and roll-forming of metal; powder metallurgy) as a full
actor, mirroring `cloud-itonami-isic-2593`'s verified module shape
module-for-module (`metalforming.*` in place of `hardwaremfg.*`),
adapted to a forging-press/stamping-press/roll-forming-mill/powder-
metallurgy-press-and-sinter production line producing forged parts,
pressed parts, stamped parts, roll-formed parts, powder-metallurgy
parts, and forging billets:

1. **Advisor** (`metalforming.advisor`, `MetalFormingAdvisor`): drafts
   production-batch-log, maintenance-schedule, safety-concern-flag and
   shipment-coordination proposals (deterministic mock advisor,
   extensible to a real LLM via the same `Advisor` protocol seam every
   sibling actor uses).

2. **Governor** (`metalforming.governor`, `Metal Forming Plant
   Operations Governor`): ten concrete checks elaborating four HARD
   invariants — request-level propose-only effect, closed op
   allowlist, closed proposal-effect allowlist (no direct forging/
   pressing-line-equipment control), permanent forge/press-line-
   actuate block (`:actuate-forge-press-line?`), independent
   equipment-verified/registered gate before maintenance scheduling,
   independent batch-verified/registered gate before shipment
   coordination, independent shipment-weight recompute against the
   batch's own logged production weight, double-schedule guard,
   product-category validation, and defect-rate plausibility
   validation.

3. **Store** (`metalforming.store`): single `MemStore` backend (atom
   of EDN) behind a `Store` protocol — batches/equipment/maintenance/
   shipments/safety-concerns/ledger, same shape every sibling actor's
   own MemStore uses.

4. **Registry** (`metalforming.registry`): pure-function domain logic
   — equipment/batch verification, shipment-weight recompute,
   product-category/defect-rate plausibility validation, draft
   maintenance-schedule/shipment-coordination record construction
   (unsigned certificates only).

5. **Phase** (`metalforming.phase`): 0→3 rollout (read-only →
   assisted-intake → assisted-coordinate → supervised-auto).
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are NEVER in any phase's `:auto` set; only
   `:log-production-batch` may auto-commit at phase 3 when clean.

6. **Operation** (`metalforming.operation`): the langgraph-clj
   StateGraph wiring advisor → governor → phase-gate →
   commit/hold/human-approval, invoked exclusively via
   `langgraph.graph/run*` (cljs-portable).

7. **Tests**: 71 tests / 195 assertions across governor contract (all
   ten HARD checks, propose-only defense-in-depth, ledger discipline,
   approval rejection), phase invariants, registry
   validation/draft-record construction, store contract, operation
   smoke tests — the exact same test/assertion count as reference
   sibling `cloud-itonami-isic-2593`.

**Implementation details — deliberate differences from the 2593 reference**

- **Domain-specific safety-concern vocabulary**: `:flag-safety-concern`
  surfaces forging-press/stamping-press pinch/crush hazard, high-
  temperature-forging burn/radiant-heat exposure, roll-forming-mill
  entanglement hazard, and powder-metallurgy combustible-metal-dust/
  inhalation exposure, in place of 2593's sharp-edge-laceration/
  forging-hammer-pinch-crush/grinding-wheel-abrasive-dust/heat-
  treatment-furnace-burn vocabulary (this vertical has no cutlery/
  tool sharp-edge hazard — it ships near-net-shape stock, not
  finished cutting edges).
- **Product-category closed set**: `#{:forged-part :pressed-part
  :stamped-part :roll-formed-part :powder-metallurgy-part
  :forging-billet}` in place of 2593's cutlery/hand-tool/general-
  hardware/garden-tool/kitchen-utensil/lock-hardware set.
- **Equipment kinds**: `:forging-press` (verified/registered,
  schedulable) and `:roll-forming-mill` (unverified/unregistered,
  blocks scheduling) in place of 2593's `:forging-hammer`/
  `:grinding-wheel`.
- **Actuation guard key**: `:actuate-forge-press-line?` (forging
  press/stamping press/roll-forming mill/powder-metallurgy press-and-
  sinter line) in place of 2593's `:actuate-forge-grind-line?`
  (forging hammer/grinding wheel/heat-treatment furnace/finishing
  line).
- **Fully portable `.cljc`, no JVM-only interop anywhere in `src/`**
  (mandatory for this task) — the actor graph is invoked exclusively
  via `langgraph.graph/run*`, never `.invoke`.
- The blueprint's `:itonami.blueprint/governor` keyword,
  `:metal-forming-plant-operations-governor`, is grep-verified UNIQUE
  fleet-wide (`gh search code
  "metal-forming-plant-operations-governor" --owner cloud-itonami`,
  zero hits before this repo was created).

**Consequences**

- Certified forging/pressing/stamping/roll-forming and powder-
  metallurgy plant operators can fork and deploy independently, with
  auditable coordination records and hard safety gates (equipment-
  control bypass, forge/press-line actuation) that never permit direct
  forging/pressing-line-equipment control to slip through, even with
  human approval.
- Safety-concern flagging (forging-press/stamping-press pinch-crush,
  high-temperature-forging burn, roll-forming-mill entanglement,
  powder-metallurgy combustible-dust exposure) is a circuit-breaker,
  not a threshold — it always escalates to a human plant supervisor
  regardless of confidence.
- `kotoba-lang/industry` registry entry `"2591"` promoted `:spec` →
  `:implemented`, `:repo`/`:business-id` corrected from the stale
  `gftdcojp/cloud-itonami-C2591` placeholder to
  `cloud-itonami/cloud-itonami-isic-2591`.
- Still a simulation/proposal layer, not a real plant-operations
  control system — equipment actuation and forge/press-line operation
  remain human-controlled via external channels; no integration with
  real plant-management databases (equipment telemetry, batch
  tracking, freight dispatch).

---

Repo: [`cloud-itonami/cloud-itonami-isic-2591`](https://github.com/cloud-itonami/cloud-itonami-isic-2591)
Registry: [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
