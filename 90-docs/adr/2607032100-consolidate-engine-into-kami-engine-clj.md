# ADR 2607032100: consolidate kotoba-lang/engine into kami-engine/kami-engine-clj

## Status
Accepted

## Context

The user flagged that "engine" alone doesn't read as a game engine and asked
to organize it "as kami-engine." The literal name collides with the existing
`orgs/kotoba-lang/kami-engine` repo (the original clj-wgsl-migration
asset/contract home). Clarified via AskUserQuestion: consolidate only the
Rust-free piece into `kami-engine` as a subdirectory; leave `kami-clj-play`
and `kami-script-runtime-rs` (both real Rust) as separate standalone repos,
since `kami-engine`'s CI enforces a repo-wide no-Rust guard — the same
invariant that made those two repos need to be standalone in the first
place.

`kotoba-lang/engine` (this session's ADR-2607031700 CLJ→WASM compiler)
contains zero Rust source, confirmed by inspection and by its landing PR
passing the no-rust guard check unmodified. `kami-engine` already hosts a mix
of real `kami-*-clj` CLJC subdirectories and thin fixture-only directories
sharing a name with a since-extracted crate (`kami-script-runtime/` inside
`kami-engine` holds only a conformance-test fixture, not the real
implementation) — no `kami-engine-clj/` path existed yet, so this lands
clean. The compiler's own pre-migration name, per `kami-engine`'s historical
crate table, was literally `kami-engine-clj` — consolidating under that name
restores the identity the clj-wgsl-migration extraction had temporarily
displaced.

## Decision

### New home: `kami-engine/kami-engine-clj/`

Plain copy of `kotoba-lang/engine`'s full content at its final commit
`e8bb9afc7fd85f8ae044026f5a20c986d0bae42c` (not history-preserving, matching
this session's established precedent for repo moves). Provenance and the
source commit SHA recorded in `kami-engine-clj/PROVENANCE.md`. Verified in
the new location: `clojure -M:test` → 51 tests / 213 assertions, 0 failures
— identical to the standalone repo, confirming the move changed nothing
about the code. Landed via `orgs/kotoba-lang/kami-engine` PR #92 (CI green,
including the no-rust guard check), merged.

### Old repo: archived, not deleted

`kotoba-lang/engine`'s `README.md` was updated to point at the new location,
then the repo was archived via the GitHub API (`archived=true`) — read-only,
fully reversible, preserves history including PR #1/#2 for citation.
`kami-clj-play` and `kami-script-runtime-rs` are unaffected: both contain
real Rust and correctly remain separate standalone repos, respecting
`kami-engine`'s no-Rust CI guard. Neither was moved; `kami-script-runtime-rs`
only got a doc-link update pointing at the new compiler location.

### Manifest cleanup

Removed the `"orgs/kotoba-lang/engine"` entry from `manifest/repos.edn`
(commit `1619e2d52c4a546da97f7f6ec73ab1f2c19290d3`) and the corresponding
`engine` project block from `manifest/west.yml` (commit
`ed2f00acfd4382a2bfc45e900bde9ba1c4dfdcbf`), both via the established
GitHub Contents API single-entry method. `kami-engine`'s own existing
manifest registration needed no change — this consolidation only adds a
subdirectory to its existing tree, not a new top-level project.

### Dependent documentation updated

- `kotoba-lang/kami-script-runtime-rs/README.md` — forward-pointing intro
  reference updated; historical PR #1/#2 links left as accurate citations
  (still viewable on the archived repo).
- `kotoba-lang/kami-genre-base-systems/README.md` — forward-pointing
  verification-provenance reference updated; historical "finding from this
  pass" narrative left as-is (it describes what was true when discovered,
  not where to go today).
- `gftdcojp/isekai-network/README.md` — the "WASM compile status" section
  had gone stale (it predated this session's compiler discovery, build, and
  execution-verification work entirely, still describing "no compiler
  exists yet"); rewritten to reflect the resolved state and the new
  `kami-engine-clj` location. The "Not yet done" section's `kami-clj-play`
  wiring note was similarly outdated and corrected.

## Consequences

- The compiler is once again named `kami-engine-clj`, matching its
  pre-migration identity and removing the "engine" vs "kami-engine" vs
  "kotoba-lang/engine" naming confusion the user flagged.
- `kami-engine`'s CI-enforced Rust-free invariant is preserved — this
  consolidation only works because the compiler is genuinely Rust-free;
  `kami-clj-play`/`kami-script-runtime-rs`'s continued separation isn't
  incidental, it's required by that same invariant.
- Zero broken integrations: sequencing (copy + verify, then update
  dependents, then archive the old repo last) meant no window where a
  dependent pointed at a dead location.
- Reversible if wrong: archiving (not deleting) means this can be cleanly
  reverted — unarchive, revert the `kami-engine` PR, re-register in
  manifest — without data loss.

## Alternatives Considered

1. **Rename `kotoba-lang/engine` to `kami-engine` (literal)**: rejected —
   collides with the existing `kotoba-lang/kami-engine` repo.
2. **Consolidate everything, including the real-Rust `kami-clj-play` and
   `kami-script-runtime-rs`, into `kami-engine`**: rejected — would require
   relaxing/removing `kami-engine`'s CI-enforced no-Rust guard, reversing a
   deliberate migration decision (ADR-2607010930); the user explicitly
   declined this via AskUserQuestion in favour of the Rust-free-only option.
3. **Rename only (GitHub repo rename `kotoba-lang/engine` →
   `kotoba-lang/kami-engine-clj`), without physically moving content**:
   rejected — would leave the compiler as a fourth top-level `kotoba-lang`
   repo alongside `kami-engine`/`kami-clj-play`/`kami-script-runtime-rs`
   rather than actually organizing it *under* `kami-engine` as asked; only
   relabels the problem instead of resolving where it belongs.

## References

- ADR-2607010930 (clj-wgsl migration — the extraction this consolidation
  partially reverses, and the source of `kami-engine`'s no-Rust guard)
- ADR-2607031700 (kotoba-lang/engine's WASM emission and host runtime — the
  work this ADR relocates)
- ADR-2607031800 (kami-genre-base-systems — a dependent whose docs were
  updated here)
- `orgs/kotoba-lang/kami-engine` PR #92 (merged)
- `orgs/kotoba-lang/engine` (archived)
