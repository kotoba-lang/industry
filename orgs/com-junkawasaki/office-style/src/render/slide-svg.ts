/**
 * 実スライド (ppt/slides/slideN.xml) → SVG (PoC レンダラ)。
 * spTree を歩き、各シェイプの bbox・塗り・テキスト・回転を描く。色はテーマ + clrMap、
 * テキスト未指定プロパティは **master の txStyles を placeholder type で継承**する
 * (slide master 対応の核)。
 *
 * 対応 (PoC): prstGeom (rect/roundRect/ellipse) と custGeom (外接矩形近似)、
 *   solidFill / gradFill (linearGradient) / pattFill (fgClr 近似)、回転 (xfrm@rot)、
 *   表 (a:tbl)、画像 (p:pic → 枠)、placeholder のフォント/サイズ/色継承、phClr。
 * 非対応: 任意 custGeom の正確なパス、グラデの放射/パス種、SmartArt/グラフ実体。
 */
import { parseXml, findDesc, childrenOf, findAll } from "../ooxml/parse.js";
import type { XmlEl } from "../ooxml/parse.js";
import { colorIn } from "../extract/color.js";
import { resolveWithMap, DEFAULT_COLOR_MAP } from "../extract/colormap.js";
import type { ColorMap } from "../extract/colormap.js";
import { EMU_PER_PX } from "../types.js";
import type { ColorScheme, Color, SlideSize, TextStyle, TextLevel } from "../types.js";

const px = (emu: number): number => Math.round(emu / EMU_PER_PX);
const pt2px = (pt: number): number => Math.round(pt * (96 / 72));
const esc = (s: string): string =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

interface RenderCtx {
  scheme: ColorScheme | undefined;
  map: ColorMap;
  major: string;
  minor: string;
  textStyles: TextStyle[];
}

/** defs (グラデ等) と一意 id を貯める描画状態。 */
interface State {
  defs: string[];
  seq: number;
}

function resolve(c: Color | undefined, ctx: RenderCtx): Color | undefined {
  return c && ctx.scheme ? resolveWithMap(c, ctx.scheme, ctx.map) : c;
}

function resolveFont(latin: string | undefined, ctx: RenderCtx): string | undefined {
  if (!latin) return undefined;
  if (latin.startsWith("+mj")) return ctx.major;
  if (latin.startsWith("+mn")) return ctx.minor;
  return latin;
}

function boxFrom(xfrm: XmlEl | undefined) {
  if (!xfrm) return undefined;
  const off = childrenOf(xfrm, "a:off")[0];
  const ext = childrenOf(xfrm, "a:ext")[0];
  if (!off || !ext) return undefined;
  const x = Number(off.attrs["x"]), y = Number(off.attrs["y"]);
  const w = Number(ext.attrs["cx"]), h = Number(ext.attrs["cy"]);
  if ([x, y, w, h].some((v) => !Number.isFinite(v))) return undefined;
  return { x: px(x), y: px(y), w: px(w), h: px(h), rotDeg: Number(xfrm.attrs["rot"] || 0) / 60000 };
}

function bboxOf(sp: XmlEl) {
  return boxFrom(findDesc(sp, "a:xfrm"));
}

function roleOfPlaceholder(sp: XmlEl): TextStyle["role"] | undefined {
  const ph = findDesc(sp, "p:ph");
  if (!ph) return undefined;
  const t = ph.attrs["type"];
  if (t === "title" || t === "ctrTitle") return "title";
  if (t === "body" || t === "subTitle" || t === "obj" || t === undefined) return "body";
  return "other";
}

function inheritedLevel(ctx: RenderCtx, role: TextStyle["role"] | undefined): TextLevel | undefined {
  return role ? ctx.textStyles.find((s) => s.role === role)?.levels[0] : undefined;
}

/** a:gradFill → <linearGradient> def を作り url(#id) を返す。 */
function gradientPaint(grad: XmlEl, ctx: RenderCtx, st: State): string | undefined {
  const stops = findAll(grad, "a:gs")
    .map((gs) => {
      const off = Number(gs.attrs["pos"] || 0) / 1000; // 1000ths % → %
      const c = resolve(colorIn(gs), ctx);
      return c ? { off, hex: c.hex } : undefined;
    })
    .filter((s): s is { off: number; hex: string } => !!s);
  if (stops.length < 2) return undefined;
  const ang = Number(findDesc(grad, "a:lin")?.attrs["ang"] || 0) / 60000;
  const id = `g${st.seq++}`;
  st.defs.push(
    `<linearGradient id="${id}" gradientUnits="objectBoundingBox" gradientTransform="rotate(${ang} 0.5 0.5)">` +
      stops.map((s) => `<stop offset="${s.off}%" stop-color="${s.hex}"/>`).join("") +
      `</linearGradient>`,
  );
  return `url(#${id})`;
}

