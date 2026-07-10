# ADR-2607110100: cloud-itonami advertiser-ops actor — campaign governance for the first-party ad network (ADR-2607093500 follow-up #3)

## Status

Accepted.

## Related

- ADR-2607093500 (`kotoba-lang/adnet` core + x402 USDC billing + cloud-itonami
  advertiser ops — parent design; this ADR implements its follow-up #3)
- `cloud-itonami-isic-7310` `docs/adr/0001-architecture.md` (advertising-agency
  governed-actor lineage this ADR's Campaign Governor/spec-basis catalog is
  ported from)
- `gftdcojp/cloud-itonami` `docs/adr/0010-*`/`0011-*` (marketing/sales actor,
  CRM ingestion — the integration surface this ADR reuses)
- ADR-2606130200 (club-shinshi verified-production addendum — the "declaring
  live requires a dated, verified addendum" precedent this ADR follows for
  its own real-production gate)

## Context

ADR-2607093500 (2026-07-09) already decided cloud-itonami would operate a
first-party ad network's advertiser side ("cloud-itonami advertiser ops
actor (propose→govern→commit) + RAD registration") on top of a new pure
`kotoba-lang/adnet` core. As of this session (2026-07-10), two of that
ADR's three follow-ups had already landed:

1. `kotoba-lang/adnet` (public) — the pure `.cljc` auction/serving/billing
   core (`adnet.core`, `adnet.billing`).
2. `gftdcojp/adserver` (private) — the Cloudflare Worker wrapping it
   (`/serve` `/event` `/topup` `/catalog` `/health`), whose own README
   states campaigns "arrive here as `CAMPAIGNS_JSON`" and that
   "`cloud-itonami` operates the campaign lifecycle."

The third follow-up — the cloud-itonami advertiser-ops actor itself — had
not been started: `orgs/gftdcojp/cloud-itonami` had no `adnet`/`advertis*`
code, and `gftdcojp/adserver` had no `CAMPAIGNS_JSON` supplier. This ADR
closes that gap.

A separate, session-specific request asked for the ad network to "cover
the whole world" across five channels (smartphone/web/app/video/real) and
to reach real production (real traffic, real money). Two existing
lineages in this fleet already supply the missing substance for both asks
without inventing new infrastructure:

- `cloud-itonami-isic-7310` ("Advertising", an OSS advertising-agency
  governed-actor blueprint, unrelated GitHub org) already has a
  per-jurisdiction advertising-standards spec-basis catalog
  (`advertising.facts`) and a MAXIMUM-ceiling budget check
  (`advertising.registry/media-spend-exceeds-authorized-budget?`) — the
  right shape for "cover the whole world," honestly scoped (4 jurisdictions
  seeded, growable, never fabricated).
- `gftdcojp/cloud-itonami` already has a working marketing/CRM/audit
  pipeline (`cloud-itonami.marketing`, `cloud-itonami.m365/crm->tx`,
  `cloud-itonami.activity`, `cloud-itonami.approval`) that any new vertical
  can plug straight into.

Reaching *real* production (real `ADNET_TREASURY_ADDR`, real advertiser
USDC deposits, real publisher wiring) was explicitly out of scope for a
single code-only change — this repo's own precedent (club-shinshi's
ADR-2606130200 dated, verified-production addendum) is that "live" is
declared separately from "the code exists," after operational wiring is
actually verified.

## Decision

1. **New `cloud-itonami.adnetwork` ns** (`gftdcojp/cloud-itonami`,
   `src/cloud_itonami/adnetwork.cljc`) implements the advertiser-ops actor:
   `propose-campaign-create!` / `propose-campaign-pause!` /
   `propose-targeting-update!` / `propose-budget-topup!`. Each is
   propose-only (ops-LLM shape): it builds a plain `:campaign/*` record,
   re-verifies it against the advertiser's own recorded ground truth via a
   dedicated **Campaign Governor** (`governor-violations`, a pure function
   — not routed through `business_governor.cljc`'s shared kind-enum
   multimethod, since that multimethod's kernel-backed dispatch
   (`kernels/substance.cljc`, mirrored in `.kotoba` + compiled to
   `wasm/kernels/substance.wasm`) is a closed 0..5 kind enum meant for
   small generic proposals, not a rich multi-field domain — the same
   reason `cloud-itonami-isic-7310`'s `advertising.governor` is its own
   dedicated module rather than folded into a shared dispatcher), then
   lands as a normal `:itonami.activity/*`/`:itonami.effect/*` pair
   (`:external-send` risk for campaign create/pause/retarget,
   `:financial` for budget top-up) in the same approval queue every other
   cloud-itonami business action already uses.
