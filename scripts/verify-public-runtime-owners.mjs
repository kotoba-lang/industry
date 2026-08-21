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

// Only west-registered checkouts can own anything. `orgs/` also accumulates
// checkouts of repositories that moved org -- measured 2026-08-21:
// `orgs/network-awai/net-kotobase` and `orgs/gftdcojp/local-murakumo` are both
// on disk and neither is in west.yml. Their wrangler files declare the same
// hosts as the live owners, so without this filter every such host is reported
// as a "duplicate declaration" by a directory nothing deploys from. A stale
// copy is not a second owner.
const westPaths = new Set(
  [...fs.readFileSync("manifest/west.yml", "utf8").matchAll(/^\s*path:\s*(\S+)\s*$/gm)].map((m) => m[1]),
);
const registered = (p) => [...westPaths].some((w) => p === w || p.startsWith(`${w}/`));

const tracked = walk("orgs")
  .filter((p) => /\/wrangler\.(jsonc|json|toml)$/.test(p))
  .filter((p) => !p.includes("/_archive/") && !p.includes("/60-apps/ai-gftd-project-") && !p.includes("/_wt-"))
  .filter(registered);

const declarations = new Map();
const ownerErrors = [];
for (const path of tracked) {
  const text = fs.readFileSync(path, "utf8");
  if (path.startsWith("orgs/kotoba-lang/") && /custom_domain\s*[":=]+\s*true/.test(text)) {
    // Collected, not thrown. Throwing here ended the run at the FIRST offender,
    // so every later check -- including the ownership map below -- was never
    // reached, and two standing problems sat behind one exception for weeks.
    // A verifier that stops at its first finding reports a prefix of the truth.
    ownerErrors.push(`kotoba-lang must not own a custom domain: ${path}`);
  }
  const patterns = [...text.matchAll(/["']?pattern["']?\s*[:=]\s*["']([^"']+)["'][\s\S]{0,100}?custom_domain["']?\s*[:=]\s*true/g)]
    .map((m) => m[1].replace(/\/\*$/, ""));
  for (const host of patterns) {
    const paths = declarations.get(host) ?? [];
    paths.push(path);
    declarations.set(host, paths);
  }
}

// The commercial-hosting orgs. This was a single `orgs/gftdcojp/` prefix until
// 2026-08-13 (ADR-2608137200): the repos that serve these hosts were transferred
// out of gftdcojp, so the rule asserted an org that no longer owned anything and
// only stayed quiet because every stale path failed the existsSync skip below.
// The invariant that matters is unchanged — a public custom domain is never
// owned out of `orgs/kotoba-lang/` (enforced against tracked files above too).
const PUBLIC_OWNER_ORGS = ["orgs/gftdcojp/", "orgs/network-awai/", "orgs/net-kotobase/"];

const fullWorkspace = tracked.length > 0;
const skipped = [];
const errors = [...ownerErrors];
for (const [host, owner] of Object.entries(owners)) {
  if (!PUBLIC_OWNER_ORGS.some((o) => owner.startsWith(o))) {
    errors.push(`${host}: public owner must be in ${PUBLIC_OWNER_ORGS.join("/")}: ${owner}`);
  }
  if (!fs.existsSync(owner)) {
    // A named owner that is not on disk used to be skipped silently, which is
    // how `kotobase.net` pointed at `orgs/gftdcojp/net-kotobase/worker/` -- a
    // path in an org that no longer holds it -- and still reported "ok"
    // (measured 2026-08-21). The skip is legitimate ONLY in thin root CI, where
    // `orgs/` carries no wrangler files at all; there, absence proves nothing.
    // In a full west workspace the same absence is a stale map entry.
    if (fullWorkspace) errors.push(`${host}: owner path does not exist: ${owner}`);
    else skipped.push(host);
    continue;
  }
  const actual = declarations.get(host) ?? [];
  if (!actual.includes(owner)) errors.push(`${host}: owner does not declare custom domain (${owner})`);
  const foreign = actual.filter((p) => p !== owner);
  if (foreign.length) errors.push(`${host}: duplicate declarations: ${foreign.join(", ")}`);
}
// Evidence, printed on both outcomes: a reader can tell how much was actually
// examined, and cannot mistake "nothing was checkable here" for "nothing is
// wrong". `checked` is the number of hosts whose owner file was read.
const evidence = `SCANNED\thosts=${Object.keys(owners).length}\twrangler-files=${tracked.length}\t`
  + `workspace=${fullWorkspace ? "full" : "thin"}\tchecked=${Object.keys(owners).length - skipped.length}\t`
  + `skipped=${skipped.length}${skipped.length ? ` (${skipped.join(", ")})` : ""}`;
process.stdout.write(evidence + "\n");
if (errors.length) {
  process.stderr.write(errors.join("\n") + "\n");
  process.exit(1);
}
console.log(`public runtime ownership ok: ${Object.keys(owners).length} hosts`);
