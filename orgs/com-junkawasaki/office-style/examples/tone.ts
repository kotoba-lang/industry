/**
 * 実機 Gemma 4 vision トーン推定デモ。
 *   node --import tsx examples/tone.ts [path.pptx]
 * Node では device "cpu" (onnxruntime-node) + dtype "q4" を使う。
 * 初回はモデル DL (数 GB)。キャッシュは OFFICE_STYLE_CACHE か既定 HF キャッシュ。
 */
import { readFile } from "node:fs/promises";
import { extractStyle } from "../src/extract/deterministic.js";
import { inferToneFromDeckBytes } from "../src/pipeline.js";

// transformers のキャッシュ先を先に設定 (ESM singleton なので後続 import に効く)。
const cacheDir = process.env["OFFICE_STYLE_CACHE"];
if (cacheDir) {
  const t: any = await import("@huggingface/transformers");
  t.env.cacheDir = cacheDir;
  t.env.allowLocalModels = false;
}

const path = process.argv[2] ?? "examples/sample.pptx";
const bytes = new Uint8Array(await readFile(path));
const ir = extractStyle(bytes, path);

let lastPct = -1;
const t0 = Date.now();
const tone = await inferToneFromDeckBytes(bytes, ir, {
  device: (process.env["OFFICE_STYLE_DEVICE"] as "cpu" | "wasm") ?? "cpu",
  dtype: process.env["OFFICE_STYLE_DTYPE"] ?? "q4",
  maxNewTokens: 320,
  onProgress: (info: any) => {
    if (info?.status === "progress" && typeof info.progress === "number") {
      const pct = Math.floor(info.progress);
      if (pct >= lastPct + 10) {
        lastPct = pct;
        process.stderr.write(`  dl ${info.file ?? ""} ${pct}%\n`);
      }
    } else if (info?.status === "done" || info?.status === "ready") {
      process.stderr.write(`  ${info.status} ${info.file ?? ""}\n`);
    }
  },
});

process.stderr.write(`\ninference done in ${((Date.now() - t0) / 1000).toFixed(1)}s\n`);
process.stdout.write(JSON.stringify(tone, null, 2) + "\n");
