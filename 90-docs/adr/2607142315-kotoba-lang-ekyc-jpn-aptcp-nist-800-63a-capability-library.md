# ADR-2607142315: `kotoba-lang/ekyc` — JPN 犯収法 / NIST SP 800-63A eKYC capability library

**Status**: accepted (landed)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki
**Scope**: `kotoba-lang/ekyc` (`orgs/kotoba-lang/ekyc`, west-managed), `manifest/west.yml` pin

## Context

`cloud-itonami-isic-6493` (a factoring-business governed actor, ISIC Rev.5
6493 — see ADR-2607141700 / ADR-2607142000) needs a real, spec-conformant
electronic KYC (identity verification) capability library to eventually
wire into its client/debtor intake flow — consistent with this fleet's
other financial actors, which already cite JPN/金融庁 as a seed
jurisdiction. No live identity-verification vendor connection is attached:
that requires a licensed operator with real vendor contracts, explicitly
out of scope for this codebase to fabricate. The requirement was code
genuinely identical to the real specification, researched from
authoritative primary sources, not an invented approximation.

`kotoba-lang/ekyc` was registered in `manifest/west.yml` (pin
`6b7a1b32...`) but, at the point this work started, GitHub's `main` already
carried a substantial, independently-built `ekyc.core`/`ekyc.model`/
`ekyc.ports`/`ekyc.adapters.*` implementation (a generic provider-session-
lifecycle substrate — host ports, EDN provider, Kagi evidence-custody
adapter, VC issuer, identity-ledger bridge — authored 2026-07-05–10,
depending on `kotoba-lang/identity`), not the empty LICENSE-only repo this
task's brief assumed. That implementation targets a different concern
(session/evidence lifecycle against a pluggable vendor host port) and does
not cite any specific regulatory framework. The new `kotoba.ekyc` namespace
added here is additive — it does not modify, replace, or depend on
`ekyc.core`'s files or its flat `:ekyc/*` keyword namespace (this addition
uses `:ekyc.method/*` `:ekyc.evidence/*` `:ekyc.verification/*`
`:ekyc.result/*`, avoiding collision).

## Research (primary sources, not recalled from memory)

**JPN — 犯罪による収益の移転防止に関する法律施行規則 (Act on Prevention of
Transfer of Criminal Proceeds, Enforcement Regulation) Article 6, Paragraph
1.** Full current consolidated statutory text retrieved via e-Gov 法令検索's
public data API (`GET /api/2/law_data/420M60000F5A001`, retrieved
2026-07-14, revision effective 2026-04-15), not paraphrased from a
secondary source. Cross-checked against 金融庁 (FSA) reference material
"犯罪収益移転防止法におけるオンラインで完結可能な本人確認方法の概要"
(`fsa.go.jp/common/law/guide/kakunin-qa/2.pdf`) and 警察庁 JAFIC guidance,
both independently confirming the same letter-to-method mapping. Ten
methods modeled: individual sub-items ホ (photo-ID image + live facial
image), ヘ (IC-chip read + live facial image), ト(1)/ト(2) (image-or-chip +
reliance on another operator's existing confirmation record / transfer to
the customer's own verified bank account), ル (カード代替電磁的記録 — the
digital My Number Card equivalent under 番号利用法), カ (J-LIS-issued
公的個人認証 signature certificate), ワ/ヨ (accredited / specifically
recognized private certification-business certificates); corporate
sub-items ロ (registry-information-service lookup) and ホ
(登記官-issued electronic certificate). A 2027-04-01 reform is scheduled to
abolish the ホ method and renumber the remaining letters — this catalog
models the law as currently in force, with ホ carrying an explicit
scheduled-abolition note rather than silently ignoring or pre-adopting the
change.

**NIST SP 800-63A-4** (*Digital Identity Guidelines: Identity Proofing and
Enrollment*, final, published July 2025 — the current revision, which
substantially restructured 800-63A-3's IAL framework: IAL1 now requires one
real evidence piece rather than "no proofing required"; IAL2 and IAL3 share
identical evidence-strength requirements, differing only in proofing-
attendance type and mandatory biometric-sample collection at IAL3). Full
text retrieved directly from `nvlpubs.nist.gov` and read (Table 1
requirements summary, Appendix A evidence-strength examples), not
summarized secondhand.

## Decision

1. Added `kotoba.ekyc` (+ `kotoba.ekyc.ui`, `kotoba.ekyc.export`) to
   `kotoba-lang/ekyc`, mirroring `kotoba-lang/banking` and
   `kotoba-lang/swift`'s exact capability-library shape: pure `.cljc`, no
   network/I/O, an OR-of-AND `required-evidence` shape matching the
   statute's "A の画像又はB の情報...とともにC" combinations, a read-only
   governor-gated operator console on `html`+`css`, RFC-4180 CSV + JSON
   export. 40 tests / 144 assertions (105 in `kotoba.ekyc.*` alone,
   exceeding `banking`'s 53 and `swift`'s 42), 0 clj-kondo warnings.
