# ADR-2607113000: classic heavy industry 2813 (pumps, compressors, taps and valves) → `:implemented`

- Status: Accepted (2026-07-10)
- Related: ADR-2607112400 (2824 mining/construction machinery), ADR-2607112300 (2822 metal-forming machinery and machine tools), ADR-2607111800 (2511 structural metal products), ADR-2607111600 (2910 motor vehicles), ADR-2607110500 (2410 steel + 2811 engines/turbines), ADR-2607080800 (3030 aerospace — first manufacturing vertical in the fleet)

## Context

Following the same-day promotion of **basic iron and steel (2410)**
and **engines/turbines (2811)** (ADR-2607110500), **motor vehicles
(2910)** (ADR-2607111600), **structural metal products (2511)**
(ADR-2607111800), **metal-forming machinery and machine tools
(2822)** (ADR-2607112300), and **machinery for mining, quarrying and
construction (2824)** (ADR-2607112400), **manufacture of other
pumps, compressors, taps and valves (2813)** still sat as a
`:maturity :spec` placeholder with a dead `gftdcojp/cloud-itonami-
C2813` URL — no repo, no business model, no actor. Ships and floating
structures (3011) had already closed a prior classic heavy-industry
gap as its own governed actor (Shipyard Advisor ⊣ Shipyard Governor),
but has no dedicated promotion ADR of its own. 2822 and 2824 opened
and continued the cluster's machine-tool/capital-equipment-and-
general-purpose-machinery sub-cluster, distinct from the transport-
equipment sub-cluster of 2811/2910/3011; this promotion continues
that sub-cluster with a third entry, pumps, compressors and valves
manufacturing.

## Decision

1. **`cloud-itonami-isic-2813`** — Pressure Equipment Advisor ⊣
   Pressure Equipment Governor (unit intake, design-rules verify,
   hydrostatic/pneumatic pressure-test screen, dual actuation:
   dispatch-unit + issue-pressure-test-certificate, audit export).
2. Real-world regulatory basis: pressure-equipment safety regimes —
   ASME BPVC Section VIII Div. 1 (UG-99 hydrostatic/pneumatic test) +
   ASME B31.3 (Process Piping) + API 610 (centrifugal pumps) + API
   674/675/676 (reciprocating/metering/rotary pumps) as the US
   baseline, the EU Pressure Equipment Directive 2014/68/EU (PED) +
   CE marking with EN 13445 (unfired pressure vessels) as the
   harmonized EU/DEU technical standard (adopted as BS EN 13445 in
   GBR alongside UKCA marking under the Pressure Systems Safety
   Regulations 2000 (PSSR)), and Japan's JIS B 8501/8265 series +
   高圧ガス保安法 (High Pressure Gas Safety Act, METI-adjacent).
   Facts catalog seeds JPN (METI/KHK/JIS) / USA (ASME/API) / GBR
   (HSE/PSSR) / DEU (PED/DIN) only.
3. Promote the registry entry `:spec` → `:implemented` with a real
   `cloud-itonami/cloud-itonami-isic-2813` URL, replacing the dead
   `gftdcojp/cloud-itonami-C2813` placeholder.
4. Pattern: clone of the 2822/2824 (and, before them, 2410/2811/
   2910/2511, 3011/3030) governed-actor fleet shape (langgraph, dual
   actuation, dedicated boolean double-guards, honest facts catalog)
   — the cluster's THIRD capital-equipment/general-purpose-machinery
   vertical, following 2822 and 2824, and the ninth manufacturing-
   sector full actor overall (eighth in the same steel/engines/
   vehicles/ships/structural-steel/machine-tools/mining-construction-
   machinery cluster after 2410, 2811, 2910, 3011, 2511, 2822 and
   2824).

## Consequences

(+) Classic heavy-industry manufacturing cluster (steel /
engines-turbines / motor vehicles / structural-steel fabrication /
machine tools / mining-construction machinery / pumps-compressors-
valves) now has seven implemented open business stacks alongside
shipbuilding (3011) and aerospace (3030).
(−) Physics / plant digital-twin geometry remains out of scope (export
+ operator console samples only).
(−) Facts catalog seeds four jurisdictions; not exhaustive
design-rules-authority coverage.

## Verification

- `cloud-itonami-isic-2813`: `clojure -M:dev:test` green (39 tests /
  191 assertions), `clojure -M:lint` clean
- Industry: maturity `"2813"` → `:implemented` (122nd implemented
  actor fleet-wide)
- GitHub public repo under `cloud-itonami/`
