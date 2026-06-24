/**
 * Gemma 4 (E2B/E4B) を **vision** で使い、レンダリング済みスライド画像から
 * トーン・マナー・スタイルの「印象ラベル」を推定する (解釈レイヤ)。
 *
 * office-causal の WebGpuGemmaAdjudicator と同じ transformers.js v4 +
 * Gemma4ForConditionalGeneration を使うが、こちらは画像入力 (multimodal)。
 * API キー無し・データ非送信でブラウザ(WebGPU)/Node(wasm) のローカル実行。
 *
 * 入力画像は本パッケージの責務外 (svgraph で SVG 化→ラスタライズ、または
 * LibreOffice/PowerPoint でエクスポートした PNG を渡す)。`ImageInput` は
 * URL / file path / data-URI / RawImage を許容する。
 */
import { ToneProfileSchema } from "../types.js";
import type { ToneProfile, Theme } from "../types.js";

export type Device = "webgpu" | "wasm" | "cpu";
/** URL/path/data-URI 文字列、PNG 等の生バイト、または RawImage ラッパ。 */
export type ImageInput = string | Uint8Array | { raw: unknown };

export interface GemmaVisionOptions {
  model?: string; // 既定 onnx-community/gemma-4-E2B-it-ONNX
  dtype?: string; // 既定 q4f16
  device?: Device; // 既定 webgpu
  maxNewTokens?: number; // 既定 384
  onProgress?: (info: unknown) => void;
}

const DEFAULT_MODEL = "onnx-community/gemma-4-E2B-it-ONNX";

function parseJson(text: string): Record<string, unknown> {
  const c = text.replace(/```json/gi, "").replace(/```/g, "");
  const i = c.indexOf("{"), j = c.lastIndexOf("}");
  if (i >= 0 && j > i) {
    try { return JSON.parse(c.slice(i, j + 1)); } catch { /* lenient */ }
  }
  return {};
}

function buildPrompt(theme?: Theme): string {
  const palette = theme
    ? `参考(OOXML 由来の確定値): 主要書体=${theme.fontScheme.majorFont.latin ?? "?"}, ` +
      `本文書体=${theme.fontScheme.minorFont.latin ?? "?"}, ` +
      `アクセント色=${theme.colorScheme.accent1.hex}。\n`
    : "";
  return (
    `あなたはプレゼン資料のアートディレクターです。提示したスライド画像から、` +
    `資料全体の「トーン・マナー・スタイル」を分析してください。\n` +
    palette +
    `次の JSON だけを出力 (説明文なし):\n` +
    `{"formality":"formal|neutral|casual",` +
    `"mood":["corporate","energetic" など 1-3 語],` +
    `"colorMood":"warm|cool|neutral|monochrome|vivid",` +
    `"density":"sparse|balanced|dense",` +
    `"visualStyle":["flat","gradient-heavy","photo-driven","line-art" 等 1-3 語],` +
    `"summary":"全体の印象を一文(日本語)",` +
    `"confidence":0..1 の自己評価}`
  );
}

export class GemmaVisionStylist {
  private model: any = null;
  private processor: any = null;
  readonly modelId: string;

  constructor(private readonly opts: GemmaVisionOptions = {}) {
    this.modelId = opts.model ?? DEFAULT_MODEL;
  }

  async load(): Promise<void> {
    if (this.model) return;
    const specifier = "@huggingface/transformers";
    const t: any = await import(specifier);
    this.processor = await t.AutoProcessor.from_pretrained(this.modelId);
    this.model = await t.Gemma4ForConditionalGeneration.from_pretrained(this.modelId, {
      dtype: this.opts.dtype ?? "q4f16",
      device: this.opts.device ?? "webgpu",
      progress_callback: this.opts.onProgress,
    });
  }

  /** ImageInput → transformers.js RawImage。 */
  private async toRawImage(img: ImageInput): Promise<unknown> {
    if (img && typeof img === "object" && "raw" in img) return (img as { raw: unknown }).raw;
    const specifier = "@huggingface/transformers";
    const t: any = await import(specifier);
    if (img instanceof Uint8Array) {
      const blob = new Blob([img as unknown as ArrayBuffer]);
      return t.RawImage.fromBlob(blob);
    }
    return t.RawImage.read(img as string);
  }

  /**
   * 1 枚以上のスライド画像からトーンを推定。複数枚は代表 1 枚を主に、
   * 残りは文脈として渡す (枚数が多い時はサンプリング推奨)。
   */
  async inferTone(
    images: ImageInput[],
    theme?: Theme,
    evidence: string[] = [],
  ): Promise<ToneProfile> {
    await this.load();
    const raws = await Promise.all(images.slice(0, 4).map((i) => this.toRawImage(i)));
    const content = [
      ...raws.map(() => ({ type: "image" })),
      { type: "text", text: buildPrompt(theme) },
    ];
    const messages = [{ role: "user", content }];
    const inputs = this.processor.apply_chat_template(messages, {
      add_generation_prompt: true,
      tokenize: true,
      return_dict: true,
      images: raws,
    });
    const outputs = await this.model.generate({
      ...inputs,
      max_new_tokens: this.opts.maxNewTokens ?? 384,
      do_sample: false,
    });
    const decoded: string[] = this.processor.batch_decode(
      outputs.slice(null, [inputs.input_ids.dims.at(-1), null]),
      { skip_special_tokens: true },
    );
    const o = parseJson(decoded[0] ?? "");
    return ToneProfileSchema.parse({
      formality: o["formality"],
      mood: Array.isArray(o["mood"]) ? o["mood"].map(String) : [],
      colorMood: o["colorMood"],
      density: o["density"],
      visualStyle: Array.isArray(o["visualStyle"]) ? o["visualStyle"].map(String) : [],
      summary: o["summary"] !== undefined ? String(o["summary"]) : undefined,
      confidence: typeof o["confidence"] === "number" ? o["confidence"] : undefined,
      evidence,
    });
  }
}
