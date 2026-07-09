# ADR-2607050900: kotoba-lang org naming audit — IPLD/Datomic/IPNS/IPFS/Clojure-stdlib clusters

**Status**: accepted (audit; no renames executed by this document)
**Date**: 2026-07-05 (session-numbered; see repos.edn ADR numbering convention)
**Deciders**: Jun Kawasaki

## Context

Following ADR-2607050700/2607050800's rename work (RDF-flavored/generic
names → Datomic vocabulary), the owner asked for a wider sweep: across
the whole `kotoba-lang` GitHub org (415 registered repos as of this
audit), are there other repos whose naming has drifted from the
IPLD/Datomic/IPNS/IPFS/Clojure conventions this substrate has been
converging on, or that duplicate/overlap each other?

Method: every repo's own README was fetched and read directly (not
inferred from the name alone) for the candidate clusters below — the
content-addressing core, the Datomic-parallel database layer, the
identity/naming layer, and the foundational Clojure stdlib. The ~350
remaining repos (3D/robotics `kami-*` engine family, ISIC industry-
vertical libs, format/codec libs unrelated to any of the five themes)
were **not** individually audited — this is a scoped sweep, not an
org-wide census.

## Findings

### Healthy — no action needed

- **Content-addressing core**: `ipld`, `dag-cbor`, `multiformats`,
  `prolly-tree`, `arrangement`, `datom`, `kotobase-peer`, `chain` —
  already covered by ADR-2607050700/2607050800. Consistent Datomic
  vocabulary, no residual RDF-flavored naming, no overlap.
- **`ipns` / `did`**: exemplary cross-documentation. `ipns`'s own README
  states it is "the naming half of the same discipline `kotoba-lang/did`
  covers for `did:key` — the two are siblings derived from the same raw
  pubkey, kept as separate libraries because they are different specs,"
  and explicitly notes it replaced duplicated derivation code that used
  to live independently in `kekkai`'s `cacao.clj` and `kagi`'s
  `identity.clj`. This is the pattern the rest of the org should copy —
  a library's README stating *why* it's separate from its nearest
  neighbor, not just what it does in isolation.
- **`lint` / `lint-kotoba`**: also exemplary. `lint-kotoba`'s README
  states directly: "`lint` formats and parses... `lint-kotoba` is the
  source-level static analysis that sits between" `lint` and `wit`'s
  over-grant checking, and depends on `lint`. Despite the near-identical
  names, there is zero ambiguity about which does what.
- **`mst` / `atproto`**: `mst` explicitly scopes itself to pure MST
  primitives only, stating "the byte/CID/CAR layer belongs in
  `kotoba-lang/atproto`."
- **`ipfs` / `atproto-client`**: `atproto-client`'s README explicitly
  contrasts its own `IHttp` port design against `ipfs`'s (single
  `-request` vs. `-get`/`-post`/`-post-file`; string vs. byte bodies),
  reasoned from XRPC's needs vs. Kubo's — a real, stated design
  comparison, not silent duplication.
- **`witness-quorum`**: worth calling out as a positive precedent, not a
  problem. Its README states it was deliberately named `witness-quorum`
  instead of echoing its original directory name `kotoba-datomic`,
  "to describe what it actually does and avoid echoing the name of the
  now-deleted Rust crate" it was mistakenly assumed to reimplement. This
  is exactly the naming discipline ADR-2607050700/2607050800 applied —
  good evidence the practice already exists elsewhere in the org, just
  not everywhere.

### Naming drift / confusability (real, not yet acted on)

