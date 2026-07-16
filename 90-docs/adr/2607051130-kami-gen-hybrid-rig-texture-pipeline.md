# ADR-2607051130: `kotoba-lang/kami-gen-hybrid` — deterministic rig + ML-textured costume pipeline (Approach 4 of 4)

## Status
Accepted

## Context

Sibling of ADR-2607051100; see that ADR's Context for shared background. This ADR covers
**Approach 4**: split the problem so geometry/rig stays fully deterministic (Approach 1's
guarantees) and only surface appearance (texture pixels) is ML-generated — a much narrower,
lower-risk ML surface than Approach 3's full mesh synthesis, and one this org's own
`generate.html` backend already stages for (`backend/README.md`'s status table: image/SDXL
generation listed as "skeleton only, TODO" — this repo is a real consumer that motivates
finishing that wiring, rather than duplicating it).

## Decision

1. New repo `kotoba-lang/kami-gen-hybrid`. Deps on `kotoba-lang/character` (rig/body,
   shared with ADR-2607051100), `com-junkawasaki/comfyui-clj` (texture node-graph
   execution), and `kotoba-lang/vrm` (assembly), all via `:local/root`.
2. `kami.gen.hybrid/generate` takes a brief (`{:base :costume-prompt "penguin kigurumi,
   chibi, gray/black body, yellow beak" :seed n}`) and:
   - builds the **body/rig** exactly as ADR-2607051100's `compose-costumed-character`
     minus its procedural kigurumi geometry — i.e. a bare parametric humanoid body with
     guaranteed-valid topology, UVs, and blendshapes from `kotoba-lang/character`,
   - has an LLM turn `:costume-prompt` into a `comfyui-clj` **workflow EDN** (node graph:
     checkpoint load → prompt encode → sampler → VAE decode, API-format compatible with
     real ComfyUI per that repo's design) targeting the body's UV-unwrapped texture slots
     (albedo/normal/roughness),
   - **executes** the graph — a real run needs a real ComfyUI instance/GPU (the diffusion
     compute is injected as a host capability, matching `comfyui-clj`'s own "no GPU/
     diffusion; heavy work injected" design, same DI shape as ADR-2607051120's `execute`);
     a **mock** executor returns fixture textures for CI / for this comparison's no-GPU leg,
   - assembles body + generated textures into a `.vrm` via `kotoba-lang/vrm`.
3. Unlike Approach 3, the **rig half stays EDN/parametric and fork-friendly**; only the
   texture maps are opaque ML output — matching how VRM/glTF already separate mesh data
   from texture blobs regardless.

## Consequences

- Lower ML/GPU risk than Approach 3 (diffusion image generation is cheaper and more mature
  than mesh generation) while still reaching much closer to the reference image's surface
  appearance than Approaches 1/2 can, since the *shape* stays a plain humanoid (this
  approach cannot reproduce the reference's non-humanoid rounded penguin-suit silhouette —
  only its coloring/markings as a texture skin over a humanoid body). This is a real,
  expected fidelity gap versus Approach 3, distinct from Approaches 1/2's gap.
- Depends on `comfyui-clj` reaching a real diffusion backend, which per `generate.html`'s
  own status table does not exist live yet (SDXL/FLUX marked TODO) — same caveat as
  ADR-2607051120's live-GPU gate, just for a cheaper/simpler backend.
- Three repos to integrate (`character` + `comfyui-clj` + `vrm`) before first output,
  more wiring than Approach 1 alone.

## Alternatives Considered

See ADR-2607051100's Alternatives section.

## References

- `orgs/kotoba-lang/character/README.md`
- `orgs/com-junkawasaki/comfyui-clj/README.md`
- `orgs/kotoba-lang/vrm/README.md`
- `orgs/gftdcojp/network-isekai/backend/README.md` (generation status table: image=TODO)
