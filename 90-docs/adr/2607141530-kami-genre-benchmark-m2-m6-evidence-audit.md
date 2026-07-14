# ADR Addendum 2607141530: KAMI Genre Benchmark M2〜M6 evidence audit と release closure gates

- **Status**: proposed addendum（監査時点の達成状況を固定し、完了条件を具体化）
- **Date**: 2026-07-14
- **Amends**: ADR-2607140900
- **Scope**: `kami-engine-sdk`、`kami-studio`、`kami-engine`、`network-isekai`

## 結論

2026-07-14 の checkout を source、test、公開物まで照合した結果、M2〜M6 はいずれも
完了していない。M2、M3、M6 には再利用可能な既存ゲームまたは公開catalogがあるため
**部分達成**、M4 と M5 は milestone 固有の実装・測定証跡がなく**未達成**と判定する。

これは既存ゲームが遊べないという判定ではない。ADR-2607140900 が要求する
「実 browser/headless/server」「machine-readable evidence」「到達可能な導線」の三つを、
同一 build と再現可能 scenario で満たしていないという判定である。catalog の `:status
:playable` や AAA 作品名への言及だけを milestone 完了または AAA 品質の証明に使わない。

## 監査証跡

| 証跡 | 確認できたこと | 証明していないこと |
|---|---|---|
| `kami-engine-sdk/src/kami/benchmark.cljc` | manifest検証、共通metric語彙、telemetry frame、上限付きload ramp | GPU clock採取、runner、結果保存・比較、digest parity、network impairment |
| `kami-engine-sdk/resources/kami/samples/isekai-swarm.edn` | Isekai Swarm のtier/budget fixture | 10k entity、100k projectile、60 FPSの実測 |
| `kami-engine-sdk/test/kami/benchmark_test.cljc` | contract単体test | browser/native/headless横断試験、固定hardware性能gate |
| `network-isekai/src/isekai/benchmark_catalog.cljc` と `public/benchmarks/catalog.edn` | 16 sample のroadmapとtarget公開データ | targetの達成、実測result、build/hardware/browser/seed provenance |
| `network-isekai/public/benchmarks.html` | benchmark説明ページ | playable benchmark runner、chart、result ingestion |
| `network-isekai/public/games/gftd/nightglass` | FPS系既存templateへの導線 | 8-player authoritative server、prediction/reconciliation、bot loss試験 |
| `network-isekai/public/dance.html` | dance/avatar体験への導線 | 100 VRM、RTC、beat-sync、physics飽和曲線 |
| `kami-studio` checkout | Studio suite hub等の既存UI | Sample Browser、Profiler、Meltdown、LOD/net overlay、publish preflight |

benchmark contract、catalog、元ADRはそれぞれ merge 済みである。

- `kami-engine-sdk` PR #2: merge commit `67f87b9`
- `network-isekai` PR #159: merge commit `0875234`
- superproject ADR PR #439: merge commit `c366333`
- Cloudflare: production deploy 済み

したがって contract/catalog の存在は release 済み証跡として評価する。ただし merge/deploy は
各gameplay・性能・visual gateの通過を意味しない。

production visual smoke では benchmark catalog page の表示、KAMI Royale の WASM と
`app.js` の HTTP 200、console error なしを確認した。一方、KAMI Royale は5秒待機後も
300×150 canvasが黒いまま `loading` であり、playable sceneの描画成功は確認できなかった。
これは「asset/runtimeが配信されている」証跡にはなるが、「visual playable」または
「AAA visual quality」の証跡にはならない。

## Milestone判定

### M2 — Nightglass Strike 8-player slice: 部分達成

既存 `nightglass` content と catalog導線はある。しかし authoritative server、8 bot同時接続、
client prediction/reconciliation、20/30/60 Hz、loss 1/5/10% の結果artifactがない。

完了には次をすべて要求する。

- `network-isekai`: `public/games/gftd/nightglass/sample.edn` と独立play routeを追加し、
  8-player scenario、server tick、build SHA、asset attributionを固定する。
- `kami-engine-sdk`: `src/kami/network/` にauthority、input sequence、snapshot、reconciliation、
  lag compensationのpublic contract、`test/kami/network_*` にdeterministic testを置く。
- server/headless runner: 8 bot × 20/30/60 Hz × loss 0/1/5/10% を実行し、RTT、jitter、
  correction distance、rollback、server p95 tick、disconnect/crashをEDN/JSONへ保存する。
- browser E2E: join、spawn、move、shoot、damage authority、reconnectを2 browser context以上で検証する。
- release gate: 全scenario crash 0、authority violation 0、digest divergence 0。latency/quality値は
  初回baselineとして公開し、数値目標を結果確認後にamendする。

### M3 — Streaming/avatar slices: 部分達成

terrain/vegetation/VRMに利用可能な周辺repoとdance導線はあるが、Ashen Kingdomのworld-cell
project、cell residency budget、Dream Stageの100 avatar/RTC測定は確認できない。

- `kami-engine-sdk`: `world-partition`、async asset lifecycle、render/simulation/animation LOD、
  cancellationとbudget pressure APIを実装しcontract testを置く。
- `network-isekai`: `ashen-kingdom` と `dream-stage` を既存templateの別名リンクではなく、
  各 `sample.edn`、scene、logic、attributionを持つ独立projectとして追加する。
