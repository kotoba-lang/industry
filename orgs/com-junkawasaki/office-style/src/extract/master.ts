/**
 * slideMaster*.xml / slideLayout*.xml → MasterRecord / LayoutRecord。
 * 背景色・プレースホルダ (type/idx/bbox)・既定テキストスタイル (p:txStyles) を抽出。
 */
import { parseXml, findDesc, childrenOf, findAll } from "../ooxml/parse.js";
import type { XmlEl } from "../ooxml/parse.js";
import { colorIn } from "./color.js";
import { EMU_PER_PT } from "../types.js";
import type {
  MasterRecord, LayoutRecord, Placeholder, TextStyle, TextLevel,
  Background, FontFace,
} from "../types.js";

function bboxOf(sp: XmlEl): Placeholder["bbox"] {
  const xfrm = findDesc(sp, "a:xfrm");
  if (!xfrm) return undefined;
  const off = childrenOf(xfrm, "a:off")[0];
  const ext = childrenOf(xfrm, "a:ext")[0];
  if (!off || !ext) return undefined;
  const x = Number(off.attrs["x"]), y = Number(off.attrs["y"]);
  const w = Number(ext.attrs["cx"]), h = Number(ext.attrs["cy"]);
  if ([x, y, w, h].some((v) => !Number.isFinite(v))) return undefined;
  return { x, y, w, h, unit: "emu" };
}

/** シェイプ群から placeholder (p:ph を持つ p:sp) を収集。 */
function placeholders(root: XmlEl): Placeholder[] {
  const out: Placeholder[] = [];
  for (const sp of findAll(root, "p:sp")) {
    const ph = findDesc(sp, "p:ph");
    if (!ph) continue;
    const idx = ph.attrs["idx"];
    const bbox = bboxOf(sp);
    out.push({
      ...(ph.attrs["type"] ? { type: ph.attrs["type"] } : {}),
      ...(idx !== undefined && Number.isFinite(Number(idx)) ? { idx: Number(idx) } : {}),
      ...(bbox ? { bbox } : {}),
    });
  }
  return out;
}

function background(root: XmlEl): Background | undefined {
  const bg = findDesc(root, "p:bg");
  if (!bg) return undefined;
  const solid = findDesc(bg, "a:solidFill");
  if (solid) {
    const color = colorIn(solid);
    return { fill: "solid", ...(color ? { color } : {}) };
  }
  if (findDesc(bg, "a:gradFill")) return { fill: "gradient" };
  if (findDesc(bg, "a:blipFill")) return { fill: "image" };
  return { fill: "none" };
}

function fontFaceOf(props: XmlEl): FontFace | undefined {
  const face = (tag: string) => childrenOf(props, tag)[0]?.attrs["typeface"] || undefined;
  const f: FontFace = {
    ...(face("a:latin") ? { latin: face("a:latin") } : {}),
    ...(face("a:ea") ? { ea: face("a:ea") } : {}),
    ...(face("a:cs") ? { cs: face("a:cs") } : {}),
  };
  return Object.keys(f).length ? f : undefined;
}

/** a:lvl1pPr..a:lvl9pPr の既定段落スタイルを TextLevel[] に。 */
function levelsOf(styleEl: XmlEl): TextLevel[] {
  const out: TextLevel[] = [];
  for (let i = 1; i <= 9; i++) {
    const lvlPr = childrenOf(styleEl, `a:lvl${i}pPr`)[0];
    if (!lvlPr) continue;
    const defRPr = childrenOf(lvlPr, "a:defRPr")[0];
    const lvl: TextLevel = { lvl: i };
    const align = lvlPr.attrs["algn"];
    if (align && ["l", "ctr", "r", "just", "dist"].includes(align)) {
      lvl.align = align as TextLevel["align"];
    }
    if (defRPr) {
      const sz = Number(defRPr.attrs["sz"]);
      if (Number.isFinite(sz)) lvl.sizePt = sz / 100; // sz は 1/100 pt
      if (defRPr.attrs["b"] === "1") lvl.bold = true;
      if (defRPr.attrs["i"] === "1") lvl.italic = true;
      const ff = fontFaceOf(defRPr);
      if (ff) lvl.font = ff;
      const solid = childrenOf(defRPr, "a:solidFill")[0];
      if (solid) {
        const c = colorIn(solid);
        if (c) lvl.color = c;
      }
    }
    out.push(lvl);
  }
  return out;
}

function textStyles(root: XmlEl): TextStyle[] {
  const txStyles = findDesc(root, "p:txStyles");
  if (!txStyles) return [];
  const map: Array<[TextStyle["role"], string]> = [
    ["title", "p:titleStyle"],
    ["body", "p:bodyStyle"],
    ["other", "p:otherStyle"],
  ];
  const out: TextStyle[] = [];
  for (const [role, tag] of map) {
    const el = childrenOf(txStyles, tag)[0];
    if (!el) continue;
    const levels = levelsOf(el);
    if (levels.length) out.push({ role, levels });
  }
  return out;
}

export function extractMaster(partName: string, xml: string): MasterRecord {
  const root = parseXml(xml);
  const bg = background(root);
  return {
    id: partName.split("/").pop() ?? partName,
    name: root.attrs["name"] ?? partName.split("/").pop(),
    part: partName,
    ...(bg ? { background: bg } : {}),
    placeholders: placeholders(root),
    textStyles: textStyles(root),
  };
}

export function extractLayout(
  partName: string,
  xml: string,
  masterPart?: string,
): LayoutRecord {
  const root = parseXml(xml);
  return {
    id: partName.split("/").pop() ?? partName,
    name: root.attrs["name"] ?? partName.split("/").pop(),
    part: partName,
    ...(root.attrs["type"] ? { type: root.attrs["type"] } : {}),
    ...(masterPart ? { masterPart } : {}),
    placeholders: placeholders(root),
  };
}

export { EMU_PER_PT };
