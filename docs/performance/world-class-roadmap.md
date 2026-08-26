# World-class performance roadmap — kotoba / amu / kototama / aiueos

**Ambition:** #1 in every domain we own — **measured**, reproducible, fleet-gated.  
**Policy:** no claim without a contract JSON, host load recorded, and regression gate.  
**ADR:** `90-docs/adr/2608260800-kotoba-stack-performance-world-class.edn`.

---

## Principles

1. **Separate domains** — compiler throughput ≠ sandbox latency ≠ OS boot time.
2. **Publish contracts** — every benchmark is a versioned format (`kotoba.*-comparison/v1`).
3. **Beat leaders on their turf** — compare Amu Wasm to Wasmtime+clang Wasm, not to bare metal Rust.
4. **Safety is not traded** — world #1 includes fuel, capability admission, and W^X; “fast because we skipped sandbox” is disqualifying.
5. **Quiet host for certification** — load average &lt; 4 for “official” numbers; contended runs are exploratory only.

---

## Domain scorecard (2026-08-26 baseline)

| # | Domain | Owner repo | Leader today (external) | Our baseline | Gap to #1 |
|---|---|---|---|---|---|
| A | Native codegen (integer) | amu | LLVM/Rust | **1.06× Rust** (judah quiet) | wide kernel + calls + strings |
| B | Wasm codegen + run | amu + kototama | Wasmtime / V8 | 6.78× Rust (quiet) | instance model + engine |
| C | Compile latency | amu | rustc incremental / tsc | cold **663 ms** / semantic edit **~38 ms** | cold &lt;500 ms; edit &lt;15 ms p95 |
| D | Sandbox tender | kototama | Wasmtime / workerd | Chicory 3 s JVM cold | drop JVM from hot path |
| E | OS primitives | aiueos | Linux / seL4 | UNMEASURED | entire benchmark plane |
| F | Language + data | kotoba + kotobase | historical Rust kotoba | UNMEASURED | replay CLJC migration targets |
| G | End-to-end app | product repos | varies | UNMEASURED | define 3 representative apps |

---

## Milestones (ordered)

### Phase 0 — Instrumentation (now → 2 weeks)

- [x] Runtime comparison gate exists (`amu` `benchmark-runtime`)
- [x] Compile baseline gate exists (`benchmark-compile`)
- [x] Superproject evidence dir `90-docs/performance/runs/`
- [x] **Quiet-host official run** on murakumo judah (`2026-08-26-judah-quiet/`)
- [x] Human-readable summaries in `docs/performance/`
- [ ] **`kotoba.tender-comparison/v1`** — Chicory / Wasmtime / browser on same 3 guests
- [ ] **`kotoba.os-microbench/v1`** — aiueos QEMU: boot-to-marker, syscall ping, ctx-switch (2 tasks)
- [ ] **`perfgate.core/qualify` on every “we beat X” claim** (already used in amu for LLVM parity)
- [x] Fleet gate `root-kotoba-stack-performance` — fails if pinned evidence is missing or datoms/json drift; live `--live` regression when amu checkout present

### Phase 1 — Compiler #1 on edit loop (4–8 weeks)

| Gate | Target | Current |
|---|---|---|
| Worker wasm32 p95 | **&lt; 15 ms** | **~38 ms** semantic edit (quiet) |
| Worker aarch64 p95 | **&lt; 20 ms** | **~23 ms** warm (quiet) |
| Cold small module | **&lt; 500 ms** | **~663 ms** (quiet) |
| `kernel_wide` vs LLVM AArch64 | **≤ 1.10×** on quiet host | 1.62× best post-allocator (see amu docs) |

**Work:** native `bin/amu` default (skip nbb cold), expand register allocator to **call+branch** path (conservative stack slots today +33% at 24 live values).

### Phase 2 — Runtime #1 sandboxed (8–12 weeks)

| Gate | Target | Current |
|---|---|---|
| Amu Wasm vs clang Wasm on **same** engine | **≤ 1.5×** | not measured apples-to-apples |
| Amu native vs Rust (wide kernel) | **≤ 1.15×** | was 9× pre-allocator; post-fix ~1.6× |
| Tender p95 (browser, fact.wasm) | **&lt; 5 ms** | ~3.4 s JVM path |

**Work:** Wasm single long-lived instance option (relax 400-call fuel split for trusted bench only), kototama-component on workerd, retire Chicory from hot path.

### Phase 3 — OS #1 on defined microbench (12–24 weeks)

Not “beat Linux on everything” — win on **capability-secure microkernel** scorecard:

| Metric | Target |
|---|---|
| Boot to `AIUEOS_SCHEDULER_OK` | publish ms, beat previous aiueos release |
| Ring3 syscall null | **&lt; 500 ns** equivalent on QEMU tc (calibrated) |
| Preemptive switch (2 tasks) | publish cycles, regress &gt;5% = fail |

**Work:** `scripts/os-microbench.cljs` in aiueos, wired to smoke QEMU.

### Phase 4 — Language + data plane #1 (ongoing)

Replay targets from historical Rust (`HISTORICAL-RUST-ARCHITECTURE.md`):

| Workload | Rust p50 (2024) | CLJC target |
|---|---|---|
| `kg.ingest_batch` | 5222 entities/s | ≥ 5222 |
| CACAO-gated ASK | 0.68 ms | ≤ 0.68 ms |
| EAVT point lookup | ~180 ns | ≤ 200 ns |

Use `kotoba-lang/lang/performance-workloads.edn` + `manifest/projection-verify` style contracts.

---

## Weekly score ritual

1. Run benchmarks on **quiet** murakumo node (load &lt; 4).
2. Append JSON to `90-docs/performance/runs/YYYY-MM-DD/`.
3. Update markdown summary in `docs/performance/` (one row per domain).
4. `perfgate` qualify any “we improved X%” before merging.
5. ADR body in `2608260800` gets snapshot map updated — not a new ADR per week.

---

## What “#1” means (non-negotiables)

| We claim #1 when… | We do **not** claim #1 when… |
|---|---|
| Median beats next-best in **same contract** on **quiet** host | We beat slower language on toy bench only |
| Regression gate pinned in fleet-ci | One laptop run |
| Safety path included (fuel, admission, W^X) | Bench bypasses production loader |
| At least 2 workloads per domain (narrow + wide) | Single `kernel.kotoba` only |

---

## Commands (quick reference)

```bash
# Full stack evidence capture (from superproject root)
node scripts/kotoba-stack-benchmark.mjs --runs 5 --date $(date +%Y-%m-%d)
sysctl -n vm.loadavg
```

Owner: jun. Review monthly; gates land via `scripts/fleet-ci/gates/` when stable.
