# ADR-2607051200: kotoba-lang の "*ui*" 系リポジトリ名衝突を解消する — `ui`/`ui-gpu`/`wasm-ui`/`kami-ui-sdk` を改名

**Status**: accepted — landed (2026-07-04)
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/{ui,ui-gpu,wasm-ui,kami-ui-sdk}` および依存 repo（`cssom`/`htmldom`/`browser`/`shell`/`kami-app-character-creator`）

## Context

ADR-2607022800 で `kotoba-ui`/`appkit`/`uikit`（+ `liquid-glass-ui`）を kotoba-lang
の default UI/UX design（SwiftUI/AppKit/UIKit 相当の統合エントリ + 画面形状
バインディング）として新設した。この直後にオーナーから「他にも
`kotoba-lang/ui`・`wasm-ui`・`ui-gpu` が既にあったが違いは？」と問われ、調査の
結果、これらは全く別系統であることが判明した:

- **`orgs/kotoba-lang/ui`**（package `kotoba.ui`） — kami-engine の WebGPU
  ゲームキャンバス上に DOM オーバーレイ HUD（`:panel`/`:bar`/`:minimap`/`:text`）
  を描く、完成済みの狭い責務の repo。ADR-2607022800 で「別物・転用しない」と
  明記済み。
- **`orgs/kotoba-lang/ui-gpu`** — 上記 HUD の GPU 描画バックエンド（削除された
  Rust `kami-ui-gpu` crate の移植）。
- **`orgs/kotoba-lang/wasm-ui`** — `orgs/kotoba-lang/browser`（ゼロから作る
  kotoba-only・WASM-only ブラウザエンジン）が消費する DOM ABI(`kotoba:dom`)・
  retained tree・layout・WebGL/WebGPU レンダーホスト基盤。`browser/README.md`
  には「将来的に `kotoba-lang/ui` へ改名するのが望ましい」という自己言及の
  Naming 節が既にあったが、その名前は上記の別 repo が先取り済みで恒久的に
  ブロックされていた。
- **`orgs/kotoba-lang/kami-ui-sdk`** — kami-engine の WebGPU アプリ向けブラウザ
  SDK 6 種（DOM オーバーレイ UI・アニメーション・パーティクル FX・手続き型 UI
  効果音合成・エンジン音合成・WebRTC ブリッジ）。UI 単体ではない grab-bag。

4 つとも実装・責務は正当だが、名前に `ui` を含むため、新設した
`kotoba-ui`/`appkit`/`uikit`/`liquid-glass-ui` ファミリー（web/app 向け宣言的
デザインシステム）と紛らわしく、`wasm-ui` に至っては「いずれ `ui` に改名する」
という既存の設計メモ自体が矛盾（名前衝突で実行不可能）を抱えていた。オーナーの
指示で、kotoba-lang org 全体の依存関係を踏まえて 4 repo を改名した。

## Decision

| 旧名 | 新名 | 理由 |
|---|---|---|
| `ui` | `kami-engine-hud` | kami-engine の他モジュール（`scene`/`game`/`physics`/`skeleton`/`sprite2d` 等）の裸の機能名規約から離れ、`kami-engine-core`/`kami-engine-io`/`kami-engine-render` 等の `kami-engine-*` ファミリーに揃え、"Heads-Up Display" という実体を正確に表す |
| `ui-gpu` | `kami-engine-hud-gpu` | `kami-engine-hud` とペア。既存の `sprite-gpu` と同じ `-gpu` サフィックス規約に一致 |
| `wasm-ui` | `dom-gpu` | 「`kotoba:dom` retained-tree を GPU に描く」実体を正確に表す。`ui-gpu`/`sprite-gpu` と同じ `-gpu` 規約。`browser/README.md` の「いずれ `ui` に改名」という既存アスピレーションを解消（`ui` は `kami-engine-hud` として既に占有されているため恒久的に不可能だった） |
| `kami-ui-sdk` | `kami-engine-app-sdk` | 6 SDK は UI + アニメーション + パーティクル FX + 音声合成 + WebRTC を含み UI 単体ではない。`kami-app`/`kami-app-isekai` 等の `kami-app-*` ファミリーと `kami-engine-*` ファミリーの両方に整合する名前にした |

いずれも kotoba-ui/appkit/uikit/liquid-glass-ui（宣言的 UI デザインシステム）
とは無関係であることを名前でも明確にする。

## Execution

すべて即時実行済み（オーナー承認済み。GitHub repo rename・依存 repo の
`deps.edn` 更新・west manifest 反映まで一気通貫）:

1. **GitHub repo rename**: `gh repo rename` で 4 repo を改名
   （`kotoba-lang/kami-engine-hud`・`kami-engine-hud-gpu`・`dom-gpu`・
   `kami-engine-app-sdk`。GitHub は旧名からの redirect を保持する）。
2. **ローカル checkout**: `orgs/kotoba-lang/<new-name>` へディレクトリを
   移動し、remote URL を新名に張り替え。
3. **依存 repo の `deps.edn` 更新**（実コード依存があったのは `wasm-ui`/
   `kami-ui-sdk` のみ。`ui`/`ui-gpu` に実コード依存は無かった）:
   - `cssom`・`htmldom`・`browser`: `io.github.kotoba-lang/wasm-ui
     {:local/root "../wasm-ui"}` → `io.github.kotoba-lang/dom-gpu
     {:local/root "../dom-gpu"}`
   - `kami-app-character-creator`: `io.github.kotoba-lang/kami-ui-sdk
     {:local/root "../kami-ui-sdk"}` → `io.github.kotoba-lang/kami-engine-app-sdk
     {:local/root "../kami-engine-app-sdk"}`
   - `shell`（`kotoba.shell.launcher/ui-substrate-specs`）: `:repo "../wasm-ui"`
     → `:repo "../dom-gpu"`、`:ui-substrate "kotoba-lang/wasm-ui"` ラベル
     （`surface-host-specs` 4 target + `surface-check-result`）→
     `"kotoba-lang/dom-gpu"`。対応するテスト期待値も更新し、
     `clojure -M:test` で確認（後述の pre-existing failure を除き green）。
   - 各 repo の README／`browser/README.md` の Naming 節（「いずれ `ui` に
     改名」というブロックされていたアスピレーションを解消する記述に更新）。
4. **意図的に変更しなかったもの**（scope を repo 名/依存グラフのリネームに
   限定し、blast radius を抑えるため）:
   - 内部 Clojure namespace（`kotoba.ui`・`kami-ui-sdk.*`・`kotoba.wasm.*`
     等）はそのまま。repo 名と namespace 名は独立の概念であり、namespace
     rename は別スコープの作業。
   - `dom-gpu`（旧 wasm-ui）内部の npm script 名・ビルド成果物名
     （`wasm-ui`/`wasm-webgpu` script、`kotoba-wasm-ui.html` 等）はそのまま。
   - `shell` の CLI 値 `--substrate wasm-ui`（内部 keyword `:wasm-ui`、
     デフォルト `[:wasm-ui :browser]`）はそのまま維持。`shell` の
     `stable-api-spec` はコマンドの**存在**のみを凍結対象としており
     （`["ui" "check"]`/`["ui" "smoke"]` 等）オプション値までは含まないため
     破壊的変更ではないが、既存自動化が `--substrate wasm-ui` を直値で
     叩いている可能性を考え、CLI 語彙自体は今回の改名対象に含めなかった。
5. **`manifest/repos.edn`**: `:path-overrides` に旧パス→新パスの 4 entry を
   追加（west.yml に既に焼き込まれていた旧パスをこの override で新パスへ
   翻訳し、幽霊 entry の二重登録を防ぐ — `aiueos`/`kootba→svgraph`/
   `clc→office-style` 等の既存 org-transfer/rename 事例と同じ機構）。
   併せて `:extra-projects` の該当 4 行も新パス名に更新（旧名は
   `:path-overrides` の翻訳キーとしてのみ残る）。
6. **`manifest/west.yml`**: `nbb scripts/gen-west-manifest.cljs --entry
   kami-engine-hud,kami-engine-hud-gpu,dom-gpu,kami-engine-app-sdk` で
   当該 4 entry のみ最小 diff 生成（サーバ側 pin 検証 OK）。旧 4 entry は
   splice では自動削除されない（`--entry` は指定 entry の置換/挿入のみで
   削除は行わない）ため、手動で該当ブロックを削除。全 653→649 entry の
   純増減が正しく閉じていることを確認済み。wholesale 再生成は一度試して
   他の無関係な repo（`ghosthacker-flow`/`etzhayyim/root`/`cloud-murakumo`/
   `manimani` 等）の pin まで巻き込むと判明したため破棄し、`--entry` の
   最小 diff のみを採用（CLAUDE.md の「wholesale 再生成禁止」規律どおり）。
7. **並行 push race**: `dom-gpu`（旧 `wasm-ui`）への push 中に、無関係な
   PR #4 (`feat/document-renderer-demo`) が別セッション/自動化によって
   同じ `main` へ concurrent にマージされ、単純 push が reject された。
   ローカル rebase/force-push はせず、変更を一時 branch
   (`rename-docs-fixup`) に push し、`gh api repos/.../merges` でサーバ側
   マージ commit を作成して解消（CLAUDE.md の race 対応手順どおり）。
   west.yml の `dom-gpu` pin もこのマージ後の実 tip に合わせて再取得した
   （`ai-gftd-mangaka`/`kotoba-lang/kami-engine` の 2 repo がローカル
   checkout 遅れによる無関係な pin 退行を検出したため、この 1 entry の
   再生成のみ `--no-verify-remote` を使用し、対象 pin の前進は GitHub
   compare API で個別に手動検証した）。

## Consequences

- (+) kotoba-lang org 内に 2 つの独立した "*ui*" ファミリーが存在するという
  紛らわしさが解消: 宣言的デザインシステム（`kotoba-ui`/`appkit`/`uikit`/
  `liquid-glass-ui`）と、kami-engine 系（`kami-engine-hud`/
  `kami-engine-hud-gpu`/`kami-engine-app-sdk`）・ゼロから作るブラウザ
  エンジン系（`dom-gpu`）が名前でも明確に分離された。
- (+) `browser/README.md` に残っていた「`wasm-ui` はいずれ `ui` に改名
  すべき」という、実行不可能になっていた設計メモが解消された。
- (−) GitHub は旧名からの redirect を保持するが、4 repo の URL は変わった。
  `kotoba-lang` org 外の第三者が旧 URL を直接ブックマークしていた場合は
  redirect 経由になる。
- (−) 内部 namespace（`kotoba.ui`/`kami-ui-sdk.*`/`kotoba.wasm.*`）・npm
  script 名・`shell` の `--substrate wasm-ui` CLI 値は意図的に未変更のまま
  残っており、repo 名と内部識別子の不一致が新たに生まれた（例:
  `:wasm-ui {:repo "../dom-gpu" ...}`）。将来的な namespace/CLI 語彙の
  統一は別 ADR の follow-up とする。
- (−) 作業中に `shell` の `ui-substrate-specs` に既存の無関係な drift を
  発見した: `:browser` 側が期待するファイル名
  (`src/browser/visual_smoke_check.cljc`) と npm script 名
  (`compile:smoke`/`compile:webgpu-smoke`/`smoke:visual`/`smoke:webgpu`)
  が、`browser` repo の実際の現状（`visual_smoke_model.cljc`、異なる
  script 名）と乖離しており、`clojure -M:test` で 8 件の pre-existing
  failure がある（本 ADR の改名作業とは無関係、今回は未修正）。
- (−) 同様に、いくつかの historical provenance コメント（`cssom/src/cssom/
  layout.cljc` の docstring、`cssom/README.md`、`window-session-state/
  README.md`、`freeboard/README.md` の `kami-ui-gpu`/`wasm-ui` への言及）は、
  当時の事実を正しく記録した履歴的記述であるため、既存の `kami-text→glyph`
  改名時の前例に倣い意図的に未変更のまま残した。

## Alternatives Considered

- **内部 namespace・npm script・CLI 語彙まで一括で改名する**: 却下。repo
  名/依存グラフの改名という本タスクの scope を超え、`shell` の
  `--substrate` のような外部から呼ばれうる値まで変更すると blast radius が
  不必要に広がる。
- **`:path-overrides` だけ追加し `:extra-projects` の該当行は旧名のまま
  残す**: 却下。`repos.edn` は「人間が読む tribal knowledge」でもあるため、
  正典の登録一覧が古い名前のままだと可読性が落ちる。既存の
  `aiueos`/`kootba→svgraph`/`clc→office-style`/`edn→office` の rename 事例は
  いずれも旧名を `:extra-projects` から除き `:path-overrides` にのみ残す
  形を取っており、それに揃えた。
- **wholesale `nbb scripts/gen-west-manifest.cljs`（`--entry` なし）で
  west.yml 全体を再生成する**: 却下。実際に試したところ
  `ghosthacker-flow`/`etzhayyim/root`/`cloud-murakumo`/`manimani`/
  `network-isekai`/`arrangement`/`character`/`comfyui` 等、本改名と無関係な
  多数の repo の pin まで前進させてしまうと判明（それらのローカル
  checkout が最後の pin より進んでいたため）。CLAUDE.md が禁じる
  「wholesale 再生成による無関係 pin 巻き込み事故」の再発になるため、
  `--entry` による当該 4 entry のみの最小 diff に限定した。

## Related

- ADR-2607022800: `kotoba-ui`/`appkit`/`uikit`/`liquid-glass-ui` を default
  UI/UX design として確立（本 ADR が解消した名前衝突の発生源）。
- ADR-2607011900: `liquid-glass-ui` の設計。
- ADR-2607010930: clj-wgsl migration（`kami-ui-gpu`/`kami-ui-sdk` の由来）。
- `manifest/repos.edn` の naming-collision-avoidance 系譜（`model-checking`/
  `signal-integrity`/`ic-packaging`/`ip-xact` 等、generic な短名を避けた
  既存の rename 事例群）と同じ規律に従う。
