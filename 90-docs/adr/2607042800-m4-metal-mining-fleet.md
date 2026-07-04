# ADR-2607042800: M4 / Metal mining — from-scratch RandomX + Etchash verification, and a 10-node Monero fleet

**Status**: accepted
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki

## Context

Goal: mine Monero (XMR, RandomX) and Ethereum Classic (ETC, Etchash) as
efficiently as possible on Apple M4, with a "co-scientist" analysis of the
from-scratch optimization space — then actually run it across a 10-node
M4-16GB fleet reachable over Tailscale.

The two algorithms have opposite bottlenecks: **RandomX** is a latency-bound
CPU VM, deliberately GPU-hostile; **Etchash** is pure memory-bandwidth-bound,
GPU-friendly, but no mature Metal miner exists. This ADR records the
decisions and the empirically-verified findings (full derivation is in the
working session).

Bench host: Apple M4 (base), MacBook Air — 4 P-core + 6 E-core, 10-core GPU,
32 GB unified memory, L1D 128 KB, L2 16 MB (shared), 16 KB pages, Metal 4.
Fleet: 10× Apple M4 16 GB (1 MacBook Air `asher`, 9 Mac mini) — hostnames
asher / benjamin / dan / issachar / joseph / judah / levi / naphtali /
simeon / zebulun.

## Decision

### 1. Etchash: implemented from scratch in Metal, verified against the canonical vector

Wrote a Metal compute pipeline (keccak-f1600, on-GPU DAG generation, 64-round
hashimoto) plus an independent Swift CPU reference. Verified end-to-end
against the official Ethash epoch-0 test vector — `cache_hash 35ded12e…`,
`mixhash 58f759…`, `result dd47fd2d…`; GPU DAG items bit-match the CPU
reference.

Kernel results on the M4 (10-core GPU, 120 GB/s theoretical):

- **Winner: naïve scalar 1-thread/hash, threadgroup 128 → 7.83 MH/s = 53% of
  the bandwidth ceiling.**
- Hypothesis **E1** (SIMD-group cooperative coalescing — the NVIDIA warp
  idiom) — **refuted, −64%** (2.8 MH/s): `simd_shuffle` overhead + redundant
  per-lane compute swamp the coalescing win.
- Hypothesis **E2** (uint4 vectorized 128-bit loads) — **refuted, −28%**
  (5.6 MH/s): the Metal compiler already schedules the scalar loads better.

### 2. RandomX: benchmarked the reference JIT; found a build-time trap

Building the reference lib with `-DARCH=native` silently falls back to
**software AES** on Apple clang (the `-march=native` path does not enable the
crypto feature) → 1677 H/s. Building with `-DARCH=default`
(`-march=armv8-a+crypto`) enables **hardware AES → +38%** (2318 H/s). Both
verified against the canonical reference hash `10b649a3…`. With hardware AES
the thread optimum shifts from 4 (P-only) to 9 (4P+5E) → **3287 H/s peak**.
Production XMRig auto-detects hardware AES → ~3.7–3.8 kH/s/node.

### 3. X1 (unified-memory contention) confirmed — XMR and ETC are substitutes, not complements

Running RandomX (CPU) and Etchash (GPU) concurrently on one M4: RandomX
collapses 1627 → 573 H/s (**−65%**) while the GPU drops only 7.74 → 5.99 MH/s
(−23%). The bandwidth-hungry GPU inflates memory latency and guts the
latency-bound CPU miner. **Conclusion: do not co-mine; pick the higher-$/W
coin and run it (mostly) alone.**

### 4. Production: mine XMR fleet-wide; ETC is uneconomical on this hardware

At July-2026 prices (XMR $333, ETC $7.03) and measured hashrates, XMR
(~$0.09/node/day) beats ETC (~$0.005/node/day) by ~15× — Monero's
ASIC-resistance keeps a CPU competitive, whereas the small M4 GPU is crushed
by ETC's ASIC / discrete-GPU field. **All 10 nodes run RandomX/XMR via XMRig
→ SupportXMR (TLS), worker id `m4-<name>`.**

### 5. Fleet deployment + hardening

- Reached the fleet over **Tailscale SSH** (tribe-name users). The 5 nodes
  that were offline were rebooted and reached first via a **LAN password hop**
  through an online node (`sshpass`, keyboard-interactive), then bootstrapped
  with an SSH key; all 10 subsequently rejoined the tailnet.
- **All 10 hardened with a launchd LaunchDaemon**
  (`/Library/LaunchDaemons/com.mining.xmrig.plist`, `RunAtLoad` + `KeepAlive`)
  → mining auto-starts at boot and auto-restarts on crash. Tailscale is
  already a boot daemon (`homebrew.mxcl.tailscale`) on every node.
- **Wallet**: a new Monero wallet was generated locally in `~/monero-mining/`
  (outside any repo); the receive address `45qeqP…RuxE` mines fleet-wide.
  **Seed + password are stored in Apple Keychain and 1Password** (Private
  vault, item "Monero Mining Wallet (M4 fleet)") — **no secrets in git**.

## Consequences

- (+) **10/10 M4 nodes mining, measured 37.0 kH/s total** (~3.5–3.8 kH/s
  each), 0 rejects, reboot- and crash-resilient.
- (+) A **verified, greenfield Metal Etchash kernel** (53% of M4 bandwidth)
  and a **reproducible RandomX build fix** (hardware AES, +38%) — reusable
  artifacts kept in the working scratchpad (not committed here).
- (+) Two co-scientist hypotheses (E1, E2) empirically **refuted on real
  hardware**; the real levers were the unglamorous ones — correct build
  flags, thread count, and *not* co-mining.
- (−) **Economics are ~break-even**: ~0.0027 XMR/day ≈ $0.91/day gross for 10
  nodes (~$27/mo) vs ~$0.58/day power at $0.10/kWh. Worthwhile only with
  cheap/free electricity, or as KYC-free XMR accumulation.
- (−) This is an **out-of-band operational deployment on personal M4
  hardware**; it touches no west project and no manifest. No repo/manifest
  registration.
- Operations: stop = `sudo launchctl bootout system/com.mining.xmrig` per node
  (`pkill` alone will not stop it — `KeepAlive` restarts it); monitor via each
  node's XMRig HTTP API (`127.0.0.1:8080`, token-gated) or the SupportXMR
  dashboard for the address.
