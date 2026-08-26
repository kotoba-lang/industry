#!/usr/bin/env node
/**
 * Cross-host tender comparison on fixed kototama fixtures.
 * Emits kotoba.tender-comparison/v1 JSON.
 *
 * Usage:
 *   node scripts/tender-comparison.mjs [--kototama PATH] [--runs N] [--calls N] [--warmup N] [--output PATH]
 */

import { spawnSync } from "node:child_process";
import { readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { cpus, totalmem } from "node:os";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");

function option(name, fallback) {
  const index = process.argv.indexOf(name);
  return index < 0 ? fallback : process.argv[index + 1];
}

function boundedInteger(value, name, maximum) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > maximum) {
    throw new Error(`${name} must be an integer from 1 through ${maximum}`);
  }
  return parsed;
}

function percentile(values, fraction) {
  const ordered = [...values].sort((left, right) => left - right);
  return ordered[Math.ceil(ordered.length * fraction) - 1];
}

function summary(values) {
  return {
    minimum: Math.min(...values),
    median: percentile(values, 0.5),
    p95: percentile(values, 0.95),
    maximum: Math.max(...values),
  };
}

function output(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, encoding: "utf8", timeout: 30_000 });
  return result.status === 0 ? result.stdout.trim() : null;
}

function execute(command, args, options = {}) {
  const started = process.hrtime.bigint();
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    encoding: "utf8",
    env: { ...process.env, ...(options.env ?? {}) },
    maxBuffer: 32 * 1024 * 1024,
    timeout: options.timeout ?? 300_000,
  });
  const wallMilliseconds = Number(process.hrtime.bigint() - started) / 1e6;
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(
      `${command} ${args.join(" ")} failed (${result.status})\n${result.stdout}${result.stderr}`,
    );
  }
  return { stdout: result.stdout, stderr: result.stderr, wallMilliseconds };
}

function parseJsonSample(stdout, expected) {
  const line = stdout.split(/\r?\n/).map(value => value.trim())
    .filter(value => value.startsWith("{")).at(-1);
  if (!line) throw new Error("no sample line in stdout");
  const sample = JSON.parse(line);
  if (sample.format !== "kotoba.tender-sample/v1") throw new Error("bad format");
  if (Number(sample.result) !== expected) {
    throw new Error(`result ${sample.result} != ${expected}`);
  }
  return sample;
}

function wasmNumericResult(value) {
  return typeof value === "bigint" ? Number(value) : Number(value);
}

async function nodeSteadySample(wasmPath, expected, warmup, calls) {
  const wasm = readFileSync(wasmPath);
  const { instance } = await WebAssembly.instantiate(wasm, {});
  const main = instance.exports.main;
  if (typeof main !== "function") throw new Error("guest missing main export");
  const totalInvocations = warmup + calls + 1;
  if (totalInvocations > 500) {
    throw new Error(
      `guest would exceed amu wasm32 instance invocation budget (${totalInvocations} > 500); lower --warmup/--calls`,
    );
  }
  for (let index = 0; index < warmup; index += 1) {
    const value = wasmNumericResult(main());
    if (value !== expected) throw new Error(`warmup result ${value} != ${expected}`);
  }
  const started = process.hrtime.bigint();
  let result = 0;
  for (let index = 0; index < calls; index += 1) {
    result = wasmNumericResult(main());
  }
  const elapsedNanoseconds = Number(process.hrtime.bigint() - started);
  if (result !== expected) throw new Error(`result ${result} != ${expected}`);
  return {
    format: "kotoba.tender-sample/v1",
    host: "node-webassembly",
    calls,
    warmupCalls: warmup,
    elapsedNanoseconds,
    result,
    nanosecondsPerInvocation: elapsedNanoseconds / calls,
  };
}

function chicorySingleInvocation(kototamaRoot, wasmRel, expected) {
  const { wallMilliseconds, stdout } = execute(
    "clojure",
    ["-M:cli", "run", wasmRel],
    { cwd: kototamaRoot, timeout: 600_000 },
  );
  if (!stdout.includes(":ok? true")) throw new Error("chicory CLI run failed");
  if (!stdout.includes(`:result ${expected}`) && !stdout.includes(`:result ${expected},`)) {
    throw new Error(`chicory result not ${expected} in stdout`);
  }
  return { wallMilliseconds };
}

