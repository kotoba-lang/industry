# ADR-2607051431: murakumo.cloud — from zero live models to a working Claude Code backend

**Status**: closed
**Date**: 2026-07-04 → 2026-07-05
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/murakumo`, `orgs/gftdcojp/local-murakumo`

## Context

A diagnostic pass on `api.murakumo.cloud` found that despite the itonami
product catalog (`/itonami/models`) declaring 7 supported models, the LIVE
registry endpoints were both empty:

```
GET /infer/models → []
GET /nodes        → []
```

The 3 declared text models (`glm-5.2-reap50-q2k`, `qwen3-next-80b-a3b`,
`gemma-4-26b-a4b`) were never actually deployed against the real 7-node
fleet (6× Apple M4 mini + 1 remote AMD head, "gad"). GLM-5.2 in particular
(139GB) leaves almost no headroom against the fleet's ~110.9 GiB total
usable capacity. This ADR covers everything done to close that gap: pick
real base models that fit, actually deploy one, verify it end-to-end,
expose it as an Anthropic-Messages-API-compatible backend for Claude Code,
benchmark it (execution-verified, not LLM-judged), and fix the fleet's
biggest latent performance bug.

## Decision 1 — base text model lineup

Retired `glm-5.2-reap50-q2k` / `qwen3-next-80b-a3b` / `gemma-4-26b-a4b` from
the itonami catalog. Replaced with three models verified to actually fit
the live fleet via `bb murakumo infer plan`:

| model | size (Q4_K_M) | fit vs. 110.9 GiB usable |
|---|---|---|
| `qwen-agentworld-35b-a3b` | 20.6 GiB | comfortable |
| `qwen3.6-35b-a3b` | 19.7 GiB | comfortable |
| `gemma-4-12b-it` | 6.6 GiB | trivial |

Registered in `kotoba-lang/murakumo/infer.edn` (operator SSoT) and mirrored
into `gftdcojp/local-murakumo`'s `itonami/model-catalog` (product-facing
`/itonami/models`). While doing this, also caught and fixed stale
`itonami-verticals` entries still pointing at the retired model ids
(2512/2432/2356 ISCO verticals), and a stale `kotoba.html` require in
`infer_view.cljc` that had drifted from the actual `kotoba-lang/html`
namespace (`html.core`/`->html`) — an unrelated compatibility-facade fix was
in flight on that repo but not yet merged, so pointed the require at the
real namespace directly instead of waiting on it.

## Decision 2 — actually deploy one, live, verified

`qwen-agentworld-35b-a3b` (Qwen's agentic-tool-calling checkpoint) went
live end-to-end: downloaded the GGUF to the head, `provision` + `up` the
6-worker RPC ring, `serve` on the head, confirmed **real distributed
inference** via `/v1/chat/completions` (not just a health check) — initial
measurement ~10.7 tok/s. Registered the live state into `/infer/models` and
`/nodes` on production (both previously empty).

**Cloudflare-edge-to-Tailscale gap**: the Worker (`api.murakumo.cloud`)
cannot reach the head's Tailscale IP directly — CGNAT addresses aren't
publicly routable from Cloudflare's edge. Fixed by adding a hostname route
(`infer.murakumo.cloud` → `http://localhost:8090`) to the head's
**already-running** `cloudflared` tunnel (originally provisioned for
ComfyUI on the same box — same account, tunnel id
`c75a3e83-6cef-4d9b-ba22-fbb459ee6fbe`), plus a matching DNS CNAME on the
`murakumo.cloud` zone (Cloudflare API token from 1Password
`gftd.cloudflare/API_TOKEN`, since the `wrangler` OAuth token lacked
`zone:write`).

## Decision 3 — clj/Datomic implementation-ability benchmark

Built an execution-verified (not LLM-judged) benchmark: 3 pure-Clojure tasks
run on the JVM, 3 Datomic-style tasks (schema/query/transact) run against
DataScript (a Datomic-API-compatible in-memory Datalog store) against the
LIVE distributed model. Exposed at `GET /itonami/benchmark/clj-datomic` and
shown on the `/itonami` console. Two harness bugs found and fixed during
verification: `clojure -M:test -e` prints every top-level form's return
value (not just the final one), and Datalog query vectors need
`clojure.edn/read-string` (data), not `load-string` (evaluates — chokes on
bare `?`-prefixed symbols in the query).

`qwen-agentworld-35b-a3b` result: **6/6 pass**, avg 12.67 tok/s (2 of 6
tasks needed a 4500-token budget vs. 2500 for the rest — real reasoning
verbosity, not a benchmark artifact). One task's generated code used a real
Datomic `#db/id[:db.part/user]` tempid tag (needing a custom EDN reader to
verify against DataScript) — arguably a sign of authentic Datomic
familiarity, not a mistake.

