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
  ~~Picked up and substantially exceeded — see Addendum 1 (2026-07-15).~~

## Addendum 1（2026-07-15）— Phase 1 完了を発見・独立検証（実装は本ADRの外で進行済み）

本ADRが「future session が拾う」としていたPhase 1の実装は、**このセッション
自身が書いたのではなく**、この monorepo で並行して進行していた別の作業に
よって、west pin が指す時点よりずっと先まで既に完了していた。west.yml の
`num` pin（`60e38913`）・`torch` pin（`86226f87`）が、それぞれの実際の
`main`（`aa242ee5`・`4703ae17`）から**101コミット・111コミット**も
取り残されていたことに気付いたのがきっかけ——通常の「1コミット遅れ」規模
ではなく、この規模の乖離は「この機能は未着手」という本ADR自身の記述が
既に事実と乖離していることを示唆していた。

**実際に確認できた内容**（fresh clone + 実テスト実行、shared checkout には
一切触れず——`orgs/kotoba-lang/num` は別セッションの `agent/cuda-backend`
ブランチ（8コミットの実コミット済みCUDA backend作業）をcheckoutしたまま
だったため、意図的に手を付けずそのまま残した）:

- `torch-clj` に `src/torch/num_backend.cljc`（764行）が実在し、まさに
  本ADRが要求した `torch.ports/IBackend` の実装——ただし本ADRの最小要求
  （`:linear`/`:relu`/`:conv2d`/`:softmax`のみ）を大幅に超え、`:silu`/
  `:sigmoid`/`:tanh`/`:gelu`/`:rmsnorm`/affine `:layernorm`/`:groupnorm`、
  グループ/depthwise/dilation対応の完全NCHW `:conv2d`、causal maskingと
  KV-cache付きの学習可能multi-head attentionまでカバーする。
- conv2d・attention・multi-head attention・groupnorm・KV-cache・GQA・
  paged runtime・full Llama decodingについて、本ADRが要求した「hand-computed
  oracleとのf32許容差一致」規律が、`test/torch/num_backend_test.cljc`
  （512行、約20 deftest）として実在し、**実際に実行して0 failuresを確認**
  （`num`: 85 tests / 446 assertions、`torch`: 140 tests / 615 assertions、
  いずれも0 failures/0 errors）。
- 本ADRが要求した「正直なベンチマーク」も実在（`torch/README.md`）: 2
  Llamaブロック・256隠れ次元・4クエリ/2KVヘッド・全線形層とトークン埋め込み
  Q4_K量子化・tied LM head・causal prefill・固定容量KV-cache decodeという
  whole-graph Metalベンチマークが、**実測値**（Apple M4、cold 42.806ms、
  warm prefill 23.815ms、cached decode 8.852ms/token、量子化7.11倍圧縮）
  付きで記載されている——「速いと主張する」のではなく数字を出す、という
  本ADR自身の規律に合致。
- `torch.core/run`は依然としてbackend未束縛時に投げる（意図通りの
  fail-closed設計——「describe, don't execute」というデフォルトは正しい
  ままで、本ADRが問題視していたのは「束縛できる本物のbackendが存在しない」
  ことであり、それは`torch.num-backend`の実在で解消された）。

**Phase 1のスコープ外だった項目の状況**: `num`のS2（native Apple MPS-API
高速パス）も既に一部着手済みらしいこと（`orgs/kotoba-lang/num`の
`agent/cuda-backend`ブランチ名や、直近main コミット `perf: route Metal
dense algebra through MPS`）を観測したが、本ADRのスコープではないため
深追いしていない。`kotoba-lang/comfyui`のpinも別途74コミット遅れている
ことを観測したが、本ADRが明示的に「対象外・touched by this ADR」と
述べた項目であり、本addendumのスコープ外として意図的に手を付けていない
（別途の孤立したwest-pin follow-upとして残す）。

**着地**: `manifest/west.yml`のpinを`num` `60e38913`→`aa242ee5`、
`torch` `86226f87`→`4703ae17`に前進（いずれも`gh api compare`で
`behind_by=0`の純粋な前進であることを確認済み）。コードは書いていない
——このaddendum自体が「本ADRの宿題は既に他所で片付いていた」という
発見・検証・記録のみ。

## References

- `kotoba-lang/num` ADR-0001 (architecture, staged roadmap S0–S4)
- `kotoba-lang/torch` README (`torch.ports/IBackend` — the injection seam
  this ADR wires into; "describe, don't execute" is the current, correct
  default absent a backend)
- This session's murakumo fix (ComfyUI upgrade to 0.27.0 + dead-node
  detection + LaunchDaemon self-heal across the fleet) — the reason Phase 1
  is not urgent: real diffusion-model inference speed was already solved
  pragmatically before this ADR was written.
