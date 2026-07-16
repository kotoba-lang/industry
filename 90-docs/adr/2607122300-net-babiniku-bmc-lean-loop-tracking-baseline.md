# ADR-2607122300: net-babiniku BMC / Lean Loop iteration tracking baseline (Iteration 0)

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki

## Context

As part of scaling BMC (Business Model Canvas) / Lean Loop (build-measure-learn)
iteration tracking across the portfolio, `net-babiniku` (`jk-luxury/net-babiniku`,
a live-3D-VRM-avatar AI-character chat app modeled on isekaizero.ai, sharing render
substrate with `network-isekai`) was investigated and found to have **no existing
BMC/Lean Loop tracking of any kind**:

- No `docs/business*.md`, no `docs/*lean*`/`*canvas*`/`*bmc*` files, no `.datoms.edn`
  canvas files, no ADR at the business-model-canvas level.
- The repo's only recurring iteration convention is an unrelated **"kaizen pass"**
  log (49+ rounds as of this writing) — a code-quality/UX/accessibility/bug-fix loop
  embedded in the README, not a business hypothesis loop.
- `net-babiniku` is **not** covered by the gftdcojp portfolio's shared BMC system
  (`70-tools/bmc/` in this superproject, base datoms in
  `90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`) — that system is scoped to
  the `gftdcojp` org's 11 registered products (see ADR-2607021500/2607021600 and this
  superproject's `bmc-business-operate-daily` routine). `net-babiniku` is a
  `jk-luxury`-org product (sibling to `club-shinshi`), outside that scope. Registering
  it into the shared gftdcojp datoms base is a separate, larger decision (base datoms
  are human-curated, "書き換え禁止" for routines) and is explicitly **not** what this
  ADR does.
- Technical maturity is substantial and real: 5 shipped milestones (VRM render path,
  monetization domain model with hard-hold safety gates, physical-embodiment gating
  via `kotoba-lang/giemon`, real LLM chat via the murakumo bridge, full UI/UX
  redesign), 49+ kaizen-pass commits, live production verification (real VRM
  rendering confirmed end-to-end, WCAG contrast checks run against production).
- Business maturity: monetization code exists (subscription/PPV/tip, 80/20 split,
  crypto-treasury rail configured in production per the README) but is **explicitly
  hard-held by design** — "every proposal HARD-holds, on purpose" — until a
  PSP/crypto rail is actually contracted. No confirmed revenue evidence found.

## Decision

- `orgs/jk-luxury/net-babiniku/docs/bmc-lean-loop-log.md` is the append-only BMC/Lean
  Loop iteration log for net-babiniku, seeded with **Iteration 0** (baseline
  stocktake, same pattern as `local-murakumo`'s tracking, ADR-2607121600) on
  2026-07-12.
- A short local-mirror pointer ADR is added in net-babiniku's own
  `90-docs/adr/0002-net-babiniku-bmc-lean-loop-tracking-baseline.md` (matching that
  repo's existing convention — `0001` mirrors this superproject's
  `2607051800-net-babiniku-vrm-vtuber-design.md` the same way).
- A dedicated daily cloud routine (`net-babiniku-lean-loop-daily`, same shape as
  `local-murakumo-lean-loop-daily`) advances the log by one iteration per day,
  grounded in real facts only — no fabricated metrics, unknowns marked explicitly.
- This ADR does **not** register net-babiniku into the shared gftdcojp `70-tools/bmc`
  system. If that integration is wanted later, it needs its own ADR (new base-datoms
  entry, human-reviewed) — out of scope here.

## Consequences

- (+) net-babiniku's business hypotheses become iteration-tracked and auditable,
  consistent with the pattern used across the portfolio.
- (+) No conflation with the unrelated "kaizen pass" UX/bugfix loop already running
  in that repo.
- (−) net-babiniku's business tracking remains isolated from the shared gftdcojp
  scoring/funnel system (`maturity-scores.md`, `gftd gate`) — it won't show up in
  cross-portfolio comparisons until/unless a separate registration decision is made.

## References

- `orgs/jk-luxury/net-babiniku/README.md`
- `orgs/jk-luxury/net-babiniku/90-docs/adr/0001-net-babiniku-architecture.md`
- `orgs/jk-luxury/net-babiniku/90-docs/adr/0002-net-babiniku-bmc-lean-loop-tracking-baseline.md`
  (local mirror of this ADR)
- `orgs/jk-luxury/net-babiniku/docs/bmc-lean-loop-log.md` (the log this ADR defines)
- `90-docs/adr/2607051800-net-babiniku-vrm-vtuber-design.md` (architecture decision)
- `90-docs/adr/2607121600-local-murakumo-bmc-lean-loop-tracking-baseline.md` (same
  pattern, prior precedent)
- `90-docs/adr/2607021500-portfolio-bmc-lean-canvas.md` / `70-tools/bmc/README.md`
  (the gftdcojp-scoped shared system this ADR deliberately does not join)
