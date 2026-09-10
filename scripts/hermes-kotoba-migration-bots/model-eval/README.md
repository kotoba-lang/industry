# `model-eval` — which model, measured, for the clj → Kotoba migration

The three `hermes-kotoba-migration-bots` jobs are pinned to `z-ai/glm-5.3-flash`
through `openrouter-free`. That pin was never compared against anything. This
directory is the harness that can compare it, and the first measurement taken
with it (2026-09-10, `murakumo-main` = `qwen3.8-27b-throughput-b70`).

## The number that decides a model

**Cost per ACCEPTED migration** — not $/token, and not pass rate. A model at
half the price with a third of the accept rate is more expensive. The registry's
own note on `gemma-4-12b-it` is the trap written down: *"slower (7.5 vs 12.7
tok/s) **and** needs a much larger token budget to converge"* — the throughput
number alone had the sign backwards on total cost.

## The oracle

Not the model's word, and **not `it compiles`**. For each task the harness owns
the input set, computes ground truth by running the **original `.cljc` under
nbb**, then runs the model's **compiled artifact** on the same inputs and
compares. The model never sees an expected value.

    .cljc ──nbb──────────────► expected values
    .kotoba ──amu compile──► .mjs ──node──► actual values ──compare──► verdict

This is load-bearing. In the first run `time-pad4` produced a module that
**compiled cleanly three times out of three and returned `007` where the Clojure
returns `0007`** — an off-by-one in the `cond` ladder. A compile-only gate would
have accepted all three. Verified in the other direction too: a deliberately
wrong `pad2` (`< 3` instead of `< 2`) compiles with exit 0 and is caught only by
running it.

Verdicts are deliberately distinct — `:parity-not-measured` is **not** a pass:

| verdict | meaning |
|---|---|
| `:pass` | compiled, ran, every value agreed with the Clojure |
| `:parity-fail` | compiled and ran, at least one value disagreed |
| `:compile-fail` | amu rejected it |
| `:parity-not-measured` | compiled, but this return shape has no cross-runtime comparison defined |
| `:no-code` / `:api-error` | no extractable module / the endpoint did not answer |

## Running it

```sh
# one model, the fixed 12-task set, 3 reps
nbb eval-runner.cljs --tasks tasks.edn --models murakumo-main --reps 3 \
    --out results-qwen.edn

# two models, same tasks
nbb eval-runner.cljs --tasks tasks.edn --models murakumo-main,basho-320-18b --reps 3

# feed compiler diagnostics back on a compile-fail (measured: does not help, see below)
nbb eval-runner.cljs --tasks tasks.edn --models murakumo-main --reps 3 --repair 2

nbb compare.cljs "repair=0" results-qwen.edn "repair=2" results-qwen-repair.edn
nbb where.cljs results-qwen.edn      # where the token budget went
nbb batch-test.cljs <reps> <n-filler>  # N functions in ONE call
nbb cachetest.cljs                   # does the route reuse a prompt prefix
```

`tasks.edn` is a **fixed** set, drawn from `candidates.cljs` with a throwaway
`--seen` ledger. Fixing it matters: `candidates.cljs` advances its ledger on
every run, so running it per model hands each model different work — which
returns *the model was cheap* and *the tasks were easy* as the same number.

`--reps` must be ≥3. At temperature 0.2 `string-pad-right` still flipped 3/3
between two runs. A single verdict is a coin flip, not a rate.

## What was measured, 2026-09-10, `qwen3.8-27b-throughput-b70`

12 tasks × 3 reps = 36 runs.

    pass 6 · parity-fail 3 · compile-fail 13 · parity-not-measured 14
    4,495 tokens and 57.9s of model latency per accepted migration
    6/9 (66.7%) on the three tasks the harness could actually grade

### Repair loop: negative result — do not add it

`--repair 2` feeds the compiler's own diagnostic back (never expected values, so
a `:parity-fail` is terminal by design).

| | repair=0 | repair=2 |
|---|---|---|
| pass | **6** | **6** |
| tokens | 26,971 | 51,203 |
| tokens / accepted | 4,495 | **8,534** (1.90×) |
| seconds / accepted | 57.9 | **104.0** (1.80×) |
| passes that needed a repair round | — | **0** |

