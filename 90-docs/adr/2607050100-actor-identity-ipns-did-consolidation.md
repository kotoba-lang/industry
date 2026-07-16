# ADR-2607050100: actor identity — IPNS/did:key derivation consolidation, and `kotoba-lang/did`'s first consumer

**Status**: accepted (implemented)
**Date**: 2026-07-05 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Context

A prior investigation (this session, unnumbered discussion) mapped how kotoba,
kotobase, and kotoba-lang's data/identity substrate fit together and flagged
two gaps in the identity layer specifically:

1. **IPNS name derivation had no shared library.** `kotoba-lang/kekkai`'s
   `cacao.clj` (`ipns-name`) and `kotoba-lang/kagi`'s `identity.clj`
   (`ipns-name`) each carried a private, `BigInteger`-based, byte-for-byte
   *identical* implementation of the libp2p-key CIDv1 (identity-multihash)
   name derived from an Ed25519 public key — the "`k51…`" name that is an
   actor's own graph (`kotoba/write.cljs`: "AUTHORITY is the Ed25519
   signature over a key-derived IPNS name, NOT a server"). Two hand
   copy-pasted implementations of the same math, drifting silently on any
   future edit to either.
2. **`kotoba-lang/did` was an orphan.** The repo (`did.core`: `parse`,
   `did-key->public-key`, `public-key->did-key`, `did-key-document`,
   `did-web-document`, `resolve-local` — all portable `.cljc`, zero-dep) had
   **zero deps.edn consumers anywhere in the org**, despite existing
   specifically to own DID identifier/document construction.

A closer look at *all four* originally-flagged did:key call sites
(`kekkai`, `kagi`, `kotobase-client`, and what the earlier investigation
loosely called "cacao") found the duplication picture was more textured than
"route everything through `kotoba-lang/did`":

- `kekkai/cacao.clj` and `kagi/identity.clj` are genuine, unforced
  duplicates of each other — same byte layout, same JVM `.clj`,
  `java.security.PublicKey`-native, no delegation to anything.
- `kotoba-lang/cacao` (the *language-side* CACAO library) does **not**
  reimplement did:key — it already delegates cleanly to
  `kotoba-lang/ed25519`'s `did-key-from-pub` (which also supplies
  `pubkey-from-seed`, a capability `did.core` doesn't have: deriving a
  public key from a raw seed with no JCA/BouncyCastle dependency, RFC 8032
  §5.1.5 direct). This path was already correct; "cacao" in the earlier
  summary conflated the language repo with the app-level actor files that
  are *also* internally named `cacao.clj`.
- `kotobase-client`'s `cid.cljc` (`did-key->ed25519-pub` /
  `did-key-from-ed25519-pub`) is cljs-only despite its file extension, by
  **explicit, documented design**: it must stay byte-identical to a
  separate, non-Clojure JS edge implementation (`net-kotobase` worker) and
  the `@gftd/kotobase-datomic` SDK, using typed-array arithmetic with no
  `BigInteger`. Forcing this onto either `did` or `ed25519` would trade a
  real, load-bearing byte-compat contract for a cosmetic one. **Left
  untouched, on purpose** — recorded here so it isn't rediscovered as an
  unexplained exception later.

## Decision

### 1. New repo: `kotoba-lang/ipns`

A new, small, zero-dep `.cljc` library (`ipns.core`): `pubkey->name` (Ed25519
raw pubkey → `k51…` libp2p-key CIDv1 identity-multihash name) and
`name->pubkey` (inverse, with structural validation). Portable — manual
digit-array arithmetic, no `BigInteger`, matching `kotoba-lang/did`'s own
`base58btc` style — so it runs identically on JVM and ClojureScript.

**Verified byte-for-byte identical to the prior kekkai/kagi implementation**
before the swap, across four vectors (`range 0..31`, all-zero, all-`0xff`,
and a real JCA-generated random Ed25519 key) — see
`kotoba-lang/ipns` `test/ipns/core_test.cljc` for the pinned regression
vectors.

Pushed public: `kotoba-lang/ipns` @ `f2563eb1cb010c96f6dec1dfbd468fac93367498`.

### 2. `kekkai` and `kagi` now depend on `ed25519` + `ipns`, not `did`

For these two JVM, byte-array-native actor-identity call sites, the better
fit is **`kotoba-lang/ed25519`** (not `kotoba-lang/did`) for did:key
derivation:

