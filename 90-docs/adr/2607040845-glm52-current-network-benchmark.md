# ADR-2607040845: GLM-5.2 current-network inference benchmark

**Status**: closed
**Date**: 2026-07-04
**Closed**: 2026-07-04

## Context

The goal was to find the most efficient GLM-5.2 configuration on the current
Mac mini fleet network, including `mlx-moe`, Exo-style layer partitioning, and
`llama.cpp` RPC, and to keep looping until actual load/generation behavior
replaced paper plans.

The target workload is coding-agent use, especially Clojure and Datomic. A
configuration that only loads but produces unusable text is not acceptable.

## Fleet Facts

- Local operator Mac: 32 GiB RAM, `192.168.1.4`, Tailscale `100.108.223.94`.
- Mac mini workers: 16 GiB M4 nodes.
- Local Wi-Fi and worker wired LAN are isolated:
  - local -> worker `192.168.1.x`: ARP/ping/RPC fail.
  - worker <-> worker over wired LAN: sub-ms ping.
- Tailscale is reachable for control, but too slow for model shard streaming:
  - remote pull from local Tailscale HTTP: about 70-100 KiB/s.
  - local pull from `simeon` Tailscale HTTP: about 0.48 MiB/s.
- `gad` became the usable head candidate:
  - `gad@/root@100.82.98.110`, wired LAN `192.168.1.16`.
  - Can reach worker wired LAN directly.
  - Current OS memory reports 46.7 GiB, not 96 GiB.
  - Disk was extended with LVM; GLM-5.2 REAP50 Q2_K fits on `/home/gad/models`.

## Artifacts Built

Built current `ggml-org/llama.cpp` at commit `2d973636e` with RPC/server support.

On Mac workers:

```text
GGML_METAL=ON
GGML_RPC=ON
LLAMA_BUILD_SERVER=ON
```

Artifacts were bundled and distributed through `gad` over wired LAN:

```text
rpc-server
lib*.dylib
```

On `gad`:

```text
/home/gad/murakumo/llama.cpp/build-rpc-glm/bin/llama-server
```

The GLM-5.2 REAP50 GGUF README says stock `llama.cpp` needs the GLM DSA indexer
optional patch. Current master already has the equivalent `TENSOR_NOT_REQUIRED`
behavior, so the included patch did not apply because it was already present in
substance.

## Model Download

Downloaded on `gad`:

```text
pipenetwork/GLM-5.2-REAP50-Q2_K-GGUF
```

Final shard sizes:

```text
44999739520 GLM-5.2-REAP50-Q2_K-00001-of-00004.gguf
44581813248 GLM-5.2-REAP50-Q2_K-00002-of-00004.gguf
44677711424 GLM-5.2-REAP50-Q2_K-00003-of-00004.gguf
 4731875104 GLM-5.2-REAP50-Q2_K-00004-of-00004.gguf
```

The model repository warns that this is the most fragile quality tier:
REAP-50 plus Q2_K. Greedy decoding can collapse, so the server command must use:

```text
--temp 0.6 --repeat-penalty 1.1 --top-p 0.95
```

## Planner Results

With `gad` as head and all 10 reachable Mac workers, the plan fits:

```text
model glm-5.2-reap50-q2k  weights 129.5 GiB  layers 78
naphtali   0-8    9.11 GiB
simeon     8-13   8.60 GiB
judah      13-19 10.32 GiB
zebulun    19-25 10.32 GiB
levi       25-30  8.60 GiB
joseph     30-36 10.32 GiB
issachar   36-42 10.32 GiB
dan        42-47  8.60 GiB
benjamin   47-53 10.32 GiB
asher      53-59 10.32 GiB
head       59-78 32.66 GiB
total usable 156.9 GiB - FITS
```

When `simeon` and `levi` dropped offline, 8-worker planning only fit after
lowering runtime headroom. When `joseph` also became unreachable, 7-worker
planning only fit by raising reachable worker `iogpu.wired_limit_mb` to 14 GiB
and removing planner headroom. That was a stress test, not a safe operating
profile.

## mlx-moe Measurements

Local `mlx-moe` using `mlx-community/GLM-5.2-mxfp4` and the Clojure/Datomic
profile was measured as a network-independent fallback.

`capacity=4,pin-top-k=4`:

- startup: 22.6s
- resident: 16.1 GB
- decode: about 8-10 tok/s
- fallback rate: about 97%
- output quality: unusable, nonsensical text

`capacity=8,pin-top-k=8`:

- startup: 35.3s
- resident: 22.1 GB
- decode after KV reuse: about 10-12 tok/s
- fallback rate: about 94%
- output quality: still unusable, nonsensical text

Conclusion: `mlx-moe` is useful for loader/profiling experiments, but not for
the Clojure/Datomic coding-agent workload on this hardware.

## llama.cpp RPC Load Measurements

### 10-worker, `rpc-server -c`

Started all 10 Mac workers with Metal RPC and disk tensor cache:

```text
./rpc-server -H 0.0.0.0 -p 50052 -d MTL0 -c
```

`gad` head launched:

```text
llama-server ... --rpc <10 wired workers> --split-mode layer \
  --tensor-split 8,5,6,6,5,6,6,5,6,6,19 -ngl 999 -c 4096 \
  --parallel 1 --host 0.0.0.0 --port 8090 \
  --temp 0.6 --repeat-penalty 1.1 --top-p 0.95
```

