import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const defaultMatrix = path.join(root, "90-docs/migration/kotoba-cljc-canonical-admission-matrix.json");

function argument(name, fallback = null) {
  const index = process.argv.indexOf(name);
  return index < 0 ? fallback : process.argv[index + 1];
}
function repeated(name) {
  const values = [];
  for (let index = 0; index < process.argv.length; index += 1) {
    if (process.argv[index] === name) values.push(process.argv[index + 1]);
  }
  return values;
}
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, encoding: "utf8", maxBuffer: 128 * 1024 * 1024 });
  if (result.error) throw result.error;
  return result;
}
function checked(command, args, cwd = root) {
  const result = run(command, args, cwd);
  if (result.status !== 0) throw new Error(`${command} ${args.join(" ")} failed: ${result.stderr}`);
  return result.stdout.trim();
}
function sha256(value) { return createHash("sha256").update(value).digest("hex"); }
function normalizeRemote(value) {
  const match = value.trim().match(/github\.com(?::|\/)([^/]+)\/([^/]+?)(?:\.git)?\/?$/i);
  return match ? `${match[1].toLowerCase()}/${match[2].toLowerCase()}` : null;
}

const matrixPath = path.resolve(argument("--matrix", defaultMatrix));
const outputPath = path.resolve(argument("--output", matrixPath));
const refreshOnly = process.argv.includes("--refresh-only");
const retirements = repeated("--retire").map(value => {
  const separator = value.indexOf("=");
  if (separator < 1) throw new Error("--retire requires owner/repository=checkout/path");
  return { repository: value.slice(0, separator).toLowerCase(), checkout: value.slice(separator + 1) };
});
if (retirements.length === 0 && !refreshOnly) {
  throw new Error("pass one or more --retire owner/repository=checkout/path, or --refresh-only");
}
if (retirements.length > 0 && refreshOnly) throw new Error("--refresh-only cannot be combined with --retire");
if (new Set(retirements.map(value => value.repository)).size !== retirements.length) {
  throw new Error("each retired repository must be unique");
}

const baselineBytes = fs.readFileSync(matrixPath);
const matrix = JSON.parse(baselineBytes);
if (matrix.schema !== "kotoba.cljc-canonical-admission-matrix/v1") throw new Error("unsupported matrix schema");
const byRepository = new Map(matrix.repositories.map(value => [value.repository.toLowerCase(), value]));

const qualified = [];
for (const retirement of retirements) {
  const prior = byRepository.get(retirement.repository);
  if (!prior) throw new Error(`${retirement.repository} is not present in the baseline matrix`);
  const checkout = path.resolve(root, retirement.checkout);
  const relative = path.relative(root, checkout);
  if (relative.startsWith("..") || path.isAbsolute(relative)) throw new Error(`${checkout} is outside the workspace`);
  if (!fs.existsSync(path.join(checkout, ".git"))) throw new Error(`${checkout} is not a Git checkout`);
  const remoteNames = checked("git", ["remote"], checkout).split("\n").filter(Boolean);
  const remoteName = remoteNames.includes("origin") ? "origin" : remoteNames[0];
  if (!remoteName) throw new Error(`${checkout} has no remote`);
  const remote = normalizeRemote(checked("git", ["remote", "get-url", remoteName], checkout));
  if (remote !== retirement.repository) throw new Error(`${checkout} remote ${remote} does not match ${retirement.repository}`);
  if (checked("git", ["status", "--porcelain"], checkout) !== "") throw new Error(`${checkout} is not clean`);
  const head = checked("git", ["rev-parse", "HEAD"], checkout);
  const branch = checked("git", ["branch", "--show-current"], checkout);
  const trackedCljc = checked("git", ["ls-files", "*.cljc"], checkout).split("\n").filter(Boolean);
  if (trackedCljc.length !== 0) throw new Error(`${retirement.repository} still has ${trackedCljc.length} tracked CLJC files`);
  const metadata = JSON.parse(checked("gh", ["api", `repos/${retirement.repository}`]));
  if (metadata.archived) throw new Error(`${retirement.repository} is archived`);
  if (metadata.fork) throw new Error(`${retirement.repository} is a fork`);
  if (branch !== metadata.default_branch) throw new Error(`${retirement.repository} checkout branch ${branch} is not ${metadata.default_branch}`);
  const remoteHead = checked("gh", ["api", `repos/${retirement.repository}/commits/${metadata.default_branch}`, "--jq", ".sha"]);
  if (head !== remoteHead) throw new Error(`${retirement.repository} checkout ${head} is not live head ${remoteHead}`);
  qualified.push({ repository: retirement.repository, checkout: relative, head,
    removed_local_checkouts: prior.duplicate_checkout_count,
    removed_tracked_cljc: prior.tracked_cljc_count,
    removed_production_cljc: prior.production_cljc_count });
}