function chicorySteadySample(kototamaRoot, wasmAbs, expected, warmup, calls, steadyScript) {
  const { stdout } = execute(
    "clojure",
    ["-M", steadyScript, wasmAbs, String(warmup), String(calls)],
    { cwd: kototamaRoot, timeout: 600_000 },
  );
  const sample = parseJsonSample(stdout, expected);
  return { ...sample, nanosecondsPerInvocation: sample.elapsedNanoseconds / sample.calls };
}

function wasmtimeSingleInvocation(wasmAbs, expected) {
  const { wallMilliseconds, stdout } = execute(
    "wasmtime",
    ["run", "--invoke", "main", wasmAbs],
    { timeout: 120_000 },
  );
  const trimmed = stdout.trim().split(/\r?\n/).at(-1);
  if (Number(trimmed) !== expected) {
    throw new Error(`wasmtime result ${trimmed} != ${expected}`);
  }
  return { wallMilliseconds };
}

const kototamaRoot = resolve(option("--kototama", join(root, "orgs/kotoba-lang/kototama")));
const runs = boundedInteger(option("--runs", "3"), "--runs", 20);
const calls = boundedInteger(option("--calls", "400"), "--calls", 500);
const warmup = boundedInteger(option("--warmup", "50"), "--warmup", 450);
const outputPath = option("--output", null);
const steadyScript = resolve(here, "tender-chicory-steady.clj");

const guestDefs = [
  {
    id: "kotoba-compiled-fact",
    rel: "test/kototama/fixtures/kotoba-compiled-fact.wasm",
    expected: 120,
    imports: 0,
  },
  {
    id: "kotoba-compiled-peak-cells",
    rel: "test/kototama/fixtures/kotoba-compiled-peak-cells.wasm",
    expected: 240,
    imports: 0,
  },
  {
    id: "amu-compiled-i64-main",
    rel: "test/kototama/fixtures/amu-compiled-i64-main.wasm",
    expected: 42,
    imports: 0,
  },
];

const optionalHosts = [
  { id: "wasmtime-cli", probe: () => output("wasmtime", ["--version"]) !== null },
];

const skippedHosts = {};
for (const host of optionalHosts) {
  if (!host.probe()) skippedHosts[host.id] = "toolchain not installed";
}

const hostLoad = output("sysctl", ["-n", "vm.loadavg"])
  ?? output("uptime", [])
  ?? null;

const reportGuests = {};

