# ADR-2607062210: net-babiniku characters — optional physical embodiment via kotoba-lang/giemon, procurable through an EMS ordering pipeline

## Status
Proposed

## Context

User direction (2026-07-06): "また キャラクターごとに kotoba-lang/giemon をベースに
humanoid ロボットも設計して, 発注できるようにして" — also design a humanoid robot per
`net-babiniku` character based on `kotoba-lang/giemon`, and make it orderable (発注できる状態
に).

### `kotoba-lang/giemon` — an existing open-hardware product line, not a green field

`orgs/kotoba-lang/giemon/README.md`: **Giemon** (ADR-2605142200) ships three products —
**Otete** (6-DOF arm + crawler kit, `:product/status :shipping`, real articulation fixture at
`fixtures/giemon_arm6/`), **Hitogata** (17-axis biped, `:in-design`, **no articulation fixture
yet**), **Caterpillar** (dual-track UGV, `:in-design`). A fourth application, **Giemon Kaigo**
(ADR-2605142300, in-home elder care), consumes these products but is a distinct use case from
what's being asked here. The library owns product metadata, forward kinematics/torque
validation, an actuator BOM generator (`arm/bom giemon-arm6 :all-qdd` /
`:harmonic-shoulder`, ported from `kami-articulated-scene`), a viewer scene-IR, and CSV/JSON
export (`kotoba.giemon.export`). It explicitly does **not** own safety governance — that's
delegated to `kotoba-lang/robotics` (ADR-2607011000), and `kotoba.giemon.governor` is a thin
wrapper over it.

### `kotoba-lang/robotics` — the one safety contract every actuation path must go through

`orgs/kotoba-lang/robotics/src/kotoba/robotics.cljc` (docstring, lines 1-17): "the library is
policy, not control. It does not drive motors; it gives a governor the records it needs to
refuse unsafe actuation before it ever reaches hardware." `safety-classes`
(`#{:none :low :medium :high :safety-critical}`), `human-sign-off-classes`
(`#{:high :safety-critical}` require human sign-off / `interrupt-before`), `action-kinds`
(`#{:sense :move :grasp :actuate :emit}` — only `:sense` is read-only). This is the same
contract all 26 `cloud-itonami` industry blueprints depend on (per giemon's own README, "Why"
section) — it is not something to route around for a new use case.

### Real-machine control boundary and manufacturing-package precedent

`orgs/gftdcojp/ai-gftd-apps-gftdcojp/90-docs/adr/2604252100-robotics-product-manufacturing-package.md.edn`
(Accepted): "AI agent は actuator に直接 command を送ってはならない。... 実機送信は safety
gateway が validation した後、asset-specific adapter へ渡す" — the same non-negotiable
boundary `kotoba.robotics` encodes. This ADR also defines the **manufacturing package**
concept for physical robot products: CAD/ECAD/CAM/BOM/routing/inspection/firmware/calibration
bundled together, because "この 1 ファイルを渡せば必ず作れる、という単一標準は存在しない" for
real hardware.

### Existing procurement/ordering actors — reuse, don't rebuild an EC stack

- `orgs/gftdcojp/app-aozora/20-actors/yoro-supply/` (ADR-2605250850): a building-materials
  procurement actor, five Pregel cells (`cells/{supplier_selection,order_placement,
  manufacture_track,shipment,delivery_verify}.edn`), lexicon-typed PO/RFQ/manufacturing
  progress/shipment/settlement events, 14 "Constitutional Gates," input is a `projectBOM`.
- `orgs/gftdcojp/cloud-itonami/src/itonami/{store,governor,kyber}.cljc`: `store.cljc`
  line-items for procurement (UNSPSC/supplier/qty/cost); `governor.cljc` HARD-holds any
  procurement item from an unregistered supplier (lineage-break invariant);
  `cloud_itonami/plm.cljc` wraps `kotoba.plm.item` for `item`/`bom-edge`/`change-order`/
  `make-buy`/`unit-cost`/`mbom-rows`.

No general-purpose EC/ordering stack exists in this repo, nor should one be built for this —
the actor-based procurement shape above is the established pattern for "turn a BOM into a real
purchase order."

## Decision

1. **Physical embodiment is optional and per-character, not a new robot design.** A
   `net-babiniku` persona may declare an associated Giemon body. This ADR does **not** design
   a new humanoid — it wires existing (or eventually-existing) Giemon products to character
   identities.

2. **MVP orderable body = Otete (6-DOF arm + crawler), not Hitogata.** Hitogata (17-axis biped)
   is the intuitively "humanoid VTuber body," but it is `:in-design` with **no articulation
   fixture** — there is no real BOM to order yet. Per the honest-default principle already
   adopted in ADR-2607062200 (no fake checkout / no vaporware presented as purchasable),
   **Hitogata is not orderable under this ADR.** Otete is real, shipping, and has a canonical
   BOM (`fixtures/giemon_arm6/`) — it is what "発注できるように" can actually mean today. When
   Hitogata's own design work (tracked independently under giemon/ADR-2605142200) reaches a
   real fixture and BOM, it becomes eligible for the same ordering path with no changes to this
   ADR's decisions — this ADR does not pull that design work forward.

