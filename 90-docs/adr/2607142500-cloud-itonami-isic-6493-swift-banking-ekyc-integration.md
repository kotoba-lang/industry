# ADR-2607142500: `cloud-itonami-isic-6493` wires `kotoba-lang/swift`/`kotoba-lang/banking`/`kotoba-lang/ekyc` in as live code

- Status: Accepted (2026-07-14)
- Related: ADR-2607141700 (`cloud-itonami-isic-6493`'s own build);
  ADR-2607142000 (its Cloudflare Worker deployment, re-smoke-tested
  against here); `cloud-itonami-isic-6493/docs/adr/0003-real-banking-
  swift-ekyc-integration.md` (the authoritative repo-level record --
  this superproject ADR records fleet-level context only);
  `kotoba-lang/swift`, `kotoba-lang/banking`, `kotoba-lang/ekyc` (the
  three sibling capability libraries this ADR wires in, built by
  parallel agents the same day)

## Context

Three sibling capability libraries landed the same day as `cloud-
itonami-isic-6493`'s Worker deployment, built by parallel agents:
`kotoba-lang/swift` (upgraded from an EDN placeholder to a real SWIFT
MT103/MT202 wire-format encoder + real pain.001/pacs.008 ISO 20022
XML), `kotoba-lang/banking`'s new `kotoba.banking.api` (a real Berlin
Group NextGenPSD2 XS2A payment-initiation/account-information layer),
and `kotoba-lang/ekyc`'s new `kotoba.ekyc` (a real JPN 犯収法施行規則
Art.6(1) non-face-to-face identity-verification method catalog, with an
honest NIST SP 800-63A-4 IAL cross-reference).

Every prior `cloud-itonami-isic-*` actor's `:banking`-tagged capability
reference has been "cited but not directly required" -- e.g. ADR-2607080200
(`6491`) explicitly: "cites `kotoba-lang/banking` as a related-but-not-
required capability contract... this actor remains self-contained like
every prior sibling." This ADR is the first in this fleet to change
that, on explicit owner instruction: wire these three libraries into
`cloud-itonami-isic-6493`'s ACTUAL governed flow as live code.

## Decision

1. `:receivable/verify` (re-checked at `:advance/fund` too, closing the
   same "skip verify" bypass gap `sanctions-hit-violations` already
   guards against) now constructs and validates a REAL `kotoba.ekyc`
   verification record for the CLIENT and the ACCOUNT DEBTOR against
   the real statutory method catalog. A new HARD, un-overridable check
   (`factoring.governor/ekyc-verification-invalid-violations`) carries
   the full structured `kotoba.ekyc/validate` result (`:disposition`/
   `:reasons`) -- a real, specific rejection reason, not an abstract
   boolean, per explicit instruction.
2. `:advance/fund` (**actuation 1**) and `:reserve/settle` (**actuation
   2**) now construct a REAL settlement-instruction artifact on every
   clean commit: a genuine SWIFT MT103 wire string (`kotoba.swift`) or
   a genuine Berlin Group XS2A payment-initiation JSON payload
   (`kotoba.banking.api`). **Chosen: XS2A as the default rail** --
   factoring's actual settlement pattern (the factor's own bank paying
   its own client) is structurally an XS2A payment-initiation, not a
   correspondent-banking interbank wire. MT103 is auto-selected
   whenever the currency's decimal convention isn't 2 (`kotoba.banking.
   api`'s own documented limitation: its amount conversion assumes 2
   minor units always, so a JPY advance routed through XS2A would
   misconvert by 100x) -- a correctness guard, not a preference, that
   also happens to line up with Japan having no IBAN scheme at all (an
   honestly-cited coincidence, not the stated rationale). The artifact
   is stored in the audit ledger and returned in the HTTP response --
   see `cloud-itonami-isic-6493/docs/adr/0003`'s Decision 2 for the
   full reasoning.
