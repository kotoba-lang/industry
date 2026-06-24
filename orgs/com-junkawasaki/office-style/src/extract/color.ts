/**
 * OOXML の色要素 (a:srgbClr / a:sysClr / a:schemeClr) → 正規化 Color。
 */
import type { XmlEl } from "../ooxml/parse.js";
import { childrenOf } from "../ooxml/parse.js";
import type { Color } from "../types.js";

const MOD_TAGS = new Set([
  "a:lumMod", "a:lumOff", "a:satMod", "a:shade", "a:tint", "a:alpha",
]);

/** 色修飾子 (lumMod 等) を 0..1 で集める。OOXML は 1/1000 % 単位。 */
function modsOf(clr: XmlEl): Record<string, number> | undefined {
  const mods: Record<string, number> = {};
  for (const c of clr.children) {
    if (MOD_TAGS.has(c.tag)) {
      const v = Number(c.attrs["val"]);
      if (Number.isFinite(v)) mods[c.tag.replace("a:", "")] = v / 100000;
    }
  }
  return Object.keys(mods).length ? mods : undefined;
}

/**
 * 単一の色子要素を解決。schemeClr は参照名のみ保持 (実 hex は呼び出し側で
 * colorScheme と解決可能。ここでは見つからなければ #000000 を仮に置く)。
 */
export function resolveColor(clr: XmlEl): Color | undefined {
  const mods = modsOf(clr);
  switch (clr.tag) {
    case "a:srgbClr": {
      const v = clr.attrs["val"];
      if (!v) return undefined;
      return { hex: `#${v.toUpperCase()}`, ...(mods ? { mods } : {}) };
    }
    case "a:sysClr": {
      const last = clr.attrs["lastClr"];
      return last
        ? { hex: `#${last.toUpperCase()}`, ...(mods ? { mods } : {}) }
        : undefined;
    }
    case "a:schemeClr": {
      const ref = clr.attrs["val"];
      if (!ref) return undefined;
      // hex は後段で解決。仮値を入れておく。
      return { hex: "#000000", schemeRef: ref, ...(mods ? { mods } : {}) };
    }
    default:
      return undefined;
  }
}

/** ある親要素 (a:solidFill 等) の中の最初の色を解決。 */
export function colorIn(parent: XmlEl): Color | undefined {
  for (const tag of ["a:srgbClr", "a:sysClr", "a:schemeClr"]) {
    const el = childrenOf(parent, tag)[0];
    if (el) return resolveColor(el);
  }
  return undefined;
}
