# ADR-2607051300: cross-org capability map (kotoba-lang / com-junkawasaki / gftdcojp / etzhayyim / cloud-itonami)

**Status**: accepted
**Date**: 2026-07-04
**Data SSoT**: `2607051300-cross-org-capability-map.edn` (this document is the
narrative companion; the `.edn` is the queryable source of truth)

## Context

Following ADR-2607022800 (the kotoba-lang-internal dependency map), the owner
asked a broader question: can the dependencies/responsibilities/duplication
across all 5 orgs be organized along a **capability** axis, the way
`kotoba-lang/kotoba-lang` and `aiueos` already use that word?

Investigating that question found something important before any new design
work was needed: **the word "capability" already means three different
things in this superproject**, and only one of them is the sense useful for
cross-org portfolio organization. Conflating them would have produced a
confused schema. See `:capability-senses` in the `.edn` for the full
breakdown; in short:

1. **Runtime-security capability** (`kotoba-lang/kotoba-lang` +
   `kotoba-lang/aiueos`) — an object-capability / EROS-KeyKos-style
   access-control model. Answers "what may this *running* code do right
   now" (`net/fetch`, `graph/read`, `llm/infer`, ...). This is the sense
   `ADR-safe-capability-language.md` and `ADR-2606290930-kotoba-aiueos-
   capability-bridge` are about. **Not useful here** — it's a per-call
   runtime permission model, not a classification of what a repo *is*.
