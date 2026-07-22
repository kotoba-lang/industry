#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.resolve(import.meta.dirname, "..");
const matrix = JSON.parse(fs.readFileSync(
  path.join(root, "90-docs/migration/kotoba-cljc-canonical-admission-matrix.json"), "utf8"));
const apply = process.argv.includes("--apply");
function numberOption(name, fallback, maximum = Number.MAX_SAFE_INTEGER) {
  const index = process.argv.indexOf(name);
  const value = index < 0 ? fallback : Number(process.argv[index + 1]);
  if (!Number.isSafeInteger(value) || value < 0 || value > maximum) {
    throw new Error(`${name} must be an integer from 0 through ${maximum}`);
  }
  return value;
}
const offset = numberOption("--offset", 0);
const limit = numberOption("--limit", 10, 20);
if (limit < 1) throw new Error("--limit must be at least 1");

function run(command, args, cwd = root, allowFailure = false) {
  const result = spawnSync(command, args, { cwd, encoding: "utf8", maxBuffer: 8 * 1024 * 1024 });
  if (!allowFailure && result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed: ${(result.stderr || "").trim()}`);
  }
  return { status: result.status, stdout: (result.stdout || "").trim(), stderr: (result.stderr || "").trim() };
}

function normalizedRemote(url) {
  const match = url.match(/github\.com(?::|\/)([^/]+)\/([^/]+?)(?:\.git)?$/i);
  return match ? `${match[1]}/${match[2]}`.toLowerCase() : null;
}

function sourceRemote(repo) {
  const checkout = path.join(root, repo.representative_checkout);
  for (const name of run("git", ["remote"], checkout).stdout.split("\n").filter(Boolean)) {
    const url = run("git", ["remote", "get-url", name], checkout, true).stdout;
    if (normalizedRemote(url) === repo.repository.toLowerCase()) return url;
  }
  return null;
}

const candidates = matrix.repositories
  .filter(repo => repo.source_snapshot_status === "stale-against-default-branch")
  .slice(offset, offset + limit);
const results = [];
for (const repo of candidates) {
  const [owner, name] = repo.repository.split("/");
  const relativeTarget = path.join("orgs", owner, ".kotoba-fresh", name);
  const target = path.join(root, relativeTarget);
  const remote = sourceRemote(repo);
  if (!remote) {
    results.push({ repository: repo.repository, status: "skipped-no-matching-remote" });
  } else if (fs.existsSync(target)) {
    results.push({ repository: repo.repository, status: "skipped-target-exists", target: relativeTarget });
  } else if (!apply) {
    results.push({ repository: repo.repository, status: "eligible", target: relativeTarget,
      default_branch: repo.github.default_branch, remote_head: repo.github.remote_head });
  } else {
    fs.mkdirSync(path.dirname(target), { recursive: true });
    const cloned = run("git", ["clone", "--depth", "1", "--single-branch", "--branch",
      repo.github.default_branch, "--filter=blob:none", remote, target], root, true);
    if (cloned.status !== 0) {
      results.push({ repository: repo.repository, status: "clone-failed", error: cloned.stderr });
      continue;
    }
    const head = run("git", ["rev-parse", "HEAD"], target).stdout;
    const dirty = run("git", ["status", "--porcelain=v1", "--untracked-files=all"], target).stdout;
    if (head !== repo.github.remote_head || dirty) {
      results.push({ repository: repo.repository, status: "verification-failed", target: relativeTarget, head,
        expected_head: repo.github.remote_head, dirty: Boolean(dirty) });
      continue;
    }
    results.push({ repository: repo.repository, status: "materialized", target: relativeTarget, head });
  }
}
const counts = Object.fromEntries([...new Set(results.map(value => value.status))].sort()
  .map(status => [status, results.filter(value => value.status === status).length]));
process.stdout.write(`${JSON.stringify({ mode: apply ? "apply" : "dry-run", offset, limit,
  examined: results.length, counts, results }, null, 2)}\n`);
