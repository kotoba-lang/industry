import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const inventoryPath = path.join(root, "90-docs/migration/kotoba-cljc-inventory.json");
const outputPath = path.join(root, "90-docs/migration/kotoba-cljc-preclassification.json");
const inventory = JSON.parse(fs.readFileSync(inventoryPath, "utf8"));

if (inventory.schema !== "kotoba.cljc-inventory/v1") {
  throw new Error(`unsupported inventory schema: ${inventory.schema}`);
}

const detectors = [
  ["reader-conditional", /#\?(?:@)?\s*\(/],
  ["namespace-require", /\(ns\b[\s\S]{0,16384}?\(:require\b/m],
  ["host-js-interop", /(?:^|[^\w-])(?:js\/|goog\.|cljs\.js|\.\-\w+)/m],
  ["host-jvm-interop", /(?:^|[^\w-])(?:java\.|javax\.|clojure\.java|System\/|Class\/forName|\.\w+\s*\()/m],
  ["macro-definition", /(?:^|[\s([])defmacro\b/m],
  ["mutation", /(?:^|[\s([])(?:atom|swap!|reset!|set!|alter-var-root|volatile!)\b/m],
  ["io-or-process", /(?:^|[\s([])(?:slurp|spit|shell\/sh|future|pmap)\b|clojure\.java\.(?:io|shell)/m],
  ["network-or-database", /(?:http|fetch|socket|jdbc|datasource|database|sql|datomic|xtdb)/i],
  ["higher-order-sequence", /(?:^|[\s([])(?:map|mapv|reduce|filter|remove|keep|group-by|sort-by|apply|partial|comp)\b/m],
  ["exception-control", /(?:^|[\s([])(?:try|catch|finally|throw|ex-info)\b/m],
  ["protocol-or-type", /(?:^|[\s([])(?:defprotocol|deftype|defrecord|extend-type|reify)\b/m],
  ["async-control", /(?:core\.async|go-loop|<!|>!|alts!)/m],
  ["floating-point-literal", /(?:^|[^\w.])-?\d+\.\d+(?:[eE][+-]?\d+)?(?:$|[^\w.])/m],
  ["generic-map-data", /\{[^}\n]*:/m],
  ["string-data", /"(?:\\.|[^"\\])*"/m],
];

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function multiArityEvidence(source) {
  // Lexical evidence only: a defn declaration followed by two or more arity
  // clauses. Final disposition always requires parser/compiler confirmation.
  return /\(defn-?\s+[\w*+!?.<>='\/-]+(?:\s+"(?:\\.|[^"\\])*")?\s*\(\s*\[[^\]]*\][\s\S]{0,8192}?\)\s*\(\s*\[/m.test(source);
}

function codeOnly(source) {
  // Preserve offsets/newlines while removing strings and line comments. This
  // prevents prose such as "resolve root" and embedded JS examples from being
  // treated as executable invocation heads.
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

function dynamicEvaluationEvidence(source) {
  // `resolve` is also a common domain/protocol operation (DNS, workflow
  // resolution, etc.). Once a namespace declares its own unqualified resolve,
  // every unqualified call resolves to that var rather than clojure.core/resolve.
  // Treating those calls as ambient code authority inflated the security queue.
  const code = codeOnly(source);
  const declaresResolve = /\(\s*defn-?\s+resolve(?=\s|\[)|\(\s*resolve\s+\[/m.test(code);
  // Promise callbacks and domain projections frequently bind a parameter named
  // `resolve`; calls in that namespace are ordinary higher-order invocation.
  const bindsResolve = /\[[^\]]*\bresolve\b[^\]]*\]/m.test(code);
  const patterns = [
    /\(\s*(?:eval|load-string|read-string|requiring-resolve)(?=\s|\))/m,
    ...(!declaresResolve && !bindsResolve ? [/\(\s*resolve(?=\s|\))/m] : []),
  ];
  return patterns.some(pattern => pattern.test(code));
}

function recommendation(file, evidence) {
  const set = new Set(evidence);
  if (/(?:^|\/)(?:test|tests|test-resources|fixtures?)(?:\/|$)/.test(file)) {
    return "test-oracle-retain-until-cutover";
  }
  if (set.has("dynamic-evaluation")) return "security-review-before-migration";
  if (set.has("host-js-interop") || set.has("host-jvm-interop") || set.has("io-or-process")) {
    return "split-or-retain-host-adapter";
  }
  if (set.has("network-or-database")) return "split-explicit-capability-provider";
  if (["macro-definition", "protocol-or-type", "async-control", "multi-arity-defn",
       "higher-order-sequence", "exception-control", "floating-point-literal"]
      .some(feature => set.has(feature))) {
    return "language-gap-or-refactor-review";
  }
  if (set.has("namespace-require")) return "closed-project-graph-probe";
  return "kotoba-candidate-compile-probe";
}

const entries = [];
for (const repository of inventory.repositories) {
  for (const file of repository.paths) {
    const absolute = path.join(root, file);
    const source = fs.readFileSync(absolute, "utf8");
    const evidence = detectors.filter(([, pattern]) => pattern.test(source)).map(([name]) => name);
    if (dynamicEvaluationEvidence(source)) evidence.push("dynamic-evaluation");
    if (multiArityEvidence(source)) evidence.push("multi-arity-defn");
    evidence.sort();
    entries.push({
      path: file,
      repository: repository.repository,
      organization: repository.organization,
      bytes: Buffer.byteLength(source),
      sha256: sha256(source),
      evidence,
      provisional_recommendation: recommendation(file, evidence),
      disposition: "pending-confirmation",
    });
  }
}

const clustersByDigest = new Map();
for (const entry of entries) {
  if (!clustersByDigest.has(entry.sha256)) clustersByDigest.set(entry.sha256, []);
  clustersByDigest.get(entry.sha256).push(entry.path);
}
const clusters = [...clustersByDigest]
  .map(([digest, paths]) => ({ sha256: digest, path_count: paths.length, paths: paths.sort() }))
  .sort((left, right) => right.path_count - left.path_count || left.sha256.localeCompare(right.sha256));

const recommendationCounts = Object.fromEntries(
  [...new Set(entries.map(entry => entry.provisional_recommendation))]
    .sort()
    .map(value => [value, entries.filter(entry => entry.provisional_recommendation === value).length]),
);
const evidenceCounts = Object.fromEntries(
  [...new Set(entries.flatMap(entry => entry.evidence))]
    .sort()
    .map(value => [value, entries.filter(entry => entry.evidence.includes(value)).length]),
);

const result = {
  schema: "kotoba.cljc-preclassification/v1",
  generated_on: "2026-07-19",
  inventory_sha256: sha256(fs.readFileSync(inventoryPath)),
  authority: "provisional-lexical-evidence-only",
  completion_rule: "A migration PR or explicit retain decision must replace pending-confirmation.",
  path_count: entries.length,
  unique_content_count: clusters.length,
  duplicate_path_count: entries.length - clusters.length,
  recommendation_counts: recommendationCounts,
  evidence_counts: evidenceCounts,
  entries,
  content_clusters: clusters,
};

if (result.path_count !== inventory.cljc_path_count) {
  throw new Error(`path count mismatch: ${result.path_count} != ${inventory.cljc_path_count}`);
}
fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o644 });
process.stdout.write(`${JSON.stringify({
  output: path.relative(root, outputPath),
  paths: result.path_count,
  unique_contents: result.unique_content_count,
  duplicate_paths: result.duplicate_path_count,
  recommendation_counts: recommendationCounts,
})}\n`);
