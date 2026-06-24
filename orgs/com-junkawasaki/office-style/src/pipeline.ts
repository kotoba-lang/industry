/**
 * 高水準パイプライン: deck → (実スライド | プレビュー) SVG → PNG → Gemma vision → ToneProfile。
 * 決定論抽出した「実データ」を vision 入力に通す ((1) の連携)。
 */
import { renderPreviewSvg } from "./render/preview-svg.js";
import { renderDeckSlides } from "./render/deck.js";
import { rasterizeSvg } from "./render/rasterize.js";
import { GemmaVisionStylist } from "./vision/gemma-vision.js";
import type { GemmaVisionOptions } from "./vision/gemma-vision.js";
import { ToneProfileSchema } from "./types.js";
import type { StyleIR, ToneProfile } from "./types.js";

/** 最頻値 (undefined を除外、同数なら先勝ち)。 */
function mode<T>(xs: Array<T | undefined>): T | undefined {
  const count = new Map<T, number>();
  for (const x of xs) if (x !== undefined) count.set(x, (count.get(x) ?? 0) + 1);
  let best: T | undefined, n = 0;
  for (const [k, v] of count) if (v > n) { best = k; n = v; }
  return best;
}

/** 頻度順に上位 max 個 (出現順で安定)。 */
function topByFreq(lists: string[][], max: number): string[] {
  const count = new Map<string, number>();
  for (const list of lists) for (const s of list) count.set(s, (count.get(s) ?? 0) + 1);
  return [...count.entries()].sort((a, b) => b[1] - a[1]).slice(0, max).map(([s]) => s);
}

/**
 * 複数スライドの ToneProfile をデッキ全体に集約。
 * enum は最頻値、mood/visualStyle は頻度上位、confidence は平均、
 * summary は最高 confidence のもの、evidence は連結。
 */
export function aggregateTones(tones: ToneProfile[]): ToneProfile {
  const valid = tones.filter(Boolean);
  if (valid.length === 0) return ToneProfileSchema.parse({});
  const confs = valid.map((t) => t.confidence ?? 0);
  const avg = confs.reduce((a, b) => a + b, 0) / valid.length;
  const top = [...valid].sort((a, b) => (b.confidence ?? 0) - (a.confidence ?? 0))[0]!;
  return ToneProfileSchema.parse({
    formality: mode(valid.map((t) => t.formality)),
    mood: topByFreq(valid.map((t) => t.mood), 4),
    colorMood: mode(valid.map((t) => t.colorMood)),
    density: mode(valid.map((t) => t.density)),
    visualStyle: topByFreq(valid.map((t) => t.visualStyle), 4),
    summary: top.summary,
    confidence: Math.round(avg * 100) / 100,
    evidence: valid.flatMap((t) => t.evidence),
  });
}

/** SVG → PNG ラスタライザ。Node 既定は @resvg、ブラウザは rasterizeSvgBrowser を注入。 */
export type Rasterizer = (svg: string, opts: { width?: number }) => Promise<Uint8Array>;

export interface ToneFromDeckOptions extends GemmaVisionOptions {
  /** raster 幅(px)。既定 1280。 */
  rasterWidth?: number;
  /** 実スライドの最大サンプル枚数 (先頭から)。既定 4。 */
  maxSlides?: number;
  /** 既存の Stylist を使い回す (モデル再ロード回避)。 */
  stylist?: GemmaVisionStylist;
  /** ラスタライザ差し替え (ブラウザでは rasterizeSvgBrowser を渡す)。 */
  rasterize?: Rasterizer;
}

async function rasterAll(svgs: string[], width: number, raster: Rasterizer): Promise<Uint8Array[]> {
  return Promise.all(svgs.map((s) => raster(s, { width })));
}

/**
 * IR からプレビューを描画・raster 化し Gemma vision でトーン推定。
 * (パッケージ bytes が無い時のフォールバック経路。)
 */
export async function inferToneFromDeck(
  ir: StyleIR,
  opts: ToneFromDeckOptions = {},
): Promise<ToneProfile> {
  const raster = opts.rasterize ?? rasterizeSvg;
  const png = await raster(renderPreviewSvg(ir), { width: opts.rasterWidth ?? 1280 });
  const stylist = opts.stylist ?? new GemmaVisionStylist(opts);
  return stylist.inferTone([png], ir.theme, ["<preview>"]);
}

/**
 * パッケージ bytes から実スライドを描画して vision に通す。スライドが無ければ
 * IR プレビューにフォールバック。
 */
export async function inferToneFromDeckBytes(
  bytes: Uint8Array,
  ir: StyleIR,
  opts: ToneFromDeckOptions = {},
): Promise<ToneProfile> {
  const width = opts.rasterWidth ?? 1280;
  const max = opts.maxSlides ?? 4;
  const raster = opts.rasterize ?? rasterizeSvg;
  const slides = renderDeckSlides(bytes).slice(0, max);
  const stylist = opts.stylist ?? new GemmaVisionStylist(opts);

  if (slides.length === 0) {
    const png = await raster(renderPreviewSvg(ir), { width });
    return stylist.inferTone([png], ir.theme, ["<preview>"]);
  }
  const pngs = await rasterAll(slides.map((s) => s.svg), width, raster);
  return stylist.inferTone(pngs, ir.theme, slides.map((s) => s.part));
}

/** デッキから最大 n 枚を均等サンプリング (先頭・末尾を含む)。 */
function sampleEvenly<T>(items: T[], n: number): T[] {
  if (items.length <= n) return items;
  const out: T[] = [];
  for (let i = 0; i < n; i++) out.push(items[Math.round((i * (items.length - 1)) / (n - 1))]!);
  return [...new Set(out)];
}

/**
 * デッキ全体のトーン: 複数スライドを均等サンプリングし **1 枚ずつ** vision に通して
 * 個別 ToneProfile を得てから `aggregateTones` で集約する。
 * (inferToneFromDeckBytes は複数画像を 1 回の判定に渡す版。こちらは枚数分推論し
 *  per-slide の evidence と頑健な集約を得る。)
 */
export async function inferDeckTone(
  bytes: Uint8Array,
  ir: StyleIR,
  opts: ToneFromDeckOptions = {},
): Promise<{ deck: ToneProfile; perSlide: ToneProfile[] }> {
  const width = opts.rasterWidth ?? 1280;
  const max = opts.maxSlides ?? 4;
  const raster = opts.rasterize ?? rasterizeSvg;
  const stylist = opts.stylist ?? new GemmaVisionStylist(opts);
  const all = renderDeckSlides(bytes);
  const sampled = all.length ? sampleEvenly(all, max) : [];

  const svgs = sampled.length ? sampled : [{ part: "<preview>", svg: renderPreviewSvg(ir) }];
  const perSlide: ToneProfile[] = [];
  for (const s of svgs) {
    const png = await raster(s.svg, { width });
    perSlide.push(await stylist.inferTone([png], ir.theme, [s.part]));
  }
  return { deck: aggregateTones(perSlide), perSlide };
}

export { renderPreviewSvg, renderDeckSlides };
