/**
 * StyleIR → svgraph SVGraphPresentationProjection。
 * svgraph の presentation IR の受け皿 (masters/layouts/guides/text_styles) に
 * 直接流し込める形へ変換する。長さは EMU → px (9525 EMU/px) に正規化。
 */
import { EMU_PER_PX } from "../types.js";
import type {
  StyleIR, MasterRecord, LayoutRecord, TextStyle, GuideRecord as IrGuide,
} from "../types.js";
import type {
  SVGraphPresentationProjection, TemplateRecord, GuideRecord, TextStyleRecord,
  JsonValue,
} from "./projection.js";

const px = (emu: number): number => Math.round((emu / EMU_PER_PX) * 100) / 100;

function masterToTemplate(m: MasterRecord): TemplateRecord {
  const data: Record<string, string> = { part: m.part };
  if (m.name) data["name"] = m.name;
  if (m.background?.fill) data["background"] = m.background.fill;
  if (m.background?.color) data["background_color"] = m.background.color.hex;
  if (m.themePart) data["theme_part"] = m.themePart;
  if (m.theme) {
    if (m.theme.name) data["theme"] = m.theme.name;
    if (m.theme.fontScheme.majorFont.latin) data["font_major"] = m.theme.fontScheme.majorFont.latin;
    if (m.theme.fontScheme.minorFont.latin) data["font_minor"] = m.theme.fontScheme.minorFont.latin;
    data["accent1"] = m.theme.colorScheme.accent1.hex;
  }
  return {
    template_id: m.id,
    kind: "master",
    node_id: null,
    data,
    metadata: {
      placeholders: m.placeholders.map((p) => ({
        type: p.type ?? null,
        idx: p.idx ?? null,
        bbox: p.bbox
          ? { x: px(p.bbox.x), y: px(p.bbox.y), w: px(p.bbox.w), h: px(p.bbox.h), unit: "px" }
          : null,
      })),
      text_styles: m.textStyles.map(textStyleJson),
    } as JsonValue,
  };
}

function layoutToTemplate(l: LayoutRecord): TemplateRecord {
  const data: Record<string, string> = { part: l.part };
  if (l.name) data["name"] = l.name;
  if (l.type) data["type"] = l.type;
  if (l.masterPart) data["master"] = l.masterPart;
  return {
    template_id: l.id,
    kind: "layout",
    node_id: null,
    data,
    metadata: {
      placeholders: l.placeholders.map((p) => ({
        type: p.type ?? null,
        idx: p.idx ?? null,
        bbox: p.bbox
          ? { x: px(p.bbox.x), y: px(p.bbox.y), w: px(p.bbox.w), h: px(p.bbox.h), unit: "px" }
          : null,
      })),
    } as JsonValue,
  };
}

function textStyleJson(s: TextStyle): JsonValue {
  return {
    role: s.role,
    levels: s.levels.map((l) => ({
      lvl: l.lvl,
      ...(l.font?.latin ? { fontFamily: l.font.latin } : {}),
      ...(l.sizePt !== undefined ? { fontSize: l.sizePt } : {}),
      ...(l.bold !== undefined ? { bold: l.bold } : {}),
      ...(l.italic !== undefined ? { italic: l.italic } : {}),
      ...(l.color ? { color: l.color.hex } : {}),
      ...(l.align ? { align: l.align } : {}),
    })),
  } as JsonValue;
}

/** lvl1 を代表に role 単位の TextStyleRecord を作る (svgraph の properties 形)。 */
function toTextStyleRecord(s: TextStyle, ir: StyleIR): TextStyleRecord {
  const lvl1 = s.levels[0];
  const major = ir.theme?.fontScheme.majorFont.latin;
  const minor = ir.theme?.fontScheme.minorFont.latin;
  // +mj-lt/+mn-lt のテーマトークンは実フォント名へ解決する。
  const latin = lvl1?.font?.latin;
  const resolvedLatin = latin?.startsWith("+mj")
    ? major
    : latin?.startsWith("+mn")
      ? minor
      : latin;
  const fontFamily = resolvedLatin ?? (s.role === "title" ? major : minor) ?? undefined;
  const properties: Record<string, JsonValue> = {};
  if (fontFamily) properties["fontFamily"] = fontFamily;
  if (lvl1?.sizePt !== undefined) properties["fontSize"] = lvl1.sizePt;
  if (lvl1?.bold !== undefined) properties["bold"] = lvl1.bold;
  if (lvl1?.italic !== undefined) properties["italic"] = lvl1.italic;
  if (lvl1?.color) properties["color"] = lvl1.color.hex;
  if (lvl1?.align) properties["align"] = lvl1.align;
  return { style_id: `${s.role}-lvl1`, role: s.role, properties, node_id: null };
}