Result:

- API stayed at `503 Loading model`.
- Head read over 190 GiB from disk during load.
- RPC cache writes progressed worker-by-worker.
- `asher` became receive-window limited at about 110 Mbps while writing cache.
- Load did not reach generation before operational risk became too high.

The disk cache is not a free win on the 16 GiB minis. First load can be dominated
by worker-side cache writes and memory pressure.

### 8-worker, no `-c`

Stopped the head and restarted reachable workers without disk cache:

```text
./rpc-server -H 0.0.0.0 -p 50052 -d MTL0
```

Because `simeon` and `levi` were offline, an 8-worker plan required lower
headroom to fit:

```text
--tensor-split 9,7,6,7,7,6,7,7,22
```

Result:

- API stayed at `503 Loading model`.
- Head connected to workers but then got stuck around a single worker.
- `joseph` became unreachable over Tailscale SSH and LAN RPC during load.
- Head was stopped before more nodes were lost.

### 7-worker stress attempt

After `joseph` became unreachable, 7 reachable workers could fit only by raising
their wired limit to 14 GiB and setting planner headroom to zero:

```text
naphtali  0-10
judah     10-17
zebulun   17-25
issachar  25-32
dan       32-40
benjamin  40-47
asher     47-55
head      55-78
total usable 139.7 GiB - FITS
```

Result:

- API stayed at `503 Loading model`.
- Head again stalled during load.
- `dan` then became unreachable over management SSH.
- The head was stopped and reachable workers were restored to 13 GiB wired
  limit.

## Decision

There is no production-quality GLM-5.2 configuration on the current Mac mini
fleet.

The best theoretical architecture remains `llama.cpp RPC` with `gad` as wired
LAN head and `GLM-5.2-REAP50-Q2_K-GGUF`, because the memory/layer plan fits when
enough workers are alive. However, actual load behavior is not operationally
safe on the current 16 GiB Mac mini fleet:

- `rpc-server -c` can bottleneck badly on worker-side cache writes.
- no-cache load can still wedge individual workers.
- once a worker wedges, both RPC and management SSH can be lost.
- the system did not reach first generated token in the measured loops.

`mlx-moe` is also rejected for this workload because fallback remains above 93%
and output quality is unusable.

## Closed Outcome

Close this investigation as **blocked by hardware/runtime stability, not by
planner math**.

Do not continue GLM-5.2 REAP50 Q2_K load experiments on the 16 GiB Mac mini
fleet until the following are true:

1. Worker nodes can be power-cycled or recovered out-of-band after memory
   pressure.
2. The active worker set is stable and all required nodes are reachable over
   both LAN RPC and management SSH.
3. `rpc-server -c` is controlled per node, not simply enabled for every worker
   with disk space.
4. The planner has a conservative runtime headroom profile for 16 GiB Macs and
   a separate explicit "stress" profile.
5. A smaller model proves the exact RPC runtime path can load and generate
   repeatedly without wedging nodes.

For practical coding-agent work today, use a smaller proven RPC model or a
single-node/cloud backend. GLM-5.2 on this fleet should wait for either larger
Apple-memory nodes, more stable worker recovery, or a different execution
runtime that can page/route experts without wedging 16 GiB workers.

## Implementation Notes Landed

- `murakumo.infer` can probe Linux heads via `/proc/meminfo`.
- Remote head `:bin-dir` and `:model-dir` are configurable.
- API host derivation strips SSH `user@` from `:infer/head :host`.
- `llama-server` can receive model-specific extra args.
- `mlx-moe` model entries can carry profile/capacity/pin-top-k/warmup settings.
- Tests remained green: `173 tests / 801 assertions / 0 failures`.

## Follow-up (2026-07-04): local mxfp4 cache relocated to external storage

Since `mlx-community/GLM-5.2-mxfp4` was rejected above for the coding-agent
workload (fallback rate >93%, unusable output), the local operator Mac no
longer needs it resident on internal SSD. The HuggingFace cache entry
(`~/.cache/huggingface/hub/models--mlx-community--GLM-5.2-mxfp4`, 368 GB) had
pushed internal SSD usage to 93% (73 GiB free).

Relocated to external USB HDD, following the existing HF-hub-cache convention
already used on that drive for `models--state-spaces--mamba2-370m`:

```text
rsync -a  ~/.cache/huggingface/hub/models--mlx-community--GLM-5.2-mxfp4/ \
  /Volumes/251220/models/models--mlx-community--GLM-5.2-mxfp4/
```

Verified byte-for-byte before deleting the internal copy: all 84 blob files
matched by name and exact size (395,114,852,695 bytes total on both sides).
The original cache path was then replaced with a symlink to the external
copy, so `mlx-lm`/`huggingface_hub` tooling resolves the standard cache path
unchanged:

```text
~/.cache/huggingface/hub/models--mlx-community--GLM-5.2-mxfp4
  -> /Volumes/251220/models/models--mlx-community--GLM-5.2-mxfp4
```

Internal SSD free space recovered from 56 GiB to 424 GiB. No change to the
Decision or Closed Outcome above — this is disk-management housekeeping for
an already-rejected local fallback, not a re-opening of the investigation.
