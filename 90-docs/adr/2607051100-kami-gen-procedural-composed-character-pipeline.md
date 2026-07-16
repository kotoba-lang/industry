# ADR-2607051100: `kotoba-lang/kami-gen-procedural` — EDN-composed procedural character pipeline (Approach 1 of 4)

## Status
Accepted

## Context

Investigating "how to build a 3D production pipeline (VRM etc.) as kotoba-lang, including
an LLM-generation approach" (chat 2026-07-04), five architectural approaches were analyzed
against a shared test target: a "gugugaga" penguin-kigurumi chibi character (photoreal
Pixar-style 3D render, source: a short-form video frame). The owner directed building 4 of
the 5 as real `kotoba-lang` repos for a head-to-head comparison, each a standalone GitHub
repo from the outset (standing authorization, CLAUDE.md "標準作業の常時許可").

This ADR covers **Approach 1**: deterministic EDN-parameter composition — no ML inference,
no per-asset GPU cost. It is the direct 3D extension of `kotoba-lang/kami-isekai-assets`'s
`compose-character` (2D sprite-primitive composition) and of this organization's stated
data philosophy ("everything describable is EDN", "no PNG/GLB/WAV asset files" — see that
repo's README "Why no real images/3D meshes/voice audio").

Existing building blocks (already ported from the former `kami-engine` Rust workspace to
standalone zero-dep `.cljc` repos under `kotoba-lang/`, per ADR-2607010930 "clj-wgsl
migration"):
- `kotoba-lang/character` — parametric MetaHuman-compatible face/body/hair/blendshape mesh
  generator with GLB export (ported from a 5514-line Rust crate, 66 tests).
- `kotoba-lang/mesher` — Marching Cubes SDF→mesh.
- `kotoba-lang/vegetation` — procedural mesh-from-profile (the pattern this ADR reuses for
  non-mesh "props" like the kigurumi hood/beak).
- `kotoba-lang/vrm` — VRM parse/compose/export/spring-bone/constraint pipeline.
- `kami-engine` ADR-0044 (`0044-edn-render-ir-threejs-vrm-parity.md`) — the EDN render-IR
  vocabulary (`:lights/:camera/:env/:materials/:meshes/:animations/:post`) this pipeline's
  output must be consumable by.

## Decision

1. New repo `kotoba-lang/kami-gen-procedural`. Deps on `character`/`mesher`/`vegetation`/
   `vrm` via `:local/root` (same convention as the 3 existing "actor" repos' `langgraph-clj`
   override, CLAUDE.md Actors section).
2. `kami.gen.procedural/compose-costumed-character` takes `{:base :race/human :costume
   :penguin-kigurumi :seed n}` and returns:
   - `:body` — a `kotoba-lang/character` parametric body/head build (base humanoid,
     chibi proportions via a `:proportions {:head-scale 1.6 :limb-scale 0.6}` preset,
     matching the reference image's chibi ratio).
   - `:costume` — a **new procedural "kigurumi" attachment kind**: a hood mesh (rounded
     `mesher`-compiled SDF primitive: sphere ∪ cone-beak, penguin palette) + a body-suit
     shell offset from the base body silhouette by a fixed padding, both parametric (no
     sculpting), attached as extra `kotoba-lang/character` mesh parts.
   - `:vrm` — the assembled result run through `kotoba-lang/vrm`'s compose/export to
     produce a genuine `.vrm` (humanoid bone mapping preserved from the base body; the
     hood follows head bone, no separate rig needed for a rigid hood).
   - `:render/profile` fallback (kami-isekai-assets `:render/profiles` shape) for engines
     that only want a box-instance preview, not the full mesh.
3. Every parameter (proportions, costume palette, beak size, hood droop) is a plain EDN map
   — an LLM's role here is choosing/tuning these parameters from a natural-language brief,
   never emitting raw vertices. This mirrors `kami.isekai.chargen/compose-character`'s
   existing contract exactly, extended from 2D sprite primitives to 3D parametric meshes.
4. Publish the result to `network-isekai`'s Asset Hub (`public/assets/index.edn` schema,
   `:asset/kind :model3d`, CID-addressed payload) so it's usable from `dance.html` /
   `:dance/avatar {:vrm ...}` immediately.

## Consequences

- Fully executable today with zero external services, zero GPU cost, deterministic and
  git-diffable output (an EDN parameter map, not a binary blob, is the source of truth —
  the `.vrm`/`.glb` are build artifacts of it).
- Ceiling on fidelity: cannot reproduce the reference image's photoreal chibi-CG shading
  or organic sculpted silhouette — output reads as "stylized/parametric", not photoreal.
  This is expected and is the direct comparison point against Approach 3 (ADR-2607051120).
- Requires extending `kotoba-lang/character`'s costume-attachment surface (a "kigurumi"
  category doesn't exist yet) — this is new library code, not just new call-site data.

## Alternatives Considered

Covered as 4 siblings of a single 5-approach analysis; see:
- ADR-2607051110 (`kami-gen-sdf-agent`, Approach 2 — LLM-authored SDF/SCAD code)
- ADR-2607051120 (`kami-gen-ml3d`, Approach 3 — cloud-murakumo TRELLIS/Hunyuan3D-2)
- ADR-2607051130 (`kami-gen-hybrid`, Approach 4 — parametric rig + ML texture only)
- (Approach 5, an actor-wrapper generalizing 1–4 behind a Governor/ledger contract, was
  explicitly deferred — "品質は結局その裏に挿すAdvisor依存＝これ単体はジェネレータではない" —
  until at least one concrete Advisor, e.g. this one, works standalone.)

## References

- `orgs/kotoba-lang/kami-isekai-assets/README.md` (`compose-character`, "no PNG/GLB/WAV")
- `orgs/kotoba-lang/character/README.md`, `orgs/kotoba-lang/mesher/README.md`,
  `orgs/kotoba-lang/vrm/README.md`
- `orgs/kotoba-lang/kami-engine/90-docs/adr/0044-edn-render-ir-threejs-vrm-parity.md`
- `orgs/gftdcojp/network-isekai/public/assets/index.edn` (Asset Hub schema)
- ADR-2606272330 (kagi-clj / actor `:local/root` deps.edn convention)
