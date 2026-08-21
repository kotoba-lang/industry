import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const organizations = ["etzhayyim", "kotoba-lang", "gftdcojp", "cloud-itonami", "com-junkawasaki"];
const scopes = organizations.map(name => `orgs/${name}`);
const scan = spawnSync("rg", ["--files", ...scopes, "-g", "*.cljc", "-g", "!.git/**"], {
  cwd: root, encoding: "utf8", maxBuffer: 64 * 1024 * 1024
});
if (scan.status !== 0 && scan.status !== 1) {
  process.stderr.write(scan.stderr);
  process.exit(scan.status ?? 1);
}

const files = scan.stdout.split("\n").filter(Boolean).sort();
const grouped = new Map();
for (const file of files) {
  const parts = file.split("/");
  if (parts.length < 4 || parts[0] !== "orgs") throw new Error(`unexpected inventory path: ${file}`);
  const repository = parts.slice(0, 3).join("/");
  if (!grouped.has(repository)) grouped.set(repository, []);
  grouped.get(repository).push(file);
}
const repositories = [...grouped].sort(([left], [right]) => left.localeCompare(right)).map(([repository, paths]) => ({
  repository,
  organization: repository.split("/")[1],
  cljc_count: paths.length,
  disposition: "pending-classification",
  paths
}));
const organization_counts = Object.fromEntries(organizations.map(organization => [
  organization,
  repositories.filter(repo => repo.organization === organization).reduce((sum, repo) => sum + repo.cljc_count, 0)
]));
const inventory = {
  schema: "kotoba.cljc-inventory/v1",
  generated_on: "2026-07-19",
  scope: scopes,
  source: "rg --files with explicit organization scopes",
  repository_count: repositories.length,
  cljc_path_count: files.length,
  organization_counts,
  repositories
};
const output = path.join(root, "90-docs/migration/kotoba-cljc-inventory.json");
fs.writeFileSync(output, `${JSON.stringify(inventory, null, 2)}\n`, { encoding: "utf8", mode: 0o644 });
process.stdout.write(`${JSON.stringify({ output: path.relative(root, output), repositories: repositories.length, paths: files.length, organization_counts })}\n`);
