// PROVEN live write: ingest a PLM item into the tenant knowledge graph on the
// production kotoba pod, using the LEGITIMATE tenant path — no operator master
// key. kg.ingest is the tenant WRITE surface (sub == tenant_did).
//
//   KOTOBA_INTERNAL_TRUST=$(kubectl -n kotoba get secret kotoba-internal-trust \
//       -o jsonpath='{.data}' | <decode first value>) \
//   node kg_ingest.mjs
//
// Auth model confirmed against production (2026-06-17):
//   • x-internal-trust  — pod trust boundary (edge BFF normally supplies it)
//   • Authorization: Bearer <unsigned JWT {sub: tenant_did}> — KG writes require
//     a Bearer (NOT a CACAO); the pod authorizes sub == tenant_did.
//   • claim shape is {pred, value} (NOT the lexicon's {predicate, object}).
//
// Result: {"ok":true,"subjectCid":"bafyrei…","quadCount":4}. The write lands in
// the hot Arrangement; a SPARQL read over cold storage needs an operator
// kg.commit to seal hot→cold first (see LIVE.md).

import { KotobaNode } from "../../net-kotobase/poc-cf-wasm/pkg-node/kotoba_wasm.js";
import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const POD = process.env.KOTOBA_POD || "https://kotoba-backend.gftd.ai";

// tenant identity: standard-form did:key, consistent everywhere (avoids the
// kotoba-wasm hex-form vs CLI multibase mismatch documented in LIVE.md).
const seed = readFileSync(join(here, ".secrets", "seed.hex"), "utf8").trim();
const did = execSync(`kotoba did-derive ${seed}`, { encoding: "utf8" }).trim();

const trust = process.env.KOTOBA_INTERNAL_TRUST;
if (!trust) { console.error("set KOTOBA_INTERNAL_TRUST (kubectl … kotoba-internal-trust)"); process.exit(1); }

const b64u = (s) => Buffer.from(s, "utf8").toString("base64url");
const bearer = `Bearer ${b64u('{"alg":"none","typ":"JWT"}')}.${b64u(JSON.stringify({ sub: did, exp: 9999999999 }))}.x`;

const item = {
  id: "PROBE@A", type: "plm.item", label_en: "Probe item",
  claims: [
    { pred: "plm.item/part-no", value: "PROBE" },
    { pred: "plm.item/make-buy", value: "buy" },
  ],
  tenant_did: did,
};

const r = await fetch(`${POD}/xrpc/com.etzhayyim.apps.kotobase.kg.ingest`, {
  method: "POST",
  headers: { "content-type": "application/json", "x-internal-trust": trust,
             authorization: bearer, "x-kotobase-tenant-did": did },
  body: JSON.stringify(item),
});
console.log("tenant did:", did);
console.log("status", r.status, await r.text());
