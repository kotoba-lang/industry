/**
 * StyleIR → 「スタイルプレビュー SVG」。pptx の確定値 (背景色・配色・フォント・
 * マスターのプレースホルダ枠・既定テキストスタイル) から、その資料の見た目を
 * 代表する 1 枚を合成する。これを raster 化して Gemma vision の入力にする
 * (= 実データ由来の vision 入力)。svgraph が読めるよう <metadata> を埋める。
 */
import { EMU_PER_PX } from "../types.js";
import type { StyleIR, Color, TextStyle } from "../types.js";
import { toSvgraphIngest } from "../svgraph/export.js";

const px = (emu: number): number => Math.round(emu / EMU_PER_PX);
const esc = (s: string): string =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

function levelOf(styles: TextStyle[], role: TextStyle["role"]) {
  return styles.find((s) => s.role === role)?.levels[0];
}

export function renderPreviewSvg(ir: StyleIR): string {
  const W = ir.slideSize ? px(ir.slideSize.cx) : 1280;
  const H = ir.slideSize ? px(ir.slideSize.cy) : 720;
  const cs = ir.theme?.colorScheme;
  const major = ir.theme?.fontScheme.majorFont.latin ?? "sans-serif";
  const minor = ir.theme?.fontScheme.minorFont.latin ?? "sans-serif";
  const master = ir.masters[0];
  const bg = master?.background?.color?.hex ?? cs?.lt1.hex ?? "#FFFFFF";
  const ink = cs?.dk1.hex ?? "#111111";

  const title = levelOf(ir.textStyles, "title");
  const body = levelOf(ir.textStyles, "body");
  const titleColor = title?.color?.hex ?? cs?.dk2.hex ?? ink;
  const bodyColor = body?.color?.hex ?? ink;
  const titlePt = title?.sizePt ?? 40;
  const bodyPt = body?.sizePt ?? 18;
  // pt → px (96/72)
  const pt2px = (pt: number) => Math.round(pt * (96 / 72));

  const parts: string[] = [];
  parts.push(`<rect x="0" y="0" width="${W}" height="${H}" fill="${bg}"/>`);

  // マスターのプレースホルダ枠 (実 bbox)
  for (const ph of master?.placeholders ?? []) {
    if (!ph.bbox) continue;
    const x = px(ph.bbox.x), y = px(ph.bbox.y), w = px(ph.bbox.w), h = px(ph.bbox.h);
    parts.push(
      `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="none" ` +
        `stroke="${cs?.accent1.hex ?? "#888"}" stroke-dasharray="6 6" stroke-width="2" data-ph="${esc(ph.type ?? "")}"/>`,
    );
  }

  // タイトル/本文サンプル (テーマフォント・色・サイズ)
  const tx = master?.placeholders.find((p) => p.type === "title")?.bbox;
  const bx = master?.placeholders.find((p) => p.type === "body")?.bbox;
  const tX = tx ? px(tx.x) : Math.round(W * 0.07);
  const tY = tx ? px(tx.y) + pt2px(titlePt) : Math.round(H * 0.18);
  const bX = bx ? px(bx.x) : tX;
  const bY = bx ? px(bx.y) + pt2px(bodyPt) : Math.round(H * 0.42);

  parts.push(
    `<text x="${tX}" y="${tY}" font-family="${esc(major)}" font-size="${pt2px(titlePt)}" ` +
      `font-weight="${title?.bold ? 700 : 400}" fill="${titleColor}" data-role="title">プレゼンテーション タイトル</text>`,
  );
  for (let i = 0; i < 3; i++) {
    parts.push(
      `<text x="${bX}" y="${bY + i * Math.round(pt2px(bodyPt) * 1.6)}" font-family="${esc(minor)}" ` +
        `font-size="${pt2px(bodyPt)}" fill="${bodyColor}" data-role="body">• 本文サンプル行 ${i + 1}（${esc(minor)}）</text>`,
    );
  }

  // 配色スウォッチ (accent1..6)
  if (cs) {
    const swatches: Color[] = [cs.accent1, cs.accent2, cs.accent3, cs.accent4, cs.accent5, cs.accent6];
    const sw = Math.round(W * 0.04);
    const gap = Math.round(sw * 0.3);
    const startX = W - (sw + gap) * swatches.length - Math.round(W * 0.04);
    const yY = H - sw - Math.round(H * 0.05);
    swatches.forEach((c, i) => {
      parts.push(
        `<rect x="${startX + i * (sw + gap)}" y="${yY}" width="${sw}" height="${sw}" rx="4" fill="${c.hex}" data-swatch="accent${i + 1}"/>`,
      );
    });
  }

  // svgraph がフラットに取り込めるシェイプを埋める (二重ネスト回避)。
  const presentation = toSvgraphIngest(ir);
  const metadata =
    `<metadata><![CDATA[${JSON.stringify({ kind: "office-style-preview", presentation })}]]></metadata>`;

  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">` +
    metadata +
    parts.join("") +
    `</svg>`
  );
}
