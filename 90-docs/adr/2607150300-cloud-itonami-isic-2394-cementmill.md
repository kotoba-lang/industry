# ADR-2607150300: construction value-chain mid-tier gap 2394 (cement, lime and plaster) → `:implemented`

- Status: Accepted (2026-07-15)
- Related: ADR-2607142800 (robotics premise → concrete process simulation, fleet pattern; identifies isic-2394 as a follow-up gap), ADR-2607011000 (robotics premise + ISIC section coverage)

## Context

A 2026-07-14 value-chain survey of the construction industry found
raw-material extraction (`cloud-itonami-isic-0810`, Community Quarry
and Stone Supply) and building-construction execution
(`cloud-itonami-isic-4211`, Community Building Construction) both
implemented, but the building-materials-manufacturing tier in
between — ISIC 2394, manufacture of cement, lime and plaster — had
NO actor at all. Construction's raw quarried stone/limestone must
become cement before any building gets built, and this whole
mid-chain tier was a gap in an otherwise-covered value chain.
ADR-2607142800 already flagged isic-2394 by name as one of the
"parts/midstream/downstream gap actors identified in the 2026-07-14
value-chain survey" to build with its robotics-process-simulation
pattern from day one.

## Decision

1. **`cloud-itonami-isic-2394`** — Cement Mill Advisor ⊣ Kiln Governor
   (cement-batch intake, cement-quality-standard rules verify,
   kiln-emissions screen, robot quality-lab verification mission, dual
   actuation: ship-cement-batch + issue-mill-certificate, audit
   export).
2. Real-world regulatory basis: cement product standards — JIS R 5210
   (Japan, JISC / Japan Cement Association), ASTM C150/C150M (USA,
   ASTM International / Portland Cement Association), EN 197-1
   (Europe, CEN) as nationally adopted BS EN 197-1 (UK, BSI) and DIN EN
   197-1 (Germany, DIN). Facts catalog seeds JPN / USA / GBR / DEU
   only.
3. Adopts ADR-2607142800's robotics-process-simulation pattern from
   day one (not retrofitted): `cementmill.robotics` walks every batch
   through a three-step quality-lab mission (cube-specimen sampling,
   compressive-strength-test press, Blaine-fineness scan) via
   `kotoba.robotics` mission/action/telemetry-proof contracts, gated
   by a new governor HARD check requiring the mission on file AND
   independently re-deriving out-of-tolerance from the batch's own
   28-day compressive-strength fields — never trusting the mission's
   self-reported verdict alone.
4. `cement-batch-strength-out-of-range?` continues the fleet's
   two-sided range check family (after testlab / conservation / water
   / steelworks / turbine / automotive's `vehicle-emissions-out-of-
   range?`), applied here to a cement batch's own measured 28-day
   compressive strength against its own recorded acceptance-band
   bounds — the single most standard real cement QC/acceptance
   metric. Deliberately grounded in the SAME field family as the
   robotics-simulation check (unlike automotive's two DISTINCT fields
   — structural-deviation for the CAE mission, emissions-deviation for
   dispatch): both checks co-fire whenever a batch's strength is
   genuinely out of range, a documented departure, not a defect (see
   `cementmill.robotics`/`cementmill.governor` ns docstrings).
5. Double-actuation guards use dedicated booleans (`:batch-shipped?`,
   `:mill-certified?`), never a status lifecycle (ADR-2607071320 /
   6492 lesson).
6. Kiln-emissions unresolved is evaluated unconditionally so
   `:kiln-emissions/screen` itself can HARD-hold (the same discipline
   `automotive.governor/end-of-line-defect-unresolved-violations` and
   its own prior siblings established).
7. Pattern: clone of the automotive (`cloud-itonami-isic-2910`)
   governed-actor + robotics-process-simulation shape (langgraph, dual
   actuation, dedicated boolean double-guards, honest facts catalog,
   `kotoba.robotics` mission/action/telemetry-proof) — closing the
   construction value chain's mid-tier manufacturing gap: quarrying
   (0810) → cement milling (2394, this repo) → building construction
   (4211) is now a fully-implemented chain.

## Consequences

(+) The construction value chain has no remaining tier-level gap from
raw-material extraction through building erection.
(+) Reuses langgraph + store dual-backend parity without new physics.
(+) Robotics premise is exercised from day one via a concrete
governor-gated mission, not declared as a flag to retrofit later.
(−) No physical plant digital-twin tick in this repo (follow-up domain
data is out of scope here).
(−) Quality-standard-body coverage is a starting catalog (four
jurisdictions), not exhaustive.
(−) Robotics-simulation and ground-truth-range checks sharing one
field family means they always co-fire when a batch's strength is out
of range instead of exercising in isolation like automotive's two
distinct fields — a deliberate, documented trade-off (see
`cloud-itonami-isic-2394` docs/adr/0001).

## Verification

- `cloud-itonami-isic-2394`: `clojure -M:dev:test` green (45 tests /
  222 assertions), `clojure -M:lint` clean, `clojure -M:dev:run` demo
  narrative exercises seven HARD holds (fabricated jurisdiction,
  robotics-simulation-missing, cement-batch-strength-out-of-range,
  robotics-simulation-out-of-tolerance, kiln-emissions-unresolved,
  already-shipped, already-certified) with no exceptions.
- Industry: maturity `"2394"` → `:implemented` (registry entry to be
  landed separately against `kotoba-lang/industry`, a shared file with
  concurrent-agent write contention).
- GitHub public repo: `https://github.com/cloud-itonami/cloud-itonami-isic-2394`
