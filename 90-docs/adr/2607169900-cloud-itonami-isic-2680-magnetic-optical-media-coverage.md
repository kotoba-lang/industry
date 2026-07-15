# ADR-2607169900: cloud-itonami-isic-2680 — Manufacture of Magnetic and Optical Media Plant-Operations Coordination Actor

**Status**: ACCEPTED

**Context**

ISIC Rev.5 2680 ("Manufacture of magnetic and optical media") had a
`:spec`-only placeholder entry in `kotoba-lang/industry`'s registry
pointing at a never-created `gftdcojp/cloud-itonami-C2680` repo.
Confirmed via `gh api` 404 before any work began (per this fleet's
ID/name-mismatch caution) that no repo exists at either that stale
placeholder or the real `cloud-itonami` org — this is a fresh
scaffold, not a fix to an existing broken attempt. The registry
entry's live `:name` ("Manufacture of magnetic and optical media") was
independently re-verified against a fresh clone of `kotoba-lang/
industry` before any scaffolding began, per this fleet's mandatory
ID/name-mismatch caution.

`cloud-itonami-isic-2640` (Manufacture of consumer electronics) is the
closest verified sibling: both are back-office plant-operations
coordination actors for a fixed processing PLANT with electronics/
precision-manufacturing equipment and a real physical safety
dimension, and share the same four-op shape (`:log-production-batch`/
`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`)
and the same two-entity verified/registered gate structure.

**Decision**

Scaffold and implement `cloud-itonami-isic-2680` (Manufacture of
magnetic and optical media) as a full actor, mirroring
`cloud-itonami-isic-2640`'s verified module shape module-for-module
(`magopticalmedia.*` in place of `consumerelec.*`), adapted to a
magnetic-tape coating line and CD/DVD/Blu-ray-style optical-disc
injection-molding and data-stamping line producing blank and
pre-recorded magnetic tape, magnetic-strip cards, and optical discs:

1. **Advisor** (`magopticalmedia.advisor`, `MagOpticalMediaAdvisor`):
   drafts production-batch-log, maintenance-schedule, safety-concern-
   flag and shipment-coordination proposals (deterministic mock
   advisor, extensible to a real LLM via the same `Advisor` protocol
   seam every sibling actor uses).

2. **Governor** (`magopticalmedia.governor`, `Magnetic and Optical
   Media Plant Operations Governor`): twelve concrete checks
   elaborating four HARD invariants — request-level propose-only
   effect, closed op allowlist, closed proposal-effect allowlist (no
   direct coating/molding/stamping-line-equipment control), permanent
   equipment-actuate block (`:actuate-equipment?`), permanent
   content-replication licensing-authority block
   (`:issue-certification?` — self-issuing an IFPI Source
   Identification (SID)-style authorization mark), independent
   equipment-verified/registered gate before maintenance scheduling,
   independent batch-verified/registered gate before shipment
   coordination, independent shipment-quantity recompute against the
   batch's own logged production quantity, double-schedule guard,
   product-type validation, substrate (disc/tape/card) thickness
   plausibility validation, and defect-rate plausibility validation.

3. **Store** (`magopticalmedia.store`): single `MemStore` backend
   (atom of EDN) behind a `Store` protocol — batches/equipment/
   maintenance/shipments/safety-concerns/ledger, same shape every
   sibling actor's own MemStore uses.

4. **Registry** (`magopticalmedia.registry`): pure-function domain
   logic — equipment/batch verification, shipment-quantity recompute,
   product-type/substrate-thickness/defect-rate plausibility
   validation, draft maintenance-schedule/shipment-coordination record
   construction (unsigned certificates only).

5. **Phase** (`magopticalmedia.phase`): 0→3 rollout (read-only →
   assisted-intake → assisted-coordinate → supervised-auto).
   `:schedule-maintenance`/`:flag-safety-concern`/
   `:coordinate-shipment` are NEVER in any phase's `:auto` set; only
   `:log-production-batch` may auto-commit at phase 3 when clean.

6. **Operation** (`magopticalmedia.operation`): the langgraph-clj
   StateGraph wiring advisor → governor → phase-gate →
   commit/hold/human-approval, invoked exclusively via
   `langgraph.graph/run*` (cljs-portable).