const retired = new Set(qualified.map(value => value.repository));
const repositories = matrix.repositories.filter(value => !retired.has(value.repository.toLowerCase()));

for (let offset = 0; offset < repositories.length; offset += 40) {
  const batch = repositories.slice(offset, offset + 40);
  const fields = batch.map((repository, index) => {
    const [owner, name] = repository.repository.split("/");
    return `r${index}:repository(owner:${JSON.stringify(owner)},name:${JSON.stringify(name)}){isArchived isFork defaultBranchRef{name target{... on Commit{oid}}}}`;
  }).join("\n");
  const response = run("gh", ["api", "graphql", "-f", `query=query{${fields}}`]);
  if (!response.stdout.trim()) throw new Error(`GitHub metadata batch failed: ${response.stderr}`);
  const payload = JSON.parse(response.stdout);
  batch.forEach((repository, index) => {
    const value = payload.data?.[`r${index}`];
    repository.github = value ? { status: "verified", default_branch: value.defaultBranchRef?.name ?? null,
      is_archived: value.isArchived, is_fork: value.isFork,
      remote_head: value.defaultBranchRef?.target?.oid ?? null }
      : { status: "not-found-or-inaccessible", default_branch: null, is_archived: null, is_fork: null, remote_head: null };
    const complete = repository.source_snapshot_status !== "incomplete-worktree";
    repository.source_snapshot_status = !complete ? "incomplete-worktree"
      : repository.github.status !== "verified" ? "github-unverified"
      : repository.github.remote_head !== repository.local_head ? "stale-against-default-branch"
      : "current-default-branch";
    if (repository.github.is_archived) repository.disposition = "exclude-archived-review";
    else if (repository.github.is_fork) repository.disposition = "fork-upstream-authority-review";
    else if (repository.source_snapshot_status !== "current-default-branch") repository.disposition = "fresh-clone-required";
  });
  process.stderr.write(`GitHub metadata ${Math.min(offset + batch.length, repositories.length)}/${repositories.length}\n`);
}

function counts(key) {
  return Object.fromEntries([...new Set(repositories.map(value => value[key]))].sort()
    .map(status => [status, repositories.filter(value => value[key] === status).length]));
}
const classificationCounts = {};
for (const repository of repositories) for (const [classification, count] of Object.entries(repository.classification_counts)) {
  classificationCounts[classification] = (classificationCounts[classification] ?? 0) + count;
}
const localCheckoutCount = matrix.local_checkout_count
  - qualified.reduce((sum, value) => sum + value.removed_local_checkouts, 0);
const result = { ...matrix,
  generated_on: new Date().toISOString().slice(0, 10),
  authority: "verified-retirement-delta-plus-live-github-repository-metadata",
  canonical_repository_count: repositories.length,
  local_checkout_count: localCheckoutCount,
  tracked_cljc_count: repositories.reduce((sum, value) => sum + value.tracked_cljc_count, 0),
  production_cljc_count: repositories.reduce((sum, value) => sum + value.production_cljc_count, 0),
  duplicate_checkout_count: localCheckoutCount - repositories.length,
  classification_counts: Object.fromEntries(Object.entries(classificationCounts).sort()),
  github_status_counts: Object.fromEntries([...new Set(repositories.map(value => value.github.status))].sort()
    .map(status => [status, repositories.filter(value => value.github.status === status).length])),
  source_snapshot_status_counts: counts("source_snapshot_status"),
  incremental_retirement: { baseline_sha256: sha256(baselineBytes), qualified },
  repositories,
};
fs.writeFileSync(outputPath, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o644 });
process.stdout.write(`${JSON.stringify({ output: path.relative(root, outputPath), retired: qualified,
  repositories: result.canonical_repository_count, tracked_cljc: result.tracked_cljc_count,
  production_cljc: result.production_cljc_count, duplicates: result.duplicate_checkout_count,
  classifications: result.classification_counts, github: result.github_status_counts,
  snapshots: result.source_snapshot_status_counts })}\n`);
