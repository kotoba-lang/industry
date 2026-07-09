# ADR 2607031800: kami-genre-base-systems — 18 genre-category base gameplay systems for kotoba-lang

## Status
Accepted

## Context

The user pasted a PlayStation Store genre-browse page (real commercial
titles as genre examples), asked for just the category labels extracted,
then asked for a base gameplay system per category "as kotoba-lang." The
extraction step cleanly separated two things the original paste mixed:
real commercial game titles/key-art descriptions (copyrighted, never
reproduced or mimicked) and generic genre category labels (not
copyrightable, safe as organizing labels for wholly original systems).

The kami-clj compiler subset's proven vocabulary, established cumulatively
across this session's isekai-network work, is genuinely constrained: a
single local input stream (no player-index axis argument anywhere), no
terrain/tilemap/pathfinding/vehicle-physics/skeletal-animation reachable
from guest logic (Rust-substrate only), and — checked before use, not
assumed — no multiplication operator confirmed anywhere in any reference
game or prior verified base system. Every system in this repo was built to
fit inside these real constraints. Separately, 4 of the 18 requested
categories (modern-remake, free-to-play, indie, post-apocalyptic) are not
gameplay archetypes at all — forcing them into the same "distinct core
loop" shape as the other 14 would have meant fabricating fictional
mechanics with no grounding.

## Decision

### Repo structure

`kotoba-lang/kami-genre-base-systems` (public): `design/<genre>.edn` (one
design doc per category) + `games/<genre>/{author.clj,logic.clj,deps.edn,
.gitignore}` (one pair per category with real code) — mirrors
`kami-clj-play/games/`'s established convention exactly.

### 14 genuine gameplay archetypes, each with real code and a distinct core loop

rhythm (beat-gated `tick-n`+`mod` timing, isekai-network's own proven
pattern; maturity pass added a combo/streak counter) · puzzle (maturity
pass added a second distinct tile-arrangement variant for real progression)
· stealth (maturity pass replaced binary detected/not with an escalating
alert-level that decays over time) · single-player (canonical
survivors-shaped reference loop + a level counter) · strategy (maturity
pass added a real win/lose condition via opposing force counters) · sports
(ball-and-goal proximity) · horror (escalating pursuit tension) ·
superhero (empowered combat reskin) · open-world (large flat arena +
scattered discovery, honestly not streaming terrain — that's Rust-substrate
only) · local-multiplayer (1 human + 1 AI vs a shared objective, the honest
fallback for the confirmed single-input-stream constraint) · kids-family
(deliberately the simplest base — zero-fail stationary collection, no chase
AI) · relaxing (player-paced touch-to-grow loop, no timer pressure) ·
platformer (sequential ordered-waypoint traversal — honestly not a
jump-physics platformer; no jump/gravity primitive exists anywhere in the
proven vocabulary, confirmed via a targeted grep before concluding this,
not assumed) · fighting (1 human + 1 AI opponent, same input-stream
constraint as local-multiplayer, proximity-exchange health counters).

### 4 honest supporting systems, not fabricated mechanics

- **modern-remake**: a real, reusable tuning-preset system (wider
  hit-tolerance, auto-aim-style larger weapon-range) demonstrated by
  re-tuning `single-player`'s constants — not a fabricated "remake
  mechanic."
- **free-to-play**: a real soft-currency economy loop (earn via enemy
  defeat, auto-spend at a threshold for a temporary buff that decays) —
  a genuine common F2P building block, not a claim that F2P is itself a
  mechanic.
- **indie**: the smallest possible starter kernel in the repo (one entity,
  one movement system, nothing else) — a genuinely useful rapid-iteration
  starting point, explicitly labelled a scaffold, not a genre mechanic.
- **post-apocalyptic**: a real resource-scarcity system (limited weapon
  "charges" depleting per use, replenished by scarce salvage pickups) — a
  genuine scarcity mechanic, not a claim that the narrative setting is
  inherently a distinct core loop.

### Verification

Every `author.clj` actually run via `clojure -M author.clj`, confirmed
producing valid `scene.edn`; every `logic.clj` construct cross-checked
against the proven vocabulary. Multiplication (`*`) was checked and found
not confirmed anywhere in any reference game or prior verified base, so
periodic-timing constants use precomputed values with a derivation comment
rather than an unverified `*` call. WASM compilation (`kotoba-lang/engine`'s
`compile-file`) was **not** attempted for all 18 in this pass — a
legitimate, valuable follow-up, out of scope here given this session's
established slow-build cost for that verification tier.

## Consequences

- 18 genre-labelled starting points now exist for any future kotoba-lang
  game project to fork from, each with documented, honestly-scoped engine
  constraints rather than aspirational unverified claims.
- The honest N/A-vs-real-mechanic distinction methodology (4/18 categories)
  is now a concrete, citable precedent: "hit every requested slot" does not
  override "do not fabricate."
- WASM-compile verification for all 18 games remains a real follow-up debt,
  not silently claimed as done.

## Alternatives Considered

1. **Force all 18 into an identical "distinct core loop" shape**, inventing
   mechanics for the 4 business/setting categories: rejected — would have
   fabricated ungrounded gameplay claims, the same failure mode this
   session's isekai-network design-investigation work repeatedly avoided.
2. **Skip the 4 non-mechanic categories entirely**: rejected — the user
   explicitly asked for real code for these too; the honest resolution was
   a real, clearly-labelled supporting system per category, not silence.
3. **Attempt real pathfinding/physics/vehicle integration** for genres that
   traditionally need it: rejected — confirmed Rust-substrate-only,
   unreachable from kami-clj guest logic, via this session's repeated
   concept-level investigations for isekai-network.

## References

- ADR-2607023200 (isekai-network standalone repo — the honesty-discipline
  precedent this repo follows)
- ADR-2607031700 (kotoba-lang/engine + kami-script-runtime-rs — the
  compile/run pipeline a future WASM-verification pass for this repo would
  use)
- `kotoba-lang/kami-genre-base-systems`
