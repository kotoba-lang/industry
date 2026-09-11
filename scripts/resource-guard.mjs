#!/usr/bin/env node
// Serialises the expensive scopes (build, deploy) across every agent session on
// this machine. The contract callers rely on:
//
//   held by a live owner  -> exit 2, the command is NOT run, and stderr carries
//                            both a `REFUSED <scope>-lock held by ...` line and
//                            the legacy `... is already running ...` wording
//                            that scripts/itonami-fleet/ship.sh and
//                            scripts/maturity-loop/run.cljk grep for.
//   dead owner            -> the stale lock is reclaimed and the command runs.
//   the command ran       -> the child's status is propagated verbatim,
//                            signals as 128+n.
//   the command could not be spawned -> 127, never 0 and never a child status.
//
// The last three lines exist because "could not run" must never be reportable
// as "ran and succeeded" (CLAUDE.md, 検査を書く前・緑を信じる前の 6 問). Two
// measured defects are fixed here: a signal-terminated child reported 1, which
// is indistinguishable from an ordinary failure and loses which signal fired;
// and a lock directory that existed without owner.json was treated as stale, so
// a second guard deleted a live owner's lock and ran concurrently. Acquisition
// is now a rename of a fully populated staging directory, so a lock directory
// never becomes observable in a half-created state.
//
// Proof: scripts/resource-guard-test.cljk (nbb; spawns this file for real).
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const scopes = new Set(["build", "deploy"]);
const root = path.join(os.tmpdir(), "com-junkawasaki-resource-guard-v1");

// A lock directory whose owner.json cannot be read is only reclaimed once it is
// older than this. Younger than it, assume a peer is mid-acquisition (or is an
// older copy of this script, which did have a mkdir->write window) and refuse
// rather than steal a lock that may be live.
const unreadableOwnerGraceMs = 60_000;

function lockPath(scope) {
  return path.join(root, scope);
}

function metadataPath(scope) {
  return path.join(lockPath(scope), "owner.json");
}

function readOwner(scope) {
  try {
    const parsed = JSON.parse(fs.readFileSync(metadataPath(scope), "utf8"));
    return parsed && typeof parsed === "object" ? parsed : null;
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

function lockAgeMs(scope) {
  try {
    return Date.now() - fs.statSync(lockPath(scope)).mtimeMs;
  } catch {
    return Number.POSITIVE_INFINITY;
  }
}

// free | held (a live owner, or an owner we cannot read yet) | stale (reclaimable).
function inspect(scope) {
  if (!fs.existsSync(lockPath(scope))) return { state: "free" };
  const owner = readOwner(scope);
  if (owner && alive(owner.pid)) return { state: "held", owner };
  if (owner) return { state: "stale", owner, reason: `owner pid ${owner.pid} is gone` };
  const ageMs = lockAgeMs(scope);
  if (ageMs < unreadableOwnerGraceMs) return { state: "held", owner: null, ageMs };
  return { state: "stale", owner: null, reason: `owner metadata unreadable for ${Math.round(ageMs / 1000)}s` };
}

function reclaimStale(scope) {
  const found = inspect(scope);
  if (found.state === "stale") fs.rmSync(lockPath(scope), { recursive: true, force: true });
  return found;
}

// Populate a staging directory first, then rename it into place: the lock is
// atomically either absent or complete, so no peer can observe it half-created.
function acquire(scope) {
  const staging = path.join(root, `.acquire-${scope}-${process.pid}-${Math.random().toString(36).slice(2)}`);
  const owner = { pid: process.pid, startedAt: new Date().toISOString(), cwd: process.cwd(), scope };
  fs.mkdirSync(staging, { recursive: true });
  try {
    fs.writeFileSync(path.join(staging, "owner.json"), `${JSON.stringify(owner)}\n`, { flag: "wx" });
    fs.renameSync(staging, lockPath(scope));
    return owner;
  } catch (error) {
    fs.rmSync(staging, { recursive: true, force: true });
    if (["EEXIST", "ENOTEMPTY", "EACCES", "EPERM"].includes(error?.code)) return null;
    throw error;
  }
}

// Never delete a lock that is no longer ours.
function release(scope, owner) {
  const current = readOwner(scope);
  if (current && current.pid !== owner.pid) return;
  fs.rmSync(lockPath(scope), { recursive: true, force: true });
}

function refuse(scope, found) {
  const owner = found?.owner;
  const pid = owner?.pid ?? "unknown";
  const cwd = owner?.cwd ?? "unknown";
  const since = owner?.startedAt ?? "unknown";
  process.stderr.write(`REFUSED ${scope}-lock held by pid=${pid} cwd=${cwd} since=${since}\n`);
  // Legacy wording. scripts/itonami-fleet/ship.sh and scripts/maturity-loop/run.cljk
  // wait for the lock by grepping for "already running"; dropping it would turn
  // their wait into a give-up.
  process.stderr.write(
    `resource-guard: ${scope} is already running (pid=${pid}, repo=${cwd}, started=${since})\n`,
  );
  process.exitCode = 2;
}

function status() {
  fs.mkdirSync(root, { recursive: true });
  const result = {};
  for (const scope of scopes) {
    const found = reclaimStale(scope);
    if (found.state === "held" && found.owner) result[scope] = { active: true, ...found.owner };
    else if (found.state === "held") result[scope] = { active: true, owner: "unreadable", ageMs: found.ageMs };
    else result[scope] = { active: false };
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

// A child that died on a signal has status === null. Reporting 1 there loses
// both the signal and the distinction from an ordinary failing build, so use
// the shell's 128+n convention.
function childExitCode(result) {
  if (result.signal) {
    const number = os.constants.signals[result.signal];
    return Number.isInteger(number) ? 128 + number : 1;
  }
  return Number.isInteger(result.status) ? result.status : 1;
}

function run(scope, command) {
  if (!scopes.has(scope) || command.length === 0) {
    throw new Error("usage: resource-guard.mjs run <build|deploy> -- <command> [args...]");
  }
  fs.mkdirSync(root, { recursive: true });
  const found = reclaimStale(scope);
  if (found.state === "held") {
    refuse(scope, found);
    return;
  }
  const owner = acquire(scope);
  if (!owner) {
    // Lost the race between inspect and rename; whoever won holds it now.
    refuse(scope, inspect(scope));
    return;
  }
  try {
    const result = spawnSync(command[0], command.slice(1), { stdio: "inherit", shell: false });
    if (result.error) {
      // Could not start the command at all. 127 is the shell's convention and
      // cannot be confused with any status the command itself could return.
      process.stderr.write(`resource-guard: cannot run ${command[0]}: ${result.error.message}\n`);
      process.exitCode = 127;
      return;
    }
    process.exitCode = childExitCode(result);
  } finally {
    release(scope, owner);
  }
}

const [action, scope, separator, ...command] = process.argv.slice(2);
if (action === "status" && scope === undefined) status();
else if (action === "cleanup-browser" && scope !== undefined && separator === undefined)
  cleanupBrowser(scope);
else if (action === "run" && separator === "--") run(scope, command);
else throw new Error("usage: resource-guard.mjs status | cleanup-browser <minutes>=60+ | run <build|deploy> -- <command> [args...]");
