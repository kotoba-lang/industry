/** examples/sample.pptx から StyleIR を抽出して表示。`npm run demo`。 */
import { readFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { extractStyle } from "../src/extract/deterministic.js";

const here = dirname(fileURLToPath(import.meta.url));
const path = resolve(here, "sample.pptx");
const ir = extractStyle(new Uint8Array(await readFile(path)), path);

console.log(JSON.stringify(ir, null, 2));
