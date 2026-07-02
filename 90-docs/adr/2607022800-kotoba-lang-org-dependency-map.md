# ADR-2607022800: kotoba-lang org — dependency/relationship map

**Status**: accepted
**Date**: 2026-07-02
**Data SSoT**: `2607022800-kotoba-lang-org-dependency-map.edn` (this document is the
narrative companion; the `.edn` is the queryable source of truth)

## Context

The owner asked for two things together: (1) import all ~359 `kotoba-lang`
GitHub org repos into the west manifest, and (2) organize their
dependencies/relationships in an `adr.edn`.

Investigating (1) first changed the shape of (2). A basename-vs-`path:`
comparison between the live GitHub org (363 repos as of 2026-07-02) and
`manifest/west.yml` found that **358 of 363 were already correctly
registered** — most through a long, careful history of per-cluster
`:extra-projects` additions, each landed with its own ADR and, critically,
with an inline comment in `manifest/repos.edn` explaining *why* the repo
exists and what it relates to. Only **5 repos were a genuine gap**
(`cad`, `eda`, `genome`, `mst-projector`, `lab` — real, pre-existing GitHub
repos that had simply never been added), and this ADR's companion commit
registers them (see `:gap-sweep-2026-07-02` in the `.edn`).

The remaining 4 unmatched names are not gaps:
- `cnc-toolpath` is a self-documented duplicate of `cnc` awaiting an owner
  disposition call.
- `kami-autodrive`, `kami-shugyo`, `kami-vehicle` are size-0 repos pushed
  within minutes of this ADR being written — active work-in-progress from a
  concurrent session restoring `kami-engine` crates (ADR-2607010930). Per the
  standing rule against touching another session's in-flight work, these are
  left alone; they'll presumably be registered by that session when ready.
- `com-junkawasaki/kototama` was GitHub-transferred to `kotoba-lang/kototama`,
  but the local checkout and `:path-overrides` entry haven't been updated
  together. Adding the override alone would make the generator silently
  **drop** the entry (no working checkout at the new canonical path, no
  existing `west.yml` entry there either to fall back to) — this needs the
  checkout relocated in the same change as the override, deferred as a
  separate, more careful piece of work.

So the real remaining ask was narrower than "import 359 repos": it was
**"make the tribal knowledge that already exists legible."** `repos.edn`
turned out to already contain ~44 comment-documented clusters recording real
architectural decisions (ADR references, split lineages, explicit
dependencies, and — notably — an entire naming-collision-avoidance discipline
where restoration efforts renamed away from a crate's natural short name to
avoid colliding with an unrelated, older repo). That knowledge was scattered
across 700+ lines of inline comments in a file whose primary job is being a
west generator input, not a readable map. This ADR consolidates it.

## Decision

Rather than fabricate a from-scratch, exhaustive dependency graph across all
363 repos (expensive, and mostly false precision — most repos are leaf
libraries with zero documented cross-repo relationship), this ADR:

1. **Mines every existing cluster comment** in `manifest/repos.edn`'s
   `:extra-projects` into one normalized `:clusters` vector (44 entries) in
   the companion `.edn`, each carrying its theme, spawning ADR id(s), member
   repo list, and any explicitly stated relationship.
2. **Ground-truths a dependency subgraph** for the crypto/CBOR/CID/CRDT/MST
   substrate — the repos most other things build on — by reading real
   `deps.edn` files (`:local/root` and `io.github.kotoba-lang/*` git-sha
   coordinates) rather than trusting comments alone.
3. **Extracts the full naming-collision table**: every case where a
   `kami-engine`-crate restoration renamed away from its natural short name
   because an unrelated, pre-existing `kotoba-lang/<short-name>` repo already
   held it.
4. **Flags, but does not silently fix,** three small documentation
   inconsistencies found along the way (see `:open-questions` in the `.edn`)
   — none affect west/CI correctness today, so they're recorded rather than
   edited into a live, in-flight file.

## The 4-org taxonomy

Authority: ADR-2606302300. `kotoba-lang` = pure-CLJC language substrate,
consumed by every other org. `etzhayyim` = agent-centric, public-interest
organism actors. `gftdcojp` = human-centric business products. `com-junkawasaki`
= transitional foundation, shrinking toward data-only as its `-clj` libraries
finish migrating kotoba-lang-ward. Full detail in `.edn` `:orgs`.

## Cluster catalog (summary)

The full 44-cluster catalog lives in the `.edn`'s `:clusters` vector. In
broad strokes:

- **Foundational stdlib** (P0/P1/P2/M5 tiers): `async`, `device`, `coll`,
  `json`, `spec`, `wit`, `scheduler`, `store`, `lint`, `text`, `log`, `fmt`,
  `lsp`, `test`, `fs`, `http`, `io`, `time`, etc.
- **AT Protocol / content-addressed data substrate**: `atproto`, `mst`,
  `crdt`, `commit-dag`, `prolly-tree`, `quad-store`, `kqe`, `kotoba-client` —
  the `kotoba-store`/`kotoba-query`/`kotoba-runtime` CLJC restoration lineage
  (ADR-2607010930 Phase 6), explicitly reusing `multiformats`/`dag-cbor`/`cacao`.
