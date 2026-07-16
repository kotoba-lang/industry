# ADR-2607020200: 原稿 (genko) manga-editor の document model を cljc SSoT 化

**Status**: accepted  
**Date**: 2026-07-02  
**Deciders**: Jun Kawasaki

## Context

genko（原稿）は `kami-engine-sdk` の `src/lib/genko/genko-embed.ts` — WebGPU pentab
manga エディタで、`genkoEmbedHTML(name, nanoid)` が **自己完結 HTML 文字列**（CSS +
DOM + inline JS ランタイム、約 2500 行）を返す形。`embedMode: "draw"` の全 app で共有され、
document は B2/R2 に JSON（`mangaka/docs/{docId}.json`）で永続化される。

この inline JS には、UI/WebGPU 描画/ネットワークとは独立な**純粋なドメインロジック**が
埋もれていた:

- document/page/node **データモデル**（`{pages:[{nodes:[{id,type,visible,data}]}]}`、
  node types = stroke/panel/tone/fukidashi/text/prompt/ai-image/ai-desc/group/link）
- **node-tree ops**（親子は `_parent` 文字列ポインタ、`allNodes`/`findByNid`/`setParent`/
  `wouldCycle`/`reorderNode`/`isNodeVisible`/tree 走査）
- **oplog（event-sourcing）**（`recordOp`/`replayOplog` — stroke/addOverlay/reparent/
  move/toggle/delete/undo/redo/addPage/deletePage/switchPage/youshi/panelPreset）
- **serialize/deserialize**（JSON）

プロジェクトの方針（「手書き JS でロジックを再実装しない＝SSoT 一本化」「純 cljc コア +
host 注入 port」、既存の `kami-mangaka-{text,expression,scene,page}-clj`）に照らすと、
このロジックが TS 側にしか無いのは重複源になり、langgraph/サーバ（clj）や genko/reader/
mangaka の各面が同じ genko doc を扱えない。

## Decision

**genko の「頭脳」= document model + 純ロジックを、新規 `kami-mangaka-genko-clj`
（kotoba-lang/kami-engine モノレポのサブディレクトリ、純 cljc）に忠実移植して SSoT 化**する。
WebGPU 描画・DOM・B2/PDS I/O は host（TS/Svelte ランタイム、langgraph host-fn）に残す。

1. **忠実移植**（`kami.mangaka.genko`）:
   - doc/page/node モデルを JSON キー verbatim（`:activePageIdx` `:_nid` `:fukiType`
     `:x1` …）で保持し **round-trip**（`read-doc`/`write-doc`/`normalize`）。
   - 親子は `:_parent` 文字列ポインタ（`""`=root、`:_layer` は旧別名）、可視は `!==false`
     （欠損=可視）を忠実再現（`all-nodes`/`find-by-nid`/`set-node-parent`/`would-cycle?`/
     `node-visible?`/`reorder-nodes`/`node-tree`）。
   - oplog は `record-op`（純 append）+ `replay-oplog`（純 doc 再構築）。`aiGenImage`/
     `aiGenDesc`/`scaleNode` は op に決定的再現用 payload が無いため genko と同じく no-op。
   - text node の 3 スキーム（size/dir/float-color、fontSize/vertical/fontFamily、
     fontSize/font/hex-color）は rename せず共存。
2. **expression 語彙の共有・storyboard 橋渡し**: fukidashi 形（oval/jagged/cloud/square/
   wavy）は `kami.mangaka.expression` の `:bubble` 語彙の部分集合、tone-pattern
   （dot/line/cross/grad）は背景トーン（:dot/:hatching/:gradient）へ写像。`page->storyboard`
   が genko page を `kami.mangaka.text` / `ai.gftd.mangaka.analyzeExpression` が消費できる
   `{:panels [...]}` に射影する（ADR-2607012100 と接続）。
3. **純度**: 純データ/純関数のみ（babashka-safe / JVM・cljs・WASM 可搬）。JSON read/write
   のみ reader-conditional（clj = `clojure.data.json`、cljs = `js/JSON`）。id 生成は明示 id を
   渡す純 API を基本に、host 用 `gen-nid`（非純ヘルパ）を提供。

## Consequences

- (+) genko doc の model/tree/oplog/serialize が **1 ソース（cljc）**になり、TS ランタイム・
  clj サーバ・langgraph・reader/mangaka が同じ意味論で genko を扱える。
- (+) `page->storyboard` で genko の作画データが `analyzeExpression`（表情・薄さ・大きさ・
  背景トーン）と mangaka.text/page レンダに繋がる（「genko で同 edn 語彙を消費」の実現）。
- (+) event-sourcing の replay が純関数として test 可能（決定的）。
- (−) 本 ADR では **TS の inline JS ランタイムは置換していない**。genko-embed.ts は当面
  現状のまま動き、cljc は並行の SSoT（将来 cljs へコンパイルして TS ランタイムの該当
  ロジックを差し替える follow-up が残る）。それまでは cljc↔TS の意味論一致を test で担保する。
- (−) replay は `aiGenImage`/`aiGenDesc`/`scaleNode` を再現しない（genko と同じ既知の限界）。

## References

- ADR-2607012100 — mangaka 表現パターン（表情・薄さ・大きさ・背景トーン、analyzeExpression）
- ADR-2606282100 / ADR-2606282101 — Tier-1 mangaka（work-agnostic page/text/scene 層）
- `orgs/kotoba-lang/kami-engine/kami-mangaka-genko-clj`（本 ADR で新設）
- `orgs/kotoba-lang/kami-engine-sdk/src/lib/genko/genko-embed.ts`（移植元、host ランタイムは継続）
- 本 ADR とペアの .edn
