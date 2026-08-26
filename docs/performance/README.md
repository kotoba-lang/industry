# Kotoba stack performance evidence

Measured comparisons across **kotoba** (language), **amu** (compiler), **kototama**
(runtime/tender), and **aiueos** (OS). Raw machine-readable reports live under
`90-docs/performance/runs/<date>/` (JSON).

## Reproduce today's numbers

```bash
# From superproject root (wraps amu benchmarks + host meta)
node scripts/kotoba-stack-benchmark.mjs --runs 5 --date $(date +%Y-%m-%d)

# Or manually from amu checkout
cd orgs/kotoba-lang/amu
npm run benchmark-runtime -- \
  --runs 7 --calls 100000 --warmup 10000 --n 200 \
  --output "$KOTOBA_ROOT/90-docs/performance/runs/$(date +%Y-%m-%d)/runtime.json"

npm run benchmark-compile -- --runs 5 \
  --output "$KOTOBA_ROOT/90-docs/performance/runs/$(date +%Y-%m-%d)/compile.json"

# Tender comparison (needs kototama checkout)
nbb scripts/kotoba-tender-benchmark.cljs --runs 3 --date $(date +%Y-%m-%d)
# Worktree without orgs/: KOTOTAMA_ROOT=/path/to/kototama nbb scripts/kotoba-tender-benchmark.cljs ...
```

Record host load before running (`sysctl -n vm.loadavg`). Contended hosts inflate
every engine; compare ordering and bands, not the third decimal. **Official numbers**
use murakumo fleet nodes with load &lt; 4 (see `runs/2026-08-26-judah-quiet/` and
`runs/2026-08-26-judah-quiet-tender/`).

## Documents

| File | What it answers |
|---|---|
| [kotoba-stack-benchmark-2026-08-26.md](./kotoba-stack-benchmark-2026-08-26.md) | **Measured cross-engine table** (amu / rust / go / …) |
| [world-class-roadmap.md](./world-class-roadmap.md) | **World #1 ambition** — domains, gates, honest gaps |

Authoritative ADR: `90-docs/adr/2608260800-kotoba-stack-performance-world-class.edn`.

Machine index: `90-docs/performance/performance.datoms.edn`.

## Fleet gate

`root-kotoba-stack-performance` (murakumo fleet) checks that the pinned
`2026-08-26-judah-quiet` JSON matches `performance.datoms.edn`. Local check:

```bash
nbb scripts/verify-kotoba-stack-performance.cljs --root .
# With amu checkout and load < 4, also re-run benchmarks:
nbb scripts/verify-kotoba-stack-performance.cljs --root . --live
```

Amu-local detail (methodology, kernel_wide caveats): `orgs/kotoba-lang/amu/docs/performance.md`.
