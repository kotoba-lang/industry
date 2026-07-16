# ADR-2607091400: cloud-itonami-isic-9511 (community ICT equipment repair) deepened to `:implemented`

## Status

Accepted

## Related

- ADR-2607071849 (`cloud-itonami-isic-9521`, repairshop — origin of
  the dual-actuation Repair Shop Governor shape, general pattern)
- ADR-2607086600 (`cloud-itonami-isic-9512`, commrepair — origin of
  the `customer-data-consent-unconfirmed?` check this build's own
  media-sanitization check is explicitly distinguished from)
- ADR-2607087400 (`cloud-itonami-isic-9523`, leathergoods — completion
  of the `cloud-itonami/cloud-itonami-isic-*` blueprint fleet)
- ADR-2607032000 (`cloud-itonami-isic-6511`, the original life-
  insurance reference implementation)
- the full `:adr/related` chain in the companion `.edn` file

## Context

Immediately after ADR-2607087400 declared the `cloud-itonami/cloud-
itonami-isic-*` blueprint fleet complete, the user was asked how the
recurring build loop should continue and explicitly chose to extend
the standing "pick a new ISIC blueprint vertical" authorization into
`gftdcojp/cloud-itonami-*` — 13 `kotoba-lang/industry` registry
entries whose `:repo` field pointed at that org.

Investigation revealed these 13 repos had actually been renamed/
transferred to `cloud-itonami/cloud-itonami-isic-<id>` (the same
taxonomy-prefix normalization every other repo in this fleet already
went through) — the registry's `:repo` field simply never caught up.
This was corrected in a dedicated `kotoba-lang/industry` commit
(`fix-stale-gftdcojp-repo-urls`, `c5e8afa`) prior to this build. Of the
13, one (4211, Community Building Construction) turned out to already
be partially implemented (a disaster/severe-weather-safety slice,
per its own `blueprint.edn`'s `:itonami.blueprint/maturity
:partially-implemented`) — noted for separate follow-up, since the
current three-tier maturity model (`:spec`/`:blueprint`/
`:implemented`) has no slot for a fourth tier without a library
change. The other 12 are genuine `:blueprint`-tier candidates with no
existing code, same as every prior build this session.

`cloud-itonami-isic-9511` ("Community ICT Equipment Repair") was
chosen first: its business (computers, servers, laptops, storage
arrays) is structurally close to the repair-shop-cluster pattern
already proven six times this session, and its own business-model.md
explicitly names sensitive customer device data as a Trust Control
concern ("customer device data stays outside Git", "sensitive
operating and personal data stays outside Git") — a strong, concrete
regulatory hook.

## Decision

Build `ictrepair` (RepairOps-LLM ⊣ Repair Governor) following the
exact governed-actor architecture established by `cloud-itonami-isic-
6511` and reused by every subsequent actor in this fleet:
`ictrepair.store` (Store protocol, MemStore + DatomicStore, proven
parity via `store-contract-test`), `ictrepair.registry` (pure DRAFT-
record construction, honest reuse of `parts-cost-matches-claim?`),
`ictrepair.governor` (independent HARD-check compliance layer, a new
`media-sanitization-unconfirmed?` check, and a `high-stakes` actuation
gate), `ictrepair.phase` (0→3 rollout table), `ictrepair.repairopsllm`
(mock+llm Advisor pair), `ictrepair.operation` (langgraph StateGraph,
generic shape copied verbatim), and `ictrepair.sim` (demo driver).

Unlike the `:repair-shop-governor` family, this blueprint's own
`:itonami.blueprint/governor` keyword, `:repair-governor`, is
grep-verified UNIQUE fleet-wide — no naming-collision documentation
was needed; this is simply a fresh, independent build with its own
governor identity, following the same architecture.

The shape is dual-actuation
(`#{:actuation/complete-repair :actuation/return-device}`), matching
the repair-shop-cluster's own shape, since this blueprint's own
`operating-states` (`:intake :diagnose :quote :repair :return :audit`)
and README text name two distinct real-world acts: completing a
repair and returning a device to the customer. `:device/return` (not
`:item/return`) matches `commrepair`/9512's own naming convention for
equipment.

The one genuinely new HARD check —
`media-sanitization-unconfirmed-violations` — is the SEVENTH
conditional variant of the unconditional-evaluation-discipline family
(after `socialresearch`/7220's, `bizassoc`/9411's, `training`/8549's,
`furniture`/9524's, `specialtyrepair`/9529's and `leathergoods`/9523's
own, at 63rd, 64th, 66th, 67th, 68th and 69th): it activates only when
a ticket's own record declares `:involves-storage-replacement? true`
(a screen or battery repair has no media-sanitization concern at
all). Before finalizing this check, `commrepair`/9512's own governor
was read in full: it already has a `customer-data-consent-
unconfirmed?` check (unconditional, addressing consent to ACCESS data
DURING repair). This new check addresses a genuinely different
real-world moment — secure DESTRUCTION of a REMOVED storage component
AFTER the repair decision to replace it. Grounded in real
media-sanitization/data-destruction law:

- US: NIST SP 800-88 Rev.1 (Guidelines for Media Sanitization),
  enforced via the FTC's FACTA Disposal Rule (16 C.F.R. Part 682).
- UK: UK GDPR Article 5(1)(f)/Article 32 (integrity/confidentiality,
  secure disposal), enforced by the Information Commissioner's Office.
- Germany: DSGVO Art. 32 / Bundesdatenschutzgesetz (BDSG), enforced by
  the BfDI and state data-protection authorities.
- Japan: 個人情報保護法 (APPI) 安全管理措置 requirements, enforced by
  個人情報保護委員会 (the Personal Information Protection Commission).

ALL FOUR seeded jurisdictions actually have a real regime here,
reported honestly (matching `leathergoods`/9523's own full-coverage
brand-authenticity sub-citation) rather than forcing an artificial
single-jurisdiction gap.

This is the 70th distinct application of the unconditional-evaluation
screening discipline in this fleet (most recently `leathergoods.
governor/brand-authenticity-unconfirmed-violations` at 69th).

Two checks are honest, literal reuses, not claimed as new:
`parts-cost-matches-claim?` and `safety-test-not-passed` (from the
repair-shop-cluster's own architecture).

## Consequences

- 86th actor in this fleet (85 implemented before this build).
- First build in the newly-approved gftdcojp-origin-registry scope
  extension. 11 genuine `:blueprint`-tier candidates remain (0162,
  0810, 4711, 4920, 5510, 7110, 7810, 8411, 9101, 9700, 9900), plus
  one partially-implemented entry (4211) noted for separate follow-up.
- Establishes a genuinely NEW conditional unconditional-evaluation-
  screening concept (media-sanitization-unconfirmed?), explicitly
  distinguished from `commrepair`/9512's own data-privacy check.
- `MemStore` ‖ `DatomicStore` parity is proven by
  `test/ictrepair/store_contract_test.clj`.
- 40 tests / 192 assertions pass; lint is clean; the demo
  (`clojure -M:dev:run`) walks one clean dual-actuation lifecycle plus
  five HARD-hold scenarios end-to-end.
- `kotoba-lang/industry`'s own full test suite (7 tests / 133
  assertions) was re-run clean before committing the promotion.
- `manifest/west.yml`'s `industry` pin was advanced via the GitHub API
  single-entry-commit path and verified canonical via
  `nbb scripts/gen-west-manifest.cljs --entry industry`.

## Scope note: the "630 vs 643" discrepancy is now resolved

Every ADR from 2607085700 through 2607087400 carried a scope note
explaining that `docs/cloud-itonami.md`'s own tier breakdown (summing
to 630) didn't match its own "Total entries: 643" figure. This build
resolves that discrepancy: the true cause was the 13 gftdcojp-origin
registry entries. `industry/maturity-summary` keys off `:repo`
PRESENCE regardless of URL staleness, so these 13 entries were ALWAYS
counted in the function's own `:blueprint` tally — but
`docs/cloud-itonami.md`'s manually-maintained breakdown line had
drifted out of sync and undercounted them. Ground truth via
`(industry/maturity-summary)` after this build:
`{:total 643 :spec 545 :blueprint 12 :implemented 86}` — now updated
in `docs/cloud-itonami.md` to match exactly. No further "630 vs 643"
scope note is needed in future ADRs.

