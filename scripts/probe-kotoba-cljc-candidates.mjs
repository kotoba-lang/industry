import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const preclassificationPath = path.join(root, "90-docs/migration/kotoba-cljc-preclassification.json");
const outputPath = path.join(root, "90-docs/migration/kotoba-cljc-compile-probes.json");
const cliArg = process.argv.indexOf("--cli");
const requestedCli = cliArg >= 0 ? process.argv[cliArg + 1] : "kotoba";
const cliLookup = path.isAbsolute(requestedCli)
  ? requestedCli
  : spawnSync("which", [requestedCli], { encoding: "utf8" }).stdout.trim();
if (!cliLookup || !fs.existsSync(cliLookup)) {
  throw new Error(`kotoba CLI not found: ${requestedCli}; pass --cli /absolute/path/to/kotoba`);
}
const cli = fs.realpathSync(cliLookup);
const releaseArg = process.argv.indexOf("--release");
const release = releaseArg >= 0 ? process.argv[releaseArg + 1] : "unknown";

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function resultJson(text) {
  const lines = text.trim().split("\n").reverse();
  for (const line of lines) {
    try {
      const value = JSON.parse(line);
      if (value && typeof value === "object") return value;
    } catch {
      // Native launchers may emit bounded non-JSON context before the result.
    }
  }
  return null;
}