/** spPr の塗り → paint 文字列 (hex / url(#id))。solidFill/gradFill/pattFill。 */
function fillOf(spPr: XmlEl | undefined, ctx: RenderCtx, inheritColor: string, st: State): string | undefined {
  if (!spPr) return undefined;
  const solid = childrenOf(spPr, "a:solidFill")[0];
  if (solid) {
    const c = colorIn(solid);
    if (c?.schemeRef === "phClr") return inheritColor;
    return resolve(c, ctx)?.hex;
  }
  const grad = childrenOf(spPr, "a:gradFill")[0];
  if (grad) return gradientPaint(grad, ctx, st);
  const patt = childrenOf(spPr, "a:pattFill")[0];
  if (patt) {
    const fg = childrenOf(patt, "a:fgClr")[0];
    return fg ? resolve(colorIn(fg), ctx)?.hex : undefined;
  }
  return undefined;
}

function geomKind(sp: XmlEl): "rect" | "roundRect" | "ellipse" {
  const prst = findDesc(sp, "a:prstGeom")?.attrs["prst"];
  if (prst === "ellipse" || prst === "circle") return "ellipse";
  if (prst === "roundRect") return "roundRect";
  return "rect"; // custGeom 含む既定: 外接矩形近似
}

interface Line { text: string; font: string; sizePx: number; bold: boolean; color: string }

function lines(sp: XmlEl, ctx: RenderCtx, inh: TextLevel | undefined, ink: string): Line[] {
  const tx = findDesc(sp, "p:txBody") ?? findDesc(sp, "a:txBody");
  if (!tx) return [];
  const dFont = resolveFont(inh?.font?.latin, ctx) ?? (inh ? ctx.minor : undefined);
  const out: Line[] = [];
  for (const p of childrenOf(tx, "a:p")) {
    const runs = childrenOf(p, "a:r");
    const text = runs.map((r) => childrenOf(r, "a:t")[0]?.text ?? "").join("");
    if (!text) continue;
    const rPr = runs[0] ? childrenOf(runs[0], "a:rPr")[0] : undefined;
    const sz = rPr ? Number(rPr.attrs["sz"]) : NaN;
    const latin = rPr ? childrenOf(rPr, "a:latin")[0]?.attrs["typeface"] : undefined;
    const solid = rPr ? childrenOf(rPr, "a:solidFill")[0] : undefined;
    const runColor = solid ? resolve(colorIn(solid), ctx)?.hex : undefined;
    out.push({
      text,
      font: resolveFont(latin, ctx) ?? dFont ?? ctx.minor,
      sizePx: Number.isFinite(sz) ? pt2px(sz / 100) : pt2px(inh?.sizePt ?? 18),
      bold: rPr?.attrs["b"] === "1" || (rPr?.attrs["b"] === undefined && !!inh?.bold),
      color: runColor ?? inh?.color?.hex ?? ink,
    });
  }
  return out;
}

/** 回転があれば <g transform="rotate(...)"> でラップ。 */
function wrapRot(inner: string[], box: { x: number; y: number; w: number; h: number; rotDeg: number }): string[] {
  if (!box.rotDeg) return inner;
  const cx = box.x + box.w / 2, cy = box.y + box.h / 2;
  return [`<g transform="rotate(${box.rotDeg.toFixed(2)} ${cx} ${cy})">`, ...inner, `</g>`];
}

function renderShape(sp: XmlEl, ctx: RenderCtx, ink: string, st: State): string[] {
  const box = bboxOf(sp);
  const role = roleOfPlaceholder(sp);
  const inh = inheritedLevel(ctx, role);
  const inheritColor = inh?.color?.hex ?? ink;
  const inner: string[] = [];

  const fill = box ? fillOf(findDesc(sp, "p:spPr"), ctx, inheritColor, st) : undefined;
  if (box && fill) {
    const kind = geomKind(sp);
    if (kind === "ellipse") {
      inner.push(`<ellipse cx="${box.x + box.w / 2}" cy="${box.y + box.h / 2}" rx="${box.w / 2}" ry="${box.h / 2}" fill="${fill}"/>`);
    } else {
      const rx = kind === "roundRect" ? Math.round(Math.min(box.w, box.h) * 0.1) : 0;
      inner.push(`<rect x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}" rx="${rx}" fill="${fill}"/>`);
    }
  }
  if (box) {
    let cy = box.y;
    for (const ln of lines(sp, ctx, inh, ink)) {
      cy += Math.round(ln.sizePx * 1.2);
      inner.push(
        `<text x="${box.x + 8}" y="${cy}" font-family="${esc(ln.font)}" font-size="${ln.sizePx}" ` +
          `font-weight="${ln.bold ? 700 : 400}" fill="${ln.color}">${esc(ln.text)}</text>`,
      );
      cy += Math.round(ln.sizePx * 0.4);
    }
    return wrapRot(inner, box);
  }
  return inner;
}

