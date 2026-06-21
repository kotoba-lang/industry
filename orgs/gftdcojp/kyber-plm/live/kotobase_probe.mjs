// Live CACAO probe against the production kotobase distributed-Datomic XRPC.
//
// Mints a self-signed did:key Ed25519 CACAO (reusing net-kotobase's cacao.mjs +
// kotoba-wasm identity, so the did:key form matches the server) and makes REAL
// authenticated calls to kotobase.gftd.ai — proving the credential end-to-end.
//
//   node kotobase_probe.mjs            # read probe (datomic.q)
//   node kotobase_probe.mjs --write    # also try a datomic.tx write
//
// The 32-byte seed is a PRIVATE KEY — persisted to ./.secrets/seed.hex (gitignored)
// and reused across runs so the identity (and the graph it owns) is stable.

import { KotobaNode } from "../../net-kotobase/poc-cf-wasm/pkg-node/kotoba_wasm.js";
import { mintCacao } from "../../net-kotobase/poc-cf-wasm/cacao.mjs";
import { randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.KOTOBASE_BASE || "https://kotobase.gftd.ai";

function loadSeed() {
  const dir = join(here, ".secrets");
  const f = join(dir, "seed.hex");
  if (existsSync(f)) return readFileSync(f, "utf8").trim();
  mkdirSync(dir, { recursive: true });
  const seed = randomBytes(32).toString("hex");
  writeFileSync(f, seed, { mode: 0o600 });
  return seed;
}

const seed = loadSeed();
const did = new KotobaNode().useIdentity(seed);
const b64 = (s) => Buffer.from(s, "utf8").toString("base64");
const DB_NAME = process.env.KOTOBASE_DB || "kyber-plm";

// Two auth paths (API-EXAMPLES.md): operator Bearer JWT, or self-sovereign CACAO
// + x-kotoba-did. Set KOTOBASE_OP_TOKEN to use the operator path (needed for
// Datom writes, which are operator-gated). Otherwise the tenant CACAO is used.
const OP_TOKEN = process.env.KOTOBASE_OP_TOKEN;

async function xrpc(nsid, body, op, graph) {
  const cacao = await mintCacao(seed, did, {
    aud: "kotobase", op, graph: graph || DB_NAME,
    nonce: randomBytes(8).toString("hex"),
    iat: new Date().toISOString(),
  });
  const cb = b64(cacao);
  const headers = OP_TOKEN
    ? { "content-type": "application/json", authorization: `Bearer ${OP_TOKEN}` }
    : { "content-type": "application/json", authorization: `CACAO ${cb}`, "x-kotoba-did": did };
  const r = await fetch(`${BASE}/xrpc/${nsid}`, {
    method: "POST",
    headers,
    body: JSON.stringify({ ...body, cacao_b64: cb }),
  });
  let json; try { json = await r.json(); } catch { json = { _raw: await r.text().catch(() => "") }; }
  return { status: r.status, json };
}

console.log("did    :", did);
console.log("db_name:", DB_NAME);
console.log("base   :", BASE);

// 1) provision a per-tenant graph (deterministic kotobase/db/<did>/<db_name>)
console.log("\n== datomic.createDatabase ==");
const cd = await xrpc("ai.gftd.apps.kotobase.datomic.createDatabase", { db_name: DB_NAME }, "datom:write");
console.log("status", cd.status, JSON.stringify(cd.json).slice(0, 400));
const graph = cd.json?.graph || DB_NAME;

if (process.argv.includes("--write")) {
  // 2) write one PLM item into the owned graph
  console.log("\n== datomic.transact (write one PLM item) ==");
  const tx = "[{:plm.item/id \"PROBE@A\" :plm.item/part-no \"PROBE\" :plm.item/revision \"A\" :plm.item/make-buy :buy}]";
  const w = await xrpc("ai.gftd.apps.kotobase.datomic.transact", { graph, tx_edn: tx }, "datom:write", graph);
  console.log("status", w.status, JSON.stringify(w.json));
}

// 3) read back (Datomic map-form query, per worker datomic_cloud_compat_test)
console.log("\n== datomic.q (read PLM items) ==");
const rb = await xrpc("ai.gftd.apps.kotobase.datomic.q",
  { graph, query_edn: "{:find [?id] :where [[?e :plm.item/id ?id]]}" }, "datom:read", graph);
console.log("status", rb.status, JSON.stringify(rb.json));
