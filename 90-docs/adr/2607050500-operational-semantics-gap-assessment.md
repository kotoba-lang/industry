# ADR-2607050500: kotoba-lang operational-semantics gap assessment and roadmap — Effect / Capability / Resource-Time / Sovereignty

**Status**: accepted (Phase 0 implemented in `kqe`/`quad-store`; later phases explicit follow-up)
**Date**: 2026-07-05 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Context

A cross-session review (this session, following ADR-2607050100/200/300/400)
asked how much of a "distributed-DB-plus-execution-runtime" design is
actually *operational* rather than merely *mathematically clean*: does
kotoba-lang have Effects, Capabilities, a Resource/Time model, protocol
adapters, observability, supply-chain artifacts, encryption/key management,
and a coherent identity/authority/evidence split — or only the content-
addressed data/execution core (IPLD, Datom, Prolly-tree, Tx-DAG, DID/IPNS,
Clojure-as-data, Pregel/BSP, LangGraph)?

A verification pass across the actual `kotoba-lang` org (not design intent,
not README claims — grepped code and read source) found:

### The core is real and solid (unchanged by this ADR)

IPLD/CID/dag-cbor, Datom (`datom.core`), Prolly-tree + commit-dag,
Pregel/BSP-style execution (`langgraph.graph`, `utsushi.pregel` — correctly
separate per ADR-2607050300), DID + CACAO (ADR-2607050100/400) are all real,
tested, JVM/cljs-portable implementations, not aspirational README claims.
This ADR does not touch any of them.

### The operational layer is genuinely thin — verified, not assumed

