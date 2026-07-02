# ADR 2607023200: isekai-network (Ghost Hacker: Shiro & Pico game) as a standalone repo, not nested in kami-engine

## Status
Accepted

## Context

`kotoba-lang/kami-engine` PR #91 held the full deliverable produced so far for
**Ghost Hacker: Shiro & Pico** (game project codename `isekai-network`): a
5-concept EDN game bible (`design/00-game-bible.edn` — title, cast, bestiary,
shared controls/tone/rating — plus `design/01..05.edn`, one per concept
variant) and a real first prototype for concept 1 (熱痕ランナー): `author.clj`
+ `logic.clj`, a faithful rename/recolour of `kami-clj-play/games/survivors`
onto the shiro-pico/ghost cast, with `clojure -M author.clj` verified working
end-to-end. Review found the PR clean (CI green, mergeable, no correctness
issues).

While the PR was open for review, explicit user direction arrived:
"個別のゲームは isekai-network repo にまとめてね" (consolidate the individual
games into the isekai-network repo).

This is the identical structural question ADR-2607011816 already resolved for
this franchise's narrative content. That ADR split `ai-gftd-ghosthacker-shiropico`
out of `ai-gftd-mangaka` specifically to decouple an independently-lifecycled
IP from an unrelated engine/infra repo's churn, and to give SHIRO & PICO the
same standalone-repo symmetry Ren's Ghost Hacker line already has
(`orgs/com-junkawasaki/ghosthacker`). The same reasoning transfers directly to
the game project: `kami-engine` is mid an active internal migration
(ADR-2607010930, clj-wgsl) that had already deleted the `kami-engine-clj` Rust
crate PR #91's own README depended on for its compile instructions — coupling
a product-shaped game's lifecycle to that churn repeats the exact mistake
ADR-2607011816 already named and rejected once for this franchise. Separately,
`kami-engine`'s own `ARCHITECTURE.md` already treats new games as separate
per-game crates/repos (the `kami-app-{game}` convention), not nested paths —
a standalone `isekai-network` repo is consistent with that convention too.

## Decision

### New standalone repo, not merged into kami-engine

`orgs/gftdcojp/isekai-network` (private, per `repos.edn`'s `gftdcojp` org
default). Contents: a plain copy of the 12 git-tracked files from
`kami-engine`'s `kami-clj-play/games/isekai-network/`, restructured as
`design/*.edn` (unchanged) + `games/01-netsurvivors/*` (unchanged content;
`author.clj` re-verified with `clojure -M author.clj` from the new location).
`git init`, one commit, `gh repo create --source=. --push` — the same pattern
ADR-2607011816 used for `ai-gftd-ghosthacker-shiropico`.

`kotoba-lang/kami-engine` PR #91 was **closed unmerged**, not merged-then-
reverted — `kami-engine`'s git history has zero isekai-network commits at
all. `kami-clj-play/games/{survivors,waves}` and everything else in
`kami-engine` are unaffected; PR #91 only ever touched the new
`isekai-network/` subtree.

### Manifest registration via the GitHub Contents API, not a local edit

`manifest/repos.edn`'s `:extra-projects` and `manifest/west.yml` both get one
new entry each via a GitHub Contents API single-entry commit (blob-SHA-matched
PUT against `origin/main` tip) — the same method, and the same reason,
ADR-2607011816 used: this superproject checkout has concurrent-fleet pin drift
and shallow-clone `(forced update)` noise that make a local edit + full
`bb scripts/gen-west-manifest.bb` regen risky (it would bundle unrelated
drifted child-repo pins into what should be a one-line registration). Pin is
verified to equal `orgs/gftdcojp/isekai-network@main` HEAD before commit;
`bb scripts/gen-west-manifest.bb --check` is run afterward to confirm the
hand-placed entries match canonical generator output.

## Consequences

- **Symmetry.** Ghost Hacker's three living surfaces now each have the
  standalone-repo shape ADR-2607011816 established as this franchise's norm:
  Ren's line (`orgs/com-junkawasaki/ghosthacker`), SHIRO & PICO's narrative
  content (`orgs/gftdcojp/ai-gftd-ghosthacker-shiropico`), and now the game
  project (`orgs/gftdcojp/isekai-network`).
- **Decoupled from engine churn.** `isekai-network`'s lifecycle (design
  iteration, future concept prototypes) no longer shares a repo, issue
  tracker, or commit history with `kami-engine`'s in-progress clj-wgsl
  Rust-removal migration.
- **Engine dependency unchanged.** `isekai-network` still depends on
  `kami-engine` as an external engine (a west sibling project) for anything
  beyond `clojure -M author.clj`. Concept 1's WASM compile remains blocked on
  that migration landing a working `kamiclj`-equivalent entry point — the
  same open item PR #91 already documented, now tracked from
  `isekai-network`'s own README instead.
- **Zero kami-engine footprint.** Confirmed by closing the PR unmerged rather
  than merging then reverting.

## Alternatives Considered

1. **Merge PR #91 as-is, keep the game nested in `kami-engine`'s
   `kami-clj-play/games/isekai-network/`**: rejected per explicit user
   direction, and because it repeats the exact coupling-to-unrelated-engine-
   churn mistake ADR-2607011816 already identified and rejected for this
   franchise's narrative content.
2. **Name the new repo `ai-gftd-isekai-network`**, matching gftdcojp's
   `ai-gftd-*` naming convention (`ai-gftd-mangaka`, `ai-gftd-animeka`,
   `ai-gftd-ghosthacker-shiropico`): rejected — the user explicitly requested
   the literal name "isekai-network repo"; honoring the exact requested name
   over an unrequested convention.
3. **Merge PR #91 into kami-engine first, then create isekai-network and
   delete the kami-engine copy in a follow-up commit**: rejected as a
   needless detour through `kami-engine`'s git history — closing the clean,
   unmerged PR directly leaves zero trace, matching how ADR-2607012000's
   abandoned `michibiki` actor-repo detour was handled (deleted before ever
   landing).

## References

- ADR-2607011816 (ghosthacker-shiropico standalone repo — the precedent this
  ADR reuses)
- ADR-2607010930 (clj-wgsl migration — the churn this split decouples from,
  and the source of the WASM-compile blocker)
- ADR-2607012000 (shiropico publish actor — the abandoned-detour handling
  precedent for alternative 3)
- `manifest/repos.edn` `:manifest-workflow` (canonical GitHub-API registration
  path)
- `orgs/kotoba-lang/kami-engine` PR #91 (closed, superseded)
- `orgs/gftdcojp/isekai-network`
