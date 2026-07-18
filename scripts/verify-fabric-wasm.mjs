#!/usr/bin/env node
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";

const products = [
  ["manimani", "orgs/gftdcojp/local-manimani", 2],
  ["isekai", "orgs/gftdcojp/network-isekai", 3],
  ["shinshi", "orgs/jk-luxury/club-shinshi", 4],
  ["yukkuri", "orgs/gftdcojp/ai-gftd-yukkuri", 5],
  ["dougaka", "orgs/gftdcojp/ai-gftd-dougaka", 6],
  ["animeka", "orgs/gftdcojp/ai-gftd-animeka", 7],
  ["mangaka", "orgs/gftdcojp/ai-gftd-mangaka", 8],
  ["babiniku", "orgs/jk-luxury/net-babiniku", 9],
];

const results = [{ product: "itonami", ok: existsSync("orgs/gftdcojp/cloud-itonami/wasm/fabric-evidence.edn"),
  evidence: "wasm/fabric-evidence.edn", gate: "bb test-wasm-cutover: 45 tests / 1144 assertions" }];
for (const [product, root, expected] of products) {
  const paths = ["guardian.kotoba", "kotoba.lock.edn", "host-policy.edn", "guardian.wasm", "evidence.edn"]
    .map((name) => `${root}/fabric/${name}`);
  const missing = paths.filter((path) => !existsSync(path));
  if (missing.length) { results.push({ product, ok: false, missing }); continue; }
  const bytes = readFileSync(`${root}/fabric/guardian.wasm`);
  const evidence = readFileSync(`${root}/fabric/evidence.edn`, "utf8");
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  const imports = WebAssembly.Module.imports(new WebAssembly.Module(bytes));
  const instance = await WebAssembly.instantiate(bytes);
  const result = instance.instance.exports.main();
  results.push({ product, sha256, imports: imports.length, result,
    ok: imports.length === 0 && result === expected && evidence.includes(sha256) && evidence.includes(":verified true") });
}
console.log(JSON.stringify({ schema: 1, products: results.length, passed: results.filter((x) => x.ok).length, results }, null, 2));
if (results.some((x) => !x.ok)) process.exitCode = 1;
