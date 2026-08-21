# Month 3 Pre-Flight Preparation — kotoba-server Fleet Migration

**Status**: ✅ READY FOR EXECUTION  
**Prepared**: 2026-07-21  
**Pre-Flight Gate**: 2026-09-08 (M5 Week 14)  
**Target Canary Start**: 2026-09-11 (if GO decision)  
**Target Cutover**: 2026-09-14

---

## Overview

This directory contains the complete pre-flight preparation for Month 3 of the **Strangler-Fig Fleet Migration** (kotoba-server Rust → cljc mesh nodes). The migration strategy is defined in **ADR-2607072100** and spans three months:

- **Month 1** (2026-07-21 to 2026-08-18): Staging & Parallel-Run (5% canary traffic)
- **Month 2** (2026-08-18 to 2026-09-08): Parity Validation & Traffic Ramp (5% → 50%)
- **Month 3** (2026-09-08 to 2026-09-14): **Cutover & Stabilization** ← **YOU ARE HERE**

Month 3 consists of:
1. **Pre-Flight Validation** (2026-09-01 to 2026-09-08): 7-day sentinel + final checklist
2. **Pre-Flight Gate** (2026-09-08 16:00 JST): GO/NO-GO decision
3. **Canary Execution** (2026-09-11 to 2026-09-14): Stage 1–3 traffic migration
4. **Post-Cutover** (2026-09-14+): 24–48 hour monitoring

---

## Documents in This Directory

### 1. MONTH-3-PRE-FLIGHT-CHECKLIST.edn

**Purpose**: Define the 7-item pre-flight validation checklist due 2026-09-08.

**Contents**:
- Parity validation (< 0.01% divergence)
- Latency & performance SLO (p99 ≤ 100ms)
- Reliability & error rates (5xx ≤ 0.01%)
- Memory utilization (stable, no leak)
- Audit trail (complete request/response logging)
- Team readiness (on-call trained + fire drill passed)
- GO/NO-GO decision gate

**Use This For**:
- ✅ Final pre-flight validation (Week 1 of Month 3)
- ✅ Gate committee review (2026-09-08 16:00 JST)
- ✅ Escalation thresholds (if items trending FAIL)
- ✅ Team sign-off (tracking completion)

**Key Dates**:
- Due: 2026-09-08 (7 items must PASS)
- Escalation: If any item at risk, notify by 2026-09-07 16:00 JST
- Decision: 2026-09-08 16:00 JST (M5 gate + pre-flight combined meeting)

---

### 2. MONTH-3-CANARY-DEPLOYMENT-PLAN.md

**Purpose**: Define the complete traffic migration strategy from 50% to 100% cljc fleet.

**Contents**:
- 3-stage deployment timeline (Stage 1: 50%→75%, Stage 2: 75%→90%, Stage 3: 90%→100%)
- SLO gates & thresholds (latency, parity, reliability, memory)
- Automatic rollback triggers (with implementation code)
- Manual rollback triggers (owner decision scenarios)
- Monitoring dashboard metrics (Grafana setup)
- Execution checklists per stage

**Use This For**:
- ✅ Ops team during canary execution (2026-09-11 to 2026-09-14)
- ✅ Understanding SLO thresholds (what triggers rollback)
- ✅ Monitoring setup (which metrics to watch)
- ✅ Fire drill planning (test automatic rollback procedures)

**Key Dates**:
- Stage 1: 2026-09-11 09:00 JST (4+ hours, 50%→75%)
- Stage 2: 2026-09-12 09:00 JST (4+ hours, 75%→90%)
- Stage 3: 2026-09-13 09:00 JST (6+ hours, 90%→99%→100%)
- Cutover Complete: 2026-09-14 09:00 JST

---

### 3. MONTH-3-ROLLBACK-PROCEDURE.md

**Purpose**: Define emergency rollback procedures (both automatic & manual).

