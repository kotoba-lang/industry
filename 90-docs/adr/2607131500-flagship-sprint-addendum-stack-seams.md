# ADR-2607131500: sprint addendum — the stack seams (iterations 13–18)

**Status**: accepted
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki (+ Claude, /loop 常設承認のうえ実行)

## Context

Addendum to ADR-2607122300 (iterations 1–12). Iterations 13–18
completed the seams between the flagship replacements and the business
funnel's last mile.

## What landed

1. **Third vertical on the demo-page rule** —
   `cloud-itonami-isic-7810` (Placement Desk) got the build-time
   full-actor demo (two clean match+place lifecycles, four hold kinds,
   both double guards), Pages, registry `:demo`; the catalog picked it
   up via the nightly workflow (dispatched, green). Proved the
   ADR-2607122300 pattern reproduces in one iteration.
2. **The Indeed-stack seam, designed then implemented both sides** —
   ADR-2607131000 (human-carried referral drafts, no cross-actor
   invocation, PII reference only) was implemented next day:
   6399 `:application/refer` (repo ADR-0003; consent + live-posting
   HARD gates, `JPN-REF-NNNNNN` records) and 7810's `:referral-id`
   intake field (repo ADR-0002; Mem≡Datomic parity). Both public demos
   show the SAME record id leaving one ledger and arriving in the
   other.
3. **The kaonavi-side chain** — 6310 `draft-assignment :retention?`:
   a move drafted as a retention measure following a committed
   high-risk survey insight, cites naming survey business signals
   (fairness-safe by construction), record keeping `:basis :retention`
   — "why was this person moved" is answerable from the ledger.
4. **Operator paths** — 7810 quickstart (private stance); 6399
   quickstart "running the full stack" section.
5. **Lead channel** — operator-interest issue forms on all three
   flagship repos (mode/region/use-case), linked from the catalog's
   operator path. The funnel now has a capture point without external
   services.
6. **Automation observed working unattended** — the nightly catalog
   regeneration committed the 7810 demo link on schedule; the
   lead-links commit was re-applied cleanly on top of the bot's commit
   after a push race (regenerate-not-merge, per the manifest-workflow
   spirit).

## State of the directive

成熟度 (governed op coverage + tested seams), UI/UX 公開 (three
verified live demos + catalog + org profile), business 成立 (pricing/
UE/funnel docs + validated fork paths + lead capture) are all delivered
and recorded. Remaining known items require owner decisions or private
data: itonami.cloud cockpit marketplace ADR, 6310 real-data seeding
(m365-archive annex), real-LLM advisor wiring in a deployed instance,
jurisdictions beyond 6.

## References

ADR-2607122300; ADR-2607131000; repo ADRs 6399/0002-0003, 7810/0002,
catalog/0001.
