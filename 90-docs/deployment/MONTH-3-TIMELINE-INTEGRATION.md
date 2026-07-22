# Month 3 Timeline Integration — kotoba-server Fleet Migration & Wave 5 M5–M6 Gates Coordination

**Status**: ACTIVE  
**Prepared**: 2026-07-21  
**Timeline**: Weeks 11–16 (2026-08-18 to 2026-09-29)  
**Overlap Window**: Weeks 13–14 (2026-09-01 to 2026-09-15)

---

## Executive Summary

Month 3 pre-flight preparation (target: 2026-09-08) **directly overlaps** with Wave 5 M5–M6 production gates execution (2026-08-18 to 2026-09-29). This document defines the coordination strategy to ensure:

1. Both initiatives progress in parallel without conflict
2. Same gate committee oversees both decisions (2026-09-08 16:00 JST)
3. Resource availability is confirmed (metrics lead, ops lead, team leads)
4. Contingency plans address potential delays or blockers

---

## Timeline Overview: Weeks 11–16

### Week 11 (2026-08-18 to 2026-08-24)

**Concurrent Activities**:

| Activity | Owner | Status | Impact |
|----------|-------|--------|--------|
| **Wave 5 M5 Gate Kickoff** (Week 11) | All team-leads | SCHEDULED | No conflict |
| **Month 3 Phase 2 Execution** | murawko-metrics-lead | ONGOING | Continues from Month 2 |
| **Metrics Pipeline Go-Live** | metrics-lead | SCHEDULED | Traffic monitoring ready |
| **Team Onboarding** | escalation-lead | SCHEDULED | On-call training begins |

**Phase 2 Progress** (by end Week 11):
- cljc fleet: 50% traffic (from Phase 2 Week 4)
- Metrics: Baseline established (latency, parity, reliability)
- SLO: All targets on track

**Coordination**: None required (parallel execution).

---

### Week 12 (2026-08-25 to 2026-08-31)

**Concurrent Activities**:

| Activity | Owner | Status | Impact |
|----------|-------|--------|--------|
| **Wave 5 M5 Gate Checkpoint** (Week 12) | All team-leads | SCHEDULED | Business review point |
| **Month 3 Phase 2 Traffic Ramp** | murawko-ops-lead | ON-TRACK | 50% → 25%–30% (if needed) |
| **Pre-Flight Preparation Begins** | murawko-owner | STARTS | 1-week sentinel begins |

**Sentinel Window** (2026-08-26 to 2026-09-02):
- Duration: 7 days
- Objective: Validate parity, latency, reliability at 50% traffic
- SLO: p99 ≤ 100ms, parity < 0.01%, 5xx ≤ 0.01%

**Coordination Point**:
- Wave 5 M5 gate reviews overall platform health (metrics pipeline status)
- Month 3 sentinel runs in background (no interference)
- If M5 gate detects blocking issue → may affect Month 3 pre-flight timeline

---

### Week 13 (2026-09-01 to 2026-09-07)

**Critical Week: Overlap Peak**

| Activity | Owner | Status | Impact |
|----------|-------|--------|--------|
| **Wave 5 M5 Gate** (Week 13, 2026-09-01) | All team-leads | SCHEDULED | Mid-phase checkpoint |
| **Month 3 Pre-Flight Readiness Review** | murawko-owner | ACTIVE | Last 7 days of sentinel |
| **Pre-Flight Checklist Validation** | metrics-lead | PARALLEL | 7-item checklist review |
| **Fire Drill Execution** | ops-lead | SCHEDULED | Rollback procedure testing (2026-09-08) |

**Sentinel Status** (end of Week 13):
- 5+ days of 50% traffic monitoring complete
- Metrics dashboard green (all SLOs passing)
- Parity checker running continuously
- Pre-flight checklist items 1–6 ready for final sign-off

