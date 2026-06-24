/**
 * OPC リレーションを辿る部品参照解決。
 * slide → slideLayout → slideMaster → theme の継承チェーンを引く。
 */
import type { OpcPackage } from "../ooxml/opc.js";
import { normalizeTarget } from "../ooxml/opc.js";

function relTarget(pkg: OpcPackage, fromPart: string, typeRe: RegExp): string | undefined {
  for (const r of pkg.rels.get(fromPart) ?? []) {
    if (typeRe.test(r.type)) return normalizeTarget(fromPart, r.target);
  }
  return undefined;
}

export const themeOfMaster = (pkg: OpcPackage, master: string): string | undefined =>
  relTarget(pkg, master, /\/theme$/i);

export const masterOfLayout = (pkg: OpcPackage, layout: string): string | undefined =>
  relTarget(pkg, layout, /slideMaster$/i);

export const layoutOfSlide = (pkg: OpcPackage, slide: string): string | undefined =>
  relTarget(pkg, slide, /slideLayout$/i);

/** slide → (layout 経由で) master part。 */
export function masterOfSlide(pkg: OpcPackage, slide: string): string | undefined {
  const layout = layoutOfSlide(pkg, slide);
  return layout ? masterOfLayout(pkg, layout) : undefined;
}
