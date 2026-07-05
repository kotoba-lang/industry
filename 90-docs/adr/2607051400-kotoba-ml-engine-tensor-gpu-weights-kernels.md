# ADR-2607051400: `kotoba-lang` ML execution stack — N-D tensor engine, live Metal/WebGPU backend, weight loading, attention/conv3d/sparse-conv/rasterization kernels

## Status
Accepted (phased — Phase 1–3 implemented this pass; Phase 4–6 designed, not yet built)

## Context

ADR-2607031600 (`cloud-murakumo` GPU fleet requirements) established that this org's
cloud H100/A100 fleet is design-only — no real GPU procured. A follow-up investigation
(2026-07-05) asked whether the *already-real* 11-node Mac mini M4 (16GB unified memory
each) fleet (`kotoba-lang/murakumo/fleet.edn`, Tailscale-reachable) could instead run
**TRELLIS** (image→3D), **Hunyuan3D-2/2.1** (image→3D), and **UniRig** (auto-rig) —
the three models `kotoba-lang/kami-gen-ml3d` (ADR-2607051120) and its `:autorig` stage
(ADR-0048 §2) target via `cloud-murakumo`.

Findings, condensed:
- The prior Mac-mini-fleet kernel-panic incident (ADR-2607022000, GLM-5.2 experiment) had
  a narrow, already-understood root cause (pushing macOS's Metal wired-memory ceiling to
  87% of RAM for llama.cpp RPC shards) — not a blanket fleet-safety finding. A safe
  envelope (~9-10GB/16GB) is already known.
- All three models are CUDA-first research repos with custom CUDA kernels (flash-attn,
  xformers, nvdiffrast, spconv, diffoctreerast, torch_scatter, torch_cluster) with no
  official Apple Silicon support. Community MPS forks exist for TRELLIS (tested on 24GB,
  not 16GB) and Hunyuan3D-2 (shape-only; texture stage fails, nvdiffrast-bound). UniRig has
  **no** community Mac port at all.
- `kotoba-lang/num`/`kotoba-lang/torch` — this org's own portable `.cljc` tensor/DSL
  libraries — were surveyed as a from-scratch alternative to fighting PyTorch/CUDA/MPS
  compatibility. Neither is close to sufficient today:
  - `torch` is a **shape/parameter-counting DSL only** — `torch.core/run` throws
    `"no IBackend bound — torch-clj is shape-only here"` when called; no default execution
    engine, no weight-loading mechanism, no attention/conv3d layer type.
  - `num` has dense vector/matrix ops (`matvec`/`matmul`) and CSR sparse matvec, a
    correctness-tested CPU "oracle" backend, and **real WGSL compute-shader source** for
    axpy/scal/ewise/reduce/gemv/tiled-GEMM/SpMV — but no N-D tensors, no broadcasting, no
    reshape, and (critically) **no live GPU device wired into the Clojure runtime**.
  - However: `num/verify/metal_contract.js` and `metal_pcg.js` already prove the concrete
    missing link is solvable — they run the full WGSL kernel set against
    `navigator.gpu` (raw WebGPU) **under Deno**, which has native WebGPU support backed by
    `wgpu`, which itself targets **Metal on macOS with no browser required** — confirmed
    13/13 passing on real Apple M4 Metal. This is a real, already-verified GPU-access path
    for a headless Mac mini; it is presently a standalone verification script, not wired
    into `num`'s `IBackend`/`IGpuDevice` protocol for general use.
  - No sparse-voxel-convolution or differentiable-rasterization primitive exists anywhere
    in `kotoba-lang` (`kami-nv-compat`/`rtx-native`/`voxel` are explicit "reservation only,
    no runtime algorithm" stubs).

Owner direction (2026-07-05): design and build this stack — tensor execution engine, real
GPU wiring, weight loading, and attention/conv3d/sparse-conv/rasterization kernels — as
`kotoba-lang` (portable `.cljc`, no Rust, consistent with ADR-0048's policy; Deno itself is
a pre-built host **runtime** this org already uses for `num`'s own verification scripts,
not new Rust source this org authors/maintains — same category as using the JVM or Node).

## Decision

Six phases. **Phases 1–3 are implemented and shipped in this pass** (see Consequences for
exact repos/commits); **Phases 4–6 are designed here but deferred** — they are
substantially harder systems-engineering problems and should not be claimed done until
real, tested code exists.

### Phase 1 — N-D tensor engine (extends `kotoba-lang/num`)

Add a shape-aware N-D array type (`num.tensor` or extend `num.array`) with: arbitrary-rank
shape, NumPy-style broadcasting rules, `reshape`/`transpose`/`squeeze`/`unsqueeze`, and
axis-parameterized reductions (`sum`/`amax`/`amin`/`mean` along any axis or axis set) —
generalizing the existing 1D/2D-only `num.core` ops. Every new op is correctness-tested
against `num`'s existing CPU "oracle" backend convention (hand-computed/naive-reference
expected values, the pattern `num/test/num/contract.cljc` and `cpu_test.cljc` already use)
— **not** validated against NumPy output (no NumPy dependency), but held to the same
"real numbers, not just doesn't-throw" bar this repo already sets for itself.