**Contents**:
- Automatic rollback triggers (latency regression, parity divergence, error spike, memory exhaustion)
- Manual rollback triggers (owner decision, business priority, team readiness)
- Rollback procedures per stage (how to revert traffic from cljc back to Rust)
- Graceful drain cancellation (if Rust shutdown is interrupted)
- Post-rollback analysis (incident report template)
- Fire drill procedures (test rollback execution before 2026-09-08)

**Use This For**:
- ✅ Emergency response (if automatic rollback triggered during canary)
- ✅ Fire drill execution (2026-09-08 validation)
- ✅ Owner decision documentation (manual rollback scenarios)
- ✅ Post-incident review (root cause analysis)

**Key Procedures**:
- Auto-rollback latency regression: p99 > 100ms + 100ms for > 5 min
- Auto-rollback parity divergence: divergence > 1% for > 1 min
- Auto-rollback reliability: 5xx > 1% for > 2 min
- Manual rollback: Owner approval required (app fix, business priority, team gap)

---

### 4. MONTH-3-TIMELINE-INTEGRATION.md

**Purpose**: Coordinate Month 3 pre-flight with Wave 5 M5–M6 production gates (overlapping timelines).

**Contents**:
- Week-by-week timeline (Weeks 11–16, 2026-08-18 to 2026-09-29)
- Overlap risk analysis (Week 14 pre-flight + M5 close gate both 2026-09-08)
- Combined gate meeting strategy (single 45-min meeting for both decisions)
- Contingency plans (slip timelines if blockers found)
- Resource availability matrix (shared roles, conflict resolution)
- Communication plan (Slack notifications per checkpoint)