function toGuideRecord(g: IrGuide, i: number): GuideRecord {
  return {
    guide_id: `guide-${i + 1}`,
    orientation: g.orient === "vert" ? "vertical" : "horizontal",
    position: px(g.pos), // EMU → px
    unit: "px",
    node_id: null,
  };
}

/**
 * svgraph **取り込み最適化** シェイプ (preview SVG の <metadata> に埋める用)。
 * svgraph の buildSVGraphPresentation は `presentation.text_styles` を
 *   - 配列 → 各要素を丸ごと properties に包む (二重ネスト)
 *   - オブジェクト {role: properties} → フラットな TextStyleRecord
 * として扱う。masters/layouts/guides は `obj.id`/`obj.name` を id に使い要素全体を
 * metadata に保存する。よって text_styles は role キーのオブジェクト、各 item には
 * `id` を付けて渡す (= svgraph 側でフラット・ID 保持に取り込まれる)。
 */
export function toSvgraphIngest(ir: StyleIR): Record<string, JsonValue> {
  const text_styles: Record<string, JsonValue> = {};
  for (const s of ir.textStyles) {
    text_styles[s.role] = toTextStyleRecord(s, ir).properties as JsonValue;
  }
  return {
    kind: "svgraph-presentation",
    slide_size: ir.slideSize ? [px(ir.slideSize.cx), px(ir.slideSize.cy)] : [960, 540],
    text_styles,
    guides: ir.guides.map((g, i) => ({
      id: `guide-${i + 1}`,
      orientation: g.orient === "vert" ? "vertical" : "horizontal",
      position: px(g.pos),
      unit: "px",
    })) as JsonValue,
    masters: ir.masters.map((m) => ({
      id: m.id,
      name: m.name ?? null,
      theme: m.theme?.name ?? null,
      theme_part: m.themePart ?? null,
      font_major: m.theme?.fontScheme.majorFont.latin ?? null,
      font_minor: m.theme?.fontScheme.minorFont.latin ?? null,
      accent1: m.theme?.colorScheme.accent1.hex ?? null,
      background: m.background?.fill ?? null,
      background_color: m.background?.color?.hex ?? null,
    })) as JsonValue,
    layouts: ir.layouts.map((l) => ({
      id: l.id,
      name: l.name ?? null,
      type: l.type ?? null,
      master: l.masterPart ?? null,
    })) as JsonValue,
    metadata: { source: ir.source.path, style_ir_version: ir.version } as JsonValue,
  };
}

export function toSvgraphPresentation(ir: StyleIR): SVGraphPresentationProjection {
  const slide_size: [number, number] = ir.slideSize
    ? [px(ir.slideSize.cx), px(ir.slideSize.cy)]
    : [960, 540];

  const metadata: Record<string, JsonValue> = {
    source: ir.source.path,
    style_ir_version: ir.version,
  };
  if (ir.theme) {
    const cs = ir.theme.colorScheme;
    metadata["theme"] = {
      name: ir.theme.name ?? null,
      colors: Object.fromEntries(
        (Object.keys(cs) as Array<keyof typeof cs>)
          .filter((k) => k !== "name")
          .map((k) => [k, (cs[k] as { hex: string }).hex]),
      ),
      fonts: {
        major: ir.theme.fontScheme.majorFont.latin ?? null,
        minor: ir.theme.fontScheme.minorFont.latin ?? null,
      },
    } as JsonValue;
  }
  if (ir.themes.length > 1) {
    metadata["themes"] = ir.themes.map((t) => ({
      name: t.name ?? null,
      accent1: t.colorScheme.accent1.hex,
      font_major: t.fontScheme.majorFont.latin ?? null,
    })) as JsonValue;
  }
  if (ir.tone) metadata["tone"] = ir.tone as unknown as JsonValue;

  return {
    kind: "svgraph-presentation",
    slide_size,
    slides: [],
    parts: [],
    masters: ir.masters.map(masterToTemplate),
    layouts: ir.layouts.map(layoutToTemplate),
    guides: ir.guides.map(toGuideRecord),
    rulers: [],
    text_styles: ir.textStyles.map((s) => toTextStyleRecord(s, ir)),
    metadata,
  };
}
