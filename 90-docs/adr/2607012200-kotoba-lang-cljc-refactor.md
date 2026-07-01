# ADR-2607012200: kotoba-lang TypeScript → portable CLJC refactor (pure core + injected-capability seam; delete TS)

**Status**: accepted (in progress — repo-by-repo)
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/` repos still authored in TypeScript / TS+Rust after the etzhayyim-sdk relocation.

## Context

`kotoba-lang` is the **pure-CLJC language/substrate** org. Its admission rule
(ADR-2606302300 §Step-1 layer test) is: `.cljc`, **zero vendor SDK, zero network
I/O**, models records/protocol/data only. Yet several repos relocated from
`etzhayyim-sdk` (ADR-2607011830 / 2607011930 / 2607011940) landed **as
TypeScript** and are now policy violations. Owner directive (2026-07-01): port
them to `.cljc`, extract pure logic to a zero-dep core, push vendor/network/
native calls to an **injected-capability boundary** (the established `koe`
pattern), and **delete the TypeScript** (TS disposition confirmed: delete, host
injects the capability).

### Scope (owner-confirmed)

- **IN — 6 relocated TS facades:** `ipfs`, `atproto-client`, `base-l2`,
  `checkpointer`, `witness-quorum`, `pqh`.
- **IN — large, phased (own ADRs):** `kami-nv-compat` (careful TS→CLJC),
  `kotodama-host` (merge into `kototama`).
- **OUT:** `svgraph`, `kotoba-v2025` (76 MB Rust). `kotodama-mcp` / `-py` /
  `-cells` / `-holochain` not named by owner → deferred.

### Manifest incident (2026-07-01, resolved)

While syncing to start this refactor, **`main`'s manifest was found broken**:
PR #222 (`a77ad65fb93`, squash of `a6a88c93a6ab`) intended to split
`office-causal`/`svgraph` out of `kotoba-lang` path-overrides, but instead
committed **0-line scratchpad-pointer files** for `manifest/repos.edn`,
`manifest/west.yml`, and both `ADR-2607011100` files — the referenced scratchpad
was deleted when the originating session ended. The entire west manifest
toolchain was non-functional on `main` (`west update` rejected the manifest;
`gen-west-manifest` could not read `repos.edn`). The breakage was located to
`a77ad65fb93`; the last good manifest was `f447108913f`. A concurrent session
repaired `main` independently (`5b3be53b5ef0` "repair repos.edn corrupted by a
bad API PUT", then PR #226). This refactor resumed only after `main`'s manifest
was verified healthy. Lesson: a squash-merge can land a botched working tree;
the manifest toolchain should `--check` in CI before merge.

## Decision

Every in-scope repo becomes a **pure `.cljc` core** that declares capability
ports (`defprotocol`) and consumes host-injected impls — it makes **zero**
direct vendor-SDK / network / native calls. The TypeScript is deleted. One
reusable recipe (proven on `ipfs`, ADR exemplar) applies to all:

### Porting recipe (proven end-to-end on `ipfs`)

1. **Pure core** (`src/.../<lib>.cljc`, zero vendor deps; reader conditionals
   only for platform primitives): all protocol framing, record/data defs,
   deterministic selection, validators, codecs (CBOR/msgpack), envelope framing,
   URL builders, response parsers. JSON via `clojure.data.json` (JVM) /
   `js/JSON.parse` (CLJS) — data-only, policy-fine.
2. **Capability seam** (`defprotocol` in the same or a `port.cljc` ns): one
   protocol per host-supplied concern (HTTP transport, XRPC client, viem/eth
   client, witness transport, raw crypto primitives, fs/socket, MST/CAR). The
   core takes impls as a map/arg and **never** calls a vendor SDK directly.
   Template: `orgs/kotoba-lang/koe/src/koe/ports.cljc` (`defprotocol` + consume
   `{:port impl}` map); scaffold: `orgs/kotoba-lang/ed25519/{deps.edn,bb.edn}`.
3. **Host impls live outside the lib** (in the consuming actor/app), supplied
   via `reify`: JVM JCA/BouncyCastle/`java.net`, cljs `@noble/*`/`fetch`.
4. **deps.edn**: drop vendor/network deps; keep only data libs. Aliases
   `:test` (cognitect test-runner) + `:lint` (`clj-kondo` `--fail-level error`,
   `:extra-deps` so clj-kondo resolves project deps).
5. **Delete TS**: `src/*.ts`, `dist/`, `package.json`, `package-lock.json`,
   `tsconfig.json`. CI: `DeLaGuardo/setup-clojure` + `clojure -M:lint` +
   `clojure -M:test`.
6. **Land per repo** (child repo is plain git): child-repo worktree branched
   from `origin/main` → edit → `clojure -M:lint` + `-M:test` green → commit
   (`Co-Authored-By: Claude Opus 4.8 (1M context)`) → push branch →
   **server-side merge** (`gh api repos/<org>/<repo>/merges`) → delete branch.
   Then **advance the superproject west pin** via **GitHub-API single-entry
   commit** on `manifest/west.yml` (edit only that repo's `revision:`, PUT with
   `branch=`+`sha=`, 409→retry); verify **pin == repo HEAD** and the repo is
   absent from the `gen-west-manifest.bb --check` stale diff. No `--force`, no
   history rewrite.

## Per-repo pure/IO ledger (what stays pure vs. what becomes an injected capability)

| repo | real TS src | PURE core (port) | injected capability (host) | status |
|---|---|---|---|---|
| `ipfs` | 106 LOC | URLs (`add-url`/`gateway-url`/…), NDJSON `parse-add-response`, bytes⇄string | `IHttp` (`-get`/`-post`/`-post-file`) | ✅ done (PR #1, pin advanced) |
| `atproto-client` | 376 LOC | `create-agent`, `xrpc` URL/headers/query, `did-web->url`, `pick-pds-service`, record orchestration | `IHttp` (xrpc + `fetch-json`) | ⏳ has prior direct-network port; refactor to seam |
| `base-l2` | 369 LOC | `ANCHOR_ABI` (EDN data), config records, `resolveSponsoredHolder`, encoding | `IAnchorClient` / `ISponsoredWriter` (viem) | ⏳ |
| `witness-quorum` | 2127 LOC (~80% pure) | witness selection (SHA-256), `quorumState` reducer, attestation validation + `canonicalAttestationBytes`, Ed25519 sign/verify (**reuse `ed25519`**) | `WitnessTransport` (pds-transport) | ⏳ |
| `pqh` | 1806 LOC (100% pure) | AEAD envelope framing, ISO-7816 pad/`pickBucket`, KDF composition, HKDF, PQ hybrid binding, did-signal canonical/fingerprint | raw-primitive seam (XChaCha20-Poly1305 / Argon2id / ML-KEM-768 / ML-DSA-65; JVM BouncyCastle 1.78+, cljs `@noble/*`) | ⏳ ⚠ flag ML-KEM/ML-DSA cross-platform vector parity |
| `checkpointer` | 721 LOC | wire-protocol `Op`/`Request`/`Response`, msgpack codec, `indexKey`; AEAD wrap/unwrap (**reuse `pqh`**) | fs/socket, `pin-blob` (**reuse `ipfs`**), `IMstCar` (`@atproto/repo` MST/CAR — inject now; native CLJC MST is a follow-up) | ⏳ most entangled; do last |
| `kami-nv-compat` | 20 557 LOC | (classify per own ADR) | NVIDIA SDK seam | ⏳ own ADR, phased |
| `kotodama-host` | TS+Rust | (merge into `kototama`) | — | ⏳ own ADR |

**Reuse targets (do NOT re-port):** `ed25519`, `cacao`, `did`, `dag-cbor`,
`multiformats`, `eth-crypto` (all pure `.cljc`/`.clj` in `kotoba-lang`).

## Verification (per repo, end-to-end)

1. `clojure -M:lint` exits 0 (clj-kondo errors fail).
2. `clojure -M:test` green; ported parity tests match old TS vectors (esp. pqh
   crypto round-trips, witness-quorum selection/quorum, checkpointer codec).
3. `.cljc` core loads JVM + cljs (no unguarded `java.*`/`js.*` in core).
4. Repo has **no** `package.json`/`tsconfig`/`*.ts`/`dist`/`node_modules`.
5. `bb scripts/gen-west-manifest.bb --check`: the repo is **absent** from the
   stale diff (pin == HEAD == regen output); advanced `west.yml` pin == repo HEAD.
6. Layer-test pass: repo is `.cljc`, declares capability ports, core makes
   **zero** direct vendor/network calls.

## Status & sequencing

- ✅ **Step 1 — `ipfs`**: complete; exemplar for the recipe. `kotoba-lang/ipfs`
  PR #1 (`1e26843885a5`); superproject pin advanced (`427636fb5bc`); ipfs now
  Clojure-only.
- ⏳ Steps 2–6 (`atproto-client`, `base-l2`, `witness-quorum`, `pqh`,
  `checkpointer`): dependency-ordered; `atproto-client` already has a prior
  direct-network `.cljc` port to refactor to the seam.
- ⏳ Steps 7–8 (`kami-nv-compat`, `kotodama-host→kototama`): own ADRs, phased.

Recommend executing the remaining repos one per session for context headroom;
the recipe above + the `ipfs` exemplar are sufficient to continue without
re-derivation.
