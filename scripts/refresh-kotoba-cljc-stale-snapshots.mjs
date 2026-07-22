#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.resolve(import.meta.dirname, "..");
const matrixPath = path.join(root, "90-docs/migration/kotoba-cljc-canonical-admission-matrix.json");
const args = new Set(process.argv.slice(2));
const apply = args.has("--apply");
const limitIndex = process.argv.indexOf("--limit");
const limit = limitIndex >= 0 ? Number(process.argv[limitIndex + 1]) : 20;
const offsetIndex = process.argv.indexOf("--offset");
const offset = offsetIndex >= 0 ? Number(process.argv[offsetIndex + 1]) : 0;
if (!Number.isSafeInteger(limit) || limit < 1 || limit > 200) {
  throw new Error("--limit must be an integer from 1 through 200");
}
if (!Number.isSafeInteger(offset) || offset < 0) {
  throw new Error("--offset must be a non-negative integer");
}

function git(cwd, command, { allowFailure = false } = {}) {
  const result = spawnSync("git", command, { cwd, encoding: "utf8", maxBuffer: 4 * 1024 * 1024 });
  if (!allowFailure && result.status !== 0) {
    throw new Error(`git ${command.join(" ")} failed in ${cwd}: ${(result.stderr || "").trim()}`);
  }
  return { status: result.status, stdout: (result.stdout || "").trim(), stderr: (result.stderr || "").trim() };
}

function normalizeRemote(url) {
  const match = url.match(/github\.com(?::|\/)([^/]+)\/([^/]+?)(?:\.git)?$/i);
  return match ? `${match[1]}/${match[2]}`.toLowerCase() : null;
}

function matchingRemote(cwd, repository) {
  for (const name of git(cwd, ["remote"]).stdout.split("\n").filter(Boolean)) {
    const url = git(cwd, ["remote", "get-url", name], { allowFailure: true }).stdout;
    if (normalizeRemote(url) === repository.toLowerCase()) return name;
  }
  return null;
}

const matrix = JSON.parse(fs.readFileSync(matrixPath, "utf8"));
const stale = matrix.repositories
  .filter(repo => repo.source_snapshot_status === "stale-against-default-branch")
  .slice(offset, offset + limit);
const results = [];

for (const repo of stale) {
  const cwd = path.join(root, repo.representative_checkout);
  const dirty = git(cwd, ["status", "--porcelain=v1", "--untracked-files=all"]).stdout;
  const branch = git(cwd, ["branch", "--show-current"]).stdout;
  const defaultBranch = repo.github.default_branch;
  const remote = matchingRemote(cwd, repo.repository);
  if (dirty) {
    results.push({ repository: repo.repository, status: "skipped-dirty" });
  } else if (!branch) {
    results.push({ repository: repo.repository, status: "skipped-detached" });
  } else if (branch !== defaultBranch) {
    results.push({ repository: repo.repository, status: "skipped-non-default-branch", branch, default_branch: defaultBranch });
  } else if (!remote) {
    results.push({ repository: repo.repository, status: "skipped-no-matching-remote" });
  } else if (!apply) {
    results.push({ repository: repo.repository, status: "eligible", checkout: repo.representative_checkout, remote, branch });
  } else {
    const fetched = git(cwd, ["fetch", "--no-tags", remote, defaultBranch], { allowFailure: true });
    if (fetched.status !== 0) {
      results.push({ repository: repo.repository, status: "skipped-fetch-failed", error: fetched.stderr });
      continue;
    }
    const ancestor = git(cwd, ["merge-base", "--is-ancestor", "HEAD", "FETCH_HEAD"], { allowFailure: true });
    if (ancestor.status !== 0) {
      results.push({ repository: repo.repository, status: "skipped-non-fast-forward" });
      continue;
    }
    const merged = git(cwd, ["merge", "--ff-only", "FETCH_HEAD"], { allowFailure: true });
    if (merged.status !== 0) {
      results.push({ repository: repo.repository, status: "skipped-merge-failed", error: merged.stderr });
      continue;
    }
    const head = git(cwd, ["rev-parse", "HEAD"]).stdout;
    results.push({ repository: repo.repository, status: "refreshed", head });
  }
}

const counts = Object.fromEntries(
  [...new Set(results.map(result => result.status))].sort()
    .map(status => [status, results.filter(result => result.status === status).length]));
process.stdout.write(`${JSON.stringify({ mode: apply ? "apply" : "dry-run", offset, limit, examined: results.length, counts, results }, null, 2)}\n`);
