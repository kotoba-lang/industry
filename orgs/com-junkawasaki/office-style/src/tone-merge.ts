/** 決定論 IR に vision 解釈を非破壊マージする (node/browser 共通)。 */
import type { StyleIR, ToneProfile } from "./types.js";

export function withTone(ir: StyleIR, tone: ToneProfile): StyleIR {
  return { ...ir, tone };
}
