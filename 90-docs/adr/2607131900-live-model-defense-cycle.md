# ADR-2607131900: live-model defense cycle — real-LLM verification and the HARD/SOFT division for free text

**Status**: accepted
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki (+ Claude, /loop 常設承認のうえ実行)

## Context

Third record in the flagship sprint series (after ADR-2607122300 and
ADR-2607131500). Iterations 19–29 moved from building surfaces to
hardening the actors against REAL models, and produced one
architecture rule worth pinning fleet-wide.

## What happened, in causal order

1. **Discovery**: the cockpit already carried an independently-built
   "Indeed-like" live lane (gftdcojp/cloud-itonami ADR-0022, in
   production at itonami.cloud/api/jobs) — built days apart from
   isic-6399 without either knowing. Recorded and positioned in
   cockpit ADR-0023 (complementary: sourcing-legality vs
   posting-content-legality); §3 (registry-fed /marketplace static
   surface) and §2's uncontroversial half (60-day currency gate,
   的確表示) were implemented and deployed; §2's discrimination screen
   stays owner-gated.
2. **Real-LLM verification** (standing authorization covers 生成モデル
   の実呼び出し): `dev/real_advisor_check.clj` ran isic-6399's actor
   with `llm-advisor` over the murakumo fleet's keyless Ollama nodes.
   Result: gemma4-class models do NOT reliably emit schema-conformant
   proposals, and every deviation degraded to the safe noop path while
   Store-ground-truth holds fired regardless — the degradation story
   proven live, not mocked. (Ops finding en route: benjamin's ollama
   install is broken.)
3. **The gap the live runs exposed**: hrllm/jobsearchopsllm/
   employmentopsllm all CLAIMED ":rationale — SCANNED by the
   fairness/spec-basis gate"; no governor read it. A live model can
   report clean structured cites/flags and still write 「女性なので…」
   in the free-text rationale a human approver reads.
4. **The rule that closed it** (differs per domain, deliberately):
   - talent/6310: check 7 `rationale-suspect` — protected-attribute
     keyword in an evaluative rationale → SOFT escalation (one human
     review), never HOLD (free text is too coarse for an unoverridable
     gate: 「性別を判断根拠にしていない」 matches 性別).
   - employmentops/7810: same check PORTED but SCOPED to match/place —
     assess rationales legitimately quote statute names
     (男女雇用機会均等法), pinned by a test.
   - jobsearchops/6399: NO scan — its assess rationales always quote
     statutes; docstring truthed to say what is actually scanned.
   - Plus `jobsearchops.screen`: a decision-support phrase screener
     for the operator's `:ad-content-discriminatory?` attestation,
     pinned as never-a-gate.
5. **Public surfacing**: both HR-side demos now run a deliberately
   biased advisor at page build (clean cites, biased rationale) and
   show escalate → 人間が却下 → HOLD with the ledger fact — the
   approval workflow's rejection branch is now publicly evidenced.
6. **Steady state observed**: nightly regeneration automations ran
   unattended two nights; registry drift (144→146 by concurrent
   promotions) was followed by dispatching the catalog workflow and
   regenerating+deploying the marketplace per its ADR discipline.

## The fleet rule (pinning)

**HARD gates read structured data (Store ground truth, :cites,
record flags); free text gets at most a SOFT escalation whose
false-positive cost is one human review — and where a domain's clean
free text legitimately contains the trigger vocabulary (statute
names), the scan is scoped out or omitted WITH the reason documented
and tested.** Three actors resolved this three different ways; the
divergence is the point — it is a domain judgment, not a copy-paste.

## Known-open (owner/ops)

ADR-0023 §2 discrimination screen for the live lane; 6310 real-data
seeding; benjamin ollama repair; west-pin-verify CI runner failures
(infra, pre-dates the sprint); cockpit shared checkout carries a
foreign unpushed commit (b269d02b).

## References

ADR-2607122300, ADR-2607131500; gftdcojp/cloud-itonami ADR-0022/0023;
isic-6399 dev/real_advisor_check.clj + jobsearchops.screen;
talent.policy check 7; employmentops.governor rationale-suspect.
