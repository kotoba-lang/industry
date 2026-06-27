# ADR-0022: spirit-in-physics を Svelte/TypeScript から Clojure + kami-engine へ移行する

- **Status**: Accepted（移行中 — P0/P1 ✅ 完了（HUD 実装+検証+sip.etzhayyim.com 公開）/ エンジン層 ~70-80%。2026-06-27）
- **Date**: 2026-06-27
- **Deciders**: 河崎純真 (jun@gftd.group)
- **Context tags**: spirit-in-physics, kami-engine, clojure, clojurescript, datomic, datalevin, kotoba, webgpu, migration, rewrite

## Context

「Spirit in Physics（水の都の心音）」には現在 **2 つの実装が並行して存在**する。
これまでこの関係・移行方針を記す ADR が無く、`deps.edn` の記述も旧スタックのまま
だったため、本 ADR で意思決定として確定する。

### 旧実装（現役）: `org-spirit-in-physics`（旧 `spirit-in-physics`）

- スタック: Svelte 5 / SvelteKit / TypeScript / Capacitor(iOS) / Hono + LangGraph Pregel /
  Cloudflare Workers + D1 + R2
- 構成: `apps/{web, researcher, mobile, api-worker}`
- 計測: clj/cljs **0 ファイル**、TS 53 / Svelte 70 / JS 125（2026-06-27 時点）
- `PROJECT.jsonld` の narrative は ~2026-01 まで活発に更新（GKE Autopilot 移行等）

### 新実装（移行先）: `kami-engine/kami-app-sip-clj`

- スタック: Clojure（JVM authoring）+ ClojureScript（browser runtime）+
  Datomic/datalevin（world）+ Kotoba（content-addressed durable）+
  kami-render（Rust/wgpu WASM、WebGPU/Metal/Vulkan/DX12/GL）
- 設計思想: **「clj が脳 · Datomic が世界 · Kotoba が記憶 · kami-render(wgpu) が GPU の腕」**
- story-bible の source of truth は **`org-spirit-in-physics-comics`（旧 `260208-spirit-in-physics`）**
  の EDN（volumes / personas / emotions / storyboards）

### 下地となる kami-engine の Clojure 層（別途 ADR で正規化済み）

kami-engine は Rust コア（29 crates / ~536 .rs）の上に Clojure 著作層を持ち、
2 ランタイムモデルを採る（kami-engine `90-docs/adr/0038`）:

- **Model A（authoring）**: JVM/CLJS + ライブ Datomic（`as-of` undo）+
  `kami-engine-sdk-clj` + `kami-clj-host`（render-IR → wgpu）
- **Model B（shipped）**: `game.wasm` 単一ファイル + baked snapshot +
  `kami-script-runtime`（wasmtime/wasmi、bit-identical）。web/mac/iOS/Android/PS5/Switch

関連 ADR（kami-engine 側、いずれも Accepted）:
- `0035` Clojure→WASM game scripting（compiler `kami-engine-clj`）
- `0036` `kami-engine-sdk-clj`（Datomic + scene/ECS/WebGPU）
- `0038` Rust base + clj/Datomic game layer（Model A/B 正規化）
- `0039` `kototama` 単一コンパイラ entry + `run_with_render_ir` 単一 renderer

`kami-app-sip-clj` はこの Model A の **実アプリ実装例**である。

## Decision

1. **Spirit in Physics の正式な移行先を `kami-engine/kami-app-sip-clj`（Clojure + kami-engine,
   Model A）とする。** 旧 `org-spirit-in-physics`（Svelte/TS）はパリティ達成までの
   間は現役で残し、段階的に置換する（並行運用を許容）。

2. **story-bible の source は `org-spirit-in-physics-comics` の EDN に一本化する。**
   旧 `org-spirit-in-physics` 配下の物語データは新実装の参照元にしない。

3. **バックエンドは廃し、ローカル Datomic/datalevin + Kotoba に置換する。**
   - D1（SQL）→ datalevin/kotoba-datomic（Datalog）
   - R2（object store）→ Kotoba CID（content-addressed）
   - Hono + gRPC api-worker → 不要（ローカル Datomic の as-of/pull）

