# ADR-2607051110: `kotoba-lang/kami-gen-sdf-agent` — LLM-authored SDF/CSG mesh pipeline (Approach 2 of 4)

## Status
Accepted

## Context

Sibling of ADR-2607051100; see that ADR's Context for the shared background (5-approach
analysis, penguin-kigurumi chibi-character test target, standing authorization to build 4
of the 5 as standalone `kotoba-lang` repos).

This ADR covers **Approach 2**: instead of an LLM picking parameters into a fixed schema
(Approach 1), an LLM **writes/edits a small procedural CSG/SDF program** — the same
relationship "OpenSCAD script" has to "parametric CAD form" — targeting the existing
`kotoba-lang/scad` and `kotoba-lang/sdf` repos, compiled to a mesh by `kotoba-lang/mesher`
(Marching Cubes), and iteratively refined by a vision-critique loop against an offscreen
render, following the reference-renderer pattern already proven in `kami-engine`
ADR-0047 (`0047-real-vrm-offscreen-render-reference.md`, `kami-live/examples/common/vrm.rs`).

## Decision

1. New repo `kotoba-lang/kami-gen-sdf-agent`. Deps on `scad`/`sdf`/`mesher` via
   `:local/root`, plus `com-junkawasaki/langgraph-clj` for the actor loop (same
   StateGraph pattern as the org's other actors — CLAUDE.md Actors section — but scoped
   here as a bounded generation tool, not a full containment/Governor actor; that's left
   to the Approach-5 wrapper if pursued later).
2. `kami.gen.sdf-agent/generate` takes a text brief (e.g. "penguin kigurumi hood, rounded
   chibi body, yellow beak") and runs a bounded loop:
   - **propose**: LLM emits a `kotoba-lang/scad`-flavored CSG program (unions/intersections/
     differences of primitives — sphere, cone, rounded-box — parameterized by named EDN
     values, not free-form code with unbounded API surface).
   - **compile**: `kotoba-lang/sdf` evaluates the CSG tree to a signed-distance field;
     `kotoba-lang/mesher` extracts a mesh via Marching Cubes.
   - **render**: an offscreen preview render (reusing the ADR-0047 wgpu reference-renderer
     pattern) produces a screenshot.
   - **critique**: a vision-capable LLM call compares the screenshot to the brief/reference
     image, scores fit, and either accepts or proposes a bounded edit (max N rounds, no
     unbounded retry — matches this org's "no silent caps, log what's dropped" convention).
3. Every intermediate program is EDN (the CSG tree), so a run's full history is diffable
   and re-playable — not just the final mesh.

## Consequences

- Composable/parametric output, git-diffable like Approach 1, and (unlike Approach 1) the
  LLM contributes actual novel structure per-brief rather than just filling a fixed schema
  — but the CSG/SDF primitive vocabulary caps what's reachable: rounded/blobby forms compose
  well (this org's existing `terrain`/`vegetation`/`structures.cljc` props prove this),
  organic chibi-character silhouettes and skin/costume folds compose poorly. Expected to
  land closer to Approach 1's fidelity ceiling than to Approach 3's for a character subject.
  Best real fit going forward is probably props/structures, not characters — this
  comparison is expected to demonstrate that gap concretely.
- No VRM/skeleton output in v0 (a CSG-composed chibi body has no bone hierarchy) — this
  repo produces a static prop-like mesh, not an animatable avatar, unless a follow-up wires
  it through `kotoba-lang/skeleton`'s auto-rig the same way ADR-2607051120 does for its ML
  mesh.
- Needs a bounded-loop cost/time budget (LLM propose+critique rounds) even though it uses
  no per-asset GPU — cheaper than Approach 3, not free like Approach 1.

## Alternatives Considered

See ADR-2607051100's Alternatives section (all 4 siblings cross-reference each other):
ADR-2607051100 (`kami-gen-procedural`), ADR-2607051120 (`kami-gen-ml3d`), ADR-2607051130
(`kami-gen-hybrid`).

## References

- `orgs/kotoba-lang/scad/README.md`, `orgs/kotoba-lang/sdf/README.md`,
  `orgs/kotoba-lang/mesher/README.md`
- `orgs/kotoba-lang/kami-engine/90-docs/adr/0047-real-vrm-offscreen-render-reference.md`
- `orgs/kotoba-lang/langgraph` (StateGraph actor loop pattern)
- CLAUDE.md "no silent caps" convention (bounded retries, logged not hidden)