/** a:tbl (graphicFrame 内) → セル矩形 + テキスト。 */
function renderTable(gf: XmlEl, ctx: RenderCtx, ink: string, st: State): string[] {
  const box = boxFrom(childrenOf(gf, "p:xfrm")[0] ?? findDesc(gf, "p:xfrm") ?? findDesc(gf, "a:xfrm"));
  const tbl = findDesc(gf, "a:tbl");
  if (!box || !tbl) return [];
  const cols = findAll(findDesc(tbl, "a:tblGrid") ?? tbl, "a:gridCol").map((c) => Number(c.attrs["w"] || 0));
  const rows = childrenOf(tbl, "a:tr");
  const totalW = cols.reduce((a, b) => a + b, 0) || box.w * EMU_PER_PX;
  const out: string[] = [];
  let y = box.y;
  for (const tr of rows) {
    const rh = px(Number(tr.attrs["h"] || 0)) || Math.round(box.h / Math.max(1, rows.length));
    let x = box.x;
    const cells = childrenOf(tr, "a:tc");
    cells.forEach((tc, ci) => {
      const cw = cols[ci] ? Math.round((cols[ci]! / totalW) * box.w) : Math.round(box.w / Math.max(1, cells.length));
      const fill = fillOf(findDesc(tc, "a:tcPr"), ctx, ink, st);
      out.push(`<rect x="${x}" y="${y}" width="${cw}" height="${rh}" fill="${fill ?? "none"}" stroke="#CBD5E1"/>`);
      const txt = (findDesc(tc, "a:txBody") ? lines(tc, ctx, undefined, ink) : [])[0];
      if (txt) {
        out.push(
          `<text x="${x + 6}" y="${y + Math.round(rh * 0.6)}" font-family="${esc(txt.font)}" ` +
            `font-size="${Math.min(txt.sizePx, Math.round(rh * 0.6))}" fill="${txt.color}">${esc(txt.text)}</text>`,
        );
      }
      x += cw;
    });
    y += rh;
  }
  return out;
}

function renderPic(pic: XmlEl): string[] {
  const box = bboxOf(pic);
  if (!box) return [];
  const inner = [
    `<rect x="${box.x}" y="${box.y}" width="${box.w}" height="${box.h}" fill="#E5E7EB" stroke="#9CA3AF"/>`,
    `<text x="${box.x + box.w / 2}" y="${box.y + box.h / 2}" text-anchor="middle" font-family="sans-serif" font-size="${Math.min(24, Math.round(box.h / 4))}" fill="#6B7280">image</text>`,
  ];
  return wrapRot(inner, box);
}

export function renderSlideSvg(
  slideXml: string,
  opts: {
    scheme?: ColorScheme;
    colorMap?: ColorMap;
    slideSize?: SlideSize;
    major?: string;
    minor?: string;
    textStyles?: TextStyle[];
  },
): string {
  const root = parseXml(slideXml);
  const W = opts.slideSize ? px(opts.slideSize.cx) : 1280;
  const H = opts.slideSize ? px(opts.slideSize.cy) : 720;
  const ctx: RenderCtx = {
    scheme: opts.scheme ?? undefined,
    map: opts.colorMap ?? DEFAULT_COLOR_MAP,
    major: opts.major ?? "sans-serif",
    minor: opts.minor ?? "sans-serif",
    textStyles: opts.textStyles ?? [],
  };
  const st: State = { defs: [], seq: 0 };
  const ink = ctx.scheme?.dk1.hex ?? "#111111";
  const bg = ctx.scheme?.lt1.hex ?? "#FFFFFF";

  let bgColor = bg;
  const slideBg = findDesc(root, "p:bg");
  if (slideBg) {
    const fill = findDesc(slideBg, "a:solidFill");
    const c = fill ? resolve(colorIn(fill), ctx) : undefined;
    if (c) bgColor = c.hex;
  }

  const spTree = findDesc(root, "p:spTree") ?? root;
  const body: string[] = [`<rect x="0" y="0" width="${W}" height="${H}" fill="${bgColor}"/>`];
  for (const sp of childrenOf(spTree, "p:sp")) body.push(...renderShape(sp, ctx, ink, st));
  for (const gf of childrenOf(spTree, "p:graphicFrame")) body.push(...renderTable(gf, ctx, ink, st));
  for (const pic of findAll(spTree, "p:pic")) body.push(...renderPic(pic));

  const defs = st.defs.length ? `<defs>${st.defs.join("")}</defs>` : "";
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">` +
    defs + body.join("") +
    `</svg>`
  );
}
