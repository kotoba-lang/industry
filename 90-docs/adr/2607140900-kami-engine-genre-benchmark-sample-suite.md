# ADR-2607140900: KAMI Engine 2D/3D genre benchmark sample suite、SDK/Studio 統合と公開標準

- **Status**: accepted（方向決定。実装・公開は M0〜M6 の gate 通過後）
- **Date**: 2026-07-14
- **Deciders**: Jun Kawasaki
- **Related**: ADR-2607121800（network-isekai UGC 成熟度と fork 公開層）、
  ADR-2607100030（`kami:engine` host imports）、ADR-2607100100
  （リアルタイム3D app shell / WebGPU stress gate）

## Context

isekai.network では Call of Duty / Battlefield 型の 3D FPS を参考にした
`nightglass` が存在するが、単一の 3D shooter だけでは KAMI Engine の汎用性、
2D 性能、決定論、world streaming、群衆、avatar、multiplayer、headless 実行を
横断して証明できない。また「遊べる sample」と「開発者向け SDK example」と
「回帰 benchmark」が別々に育つと、同じ機能の実装・測定・公開状態が乖離する。

本 ADR は、AAA タイトルを品質・操作感・負荷特性の**参考カテゴリ**として使う。
名称、世界観、asset、level、UI、音声、固有ルールを複製せず、すべて独自 IP とする。
「AAA」は完成を偽る表示ではなく、engine が目指す負荷・表現・制作 workflow の
比較基準である。

## Decision

### D1. sample game を KAMI Genre Benchmark Suite として標準化する

各 sample は次の4役を同じ manifest と source から提供する。

1. isekai.network で遊べる独立ゲーム。
2. KAMI Engine SDK から生成・fork できる genre template。
3. KAMI Studio で開き、編集、計測、publish できる reference project。
4. engine / SDK / Studio / server の回帰・性能 benchmark。

各 project は `sample.edn` を authoritative manifest とし、最低限
`:sample/id`、`:sample/dimension`、`:sample/genre`、`:sample/tier`、
`:sample/capabilities`、`:sample/scenarios`、`:sample/budgets`、
`:sample/attribution`、`:sample/license` を持つ。scene、logic、asset、入力、
server profile、期待値は content-addressed に参照する。2D と 3D で別 engine を
作らず、共通 ECS / scheduler / asset graph / input / telemetry / network model を
使い、renderer と component profile の差として扱う。

### D2. すべての sample に3つの実行 tierを要求する

- **Playable**: 広い端末で遊べる既定品質。性能不足時は安全に品質を落とす。
- **Showcase**: desktop WebGPU を基準に、genre の主要機能を60 FPS目標で提示する。
- **Meltdown**: entity、particle、player、world cell 等を段階増加し、飽和点、
  frame pacing、memory、degradation、recovery を記録する。公開 gameplay の既定値には
  しない。

固定の「最大数」だけを競わない。端末 profile、build SHA、browser、解像度、
品質 tier、seed を結果に含め、p50/p95/p99 CPU/GPU frame time、FPS、memory 推定、
draw/dispatch、triangle/sprite/particle/entity、script/GC、streaming、network
RTT/jitter/loss、rollback/reconciliation を machine-readable EDN/JSON で保存する。

### D3. 2D genre matrix

| Sample | Genre（参考特性） | Engine を限界まで試す主軸 |
|---|---|---|
| **Isekai Swarm** | survivors / action RPG | 10k 可視 entity、100k projectile Meltdown、ECS、GPU sprite/compute、空間分割、決定論 |
| **Iron Front 2D** | factory / logistics | 100k logical entity、物流・電力 graph、pathfinding、永続 world |
| **Nightglass Tactics** | tactical SRPG | grid/height、LOS、cover、AI、replay、rollback |
| **Chronicle City** | colony / life simulation | 数千 actor の schedule・経済、simulation LOD、off-screen tick |
| **Shiro Turbo** | precision platformer | 120 Hz input、pixel-perfect collision、rollback multiplayer |
| **Orbital Barrage** | bullet hell | 大量 projectile、GPU particle、破壊可能地形、overdraw |
| **Card Dominion** | card / deck builder | UI、SDF text、rule hot reload、決定論、観戦・replay |
| **World Canvas** | infinite 2D sandbox | procedural chunk、照明伝播、圧縮 save、asset streaming、UGC |

最初の2D vertical slice は **Isekai Swarm** とする。headless / browser / native
host で同一 seed・同一入力の simulation digest が一致することを必須 gate とする。

### D4. 3D genre matrix

