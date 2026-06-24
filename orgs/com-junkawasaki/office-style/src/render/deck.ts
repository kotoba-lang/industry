/**
 * pptx パッケージ → スライドごとの SVG。実スライドを描いて vision 入力にする。
 * 各スライドは slide → layout → master の継承チェーンで **自分の master/theme** を
 * 解決する (複数 master のデッキに対応)。placeholder のフォント/サイズ/色は
 * その master の txStyles から継承される。
 */
import { openPackage } from "../ooxml/opc.js";
import { parseXml } from "../ooxml/parse.js";
import { extractStyleFromPackage } from "../extract/deterministic.js";
import { colorMapOfMaster, DEFAULT_COLOR_MAP } from "../extract/colormap.js";
import type { ColorMap } from "../extract/colormap.js";
import { masterOfSlide } from "../extract/links.js";
import { renderSlideSvg } from "./slide-svg.js";
import type { MasterRecord, Theme, SlideSize } from "../types.js";

const SLIDE_RE = /^ppt\/slides\/slide(\d+)\.xml$/;

export interface RenderedSlide { part: string; svg: string }

export function renderDeckSlides(bytes: Uint8Array): RenderedSlide[] {
  const pkg = openPackage(bytes);
  const ir = extractStyleFromPackage(pkg, "<deck>");

  const masterByPart = new Map<string, MasterRecord>(ir.masters.map((m) => [m.part, m]));
  const clrMapCache = new Map<string, ColorMap>();
  const clrMapOf = (masterPart: string | undefined): ColorMap => {
    if (!masterPart) return DEFAULT_COLOR_MAP;
    if (clrMapCache.has(masterPart)) return clrMapCache.get(masterPart)!;
    const xml = pkg.parts.get(masterPart);
    const map = xml ? colorMapOfMaster(parseXml(xml)) : DEFAULT_COLOR_MAP;
    clrMapCache.set(masterPart, map);
    return map;
  };

  const slideSize: SlideSize | undefined = ir.slideSize;
  const slides: Array<{ no: number; part: string; svg: string }> = [];
  for (const [name] of pkg.parts) {
    const m = name.match(SLIDE_RE);
    if (!m) continue;
    const masterPart = masterOfSlide(pkg, name);
    const master = masterPart ? masterByPart.get(masterPart) : ir.masters[0];
    const theme: Theme | undefined = master?.theme ?? ir.theme;
    const map = clrMapOf(masterPart ?? ir.masters[0]?.part);

    slides.push({
      no: Number(m[1]),
      part: name,
      svg: renderSlideSvg(pkg.parts.get(name)!, {
        ...(theme ? { scheme: theme.colorScheme } : {}),
        colorMap: map,
        ...(slideSize ? { slideSize } : {}),
        ...(theme?.fontScheme.majorFont.latin ? { major: theme.fontScheme.majorFont.latin } : {}),
        ...(theme?.fontScheme.minorFont.latin ? { minor: theme.fontScheme.minorFont.latin } : {}),
        textStyles: master?.textStyles ?? ir.textStyles,
      }),
    });
  }
  slides.sort((a, b) => a.no - b.no);
  return slides.map(({ part, svg }) => ({ part, svg }));
}
