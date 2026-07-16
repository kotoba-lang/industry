# ADR-2607072700: kotoba-lang/kami-solar-helix-scene — solar helical-model EDN authoring surface

## Status

Accepted (implemented)

## Context

Owner question: is the solar system's "helical model" (the Sun itself
moves through the Galaxy, so a planet's path in a frame where the Sun is
not held fixed is a helix, not the flat ellipse seen in the ordinary
heliocentric frame) physically accurate, and can it be rendered as a 3D
model via `kami-engine`?

The physics answer is yes, with a caveat: "flat" and "helical" are both
correct descriptions of the same motion — the heliocentric frame holds the
Sun fixed, the Galactic/inertial frame does not, and a planet's velocity
in that frame is just the Galilean sum of its orbital velocity and the
Sun's own galactic translation (~220-230 km/s). The popular 2012-era
"vortex" animations of this idea (e.g. DjSadhu's "The Helical Model") got
the *geometry* wrong, though: they drew a tight corkscrew. The real ratio
of the Sun's galactic advance during one planetary orbit to that orbit's
own circumference is large (~7.4x for Earth, ~16.8x for Jupiter — the
further out/slower the planet, the longer its period, the more the Sun
travels meanwhile), so the true curve is a gently stretched-out spring,
nearly a straight line with a small wobble, not a tight vortex tube.

On the rendering side: `com-junkawasaki/kami-engine` (local checkout) is
empty (no commits). `com-junkawasaki/kami-engine-sdk` is a Svelte/TS
VRM-viewer + document-scene bridge with no 3D primitive/orbit API.
`kotoba-lang/kami-engine` and its `kami-*-scene`/`kami-engine-*` siblings
are mid-migration off a deleted Rust GPU workspace (ADR-2607010930,
"clj-wgsl migration"): most, including `kami-engine-core` /
`kami-engine-render`, are explicitly "Scaffold only — restoration
pending," with native/WebGPU rendering execution deferred to adapter
repos not yet wired up for arbitrary custom 3D scenes. There is currently
no path from a custom scene like this to actual rendered pixels via
`kami-engine`.

Given that, the owner chose (of three options offered: an immediate
self-contained Artifact visualization, a real `kami-engine`-pattern EDN
scene now with no render output yet, or both) to have a proper
`kami-engine`-family EDN authoring surface written now, matching the
existing `kami-atmosphere-scene`/`kami-cam-scene` convention, as a
correct, reusable data asset for whichever native/WebGPU adapter picks it
up later — explicitly accepting that no pixels are produced by this ADR.

## Decision

New repo `kotoba-lang/kami-solar-helix-scene`
(https://github.com/kotoba-lang/kami-solar-helix-scene), following the
`kami-atmosphere-scene`/`kami-cam-scene` EDN-authoring-surface pattern
(zero-dep portable `.cljc`, `kotoba-lang/scene` tolerant accessors:
`scene/mget`/`scene/num`/`scene/vec3`/`scene/root-map`/`scene/kw-key`).
Unlike those siblings this is **not** a restoration of a deleted Rust
crate — it is new physics/data content:

- `resources/solar-helix.edn` / `src/solar_helix_scene.cljc`'s embedded
  `solar-helix-edn` (kept byte-identical): circular/coplanar orbital
  elements (`:orbit-radius-au` = real semi-major axis, `:period-days` =
  real sidereal period, `:color`) for the Sun + 8 planets, plus
  `:helix/galactic-frame {:sun-speed-km-s 220.0 :ecliptic-tilt-deg 60.0}`.
  Both galactic-frame numbers are documented as rounded CONFIG defaults
  with their real-world uncertainty spelled out in the docstring/comments
  (Sun's LSR speed is commonly cited 220-240 km/s depending on the
  adopted rotation curve; the ecliptic/Galactic-plane tilt is a
  popular-science rounding, not pinned past whole degrees).
- `heliocentric-position-au` (the flat, Sun-fixed view) and
  `galactic-frame-position-au` (the helical, Sun-moving view) — the
  latter a deliberately simplified single-axis transform (rotate the
  orbital plane by `:ecliptic-tilt-deg` around the x-axis, then add the
  Sun's forward translation along z), documented as illustrative of helix
  *shape and pitch*, not true galactic coordinates (a real transform would
  need the solar apex's full spherical coordinates).
- `orbital-speed-km-s` — *derived* from radius/period (`2*pi*r/T`), not
  transcribed, and checked in the test suite against published mean
  orbital speeds (Mercury through Neptune, ~2% tolerance) as a parity
  oracle, the same idiom `atmosphere-scene`'s `builtin-preset` uses.
- `pitch-au` / `circumference-au` / `pitch-to-circumference-ratio` — the
  number that makes "gentle stretched-out spring, not a tight corkscrew"
  a checked fact instead of a claim: ratio ≈ 7.4 for Earth, ≈ 16.8 for
  Jupiter, asserted in tests.
- `helix-tick` — per-frame convenience returning both frames for every
  body at a given time, for whichever future renderer consumes this data.

15 tests / 60 assertions, 0 failures (`clojure -M:test`).

Registered in `manifest/repos.edn` (`orgs/kotoba-lang/kami-solar-helix-scene`,
alongside the other `kami-*-scene` entries) and `manifest/west.yml`
(single-entry regeneration, pin verified against the pushed `main` HEAD).

## Consequences

- (+) The corrected "helical model" geometry (large pitch/circumference
  ratio, not a tight vortex) now exists as checked, reusable physics data,
  not just a chat explanation — any future `kami-engine` native/WebGPU
  adapter can consume `helix-tick` directly once one exists.
- (+) Uncertainty in the two Galactic-frame constants (Sun's LSR speed,
  ecliptic/Galactic tilt) is documented in-repo rather than presented as
  more precise than it is.
- (−) No rendering happens as a result of this ADR. `kami-engine`'s
  native/WebGPU adapter line remains scaffold-only for custom 3D scenes;
  this data asset has no consumer yet.
- (−) Circular/coplanar orbit approximation and the single-axis galactic
  tilt are explicitly not an ephemeris and not true galactic coordinates —
  documented as such so a future consumer doesn't mistake this for
  astrometric ground truth.

## Follow-up

- Once a `kami-engine` native/WebGPU adapter capable of rendering custom
  3D scenes exists, wire `helix-tick`'s per-frame `{:heliocentric [...]
  :galactic [...]}` output into it (spheres for bodies, a line/tube
  primitive for the traced path) to actually produce the render the
  owner originally asked for.
- If true positions (not the circular/coplanar approximation) are ever
  needed, replace the orbit math with a VSOP/DE440-backed source; the
  `bodies-from-edn`/`galactic-frame-position-au` split already isolates
  where that swap would happen.

## One-line summary

**New `kotoba-lang/kami-solar-helix-scene`: a zero-dep `.cljc` EDN
authoring surface (following the `kami-atmosphere-scene`/`kami-cam-scene`
pattern) that derives and tests the real geometry of the solar system's
"helical model" — heliocentric vs. Galactic-frame positions for the Sun +
8 planets, and the pitch-to-circumference ratio (~7.4x Earth, ~16.8x
Jupiter) showing it is a gentle stretched spring, not the tight vortex
popular animations depict — registered in the manifest as a reusable data
asset, with no `kami-engine` renderer yet available to consume it.**
