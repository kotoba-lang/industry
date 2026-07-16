# ADR-2607111800: classic heavy industry 2511 (structural metal products) → `:implemented`

- Status: Accepted (2026-07-10)
- Related: ADR-2607111600 (2910 motor vehicles), ADR-2607110500 (2410 steel + 2811 engines/turbines), ADR-2607080800 (3030 aerospace — first manufacturing vertical in the fleet)

## Context

Following the same-day promotion of **basic iron and steel (2410)** and
**engines/turbines (2811)** (ADR-2607110500), and **motor vehicles
(2910)** (ADR-2607111600), **structural metal products (2511)** still
sat as a `:maturity :spec` placeholder with a dead
`gftdcojp/cloud-itonami-C2511` URL — no repo, no business model, no
actor. Ships and floating structures (3011) had already closed a
prior classic heavy-industry gap as its own governed actor (Shipyard
Advisor ⊣ Shipyard Governor), but has no dedicated promotion ADR of
its own.

## Decision

1. **`cloud-itonami-isic-2511`** — Structural Fabrication Advisor ⊣
   Structural Fabrication Governor (assembly intake,
   welding-procedure-qualification rules verify, NDE-inspection
   screen, dual actuation: dispatch-assembly +
   issue-fabrication-certificate, audit export).
2. Real-world regulatory basis: structural-steel fabricator
   certification / welding-procedure-qualification regimes — AISC 207
   + AWS D1.1 (US self-certification, AISC/AWS), EN 1090-1/-2 + CE
   marking under the Execution Class (EXC) system (adopted as BS EN
   1090 in GBR with UKCA alongside CE), and Japan's Building Center of
   Japan (BCJ) factory-grade certification (M/H/S grades) under JASS
   6. Facts catalog seeds JPN (BCJ) / USA (AISC/AWS) / GBR (BSI/UKCA)
   / DEU (DIN/EN 1090) only.
3. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2511` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2511` placeholder.
4. Pattern: clone of the 2410/2811/2910 (and, before them, 3011/3030)
   governed-actor fleet shape (langgraph, dual actuation, dedicated
   boolean double-guards, honest facts catalog) — sixth
   manufacturing-sector full actor overall, fifth in the same
   steel/engines/vehicles/ships cluster after 2410, 2811, 2910 and
   3011.

## Consequences

(+) Classic heavy-industry manufacturing cluster (steel /
engines-turbines / motor vehicles / structural-steel fabrication) now
has four implemented open business stacks alongside shipbuilding
(3011) and aerospace (3030).
(−) Physics / fab-shop digital-twin geometry remains out of scope
(export + operator console samples only).
(−) Facts catalog seeds four jurisdictions; not exhaustive
fabricator-certification-authority coverage.

## Verification

- `cloud-itonami-isic-2511`: `clojure -M:dev:test` green (39 tests /
  191 assertions), `clojure -M:lint` clean
- Industry: maturity `"2511"` → `:implemented` (118th implemented
  actor fleet-wide)
- GitHub public repo under `cloud-itonami/`
