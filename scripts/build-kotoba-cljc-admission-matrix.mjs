import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const owners = ["etzhayyim", "kotoba-lang", "gftdcojp", "cloud-itonami", "com-junkawasaki"];
const outputPath = path.join(root, "90-docs/migration/kotoba-cljc-canonical-admission-matrix.json");
const withGitHub = process.argv.includes("--github");

function run(command, args, cwd = root, options = {}) {
  const result = spawnSync(command, args, {
    cwd, encoding: "utf8", maxBuffer: 128 * 1024 * 1024, ...options,
  });
  if (result.error) throw result.error;
  return result;
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function normalizeRemote(value) {
  const match = value.trim().match(/github\.com(?::|\/)([^/]+)\/([^/]+?)(?:\.git)?\/?$/i);
  return match ? `${match[1].toLowerCase()}/${match[2].toLowerCase()}` : null;
}

function codeOnly(source) {
  const output = [];
  let string = false, escaped = false, comment = false;
  for (const character of source) {
    if (comment) {
      if (character === "\n") { comment = false; output.push(character); } else output.push(" ");
    } else if (string) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === '"') string = false;
      output.push(character === "\n" ? "\n" : " ");
    } else if (character === ";") { comment = true; output.push(" "); }
    else if (character === '"') { string = true; output.push(" "); }
    else output.push(character);
  }
  return output.join("");
}