2. **Campaign Governor HARD-hold checks** (ported/extended from
   `cloud-itonami-isic-7310`'s MAXIMUM-ceiling-check family):
   `campaign-budget-exceeds-advertiser-authorized?` (ceiling vs the
   advertiser's own recorded budget), `tier-mismatch?` (adult/general
   segregation, club-shinshi's tier concept), `unresolved-content-risk?`
   (self-reported flag, evaluated unconditionally), and
   `missing-jurisdiction-spec-basis?` (below).
3. **New `cloud-itonami.advertising-facts` ns** ports
   `cloud-itonami-isic-7310`'s `advertising.facts` jurisdiction spec-basis
   catalog verbatim (4 jurisdictions: JPN/USA/GBR/DEU, each citing a real
   official source) plus a new **5-way ad-channel taxonomy**
   (`ad-channels`: `#{:smartphone :web :app :video :real}`) that the
   governor validates campaign `:targeting :channels` against. This is
   cloud-itonami's own governance-side layer on top of `adnet.core`'s
   free-form `:targeting :formats`/`:placements` strings — `adnet.core`
   itself is not modified. "Cover the whole world" is operationalized
   honestly: coverage grows one verified jurisdiction at a time by
   appending to `catalog`, never fabricated, exactly like every prior
   sibling actor's `facts` namespace in this fleet.
4. **CRM/sales/marketing integration reuses existing cloud-itonami
   modules, no reimplementation**: `advertiser->tx` mirrors
   `cloud-itonami.m365/crm->tx`'s exact 2-entity `{activity effect}`
   shape so advertiser accounts land in the same CRM/audit trail as every
   other account this repo tracks; `propose-advertiser-outreach!`
   delegates straight to the existing `cloud-itonami.marketing/
   propose-outreach!` for lead-gen.
5. **`campaigns->CAMPAIGNS_JSON`** is the pure projection that closes the
   loop with `gftdcojp/adserver`: it takes governor-approved campaigns
   (effect status `:approved`/`:executed` — never `:proposed`) and
   produces the exact string-keyed shape `adserver`'s README documents for
   its `CAMPAIGNS_JSON` feed. Publishing that projection to a live Worker
   secret remains a deploy-time operational step, outside this ns's job
   (same boundary discipline as `cloud-itonami.marketing`'s "fetching the
   number is deliberately NOT this ns's job").
6. **Real production go-live is explicitly deferred**, not silently
   assumed. This build produces the governed campaign records and the
   `adserver`-compatible projection; it does **not** configure a real
   `ADNET_TREASURY_ADDR`, onboard a real advertiser, or wire a real
   publisher's `ad_slot`. Declaring the pipeline "live" follows this
   repo's own precedent (ADR-2606130200's dated, verified-production
   addendum) — a separate, explicitly-confirmed step after real
   operational wiring is actually verified, not a code-existence claim.

## Consequences

- Closes ADR-2607093500's third follow-up. `gftdcojp/adserver`'s
  `CAMPAIGNS_JSON` feed now has an intended supplier.
- `cloud-itonami-isic-7310`'s advertising-agency governance lineage now
  has a second, sibling application inside `gftdcojp/cloud-itonami`
  itself, proving the pattern (spec-basis catalog + MAXIMUM-ceiling check)
  generalizes from "OSS blueprint for a licensed agency" to "in-house
  first-party ad network governance."
- No changes to `kotoba-lang/adnet`, `gftdcojp/adserver`, or
  `business_governor.cljc`/`kernels/substance.cljc` (and therefore no
  `wasm/kernels/*.wasm` regeneration) — the new actor is fully additive.
- 4-jurisdiction spec-basis coverage is a deliberate starting point, not a
  claim of global coverage — `advertising-facts/coverage` reports this
  honestly; extending is additive (cite a real source, append to
  `catalog`).
- Real advertiser onboarding, real treasury wiring, and real publisher
  `ad_slot` integration remain open follow-ups, not silently declared
  done by this build.
- Test status: 25 new tests across `adnetwork_test.cljc` +
  `advertising_facts_test.cljc`; full suite 631 JVM tests / 5083
  assertions and 246 portable-cljs tests / 2353 assertions pass;
  clj-kondo clean.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| New `cloud-itonami-isic-<code>` OSS-blueprint child repo under the `cloud-itonami` GitHub org | Would duplicate/diverge from the already-approved, already-half-built ADR-2607093500 architecture and would not supply `gftdcojp/adserver`'s missing `CAMPAIGNS_JSON` feed; that fleet's pattern is a generic forkable blueprint for a third-party licensed operator, not this company's own first-party network |
| Route campaign proposals through `business_governor.cljc`'s shared multimethod, extending `kernels/substance.cljc`'s closed kind enum (+ `.kotoba` twin + wasm regen) | A much deeper, riskier change to a shared, wasm-compiled kernel other domains depend on, for a proposal shape (rich multi-field campaign entity with 4+ domain-specific HARD-hold checks) this repo's own precedent (isic-7310's dedicated `advertising.governor`) already shows belongs in its own module, not the shared small-generic-proposal dispatcher |
| Declare the ad network "live" as part of this build | This repo's own precedent (club-shinshi ADR-2606130200) is a dated, verified-production addendum after real operational wiring, not a code-existence claim; real money handling deserves that same discipline |

## References

- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/adnetwork.cljc`
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/advertising_facts.cljc`
- `orgs/gftdcojp/cloud-itonami/test/cloud_itonami/adnetwork_test.cljc`
- `orgs/gftdcojp/cloud-itonami/test/cloud_itonami/advertising_facts_test.cljc`
- `orgs/cloud-itonami/cloud-itonami-isic-7310/src/advertising/{facts,registry,governor}.cljc`
- `90-docs/adr/2607093500-adnet-first-party-ad-network.md`
- `90-docs/adr/2607081900-cloud-itonami-advertising-7310-coverage.md`
