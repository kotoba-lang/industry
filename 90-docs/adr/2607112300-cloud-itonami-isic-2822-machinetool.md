# ADR-2607112300: classic heavy industry 2822 (machine tools) → `:implemented`

- Status: Accepted (2026-07-10)
- Related: ADR-2607111800 (2511 structural metal products), ADR-2607111600 (2910 motor vehicles), ADR-2607110500 (2410 steel + 2811 engines/turbines), ADR-2607080800 (3030 aerospace — first manufacturing vertical in the fleet)

## Context

Following the same-day promotion of **basic iron and steel (2410)**
and **engines/turbines (2811)** (ADR-2607110500), **motor vehicles
(2910)** (ADR-2607111600), and **structural metal products (2511)**
(ADR-2607111800), **manufacture of metal-forming machinery and
machine tools (2822)** still sat as a `:maturity :spec` placeholder
with a dead `gftdcojp/cloud-itonami-C2822` URL — no repo, no business
model, no actor. Ships and floating structures (3011) had already
closed a prior classic heavy-industry gap as its own governed actor
(Shipyard Advisor ⊣ Shipyard Governor), but has no dedicated
promotion ADR of its own.

## Decision

1. **`cloud-itonami-isic-2822`** — Machine Tool Advisor ⊣ Machine
   Tool Governor (unit intake, design-rules verify, ISO 230
   geometric/positioning accuracy-test screen, dual actuation:
   dispatch-unit + issue-accuracy-certificate, audit export).
2. Real-world regulatory basis: machine-tool accuracy-testing and
   conformity-marking regimes — ISO 230 series (test code for machine
   tools, geometric/positioning accuracy; the shared international
   baseline all four jurisdictions' national standards reference), EU
   Machinery Directive 2006/42/EC (moving to Machinery Regulation
   (EU) 2023/1230) + CE marking with harmonized standards EN ISO
   16090 / EN 12417 (DEU), JIS B 6201/B 6336 (JPN, METI/JMTBA/JIS),
   ANSI/ASME B5 series + OSHA 29 CFR 1910.212 machine guarding (USA),
   and UKCA under the Supply of Machinery (Safety) Regulations 2008
   (GBR, HSE). Facts catalog seeds JPN / USA / GBR / DEU only.
3. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2822` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2822` placeholder.
4. Pattern: clone of the 2410/2811/2910/2511 (and, before them,
   3011/3030) governed-actor fleet shape (langgraph, dual actuation,
   dedicated boolean double-guards, honest facts catalog) — the
   cluster's FIRST machine-tool/capital-equipment vertical (the
   machines that make other machines), distinct from the transport-
   equipment sub-cluster (2811/2910/3011).

## Consequences

(+) Classic heavy-industry manufacturing cluster (steel /
engines-turbines / motor vehicles / structural-steel fabrication /
machine tools) now has five implemented open business stacks
alongside shipbuilding (3011) and aerospace (3030).
(−) Physics / fab-shop digital-twin geometry remains out of scope
(export + operator console samples only).
(−) Facts catalog seeds four jurisdictions; not exhaustive
design-conformity-authority coverage.

## Verification

- `cloud-itonami-isic-2822`: `clojure -M:dev:test` green (39 tests /
  191 assertions), `clojure -M:lint` clean
- Industry: maturity `"2822"` → `:implemented` (119th implemented
  actor fleet-wide)
- GitHub public repo under `cloud-itonami/`