const signalPatterns = {
  namespace_dependency: /\(\s*:require\b/m,
  reader_conditional: /#\?(?:@)?\s*\(/m,
  jvm_interop: /(?:java\.|javax\.|System\/|Thread\/|Class\/forName|clojure\.java\.)/m,
  js_interop: /(?:^|[^\w-])(?:js\/|goog\.|cljs\.js|clj->js|js->clj)/m,
  io_process: /\b(?:slurp|spit|future|pmap)\b|clojure\.java\.(?:io|shell)/m,
  network_database: /(?:https?\b|fetch\b|socket|jdbc|datasource|database|datomic|xtdb|sql\b)/im,
  macro_definition: /\(\s*defmacro\b/m,
  protocol_type: /\(\s*(?:defprotocol|deftype|defrecord|extend-type|reify)\b/m,
  mutation: /\(\s*(?:atom|swap!|reset!|set!|alter-var-root|volatile!)\b/m,
  higher_order: /\(\s*(?:map|mapv|reduce|filter|remove|keep|group-by|sort-by|apply|partial|comp)\b/m,
  exception_control: /\(\s*(?:try|catch|finally|throw|ex-info)\b/m,
  dynamic_evaluation: /\(\s*(?:eval|load-string|read-string|requiring-resolve)\b/m,
};

function classify(relativePath, source) {
  const code = codeOnly(source);
  const signals = Object.entries(signalPatterns)
    .filter(([, pattern]) => pattern.test(code)).map(([name]) => name).sort();
  const isTest = /(?:^|\/)(?:test|tests|fixtures?|test-resources)(?:\/|$)/.test(relativePath);
  const generated = /(?:^|\/)(?:generated|target|build)(?:\/|$)/.test(relativePath)
    || /(?:generated file|do not edit)/i.test(source.slice(0, 1024));
  let classification;
  if (isTest) classification = "test-only";
  else if (generated) classification = "generated";
  else if (signals.includes("dynamic_evaluation")) classification = "security-host-bound";
  else if (signals.includes("macro_definition")) classification = "macro-compiler-time";
  else if (["jvm_interop", "js_interop", "io_process"].some(value => signals.includes(value))) classification = "host-adapter";
  else if (signals.includes("network_database")) classification = "capability-bound";
  else if (["namespace_dependency", "reader_conditional", "protocol_type", "mutation", "higher_order", "exception_control"]
    .some(value => signals.includes(value))) classification = "portable-refactor-required";
  else classification = "portable-direct-candidate";
  const namespace = /\(ns\s+([^\s()[\]{}";]+)/m.exec(source)?.[1] ?? null;
  return { classification, namespace, signals };
}

function gitRoots() {
  const scan = run("find", owners.map(owner => `orgs/${owner}`).flatMap(scope => [scope])
    .concat(["-name", ".git", "-print", "-prune"]));
  if (scan.status !== 0) throw new Error(scan.stderr);
  return scan.stdout.trim().split("\n").filter(Boolean).map(value => path.dirname(value)).sort();
}

function remoteFor(checkout) {
  const names = run("git", ["remote"], checkout).stdout.trim().split("\n").filter(Boolean);
  const preferred = names.includes("origin") ? "origin" : names[0];
  if (!preferred) return null;
  const url = run("git", ["remote", "get-url", preferred], checkout);
  return url.status === 0 ? { name: preferred, identity: normalizeRemote(url.stdout) } : null;
}

const checkouts = [];
for (const relativeCheckout of gitRoots()) {
  const absolute = path.join(root, relativeCheckout);
  const remote = remoteFor(absolute);
  if (!remote?.identity || !owners.includes(remote.identity.split("/")[0])) continue;
  const tracked = run("git", ["ls-files", "*.cljc"], absolute).stdout.trim().split("\n").filter(Boolean);
  if (tracked.length === 0) continue;
  const materialized = tracked.filter(value => fs.existsSync(path.join(absolute, value)));
  const head = run("git", ["rev-parse", "HEAD"], absolute).stdout.trim();
  const branch = run("git", ["branch", "--show-current"], absolute).stdout.trim() || null;
  checkouts.push({ checkout: relativeCheckout, remote: remote.identity, remote_name: remote.name,
    tracked_cljc_count: tracked.length, materialized_cljc_count: materialized.length,
    worktree_complete: materialized.length === tracked.length, tracked, materialized, head, branch });
}

const groups = new Map();
for (const checkout of checkouts) {
  if (!groups.has(checkout.remote)) groups.set(checkout.remote, []);
  groups.get(checkout.remote).push(checkout);
}

const repositories = [...groups].sort(([a], [b]) => a.localeCompare(b)).map(([identity, copies]) => {
  copies.sort((a, b) => Number(b.worktree_complete) - Number(a.worktree_complete)
    || b.materialized_cljc_count - a.materialized_cljc_count
    || b.tracked_cljc_count - a.tracked_cljc_count || a.checkout.localeCompare(b.checkout));
  const representative = copies[0];
  const namespaces = representative.materialized.map(relativePath => {
    const source = fs.readFileSync(path.join(root, representative.checkout, relativePath), "utf8");
    return { path: relativePath, sha256: sha256(source), bytes: Buffer.byteLength(source),
      ...classify(relativePath, source) };
  });
  const classification_counts = Object.fromEntries([...new Set(namespaces.map(value => value.classification))]
    .sort().map(value => [value, namespaces.filter(item => item.classification === value).length]));
  const production = namespaces.filter(value => !["test-only", "generated"].includes(value.classification));
  const priority = ["security-host-bound", "macro-compiler-time", "host-adapter", "capability-bound",
    "portable-refactor-required", "portable-direct-candidate"];
  const dominant = priority.find(value => production.some(item => item.classification === value)) ?? "test-only";
  const pilot = identity === "kotoba-lang/annotation" || identity === "kotoba-lang/cartpole-math";
  return {
    repository: identity,
    organization: identity.split("/")[0],
    representative_checkout: representative.checkout,
    duplicate_checkout_count: copies.length,
    local_head: representative.head,
    local_branch: representative.branch,
    source_snapshot_status: representative.worktree_complete ? "complete" : "incomplete-worktree",
    github: { status: "unverified", default_branch: null, is_archived: null, is_fork: null, remote_head: null },
    tracked_cljc_count: representative.tracked_cljc_count,
    materialized_cljc_count: namespaces.length,
    production_cljc_count: production.length,
    classification_counts,
    disposition: pilot ? "pilot-migrated-remote-head-recheck" : dominant,
    namespaces,
  };
});

function githubFacts(items) {
  for (let offset = 0; offset < items.length; offset += 40) {
    const batch = items.slice(offset, offset + 40);
    const fields = batch.map((repository, index) => {
      const [owner, name] = repository.repository.split("/");
      return `r${index}:repository(owner:${JSON.stringify(owner)},name:${JSON.stringify(name)}){nameWithOwner isArchived isFork defaultBranchRef{name target{... on Commit{oid}}}}`;
    }).join("\n");
    const query = `query{${fields}}`;
    const response = run("gh", ["api", "graphql", "-f", `query=${query}`]);
    if (!response.stdout.trim()) throw new Error(`GitHub metadata batch failed: ${response.stderr}`);
    const payload = JSON.parse(response.stdout);
    const data = payload.data ?? {};
    batch.forEach((repository, index) => {
      const value = data[`r${index}`];
      repository.github = value ? {
        status: "verified",
        default_branch: value.defaultBranchRef?.name ?? null,
        is_archived: value.isArchived,
        is_fork: value.isFork,
        remote_head: value.defaultBranchRef?.target?.oid ?? null,
      } : { status: "not-found-or-inaccessible", default_branch: null, is_archived: null, is_fork: null, remote_head: null };
    });
    process.stderr.write(`GitHub metadata ${Math.min(offset + batch.length, items.length)}/${items.length}\n`);
  }
}

if (withGitHub) githubFacts(repositories);

for (const repository of repositories) {
  const github = repository.github;
  const localComplete = repository.source_snapshot_status === "complete";
  repository.source_snapshot_status = !localComplete ? "incomplete-worktree"
    : github.status !== "verified" ? "github-unverified"
    : github.remote_head !== repository.local_head ? "stale-against-default-branch"
    : "current-default-branch";
  if (github.is_archived) repository.disposition = "exclude-archived-review";
  else if (github.is_fork) repository.disposition = "fork-upstream-authority-review";
  else if (repository.source_snapshot_status !== "current-default-branch") {
    repository.disposition = "fresh-clone-required";
  }
}

const classificationCounts = {};
for (const repository of repositories) for (const [classification, count] of Object.entries(repository.classification_counts)) {
  classificationCounts[classification] = (classificationCounts[classification] ?? 0) + count;
}
const githubCounts = Object.fromEntries([...new Set(repositories.map(value => value.github.status))].sort()
  .map(status => [status, repositories.filter(value => value.github.status === status).length]));
const result = {
  schema: "kotoba.cljc-canonical-admission-matrix/v1",
  generated_on: "2026-07-20",
  authority: withGitHub ? "tracked-local-source-plus-live-github-repository-metadata" : "tracked-local-source",
  owners,
  canonical_repository_count: repositories.length,
  local_checkout_count: checkouts.length,
  tracked_cljc_count: repositories.reduce((sum, value) => sum + value.tracked_cljc_count, 0),
  production_cljc_count: repositories.reduce((sum, value) => sum + value.production_cljc_count, 0),
  duplicate_checkout_count: checkouts.length - repositories.length,
  classification_counts: Object.fromEntries(Object.entries(classificationCounts).sort()),
  github_status_counts: githubCounts,
  source_snapshot_status_counts: Object.fromEntries(
    [...new Set(repositories.map(value => value.source_snapshot_status))].sort().map(status => [
      status, repositories.filter(value => value.source_snapshot_status === status).length,
    ]),
  ),
  completion_rule: "Every production namespace requires a reviewed disposition and remote-head-fresh compiler/host/runtime evidence before extension change.",
  repositories,
};
fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o644 });
process.stdout.write(`${JSON.stringify({ output: path.relative(root, outputPath), repositories: result.canonical_repository_count,
  tracked_cljc: result.tracked_cljc_count, production_cljc: result.production_cljc_count,
  duplicates: result.duplicate_checkout_count, classifications: result.classification_counts,
  github: result.github_status_counts })}\n`);
