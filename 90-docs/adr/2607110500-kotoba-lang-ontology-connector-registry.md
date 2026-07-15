# ADR-2607110500: kotoba-lang/ontology — object-type + connector provenance registry

**Status**: closed（Addendum 1 で実統合まで完了・検証済み）
**Date**: 2026-07-10
**Closed**: 2026-07-10
**Deciders**: Jun Kawasaki

## Context

Owner asked whether `cloud-itonami` has anything like Palantir. Comparison
found a real Foundry/AIP-shaped core already in place (`kotoba` datom log
projecting `activity → decision → effect → audit`, advisor-proposes /
independent-governor-gates pattern repeated across every
`cloud-itonami-isic-*` / `cloud-itonami-iso3166-*` blueprint, org/repo tenant
namespace per `docs/adr/0002-org-repo-tenant-isolation.md`) but also two real
gaps versus Foundry: no shared object-type vocabulary (each ingested source
invents its own ad hoc keyword shape) and no generalized external-data
connector foundation (only `kotoba-lang/goyoukiki`'s `jp.kkj`/`jp.geps`
existed, as a per-vertical embedded pattern, per
`90-docs/adr/2607070300-kotoba-lang-goyoukiki-jp-real-ingestion-connectors.md`).
Owner chose to build the data-integration/connector foundation and realize
it as an actual new child repo via the standard project-creation flow.

Researched existing conventions before designing: `manifest/repos.edn` has
no per-repo entry schema (repos are bare paths in `:extra-projects`);
`kotoba-lang` capability libraries (`robotics`, `phone`, `industry`,
`technology`) share a light skeleton (`LICENSE` Apache-2.0, `README.md`,
`deps.edn`, `.github/workflows/ci.yml`, `src/kotoba/<name>`, matching
`test/`) with no governance boilerplate and no `docs/adr/` — that heavier
skeleton belongs to `cloud-itonami-*` business blueprints, not capability
libraries. `kotoba-lang/technology`'s `resources/kotoba/technology/registry.edn`
is the fleet-wide SSoT mapping a `:required-technologies` keyword (`:robotics`,
`:phone`, `:audit-ledger`, ...) to the repo(s) implementing it; that is where
a new technology keyword must be registered for blueprints to ever declare
it. `blueprint.edn` already has a stable shape
(`:itonami.blueprint/required-technologies [...]`) confirmed against
`cloud-itonami-isic-6120` and `cloud-itonami-iso3166-jpn`.

## Decision

**Add `kotoba-lang/ontology`, a capability library (not a
`cloud-itonami-*` business blueprint), and register `:ontology` in
`kotoba-lang/technology`'s registry.**

1. `kotoba.ontology` — object-type registry + pure structural check.
   Seeded with exactly one object type, `:tender`, generalizing
   `goyoukiki.model/opportunity`'s coarse common denominator (`:status`).
   No speculative taxonomy: further object types are added only when a real
   connector needs one.
2. `kotoba.ontology.connector` — connector registry (id → object type →
   owning repo → maturity) plus `tag`, a pure function stamping an
   already-normalized fact with `:ontology/type` / `:ontology/source` /
   optional `:ontology/fetched-at` / `:ontology/confidence`. Seeded with the
   two connectors that already exist and already work:
   `kotoba-lang/goyoukiki`'s `jp.kkj` and `jp.geps`. Neither namespace
   performs I/O — fetch/parse stays in the connector's own JVM-only adapter,
   exactly where `2607070300` already put it; this repo only indexes that
   pattern and gives its output a common, checkable provenance tag.