**Coordination Point**:
- Week 13 M5 gate (2026-09-01) may review Month 3 progress informally
- Formal pre-flight gate is Week 14 (2026-09-08), separate from M5 gate
- If M5 identifies critical issue → escalate to platform-lead (could delay pre-flight)

---

### Week 14 (2026-09-08 to 2026-09-14) — **CRITICAL ALIGNMENT WEEK**

**Both Gates Aligned on 2026-09-08 16:00 JST**

#### Pre-Flight Gate (2026-09-08, 16:00 JST)

**Decision**: GO ✓ / NO-GO ✗ for traffic migration (2026-09-11 start)

**Attendees** (Gate Committee):
- platform-lead (decision maker)
- murawko-owner (technical approval)
- ops-lead (infrastructure readiness)
- metrics-lead (SLO verification)
- escalation-lead (process oversight)

**Pre-Flight Checklist Items** (due 2026-09-08):
1. Parity validation (< 0.01%)
2. Latency SLO (p99 ≤ 100ms)
3. Reliability SLO (5xx ≤ 0.01%)
4. Memory utilization (stable, no leak)
5. Audit trail (request/response logged)
6. Team readiness (on-call trained)
7. GO/NO-GO decision

**Decision Options**:
- ✅ **GO**: Traffic migration starts 2026-09-11 09:00 JST (Stage 1)
- ❌ **NO-GO**: Extend pre-flight to 2026-09-15 or 2026-09-22 (slip timeline)

#### M5 Week 14 Gate (same date/time)

**Decision**: M5 phase complete, go to M6 phase

**Attendees**: Overlaps with pre-flight gate (same committee + team-leads)

**Coordination Strategy**: **Combined Gate Meeting (45 min)**

```
16:00–16:20 JST: Wave 5 M5 Close Review
  - M5 metrics pipeline status (Prometheus, Grafana, CI bot)
  - Team velocity & blockers summary
  - Gate readiness for M5 completion

16:20–16:45 JST: Month 3 Pre-Flight Review
  - Pre-flight checklist items 1–7 (SLO verification)
  - Canary deployment plan walkthrough
  - Rollback procedure fire drill results

16:45–17:00 JST: Joint GO/NO-GO Decision
  - M5 phase completion approval
  - Month 3 canary start approval (if GO)
  - Escalation contacts confirmed
```

**Outcome Recording**:
- File: `90-docs/deployment/MONTH-3-GO-NO-GO-DECISION.edn`
- File: `90-docs/gates/wave5-m5-close-decision.edn` (M5 gate outcome)
- Both decisions recorded by escalation-lead (single source of truth)

**Overlap Risk**: If M5 gate runs long or discovers blocking issue → may delay pre-flight decision. **Mitigation**: Pre-schedule 60 min, accept potential 15-min overrun.

---

### Week 14–15 Transition (2026-09-11 to 2026-09-15)

**If Pre-Flight GO**:

| Date | Activity | Owner | Impact |
|------|----------|-------|--------|
| 2026-09-11 | **Stage 1 Canary Start** (50% → 75%) | murawko-ops-lead | Canary execution begins |
| 2026-09-11 | **Wave 5 M6 Phase Kickoff** (planned) | All team-leads | M6 execution starts (no conflict) |
| 2026-09-12 | **Stage 2 Ramp** (75% → 90%) | murawko-ops-lead | Canary continues |
| 2026-09-12–13 | **Wave 5 Week 15 Checkpoint** (scheduled) | team-leads | May review canary progress informally |
| 2026-09-13 | **Stage 3 Cutover** (90% → 100%) | murawko-ops-lead | Final traffic migration |
| 2026-09-14 | **Cutover Complete** | murawko-owner | 100% cljc fleet live |
| 2026-09-15 | **Wave 5 M6 Gate** (2026-09-15) | All team-leads | M6 progress review (canary execution observed) |