It converted `compile-fail` into *compiles* on `kotoba-io-len` (3/3) and
`netcap-surface-to-cap` (1/3) — but only on tasks the harness cannot grade, so
what it bought was a module nobody verified. Every task that **could** be graded
already compiled on the first attempt (all `r0`). And the failure that actually
costs you is invisible to it: `time-pad4` compiles, so there is no diagnostic to
feed back.

### Where the budget actually goes

    PROMPT tokens  24,228  (89.8% of all tokens)   ← the rules block, re-sent per task
    OUTPUT tokens   2,743  (10.2%)
    spent on tasks that never passed once: 22,384  (83.0%)

Two levers, both measured, neither of them "use a better model":

**1. Shape pre-filter — 2.25×, loses no passes.** Drop candidates whose Clojure
signature touches map / atom / set / char / keyword.

    unfiltered (12 tasks)   runs=36 pass=6 tokens=26,971  tok/accept=4,495
    shape-filtered (5)      runs=15 pass=6 tokens=12,004  tok/accept=2,001
    the dropped 7           runs=21 pass=0 tokens=14,967  tok/accept=INF

The dropped seven burned 55.5% of the budget for zero passes, and two repair
rounds did not rescue them. `candidates.cljs` ranks by size and interop markers;
it does not ask whether the value shapes are expressible in today's profile.

**2. Batching 3–4 per call — 2.46×, verdicts identical.** Same three tasks:

| | per-task | batch-of-3 |
|---|---|---|
| pass | 6/9 | 6/9 (same verdicts, task for task) |
| tokens | 6,985 | 2,846 |
| tokens / accepted | 1,164 | **474** |
| seconds / accepted | 17.6 | **10.4** |

The cap is operational, not qualitative:

    batch-of-3   ~20.8s  OK
    batch-of-4   ~25.9s  OK           525 tok/accept
    batch-of-6   ~30.2s  {"error":"murakumo fleet unreachable: Error: connection_refused"}  2/2

Small requests (6.2s) and a 12-item batch of *trivial* functions (30.2s, HTTP
200) both succeed, so the fleet is healthy — only calls whose generation crosses
~30s are refused. Raising that ceiling (streaming, or a longer-timeout route)
would allow further amortisation.

No combined filter+batch multiplier is recorded. Two of the five surviving tasks
(`string-pad-left`, `string-pad-right`) depend on `kotoba.string.pad`, which is
not on the harness classpath, so their ground truth is UNAVAILABLE and their
pass/fail cannot be counted. Multiplying the two measured factors would state a
precision that was not measured.

### Prompt caching does not reduce billed tokens

Same 1,297-token prefix, three times: `cached=0` every time, while wall clock
went 7,512 → 4,352 → 4,340 ms. There is prefix reuse in the compute path that
the token accounting does not report. Since murakumo is owner-operated and
marginal cost is time rather than dollars, this is real — but it is not a way to
send fewer tokens. Batching is.

## `basho-320-18b` was NOT measured

Probed four times over ~2 hours:

    POST junkawasakicom--basho-320-18b-serve.modal.run/v1/chat/completions
      → HTTP 404  modal-http: workspace ac-qrwBVal0OY1NvoOHAJjUPJ is disabled
    via api.murakumo.cloud with model=basho-320-18b
      → HTTP 502  (the gateway proxies to Modal and gets the same non-JSON body)

`modal app list` authenticates and reports `basho-320-1…` as `deployed`, so this
is an account-level state on the Modal side (billing / spend limit is the usual
cause), not an app fault. The model registry declares it `serving` / `fresh`
(verified 2026-09-04) — a declared status that disagrees with measurement.

When it is back, `--models murakumo-main,basho-320-18b` runs the identical 36.

## Known limits of this harness

- `--top` in `candidates.cljs` is parsed and **never used** (only `--pool` is), so
  a fixed task set is built by running it N times against a throwaway `--seen`.
- Cross-runtime comparison is defined for scalar returns only. Map / char /
  keyword shapes report `:parity-not-measured`, never `:pass`.
- Ground truth needs the original namespace to load under nbb. A task whose repo
  pulls a sibling repo reports `UNAVAILABLE` rather than being silently skipped.