### Phase 2 — Live GPU backend (Deno + WebGPU → Metal)

Promote `num/verify/metal_contract.js`'s pattern from a standalone verification script into
a real `IGpuDevice` implementation `num`'s `wgsl_backend.cljc` can dispatch through from
ClojureScript compiled for Deno (`shadow-cljs` Deno target, or `kotoba wasm` compiled to a
module Deno's WebGPU-capable runtime loads) — i.e., **the actual execution path, not just
its own test**. Extends the existing WGSL kernel set (axpy/scal/ewise/reduce/gemv/GEMM/
SpMV) to the Phase 1 N-D ops where a GPU dispatch is warranted (elementwise/reduce
generalize directly; N-D matmul/broadcast dispatch is new WGSL). This targets exactly the
hardware already in-fleet (Mac mini M4 via Metal) with no new procurement.

### Phase 3 — Weight loading (safetensors reader)

A pure `.cljc` `safetensors` parser (new small repo, `kotoba-lang/safetensors`, or a
`num.safetensors` namespace) reading the real, simple safetensors format (a JSON header
describing each tensor's name/dtype/shape/byte-offset, followed by a raw contiguous byte
buffer) — no PyTorch/pickle format support (safetensors was designed specifically to avoid
pickle's arbitrary-code-execution risk and is what HuggingFace publishes TRELLIS/
Hunyuan3D-2/UniRig checkpoints as by default). Loads real published weight files into
Phase-1 N-D tensors, keyed by parameter name, ready for `kotoba-lang/torch` (once Phase-3.5
below exists) or direct `num` op consumption.

### Phase 3.5 — `torch` gains a real execution engine (wires to `num`)

`kotoba-lang/torch` currently has **no** `:local/root` dependency on `num` and no
`IBackend` implementation at all. Add one: a real backend that executes `torch`'s described
layers (`:linear`/`:conv2d`/`:layernorm`/`:softmax`/`:relu`/`:gelu`/etc., per
`torch.shape/built-in-types`) as calls into Phase-1/2 `num` tensor ops, so
`torch.core/run` stops throwing "shape-only" and actually computes. This is the connective
tissue between "describe a model's shape" (`torch`, already real) and "execute real tensor
math" (`num`, now real after Phases 1-2).

### Phase 4 — Attention kernel (designed, not yet implemented)

Scaled-dot-product / multi-head attention is expressible entirely in terms of Phase-1/3.5
primitives (matmul + scale + softmax + matmul, plus a head-split/merge reshape) — no new
GPU-kernel primitive is required beyond what Phases 1-2 already provide, only correct
composition + a numerically-stable softmax (subtract-max-before-exp). This is the
**most tractable** of the four originally-requested kernel categories and the natural next
implementation step after Phase 3.5 lands, but is not implemented in this pass.

### Phase 5 — `conv3d` kernel (designed, not yet implemented)

3D convolution (needed by both TRELLIS's and Hunyuan3D-2's volumetric/voxel stages) is a
well-trodden but nontrivial GPU-kernel problem. Two implementation strategies to choose
between when this phase starts: (a) direct sliding-window WGSL compute kernel, or (b)
im2col-style lowering to a Phase-1 batched matmul (simpler to implement correctly first,
trades memory for simplicity — the common bring-up path before hand-writing a fused
kernel). Not implemented in this pass.

### Phase 6 — Sparse convolution + forward-only rasterization (designed, not yet implemented — hardest phase)

Two genuinely novel-to-this-org systems problems, scoped down deliberately from the full
research-model requirement to only what **inference** (not training) needs:

- **Sparse voxel convolution** (spconv-equivalent, used by TRELLIS/UniRig): requires a
  "rulebook" structure — a hash map from `(input-active-voxel, kernel-offset) →
  output-active-voxel` — built once per sparse tensor's index structure, then a
  gather → dense-matmul → scatter execution per kernel weight offset. This is a real
  sparse-data-structure design problem, not a trivial extension of Phase 1's CSR sparse
  matvec (that's 2D; this is N-D spatial with a learned/dynamic sparsity pattern).
- **Rasterization** (nvdiffrast-equivalent, used by TRELLIS/Hunyuan3D-2's texture/render
  stages): scoped to **forward-only** rasterization (project triangles → per-pixel
  barycentric interpolation of vertex attributes) since generation-time inference does not
  need nvdiffrast's differentiable/backward pass (that's a training-time need this org has
  no use for here) — a meaningfully smaller problem than "reimplement nvdiffrast," but
  still real rasterizer-pipeline work (triangle setup, depth test, barycentric interp,
  perspective correction).

Neither is implemented in this pass; both need their own dedicated design ADR before
implementation starts, given their novelty and risk of a half-correct implementation
silently producing wrong geometry/textures.

## Consequences

- (+) Phases 1-3 give `kotoba-lang/num` real N-D tensor math with a genuinely live,
  already-hardware-verified GPU path (Deno+WebGPU→Metal) on the exact fleet this org
  already owns, and give `kotoba-lang/torch` its first real execution engine instead of a
  shape-only DSL — usable well beyond the TRELLIS/Hunyuan3D-2/UniRig motivation (any future
  `kotoba-lang` ML work benefits).
- (+) Weight loading via safetensors (not pickle) matches how these org's actual target
  models are published on HuggingFace, and avoids pickle's code-execution risk entirely.
- (−) **This does not yet make TRELLIS/Hunyuan3D-2/UniRig runnable.** Phases 4-6
  (attention/conv3d/sparse-conv/rasterization) are the parts that would actually let a real
  port of these 3 architectures execute, and only Phase 4 (attention) is even fully
  designed as directly buildable; Phase 6 (sparse-conv + rasterization) is explicitly the
  hardest, riskiest, most novel-to-this-org part and is correctly not claimed as done.
  Overclaiming completion here would repeat exactly the mistake this org's own
  "kami-gen-*" bug-hunting session (ADR-2607051100/1130) was built to catch — real
  verification over trusted claims.
- (−) Even once all 6 phases are real, actually porting TRELLIS/Hunyuan3D-2/UniRig's full
  architectures on top of them is a separate, further undertaking (mapping each model's
  specific layer graph onto these primitives, validating numerical correctness against the
  reference PyTorch outputs) — not automatically unlocked by the primitives existing.
- Follow-up: Phase 4 (attention) is the natural next implementation step. Phases 5-6 need
  their own dedicated ADRs before implementation, given their novelty.

## Alternatives Considered

1. **Port the 3 models' actual CUDA kernels to Metal/MSL directly (skip the kotoba-clj
   tensor engine).** Rejected for this pass — would produce Python+MSL glue outside this
   org's `.cljc`-portable, WASM-targetable convention, and wouldn't benefit any future
   `kotoba-lang` ML work the way a general tensor engine does.
