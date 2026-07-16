# ADR-2607071400: murakumo ファミリー = k8s / wasmCloud+wadm 相当ポジション — 分散 hosting・control-plane 層の明文化

**Status**: accepted
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/murakumo`, `orgs/gftdcojp/cloud-murakumo`,
`orgs/gftdcojp/cloud-murakumo-fleet`（→ `local-murakumo`、ADR-2607041302）,
`orgs/kotoba-lang/kotobase`, `orgs/kotoba-lang/com-kubernetes`

## Context

kotoba スタックには分散 storage（kotobase / kotobase-peer）と分散 compute
（murakumo `infer/*` の exo 型分散推論、cloud-murakumo の GPU serverless）が
cljc で存在するが、「k8s のような**分散 hosting / control plane**（宣言的
desired state・placement scheduling・service lifecycle・self-healing・node
membership/health）はどこが担うのか」が単一のドキュメントとして明文化されて
いなかった。答えは ADR-2606271600（kotoba-stack-equivalences）・
ADR-2607041302（murakumo family naming）・ADR-2607031600（GPU fleet
requirements）等に**分散して**書かれており、リポジトリ一覧だけを見た読者は
`scheduler`（実体は tick timer）や `com-kubernetes`（実体は API 互換 CRUD
facade）を control plane と誤認しうる。

本 ADR はその散在した位置づけを1枚に集約し、「murakumo ファミリーが本スタック
における k8s / wasmCloud+wadm 的ポジションである」ことを正式に記録する。
新規の設計判断は含まない（既存 ADR 群の帰結の明文化）。

## Decision

1. **murakumo ファミリーが、本スタックにおける「分散 hosting / control plane」
   層 — 一般的なスタックで Kubernetes が占めるポジション — を担う。**
   kotobase(+peer) が storage plane（etcd 相当の永続基盤は kotoba Datom log）、
   murakumo `infer/*` / cloud-murakumo が compute plane、その上の hosting・
   control（何をどこで何レプリカ動かすか、収束させるか）は murakumo
   ファミリーの責務である。

2. **実現モデルは k8s 型（中央 control plane）ではなく wasmCloud/wadm 型
   （leaderless lattice）である。** ADR-2606271600 の等価対応
   `murakumo : kotoba ≅ wash + wadm : wasmCloud` を正とする。placement は
   中央 scheduler ではなく gossipsub lattice 上の **leaderless auction**、
   desired state は `murakumo.app.edn`（wadm manifest 相当）→
   `nbb murakumo reconcile` の収束ループ。Spin/SpinKube 型の「k8s 制御面に
   依存する分散」は no-central-master 原則と衝突するため不採用（同 ADR）。
   例外は etzhayyim 側 murakumo（k3s-on-Lima + Ansible、LangGraph/Pregel
   cells 用）のみで、これは本ファミリーの外。

3. **ファミリー内の役割分担**（ADR-2607041302 の命名整理を踏襲）:
   - **`kotoba-lang/murakumo`** = **wash + wadm 相当**（control plane 本体）。
     `nodes/provision/up/down/status/mesh/deploy/reconcile/fleet/cloud/overlay/
     infer` の CLI と、pure な reconcile/placement コア
     （`reconcile/plan.cljc` 等）を持つ唯一の実行ツール。
   - **`gftdcojp/cloud-murakumo`（Sora）** = **Modal 等価の GPU serverless
     製品面**。k8s の語彙では「cloud provider の node pool + bin-pack
     scheduler」に相当し、GPU 在庫を murakumo の auction に bid として乗せる
     （`scheduler.cljc/plan-placements` の bin-pack auction、承認 gate 付き
     lifecycle effect。ADR-2607031600）。
   - **`gftdcojp/cloud-murakumo-fleet`（→ `local-murakumo`）** = fleet 状態の
     **外部公開エッジ API**（CF Worker + kotobase.net）。k8s の語彙では
     「hosted API server の外部エンドポイント」に相当する薄い面。

4. **`kotoba-lang/com-kubernetes` は本ポジションを占めない。** これは k8s
   **API 互換の clean-room CRUD facade**（Pod/Deployment 等の REST 形状のみ、
   controller/scheduler/kubelet なし）であり、compat adapter 層に属する。
   同様に `kotoba-lang/scheduler` は durable tick timer（cron 原語）、
   `kotoba-lang/kotoba-fleet` は coding-agent 協調の lease/governor 基盤で
   あって、いずれも workload hosting の control plane ではない。

## 等価対応表（正式版）

| k8s | wasmCloud/wadm | murakumo ファミリー | 状態 |
|---|---|---|---|
| manifest / Deployment | wadm manifest | `murakumo.app.edn`（apps × replicas × placement） | 実装済み |
| controller / reconcile loop | wadm reconciler | `nbb reconcile --dry-run/--apply/--watch` + pure core `reconcile/plan.cljc` | 実装済み（テスト有） |
| kube-scheduler | lattice auction | gossipsub **leaderless auction**（label/role/reach eligibility → least-loaded）＋ Sora の bin-pack / `infer/plan` のメモリ加重分割 | pure コア実装済み・**fleet 横断 auction は未配線** |
| kubelet | host runtime | 各 node の `kotoba-server`（macOS LaunchAgent、RunAtLoad+KeepAlive） | 実装済み |
| liveness probe / 自己修復 | health check | deep `/health` + watchdog（60s probe→kill→respawn）+ `reconcile --watch` | **node ローカルのみ**（fleet レベル再配置は未達、ADR-2607022000） |
| node membership / 認可 | lattice membership | `murakumo.kekkai`（zero-trust admission ledger） | **proposed・未実装**（現状 `fleet.edn` 静的 inventory、ADR-2607023100） |
| etcd | — | kotoba Datom log / kotobase（placement snapshot・reconcile plan の as-of 履歴） | 実装済み |
| Service / CNI | lattice networking | `murakumo.cloud` overlay（DID/CID identity、QUIC/relay、connect.edn reach） | 構築中 |
| kubectl / API server | wash CLI | murakumo CLI ＋ `cloud-murakumo-fleet`（外部 HTTP API） | 実装済み |
| cloud provider / node pool | provider | **cloud-murakumo（Sora）**: GPU 在庫の auction bid・承認 gate 付き scale effect | 設計＋pure コア実装済み・**actuation は propose-only**（ADR-2607031600） |

## 既知のギャップ（本 ADR で新設せず、既存 ADR の follow-up に帰属）

- fleet 横断の単一 lattice auction（cross-node peering）未配線 — ADR-2606271600。
- reconcile → 実行の経路が propose-only（財務承認 gate 後の実 GPU/node 起動
  API 未実装） — ADR-2607031600。
- fleet レベル self-healing（動的 join/leave・障害時自動再分割）未達。
  judah/naphtali の kernel panic では物理電源投入が必要だった — ADR-2607022000。
- kekkai membership / net gossip / overlay の統合は proposed — ADR-2607023100。
- k8s が持つが対応物を意図的に持たないもの: RBAC・admission control・
  HPA（メトリクス駆動 autoscale）・health-gated progressive rollout。
  必要になった時点で個別 ADR を起こす。

## Consequences

- 今後「分散 hosting / orchestration / control plane」を要する設計・議論は、
  まず murakumo ファミリー（本 ADR の等価対応表）を参照点にする。k8s 系の
  語彙で要求が来た場合も、この表で対応物（または意図的な非対応）に翻訳する。
- `com-kubernetes` / `scheduler` / `kotoba-fleet` を control plane と誤認した
  設計提案は本 ADR を根拠に差し戻す。
- ギャップを埋める実装はそれぞれの親 ADR の follow-up として進め、本 ADR は
  ポジショニングの参照点に留める（実装進捗で本文を書き換えない。大きな転換が
  あれば superseding ADR を起こす）。

## Related

- ADR-2606271600（kotoba-stack-equivalences）: `murakumo ≅ wash+wadm` の原典、
  k8s 中央 control plane 不採用の決定。
- ADR-2607041302（murakumo-family-naming）: ファミリー3リポジトリの役割分担と
  `local-murakumo` 改名方針。
- ADR-2606272300（cloud-murakumo-gpu-cloud）: Sora 製品の決定。
- ADR-2607031600（cloud-murakumo-gpu-fleet-requirements）: bin-pack auction・
  lifecycle effect・propose-only actuation の現状。
- ADR-2607022000（murakumo-exo-distributed-inference）: 分散推論 placement と
  self-healing 未達の実測。
- ADR-2607021900（murakumo-cloud-ops-hardening）: deep /health + watchdog。
- ADR-2607023100（murakumo-kotoba-lang-net-p2p-semantics-integration）:
  kekkai/net/overlay の層設計。
- ADR-2607051410（net-kotobase-distributed-storage-mesh-design）: 同じ
  control-plane 原語（membership + auction + overlay）を storage 側へ一般化。
