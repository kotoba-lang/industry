# ADR-2607051000: ciphertext-over-CID persistence design for `arrangement`/`kotobase-peer` (Phase 2 of ADR-2607050500)

**Status**: accepted (2026-07-06 — see "Acceptance" section below for the owner's resolution of each Open question)
**Date**: 2026-07-05 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Acceptance (2026-07-06)

This design is accepted as written, with the six Open questions below resolved
as follows. ADR-2607061900 (gftdcojp recipient-set / capability-grant / epoch
rotation) is the follow-on document that builds the multi-reader access layer
this acceptance presupposes — read the two together.

1. **Granularity: per-graph, as recommended, extended to per-graph +
   per-recipient.** The DEK stays one-per-graph (not per-actor-global), but
   ADR-2607061900 adds a wrapping layer on top: the same per-graph DEK is
   HPKE-wrapped once per authorized reader's X25519 public key, so "who can
   read this graph" is a set, not a single key-holder. This does not change
   anything in this document's Decision section (the DEK/blind-index design
   is unaffected) — it only answers "how does more than one actor come to
   hold the same DEK."
2. **Key-separation: option (b), a dedicated X25519 KEM keypair per actor.**
   Reusing the Ed25519 signing key via signature-derived HKDF (option a) does
   not compose with ADR-2607061900's requirement that each gftdcojp member
   have an addressable X25519 public key to wrap graph DEKs to — HPKE needs a
   Diffie-Hellman-capable key, and Ed25519 is not that (converting via XEdDSA
   was already ruled out for `kotoba-signal`'s own reasons, cited in this
   document's Alternatives). Every actor generates+persists one companion
   X25519 keypair alongside its existing Ed25519 identity key, exactly the
   shape `kotoba-signal`'s `:ik`/`:sign-seed` split already established.
3. **Compromised-Worker threat model: out of scope, as this document already
   stated.** No per-request key-wrapping/KMS-equivalent layer is added. A
   Worker instance that holds a graph's unwrapped DEK in memory during a
   request is trusted for that request's duration, same as today's capability
   host. Revisit only if a concrete incident or compliance requirement forces
   the question.
4. **Mandatory, no silent default — as this document leaned toward.** Every
   new graph on `arrangement`/`kotobase-peer` must supply `blind-fn` and
   `encrypt-fn`/`decrypt-fn` from day one; there is no opt-in/legacy-plaintext
   path (Alternative (c) is rejected, not merely deprioritized).
5. **Rotation policy: defined in ADR-2607061900, not here.** Summary: rotation
   is membership-driven (adding/removing a recipient bumps the graph's epoch
   and mints a new DEK, wrapped only to the then-current recipient set) and
   forward-only — an old epoch's DEK stays wrapped to whoever held it when it
   was current, so data written under that epoch remains readable to those
   readers without a mandatory O(graph) re-blind/re-encrypt pass. A removed
   member therefore keeps the ability to decrypt data from before their
   removal unless a stronger (and explicitly opt-in, because it's expensive)
   re-encrypt-under-new-epoch pass is run — this asymmetry is intentional, not
   an oversight, and is spelled out in ADR-2607061900's own Key management
   section.
6. **Current `kotobase/cljc-v2` volume: not independently re-confirmed by
   this acceptance.** The Migration section's "real but likely small" framing
   stands as this document's working assumption; if the owner has since
   confirmed a materially different volume, the Migration section should be
   revisited before the re-transact-under-a-fresh-prefix plan is executed as
   final.

## Addendum (2026-07-06): the value slot needs encryption too — original text was wrong

Found during implementation ([kotoba-lang/arrangement#1](https://github.com/kotoba-lang/arrangement/pull/1),
[kotoba-lang/kotobase-peer#4](https://github.com/kotoba-lang/kotobase-peer/pull/4)),
not caught during design review. The Decision section below (see "Proposed
shape") originally claimed: *"the value slot (currently always `true`,
membership-only) is unaffected by this — there's nothing to encrypt there —
but the datom's actual payload for anything `cold-datoms` reconstructs via
`->eav` is exactly the s/p/o triple that's now blinded, so no separate
'value encryption' step is needed at this layer; the protection comes
entirely from blinding the key."*

That is wrong. `blind-fn` is a one-way keyed MAC (HMAC-SHA256) — once the
leaf key stops being the plaintext `(pr-str [k1 k2 v])` and becomes a blind
token, **there is no way to invert it back to the original triple**.
`cold-datoms` previously read the row's actual `{:e :a :v_edn}` by
`edn/read-string`-ing the KEY (`arrangement.core/index-root`'s "all the
information is packed into the key; the value carries zero bits" encoding,
quoted in this document's own Context section). With the key blinded, that
recovery path is gone — and since `hydrate-db` calls `cold-datoms` to
rebuild the actual hot db that `fold!` then re-commits as the new canonical
snapshot, this wasn't just a read-path bug: the first `fold!` after this
design shipped as originally written would have baked corrupted (blinded,
unreadable) data into the graph permanently.

**The fix, now implemented as accepted**: the value slot carries the AEAD
ciphertext of the real `[k1 k2 v]` triple (`encrypt-fn`'d, not `true`); the
key stays blind-only (for prefix search). `cold-datoms` decrypts the
*value* to reconstruct each row, never the key. See
`arrangement.core/index-root`'s and `kotobase-peer.core/cold-datoms`'s
current docstrings for the corrected contract.

A second implementation-time finding, also corrected: the original text's
"a random nonce is fine for tx blocks" reasoning doesn't extend to the new
value-slot ciphertext for `index-root` — `commit!`'s content-addressing
(*"committing the same `db` + `prev` + `schema-version` twice returns the
same CID"*, and `kotobase-peer.core/fold!`'s *"concurrent folds of the same
state are safe, redundant, and cheap"*) depends on identical plaintext
producing identical ciphertext. A random-nonce `encrypt-fn` would silently
break that property (same db → different snapshot CID every time). Both
implementations' test `encrypt-fn` derives its nonce deterministically
(`HMAC-SHA256(nonce-key, plaintext)` truncated to 12 bytes — a standard
synthetic-IV composition of two primitives already used elsewhere in this
document, not a new hand-rolled construction) specifically to preserve this.
Production `encrypt-fn` implementations should make the same choice unless
they've separately decided this idempotency property doesn't matter to
them.

Neither correction changes this document's threat model, algorithm choice
(AES-256-GCM + HMAC-SHA256), or key-management story — both are
implementation-detail corrections to the Decision section's mechanics, not
new design decisions. See ADR-2607061900 (gftdcojp recipient-set) for the
layer built on top of this corrected design.

## Context

ADR-2607050500's operational-semantics gap assessment named "Plaintext-first
persistence" as the one finding in that audit that is "an active risk, not
just an incompleteness": `quad-store.core/index-root` persists index keys via
plain `pr-str` — "the exact 'fetchable = readable' risk: anyone who can fetch
a persisted block can read it, full stop." It phased the fix as **Phase 2**:
"ciphertext-over-CID persistence — quad-store values encrypted before
`pr-str`/`edn/read-string`, keyed via `signal`'s X3DH/ratchet primitives or a
simpler envelope." That phrasing was explicitly hedged ("or a simpler
envelope"), not a settled decision.

Since that ADR, `quad-store`+`kqe` merged and renamed to
`kotoba-lang/arrangement`, and `kotobase-engine` renamed to
`kotoba-lang/kotobase-peer` (ADR-2607050700). Phase 1 (WASM fuel/capability
enforcement, `kotoba-lang/kotoba#279`) and part of Phase 3
(`kotobase-peer#2`+`kotobase-cljc-worker#5`, wiring the `visible?` seam
through `datoms`/`cold-datoms`/`hot-datoms`) are already separate, open,
unmerged PRs from this same working session and are deliberately NOT part of
this document. Phase 2 itself is explicitly still open per ADR-2607050700's
own Follow-up section. This document is the design review Phase 2 needs
before an autonomous agent starts writing encryption code into a shared
persistence layer — a proposed design, not an implementation.

### Where plaintext actually lives today (re-verified against current code)

Two independent sites, not one — the original ADR only cited the first:

- **`arrangement.core/index-root`** (private fn, `arrangement/src/arrangement/core.cljc:102-113`)
  flattens each of the 4 hot-db indices (`spo`/`pso`/`pos`/`ocp`) into
  `[key val]` prolly-tree leaf entries where `key = (pr-str [(link->edn k1)
  (link->edn k2) (link->edn v)])` — literally the printed `(s,p,o)` triple —
  and `val = true` (a membership-only encoding: **all** the information is
  packed into the key; the value carries zero bits). `commit!` (lines
  124-144) calls `index-root` for each index and `ipld/put-node!`s the
  resulting commit map; `prolly_tree.core/build-leaf-level`
  (`prolly-tree/src/prolly_tree/core.cljc:78-87`) dag-cbor-encodes each leaf
  as `{"kind" "leaf" "entries" [[k v] ...]}` and persists those bytes via
  `put!`. The persisted block genuinely contains the plaintext triple, in the
  clear, as a printed EDN string inside a dag-cbor byte string — this
  confirms ADR-2607050500's citation exactly.
- **`kotobase_peer.core/put-tx-block!`** (`kotobase-peer/src/kotobase_peer/core.cljc:244-245`,
  previously uncited) persists every write's novelty tx block as
  `(ipld/put-node! put! {"quads" (mapv quad->wire quads)})`, where
  `quad->wire` (line 241) is `{:keys [s p o]} -> {"s" s "p" p "o" o}` — the
  raw s/p/o values as dag-cbor map values, again unencrypted. Since
  ADR-2607032430 (the log-structured write path), **every write** goes
  through `commit!` → `put-tx-block!` first (novelty); only later does
  `fold!` compact novelty into `index-root`'s structure. Tx blocks are
  therefore the *first* place plaintext hits a persisted block on every
  write, with `index-root` a second, compacted copy of the same plaintext.
- The outer wrapper nodes — `arrangement.core/commit!`'s `{"schema-version"
  ... "index-roots" ... "prev" ...}` and `chain.core/commit!`'s `{"state" ...
  "prev" ... "seq" ...}` — hold only metadata and IPLD links, not raw datom
  content (`chain.core`'s own docstring: it never looks inside `state`).
  These leak coarse metadata (graph size via seq/fold count, schema version)
  but not datom content, so they're out of scope as an encryption target;
  the residual metadata leakage is named as a known non-goal below.
- In-memory hot-db operations (`assert-quad`, `entity-attrs`, `by-predicate`,
  `by-predicate-value`, `refs-to`) never touch a persisted block — they run
  over plain Clojure maps in the runtime's heap. This design's scope is
  exactly the persist boundary (`index-root`/`put-tx-block!`) and the
  corresponding read/decode boundary (`cold-datoms`/`hydrate-db`/
  `read-tx-block`), not the whole engine.

### The cross-runtime constraint (the central design tension)

`arrangement` and `kotobase-peer` are `.cljc` — portable to JVM and
ClojureScript. Their real production consumer, `kotobase-cljc-worker`, is
confirmed (its own README + `shadow-cljs.edn`) to be an `:esm`-target
Cloudflare Worker (`shadow-cljs.edn`'s `:worker {:target :esm ...}` build;
`wrangler.jsonc`'s `routes: [{"pattern": "kotobase.aozora.app",
"custom_domain": true}]`) — a V8 isolate, not a JVM. `kotoba-lang/signal`'s
own README states plainly: "This implementation targets JVM Clojure (`.clj`)
only... **CJS / browser / babashka are NOT supported**" (X25519 via the JDK's
`java.security`/`javax.crypto`, no pure-Clojure/CLJS X25519 anywhere in the
dependency graph, hand-rolling one explicitly ruled out "for correctness
reasons"). Whatever encryption scheme this document proposes needs a real,
concrete implementation path in **both** runtimes, or it cannot ship into the
one place production traffic actually flows through.

Evidence this is answerable, not just hoped for: `orgs/gftdcojp/cloud-itonami`
(a sibling org already deployed on Cloudflare — `wrangler.jsonc` present,
README documents a "Cloudflare Pages operator cockpit" and a Cloudflare Email
Routing → Worker pipeline) ships `cloud_itonami.edge.cacao`
(`src/cloud_itonami/edge/cacao.cljc`, explicitly CLJS-only), which performs
real Ed25519 signature verification via `js/crypto.subtle.importKey`/
`.verify` in that exact edge/Worker-shaped runtime, today, in production.
This is concrete, same-superproject precedent that the standard Web Crypto
API (`crypto.subtle`) is available and used for real cryptography in a
Cloudflare edge JS environment — not a speculative assumption. AES-256-GCM is
a considerably older, more universally implemented `SubtleCrypto` algorithm
than Ed25519 (AES-GCM has been part of the Web Crypto API since its Level 1
spec; Ed25519 support is a comparatively recent addition to major engines),
so if Ed25519-via-`crypto.subtle` already works in this org's own Cloudflare
deployment, AES-256-GCM should be at least as reliably available. **I have
not personally executed code inside a live Cloudflare Worker to verify this
during this investigation** — this is an inference from strong same-org
precedent plus the Web Crypto spec, not a directly-tested fact, and it should
be smoke-tested as the first implementation step before any broader rollout.

### Is this live-production-urgent, or pre-launch/synthetic?

Real, not purely hypothetical, but likely still small. `kotobase-cljc-worker/
wrangler.jsonc`'s own comments say the `kotobase.aozora.app` custom-domain
route was "moved off the WASM worker" on 2026-07-02 — confirmed by git log
(`03b6044 chore(deploy): serve kotobase.aozora.app (route moved off the WASM
worker)`, dated 2026-07-02). This is the live worker behind a real custom
domain, not a staging-only deployment. The README describes the data as
`yoro-social`, a real, named, growing dataset, and documents that a
"relay-cron firehose-ingested ~3k datoms into the shared yoro-social" —
enough of a real incident that the R2 prefix was bumped to a fresh
`kotobase/cljc-v2` namespace and the offending cron disabled. Real data is
already accumulating under the current, plaintext-persisting code, as of
this writing (today is 2026-07-04, two days after that reset).

Countervailing signal: `wrangler.jsonc`'s own `KOTOBASE_OPERATOR_DIDS: ""`
comment ("empty = accept any validly-signed CACAO (staging); set the operator
DID allowlist before the production cutover") shows the project's own
operators still label the deployment "staging" for authorization purposes,
and the README's "Deploy — GATED on the yoro-social migration" section
describes an export/parity-diff/route-flip procedure phrased as not yet
fully executed.

Taken together: this is **not** purely hypothetical/pre-launch (real domain,
real recurring writes, already reset once for a real operational reason),
but the actual current data volume is very likely still small (days old,
post-reset), and the project doesn't yet consider itself past its own
"production cutover" gate. This document treats it as "real but currently
small" — a migration plan is warranted, not optional, but it need not assume
years of accumulated data. The owner is better positioned than a static code
read to confirm exactly how much `kotobase/cljc-v2` data exists today; this
is flagged as an open question rather than assumed.

## Decision (proposed)

### What gets encrypted, and where the plaintext/ciphertext boundary sits

The two persisted-plaintext sites get different treatments, because they
have different query-access patterns:

**1. Novelty tx blocks** (`kotobase-peer.core/put-tx-block!`/`read-tx-block`,
lines 244-248): read *whole* by CID — `read-tx-block` decodes one block and
returns every quad in it; `hot-datoms`/`fold!` iterate all novelty blocks,
never look inside one by key. No range/prefix query ever touches a tx
block's internals. → **Encrypt the whole quad payload as one opaque
ciphertext blob** per tx-data item (or per block): replace `{"s" s "p" p "o"
o}` with something like `{"ct" <AEAD ciphertext bytes> "n" <nonce bytes>}`.
Trivial to implement, no query-shape tradeoff, no compatibility concern with
`prolly-tree`.

**2. Folded index-root prolly-tree leaves** (`arrangement.core/index-root`,
`cold-datoms`'s `pt/scan-prefix`): genuinely harder, because `cold-datoms`/
`hot-datoms`'s whole reason for existing (ADR-2607022330 addendum 2 — the CF
Worker OOM/CPU-limit regression fix) is to avoid rehydrating the whole graph
by doing a **prefix-seek** on the persisted tree via `components-prefix`
(`kotobase_peer/core.cljc:331-338`). If `index-root`'s keys become opaque,
non-deterministic ciphertext, `scan-prefix` can no longer find anything by
prefix and `cold-datoms` degrades back into the O(graph) full-scan the
log-structured-write-path/range-pruning effort was built to avoid.

Look closely at what `components-prefix` is actually used for, though: every
caller of `cold-datoms`/`datoms` that supplies `:components` supplies
**values it already knows** (a specific entity id, a specific attribute) to
filter down to an unknown *suffix* (the attributes/values it doesn't know yet
and is querying to discover) — this is `[known-prefix, unknown-suffix]`, not
an open inequality range scan over an unknown value (nothing in this
codebase does "value > X"; ordering only matters for prolly-tree's own
chunking-boundary determinism and for `:limit`'s already-arbitrary cutoff,
not for any caller-visible ordering guarantee). That distinction matters: the
key doesn't need to be **order-preserving** ciphertext (which would be
weak/leaky), it needs to be **queryable by a caller who already knows the
plaintext prefix value** — exactly what a keyed **blind index** (deterministic
MAC per component) gives you, without the order/frequency leakage an
order-preserving-encryption scheme would add on top.

Proposed shape: for each of s/p/o, `index-root` builds the key from
`blind(component) = base64(HMAC-SHA256(index-key, pr-str (link->edn
component)))` instead of the raw `pr-str`'d value, keeping the same
`[bs bp bv]`-vector-then-`pr-str` framing so `components-prefix`'s
"print the known prefix, truncate before the closing bracket" trick keeps
working unchanged — a caller filtering on a known entity/attribute
independently computes the same HMAC (it has the plaintext, and the same
`index-key`) and gets the identical prefix bytes to seek on. ~~The value slot
(currently always `true`, membership-only) is unaffected by this — there's
nothing to encrypt there — but the datom's actual payload for anything
`cold-datoms` reconstructs via `->eav` is exactly the s/p/o triple that's now
blinded, so no separate "value encryption" step is needed at this layer; the
protection comes entirely from blinding the key.~~ **Wrong — see the
Addendum (2026-07-06) above.** `blind-fn` is one-way; once the key stops
being plaintext, the value slot is the ONLY place left to recover the real
triple from, so it now carries `encrypt-fn`'d ciphertext of `[k1 k2 v]`
instead of `true`.

What this explicitly does **not** hide: which blinded tokens repeat
(equality/frequency pattern — e.g. "predicate X appears 40 times" is visible
even though nobody can read what X is). A known, accepted limitation of
blind/deterministic indexing, not an oversight — named again in Key
management below.

### Encrypt/decrypt boundary in the call graph

Both transforms belong at the same seam this codebase already uses for
`ref?`/`visible?`/`schema-version` — an injected function, not a hardcoded
dependency, and not pushed up to callers:

- `arrangement.core/index-root` gains a `blind-fn` parameter (`(blind-fn
  component) -> bytes-or-string`). Per this codebase's own no-silent-default
  stance (ADR-2607050700), this should likely be a **required** argument once
  accepted, exactly like `visible?`/`schema-version` became required rather
  than optional.
- `kotobase_peer.core/put-tx-block!`/`read-tx-block` gain an `encrypt-fn`/
  `decrypt-fn` pair (`(encrypt-fn quad) -> bytes`, `(decrypt-fn bytes) ->
  quad`), threaded through `commit!`/`hot-datoms`/`fold!`/`cold-datoms` the
  same way `put!`/`get-fn` already are.
- `arrangement.query/query` and `kotobase_peer.core/q` need no change — a
  caller that wants to query by known prefix values pre-blinds them the same
  way `index-root` does (a shared helper both sides call), so
  `by-predicate-value`/`cold-datoms`'s `:components` keep taking plaintext
  values from the caller's point of view; blinding happens transparently at
  the library boundary.
- Callers upstream (`kotobase-cljc-worker`'s `worker.cljc`, `kotoba`'s
  host-providers) supply the actual crypto function values (JDK
  `javax.crypto`-backed on the JVM, `crypto.subtle`-backed in the Worker) —
  the two library repos stay crypto-algorithm-agnostic (same
  non-opinionated-injected-predicate discipline as `ref?`); only the shape of
  the seam (an encrypt/decrypt or blind fn pair) is new.

### Concrete algorithm recommendation

- **AEAD**: AES-256-GCM for tx-block quad payloads. Available natively via
  `javax.crypto.Cipher` ("AES/GCM/NoPadding") on JVM, and via
  `crypto.subtle.encrypt`/`decrypt` with `{name: "AES-GCM"}` in Workers
  (Web Crypto Level 1, long-standing/universal support — see the caveat
  above about not having tested this myself in a live Worker). Prolly-tree/
  dag-cbor already support raw byte-string values natively (confirmed by
  reading `dag-cbor/src/cbor/core.cljc`'s `bytes-like?`/major-type-2
  encoding) — a ciphertext blob (nonce ++ ciphertext ++ tag, or nonce/tag as
  separate byte-string fields) fits as an ordinary CBOR byte string, no new
  wire convention needed.
- Nonce handling needs care because tx blocks *are* content-addressed (the
  CID commits to the encrypted bytes) — a random nonce is fine for tx blocks
  specifically (nobody prefix-scans or re-derives a tx block's CID from its
  plaintext; different ciphertexts for identical plaintext writes are
  harmless since novelty blocks are never deduplicated by content today
  anyway). For `index-root`'s blind index, use a **keyed, deterministic MAC**
  (HMAC-SHA256, not an AEAD, no nonce) specifically because deterministic
  output is what makes independent re-derivation by a querying caller
  possible — a genuinely different primitive for a genuinely different job
  (queryable blinding vs. confidential bulk encryption), not an
  inconsistency.

## Key management

- Threat model **protected against**: anyone who can fetch a persisted block
  from the shared BlockStore/R2/IPFS layer without holding the Data
  Encryption Key (DEK) — the exact "fetchable = readable" finding
  ADR-2607050500 named. This includes a misconfigured/leaked R2 bucket, a
  compromised CDN edge cache, or any other party with read access to the
  content-addressed block store but not the key.
- Threat model **NOT protected against** (stated explicitly, not oversold):
  (a) a compromised runtime that already holds the derived DEK in memory —
  this design protects data at rest in the block store, not a runtime
  actively executing with the key loaded; (b) traffic/frequency analysis on
  the blind index (repetition of a blinded s/p/o component is visible even
  though its plaintext is not); (c) metadata carried in the unencrypted
  wrapper nodes (schema-version, seq count, number of folds — coarse
  graph-shape signal); (d) anything upstream of this boundary (a
  capability-host bug in `kotoba/host_providers.clj` that leaks a query
  result to the wrong caller is a Phase 3/authorization problem, not
  something ciphertext-over-CID touches).
- Key derivation: tie the DEK to the *same* actor-key infrastructure the rest
  of this stack already uses rather than minting a disconnected keystore —
  per this superproject's own "actor holds its own key, graph identity is
  key-derived, self-mint is structurally authorized" pattern (see the root
  `CLAUDE.md`'s "kotoba-server（kotobase.net）" section), and
  `ai-gftd-itonami/src/itonami/cacao.clj`'s `load-or-create-identity!`
  precedent — an actor generates+persists one Ed25519 keypair, its graph *is*
  the key-derived IPNS name, and self-mint is authorized purely by holding
  that key. Two candidate ways to get a symmetric DEK out of that same
  already-held Ed25519 key, presented as options rather than a single final
  call (crypto key-separation deserves real scrutiny, not a default):
  - **(a) Deterministic-signature-derived DEK.** Ed25519 signatures are
    deterministic per RFC 8032 (same message + key always produces the same
    signature bytes) — an actor can sign a fixed, versioned context string
    (e.g. `"kotobase-dek-v1:" graph-ipns-name`) with its existing private key
    and run the resulting signature bytes through HKDF-Extract+Expand
    (SHA-256) to derive a 256-bit DEK. Reuses the exact key already loaded by
    `load-or-create-identity!`, no second keypair, no ECDH. Downside:
    signing keys and encryption keys are conventionally kept separate
    (key-separation best practice) — this reuses one key for two purposes
    via a derivation trick, which is defensible but not the
    textbook-cleanest shape.
  - **(b) A dedicated X25519 KEM keypair per actor**, generated alongside the
    existing Ed25519 identity (analogous to how `kotoba-signal`'s own
    identity already keeps a separate X25519 `:ik` DH key from its Ed25519
    `:sign-seed` signing key, precisely to avoid the XEdDSA curve-conversion
    path — see `kotoba-signal`'s own README rationale, "Why JVM-only, why
    HKDF is hand-rolled, why not XEdDSA"), then HKDF the DEK from an
    X25519-based key agreement (e.g. self-agreement, or agreement with a
    per-graph salt). Cleaner separation, standard practice, at the cost of a
    second keypair per actor to generate/persist/rotate.
  - Either way, **per-graph, not per-actor-global**: derive a distinct DEK
    per graph (HKDF `info` includes the graph's IPNS name) so leaking one
    graph's DEK doesn't expose every graph the same actor owns, and so
    rotation can be scoped to one graph.
  - Rotation is **not designed** in this document — flagged as an open
    question below. Rotating a DEK for `index-root`'s blind index means
    re-blinding (and re-committing) every existing leaf, an O(graph)
    operation; rotating the tx-block AEAD key doesn't invalidate already-
    written novelty blocks unless they're re-encrypted too. A rotation
    *policy* (when, how often, lazy-on-fold vs. eager) needs its own
    decision.
  - Note on `kotoba-lang/signal` specifically (Alternatives, below, expands
    on this): this document does **not** recommend X3DH/Double-Ratchet key
    agreement itself as the DEK source — only, optionally, reusing the
    *shape* of "a separate X25519 key alongside the Ed25519 identity key"
    that `kotoba-signal`'s own design already established, for an analogous
    reason (key separation without XEdDSA).

## Migration

Real, not hypothetical, per the Context investigation above —
`kotobase.aozora.app` is a live custom-domain route (moved off the WASM
worker 2026-07-02, confirmed by `wrangler.jsonc` + git log) accumulating real
(`yoro-social`) data under `kotobase/cljc-v2`, even though the project's own
comments still call the deployment "staging" pending an operator-DID-
allowlist cutover, and the volume is very likely small (days old, post-reset).

1. Land the encrypt/blind seams as **required, non-optional** parameters
   (matching this codebase's established no-silent-default discipline)
   behind a feature-gate the worker turns on deliberately, not automatically
   for existing data.
2. Because `kotobase/cljc-v2` is so recent, the cheapest correct migration is
   **re-transact, not in-place re-encrypt**: export the current small graph
   via `hot-datoms`/`datoms` (the same export mechanism the README's own
   "Deploy" section already describes using for the WASM→CLJC cutover), and
   `transact` it back in under a fresh, encrypted prefix (e.g.
   `kotobase/cljc-v3`) the same way the WASM→CLJC migration itself worked —
   reusing an already-proven pattern in this exact repo rather than
   inventing a new one.
3. This is **not** a "no migration needed" case — there is real, if small,
   already-persisted plaintext today — but it *is* a case where a clean
   re-transact cutover is legitimate instead of a complex in-place rewrite,
   precisely because the volume is still small and a proven export/reimport
   path already exists. If the owner confirms `cljc-v2` has grown
   significantly beyond a few days' worth of writes by the time this is
   implemented, this plan should be revisited (in-place migration of a large
   existing graph is a meaningfully different, harder problem this document
   does not design for).
4. Any other `kotobase-peer`/`arrangement` consumer (tests, other worker
   deployments) has no persisted data to migrate — new commits should be
   required (not optional) to supply the encrypt/blind functions from day
   one, per the pre-release no-backward-compat stance ADR-2607050700 already
   established for this exact pair of repos.

## Alternatives considered

**(a) Reuse `kotoba-lang/signal`'s X3DH/Double-Ratchet machinery directly**
(ADR-2607050500's own phrasing). Assessed and rejected as a category
mismatch, not adopted even partially beyond the "separate key for separate
purpose" shape noted in Key management: Signal's ratchet model exists to
give *two or more live parties* exchanging a *stream of messages* forward
secrecy and post-compromise security — a new ratchet step per message,
session state both parties must keep in lockstep, explicitly no design for
out-of-order/skipped-message recovery beyond a cache `kotoba-signal` doesn't
implement yet ("Skipped-message-key cache... both assume in-order delivery,"
per its own README). A persisted key/value store read by an unbounded number
of callers *over time*, where any caller holding the DEK needs to decrypt
*any* historical entry independent of "session" or delivery order, is a
data-at-rest envelope-encryption problem, not a conversation. Forcing it
through a ratchet means either (i) never advancing the ratchet (degrading it
to a single static key — at which point it isn't buying anything a plain
AEAD envelope doesn't already give, while adding X3DH's bootstrap complexity
for no benefit), or (ii) actually advancing it, which makes *older* data
permanently undecryptable to a party that only holds a *later* ratchet key —
the opposite of what a database needs (every past commit must stay readable
to an authorized reader forever, not just the most recent one). Compounding
this, `kotoba-signal` is explicitly JVM-only by its own README, which alone
rules it out for the Cloudflare Worker consumer regardless of the conceptual
mismatch. ADR-2607050500's own phrasing ("keyed via signal's X3DH/ratchet
primitives **or** a simpler envelope") already hedged this as speculative,
not a settled decision — this document resolves that hedge: don't use the
ratchet, use a simpler envelope, for the reasons above.

**(b) A simpler symmetric envelope (this document's recommendation).**
AES-256-GCM for bulk quad-payload confidentiality + HMAC-SHA256 blind
indexing for queryable structural keys, DEK derived from the actor's
already-held Ed25519 identity key (or a purpose-built companion X25519 key).
Tradeoffs: real implementation work in two runtimes, a rotation policy still
to be designed, and the blind-index frequency-leakage caveat above — but it
fits the actual read/write/query shape this system has (multiple readers
over unbounded time, prefix-known-value lookups, content-addressed
persistence) rather than the shape Signal's ratchet was built for.

**(c) Do nothing at this layer; rely entirely on capability/access-receipt
controls instead.** I.e. leave `index-root`/tx-blocks plaintext, and depend
on `kotoba/host_providers.clj`'s `guard-call` (capability-scoped access) plus
the access-receipt audit trail (`kotoba-server`'s `access_receipt.rs`, per
the root CLAUDE.md's Actor section and the gap-assessment's Capability row)
to be the sole confidentiality boundary. This is a real, legitimate
alternative — not a strawman — for a design that's confident every path to
the block store (R2/IPFS) itself is equally access-controlled as the query
API, so "fetchable" never actually means "fetchable by an unauthorized
party" in practice. The gap-assessment ADR's own finding is precisely that
this is **not** currently true ("anyone who can fetch a persisted block can
read it, full stop" — the R2 bucket, any B2/IPFS mirror, or a future public
content-addressed distribution layer are all more permissive read paths than
the capability-gated query API), so accepting this alternative means
explicitly accepting that risk rather than closing it — a legitimate choice
if the owner judges the capability layer's access control to be sufficient
in practice (e.g. if R2/block-store access is already as tightly scoped as
the query API, this whole design is unnecessary complexity), but one this
document flags rather than assumes.

## Open questions for the owner (resolved — see "Acceptance" section above)

Preserved verbatim as the record of what was open at proposal time; each is
now answered above, not still open:

1. **Per-graph key vs. per-actor key vs. some other granularity** — this
   document recommends per-graph (Key management, above), but the owner may
   have a different multi-tenant or key-sprawl tradeoff in mind, especially
   once Phase 4 (organizational identity, ADR-2607050500) exists and
   "actor" might mean an org/team, not a single individual.
2. **Deterministic-signature-derived DEK (option a) vs. a dedicated
   companion X25519 KEM keypair (option b)** — the owner (or a dedicated
   crypto review) should pick one; this document deliberately doesn't commit
   to a single answer because key-separation tradeoffs deserve real
   scrutiny, not a default.
3. **Is a compromised Worker instance in-scope for the threat model?** This
   design explicitly does *not* protect data from a runtime that already
   holds the loaded DEK (Key management, above) — if the owner needs a
   stronger guarantee (e.g. Workers should never hold a long-lived DEK in
   memory, only per-request derived material), the design needs an
   additional layer (e.g. per-request key wrapping via a separate
   KMS-equivalent) this document doesn't attempt.
4. **Mandatory vs. opt-in per graph during a transition period** — should
   every new graph be required to supply encrypt/blind functions from day
   one (matching ADR-2607050700's no-silent-default precedent), or should
   there be a transitional opt-in flag for graphs that explicitly accept the
   risk (Alternative (c)) while the rest of the org catches up? This
   document leans toward "required, no silent default," consistent with
   this codebase's stated pre-release stance, but flags it as a real choice.
5. **Key rotation policy** — not designed here at all (Key management,
   above); needs its own follow-up decision once a scheme is chosen, since
   `index-root` rotation is O(graph) and tx-block rotation only covers
   future writes unless old blocks are explicitly re-encrypted.
6. **Actual current volume of `kotobase/cljc-v2` data** — this document's
   Migration section assumes it's still small (days old, post-reset) based
   on static evidence (git log dates, `wrangler.jsonc` comments); the owner
   can confirm this directly (e.g. via R2 bucket size/object count) before
   treating the "re-transact under a fresh prefix" plan as final.

## Consequences

- (+) Names a concrete, implementable scheme (AES-256-GCM for bulk payloads,
  HMAC-SHA256 blind indexing for queryable keys) instead of leaving Phase 2
  as an unscoped "encrypt it somehow" line item — the two persisted-plaintext
  sites (`index-root`, `put-tx-block!`) get treatments matched to their
  actual access patterns (whole-block read vs. prefix-seek), not
  one-size-fits-all.
- (+) Resolves ADR-2607050500's own hedge ("signal's ratchet primitives or a
  simpler envelope") with a first-principles argument for why the ratchet is
  a category mismatch for this access pattern, not just a restatement of the
  hedge.
- (+) Ties key management to already-existing actor-key infrastructure
  (DID/CACAO self-mint pattern) instead of proposing a disconnected
  keystore, and gives the cross-runtime constraint (JVM + Cloudflare Worker)
  a concrete, evidence-backed answer (`crypto.subtle`, backed by same-org
  precedent in `cloud-itonami`) rather than leaving it unaddressed.
- (+) Honestly scopes what this design protects against (fetchable-block
  confidentiality) versus what it explicitly does not (a compromised runtime
  holding the live key, blind-index frequency leakage, wrapper-node metadata)
  — so a future reader can't mistake this for a stronger guarantee than it
  provides.
- (−) This document proposes, it does not implement — Phase 2 remains open
  until the owner accepts a specific design (this one, a variant of it, or
  none of it) and someone writes the actual encryption code into two shared,
  actively-consumed persistence libraries; a design mistake caught during
  review here is far cheaper than one caught after `arrangement`/
  `kotobase-peer` callers depend on a specific wire shape.
- (−) Several real decisions are deliberately left open (Open questions,
  above) rather than settled by this document — key-separation approach,
  rotation policy, mandatory-vs-opt-in, and the actual current data volume
  all need the owner's judgment before implementation starts.
- (−) The blind-index approach for `index-root` means equality/frequency
  patterns on s/p/o components remain visible even after this lands (a
  known, named limitation, not an oversight) — a reader who wants to hide
  "how many times does predicate X occur" needs a different (and much more
  expensive) scheme this document doesn't propose.
- (±) The migration plan (re-transact under a fresh prefix) reuses the exact
  pattern this repo already used for its WASM→CLJC cutover, so it's
  low-novelty to execute — but it depends on the current `kotobase/cljc-v2`
  data genuinely being small; if that assumption is wrong, this section
  needs revisiting before implementation.

## References

- [kotoba-lang/arrangement#1](https://github.com/kotoba-lang/arrangement/pull/1)
  and [kotoba-lang/kotobase-peer#4](https://github.com/kotoba-lang/kotobase-peer/pull/4)
  — the implementation PRs (JVM-only, per ADR-2607061900's sequencing
  decision) that found and fixed the value-slot bug this ADR's 2026-07-06
  Addendum documents.
- ADR-2607061900 (gftdcojp recipient-set / capability-grant / epoch rotation)
  — the follow-on document that adds multi-reader access (per-recipient HPKE
  wrapping of this document's per-graph DEK) and defines the rotation policy
  this document's Open question 5 left undesigned.
- ADR-2607050500 (kotoba-lang operational-semantics gap assessment) — names
  this gap ("Plaintext-first persistence") and phases it as Phase 2.
- ADR-2607050700 (Datomic terminology rename) — `quad-store`+`kqe` →
  `arrangement`, `kotobase-engine` → `kotobase-peer`; establishes the
  no-silent-default / required-argument precedent this document follows for
  the proposed `blind-fn`/`encrypt-fn` seams.
- `orgs/kotoba-lang/arrangement/src/arrangement/core.cljc` (`index-root`
  lines 102-113, `commit!` lines 124-144) — the confirmed plaintext-key
  persistence site.
- `orgs/kotoba-lang/kotobase-peer/src/kotobase_peer/core.cljc`
  (`put-tx-block!`/`quad->wire` lines 241-245, `cold-datoms` lines 340-362,
  `components-prefix` lines 331-338) — the second plaintext-payload site and
  the query-access-pattern evidence behind the blind-index proposal.
- `orgs/kotoba-lang/prolly-tree/src/prolly_tree/core.cljc`
  (`build-leaf-level` lines 78-87, `scan-prefix` docstring) — confirms leaf
  blocks are dag-cbor-encoded, unencrypted, and that `scan-prefix` is
  genuinely key-range-pruned (so blinding needs to preserve
  prefix-matchability, not full-tree order).
- `orgs/kotoba-lang/dag-cbor/src/cbor/core.cljc` (`bytes-like?`,
  major-type-2 byte-string encoding) — confirms ciphertext blobs fit as
  native CBOR byte strings, no new wire convention needed.
- `orgs/kotoba-lang/signal/README.md` — JVM-only scope statement and
  rationale (why hand-rolling portable X25519 was ruled out), and the
  "separate X25519 DH key + separate Ed25519 signing key, not XEdDSA"
  precedent this document's key-management option (b) borrows the shape of.
- `orgs/kotoba-lang/kotobase-cljc-worker/README.md`, `shadow-cljs.edn`,
  `wrangler.jsonc` — confirms the Cloudflare Worker/V8 runtime constraint and
  the live-production-route evidence (git log `03b6044`, 2026-07-02) behind
  this document's "real, not hypothetical" migration framing.
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/edge/cacao.cljc`,
  `wrangler.jsonc` — same-superproject precedent for `crypto.subtle`-backed
  cryptography running in a real Cloudflare edge/Worker deployment.
- `orgs/gftdcojp/ai-gftd-itonami/src/itonami/cacao.clj`
  (`load-or-create-identity!`) — the existing per-actor Ed25519
  key-generation/persistence precedent this document's key-derivation
  options build on.
- Root `CLAUDE.md`, "kotoba-server（kotobase.net）= actor が自分の鍵で CACAO
  を自己発行" section — the "actor holds its own key, graph identity is
  key-derived, self-mint is structurally authorized" pattern this document's
  key-management section ties into rather than reinventing.

## Addendum: adoption attempt + confirmed migration gate (2026-07-07)

`kotobase-cljc-worker` first ADOPTED this seam a full day after acceptance
(the worker was never redeployed in the interim) — surfacing two real,
previously-latent issues at actual deploy time, not synthetically:

1. **Arity crash on first deploy**: `handler.cljc`'s `do-datoms`/`do-transact`/
   `do-q`/`do-pull`/`do-fold` never passed the (already-required, no-silent-
   default) `blind-fn`/`encrypt-fn`/`decrypt-fn` params this document added
   to `kotobase-peer.core`'s public API — a straight oversight, since no
   consumer had actually adopted the new peer API in production before now.
   Fixed (`kotobase-cljc-worker` `f9454dbf`, merged to main): an explicit
   plaintext-passthrough crypto profile (`kotobase.cljc-worker.crypto` —
   behaviorally identical to pre-seam, real per-graph keys remain this
   document's own follow-up) + threading every handler response through the
   engine's sync-JVM/Promise-cljs split. Verified against `wrangler dev
   --local` (mint→transact→retractEntity→datoms/pull→fold→datoms) and 79
   node-test assertions, 0 failures.
2. **This document's own Migration section was designed but never
   executed** — confirmed live 2026-07-07: deploying the arity-fixed build
   to `kotobase.aozora.app` broke reading `kotobase/cljc-v2`'s real,
   already-accumulated data (`cbor: unexpected end of input` reading
   pre-existing plaintext novelty tx blocks under the new `{"ct": ...}`-
   wrapped `read-tx-block` format — no backward-compat fallback exists,
   matching this section's own "re-transact, not in-place re-encrypt"
   framing). **Immediately rolled back** to the last-known-good deploy
   (confirmed healthy: `com.etzhayyim.yoro.feed.getVideoFeed`/`getTimeline`
   both serving real data again). Real data volume as of this writing:
   **2,743 datoms** on the operator `yoro-social` graph — squarely in this
   section's anticipated "real, if small" range, confirming the planned
   re-transact-to-a-fresh-prefix migration (step 2 of Migration, above) is
   still the right shape, just not yet executed.

**Current state**: `kotobase-cljc-worker` git main (`f9454dbf`) has the
crypto-seam adoption merged and is pinned in the superproject manifest, but
**production `kotobase.aozora.app` deliberately still runs the prior,
pre-adoption deploy** — the git pin and the live Cloudflare deployment are
intentionally decoupled here until the migration below executes. Cutting
over without it silently breaks every existing actor's persisted data.

**Follow-up (blocking further deploys of this worker)**: execute this
document's Migration section for real — export the operator `yoro-social`
graph's current datoms from the live (safe) deploy, `transact` them into a
fresh `KOTOBASE_B2_PREFIX` (e.g. `kotobase/cljc-v3`) via the ciphertext-
adoption build, parity-diff old vs. new, then cut the custom-domain route
over — the exact pattern already proven for this repo's WASM→CLJC migration.
Given it is a real-production-data cutover (not a reversible-in-place
change), this is left as an explicit, deliberately-not-rushed follow-up
rather than executed inline in the same pass that found the gap.

## Addendum 2: migration executed, real cutover live (2026-07-07)

Owner authorized the real-data migration in the same session. Executed as:

1. **Not** via the CACAO-gated `datomic.transact` endpoint — that derives
   the write graph from the CACAO signer (`canonical-graph(issuer,
   db_name)`), so it structurally cannot target an existing DID's graph
   without that DID's actual private key (confirmed by a first attempt that
   landed on the WRONG, self-signed throwaway graph). Instead: an offline
   JVM Clojure script (no HTTP, no CACAO) built the full hot db from an
   exported-datoms JSON via `eng/transact`, then called
   `kotobase-peer.core/snapshot!` — its own documented "one-shot cold-start
   entry point for... backfill/migration tooling" — with the identity
   crypto profile (matching `kotobase.cljc-worker.crypto`'s plaintext-
   passthrough exactly), producing IPLD blocks uploaded directly to R2
   (`wrangler r2 object put`) under `kotobase/cljc-v3/`.
2. **First attempt targeted the wrong graph.** `canonical-graph` is keyed
   by `(operator-did, db-name)`, and this document had been assuming
   db-name `"yoro-social"` — but `app-aozora-appview`/`-pds`'s own
   `wrangler.jsonc` name it `YORO_DB_NAME = "yoro-social-v2"` (the name
   changed 2026-07-03, before this document's own Context section was
   written, and it wasn't re-checked at migration time). Migrating and
   cutting over `"yoro-social"` (2,743 datoms — a real, but superseded,
   legacy graph) left the REAL live graph unmigrated; the custom-domain
   route briefly served an EMPTY feed in production
   (`getTimeline`/`getVideoFeed` both `{"feed":[]}` despite the legacy
   graph's datoms reading back correctly) until caught within the same
   pass and corrected.
3. **Corrected**: re-derived the graph from `"yoro-social-v2"` (458
   datoms), re-ran the same offline snapshot!+upload procedure, parity-
   diffed byte-for-byte (`{e,a,v_edn}` set-equality, 0 missing/extra) against
   a fresh export taken immediately before cutover (confirming zero writes
   landed in the export→migrate window), then redeployed
   `kotobase-cljc-worker` with `KOTOBASE_B2_PREFIX=kotobase/cljc-v3`. Both
   graphs (`yoro-social` legacy + `yoro-social-v2` real) now live under v3;
   both v2 prefixes stay untouched in R2 as rollback. Production confirmed
   healthy post-cutover: real posts (including the minidrama actor's video
   post) served correctly via `getTimeline`/`getVideoFeed`, `aozora.app` SPA
   200.
4. **Lesson for future migrations of this kind**: always re-derive the
   target graph from whatever `wrangler.jsonc`/config a REAL current
   consumer actually names *at migration time* — don't reuse an assumed
   db-name from an earlier document/session without re-checking it
   against the consumers that will read the migrated data.

`kotobase-cljc-worker` git main (`7c0e3d7`) now matches the live deploy;
manifest pin advanced accordingly. This document's Migration section is
executed; the crypto seam is live in production.

## One-line summary

**Proposes closing ADR-2607050500's "plaintext-first persistence" gap with
AES-256-GCM envelope encryption for whole tx-block quad payloads
(`kotobase-peer.core/put-tx-block!`) plus HMAC-SHA256 blind indexing for
`arrangement.core/index-root`'s queryable prolly-tree keys (preserving
`cold-datoms`'s prefix-seek performance rather than breaking it),
DEK-derived from the actor's already-held Ed25519 identity key rather than a
new keystore, with a concrete answer to the JVM/Cloudflare-Worker
cross-runtime constraint (`javax.crypto` / Web Crypto `crypto.subtle`, backed
by real same-org precedent in `cloud-itonami`) — rejects `kotoba-signal`'s
ratchet as a category mismatch (built for live multi-party message exchange,
not unbounded-time reads of a shared at-rest store) rather than accepting
ADR-2607050500's own hedged phrasing at face value, and leaves key-separation
approach, rotation policy, mandatory-vs-opt-in transition, and current data
volume as open questions for the owner before any implementation begins.**