2. `assurance-level` is an honest, per-method, non-uniform NIST-IAL mapping
   reasoned from Appendix A's evidence-strength examples — not a fabricated
   constant: ホ→IAL1 (single document-strength piece, short of IAL2's
   2-piece/SUPERIOR floor); ヘ/ル/カ→IAL2 (IC-chip/cryptographic-credential/
   PKI-certificate evidence analogous to NIST's SUPERIOR examples — mDL,
   PIV Card, digital VC); ワ/ヨ→IAL2-conditional (assurance inherited from a
   private certifier's own proofing rigor, not independently verifiable);
   ト(1)/ト(2)→not-directly-comparable (a transitive-reliance/federation-
   like trust model outside 800-63A's single-proofing-event scope);
   corporate ロ/ホ→not-applicable (IAL is a natural-person framework). No
   individual method reaches IAL3: every 犯収法 electronic method is
   remote/software-mediated, and IAL3 mandates on-site attended proofing
   regardless of evidence strength.
3. `validate` returns `:fail` (never a fabricated fallback) for any method
   id outside the real catalog — the fabrication guard a `PolicyGovernor`
   needs before accepting a client/debtor.
4. Kept `kotoba-lang/ekyc`'s existing MIT LICENSE as-is (already attributed
   to the real copyright holder, already covering pre-existing shipped
   code) rather than switching to Apache 2.0 to match `banking`/`swift` —
   changing it now would retroactively relicense already-shipped code, out
   of this addition's scope; MIT does not conflict with coexisting
   alongside Apache-licensed sibling libraries.
5. Built on a sibling-path clone (`/tmp/ekyc-build/ekyc`, not the shared
   `orgs/kotoba-lang/ekyc` checkout — which was independently found to be in
   a stale, dirty local-commit state unrelated to this task and left
   untouched), branch `feat/ekyc-scaffold`, landed via server-side merge
   (`POST /repos/kotoba-lang/ekyc/merges`, no local rebase, no force-push).
   GitHub CI green post-merge (JDK 17 + 21 matrix).
6. `manifest/west.yml`'s `ekyc` entry pin advanced `6b7a1b3` → `3494b31` via
   the documented GitHub-API single-entry-PUT method (tip blob SHA fetched,
   only the `revision:` line edited, PUT with matching `sha=` — confirmed a
   single-line diff). Verified with `nbb scripts/gen-west-manifest.cljs
   --check` from a properly topdir-isolated sibling worktree (`west init -l
   manifest` + `west update --fetch smart ekyc` only, to avoid the
   superproject's other stale local checkouts producing unrelated noise):
   `west.yml is up to date.`

## Consequences

(+) `cloud-itonami-isic-6493` gets a governor-checkable, real-regulation
identity-verification method catalog with an honest (not fabricated)
NIST-IAL cross-reference, ready for a licensed operator to wire a real
vendor behind with minimal translation work.
(+) Coexists cleanly with the pre-existing `ekyc.core` provider-session
substrate discovered mid-task — different namespaces, different files, no
keyword-namespace collision, no destructive overwrite of prior work.
(+) Explicitly NOT covered, stated honestly in the README: real biometric
face-matching, real liveness detection, real document OCR/authenticity
forensics, real NFC/IC-chip reads, real My Number Card cryptographic
verification — this library validates only the structural/legal shape of a
verification record.
(−) Only JPN is modeled; other jurisdictions (EU eIDAS, US state-level
identity-proofing rules) are not covered and must not be inferred from this
catalog.
(−) The 2027-04-01 reform will require a follow-up revision (abolish ホ,
renumber remaining letters) — tracked as a known future change, not
modeled preemptively.
(−) The task's original premise ("repo has only LICENSE + .github") was
stale by the time work started; this ADR documents the actual state found
and the coexistence decision made, for anyone reconciling future task briefs
against repo reality.

## References

- `kotoba-lang/ekyc` `docs/adr/0001-architecture.md` (repo-level ADR, full
  citation trail and per-method IAL reasoning)
- Sibling capability-library convention: `kotoba-lang/banking`,
  `kotoba-lang/swift`
- Target actor: ADR-2607141700 (`cloud-itonami-isic-6493-factoring-actor`),
  ADR-2607142000 (murakumo worker deployment)
- "Missing jurisdictions are uncovered, never fabricated" convention:
  `cloud-itonami-isic-2816` docs/adr/0001
- `manifest/README.md`, `manifest/repos.edn` `:manifest-workflow` (west.yml
  single-entry-PUT method)
- 本 ADR とペアの `.edn`