**Coordination Point** (Week 15):
- Wave 5 M6 gate may review Month 3 canary progress as background context
- Canary execution should not impact M6 gate proceedings (parallel)
- If canary completes early (< 2026-09-15), confirm to M6 gate committee

---

### Week 15–16 (2026-09-15 to 2026-09-29)

**Post-Cutover Stabilization & Final M6 Gates**

| Date | Activity | Owner |
|------|----------|-------|
| 2026-09-14+ | **Post-Cutover Monitoring** (24–48h) | murawko-metrics-lead |
| 2026-09-15 | **Wave 5 M6 Gate** (Week 15) | All team-leads |
| 2026-09-22 | **Wave 5 M6 Final Gate** (Week 16) | All team-leads |
| 2026-09-29 | **Wave 5 Gates Complete** | escalation-lead (archive) |

**Coordination**: No direct overlap. Month 3 cutover complete by 2026-09-14, M6 gates proceed independently.

---

## Contingency Plans

### Scenario 1: Month 3 Pre-Flight Delayed (Slip to 2026-09-15)

**Trigger**: Pre-flight checklist item FAILED on 2026-09-08
- Example: Parity divergence > 0.01%, requires 1-week debugging

**Impact on Wave 5 Gates**:
- Week 14 M5 gate proceeds (no dependency on Month 3 decision)
- Month 3 canary start delayed 1 week (2026-09-18 instead of 2026-09-11)
- Canary completion slips to 2026-09-21 (late for Week 15 M6 gate review)

**Mitigation**:
1. Escalate debugging decision to platform-lead (by 2026-09-08 17:00 JST)
2. Allocate additional resources (murawko-owner full-time on investigation)
3. Communicate new pre-flight target (2026-09-15) to stakeholders
4. Wave 5 M6 gate (2026-09-15) proceeds without Month 3 update

**Recovery Timeline**:
- 2026-09-09 to 2026-09-14: Debugging + fix
- 2026-09-15: Second pre-flight attempt
- 2026-09-18: Canary rescheduled start
- 2026-09-21: Canary completion (tentative)

---

### Scenario 2: Wave 5 M5 Gate Blocked (Critical Blocker Found)

**Trigger**: M5 gate (2026-09-01 or 2026-09-08) discovers critical blocking issue
- Example: Prometheus storage failure, metrics pipeline down

**Impact on Month 3**:
- Pre-flight depends on metrics pipeline (parity checker, latency dashboard)
- If metrics pipeline down → pre-flight cannot proceed (SLO validation impossible)

**Mitigation**:
1. M5 gate escalation team fixes issue immediately (critical path)
2. If fix < 24 hours → pre-flight delayed < 1 day (acceptable)
3. If fix > 24 hours → pre-flight delayed, potential slip to 2026-09-15

**Coordination**:
- escalation-lead informs murawko-owner (via Slack) of blocker + ETA to fix
- If ETA > 2026-09-07 → pre-flight moved to 2026-09-15 (1-week slip)
- Canary start slips accordingly (2026-09-18)

---

### Scenario 3: Both M5 & Month 3 Need Same Resource at Same Time

**Trigger**: Metrics lead / ops-lead / platform-lead overcommitted (conflicting meetings/tasks)

**Example**:
- 2026-09-08 16:00: Both M5 gate AND pre-flight gate scheduled (same person)
- Person cannot attend both

**Mitigation**:
1. **Pre-schedule combined gate meeting** (45 min, single slot, both gates reviewed together)
   - See "Week 14 Timeline Integration" above
   - Both gate committees present simultaneously
   - Single decision moment for both initiatives