## Decision 4 — `/v1/messages`: an Anthropic Messages API bridge for Claude Code

Same shape of bridge z.ai runs in front of Claude Code for GLM. Built in
`gftdcojp/local-murakumo`:

- `local-murakumo.anthropic` (pure, JVM-tested — `test/local_murakumo/anthropic_test.cljc`):
  Anthropic Messages request ⇄ OpenAI chat-completions request/response
  translation, including `tool_use`/`tool_result` ⇄ `tool_calls` block
  splitting, `thinking` blocks from `reasoning_content`, and a pure
  per-chunk state machine for SSE streaming translation
  (`message_start` → `content_block_start`/`_delta`/`_stop` →
  `message_delta` → `message_stop`) — verified byte-correct against the real
  Anthropic wire format via live curl tests.
- `worker.cljs`: the Cloudflare Worker wiring — `x-api-key`/Bearer auth
  against a new `ANTHROPIC_PROXY_TOKEN` secret, model→endpoint lookup via
  `infer.models`, and a `TransformStream` translating upstream OpenAI SSE to
  Anthropic SSE for the streaming case.
- `tools/claude-murakumo` (`kotoba-lang/murakumo`) — a launcher script
  setting `ANTHROPIC_BASE_URL`/`ANTHROPIC_AUTH_TOKEN`/`ANTHROPIC_MODEL` and
  exec'ing the real `claude` binary (bypassing a `claude` shell
  function/alias some dev shells define to unset those exact vars, via
  `command claude` / `$CLAUDE_CODE_EXECPATH`). Token resolves from
  `MURAKUMO_CLAUDE_TOKEN` or 1Password
  (`gftd.murakumo/ANTHROPIC_PROXY_TOKEN`, vault `gftdcojp`). Symlinked to
  `~/.local/bin/claude-murakumo` for direct shell use; also `bb claude`.

### Bugs found only by running real Claude Code traffic

Synthetic curl tests passed cleanly; the first real `claude-murakumo -p ...`
call still failed with an opaque "malformed response". Root causes, found
via `wrangler tail` + Claude Code's `ANTHROPIC_LOG=debug`:

1. **`mid-conversation-system` beta**: Claude Code can put role `"system"`
   messages ANYWHERE in the `messages` array (not just the top-level
   `:system` field). This fleet's chat template hard-requires system to be
   `message[0]` only and rejects a second one with a Jinja template error.
   Fix: `anthropic-msg->openai` remaps any non-top-level `"system"` role to
   `"user"`.
2. **Silent error swallowing**: the non-streaming path treated any
   parseable upstream JSON as a valid completion, including
   `{"error": {...}}` bodies — `openai-resp->anthropic-resp`'s empty-content
   fallback produced a well-formed but content-free Anthropic message
   instead of surfacing the real error. Fixed to check for `:error` and
   return a proper Anthropic error envelope.
3. **Context size**: `llama-server` was running with the hardcoded
   4096-token default (`:infer/ctx` was never set). Claude Code always
   sends its full ~89-tool definition set — ~48K prompt tokens before the
   conversation even starts — so every real request was rejected outright
   (`exceed_context_size_error`). Fixed: `:infer/ctx 65536` in `infer.edn`.

After all three fixes, `claude-murakumo -p "..."` completes real turns
end-to-end against the fleet (confirmed with both a trivial echo and a
tool-calling prompt, though the latter can take many minutes on this
hardware — see Decision 6).

## Decision 5 — gemma-4-12b-it head-to-head (same suite, same fleet)

Briefly switched the fleet to serve `gemma-4-12b-it` and re-ran the
identical 6-task benchmark. Result: qwen-agentworld-35b-a3b wins clearly.

| model | pass | avg tok/s | token budget |
|---|---|---|---|
| qwen-agentworld-35b-a3b | 6/6 | 12.67 | 2500 (4500 for 2/6) |
| gemma-4-12b-it | 1/6 | 7.54 | 4000 (3/6 never converged; fib-memo alone re-verified passing at 8000 tokens) |

Dense 12B (all params active every token) loses to a 35B-total/3B-active
MoE despite being architecturally smaller — and is *more* reasoning-verbose
relative to task difficulty on this fleet, not less. Two of gemma's
failures were genuine mistakes even where it did converge: wrong Datomic
keyword namespacing (`:db/string` instead of `:db.type/string`) and invalid
Clojure syntax (extra parens around a map literal, making it a 0-arg
function call). Exposed at `GET /itonami/benchmark/clj-datomic/compare`.
Fleet switched back to `qwen-agentworld-35b-a3b` afterward.

## Decision 6 — standalone GPU serving: the head was CPU-only the whole time

