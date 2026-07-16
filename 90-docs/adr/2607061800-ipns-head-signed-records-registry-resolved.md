# ADR-2607061800: `ipns.head` — signed IPNS head records, resolved in kotobase.net's own registry

## Status

Accepted (implemented)

## Context

`kotoba-lang/kotobase`'s completion review found IPNS (a key-derived,
signed mutable pointer) referenced only by XRPC lexicon schemas
(`kotoba/lexicons/.../ipns/{head,publish}.json`), with `kotoba-client`
and `kotobase-client` both explicitly documenting "NOT implemented ...
needs cacao's Ed25519 signing surface."

Further investigation found the **name-derivation half of this gap was
already closed**: `kotoba-lang/ipns` (created 2026-07-05, ADR-2607050100)
already provides `ipns.core/pubkey->name`/`name->pubkey` — a zero-dep,
portable `.cljc` CIDv1/libp2p-key/base36 derivation, cross-checked
byte-for-byte against two independent prior JVM implementations
(`kekkai`'s and `kagi`'s `ipns-name`). What was still missing was the
other half the lexicon schemas describe: **signing/verifying the
mutable head record** the name points at (`{:name :value :sequence
:valid_until :public_key_multibase :signature_multibase}`).

The owner's decision on scope: kotobase.net does **not** join the real
IPFS/libp2p DHT (no libp2p transport, no publish/resolve over the actual
network). Resolution stays inside kotobase.net's own XRPC registry (the
existing `head`/`publish` lexicon routes); only the *name format* needs
to be genuinely IPFS-interoperable, which `ipns.core` already is.

## Decision

**New namespace `ipns.head` in the existing `kotoba-lang/ipns` repo**
(not a new repo — this extends ADR-2607050100's library rather than
duplicating it):

- `ipns.head/sign` / `ipns.head/verify` — sign/verify an IPNS head
  record over a canonical dag-cbor payload (`kotoba-lang/dag-cbor`), per
  the lexicon's own documented contract ("signature over a canonical CBOR
  payload"), using `kotoba-lang/ed25519`'s existing `did:key` primitives
  (`did-key-from-seed`, `verify-did`, `b58`/`b58-decode`) — reused, not
  reimplemented.
- `:clj`-only, matching `ed25519`/`cacao`'s own JVM-only convention.
  `ipns.core` (name derivation) stays zero-dep and portable; only
  `ipns.head` pulls in `ed25519`/`dag-cbor`, so a caller that only needs
  `pubkey->name` never resolves them.

**`ipns.core`'s existing test suite gained a real-world cross-check**,
not just internal ones: an isolated `ipfs init` (Kubo 0.41.0, temp
`IPFS_PATH`) generated a real Ed25519 keypair; `ipfs id` and `ipfs cid
format -v 1 -b base36 --codec libp2p-key` produced a ground-truth legacy
peer-id and IPNS name for that node's own public key.
`ipns.core/pubkey->name` reproduces the IPNS name **byte-for-byte** from
the same raw pubkey (`matches-a-real-kubo-node` test) — this is now
independently verified against a real IPFS implementation, on top of
the prior kekkai/kagi cross-check.

Verified: 6 tests/15 assertions green (JVM) — the existing `ipns.core`
suite plus the new real-Kubo vector, plus `ipns.head`'s sign/verify/
tamper/wrong-signer roundtrip.

## Consequences

- (+) `kotoba-lang/kotobase`'s IPNS gap is now fully closed for the
  scoped use case (name derivation + signed head), not just the naming
  half — both `kotoba-client` and `kotobase-client`'s "NOT implemented"
  stubs have a real implementation to call into (wiring them up is a
  tracked follow-up, not done in this landing).
- (+) No duplicate `kotoba-lang/ipns` repo was created — the existing
  one (ADR-2607050100) was found and extended instead, avoiding a
  second, competing name-derivation implementation.
- (+) The real-Kubo cross-check strengthens confidence in the existing
  `pubkey->name`/`name->pubkey` beyond internal-only agreement.
- (−) No real DHT publish/resolve — a kotoba-derived IPNS head record is
  not actually resolvable by a real IPFS node today; only kotobase.net's
  own XRPC registry knows about it. Explicit scope choice.
- (−) `ipns.head` is JVM-only. `kotobase-client` (the `:cljs`-only
  tenant-plane client) can already derive names via `ipns.core` but
  cannot sign/verify heads without its own port (tracked follow-up).

## Follow-up (closed, second addendum 2026-07-06)

Both follow-ups above are now done, same day:

