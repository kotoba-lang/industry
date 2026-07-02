# ADR-2607022340: kami-isekai-assets — isekai/tensei RPG template asset library

**Status**: accepted **Date**: 2026-07-02

## Context

isekai.network (network-isekai) wanted a "default asset" library for the
isekai/tensei genre: races (elf/dwarf/orc/goblin/beastman/dragon-kin/human),
classes (knight/mage/merchant/guild-master/king/princess/adventurer),
monsters (slime/goblin/orc/dragon), skills/magic, and a "cheat protagonist"
effect — usable both as isekai.network's own Asset Hub presets and as a
reusable lib other kami-engine projects can depend on.

Two things it explicitly should NOT be: (1) a costed AI-generation job for
every asset (images/3D meshes/TTS voice via the existing `generate.html` /
Modal GPU backend) — that stays a separate, per-asset, user-triggered step;
(2) a random modern-anime-isekai skin. The requested aesthetic direction was
specifically the late-90s Squaresoft "artisan RPG" register (*Legend of
Mana*, *SaGa Frontier 2*, *Valkyrie Profile*, *Final Fantasy Tactics*):
watercolour-desaturated, mythological/medieval, restrained — not saturated
cartoon primaries, and explicitly not the current internet "brainrot"
aesthetic (offered only as an opt-in variant, never the default).

## Decision

New standalone repo `kotoba-lang/kami-isekai-assets` (public, `.cljc`, no
external deps) — an **EDN template generator**, not a binary asset pack:

- `kami.isekai.palette` — the watercolour desaturation function (+ an
  explicit opt-in `brainrot` loud-remix variant, never applied by default).
- `kami.isekai.races` / `kami.isekai.classes` — data tables of
  proportion/accent tweaks on one shared humanoid body plan.
- `kami.isekai.chargen/compose-character` — turns `{:race :class :seed}`
  into the exact `kami.sprite2d` primitive vector every network-isekai game
  already uses (`[:circle/:rect/:ellipse/:arc {...}]`) + a `:render/profiles`
  3D fallback. Deterministic per seed. `cheat-aura` is a composable golden
  halo for the OP-protagonist trope.
- `kami.isekai.monsters` — slime (standalone blob plan) + goblin/orc/dragon
  (reuse the humanoid plan, menacing recolour).
- `kami.isekai.skills` — magic/skill catalog as `kami.audio` synth recipes +
  `kami` `:fx :burst` particle specs (fireball/ice-lance/holy-heal/curse/
  cheat-aura) — same "no asset files, synthesised" pattern the rest of the
  catalog uses for SFX.
- `scripts/gen_presets.clj` (`bb gen-presets`) — writes a curated slice of
  the catalog as standalone `character.edn` files; this is what seeds
  network-isekai's Asset Hub defaults (`:asset/kind :scene`, format
  `:scene-edn`, under `public/assets/isekai/`).

This is a generation-time authoring library (plain Clojure/ClojureScript,
run via babashka/REPL/build step) — its *output* is plain EDN pasted into a
game's `scene.edn`, not something that runs inside the kototama guest
sandbox.

## Consequences

(+) Reusable across network-isekai and any other kami-engine project via
`:local/root`; zero ongoing cost (no GPU calls); "view source + fork" stays
true since every character is composed primitives, readable and forkable
like the rest of the catalog. (+) `bb test` gates every race×class
combination (56) + all 4 monsters + all 5 skills structurally.
(−) Character fidelity is deliberately low — a handful of primitives, not
illustrated art; real images/3D meshes/voice need a separate `generate.html`
pass per asset, user-triggered (GPU cost). (−) Coverage is representative,
not exhaustive — not every trope keyword requested got its own archetype
(e.g. no dedicated "cult"/"labyrinth" system); the composer is designed to
be extended with more races/classes/skills rather than enumerate everything
up front.
