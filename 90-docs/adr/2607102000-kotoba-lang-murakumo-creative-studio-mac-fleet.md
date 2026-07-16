# ADR-2607102000: Creative Studio — model / music / motion / rig を Mac mini fleet 制御面で統合する

## Status

Accepted — initial implementation landed.

## Context

`kotoba-lang/kami-gen-ml3d` は image/text → 3D → VRM と UniRig auto-rig の契約を持ち、`cloud-murakumo` は image/video/audio/3D/rig を `:gen.job` として配置・台帳化する。一方で motion は import/playback/retarget に留まり、クリエイターが一つの画面から model、music、motion、rig を生成・組み合わせる操作面がなかった。

Mac mini fleet は実在する kotoba mesh の control plane であり、Apple Silicon のローカル LLM と job queue を動かせる。しかし TRELLIS、UniRig、音楽生成の CUDA 実行を Mac mini が行えると偽ってはならない。重い生成は明示的な remote GPU capability に委譲し、Mac mini は job の設計、検証、queue、motion の EDN化、artifact 結合を担う。

## Decision

1. `cloud-murakumo` に `:motion` modality を追加する。初期 engine は `:skeleton` で、LLM が出す自由文を直接実行せず、validated EDN animation clipへ正規化する。
2. `:creative-studio` pipeline は `:model3d → :autorig → :motion`と`:music`を同じ project manifest に束ねる。各stageはartifact CIDを明記し、前段が失敗した時に後段を勝手に代替しない。
3. Mac mini fleetはcontrol/assembly workerとする。GPU stageはplacementが示すcapability endpointがある場合だけ実行し、無い場合は`:queued-capability`として残す。
4. public GitHub Pages Studioはcredentialを持たないstatic control/preview surfaceとし、CLJS Hiccup + shadow-cssで構築する。
5. `kotoba-lang/kisekae`をVRM composition authorityとする。body変更はbase humanoid/skeleton変更、hair/face/outfit/accessoryはdonor mesh overrideとして扱う。
6. asset read、compose、preview、export、publishはそれぞれ`:vrm/*` capabilityで制御する。URL/CIDはresource identifierでありauthorityではない。

## Pipeline

```text
brief + image refs
  -> Mac mini LLM / planner
  -> model3d (remote GPU capability)
  -> autorig (remote UniRig capability)
  -> capability-authorized kisekae compose
  -> motion (Mac mini EDN clip + retarget)
  -> music (remote audio capability)
  -> CID-linked Creative Project manifest
  -> CLJS single-window Studio preview / submit / export
```

## Capability boundary

| Capability | Scope |
|---|---|
| `:vrm/asset-read` | base/donor VRM or GLB URL/CID |
| `:vrm/compose` | character spec ID |
| `:vrm/preview` | character spec ID |
| `:vrm/export` | output URL/CID |
| `:vrm/publish` | Murakumo/IPFS/Kotobase destination |

Canonical registration and CACAO/local-policy intersection live in `kotoba-lang`; the portable plan and fail-closed enforcement live in `kotoba-lang/kisekae`. Browser CLJS and Murakumo workers consume the same pure CLJC plan.

## Consequences

- model、rig、motion、music、VRM compositionが同一provenance/ledger shapeで追跡できる。
- Mac mini fleetは全stageを制御・統合するが、GPU modelをローカル実行するという誤った主張はしない。
- body/hair/face/outfit selectionはplaceholder manifestではなく、実asset composition contractへ接続される。
- capability不足、license不適合、missing part、skeleton不整合はsilent fallbackせず明示的に失敗する。
- StudioはiPad-first single-window workspaceとしてLibrary、Character Canvas、Inspector、Timelineを一画面に集約する。

## Landed implementation

- `cloud-murakumo`: motion modality and worker contract.
- `kotoba-lang/kami-creative-studio`: public CLJS/Hiccup/shadow-css workspace with real VRM preview.
- `kotoba-lang/kisekae` PR #1: capability-driven compositor plan, skeleton/base semantics, material/expression/export/Murakumo job contract.
- `kotoba-lang/kotoba-lang` PR #15: canonical VRM capability registration.
