import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const preclassificationPath = path.join(root, "90-docs/migration/kotoba-cljc-preclassification.json");
const outputPath = path.join(root, "90-docs/migration/kotoba-cljc-project-graphs.json");
const inputBytes = fs.readFileSync(preclassificationPath);
const preclassification = JSON.parse(inputBytes);

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function balanced(source, start) {
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
    if (char === ";") {
      comment = true;
      continue;
    }
    if (char === '"') {
      string = true;
      continue;
    }
    if (char === "(") depth += 1;
    if (char === ")") {
      depth -= 1;
      if (depth === 0) return source.slice(start, index + 1);
    }
  }
  return null;
}

function namespaceFacts(source) {
  const match = /\(ns\s+([^\s()[\]{}";]+)/m.exec(source);
  if (!match) return { namespace: null, requires: [], parse_status: "missing-ns" };
  const start = match.index;
  const nsForm = balanced(source, start);
  if (!nsForm) return { namespace: match[1], requires: [], parse_status: "unbalanced-ns" };
  const requires = [];
  for (const marker of ["(:require", "(:require-macros"]) {
    let offset = 0;
    while (true) {
      const index = nsForm.indexOf(marker, offset);
      if (index < 0) break;
      const clause = balanced(nsForm, index);
      if (!clause) break;
      for (const dependency of clause.matchAll(/\[\s*([^\s()[\]{}";:]+)/g)) {
        requires.push(dependency[1]);
      }
      offset = index + clause.length;
    }
  }
  return {
    namespace: match[1],
    requires: [...new Set(requires)].sort(),
    parse_status: "parsed",
  };
}

const all = preclassification.entries.map(entry => {
  const source = fs.readFileSync(path.join(root, entry.path), "utf8");
  return { ...entry, ...namespaceFacts(source) };
});
const byRepository = new Map();
for (const entry of all) {
  if (!byRepository.has(entry.repository)) byRepository.set(entry.repository, []);
  byRepository.get(entry.repository).push(entry);
}

const graphs = [];

function dependencyClosure(rootEntry, namespaceIndex, entriesByPath) {
  const visited = new Set();
  const active = new Set();
  const cycles = new Set();
  const missing = new Set();
  const ambiguous = new Set();
  function visit(entry) {
    if (active.has(entry.path)) {
      cycles.add(entry.path);
      return;
    }
    if (visited.has(entry.path)) return;
    visited.add(entry.path);
    active.add(entry.path);
    for (const dependency of entry.requires) {
      const matches = namespaceIndex.get(dependency) ?? [];
      if (matches.length === 0) missing.add(dependency);
      else if (matches.length > 1) ambiguous.add(dependency);
      else visit(entriesByPath.get(matches[0]));
    }
    active.delete(entry.path);
  }
  visit(rootEntry);
  const entries = [...visited].map(item => entriesByPath.get(item));
  return {
    paths: entries.map(item => item.path).sort(),
    cycles: [...cycles].sort(),
    missing_dependencies: [...missing].sort(),
    ambiguous_dependencies: [...ambiguous].sort(),
    non_direct_recommendations: entries
      .filter(item => !["kotoba-candidate-compile-probe", "closed-project-graph-probe"]
        .includes(item.provisional_recommendation))
      .map(item => ({ path: item.path, recommendation: item.provisional_recommendation }))
      .sort((a, b) => a.path.localeCompare(b.path)),
  };
}

for (const entry of all.filter(
  value => value.provisional_recommendation === "closed-project-graph-probe",
)) {
  const repositoryEntries = byRepository.get(entry.repository);
  const entriesByPath = new Map(repositoryEntries.map(value => [value.path, value]));
  const namespaceIndex = new Map();
  for (const candidate of repositoryEntries) {
    if (!candidate.namespace) continue;
    if (!namespaceIndex.has(candidate.namespace)) namespaceIndex.set(candidate.namespace, []);
    namespaceIndex.get(candidate.namespace).push(candidate.path);
  }
  const internal = [];
  const ambiguous = [];
  const external = [];
  for (const dependency of entry.requires) {
    const matches = namespaceIndex.get(dependency) ?? [];
    if (matches.length === 1) internal.push({ namespace: dependency, path: matches[0] });
    else if (matches.length > 1) ambiguous.push({ namespace: dependency, paths: matches.sort() });
    else external.push(dependency);
  }
  const closure = dependencyClosure(entry, namespaceIndex, entriesByPath);
  graphs.push({
    path: entry.path,
    sha256: entry.sha256,
    repository: entry.repository,
    organization: entry.organization,
    namespace: entry.namespace,
    parse_status: entry.parse_status,
    internal_dependencies: internal.sort((a, b) => a.namespace.localeCompare(b.namespace)),
    ambiguous_dependencies: ambiguous.sort((a, b) => a.namespace.localeCompare(b.namespace)),
    external_dependencies: external.sort(),
    dependency_closure: closure,
    closure_status:
      closure.ambiguous_dependencies.length > 0 ? "ambiguous-namespace" :
      closure.missing_dependencies.length > 0 ? "external-or-missing-dependency" :
      closure.non_direct_recommendations.length > 0 ? "contains-non-direct-migration-work" :
      closure.cycles.length > 0 ? "closed-cycle-requires-project-admission" :
      "closed-and-direct-probe-ready",
    graph_status:
      entry.parse_status !== "parsed" ? "namespace-parse-review" :
      ambiguous.length > 0 ? "ambiguous-in-repository-namespace" :
      external.length > 0 ? "external-host-or-library-dependency" :
      "repository-closed-direct-dependencies",
  });
}
graphs.sort((left, right) => left.path.localeCompare(right.path));
const statusCounts = Object.fromEntries(
  [...new Set(graphs.map(graph => graph.graph_status))].sort().map(status => [
    status,
    graphs.filter(graph => graph.graph_status === status).length,
  ]),
);
const closureStatusCounts = Object.fromEntries(
  [...new Set(graphs.map(graph => graph.closure_status))].sort().map(status => [
    status,
    graphs.filter(graph => graph.closure_status === status).length,
  ]),
);
const result = {
  schema: "kotoba.cljc-project-graphs/v1",
  generated_on: "2026-07-19",
  authority: "provisional-namespace-graph-evidence",
  preclassification_sha256: sha256(inputBytes),
  candidate_paths: graphs.length,
  unique_content_count: new Set(graphs.map(graph => graph.sha256)).size,
  graph_status_counts: statusCounts,
  closure_status_counts: closureStatusCounts,
  graphs,
};
fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o644 });
process.stdout.write(`${JSON.stringify({
  output: path.relative(root, outputPath),
  candidates: result.candidate_paths,
  unique: result.unique_content_count,
  graph_status_counts: result.graph_status_counts,
  closure_status_counts: result.closure_status_counts,
})}\n`);
