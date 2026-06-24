#!/usr/bin/env node
/**
 * office-style CLI。
 *   office-style extract <file.pptx>            # 決定論 IR を JSON 出力
 *   office-style extract <file.pptx> --vision a.png b.png
 *                                               # + Gemma vision でトーン付与
 *   office-style tokens <file.pptx>             # 配色/フォント要約を表示
 */
import { readFile, writeFile } from "node:fs/promises";
import { extractStyle } from "./extract/deterministic.js";
import { GemmaVisionStylist } from "./vision/gemma-vision.js";
import { withTone } from "./index.js";
import { toSvgraphPresentation } from "./svgraph/export.js";
import { renderPreviewSvg } from "./render/preview-svg.js";
import { renderDeckSlides } from "./render/deck.js";
import { inferToneFromDeckBytes, inferDeckTone } from "./pipeline.js";

function flag(rest: string[], name: string): string | undefined {
  const i = rest.indexOf(name);
  return i >= 0 ? rest[i + 1] : undefined;
}

function usage(): never {
  process.stderr.write(
    "usage:\n" +
      "  office-style extract <file.pptx> [--vision <img...>] [--device webgpu|wasm]\n" +
      "  office-style tokens  <file.pptx>\n" +
      "  office-style svgraph <file.pptx>                 # svgraph presentation IR (JSON)\n" +
      "  office-style preview <file.pptx> [--svg-out f.svg]\n" +
      "  office-style render  <file.pptx> [--out-dir dir]  # 実スライドを SVG 化\n" +
      "  office-style tone    <file.pptx> [--device wasm] [--deck] [--max-slides N]\n",
  );
  process.exit(2);
}

async function main(): Promise<void> {
  const [cmd, file, ...rest] = process.argv.slice(2);
  if (!cmd || !file) usage();

  const bytes = new Uint8Array(await readFile(file));
  const ir = extractStyle(bytes, file);

  if (cmd === "tokens") {
    const cs = ir.theme?.colorScheme;
    const fs = ir.theme?.fontScheme;
    const lines = [
      `# ${file}`,
      ir.slideSize ? `slide: ${ir.slideSize.aspect ?? ""} (${ir.slideSize.cx}x${ir.slideSize.cy} emu)` : "slide: ?",
      fs ? `fonts: major=${fs.majorFont.latin ?? "?"} / minor=${fs.minorFont.latin ?? "?"}` : "fonts: ?",
      cs
        ? `colors: accent1=${cs.accent1.hex} accent2=${cs.accent2.hex} accent3=${cs.accent3.hex} dk1=${cs.dk1.hex} lt1=${cs.lt1.hex}`
        : "colors: ?",
      `masters: ${ir.masters.length}, layouts: ${ir.layouts.length}, guides: ${ir.guides.length}`,
    ];
    process.stdout.write(lines.join("\n") + "\n");
    return;
  }

  if (cmd === "svgraph") {
    process.stdout.write(JSON.stringify(toSvgraphPresentation(ir), null, 2) + "\n");
    return;
  }

  if (cmd === "preview") {
    const svg = renderPreviewSvg(ir);
    const out = flag(rest, "--svg-out");
    if (out) {
      await writeFile(out, svg);
      process.stderr.write(`wrote ${out}\n`);
    } else {
      process.stdout.write(svg + "\n");
    }
    return;
  }

  if (cmd === "render") {
    const slides = renderDeckSlides(bytes);
    const dir = flag(rest, "--out-dir");
    if (!slides.length) { process.stderr.write("no slides found\n"); return; }
    if (dir) {
      for (const s of slides) {
        const name = `${dir}/${s.part.split("/").pop()!.replace(/\.xml$/, ".svg")}`;
        await writeFile(name, s.svg);
        process.stderr.write(`wrote ${name}\n`);
      }
    } else {
      process.stdout.write(slides[0]!.svg + "\n");
    }
    return;
  }

  if (cmd === "tone") {
    const device = (flag(rest, "--device") ?? "webgpu") as "webgpu" | "wasm";
    const maxSlides = Number(flag(rest, "--max-slides") ?? 4);
    if (rest.includes("--deck")) {
      // 複数スライドを 1 枚ずつ推論して集約
      const { deck, perSlide } = await inferDeckTone(bytes, ir, { device, maxSlides });
      process.stdout.write(JSON.stringify({ deck, perSlide }, null, 2) + "\n");
    } else {
      const tone = await inferToneFromDeckBytes(bytes, ir, { device, maxSlides });
      process.stdout.write(JSON.stringify(withTone(ir, tone).tone, null, 2) + "\n");
    }
    return;
  }

  if (cmd === "extract") {
    let out = ir;
    const vi = rest.indexOf("--vision");
    if (vi >= 0) {
      const di = rest.indexOf("--device");
      const device = (di >= 0 ? rest[di + 1] : "webgpu") as "webgpu" | "wasm";
      const imgs = rest.slice(vi + 1).filter((a) => !a.startsWith("--"));
      const stylist = new GemmaVisionStylist({ device });
      const tone = await stylist.inferTone(imgs, ir.theme, imgs);
      out = withTone(ir, tone);
    }
    process.stdout.write(JSON.stringify(out, null, 2) + "\n");
    return;
  }

  usage();
}

main().catch((e) => {
  process.stderr.write(`error: ${e?.message ?? e}\n`);
  process.exit(1);
});
