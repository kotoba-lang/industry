# ADR-2607012100: mangaka 表現パターン（表情・姿勢・吹き出し・文字の薄さ/大きさ・背景トーン）を data 化

**Status**: accepted  
**Date**: 2026-07-01  
**Deciders**: Jun Kawasaki

## Context

オーナーから HUNTER×HUNTER 王位継承戦編のコマ（従事兵の名札、モブのざわめき、
スラッカの割り込み、ガターの激昂アップ、対峙の緊張）を参照として示され、
「キャラクターごとに、表情・姿勢・吹き出しの形・文字の薄さ・大きさ・背景のトーンを
変えるのが manga 的表現として重要。今の kotoba-lang mangaka として、これらを edn/cljc の
生成パターンにし、langgraph に分析してから取り込め」との指示。

現状の棚卸し:

- **kotoba-lang/kami-engine の mangaka モジュール群**（Tier-1 mangaka, ADR-2606282100/101）:
  `kami.mangaka.text`（吹き出し `#{:oval :jagged :cloud :square :wavy}` / kind
  `#{:dialogue :narration :sfx :thought}` / locale・hiccup・CSS）、`kami.mangaka.scene`
  （Expression `#{Neutral…Smirk}` / ShotGrammar / FxKind / pose-label）、
  `kami.mangaka.page`（Java2D bake）。→ **文字の薄さ・大きさ・背景トーン・キャラ名札・
  モブのざわめき（mob chatter）は未モデル化**。HxH で効いているのはまさにここ。
- **ai-gftd-mangaka/clj**: langgraph ランタイム（graphs/* + registry.cljc + langgraph.edn の
  lock-step）、`domain.cljc`（`:panel/tone` `:bubble/font(=fontRole)` `:bubble/font-size`
  `:bubble/shape` 等の“受け皿”は既にある）、`data/style-registry.edn`（genre→作画スタイル）、
  `manga-font-palette.json`（10 fontRole = 書体×太さ×用途）。→ **キャラ×感情×強度→表現属性を
  決める pattern 本体が無い**。
- **genko**（kami-engine-sdk の TS エディタ）は consumer 側。

## Decision

1. **新規 `kami-mangaka-expression-clj`**（kotoba-lang/kami-engine モノレポのサブ
   ディレクトリ、既存 `kami-mangaka-*-clj` と同型の純 cljc モジュール）を新設:
   - `resources/mangaka_expression_patterns.edn` を **SSoT**（生成パターン data）とし、
     HxH のコマ観察を `:source` / `:observations` に明記（トレーサビリティ）。
   - `kami.mangaka.expression` — `archetype`(キャラ類型) ← `register`(セリフ種別:
     speech/shout/whisper/thought/monologue/narration/**chatter**/**nameplate**/sfx) ←
     `expression-cue`(感情) ← `intensity`(強度) を merge して 1 行分のスタイルを
     `resolve-style` で解決し、`analyze-line`/`analyze-panel`/`analyze-page` で storyboard に
     当てる。出力は **semantic タグのみ**（`:expression :posture :bubble :font-role`
     `:weight`(薄さ↔太さ) `:scale`(大きさ) `:tone`(背景トーン) `:marks :fx`）。見た目への
     写像は renderer 側に置く。語彙は text の `:bubble`、scene の Expression、
     manga-font-palette の fontRole と整合（vocab lock-step test）。
2. **kotoba-lang mangaka のレンダに取り込み**:
   - `kami.mangaka.text`（hiccup+CSS）: `:weight`→色/太さ class、`:scale`→responsive
     `--mk-scale`、`:bubble :spike/:burst`→clip-path、`:register :chatter`（薄い小さな
     ざわめき）/`:nameplate`（黒箱白抜きゴシック）を描画。reagent/SSR 両対応（`:style`
     map を使わない）。後方互換維持。
   - `kami.mangaka.page`（Java2D bake）: 背景トーン（集中線/フラッシュ/ビネット/
     群衆シルエット/dot/gradient/hatching）+ 薄さ（色）+ 大きさ（font）+ 名札 + ざわめき。
3. **langgraph 分析グラフ `ai.gftd.mangaka.analyzeExpression`**（ai-gftd-mangaka/clj）:
   storyboard の panel/bubble を pattern で enrich する **決定的**解析（LLM 不要・
   オフライン可）。`{:id}`(stored load→enrich→persist) / `{:page}`(inline) の両入力、
   storyboard domain(`:page/panels`/`:panel/bubbles`) と authoring(`:panels`/`:dialogue`)
   の両形に対応。`:cast` で speaker→archetype。registry.cljc + langgraph.edn に lock-step
   登録。`io.github.kotoba-lang/kami-mangaka-expression`（kami-engine の `:deps/root`,
   git-pin + `:dev` local-root）を依存に追加。
4. **`mangaka.domain` の bubble 分解キーに `:bubble/weight :bubble/scale :bubble/register`
   を追加**し、enrich した表現タグが datom round-trip で保持されるようにする。

## Consequences

- (+) 「キャラクターごとの表情・姿勢・吹き出し・文字の薄さ/大きさ・背景トーン」が
  一箇所の edn（`mangaka_expression_patterns.edn`）で宣言され、web reader（text の
  hiccup+CSS）/ 印刷 bake（page の Java2D）/ langgraph 解析 / genko が同じ語彙を共有する。
- (+) 解析は決定的（LLM 不要）でオフラインでも完全に動く。強度で大きさ・太さ・トーンが
  連続的にスケールする。
- (+) 後方互換：既存の text/page/scene は無改変で動作（追加は additive）。
- (−) 吹き出しの spike/burst clip-path の“完成形”は web reader（CSS）側で、Java2D bake は
  太い輪郭での強調に留める（print は近似）。
- (−) `kami-mangaka-expression-clj` は resource-load が `:clj` のみ（cljs/WASM は analyze に
  patterns を渡す）。pattern 本体は edn、code はそれを解決する純関数、という分担。

## References

- ADR-2606282100 / ADR-2606282101 — Tier-1 mangaka（work-agnostic page/text/scene 層）
- `orgs/kotoba-lang/kami-engine/kami-mangaka-expression-clj`（本 ADR で新設）
- `orgs/kotoba-lang/kami-engine/kami-mangaka-{text,page}-clj`（本 ADR で取り込み）
- `orgs/gftdcojp/ai-gftd-mangaka/clj/src/mangaka/graphs/analyze_expression.cljc`（本 ADR で新設）
- `orgs/gftdcojp/ai-gftd-mangaka/lg/scripts/manga-font-palette.json`（fontRole 語彙）
- `orgs/gftdcojp/ai-gftd-mangaka/clj/data/style-registry.edn`（genre→作画スタイル、補完関係）
- 本 ADR とペアの .edn