3. **No LLM-to-actuator shortcut, ever.** The persona/governor from ADR-2607062200 proposes
   character behavior only (`dialogue`/`emotion`/`motion-cue`) for the *virtual* VRM body.
   Any path from a character's behavior to a *physical* Giemon body's joint commands must pass
   through `kotoba.robotics`'s `gate` exactly as `kotoba.giemon.governor` already requires for
   Kaigo — `net-babiniku` gets no exception to `:high`/`:safety-critical` human sign-off, and
   `:actuate`/`:move`/`:grasp`/`:emit` are never dispatched directly from a persona proposal.

4. **Ordering reuses the yoro-supply Pregel-cell shape and cloud-itonami's `plm.cljc`
   primitives**, rather than a new ordering stack: a per-character Otete BOM
   (`arm/bom giemon-arm6 <realization-variant>`) becomes the `projectBOM` input to a
   `supplier_selection → order_placement → manufacture_track → shipment → delivery_verify`
   flow, packaged per ADR-2604252100's manufacturing-package manifest (BOM CSV via
   `kotoba.giemon.export`, plus STEP/3MF/Gerber/inspection-plan as applicable) as the artifact
   an EMS actually RFQs against.

5. **Payment/fulfillment counterparty is separate from ADR-2607062200's chat-monetization
   rail.** A robot purchase is an ordinary physical-goods fiat transaction with no
   adult-content PSP constraint — it can use the org's existing Stripe-shaped billing schema
   (`kotoba-lang/com-stripe`, `net-kotobase/billing.cljc` pattern) for the purchase-order
   payment leg. This is intentionally a **different rail** from the paid-chat-tier decision in
   ADR-2607062200; the two must not be conflated (one is adult-content-gated recurring
   billing, the other is a one-time hardware PO).

6. **Character↔body binding is a data association, not new engineering.** Which Giemon SKU
   (today: only Otete) a persona "owns" as its physical embodiment is a persona-record field —
   cosmetic/identity binding — not a new hardware variant per character.

## Consequences

- Only Otete is actually orderable at this ADR's acceptance; a per-character Hitogata biped
  remains aspirational until giemon's own design track ships a real fixture/BOM.
- The procurement pipeline's *durability at scale* (a real, contracted EMS relationship;
  live supplier onboarding; real shipment tracking) is a business/operations follow-up, not
  something this ADR builds.
- `net-babiniku` now depends on `kotoba-lang/giemon` and `kotoba-lang/robotics` for anything
  touching a physical body — these become real cross-repo dependencies, not aspirational
  references.
- Follow-up: registering a `net-babiniku`-specific procurement actor (vs. reusing/forking
  `yoro-supply`'s cells directly) is an implementation decision left open.

## Alternatives Considered

1. **Design an all-new bespoke humanoid robot for net-babiniku instead of reusing giemon.**
   Rejected — giemon is the existing open-hardware product line with real BOM/kinematics/
   governor/manufacturing-package precedent; a bespoke design duplicates real engineering work
   for no benefit, the same reasoning ADR-2607051800 already used to reject reimplementing VRM
   rendering glue.
2. **Let the persona governor command giemon joints directly (skip `kotoba.robotics`'s
   gate).** Rejected — violates the repo's non-negotiable "AI agent never commands actuators
   directly" doctrine (`kotoba.robotics` docstring, `itonami.governor`'s no-actuation
   invariant, ADR-2604252100's safety-gateway requirement).
3. **Build a bespoke EC/ordering system for robot purchases.** Rejected —
   `yoro-supply`'s Pregel-cell procurement actor and `cloud-itonami/plm.cljc` already implement
   this shape; reuse over reinvention.
4. **Default to Hitogata (biped) as the flagship orderable body since it reads as more
   "humanoid"/VTuber-appropriate.** Rejected — Hitogata is still `:in-design` per giemon's own
   README, with no real BOM; presenting it as orderable would violate the honest-default
   principle applied in ADR-2607062200.

## References

- `orgs/kotoba-lang/giemon/README.md`, `src/kotoba/giemon/{giemon,arm,kinematics,governor,
  viewer,export,ui}.cljc`, `fixtures/giemon_arm6/`
- `orgs/kotoba-lang/robotics/src/kotoba/robotics.cljc` (safety-classes, human-sign-off-classes,
  action-kinds, gate contract)
- `orgs/gftdcojp/ai-gftd-apps-gftdcojp/90-docs/adr/2604252100-robotics-product-manufacturing-package.md.edn`
  (real-machine control adapter boundary, manufacturing package definition)
- `90-docs/adr/2607011000-cloud-itonami-robotics-premise-and-isic-21-21.md`,
  `90-docs/adr/2607020000-kotoba-lang-giemon-product-line.md`
- `orgs/gftdcojp/app-aozora/20-actors/yoro-supply/` (procurement Pregel cells, lexicons,
  Constitutional Gates)
- `orgs/gftdcojp/cloud-itonami/src/{itonami/store.cljc,itonami/governor.cljc,
  cloud_itonami/plm.cljc}` (procurement line-items, supplier-lineage HARD hold, BOM/PLM
  primitives)
- `90-docs/adr/2607062200-net-babiniku-onlyfans-style-creator-monetization.md` (sibling ADR —
  the chat-monetization payment rail this ADR's PO payment rail must stay distinct from)
- `orgs/kotoba-lang/com-stripe/src/stripe/main.cljc`,
  `orgs/gftdcojp/net-kotobase/clj-edge/src/kotobase/billing.cljc` (PO payment-leg schema
  precedent)