for (const guest of guestDefs) {
  const wasmAbs = join(kototamaRoot, guest.rel);
  const wasmRel = guest.rel;
  statSync(wasmAbs);

  const nodeSamples = [];
  for (let run = 0; run < runs; run += 1) {
    nodeSamples.push(await nodeSteadySample(wasmAbs, guest.expected, warmup, calls));
  }

  const chicorySingle = [];
  const chicorySteady = [];
  for (let run = 0; run < runs; run += 1) {
    chicorySingle.push(chicorySingleInvocation(kototamaRoot, wasmRel, guest.expected));
    chicorySteady.push(
      chicorySteadySample(kototamaRoot, wasmAbs, guest.expected, warmup, calls, steadyScript),
    );
  }

  const wasmtimeSingle = [];
  if (!skippedHosts["wasmtime-cli"]) {
    for (let run = 0; run < runs; run += 1) {
      wasmtimeSingle.push(wasmtimeSingleInvocation(wasmAbs, guest.expected));
    }
  }

  const nodeNs = nodeSamples.map(sample => sample.nanosecondsPerInvocation);
  const chicoryNs = chicorySteady.map(sample => sample.nanosecondsPerInvocation);

  reportGuests[guest.id] = {
    fixture: guest.rel,
    expectedResult: guest.expected,
    importCount: guest.imports,
    bytes: statSync(wasmAbs).size,
    hosts: {
      "node-webassembly": {
        role: "browser-engine-parity (V8/Wasm in Node)",
        steadyStateNanosecondsPerInvocation: summary(nodeNs),
        samples: nodeSamples,
      },
      "chicory-jvm": {
        role: "kototama.tender R1 (JVM/Chicory)",
        singleInvocationWallMilliseconds: summary(
          chicorySingle.map(sample => sample.wallMilliseconds),
        ),
        steadyStateNanosecondsPerInvocation: summary(chicoryNs),
        samples: chicorySteady.map(sample => ({
          calls: sample.calls,
          warmupCalls: sample.warmupCalls,
          elapsedNanoseconds: sample.elapsedNanoseconds,
          nanosecondsPerInvocation: sample.nanosecondsPerInvocation,
          result: sample.result,
        })),
      },
      ...(skippedHosts["wasmtime-cli"]
        ? {}
        : {
            "wasmtime-cli": {
              role: "external Wasm engine (process-per-invocation)",
              singleInvocationWallMilliseconds: summary(
                wasmtimeSingle.map(sample => sample.wallMilliseconds),
              ),
              note: "wasmtime run spawns a fresh process each sample; not comparable to in-process steady state",
              samples: wasmtimeSingle,
            },
          }),
    },
    ratios: {
      chicorySteadyVsNodeMedian: summary(chicoryNs).median / summary(nodeNs).median,
      chicorySingleVsWasmtimeMedian: skippedHosts["wasmtime-cli"]
        ? null
        : summary(chicorySingle.map(s => s.wallMilliseconds)).median
          / summary(wasmtimeSingle.map(s => s.wallMilliseconds)).median,
    },
  };
}

const factNodeMedian = reportGuests["kotoba-compiled-fact"].hosts["node-webassembly"]
  .steadyStateNanosecondsPerInvocation.median;
const factChicoryMedian = reportGuests["kotoba-compiled-fact"].hosts["chicory-jvm"]
  .steadyStateNanosecondsPerInvocation.median;

const report = {
  format: "kotoba.tender-comparison/v1",
  contract: {
    guests: guestDefs.map(g => g.id),
    hosts: ["node-webassembly", "chicory-jvm", ...(skippedHosts["wasmtime-cli"] ? [] : ["wasmtime-cli"])],
    runs,
    calls,
    warmupCalls: warmup,
    timing: {
      nodeWebassembly: "in-process WebAssembly.instantiate; reused instance; main() loop after warmup (≤500 invocations/instance for amu fuel budget)",
      chicoryJvmSingle: "subprocess clojure -M:cli run (includes JVM cold start + Chicory instantiate per sample)",
      chicoryJvmSteady: "single JVM; tender/open-session reused; main() loop after warmup",
      wasmtimeCli: "subprocess wasmtime run --invoke main per sample",
    },
    safety: "host-free guests only (zero imports); fuel metering active on Chicory path",
  },
  environment: {
    capturedAt: new Date().toISOString(),
    platform: process.platform,
    architecture: process.arch,
    cpu: cpus()[0]?.model ?? null,
    logicalCpus: cpus().length,
    totalMemoryBytes: totalmem(),
    node: process.version,
    loadavg: hostLoad,
    clojure: output("clojure", ["-Sdescribe"], kototamaRoot),
    wasmtime: output("wasmtime", ["--version"]),
    kototamaRoot,
    kototamaCommit: output("git", ["-C", kototamaRoot, "rev-parse", "HEAD"]),
  },
  skippedHosts,
  guests: reportGuests,
  headline: {
    guest: "kotoba-compiled-fact",
    nodeWebassemblyNanosecondsPerInvocationMedian: factNodeMedian,
    chicoryJvmSteadyNanosecondsPerInvocationMedian: factChicoryMedian,
    chicorySlowdownVsNodeSteady: factChicoryMedian / factNodeMedian,
    chicoryJvmSingleInvocationWallMsMedian: reportGuests["kotoba-compiled-fact"].hosts["chicory-jvm"]
      .singleInvocationWallMilliseconds.median,
  },
};

const encoded = `${JSON.stringify(report, null, 2)}\n`;
if (outputPath) writeFileSync(resolve(outputPath), encoded);
process.stdout.write(encoded);
