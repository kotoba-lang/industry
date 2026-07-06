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

## Follow-up

- Port `ipns.head/sign`/`verify` to `:cljs` (`@noble/curves`, matching
  `kotobase-client`'s own `cacao.cljc` precedent).
- Wire `ipns.head` into `kotoba-client`'s documented IPNS stub and
  `kotobase-cljc-worker`'s XRPC dispatch for the `ipns.head`/
  `ipns.publish` lexicon methods.

## One-line summary

**`ipns.head` (new namespace in the existing `kotoba-lang/ipns`, not a
new repo) adds JVM-only signed IPNS head record sign/verify — reusing
`ed25519`'s did:key primitives over a canonical dag-cbor payload — while
`ipns.core`'s existing name derivation gains a cross-check against a
real Kubo node's byte output; resolution stays inside kotobase.net's own
XRPC registry, deliberately not the real IPFS/libp2p DHT.**
