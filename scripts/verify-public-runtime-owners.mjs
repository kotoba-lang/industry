import fs from "node:fs";

const owners = JSON.parse(fs.readFileSync("manifest/public-runtime-owners.json", "utf8"));
function walk(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    if (entry.name === ".git" || entry.name === "node_modules") return [];
    const path = `${dir}/${entry.name}`;
    return entry.isDirectory() ? walk(path) : [path];
  });
}

const tracked = walk("orgs")
  .filter((p) => /\/wrangler\.(jsonc|json|toml)$/.test(p))
  .filter((p) => !p.includes("/_archive/") && !p.includes("/60-apps/ai-gftd-project-") && !p.includes("/_wt-"));

const declarations = new Map();
for (const path of tracked) {
  const text = fs.readFileSync(path, "utf8");
  if (path.startsWith("orgs/kotoba-lang/") && /custom_domain\s*[":=]+\s*true/.test(text)) {
    throw new Error(`kotoba-lang must not own a custom domain: ${path}`);
  }
  const patterns = [...text.matchAll(/["']?pattern["']?\s*[:=]\s*["']([^"']+)["'][\s\S]{0,100}?custom_domain["']?\s*[:=]\s*true/g)]
    .map((m) => m[1].replace(/\/\*$/, ""));
  for (const host of patterns) {
    const paths = declarations.get(host) ?? [];
    paths.push(path);
    declarations.set(host, paths);
  }
}

const errors = [];
for (const [host, owner] of Object.entries(owners)) {
  if (!owner.startsWith("orgs/gftdcojp/")) errors.push(`${host}: public owner must be in gftdcojp: ${owner}`);
  if (!fs.existsSync(owner)) continue; // thin root CI; full west workspace verifies contents
  const actual = declarations.get(host) ?? [];
  if (!actual.includes(owner)) errors.push(`${host}: owner does not declare custom domain (${owner})`);
  const foreign = actual.filter((p) => p !== owner);
  if (foreign.length) errors.push(`${host}: duplicate declarations: ${foreign.join(", ")}`);
}
if (errors.length) {
  process.stderr.write(errors.join("\n") + "\n");
  process.exit(1);
}
console.log(`public runtime ownership ok: ${Object.keys(owners).length} hosts`);
