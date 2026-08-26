#!/usr/bin/env node
/**
 * Capture kototama tender comparison evidence into 90-docs/performance/runs/<date>/.
 *
 * Usage (from superproject root):
 *   node scripts/kotoba-tender-benchmark.mjs [--runs N] [--date YYYY-MM-DD]
 */

import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync, existsSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { cpus, totalmem } from "node:os";

const root = resolve(fileURLToPath(new URL(".", import.meta.url)), "..");
const defaultKototama = join(root, "orgs/kotoba-lang/kototama");
const kototama = resolve(option("--kototama", process.env.KOTOTAMA_ROOT ?? defaultKototama));
if (!existsSync(join(kototama, "deps.edn"))) {
  console.error(
    "kototama checkout missing at",
    kototama,
    "— run `west update --fetch smart kototama` or pass --kototama / set KOTOTAMA_ROOT",
  );
  process.exit(91);
}

function opt(name, fallback) {
  const i = process.argv.indexOf(name);
  return i < 0 ? fallback : process.argv[i + 1];
}

const date = opt("--date", new Date().toISOString().slice(0, 10));
const runs = Number(opt("--runs", "3"));
const outDir = join(root, "90-docs/performance/runs", `${date}-tender`);
mkdirSync(outDir, { recursive: true });

const meta = {
  capturedAt: new Date().toISOString(),
  platform: process.platform,
  arch: process.arch,
  cpus: cpus().length,
  totalMemoryBytes: totalmem(),
  node: process.version,
  loadavg: spawnSync("sysctl", ["-n", "vm.loadavg"], { encoding: "utf8" }).stdout?.trim()
    ?? spawnSync("uptime", [], { encoding: "utf8" }).stdout?.trim(),
  benchmark: "kotoba.tender-comparison/v1",
};

writeFileSync(join(outDir, "host-meta.json"), JSON.stringify(meta, null, 2));

console.log("\n=== tender comparison ===");
const result = spawnSync(
  process.execPath,
  [
    join(root, "scripts/tender-comparison.mjs"),
    "--kototama", kototama,
    "--runs", String(runs),
    "--calls", "400",
    "--warmup", "50",
    "--output", join(outDir, "tender.json"),
  ],
  { cwd: root, encoding: "utf8", stdio: "inherit" },
);
if (result.status !== 0) process.exit(result.status ?? 1);

console.log(`\nEvidence written to ${outDir}`);