| Sample | Genre（参考特性） | Engine を限界まで試す主軸 |
|---|---|---|
| **Nightglass Strike** | military FPS | 8→32→64 player authoritative server、prediction、lag compensation、animation、破壊 |
| **KAMI Royale** | battle royale | 100 player target、large-world streaming、replication、LOD、UGC |
| **Titan Hunt** | co-op boss action | 4 player、skinning、IK、cloth、boss AI、部位破壊 |
| **Ashen Kingdom** | open-world action RPG | 16×16 km logical world、terrain/water/foliage/weather、cell streaming、save |
| **Velocity Nexus** | racing | 24 vehicle、vehicle physics、prediction、reflection、high-speed streaming |
| **Steel Horizon** | high-speed mech action | 多関節 animation/IK、垂直 level、particle、destruction |
| **Isekai Dominion** | army / strategy action | 10k logical / 2k visible soldier、GPU skinning、formation AI、simulation LOD |
| **Deep Station** | cinematic horror | dynamic light/shadow、audio occlusion、dense interior streaming |
| **Living Metropolis** | city sandbox | traffic・pedestrian AI、interior streaming、physics、world persistence |
| **Frontier Craft** | voxel sandbox | chunk meshing、voxel lighting、multiplayer sync、persistent UGC |
| **Dream Stage** | avatar live / social | 1→100 VRM、animation blending、spring/IK、beat sync、RTC、観客配信 |
| **Reality Capture** | planet / scanned-world viewer | Gaussian splat / photogrammetry、hierarchical LOD、巨大 asset 配信 |

重点 vertical slice は Nightglass Strike、Ashen Kingdom、Isekai Dominion、
Dream Stage とし、Isekai Swarm と合わせた5本で compute/ECS、netcode、streaming、
群衆、avatar/social を覆う。

### D5. 共通性能 gate

数値は **Showcase acceptance target** であり、未測定の達成済み主張ではない。
基準 desktop WebGPU profile と端末別 baseline は M0 で固定し、変更時は実測結果を
添えて本 ADR を amend する。

| 領域 | Showcase target | Meltdown / failure-safety gate |
|---|---:|---|
| 2D sprite | 50k visible @ 60 FPS | 250k まで sweep、停止せず品質低下 |
| ECS | 100k active | 1m logical entity、budget 超過を可視化 |
| 3D instance | 10k | 100k sweep、無警告切捨て禁止 |
| animated character | 500 high-detail | 5k mixed LOD、animation LOD 有効 |
| GPU particle | 1m | 飽和まで sweep、allocation failure 回復 |
| open world | 16×16 km logical | procedural continuation、cell memory budget 維持 |
| multiplayer | 64 active | 128 bot、loss 1/5/10%、tick 20/30/60 Hz 比較 |
| VRM | 100 avatar | animation/physics 飽和曲線を保存 |
| frame pacing | p95 ≤ 16.6 ms | p99、long task、回復時間を保存 |
| memory | profile budget 内 | OOM 前に asset/LOD/particle を段階劣化 |

CI の hard gate は再現可能な reduced fixture と regression percentage を用い、共有
runner の絶対 FPS だけで merge を不安定にしない。full Showcase/Meltdown は固定
hardware の scheduled run と release candidate run で実行する。baseline に対する
p95 frame-time 10%以上の悪化、simulation digest 不一致、crash/OOM、telemetry 欠落、
上限超過 entity の無言切捨てを release blocker とする。性能改善のため gameplay
correctness、決定論、network authority を弱めてはならない。

### D6. KAMI Engine SDK を更新する

SDK は sample ごとの私有 helper を増やすのでなく、次を共通 public contract とする。

- 2D/3D 共通 ECS component、fixed-step scheduler、seeded RNG、snapshot/digest。
- sprite/mesh/skin/particle/terrain/voxel/VRM の capability declaration と fallback。
- input action map、replay、bot driver、headless runner。
- authoritative server、prediction/reconciliation/rollback、network impairment harness。
- world partition、asset streaming、render/simulation/animation LOD、quality scaler。
- `kami telemetry run|compare|export` と sample scaffolding command。

SDK example、documentation snippet、sample 本体で同じ API を実行する contract test を
置く。未実装 capability は `unsupported` と明示して fail closed にし、placeholder を
達成済みとして catalog に表示しない。

### D7. KAMI Studio を更新する

Studio に Sample Browser（2D/3D/genre/capability）、Create from Template、Play
Playable/Showcase、Run Meltdown、Profiler、budget inspector、LOD/streaming/network
debug overlay、replay comparison、headless/bot scenario editor を追加する。

Publish 前に manifest/schema、license/attribution、asset budget、deterministic fixture、
required capability、Playable smoke を検査する。Studio の測定 UI は engine telemetry
schema の consumer とし、独自カウンタを持たない。ブラウザ editor と SDK CLI の
project は相互 round-trip し、Studio 専用の非公開 scene format を作らない。

### D8. isekai.network での公開形を統一する

