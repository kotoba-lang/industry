/**
 * 決定論抽出の最小テスト。examples/sample.pptx (gen:sample で生成) を読む。
 * node:assert + node:test。`npm run gen:sample && npm test`。
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { extractStyle } from "../src/extract/deterministic.js";
import { toSvgraphPresentation, toSvgraphIngest } from "../src/svgraph/export.js";
import { renderPreviewSvg } from "../src/render/preview-svg.js";
import { renderDeckSlides } from "../src/render/deck.js";
import { aggregateTones } from "../src/pipeline.js";

const here = dirname(fileURLToPath(import.meta.url));
const samplePath = resolve(here, "../examples/sample.pptx");

test("StyleIR: theme colors + fonts", async () => {
  const ir = extractStyle(new Uint8Array(await readFile(samplePath)), samplePath);
  assert.equal(ir.version, "style-ir/1");
  assert.equal(ir.theme?.colorScheme.accent1.hex, "#3B82F6");
  assert.equal(ir.theme?.colorScheme.accent2.hex, "#10B981");
  // sysClr lastClr 解決
  assert.equal(ir.theme?.colorScheme.lt1.hex, "#FFFFFF");
  assert.equal(ir.theme?.colorScheme.dk1.hex, "#1A1A1A");
  assert.equal(ir.theme?.fontScheme.majorFont.latin, "Inter");
  assert.equal(ir.theme?.fontScheme.majorFont.ea, "Noto Sans JP");
});

test("StyleIR: slide size + aspect", async () => {
  const ir = extractStyle(new Uint8Array(await readFile(samplePath)), samplePath);
  assert.equal(ir.slideSize?.aspect, "16:9");
  assert.equal(ir.slideSize?.cx, 12192000);
});

test("StyleIR: master placeholders + background + text styles", async () => {
  const ir = extractStyle(new Uint8Array(await readFile(samplePath)), samplePath);
  assert.equal(ir.masters.length, 2);
  const m = ir.masters[0]!; // slideMaster1.xml (sorted by part)
  assert.equal(m.background?.fill, "solid");
  assert.ok(m.placeholders.some((p) => p.type === "title"));
  assert.ok(m.placeholders.some((p) => p.type === "body"));
  const title = m.textStyles.find((s) => s.role === "title");
  assert.ok(title);
  assert.equal(title!.levels[0]?.sizePt, 44);
  assert.equal(title!.levels[0]?.bold, true);
  // schemeClr "dk2" → 実 hex に解決済み
  assert.equal(title!.levels[0]?.color?.hex, "#2B3A55");
  assert.equal(title!.levels[0]?.color?.schemeRef, "dk2");
});

test("clrMap: placeholder color name resolved via master clrMap", async () => {
  const ir = extractStyle(new Uint8Array(await readFile(samplePath)), samplePath);
  const body = ir.masters[0]!.textStyles.find((s) => s.role === "body");
  // body lvl1 は schemeClr val="tx1"。master clrMap は tx1="dk2" なので #2B3A55。
  // (clrMap を読まない素朴実装なら tx1→dk1=#1A1A1A になり区別できる)
  assert.equal(body!.levels[0]?.color?.schemeRef, "tx1");
  assert.equal(body!.levels[0]?.color?.hex, "#2B3A55");
});

test("StyleIR: layout type + master link", async () => {
  const ir = extractStyle(new Uint8Array(await readFile(samplePath)), samplePath);
  assert.equal(ir.layouts.length, 2);
  const l1 = ir.layouts.find((l) => /slideLayout1\.xml$/.test(l.part))!;
  assert.equal(l1.type, "title");
  assert.match(l1.masterPart ?? "", /slideMaster1\.xml$/);
  assert.ok(l1.placeholders.some((p) => p.type === "ctrTitle"));
  // layout2 は master2 にリンク
  const l2 = ir.layouts.find((l) => /slideLayout2\.xml$/.test(l.part))!;
  assert.equal(l2.type, "obj");
  assert.match(l2.masterPart ?? "", /slideMaster2\.xml$/);
});

test("StyleIR: guides (1/8 pt → EMU)", async () => {
  const ir = extractStyle(new Uint8Array(await readFile(samplePath)), samplePath);
  assert.equal(ir.guides.length, 3);
  // pos=2160 (1/8 pt) → 270pt → 3429000 EMU
  assert.ok(ir.guides.some((g) => g.orient === "horz" && g.pos === 3429000 && g.unit === "emu"));
  assert.equal(ir.guides.filter((g) => g.orient === "vert").length, 2);
});

test("svgraph export: presentation projection shape", async () => {
  const ir = extractStyle(new Uint8Array(await readFile(samplePath)), samplePath);
  const p = toSvgraphPresentation(ir);
  assert.equal(p.kind, "svgraph-presentation");
  assert.deepEqual(p.slide_size, [1280, 720]); // EMU→px
  assert.equal(p.masters.length, 2);
  assert.equal(p.masters[0]?.kind, "master");
  assert.equal(p.layouts[0]?.data["type"], "title");
  // text_styles は role 単位 (svgraph properties 形)
  const title = p.text_styles.find((s) => s.style_id === "title-lvl1");
  assert.equal(title?.properties["fontSize"], 44);
  assert.equal(title?.properties["color"], "#2B3A55");
  // guides は EMU → px (3429000 EMU → 360 px)
  assert.ok(p.guides.some((g) => g.position === 360 && g.unit === "px"));
  // theme は metadata に
  assert.equal((p.metadata["theme"] as any)?.colors?.accent1, "#3B82F6");
});

test("slide render: real slide → SVG with resolved colors/fonts", async () => {
  const slides = renderDeckSlides(new Uint8Array(await readFile(samplePath)));
  assert.equal(slides.length, 3);
  const svg = slides[0]!.svg;
  assert.match(svg, /viewBox="0 0 1280 720"/);
  assert.match(svg, /四半期業績レビュー/);          // タイトル本文
  assert.match(svg, /売上は前年同期比/);             // 本文
  assert.match(svg, /fill="#3B82F6"/);               // accent1 図形塗り (schemeClr→theme)
  assert.match(svg, /font-family="Inter"/);          // +mn-lt → minorFont 解決
  // タイトル run の schemeClr val="tx2"。clrMap tx2="dk1" → #1A1A1A
  assert.match(svg, /fill="#1A1A1A"/);
});

test("slide render: placeholder inheritance + geometry + image", async () => {
  const slides = renderDeckSlides(new Uint8Array(await readFile(samplePath)));
  const svg = slides[0]!.svg;
  // rPr 無しの本文 run → master body を継承 (color tx1→dk2=#2B3A55, size 20pt=27px)
  assert.match(svg, /継承テスト/);
  assert.match(svg, /<text[^>]*font-size="27"[^>]*fill="#2B3A55"[^>]*>・継承テスト/);
  // prstGeom ellipse (accent2 塗り)
  assert.match(svg, /<ellipse[^>]*fill="#10B981"/);
  // roundRect (rx>0) の accent1 バッジ
  assert.match(svg, /<rect[^>]*rx="(?!0")\d+"[^>]*fill="#3B82F6"/);
  // 画像プレースホルダ
  assert.match(svg, /image<\/text>/);
});

test("slide render: gradient + rotation + table (slide2)", async () => {
  const slides = renderDeckSlides(new Uint8Array(await readFile(samplePath)));
  assert.equal(slides.length, 3);
  const svg = slides[1]!.svg; // slide2
  // gradFill → linearGradient def (accent1→accent5)
  assert.match(svg, /<linearGradient[^>]*gradientTransform="rotate\(45/);
  assert.match(svg, /stop-color="#3B82F6"/);
  assert.match(svg, /stop-color="#8B5CF6"/);
  assert.match(svg, /fill="url\(#g0\)"/);
  // 回転シェイプ → <g transform="rotate(45...)">
  assert.match(svg, /<g transform="rotate\(45\.00 /);
  // 表 → セル矩形 (accent1 ヘッダ) + テキスト
  assert.match(svg, /<rect[^>]*fill="#3B82F6"[^>]*stroke="#CBD5E1"/);
  assert.match(svg, /指標</);
  assert.match(svg, /\+18%</);
});

test("multi-master: each master resolves its own theme", async () => {
  const ir = extractStyle(new Uint8Array(await readFile(samplePath)), samplePath);
  assert.equal(ir.masters.length, 2);
  assert.equal(ir.themes.length, 2);
  const m1 = ir.masters.find((m) => /slideMaster1/.test(m.part))!;
  const m2 = ir.masters.find((m) => /slideMaster2/.test(m.part))!;
  // 各 master が自分の theme(rels) を持つ
  assert.equal(m1.theme?.fontScheme.majorFont.latin, "Inter");
  assert.equal(m2.theme?.fontScheme.majorFont.latin, "Roboto Slab");
  assert.equal(m1.theme?.colorScheme.accent1.hex, "#3B82F6");
  assert.equal(m2.theme?.colorScheme.accent1.hex, "#DB2777");
  assert.match(m2.themePart ?? "", /theme2\.xml$/);
});

test("multi-master: slide picks its master's theme via slide→layout→master", async () => {
  const slides = renderDeckSlides(new Uint8Array(await readFile(samplePath)));
  const slide3 = slides[2]!.svg; // slideMaster2 系
  assert.match(slide3, /別マスターのスライド/);
  // title は master2 を継承: Roboto Slab フォント, tx1→dk2=#7C2D12
  assert.match(slide3, /font-family="Roboto Slab"/);
  assert.match(slide3, /fill="#7C2D12"/);
  // accent1 図形は theme2 のピンク #DB2777 (theme1 の青ではない)
  assert.match(slide3, /fill="#DB2777"/);
  assert.doesNotMatch(slide3, /#3B82F6/);
});

test("aggregateTones: mode for enums, freq-ranked union for arrays", () => {
  const deck = aggregateTones([
    { formality: "formal", mood: ["corporate", "calm"], colorMood: "cool", density: "balanced", visualStyle: ["flat"], confidence: 0.9, evidence: ["s1"] },
    { formality: "formal", mood: ["corporate", "bold"], colorMood: "warm", density: "balanced", visualStyle: ["flat", "photo"], confidence: 0.7, evidence: ["s2"] },
    { formality: "casual", mood: ["corporate"], colorMood: "cool", density: "dense", visualStyle: ["photo"], confidence: 0.5, evidence: ["s3"] },
  ] as any);
  assert.equal(deck.formality, "formal");        // 2x formal
  assert.equal(deck.colorMood, "cool");           // 2x cool
  assert.equal(deck.density, "balanced");         // 2x balanced
  assert.equal(deck.mood[0], "corporate");        // 最頻
  assert.ok(deck.visualStyle.includes("flat") && deck.visualStyle.includes("photo"));
  assert.equal(deck.confidence, 0.7);             // (0.9+0.7+0.5)/3
  assert.deepEqual(deck.evidence, ["s1", "s2", "s3"]);
});

test("toSvgraphIngest: svgraph-friendly shape (avoids double-nesting)", async () => {
  const ir = extractStyle(new Uint8Array(await readFile(samplePath)), samplePath);
  const ing = toSvgraphIngest(ir) as any;
  // text_styles は role キーのオブジェクト (配列ではない → svgraph がフラットに取り込む)
  assert.equal(typeof ing.text_styles, "object");
  assert.ok(!Array.isArray(ing.text_styles));
  // +mj-lt が実フォント Inter に解決済み、properties は raw 袋
  assert.equal(ing.text_styles.title.fontFamily, "Inter");
  assert.equal(ing.text_styles.title.fontSize, 44);
  assert.equal(ing.text_styles.title.properties, undefined); // 二重ネストしない
  // masters/layouts は id 付き、guides は px
  assert.equal(ing.masters[0].id, "slideMaster1.xml");
  assert.ok(ing.masters.some((m: any) => m.theme === "GFTD Alt"));
  assert.equal(ing.layouts[0].id, "slideLayout1.xml");
  assert.equal(ing.guides[0].unit, "px");
});

test("preview SVG: derived from real deck data", async () => {
  const ir = extractStyle(new Uint8Array(await readFile(samplePath)), samplePath);
  const svg = renderPreviewSvg(ir);
  assert.match(svg, /<svg[^>]*viewBox="0 0 1280 720"/);
  assert.match(svg, /fill="#3B82F6"/); // accent1 swatch
  assert.match(svg, /data-role="title"/);
  assert.match(svg, /office-style-preview/); // svgraph 用 metadata 埋め込み
});
