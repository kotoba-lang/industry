# ADR-2607050400: WebAuthn ↔ CACAO — already peers, missing a real verifier, and a genuine repo-graduation gap found along the way

**Status**: accepted (implemented; repo-graduation follow-up completed
2026-07-09 — see Addendum; one sub-item of that follow-up still open)
**Date**: 2026-07-05 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Context

The question was how `kotoba-lang/authentication`'s WebAuthn port and
`kotoba-lang/kotoba`/`kotobase`'s CACAO authentication (SIWE/EIP-4361 over
Ed25519 `did:key`, self-issued, no owner hand-off) should connect. Reading
`authentication`'s actual code found the architecture question was already
answered, just not documented as a deliberate decision, and found one real
gap and one real implementation hole along the way.

### They're already peers — `authentication.core` already composes them uniformly

`authentication.ports/IFactorVerifier` is one protocol
(`verify-factor! [factor-request response] → factor`) that **every**
factor type implements identically: `webauthn-verifier`, `faceid-verifier`,
`touchid-verifier`, `onetime-verifier`, `oauth-verifier`, `oidc-verifier`,
`saml-verifier`, and **`cacao-factor-verifier`** — all in
`authentication.adapters.external-factors`/`authentication.adapters.cacao`,
all composed the same way through `authentication.core/authenticate`
(verify each requested factor, then `decide` against a required assurance
level). `:cacao` is already a first-class `authentication.model/factor-types`
member. **No architectural change needed here** — WebAuthn and CACAO were
never in tension, they were designed as peers from the start.

### `achieved-level` correctly excludes `:cacao` from `:phishing-resistant` — and nobody had said why