| Layer | State | Evidence |
|---|---|---|
| **Effect** | partial | `kotoba.lang.capability-values/effect-for-kind` is a 1:1 capability-kind→tag map + static declare-checking (`kotoba/runtime.clj cap-effect-problems`) + receipted dispatch (`capability-host/guard-call`). **Not** an algebraic-effect system — no handler stack, no effect composition, no resumability. |
| **Capability** | mature | `kotoba.lang.capability-*`: `{:cap/kind :cap/resource :cap/holder? :cap/expires? :cap/provenance}`, CACAO-grant intersection, fails closed. **Zero references to UCAN anywhere in the org** (verified by grep) — the capability envelope is CACAO, not UCAN. |
| **Resource/Time** | fragmented | `utsushi.pregel`/`utsushi.policy` has real gas accounting (`gas-cost`, `gas-limit`, throws `:out-of-gas`). **`kotoba`'s own WASM executor (`wasm_exec.clj`) has no fuel metering at all** — `has-capability-fn` is a permissive always-grant stub. Deadline/priority exist only as ad-hoc, per-domain fields (`kiyaku`, `madoguchi`, `kotodama-host`, `witness-quorum`) — no shared type. |
| **Protocol Adapter** | data-model only | `http`/`webrtc`/`netcap`/`rtc`/`p2p`/`net`/`turn` are all pure Clojure/EDN protocol *records* with host-injected transport — **none has actual wire-level socket I/O**. No `mqtt`/`dds`/`ros2`/`quic` repos exist. |
| **Observability** | own vocabulary | `kotoba.lang.log` has span/parent-id as pure data, but **no trace-id, not OTel-wire-compatible**. The capability-host audit journal is in-memory, per-call, no persistence/export. |
| **Artifact/supply-chain** | narrower than SLSA | `package_admission.clj`: CID-pinned tree/manifest/repo + signers + revocation blocklist, capability-subset check. **No SBOM, no SLSA-level field.** |
| **Encryption** | exists, narrow | `kotoba-lang/signal` (X3DH + Double Ratchet, JVM-only, not cljs-portable). No "encrypted envelope over CID" pattern anywhere else — see Query/persistence finding below. |
| **VC** | shape only | `kotoba-lang/vc`: W3C VC v2 document *shape* (69 lines) — no DID-method proof verification, no JWT/JSON-LD signature crypto. |
| **Query as effect** | **none, confirmed** | `kqe.core`/`quad_store.core`: zero references to purpose/scope/caveat/capability/auth anywhere. The engine is deliberately auth-agnostic; authorization is bolted on *above* it (`kotoba/host_providers.clj`'s `guard-call`), never inside it. |
| **Schema evolution** | none | No schema-version field, no migration registry, in `datom`/`quad-store`/`kotobase-engine`. |
| **Plaintext-first persistence** | **confirmed real gap** | `quad-store.core/index-root` (as of ADR-2607050200) persists index keys via plain `pr-str` — the exact "fetchable = readable" risk: anyone who can fetch a persisted block can read it, full stop. No ciphertext-over-CID discipline exists anywhere in the persisted-datom path. |

### Identity/Authority/Evidence — already correctly layered, just not written down until ADR-2607050400

This session's ADR-2607050100/400 independently arrived at exactly the
3-layer split (**Identity = DID, Authority = capability envelope, Evidence
= credential/proof**) that a clean design calls for:

- **Identity**: `did:key` (self-issued, ADR-2607050100) — `kotoba-lang/did`,
  now with its first real consumer (`kotoba.did-adapter`).
- **Authority**: **CACAO**, not UCAN (ADR-2607050400) — `kotoba-lang/cacao`
  + `kotoba.lang.capability-cacao`, now with a real production verifier
  (`authentication.adapters.cacao/production-cacao-verifier`).
- **Evidence**: `kotoba-lang/vc` — exists, but shape-only (see table above).
- **WebAuthn** is correctly kept as the *human-authentication entry point*,
  not folded into the DID signing key (ADR-2607050400 explicitly deferred
  that as a separate, riskier wire-format change) — matching the general
  security-community position that origin-bound WebAuthn credentials and
  portable signing keys serve different trust properties.
- **Organizational identity (SCIM/SPIFFE-equivalent: org/team/workload
  DIDs) has no presence anywhere in the org** — every identity primitive
  built so far (kekkai, kagi, `did`, `ipns`) models a single self-sovereign
  individual actor. This is a real, currently-unaddressed gap for any
  multi-principal/organizational deployment.

## Decision

### Roadmap (phased; only Phase 0 is implemented by this ADR)

Adopting the priority order: **promote Effect + Capability + Resource/Time
to core-required layers first** (they're the cheapest to complete given
what already exists — capability is mature, effect/resource are partial),
**then build the sovereignty layer** (Encryption + Key-management + Policy)
on top, since ciphertext-first persistence is the one finding here that is
an active risk, not just an incompleteness.

- **Phase 0 (this ADR, implemented now)**: `kqe` gets a query-time
  visibility seam; `quad-store` gets a schema-version marker. See
  Implementation below. Deliberately the *smallest* slice that makes
  "Query as first-class effect" and "Schema evolution" structurally
  possible without kqe/quad-store taking on a policy opinion they were
  never designed to hold (matching this codebase's established minimalism
  discipline — same shape as `ref?`'s injection pattern, ADR-2607050200).
- **Phase 1 (follow-up)**: wire real fuel/gas metering into `kotoba`'s
  `wasm_exec.clj` (currently a permissive stub — flagged here as the
  highest-priority Phase 1 item since it's a live security gap, not just
  an absence).
- **Phase 2 (follow-up)**: ciphertext-over-CID persistence — quad-store
  values encrypted before `pr-str`/`edn/read-string`, keyed via `signal`'s
  X3DH/ratchet primitives or a simpler envelope; closes the "fetchable =
  readable" finding.
- **Phase 3 (follow-up)**: wire `kqe`'s new visibility seam through
  `kotobase-engine`'s `q`/`hot-datoms`/`cold-datoms` and `kotoba`'s
  `host_providers.clj`, so capability-scoped query redaction is enforced
  end to end, not just structurally possible.
- **Phase 4 (follow-up)**: organizational identity (org/team/workload DID
  methods, or an SCIM/SPIFFE-equivalent principal graph) — no design
  exists yet; needed before any multi-principal deployment.
- **Phase 5 (follow-up)**: Protocol Adapter real wire I/O (at least one of
  HTTP/WebRTC/MQTT actually opening a socket), Observability OTel-wire
  compatibility, Artifact SBOM/SLSA fields — lower priority, no active
  risk, purely completeness.

### Implementation (Phase 0)

**`kqe.core/query`** gains an optional `visible?` predicate (default:
`(constantly true)`, fully backward compatible) applied as a post-filter
over every candidate quad before it's returned — the structural seam a
composing layer (`kotobase-engine`, `kotoba`'s host providers) can use to
enforce purpose/scope-bound redaction, without `kqe` itself taking on any
authorization opinion. Same non-opinionated-primitive-with-injected-
predicate shape as `quad-store.core/assert-quad`'s `ref?` (ADR-2607050200).

**`quad-store.core/commit!`** gains a `"schema-version"` field (integer,
currently `1`) in the persisted commit node, alongside `"index-roots"` and
`"prev"`. Purely additive — existing readers that only look up specific
keys (`kotobase-engine`'s `indexed-cid`/`cold-datoms`) are unaffected; this
just gives a future incompatible index-shape change something to key a
migration off of, instead of guessing from absence.

## Consequences

- (+) Two real, tested, backward-compatible seams land today, precisely
  targeting the two gaps (`Query as effect`, `Schema evolution`) that were
  both previously "none" — without kqe/quad-store absorbing a policy engine
  they were never designed to hold.
- (+) The identity/authority/evidence layering question is now formally
  answered (CACAO not UCAN, WebAuthn stays a human-auth entry point) with
  a citation trail back to the ADRs that actually implemented each piece.
- (+) The plaintext-persistence and fuel-metering-stub findings are now on
  record as *known, named* gaps rather than things a future audit
  rediscovers from scratch.
- (−) Nothing in this ADR closes the plaintext-persistence gap itself —
  Phase 0's seams make future enforcement *possible*, they don't *enforce*
  anything yet. A deployment relying on this ADR alone for confidentiality
  would be wrong to do so.
- (−) Organizational identity remains entirely unaddressed; any near-term
  plan for team/company-scale (not single-actor) deployment needs Phase 4
  designed first, not assumed to fall out of the existing individual-actor
  DID model.

## Follow-up

Phases 1–5 above, in the stated priority order. Phase 1 (WASM fuel
metering) is flagged as the single highest-priority item — it's a live gap
in an already-shipped execution path, not a not-yet-built feature.

## One-line summary

**The content-addressed core (IPLD/Datom/Prolly-tree/Tx-DAG/DID/Pregel) is
real and solid; the operational layer (Effect/Capability/Resource-Time/
Adapter/Observability/Artifact/Encryption) is genuinely thin, verified
gap-by-gap rather than assumed. CACAO (not UCAN) is confirmed as the
capability envelope, WebAuthn stays a human-auth entry point rather than a
universal signing key, and this ADR lands the smallest possible seam in
`kqe`/`quad-store` (query-time visibility predicate + schema-version tag)
to make the two most concrete gaps structurally closable — without
pretending they're closed.**