- streaming gate: camera traceを固定し、load/unload順序、resident cells、bytes/sec、VRAM推定、
  hitch p95/p99、missing asset、recoveryを保存する。16×16 kmは論理extentとして検証する。
- avatar gate: 1/10/25/50/100 VRM sweepでCPU/GPU frame time、draw call、animation/physics
  update数、音声同期ずれを保存する。RTCは2 peer接続、切断・再接続をE2E化する。

### M4 — Crowd/simulation LOD: 未達成

Isekai Dominion、Chronicle City、Iron Front 2D はcatalog上 `planned` で、milestone固有の
project、runner、結果がない。

- `kami-engine-sdk`: logical/visible entity分離、fixed-step schedule、off-screen tick、formation、
  deterministic seeded workload、simulation/animation LODを共通APIにする。
- `network-isekai`: 三つの独立sampleを追加する。最低CI fixtureは各1k logical、full固定hardwareは
  Dominion 10k logical/2k visible、factory 100k logicalをsweepする。
- gate: 同一seed/inputのdigest一致、LOD切替時のentity消失0、simulation debt、AI tick p95、
  visible count、GPU skin count、memoryとframe pacingをartifact化する。

### M5 — Matrix expansion: 未達成

catalogにgenre名はあるが、残りmatrixを共通API上で動かす実装証拠はない。既存ゲームへの
`:template` linkはvertical sliceの代替にならない。

- 各sampleは独立 `sample.edn`、source/fork/play/benchmark URL、reduced CI scenario、license
  provenanceを持つ。未実装は `planned` または `lab` のままにする。
- `kami-engine-sdk` にsample generatorとschema version migrationを実装し、全manifestを同じ
  validatorで走らせる。
- engine fork削減は、sample固有host import数と共通component/API利用率をreleaseごとに集計する。
- gate: 2D/3D各matrixで最低1 playable、全catalog entryのリンクcheck、schema contract test、
  accessibility keyboard/controller smoke、console error 0。

### M6 — Public release: 部分達成

benchmark HTML/catalog EDNはproduction deploy済みで、catalog page smokeも通過した。しかし
測定result、Studio導線、rollback rehearsal、security/license/accessibility review artifactが
なく、KAMI Royale のproduction visual smokeも黒い300×150 canvasと `loading` で停止したため
release完了ではない。WASM/`app.js` の200応答とconsole errorなしだけでは描画成功と判定しない。

- `network-isekai/public/benchmarks/results/<build-sha>/<scenario>.{edn,json}` をimmutableに保存し、
  pageには `target` と `measured`、hardware/browser/date/seedを明確に分けて表示する。
- production URLで Play/Fork/Source/Benchmark 全link、WebGPU fallback、404、CSP、console、
  keyboard操作を自動E2Eする。canvasについては非黒画素率、期待scene marker、`loading`解除、
  request完了をassertし、単なるHTTP 200をvisual passにしない。
- deployment ID、live smoke結果、直前releaseへのrollback commandとrehearsal時刻をrelease recordへ残す。
- gate: M2〜M5のうち公開対象sliceだけが各milestone gateを満たすこと。計画中sampleは `lab` とし、
  production/AAA-quality countへ含めない。

## AAA visual quality の合否標準

「AAA quality」は解像度やPBR shaderの存在だけでは判定しない。独自assetを用い、比較対象の
固有UI、level、character、音声を複製せず、次の固定capture protocolを通す。

1. desktop WebGPU基準機、2560×1440、quality preset固定、release build、同一camera/input trace。
2. traversal、combat、crowd、streaming transition、stress/recoveryを各30秒以上captureする。
3. frame pacing p95 ≤ 16.6 ms、p99と1% lowを併記。shader compilation hitch、LOD pop、texture
   residency miss、shadow instability、animation foot sliding、alpha/particle artifactを記録する。
4. art review rubricを Lighting/material、composition/readability、animation/feedback、VFX/audio、
   UI/accessibility、temporal stability の6軸各0〜5点で採点する。各軸4以上かつ重大artifact 0を
   **Showcase visual gate** とする。
5. raw video、lossless screenshots、telemetry、build SHA、settingsを同じresult bundleへ保存し、
   reviewer 2名の署名を付ける。自動screenshotだけでAAA相当とは表示しない。

現時点ではこのcapture bundleがなく、KAMI Royale のproduction canvasも描画未確認のため、公開文言は **AAA-inspired target** または
**AAA reference characteristics** に限定する。「AAA quality achieved」は使用しない。

## 実装優先順位

1. **P0 — 証拠基盤**: M0の未完部分（runner、result schema/store/compare、fixed hardware profile、
   browser capture）とStudio telemetry consumerを先に閉じる。
2. **P1 — M2**: Nightglass 8-player authorityを最小sliceで完成し、netcode contractを固定する。
3. **P1 — visual gate**: Nightglassでcapture protocolとrubricを実運用し、誇大表示を防ぐ。
4. **P2 — M3**: Ashen streamingとDream Stage avatarを別laneで進め、共通LOD/budget APIで統合する。
5. **P3 — M4**: crowd/simulation LOD。M3のLOD contractを再利用してからfull scaleを測る。
6. **P4 — M5/M6**: matrixを薄く広げず、gate通過sliceだけを段階公開しrollback rehearsalを行う。

milestoneはPR merge、catalog entry、design mockだけでは閉じない。上記artifactのURLまたは
content hashを本addendumへ追記し、第三者が同じscenarioを再実行できた時点で `accepted/complete`
へ変更する。
