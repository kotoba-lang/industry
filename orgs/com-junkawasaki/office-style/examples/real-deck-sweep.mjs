// 実 OOXML (外部ジェネレータ生成) に対するロバスト性スイープ。
// 既定で svgraph が吐いた tmp/*.pptx を対象に、抽出(zod 検証) + 全スライド描画が
// 例外なく通ることを確認する。office-style 自身の sample 以外の「他人が作った
// pptx」で壊れないことの回帰チェック。
//
//   REAL_DECK_DIR=/path/to/pptx-dir node examples/real-deck-sweep.mjs
//
// ディレクトリが無ければ skip (exit 0)。
import { readFile, readdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { extractStyle } from "../dist/src/extract/deterministic.js";
import { renderDeckSlides } from "../dist/src/render/deck.js";
import { StyleIRSchema } from "../dist/src/types.js";

const here = dirname(fileURLToPath(import.meta.url));
const dir = process.env.REAL_DECK_DIR ?? resolve(here, "../../svgraph/tmp");
if (!existsSync(dir)) {
  console.log(`SKIP: deck dir not found at ${dir} (set REAL_DECK_DIR)`);
  process.exit(0);
}

const files = (await readdir(dir)).filter((f) => f.endsWith(".pptx")).sort();
if (files.length === 0) {
  console.log(`SKIP: no .pptx in ${dir}`);
  process.exit(0);
}

let ok = 0, fail = 0;
for (const f of files) {
  try {
    const bytes = new Uint8Array(await readFile(`${dir}/${f}`));
    const ir = StyleIRSchema.parse(extractStyle(bytes, f));
    const slides = renderDeckSlides(bytes);
    if (!slides.every((s) => s.svg.startsWith("<svg") && s.svg.endsWith("</svg>"))) {
      throw new Error("malformed svg");
    }
    ok++;
    console.log(`OK   ${f.padEnd(40)} m=${ir.masters.length} l=${ir.layouts.length} th=${ir.themes.length} sl=${slides.length} ${ir.slideSize?.aspect ?? "?"}`);
  } catch (e) {
    fail++;
    console.log(`FAIL ${f.padEnd(40)} ${e?.message ?? e}`);
  }
}
console.log(`\n${ok}/${ok + fail} real decks parsed + rendered without error`);
process.exit(fail ? 1 : 0);