3. Skeleton mirrors `kotoba-lang/robotics`/`phone`/`technology`: Apache-2.0
   `LICENSE`, `README.md` (including an explicit "Non-goals" section — not a
   pipeline/ETL builder, not a connector marketplace, not Gotham, not a
   replacement for a domain's own richer model), `deps.edn`
   (`:test`/`:lint` aliases, cognitect test-runner + clj-kondo, matching
   every sibling capability lib), `.github/workflows/ci.yml`. No
   `docs/adr/`, `GOVERNANCE.md`, `CONTRIBUTING.md`, `SECURITY.md`,
   `CODE_OF_CONDUCT.md` — that skeleton is for business blueprints, not
   capability libraries (confirmed against `robotics`/`phone`/`technology`,
   none of which carry it).
4. Registry data lives as an in-source `def` (not a `resources/*.edn` file
   read via `clojure.java.io/resource`/`slurp`), unlike
   `kotoba.industry`/`kotoba.technology`'s existing JVM-only pattern. Those
   two are grandfathered (CLAUDE.md: don't retroactively rewrite a working
   JVM-only lib once a higher-priority runtime exists), but this is new code
   and the monorepo's runtime-priority rule (kotoba wasm > clojurewasm >
   ClojureScript > nbb > JVM/nbb last-resort) applies to it — an in-source
   data literal has zero I/O and is trivially portable to `:cljs`, so there
   is no reason to copy the JVM-only resource-loading shape into new code.
5. **Add one entry to `kotoba-lang/technology`'s
   `resources/kotoba/technology/registry.edn`** (`:id :ontology`, pointing
   at this repo) so `:ontology` becomes a nameable
   `:required-technologies` keyword — the only change needed to an existing
   repo, landed via an isolated worktree + server-side merge
   (`gh api .../merges`), never by editing the shared `orgs/kotoba-lang/technology`
   checkout in place (that checkout's branch changed under this session
   mid-task, confirming the multi-agent shared-checkout hazard
   `manifest/cleanup-workflow.md` warns about).
6. **Explicitly out of scope for this change** (fleet-wide follow-up, not
   done here): wiring individual `cloud-itonami-*` blueprints'
   `required-technologies` to include `:ontology`; adding `:ontology` to any
   `kotoba-lang/industry` per-ISIC entry; any further object types beyond
   `:tender`; any UI/export namespace (unlike `robotics`/`phone`, this R0
   ships no `.ui`/`.export` — add them if/when a consumer needs a dashboard
   or a CSV/JSON dump, not speculatively).

## Consequences

- (+) Closes the "no shared object-type vocabulary" and "no generalized
  connector foundation" gaps identified in the Palantir comparison, scoped
  honestly (seeded from what already exists and is tested, not invented
  ahead of a real need).
- (+) `kotoba-lang/goyoukiki`'s already-working connectors get a shared
  index and a common provenance tag without any change to `goyoukiki`
  itself — `kotoba.ontology.connector` only references them by URL/id.
- (+) 5 tests / 15 assertions, clj-kondo clean, verified locally before any
  commit.
- (−) Only one real object type (`:tender`) and two real connectors seeded;
  broader fleet coverage is deliberate follow-up, not delivered here.
- (−) No blueprint anywhere declares `:required-technologies [:ontology]`
  yet — this ADR only makes the keyword nameable via
  `kotoba-lang/technology`, adoption is separate follow-up work.
- (−) Still not Foundry-scale: no pipeline/ETL builder, no cross-org
  connector marketplace, no Gotham-style intel product. See the repo
  README's "Non-goals" section.

## References

- `90-docs/adr/2607070300-kotoba-lang-goyoukiki-jp-real-ingestion-connectors.md`
  (the real connectors this registry indexes)
- `90-docs/adr/2607070200-kotoba-lang-goyoukiki-procurement-signal-actor.md`
- `gftdcojp/cloud-itonami`'s `docs/adr/0002-org-repo-tenant-isolation.md`
  (the tenant namespace this ontology projects into)
- `kotoba-lang/technology`'s `resources/kotoba/technology/registry.edn` (the
  fleet-wide technology-keyword SSoT this ADR adds one entry to)

## Addendum 1 (2026-07-10): real integration into kotoba-lang/goyoukiki

