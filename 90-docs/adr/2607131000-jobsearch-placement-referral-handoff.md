# ADR-2607131000: 求人サーチ→職業紹介のハンドオフは「人間が運ぶ referral draft」— actor 間直接呼び出しの禁止

**Status**: accepted (design rule; implementation deferred)
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki (+ Claude, /loop 常設承認のうえ実行)

## Context

The Indeed-stack now has both halves live as governed actors:
`cloud-itonami-isic-6399` (Meta Job Search — posting aggregation/
publication, public board) and `cloud-itonami-isic-7810` (Community
Employment Agency — candidacy intake → match → place, private desk).
The obvious product question is the seam: a job seeker responds to a
posting on a 6399 board — how does that become a 7810 candidacy?

The tempting design is a direct integration (6399 calls 7810's intake,
or a shared store). That would quietly fuse two governance domains
with different risk profiles: 6399's public-content law
(職業安定法5条の4 的確表示 etc., data = postings) versus 7810's
placement law (紹介許可・差別禁止・就労資格, data = candidate PII).
A fused pipeline would let a failure or a permission model in one
domain leak into the other, and would put candidate PII adjacent to a
public publishing pipeline.

Fleet precedent already answers this: ADR-2607111000 (cloud-mamori)
established that a venture front "produces ready-to-submit referral
drafts toward [the governed actors] — it cannot invoke any of them
itself, only name which one a human takes the case to next."

## Decision

1. **The handoff is a human-carried referral, never a cross-actor
   invocation.** 6399 MAY (future work) grow a low-risk
   `:application/refer` op that drafts an application-referral record
   — posting-id + publication-number, the applicant's contact
   REFERENCE (an operator-held pointer, never PII payload in the
   public actor's store), the source board, and the applicant's own
   consent-to-refer flag. The draft is governed like any 6399 write
   (spec-basis for the posting's jurisdiction, posting must be live)
   but its commit only records the referral — a human agency operator
   carries it into 7810's `:candidacy/intake`, where 7810's OWN
   governor takes over (anti-discrimination, fee recompute,
   work-authorization).
2. **Each actor's ledger records its own half.** 6399's ledger shows
   referral-drafted; 7810's shows intake-committed with the referral's
   record-id in the intake patch — reconstructing the end-to-end story
   requires both ledgers, deliberately (no single store spans the
   PII boundary).
3. **No shared store, no shared governor, no API call between the
   two.** If a live integration is ever wanted, it must be a new ADR
   naming the transport AND the PII-minimization contract; this ADR's
   rule (referral drafts + human carry) is the default until then.
4. **Implementation is deferred.** Both repos' docs (7810
   operator-quickstart, 6399 business-model funnel) reference this
   rule; the `:application/refer` op is future work gated on an
   operator actually needing it.

## Consequences

- (+) The two governance domains stay independently auditable and
  independently licensable (a 6399 operator needs no 紹介許可; a 7810
  operator carries that licence and its liability).
- (+) Candidate PII never enters the public actor's store; the public
  actor's referral record holds a reference, not the person.
- (−) The handoff has human latency by design — the same trade
  ADR-2607111000 accepted ("referrals are hand-carried by a human
  today").
- (−) End-to-end analytics require joining two ledgers.

## References

- ADR-2607111000 (cloud-mamori referral pattern — the precedent this
  generalizes); ADR-2607121700 (6399 placement, scope boundary vs
  7810); ADR-2607122300 (sprint record).
- cloud-itonami/cloud-itonami-isic-6399, cloud-itonami-isic-7810
  (docs referencing this rule).
