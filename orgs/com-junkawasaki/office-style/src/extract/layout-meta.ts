/**
 * presentation.xml の p:sldSz → SlideSize、viewProps.xml の p:guide → GuideRecord。
 */
import { parseXml, findDesc, findAll } from "../ooxml/parse.js";
import { EMU_PER_PT } from "../types.js";
import type { SlideSize, GuideRecord } from "../types.js";

function approxAspect(cx: number, cy: number): string | undefined {
  if (!cx || !cy) return undefined;
  const r = cx / cy;
  const table: Array<[string, number]> = [
    ["16:9", 16 / 9], ["4:3", 4 / 3], ["16:10", 16 / 10], ["3:2", 3 / 2],
  ];
  let best: [string, number] | undefined;
  for (const t of table) {
    if (!best || Math.abs(t[1] - r) < Math.abs(best[1] - r)) best = t;
  }
  return best && Math.abs(best[1] - r) < 0.05 ? best[0] : undefined;
}

export function extractSlideSize(presentationXml: string): SlideSize | undefined {
  const root = parseXml(presentationXml);
  const sz = findDesc(root, "p:sldSz");
  if (!sz) return undefined;
  const cx = Number(sz.attrs["cx"]), cy = Number(sz.attrs["cy"]);
  if (!Number.isFinite(cx) || !Number.isFinite(cy)) return undefined;
  const aspect = approxAspect(cx, cy);
  return { cx, cy, unit: "emu", ...(aspect ? { aspect } : {}) };
}

export function extractGuides(viewPropsXml: string): GuideRecord[] {
  const root = parseXml(viewPropsXml);
  const out: GuideRecord[] = [];
  for (const g of findAll(root, "p:guide")) {
    const raw = Number(g.attrs["pos"]);
    if (!Number.isFinite(raw)) continue;
    // pos は 1/8 pt 単位 → EMU。orient 省略時は horz。
    const pos = Math.round((raw * EMU_PER_PT) / 8);
    const orient = g.attrs["orient"] === "vert" ? "vert" : "horz";
    out.push({ orient, pos, unit: "emu" });
  }
  return out;
}
