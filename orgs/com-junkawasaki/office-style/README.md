# @com-junkawasaki/office-style

アップロードした **.pptx からスライドのトーン・マナー・スタイルを抽出**し、
単一の **StyleIR** に正規化する TypeScript パッケージ。

2 レイヤを 1 つの IR に統合する:

| レイヤ | 手段 | 抽出するもの | 確度 |
|---|---|---|---|
| **決定論** | OOXML 直読み (vision 不要) | 配色 (theme `clrScheme` 12 色)・フォント (`fontScheme` major/minor)・スライドマスター/レイアウト・プレースホルダ・既定テキストスタイル・ガイド・スライドサイズ・複数 theme/master | 正解値 |
| **解釈** | Gemma 4 vision (ローカル) | フォーマリティ・ムード・カラームード・密度・ビジュアルスタイル・要約 | 推定 (confidence 付) |

> svgraph (presentation IR の受け皿: masters/text_styles/guides) と office-causal
> (pptx リーダ + ローカル Gemma 4) の中間に位置し、両者で欠けていた
> 「visual からスタイルを抽出して IR 化する層」を埋める。

**ドキュメント**: [アーキテクチャ](docs/architecture.md) ·
[StyleIR スキーマ](docs/ir-schema.md) · [実装状況](docs/STATUS.md) ·
[ADR](docs/adr/) · [LP](docs/index.html)

## なぜ 2 レイヤか

配色・フォント・マスター・ガイドは pptx 内の `theme*.xml` / `slideMaster*.xml` /
`viewProps.xml` に**確定値**として存在する。これを vision で「読む」のは誤差を
生むだけ。一方「上品」「エネルギッシュ」「余白が広い」といった**印象**は XML に
無いので vision が要る。よって構造は OOXML、印象は Gemma という役割分担にした
(ADR [0001](docs/adr/0001-style-ir.md))。

## インストール / ビルド

```bash
npm install
npm run gen:sample   # examples/sample.pptx を生成 (デモ用 2-master pptx)
npm run build        # dist/ に出力
npm test             # ユニットテスト (15)
```

任意依存: `@huggingface/transformers` (vision), `@resvg/resvg-js` (Node ラスタライズ)。

## CLI

```bash
office-style tokens  deck.pptx        # デザイントークン要約 (配色/フォント/数)
office-style extract deck.pptx        # StyleIR (決定論) を JSON 出力
office-style svgraph deck.pptx        # svgraph presentation IR に変換
office-style preview deck.pptx --svg-out preview.svg   # 代表スタイルプレビュー SVG
office-style render  deck.pptx --out-dir ./out         # 実スライドを SVG 化
office-style tone    deck.pptx --device wasm           # 実スライド → Gemma vision
office-style tone    deck.pptx --deck --max-slides 6   # 複数スライド集約
office-style extract deck.pptx --vision a.png b.png --device wasm  # 外部画像を直接 vision
```

`tokens` の出力例:
```
slide: 16:9 (12192000x6858000 emu)
fonts: major=Inter / minor=Inter
colors: accent1=#3B82F6 accent2=#10B981 accent3=#F59E0B dk1=#1A1A1A lt1=#FFFFFF
masters: 2, layouts: 2, guides: 3
```

## API (Node)

```ts
import {
  extractStyle, inferToneFromDeckBytes, withTone, toSvgraphPresentation,
} from "@com-junkawasaki/office-style";

const ir = extractStyle(pptxBytes, "deck.pptx");   // 決定論 StyleIR
ir.theme.colorScheme.accent1.hex;                  // "#3B82F6"
ir.theme.fontScheme.majorFont.latin;               // "Inter"
ir.masters[0].textStyles;                          // title/body/other の既定スタイル
ir.guides;                                         // 編集ガイド (EMU)

// 実スライド → プレビュー描画 → raster → Gemma vision → tone
const tone = await inferToneFromDeckBytes(pptxBytes, ir, { device: "wasm" });
const full = withTone(ir, tone);                   // 非破壊マージ

// svgraph presentation IR へ
const projection = toSvgraphPresentation(full);
```

## ブラウザ (WebGPU)

Node 専用 (CLI, @resvg) を含まないブラウザ入口。Gemma 4 vision は **WebGPU +
q4f16**、ラスタライズは canvas。抽出/描画/svgraph 出力は isomorphic
(fflate + fast-xml-parser)。詳細は ADR [0004](docs/adr/0004-browser-webgpu.md)。

