# ADR-2607112400: classic heavy industry 2824 (mining/construction machinery) → `:implemented`

- Status: Accepted (2026-07-10)
- Related: ADR-2607112300 (2822 metal-forming machinery and machine tools), ADR-2607111800 (2511 structural metal products), ADR-2607111600 (2910 motor vehicles), ADR-2607110500 (2410 steel + 2811 engines/turbines), ADR-2607080800 (3030 aerospace — first manufacturing vertical in the fleet)

## Context

Following the same-day promotion of **basic iron and steel (2410)** and
**engines/turbines (2811)** (ADR-2607110500), **motor vehicles (2910)**
(ADR-2607111600), **structural metal products (2511)**
(ADR-2607111800), and **metal-forming machinery and machine tools
(2822)** (ADR-2607112300), **machinery for mining, quarrying and
construction (2824)** still sat as a `:maturity :spec` placeholder
with a dead `gftdcojp/cloud-itonami-C2824` URL — no repo, no business
model, no actor. Ships and floating structures (3011) had already
closed a prior classic heavy-industry gap as its own governed actor
(Shipyard Advisor ⊣ Shipyard Governor), but has no dedicated promotion
ADR of its own. 2822 opened the cluster's first machine-tool/
capital-equipment vertical, distinct from the transport-equipment
sub-cluster of 2811/2910/3011; this promotion continues that
capital-equipment sub-cluster with a second entry, mining/quarrying/
construction machinery.

## Decision

1. **`cloud-itonami-isic-2824`** — Heavy Equipment Advisor ⊣ Heavy
   Equipment Governor (unit intake, machinery design-rules verify,
   stability/braking-test screen, dual actuation: dispatch-unit +
   issue-stability-certificate, audit export).
2. Real-world regulatory basis: earth-moving-machinery safety regimes
   — EU Machinery Directive 2006/42/EC (soon Machinery Regulation (EU)
   2023/1230) + CE marking with the EN 474 series (Earthmoving
   machinery — Safety) harmonized standard (adopted as BS EN 474 in
   GBR with UKCA alongside CE), ANSI/SAE J1040 ROPS certification +
   OSHA 29 CFR 1926 construction safety (US self-certification), and
   Japan's JIS A 8403/A 8411 construction-machinery standards
   (METI/MLIT-adjacent). ISO 3450 (braking-systems performance) and
   ISO 10262 (hydraulic-excavator stability) form the
   internationally-shared technical baseline referenced across all
   four jurisdictions. Facts catalog seeds JPN (METI/MLIT/JIS) / USA
   (OSHA/SAE) / GBR (HSE/UKCA) / DEU (DGUV/DIN) only.
3. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2824` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2824` placeholder.
4. Pattern: clone of the 2410/2811/2910/2511/2822 (and, before them,
   3011/3030) governed-actor fleet shape (langgraph, dual actuation,
   dedicated boolean double-guards, honest facts catalog) — the
   cluster's SECOND machine-tool/capital-equipment vertical, following
   2822, and the eighth manufacturing-sector full actor overall
   (seventh in the same steel/engines/vehicles/ships/structural-steel/
   machine-tools cluster after 2410, 2811, 2910, 3011, 2511 and 2822).

## Consequences

(+) Classic heavy-industry manufacturing cluster (steel /
engines-turbines / motor vehicles / structural-steel fabrication /
machine tools / mining-construction machinery) now has six implemented
open business stacks alongside shipbuilding (3011) and aerospace
(3030).
(−) Physics / plant digital-twin geometry remains out of scope (export
+ operator console samples only).
(−) Facts catalog seeds four jurisdictions; not exhaustive
design-rules-authority coverage.

## Verification

- `cloud-itonami-isic-2824`: `clojure -M:dev:test` green (39 tests /
  191 assertions), `clojure -M:lint` clean
- Industry: maturity `"2824"` → `:implemented` (120th implemented
  actor fleet-wide)
- GitHub public repo under `cloud-itonami/`
