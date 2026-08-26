# Kotoba stack performance — measured comparison (2026-08-26)

**Measured:** 2026-08-26 08:58 JST on Apple M4, Darwin arm64, Node v26.7.0.  
**Host load:** load average **17.2 / 23.7 / 25.4** (contended — all engines inflated; trust **ranking** over absolute ns).  
**amu commit:** `6889fa736750` (clean).  
**Raw JSON:** `90-docs/performance/runs/2026-08-26/kotoba-runtime-20260826.json`, `kotoba-compile-20260826.json`.

---

## Executive summary

| Domain | Today vs world leaders | Verdict |
|---|---|---|
| **amu native runtime** (integer kernel) | **1.04× Rust** median | **Competitive** — same class as Rust/Go on this workload |
| **amu compile** (loaded) | ~394 ms wasm32 / ~702 ms aarch64 per small example | **Not #1** — JVM/nbb startup dominates cold path |
| **amu Wasm32** | 5.25× Rust | **Not #1** — instance admission + fuel contract |
| **kototama** (JVM/Chicory path) | ~3.4 s cold process per tiny guest | **Not #1** — compat path; browser-native is first-class |
| **aiueos** | No published ns/boot/syscall table | **Unmeasured** — correctness gates only today |
| **kotoba language** (end-to-end app) | No representative app benchmark | **Unmeasured** |

**Honest headline:** on a narrow integer microbenchmark, **amu native is already in the Rust band**. World #1 requires winning **compile latency, sandboxed Wasm, OS primitives, and real application workloads** — not just this kernel.

---

## 1. Runtime — `kotoba.runtime-comparison/v1`

**Contract:** 8-round i64 quotient/remainder mix, `n=200`, 10k warmup + 100k timed calls, 5 runs.  
**Workload:** `amu/bench/runtime-comparison/kernel.kotoba` (single live value — fits in registers).

### Steady-state (median ns per kernel call)

| Rank | Engine | Median (ns) | vs Rust | Max RSS (median) |
|---:|---|---:|---:|---:|
| 1 | **Rust** (rustc 1.97.1 -O) | **22.64** | 1.00× | 1.44 MiB |
| 2 | **Amu native** (AOT kexe, bench runner) | **23.62** | **1.04×** | **1.30 MiB** |
| 3 | Go 1.25.6 | 31.67 | 1.40× | 3.91 MiB |
| 4 | Mojo 1.0.0 | 43.42 | 1.92× | 11.09 MiB |
| 5 | TypeScript (Deno 2.4.5) | 69.70 | 3.08× | 56.02 MiB |
| 6 | Clojure JVM (warmed) | 76.03 | 3.36× | 121.81 MiB |
| 7 | TypeScript (Node 26.7) | 106.56 | 4.71× | 50.36 MiB |
| 8 | **Amu Wasm32** | 118.90 | 5.25× | 56.59 MiB |
| 9 | ClojureScript (advanced) | 461.75 | 20.39× | 51.75 MiB |
| 10 | CPython 3.14.5 | 1957.22 | 86.44× | 14.72 MiB |

**Common result:** `1830338420` (all engines agreed).

### Build time (one-off, this host)

| Artifact | Build (ms) |
|---|---:|
| amu Wasm32 compile | 2,044 |
| amu native compile + extract | 3,464 + 2,869 |
| Rust executable | 8,410 |
| Go executable | 6,292 |
| ClojureScript release | 17,310 |

### What this does **not** prove

- Allocation-heavy code, strings, closures across calls, capability I/O, concurrency.
- Production native path (supervisor, fork, W^X loader) — bench uses direct mapped invoke.
- `kernel_wide` / `kernel_deep` (multi-lane register pressure) — see `amu/docs/performance.md`; LLVM parity reported on quiet hosts after allocator fixes, **not re-run today**.

---

## 2. Compile — `kotoba.performance-baseline/v1`

**Fixture:** `amu/examples/i64-semantics.kotoba`, 3 cold-process runs.

| Target | Process-cold median | Loaded compiler median | Artifact |
|---|---:|---:|---:|
| wasm32 | **1,949 ms** | **394 ms** | 529 B |
| aarch64 | **2,051 ms** (startup line in worker section) | **702 ms** | 3,049 B |

**Phase breakdown (wasm32 cold, typical sample):** frontend ~195 ms, KIR lower ~60 ms, wasm emit ~20 ms — **~1.5 s is Node/nbb process + namespace startup**, not compiler core.

**Worker incremental (from same report):** semantic edit with KIR cache hit ~**31 ms** round-trip (wasm32).

### vs industry (qualitative)

| Toolchain | Typical small-module cold compile | Notes |
|---|---|---|
| `rustc` | sub-second to few seconds | depends on crate graph |
| `go build` | sub-second | single file |
| **amu (cold)** | **~2 s** | nbb/JVM path |
| **amu (worker hit)** | **~30 ms** | competitive for edit loop |

---

## 3. kototama (runtime/tender)

**Measured today (informal):** `clojure -M:cli run kotoba-compiled-fact.wasm` → **~3.4 s wall** (JVM cold + Chicory). Guest computes 5! = 120.

| Path | Role | Performance posture |
|---|---|---|
| Browser `actor-host.js` | **First-class** R2 | No JVM; engine = browser Wasm |
| `kototama.tender` (Chicory) | **Compat / CI** R1 | Per-instruction fuel — correctness over speed |
| `kototama-component` (Wasmtime) | Component profile | Differential conformance, not QPS leaderboard |

**Gap:** no `kotoba.tender-comparison/v1` yet (Chicory vs Wasmtime vs browser vs workerd on identical guests).

---

## 4. aiueos (OS)

**Not benchmarked today.** Evidence is QEMU marker strings (`AIUEOS_SCHEDULER_OK`, `AIUEOS_RING3_OK`, …), not latency tables.

| Metric | Status | World-class reference |
|---|---|---|
| Cold boot to shell | **UNMEASURED** | Linux ~1–10 s VM-dependent |
| Context switch | **UNMEASURED** | Linux ~1–5 µs |
| Syscall round-trip | **UNMEASURED** | seL4 nanoseconds–µs |
| Preemptive scheduler | **proven in QEMU** | not timed |

---

## 5. kotoba (language, end-to-end)

Legacy Rust kotoba DB benchmarks (ingest 5k entities/s, CACAO 12.8k QPS) are **pre-2026-07 migration** — not this stack (`HISTORICAL-RUST-ARCHITECTURE.md`).

**Gap:** `kotoba-lang/lang/performance-workloads.edn` workloads not yet wired to a cross-runtime gate.

---

## 6. Position vs “world #1” (2026-08-26 snapshot)

```text
                    measured today          world #1 target
runtime (narrow)    amu native ≈ Rust       win on wide + app kernels too
compile (edit)      ~30 ms worker hit       <10 ms p95, cold <500 ms
compile (cold)      ~2 s                    <300 ms small module
wasm sandboxed      5× Rust                 ≤1.5× same-engine Wasm
tender              JVM 3 s cold            browser/wasmtime <50 ms p95
OS                  unmeasured              publish boot/switch/syscall table
language+DB         unmeasured              beat 2024 Rust kotoba DB on replay
```

Next: [world-class-roadmap.md](./world-class-roadmap.md).
