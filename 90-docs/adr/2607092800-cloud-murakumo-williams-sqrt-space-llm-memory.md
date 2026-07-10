# ADR-2607092800: cloud-murakumo LLM memory via Williams √t-space + co-scientist tournament

## Status
Accepted

## Context

Owner request: use MIT CSAIL R. Ryan Williams' *Simulating Time With Square-Root Space*
(STOC 2025 Best Paper; arXiv:2502.17779) to design and implement memory-efficient LLM
inference in `cloud-murakumo`, validated with a **co-scientist** approach (same
Generation / Reflection / Ranking / Evolution / Meta-review shape as ADR-2607012300
`sha256d-clj` and DeepMind's AI co-scientist).

`cloud-murakumo` already serves:

- dense models via **vLLM** on rented NVIDIA GPUs (`:minimax-m27`, `:kimi-k27`)
- MoE via **mlx-moe** expert paging on Apple Silicon unified-memory fleet
  (`:qwen3-coder-next-moe`)

Long-context KV cache is the dominant decode-time memory growth term \(\Theta(S)\).
Williams proves \(\mathsf{TIME}[t]\subseteq\mathsf{SPACE}[\sqrt{t\log t}]\) for multitape
TMs via block-respecting decomposition → Tree Evaluation → Cook–Mertz. The product
question is which *transferable* design, if any, improves murakumo serving without
lying about fidelity.

## Decision

1. **Honest framing:** do **not** claim Transformers are multitape TMs or that the
   theorem applies literally. Transfer the **parameter-balancing + block recompute**
   pattern (same family as gradient checkpointing / KV recompute).

2. **Closed strategy catalog** in `cloud-murakumo.sqrt-space`:
   `:full-kv`, `:sliding-window` (approximate), `:sqrt-checkpoint`,
   `:block-respecting` (Cook–Mertz proxy), `:expert-page` (mlx-moe weights),
   `:sqrt-plus-page` (compose √S KV + expert paging).

3. **Co-scientist tournament** in `cloud-murakumo.cosci` (deterministic, no LLM in
   loop): hard Reflection gates (exact fidelity; no KV regression; √S claimers must be
   \(o(S)\)); Elo Ranking on work-cost
   `kv-cells + α·recompute + β·S·weight-frac` (memory-first, Williams-aligned).

4. **Product wiring** via opt-in `:serve :kv-policy` in `murakumo.edn`:
   - dense vLLM functions → `:sqrt-checkpoint`
   - mlx-moe MoE → `:sqrt-plus-page`
   - `vllm.cljc` / `mlx_moe.cljc` only annotate when `:kv-policy` present (legacy
     serve maps stay bit-identical)
   - CLI: `clj -M:cosci`, `clj -M:sqrt-space`, `clj -M:kv-policy`

5. **Measured tournament (2026-07-09):** winner **`:sqrt-plus-page`**; at \(S=65536\)
   peak kv-cells **930 vs 65536** (~1.4% of full-KV) with exact-with-recompute
   fidelity; `:sliding-window` hard-fails exact gate.

## Consequences

- Operators get a datom-declared memory policy and cost model before GPU spend.
- Serve command extras express *intent* (`--max-num-batched-tokens`,
  `--enable-prefix-caching`, mlx `--profile sqrt-kv`); **true kernel recompute** is
  an explicit follow-up, not claimed done.
- Docs: `orgs/gftdcojp/cloud-murakumo/docs/sqrt-space-cosci.md`.
- Tests: `sqrt_space_test`, `cosci_test`, `kv_policy_test` (suite 83 tests / 323
  assertions green at land).

## Follow-ups

1. vLLM / mlx block-KV recompute plugin + live H100/asher VRAM·tok/s A/B.
2. Fold √S vs full-KV into BMC `cost.cljc` fleet ¥/tok gate from run ledger.
3. Optional Cook–Mertz multipoint evaluation for multi-head shared tape blocks
   (`:block-respecting` beyond the space proxy).

## Addendum 1 — real-model validation + paper + arXiv submit (2026-07-09→10)

| Step | Outcome |
|---|---|
| Mac M4 MLX (135M / 0.5B) | KV save **93–97%**; recompute-no-cache **worse** peak |
| Modal A100 (7B / 14B bf16) | same save ratio; 14B@16k **3.22 GB → 86.5 MB** |
| Attention | host-paged online-softmax numerically exact (\(\max\|\Delta\|\sim 10^{-5}\)) |
| Results home | public [`com-junkawasaki/sqrt-space-kv`](https://github.com/com-junkawasaki/sqrt-space-kv) (ADR-2607101000) |
| arXiv | **submitted** 2026-07-10 · draft **7807366** · primary **cs.CL** · N24 (ADR-2607101010) |
| Public arXiv id | *pending announce* |

Paper does **not** claim TM simulation of Transformers; it transfers block-size
balancing to KV residency with measured storage savings.
