/**
 * StyleIR — pptx の「トーン・マナー・スタイル」を保持する中間表現 (IR)。
 *
 * 2 レイヤを 1 つの IR に統合する:
 *   1. **決定論レイヤ** (vision 不要): theme1.xml / slideMaster / slideLayout /
 *      presentation.xml / viewProps.xml を OOXML から直接読み、配色・フォント・
 *      プレースホルダ・既定テキストスタイル・ガイドを「正解値」として埋める。
 *   2. **解釈レイヤ** (Gemma 4 vision): レンダリング画像から、トーン/ムード/
 *      フォーマリティ等の「人が言語化する印象」をラベル付けする。`tone` に入る。
 *
 * すべて zod スキーマで定義し、`StyleIRSchema.parse()` で検証できる。
 */
import { z } from "zod";

/** OOXML の長さ単位。既定は EMU (914400 EMU = 1 inch = 96 px @ 9525 EMU/px)。 */
export const EMU_PER_PX = 9525;
export const EMU_PER_PT = 12700;

export const AppKindSchema = z.enum(["ppt"]);
export type AppKind = z.infer<typeof AppKindSchema>;

/** 解決済みの色。OOXML の srgbClr/sysClr/schemeClr を hex (#RRGGBB) に正規化。 */
export const ColorSchema = z.object({
  hex: z.string().regex(/^#[0-9A-Fa-f]{6}$/),
  /** 元が schemeClr 参照ならその名前 (例 "accent1")。 */
  schemeRef: z.string().optional(),
  /** lumMod/lumOff/alpha 等の修飾 (情報保持用)。 */
  mods: z.record(z.string(), z.number()).optional(),
});
export type Color = z.infer<typeof ColorSchema>;

/** theme1.xml の a:clrScheme (12 ロール)。 */
export const ColorSchemeSchema = z.object({
  name: z.string().optional(),
  dk1: ColorSchema,
  lt1: ColorSchema,
  dk2: ColorSchema,
  lt2: ColorSchema,
  accent1: ColorSchema,
  accent2: ColorSchema,
  accent3: ColorSchema,
  accent4: ColorSchema,
  accent5: ColorSchema,
  accent6: ColorSchema,
  hlink: ColorSchema,
  folHlink: ColorSchema,
});
export type ColorScheme = z.infer<typeof ColorSchemeSchema>;

/** major/minor それぞれの latin/ea/cs 書体。 */
export const FontFaceSchema = z.object({
  latin: z.string().optional(),
  ea: z.string().optional(),
  cs: z.string().optional(),
});
export type FontFace = z.infer<typeof FontFaceSchema>;

/** theme1.xml の a:fontScheme。 */
export const FontSchemeSchema = z.object({
  name: z.string().optional(),
  majorFont: FontFaceSchema,
  minorFont: FontFaceSchema,
});
export type FontScheme = z.infer<typeof FontSchemeSchema>;

export const ThemeSchema = z.object({
  name: z.string().optional(),
  colorScheme: ColorSchemeSchema,
  fontScheme: FontSchemeSchema,
});
export type Theme = z.infer<typeof ThemeSchema>;

/** EMU 矩形。 */
export const BBoxSchema = z.object({
  x: z.number(),
  y: z.number(),
  w: z.number(),
  h: z.number(),
  unit: z.literal("emu"),
});
export type BBox = z.infer<typeof BBoxSchema>;

/** プレースホルダ (placeholder)。type は title/body/ctrTitle/subTitle/pic 等。 */
export const PlaceholderSchema = z.object({
  type: z.string().optional(),
  idx: z.number().optional(),
  bbox: BBoxSchema.optional(),
});
export type Placeholder = z.infer<typeof PlaceholderSchema>;

/** 段落レベルの既定テキストスタイル (lvl1..9)。 */
export const TextLevelSchema = z.object({
  lvl: z.number(),
  font: FontFaceSchema.optional(),
  sizePt: z.number().optional(),
  bold: z.boolean().optional(),
  italic: z.boolean().optional(),
  color: ColorSchema.optional(),
  align: z.enum(["l", "ctr", "r", "just", "dist"]).optional(),
});
export type TextLevel = z.infer<typeof TextLevelSchema>;

/** title/body/other の既定テキストスタイル束。slideMaster の p:txStyles 由来。 */
export const TextStyleSchema = z.object({
  role: z.enum(["title", "body", "other"]),
  levels: z.array(TextLevelSchema),
});
export type TextStyle = z.infer<typeof TextStyleSchema>;

/** スライド背景。色 or テーマ参照 or 画像。 */
export const BackgroundSchema = z.object({
  fill: z.enum(["solid", "gradient", "image", "none"]).optional(),
  color: ColorSchema.optional(),
});
export type Background = z.infer<typeof BackgroundSchema>;

export const MasterRecordSchema = z.object({
  id: z.string(),
  name: z.string().optional(),
  part: z.string(),
  /** この master が参照する theme part (rels 由来)。 */
  themePart: z.string().optional(),
  /** この master 自身の theme (配色/フォント)。複数 master のデッキで個別に解決。 */
  theme: ThemeSchema.optional(),
  background: BackgroundSchema.optional(),
  placeholders: z.array(PlaceholderSchema),
  textStyles: z.array(TextStyleSchema),
});
export type MasterRecord = z.infer<typeof MasterRecordSchema>;

export const LayoutRecordSchema = z.object({
  id: z.string(),
  name: z.string().optional(),
  part: z.string(),
  /** layout の type 属性 (例 "title","obj","secHead","blank")。 */
  type: z.string().optional(),
  masterPart: z.string().optional(),
  placeholders: z.array(PlaceholderSchema),
});
export type LayoutRecord = z.infer<typeof LayoutRecordSchema>;

/**
 * 編集ガイド (viewProps.xml の p:guide)。
 * OOXML の p:guide/@pos は「1/8 pt (eighths of a point)」単位 (例: 4:3 中央ガイド
 * pos=2160 → 270pt=3.75in, pos=2880 → 360pt=5in)。抽出時に EMU へ正規化して保持する。
 *   EMU = pos * 12700 / 8 = pos * 1587.5
 */
export const GuideRecordSchema = z.object({
  orient: z.enum(["horz", "vert"]),
  pos: z.number(),
  unit: z.literal("emu"),
});
export type GuideRecord = z.infer<typeof GuideRecordSchema>;

export const SlideSizeSchema = z.object({
  cx: z.number(),
  cy: z.number(),
  unit: z.literal("emu"),
  /** 例 "16:9","4:3" (近似)。 */
  aspect: z.string().optional(),
});
export type SlideSize = z.infer<typeof SlideSizeSchema>;

/** ── 解釈レイヤ (Gemma 4 vision 由来) ── */
export const ToneProfileSchema = z.object({
  formality: z.enum(["formal", "neutral", "casual"]).optional(),
  /** 例 ["corporate","energetic","minimal"]。 */
  mood: z.array(z.string()).default([]),
  colorMood: z.enum(["warm", "cool", "neutral", "monochrome", "vivid"]).optional(),
  density: z.enum(["sparse", "balanced", "dense"]).optional(),
  /** 例 ["flat","gradient-heavy","photo-driven","line-art"]。 */
  visualStyle: z.array(z.string()).default([]),
  summary: z.string().optional(),
  confidence: z.number().min(0).max(1).optional(),
  /** どのスライド画像から推定したか (part 名 or ファイルパス)。 */
  evidence: z.array(z.string()).default([]),
});
export type ToneProfile = z.infer<typeof ToneProfileSchema>;

/** ── 統合 IR ── */
export const StyleIRSchema = z.object({
  version: z.literal("style-ir/1"),
  source: z.object({ path: z.string(), app: AppKindSchema }),
  slideSize: SlideSizeSchema.optional(),
  /** 代表 theme (= 既定 master の theme)。後方互換用。 */
  theme: ThemeSchema.optional(),
  /** デッキ内の全 theme (part 単位で重複排除)。複数 master 対応。 */
  themes: z.array(ThemeSchema).default([]),
  masters: z.array(MasterRecordSchema).default([]),
  layouts: z.array(LayoutRecordSchema).default([]),
  guides: z.array(GuideRecordSchema).default([]),
  /** master 横断でまとめた既定テキストスタイル (代表)。 */
  textStyles: z.array(TextStyleSchema).default([]),
  /** vision 解釈。決定論抽出のみなら undefined。 */
  tone: ToneProfileSchema.optional(),
});
export type StyleIR = z.infer<typeof StyleIRSchema>;