**Use This For**:
- ✅ Coordinating with M5–M6 gates leadership
- ✅ Resource scheduling (ensuring both initiatives don't conflict)
- ✅ Contingency planning (what if pre-flight delayed?)
- ✅ Stakeholder communication (Slack updates, checkpoint reviews)

**Key Coordination Points**:
- Week 11 (2026-08-18): Gates kickoff + Month 3 Phase 2 ongoing
- Week 12 (2026-08-25): M5 checkpoint + sentinel window starts
- Week 13 (2026-09-01): M5 checkpoint + final pre-flight prep
- **Week 14 (2026-09-08): COMBINED GATE MEETING (M5 close + pre-flight)**
- Week 14+ (2026-09-11): Canary execution + M6 phase startup

---

### 5. MONTH-3-PRE-FLIGHT-README.md (this file)

**Purpose**: Index and overview of all Month 3 pre-flight documents.

---

## Pre-Flight Execution Timeline

### Phase 1: Preparation (2026-07-21 to 2026-09-01)

| Week | Date | Activity | Owner | Status |
|------|------|----------|-------|--------|
| Pre | 2026-07-21 | Finalize pre-flight docs (4 files) | Claude Code Agent | ✅ DONE |
| Pre | 2026-07-25 | Internal review + feedback | murawko-owner | Pending |
| 10 | 2026-08-18 | Kickoff coordination with M5 gates | escalation-lead | Pending |
| 12 | 2026-08-25 | Resource availability confirmed | ops-lead | Pending |

### Phase 2: Validation Window (2026-09-01 to 2026-09-08)

| Week | Date | Activity | Owner | Status |
|------|------|----------|-------|--------|
| 13 | 2026-09-01 | 7-day sentinel begins (50% traffic, all metrics) | murawko-metrics-lead | Pending |
| 13 | 2026-09-01 | Final pre-flight checklist review starts | metrics-lead | Pending |
| 13 | 2026-09-06 | Fire drill: simulate latency regression | ops-lead | Pending |
| 13 | 2026-09-06 | Fire drill: simulate parity divergence | ops-lead | Pending |
| 14 | 2026-09-08 | Final checklist items due (7 items) | All | Pending |
| 14 | 2026-09-08 16:00 JST | **COMBINED GATE MEETING** (45 min) | platform-lead + murawko-owner | Pending |

### Phase 3: Canary Execution (2026-09-11 to 2026-09-14)

| Date | Activity | Owner | Status |
|------|----------|-------|--------|
| 2026-09-11 09:00 | Stage 1: 50%→75% traffic ramp | murawko-ops-lead | Pending (if GO) |
| 2026-09-12 09:00 | Stage 2: 75%→90% traffic ramp | murawko-ops-lead | Pending (if GO) |
| 2026-09-13 09:00 | Stage 3: 90%→99%→100% cutover | murawko-ops-lead | Pending (if GO) |
| 2026-09-13 16:00 | Graceful Rust drain + final cutover | murawko-ops-lead | Pending (if GO) |
| 2026-09-14 09:00 | Cutover complete, post-monitoring | murawko-owner | Pending (if GO) |

---

## Pre-Flight Checklist Summary

### The 7 Items (all due 2026-09-08)

1. **Parity Validation** (< 0.01% divergence)
   - Acceptance: Divergence count < 10 per 1M requests
   - Owner: murawko-metrics-lead
   - Trigger: If parity > 0.01%, pause and debug

2. **Latency SLO** (p99 ≤ 100ms)
   - Acceptance: p99 sustained at or below 100ms
   - Owner: murawko-performance-lead
   - Trigger: If p99 > 100ms sustained > 5 min, automatic rollback

3. **Reliability SLO** (5xx ≤ 0.01%)
   - Acceptance: HTTP 5xx rate < 1 per 10K requests
   - Owner: murawko-ops-lead
   - Trigger: If 5xx > 0.01% sustained > 2 min, automatic rollback

4. **Memory Utilization** (stable, no leak)
   - Acceptance: Per-instance heap peak ≤ 256MB post-GC
   - Owner: murawko-performance-lead
   - Trigger: If memory > 512MB, automatic rollback

5. **Audit Trail** (complete logging)
   - Acceptance: All canary requests logged to B2, no data loss
   - Owner: murawko-audit-lead
   - Trigger: If audit log incomplete, cannot proceed with traffic migration

6. **Team Readiness** (on-call trained, fire drill passed)
   - Acceptance: Runbook reviewed, fire drills passed (2), escalation ready
   - Owner: murawko-ops-lead
   - Trigger: If fire drill fails, delay canary start

7. **GO/NO-GO Decision** (platform-lead + owner consensus)
   - Acceptance: All 6 items above PASS, owner approval documented
   - Owner: platform-lead
   - Decision: GO (canary starts 2026-09-11) or NO-GO (slip to 2026-09-15+)

---

## Gate Committee

### Pre-Flight Gate Decision Makers (2026-09-08 16:00 JST)

| Role | Name | Responsibility | Contact |
|------|------|---|---|
| **Platform Lead** | [TBD] | Final GO/NO-GO decision | platform-lead@gftd.group |
| **murawko Owner** | [TBD] | Technical approval (parity, performance, reliability) | murawko-owner@gftd.group |
| **Ops Lead** | [TBD] | Infrastructure readiness, on-call readiness | ops-lead@gftd.group |
| **Metrics Lead** | [TBD] | SLO verification (latency, error rates, parity) | metrics-lead@gftd.group |
| **Escalation Lead** | [TBD] | Gate process, decision recording | escalation-lead@gftd.group |

---

## Critical Paths & Dependencies

### Dependency: Metrics Pipeline

**Must Be Ready By**: 2026-09-08

- Prometheus collecting all 5 core metrics
- Grafana dashboard rendering (refresh < 30s)
- Alert routing working (Slack, PagerDuty, email)
- CI gate-bot executing successfully

**If Metrics Pipeline Fails**: Pre-flight cannot validate SLOs → pre-flight delayed 1 week

### Dependency: On-Call Team

**Must Be Ready By**: 2026-09-08

- On-call engineer trained on runbook
- Fire drill (auto-rollback simulation) passed
- Escalation contacts confirmed
- PagerDuty on-call rotation assigned

**If On-Call Team Gap**: Canary start delayed 1–7 days (depending on staffing gap)

### Dependency: Wave 5 M5 Gates

**Must Be Ready By**: 2026-09-08

- M5 phase completion approved
- Metrics pipeline deployed and working
- No critical blockers in M5 execution

**If M5 Blocker Found**: May impact Month 3 timeline (shared resources)

---

## Escalation Protocol

### If Any Pre-Flight Item at Risk

**Notification Path**:
1. **Day of Detection** (e.g., 2026-09-05): Owner notifies metrics-lead + murawko-owner
2. **Slack Alert**: `#prod-gates-wave5` (severity: YELLOW - at risk)
3. **Response SLA**: 2 hours (root cause analysis + mitigation plan)
4. **Escalation Lead**: Tracks status, reports to platform-lead if needed

### If Pre-Flight Item FAILED on 2026-09-08

**Notification Path**:
1. **Morning of 2026-09-08**: Owner reports FAIL to metrics-lead + murawko-owner
2. **Slack Alert**: `#prod-gates-wave5` (severity: RED - FAILED)
3. **Emergency Response**: Escalation lead convenes immediate triage (30 min)
4. **Decision**: Go/no-go call by 2026-09-08 17:00 JST

---

## Success Criteria

### Pre-Flight Success (2026-09-08 16:00 JST)

- ✅ All 7 checklist items PASS
- ✅ No blocker issues remain
- ✅ GO decision approved by platform-lead + murawko-owner
- ✅ Team ready for canary execution
- ✅ Rollback procedures fire-drilled and confirmed working
- ✅ Monitoring dashboard live and refreshing

### Canary Success (2026-09-14)

- ✅ Stage 1 (50%→75%) completed without auto-rollback
- ✅ Stage 2 (75%→90%) completed without auto-rollback
- ✅ Stage 3 (90%→100%) completed with graceful Rust drain
- ✅ Cutover complete: 100% traffic on cljc fleet
- ✅ All SLOs maintained post-cutover
- ✅ No data loss during transition

### Post-Cutover Success (2026-09-14+)

- ✅ 24–48 hour monitoring confirms stability
- ✅ Metrics dashboard shows healthy cljc fleet performance
- ✅ Team confidence high (cutover approved)
- ✅ Documentation updated with lessons learned
- ✅ Rust fleet graceful shutdown confirmed

---

## Risk Mitigation Summary

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Parity divergence not detected early | MEDIUM | CRITICAL | Parity checker running continuously (< 2 min alert) |
| Latency regression during ramp | MEDIUM | HIGH | ZGC/Shenandoah tuning + automatic rollback trigger |
| GC pause spike > 100ms | MEDIUM | HIGH | Pre-flight validation includes 7-day stability window |
| Memory leak in cljc fleet | LOW | HIGH | Heap profile monitoring + pre-flight validation |
| Rust graceful drain hangs | LOW | MEDIUM | 30-sec timeout + manual force-shutdown option |
| Resource conflict (M5 + Month 3) | LOW | MEDIUM | Combined gate meeting (single 45-min slot) |
| Pre-flight checklist item FAILED | MEDIUM | MEDIUM | 1-week slip (retry 2026-09-15) or abort |
| Canary auto-rollback triggered | MEDIUM | MEDIUM | Incident report + recovery plan, deferred if needed |

---

## Document Status & Revision History

### Version 1.0 (2026-07-21) — Claude Code Agent

| Document | Status | Revision |
|----------|--------|----------|
| MONTH-3-PRE-FLIGHT-CHECKLIST.edn | ✅ ACTIVE | 1.0 |
| MONTH-3-CANARY-DEPLOYMENT-PLAN.md | ✅ ACTIVE | 1.0 |
| MONTH-3-ROLLBACK-PROCEDURE.md | ✅ ACTIVE | 1.0 |
| MONTH-3-TIMELINE-INTEGRATION.md | ✅ ACTIVE | 1.0 |
| MONTH-3-PRE-FLIGHT-README.md | ✅ ACTIVE | 1.0 |

### Pending (to be created after pre-flight gate)

| Document | Type | Timeline |
|----------|------|----------|
| MONTH-3-GO-NO-GO-DECISION.edn | Decision Record | 2026-09-08 17:00 JST |
| MONTH-3-CANARY-EXECUTION-REPORT.md | Execution Report | 2026-09-14 18:00 JST |
| MONTH-3-POST-INCIDENT-REVIEW.md | Analysis Report | 2026-09-15 (if rollback triggered) |

---

## Next Steps

### Immediate (2026-07-21 to 2026-07-25)

- [ ] Internal review of 4 pre-flight documents (murawko-owner)
- [ ] Feedback collection & revisions (if any)
- [ ] Finalize gate committee assignments
- [ ] Communicate pre-flight timeline to stakeholders

### Checkpoint: 2026-08-18 (Week 11, M5 Gate Kickoff)

- [ ] Confirm Month 3 Phase 2 progressing (50% traffic)
- [ ] Brief team leads on upcoming Month 3 pre-flight
- [ ] Confirm metrics pipeline deployment on schedule
- [ ] Assign on-call engineer + fire drill dates

### Checkpoint: 2026-08-25 (Week 12, M5 Gate Checkpoint)

- [ ] Sentinel window started (2026-08-26, 7-day window)
- [ ] Fire drill dates confirmed (2026-09-06)
- [ ] Resource availability confirmed for combined gate (2026-09-08)
- [ ] Pre-flight checklist validation begins

### Checkpoint: 2026-09-01 (Week 13, M5 Gate Checkpoint)

- [ ] Final 7 days of sentinel window (2026-09-01 to 2026-09-08)
- [ ] Pre-flight checklist items 1–6 validated (7 items due 2026-09-08)
- [ ] Fire drills completed (latency + parity scenarios)
- [ ] Confirm gate committee attendance (2026-09-08 16:00 JST)

### Gate Decision: 2026-09-08 (Week 14)

- [ ] **16:00 JST: Combined Gate Meeting** (M5 close + Month 3 pre-flight)
- [ ] **17:00 JST: GO/NO-GO Decision** recorded in `MONTH-3-GO-NO-GO-DECISION.edn`
- [ ] Canary start date confirmed (2026-09-11 if GO, 2026-09-18+ if NO-GO)

### Execution: 2026-09-11 to 2026-09-14 (if GO)

- [ ] Stage 1: 2026-09-11 09:00 JST (50%→75%, 4+ hours)
- [ ] Stage 2: 2026-09-12 09:00 JST (75%→90%, 4+ hours)
- [ ] Stage 3: 2026-09-13 09:00 JST (90%→100%, 6+ hours)
- [ ] Cutover Complete: 2026-09-14 09:00 JST (100% cljc live)

---

## Contact & Support

### For Questions About Pre-Flight Preparation

- **Pre-Flight Checklist**: metrics-lead@gftd.group
- **Canary Deployment Plan**: murawko-ops-lead@gftd.group
- **Rollback Procedures**: ops-lead@gftd.group
- **Timeline Coordination**: escalation-lead@gftd.group
- **Technical Approval**: murawko-owner@gftd.group
- **Final Decision**: platform-lead@gftd.group

### Slack Channel

- **Primary**: `#prod-gates-wave5` (all updates, decision records, incident reports)
- **Escalation**: PagerDuty `prod-gates` service (if critical alert)
- **Archive**: Google Drive / Confluence (if applicable)

---

## Related Documentation

- **ADR-2607072100**: Strangler-Fig strategy & month-by-month timeline (source of truth)
- **ADR-2607082400**: Component model wall & gossipsub findings (technical foundation)
- **ADR-2607071900**: murawko cross-node coordination (cljc control plane)
- **ADR-2607062330**: Kototama Chicory runtime (WASM guest execution)
- **wave-5-m5-m6-execution-kickoff.edn**: M5–M6 gates timeline & resource allocation

---

**Prepared by**: Claude Code Agent (Haiku 4.5)  
**Date**: 2026-07-21  
**Status**: ✅ READY FOR EXECUTION  
**Target Date**: All preparation complete for 2026-09-08 pre-flight gate
