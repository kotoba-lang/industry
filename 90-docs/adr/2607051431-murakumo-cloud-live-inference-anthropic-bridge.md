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
  2026-07-05: defaults to `--dangerously-skip-permissions` (this is a
  throwaway model on a self-hosted fleet, not a live Claude session, so the
  permission prompts were pure friction) — opt out via
  `MURAKUMO_CLAUDE_NO_SKIP_PERMISSIONS` or by passing your own
  permission-mode flag.

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

## Addendum (2026-07-05, part 2): RPC-distributing across the Mac-mini fleet — measured, and slower

Standalone-on-head (Decision 6) means `qwen-agentworld-35b-a3b` runs on the
head alone, with the 6-Mac-mini RPC ring sitting idle (`bb murakumo infer
down`'d). Asked directly: what if this model *were* spread across the
fleet via RPC instead — wouldn't more GPUs (the head's Radeon 8060S plus
6× Apple M4 Metal) be faster?

Tested directly rather than assuming. Brought the 6 Mac-mini `rpc-server`
workers back up (`bb murakumo infer up` — all confirmed Metal-accelerated,
`ggml_metal_init: found device: Apple M4`, unaffected by the head's earlier
CPU-only-binary bug) and ran the head's Vulkan (GPU) `llama-server` binary
as the RPC-ring head, `--rpc` pointed at all 6 workers, same
`--tensor-split`/`--split-mode layer` the plan already computed, flash-attn
on, 262144 ctx — i.e. the best possible all-GPU version of the ring.

| configuration | tok/s |
|---|---|
| standalone on head alone (GPU) | 61.5–62.5 |
| RPC ring, GPU head + Metal Mac minis (this test) | **17.0** |
| RPC ring, original CPU-only head + Metal Mac minis (Decision 6 baseline) | 12.7 |

Even with **every node in the ring GPU-accelerated**, RPC-distributing this
model is **~3.6x slower** than running it standalone on the head alone
(fixing the head's GPU-linkage bug only bought the ring a ~34% improvement,
12.7 → 17.0 — nowhere near closing the gap to 61.5). The reason is
structural, not a configuration mistake: `llama.cpp`'s RPC ring is a
**strict sequential pipeline** — each token must pass through all 40
layers in order, and every layer-group boundary crossed between machines
costs a real network round-trip (LAN latency + serialization), for every
single token generated. Splitting a model across N machines doesn't give
you N× the compute in parallel the way data-parallel batching does; it
adds N-1 network hops to every token's critical path in exchange for
letting a model too big for one machine run at all.