```ts
import { analyzeDeck } from "@com-junkawasaki/office-style/browser";
const { ir, perSlide } = await analyzeDeck(pptxBytes, { onProgress });
// ir.tone = デッキ集約トーン (実スライドを canvas で raster → WebGPU 推論)
```

## パイプライン

```
pptx ─extractStyle→ StyleIR ─renderDeckSlides→ SVG ─raster→ PNG ─Gemma vision→ ToneProfile
                      │   (実スライド / 代表プレビュー)        (resvg / canvas)         │
                      │                                                   aggregateTones │
                      │                                                        withTone ─┘
                      └─toSvgraphPresentation→ svgraph SVGraphPresentationProjection
                                                (masters / layouts / guides / text_styles)
```

## svgraph editor への流し込み (検証済み)

プレビュー SVG は `<metadata>` に取り込み最適化シェイプ (`toSvgraphIngest`) を
埋めるため、svgraph の CLI/エディタがそのまま取り込める。実 svgraph (v0.1.52)
で確認済み (ADR [0003](docs/adr/0003-svgraph-interop.md)):

```bash
office-style preview deck.pptx --svg-out preview.svg
svgraph svgraph-presentation preview.svg     # → svgraph presentation projection

SVGRAPH_BIN=/path/to/svgraph/bin/svgraph.mjs npm run verify:svgraph   # 自動検証 9/9
```

svgraph 側で `text_styles` はフラット (`properties.fontFamily` 直下、`+mj-lt`→Inter
解決済み)、master の `template_id` (`slideMaster1.xml` 等) と per-master theme が
保持され、完全な projection は `slides[0].metadata.json` に round-trip する。
`toSvgraphPresentation` は正規の `SVGraphPresentationProjection` 出力として別途温存。

## 実 OOXML ロバスト性 (検証済み)

外部ジェネレータ (svgraph svgToPptx, テーマ=Aptos) が出力した **24 個の実 pptx** で
抽出 + 全スライド描画が例外・zod 失敗なく通ることを確認:

```bash
REAL_DECK_DIR=/path/to/pptx-dir npm run verify:real   # 無ければ skip → 24/24 OK
```

## StyleIR (`style-ir/1`)

```jsonc
{
  "version": "style-ir/1",
  "source": { "path": "deck.pptx", "app": "ppt" },
  "slideSize": { "cx": 12192000, "cy": 6858000, "unit": "emu", "aspect": "16:9" },
  "theme":  { "colorScheme": {...}, "fontScheme": {...} },   // 代表 theme
  "themes": [ /* デッキ全 theme */ ],
  "masters": [{ "part", "themePart", "theme", "background", "placeholders", "textStyles" }],
  "layouts": [{ "type", "masterPart", "placeholders" }],
  "guides":  [{ "orient":"horz", "pos":3429000, "unit":"emu" }],   // 1/8pt→EMU
  "textStyles": [{ "role":"title", "levels":[{ "lvl":1, "sizePt":44, "bold":true, "color":{...} }] }],
  "tone": { "formality":"formal", "mood":["corporate"], "colorMood":"cool", ... }  // 任意
}
```

全フィールドは `src/types.ts` の zod スキーマで定義・検証される
(`StyleIRSchema.parse()`)。詳細は [StyleIR スキーマ](docs/ir-schema.md)。

## スコープ / 既知の制約 (PoC)

- **読む part**: `theme*.xml`, `slideMaster*.xml`, `slideLayout*.xml`,
  `presentation.xml`, `viewProps.xml`, `ppt/slides/*`。
- **`clrMap`/`clrMapOvr`** 二段解決済み。`phClr` は文脈依存のため代替解決のみ。
- **ガイド単位** 校正済み (1/8 pt → EMU)。スライドサイズ・bbox は EMU。
- **スライドレンダラ (PoC)**: prstGeom (rect/roundRect/ellipse) + custGeom (外接
  矩形近似)、solidFill/gradFill/pattFill、回転、表、画像枠、placeholder スタイル
  継承。未対応: 任意 custGeom の正確なパス・グラデ放射/パス種・SmartArt/グラフ実体
  (必要時は PowerPoint/LibreOffice の PNG を `--vision` に渡す)。
- **複数 master/theme** 対応 (master 別 theme + slide→layout→master 束ね)。

詳細・今後は [docs/STATUS.md](docs/STATUS.md)。

## ライセンス

MIT
