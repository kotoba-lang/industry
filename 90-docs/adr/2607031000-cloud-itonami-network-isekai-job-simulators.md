# ADR-2607031000: cloud-itonami × network-isekai — occupation/business job-simulator games

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

`cloud-itonami-{ISIC}` (business blueprints) and `cloud-itonami-isco-{code}`
(occupation blueprints) are forkable sole-proprietor **business** blueprints
(ADR-2607011000, ADR-2607012000): a robot performs the physical domain work,
an actor proposes actions, an independent occupation/domain-specific Governor
gates them, and every blueprint's `blueprint.edn` names its `:governor` and
`:required-technologies`. These are documents and code scaffolding — nothing
about a blueprint is *playable*.

`network-isekai` (isekai.network) is the gftd UGC game/creator platform
(ADR-2607021900): a game there is **data** — a `scene.edn` + `logic.cljc`
authored in the `kami-clj` guest subset (no mutable globals, no runtime f32
arithmetic — only precomputed `f32` constants; state lives entirely in
tagged marker entities the host counts/positions), rendered by tag via
`kami.sprite2d`. `kami-isekai-assets` supplies isekai/tensei-genre character
primitives for games that want them.

No bridge existed between a `cloud-itonami-*` blueprint's business logic
(what the operator actually does, gated by a Governor) and a playable
network-isekai game. The ask: give each of a pilot set of `cloud-itonami`
occupations/businesses a standalone simulator game so the blueprint's
day-in-the-life is playable, not just documented.

## Decision

### 1. One network-isekai game per occupation/business, `public/games/itonami/<slug>/`

Structurally identical to every other network-isekai game
(`game.edn` manifest + `scene.edn` data + `logic.cljc` kami-clj logic,
auto-discovered by `isekai.feed-index/build-index`'s directory walk — no
registry edit needed beyond `nbb gen-feed-index`). Games from `cloud-itonami`
blueprints live under the `itonami/` author bucket (parallel to the existing
`gftd/` bucket), one game per blueprint, matching the 1-blueprint-repo =
1-business/occupation convention already used by `cloud-itonami-*` itself.
Not separate git repos: a network-isekai game's unit of publication is
already "fork this EDN", not "clone this repo" — a new top-level repo per
occupation would fight the platform's own content model.

### 2. The robotics-premise operating-states become the core game loop

Every `cloud-itonami` blueprint shares one operating-state contract:
**intake → propose → approve → execute → audit**, with the Governor gating
`approve`. This maps onto a common `kami-clj` mechanic — the **depot loop**
— reused by all 8 pilot games, themed per occupation:

