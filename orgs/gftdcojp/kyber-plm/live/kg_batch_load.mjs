// Bulk-load the projected PLM graph into production kotobase, end to end:
//   kg.ingest_batch (tenant)  →  kg.commit (operator)  →  kg.entity read-back
//
//   clojure -M:export > live/plm-batch.json
//   KOTOBA_INTERNAL_TRUST=$(kubectl -n kotoba get secret kotoba-internal-trust \
//       -o jsonpath='{.data}' | <decode first value>) \
//   node live/kg_batch_load.mjs
//
// Tenant identity for writes (kg.* sub == tenant_did); operator identity only
// for the global kg.commit (hot→cold seal). See LIVE.md.

import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const POD = process.env.KOTOBA_POD || "https://kotoba-backend.gftd.ai";
const OP_DID = "did:key:z35dec6b49a374eec5711a4f3ccaf66b944ecae6773766e62d71c86ff8e3b5a37";

const trust = process.env.KOTOBA_INTERNAL_TRUST;
if (!trust) { console.error("set KOTOBA_INTERNAL_TRUST (kubectl … kotoba-internal-trust)"); process.exit(1); }

const seed = readFileSync(join(here, ".secrets", "seed.hex"), "utf8").trim();
const tenant = execSync(`kotoba did-derive ${seed}`, { encoding: "utf8" }).trim();
const batch = JSON.parse(readFileSync(join(here, "plm-batch.json"), "utf8"));

const b64u = (s) => Buffer.from(s, "utf8").toString("base64url");
const bearer = (sub, alg = "none") =>
  `Bearer ${b64u(JSON.stringify({ alg, typ: "JWT" }))}.${b64u(JSON.stringify({ sub, exp: 9999999999 }))}.x`;

async function xrpc(nsid, body, sub, alg) {
  const r = await fetch(`${POD}/xrpc/${nsid}`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-internal-trust": trust,
               authorization: bearer(sub, alg), "x-kotobase-tenant-did": tenant },
    body: JSON.stringify(body),
  });
  return { status: r.status, text: await r.text() };
}

console.log("tenant:", tenant, "\nentities:", batch.entities.length);

// 1) batch ingest (tenant)
const ing = await xrpc("com.etzhayyim.apps.kotobase.kg.ingest_batch",
  { ...batch, tenant_did: tenant }, tenant);
console.log("\n[ingest_batch]", ing.status, ing.text.slice(0, 300));

// 2) seal hot→cold (operator)
const commit = await xrpc("com.etzhayyim.apps.kotobase.kg.commit",
  { author: "kyber-plm" }, OP_DID, "HS256");
console.log("[commit]", commit.status, commit.text.slice(0, 200));

// 3) read each entity back (tenant)
for (const e of batch.entities) {
  const r = await fetch(`${POD}/xrpc/com.etzhayyim.apps.kotobase.kg.entity?id=${encodeURIComponent(e.id)}`,
    { headers: { "x-internal-trust": trust, authorization: bearer(tenant), "x-kotobase-tenant-did": tenant } });
  const j = JSON.parse(await r.text());
  const claims = (j.entity?.claims || []).length, rels = (j.entity?.relations || []).length;
  console.log(`[read] ${e.id} → ok=${j.ok} type=${j.entity?.type} claims=${claims} relations=${rels}`);
}
