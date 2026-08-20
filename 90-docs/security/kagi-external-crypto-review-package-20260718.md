# kagi external cryptographic review package

Status: ready for independent reviewer; review not yet performed.

## Scope

Review the implementation—not only the algorithm names—of:

- hybrid X25519 + ML-KEM-768 combiner and transcript binding;
- hybrid Ed25519 + ML-DSA-65 signatures and downgrade resistance;
- AES-256-GCM nonce generation, AAD, DEK wrapping, revoke/rekey;
- real Argon2id parameters and legacy-envelope migration;
- identity custody, native key handles, Passkey PRF envelopes;
- rotation event canonicalization, event IDs, epoch rules and fork quarantine;
- threshold recovery approvals, witness checkpoints and gossip split-view detection;
- `kotoba -> aiueos -> kototama -> kagi` reference-only reveal boundary;
- memory lifetime, zeroization claims, error handling and side channels.
- kotobase atomic rotation/ledger transactions, R2 ETag CAS, CACAO capability,
  audience, lifetime and nonce-replay enforcement.

Out of scope unless separately commissioned: formal proof of JDK/BouncyCastle,
hardware-token firmware, OS Keychain, WebAuthn authenticator firmware, and physical attacks.

## Source manifest

- `orgs/kotoba-lang/kagi/src/kagi/crypto.clj`
- `orgs/kotoba-lang/kagi/src/kagi/identity.clj`
- `orgs/kotoba-lang/kagi/src/kagi/key_registry.cljc`
- `orgs/kotoba-lang/kagi/src/kagi/rotation.clj`
- `orgs/kotoba-lang/kagi/src/kagi/rotation_store.clj`
- `orgs/kotoba-lang/kagi/src/kagi/rotation_scheduler.clj`
- `orgs/kotoba-lang/kagi/src/kagi/rotation_main.clj`
- `orgs/kotoba-lang/kagi/src/kagi/recovery.clj`
- `orgs/kotoba-lang/kagi/src/kagi/witness.clj`
- `orgs/kotoba-lang/kagi/src/kagi/native_key.clj`
- `orgs/kotoba-lang/kagi/src/kagi/unlock.clj`
- `orgs/kotoba-lang/kagi/src/kagi/operation.cljc`
- reference adapters in `kotoba.kagi-boundary`, `aiueos.kagi-policy`, and
  `kototama.kagi-adapter`.
- `orgs/kotoba-lang/langchain/src/langchain/kotoba_db.cljc`
- `orgs/kotoba-lang/kotobase-cljc-worker/src/kotobase/cljc_worker/handler.cljc`
- `orgs/kotoba-lang/kotobase-cljc-worker/src/kotobase/cljc_worker/worker.cljs`
- `orgs/kotoba-lang/kotobase-cljc-worker/src/kotobase/cljc_worker/r2.cljs`
- `orgs/kotoba-lang/kotobase-server/src/kotobase/server/handler.cljc`

## Reproduction

From `orgs/kotoba-lang/kagi`:

```sh
clojure -M:test
clojure -M:lint
```

Current internal evidence is 100 kagi tests / 288 assertions, including the
hybrid-signed deployment-readiness evidence gate and opaque
signing-handle CACAO and hybrid-signing boundary tests, RFC 5869 HKDF-SHA256,
NIST AES-256-GCM, and pinned NIST ACVP FIPS 203 ML-KEM-768
decapsulation tc86 / FIPS 204 ML-DSA-65 signature-verification tc35 known-answer
tests with mutation negatives; 44 kotobase worker tests / 160
assertions; six passing browser-adapter unit tests (including credential, challenge,
origin, ceremony-type, UP/UV flag, downgrade, and zeroization checks); and zero known
npm audit vulnerabilities in the worker and Passkey module.
The JDK supplies ML-KEM-768, ML-DSA-65, Ed25519, X25519, AES-GCM and HKDF/HMAC;
Bouncy Castle 1.84 supplies Argon2id only. The worker build tool is
shadow-cljs 3.4.11.

The reviewer must still independently reproduce the pinned FIPS 203/204 vectors,
run cross-provider interoperability, mutation/fuzz tests for canonical event encodings,
and hardware-backed PKCS#11 integration. Internal round trips are not independent
interoperability evidence.

Terminology note: recovery is an M-of-N set of distinct hybrid
Ed25519+ML-DSA approvals, not a distributed-key threshold-ML-DSA construction.
The policy CID is part of the rotation event ID, both recovery and witness
policies must match it, and multiple member IDs may not resolve to the same
physical public-key bundle.

## Required findings format

Each finding must include severity, affected file/line, exploit preconditions,
reproduction, impact, recommended remediation, and whether existing ciphertext or keys
need migration. Avoid declaring the system “quantum safe” solely because ML-KEM and
ML-DSA appear in the suite.

## Acceptance gate

Production high-value use remains blocked until:

1. no unresolved critical/high findings remain;
2. medium findings have an owner and dated remediation or explicit risk acceptance;
3. KAT and independent-provider interoperability pass;
4. compromise recovery, fork and witness split-view exercises pass;
5. the reviewer signs the SHA-256/CID manifest of the reviewed commit and dependency lock.

Reviewer name, organization, report hash, reviewed commit, date and disposition must be
recorded in the companion ADR. An internal agent-generated document is not an external
review and must never populate those fields.

The independent reviewer emits, but does not install, the signed evidence and trust-root
fragments from a separately controlled kagi identity:

```sh
clojure -M:cli security-attest independent-crypto-review \
  <lowercase-sha256-of-final-report-and-reviewed-manifest> --issuer <reviewer-id>
clojure -M:cli security-trust-root --issuer <reviewer-id>
```

Deployment operators review and merge those fragments into separate
`.kagi/security-evidence.edn` and `.kagi/security-trust-roots.edn` stores. The
`security-check` command verifies the hybrid signature and exits 2 while any local or
external production gate is missing.
