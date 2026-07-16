# ADR-2607081800: `cloud-itonami-isic-6419` (community monetary intermediation) deepened to `:implemented`

## Status

Accepted.

## Related

- ADR-2607032000 (original insurance/real-estate batch)
- ADR-2607081500 (`cloud-itonami-isic-8810`, community care coordination)
- ADR-2607081600 (`cloud-itonami-isic-8691`, health access navigation)
- ADR-2607081700 (`cloud-itonami-isic-8569`, community learning support)
- `cloud-itonami-isic-6419/docs/adr/0001-architecture.md` (child-repo architecture ADR)
- `langgraph-clj` ADR-0001 (StateGraph actor pattern)

## Context

`cloud-itonami-isic-6419` publishes an OSS business blueprint for
community banking: deposit and account operations, lending, interbank
messaging, clearing and settlement. Like every prior vertical in this
fleet, the blueprint text alone is not an implementation — this ADR
records the governed-actor build that promotes
`cloud-itonami-isic-6419` from `:blueprint` to `:implemented` in the
`kotoba-lang/industry` registry, the thirty-ninth vertical built
outside ADR-2607032000's original insurance/real-estate batch.

## Problem

1. `cloud-itonami-isic-6419`'s blueprint described a real-world
   community-banking operating model (account intake, AML/KYC
   assessment, sanctions screening, settlement posting, interbank-
   message dispatch) but had no governed-actor implementation: no
   Store, no Governor, no rollout phasing, no tests.
2. The blueprint's own Minimum Production Controls name a concrete,
   verifiable requirement not seen in any prior sibling: "IBAN mod-97
   verification before any external transfer" — this needed a REAL
   algorithm, not a fabricated placeholder, unlike every prior check
   family in this fleet (which compare two already-trusted fields
   against each other or a threshold).
3. `blueprint.edn` carried a stale pre-rename `:itonami.blueprint/id`
   (`"cloud-itonami-6419"`, missing the `isic-` infix) and was missing
   `:robotics` from its `:required-technologies` vector despite
   `:itonami.blueprint/robotics true` already being set separately.
4. This blueprint's `:required-technologies` uniquely names `:banking`/
   `:swift` as required capability libraries — a decision was needed
   on whether to add `kotoba-lang/banking`/`kotoba-lang/swift` as real
   code dependencies or follow the fleet's established self-contained
   convention.

## Decision

1. **Entity and op shape.** Primary entity `account`. Five ops:
   `:account/intake`, `:compliance/verify`, `:sanctions/screen`,
   `:actuation/post-settlement` (high-stakes), and `:actuation/
   dispatch-interbank-message` (high-stakes) — a dual-actuation-on-
   one-entity shape grounded directly in the blueprint's own Core
   Contract diagram and Trust Controls.
2. **`iban-checksum-invalid?` — the FIRST checksum/format-validity
   check, a real algorithm.** `banking.registry` implements ISO 7064
   MOD 97-10 (rearrange, letter-substitute A=10..Z=35, mod 97, valid
   iff remainder = 1) against the account's own IBAN field — no
   fabricated placeholder, tested against well-known published IBAN
   examples (Deutsche Bundesbank's own `DE89370400440532013000`, plus
   commonly-cited UK/FR examples). This is a genuinely new check-
   family shape: the identifier proves or disproves itself, no second
   field needed. Gates only `:actuation/post-settlement`.
3. **A portability lesson, caught before any test ran.** A first
   draft of the mod-97 computation used JVM-only `Character/isDigit`,
   `Character/toUpperCase` and `int`-on-char arithmetic — this would
   silently misbehave if the `.cljc` namespace were ever compiled for
   ClojureScript. Rewritten to use portable `clojure.string`-based
   digit lookups (single-character substrings + a string-keyed digit-
   value map) before any test was written, keeping the file a real,
   portable `.cljc`.