7. **Tests**: 77 tests / 211 assertions across governor contract (all
   twelve HARD checks, propose-only defense-in-depth, ledger
   discipline, approval rejection), phase invariants, registry
   validation/draft-record construction, store contract, operation
   smoke tests — the same shape as reference sibling
   `cloud-itonami-isic-2640` (77 tests/210 assertions; one additional
   assertion here).

**Implementation details — deliberate differences from the 2640 reference**

- **Domain-specific safety-concern vocabulary**: `:flag-safety-concern`
  surfaces a solvent-coating chemical-hazard/equipment-safety concern
  (magnetic-particle coating uses solvent-based binders whose vapor
  concentration is a real plant hazard), in place of 2640's
  battery-safety/electrical-safety/RoHS-compliance vocabulary.
- **Product-type closed set**: `#{:cd :dvd :blu-ray :magnetic-tape
  :magnetic-strip-card}` in place of 2640's television/audio-device/
  video-device/smart-speaker/wearable-device set.
- **Equipment kinds**: `:coating-line` (verified/registered,
  schedulable) and `:data-stamping-line` (unverified/unregistered,
  blocks scheduling) in place of 2640's `:smt-placement-line`/
  `:final-assembly-test-bench`.
- **Substrate thickness plausibility field**: `:substrate-thickness-mm`
  (0-1.5 mm, grounded in ECMA-130/267/405's ~1.2 mm optical-disc
  thickness and ISO/IEC 7810's ~0.76 mm ID-1 magnetic-strip-card
  thickness) in place of 2640's `:dielectric-test-kv` electrical
  hipot/withstand safety-test voltage — a different physical quantity
  entirely, since this vertical's plausibility check is about physical
  media geometry, not electrical safety.
- **Licensing-authority block, not safety-certification block**: this
  vertical's permanent, unconditional `:issue-certification?` block
  guards against self-issuing a content-replication licensing/
  source-identification authorization mark (e.g. an IFPI Source
  Identification (SID) Code, the code a licensed optical-disc
  mastering/replication plant stamps into every disc under
  authorization from the rights holder / licensing body), in place of
  2640's UL/CE/FCC/RoHS product-safety-certification block — the same
  "no phase, no human override" posture, adapted to a copyright/
  replication-licensing authority boundary rather than a product-
  safety-certification authority boundary.
- **Fully portable `.cljc`, no JVM-only interop anywhere in `src/`**
  (mandatory for this task) — the actor graph is invoked exclusively
  via `langgraph.graph/run*`, never `.invoke`.
- The blueprint's `:itonami.blueprint/governor` keyword,
  `:magnetic-optical-media-plant-operations-governor`, is grep-verified
  UNIQUE fleet-wide (`gh search code
  "magnetic-optical-media-plant-operations-governor" --owner
  cloud-itonami`, zero hits before this repo was created).

**Consequences**

- Certified magnetic-tape-coating and optical-disc-molding/stamping
  plant operators can fork and deploy independently, with auditable
  coordination records and hard safety gates (equipment-control
  bypass, coating/molding/stamping-line actuation, content-replication
  licensing self-issuance) that never permit direct coating/molding/
  stamping-line-equipment control or licensing self-issuance to slip
  through, even with human approval.
- Safety-concern flagging (solvent-coating chemical hazard,
  equipment-safety hazard) is a circuit-breaker, not a threshold — it
  always escalates to a human plant supervisor regardless of
  confidence.
- `kotoba-lang/industry` registry entry `"2680"` promoted `:spec` →
  `:implemented`, `:repo`/`:business-id` corrected from the stale
  `gftdcojp/cloud-itonami-C2680` placeholder to
  `cloud-itonami/cloud-itonami-isic-2680`.
- Still a simulation/proposal layer, not a real plant-operations
  control system — equipment actuation and coating/molding/stamping-
  line operation remain human-controlled via external channels; no
  integration with real plant-management databases (equipment
  telemetry, batch tracking, freight dispatch, licensing-body APIs).

---

Repo: [`cloud-itonami/cloud-itonami-isic-2680`](https://github.com/cloud-itonami/cloud-itonami-isic-2680)
Registry: [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
