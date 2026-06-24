/**
 * theme1.xml → Theme (a:clrScheme 12 色 + a:fontScheme major/minor)。
 */
import { parseXml, findDesc, childrenOf } from "../ooxml/parse.js";
import type { XmlEl } from "../ooxml/parse.js";
import { resolveColor } from "./color.js";
import type {
  Theme, ColorScheme, FontScheme, FontFace, Color,
} from "../types.js";

const SCHEME_ROLES = [
  "dk1", "lt1", "dk2", "lt2",
  "accent1", "accent2", "accent3", "accent4", "accent5", "accent6",
  "hlink", "folHlink",
] as const;

function firstColorChild(el: XmlEl): Color | undefined {
  for (const c of el.children) {
    const r = resolveColor(c);
    if (r) return r;
  }
  return undefined;
}

function parseColorScheme(clrScheme: XmlEl): ColorScheme {
  const black: Color = { hex: "#000000" };
  const get = (role: string): Color => {
    const el = childrenOf(clrScheme, `a:${role}`)[0];
    return (el && firstColorChild(el)) || black;
  };
  const out = { name: clrScheme.attrs["name"] } as ColorScheme;
  for (const role of SCHEME_ROLES) {
    (out as Record<string, Color | string | undefined>)[role] = get(role);
  }
  return out;
}

function parseFontFace(el: XmlEl | undefined): FontFace {
  if (!el) return {};
  const face = (tag: string) => childrenOf(el, tag)[0]?.attrs["typeface"] || undefined;
  return {
    ...(face("a:latin") ? { latin: face("a:latin") } : {}),
    ...(face("a:ea") ? { ea: face("a:ea") } : {}),
    ...(face("a:cs") ? { cs: face("a:cs") } : {}),
  };
}

function parseFontScheme(fontScheme: XmlEl): FontScheme {
  return {
    ...(fontScheme.attrs["name"] ? { name: fontScheme.attrs["name"] } : {}),
    majorFont: parseFontFace(childrenOf(fontScheme, "a:majorFont")[0]),
    minorFont: parseFontFace(childrenOf(fontScheme, "a:minorFont")[0]),
  };
}

export function extractTheme(themeXml: string): Theme {
  const root = parseXml(themeXml);
  const clrScheme = findDesc(root, "a:clrScheme");
  const fontScheme = findDesc(root, "a:fontScheme");
  if (!clrScheme || !fontScheme) {
    throw new Error("theme: a:clrScheme / a:fontScheme not found");
  }
  return {
    ...(root.attrs["name"] ? { name: root.attrs["name"] } : {}),
    colorScheme: parseColorScheme(clrScheme),
    fontScheme: parseFontScheme(fontScheme),
  };
}

/** schemeClr 参照を colorScheme の実 hex に解決し直す (IR 仕上げ用)。 */
export function resolveSchemeRefs(color: Color, scheme: ColorScheme): Color {
  if (!color.schemeRef) return color;
  // theme の clrMap で "tx1"→dk1 等の別名があるが、PoC では主要別名のみ対応。
  const alias: Record<string, keyof ColorScheme> = {
    tx1: "dk1", bg1: "lt1", tx2: "dk2", bg2: "lt2",
    dk1: "dk1", lt1: "lt1", dk2: "dk2", lt2: "lt2",
    accent1: "accent1", accent2: "accent2", accent3: "accent3",
    accent4: "accent4", accent5: "accent5", accent6: "accent6",
    hlink: "hlink", folHlink: "folHlink",
  };
  const key = alias[color.schemeRef];
  const resolved = key ? (scheme[key] as Color | undefined) : undefined;
  if (!resolved) return color;
  return { ...color, hex: resolved.hex };
}