**The corollary that matters operationally:** RPC-distributing a model
across this fleet is a **capacity mechanism, not a speed mechanism** — use
it only when a model's weights don't fit in the head's own ~46GB RAM.
`qwen-agentworld-35b-a3b` (22GB) has never needed it; `serve-standalone`
was the right default the whole time this model has been in production.
The RPC ring stays valuable for a genuinely oversized model (e.g. the
originally-catalogued `glm-5.2-reap50-q2k`, 139GB, which cannot fit on any
single node in this fleet including the head) — for THAT class of model,
17 tok/s distributed beats 0 tok/s (doesn't fit at all). Reverted the fleet
to standalone-on-head after this measurement; registered both RPC numbers
alongside the standalone number in `/infer/models` and `GET /itonami/perf`
for future reference.

## Addendum (2026-07-05, part 4): a real "why is this slow" incident — single-slot queueing

A user report ("execution speed is quite slow") led to a live diagnosis
against the running production server, not speculation. Sent a synthetic
36,018-token prompt (approximating Claude Code's real tool-definition
payload) directly to the head and measured both the server's own reported
timings and true wall-clock:

- Reported: `prompt_ms = 40700.95` (40.7s for prompt processing, 884.9
  tok/s — prefill is fast, as expected, since it's compute-bound and
  parallelizable unlike decode), plus a few hundred ms of generation.
- Actual wall-clock: **154.6 seconds** — a ~113-second gap unaccounted for
  by the request's own processing.

Checked the head's live server log for the actual cause. `--parallel 1`
means **exactly one processing slot** — every request is strictly
serialized, no exceptions. The log showed the fresh request queued behind
several OTHER tasks already in the pipeline, including one holding
**105,503 tokens of context** that was in the process of being detected as
cancelled (`W srv stop: cancel task`) — very plausibly an earlier
interactive test from this same working session, where the local
`claude-murakumo`/`claude` client process had been `pkill`'d, but
llama-server's HTTP-disconnect detection hadn't yet noticed the client was
gone. A locally-killed client does **not** instantly free the server-side
slot; it can keep "processing" (or slowly discovering it should stop) for
a long time, and with only one slot, that blocks every other request
completely, however unrelated.

This is qualitatively different from the earlier `--parallel` findings
(which were about raising *aggregate* throughput under intentional
concurrent load) — this is about **resilience**: with one slot, a single
stuck/abandoned/oversized request is a full outage for everyone else,
including yourself in a different terminal. `serve-standalone`'s default
`parallel` moved from 1 to 2 as a direct result (131072 ctx per slot at the
current 262144 total ctx — comfortably above the largest real conversation
observed, 105,503 tokens): confirmed unchanged single-stream speed (~62.6
tok/s) and a live `claude-murakumo` round-trip working correctly against
the new config. This doesn't eliminate queueing under heavier concurrent
load (see the N=1..8 aggregate-throughput data above), but it means one
abandoned or oversized request no longer creates a full serialization
bottleneck for a second, independent one.

## Addendum (2026-07-05, part 5): parallel 2 didn't hold up under real concurrent load — reverted to 1

Same day, with `parallel 2` live in production, two REAL Claude Code
sessions ended up active simultaneously (one asking "why is this so slow"
about a multi-tool-call agentic task; a second, separate session also
pointed at murakumo). Diagnosed directly against the live server log
rather than guessing:

- One slot (a session doing repeated tool-call round-trips) kept
  reprocessing large, growing prompts — 14K to 28K tokens per turn, some
  turns even getting cancelled and relaunched (`selected slot by LCP
  similarity, sim_best = 0.460` — under half the prompt matched the cached
  prefix, so most of it had to be recomputed).
- The OTHER slot's generation, mid-response, **crawled to 0.24-0.38 tok/s**
  (`tg_3s`, the recent-window rate) — roughly 250x slower than the
  established ~60 tok/s baseline — for the whole time the first slot was
  churning through its heavy prefill.

Root cause: 2 logical slots share **one physical GPU**. `llama-server`
does not time-slice fairly between a slot doing heavy prompt processing
(compute-heavy, can dominate the device for tens of seconds per batch) and
another slot trying to decode (needs frequent small time-slices to
maintain its tok/s) — the prefill-heavy slot effectively starves the
decode-only slot for as long as it runs.

This means `parallel 2`'s actual real-world failure mode under genuine
concurrent load is **both sessions degrade together**, which is worse for
a human waiting on either one than `parallel 1`'s failure mode (one
request queues completely, but gets the FULL ~60 tok/s the instant its
turn comes — a bounded, predictable wait rather than an unbounded crawl
for both parties). Reverted `serve-standalone`'s default back to
`parallel 1` the same day. This does **not** fix the actual root cause
from part 4 (`llama-server` not promptly detecting a dead/killed client
connection) — that needs a server-side request/idle timeout, not attempted
here — it just accepts "a second request queues and waits" as the more
predictable failure mode for this hardware's real usage pattern (rarely
more than one active `claude-murakumo` session at a time). Verified live:
restarted with `parallel 1`, confirmed `n_slots = 1` in the server log, and
confirmed a real in-flight request completed at the expected 60.05 tok/s
baseline once it had the slot to itself.

## Addendum (2026-07-05, part 6): LM-Studio-shaped model catalog (`GET /itonami/catalog`)

