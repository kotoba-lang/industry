// office-style → svgraph editor 実流し込み確認。
// プレビュー SVG (presentation 埋め込み) を svgraph CLI の `svgraph-presentation`
// に通し、svgraph 側のトップレベル presentation に masters/layouts/guides/
// text_styles が反映されることを検証する。
//
//   SVGRAPH_BIN=/path/to/svgraph/bin/svgraph.mjs node examples/svgraph-roundtrip.mjs [deck.pptx]
//
// svgraph が見つからなければ skip (exit 0)。
import { readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { extractStyle } from "../dist/src/extract/deterministic.js";
import { renderPreviewSvg } from "../dist/src/render/preview-svg.js";

const here = dirname(fileURLToPath(import.meta.url));
const svgraphBin =
  process.env.SVGRAPH_BIN ?? resolve(here, "../../svgraph/bin/svgraph.mjs");

if (!existsSync(svgraphBin)) {
  console.log(`SKIP: svgraph CLI not found at ${svgraphBin} (set SVGRAPH_BIN)`);
  process.exit(0);
}

const deck = process.argv[2] ?? resolve(here, "sample.pptx");
const ir = extractStyle(new Uint8Array(await readFile(deck)), deck);
const svg = renderPreviewSvg(ir);
const svgPath = resolve(here, "../.cache/office-style-preview.svg");
await writeFile(svgPath, svg);

const out = execFileSync("node", [svgraphBin, "svgraph-presentation", svgPath], {
  encoding: "utf8",
  maxBuffer: 64 * 1024 * 1024,
});
const pres = JSON.parse(out);

const checks = [];
const ok = (name, cond, detail) => checks.push({ name, cond: !!cond, detail });

ok("svgraph parsed our SVG as a slide", pres.slides?.length >= 1, `slides=${pres.slides?.length}`);
ok("masters flow in", pres.masters?.length === ir.masters.length, `svgraph=${pres.masters?.length} ir=${ir.masters.length}`);
ok("layouts flow in", pres.layouts?.length === ir.layouts.length, `svgraph=${pres.layouts?.length} ir=${ir.layouts.length}`);
ok("guides flow in (flat, EMU→px)", pres.guides?.length === ir.guides.length, `svgraph=${pres.guides?.length} ir=${ir.guides.length}`);
const roles = new Set((pres.text_styles ?? []).map((t) => t.role));
ok("text_styles roles flow in", roles.has("title") && roles.has("body"), `roles=${[...roles].join(",")}`);
// 二重ネスト解消: properties は raw プロパティ袋 (properties.properties が無い)
const flat = (pres.text_styles ?? []).every((t) => t.properties?.properties === undefined && typeof t.properties?.fontSize !== "object");
ok("text_styles NOT double-nested", flat, `title.fontFamily=${pres.text_styles?.find((t) => t.role === "title")?.properties?.fontFamily}`);
// master の id が保持される (slideMasterN.xml)
const mids = new Set((pres.masters ?? []).map((m) => m.template_id));
ok("master template_id preserved", [...mids].some((x) => /slideMaster\d+\.xml/.test(x)), `ids=${[...mids].join(",")}`);
// 各 master の theme が個別に届く (複数 master)
const masterThemes = (pres.masters ?? []).map((m) => (m.metadata ?? {}).theme ?? (m.metadata?.data ?? {}).theme).filter(Boolean);
ok("per-master theme preserved", new Set(masterThemes).size === new Set(ir.masters.map((m) => m.theme?.name).filter(Boolean)).size, `themes=${[...new Set(masterThemes)].join(" / ")}`);
// 我々の完全な projection が slide metadata に round-trip
const slideJson = pres.slides?.[0]?.metadata?.json;
ok("full projection round-trips in slide metadata", slideJson?.kind === "office-style-preview", `kind=${slideJson?.kind}`);

let pass = 0;
for (const c of checks) {
  console.log(`${c.cond ? "PASS" : "FAIL"}  ${c.name}  (${c.detail})`);
  if (c.cond) pass++;
}
console.log(`\n${pass}/${checks.length} checks passed (svgraph ${(() => { try { return execFileSync("node", [svgraphBin, "--version"], { encoding: "utf8" }).trim(); } catch { return "?"; } })()})`);
process.exit(pass === checks.length ? 0 : 1);