2. **Labor-classification tag** (etzhayyim actors' `:actor/labor {:isic
   [...] :isco [...] :unspsc [...]}`) — which human-labor vertical an
   actor's work touches, for Displacement Dividend accounting. Answers a
   different question (whose labor, not which tech dependency) — not mined
   in this pass.
3. **Vertical capability lib** (cloud-itonami blueprints' `blueprint.edn
   :itonami.blueprint/required-technologies`) — which named, reusable
   kotoba-lang module implements a function a product needs. **This is the
   sense that generalizes.**

Sense 3 turned out to already exist as clean, uniform, machine-readable data
across all 175 `orgs/cloud-itonami/cloud-itonami-*` blueprint repos — it just
hadn't been mined and cross-referenced yet.

## Decision

Mine sense 3 mechanically rather than design a new taxonomy from scratch:

1. A babashka script parsed all 175 `blueprint.edn` files (0 parse errors)
   and frequency-counted `:itonami.blueprint/required-technologies`.
2. Each of the resulting 13 capability keywords was cross-referenced against
   `orgs/kotoba-lang/<kw>` and `orgs/com-junkawasaki/{<kw>,<kw>-clj}` on
   disk, then against `manifest/west.yml` registration.
3. Any keyword resolving to both a `kotoba-lang` repo and a `-clj` sibling
   was byte-diffed to tell genuine duplication apart from a
   completed-but-uncleaned migration.

This ADR is **descriptive only** — no manifest edits, no repo retirements,
no new scaffolding. Every finding below is a candidate follow-up, not an
action already taken.

## The 13-keyword capability vocabulary

| capability | used by | canonical provider | west-registered | status |
|---|---|---|---|---|
| `:audit-ledger` | 175/175 (universal) | *none* | — | **gap** |
| `:bpmn` | 170/175 | `kotoba-lang/bpmn` | yes | **duplicate found** |
| `:forms` | 168/175 | `kotoba-lang/forms` | yes | clean |
| `:identity` | 167/175 | `kotoba-lang/identity` | **no** | **west-registration gap** |
| `:dmn` | 167/175 | `kotoba-lang/dmn` | yes | **duplicate found** |
| `:robotics` | 80/175 | `kotoba-lang/robotics` | yes | clean |
| `:telemetry` | 10/175 | *none* | — | **gap** |
| `:insurance` | 7/175 | `kotoba-lang/insurance` | yes | clean |
| `:securities` | 5/175 | `kotoba-lang/securities` | yes | clean |
| `:banking` | 4/175 | `kotoba-lang/banking` | yes | clean |
| `:optimization` | 4/175 | *none* | — | **gap** |
| `:property` | 2/175 | `kotoba-lang/property` | yes | clean |
| `:cae` | 1/175 | *ambiguous* (`cae-solver`?) | yes (solver) | **open question** |

8 of 13 keywords are clean (registered, unambiguous, single provider). The
other 5 are the actual findings:

### `:audit-ledger` — the highest-leverage gap

Every one of the 175 blueprints declares this as required. No repo is
literally named `kotoba-lang/audit-ledger`. The real substrate already
exists, just under other names: `commit-dag`, `prolly-tree`,
`kotobase-peer`, and kotoba's own Datom log (ADR-2607022600's database-crates
roadmap). This is a **discoverability gap, not a from-scratch build** — the
fix is either naming one of these the canonical `:audit-ledger` referent, or
scaffolding a thin composing contract repo the way `kotoba-lang/industry`
does for ISIC.

### `:bpmn` / `:dmn` — real, confirmed duplication

`com-junkawasaki/bpmn-clj` and `com-junkawasaki/dmn-clj` both exist on disk
(untracked, unregistered), and both have a `src/` tree that is **byte-for-
byte identical** to their `kotoba-lang/{bpmn,dmn}` counterparts (`diff -rq`
reports zero differences). This is the exact shape of the `kototama-clj`
leftover ADR-2607022800 Phase 5 retired: a completed `-clj`-suffix migration
(ADR-2606302300) whose pre-migration original was never cleaned up. Same
retirement treatment is the obvious follow-up, pending an explicit go-ahead
since it deletes a GitHub repo.

### `:identity` — a plain registration gap

`orgs/kotoba-lang/identity` exists on disk and is the provider 167 of 175
blueprints declare they need, but has **zero entry in `manifest/west.yml`**.
Lower-risk than the 175-repo gap below (single entry, no known concurrent
work touching it).

### `:telemetry` / `:optimization` — genuinely unbuilt

No `kotoba-lang` repo exists for either. Declared in blueprints, not yet
implemented anywhere.

### `:cae` — open question, not resolved

`kotoba-lang/cae-solver` exists and is registered, but ADR-2607022800's own
naming-collision table scopes it narrowly to "reduced-order vehicle CAE
solvers" (the aero/crash/datom/echem/motor/vphysics cluster) — not confirmed
to be what this one blueprint (ISIC 7210, R&D natural sciences) means by
generic `:cae`. Flagged, not guessed at.

## The cloud-itonami blueprint fleet, as found

175 local `orgs/cloud-itonami/cloud-itonami-*` checkouts, all real git repos
with a remote under the `cloud-itonami` GitHub org, all declaring
`:itonami.blueprint/status :public-oss` and `AGPL-3.0-or-later`. Naming
schemes: 70 plain 4-digit ISIC codes, 5 `cofog-*`, 5 `unspsc-*`, ~80
`iso3166-*` (country/agency codes — see below), 3 `gtin-*`, and 2 oddly
capital-letter-prefixed (`L6810`, `M6910`).

Of the 175, only **2 have graduated past scaffold to a real, actively-
developed actor**: `cloud-itonami-L6810` (real-estate agency) and
`cloud-itonami-M6910` (Global Incorporation Actor) — both have genuine
feature/fix commit history, not just generated boilerplate. Empirically, the
capital-letter prefix correlates with "graduated past scaffold" rather than
any ISIC-taxonomy convention (other ISIC Section L/M divisions in the fleet,
e.g. 7010/7210/7320, use plain digits). Nobody has documented this on
purpose as far as this pass found — see open questions.

One genuine small inconsistency: `cloud-itonami-L6810`'s own `blueprint.edn`
self-identifies its `:itonami.blueprint/id` as `"cloud-itonami-6810"` — not
`"cloud-itonami-L6810"`. Directory name, GitHub remote, and self-declared id
disagree. Flagged, not silently fixed, precisely because this is the fleet's
one repo with real, active development behind it.

**The `iso3166-*` sub-family is not a gap** — it's a known, actively-growing
roadmap item. ADR-2607040800 and ADR-2607042900 document two self-paced
`/loop` sweeps that have promoted 71/193 countries + 19/19 Japan agencies
from registry `:spec` to `:blueprint` so far, with 122 countries explicitly
left as future work. This ADR's iso3166 numbers are a snapshot, not a
finding.

## The west-registration gap (found as a side effect, not acted on)

`manifest/west.yml` has exactly **one** cloud-itonami-related entry — the
private `gftdcojp/cloud-itonami` business-os base repo. **None of the 175
public `cloud-itonami-<code>` blueprint repos are registered at all** — 35x
the size of the 5-repo gap ADR-2607022800 gap-swept for kotoba-lang.

This ADR does **not** register them. Roughly 80 of the 175 (the `iso3166-*`
family) are mid-flight under the self-paced `/loop` maturity-promotion
process referenced above, which lands its own commits in waves — a
concurrent wholesale west sweep risks racing it. If/when this gap is closed,
it should happen after confirming that loop isn't currently running, using
the same `--entry`-minimal-diff + server-side pin-verification discipline as
every other registration in this superproject, not a wholesale regen.

`kotoba-lang/identity`'s single missing entry carries no such risk and could
be fixed independently.

## Forward-looking gap: AI-driven supply-chain matching

The owner clarified mid-investigation that cloud-itonami is fundamentally
about **robotics/agent automation where supply-chain companies post
information for AI to analyze and match** — not a plain SaaS form-filler.
Checked for an existing capability covering this (searched kotoba-lang
READMEs and `repos.edn` for matching/marketplace/supply-chain terms): found
nothing. `:robotics` (80/175 blueprints) covers the *physical execution*
premise (a robot/actor does the domain work under a governor); there is no
equivalent named capability yet for the *data* side — ingesting multi-party
supply-chain listings and running AI matching/analysis over them.

This is a genuine **forward-looking gap**, not a duplication or naming
problem — nothing to consolidate, something to eventually design. Candidate
shape: a `kotoba-lang` vertical-capability-lib alongside
`banking`/`insurance`/`securities` (e.g. `:supply-chain-matching`), consumed
by whichever cloud-itonami blueprints need multi-party data intake —
wholesale-trade or logistics ISIC divisions and the KAMI `:giemon` robot line
are the most plausible first consumers. Not scoped further in this ADR; no
repo scaffolded.

## How this composes with the existing 4-org taxonomy

ADR-2606302300 answers **where** a new repo gets created (what-kind-of-thing
× for-whom). This ADR answers a different, orthogonal question: **what
function** does an existing repo provide or consume, and who else provides
or consumes the *same* function across an org boundary. A repo's org
placement doesn't change; the capability map is a cross-cutting index on
top, and it overwhelmingly points at `kotoba-lang` as provider, consistent
with that org's "language substrate consumed by all orgs" role.

`cloud-itonami` is not promoted to a 6th taxonomy tier by this ADR — per
ADR-2606302300's own 2026-07-01 amendment it remains the public-repo home
for one `gftdcojp` product line. It's used here purely as a **data source**:
the only org-wide corpus with a uniform, machine-readable per-repo
capability manifest at the time of writing.

This generalizes exactly the kind of check ADR-2607022800's 2026-07-04
follow-up did by hand — discovering that `gftdcojp/ai-gftd-mangaka` and
`kami-mangaka-page/render/text/scene` were two unrelated things both called
"mangaka". A capability-tag index makes that class of cross-org confusion
mechanically checkable instead of requiring an ad-hoc owner question each
time it happens to surface.

## Open questions (flagged, not resolved)

1. `cloud-itonami-L6810`'s self-id vs. dirname/remote mismatch — owner call
   needed on which side is wrong, given it's the fleet's one actively-
   developed repo.
2. `:cae` → `cae-solver` correspondence — unconfirmed, needs the blueprint's
   own domain context checked.
3. Why only `L6810`/`M6910` carry a letter prefix — noted so a future
   batch-registration pass doesn't assume a single clean regex covers all
   175 dirs.

## Follow-up (2026-07-04) — ISIC taxonomy-prefix rename, questions 1 and 3 resolved

Owner instruction: normalize cloud-itonami blueprint repo names to the
`cloud-itonami-isic-{code}` / `cloud-itonami-isco-{code}` shape the ISCO
family already used correctly. This closed out open questions 1 and 3 above
and surfaced a second, larger corpus this ADR's local-checkout-only scan had
missed entirely.

**The 175-local-checkout scan undercounted the fleet.** `gh repo list
cloud-itonami` (the authoritative GitHub-side list) returned **288** repos,
not 175: 88 `cloud-itonami-isco-*` (occupation blueprints per ADR-2607012000,
never checked out locally, entirely absent from this ADR's original
inventory) plus 25 more ISIC letter-prefixed repos beyond the 2 (`L6810`,
`M6910`) this ADR had found. Combined with the 70 plain-digit + 2 sampled
letter-prefixed repos already known, the true ISIC-family count was **97**,
not 2-anomalies-out-of-175. This ADR's `:cloud-itonami-blueprint-inventory`
maturity read ("only L6810/M6910 graduated past scaffold") is now known to
have been drawn from an incomplete sample for the same reason — the letter
prefix was never a "graduated" marker (question 3's speculation), it was
simply this ADR's blind spot for the 25 letter-prefixed repos it never saw.

**What shipped:**

1. All 97 ISIC-family repos (70 plain-digit + 27 letter-prefixed) renamed on
   GitHub via `gh repo rename` to `cloud-itonami-isic-{code}`, dropping the
   section-letter prefix. Zero collisions, zero failures. Each repo's
   internal self-references (`blueprint.edn`, `README.md`, and any `docs/*`
   file containing the old literal name) were updated and pushed in the same
   commit as the rename.
2. `cloud-itonami-L6810` → `cloud-itonami-isic-6810`: this also resolves
   question 1. Its `blueprint.edn` `:itonami.blueprint/id` field had
   pre-dated the rename disagreeing with its own dirname (self-declared
   `"cloud-itonami-6810"`, no `L`) — the rename's literal-string replacement
   pass correctly left that field alone (it never contained the string
   `cloud-itonami-L6810`), so it was fixed with one direct follow-up edit to
   read `"cloud-itonami-isic-6810"`, now agreeing with the repo name.
3. **A previously-undiscovered coupling**: `kotoba-lang/industry`'s
   `resources/kotoba/industry/registry.edn` (the canonical ISIC registry
   `kotoba.industry` code reads at runtime) held `:repo`/`:business-id`
   fields for 84 real blueprints, all pointing at the stale `gftdcojp` org
   and, for the 12 letter-prefixed ones, a business-id that had silently
   *dropped the letter* — e.g. the registry's "Talent Actor" entry (`:id
   "6310"`) pointed at `gftdcojp/cloud-itonami-6310`, a URL that never
   existed; the real repo was `cloud-itonami/cloud-itonami-J6310`. Confirmed
   via `industry.cljc` that `:repo`/`:business-id` are opaque metadata
   strings never parsed or validated (`maturity` only checks `(boolean
   (:repo industry))`), and that `industry_test.clj` asserts exclusively by
   ISIC `:id`, never by repo-name string — so all 84 entries were safely
   updated to `https://github.com/cloud-itonami/cloud-itonami-isic-{id}` /
   `cloud-itonami-isic-{id}` with the test suite unaffected (7 tests / 52
   assertions green before and after).
4. Separately found, **not fixed** (out of scope for this follow-up): of
   `registry.edn`'s 425 entries with a non-nil `:repo`, only 84 correspond to
   a real GitHub repo; the other **341 are phantom** — mostly (328) entries
   explicitly tagged `:maturity :spec` that nonetheless carry a populated
   `:repo` URL, contradicting the registry's own documented semantics
   (`:spec` = "registry only", no repo yet). This is a pre-existing
   data-integrity issue unrelated to the naming scheme and orthogonal to
   what this ADR set out to check — recorded here for whoever next audits
   `kotoba-lang/industry`'s registry, not resolved.
5. Also out of scope: 13 of the 97 real repos aren't referenced with a real
   `:maturity` tier in the registry at all (it hasn't caught up to their
   creation) — a freshness gap, not a naming one.

Question 2 (`:cae` → `cae-solver`) remains genuinely open; nothing in this
pass touched it.

## Consequences

- Capability-based cross-org organization is validated: the
  vertical-capability-lib sense already exists as structured data across
  175 repos, and needed mining + cross-referencing, not invention.
- 8/13 mined capability keywords are clean; the other 5 are concrete,
  actionable findings (1 discoverability gap, 2 confirmed byte-identical
  duplicates, 1 registration gap, 1 ambiguous naming, 2 genuinely unbuilt).
- A much larger mechanical gap (175 unregistered west entries) surfaced as a
  side effect and is deliberately *not* acted on here, pending confirmation
  the concurrent iso3166 `/loop` sweep is idle.
- This ADR bundles no action. Candidate follow-ups — register
  `kotoba-lang/identity`; retire `com-junkawasaki/{bpmn,dmn}-clj`;
  batch-register the 175 cloud-itonami blueprints once safe; scope a
  `:supply-chain-matching` capability lib — are each independent and
  separately approvable.