- **`kotodama*` vs `kototama*` — undocumented romanization split.**
  `kotodama` (5 sibling repos: `-cells`, `-holochain`, `-host`, `-mcp`,
  `-py`) and `kototama` (2 repos: `kototama`, `kototama-cljc-contract`)
  are two different romanizations of the same Japanese word (言霊,
  "word-spirit") — thematically on-brand for a project called "kotoba"
  (言葉, "word/language"), which makes the two families *more* likely to
  be confused, not less. Checked both sides directly: `kotodama`'s
  README mentions "kototama" exactly once, as `etzhayyim/kototama` — a
  repo in a **different GitHub org**, cited as historical origin, not as
  an explanation for the spelling split. `kotoba-lang/kototama`'s own
  README never mentions `kotodama` at all. Neither side documents *why*
  both spellings persist as separate, non-cross-referenced repo
  families. This is the clearest finding in this audit.
- **`kotoba-client` / `kotobase-client` / `kotobase` — three similarly
  named, genuinely different repos with no explicit disambiguation.**
  Reading all three READMEs independently (since none of them names or
  contrasts itself against the other two):
  - `kotobase` = the server-side `IStore` port seam.
  - `kotobase-client` = a CACAO-authed client for the `kotobase.net`
    tenant Datom plane, de-forking code that used to be hand-copied into
    `app-aozora`/`app-aozora-boundary`/`kami-genko`.
  - `kotoba-client` = a *generic, non-CACAO* CID-verified block
    ingest/hydrate client over kotoba's content graph, consumed by
    `p2p`.
  These are real, distinct, correctly-scoped repos — the finding isn't
  duplication of function, it's duplication of *naming surface*: a
  reader (or a new contributor) has no way to tell `kotoba-client` and
  `kotobase-client` apart from the name alone, and `kotobase` sits one
  distinguishing syllable away from both. None of the three READMEs
  cross-references the other two the way `ipns`↔`did` or
  `lint`↔`lint-kotoba` do.

### Likely stale / low-value duplication

- **`kotoba-v2025`** — self-described as "Legacy Reference," a
  historical Rust workspace pursuing an entirely different, abandoned
  design (JSON-LD + OWL inference via a "fukurow" reasoner, a
  Kernel/Actor/Mediator "semanticos" pattern). Its own README correctly
  redirects readers to `kotoba-lang/kotoba` and `kotoba-lang/kotoba-lang`
  as the active repos. The repo itself is correctly labeled and not a
  live naming conflict — but it appears **twice** in
  `manifest/repos.edn`'s `:extra-projects` list (once at line ~79,
  again at line ~563), a repos.edn hygiene bug (harmless today since
  `render()` de-duplicates via `distinct`, but worth a one-line cleanup
  commit whenever that file is next touched for an unrelated reason).
- **Three scaffold repos share identical placeholder boilerplate**:
  `kotoba-adapter-contracts`, `kototama-cljc-contract`, and
  `kotoba-lang-cli-contract` all have the exact same one-line README:
  "KAMI clj-wgsl migration Phase 4 home (ADR-2607010930) — scaffold,
  Wave-2 port lands here." This may be intentional (three genuinely
  distinct landing zones for the same migration wave, not yet
  populated) or may indicate scaffold repos created ahead of need that
  never got differentiated. Not a naming problem in itself, but the
  identical boilerplate makes it impossible to tell from the outside
  which of the three (if any) is still an active target.

### Placement question (org, not naming)

- **`witness-quorum`**: its own README self-identifies as
  `@etzhayyim/witness-quorum`, and states it was relocated from
  `etzhayyim/root`. Per `repos.edn`'s own org taxonomy (ADR-2606302300):
  `kotoba-lang` = language-substrate/platform, `etzhayyim` =
  agent-centric/public-interest. Deterministic witness selection +
  quorum + Ed25519 attestation reads as agent-centric/public-interest
  infrastructure, not core language substrate — worth a follow-up
  question (not answered by this audit) about whether it belongs in
  `kotoba-lang` at all, independent of its (good) name.

### Noted but not a problem

