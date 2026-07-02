# ADR-2607022900: kotodama-host stays standalone (`:component-host`) — retires the "merge into kototama" plan

**Status**: accepted
**Date**: 2026-07-02
**Closes**: ADR-2607012200 §Step-8 (the umbrella kotoba-lang TS→CLJC refactor's
last open item)

## Context

ADR-2607012200 (the umbrella kotoba-lang TS→CLJC refactor) scoped Step 8 as:

> `kotodama-host` — TS+Rust host (Cargo workspace `hosts/*` +
> `sdk/kotodama-host-sdk/` TS). ... Own ADR: decide what merges where (Rust
> host crates vs CLJC SDK), then fold `kotodama-host` content into `kototama`
> and retire the `kotodama-host` west entry.

The CLJC-migration/TS-deletion half of Step 8 landed cleanly: `kotoba-lang/kotodama-host`
has zero `*.ts`/`*.rs`/`Cargo.toml`/`package.json` anywhere in its tree, and
carries its own CI guard (`.github/workflows/no-rust.yml`) that fails the
build if a Rust file reappears. `src/kotodama/host_contract.cljc` uses real
`#?(:clj … :cljs …)` reader conditionals, and `test/kotodama/host_contract_test.cljc`
has a dedicated `component-host-boundary` test — `:component-host` is a
tested architectural boundary, not just a name.

But the "merge into `kototama`" half was never validated against what
`kototama` actually is, and turns out not to hold up:

- **`kotodama-host`'s own originating design ADR, ADR-2607010000
  (§`kotodama-host`)**, never said "merge." It said the host runtime should
  be split into "Kotodama actor protocol の **Wasm component host**" — i.e.
  exactly what shipped. The umbrella ADR-2607012200's "merge into kototama"
  phrase was inaccurate shorthand written before that design was checked
  against the earlier, more specific ADR.
- There are, confusingly, **three separate "koto(d/t)ama"-named things**,
  and none is a natural merge target for `kotodama-host`:
  1. `kotoba-lang/kotodama-host` (this repo) — the Wasm component-host
     boundary for the Kotodama actor protocol. README explicitly scopes
     itself narrowly: "It does not own the portable inference runtime.
     Inference lives in `kotoba-lang/inference`."
  2. `kotoba-lang/kotodama` — a *different* repo: the generic
     functional-organism runtime, part of the `kotodama`/`-cells`/`-host`/
     `-mcp`/`-holochain`/`-py` family. This is `kotodama-host`'s real
     sibling by name, and it's already a separate, correctly-registered
     west entry — no merge needed, they already coexist as designed.
  3. `com-junkawasaki/kototama` — per the newest, explicitly-authoritative
     **ADR-2607022400** (`kototama-unikernel-tender-runtime-vocabulary`,
     supersedes `aiueos` ADR-0001), `kototama` is the **Wasm execution
     runtime ("tender")** layered under `aiueos`, serving the UNSPSC
     organism fleet (etzhayyim lineage) via a Charter/leash/gates
     vocabulary. This is architecturally unrelated to the Kotodama actor
     protocol's component-host boundary — the name similarity
     ("kotodama"/"kototama") is coincidental, not a shared concern.
  4. `kotoba-lang/kototama-clj` — a stale duplicate. Its own upstream
     ADR-0003 (in `com-junkawasaki/kototama`) already says it was archived
     and consolidated into `com-junkawasaki/kototama`; the west manifest
     still has drift here (tracked separately, not part of this ADR).
  5. `kotoba-lang/kototama-cljc-contract` — an empty placeholder repo (zero
     commits), not relevant to this decision.

Given `kotodama-host` (Kotodama actor protocol component-host) and
`kototama` (unikernel Wasm tender for the UNSPSC organism fleet) solve
genuinely different problems for genuinely different actor families, forcing
a merge would conflate two unrelated boundaries for no benefit beyond
resolving an inaccurate umbrella-ADR phrase.

## Decision

Retire the "merge into `kototama`" plan. Ratify the standalone
`:component-host` architecture — that is what `kotodama-host`'s own design
ADR (2607010000) specified, what actually shipped, and what its README
documents — as the permanent end-state. `kotodama-host` keeps its own repo
and its own `west.yml` entry; nothing merges, nothing retires.

## Consequences

- ADR-2607012200 §Step-8 is now closed: all 8 repos in that umbrella refactor
  have landed, with no remaining plan conflicts.
- No repo content moves. `kotodama-host`, `kotodama`, and `kototama` all
  keep their current, separate west registrations.
- The `kototama-clj` drift (stale duplicate per its own upstream ADR-0003,
  plus the separate `com-junkawasaki/kototama` → `kotoba-lang/kototama`
  org-transfer path-override drift) remains open but is unrelated to this
  decision — tracked as its own follow-up, not gated on this ADR.