4. **`deps.edn` / west manifest に新旧の関係を明記する**（下記 Consequences）。

### 旧→新コンポーネント対応

| 旧（Svelte/TS） | 新（clj + kami） | 状況 |
|---|---|---|
| apps/web | `sip.web`(cljs) + `sip.render`/`sip.page` | コア実装済み |
| apps/researcher | 単一 world に統合（分離しない方針） | 未確定 |
| apps/mobile（Capacitor） | cljs CSR / 将来 Model B（native ship） | 未着手 |
| apps/api-worker（Hono gRPC） | 廃止（ローカル Datomic） | 廃止 |
| D1（SQL） | datalevin / kotoba-datomic | 置換 |
| R2 | Kotoba CID（LocalCas → real server） | 置換中 |

## Status / 実装進捗（2026-06-27 ローカル実地検証）

### アプリ層 `kami-app-sip-clj` — コア完成・統合段階

ローカル検証（このマシン: clojure 1.12 / bb 1.12 / java 21 / node 26）:

- ✅ **純粋セッション FSM テスト**: `bb test:pure` → **5 tests / 13 assertions / 0 failures**
- ✅ **ワールド構築**: `clojure -M:datomic:build` →
  `public/snapshot.edn`（**47 entities / 8 assets**, scene `spirit-in-physics/water-city`）生成
- ✅ **full テストスイート**: `clojure -M:datomic:test` →
  **12 tests / 38 assertions / 0 failures**（session + render + store）
- ✅ **cljs リリースビルド**: `clojure -M:shadow release app`（JVM classpath, npm 不要）→
  `public/js/sip.js`（**205 KB**）+ `manifest.edn` 生成。型推論 warning 1 件
  （`kami-engine-sdk-clj/src/kami/backend/browser.cljs:33`、非致命）のみ。
  ※ `npx shadow-cljs` 経由はローカル npm 不具合（`cb.apply is not a function`）で失敗するため JVM 経由を推奨

実装済み: session FSM（observe→resonate→accompany→name、純粋・無敗北）/ world（水の都+8 area）/
schema（kokoro/agent/area/session/insight/瓶詞）/ store 二層（Datomic + Kotoba CID）/
panel 生成（108 storyboard → 実 768×1152 PNG, AnimagineXL）/ ブラウザ end-to-end GPU 描画（Chrome WebGPU 確認済み）。

未実装: ゲームプレイ UI/HUD・入力処理、本番 Kotoba サーバ連携、`as-of` undo（datalevin に
time-travel が無く Datomic Cloud/Peer が必要）、mobile/researcher。

### エンジン層 `kami-engine-clj` 系 — ~70-80%

`kami-engine-clj`（CLJ→WASM compiler, parity-tested）/ `kami-engine-sdk-clj`（16 tests・61 assertions
緑、datalevin roundtrip、clj↔Rust byte contract）/ `kami-clj-host`（render-IR デコーダ + wgpu, 4 Rust tests）/
`kami-script-runtime`（wasmtime+wasmi bit-identical）/ ネイティブプレイヤー（survivors, waves）すべて稼働。
残: browser backend の WIT、Kotoba 本番、`as-of`、WGSL emitter 範囲、open-world ストリーミング、
性能/コンソール packaging。

### TS SDK との関係

`kami-engine-sdk`(TS/Svelte: DOM UI/VRM/manga editor) と `kami-engine-sdk-clj`(clj: scene/ECS/render-IR)
は **用途が異なり共存**（置換ではない）。

## Consequences

- (+) バックエンド（gRPC/Hono/D1/R2）を排し、ローカル Datomic + Kotoba で状態・記憶・undo を一元化。
- (+) GPU は Rust/wgpu を再利用し、ゲーム論理は data-driven な Clojure/EDN に集約。
- (+) story-bible が comics リポ（`org-spirit-in-physics-comics`）に一本化され、参照が明確化。
- (−) パリティ達成まで新旧 2 実装が並行し、二重メンテのコストが生じる。
- (−) mobile/researcher は新実装で未着手。ネイティブ出荷は Model B（`game.wasm`）への対応が前提。
- (−) `as-of` undo は datalevin では不可。フル機能には Datomic Cloud/Peer への昇格が必要。

