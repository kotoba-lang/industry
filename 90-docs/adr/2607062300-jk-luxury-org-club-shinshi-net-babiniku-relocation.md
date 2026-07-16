# ADR-2607062300: new GitHub org `jk-luxury` — relocate `club-shinshi` and `net-babiniku` out of `gftdcojp`; crypto-only payment rail for now

## Status
Accepted

## Context

`manifest/repos.edn` (`:orgs` §, taxonomy authority ADR-2606302300) already implicitly
distinguishes legal operating entities in comments even though it has never had a formal
`:orgs` entry for them: `orgs/gftdcojp/gftd-talent-actor` line ~1104 notes "itonami (Gftd Japan
株式会社, cloud-itonami) serves club-shinshi (**JK株式会社**)" — i.e. `club-shinshi`
(`shinshi.club`) is already, informally, operated under a *different* legal entity (JK株式会社)
than the rest of `gftdcojp`'s product line (Gftd Japan株式会社), even though its repo has lived
under the `gftdcojp` GitHub org since its ADR-2606010100-era split from
`ai-gftd-apps-gftdcojp`.

User direction (2026-07-06): created a new GitHub org, `https://github.com/jk-luxury`, and
directed that **`club-shinshi` and `net-babiniku` be relocated there, private**. This lands
in the same session as ADR-2607062200 (net-babiniku OnlyFans-style creator monetization) and
ADR-2607062210 (giemon humanoid embodiment/procurement) — both adult-content-adjacent,
creator-economy products. Separating them into their own org matches the pre-existing informal
JK株式会社/Gftd Japan株式会社 entity split already on record, giving the adult-content/creator
brand line its own GitHub-org boundary (billing, access control, and any future PSP/compliance
audit surface stay scoped to `jk-luxury`, distinct from `gftdcojp`'s other business lines).

Additionally, user direction (2026-07-06, same session): **for now, crypto payment only** —
narrower than ADR-2607062200's original "fiat via adult PSP, crypto out of scope for gftdcojp"
decision. This ADR records that amendment (see below); it does not reopen ADR-2607062200's
other decisions (content tiering, 80/20 split, HARD/SOFT governor, honest-default).

Repo state checked before acting (both admin-accessible, private, no GitHub Actions workflows,
no webhooks — deploys are manual `wrangler pages deploy`/`wrangler deploy` CLI invocations, not
GitHub-App-triggered CI/CD, so an org transfer carries materially lower operational risk than it
would for a repo with org-scoped Actions secrets or a GitHub-App-linked Cloudflare Pages
project):

- `gftdcojp/club-shinshi` — private, no `.github/workflows/`, no webhooks.
- `gftdcojp/net-babiniku` — private, no `.github/workflows/`, no webhooks; deploys via
  `wrangler pages deploy public --project-name net-babiniku` (README/wrangler.toml), no custom
  domain bound yet (`net-babiniku.pages.dev`).

## Decision

1. **New org `jk-luxury`** added to `manifest/repos.edn` `:remotes` and `:orgs`: `:role
   :human-centric` (same category as `gftdcojp` — real consumer products/creator brands), `:scope
   :business`, with a note that it is the GitHub home for the **JK株式会社** legal entity,
   carved out of `gftdcojp` (Gftd Japan株式会社) specifically for adult-content/creator-economy
   products, matching the pre-existing informal split already on record in
   `gftd-talent-actor`'s docs.

2. **`club-shinshi` and `net-babiniku` transfer from `gftdcojp` to `jk-luxury` on GitHub**
   (`gh api repos/{owner}/{repo}/transfer`), **remaining private**. Local checkouts move from
   `orgs/gftdcojp/{club-shinshi,net-babiniku}` to `orgs/jk-luxury/{club-shinshi,net-babiniku}`,
   matching the established path-override precedent for every prior GitHub-org transfer in this
   repo (`manimani`→`gftdcojp`, `cloud-manimani`→`gftdcojp`, `cloud-murakumo`→`local-murakumo`,
   `kototama`→`kotoba-lang`, etc. — all landed via `manifest/repos.edn` `:path-overrides` with a
   dated comment, not a silent in-place edit of the original `:extra-projects` entry).

3. **`manifest/repos.edn` `:path-overrides`** gets two new entries:
   `"orgs/gftdcojp/club-shinshi" -> "orgs/jk-luxury/club-shinshi"` and
   `"orgs/gftdcojp/net-babiniku" -> "orgs/jk-luxury/net-babiniku"`, each dated and reasoned,
   per the same convention as every prior transfer entry in that map. `net-babiniku`'s original
   `:extra-projects` entry (registered under ADR-2607051800) is left textually unchanged — the
   override is the record of the subsequent move, not a rewrite of history.

4. **`manifest/west.yml`** regenerated for exactly these two entries
   (`nbb scripts/gen-west-manifest.cljs --entry club-shinshi --entry net-babiniku`), not a wholesale
   regeneration — per the standing minimal-diff rule (ADR-2607022900).

5. **Amendment to ADR-2607062200's payment-rail decision: crypto-only for now.** Per direct
   user direction, net-babiniku's monetization launches with **only the crypto/USDC rail**,
   not the fiat/adult-PSP rail ADR-2607062200 specified as primary. The **repo boundary from
   ADR-2607062200 is unchanged and still binding**: self-custody on-chain settlement code may
   not be scaffolded in `net-babiniku` itself (now a `jk-luxury` repo, but the boundary was never
   `gftdcojp`-specific — it is a vendor/etzhayyim centralization-axis boundary, ADR-2605211950 /
   ADR-2605172100, that applies to any vendor-side repo, `jk-luxury` included). The actual
   self-custody settlement logic must still live in `etzhayyim/root`, consumed via a
   consent-capability boundary; `net-babiniku` (now under `jk-luxury`) implements only the
   domain model, governor, ledger, and UI that call into that capability. Concrete
   chain/wallet-custody choice is **not decided by this ADR** — building real fund-moving code
   without that input would be premature; this round's implementation therefore stubs the actual
   settlement call behind the same honest-default/no-fake-checkout pattern ADR-2607062200 already
   established for the fiat rail.

## Consequences

- `club-shinshi` and `net-babiniku` are now governed by `jk-luxury`'s (JK株式会社's) access
  control and billing surface, separate from `gftdcojp`'s — any future PSP/compliance audit for
  the adult-content/creator-economy line scopes cleanly to one org.