4. **`sanctions-violations` — reuse of the established name, the 6th
   literal grounding.** Verified via a literal `defn-` grep (not just
   a docstring mention): `underwriting.governor`, `casualty.governor`
   (the flagship original), `vcfund.governor`, `formation.governor`
   and `realty.governor` all already have their own literal
   `sanctions-violations` check. Sanctions screening is a genuinely
   shared, industry-standard AML/OFAC concept across financial-
   services verticals — unlike this fleet's domain-specific
   "safeguarding"/"risk" concepts, reusing the identical name here is
   the honest choice. Gates `:sanctions/screen` and `:actuation/
   dispatch-interbank-message`.
5. **No bespoke capability-lib dependency**, despite `blueprint.edn`
   naming `:banking`/`:swift` as required technologies — following
   every sibling actor's posture of implementing the specific ground-
   truth check a governor needs directly (the real IBAN checksum)
   rather than adding an external dependency for a scaffold this
   narrow in scope.
6. **Dedicated double-actuation-guard booleans.** `:settlement-
   posted?`/`:interbank-message-dispatched?` on the `account` record.
7. **Store protocol, MemStore + DatomicStore parity**, proven via
   `test/banking/store_contract_test.clj`. The per-entity accessor is
   safely named `account` directly.
8. **Phase 0→3 rollout** — Phase 3 `:auto` set is `{:account/intake}`
   only; both actuations are permanently excluded from every phase's
   `:auto` set.
9. **Mock + LLM advisor pair** — `mock-advisor` default everywhere,
   `llm-advisor` with a defensive EDN-proposal parser.
10. **`blueprint.edn` field-sync fixes** — corrected the stale
    `:itonami.blueprint/id` and added the missing `:robotics` entry to
    `:required-technologies`.
11. **A pre-existing test-fixture repoint.** Promoting `6419` broke a
    pre-existing `industry_test.clj` fixture that had hardcoded ISIC
    `"6419"` as its generic "some `:blueprint` entry" example — caught
    by re-running the full industry test suite before committing, and
    repointed to `"9411"` (still `:blueprint`, confirmed with a real
    repo) rather than deleting or weakening the assertions.

## Consequences

- Fifty-third actor in this fleet (52 implemented before this build).
- Establishes the first checksum/format-validity check family in this
  fleet, backed by a real, standards-conformant algorithm.
- Confirms the `sanctions-violations` concept generalizes cleanly to
  a sixth grounding, the first in a deposit-taking/core-banking
  context specifically.
- `MemStore` ‖ `DatomicStore` parity proven by contract test.
- Two pre-existing `blueprint.edn` inconsistencies and one pre-
  existing test-fixture assumption fixed as in-scope minor
  consistency work.
- Fleet maturity: `:implemented` 52 → 53, `:blueprint` 32 → 31,
  `:spec` 546 unchanged, total 643.
- Test status: 38 tests / 176 assertions (child repo), plus a
  repointed 7 tests / 101 assertions (kotoba-lang/industry), all
  green.
- Next vertical selection remains unconstrained under the standing
  authorization.

## Alternatives considered

| Alternative | Rejected because |
|---|---|
| Add `kotoba-lang/banking`/`kotoba-lang/swift` as real `deps.edn` dependencies | Every sibling implements domain-specific ground-truth checks directly rather than depending on an external capability library; this would be a scope expansion beyond a `:blueprint`→`:implemented` promotion and introduce version-compatibility risk none of this fleet's other 52 actors carry |
| Name the sanctions check something domain-specific | Sanctions screening is genuinely the same AML/OFAC concept across every financial-services vertical in this fleet (confirmed by reading, not just grepping, five siblings' own literal definitions) — reusing the exact name is the honest choice |
| Implement the IBAN checksum with JVM `Character`/`int` interop (first draft) | Breaks ClojureScript portability, violating the `.cljc` discipline every sibling namespace follows — rewritten before any test was written |

## References

- `orgs/cloud-itonami/cloud-itonami-isic-6419/docs/adr/0001-architecture.md`
- `orgs/cloud-itonami/cloud-itonami-isic-6419/README.md`
- `orgs/cloud-itonami/cloud-itonami-isic-6419/docs/business-model.md`
- `orgs/kotoba-lang/industry/resources/kotoba/industry/registry.edn` (entry `"6419"`)
