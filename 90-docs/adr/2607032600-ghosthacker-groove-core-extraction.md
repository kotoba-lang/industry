# ADR-2607032600: ghosthacker-groove-core — extract FLOW's judgment engine into a shared repo ahead of HARMONY

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

ADR-2607023200's Addendum (2026-07-03) found that `ghosthacker-flow.core` had
grown into a general "stay synced to a four-on-the-floor beat" judgment engine
(beat-phase/window judging, difficulty presets, score/combo/groove-crossfade
state transitions, anti-mash guard, miss-on-silence detection, chart-based
multi-section support) with almost nothing FLOW-action-specific in it. That
Addendum fixed the *direction* — share, don't duplicate, when HARMONY starts —
but explicitly deferred the *mechanism* (extract now vs. duplicate-then-unify)
to "HARMONY着手PR".

This ADR is that PR's decision: **HARMONY is starting now** (Ghost Hacker
portfolio #2, ADR-2607023200 table row 2 — the flagship rhythm game, Ghost
Battle proper: sync precision to the beat is literally the win/lose state,
ASYMMETRY vs HARMONY). Its design brief ("四つ打ちに同期し続ける精度を競い、
乗れているほどTENSE→Sky Highへ曲が転調していく") is the *same* judgment/
crossfade model FLOW already implements, not a variant of it.

## Decision

Extract `ghosthacker-flow.core` into a new standalone repo,
**`com-junkawasaki/ghosthacker-groove-core`**, before writing any HARMONY-
specific code. This is option (a) from the Addendum, not option (b)
(duplicate-then-unify) — with a mature, tested core already sitting in one
repo and a second, concrete consumer (HARMONY) about to exist, extracting
first avoids ever letting two copies of this logic diverge, even briefly.

- **What moves**: `src/ghosthacker_flow/core.cljc` and
  `test/ghosthacker_flow/core_test.cljc`, renamed to
  `ghosthacker.groove.core` (the ns no longer belongs to FLOW specifically —
  `judge`, `apply-judgment`, `chart-beats`, `judge-chart-input`, etc. are
  host/game-agnostic). Namespace-only rename, zero behavior change.
- **What stays in `ghosthacker-flow`**: `demo.clj` and `terminal.clj` (FLOW's
  own host adapters — CLI demo, the playable terminal prototype), the
  network-isekai `logic.cljc` guest port under
  `games/gftd/ghosthacker-flow` (a from-scratch reimplementation in a
  different, restricted language anyway — not affected), and everything
  README/CHANGELOG that is FLOW-specific. `ghosthacker-flow` depends on
  `ghosthacker-groove-core` via a pinned `:git/url`/`:sha` dependency (both
  are standalone repos, not a monorepo — this mirrors how every other
  `kami-mangaka-*` sibling-repo dependency in this org is wired).
- **HARMONY** (`com-junkawasaki/ghosthacker-harmony`, new repo, this ADR's
  companion work) depends on the same shared core the same way. HARMONY-
  specific work is: the Ghost Battle framing (win/lose state derived from
  `grade`/`accuracy`/`groove` — ASYMMETRY when groove stays low / drops to 0,
  HARMONY when it sustains at the top), its own host adapter(s), and any
  richer chart/song content a flagship rhythm game needs that FLOW's simpler
  chart-beats didn't yet require.
- **Visibility**: private, `com-junkawasaki` org default (repos.edn
  `:orgs :visibility`), same as `ghosthacker-flow`.

## Consequences

**Positive**
- One tested judgment engine, one place bugs get fixed, both games benefit
  immediately (mirrors the value already realized this session by
  discovering + fixing a real bug in the *shared* `kami-engine-clj` compiler
  while porting FLOW to network-isekai — shared infra finds shared bugs).
- FLOW's own repo shrinks to what's actually FLOW: host adapters, not a
  judgment engine that happens to also serve another game.
- Establishes the pattern for titles #3-10 in the portfolio: any future game
  needing the same sync-to-a-beat mechanic (there is at least one more
  rhythm-adjacent entry, #8 DUET) reuses this core rather than re-deriving it.

**Negative / constraints (honest)**
- Two more repos to keep in sync (groove-core + harmony) versus one — a real,
  small ongoing coordination cost (dependency pin bumps when groove-core
  changes) that duplication would have avoided in the short term. Judged
  worth it given the alternative was letting a second consumer of hand-tuned
  timing/scoring math exist as a fork rather than a dependent.
- HARMONY itself is *not* implemented by this ADR — only the extraction the
  Addendum asked this PR to resolve. HARMONY's own design/build is separate,
  immediately-following work.
- `ghosthacker-flow`'s git history for `core.cljc` does not follow it to the
  new repo (a fresh file in a fresh repo, not a `git subtree split`) — judged
  acceptable for a young, small file (currently 38 tests / 133 assertions,
  a handful of commits) versus the complexity of a history-preserving split.

## References

- ADR-2607023200 (Ghost Hacker portfolio, FLOW design, the Addendum this ADR
  resolves)
- `com-junkawasaki/ghosthacker-flow` (FLOW; loses `core.cljc` to this
  extraction)
- `com-junkawasaki/ghosthacker-groove-core` (new, this ADR)
- `com-junkawasaki/ghosthacker-harmony` (new, companion work immediately
  following this ADR)