- **`kotoba-lang` (repo) sharing its name with the org itself.** Its own
  README explains the split clearly ("split from `kotoba-lang/kotoba`
  so the language surface can be reviewed independently from the
  current compiler/runtime/server/mesh implementation") — documented,
  not accidental. Flagged only because an org-named repo is an inherent
  discoverability hazard for anyone searching "kotoba-lang" and landing
  on the wrong result; not a naming-quality problem to fix, just a
  standing minor friction to be aware of.
- **`kotoba-topology`**: an arXiv-style LaTeX paper ("Languages as Graph
  Topologies," cross-linguistic syntax analysis) that happens to share
  the `kotoba`/`topology`-adjacent naming with the software org. No
  stated relationship to any other repo in the org — reads as an
  unrelated academic artifact that happens to live in the same org,
  rather than a naming conflict with any specific software component.
- **`sql`**: "Kotoba DSL package for `kotoba.sql`... a compatibility
  facade" over `sql.core`. Exists alongside `rdf`/`sparql`/`shacl` as
  another query-vocabulary member; given this session's explicit
  Datomic-over-RDF priority, `sql` isn't itself mis-named (SQL is its
  own real, distinct vocabulary, not a competing Datomic/RDF term), but
  it's worth remembering it exists the next time query-layer naming
  gets revisited.

## Consequences

- (+) Two exemplary cross-documentation patterns already exist in the
  org (`ipns`↔`did`, `lint`↔`lint-kotoba`) — the fix for the
  `kotodama`/`kototama` and `kotoba-client`/`kotobase-client`/`kotobase`
  findings isn't a new practice, it's applying an existing one.
- (+) No functional/dependency-level duplication was found — every
  cluster investigated turned out to implement genuinely different
  scope once its README was actually read, including the ones with
  confusable names. This audit found a *documentation* gap, not a
  *duplicated-effort* gap.
- (−) `kotodama`/`kototama` is a real, unresolved point of confusion for
  anyone new to the org — this audit does not resolve it (no rename
  executed), it only confirms the drift and that neither side explains
  it.
- (±) The `kotoba-v2025` duplicate list entry and the three identical-
  boilerplate scaffold repos are low-severity hygiene items, not naming
  decisions — left as noted findings rather than acted on, since fixing
  them wasn't the ask.

## Follow-up (recommended, not yet authorized)

- Add a short cross-reference note to `kotodama`'s and `kototama`'s
  READMEs (mirroring `ipns`'s own pattern) explaining the spelling
  split and each repo's actual scope relative to the other.
- Add a one-line disambiguation section to `kotobase`, `kotobase-client`,
  and `kotoba-client`'s READMEs cross-referencing the other two (same
  pattern `atproto-client` already uses against `ipfs`).
- Dedupe the `kotoba-v2025` line in `manifest/repos.edn`'s
  `:extra-projects` (minimal diff, `--entry` not needed since it's a
  pure list-hygiene fix, not a pin change).
- Confirm whether the three identical-boilerplate scaffold repos
  (`kotoba-adapter-contracts`, `kototama-cljc-contract`,
  `kotoba-lang-cli-contract`) are still live migration targets or can be
  retired/merged.
- Revisit `witness-quorum`'s org placement (`kotoba-lang` vs
  `etzhayyim`) against ADR-2606302300's taxonomy.

## Follow-up (2026-07-08) — scaffold-repo status investigated

Investigated whether `kotoba-adapter-contracts`, `kototama-cljc-contract`,
and `kotoba-lang-cli-contract` are still live migration targets:

- All three exist as their own correctly-named GitHub repos, but each is
  `"size":0` — nothing beyond the identical placeholder README was ever
  pushed. No `deps.edn`, no `src`.
- The placeholder text cites "KAMI clj-wgsl migration Phase 4... Wave-2
  port lands here" (ADR-2607010930), but none of the three repo names
  appears anywhere in that ADR's `.md` or `.edn` (grepped both), and
  "Wave-2" as a term doesn't appear in it either — the only "wave"
  language there is Phase 7's unrelated "ambitious-scope wave closed:
  113/116 kami-engine crates restored." The migration that ADR tracks has
  since progressed through Phase 7 by a different route, without ever
  populating these three.