`authentication.core/achieved-level` grants `:phishing-resistant`
automatically for `#{:webauthn :passkey}` but not for `:cacao`, even though
a CACAO is "just" an Ed25519 signature — no shared secret, no password,
public-key crypto same as WebAuthn. The reason this is *correct*, not an
oversight: **WebAuthn's phishing resistance comes from origin/RP-ID binding
enforced by the browser/OS below the application**, not from public-key
crypto alone — a phishing site simply cannot obtain a valid assertion for
the real origin, full stop, no user action can override it. SIWE/EIP-4361
(what `cacao.core/mint`'s `siwe-message` produces and signs) has no
equivalent enforcement layer: the signing wallet/agent has no cryptographic
way to confirm the requesting page is who it claims to be in the `aud`
field — a malicious relay can present a legitimate-looking SIWE prompt for
its own `aud` and a human signer has no OS-enforced guarantee against it.
This is a known, real distinction in the broader security community, not
specific to this codebase — and now it's written down here instead of
silently living only in `achieved-level`'s source code.

(For **kekkai/kagi's actual usage** — an actor self-issuing its own CACAO
with no human approval step at all — "phishing" doesn't apply the same way;
there's no human being asked to sign anything. `authentication`'s factor
framework models the general, human-in-the-loop case correctly regardless.)

### The real gap: no production `ICacaoVerifier` existed

`authentication.adapters.cacao` had `cacao-factor-verifier` (generic, takes
any `ICacaoVerifier`) and `static-cacao-verifier` (a **test-only stub**
returning canned claims) — but **no adapter that calls real CACAO
verification**. Every other factor type in `external-factors.cljc` has both
a generic wrapper *and* is exercised against something real (or an
explicitly injected verifier port for host-side crypto); CACAO alone had
only the stub. `:cacao` was a factor type in name and plumbing, with no
working implementation behind it.

### An unrelated but material discovery: 8 "repos" aren't repos

Investigating `authentication`'s dependencies to wire in `cacao` found that
`kotoba-lang/authentication` itself, and every one of its direct
dependencies (`webauthn`, `faceid`, `touchid`, `onetime`, `oauth`, `oidc`,
`saml`), have **no `.git` of their own** — they are plain files committed
directly into `com-junkawasaki/root`'s own tree at `orgs/kotoba-lang/*`,
with no GitHub repo and no `manifest/west.yml` entry. Every *other*
`kotoba-lang/*` path this session touched (`ed25519`, `did`, `cacao`,
`quad-store`, `kqe`, `kotobase-engine`, `kekkai`, `kagi`, `kotoba`, the new
`ipns`) is a real, independently-pushed, west-registered repo — this cluster
of 8 is the exception, not the rule. They read as fully scaffolded
(real `README.md`/`MATURITY.md`/`deps.edn`/`src`/`test`, genuine working
code, 17 passing tests before this ADR) but never graduated through the
standard `git init → GitHub repo create + push → manifest registration`
flow this codebase otherwise follows for every new library. **Not fixed in
this ADR** (see Follow-up) — graduating 8 repos is a separate, larger piece
of work than a WebAuthn↔CACAO connection decision, and doing it hastily
under a different ADR's heading would bury a decision that deserves its own
record.

## Decision

1. **No unification of WebAuthn and CACAO as a single mechanism.** They stay
   two independent `IFactorVerifier` implementations, composed by
   `authentication.core` exactly as today. This is confirmed correct, not
   changed.
2. **`achieved-level` keeps excluding `:cacao` from `:phishing-resistant`.**
   Confirmed correct (see Context) — no code change, this ADR is the
   documentation that was missing.
3. **New `authentication.adapters.cacao/production-cacao-verifier`**: a real
   `ICacaoVerifier` bridging to `kotoba-lang/cacao`'s `cacao.core/verify`
   (single self-issued CACAO, via `:cacao/cacao-b64` in the factor response)
   and `cacao.core/verify-chain` (an ordered delegation chain, via
   `:cacao/chain`). Pure/offline, no injected port needed (unlike
   webauthn/faceid/touchid, whose crypto lives on a device this code can't
   reach directly) — did:key CACAO verification is self-contained crypto.
   Assurance from this verifier is always capped at `:single-factor`
   (never `:phishing-resistant`, matching point 2's reasoning; a caller
   composing CACAO with a second independent factor gets `:multi-factor`
   from `achieved-level`'s own type-count logic, unaffected by this cap).
   `authentication`'s `deps.edn` gains a real dependency on
   `io.github.kotoba-lang/cacao` (git-sha pinned — `cacao` itself *is* a
   real repo, unlike `authentication`'s other, embedded-in-root
   dependencies). 3 new tests (real mint→verify round trip, tamper
   rejection, delegation-chain verification) — 20 tests / 49 assertions
   green for the whole `authentication` suite.
4. **Deferred, not implemented: WebAuthn-backed CACAO signing keys.** A
   WebAuthn credential using the Ed25519 COSE algorithm (`-8`) produces a
   real Ed25519 keypair — in principle, an actor's `did:key` signing key
   *could* be hardware-backed (Secure Enclave/TPM) by a passkey instead of
   `kekkai`/`kagi`'s current software-generated,
   file-persisted key. This is a genuinely deeper integration than "another
   factor," but it needs a **new CACAO wire signature-type**: WebAuthn
   assertions sign `authenticatorData || SHA256(clientDataJSON)`, not a raw
   message the way `cacao.core/mint`'s `ed25519.core/sign` does — you cannot
   feed a WebAuthn assertion into the existing `s.t = "EdDSA"` verification
   path unchanged. That's a real, riskier change to an already-shipped wire
   format, with no current caller asking for hardware-backed actor keys.
   Not fabricated here; recorded as a real, well-defined follow-up instead.

## Consequences

- (+) `:cacao` is now a real, working factor type end to end, not plumbing
  around a test stub — `production-cacao-verifier` is what a real deployment
  wires into its `authentication.core/authenticate` verifier map.
- (+) The WebAuthn/CACAO relationship, and the reason `:cacao` doesn't get
  `:phishing-resistant` for free, are now both written down instead of
  living only as an implicit property of `achieved-level`'s source and
  `cacao-factor-verifier`'s never-implemented production path.
- (+) The 8-repo embedding discovery is now on record rather than something
  the next person re-discovers by accident (as this ADR's own investigation
  did) — see Follow-up.
- (−) A CACAO-authenticated request can never reach `:phishing-resistant`
  alone under this decision — if a future deployment genuinely needs
  phishing-resistant assurance from a self-sovereign key without WebAuthn,
  that requires either the hardware-backed-key integration (point 4,
  deferred) or a documented case for why a specific deployment's CACAO
  issuance flow *does* have equivalent origin-binding (not the general case
  this ADR addresses).

## Follow-up

- **Graduate `kotoba-lang/authentication` + its 7 embedded-in-root
  dependencies** (`webauthn`, `faceid`, `touchid`, `onetime`, `oauth`,
  `oidc`, `saml`) to real, independent, west-registered repos via the
  standard scaffold → `git init` → GitHub push → manifest-registration flow
  — the same standing-authorization workflow this session used for the new
  `kotoba-lang/ipns` repo. Order matters (`authentication` depends on all
  7 others); do the 7 leaves first, then `authentication` last, converting
  its `:local/root` deps to real git-sha coordinates as each lands.
- WebAuthn-backed CACAO signing keys (point 4) — design the new wire
  signature-type as its own ADR against `cacao.core`, if/when an actual
  caller wants hardware-backed actor identity keys.

## Addendum (2026-07-09) — repo-graduation follow-up carried out

The first Follow-up bullet above is now done. `kotoba-lang/authentication`
and all 7 of its embedded-in-root dependencies each got a real `.git init`,
a pushed GitHub repo, and a `manifest/west.yml` entry:

| repo | HEAD (pushed) |
|---|---|
| `webauthn` | `001fa11` |
| `faceid` | `4bebc9e` |
| `touchid` | `c2ef9a1` |
| `onetime` | `011b3c4` |
| `oauth` | `e47999a` |
| `oidc` | `b224d19` |
| `saml` | `7f04362` |
| `authentication` | `e5ee5ec` |

Beyond the graduation this ADR called for, 6 of the 7 leaves (every one
except `onetime`) went a step further and split off a **raw
external-spec-substrate** sibling repo, following the
`org-materialx`/`org-w3-webgpu` precedent (ADR-2607051400) rather than
something invented for this ADR:

- `oauth` → `org-ietf-oauth2` (RFC 6749/PKCE)
- `oidc` → `org-openid-oidc` (OpenID Connect Core)
- `saml` → `org-oasis-saml` (OASIS SAML 2.0)
- `webauthn` → `org-w3-webauthn` (W3C WebAuthn)
- `faceid` → `com-apple-faceid` (LocalAuthentication FaceID protocol)
- `touchid` → `com-apple-touchid` (LocalAuthentication TouchID protocol)

Each pair now cross-references both directions in its README: the raw-spec
repo says it's the zero-deps "spec as data" layer consumed by the
result-shape repo, and the result-shape repo says it's consumed in turn by
`kotoba-lang/authentication`. `onetime` did not need a new sibling — its own
README already states TOTP/HOTP (RFC 4226/6238) generation lives in the
pre-existing `kotoba-lang/authenticator` repo, so the raw-spec/result-shape
split for one-time codes already existed before this ADR. All 6 new sibling
repos are pushed and west-registered alongside the 8 graduated repos (14
`manifest/west.yml` entries total for this cluster).

**Still open, not done in this pass**:

- The Follow-up text said to convert `authentication`'s `:local/root` deps
  on the 7 leaves to real git-sha coordinates "as each lands" (the same
  treatment its `cacao` dependency already got in Decision point 3).
  `authentication/deps.edn` still reads `{:local/root "../faceid"}` etc. for
  all 7 — it happens to work because west checks every kotoba-lang project
  out as a sibling directory under `orgs/kotoba-lang/`, but that's an
  accident of layout, not the git-sha pin this ADR decided on. Left as an
  open item rather than done silently in an ADR-documentation pass.
- `manifest/west.yml`'s recorded pin for `saml`, `webauthn`, `touchid`,
  `authentication`, and `onetime` is behind each repo's actual (pushed)
  HEAD shown in the table above (`oauth`/`oidc`/`faceid`'s pins already
  match). Needs the usual `nbb scripts/gen-west-manifest.cljs --entry <name>`
  pin-advance pass; not done here to keep this addendum scoped to the ADR
  record itself.
- `com-junkawasaki/root`'s own git history still carries these 8 repos'
  `README.md` (and, for the 6 that gained `.github/`/`LICENSE`/`.gitignore`
  during graduation, those files too) as directly-committed content from
  before graduation — the same "embedded, not west-managed" state this
  ADR's Context originally flagged. Now that all 8 have real `.git`
  identity and a west entry, whether to `git rm --cached` the superproject's
  duplicate copies (matching how every other `kotoba-lang/*` project is
  untracked-by-design in the superproject) is a separate decision, not made
  here.

## One-line summary

**WebAuthn and CACAO were already correctly composed as peer factors —
this ADR documents that decision and the (correct) reason CACAO alone never
grants phishing-resistant assurance, then closes the one real gap found:
`authentication.adapters.cacao` had no production verifier, only a test
stub, despite `:cacao` being a first-class factor type. Along the way,
`authentication` and 7 of its dependencies turned out to not be real repos
at all — scaffolded but never graduated — recorded as its own follow-up
rather than silently expanded into this ADR's scope.**