2. **Wait for real cloud GPU procurement (ADR-2607031600) instead of investing in a
   from-scratch tensor engine.** Not rejected — genuinely the faster path to running these
   *specific* 3 models unmodified. This ADR's work is a parallel, longer-horizon investment
   in this org's own ML infrastructure, not a claim that it's the fastest way to a working
   TRELLIS/Hunyuan3D-2/UniRig demo.

## References

- ADR-2607031600 (`cloud-murakumo` GPU fleet requirements, `com-junkawasaki/root`)
- ADR-2607022000 (Mac mini fleet exo distributed-inference experiment, kernel-panic root
  cause and safe memory envelope)
- ADR-0048 (`kami-engine`) §2 (`:autorig`/UniRig), this ADR's motivating consumer
- ADR-2607051120 (`kami-gen-ml3d`), the other motivating consumer
- `orgs/kotoba-lang/num/README.md`, `orgs/kotoba-lang/num/verify/metal_contract.js`
  (the already-proven Deno+WebGPU→Metal path this ADR promotes to a real backend)
- `orgs/kotoba-lang/torch/README.md` ("Describe, don't execute" — current shape-only scope)
- `orgs/kotoba-lang/murakumo/fleet.edn` (the target hardware — 11× Mac mini M4 16GB)
- microsoft/TRELLIS, Tencent-Hunyuan/Hunyuan3D-2, VAST-AI-Research/UniRig (the 3 motivating
  models, all CUDA-first, no fully-working Mac port for any of the three)
- safetensors format: `github.com/huggingface/safetensors`