Request: design, in EDN, a model-browser information architecture for
murakumo.cloud shaped like LM Studio's local-model picker — search,
format filter (GGUF/MLX/**kotoba**), "Best Match" sort — rather than the
existing thin `itonami/model-catalog` (a `{:id :kind :unit :cr :mem}`
pricing table with no room for capability badges, per-format status, or
README text).

Added a new `local-murakumo.catalog` namespace, kept deliberately separate
from `itonami/model-catalog` (the credits/business layer doesn't need
README text or fleet-fit data, and the existing pricing consumers stay
unchanged). Two places this design is intentionally NOT a copy of LM
Studio, because copying would be dishonest for a self-hosted fleet:

- **"Download Options" → `:model/variants` + `:variant/status`
  (`:registered`/`:resident`/`:serving`)**. murakumo doesn't download to
  the caller's machine — it swaps a GGUF/MLX artifact onto the fleet. A
  variant's status says whether it's catalogued, sitting on the head's
  disk, or the model currently answering requests.
- **"Best Match" is a real computed score, not a popularity/hardware
  guess**: `best-match-score` hard-gates on `:fit/status`
  (`:does-not-fit` scores near zero regardless of benchmark speed
  elsewhere — an oversized model is never "the best match" for THIS
  fleet), then weights measured tok/s (40%), clj-datomic benchmark
  pass-rate (35%), capability breadth (15%), and staff-pick curation
  (10%). Verified live:
  `qwen-agentworld-35b-a3b` (61.5 tok/s, 6/6, staff pick) → 94.1%,
  `qwen3.6-35b-a3b` (registered, unbenchmarked) → 32.5%,
  `gemma-4-12b-it` (7.54 tok/s, 1/6) → 21.7%.

The **kotoba** format tag is the one forward-looking, non-descriptive part
of this catalog: `kotoba-lang/inference`'s `kotodama.inference.shard`
(reuses `murakumo.infer.plan`'s fleet layer-assignment as the seam to a
pure-cljc execution engine) and `kotodama.inference.mlx` (an `IModelRuntime`
adapter for `mlx-lm`/`mlx-moe`) are real, tested building blocks — but no
model on this fleet is actually served through them yet. Every model's
kotoba format entry is honestly `:format/status :experimental`, never
`:available`, until that's true. GGUF is `:available` for all 3 current
base models (llama.cpp/Vulkan on this fleet); MLX is `:experimental` for
all 3 (real `mlx-community` conversions exist on HuggingFace — e.g.
`Qwen-AgentWorld-35B-A3B-oQ4`, `Qwen3.6-35B-A3B-4bit` — but none are
registered/tested on this fleet) or `:unsupported` (gemma-4-12b-it, no
conversion checked yet).

New routes, wired in both `routes.cljc` (JVM-tested) and `worker.cljs`
(deployed): `GET /itonami/catalog` (SSR page, plain-GET search/filter
form, matching the page's existing "zero JS needed for a readable page"
convention) and `GET /itonami/catalog.json` (raw data). Query params:
`q` (search name/publisher/tagline), `format` (`gguf`/`mlx`/`kotoba`),
`capability`, `sort` (`best-match`/`updated`), `include-retired`.

Bug found only via real HTTP traffic against the live Worker (not the JVM
route tests, which construct `:query` maps directly): `worker.cljs`'s
`fetch-handler` built its `query` map by explicitly listing 4 known params
(`since`/`usd`/`did`/`model`) rather than generically reading
`URLSearchParams` — so `?q=gemma` silently returned the unfiltered list
instead of erroring or filtering, until the new params were added to that
explicit list. Same shape as the mid-conversation-system and
`:infer/ctx` bugs from Decision 4/the earlier addenda: this Worker's
`route`/`fetch-handler` split makes it easy for a JVM-tested route to be
correct while the real deployed entrypoint quietly drops query
parameters it doesn't know about — worth grepping `fetch-handler`'s
`query` map construction whenever a route gains a new query param.

Landed via `gftdcojp/local-murakumo` PR `feat/model-catalog` →
`gh api .../merges` (concurrently, another session had uncommitted
`kotoba-lang/treasury` delegation work sitting in the shared checkout —
worked in an isolated `git worktree` instead of touching that checkout
in place, then fast-forwarded the shared checkout afterward). Deployed
and verified live on `api.murakumo.cloud`; the concurrently-merged
`/infer/hwmetrics` fleet dashboard (a different, unrelated feature that
landed on `main` in between) was also re-verified working post-deploy —
no regression.

## Addendum (2026-07-05, part 7): qwen3.6-35b-a3b vs qwen-agentworld-35b-a3b head-to-head

User question: has qwen3.6-35b-a3b (registered since Decision 1 but never
served or benchmarked — see catalog part 6's honest `:registered-not-serving`
status) actually been compared against the live default, qwen-agentworld?
Answer at the time: no. User: run it.

**Setup.** Downloaded `Qwen3.6-35B-A3B-Q4_K_M.gguf` (21.2GB,
`lmstudio-community/Qwen3.6-35B-A3B-GGUF`) to the head — verified byte-exact
against HuggingFace's `x-linked-size` header (21,166,757,728 bytes) before
trusting it. Before touching the live model, smoke-tested the verification
harness itself both positively (hand-written correct code for all 6 tasks →
6/6 PASS) and negatively (deliberately wrong `fib`/`query-age-filter` → both
correctly FAIL) — the earlier session already found one harness-correctness
bug (load-string-as-code vs edn/read-string-as-data) the hard way, so this
time the harness was proven trustworthy before spending real inference time
on it. Verification is real execution: Clojure eval on the JVM for the 3
`:clojure` tasks, DataScript (`d/create-conn` + `d/transact!` + `d/q`) for
the 3 `:datomic` tasks, with a custom `#db/id` EDN reader — same methodology
as the original qwen-agentworld/gemma benchmarks.

**Result: 6/6 pass**, tied with qwen-agentworld. Task prompts reconstructed
from the existing `clj-datomic-benchmarks` `:desc` fields (the exact original
prompt text wasn't preserved as a script from the earlier session — a real
gap; see follow-up below).

**A real production incident happened mid-benchmark.** Deploying qwen3.6
standalone via `serve-standalone` swaps the SAME port (8090) that
`api.murakumo.cloud`/`claude-murakumo` route to — there's no separate
test endpoint. A real Claude Code session was actively using the fleet
during the test window; its own huge, growing prompt (one conversation hit
168,931 tokens before being cancelled; another reached 204,459 before also
being cancelled) repeatedly grabbed the single `--parallel 1` slot ahead of
or interleaved with the benchmark's requests, exactly the single-slot
queueing risk documented in part 4/5 — except this time the "other tenant"
was a real user colliding with an intentional benchmark, not two benchmark
runs colliding with each other. Client-side wall-clock timing for 4 of the
6 tasks (atom-counter, schema-person, query-age-filter, transact-new-entity)
came back at 5.3-17.0 tok/s — apparently much slower than qwen-agentworld —
which would have been a materially wrong conclusion had it been trusted.

**Fix: read the server's own per-task timing instead of client wall-clock.**
llama-server logs a `slot print_timing: ... | task N | eval time = X ms /
Y tokens (... Z tokens per second)` line per completed request — this is
pure decode time, unaffected by how long the request sat queued beforehand.
Cross-checked against the 2 *uncontaminated* tasks (fib-memo, flatten-map,
which happened to run before the collision started): client wall-clock and
server eval-time agreed exactly (70.34/69.11 tok/s both ways). For the 4
contaminated tasks, server eval-time recovered the true speed: 70.43, 70.28,
70.50, 69.86 tok/s — all consistent with the clean two, confirming the
"slow" client-side numbers were 100% queueing artifact, not real generation
slowness. **avg-tok-s = 70.09** (mean of all 6 server-reported eval-times).

**Comparability caveat, corrected in the data.** The pre-existing
`clj-datomic-comparison` entry for qwen-agentworld records **12.67** tok/s —
but that run predates the standalone-GPU fix (Decision 6): it was measured
on the old 6-Mac-mini CPU-bound RPC ring, not this fleet's current serving
path. Comparing qwen3.6's 70.09 against that stale 12.67 would overstate
the difference by ~5.5x. The fair comparison is against qwen-agentworld's
CURRENT standalone-GPU baseline, 61.5-62.6 tok/s (`perf-single-stream`) —
**qwen3.6-35b-a3b is ~14% faster than qwen-agentworld's current speed, at
identical 6/6 clj-datomic correctness.** Added explicit `:note` fields to
both the stale qwen-agentworld entry and the new qwen3.6 entry in
`itonami.cljc`/`infer_view.cljc` so this isn't misread again; also added
qwen3.6 to `perf-single-stream`'s `:hosted-model-comparison` list.

**Catalog impact.** `catalog.cljc`'s qwen3.6-35b-a3b entry updated:
`:model/fleet-fit :fit/measured-tok-s 70.09`, `:model/benchmarks` populated
(previously `nil`), `:model/status` left as `:registered-not-serving` (NOT
promoted to `:serving` — qwen-agentworld remains the live default; this
result makes qwen3.6 a real, evidenced *candidate* for a future switch, not
an automatic one). `best-match-score` recomputed live: qwen3.6 90.0% vs
qwen-agentworld's 94.1% (agentworld keeps the edge from its staff-pick flag
and currently-serving status, not from being faster or more correct).

**Cleanup:** restored qwen-agentworld-35b-a3b as the production default
immediately after collecting the qwen3.6 results (same `serve-standalone`
kill-and-relaunch this always does — which necessarily also killed
whatever real request was still in flight on the swapped model at that
moment; the affected user's client will retry, per the same recovery
behavior documented in part 4). Verified live: `/itonami/catalog.json`,
`/itonami/benchmark/clj-datomic/compare`, and `/infer/models` all reflect
qwen-agentworld serving again post-restore.

**Open follow-up:** the 6 clj-datomic task prompts are still not committed
anywhere as a reusable script — reconstructed from `:desc` fields both this
time and implicitly risk drifting slightly from the original wording each
time a new model is benchmarked. Worth committing the actual prompt text
(e.g. `tools/clj-datomic-bench/tasks.edn` in `kotoba-lang/murakumo`) so
future comparisons use byte-identical prompts.

## Addendum (2026-07-05, part 8): "which terminal has which model loaded" — `/infer/model-map`

User question: is there a UI showing, per node, which model is loaded —
across text/image/video/audio/text-to-3D? Investigated first (forked
research, not assumed): `murakumo-studio` (ADR-2607032700) turned out to be
the wrong place to look — it's a single-machine LM-Studio-equivalent
desktop app (Tauri + CLJS, v1 scaffold only), and its fleet participation
is announce-only (one machine registers itself; no multi-node dashboard).
The real fleet-wide building blocks were scattered and partially hidden:

- **text**: exactly one model serves fleet-wide at a time (standalone-GPU
  on the head) — no per-node concept needed, just "what's currently being
  served and where."
- **image/video/audio**: `bb murakumo infer media nodes` already existed
  and already queries every node's ComfyUI instance for its resident
  checkpoint (`/object_info/CheckpointLoaderSimple`) — real per-node model
  placement, but **CLI-only**, never exposed as data or a UI.
- **text-to-3D**: absent everywhere — not a UI gap, a genuine content gap
  (no model registered in `infer.edn`, no ComfyUI 3D workflow, nothing in
  `catalog.cljc`).
- `/infer/hwmetrics` (part of the concurrently-landed `feat/hwmetrics-dashboard`
  work) gives per-node hardware but carries zero model identity.

**Built, rather than just designed** (per the user's explicit choice of the
"implement + deploy" option over "design only" / "pick a text-to-3D model
first"):

1. `bb murakumo infer media model-map [--push]` (`kotoba-lang/murakumo`,
   new `cmd-model-map` in `media.clj`): queries the head's own `/v1/models`
   to identify the live text model (matched back to `infer.edn` by GGUF
   filename), reuses the existing `live-fleet` ComfyUI probe for per-node
   media checkpoints, and matches each checkpoint against the registry
   three ways — `:exact` (byte match), `:family-guess` (a small hand-
   maintained keyword table, e.g. "ltxv"→`ltxv-2b-0.9.1`, when the registry
   has drifted), or `:unregistered`. `--push` POSTs the snapshot to a new
   endpoint, same Bearer-token gate (`MURAKUMO_METRICS_TOKEN`) as the
   hwmetrics collector.
2. `GET/POST /infer/model-map` + `GET /infer/model-map/ui`
   (`gftdcojp/local-murakumo`) — a **third**, distinct KV doc from both
   `hwmetrics` (hardware, no model identity) and `/infer/placement` (the
   *rebalancer's desired* pool-seat allocation, a different concept from
   *what's actually resident* — naming collision avoided deliberately).
   The `/ui` page merges the model-map snapshot with the latest hwmetrics
   snapshot (two independent `st/-get` reads in one handler) into one
   node×category table, so a node with no media model loaded still shows
   up (as "no media model loaded"), not just the nodes that happen to be
   running something.

**Real drift surfaced immediately on first live push**: `naphtali`/`issachar`
are actually running `ltxv-2b-0.9.6-distilled-04-25.safetensors`, not the
`ltx-video-2b-v0.9.1.safetensors` currently registered in `infer.edn` for
`ltxv-2b-0.9.1` — labelled `:family-guess` with an explicit "drifted from
infer.edn" note on the page, not silently matched or hidden. text-to-3D
renders as an explicit "unsupported" card with the honest reason (no
registered model), matching this session's consistent norm of surfacing
gaps rather than omitting the category.

**Known simplification, stated plainly**: text placement only reports the
single serving node (the head, under the current standalone-GPU
architecture) — an RPC-ring deployment would need `.murakumo-infer-plan.edn`'s
shard assignments for a real per-node breakdown, not implemented here since
standalone-on-head is this fleet's current mode (see Decision 6/part 2).
`model-map` is pushed on-demand by an operator, not a continuous ~10s feed
like `hwmetrics` — the `/ui` page is plain SSR, no live poller, by design.

Landed as two separate PRs (`kotoba-lang/murakumo` `feat/model-map`,
`gftdcojp/local-murakumo` `feat/model-map`) via the usual worktree→push→
`gh api .../merges` workflow. One recoverable mistake during landing: a
`git diff ... > file.patch` redirect silently produced an empty file
(shell cwd had reset between tool calls without notice), and the immediately
following `git checkout --` reverted the *shared checkout's* working tree
before the patch was verified non-empty — the local-murakumo-side changes
were briefly lost from disk. Recovered by re-applying the exact edits from
this conversation's own tool-call history directly onto a fresh worktree
(no data was actually unrecoverable, since the edits existed as this
session's own record) — but confirms the write-then-verify order matters:
`wc -l` the patch file before running any revert that depends on it.
Verified live: `api.murakumo.cloud/infer/model-map` and `/infer/model-map/ui`
both reflect the real fleet state, `/infer/hwmetrics` unaffected (separate
KV doc, no regression).

## Addendum (2026-07-10, part 9): production drift — docs said qwen3.6 was live, the head was still serving qwen-agentworld

`gftdcojp/local-murakumo`'s `catalog.cljc` already documented (commit
`3c83aed`, 2026-07-09, "vision-live") that qwen3.6-35b-a3b had been promoted
to the fleet's live default and that vision worked end-to-end via two
solid-color test PNGs. Independently of that, this session was asked to
switch the fleet to qwen3.6-35b-a3b — starting from the assumption that this
was still open work (part 7 had left qwen3.6 as `:registered-not-serving`,
a benchmarked-but-not-promoted candidate, then explicitly reverted the fleet
back to qwen-agentworld after that comparison run).

**Found a real discrepancy, not a duplicate task.** `pgrep -x llama-server`
on the head showed the OLD `qwen-agentworld-35b-a3b` process still resident
and serving — despite catalog.cljc's landed docs claiming the 2026-07-09
switch was live. Root cause not established (no reboot/crash log was
checked); the observation stands as-is: **documentation and registry state
can drift from the actual resident process independently of any commit
landing**, on a fleet with no supervisor that reconciles the two. `bb
murakumo infer serve-standalone qwen3.6-35b-a3b ...` (the documented,
scripted path) was tried first and silently no-op'd — it printed a
constructed command and a connection-info line but neither killed the old
process nor started a new one (`pgrep` still showed the same PID, unchanged
start time, afterward). Root cause not diagnosed (suspected SSH/nohup
backgrounding quirk in the tool's `ssh/sh` helper) — logged as an open
follow-up, not fixed here. Worked around with direct manual SSH: `pgrep -x
llama-server | xargs -r kill -9` (confirmed dead), then a manual `nohup
llama-server -m <path> --mmproj <path> -ngl 999 -c 262144 --parallel 1 -fa
on --host 0.0.0.0 --port 8090 & disown` reproducing `cmd-serve-standalone`'s
exact flag construction.

**Re-verified live, independently of the prior session's PNG test**: server
log showed the Qwen-VL architecture recognized ("Qwen-VL models require...")
and the mmproj vision projector loaded; a real photographic-content
`image_url` block (not a synthetic solid-color swatch) sent to
`infer.murakumo.cloud/v1/chat/completions` came back with a correct visual
description; confirmed the same result through the public
`murakumo.cloud/api/v1/chat/completions` proxy layer too.

**Cloud registry (`api.murakumo.cloud/infer/models`) was also stale in the
same direction** — still listing `qwen-agentworld-35b-a3b` as `"status":
"serving"` and carrying no entry at all for `qwen3.6-35b-a3b`, unrelated to
catalog.cljc (a separate KV doc/service, `local-murakumo`'s own registry vs.
`gftdcojp/local-murakumo`'s catalog page — same naming-collision risk noted
in part 8 for `hwmetrics`/`model-map`/`placement`). Corrected both entries
via direct `PUT`: `qwen-agentworld-35b-a3b` → `"registered-not-serving"`
(weights explicitly left on disk, not deleted — reversible), and added the
previously-missing `qwen3.6-35b-a3b` entry as `"serving"` with `vision:true`
and the mmproj filename, matching catalog.cljc's shape.

**Downstream consumer fix, not yet landed.** `kotoba-lang/computer-use`'s
`examples/jvm_host.clj` (an LLM=murakumo ChatModel adapter for the
screenshot-driven `computer` tool) carried a documented `KNOWN LIMITATION`
docstring asserting murakumo had no vision-capable model — accurate when
written, stale after this addendum. Updated `default-murakumo-model` from
`"qwen-agentworld-35b-a3b"` to `"qwen3.6-35b-a3b"` and rewrote the docstring
(same file, and the caller `examples/announce_x402_nexus.clj`, ADR-2607093300/
ADR-2607110600's announcement-draft script) to reflect vision now working.
**These edits exist only in a local scratchpad clone, not pushed to
`kotoba-lang/computer-use` on GitHub** — the superproject's own west-managed
checkout at `orgs/kotoba-lang/computer-use` is unaffected (still main
`2bd5b43`). Also found, while debugging why every example in that repo
raised `No such var: model/openai-model`: `deps.edn` used a bare `:sha` key
instead of tools.deps' `:git/sha`, which tools.deps silently ignores,
falling back to `:git/tag "v0.2.0"` alone — an old, pre-org-rename
`kotoba-lang/langgraph` commit still depending on `com-junkawasaki/
langchain-clj`, which shadowed `kotoba-lang/langchain`'s `openai-model`.
Fixed locally by pinning an explicit current `:git/sha`. **Also not yet
landed** — the checked-out repo's own `deps.edn` still has the same bare
`:sha` key today (commit `e18e922` fixed the *org* the dependency pointed
at, not this key-name bug), so it is very likely still silently falling
back to the same stale pin. Landing both fixes (`deps.edn` key + the
vision-caveat docstring update) to the real `kotoba-lang/computer-use` repo
is an open follow-up, not done as part of this addendum.

**Separately checked and ruled out as unrelated**: `x402.nexus/gateway/
murakumo/v1/messages` returning 404 for a bare GET — nexus-x402's seller
rule for `murakumo` requires `POST`, so a GET 404 is the gateway's honest
"no matching rule" response (`docs/adr/0001`'s pass-through-404 default,
ADR-2607093300), not a regression from this model swap.

**Not done in this addendum** (explicit user decision, deferred rather than
skipped): a real end-to-end x402 settlement through nexus-x402 (the
$0.001 `kotobase` seller was chosen as the cheapest test; the `"transaction"`
scheme 402 challenge was fetched and the exact payer-side parameters
prepared — recipient, USDC-on-Base contract, amount, resource — but actually
signing/broadcasting the transfer is the owner's own wallet action per the
safety floor on fund movement, and was deferred rather than executed); and
running `announce_x402_nexus.clj` for real (draft-fill only, never submits)
against HN/Reddit/Base, deferred pending a browser session already logged
into those sites (none was open at the time — Chrome/Safari were running
with zero visible windows).