- `ed25519.core/did-key-from-pub` already takes/returns JVM byte arrays with
  zero conversion glue (`did.core`'s API works in int-vectors — a portability
  feature that isn't needed here and would add friction).
- It's the **already-established** pattern: `kotoba-lang/cacao` already
  depends on it.
- It comes with `pubkey-from-seed` for free, which `did.core` doesn't
  provide and which actor bootstrap code plausibly wants later.

This is a deliberate deviation from routing literally through `did` for
these two call sites — the point was eliminating the *duplication*, and
`ed25519` is the correct existing home for JVM did:key derivation, not a
second parallel one.

Changes (both repos, same shape):
- `deps.edn`: add `io.github.kotoba-lang/ed25519` and
  `io.github.kotoba-lang/ipns` git-sha deps.
- Replace the private `base58btc`/`base36`/`did-key`/`ipns-name` definitions
  with calls to `ed25519.core/did-key-from-pub` and `ipns.core/pubkey->name`,
  fed by the existing local `raw-pub` (X.509 SPKI → raw 32 bytes; kept local
  — it's a one-line JVM `PublicKey`-object accessor, not part of either
  shared library's scope).

Results: kekkai 20 tests / 82 assertions green; kagi 38 tests / 94 assertions
green (`kagi.import.onepassword-test` excluded from the local verification
run only — it fails on a **pre-existing, unrelated** break: `kagitaba`
doesn't exist on GitHub yet, per ADR-2607022800 Phase 4 finding #1; untouched
by this change, not fixed here).

Landed: `kekkai` @ `b8d2de539402792d6154663831170054fe923d91` (merge),
`kagi` @ `b0c7ddb69eb5df5f561a119a9bf46695cae3508e` (merge).

### 3. `kotoba-lang/did` gets its first real consumer: `kotoba-lang/kotoba`

Rather than force `did` onto a JVM byte-array call site it doesn't naturally
fit, it gets used for what it was actually built for: **DID Document**
construction and local resolution — a capability `kotoba` has lexicon
surface for (`com.etzhayyim.apps.kotoba.did.document.publish`) but no CLJC
implementation wired in yet.

New `kotoba.did-adapter` namespace (`.cljc`, pure — no injected host port,
unlike `kotoba.git-adapter`/`kotoba.rad-adapter`, because did:key/did:web
local resolution needs no I/O by `did.core/resolve-local`'s own design):

- `resolve-did` — resolve a DID string to a Document, `{:ok :document}` /
  `{:ok false :error :data}`, never throws.
- `publish-did-key` — build the DID Document for an actor's own Ed25519
  did:key from its raw public key.

Result: 114 tests / 625 assertions green (kotoba's full suite, including the
new `kotoba.did-adapter-test`).

Landed: `kotoba` @ `a6cd03456a1d4774d85bef342f3e16beadfebfe0` (merge).

### 4. `kotobase-client` — explicitly excluded, not silently dropped

`kotobase-client/src/kotobase/cid.cljc`'s did-key conversion keeps its own
independent implementation. Its docstring already states the reason (byte-
identical to a separate JS edge/SDK, typed-array-native, no `BigInteger`) —
this ADR just makes the *exclusion* a recorded decision instead of a fact
someone has to rediscover by reading the file.

## Consequences

- (+) One canonical IPNS-name implementation (`kotoba-lang/ipns`), one
  canonical JVM did:key implementation (`kotoba-lang/ed25519`, already
  established via `cacao`), one canonical DID-document implementation
  (`kotoba-lang/did`, now with a real consumer) — down from four independent
  reimplementations plus one legitimately-separate one.
- (+) `kotoba-lang/did` stops being a true orphan; its first consumer
  exercises document construction and local resolution, its actual scope.
- (−) `did.core`'s int-vector API and `ed25519.core`'s byte-array API remain
  two different representations for the "same" did:key concept, by design —
  a future reader might reasonably ask why they aren't unified. This ADR is
  that answer: they serve different callers (portable `.cljc` vs.
  JVM-native-with-seed-derivation) and unifying them would cost one side its
  fit.
- (±) `kekkai`/`kagi`'s own pre-existing, unrelated dep issues
  (`kagi`'s `:local/root "../langgraph"` / `"../kagitaba"` as *primary*
  `:deps`, and the missing `kotoba-lang/kagitaba` repo itself) were newly
  re-confirmed during this work but **not fixed here** — out of scope for an
  identity-derivation consolidation, left for the follow-up ADR-2607022800
  already tracks.

## Follow-up

- Fix `kagi`'s `:local/root`-as-primary-dep pattern (`langgraph`, `kagitaba`)
  once `kotoba-lang/kagitaba` is pushed — same class of fix as
  ADR-2607022800 Phase 3's stale-`-clj`-coordinate sweep.
- If an actor bootstrap flow later wants `pubkey-from-seed`-style derivation
  producing an IPNS name too (not just did:key), that composition already
  works today: `(ipns/pubkey->name (ed25519/pubkey-from-seed seed))` — no
  new library code needed.

## One-line summary

**IPNS-name derivation gets one home (`kotoba-lang/ipns`, new); JVM did:key
derivation keeps its one existing home (`kotoba-lang/ed25519`, via `cacao`'s
established pattern) instead of gaining a second; `kotoba-lang/did` gets used
for what it's actually for (DID Documents) in `kotoba`; `kotobase-client`'s
independent implementation stays, because its reason to be independent is
real.**
