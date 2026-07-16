# ADR-2607142340: kotoba-lang/swift — real SWIFT MT wire format + real ISO 20022 XML (upgrade from placeholder EDN)

**Status**: accepted (implemented, landed to `kotoba-lang/swift` main)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/swift` (west project `swift`); no other repo's code
changed, `manifest/west.yml` pin advanced for the `swift` entry only.

## Context

`kotoba-lang/swift` modeled SWIFT MT messages and ISO 20022 envelopes as
plain EDN records — explicitly documented as a deliberate placeholder
("SWIFT MT on the wire uses `{1:...}{4:...}` brace blocks and ISO 20022 is
XML; here both are EDN"). That was reasonable for an early capability
library but meant nothing built against it was genuinely compatible with a
real banking backend.

This matters now because `cloud-itonami-isic-6493` (a factoring-business
governed actor being built in this fleet concurrently — see
ADR-2607141700-cloud-itonami-isic-6493-factoring-actor and
ADR-2607142000-cloud-itonami-isic-6493-murakumo-worker-deployment) and its
Cloudflare Worker deployment need standards-accurate banking-message
capability to eventually wire into — even though no live SWIFTNet/bank
connection will ever be attached from this codebase (that step requires a
licensed financial institution, out of scope here). The owner's explicit
ask: implement code genuinely identical to the real specification, not an
approximation, so a licensed operator could plug it in downstream with
minimal translation work.

## Decision

Upgraded `kotoba-lang/swift` in place (landed to its own `main`, this
superproject's pin advanced to follow):

1. **Real SWIFT MT wire format** for **MT103** (Single Customer Credit
   Transfer) and **MT202** (General Financial Institution Transfer): the
   actual `{1:...}{2:...}{3:...}{4:...}{5:...}` brace-block structure and
   real field tags (`:20:`, `:23B:`, `:32A:`, `:50K:`, `:59:`, `:70:`,
   `:71A:`, etc.), generated as a real wire string and parsed back
   losslessly (`kotoba.swift/mt->wire` / `parse-mt-wire`).
2. **Real ISO 20022 XML** for **pain.001.001.09**
   (CustomerCreditTransferInitiation) and **pacs.008.001.08**
   (FIToFICustomerCreditTransfer): the real element hierarchy and namespace
   URIs, emitted as actual well-formed XML via `kotoba-lang/xml` (new
   `kotoba.swift.iso20022` namespace) and parsed back losslessly.
3. BIC (ISO 9362) validation kept unchanged (it was already correct).
4. Purity kept: no network, no I/O — construction/parsing only.
5. `kotoba.swift.ui` / `kotoba.swift.export` updated to render/export the
   real wire string and real XML instead of the old EDN shape.

Every field tag, format constraint, and element name was independently
verified against public sources (SWIFT field-tag detail references, a
SWIFT MT developer guide, a raw MT103 wire example, SWIFT usage-rule T26
docs, ISO 20022 implementation guides quoting the real element hierarchy,
and the `ChargeBearerType1Code` external code list) before being encoded —
not reconstructed from memory. Full citation-per-claim list lives in
`kotoba-lang/swift`'s own `docs/adr/0001-real-wire-format.md`.

**Scope, stated honestly** (matches this fleet's jurisdiction-facts
honesty discipline: report what's covered, don't overclaim): only
MT103/MT202 out of SWIFT's full MT catalogue, and only
pain.001.001.09/pacs.008.001.08 out of the full ISO 20022 catalogue.
Within those, only the field/element subset each builder's docstring lists
(no field 13C/23E/26T/36/51A/71F/71G/77B for MT103, no full Output-direction
Block 2 decoding, no SWIFT checksum algorithm, no `PstlAdr`/ultimate-party/
structured-remittance for ISO 20022). No XSD schema validation. No
connection to SWIFTNet or any bank, ever, from this codebase.

**Zero-blast-radius verified before removing the old placeholder API**: a
repo-wide grep found `kotoba-lang/kessai` as this superproject's only other
consumer of `kotoba.swift`, and it only calls `bic-valid?` (unchanged) — so
`mt-message`/`iso-20022-envelope` (the old placeholder constructors) were
removed rather than deprecated-and-kept.

## Landing

- Fresh clone to a sibling scratch path (not the shared west checkout),
  branch `feat/real-wire-format`, commit, push, server-side merge via
  `gh api repos/kotoba-lang/swift/merges` (no local rebase, no force-push)
  → `kotoba-lang/swift` main at `00b51ce26e58878abff52e0db31bd05eb69c7e55`.
  Merged branch deleted.
- Tests: 42 → 207 assertions, all green (BIC unchanged + MT103/MT202 wire
  round-trip + pain.001/pacs.008 XML round-trip + malformed-input rejection
  for both). `clj-kondo` clean (0 errors, 0 warnings).
- This superproject's `manifest/west.yml` `swift` entry pin advanced
  `7290f20…` → `00b51ce…` via the documented single-entry GitHub API PUT
  (blob-SHA optimistic lock, diff limited to the one `revision:` line) —
  commit `f99d649df0da28ff2f606c42ed5354ae2f1313b6`. Verified with
  `nbb scripts/gen-west-manifest.cljs --check` from a topdir-isolated
  sibling worktree (`west.yml is up to date.`), then the worktree was
  removed.

## Consequences

- (+) `kotoba.swift` / `kotoba.swift.iso20022` now produce message
  construction/parsing that a licensed operator can plug into a real
  banking backend with additive translation work (more fields, more
  message types) rather than corrective work (fixing a wrong wire shape).
- (+) `cloud-itonami-isic-6493` and future banking-adjacent actors in this
  fleet have a real capability library available to wire into once that
  work starts, instead of an EDN placeholder that would need a rewrite
  first.
- (+) The operator console (`kotoba.swift.ui`) and audit export
  (`kotoba.swift.export`) now show operators exactly what would go over the
  wire, not an internal approximation.
- (−) The two-message-type-per-standard scope is a real, documented limit —
  callers needing MT200/MT210/MT9xx or other ISO 20022 message definitions
  must extend the library first (tracked as an explicit non-goal, not a
  silent gap).
- (−) `kotoba-lang/kessai`'s own hand-rolled minimal pain.001 XML builder
  (`kotoba.kessai.wire/->pain001-xml`) was left untouched — it's a separate,
  simpler, single-transaction builder that predates this upgrade and was
  out of scope; a follow-up could migrate it to `kotoba.swift.iso20022` but
  that wasn't done here to keep this change's blast radius to the one repo.
  ~~Migrated in Addendum 1 (2026-07-15).~~

## Addendum 1（2026-07-15、kessai migration）

Migrated the one follow-up this ADR's own Consequences flagged:
`kotoba-lang/kessai`'s `kotoba.kessai.wire/->pain001-xml` now delegates to
`kotoba.swift.iso20022/pain001-doc` + `xml->str` instead of its own
hand-rolled string-splicing builder.

The migration surfaced two real, previously-silent gaps in the old
hand-rolled version: `<CreDtTm/>` and `<Dbtr><Nm/></Dbtr>` both rendered
**empty**, because `credit-transfer` never collected `creation-date-time`
or `debtor-name` at all. `pain001-doc` correctly refuses to build an
incomplete document (returns `nil` for a missing required field) — so
`credit-transfer` now requires `debtor-name`, `creation-date-time`,
`payment-info-id`, and `requested-execution-date` as well.

Converting the amount from major units to the integer minor units
`pain001-doc` requires introduces a NEW failure mode the old code never
had: silent sub-cent precision loss (e.g. `12.567` → `1257`). Closed with
a `representable-in-2-decimals?` guard that throws instead of silently
rounding away money — verified empirically that `2.675` is correctly
rejected (`2.675 * 100` evaluates to exactly `267.5` in double arithmetic,
genuinely not a whole cent, not a false positive).

Blast radius confirmed contained to `kotoba-lang/kessai` alone (its
`wire` namespace has no external callers; `kotoba.kessai`'s own main
namespace only mentions it in docstrings, never requires or calls it).

Tests rewritten to use `parse-xml`/`xml-find`/`xml-text` for structural
assertions (robust to the real emitter's pretty-printed whitespace)
instead of raw string regex assuming compact XML, matching this
namespace's own test conventions (`iso20022_test.cljc`). Full suite:
10 tests / 87 assertions (was 66), 0 failures/errors. The README example
was updated and independently re-run to confirm it still works end to
end.

Landed: `kotoba-lang/kessai`, `main` → `0c0f795761308c251821bc700368bfc4e4004974`,
sibling worktree (with `swift`/`banking`/`card`/`html`/`css`/`xml` mirrored
in via symlinks for `:local/root` dependency resolution) + `gh api
.../merges` server-side merge + branch cleanup. West pin advanced
(`kessai` `f530cdf5` → `0c0f7957`, verified via `gh api compare`:
`ahead_by=2, behind_by=0, merge_base==old pin`).

## References

- `kotoba-lang/swift` `docs/adr/0001-real-wire-format.md` (full source
  citations per field/element claim)
- `kotoba-lang/swift` `README.md` (updated contract + scope section)
- `kotoba-lang/banking` (sibling capability library whose conventions —
  pure `.cljc`, operator console, CSV/JSON export, integer-minor-unit
  amounts — this upgrade matched)
- ADR-2607141700-cloud-itonami-isic-6493-factoring-actor,
  ADR-2607142000-cloud-itonami-isic-6493-murakumo-worker-deployment (the
  concurrent actor work this capability library is meant to eventually
  support)
- This file's paired `.edn`
