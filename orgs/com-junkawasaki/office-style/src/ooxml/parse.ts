/**
 * 生 XML → 汎用 AST (XmlEl)。fast-xml-parser を要素順・属性・名前空間保持で使う。
 * (office-causal/src/ooxml/parse.ts と同じ規約。本パッケージ独立のため再掲。)
 */
import { XMLParser } from "fast-xml-parser";

export interface XmlEl {
  /** 名前空間プレフィックス付きタグ名 ("a:srgbClr", "p:sldSz")。 */
  tag: string;
  attrs: Record<string, string>;
  children: XmlEl[];
  text?: string;
}

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: "@_",
  preserveOrder: true,
  trimValues: false,
  textNodeName: "#text",
});

type RawNode = Record<string, unknown> & { ":@"?: Record<string, string> };

function convert(raw: RawNode): XmlEl | null {
  const keys = Object.keys(raw).filter((k) => k !== ":@");
  const tag = keys[0];
  if (!tag) return null;

  if (tag === "#text") {
    const t = String((raw as Record<string, unknown>)["#text"] ?? "");
    return t.trim() ? { tag: "#text", attrs: {}, children: [], text: t } : null;
  }

  const attrsRaw = raw[":@"] ?? {};
  const attrs: Record<string, string> = {};
  for (const [k, v] of Object.entries(attrsRaw)) {
    attrs[k.replace(/^@_/, "")] = String(v);
  }

  const childArr = (raw as Record<string, unknown>)[tag] as RawNode[] | undefined;
  const children: XmlEl[] = [];
  let text: string | undefined;
  for (const c of childArr ?? []) {
    const el = convert(c);
    if (!el) continue;
    if (el.tag === "#text") text = (text ?? "") + el.text;
    else children.push(el);
  }

  return { tag, attrs, children, ...(text !== undefined ? { text } : {}) };
}

export function parseXml(xml: string): XmlEl {
  const raw = parser.parse(xml) as RawNode[];
  for (const r of raw) {
    const el = convert(r);
    if (el && el.tag !== "?xml") return el;
  }
  throw new Error("Empty XML document");
}

/** 深さ優先で子孫を走査。 */
export function* walk(el: XmlEl): Generator<XmlEl> {
  yield el;
  for (const c of el.children) {
    if (c.tag === "#text") continue;
    yield* walk(c);
  }
}

/** 直下/子孫から最初の tag 一致要素。 */
export function findDesc(el: XmlEl, tag: string): XmlEl | undefined {
  for (const c of el.children) {
    if (c.tag === tag) return c;
    const f = findDesc(c, tag);
    if (f) return f;
  }
  return undefined;
}

/** 直下の子で tag 一致するもの全部。 */
export function childrenOf(el: XmlEl, tag: string): XmlEl[] {
  return el.children.filter((c) => c.tag === tag);
}

/** 子孫すべてで tag 一致するもの (DFS 順)。 */
export function findAll(el: XmlEl, tag: string): XmlEl[] {
  const out: XmlEl[] = [];
  for (const e of walk(el)) if (e.tag === tag) out.push(e);
  return out;
}
