# ADR-2607131500: kotoba-lang num/torch — Phase 1 toward real Metal execution (not PyTorch parity)

## Status

Accepted.

## Context

The owner asked, in the same session as ADR-2607131000-adjacent murakumo work,
to bring `kotoba-lang/num`, `kotoba-lang/torch`, and `kotoba-lang/comfyui`
(clj) up to the maturity of the real Python ComfyUI + PyTorch stack this
session had just fixed and sped up on the murakumo fleet (Metal/MPS SDXL
inference, ~3 min/image, self-healing). Before writing any code, the actual
gap was measured rather than assumed:

| | current |
|---|---|
| `kotoba-lang/num` | 744 lines `.cljc` |
| `kotoba-lang/torch` | 626 lines `.cljc` |
| `kotoba-lang/comfyui` | 1,309 lines `.cljc` |
| real ComfyUI (Python orchestration only, excludes PyTorch itself) | 239,926 lines |
| PyTorch's own ATen/MPS kernel library (not counted above) | multi-million-line C++/CUDA/Metal, Meta + Apple, 8+ years |

Reaching literal "ComfyUI-py-equivalent maturity" is roughly a 1000x scope
gap and would mean rebuilding the GPU kernel library ComfyUI merely sits on
top of — not a task to attempt wholesale in a session or to scope as a
single ADR. The owner agreed (AskUserQuestion, this session) to scope only a
realistic Phase 1 here, not the end state.

**Correction to an earlier inaccurate claim made in this same session**: it
was stated that `kotoba-lang/num`'s Metal backend was "S2, unimplemented,
CPU + WGSL reference only." This is wrong. Re-reading `num`'s own
ADR-0001 and actually running its verification harness on this machine
(Apple M4) shows S1 and S3 are done, not aspirational:

```
$ deno run --allow-read --unstable-webgpu verify/metal_contract.js
GPU: Apple M4 (wgpu→Metal)
✓ axpy ✓ scal ✓ dot ✓ nrm2 ✓ add ✓ sub ✓ mul ✓ sum ✓ amax ✓ amin ✓ gemv ✓ gemm ✓ spmv
Metal full-contract: 13 passed, 0 failed

$ deno run --allow-read --unstable-webgpu verify/metal_pcg.js
2-D Poisson 32x32 (N=1024), Jacobi-PCG on Metal:
  iterations: 64, relative residual ‖r‖/‖b‖: 8.79e-6
✓ GPU PCG converged to the analytic solution
```

All 13 BLAS-level ops run for real on this machine's Metal GPU via WGSL/wgpu
(wgpu compiles WGSL → MSL on macOS), verified against the CPU reference to
f32 tolerance, and a real Krylov solver (the shape nagare-clj's `linsolve`
needs) converges on-GPU. What's genuinely missing is narrower than "no
Metal": num's own roadmap's S2 (a native Apple MPS-API fast path bypassing
WGSL) is unstarted, and — the actual gap relevant to this ADR — **num has no
conv2d kernel**, and **nothing calls num from torch-clj**, which is why
`torch.core/run` still throws by design ("describe, don't execute").

## Decision

Phase 1 wires `torch-clj`'s existing shape/module graph to `num`'s
already-verified Metal backend for a small real forward pass — not a
diffusion model, not attention, not training.

**In scope:**

1. `num.wgsl`: one new kernel, `conv2d` (2-D convolution — direct tiled WGSL
   kernel or im2col+existing gemm), added with the same rigor as the
   existing 13: a CPU reference in `num.cpu`, a contract test asserting
   `WgslBackend ≡ CpuBackend` to f32 tolerance, and live verification on
   real Metal via the `verify/metal_contract.js`-style harness. This is the
   one primitive `torch.model`'s built-in `:conv2d` layer type needs that
   `num` doesn't have yet (`:linear`/`:relu`/`:softmax` already map onto
   existing gemv/gemm/elementwise/reduce ops).
2. `torch-clj`: a new namespace (e.g. `torch.num-backend`) implementing
   `torch.ports/IBackend`, backed by `num`, covering exactly `:linear`
   `:relu` `:conv2d` `:softmax` — enough to run the README's own MLP example
   and a small CNN (conv → relu → flatten → linear) for real.
3. Correctness: the num-backed `core/run` output for a small fixed model +
   input matches torch-clj's own pure-Clojure CPU path (or `num.cpu`
   directly) to f32 tolerance — the same oracle discipline `num` already
   uses, not a new standard.
4. A benchmark, reported honestly (not necessarily "faster"): wall-clock for
   that same small model's forward pass on `num`'s Metal backend vs. the
   identical shapes run through the real PyTorch already confirmed live on
   the murakumo fleet's Metal/MPS device (ADR from this session's murakumo
   fix). The number is the deliverable, not a target to hit.

**Explicitly out of scope for Phase 1** (future-phase candidates, not
promised or started here):

- Attention/transformer ops, any U-Net-scale architecture, diffusion
  samplers/schedulers, safetensors loading, or any actual SDXL-class model.
- Autograd / backward pass / training — forward-pass inference only.
- `num`'s own S2 (native Apple MPS-API fast path) — Phase 1 stays on the
  already-working WGSL/wgpu path, which is portable (S1's whole point).
- `kotoba-lang/comfyui` (the node-graph engine) — untouched. It keeps
  orchestrating the real Python ComfyUI over HTTP via murakumo, unchanged by
  this ADR; whether/how it ever calls a Clojure-native execution path is a
  separate future decision, not implied by Phase 1 landing.
- Any claim, on completion, of "maturity parity" with PyTorch or ComfyUI.
  Phase 1's success criterion is "a small real model runs correctly and at a
  measured speed on `num`'s Metal backend" — a single verified brick, not a
  finish line.

## Consequences

- (+) A genuine, benchmarked, oracle-verified next step that reuses `num`'s
  already-proven Metal execution instead of duplicating GPU kernel work —
  consistent with `num`'s own staged roadmap (S1→S3 already real; this ADR
  is the `torch-clj` consumer-wiring half of what `num`'s ADR-0001 S3
  described for nagare/kudaki, applied to `torch-clj` instead).
- (+) Closes the actual, narrower gap found during investigation (no conv2d
  kernel, no torch↔num wiring) rather than the initially-misstated one
  ("no Metal").
- (−) Does not reduce reliance on the real Python ComfyUI + PyTorch stack
  for actual production image generation — that stays exactly as this
  session left it (fixed, upgraded, self-healing on the murakumo fleet).
  Phase 1 is a parallel foundation investment, not a replacement path.
- (−) Deliberately does not start implementation in this ADR. A future
  session picks this up as its own scoped piece of work; this document is
  the scope fence so that work doesn't silently balloon back toward
  "PyTorch parity."

## References

- `kotoba-lang/num` ADR-0001 (architecture, staged roadmap S0–S4)
- `kotoba-lang/torch` README (`torch.ports/IBackend` — the injection seam
  this ADR wires into; "describe, don't execute" is the current, correct
  default absent a backend)
- This session's murakumo fix (ComfyUI upgrade to 0.27.0 + dead-node
  detection + LaunchDaemon self-heal across the fleet) — the reason Phase 1
  is not urgent: real diffusion-model inference speed was already solved
  pragmatically before this ADR was written.
