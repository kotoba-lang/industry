# ADR-2607124500: bmc-business-operate-daily — product-list completeness + bb→nbb command fix

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki

## Context

While designing BMC/Lean Loop tracking for `cloud-itonami`, `cloud-manimani`,
`cloud-murakumo`, `network-isekai`, `net-babiniku`, `club-shinshi`,
`ai-gftd-yukkuri`, `net-kotobase`, `app-aozora` (owner request, 2026-07-12),
investigation found that a production system for exactly this purpose
**already exists and was already running**: `70-tools/bmc/` (ADR-2607021600/
1700/1800/2100/2200) plus two claude.ai cloud routines —
`itonami-react-growth-hourly` (cloud-itonami only) and
`bmc-business-operate-daily` (the shared 8-product loop).

Two problems were found in the deployed `bmc-business-operate-daily` routine
(`trig_01VmX7RyxQzBVJeYRZMTuku5`), neither previously ADR'd:

1. **Product-list gap.** `network-isekai`, `ai-gftd-yukkuri`, and
   `club-shinshi` were already fully registered in the shared system — real
   canvas (9 blocks), a riskiest hypothesis, and `90-docs/business/metrics/
   <product>.edn` all existed for all three (confirmed via `gftd products`,
   `gftd canvas show --product <p>`, and `maturity-scores.md`, which already
   scored all three as of 2026-07-02). But the routine's hardcoded
   `--product` loop list only names the original 8
   (`90-docs/adr/2607021500-...`'s original set), so `react loop` — the step
   that actually evolves canvas content from observed metrics — silently
   skipped all three every run. `collect`/`funnel analyze --all`/`canvas md
   --all`/`score md` are `--all`-scoped and were unaffected; only the
   per-product `react loop` step had the gap.
2. **Stale babashka(bb) references.** The routine's prompt (authored
   2026-07-02) invokes `bb 70-tools/bmc/collect.bb`, bare
   `70-tools/bmc/bin/gftd`, and `bb 70-tools/bmc/run-tests.bb`. This toolchain
   was ported from babashka to nbb on 2026-07-10 (commit `b073ea7da12`,
   "8 CLI binaries were dead under nbb") — `.bb` files no longer exist; the
   real files are `70-tools/bmc/collect.cljs`, `70-tools/bmc/bin/gftd.cljs`,
   `70-tools/bmc/run-tests.cljs`, invoked as `nbb <path>.cljs`
   (`70-tools/bmc/README.md` already documents the correct form). The routine
   kept succeeding for ~48+ hours of hourly/daily runs anyway — the CCR agent
   running it is intelligent enough to hit "command not found," discover the
   real `.cljs` files, and self-correct each time — but this wastes tokens
   rediscovering the same fix every run and is fragile (a future agent might
   not recover as cleanly).

Also found: `70-tools/bmc/` and `90-docs/business/` are **excluded from this
superproject's default sparse-checkout** — they exist in git history/on
origin but are invisible in a normal `com-junkawasaki/root` clone/pull until
explicitly added (`git sparse-checkout add 70-tools/bmc 70-tools/scripts
scripts 90-docs/business 90-docs/adr`). This is why the gap above went
unnoticed locally — `find`/`ls` against a default checkout report these
paths as simply not existing.

## Decision

- Updated `bmc-business-operate-daily` (`trig_01VmX7RyxQzBVJeYRZMTuku5`) via
  the claude.ai routines API:
  - `--product` loop list: 8 → 11 (added `network-isekai`, `ai-gftd-yukkuri`,
    `club-shinshi`).
  - All `bb`/`.bb` command references corrected to their `nbb`/`.cljs`
    equivalents.
  - Commit-message template's `loops=8` → `loops=11`.
- Verified via `RemoteTrigger run` (session `cse_017MTT1aFdUFxd5yByWjG4Na`):
  produced PR #389 (`routine(bmc): 運転 2026-07-12 — collect=done, loops=11,
  funnel=11`) with all 11 products participating cleanly (0 governor
  rejections other than expected dedup), `run-tests.cljs` green, base datoms
  and `maturity-facts.edn` untouched, `club-shinshi`'s separate repo-local
  kaizen loop (`orgs/jk-luxury/club-shinshi/60-apps/ai-gftd-project-shinshi/
  docs/260613-*.datoms.edn`, its own ADR-2606131400, distinct from the
  `90-docs/business/club-shinshi-*` canvas this routine touches) left
  untouched, as intended.
- `itonami-react-growth-hourly` (cloud-itonami only, unaffected by the
  product-list gap since cloud-itonami was already in scope) has the
  identical stale bb/nbb wording but was **not** touched by this ADR — it
  keeps succeeding via the same live self-correction, and touching a second
  already-working hourly production routine was judged out of scope for this
  pass. Flagged here as a known follow-up if anyone wants to clean it up.
- Did **not** register any new product into the base datoms
  (`90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`) — the fix was
  purely "the routine now loops over products the base datoms already knew
  about."

## Consequences

- (+) `network-isekai`/`ai-gftd-yukkuri`/`club-shinshi` now get the same
  daily canvas-evolution treatment as the other 8 gftdcojp products.
- (+) The routine's own instructions are now internally consistent with the
  actual toolchain, removing a recurring silent-recovery tax on every run.
- (+) Documents, for the first time, that `70-tools/bmc/` + these cloud
  routines are a **live production system**, not a proposed design — prior
  ADRs (2607021600/1700/1800/2100/2200) described the design and an initial
  "as-of 2026-07-02" score snapshot, but nothing previously recorded that it
  had since been deployed as recurring cloud routines and was actively
  running.
- (−) `itonami-react-growth-hourly`'s identical staleness remains
  un-fixed (follow-up).
- (−) `net-babiniku` remains intentionally outside this shared system (see
  `90-docs/adr/2607122300-net-babiniku-bmc-lean-loop-tracking-baseline.md`)
  — different org, different scope, tracked standalone.

## References

- `70-tools/bmc/README.md`, `90-docs/adr/2607021600-portfolio-bmc-cli-react-loop.md`,
  `90-docs/adr/2607021700-portfolio-maturity-scoring.md`,
  `90-docs/adr/2607021800-bmc-collect-real-metrics.md`,
  `90-docs/adr/2607022100-bmc-gate-evaluator-llm-advisor.md`,
  `90-docs/adr/2607022200-per-product-gate-instruments.md`
- `90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn` (base datoms, unchanged)
- `90-docs/adr/2607121600-local-murakumo-bmc-lean-loop-tracking-baseline.md`,
  `90-docs/adr/2607122300-net-babiniku-bmc-lean-loop-tracking-baseline.md`
  (standalone-tracking precedent for products outside this shared system)
- PR #389 (`com-junkawasaki/root`), commit `b073ea7da12` (bb→nbb port)
- CLAUDE.md「`.cljc` / `.kotoba` ランタイム優先順位」節（nbb 優先の背景）