- **etzhayyim-sdk relocation batch** (8 repos, 3 ADR waves): `kami-nv-compat`,
  `hinshitsu`, `pqh`, `ipfs`, `checkpointer`, `base-l2`, `witness-quorum`,
  `atproto-client` — the former `etzhayyim-sdk` monorepo split apart.
- **kami-engine crate restoration** (by far the largest family, ~150+ repos
  across many waves — ADR-2607010930): everything from `kami-app-*` domain
  apps to the WebGPU DSL/runtime split (~55 repos) to the scene/gameplay
  block (~50 repos, uncommented but contextually the same effort) to the
  EDA/CAD restoration cluster (`brep`/`fea`/`pcb`/`dft`/`spice`) to the
  mangaka `*-clj` domain crates (22 repos, ADR-2607010930, following the
  `kami-genko` precedent).
- **Vertical capability libs**: ISIC (`swift`, `banking`, `phone`, `card`,
  `retail`, `logistics`, `property`, `labor`, `robotics`), ISCO-08
  (`occupation`), Bitcoin (`btc-crypto`, `btc-mining`, `mining-pool`,
  `wallet`).
- **Product/actor repos**: `giemon` (robot line, depends on `robotics`),
  `arxiv`, `security`, `gftdcojp/ai-gftd-newscaster`, various
  `etzhayyim/com-etzhayyim-*` organism actors.

## Naming-collision-avoidance discipline

The most structurally interesting finding: `kami-engine` crate restorations
never blindly claim the crate's natural short name. Eleven documented cases
renamed away from a collision with an unrelated, older `kotoba-lang/<name>`
repo:

| crate | rejected name | actual name | why |
|---|---|---|---|
| kami-cam | `cam` | `cnc` | `cam` = unrelated camera-rig repo |
| kami-rt | `rt` | `raytrace` | `rt` = real-time media transport (ADR-2607011700) |
| kami-text | `text` | `glyph` | `text` = foundational stdlib repo |
| kami-cad | `cad` | `brep` | `cad` = unrelated industrial CAD/CAM workbench |
| kami-cae | `cae-solver` | `fea` | `cae-solver` = unrelated ROM/LBM CFD contract |
| kami-eda | `eda` | `pcb` | `eda` = unrelated OpenSTA/OpenROAD-class kernel |
| kami-verify | `verify` | `model-checking` | too generic/collision-prone |
| kami-si | `si` | `signal-integrity` | ambiguous vs SI units |
| kami-pkg | `pkg` | `ic-packaging` | npm/Debian package connotation |
| kami-ip | `ip` | `ip-xact` | IP address vs intellectual property |
| kami-flow | — | *(not restored)* | actually an unrelated Node.js/Cypher tool |

This is also exactly why this ADR's own gap-sweep registration of `cad` and
`eda` (cluster 44) is unrelated to the `brep`/`pcb` renames — those renames
exist *because* `cad`/`eda` are real, older, different repos.

## Ground-truthed dependency graph (substrate hub)

Read directly from `deps.edn` in the 17 locally-checked-out hub repos, not
inferred from comments:

```
ed25519 ─┬─▶ cacao (local/root)
         ├─▶ pqh
         └─▶ witness-quorum

dag-cbor ─┬─▶ cacao (local/root)
          ├─▶ pqh
          ├─▶ commit-dag
          └─▶ prolly-tree

multiformats ─┬─▶ checkpointer
              ├─▶ commit-dag
              ├─▶ prolly-tree
              └─▶ quad-store

mst ─▶ checkpointer
pqh ─▶ checkpointer
prolly-tree ─▶ quad-store
eth-crypto ─▶ base-l2
```

Zero-dep leaves: `ed25519`, `dag-cbor`, `multiformats`, `did`, `atproto`,
`mst`, `eth-crypto`, `crdt`. Notably, `commit-dag` has **no** compile-time
dependency on `prolly-tree` despite both belonging to the same
database-crates roadmap (ADR-2607022600) — a commit wraps an opaque `state`
value, so the coupling is architectural, not a code dependency.

Two loose ends worth knowing about (not fixed here, see `:open-questions`):
`koe`'s `:dev` alias has a stale `:local/root` pointing at
`../langgraph-clj`/`../langchain-clj`, which resolves incorrectly from
`koe`'s current `kotoba-lang` location (the real targets are under
`orgs/com-junkawasaki/`) — likely a leftover from before `koe` migrated orgs.
And `eth-crypto`'s own `deps.edn` header says it's "lift-ready" for
extraction back to `com-junkawasaki/eth-crypto-clj`, a small tension against
the general kotoba-lang-ward migration direction.

## Consequences

- The "359-repo import" is closed: 5 genuine gaps registered, 4 exceptions
  documented and correctly left alone.
- Future contributors have one place (`:clusters` in the `.edn`) to check
  "why does this repo exist and what's it related to" instead of grepping
  700 lines of `repos.edn` comments.
- Three small `repos.edn` comment inaccuracies are now flagged
  (`:open-questions`) for whoever next touches those regions — not fixed
  proactively, since `repos.edn` is a live, actively-edited file and none of
  the three affect generator correctness.
- This ADR is descriptive, not prescriptive: it does not change how new
  repos get registered (`:manifest-workflow` in `repos.edn` remains the
  authority for that) or reorganize `repos.edn` itself.