- Cross-references to the old `gftdcojp/club-shinshi` / `gftdcojp/net-babiniku` paths elsewhere
  in this repo (ADR-2607062200, ADR-2607062210, `gftd-talent-actor`'s docs, etc.) become
  historical — GitHub's automatic transfer redirect keeps old remote URLs working, and this ADR
  is the record of the move; those documents are not retroactively rewritten.
  This ADR is itself part of that record.
- No CI/webhook breakage expected (none existed pre-transfer), but any *future* GitHub-App-based
  integration (e.g. if Cloudflare Pages' GitHub App is later connected for auto-deploy) will need
  to be (re-)authorized for the `jk-luxury` org specifically.
- Fiat/adult-PSP integration (ADR-2607062200's original primary rail) is deferred, not
  cancelled — it remains the eventual second rail once a PSP is actually contracted.
- Follow-up: concrete crypto chain/custody-provider selection remains a business/security
  decision this ADR does not make; real fund-moving code is not implemented until that's
  resolved.

## Alternatives Considered

1. **Keep both repos under `gftdcojp`, rely on repo-level access control only.** Rejected —
   the org itself is the natural boundary for an already-distinct legal entity (JK株式会社); the
   informal split was already on record before this ADR, this just makes the GitHub structure
   match it.
2. **Create fresh repos under `jk-luxury` with clean history (as `club-shinshi` itself did when
   splitting from `ai-gftd-apps-gftdcojp`) instead of transferring.** Rejected here — a GitHub
   `transfer` preserves issues/stars/history and both repos are recent/small with no CI coupling
   to lose; a clean-history split made sense for `club-shinshi`'s original monorepo extraction
   (avoiding dragging the monorepo's full history) but is unnecessary overhead for an org-only
   move of already-standalone repos.
3. **Silently rewrite the existing `:extra-projects` entry's path instead of adding a
   `:path-overrides` entry.** Rejected — every prior GitHub-org transfer in this repo used
   `:path-overrides` with a dated rationale comment, preserving the original registration
   decision's text; breaking that convention here would lose the audit trail this repo
   otherwise maintains carefully.

## References

- `manifest/repos.edn` `:orgs`, `:path-overrides` (existing transfer precedents: manimani,
  cloud-manimani, cloud-murakumo/local-murakumo, kototama)
- `90-docs/adr/2607062200-net-babiniku-onlyfans-style-creator-monetization.md` (payment-rail
  decision amended here)
- `90-docs/adr/2607062210-net-babiniku-giemon-humanoid-embodiment-procurement.md`
- `orgs/etzhayyim/root/90-docs/adr/2605211950-vendor-centralized-etzhayyim-decentralized-substrate-axis.md`,
  `orgs/etzhayyim/root/90-docs/adr/2605172100-etzhayyim-payments-on-chain-only.md` (unchanged
  boundary this amendment still respects)
- `orgs/gftdcojp/gftd-talent-actor` docs (pre-existing JK株式会社/Gftd Japan株式会社 entity
  split reference)

## Amendment (2026-07-06): operating entity is JK Inc. (BVI), not JK株式会社 (Japan)

`jk-luxury/club-shinshi#3` (`legal/terms.md`, `legal/privacy.md`, merged this session) reflects
a **2026-07-03 owner decision, made before this ADR was written**, that superseded the
`gftd-talent-actor`-docs framing this ADR's Context/Decision/Alternatives sections relied on:
the operator is **JK Inc., a British Virgin Islands business company** (CR-113 Hannah Bay
Commercial Building Unit 2, Hannah's Bay, Tortola, BVI; `[CONFIRM: BVI company/registration
number]`), governing law **British Virgin Islands** (not Japan/APPI as primary regime — APPI/
GDPR/CCPA are maintained as secondary regimes for users in those jurisdictions). This ADR's
body text (Context, Decision §1, Alternative #1) still says "JK株式会社" — left as originally
written per this repo's amendment convention (the original decision record isn't rewritten),
corrected here instead. The underlying decision this ADR made — split `jk-luxury` out as its
own GitHub org/access-control/compliance-audit boundary, distinct from `gftdcojp` — is
unaffected by which specific entity operates within that boundary.

`legal/terms.md`/`legal/privacy.md` are themselves still DRAFT with multiple unresolved
`[CONFIRM]` items (age-assurance method, 18 U.S.C. §2257 applicability, regional access
restrictions, ad-partner data-sharing classification, creator payout/tax terms, BVI company
number) requiring specialized adult-industry counsel review before being relied upon —
merging the PR landed the draft in `main`, it did not resolve those items.