- `kotoba-adapter-contracts`'s naming pattern (`kotoba-*-contracts`) is a
  real, live convention elsewhere in the org — `kotoba-core-contracts` and
  `kotoba-selfhost-contracts` are both real, populated CLJC/EDN-authority
  repos — so this one specifically reads as a legitimate scaffold that
  simply never got filled in, not a naming mistake.
- **Found and fixed a real, separate bug while investigating**: the local
  west checkouts for `kototama-cljc-contract` and `kotoba-lang-cli-contract`
  had their git `origin` remotes misconfigured to point at `kotoba-lang/
  kototama.git` and `kotoba-lang/kotoba-lang.git` respectively (unrelated,
  actively-developed repos) instead of their own repos. The checked-out
  content itself was correct (verified identical to each repo's real
  `origin/main`), so no data was at risk from this session's use, but a
  future `git push` from either directory would have silently pushed
  scaffold content into the wrong (real, active) repo. Corrected both
  remote URLs; re-verified fetch and content match.

**Recommendation (not executed here): retire all three** — archiving or
deleting a GitHub repo is a more consequential, less reversible action
than this pass's fixes, so it's left as an explicit, owner-confirmable
follow-up rather than done unilaterally. If the `adapter-contracts` /
`cli-contract` scopes are still wanted, they should be re-scaffolded
fresh against the *current* migration plan rather than reusing these
empty, stale-citation placeholders.

## Follow-up (2026-07-08) — witness-quorum org-placement question resolved

Re-examined `witness-quorum`'s residence in `kotoba-lang` against
ADR-2606302300's actual placement criteria, rather than domain-name
intuition:

- ADR-2606302300 defines `kotoba-lang` as "language substrate, consumed
  by all orgs" — the decisive test is genericity/parameterization, not
  whether a library's *subject matter* (witness selection, quorum,
  attestation) sounds agent-flavored.
- `witness-quorum`'s own README already states this directly: "Zero
  etzhayyim-specific coupling beyond an NSID string constant and a
  \"council\" escalation label — every fleet topology, membrane rule,
  and signer is a caller-supplied parameter." `WitnessTransport` is
  "a documented plain map of functions," and the production HTTP/PDS
  transport was *deliberately not ported* so the package doesn't
  hard-code any one deployment's shape.
- Its stated real target consumer is "Murakumo babashka cell-runners" —
  `kotoba-lang/murakumo`, itself a `kotoba-lang` cross-cutting
  infra repo, not an `etzhayyim` actor.
- This matches — and was the actual justification for — its 2026-07-01
  relocation *from* `etzhayyim/root` *to* `kotoba-lang` in the first
  place (per the README's own "Provenance" section), applying
  ADR-2606302300's rule as designed.

**Conclusion: no move needed.** The naming-audit ADR's placement
question is resolved as "already correctly placed" — surface-level
domain vibes (witness/quorum/attestation "sounds" agent-centric) don't
override the taxonomy's actual test (generic + parameterized + already
consumed by kotoba-lang's own infra). This closes the last open
follow-up from this ADR.

## One-line summary

**A scoped naming sweep of `kotoba-lang`'s IPLD/Datomic/IPNS/IPFS/
Clojure-stdlib clusters found no functional duplication — every
confusable-looking pair, once its README was actually read, turned out
to be genuinely distinct — but two real documentation gaps: the
undocumented `kotodama`/`kototama` romanization split, and the
`kotoba-client`/`kotobase-client`/`kotobase` naming-surface collision,
neither of which cross-references its neighbors the way `ipns`↔`did` and
`lint`↔`lint-kotoba` already exemplarily do. A `kotoba-v2025` duplicate
manifest entry, three identically-boilerplated scaffold repos, and
`witness-quorum`'s org placement are noted as minor, lower-priority
hygiene items.**