3. New admin (not governed) `factoring.store` concept:
   `settlement-account` -- this factor's own `{:iban :bic :account-
   holder-name}`, the ordering/debtor side of every artifact. Same
   admin-not-governed posture ADR-2607142000's `register-funder!`
   already established.
4. `build-settlement-instruction` gracefully degrades (never crashes)
   when a receivable's banking details are incomplete for the chosen
   rail -- a data-completeness concern, not a governance one; the
   actuation DECISION itself is unaffected either way.
5. A second instance of the JSON-string-vs-Clojure-keyword coercion bug
   class (ADR-2607142000 found `:debtor-risk-tier` broken this way
   live) was found and fixed PROACTIVELY this time for the new eKYC
   fields, before shipping -- not rediscovered live a second time.
6. Live re-smoke-tested end-to-end against `https://factoring.
   murakumo.cloud` before landing: a JPY receivable's `POST /verify`
   response was confirmed to carry a real `kotoba.ekyc/validate` result
   (`"disposition":"pass"`); its `POST /advance`/`POST /settle`
   responses were confirmed to carry a genuine, well-formed SWIFT MT103
   wire string with correct value-date/currency/amount; an unrecognized
   eKYC method was confirmed to produce a real, specific 409. Test data
   deleted from the live KV afterward, same discipline ADR-2607142000
   established.

## Honesty boundary -- restated, sharper, now that real artifacts are in play

This deployment makes the GOVERNED DECISION software live, callable and
durable at a real HTTPS URL. It does NOT wire any real bank transfer/
payment rail, real funder capital, or real KYC/AML provider, and does
not claim any real money moves through it. As of this integration, the
ARTIFACTS this actor produces are themselves REAL and spec-conformant
-- a genuine SWIFT MT103 wire string, a genuine Berlin Group XS2A
payment-initiation JSON payload, a genuine 犯収法施行規則-conformant eKYC
verification-method validation -- but EVERY one of them is constructed,
stored in the audit ledger, and returned in the API response, and NEVER
transmitted anywhere: no real SWIFTNet connection, no real bank API
call, no real eKYC vendor call. This actor produces exactly what a
licensed operator's real banking/eKYC integration would need to receive
and forward; the forwarding step itself remains explicitly out of scope
and unattached. This boundary is documented verbatim in the repo's
`worker/README.md`, the repo's own `docs/adr/0003`, and this
superproject ADR -- three places, per explicit instruction, because
"deployed and live" is exactly the point at which this boundary is
easiest to blur by accident.

## Consequences

- (+) `cloud-itonami-isic-6493` is the first actor in this fleet to
  depend on a `:banking`-family capability library as live code rather
  than a docstring citation -- a genuine precedent change.
- (+) The rail-selection logic is driven by a genuine technical
  correctness constraint (XS2A's documented 2-decimal assumption)
  rather than an arbitrary rule, and the JPY/no-IBAN correspondence is
  honestly cited as a coincidence, not oversold as the design
  rationale.
- (+) The second JSON-coercion bug class was fixed proactively, direct
  evidence the prior ADR's lesson transferred forward within the same
  day.
- (-) The eKYC check's scope (extending 犯収法's customer-only reading
  to the account debtor too) is a deliberate, documented enhanced-due-
  diligence choice, not the only defensible one -- see the repo ADR's
  own Consequences section.
- Repo-wide: 100 tests / 389 assertions (82/336 core actor + 18/53
  worker HTTP-API), lint-clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| MT103/MT202 (correspondent banking) as the default settlement rail | ❌ | Factoring's actual pattern (the factor's own bank paying its own client) is structurally an XS2A payment-initiation, not an interbank wire |
| See `cloud-itonami-isic-6493/docs/adr/0003` for build-level decisions | -- | (graceful-degradation design, eKYC scope choice, settlement-account admin posture, etc.) |

## References

- ADR-2607141700 (`cloud-itonami-isic-6493`'s own build)
- ADR-2607142000 (its Cloudflare Worker deployment)
- `cloud-itonami-isic-6493/docs/adr/0003-real-banking-swift-ekyc-integration.md`
  (the authoritative integration record)
- `cloud-itonami-isic-6493/worker/README.md`
- Live URL: `https://factoring.murakumo.cloud`