### フォローアップ（別タスク）

- `deps.edn`: spirit エントリに新実装（kami-app-sip-clj）への相互参照と本 ADR リンクを追記。
- **命名整合**: GitHub repo は `org-spirit-in-physics` / `org-spirit-in-physics-comics` に rename 済みだが、
  `manifest/west.yml` と `deps.edn` はメイン同期時に旧名へ巻き戻っている。west 再生成 + dir 整理で解消する
  （old 名 dir が redirect 経由で再 clone され重複している）。
- 旧 `org-spirit-in-physics` の段階的廃止スケジュール（web パリティ → researcher → mobile）。

## 旧アプリ廃止計画（フェーズ）

旧 `org-spirit-in-physics`（Svelte/TS）を一気に止めず、**web パリティ → researcher → mobile**
の順で段階的に置換する。各フェーズに exit 条件を置き、満たすまで旧実装を現役で残す。

| Phase | 内容 | 主要タスク | Exit 条件 |
|---|---|---|---|
| **P0 ✅ 済** | clj コア基盤 | engine層 + app scaffold | session/world/render/store 緑（2026-06-27 検証済み）、cljs bundle 生成可 |
| **P1 ✅ 済** web パリティ | プレイ可能な web | HUD `sip.ui`（純粋 FSM 駆動・DOM オーバーレイ・心音/寄り添いメーター・呼吸入力・完了画面）+ wasm 動的 import。Worker 静的配信で **sip.etzhayyim.com** へ deploy | **達成**（kami-engine PR #59 HUD / #62 deploy）。cljs build 0警告・`bb test:pure` 緑・ヘッドレス実クリックで observe→resonate→accompany→name→complete・エッジ HTTP 200。※現状 wasm 無で HUD のみ（3D 背景は後続） |
| **P2 durable/multiplayer** | 永続・非同期協調 | 本番 Kotoba サーバ連携、瓶詞（CID 非同期マルチ）end-to-end | LocalCas mock を実サーバへ置換し往復成立 |
| **P3 researcher** | 研究ポータル | apps/researcher（voice assessment / n=1000 study）の移植 or TS researcher 併存判断、D1→datalevin/**Datomic Cloud**（as-of 用）データ移行 | 研究データ継続性を保ったまま新基盤で研究フロー成立 |
| **P4 mobile** | ネイティブ出荷 | Capacitor を **Model B（game.wasm native ship）** か PWA へ置換 | iOS/Android で配布可能 |
| **P5 cutover & 廃止** | 切替・退役 | DNS（spirit-in-physics.com）を新実装へ、旧 apps を archive、本 ADR と STRATEGY/PROJECT を更新 | 全トラフィックが新実装、旧 repo は参照専用にアーカイブ |

**クリティカルパス / リスク**:
- **as-of undo は datalevin 不可** → P3 で Datomic Cloud/Peer 昇格（コスト発生）。それまで P1/P2 は baked snapshot + Kotoba で回す。
- **researcher の研究継続性**（n=1000 戦略 / Nature 投稿）が最大の非機能要件。P3 はデータ移行を慎重に。
- **mobile はストア再申請**が絡むため最後段（P4）。
- 旧 `org-spirit-in-physics` は大容量データ移行（ADR-0006: LFS→DataLad）が未了。P5 アーカイブ時に併せて B2+DataLad へ。

**次の着手は P1**（既存の緑なコア＋生成済み `sip.js` の上に、プレイ UI と入力を載せて公開）。

## References

- 新実装: `orgs/com-junkawasaki/kami-engine/kami-app-sip-clj/README.md`, `docs/ARCHITECTURE.md`
- エンジン層: kami-engine `90-docs/adr/0035,0036,0038,0039`
- 旧実装: `orgs/com-junkawasaki/org-spirit-in-physics/claude.md`, `STRATEGY.jsonld`, `PROJECT.jsonld`
- 関連: ADR-0006（spirit-in-physics LFS→DataLad）, ADR-0020（three-org taxonomy）
</content>
</invoke>
