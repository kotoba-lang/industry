# ADR-2607060100: kotoba-lang — reverse-domain external-spec rename, batch 2 (atproto/ipld/bpmn/cmmn/dmn/ical/mcp/multiformats/vrm)

**Status**: accepted — landed (2026-07-06)
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/{atproto,ipld,bpmn,cmmn,dmn,ical,mcp,multiformats,vrm}`, their
dependent repos' `deps.edn`, and a manifest hygiene cleanup (3 stale entries)

## Context

Following ADR-2607052300 (`turn`/`rt`/`net`/`signal` reverse-domain rename), a follow-up
audit was requested: which other `kotoba-lang` repos have bare, generic names that plausibly
port a specific, named external spec but aren't yet renamed to the `org-<body>-<spec>` /
`io-<domain>` form?

A background research agent scanned all ~1200 non-compat-catalog `kotoba-lang` entries in
`manifest/west.yml`, read each plausible candidate's actual README/ADR content (not just
pattern-matching the name — the same discipline ADR-2607052300 applied to `rt`/`net`, which
turned out NOT to be full spec ports on closer reading), and reported 12 candidates at
varying confidence plus a manifest hygiene issue (3 stale duplicate entries). The owner
selected the 9 high-confidence candidates with verified spec conformance (5 with no
dependents, 4 with real dependents) and the hygiene cleanup; the 3 medium-confidence
candidates (`json`, `dag-cbor`, `openapi` — weaker fit, e.g. `json`'s README never cites RFC
8259 by name, `dag-cbor` is an explicitly partial RFC 8949 profile, and the OpenAPI
Initiative isn't a `w3`/`ietf`-style formal body) were deliberately **not** renamed this
round.

## Decision

| Old name | New name | Verified spec | Dependents |
|---|---|---|---|
| `atproto` | `org-bluesky-atproto` | AT Protocol (Bluesky) | none |
| `ipld` | `io-ipld` | IPLD DAG-CBOR (tag-42 link discipline) | arrangement, prolly-tree, chain (formerly commit-dag), kotoba-client |
| `bpmn` | `org-omg-bpmn` | BPMN 2.0 (OMG) | none |
| `cmmn` | `org-omg-cmmn` | CMMN 1.1 (OMG) | none |
| `dmn` | `org-omg-dmn` | DMN 1.4 (OMG) | none |
| `ical` | `org-ietf-ical` | iCalendar, RFC 5545 (IETF) | none |
| `mcp` | `org-anthropic-mcp` | Model Context Protocol (Anthropic) | kotodama-mcp |
| `multiformats` | `io-multiformats` | multiformats (multihash/CID), verified byte-identical to real go-ipfs/kubo output | prolly-tree, kami-nv-compat, checkpointer, io-ipld (itself) |
| `vrm` | `org-vrmc-vrm` | VRM 1.0 (VRM Consortium) | network-isekai, net-babiniku, kami-gen-procedural, kami-gen-ml3d, dance, kami-app-character-creator, kami-gen-hybrid |

Each was verified genuine (not overclaiming, per ADR-2607052300's own caution) before
renaming — e.g. `bpmn`/`cmmn`/`dmn` each ship a real graph/plan-item/decision-table model
plus an interpreter (token-walker / sentry-driven / hit-policy evaluator), not just types;
`vrm`'s README documents a full 1:1 restoration from a deleted Rust crate (17 namespaces,
~2,650 lines, 116 ported test assertions); `mcp` implements real JSON-RPC method
names/error codes at the manifest/message layer (no transport, an honest scoping, not a
disclaimer).

**`multiformats`/`io-multiformats` was renamed before `ipld`/`io-ipld`**, since `ipld`
itself depends on `multiformats` — same dependency-order care as ADR-2607052300's
`turn`-before-nothing (no such ordering issue there, but the same principle: rename leaves
of the dependency graph first, fix each dependent's `deps.edn` before renaming the next
node up).

### Manifest hygiene: 3 stale entries removed

`manifest/west.yml` had 3 leftover entries (`gltf`, `glb`, `usd`) pointing at local paths
that no longer exist — these repos were already renamed on GitHub to `org-khronos-gltf`,
`org-khronos-glb`, and `org-openusd` respectively (confirmed via `gh api` redirect), but the
old manifest entries were never deleted at the time (the generator's `--entry` flag only
adds/replaces, never deletes — same caveat noted in ADR-2607051200 and ADR-2607052300).
Removed as pure dead-weight cleanup, no new naming decision involved.

## Execution

Same procedure as ADR-2607052300, with two real complications caught mid-operation:

1. **GitHub repo rename** (`gh repo rename`) ×9, GitHub preserves old-name redirects.
2. **Local checkout moved + remote retargeted** ×9.
3. **Dependent `deps.edn` updates**: 13 dependent repos across the 4 with-dependents renames
   (`vrm`: network-isekai, net-babiniku, kami-gen-procedural, kami-gen-ml3d, dance,
   kami-app-character-creator, kami-gen-hybrid; `multiformats`: prolly-tree, kami-nv-compat,
   checkpointer, ipld-itself; `ipld`: arrangement, chain, kotoba-client, prolly-tree
   (already counted)). Each got a small, single-purpose commit (`chore(deps): X renamed to
   Y`) — never bundled with unrelated changes.
4. **`manifest/repos.edn`**: 9 new `:path-overrides` entries + 9 `:extra-projects` lines
   updated, landed via the GitHub Contents API single-entry-commit path.
5. **`manifest/west.yml`**: `nbb scripts/gen-west-manifest.cljs --entry <9 names>` verified all
   9 pins OK; the whole-file write was blocked by 4 unrelated pre-existing pin-drift repos
   (`browser`/`cssom`/`dom-gpu`/`htmldom`, not this batch's concern) — landed the surgical
   9-entry-add + 9-old-entry-remove + 3-stale-entry-remove diff directly against the GitHub
   tip via the API, same as ADR-2607052300.

### Real mistakes caught and fixed mid-operation (documented, not swept under the rug)

- **`network-isekai` and `net-babiniku` had real, unrelated uncommitted WIP** (a live
  streaming/broadcast feature) at the time their `deps.edn` needed the `vrm` rename.
  Committed *only* `deps.edn` in each (`git add deps.edn`, never `git add -A`/`git commit
  -a`) — the broadcast-feature files were left exactly as they were, uncommitted, for their
  owner to land separately.
- **`arrangement`**: a naive `git reset --soft <true-tip>` left a stale README.md line
  staged from before the reset (the tip had since fixed an ADR citation
  `2607050600`→`2607050700`; the stale index entry still had the old, wrong number). The
  resulting commit **accidentally reverted that fix**. Caught immediately by inspecting the
  pushed commit's diff, fixed with an immediate follow-up commit restoring the correct
  citation, verified `git diff` against the remote showed zero difference afterward. The
  corrected procedure used for every subsequent repo: `git checkout <true-tip> -- .` (resets
  **both** index and working tree, not just the branch pointer) before reapplying the
  intended one-line edit.
- **`quad-store`**: already merged into `arrangement` by an unrelated, earlier rename
  (ADR-2607050700 — "quad-store renamed to arrangement, absorb kqe"). Recognized via a
  suspicious identical remote-tip SHA between two supposedly-different local checkouts;
  confirmed via `gh api` (both names resolve to the same repo). No separate action taken —
  `arrangement`'s own `deps.edn` fix covers it; the stale local `quad-store` directory was
  left alone (out of scope for this ADR to clean up).
- **`commit-dag`**: already renamed to `chain` by another concurrent session
  (ADR-2607050800), with a real content restructure (`src/chain/` replacing
  `src/commit_dag/`). Detected the same way (repo-name/content mismatch on diff), fixed via
  a direct GitHub Contents API edit against the *real* current repo (`chain`) rather than
  trusting the stale local checkout, then resynced the local directory name to match.
- **`kotodama-mcp`'s fetch showed `(forced update)`** — verified via `gh api
  .../compare/<old>...<new>` that it was a pure fast-forward (`ahead_by: 2, behind_by: 0`),
  not a real force-push, per the shallow-clone false-positive pattern documented in
  `CLAUDE.md`, before proceeding.

## Consequences

- No behavior change — name-only renames plus mechanical `deps.edn` coordinate updates.
  Internal Clojure namespaces (`vrm.parse`, `vrm.convert`, etc.) are unchanged — repo name
  and namespace name are independent concepts, same principle ADR-2607051200 already
  established.
- 13 dependent repos now correctly reference the renamed coordinates; none were left
  pointing at a now-stale name.
- This pass **did not** touch `json`/`dag-cbor`/`openapi` (weaker fit) or the ~40 other
  repos the audit explicitly investigated and excluded (the `card`/`swift`/`webrtc`/etc.
  "capability library" family, the RDF/linked-data family, and others) — all of which
  self-disclaim full spec conformance in their own README/ADR and should stay as-is unless
  a future ADR finds new evidence otherwise.
- Two real, unrelated repo-state surprises (`arrangement`'s accidental revert,
  `commit-dag`→`chain`) reinforce the standing practice (`CLAUDE.md`, `git-cleanup-conflict`
  skill): **always fetch and diff against the true remote tip — never assume a shared
  checkout's local HEAD is current — and verify the actual pushed commit's diff before
  moving to the next repo**, especially when using `git reset --soft`, which does not reset
  the working tree or catch stale index entries.

## References

- ADR-2607052300 (`turn`/`rt`/`net`/`signal` reverse-domain rename — the immediate precedent
  and execution-shape template this ADR follows)
- ADR-2607051200 (`kotoba-lang` の `*ui*` 系リポジトリ名衝突を解消する — the original
  execution-shape precedent)
- ADR-2607050700 (quad-store → arrangement, absorb kqe — discovered mid-operation)
- ADR-2607050800 (commit-dag → chain — discovered mid-operation)
- `kotoba-lang/org-vrmc-vrm`, `io-multiformats`, `io-ipld`, `org-anthropic-mcp`,
  `org-omg-{bpmn,cmmn,dmn}`, `org-ietf-ical`, `org-bluesky-atproto`: each repo's own
  README/ADR (unchanged by this rename — the content this ADR's naming decision is based on)
