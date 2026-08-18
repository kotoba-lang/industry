import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const inputPath = path.join(root, "90-docs/migration/kotoba-cljc-preclassification.json");
const outputPath = path.join(root, "90-docs/migration/kotoba-cljc-security-dispositions.json");
const inputBytes = fs.readFileSync(inputPath);
const ledger = JSON.parse(inputBytes);
const alwaysDynamicCallPattern = /\(\s*(eval|load-string|read-string|requiring-resolve)(?=\s|\))/g;
const resolveCallPattern = /\(\s*(resolve)(?=\s|\))/g;

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function lineAt(source, offset) {
  return source.slice(0, offset).split("\n").length;
}

function testPath(value) {
  return /(?:^|\/)(?:test|tests|test-resources|fixtures?)(?:\/|$)/.test(value);
}

function codeOnly(source) {
  let out = "";
  let string = false;
  let escaped = false;
  let comment = false;
  for (const char of source) {
    if (comment) {
      if (char === "\n") { comment = false; out += char; } else out += " ";
    } else if (string) {
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === '"') string = false;
      out += char === "\n" ? "\n" : " ";
    } else if (char === ";") { comment = true; out += " "; }
    else if (char === '"') { string = true; out += " "; }
    else out += char;
  }
  return out;
}

function dynamicCalls(source) {
  const code = codeOnly(source);
  const declaresResolve = /\(\s*defn-?\s+resolve(?=\s|\[)|\(\s*resolve\s+\[/m.test(code);
  const bindsResolve = /\[[^\]]*\bresolve\b[^\]]*\]/m.test(code);
  const matches = [...code.matchAll(alwaysDynamicCallPattern)];
  if (!declaresResolve && !bindsResolve) matches.push(...code.matchAll(resolveCallPattern));
  return matches.sort((left, right) => left.index - right.index).map(match => ({
    head: match[1],
    line: lineAt(source, match.index),
  }));
}

const entries = ledger.entries.filter(entry => entry.evidence.includes("dynamic-evaluation"));
const byDigest = new Map();
for (const entry of entries) {
  const source = fs.readFileSync(path.join(root, entry.path), "utf8");
  const calls = dynamicCalls(source);
  if (calls.length === 0) throw new Error(`dynamic-evaluation entry has no call head: ${entry.path}`);
  if (!byDigest.has(entry.sha256)) byDigest.set(entry.sha256, []);
  byDigest.get(entry.sha256).push({ path: entry.path, calls });
}

const clusters = [...byDigest].map(([digest, paths]) => {
  const heads = [...new Set(paths.flatMap(item => item.calls.map(call => call.head)))].sort();
  const allTests = paths.every(item => testPath(item.path));
  const hasDynamicResolution = heads.includes("resolve") || heads.includes("requiring-resolve");
  const disposition = hasDynamicResolution
    ? "split-or-retain-dynamic-resolution-host-adapter"
    : allTests
      ? "retain-security-test-oracle-through-cutover"
      : "replace-with-bounded-data-reader-before-migration";
  return {
    sha256: digest,
    path_count: paths.length,
    paths: paths.sort((left, right) => left.path.localeCompare(right.path)),
    call_heads: heads,
    disposition,
    migration_authorized: false,
    safety_basis: hasDynamicResolution
      ? "Runtime namespace/var resolution is ambient code authority and cannot cross the Kotoba boundary."
      : "clojure.core/read-string admits reader evaluation surface; use an explicitly bounded data decoder.",
  };
}).sort((left, right) => left.sha256.localeCompare(right.sha256));

const dispositionCounts = Object.fromEntries(
  [...new Set(clusters.map(cluster => cluster.disposition))].sort().map(disposition => [
    disposition,
    clusters.filter(cluster => cluster.disposition === disposition).length,
  ]),
);
const result = {
  schema: "kotoba.cljc-security-dispositions/v1",
  generated_on: "2026-07-19",
  authority: "reviewed-exact-call-head-safety-policy",
  preclassification_sha256: sha256(inputBytes),
  path_count: entries.length,
  unique_content_count: clusters.length,
  migration_authorized_count: 0,
  disposition_counts: dispositionCounts,
  completion_rule: "Every exact dynamic-evaluation call is retained behind a host boundary or removed by a reviewed bounded-data refactor before migration.",
  clusters,
};
fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o644 });
process.stdout.write(`${JSON.stringify({
  output: path.relative(root, outputPath),
  paths: result.path_count,
  unique: result.unique_content_count,
  disposition_counts: result.disposition_counts,
})}\n`);
