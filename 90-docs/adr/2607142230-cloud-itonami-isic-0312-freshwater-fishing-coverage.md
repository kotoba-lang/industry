# ADR-2607142230: cloud-itonami-isic-0312 (Freshwater fishing) fleet-operations-coordination actor

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki (agent-executed, standing authorization)
**Related**: ADR-2607151700 (cloud-itonami-isic-0311 Marine fishing --
the direct domain analog and reference implementation this actor
mirrors), ADR-2607011000 (ISIC section coverage), the
`kotoba-lang/industry` registry's `"0312"` catalog entry

## Context

`cloud-itonami-isic-0311` (Marine fishing, ADR-2607151700) established a
verified, tested fishing-fleet operations coordination actor after a
prior fabricated-report incident on that same code. This ADR extends
coverage to ISIC Rev.5 0312 (Freshwater fishing) as a fresh scaffold,
with no prior repo or attempt to correct.

Freshwater (inland: river/lake) fishing fleets need the same class of
back-office coordination as marine fleets -- catch-record/quota logging,
maintenance scheduling, safety-concern escalation, supply ordering --
but differ from marine fishing in one structural respect: there is no
maritime-zone (EEZ) concept. A freshwater vessel operates on a single
named inland water body (a river or lake) under one jurisdiction's
permit/quota regime, not a multi-zone exclusive-economic-zone allocation.
This actor's `store.cljc` therefore replaces 0311's `:flag-state` field
with `:water-body`, and its governor's quota-exceedance check remains a
single-permit, single-quota check with no per-zone split.

This is a safety-critical small-craft domain (rivers/lakes). The actor is
explicitly coordination-only and never has vessel navigation, vessel
command, fishing-gear-operation, or catch-decision authority -- those
remain the vessel operator's exclusive human authority on the water.

## Decision

Implement `cloud-itonami-isic-0312` as a freshwater fishing-fleet
BACK-OFFICE OPERATIONS COORDINATION actor, mirroring
`cloud-itonami-isic-0311`'s verified pattern exactly in shape:

1. **`freshwater-fishing.governor`** -- independent compliance layer,
   hard rules:
   - vessel/permit must be verified and `:status :active` before any
     action (`:vessel-not-found` / `:vessel-inactive`)
   - proposal `:effect` must match the op's one legitimate effect
     (`:effect-mismatch`)
   - all effects must be `:propose` only (`:non-propose-effect`)
   - `:vessel-id`, `:permit-number`, `:vessel-status` are forbidden
     patch fields (`:forbidden-field`)
   - a CLOSED op allowlist (`op->effect`) is the only legitimate set of
     proposals: `:log-catch-record`, `:schedule-vessel-maintenance`,
     `:flag-safety-concern`, `:order-supplies` -- anything outside it
     (`:navigate-vessel`, `:command-vessel`, `:decide-catch`) is a
     structural, permanent `:blocked-operation` hard-hold, no human
     override
   - quota exceedance (a `:log-catch-record` proposal whose
     `:quantity-kg` would push the vessel's cumulative `:landed-kg`
     past its `:quota-kg`) is ALSO a hard, permanent block
     (`:quota-exceedance`) -- a regulatory compliance breach, not a
     judgment call, so it is never a soft/overridable escalation. No
     maritime-zone split exists to reallocate against.
   - soft escalations (human sign-off required, not a rejection):
     `:flag-safety-concern` always escalates immediately (covers
     vessel-safety, water-level/flash-flood, and weather concerns);
     supply orders >= 10,000 units cost escalate; advisor confidence
     < 0.6 escalates
