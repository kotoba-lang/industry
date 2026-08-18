#!/usr/bin/env node
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const scopes = new Set(["build", "deploy"]);
const root = path.join(os.tmpdir(), "com-junkawasaki-resource-guard-v1");

function lockPath(scope) {
  return path.join(root, scope);
}

function metadataPath(scope) {
  return path.join(lockPath(scope), "owner.json");
}

function readOwner(scope) {
  try {
    return JSON.parse(fs.readFileSync(metadataPath(scope), "utf8"));
  } catch {
    return null;
  }
}

function alive(pid) {
  if (!Number.isSafeInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error?.code === "EPERM";
  }
}

function clearStale(scope) {
  const owner = readOwner(scope);
  if (owner && alive(owner.pid)) return owner;
  fs.rmSync(lockPath(scope), { recursive: true, force: true });
  return null;
}

function status() {
  fs.mkdirSync(root, { recursive: true });
  const result = {};
  for (const scope of scopes) {
    const owner = clearStale(scope);
    result[scope] = owner ? { active: true, ...owner } : { active: false };
  }
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

function cleanupBrowser(minutesText) {
  const minutes = Number(minutesText);
  if (!Number.isFinite(minutes) || minutes < 60) {
    throw new Error("cleanup-browser requires an age of at least 60 minutes");
  }
  const temporaryRoot = os.tmpdir();
  const cutoff = Date.now() - minutes * 60 * 1000;
  const removed = [];
  for (const entry of fs.readdirSync(temporaryRoot, { withFileTypes: true })) {
    if (!entry.isDirectory() || !entry.name.startsWith("agent-browser-")) continue;
    const target = path.join(temporaryRoot, entry.name);
    const stat = fs.lstatSync(target);
    if (stat.isSymbolicLink() || stat.mtimeMs >= cutoff) continue;
    fs.rmSync(target, { recursive: true, force: true });
    removed.push(entry.name);
  }
  process.stdout.write(`${JSON.stringify({ temporaryRoot, minimumAgeMinutes: minutes, removed })}\n`);
}

function run(scope, command) {
  if (!scopes.has(scope) || command.length === 0) {
    throw new Error("usage: resource-guard.mjs run <build|deploy> -- <command> [args...]");
  }
  fs.mkdirSync(root, { recursive: true });
  const current = clearStale(scope);
  if (current) {
    process.stderr.write(
      `resource-guard: ${scope} is already running (pid=${current.pid}, repo=${current.cwd}, started=${current.startedAt})\n`,
    );
    process.exit(2);
  }
  try {
    fs.mkdirSync(lockPath(scope));
  } catch (error) {
    if (error?.code === "EEXIST") {
      const owner = readOwner(scope);
      process.stderr.write(`resource-guard: ${scope} lock is already held${owner ? ` (pid=${owner.pid})` : ""}\n`);
      process.exit(2);
    }
    throw error;
  }
  const owner = { pid: process.pid, startedAt: new Date().toISOString(), cwd: process.cwd(), scope };
  fs.writeFileSync(metadataPath(scope), `${JSON.stringify(owner)}\n`, { flag: "wx" });
  try {
    const result = spawnSync(command[0], command.slice(1), { stdio: "inherit", shell: false });
    if (result.error) throw result.error;
    process.exitCode = result.status ?? 1;
  } finally {
    fs.rmSync(lockPath(scope), { recursive: true, force: true });
  }
}

const [action, scope, separator, ...command] = process.argv.slice(2);
if (action === "status" && scope === undefined) status();
else if (action === "cleanup-browser" && scope !== undefined && separator === undefined)
  cleanupBrowser(scope);
else if (action === "run" && separator === "--") run(scope, command);
else throw new Error("usage: resource-guard.mjs status | cleanup-browser <minutes>=60+ | run <build|deploy> -- <command> [args...]");
