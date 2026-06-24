/**
 * 色マップ解決。slideMaster の <p:clrMap> は「プレースホルダ色名」
 * (bg1/tx1/bg2/tx2/accent1.. ) を theme の clrScheme スロット (lt1/dk1/lt2/dk2..)
 * へ写像する。slideLayout の <p:clrMapOvr><a:overrideClrMapping> はそれを上書きする。
 *
 * schemeClr val はこの「プレースホルダ色名」を取るため、実 hex を出すには
 *   schemeClr.val → (clrMap で) clrScheme スロット → ColorScheme[slot].hex
 * の二段解決が要る。
 */
import { parseXml, findDesc } from "../ooxml/parse.js";
import type { ColorScheme, Color } from "../types.js";

/** プレースホルダ色名 → clrScheme スロット名。 */
export type ColorMap = Record<string, string>;

/** OOXML 既定 (clrMap 省略時)。 */
export const DEFAULT_COLOR_MAP: ColorMap = {
  bg1: "lt1", tx1: "dk1", bg2: "lt2", tx2: "dk2",
  accent1: "accent1", accent2: "accent2", accent3: "accent3",
  accent4: "accent4", accent5: "accent5", accent6: "accent6",
  hlink: "hlink", folHlink: "folHlink",
};

const MAP_KEYS = Object.keys(DEFAULT_COLOR_MAP);

/** <p:clrMap .../> または <a:overrideClrMapping .../> の属性を ColorMap に。 */
function attrsToMap(attrs: Record<string, string>): ColorMap {
  const m: ColorMap = {};
  for (const k of MAP_KEYS) {
    const v = attrs[k];
    if (v) m[k] = v;
  }
  return m;
}

/** slideMaster XML から clrMap を取得 (無ければ既定)。 */
export function colorMapOfMaster(masterRoot: ReturnType<typeof parseXml>): ColorMap {
  const clrMap = findDesc(masterRoot, "p:clrMap");
  return clrMap ? { ...DEFAULT_COLOR_MAP, ...attrsToMap(clrMap.attrs) } : { ...DEFAULT_COLOR_MAP };
}

/** slideLayout XML の clrMapOvr を base に重ねる。override 無しなら base のまま。 */
export function applyLayoutOverride(layoutXml: string, base: ColorMap): ColorMap {
  const root = parseXml(layoutXml);
  const ovr = findDesc(root, "p:clrMapOvr");
  if (!ovr) return base;
  // <p:masterClrMapping/> なら master 準拠 (上書きなし)
  if (findDesc(ovr, "p:masterClrMapping")) return base;
  const override = findDesc(ovr, "a:overrideClrMapping");
  return override ? { ...base, ...attrsToMap(override.attrs) } : base;
}

/**
 * schemeClr 参照を持つ Color を、clrMap + clrScheme で実 hex に解決。
 * scheme スロット名 (dk1 等) が直接来た場合も解決する。
 */
export function resolveWithMap(
  color: Color,
  scheme: ColorScheme,
  map: ColorMap = DEFAULT_COLOR_MAP,
): Color {
  if (!color.schemeRef) return color;
  // phClr (placeholder color) は文脈依存。PoC では未解決のまま残す。
  if (color.schemeRef === "phClr") return color;
  const slot = map[color.schemeRef] ?? color.schemeRef; // 写像 or そのまま slot 名
  const resolved = (scheme as unknown as Record<string, Color | string | undefined>)[slot];
  if (resolved && typeof resolved === "object" && "hex" in resolved) {
    return { ...color, hex: (resolved as Color).hex };
  }
  return color;
}