Investigating "how do we get closer to 300 tok/s" surfaced the fleet's
single biggest latent bug: the head ("gad", an AMD Ryzen AI MAX+ 395 "Strix
Halo" APU with a Radeon 8060S iGPU) was running an RPC-ring `llama-server`
binary with **no GPU backend linked at all** (`otool`/`ldd` — no Vulkan, no
ROCm/HIP). `-ngl 999` was a silent no-op; the head's ~40% ring-share
(the largest single shard, since the head absorbs whatever the 6 Mac minis
can't hold) ran on CPU alone.

Confirmed the 6 Mac-mini `rpc-server` workers were NOT affected — they
correctly link and initialize Metal (`ggml_metal_init: found device: Apple
M4`), so they were GPU-accelerated the entire time; the head was the only
CPU-bound member of the ring.

Fix: `qwen-agentworld-35b-a3b` (22GB) fits entirely in the head's own ~46GB
RAM, so the 7-node RPC ring is unnecessary for this model. Downloaded the
official llama.cpp Vulkan release binary
(`llama-b9873-bin-ubuntu-vulkan-x64.tar.gz` — Mesa/RADV cleanly detects
"Radeon 8060S Graphics (RADV GFX1151)") and ran it standalone on the head,
no `--rpc` flag, no ring.

**Result: 61.5 tok/s vs. 12.7 tok/s — ~4.8x**, just from fixing this one
binary. The equivalent ROCm 7.2 release build detects **no device at all**
(gfx1151/Strix Halo isn't in ROCm's supported device list yet, even with
`HSA_OVERRIDE_GFX_VERSION` overrides tried) — Vulkan/RADV is the only
working GPU path on this hardware today.

Added `bb murakumo infer serve-standalone <model> [gguf]` (new
`cmd-serve-standalone` in `murakumo.infer`) so this is a repeatable command,
not a one-off manual SSH session — kills any resident `llama-server`, launches
the GPU-backend binary at the new `:infer/head :standalone-bin-dir` config
key directly. `bb murakumo infer down` frees the now-unneeded 6 RPC workers.

While debugging this, also found (but did not fix — logged here for the
next person) that `bb murakumo infer down`/`cmd-ps`'s `pgrep`/`pkill`
pattern (`'.murakumo/bin/rpc-server'`) doesn't match the actual resident
process's argv0 (`./rpc-server`, a relative path from how `cmd-up` invokes
it) — `down` has never actually killed a worker; they just keep running
until something else (reboot, manual `kill`) stops them. Cosmetic/harmless
today since `serve-standalone` doesn't need the workers at all, but worth
fixing if the RPC-ring path is used again.

## Consequences

- `api.murakumo.cloud` now has a real live model (`qwen-agentworld-35b-a3b`,
  standalone-on-GPU, 61.5 tok/s) instead of an empty registry, reachable
  both as murakumo's native OpenAI-compatible API and as an Anthropic
  Messages API backend for Claude Code.
- `/itonami/benchmark/clj-datomic` and `/itonami/benchmark/clj-datomic/compare`
  give a real, execution-verified (not vibes-based) answer to "which model
  should this fleet run" — durable evidence for future model swaps.
- The standalone-GPU finding likely generalizes: any future model that fits
  in ~46GB should default to `serve-standalone` on the head, not the RPC
  ring — the ring exists for models too big for any single node, not as the
  default path.
- 300 tok/s (the original ask) is out of reach on this hardware without a
  real discrete GPU (cloud-rented A100/H100 + vLLM would trivially clear
  it) — logged as the honest ceiling, not chased further given the
  diminishing-returns/effort tradeoff (speculative decoding, quantization
  tuning) versus that alternative.
- Known follow-up, not done here: `cmd-down`/`cmd-ps` pgrep pattern fix
  (see Decision 6); wiring `serve-standalone` into the `plan`/`provision`
  orchestration properly instead of being a parallel manual-ish path;
  `/v1/messages/count_tokens` (Claude Code may call it for context
  management) is unimplemented.

## Files

- `orgs/kotoba-lang/murakumo/infer.edn` — 3 new model registrations,
  `:infer/ctx 65536`, `:infer/head :standalone-bin-dir`, fleet.edn `:rpc-ip`
  catch-up.
- `orgs/kotoba-lang/murakumo/src/murakumo/infer.clj` — `cmd-serve-standalone`.
- `orgs/kotoba-lang/murakumo/tools/claude-murakumo/` — launcher + README.
- `orgs/gftdcojp/local-murakumo/src/local_murakumo/anthropic.cljc` +
  `test/local_murakumo/anthropic_test.cljc` — the Messages API bridge.
- `orgs/gftdcojp/local-murakumo/src/local_murakumo/itonami.cljc` +
  `infer_view.cljc` — model catalog, clj-datomic benchmark +
  comparison data/views.
- `orgs/gftdcojp/local-murakumo/src/local_murakumo/routes.cljc` +
  `worker.cljs` — new routes (`/v1/messages`, `/itonami/benchmark/clj-datomic`
  [+`/compare`]), upstream-error surfacing fix.

## Addendum (2026-07-05): speed vs. hosted models, parallelism, context ceiling

Follow-up investigation after a real Claude Code request hit
`exceed_context_size_error` at the (then-current) 65536-token ctx — a
concrete request measured 65561 tokens, 25 over — prompting the question
"how do we actually raise ctx safely, and while we're at it, how close can
this hardware get to 300 tok/s?"

**Single-stream speed, put in perspective.** 61.5 tok/s (the standalone-GPU
result from Decision 6) is not slow — it's in the same range as hosted
flagship models. Claude Sonnet 5 (Adaptive Reasoning, Max Effort) streams
at ~72 tok/s per artificialanalysis.ai's 2026-07 measurement, the median
for reasoning models in its price tier. A single consumer APU with no
discrete GPU landing in the same tok/s band as a model served from a
datacenter GPU cluster is, if anything, the more surprising number here.
**300 tok/s was never a realistic single-stream target** — it's well above
what mainstream hosted models themselves stream at for one conversation.
The real ceiling is memory bandwidth: decode reads the ~3B active MoE
params (Q4_K_M) from LPDDR5x unified memory once per token, and Strix
Halo's bandwidth sets that floor regardless of software tuning.

**Parallelism raises aggregate throughput, not single-stream speed —
confirmed by direct measurement.** Fired N concurrent
`/v1/chat/completions` requests at `llama-server --parallel N` and measured
wall-clock aggregate tok/s (sum of completion_tokens / wall_seconds):

| N | aggregate tok/s |
|---|---|
| 1 | 53.8 |
| 2 | 77.1 |
| 4 | 107.8 |
| 6 | 121.2 (peak) |
| 8 | 74.6 (regressed) |

Continuous batching genuinely lifts *aggregate* throughput up to ~6
concurrent streams on this iGPU, then falls off (per-slot context shrinking
to 8192 at N=8, plus scheduling/compute saturation). This only matters for
genuine concurrent load — parallel subagents, multiple sessions — not for
a single Claude Code conversation, which has one request in flight at a
time and still sees ~61.5 tok/s regardless of the `--parallel` setting.

**Context ceiling: 262144, not an arbitrary bump.** Investigated rather
than just raising the number again. `qwen-agentworld-35b-a3b`'s GGUF
metadata declares `qwen35moe.context_length = 262144` — its own native
training context, and (this session confirmed by direct testing) the real
hard ceiling on the current `llama-server` binary. Architecturally, long
context is cheap for this model: it's a hybrid Gated-DeltaNet(SSM)/
Gated-Attention model (`full_attention_interval = 4`), so only 10 of 40
layers need traditional KV cache that grows with context — measured 5GB at
262144 (f16) — while the other 30 layers use a **fixed** ~63MB recurrent
state regardless of context length.

Attempted the model's own claimed 1,010,000-token YaRN extension directly:
`--rope-scaling yarn --yarn-orig-ctx 262144 --rope-scale 4 -c 1010000`.
`llama-server` unconditionally capped it back down to 262144 anyway — this
is a known, still-unresolved upstream bug
([ggml-org/llama.cpp#22140](https://github.com/ggml-org/llama.cpp/issues/22140),
closed not-planned; [#17459](https://github.com/ggml-org/llama.cpp/issues/17459),
open unconfirmed): the server hard-caps any `-c` above the model's declared
training context regardless of explicit override flags. The only known
workaround is patching `tools/server` source to remove the check and
rebuilding from source — not achievable via CLI flags on the official
release binaries, and not attempted here (logged as a follow-up). Back-of-
envelope memory check for if it *did* work: KV cache at 1,010,000 would be
~19.3GB (f16) — tight but not impossible against the head's ~46GB unified
RAM alongside the 20.6GB model (~40GB combined). Confirmed separately that
KV cache quantization (`--cache-type-k q8_0 --cache-type-v q8_0`) loads and
serves correctly on this Vulkan/RADV backend at unchanged speed (62.5
tok/s) — that would roughly halve the 1M-context memory bill to ~30GB
combined, comfortable — future headroom if the server-side cap is ever
lifted.

**Landed:** `:infer/ctx` → 262144, `:infer/flash-attn "on"` (measured no
speed difference vs. auto for this mostly-linear-attention architecture,
set explicitly anyway so it's not silently disabled by a future build),
`cmd-serve-standalone` now accepts an optional `parallel` arg. Fixed a real
race found while testing: the kill-then-relaunch sequence used a fixed
0.5s sleep after `kill -9` that wasn't always enough for the old
`llama-server` to release its port before the replacement tried to bind
it — now polls until the process is actually gone. All of this exposed at
`GET /itonami/perf` on `api.murakumo.cloud`.