2. **`freshwater-fishing.store`** -- `Store` protocol + `MemStore`:
   vessel lookup (`:water-body` replaces 0311's `:flag-state`),
   `log-catch!` (accrues `:landed-kg`), `schedule-maintenance!`,
   `flag-safety-concern!`, `order-supply!`, `audit-log` -- append-only
   per-vessel audit trail.
3. **`freshwater-fishing.llm-advisor`** -- `Advisor` protocol + mock
   impl; no external service calls in the base module; structurally
   cannot generate ops outside the governor's closed allowlist.
4. **`freshwater-fishing.operation`** -- langgraph-clj `OperationActor`
   StateGraph: `intake -> advise -> govern -> decide -> finalize`, one
   graph run = one auditable operation, checkpointed, no unbounded loop.
   Uses the correct `g/compile-graph` API (verified against
   `kotoba-lang/langgraph`, cloned fresh into the build environment).
5. **`freshwater-fishing.sim`** -- demo driver.

### What this actor does NOT do

Explicitly documented (README, ADR, `freshwater-fishing.operation`
docstring): no vessel navigation (course/heading/waypoint/autopilot), no
fishing-gear operation (nets/lines/traps/hooks), no catch decisions
(species/quota-allocation/where-when-to-fish), no vessel command
(engine/throttle/propulsion). These remain the vessel operator's
exclusive human authority on the water, permanently, with no actor or
human-approval override path.

## Verification

- `cloud-itonami-isic-0312`: `clojure -M:test` -- raw final line: `Ran
  20 tests containing 31 assertions.` / `0 failures, 0 errors.`
  (governor contract: vessel verification, effect integrity,
  propose-only, forbidden fields, all three blocked ops individually,
  quota-exceedance hard-block AND quota-within-limit non-block, all
  three escalation triggers, clean-proposal-passes; store contract:
  vessel lookup, catch logging, quota accrual across two catches, audit
  trail, maintenance scheduling, supply ordering).
- `clojure -M:lint` -- 0 errors, 8 warnings (unused-binding style
  warnings only, same category the 0311 reference also carries;
  `--fail-level error` does not fail on these).
- Commit `9020852` pushed directly to `cloud-itonami-isic-0312`'s
  `main` (fresh repo, initial commit, `main` created at this SHA).
- Independently re-verified with a fresh `git clone --depth 1` into a
  brand-new scratch directory (plus fresh `kotoba-lang/langgraph` and
  `kotoba-lang/langchain` sibling clones), after all registry work
  below was complete: `clojure -M:dev:test` -- `Ran 20 tests containing
  31 assertions.` / `0 failures, 0 errors.` (unchanged from the initial
  run, as expected -- this repo had no further pushes). `clojure
  -M:lint` -- 0 errors, 8 warnings, same as before.

## Registry

`kotoba-lang/industry`'s `"0312"` catalog entry updated in place
(exact-text edit of the literal `{:id "0312" ...}` block, not a
parse-transform-reserialize) from `:maturity :spec` to `:maturity
:implemented`, `:repo` set to
`https://github.com/cloud-itonami/cloud-itonami-isic-0312`, and an ADR
reference added pointing at this document. `industry_test.clj`'s
`:implemented` count assertion was recomputed from the live file via
`industry/maturity-summary` (not assumed) and bumped accordingly.
Landed via a GitHub API server-side merge (`base=main`,
`head=<registration-branch>`), not a local merge/rebase, retrying after
two textual 409s caused by other concurrent agents landing their own
ISIC promotions on `kotoba-lang/industry`'s `main` in the same window
(observed tip advancing `47a2617` -> `848dc0c` -> `c607b37` -> `d822e0d`
across retries; each retry re-fetched `origin/main`, re-verified the
pristine baseline was green, reapplied the edit, and recomputed the
count fresh rather than assuming a fixed number).

### Incident: a self-inflicted file-wide encoding corruption, and its fix

The registration commit that finally landed (`a78decb`, merged as
`0c00a63`) was produced by an automated retry-loop script using a Perl
`-0777 -pi` one-liner to reapply the 0312 edit against each freshly
fetched `main` tip. That one-liner's replacement text contained a
Unicode escape (`\x{22a3}` for `⊣`) without `use utf8`/proper IO
encoding directives. Perl's internal byte-string/UTF8-string "upgrade"
semantics then re-encoded **every pre-existing multi-byte UTF-8
character in the entire file** (em dashes, section signs, Japanese
text, and the `⊣` turnstile used throughout many other entries' Governor
doc comments) as if each original UTF-8 byte were a separate Latin-1
codepoint -- e.g. `⊣` became `â£`, `建築基準法` became
`å»ºç¯...`. 859 lines were mangled this way. The newly-added
`cloud-itonami-isic-0312` entry itself was unaffected (its replacement
text used a real Perl Unicode literal, not raw file bytes), so the
corruption was entirely collateral damage to unrelated, pre-existing
registry entries.

This was caught immediately after the merge by diffing the merged
commit against its parent and noticing an alarming "881 insertions,
866 deletions" for what should have been a ~20-line change (violating
this ADR's own "exact-text in-place edit" discipline). Root-caused via
`gh api .../commits/<sha>` + a raw diff fetch showing the mojibake
pattern. Fixed in a follow-up commit (`046d0ee`, merged as `1dd961a`)
using a Python 3 script (`line.encode('latin-1').decode('utf-8')` per
line) that reverses the double-encoding exactly: the round-trip only
succeeds for lines that were actually corrupted (genuine non-Latin-1
codepoints -- including the correctly-encoded `0312` entry's own `⊣`
-- raise `UnicodeEncodeError` and are left untouched), so no manual
transcription was needed and no legitimate content was at risk. Fetched
`origin/main` immediately before and after the fix; confirmed no other
agent had landed a commit on top of the corrupted state in that window,
so no other agent's work needed reconciling. Verified the fix commit's
diff against the last known-good pre-corruption commit (`d822e0d`)
showed *only* the intended 20-line `0312` change, byte-identical
elsewhere.

Post-fix, full-suite `clojure -M:test` on a fresh clone: `Ran 15 tests
containing 941 assertions.` / `0 failures, 0 errors.` `clojure -M:lint`:
`0 errors, 0 warnings`. `grep -c 'â' resources/kotoba/industry/registry.edn`
on the fresh clone: `0`. `industry/maturity-summary` on the fresh clone:
`{:total 648, :spec 426, :blueprint 25, :implemented 197}`, matching the
test file's `(is (= 197 (:implemented m)))`.

## Consequences

(+) `cloud-itonami-isic-0312` (Freshwater fishing) now has a real,
tested implementation matching the shape of `cloud-itonami-isic-0311`
and every other cloud-itonami ISIC actor.
(+) The EEZ/maritime-zone concept is deliberately absent from this
actor's design (`:water-body` instead of `:flag-state`, single-permit
quota with no zone split) -- a freshwater-specific adaptation rather
than a mechanical marine-domain copy.
(+) Safety-critical discipline (coordination-only, no navigation/
vessel-command/gear-operation/catch-decision authority) is preserved
identically to the marine reference.
(-) `freshwater-fishing.sim`'s demo prints a summary rather than driving
a full request through the compiled `freshwater-fishing.operation`
graph end-to-end (mirroring 0311's own scope); wiring that up is a
natural, small future extension, not required for this ADR's
verification bar.
(-) A self-inflicted registry-wide mojibake corruption (see Incident
section above) briefly landed on `kotoba-lang/industry`'s `main`
between commits `0c00a63` and `1dd961a`; it was caught and fixed within
the same work session, but it is a real regression this ADR's own
author caused, not a hypothetical risk -- future automated in-place
edits to this file (or any `.edn` containing non-ASCII text) must go
through a text-editing tool with well-defined, verified UTF-8 handling
(this codebase's `Edit` tool, or Python 3 with explicit `encoding="utf-8"`
I/O) rather than a raw Perl one-liner mixing byte-mode reads with
Unicode-escaped replacement text.
(-) No jurisdiction-specific regulatory-fact catalog (citation-backed
`facts.cljc`) is implemented; docs explicitly flag this as
unimplemented rather than claiming it exists.
