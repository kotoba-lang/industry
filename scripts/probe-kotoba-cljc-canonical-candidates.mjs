import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const matrixPath = path.join(root, "90-docs/migration/kotoba-cljc-canonical-admission-matrix.json");
const outputPath = path.join(root, "90-docs/migration/kotoba-cljc-canonical-compile-probes.json");
const cliIndex = process.argv.indexOf("--cli");
const cli = cliIndex >= 0 ? path.resolve(process.argv[cliIndex + 1]) : null;
if (!cli || !fs.existsSync(cli)) throw new Error("pass --cli /absolute/path/to/current/compiler/bin/kotoba");
const compilerRoot = path.dirname(path.dirname(cli));

function gitOutput(...args) {
  const result = spawnSync("git", args, { cwd: compilerRoot, encoding: "utf8" });
  if (result.status !== 0) throw new Error(`cannot establish compiler git authority: ${result.stderr}`);
  return result.stdout.trim();
}
const compilerGitRevision = gitOutput("rev-parse", "HEAD");
const compilerGitDirty = gitOutput("status", "--porcelain").length > 0;

function sha256(value) { return createHash("sha256").update(value).digest("hex"); }
function wireResult(output) {
  for (const line of output.trim().split("\n").reverse()) {
    try { const value = JSON.parse(line); if (value && typeof value === "object") return value; } catch {}
  }
  return null;
}
function namespaceEnd(source) {
  const start = source.search(/\(ns\s+/m);
  if (start < 0) return -1;
  let depth = 0, string = false, escaped = false, comment = false;
  for (let index = start; index < source.length; index += 1) {
    const character = source[index];
    if (comment) { if (character === "\n") comment = false; continue; }
    if (string) {
      if (escaped) escaped = false; else if (character === "\\") escaped = true;
      else if (character === '"') string = false; continue;
    }
    if (character === ";") comment = true;
    else if (character === '"') string = true;
    else if (character === "(") depth += 1;
    else if (character === ")" && --depth === 0) return index;
  }
  return -1;
}
function explicitExports(source) {
  const declared = /\(:export\s+\[([^\]]+)\]/m.exec(source)?.[1].trim().split(/\s+/).filter(Boolean) ?? [];
  const names = [...new Set(declared.length > 0 ? declared
    : [...source.matchAll(/\(defn\s+([^\s()[\]{}";]+)/g)].map(value => value[1]))];
  const namespace = /\(ns\s+([^\s()[\]{}";]+)/m.exec(source)?.[1] ?? null;
  const start = source.search(/\(ns\s+/m);
  const end = namespaceEnd(source);
  if (start < 0 || end < 0 || !namespace || names.length === 0) return null;
  return `${source.slice(0, start)}(ns ${namespace} (:export [${names.join(" ")}]))${source.slice(end + 1)}`;
}
const matrixBytes = fs.readFileSync(matrixPath);
const matrix = JSON.parse(matrixBytes);
if (matrix.schema !== "kotoba.cljc-canonical-admission-matrix/v1") throw new Error("unsupported matrix");
const repositories = matrix.repositories.filter(value => value.disposition === "portable-direct-candidate");
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "kotoba-canonical-probes-"));
let probes = [];
const tasks = [];
try {
  for (const [repositoryIndex, repository] of repositories.entries()) {
    for (const namespace of repository.namespaces.filter(value => value.classification === "portable-direct-candidate")) {
      const sourcePath = path.join(root, repository.representative_checkout, namespace.path);
      const source = fs.readFileSync(sourcePath, "utf8");
      const transformed = explicitExports(source);
      const hasCallableApi = transformed !== null;
      for (const target of ["web", "wasm"]) {
        tasks.push({ repository: repository.repository, path: namespace.path,
          source_sha256: namespace.sha256, target, has_callable_api: hasCallableApi,
          source, transformed });
      }
    }
  }
  const inputPath = path.join(temporary, "tasks.json");
  const batchOutputPath = path.join(temporary, "results.json");
  fs.writeFileSync(inputPath, JSON.stringify(tasks));
  const runner = path.join(root, "scripts/probe-kotoba-cljc-batch.clj");
  const batch = spawnSync("clojure", ["-M", runner, inputPath, batchOutputPath], {
    cwd: compilerRoot, encoding: "utf8", maxBuffer: 16 * 1024 * 1024,
  });
  if (batch.status !== 0 || !fs.existsSync(batchOutputPath)) {
    throw new Error(`batch compiler failed: ${batch.stderr || batch.stdout}`);
  }
  probes = JSON.parse(fs.readFileSync(batchOutputPath, "utf8"));
} finally { fs.rmSync(temporary, { recursive: true, force: true }); }

probes.sort((left, right) => left.repository.localeCompare(right.repository)
  || left.path.localeCompare(right.path) || left.target.localeCompare(right.target));

const accepted = probes.filter(value => value.direct.status === "accepted");
const remediated = probes.filter(value => value.explicit_export_remediation?.status === "accepted");
const rejectionCounts = {};
for (const probe of probes.filter(value => value.direct.status === "rejected")) {
  const key = `${probe.direct.phase ?? "unknown"}:${probe.direct.message}`;
  rejectionCounts[key] = (rejectionCounts[key] ?? 0) + 1;
}
const result = {
  schema: "kotoba.cljc-canonical-compile-probes/v1",
  generated_on: new Date().toISOString(),
  authority: "current-default-branch-local-snapshot-plus-current-compiler-core-batch",
  matrix_sha256: sha256(matrixBytes),
  compiler_cli: cli,
  compiler_cli_sha256: sha256(fs.readFileSync(cli)),
  compiler_git_revision: compilerGitRevision,
  compiler_git_dirty: compilerGitDirty,
  repository_count: repositories.length,
  probe_count: probes.length,
  direct_accepted: accepted.length,
  remediated_accepted: remediated.length,
  rejected: probes.length - accepted.length,
  rejection_counts: Object.fromEntries(Object.entries(rejectionCounts).sort()),
  probes,
};
fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o644 });
process.stdout.write(`${JSON.stringify({ output: path.relative(root, outputPath), repositories: result.repository_count,
  probes: result.probe_count, direct_accepted: result.direct_accepted,
  remediated_accepted: result.remediated_accepted, rejected: result.rejected })}\n`);