- **`kotoba-client`** (JVM): `verify-ipns-head` added, calling `ipns.head/
  verify` directly — the documented "NOT implemented" stub is resolved.
  7 tests/513 assertions green.
- **`kotobase-client`** (`:cljs`): new `kotobase.ipns/sign-head`/
  `verify-head`, built on this repo's own `@noble/curves`/`@ipld/dag-cbor`
  stack (same precedent `kotobase.cacao` already established, ADR-2607050100)
  rather than porting `ipns.head` itself. **Verified against a real
  JVM-signed fixture, not just cljs self-consistency**: the same seed +
  record produces the byte-identical canonical dag-cbor payload AND the
  byte-identical deterministic Ed25519 signature on both platforms.
  25 tests/80 assertions green.
- **`kotobase-cljc-worker`**: wired `com.etzhayyim.apps.kotoba.ipns.
  {head,publish}` as a XRPC route family separate from the `ai.gftd.apps.
  kotobase.datomic.*` surface (genuinely different trust model —
  unauthenticated self-verifying reads, signature-gated writes with no
  CACAO). `publish` calls `kotobase.ipns/verify-head` server-side before
  ever touching R2 (401 on failure) and CASes on a monotonic `:sequence`
  (409 on rollback), reusing `r2-get-head`/`r2-put-head-if-match` by
  JSON-stringifying the signed record as that pair's "chain" string.
  20 tests/69 assertions green.
  - **Lexicon/implementation mismatch found and documented, not silently
    resolved**: `ipns/head.json`'s query param is named `graph`
    ("Graph CID ... IPNS name is derived from it"), but no graph-CID→
    IPNS-name derivation exists anywhere — `ipns.core/pubkey->name`
    derives a name from an Ed25519 **pubkey**. Owner-confirmed
    resolution: storage/lookup key off the signed record's own `:name`
    field instead; the worker's actual query param is `name`. Fixing the
    lexicon file itself is a separate, not-yet-scheduled follow-up.

## One-line summary

**`ipns.head` (new namespace in the existing `kotoba-lang/ipns`, not a
new repo) adds JVM-only signed IPNS head record sign/verify — reusing
`ed25519`'s did:key primitives over a canonical dag-cbor payload — while
`ipns.core`'s existing name derivation gains a cross-check against a
real Kubo node's byte output; resolution stays inside kotobase.net's own
XRPC registry, deliberately not the real IPFS/libp2p DHT.**

## Addendum (2026-07-06, same day): repo renamed to the reverse-domain external-spec convention

Per owner direction, `kotoba-lang/ipns` → `kotoba-lang/tech-ipfs-specs-ipns`,
following the same `org-<body>-<spec>`/`io-<domain>` reverse-domain
convention ADR-2607052300 already established (e.g. `org-ietf-turn`,
`io-libp2p`) — here reversing `specs.ipfs.tech` (the spec's actual host)
with the `-ipns` suffix scoping the name to the one spec section this
repo implements (IPFS/IPFS specs host several other specs — bitswap,
graphsync, etc. — under the same domain; the suffix keeps the name from
overclaiming conformance to specs this repo doesn't touch, the same
discipline ADR-2607052300 states explicitly).

Executed the same procedure as ADR-2607052300: `gh repo rename` (GitHub
preserves a redirect from the old name), local checkout path moved +
remote retargeted, the 3 real dependents' `deps.edn` coordinates updated
(`kekkai`, `kagi`, `tayori` — same pinned commit, coordinate only, no
functional change), and `manifest/repos.edn`/`manifest/west.yml` updated
(`:path-overrides` entry added, `:extra-projects` path renamed, new
west.yml entry added via `gen-west-manifest.cljs --entry` then the stale
old-name entry removed manually — `--entry` mode doesn't auto-delete the
superseded entry, same caveat ADR-2607052300 notes for its own renames).

**`nbb scripts/gen-west-manifest.cljs --check` reports STALE after this
edit** — not because this rename is wrong, but because a full regen at
the time of this change would also "fix" two *unrelated* pin regressions
(`kotoba`, `tayori` — both had local checkouts behind their true
upstream) that are out of scope here and belong to whoever is actively
working those repos. Per CLAUDE.md's explicit pin-freshness guardrail, a
wholesale regen to chase a clean `--check` would silently roll those
pins backward — worse than a STALE flag. This ADR's own entry (`tech-
ipfs-specs-ipns`, pin `f3847f2ca371...`) was individually verified
reachable-from-main via `--entry`'s own per-project check before the
manual old-entry removal.
