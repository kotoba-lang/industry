# ADR-2607150400: downstream retail gap 4741 (computer/peripheral/software retail) → `:implemented`

- Status: Accepted (2026-07-14)
- Related: ADR-2607142800 (robotics premise → concrete process simulation, fleet pattern), ADR-2607011000 (original robotics premise + ISIC section coverage 21/21), ADR-2607111600 (isic-2910 motorvehicle → `:implemented`, structural template), ADR-2607150200 (isic-2620 device-assembly, which explicitly named isic-4741 as remaining follow-up work)

## Context

A value-chain survey of the computer/electronics industry (2026-07-14)
found manufacturing stages covered -- `cloud-itonami-isic-2610`
(semiconductor/component fab) and `cloud-itonami-isic-2620` (final
device assembly, ADR-2607150200) -- but the DOWNSTREAM RETAIL tier
(ISIC 4741, retail sale of computers, peripheral equipment and
software in specialized stores) had NO actor at all. The registry
entry sat as a `:maturity :spec` placeholder with a dead
`gftdcojp/cloud-itonami-G4741` URL and `business-id
"cloud-itonami-G4741"` -- no repo, no business model, no actor.
ADR-2607150200's own Consequences section named `isic-4741 computer
retail` explicitly as follow-up work still outstanding.

This gap was also an opportunity: every prior cloud-itonami vertical
that ships the ADR-2607142800 robotics-process-simulation pattern
(`automotive.robotics` and its siblings) does so in a MANUFACTURING
context -- a factory/assembly-line robot cell. No cloud-itonami
vertical had yet made the ADR-2607011000 robotics premise ("every
cloud-itonami vertical is designed on the premise that a robot
performs the physical-domain work") concrete for a RETAIL business.
A computer retailer that runs a buy-back / trade-in-for-credit program
needs a robot-executed, NIST-SP-800-88-compliant data-sanitization/
erasure cell before a trade-in device can be resold -- a genuine
physical-domain robot task fitting this specific vertical, not a
factory line.

## Decision

1. **`cloud-itonami-isic-4741`** -- Retail Advisor ⊣ Retail Governor
   (order intake, per-jurisdiction consumer-protection/distance-
   selling rules verify, trade-in-condition defect screen, robot
   certified data-wipe mission, dual actuation: fulfill-order +
   issue-sanitization-certificate, audit export).
2. UNLIKE every single-entity sibling (one entity, two actuations on
   it), this domain has TWO entity types -- an **order** (customer
   purchase, optionally bundling a trade-in) and a **trade-in-unit**
   (the traded-in device) -- each independently governed and each
   carrying its own actuation, append-only history and jurisdiction-
   scoped sequence counter. No governor check traverses the
   descriptive `:trade-in-unit-id` link between them.
3. Real-world regulatory basis: consumer-protection/distance-selling
   law -- Japan's 特定商取引法 (administered by 消費者庁), the US FTC's
   Mail, Internet, or Telephone Order Merchandise Rule (16 CFR Part
   435), and the EU's Consumer Rights Directive 2011/83/EU. Facts
   catalog seeds JPN / USA / EUR only; GBR and others are honestly
   uncovered. Japan's entry deliberately does NOT claim a cooling-off
   right for 通信販売 (mail order) -- that is a door-to-door/
   telemarketing concept under Japanese law, not a distance-selling
   one; the catalog cites the seller-identity/price/return-policy
   disclosure duties the law actually imposes instead.
4. **Robot process simulation delivered from day one, per the
   ADR-2607142800 pattern, and the first instance of that pattern in a
   RETAIL (not manufacturing) vertical**: `techretail.robotics` (a
   robot certified data-wipe mission -- device connect-and-
   authenticate, sanitization pass, post-wipe functional test),
   `techretail.governor/data-wipe-mission-violations` (HARD-holds
   `:actuation/issue-sanitization-certificate` if the mission never
   ran, OR if an independent recheck of the device's own post-wipe
   verification-read field disagrees with the mission's stored
   `:passed?`), and `techretail.registry/order-total-mismatch?` (a
   further instance of the fleet's two-sided range-check family, after
   `automotive.registry/vehicle-emissions-out-of-range?` and its
   siblings, applied here to commerce arithmetic rather than a
   physical measurement).
5. The media-sanitization evidence basis cites **NIST SP 800-88 Rev. 2**
   (2025-09), not the older Rev. 1 (2014-12-17, withdrawn 2025-09-26)
   -- the current, in-force standard, the same honesty discipline this
   fleet applies to jurisdiction coverage.
6. Double-actuation guards use dedicated booleans
   (`:order-fulfilled?`, `:sanitization-certified?`), never a status
   lifecycle (ADR-2607071320 lesson). Trade-in grading/defect-
   unresolved is evaluated unconditionally so `:trade-in-condition/
   screen` itself can HARD-hold (parksafety ADR-2607071922 Decision 5
   discipline).
7. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-4741` URL, replacing the dead
   `gftdcojp/cloud-itonami-G4741` placeholder and `business-id`.

## Consequences

(+) The computer/electronics value chain now has a component-fab actor
(`cloud-itonami-isic-2610`), a final-product-assembly actor
(`cloud-itonami-isic-2620`), and a downstream-retail actor
(`cloud-itonami-isic-4741`), closing the gap ADR-2607150200 flagged.
(+) First demonstration that the ADR-2607011000 robotics premise
applies concretely OUTSIDE manufacturing -- a retail trade-in
sanitization cell, not a factory assembly line.
(+) Reuses langgraph + store dual-backend parity without new physics;
the two-entity-type shape (order / trade-in-unit) is a new but bounded
variation other multi-entity retail/service verticals in this fleet
can reuse.
(−) No physical store/warehouse digital-twin tick in this repo (export
+ operator console samples only, matching every prior sibling).
(−) Consumer-protection-authority coverage is a starting catalog (JPN/
USA/EUR), not exhaustive -- GBR and others are explicitly uncovered.

## Verification

- `cloud-itonami-isic-4741`: `clojure -M:dev:test` green (48 tests /
  235 assertions), `clojure -M:lint` clean, `clojure -M:dev:run` demo
  narrative exercises all 6 HARD holds (`:no-spec-basis`,
  `:evidence-incomplete`, `:order-total-mismatch`, `:trade-in-defect-
  unresolved`, `:data-wipe-mission-missing`, `:sanitization-
  incomplete`) plus `:already-fulfilled`/`:already-sanitization-
  certified` double-actuation guards, with no exceptions.
- Industry: maturity `"4741"` → `:implemented`
- GitHub public repo under `cloud-itonami/`