function namespaceEnd(source) {
  const start = source.search(/\(ns\s+/m);
  if (start < 0) return -1;
  let depth = 0;
  let string = false;
  let escape = false;
  let comment = false;
  for (let index = start; index < source.length; index += 1) {
    const char = source[index];
    if (comment) {
      if (char === "\n") comment = false;
      continue;
    }
    if (string) {
      if (escape) escape = false;
      else if (char === "\\") escape = true;
      else if (char === '"') string = false;
      continue;
    }
    if (char === ";") comment = true;
    else if (char === '"') string = true;
    else if (char === "(") depth += 1;
    else if (char === ")" && --depth === 0) return index;
  }
  return -1;
}

function withExplicitExports(source) {
  const names = [...source.matchAll(/\(defn\s+([^\s()[\]{}";]+)/g)].map(match => match[1]);
  const unique = [...new Set(names)];
  const end = namespaceEnd(source);
  if (end < 0 || unique.length === 0) return null;
  return `${source.slice(0, end)}\n  (:export [${unique.join(" ")}])${source.slice(end)}`;
}

const sourceBytes = fs.readFileSync(preclassificationPath);
const preclassification = JSON.parse(sourceBytes);
if (preclassification.schema !== "kotoba.cljc-preclassification/v1") {
  throw new Error(`unsupported preclassification schema: ${preclassification.schema}`);
}
const candidates = preclassification.entries.filter(
  entry => entry.provisional_recommendation === "kotoba-candidate-compile-probe",
);
const representatives = [...new Map(candidates.map(entry => [entry.sha256, entry])).values()]
  .sort((left, right) => left.path.localeCompare(right.path));
const pathsByDigest = new Map();
for (const entry of candidates) {
  if (!pathsByDigest.has(entry.sha256)) pathsByDigest.set(entry.sha256, []);
  pathsByDigest.get(entry.sha256).push(entry.path);
}

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "kotoba-cljc-probes-"));
const probes = [];
try {
  representatives.forEach((entry, index) => {
    const artifact = path.join(temporary, `${entry.sha256}.mjs`);
    const run = spawnSync(
      cli,
      ["compile", path.join(root, entry.path), "--target", "web", "--output", artifact, "--json"],
      { cwd: root, encoding: "utf8", maxBuffer: 4 * 1024 * 1024 },
    );
    if (run.error) throw run.error;
    const wire = resultJson(`${run.stdout}${run.stderr}`);
    const ok = run.status === 0 && wire?.["kotoba.cli/ok?"] === true;
    const data = wire?.["kotoba.cli/data"] ?? {};
    const exception = Array.isArray(data["exception-chain"]) ? data["exception-chain"][0] : null;
    const message = wire?.["kotoba.cli/message"] ?? exception?.message ?? "missing-wire-result";
    let remediation = null;
    if (!ok && message === "entryless library requires an explicit non-empty namespace export list") {
      const transformed = withExplicitExports(fs.readFileSync(path.join(root, entry.path), "utf8"));
      if (transformed) {
        const transformedPath = path.join(temporary, `${entry.sha256}.explicit-export.cljc`);
        const transformedArtifact = path.join(temporary, `${entry.sha256}.explicit-export.mjs`);
        fs.writeFileSync(transformedPath, transformed);
        const transformedRun = spawnSync(
          cli,
          ["compile", transformedPath, "--target", "web", "--output", transformedArtifact, "--json"],
          { cwd: root, encoding: "utf8", maxBuffer: 4 * 1024 * 1024 },
        );
        const transformedWire = resultJson(`${transformedRun.stdout}${transformedRun.stderr}`);
        const transformedData = transformedWire?.["kotoba.cli/data"] ?? {};
        remediation = {
          kind: "add-explicit-public-exports",
          exports: [...transformed.matchAll(/\(:export\s+\[([^\]]+)\]/g)]
            .flatMap(match => match[1].trim().split(/\s+/)),
          status: transformedRun.status === 0 && transformedWire?.["kotoba.cli/ok?"] === true
            ? "compile-accepted" : "compile-rejected",
          phase: transformedData.phase ?? null,
          message: transformedWire?.["kotoba.cli/message"] ?? null,
          artifact_sha256: fs.existsSync(transformedArtifact)
            ? sha256(fs.readFileSync(transformedArtifact)) : null,
          provenance_manifest: fs.existsSync(`${transformedArtifact}.manifest.edn`),
        };
      }
    }
    probes.push({
      sha256: entry.sha256,
      representative_path: entry.path,
      propagated_paths: [...pathsByDigest.get(entry.sha256)].sort(),
      status: ok ? "compile-accepted" : "compile-rejected",
      exit_status: run.status,
      code: wire?.["kotoba.cli/code"] ?? "missing-wire-result",
      phase: data.phase ?? null,
      message,
      artifact_sha256: ok && fs.existsSync(artifact) ? sha256(fs.readFileSync(artifact)) : null,
      provenance_manifest: ok && fs.existsSync(`${artifact}.manifest.edn`),
      remediation,
    });
    if ((index + 1) % 50 === 0 || index + 1 === representatives.length) {
      process.stderr.write(`probed ${index + 1}/${representatives.length}\n`);
    }
  });
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

const accepted = probes.filter(probe => probe.status === "compile-accepted");
const rejected = probes.filter(probe => probe.status === "compile-rejected");
const propagatedAccepted = accepted.reduce((total, probe) => total + probe.propagated_paths.length, 0);
const remediated = probes.filter(probe => probe.remediation?.status === "compile-accepted");
const phaseCounts = Object.fromEntries(
  [...new Set(rejected.map(probe => probe.phase ?? "unknown"))].sort().map(phase => [
    phase,
    rejected.filter(probe => (probe.phase ?? "unknown") === phase).length,
  ]),
);
const result = {
  schema: "kotoba.cljc-compile-probes/v1",
  generated_on: "2026-07-19",
  authority: "installed-native-release-compiler",
  release,
  cli_path: cli,
  cli_sha256: sha256(fs.readFileSync(cli)),
  preclassification_sha256: sha256(sourceBytes),
  target: "web",
  candidate_paths: candidates.length,
  unique_content_probes: probes.length,
  accepted_unique_contents: accepted.length,
  rejected_unique_contents: rejected.length,
  accepted_propagated_paths: propagatedAccepted,
  remediated_unique_contents: remediated.length,
  remediated_propagated_paths: remediated.reduce(
    (total, probe) => total + probe.propagated_paths.length, 0,
  ),
  rejected_phase_counts: phaseCounts,
  probes,
};
fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o644 });
process.stdout.write(`${JSON.stringify({
  output: path.relative(root, outputPath),
  candidates: result.candidate_paths,
  unique: result.unique_content_probes,
  accepted_unique: result.accepted_unique_contents,
  accepted_paths: result.accepted_propagated_paths,
  remediated_unique: result.remediated_unique_contents,
  remediated_paths: result.remediated_propagated_paths,
  rejected_phase_counts: result.rejected_phase_counts,
})}\n`);
