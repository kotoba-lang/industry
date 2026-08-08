# Kingdom Cascade

A swap-and-match puzzle for network-isekai. Break the cellar open, get the
king out.

Original IP. The genre is the reference, nothing else — no title, character,
artwork, level, string or specific rule is taken from any commercial game.

```
    ........        O R G B O O G R
    ........        B G O R G B R O
    ..cccc..   ->   R O c c c c O B      match beside a crate to break it
    ........        G B R G O R G B
```

## Run it

```bash
npx nbb --classpath src:test run-tests.cljs          # 88 tests, 2202 assertions
npx nbb --classpath src play.cljs resources/levels/kc-001.edn
npx nbb --classpath src levels.cljs --gate           # solvability over the set
```

`play.cljs` prints the board after every move, so the cascade rules are
reviewable without a GPU. `--dumb` plays the first legal swap instead of the
best one, which is a quick way to see how much of a level's difficulty is the
level and how much is the player.

## What is here, and what is not

| | |
|---|---|
| Game rules, levels, solver, input | **done and tested** |
| Render-IR emitter | **written, not compiled against the real renderer** |
| Web build, `.ipa`, `.aab` | **not built** |

The session that wrote this had GitHub access scoped to
`com-junkawasaki/root`, so `network-awai/network-isekai`,
`kotoba-lang/kami-engine` and `kotoba-lang/shell` were all out of reach, and
the container was Linux with no Xcode and no Android SDK. `docs/publishing.md`
is the runbook for the parts that are still unrun, and is specific about which
blocker stops each one.

## Layout

```
src/isekai/games/kingdom_cascade/
  rng.cljc        Lehmer PRNG, identical on the JVM and in ClojureScript
  board.cljc      sparse grid — only playable cells exist
  matcher.cljc    runs, merged into L/T groups, promoted to specials
  clear.cljc      what a clear does to pieces, covers and blocks
  blast.cljc      areas of effect for specials and for special pairs
  gravity.cljc    falling (straight and diagonal) and refill
  level.cljc      level EDN, the opening deal, goal accounting, validation
  core.cljc       state, legal moves, the cascade loop
  solver.cljc     greedy player — hints, solvability, difficulty tuning
  input.cljc      pointer gestures -> actions
  render_ir.cljc  state -> :render/sprite2d draw list          ← renderer seam
  art.cljc        atlas layout, palette, HUD token names
resources/levels/ three levels
shell/app.edn     kotoba-shell manifest for iOS and Android
sample.edn        genre-benchmark manifest (ADR-2607140900 D1)
```

Everything in `src/` is pure `.cljc`. No atoms, no ambient state, no I/O — a
swap is a function from state to state. That is what lets the same code be the
authority in the browser, in the packaged mobile app, and in the headless
runner that the tests and the replay digest use.

## Rules

**Swap** two adjacent pieces to line up three or more of a colour. A swap is
legal only if it opens a match, or if a special is involved.

| Match | Reward |
|---|---|
| 3 | — |
| 4 in a line | arrow along that line — clears its row or column |
| 5+ in a line | prism — clears every piece of one colour |
| L or T | bomb — clears a 3×3 |

Specials are colour-neutral: they never join a colour run. **Tap** one to fire
it, or swap two together for a combo — two arrows make a cross, two bombs a
wider crater, bomb and arrow a three-wide beam both ways, a prism and anything
converts a whole colour, two prisms take the board.

**Crates** and **stone** occupy a cell and hold no piece; a match *next to*
one breaks it, and stone needs two hits. **Ice** and **frost** cover a piece
instead: the piece is pinned in place and nothing falls past it, but it can
still be matched, and clearing it takes the cover off rather than the piece.

Pieces fall straight down, and diagonally when the cell directly above is
solid. Without the diagonal case a crate under an overhang leaves a pocket
that never refills and the level deadlocks. When no legal move is left the
board reshuffles the loose pieces — obstacles stay where the designer put
them.

## Levels

Plain EDN, with the grid as row strings, so a level diff is legible in review:

```clojure
{:level/id "kc-001"
 :level/moves 12
 :level/seed 20260808
 :level/colors [:coin :gem :clover :goblet]
 :level/goals [{:goal/kind :block :goal/block :crate :goal/count 8}
               {:goal/kind :collect :goal/color :coin :goal/count 40}]
 :level/grid ["........" "........" "..cccc.." "........"
              "..cccc.." "........" "........" "........"]}
```

Glyphs: `.` ground, `*` explicit spawner, `c` crate, `s` stone, `i` ice,
`f` frost, `#` outside the play area. An unknown glyph is an error, not a
hole — a typo should not quietly shrink the board.

`levels.cljs` reports, per level, whether it validates and how many moves the
greedy solver needs against its budget:

```
  kc-001  win   greedy 6/12   slack 6   score 24840
  kc-002  win   greedy 14/26  slack 12  score 19560
  kc-003  win   greedy 21/30  slack 9   score 45720
```

It has already earned its place twice: it caught a goal asking for eight
crates on a grid holding four, and it caught covered cells never being dealt
a piece — which made every cover goal unreachable without anything erroring.

## The renderer seam

`render-ir/scene` turns a board into an EDN scene for the KAMI sprite2d path
(`kotoba.sprite2d` over `kotoba.webgpu`, falling back to `kotoba.webgl`).
`render-ir/timeline` turns the frames of one player action into an animation
script — the renderer plays it and never recomputes a rule.

`render_ir.cljc` is the **only** file that knows a renderer exists. If the
upstream contract spells something differently, that is the one file to
change. Its key names follow the documented shape but have not been compiled
against the real consumer; see the namespace docstring.

The HUD is data here and DADS (`jp-go-dds`) chrome there, per the workspace UI
standard. `art/hud-tokens` carries `--hig-*` token *names* only — and stays
off `--hig-spacing-*`, `--hig-text-*-size` and `--hig-radius-*`, which the
DADS bridge does not carry and which collapse to zero instead of erroring.

## Determinism

Same level, same seed, same moves must give the same `core/digest` in the
browser, in the packaged app and under nbb. Two things make that hold:

- The RNG is Park-Miller with a multiplier chosen so every intermediate
  product stays under 2^53 — the JVM's longs and ClojureScript's doubles
  cannot drift apart.
- `digest` is a polynomial rolling hash, deliberately **not**
  `clojure.core/hash`, which is free to differ between Clojure and
  ClojureScript and would make the check silently vacuous.

Board iteration is in a fixed reading order for the same reason.
