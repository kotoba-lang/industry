# ADR-2607113200: classic heavy industry 2816 (lifting and handling equipment) → `:implemented`

- Status: Accepted (2026-07-10)
- Related: ADR-2607113000 (2813 pumps, compressors, taps and valves), ADR-2607112400 (2824 mining/construction machinery), ADR-2607112300 (2822 metal-forming machinery and machine tools), ADR-2607111800 (2511 structural metal products), ADR-2607111600 (2910 motor vehicles), ADR-2607110500 (2410 steel + 2811 engines/turbines), ADR-2607080800 (3030 aerospace — first manufacturing vertical in the fleet)

## Context

Following the same-day promotion of **basic iron and steel (2410)**
and **engines/turbines (2811)** (ADR-2607110500), **motor vehicles
(2910)** (ADR-2607111600), **structural metal products (2511)**
(ADR-2607111800), **metal-forming machinery and machine tools
(2822)** (ADR-2607112300), **machinery for mining, quarrying and
construction (2824)** (ADR-2607112400), and **pumps, compressors,
taps and valves (2813)** (ADR-2607113000), **manufacture of lifting
and handling equipment (2816)** still sat as a `:maturity :spec`
placeholder with a dead `gftdcojp/cloud-itonami-C2816` URL — no
repo, no business model, no actor. Ships and floating structures
(3011) had already closed a prior classic heavy-industry gap as its
own governed actor (Shipyard Advisor ⊣ Shipyard Governor), but has
no dedicated promotion ADR of its own. 2822, 2824 and 2813 opened
and continued the cluster's machine-tool/capital-equipment-and-
general-purpose-machinery sub-cluster, distinct from the transport-
equipment sub-cluster of 2811/2910/3011; this promotion continues
that sub-cluster with a fourth entry, lifting and handling equipment
(cranes, hoists, forklifts) manufacturing.

## Decision

1. **`cloud-itonami-isic-2816`** — Lifting Equipment Advisor ⊣
   Lifting Equipment Governor (unit intake, design-rules verify,
   proof-load-test screen, dual actuation: dispatch-unit + issue-
   load-test-certificate, audit export).
2. Real-world regulatory basis: lifting-equipment safety regimes —
   the ASME B30 series (Safety Standards for Cableways, Cranes,
   Derricks, Hoists, Hooks, Jacks, and Slings) + OSHA 29 CFR
   1926.1400 (Cranes and Derricks in Construction) as the US
   baseline, the EU Machinery Directive 2006/42/EC (CE marking) with
   EN 13001 (crane safety, general design) as the harmonized EU/DEU
   technical standard (adopted as BS EN 13001 in GBR alongside UKCA
   marking under the Lifting Operations and Lifting Equipment
   Regulations 1998 (LOLER)), and Japan's JIS B 8821/B 8830 series +
   労働安全衛生法 (Industrial Safety and Health Act) クレーン等安全
   規則, MHLW/METI-adjacent. Facts catalog seeds JPN (MHLW/METI/JIS)
   / USA (ASME/OSHA) / GBR (HSE/LOLER) / DEU (Machinery
   Directive/DIN) only.
3. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2816` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2816` placeholder.
4. Pattern: clone of the 2822/2824/2813 (and, before them, 2410/
   2811/2910/2511, 3011/3030) governed-actor fleet shape (langgraph,
   dual actuation, dedicated boolean double-guards, honest facts
   catalog) — the cluster's FOURTH capital-equipment/general-purpose-
   machinery vertical, following 2822, 2824 and 2813, and the tenth
   manufacturing-sector full actor overall (ninth in the same
   steel/engines/vehicles/ships/structural-steel/machine-tools/
   mining-construction-machinery/pumps-valves cluster after 2410,
   2811, 2910, 3011, 2511, 2822, 2824 and 2813).

## Consequences

(+) Classic heavy-industry manufacturing cluster (steel /
engines-turbines / motor vehicles / structural-steel fabrication /
machine tools / mining-construction machinery / pumps-compressors-
valves / lifting-handling equipment) now has eight implemented open
business stacks alongside shipbuilding (3011) and aerospace (3030).
(−) Physics / plant digital-twin geometry remains out of scope (export
+ operator console samples only).
(−) Facts catalog seeds four jurisdictions; not exhaustive
design-rules-authority coverage.

## Verification

- `cloud-itonami-isic-2816`: `clojure -M:dev:test` green (39 tests /
  191 assertions), `clojure -M:lint` clean
- Industry: maturity `"2816"` → `:implemented` (124th implemented
  actor fleet-wide)
- GitHub public repo under `cloud-itonami/`
