/**
 * 決定論レイヤのオーケストレーション: OPC package → StyleIR (vision なし)。
 * theme / master / layout / slideSize / guides を読み、schemeClr 参照を解決する。
 */
import type { OpcPackage } from "../ooxml/opc.js";
import { openPackage } from "../ooxml/opc.js";
import { parseXml } from "../ooxml/parse.js";
import { extractTheme } from "./theme.js";
import { extractMaster, extractLayout } from "./master.js";
import { extractSlideSize, extractGuides } from "./layout-meta.js";
import { colorMapOfMaster, resolveWithMap } from "./colormap.js";
import { themeOfMaster, masterOfLayout } from "./links.js";
import type { ColorMap } from "./colormap.js";
import { StyleIRSchema } from "../types.js";
import type {
  StyleIR, Theme, MasterRecord, LayoutRecord, TextStyle, ColorScheme,
} from "../types.js";

const MASTER_RE = /^ppt\/slideMasters\/slideMaster\d+\.xml$/;
const LAYOUT_RE = /^ppt\/slideLayouts\/slideLayout\d+\.xml$/;
const THEME_RE = /^ppt\/theme\/theme\d+\.xml$/;

/** TextStyle 内の schemeClr を clrMap + clrScheme で実 hex に解決。 */
function resolveStyleColors(styles: TextStyle[], scheme: ColorScheme, map: ColorMap): TextStyle[] {
  return styles.map((s) => ({
    ...s,
    levels: s.levels.map((l) =>
      l.color ? { ...l, color: resolveWithMap(l.color, scheme, map) } : l,
    ),
  }));
}

export function extractStyleFromPackage(
  pkg: OpcPackage,
  sourcePath: string,
): StyleIR {
  const themeCache = new Map<string, Theme>();
  const themeAt = (part: string | undefined): Theme | undefined => {
    if (!part) return undefined;
    if (themeCache.has(part)) return themeCache.get(part);
    const xml = pkg.parts.get(part);
    if (!xml) return undefined;
    try { const t = extractTheme(xml); themeCache.set(part, t); return t; } catch { return undefined; }
  };

  // masters: 各 master が **自分の** theme(rels) と clrMap で色解決する
  const masters: MasterRecord[] = [];
  for (const [name, xml] of pkg.parts) {
    if (!MASTER_RE.test(name)) continue;
    const m = extractMaster(name, xml);
    const themePart = themeOfMaster(pkg, name);
    const mTheme = themeAt(themePart);
    if (mTheme) {
      const map = colorMapOfMaster(parseXml(xml));
      m.textStyles = resolveStyleColors(m.textStyles, mTheme.colorScheme, map);
      if (m.background?.color) {
        m.background.color = resolveWithMap(m.background.color, mTheme.colorScheme, map);
      }
      m.theme = mTheme;
      if (themePart) m.themePart = themePart;
    }
    masters.push(m);
  }
  masters.sort((a, b) => a.part.localeCompare(b.part));

  // 代表 theme: 既定 master の theme、無ければ最初の theme part
  const defaultMasterTheme = masters[0]?.theme;
  const firstThemePart = [...pkg.parts.keys()].find((n) => THEME_RE.test(n));
  const theme = defaultMasterTheme ?? themeAt(firstThemePart);

  // 全 theme (part 単位で重複排除、part 名順)
  const themeParts = [...pkg.parts.keys()].filter((n) => THEME_RE.test(n)).sort();
  const themes = themeParts.map((p) => themeAt(p)).filter((t): t is Theme => !!t);

  // layouts
  const layouts: LayoutRecord[] = [];
  for (const [name, xml] of pkg.parts) {
    if (!LAYOUT_RE.test(name)) continue;
    layouts.push(extractLayout(name, xml, masterOfLayout(pkg, name)));
  }
  layouts.sort((a, b) => a.part.localeCompare(b.part));

  // slide size
  const presentationXml = pkg.parts.get("ppt/presentation.xml");
  const slideSize = presentationXml ? extractSlideSize(presentationXml) : undefined;

  // guides (viewProps)
  const viewPropsXml = pkg.parts.get("ppt/viewProps.xml");
  const guides = viewPropsXml ? extractGuides(viewPropsXml) : [];

  // 代表テキストスタイル = 既定 master
  const textStyles = masters[0]?.textStyles ?? [];

  const ir: StyleIR = {
    version: "style-ir/1",
    source: { path: sourcePath, app: "ppt" },
    ...(slideSize ? { slideSize } : {}),
    ...(theme ? { theme } : {}),
    themes,
    masters,
    layouts,
    guides,
    textStyles,
  };
  return StyleIRSchema.parse(ir);
}

/** バイト列から直接抽出 (CLI / ブラウザ共通入口)。 */
export function extractStyle(bytes: Uint8Array, sourcePath = "<bytes>"): StyleIR {
  return extractStyleFromPackage(openPackage(bytes), sourcePath);
}