## Test-example swap

`kotoba-lang/industry`'s own `test/kotoba/industry_test.clj` hardcoded
`"9511"` as its still-blueprint example (a leftover from the prior
turn's own `9523`-promotion swap). Since `9511` was the id being
promoted in this same commit, all 3 references were swapped to
`"9101"` (`gftdcojp`-origin, "Community Library and Archive") —
verified to have the IDENTICAL generic `:required-technologies` stack
and its own `:repo` field, so the `:has-repo?`/`ui-ready?`/
`export-ready?` assertions remain valid against the new example.

## Alternatives considered

- **Reusing `commrepair`/9512's own `customer-data-consent-
  unconfirmed?` check verbatim.** Rejected: that check is about
  consent to access data during repair; this check is about secure
  destruction of removed storage media afterward — a genuinely
  different real-world duty, explicitly distinguished in the code and
  docs, not a rehash.
- **An unconditional media-sanitization check.** Rejected: a screen
  or battery repair has no media-sanitization concern at all —
  forcing the check onto every ticket would fabricate a requirement.
- **Fabricating a jurisdiction gap** to match some prior siblings' own
  single-jurisdiction honesty gap. Rejected: the same honesty
  discipline that forbids fabricating coverage also forbids
  under-reporting it.

## References

- `cloud-itonami-isic-9511/docs/adr/0001-architecture.md` (child-repo
  ADR, full 10-decision structure)
- `cloud-itonami-isic-9512/docs/adr/0001-architecture.md`
  (`customer-data-consent-unconfirmed?`, the check this build's check
  is explicitly distinguished from)
- NIST SP 800-88 Rev.1, Guidelines for Media Sanitization (US)
- FACTA Disposal Rule, 16 C.F.R. Part 682 (US)
- UK GDPR Article 5(1)(f) / Article 32 (UK)
- DSGVO Art. 32; Bundesdatenschutzgesetz (BDSG) (Germany)
- 個人情報の保護に関する法律 (APPI) 安全管理措置 (Japan)
