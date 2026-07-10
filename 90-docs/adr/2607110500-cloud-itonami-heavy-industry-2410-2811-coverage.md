# ADR-2607110500: classic heavy industry 2410 (steel) + 2811 (engines/turbines) → `:implemented`

- Status: Accepted (2026-07-10)
- Related: ADR-2607105200 (3011 shipbuilding), ADR-2607080800 (3030 aerospace)

## Context

After shipbuilding (3011) closed the first classic heavy-industry gap,
**basic iron and steel (2410)** and **engines/turbines (2811)** still sat
as `:maturity :spec` placeholders with dead `gftdcojp/cloud-itonami-C####`
URLs — no repo, no business model, no actor.

## Decision

1. **`cloud-itonami-isic-2811`** — Turbine Advisor ⊣ Turbine Governor
   (unit fabrication, type-rules, NDT, type-evidence, audit export).
2. **`cloud-itonami-isic-2410`** — Steelworks Advisor ⊣ Steelworks Governor
   (heat fabrication, mill-rules, quality screen, mill-cert, audit export).
3. Promote both registry entries `:spec` → `:implemented` with real
   `cloud-itonami/cloud-itonami-isic-####` URLs.
4. Pattern: clone of 3011/3030 governed-actor fleet shape (langgraph,
   dual actuation, dedicated boolean double-guards, honest facts catalog).

## Consequences

(+) Classic heavy-industry manufacturing triad (steel / engines-turbines /
ships) now has implemented open business stacks.
(−) Physics / plant digital-twin geometry remains out of scope (export +
operator console samples only).
(−) Facts catalogs seed four jurisdictions each; not exhaustive mill or
type-approval coverage.

## Verification

- Each child: `clojure -M:dev:test` green (39 tests / 191 assertions each)
- Industry: maturity `"2410"` / `"2811"` → `:implemented`
- GitHub public repos under `cloud-itonami/`
