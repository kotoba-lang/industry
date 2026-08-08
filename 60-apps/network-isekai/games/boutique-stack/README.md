# Boutique Stack

A stack-and-carry shop tycoon for network-isekai. Haul stock from the crates
to the racks, run the till when the line builds, and spend the takings on the
next department.

Original IP. The genre is the reference, nothing else.

**服屋のアンロックで靴も** — the shoe department is the far end of the same
clothing store, not a separate shop. Tops are open from the first minute,
outerwear is the first unlock, and shoes sit behind both of them. That chain
is enforced in three places that cannot disagree: `catalog/departments`
declares it, `world` refuses to route an agent into a locked zone, and
`economy/can-unlock?` checks the requirements before the price.

## Run it

```bash
npx nbb --classpath src:test:../common/src run-tests.cljs   # 57 tests, 153 assertions
npx nbb --classpath src:../common/src shift.cljs            # balance report
npx nbb --classpath src:../common/src shift.cljs --gate     # non-zero on a broken shop
```

A 20-minute shift of the flagship shop, played by the built-in manager:

```
bs-flagship — Boutique Stack   (20 minute shift)
  served      677   lost 25 (3%)   empty 15 / queue 10
  revenue     67052   cash left 38902
  departments tops, outerwear, shoes   (all open)
              dept-outerwear at minute 10
              dept-shoes at minute 15
  staff       {:restocker 3, :cashier 2}
```

## What is here, and what is not

| | |
|---|---|
| Simulation, economy, unlock chain, manager | **done and tested** |
| Render-IR emitter | **written, not compiled against the real renderer** |
| Web build, `.ipa`, `.aab` | **not built** |

Same two blockers as the other game: GitHub access in the session that wrote
this was scoped to `com-junkawasaki/root`, so `network-awai/network-isekai`,
`kotoba-lang/kami-engine` and `kotoba-lang/shell` were out of reach, and the
container was Linux with no Xcode and no Android SDK. The publishing runbook
in `../kingdom-cascade/docs/publishing.md` covers this game too — the
`kotoba-shell` path is identical, only `shell/app.edn` differs.

## Layout

```
../common/src/isekai/games/common/
  rng.cljc        Park-Miller PRNG, shared with Kingdom Cascade
src/isekai/games/boutique_stack/
  catalog.cljc    departments, items, prices, the unlock chain
  world.cljc      zones, fixtures, integer geometry, shop validation
  economy.cljc    money, unlocks, upgrades, hiring
  sim.cljc        the tick — agents, customers, tills, deliveries
  manager.cljc    greedy spending policy — balance testing, not an autoplayer
  render_ir.cljc  state -> 3D instances                        ← renderer seam
  art.cljc        meshes, palette, HUD token names
resources/shops/  the flagship shop
shell/app.edn     kotoba-shell manifest for iOS and Android
sample.edn        genre-benchmark manifest (ADR-2607140900 D1)
```

## How it works

`(sim/tick state world intent)` is one pure function. No atoms, no wall
clock, no `Math/random`; `intent` is `{:move [dx dy]}` for direct control or
`nil` to let the player's autopilot decide. The browser frame loop, the
packaged app and the headless gate all call the same function.

**Positions are integers** — one unit is a centimetre, one floor tile is 100
units. Not floats: integer movement is exactly reproducible on the JVM and in
ClojureScript with no reasoning about rounding, which is what the replay
digest rests on.

**Tick order is fixed**: deliveries, staff, player, customers, tills, spawns.
Serving *after* customers move means someone who reaches the queue this tick
can be served this tick. Spawning last means a customer never acts on the
tick it appears.

**Customers** walk a shopping list, take from a rack, join the shortest queue,
pay, and leave. They give up two different ways, and the two are counted
separately because they have different cures: an empty shelf wants another
stocker, a slow line wants another cashier. Collapsing them into one "lost"
number is how a shop ends up with two cashiers and bare shelves.

**There is no navmesh and no collision**, deliberately. At this layer the
question is how long a trip takes and what it blocks; a real path would move
that by a few percent and make every test a geometry puzzle. The renderer can
draw a nicer path over the same timing.

## The balance gate

`shift.cljs` is the design tool, and it fails a shop that

- does not validate,
- a competent manager cannot play to its last department,
- loses more customers than it serves, or
- offers a hire nobody would ever buy.

All four ship a shop that *runs* — no exception, no visible error — and is
broken anyway. It has already earned its place four times:

1. The manager only ever acted on the worst leak, so it hired stockers to the
   cap and stopped. **The cashier role was unbuyable content** on every shop
   measured. It now falls through to the secondary leak.
2. At the first costs tried, outerwear and shoes opened **one minute apart**
   and the shop was finished by minute 9 of a 20-minute session. The current
   costs are picked from a sweep, not from feel.
3. `validate` used to **throw** on a shop it could not build, so the one
   input the gate exists to catch crashed the run and hid every other shop in
   it. It now reports the offending fixture and zone.
4. A one-till shop still never hires a cashier — the player alone covers a
   single till at every traffic level measured. That is now a written-down
   fact about the layout rather than a mystery, and the test that checks
   "every role is worth hiring" uses a two-till shop and says why.

## Determinism

Same shop, same seed, same tick count, same `sim/digest` in the browser, in
the packaged app and under nbb. Integer positions, an explicit RNG state
threaded through every draw, a fixed tick order, and a digest that is a
polynomial rolling hash rather than `clojure.core/hash` — which is free to
differ between Clojure and ClojureScript and would make the check silently
vacuous.

## The renderer seam

`render-ir/scene` emits a 3D scene: `:render/instances` for the floor,
fixtures, shelf stacks, people and the garments they are carrying, plus
`:render/badges` for the MAX label over a full rack and the price pad on the
next department. This is the pure-3D `entities->instances` path, not the
sprite2d path the match-3 game uses.

`render_ir.cljc` is the only file that names a mesh, a camera or a colour. Its
vocabulary follows the documented shape but has not been compiled against the
real consumer — see the namespace docstring.

## Known gaps

- **No sinks at the end.** A 20-minute shift finishes with ~39k idle cash and
  nothing left to buy. Three departments and three upgrades is the whole
  economy; real progression needs more of them, or a prestige loop.
- **No cleaning mechanic**, though the genre usually has one. Scoped out: it
  would add a third loss mode and a fourth role.
- **No art.** `art/meshes` describes what the renderer expects to load.
