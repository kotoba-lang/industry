# ADR-2607051120: `kotoba-lang/kami-gen-ml3d` — cloud-murakumo TRELLIS/Hunyuan3D-2 photo→3D pipeline (Approach 3 of 4)

## Status
Accepted

## Context

Sibling of ADR-2607051100; see that ADR's Context for shared background. This ADR covers
**Approach 3**: ML-generative image/text→3D, the only one of the 4 approaches capable of
approaching the reference image's photoreal chibi-CG fidelity (the owner explicitly chose
the unmodified photoreal frame as the shared comparison target, 2026-07-04 — approaches
1/2/4 are expected to visibly fall short of it; that gap is the point of the comparison).

The owner's direction was to run this "1 と cloud-murakumo の分散推論で" — i.e. actually
invoke real GPU inference, via `cloud-murakumo`'s distributed inference surface rather than
a bespoke Modal integration. Investigation found this is a closer match than initially
assumed: **`gftdcojp/cloud-murakumo` (the "Sora" GPU-rental product, distinct from
`gftdcojp/local-murakumo`, formerly `cloud-murakumo`, renamed 2026-07-04 per
ADR-2607041302 — the on-prem Mac-mini fleet Worker, a different repo) already declares a
`:model3d` generation function wired to exactly this use case**:

```clojure
;; gftdcojp/cloud-murakumo resources/murakumo.edn :apps :generation :functions
:model3d
{:fn/kind :gen :fn/engine :trellis :fn/modality :3d
 :gpu/class :a100-80 :gpu/count 1
 :image   :trellis-cuda
 :volumes [:hf-cache :artifacts]
 :scale   {:min 0 :max 2 :target-concurrency 2}
 :gen     {:models {"trellis" {} "hunyuan3d-2.1" {}}
           :default "trellis" :out [:gltf :glb]
           :note "出力 glTF は kami-nerf/gsplat/terrain が取り込む"}}
```

`cloud-murakumo`'s own README describes it as "gftdcojp の分散 GPU cloud サービス.
Modal 等価...配置は murakumo の leaderless auction に乗る" — the GPU placement genuinely is
fleet/auction-based distributed inference, matching the owner's direction directly (no need
to invent a separate Modal path). The execution chain is real, already-built Clojure code:
`cloud-murakumo.gen/job` (function+request → normalized `:gen.job`) →
`cloud-murakumo.worker/run-job` (pulls job, calls an injected `execute`) →
`cloud-murakumo.executor/execute` (`:via :proc` → `ProcessBuilder` shells out to the
`trellis-cuda` image's CLI; `:via :http` → calls a reachable service) → artifacts written
as CIDs + a `:murakumo.run` ledger entry. `default-execute` is injected (DI, same
Store/Advisor pattern as this org's actors) specifically so tests can run against a mock
`execute` with no GPU present — `worker.cljc`'s own comment: "未配線時は明示エラー(silent
に空成果物を返さない)".

**Caveat surfaced during implementation planning**: `default-execute` throws explicitly
when no real backend is configured (`MURAKUMO_BACKEND_URL`/`COMFY_URL` env, or a live
`trellis-cuda` container reachable over `:proc`) — this dev environment has neither.
Building the real client/job-submission code does not by itself grant a live GPU run; a
live run needs real GPU credentials/environment the owner controls, and — being real
GPU-seconds billing — is exactly the kind of hard-to-reverse, costs-real-money action this
org's own actions-with-care guidance says to confirm before firing, not assume.

## Decision

1. New repo `kotoba-lang/kami-gen-ml3d`. Dep on `gftdcojp/cloud-murakumo` via `:local/root`
   (consuming `cloud-murakumo.gen`/`.worker`/`.engine` directly, not re-implementing the
   job/executor contract) plus `kotoba-lang/vrm` + `kotoba-lang/skeleton` for post-processing.
2. `kami.gen.ml3d/generate-from-image` takes a reference image (or text prompt) and:
   - builds a `:model3d` `:gen.job` via `cloud-murakumo.gen/job` (`model` defaults to
     `"trellis"`, `"hunyuan3d-2.1"` selectable),
   - runs it via `cloud-murakumo.worker/run-job` with an **injected `execute`** — a real
     one (`cloud-murakumo.executor/execute`, requires live backend env) or a **mock** one
     (for CI / for this comparison's dry-run leg) that returns a fixture glTF,
   - downloads the resulting `.glb`/`.gltf`,
   - **auto-rigs** it to a VRM humanoid skeleton via `kotoba-lang/skeleton` (nearest-bone
     heuristic mapping onto the generated mesh's silhouette) + `kotoba-lang/vrm` compose,
   - publishes to the `network-isekai` Asset Hub (`:asset/kind :model3d`, same CID-payload
     shape used elsewhere in this comparison).
3. README documents explicitly: live firing requires `MURAKUMO_BACKEND_URL` (or
   `:proc`-reachable `trellis-cuda` image) configured by the fleet operator; this repo's
   test suite runs entirely against the mock `execute` and asserts the job/CID/ledger shape
   without ever touching a GPU.

## Consequences

- The only approach of the 4 that can plausibly match the reference image's photorealism —
  and the only one with real per-run GPU cost (`:gpu/class :a100-80`) and external
  dependency (a live `trellis-cuda`/backend endpoint that does not exist in this dev
  environment yet).
- Auto-rigging an arbitrary generated mesh to a valid VRM humanoid skeleton is genuinely
  unsolved elsewhere in this org (no prior auto-rig tool found in survey) — this is new,
  nontrivial code, not just glue.
- Output is an opaque mesh/texture blob (a real `.glb`), not EDN-forkable data — this is
  the one approach that steps outside the "everything is data you can read/tweak/fork"
  ethos the other 3 hold to, matching how VRM/glTF already separate a binary mesh/texture
  payload from any authoring metadata anyway.
- A live comparison run is gated on the owner making a real GPU backend reachable; until
  then this repo's contribution to the comparison is a dry-run against the mock executor
  (proves the wiring, not the fidelity).

## Alternatives Considered

See ADR-2607051100's Alternatives section. Also considered and rejected: building a
bespoke Modal integration instead of `cloud-murakumo` — rejected because `cloud-murakumo`
already declares this exact `:model3d`/`:trellis`/`:hunyuan3d-2.1` function with fleet
auction placement, matching the owner's explicit "cloud-murakumo の分散推論で" direction;
duplicating it in a new Modal-specific path would be redundant infrastructure.

## References

- `orgs/gftdcojp/cloud-murakumo/README.md`, `resources/murakumo.edn` (`:apps :generation`)
- `orgs/gftdcojp/cloud-murakumo/src/cloud_murakumo/{gen,worker,executor,engine}.cljc/.clj`
- `orgs/gftdcojp/local-murakumo/README.md` (naming disambiguation, ADR-2607041302)
- `orgs/kotoba-lang/vrm/README.md`, `orgs/kotoba-lang/skeleton/README.md`
- `orgs/gftdcojp/network-isekai/public/assets/index.edn` (Asset Hub schema)
- `orgs/etzhayyim/root/90-docs/adr/2605202115-baien-graft-3d-augmented-dataset.md`
  (prior art: Hunyuan3D-2 image→3D used for LLM training-data generation, not live assets)
