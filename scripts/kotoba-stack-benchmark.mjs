#!/usr/bin/env node
/**
 * Capture kotoba-stack performance evidence into 90-docs/performance/runs/<date>/.
 * Wraps amu's benchmark-runtime and benchmark-compile; records host load.
 *
 * Usage (from superproject root):
 *   node scripts/kotoba-stack-benchmark.mjs [--runs N] [--date YYYY-MM-DD]
 */

import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { cpus, totalmem } from "node:os";

const root = resolve(fileURLToPath(new URL(".", import.meta.url)), "..");
const amu = join(root, "orgs/kotoba-lang/amu");

function opt(name, fallback) {
  const i = process.argv.indexOf(name);
  return i < 0 ? fallback : process.argv[i + 1];
}

const date = opt("--date", new Date().toISOString().slice(0, 10));
const runs = Number(opt("--runs", "5"));
const outDir = join(root, "90-docs/performance/runs", date);
mkdirSync(outDir, { recursive: true });

function sh(cmd, args, cwd) {
  const r = spawnSync(cmd, args, { cwd, encoding: "utf8", stdio: "inherit" });
  if (r.status !== 0) process.exit(r.status ?? 1);
}

const meta = {
  capturedAt: new Date().toISOString(),
  platform: process.platform,
  arch: process.arch,
  cpus: cpus().length,
  totalMemoryBytes: totalmem(),
  node: process.version,
  loadavg: spawnSync("sysctl", ["-n", "vm.loadavg"], { encoding: "utf8" }).stdout?.trim()
    ?? spawnSync("uptime", [], { encoding: "utf8" }).stdout?.trim(),
};

writeFileSync(join(outDir, "host-meta.json"), JSON.stringify(meta, null, 2));

console.log("\n=== runtime comparison ===");
sh("node", [
  "scripts/runtime-comparison.mjs",
  "--runs", String(runs),
  "--calls", "100000",
  "--warmup", "10000",
  "--n", "200",
  "--output", join(outDir, "runtime.json"),
], amu);

console.log("\n=== compile baseline ===");
sh("node", [
  "scripts/performance-baseline.mjs",
  "--runs", String(Math.min(runs, 5)),
  "--output", join(outDir, "compile.json"),
], amu);

console.log(`\nEvidence written to ${outDir}`);
