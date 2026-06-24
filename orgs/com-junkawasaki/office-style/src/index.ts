/**
 * @com-junkawasaki/office-style — pptx の「トーン・マナー・スタイル」を
 * 単一の StyleIR に抽出する。決定論レイヤ(OOXML) + 解釈レイヤ(Gemma 4 vision)。
 */
export * from "./types.js";
export { openPackage, normalizeTarget } from "./ooxml/opc.js";
export { extractStyle, extractStyleFromPackage } from "./extract/deterministic.js";
export { extractTheme, resolveSchemeRefs } from "./extract/theme.js";
export { extractMaster, extractLayout } from "./extract/master.js";
export { extractSlideSize, extractGuides } from "./extract/layout-meta.js";
export { GemmaVisionStylist } from "./vision/gemma-vision.js";
export type { GemmaVisionOptions, ImageInput } from "./vision/gemma-vision.js";
export { toSvgraphPresentation, toSvgraphIngest } from "./svgraph/export.js";
export type {
  SVGraphPresentationProjection, TemplateRecord, GuideRecord, TextStyleRecord,
} from "./svgraph/projection.js";
export { withTone } from "./tone-merge.js";
export { renderPreviewSvg } from "./render/preview-svg.js";
export { renderSlideSvg } from "./render/slide-svg.js";
export { renderDeckSlides } from "./render/deck.js";
export type { RenderedSlide } from "./render/deck.js";
export { rasterizeSvg } from "./render/rasterize.js";
export {
  inferToneFromDeck, inferToneFromDeckBytes, inferDeckTone, aggregateTones,
} from "./pipeline.js";
export type { ToneFromDeckOptions } from "./pipeline.js";
export {
  colorMapOfMaster, applyLayoutOverride, resolveWithMap, DEFAULT_COLOR_MAP,
} from "./extract/colormap.js";
export type { ColorMap } from "./extract/colormap.js";
