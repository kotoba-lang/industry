/**
 * ブラウザ向けエントリ。Node 専用 (cli, @resvg) を含まず、
 * **WebGPU + q4f16** の Gemma vision と canvas ラスタライザを既定にする。
 *
 *   import { analyzeDeck } from "@com-junkawasaki/office-style/browser";
 *   const { ir, perSlide } = await analyzeDeck(pptxBytes, { onProgress });
 *
 * 抽出/描画/svgraph 出力は isomorphic (fflate + fast-xml-parser)。
 */
export * from "./types.js";
export { extractStyle, extractStyleFromPackage } from "./extract/deterministic.js";
export { renderDeckSlides } from "./render/deck.js";
export { renderPreviewSvg } from "./render/preview-svg.js";
export { renderSlideSvg } from "./render/slide-svg.js";
export { toSvgraphPresentation } from "./svgraph/export.js";
export { GemmaVisionStylist } from "./vision/gemma-vision.js";
export { rasterizeSvgBrowser } from "./render/rasterize-browser.js";
export { aggregateTones, inferDeckTone, inferToneFromDeckBytes } from "./pipeline.js";
export type { ToneFromDeckOptions, Rasterizer } from "./pipeline.js";

import { extractStyle } from "./extract/deterministic.js";
import { inferDeckTone } from "./pipeline.js";
import { rasterizeSvgBrowser } from "./render/rasterize-browser.js";
import type { ToneFromDeckOptions } from "./pipeline.js";
import type { StyleIR, ToneProfile } from "./types.js";
import { withTone } from "./tone-merge.js";

/**
 * ブラウザでデッキを 1 発解析: 決定論 StyleIR を抽出し、実スライドを canvas で
 * raster 化して WebGPU 上の Gemma 4 vision でデッキトーンを推定・マージ。
 * device/dtype は webgpu/q4f16 が既定 (opts で上書き可)。
 */
export async function analyzeDeck(
  bytes: Uint8Array,
  opts: ToneFromDeckOptions = {},
): Promise<{ ir: StyleIR; perSlide: ToneProfile[] }> {
  const ir = extractStyle(bytes);
  const { deck, perSlide } = await inferDeckTone(bytes, ir, {
    device: "webgpu",
    dtype: "q4f16",
    rasterize: rasterizeSvgBrowser,
    ...opts,
  });
  return { ir: withTone(ir, deck), perSlide };
}