| Blueprint state | Game mechanic |
|---|---|
| `intake` | A `"job"`-tagged entity exists at a fixed site (a leak, a parcel stop, a patient, a garden plot, …) |
| `propose` | Player walks toward the job site |
| `approve` (Governor gate) | Player must first touch the **depot** entity (`"van"`/`"yard"`/`"clinic"`/`"shed"`/…) to set an `equipped` flag (`defatom`) — the stand-in for the blueprint's named `:governor` signing off; touching a job while `equipped = 0` is a **governor violation**: no task clears, a `"life"` marker is docked (rate-limited by a short `cooldown` `defatom`, the same scripted-timer idiom `orbs`' `phase`/`t` counters use) |
| `execute` | Job entity despawns, a `"done"` + `"score"` + `"picked"` marker spawn, `equipped` resets to `0` — the operator must return to the depot before the next job, one governor approval per task, never a standing blanket permit |
| `audit` | `:flow :victory :when-picked <job-count>` (the same field `drive`'s 21-gate flag already uses) — clearing every job *is* the audit trail closing clean |

Zero lives (`count-tagged "life" = 0`) despawns the player, which the
existing `:flow :gameover :when-missing "player"` convention already
handles — same shape as `drive`/`goriketsu`. This is a template, not a new
engine feature: every primitive it uses (`spawn-entity`, `despawn-entity`,
`nearest-tagged`, `count-tagged`, `defatom`/`atom-val`/`set-atom!`,
`defsystem`, precomputed `f32` constants, `key-pressed?`) already exists in
`drive`, `orbs`, and `goriketsu`.

### 3. Pilot set — 8 games, chosen so each ISCO occupation pairs with an
adjacent ISIC business

| Blueprint | Governor | Game | Slug |
|---|---|---|---|
| `cloud-itonami-isco-7126` Independent Plumbing Practice | `:plumbing-governor` | Plumbing Rounds | `plumbing-rounds` |
| `cloud-itonami-4211` Community Building Construction | `:construction-governor` | Building Site | `building-site` |
| `cloud-itonami-isco-8332` Independent Freight Driving Operations | `:freight-driver-governor` | Freight Run | `freight-run` |
| `cloud-itonami-4920` Community Freight Transport | `:freight-governor` | Freight Dispatch | `freight-dispatch` |
| `cloud-itonami-isco-2221` Independent Home Nursing Practice | `:home-nursing-governor` | Home Nursing Rounds | `home-nursing-rounds` |
| `cloud-itonami-8810` Community Care Coordination | `:safeguarding-governor` | Care Coordination | `care-coordination` |
| `cloud-itonami-isco-6112` Independent Market Gardening Operations | `:market-garden-governor` | Market Garden | `market-garden` |
| `cloud-itonami-4711` Community Retail Operations | `:retail-governor` | Retail Shift | `retail-shift` |

The ISCO game plays the sole-proprietor operator directly (one van, one bag,
one route); its paired ISIC game plays the same domain one layer up — a
small crew/site/fleet the operator now coordinates — so the pair reads as
"do the job" → "run the business", not two reskins of the same game.

### 4. Sprites stay hand-composed primitives, not `kami-isekai-assets`

`kami-isekai-assets`' `chargen` targets the isekai/tensei RPG genre (races,
classes, monsters) — these are contemporary trade/care/logistics sims, a
different register. Sprites follow the existing non-isekai games' convention
(`drive`, `orbs`, `goriketsu`): hand-composed `kami.sprite2d` primitive
vectors (`:rect`/`:circle`/`:arc`), no asset files, no GPU generation cost.

## Consequences

- (+) 8 playable prototypes land as ordinary network-isekai game content —
  `nbb gen-feed-index` picks them up with no registry change, they're
  fork/play/share like every other game on the platform.
- (+) The depot-loop template is reusable for any future `cloud-itonami`
  blueprint (427 more `:spec`-tier ISCO unit groups, more ISIC classes) —
  adding a game is theme + fixed coordinates + blueprint-sourced flavor
  text, not new engine work.
- (+) The Governor-gate mechanic is legible as *gameplay* (skip the depot,
  get penalized) rather than a paragraph of blueprint prose.
- (−) Fidelity is deliberately low (a handful of primitives, ~6-10 job sites,
  one shared mechanic reskinned 8 ways) — a prototype tier, not a full game;
  richer per-domain mechanics (e.g. plumbing's actual pipe-fitting minigame,
  freight's actual route optimization) are a follow-up, not blocked by this
  ADR's template.
- (−) Only 8 of the 111 `cloud-itonami` repos got a game; the remaining 103
  (most of them `:spec`-tier stubs, not yet `:blueprint`) are unaddressed —
  explicitly scoped as a pilot, not a batch job.
- (−) Not committed/pushed by this ADR — prototypes land in the working tree
  of the existing `orgs/gftdcojp/network-isekai` checkout for review before
  any push, per the repo's standing "confirm before push" policy.

## References

- ADR-2607011000 (cloud-itonami robotics premise + ISIC 21/21)
- ADR-2607012000 (cloud-itonami-isco occupation blueprints)
- ADR-2607021900 (network-isekai portfolio addition, Roblox-type UGC platform)
- ADR-2607022340 (kami-isekai-assets template lib — why sprites here are
  hand-composed instead)
- `network-isekai/90-docs/adr/0001-network-isekai-web-architecture.md`,
  `0008-asset-hub.md`, `0009-moderation-mechanism-and-template-gallery.md`
- 本 ADR とペアの `.edn`