2. **Role delegation** (if person still unavailable):
   - Escalation lead can chair pre-flight gate (in platform-lead's absence)
   - Deputy metrics-lead can present SLO data
   - Deputy ops-lead can confirm team readiness

3. **Rescheduling as last resort**:
   - Pre-flight moved to 2026-09-15 (if 2026-09-08 conflict unresolvable)
   - Canary start slips to 2026-09-18

---

### Scenario 4: Canary Rolls Back During M6 Gate Week

**Trigger**: Auto-rollback triggered during Stage 1–3 (2026-09-11 to 2026-09-14)

**Example**: Parity divergence detected on 2026-09-12, automatic rollback to Phase 2

**Impact on Wave 5 M6 Gate** (2026-09-15):
- Wave 5 M6 gate may inquire about Month 3 progress
- Canary pause/rollback is visible in metrics dashboard
- Need to communicate incident + recovery plan

**Mitigation**:
1. **Incident report** filed by 2026-09-12 18:00 JST (root cause + fix plan)
2. **Wave 5 M6 gate notified** (informally) of rollback incident
   - Canary paused, debugging in progress
   - Expected to resume 2026-09-18 (after 1-week fix cycle)
   - No impact on M6 gate proceedings
3. **Escalation decision** (platform-lead + murawko-owner):
   - If fix quick (< 24h) → continue canary same week
   - If fix slow (> 3 days) → abort Month 3 entirely, reschedule to October

---

## Resource Availability Matrix

### Critical Resources (Shared Between M5/M6 Gates & Month 3)

| Role | M5 Gate Requirement | Month 3 Requirement | Conflict Window | Mitigation |
|------|-------------------|-------------------|---|---|
| **platform-lead** | Weekly gate chair | Pre-flight go/no-go decision | 2026-09-08 16:00 | Combined gate meeting (45 min) |
| **metrics-lead** | Metrics pipeline status | SLO verification (checklist item 2–3) | 2026-09-08 16:00 | Combined gate meeting |
| **ops-lead** | Team onboarding status | Team readiness (checklist item 6) | 2026-09-08 16:00 | Combined gate meeting |
| **escalation-lead** | Gate process oversight | Decision recording | 2026-09-08 16:00 | Combined gate meeting |
| **murawko-owner** | Support (as needed) | Pre-flight technical approval + canary execution | 2026-09-11 onward | Dedicated to canary (part-time M5 support) |
| **murawko-ops-lead** | Support (as needed) | Stage 1–3 traffic ramp + monitoring | 2026-09-11 onward | Full-time canary execution (available for M6 support post-cutover) |

**Conclusion**: All roles can participate in combined gate meeting (2026-09-08 16:00). No conflicts expected if combined meeting model followed.

---

## Decision Tree: Pre-Flight GO/NO-GO

### 2026-09-08 16:00 JST Gate Meeting

```
┌─ All 7 pre-flight checklist items PASS?
│  ├─ YES → Go to "GO Decision"
│  └─ NO → Go to "NO-GO Decision"
│
├─ GO Decision:
│  ├─ platform-lead approves traffic migration start
│  ├─ murawko-owner approves technical readiness
│  ├─ ops-lead confirms on-call team ready
│  └─ Decision: ✅ GO (2026-09-11 09:00 JST start)
│
└─ NO-GO Decision:
   ├─ Identify failing items (1–7)
   ├─ Determine root cause (infrastructure, application, team readiness)
   ├─ Estimate fix timeline (< 1 week vs > 1 week)
   └─ Decision options:
       ├─ Option A: Extend pre-flight 1 week (2026-09-15 retry)
       ├─ Option B: Abort Month 3 entirely, reschedule to October
       └─ Option C: Accept reduced SLO (if minor gap)
```

### Slip Thresholds

| Scenario | Pre-Flight Slip | Canary Start Slip |
|----------|---|---|
| **1 item FAIL (quick fix < 24h)** | No slip | No slip |
| **2 items FAIL (team + app fix)** | +3 days (to 2026-09-11) | +3 days (to 2026-09-14) |
| **3+ items FAIL (major issue)** | +7 days (to 2026-09-15) | +7 days (to 2026-09-18) |
| **Metrics pipeline down** | +7 days | +7 days |
| **On-call team unavailable** | +1 day (rescheduled) | +1 day |

---

## Coordination Checkpoints

### Checkpoint 1: 2026-08-18 (Week 11, M5 Gate Kickoff)

**Action Items**:
- [ ] Confirm Month 3 Phase 2 proceeding (50% traffic, SLOs on track)
- [ ] Confirm metrics pipeline scheduled go-live (2026-08-08 to 2026-08-10)
- [ ] Brief team leads on upcoming Month 3 pre-flight (2026-09-08)
- [ ] Slack announcement: `#prod-gates-wave5` notified of dual-gate alignment

---

### Checkpoint 2: 2026-08-25 (Week 12, M5 Gate Checkpoint)

**Action Items**:
- [ ] Confirm sentinel window started (2026-08-26, 7-day window)
- [ ] Metrics pipeline confirmed live and collecting data
- [ ] On-call team onboarding progressing (fire drill scheduled for 2026-09-08)
- [ ] Coordinate resource availability for combined gate meeting (2026-09-08)

---

### Checkpoint 3: 2026-09-01 (Week 13, M5 Gate Checkpoint)

**Action Items**:
- [ ] Sentinel window nearly complete (last 7 days)
- [ ] Pre-flight checklist items 1–6 ready for validation (by 2026-09-08)
- [ ] Confirm all gate committee members available for combined meeting (2026-09-08 16:00)
- [ ] Final go/no-go decision communication plan finalized

---

### Checkpoint 4: 2026-09-08 (Week 14, Combined Gate)

**Action Items**:
- [ ] Pre-flight gate: All 7 items validated, GO/NO-GO decided
- [ ] M5 gate: M5 phase complete, M6 phase approved
- [ ] Both decisions recorded (separate EDN files or combined EDN)
- [ ] Slack notification: `#prod-gates-wave5` announces outcomes + next steps

---

## Communication Plan

### Stakeholder Notifications

**Slack Channel**: `#prod-gates-wave5` (single source of truth for both initiatives)

#### Week 11 (2026-08-18): Kickoff
```
@channel Wave 5 M5–M6 gates KICKOFF + Month 3 pre-flight alignment
Timeline: 2026-08-18 (Week 11) → 2026-09-08 (Week 14) pre-flight gate
Resource Coordination: Combined gate meeting 2026-09-08 16:00 JST
Pre-flight Outcome: Impacts canary start date (2026-09-11 if GO)
Questions? Ask @escalation-lead
```

#### Week 12 (2026-08-25): Midpoint
```
Checkpoint: Wave 5 M5 gate + Month 3 sentinel window progressing
Metrics Pipeline: Live (2026-08-08 to 2026-08-10) ✓
On-Call Training: Fire drill scheduled 2026-09-08
Next: 2026-09-01 (Week 13) final pre-flight checklist review
```

#### Week 13 (2026-09-01): Final Prep
```
Last stretch! Month 3 pre-flight validation week.
Sentinel window: Last 7 days (2026-09-01 to 2026-09-08)
Checklist items: Final sign-off 2026-09-08
Combined gate meeting: 2026-09-08 16:00 JST (45 min, both M5 + pre-flight)
GO/NO-GO decision: Recorded in #prod-gates-wave5 thread
Canary start: 2026-09-11 09:00 JST (if GO) or 2026-09-18 (if slip)
```

#### Week 14 (2026-09-08): Decision Day
```
🎯 COMBINED GATE MEETING: 2026-09-08 16:00 JST (45 min)
Attendees: platform-lead, murawko-owner, ops-lead, metrics-lead, escalation-lead, team-leads
Agenda:
  (20 min) Wave 5 M5 close review + go/no-go
  (25 min) Month 3 pre-flight review + go/no-go
  (10 min) Joint decision recording + next steps
Outcome: Two separate decisions (M5 close ✓, Month 3 canary GO/NO-GO)
Slack thread: Recording both outcomes + escalation contacts
```

#### Week 14+ (2026-09-11–2026-09-14): Canary Execution
```
Stage 1 (2026-09-11): 50% → 75% (4+ hours)
Stage 2 (2026-09-12): 75% → 90% (4+ hours)
Stage 3 (2026-09-13): 90% → 100% (6+ hours)
Daily Reports: 18:00 JST metrics summary + next day gate decision
```

---

## Risk Summary

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Pre-flight delayed (slip 1 week) | MEDIUM | MEDIUM | Extend testing window, additional debugging resources |
| Resource conflict (same person at 2 gates) | LOW | MEDIUM | Combined gate meeting model, role delegation |
| Metrics pipeline failure blocks pre-flight | LOW | HIGH | M5 gate team prioritizes fix, escalation path clear |
| Canary auto-rollback during M6 gate week | MEDIUM | MEDIUM | Incident report + communication plan, deferred to Oct if needed |
| Team readiness gap discovered late | MEDIUM | MEDIUM | Fire drill (2026-09-08) validates readiness early |

---

## Success Criteria

### Month 3 Timeline Integration Success

✅ **Pre-Flight Gate** (2026-09-08):
- All 7 checklist items validated PASS
- GO decision approved by platform-lead + murawko-owner
- Canary start authorized for 2026-09-11

✅ **Wave 5 M5 Close Gate** (2026-09-08):
- M5 phase completion approved
- M6 phase startup authorized

✅ **Combined Gate Meeting** (2026-09-08 16:00 JST):
- Single 45-min meeting covers both initiatives
- Both decisions recorded (EDN files)
- Stakeholders notified on Slack

✅ **Canary Execution** (2026-09-11 to 2026-09-14):
- Stage 1–3 complete without catastrophic rollback
- Cutover successful by 2026-09-14
- No data loss during transition

✅ **Wave 5 M6 Gate Progress** (2026-09-15):
- M6 execution proceeds independently
- Month 3 canary completion noted as background context

---

## Appendix: Calendar Reference

### Key Dates

| Date | Event | Owner | Decision |
|------|-------|-------|----------|
| 2026-08-18 | Wave 5 M5 Gate Kickoff (Week 11) | All team-leads | M5 phase approval |
| 2026-08-25 | Wave 5 M5 Gate Checkpoint (Week 12) | team-leads | M5 progress review |
| 2026-08-26 | Month 3 Sentinel Window Starts | murawko-metrics-lead | 7-day baseline collection |
| 2026-09-01 | Wave 5 M5 Gate Checkpoint (Week 13) | team-leads | Mid-phase review |
| 2026-09-08 | **COMBINED GATE MEETING** (Week 14) | All + murawko-owner | M5 close ✓ + Month 3 GO/NO-GO |
| 2026-09-08 (if NO-GO) | Pre-Flight Slip to 2026-09-15 | platform-lead | 1-week debugging extension |
| 2026-09-11 | **Canary Stage 1 Start** (50%→75%) | murawko-ops-lead | Traffic migration begins |
| 2026-09-12 | Canary Stage 2 (75%→90%) | murawko-ops-lead | Ramp continues |
| 2026-09-13 | Canary Stage 3 (90%→100%) | murawko-ops-lead | Final cutover |
| 2026-09-14 | Canary Complete (100% cljc live) | murawko-owner | Cutover approved |
| 2026-09-15 | Wave 5 M6 Gate (Week 15) | team-leads | M6 progress + Month 3 context |
| 2026-09-22 | Wave 5 M6 Final Gate (Week 16) | team-leads | Final M6 review |
| 2026-09-29 | Wave 5 Gates Complete | escalation-lead | Archive + summary report |

---

**Prepared by**: Claude Code Agent  
**Date**: 2026-07-21  
**Status**: ✅ READY FOR EXECUTION  
**Review Date**: 2026-08-25 (confirm resource availability at Week 12 checkpoint)