Owner asked to raise maturity further. Checked `teian`/`tayori` (goyoukiki's
downstream `match` consumers per its own docstring) as candidates for a
`:required-technologies [:ontology]` declaration — both are still spec-only
(no `src/` at all), so wiring either would not be a real adoption. Chose a
more substantive integration instead: `kotoba-lang/goyoukiki` itself now
depends on `kotoba-lang/ontology` and calls
`kotoba.ontology.connector/tag` on the opportunity map inside both
`jp.kkj/ingest!` and `jp.geps/ingest!`, right before `operation/register!` —
every fact registered from either connector now carries `:ontology/type
:tender` and `:ontology/source (:jp.kkj or :jp.geps)`.

`->opportunity` itself (the directly unit-tested mapping fn in both
namespaces) is untouched — tagging happens only at the `ingest!` boundary,
which was already documented as real-network-only and outside the existing
test suite, so this is non-breaking by construction. Added one new test per
connector (`ingest-tags-opportunity-with-ontology-provenance`) verifying the
composed `connector/tag` output against the same real captured fixtures the
existing tests already use, and asserting
`kotoba.ontology.connector/tagged-conforms?` is true. Full suite: 45 tests /
170 assertions green (was 41/150 before goyoukiki's own maturity work since
ADR-2607070300 added more; this addendum adds 2 tests / 20 assertions to
that count), clj-kondo clean. Landed via isolated worktree + server-side
merge (`gh api repos/kotoba-lang/goyoukiki/merges`), same as the rest of
this ADR's changes.