公開 catalog は `2D / 3D / Multiplayer / Simulation / UGC / Benchmark` で分類し、
各 sample に **Play / Fork / Source / Benchmark** を表示する。Benchmark には build、
端末、測定日、tier、達成済み/target を区別して掲示し、最高値だけでなく frame-time
curve と品質劣化点を示す。Fork/Source は ADR-2607121800 の content-addressed commit
DAG と license policy に従う。

公開条件は、(1) 独自または再配布可能 asset、(2) Playable smoke、(3) telemetry schema、
(4) reproducible scenario、(5) source/fork link、(6) accessibility の基本入力、
(7) crash/OOM safety、(8) false AAA claim がないこと。未完成 sample は `lab` と表示し、
catalog の production sample 数に含めない。

## Parallel implementation lanes and ownership boundaries

並列 agent / repo 作業は下記の独立 lane に分ける。各 lane は先に contract fixture を
共有し、他 repo の source をコピーせず versioned schema/API を介して統合する。

| Lane | 主成果物 | Merge gate |
|---|---|---|
| A — Benchmark contract | `sample.edn` schema、telemetry、scenario runner、baseline comparison | JVM/CLJS/browser schema round-trip、reduced CI fixture |
| B — Engine/SDK | 共通 ECS、renderer capability、headless/replay/net/streaming API | contract tests、digest parity、API docs |
| C — Studio | template browser、profiler、Meltdown/LOD/net overlays、publish preflight | browser E2E、SDK round-trip、console error なし |
| D — 2D samples | Isekai Swarm から D3 matrix を段階実装 | Playable + deterministic + performance gate |
| E — 3D samples | Nightglass Strike から D4 vertical slices を段階実装 | Playable + bot/headless + capability-specific gate |
| F — Platform/publish | catalog、Play/Fork/Source/Benchmark、result ingestion | preview E2E、別 identity fork、rollback 手順 |

同時編集 collision を避けるため schema ownership は Lane A、public SDK contract は B、
Studio UI は C、sample-specific content は D/E、production routes は F とする。contract
変更は consumer fixture を同じ PR train で先に green にし、west pin は各 repo の CI
green と fast-forward を確認してから進める。

## Milestones

- **M0 — Contract and baseline**: manifest/telemetry/scenario schema、固定 hardware profile、
  reduced/full gate、結果保存形式を実装。既存 sample を inventory し target と measured を分離。
- **M1 — Isekai Swarm vertical slice**: 2D ECS/compute、headless digest、Studio profiler、
  SDK template、Playable/Showcase/Meltdown の end-to-end reference。
- **M2 — Nightglass Strike 8-player slice**: authoritative server、prediction、bot harness、
  20/30/60 Hz と loss injection。32/64 player は測定後に段階昇格。
- **M3 — Streaming/avatar slices**: Ashen Kingdom world cells と Dream Stage VRM/RTC。
- **M4 — Crowd/simulation LOD**: Isekai Dominion と Chronicle City / Iron Front 2D。
- **M5 — Matrix expansion**: 残り genre を共通 API 上で追加し、sample-specific engine fork を削減。
- **M6 — Public release**: preview、security/license/performance/accessibility review、production
  deploy、live smoke、rollback rehearsal。公開後も release ごとに benchmark を再測定。

各 milestone は design-only mock では完了しない。実 browser または該当 headless/server、
machine-readable evidence、ユーザーが到達できる導線の3点を gate とする。

## Consequences

- sample の追加が engine の汎用性証明と regression coverage を同時に増やす。
- SDK、Studio、公開 catalog の「対応済み」が同じ capability evidence に揃う。
- Meltdown は端末差を含むため単一順位では表せず、固定 profile と相対 regression の運用費が発生する。
- 64/100 player、1m particle 等は target であり、実装・測定前に marketing claim として使えない。
- AAA 参考作との IP 混同を避けるため、独自名称・asset・ruleset review が公開 gate に加わる。

## Alternatives considered

1. **FPS sample だけを磨く**: netcode と3D表現は試せるが、2D、UI、決定論、simulation、
   streaming、avatar の汎用性を証明できないため却下。
2. **game ごとに個別 benchmark を作る**: 数値と schema が比較不能になり SDK/Studio と乖離するため却下。
3. **最大 entity 数だけを gate にする**: frame pacing、memory、correctness、劣化時の安全性を隠すため却下。
4. **全 matrix を同時完成させてから公開する**: feedback と回帰基盤の獲得が遅れるため却下。
   5本の vertical slice と段階公開を採用。
5. **AAA の名称・asset・ruleset を忠実に再現する**: IP risk があり engine benchmark に不要なため却下。

## Open questions

- M0 で固定する desktop/mobile/WebGPU/WebGL2 baseline hardware と運用主体。
- full Meltdown の保存期間、公開粒度、コスト上限。
- realtime server の deployment topology と地域別 latency profile（M2 の follow-up ADR 候補）。
- asset の自動 license provenance check をどの service が authoritative に担うか。
