# ADR-2607072645: kotoba's package CID pinning becomes real verification, not shape-only

## Status

Accepted (implemented)

## Context

Owner directive: continue raising aiueos/kototama/kotoba-lang maturity.
A prior assessment of `kotoba-lang/kotoba`'s package-admission system
found: "package_admission.clj validates lock/manifest/trust shape ...
but every CID in the test fixtures is a literal placeholder string ...
There is no IPFS/content-addressing code anywhere in the repo ... The
'package registry' is purely a signature/shape-validation gate over
locally-supplied EDN; it is not a working content-addressed dependency
system." `kotoba-lang/kotoba-lang`'s own `ADR-kotoba-package-cid-lock.md`
places the system at maturity milestone **M4** ("manifest-driven package
contract runner") on an M0–M6 ladder, with **M5+ ("a registry or CLI
consumes the same suite") and real fetch/pin/resolve explicitly
"entirely greenfield."**

Two concrete findings shaped scope. First,
`kotoba.lang.package-contract/cid?` was:
```clojure
(defn cid? [x] (and (non-empty-string? x) (str/starts-with? x "bafy")))
```
— a prefix sniff that never decoded anything. Every fixture CID (e.g.
`"bafyrepojson111111111111111111111111111111111111111111111111"`)
contains characters (`0`/`1`/`8`/`9`) outside the base32 `b`-multibase
alphabet and could never have decoded as a real CID — this check would
have accepted them regardless. Second, even a genuinely well-formed CID
proves nothing about the content it claims to pin: `wasm emit`/`run`'s
admission gate never read a manifest's bytes and compared them against
anything. "CID pinning gives integrity" (the design ADR's own words)
was aspirational, not enforced.

Building the M5 registry/fetch layer was explicitly out of scope for
this work (real content-addressing infrastructure, greenfield, its own
multi-session effort). What's in scope and delivered here: make the
CIDs that already exist in M4's shape real, and verify local content
against them — the part of "integrity" that doesn't require a network.

## Decision

### 1. `cid?` decodes and structurally validates (`kotoba-lang/kotoba-lang#13`)

Now: decode via `multiformats.core/cid->bytes` (a real, Kubo-verified
multibase/multihash implementation this org already has, not
reimplemented) and parse `[version-varint][codec-varint][multihash:
fn-varint len-varint digest]`, requiring version 1 and that the
multihash's declared digest length actually matches the decoded byte
count. Every placeholder CID across `kotoba-lang`'s and `kotoba`'s test
fixtures (22 distinct placeholder strings total across both repos) was
regenerated with genuine `multiformats.core/cidv1-dag-cbor` output —
the old placeholders now correctly fail.

### 2. Manifest content is verified against its own pinned CID (`kotoba#290`)

New `kotoba.package-admission/manifest-integrity-error`: recomputes a
manifest's real CID (canonical DAG-CBOR encoding via `kotoba-lang/
dag-cbor`, then `multiformats.core/cidv1-dag-cbor`) over the manifest's
own content and compares it against the manifest's self-declared
`:kotoba.package/source :manifest-cid`. The computation excludes the
`:manifest-cid` field itself before hashing — the same reason a git
commit's hash never covers its own hash, or an IPFS DAG node's CID
never covers its own CID field; including it would make the value
depend on itself. Wired into `verify-lock` as a new
`:package/manifest-cid-mismatch` problem, checked only once the shape
gate (`package-manifest-error`) already confirmed `:manifest-cid` is
CID-shaped at all — a missing/malformed field is that check's problem
to report, not a mismatch (there's nothing valid to mismatch against).

This closes a real detection gap `cid?`'s structural fix alone cannot:
a manifest edited without updating its pinned CID, or a CID
copy-pasted from an unrelated package, is a perfectly well-formed
CIDv1 — just not *this* content's. `test/fixtures/package/
positive-manifest.edn`'s `:manifest-cid` is now genuinely
self-consistent (its declared value really is its own content's
computed CID), verified by a dedicated test, not a placeholder that
merely happens to pass shape validation.

## Consequences

- (+) "CID pinning gives integrity" is now literally true for local
  content, not aspirational prose in a design ADR. A tampered manifest
  — content changed, pin left stale — is rejected; it previously was
  not (the shape check only asked "is this string CID-shaped," never
  "does this string match this content").
- (+) `kotoba` gained real, reusable content-addressing primitives
  (`compute-manifest-cid`) other package-admission work can build on —
  e.g., a future `:dep/tree-cid` check against an actual fetched source
  tree, once M5's fetch layer exists.
- (−) Coverage is manifest-only. `:dep/tree-cid`/`:dep/repo-rid`/
  `:component-cid` (in both the lock and the manifest's own `:source`)
  remain shape-checked only — verifying them requires the actual tree/
  repo/compiled-component bytes to hash against, which in turn requires
  M5's fetch/resolve layer this ADR deliberately does not build.
- (−) No network fetch, no registry, no `kotoba-rad` repo-identity
  integration — M5+ is unchanged, still entirely greenfield. This ADR
  advances integrity WITHIN M4, it does not advance the milestone.

## Follow-up

- M5 (a real `kotoba-lang/registry` or CLI-consumed name/version→CID
  index, and actual content fetch/pin/resolve) remains unstarted,
  correctly scoped as separate, larger work.
- Once tree content is fetchable, extend `manifest-integrity-error`'s
  pattern (recompute, compare, reject on mismatch) to `:dep/tree-cid`/
  `:component-cid` — the verification shape is already proven, only the
  "get the actual tree bytes" half is missing.

## One-line summary

**`kotoba.lang.package-contract/cid?` went from a `"bafy"`-prefix sniff
to a genuine CIDv1 decode+structural-validate (kotoba-lang/kotoba-lang#13,
using this org's own Kubo-verified `io-multiformats`); `kotoba.package-
admission` now recomputes and verifies a manifest's real content against
its own pinned CID (kotoba#290, canonical DAG-CBOR + CIDv1) instead of
only checking the pin's shape — closing the gap between "pinning gives
integrity" as design-ADR prose and as an enforced fact, entirely within
the existing M4 milestone, with M5's registry/fetch layer correctly left
out of scope.**