This resolves the "no blueprint anywhere declares `:required-technologies
[:ontology]`" gap in a stronger form than originally scoped: not a business
blueprint's declarative metadata, but the connector library itself
consuming the ontology tag in real, tested code. Moves `kotoba-lang/ontology`
from "registered, zero real consumers" to "registered, one real consumer".
Still open: `teian`/`tayori` remain spec-only so cannot yet declare
`:required-technologies [:ontology]` themselves; no `cloud-itonami-*`
blueprint or `kotoba-lang/industry` entry references `:ontology` yet;
`:tender` is still the only object type.

## Addendum 2 (2026-07-10): teian/tayori correction + real goyoukiki→tayori wiring

**Correction to Addendum 1**: the claim that `teian`/`tayori` are
"spec-only" was wrong — it was checked against this session's stale local
`orgs/kotoba-lang/{teian,tayori}` checkouts (pin drift: `west update` only
syncs to whatever pin is already in `west.yml`, it does not fetch upstream's
latest — exactly the "pin鮮度" hazard `CLAUDE.md` already warns about).
Fresh clones from GitHub showed both are fully implemented (`teian` pushed
2026-07-10, `tayori` pushed 2026-07-09): full model/store/policy/advisor/
governor/phase/operation/port/cacao/kotoba/query/sim/cli layout each,
matching `goyoukiki`'s own shape, "Scaffold + runnable" per `teian`'s own
README with live-verified pptx export + Resend email delivery.

Checked whether `goyoukiki` and `teian`/`tayori` already reference each
other in code: zero hits either direction. The "teian/tayori consume
goyoukiki's match" framing in `goyoukiki.model`'s own docstring was a
forward-looking design note, not yet-built wiring. Owner asked to build it
as new feature work.

**Decision**: added `kotoba-lang/goyoukiki`'s `src/goyoukiki/matchport/tayori.cljc`
— a real `MatchTarget` (the protocol `goyoukiki.matchport` already defines
for exactly this seam) that, on `share!` (called once, only after a human
has ALREADY approved sharing a match), drives a real `tayori`
`CorrespondenceActor` to `:reply/draft` an outreach message to the
candidate — never `:reply/send`, which stays tayori's own separate,
always-human step. `fetch-match`/`propose-match!` mirror
`goyoukiki.matchport/mock-matchport`'s bookkeeping exactly (deterministic,
no I/O); only `share!` differs. The seeded thread's one message
(`system-note`) carries the underlying opportunity's `:ontology/type`/
`:ontology/source` when tagged by a registered connector, so tayori's own
audit ledger (and a human reviewing the draft) can see whether a match
being shared originated from a real external feed (`jp.kkj`/`jp.geps`) or
an internally-proposed opportunity, without a side channel.

**Framing caveat, disclosed rather than hidden**: tayori's actual charter is
drafting *replies* to inbound correspondence; a goyoukiki share is really
the *first* outbound contact about a new match, not a reply. This reuses
tayori's reply-drafting machinery (a thread + one seeded system-context
message standing in for the "incoming" side) for that first-contact case —
a reasonable reuse, not a perfect semantic fit. `tayori.replyllm/mock-advisor`
produces slightly reply-flavored canned Japanese phrasing regardless
("ご連絡ありがとうございます…"); a real `llm-advisor` given these same facts
would generate genuinely first-contact-appropriate text since its system
prompt only says "propose based on the given facts", not "always reply".

Verified: two independent StateGraph governors compose without either
short-circuiting the other — `goyoukiki`'s `ProcurementGovernor` already
gated the `:match/share` that triggers this; `tayori`'s own
`ComplianceGovernor`/phase-3 auto-commit gates the resulting `:reply/draft`
(clean + confident → auto-commit; the follow-up `:reply/send` this
namespace never calls would separately need its own human sign-off, `tayori`'s
existing, unmodified high-stakes rule). 50 tests / 180 assertions green
(was 45/170 after Addendum 1; this adds 5 tests / 10 assertions), clj-kondo
clean. Landed via isolated worktree + server-side merge
(`gh api repos/kotoba-lang/goyoukiki/merges`), same as every other change
in this ADR.

Still open: the reverse direction (`teian` briefing decks summarizing
matches) was considered and NOT built — it is a weaker semantic fit for
this specific flow (board/sales-review decks, not per-match outreach) and
forcing it would repeat the mistake this addendum just corrected out of;
`kotoba-lang/industry`/`cloud-itonami-*` blueprint adoption of
`:required-technologies [:ontology]` is still untouched; `:tender` is still
the only ontology object type.

## Addendum 3 (2026-07-10): live end-to-end run + real wiring into gftdcojp/cloud-itonami

Owner asked whether this actually runs, and whether `cloud-itonami` itself
is connected — not just unit tests. Ran a real, unscripted end-to-end demo
(no synthetic data): `goyoukiki.jp.kkj/ingest!` against the live kkj.go.jp
API pulled 3 real, currently-open government tenders (参議院/House of
Councillors, 法務省/Ministry of Justice, 東京都町田市/Machida City), each
registered with `:ontology/type :tender :ontology/source :jp.kkj`. Ran the
real `:match/propose → :match/share` flow (human-approval interrupt/resume,
not stubbed) against one of them; `goyoukiki.matchport.tayori/match-target`
then drove a real `tayori` `CorrespondenceActor` that produced a genuine
`:reply/draft` whose seeded message read "新規マッチ通知: 図書情報システム用
サーバ等の賃貸借及び保守業務一式(参議院) [出所: jp.kkj, 種別: tender]" —
the real government tender's provenance, visible in the actual draft text.
Two independent audit ledgers (goyoukiki's own, tayori's own) both recorded
the run. Separately ran `gftdcojp/cloud-itonami`'s full JVM test suite
(674 tests / 5238 assertions, 0 failures) and confirmed it already has its
own real, tested `goyoukiki` integration (`cloud_itonami.workspace`'s
`effect->goyoukiki-request`/`propose-match!`/`share-match!`) — but that
layer built its own `opportunity` fresh from each effect's payload fields
and had no path for a live-ingested, ontology-tagged fact to reach it, and
used `goyoukiki.matchport/mock-matchport` (no real downstream effect) for
`:match/share`. Two real, tested layers existed; they were not connected.

**Decision**: connected them, in `gftdcojp/cloud-itonami`'s
`src/cloud_itonami/workspace.cljc`.

1. `effect->goyoukiki-request` now optionally reads `:ontology-type`/
   `:ontology-source` from the effect payload and assocs them onto the
   built opportunity as `:ontology/type`/`:ontology/source` (via `cond->`,
   so an internally-proposed opportunity with neither key present carries
   neither key — verified by a dedicated test, not just an implicit nil).
2. `run-goyoukiki-share!`/`share-match!` now build the actor with
   `goyoukiki.matchport.tayori/match-target` instead of
   `goyoukiki.matchport/mock-matchport` — a real, human-approved
   `:procurement/share-match` effect now actually drives a `tayori`
   `CorrespondenceActor` to draft (never send) outreach, and the resulting
   draft is surfaced on the effect's own `:itonami.effect/tool` payload as
   `:goyoukiki/tayori-draft` (no side channel — a human reviewing the
   effect's audit trail sees it directly). `tayori`'s own
   `ComplianceGovernor` stays in control of the real `:reply/send` via a
   separate, later human approval this handler never triggers.
3. No new dependency needed in `cloud-itonami`'s `deps.edn` — it already
   carries plain `:local/root` sibling deps on both `kotoba-lang/goyoukiki`
   and `kotoba-lang/tayori` (used elsewhere in `workspace.cljc` for its own
   `teian`/`koyomi`-style integrations); only the two local sibling
   checkouts needed fast-forwarding to pick up this ADR's earlier
   `goyoukiki` commits (clean trees, straight fast-forwards, verified via
   `git status --short` before touching either).
4. Added 3 tests to `cloud_itonami.workspace-test`: ontology carry-through
   (present and absent cases) and a full propose→share round trip against a
   payload carrying `:ontology-type :tender :ontology-source :jp.kkj`,
   asserting the real tayori draft text contains both. `cloud-itonami`'s
   full suite: 677 tests / 5243 assertions green (was 674/5238), plus its
   own PRIMARY portable-cljs gate (274 tests / 2451 assertions, triggered
   automatically by its `pre-push` lefthook) also green — confirming the
   change is cljs-compatible, not just JVM-compat-tested.
5. **Verification method note**: `cloud-itonami`'s `deps.edn` declares 18
   sibling `kotoba-lang/*` dependencies via `:local/root`, making a fully
   isolated worktree impractical to test in (every sibling path must exist
   for classpath resolution even if unused by the changed file). Tested via
   a git worktree placed at a path whose `kotoba-lang` sibling directory
   was a **symlink to the real, already-existing** `orgs/kotoba-lang`
   checkouts (read-only reference, not a copy) — after first confirming via
   `git status --short` that the two specific checkouts this change
   actually touches (`goyoukiki`, `tayori`) were clean, then fast-forwarding
   only those two to pick up this ADR's own already-pushed-to-main commits.
   Landed the actual code change via the usual isolated worktree (this one
   moved into the symlink structure via `git worktree move`, not a second
   copy) + server-side merge (`gh api repos/gftdcojp/cloud-itonami/merges`),
   same as every other change in this ADR.
6. **Side effect disclosed**: `gftdcojp/cloud-itonami` runs its own
   continuous-deployment replacement for the disabled GitHub Actions
   workflow via `lefthook` `post-checkout`/`post-merge` hooks
   (`scripts/deploy-if-main-advanced.sh`) — any local checkout (including a
   freshly-created `git worktree`) that lands exactly on `main`'s current
   tip triggers a real `wrangler pages deploy` to the production
   `cloud-itonami.pages.dev` (custom domain `itonami.cloud`), idempotent via
   a SHA marker in the shared `.git` dir. Creating the isolated worktree for
   this change triggered one such deploy of the *already-merged* main tip
   (not this ADR's own WIP) before any edit was made; merging this ADR's
   change to main will trigger another, this time deploying the change
   itself — confirmed with the owner mid-session as the repo's intended,
   already-standard behavior (observed multiple other sessions' deploys in
   `wrangler pages deployment list` within the same few minutes) before
   continuing.

Still open: `teian` (briefing decks) remains unconnected to either
`goyoukiki` or this cloud-itonami flow, by choice (Addendum 2); no
`cloud-itonami-*` business blueprint (as opposed to `cloud-itonami` itself)
consumes any of this; `:tender` is still the only ontology object type; no
other `gftdcojp/cloud-itonami` effect kind besides `:procurement/*` carries
ontology provenance.

## Addendum 4 (2026-07-10): connect teian — an internal briefing, not outreach

Owner asked to connect `teian` after all (Addendum 2/3 had deliberately left
it unconnected, reasoning its briefing-deck charter was a weaker fit for
the per-match *outreach* flow `tayori` already covers). Re-examined
`gftdcojp/cloud-itonami`'s own `src/cloud_itonami/workspace.cljc` first
(not `teian`'s README, whose own "out of scope here" note about a
`cloud_itonami.workspace` teian bridge turned out to be **stale** —
`effect->teian-request`/`generate-deck!`/`publish-deck!` already exist,
wired to a *different*, pre-existing `:document/generate-deck` effect kind
unrelated to procurement). This reframed the fit: teian's actual niche
here is an **internal** sales/management review deck about the decision to
share a match — genuinely distinct from `tayori`'s **external** outreach
draft, not a forced reuse of the same seam.

**Decision**: `share-match!` now also drives a new
`run-goyoukiki-teian-briefing!`, called ONLY after the share is confirmed
to have reached `:shared` (a held/escalated share never gets a briefing
drafted about it — checked explicitly, not implied). It registers a teian
`:artifact` and runs `:deck/draft` (phase 3, auto-commit, mirroring
`tayori`'s draft autonomy) — never `:deck/publish`, which stays a separate,
always-human effect. The artifact's `:title` carries the opportunity's
ontology provenance the same way `goyoukiki.matchport.tayori`'s
`system-note` does, since that is the one piece of content teian's mock
deck-LLM actually renders from. Result surfaced as `:goyoukiki/teian-deck`
on the effect's own `:itonami.effect/tool` payload, alongside the existing
`:goyoukiki/tayori-draft` — same effect, two independently-governed real
downstream drafts for two genuinely different purposes. No new dependency
(teian already required in this ns). 684 tests / 5293 assertions green,
plus the PRIMARY portable-cljs gate (277/2466, its `pre-push` lefthook)
also green. Landed via isolated worktree + server-side merge, same pattern
as every other change in this ADR.

## Addendum 5 (2026-07-10): a real per-blueprint `:ontology` connection

Owner asked to connect an individual `cloud-itonami-*` business blueprint
next. Searched all `orgs/cloud-itonami/*` for a genuine (non-speculative)
domain overlap with `goyoukiki`'s own real domain (JP government
procurement tender matching) rather than picking one arbitrarily. Found
`cloud-itonami-iso3166-jpn` (`:implemented`, the first running actor in the
`iso3166-*` family, `src/marketentry/*`) — its own `docs/business-model.md`
names **GEPS** and **全省庁統一資格** by their real names, the exact same
systems `goyoukiki`'s own README documents its `jp.kkj`/`jp.geps`
connectors against verbatim. Two agency-leaf siblings
(`cloud-itonami-iso3166-jpn-mlit`, `-jpn-digital`) were also real but
sector-narrower forks under it, not the primary fit.

**Decision**: added `:ontology` to `blueprint.edn`'s
`:required-technologies`, and — since a bare metadata label without a real
consumer would repeat exactly the mistake corrected in Addendum 2 — added
`src/marketentry/goyoukiki.cljc`
(`marketentry.goyoukiki/opportunity-assessment-context`): a pure fn that
takes a real procurement-tender fact tagged by `kotoba-lang/goyoukiki`'s
connectors and, ONLY once verified via
`kotoba.ontology.connector/tagged-conforms?`, combines
`marketentry.facts`'s generic per-country evidence checklist with that
SPECIFIC opportunity's own eligibility floor (`:min-rank`/
`:required-categories`) — grounding "you will need these documents to
register" in a concrete, verifiably-sourced real case ("here is the tender
you actually want to bid on") rather than only the abstract catalog. Fails
closed (`nil`) on an untagged or mistagged fact, mirroring this blueprint's
own charter of never fabricating a jurisdiction's requirements, applied to
the opportunity-input side too. Added `kotoba-lang/ontology` as a
`:local/root` sibling dependency (matching this repo's existing
`kotoba-lang/langgraph` convention) and documented the new capability in
both the README's "Implementation" table and "Capability layer" list. 27
tests / 88 assertions green (was 24/79), clj-kondo clean (no new warnings
in the added files). Left an unrelated concurrent session's uncommitted
`blueprint.edn`/`organization.edn` WIP in the shared checkout untouched —
worked from an isolated worktree based on `origin/main` instead. Landed via
server-side merge, same pattern as every other change in this ADR.

Still open: no OTHER `cloud-itonami-*` blueprint references `:ontology`;
`marketentry.goyoukiki`'s bridge is a pure fn, not yet wired into
`marketentry.operation`'s own `:jurisdiction/assess` request flow (a caller
must invoke it explicitly and feed its output in, e.g. as engagement
context) -- that deeper wiring is a further follow-up, not done here;
`:tender` is still the only ontology object type.

## Addendum 6 (2026-07-10): wire the bridge into the real `:jurisdiction/assess` flow

Addendum 5 left `marketentry.goyoukiki/opportunity-assessment-context` as a
standalone pure fn a caller must invoke explicitly. Closed that gap: an
engagement can now declare `:motivating-opportunity` (a real,
ontology-tagged tender fact) + `:motivating-connector` via
`:engagement/intake`'s ordinary patch mechanism (no schema change needed —
`marketentry.store`'s `:engagement/upsert` already merges arbitrary keys).
`marketentryllm/assess-jurisdiction` now calls the bridge when one is
present, grounding the generic JPN evidence checklist in that specific
opportunity's own eligibility floor (`:min-rank`/`:required-categories`) in
the proposal's `:summary`/`:rationale`/`:cites`/`:value`.

Added an eighth governor HARD check,
`motivating-opportunity-unverified-violations`: when the proposal claims a
motivating opportunity (`:motivating-opportunity-claimed? true`) but
`kotoba.ontology.connector/tagged-conforms?` could not verify it
(`:motivating-opportunity-verified? false`), that is precisely the
fabricated-regulatory-claim case this governor's own charter names as an
unoverridable HARD hold — mirroring the existing `spec-basis-violations`
pattern (don't invent a jurisdiction's requirements) applied to the
opportunity-input side.

Caught a real bug while writing the test for the unverified case: the
first attempt patched `:motivating-opportunity`/`:motivating-connector`
onto an engagement's intake WITHOUT an `:id` key in the patch map, and the
assertions read back `nil` — `marketentry.store`'s `:engagement/upsert`
merges onto `[:engagements (:id value)]` (the patch's OWN `:id`, not the
request's `:subject`), so an intake patch missing `:id` silently merges
onto a bogus `nil` key instead of the intended engagement. Fixed by
including `:id "eng-1"` in the patch, matching the convention the repo's
own existing `clean-intake-auto-commits` test already used but this
addendum's first draft missed.

29 tests / 97 assertions green (was 27/88), clj-kondo clean (no new
warnings in touched files). Landed via isolated worktree + server-side
merge, same pattern as every other change in this ADR.

Still open: no other `cloud-itonami-*` blueprint references `:ontology`;
`:tender` is still the only object type; the `:motivating-opportunity`
convention is specific to this one blueprint, not a fleet-wide pattern.
