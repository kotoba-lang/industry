import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const graphPath = path.join(root, "90-docs/migration/kotoba-cljc-project-graphs.json");
const outputPath = path.join(root, "90-docs/migration/kotoba-cljc-project-probes.json");
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
  for (const line of text.trim().split("\n").reverse()) {
    try {
      const value = JSON.parse(line);
      if (value && typeof value === "object") return value;
    } catch {
      // Native launchers may emit bounded context before their JSON result.
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
    if (comment) { if (char === "\n") comment = false; continue; }
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
  if (/\(:export\s+\[/.test(source)) return source;
  const names = [...source.matchAll(/\(defn\s+([^\s()[\]{}";]+)/g)].map(match => match[1]);
  const exports = [...new Set(names)];
  const end = namespaceEnd(source);
  if (end < 0 || exports.length === 0) return null;
  return `${source.slice(0, end)}\n  (:export [${exports.join(" ")}])${source.slice(end)}`;
}

function declaredNamespace(source) {
  return /\(ns\s+([^\s()[\]{}";]+)/m.exec(source)?.[1] ?? null;
}

function compile(source, target, artifact, sourceRoot = null) {
  const argv = ["compile", source, "--target", target, "--output", artifact, "--json"];
  if (sourceRoot) argv.push("--source-path", sourceRoot);
  const run = spawnSync(cli, argv, { cwd: root, encoding: "utf8", maxBuffer: 4 * 1024 * 1024 });
  if (run.error) throw run.error;
  const wire = resultJson(`${run.stdout}${run.stderr}`);
  const data = wire?.["kotoba.cli/data"] ?? {};
  const exception = Array.isArray(data["exception-chain"]) ? data["exception-chain"][0] : null;
  const accepted = run.status === 0 && wire?.["kotoba.cli/ok?"] === true;
  return {
    status: accepted ? "compile-accepted" : "compile-rejected",
    exit_status: run.status,
    code: wire?.["kotoba.cli/code"] ?? "missing-wire-result",
    phase: data.phase ?? null,
    message: wire?.["kotoba.cli/message"] ?? exception?.message ?? "missing-wire-result",
    artifact_sha256: accepted && fs.existsSync(artifact) ? sha256(fs.readFileSync(artifact)) : null,
    provenance_manifest: accepted && fs.existsSync(`${artifact}.manifest.edn`),
  };
}

const graphBytes = fs.readFileSync(graphPath);
const graphLedger = JSON.parse(graphBytes);
if (graphLedger.schema !== "kotoba.cljc-project-graphs/v1") {
  throw new Error(`unsupported project graph schema: ${graphLedger.schema}`);
}
const graphs = graphLedger.graphs.filter(
  graph => graph.closure_status === "closed-and-direct-probe-ready",
);
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "kotoba-cljc-project-probes-"));
const probes = [];
function sourceRootFor(graph) {
  const dependency = graph.internal_dependencies[0];
  if (!dependency) throw new Error(`project graph has no internal dependency: ${graph.path}`);
  const namespacePath = dependency.namespace.replaceAll(".", "/").replaceAll("-", "_");
  const suffix = `${namespacePath}${path.extname(dependency.path)}`;
  if (!dependency.path.endsWith(suffix)) {
    throw new Error(`dependency path does not match namespace: ${dependency.path}`);
  }
  return path.join(root, dependency.path.slice(0, -suffix.length));
}
try {
  for (const [graphIndex, graph] of graphs.entries()) {
    for (const target of ["web", "wasm"]) {
      const suffix = target === "web" ? "mjs" : "wasm";
      const standalone = graph.dependency_closure.paths.map((relativePath, nodeIndex) => ({
        path: relativePath,
        result: compile(
          path.join(root, relativePath),
          target,
          path.join(temporary, `${graphIndex}-${target}-${nodeIndex}.${suffix}`),
        ),
      }));
      const dependencies = graph.dependency_closure.paths.filter(value => value !== graph.path);
      const sourceRoot = sourceRootFor(graph);
      const remediationRoot = path.join(temporary, `${graphIndex}-${target}-root.cljc`);
      const remediationSourceRoot = path.join(temporary, `${graphIndex}-${target}-src`);
      let remediationReady = true;
      for (const relativePath of graph.dependency_closure.paths) {
        const source = fs.readFileSync(path.join(root, relativePath), "utf8");
        const transformed = withExplicitExports(source);
        const namespace = declaredNamespace(source);
        if (!transformed || !namespace) { remediationReady = false; break; }
        const destination = relativePath === graph.path
          ? remediationRoot
          : path.join(remediationSourceRoot,
            `${namespace.replaceAll(".", "/").replaceAll("-", "_")}${path.extname(relativePath)}`);
        fs.mkdirSync(path.dirname(destination), { recursive: true });
        fs.writeFileSync(destination, transformed);
      }
      const remediationArtifact = path.join(
        temporary, `${graphIndex}-${target}-explicit-exports.${suffix}`,
      );
      probes.push({
        root_path: graph.path,
        repository: graph.repository,
        target,
        source_path: path.relative(root, sourceRoot),
        dependency_paths: dependencies,
        standalone_nodes: standalone,
        composed_root: compile(
          path.join(root, graph.path),
          target,
          path.join(temporary, `${graphIndex}-${target}-composed.${suffix}`),
          sourceRoot,
        ),
        explicit_export_remediation: remediationReady
          ? compile(remediationRoot, target, remediationArtifact, remediationSourceRoot)
          : { status: "not-applicable", message: "no public defn export set" },
      });
    }
  }
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

const result = {
  schema: "kotoba.cljc-project-probes/v1",
  generated_on: "2026-07-19",
  authority: "installed-native-release-public-cli",
  release,
  cli_path: cli,
  cli_sha256: sha256(fs.readFileSync(cli)),
  project_graphs_sha256: sha256(graphBytes),
  targets: ["web", "wasm"],
  graph_count: graphs.length,
  probe_count: probes.length,
  composed_accepted: probes.filter(probe => probe.composed_root.status === "compile-accepted").length,
  composed_rejected: probes.filter(probe => probe.composed_root.status === "compile-rejected").length,
  remediated_accepted: probes.filter(
    probe => probe.explicit_export_remediation.status === "compile-accepted",
  ).length,
  remediated_rejected: probes.filter(
    probe => probe.explicit_export_remediation.status === "compile-rejected",
  ).length,
  probes,
};
fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o644 });
process.stdout.write(`${JSON.stringify({
  output: path.relative(root, outputPath),
  graphs: result.graph_count,
  probes: result.probe_count,
  composed_accepted: result.composed_accepted,
  composed_rejected: result.composed_rejected,
  remediated_accepted: result.remediated_accepted,
  remediated_rejected: result.remediated_rejected,
})}\n`);
