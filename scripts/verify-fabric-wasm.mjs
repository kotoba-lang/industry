#!/usr/bin/env node
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";

// Paths are the west-DECLARED checkout of each product (ADR-2608137200).
// Every path below was previously the pre-rename / pre-transfer location; the
// mapping was established by GitHub repository id, not by name similarity, and
// each right-hand side is `path:` in manifest/west.yml:
//   1265956398 gftdcojp/local-manimani   -> network-awai/local-manimani
//   1275877152 gftdcojp/network-isekai   -> network-awai/network-isekai
//   1281275923 jk-luxury/club-shinshi    -> network-awai/club-shinshi
//   1281909207 gftdcojp/ai-gftd-yukkuri  -> cloud-itonami/yukkuri
//   1284882156 gftdcojp/ai-gftd-dougaka  -> cloud-itonami/ai-gftd-dougaka
//   1284881213 gftdcojp/ai-gftd-animeka  -> cloud-itonami/animeka
//   1284885000 gftdcojp/ai-gftd-mangaka  -> cloud-itonami/mangaka
//   1289900617 jk-luxury/net-babiniku    -> network-awai/net-babiniku
//   1282029812 gftdcojp/cloud-itonami    -> network-awai/cloud-itonami
const products = [
  ["manimani", "orgs/network-awai/local-manimani", 2],
  ["isekai", "orgs/network-awai/network-isekai", 3],
  ["shinshi", "orgs/network-awai/club-shinshi", 4],
  ["yukkuri", "orgs/cloud-itonami/yukkuri", 5],
  ["dougaka", "orgs/cloud-itonami/ai-gftd-dougaka", 6],
  ["animeka", "orgs/cloud-itonami/animeka", 7],
  ["mangaka", "orgs/cloud-itonami/mangaka", 8],
  ["babiniku", "orgs/network-awai/net-babiniku", 9],
];

const results = [{ product: "itonami", ok: existsSync("orgs/network-awai/cloud-itonami/wasm/fabric-evidence.edn"),
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
