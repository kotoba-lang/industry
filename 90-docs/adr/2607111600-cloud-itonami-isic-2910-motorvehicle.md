# ADR-2607111600: classic heavy industry 2910 (motor vehicles) → `:implemented`

- Status: Accepted (2026-07-10)
- Related: ADR-2607110500 (2410 steel + 2811 engines/turbines), ADR-2607080800 (3030 aerospace — first manufacturing vertical in the fleet)

## Context

Following the same-day promotion of **basic iron and steel (2410)** and
**engines/turbines (2811)** (ADR-2607110500), **motor vehicles (2910)**
still sat as a `:maturity :spec` placeholder with a dead
`gftdcojp/cloud-itonami-C2910` URL — no repo, no business model, no
actor. Ships and floating structures (3011) had already closed the
first classic heavy-industry gap as its own governed actor (Shipyard
Advisor ⊣ Shipyard Governor), but has no dedicated promotion ADR of
its own.

## Decision

1. **`cloud-itonami-isic-2910`** — Automotive Advisor ⊣ Automotive
   Governor (vehicle intake, type-approval/homologation rules verify,
   end-of-line quality screen, dual actuation: dispatch-vehicle +
   issue-conformity-certificate, audit export).
2. Real-world regulatory basis: vehicle type-approval / homologation
   regimes — UNECE WVTA (used by GBR/DEU), US FMVSS self-certification
   (NHTSA), Japan's MLIT type designation. Facts catalog seeds JPN
   (MLIT) / USA (NHTSA) / GBR (DVSA) / DEU (KBA) only.
3. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2910` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2910` placeholder.
4. Pattern: clone of the 2410/2811 (and, before them, 3011/3030)
   governed-actor fleet shape (langgraph, dual actuation, dedicated
   boolean double-guards, honest facts catalog) — fourth classic
   heavy-industry manufacturing vertical, third in the same
   steel/engines/vehicles cluster after 2410 and 2811.

## Consequences

(+) Classic heavy-industry manufacturing cluster (steel / engines-
turbines / motor vehicles) now has three implemented open business
stacks alongside shipbuilding (3011) and aerospace (3030).
(−) Physics / plant digital-twin geometry remains out of scope (export
+ operator console samples only).
(−) Facts catalog seeds four jurisdictions; not exhaustive
type-approval-authority coverage.

## Verification

- `cloud-itonami-isic-2910`: `clojure -M:dev:test` green (39 tests /
  191 assertions), `clojure -M:lint` clean
- Industry: maturity `"2910"` → `:implemented`
- GitHub public repo under `cloud-itonami/`
