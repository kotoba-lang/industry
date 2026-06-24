/**
 * OPC (Open Packaging Conventions) 展開。pptx は zip。fflate で展開し、
 * part 名 → 生 XML のマップを返す。スタイル抽出に必要な part だけを読む。
 */
import { unzipSync, strFromU8 } from "fflate";

export interface Relationship {
  id: string;
  type: string;
  target: string;
}

export interface OpcPackage {
  parts: Map<string, string>; // part 名 → 生 XML
  rels: Map<string, Relationship[]>; // part 名 → そのリレーション
}

function relsPathFor(partName: string): string {
  const slash = partName.lastIndexOf("/");
  const dir = slash >= 0 ? partName.slice(0, slash) : "";
  const base = slash >= 0 ? partName.slice(slash + 1) : partName;
  return dir ? `${dir}/_rels/${base}.rels` : `_rels/${base}.rels`;
}

function parseRels(xml: string | undefined): Relationship[] {
  if (!xml) return [];
  const out: Relationship[] = [];
  for (const m of xml.matchAll(/<Relationship\b[^>]*\/?>/g)) {
    const tag = m[0];
    const get = (a: string) => tag.match(new RegExp(`${a}="([^"]*)"`))?.[1];
    const id = get("Id"), type = get("Type"), target = get("Target");
    if (id && type && target) out.push({ id, type, target });
  }
  return out;
}

export function openPackage(bytes: Uint8Array): OpcPackage {
  const entries = unzipSync(bytes);
  const names = Object.keys(entries);
  if (!names.some((n) => n.startsWith("ppt/"))) {
    throw new Error("Not a PowerPoint (.pptx) package: no ppt/ parts");
  }

  const relsRaw = new Map<string, string>();
  for (const n of names) {
    if (n.endsWith(".rels")) relsRaw.set(n, strFromU8(entries[n]!));
  }

  const parts = new Map<string, string>();
  const rels = new Map<string, Relationship[]>();
  for (const n of names) {
    if (!n.endsWith(".xml") || n.endsWith(".rels")) continue;
    parts.set(n, strFromU8(entries[n]!));
    rels.set(n, parseRels(relsRaw.get(relsPathFor(n))));
  }
  return { parts, rels };
}

/** "../theme/theme1.xml" を from part 基準で正規化。 */
export function normalizeTarget(fromPart: string, target: string): string {
  const dir = fromPart.slice(0, fromPart.lastIndexOf("/"));
  const segs = `${dir}/${target}`.split("/");
  const out: string[] = [];
  for (const s of segs) {
    if (s === "..") out.pop();
    else if (s !== "." && s !== "") out.push(s);
  }
  return out.join("/");
}
