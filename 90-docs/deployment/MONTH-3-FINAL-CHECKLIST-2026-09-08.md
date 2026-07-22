# Month 3 Pre-Flight Gate — Master Checklist (2026-07-21 to 2026-09-08)

**Target:** Pre-flight gate decision on 2026-09-08 16:00 JST (GO/NO-GO for 2026-09-11 canary start)

**Status:** All documentation prepared. Remaining work: team assignments finalization, fire-drill execution, pre-flight validation (2026-09-01 to 2026-09-08).

---

## Phase 1: Team Assignments (By 2026-08-25)

- [ ] **Canary Lead:** Assigned to Jun Kawasaki (murakumo-owner)
  - Co-lead: ops-lead (TBD name)
  - Status: ✓ Primary assigned
  - Backup: Platform Lead (if primary unavailable)
  
- [ ] **Traffic Controller:** Assigned to ops team lead
  - Primary: murakumo-ops-lead (TBD name)
  - Status: TBD
  - Backup: Senior ops engineer (TBD name)
  - Pre-flight: Test ramp script in staging by 2026-09-05

- [ ] **Monitoring Lead:** Assigned to metrics team lead
  - Primary: murakumo-metrics-lead (TBD name)
  - Status: TBD
  - Backup: Senior metrics engineer (TBD name)
  - Pre-flight: Verify SLO thresholds locked in monitoring by 2026-09-05

- [ ] **Escalation Lead:** Assigned to ops/platform coordinator
  - Primary: (TBD name)
  - Status: TBD (PRIORITY: assign by 2026-07-25)
  - Backup: ops-lead or platform-lead
  - Pre-flight: Verify alert routing (PagerDuty → Slack → email → phone) by 2026-09-05

- [ ] **On-Call Engineers (4 shifts):** Assign 4 engineers to shifts
  - Shift 1 (Tue 17:00 to Wed 09:00): (TBD name)
  - Shift 2 (Wed 17:00 to Thu 09:00): (TBD name)
  - Shift 3 (Thu 17:00 to Fri 09:00): (TBD name)
  - Shift 4 (Fri 09:00 to 22:00): (TBD name)
  - Status: TBD
  - Pre-flight: Cross-trained on alert triage + rollback by 2026-09-08

- [ ] **Team Availability Confirmation (by 2026-08-25)**
  - [ ] All 5 core roles confirm 24/7 availability 2026-09-11 to 2026-09-14
  - [ ] Backup roles confirm availability
  - [ ] Escalation contact info verified (phone, email, Slack)
  - [ ] Calendar holds placed (no PTO, no conflicts)
  
- [ ] **Record in manifest/fleet-db.edn**
  - [ ] Create fleet-db entries for all roles
  - [ ] Link to MONTH-3-TEAM-ASSIGNMENTS.edn
  - [ ] Commit to main branch by 2026-08-25

---

## Phase 2: Fire-Drill Execution (2026-09-08 14:00-16:00 JST)

**Schedule:** 2 hours before pre-flight gate meeting

### Scenario 1: Latency Regression Rollback (14:05-14:15)
- [ ] Monitoring-lead simulates p99 latency spike to 210ms
- [ ] Alert fires, automatic rollback triggered
- [ ] Traffic-controller executes rollback script
- [ ] Verify: traffic reverts from 75% → 50% within 2 min
- [ ] Verify: Rust pool connection count increases, cljc decreases
- [ ] **Pass Criteria:** Rollback executes without error, < 2 min

### Scenario 2: Parity Divergence Full Rollback (14:20-14:30)
- [ ] Monitoring-lead simulates parity failure 1.5% (> 1% threshold)
- [ ] Alert fires, FULL rollback triggered
- [ ] Traffic-controller executes full rollback (100% → Rust)
- [ ] Verify: Rust pool absorbs 100% traffic within 2 min
- [ ] Escalation-lead logs decision in Slack
- [ ] **Pass Criteria:** Full rollback completes < 2 min

### Scenario 3: Graceful Rust Drain Procedure (14:35-14:50)
- [ ] Pre-stage staging cljc at 99%, Rust at 1%
- [ ] Traffic-controller: stop new connections on Rust pool
- [ ] Wait: monitor in-flight requests (60s grace period)
- [ ] Monitoring-lead: verify all sessions migrated to cljc
- [ ] Traffic-controller: deactivate Rust pool (STANDBY)
- [ ] Verify: Rust infrastructure alive (emergency restore readiness)
- [ ] **Pass Criteria:** Drain completes < 10 min, sessions 100% migrated

### Fire-Drill Debrief (14:50-15:00)
- [ ] Each role reports confidence level (1-5 scale)
- [ ] Identify any concerns or script fixes needed
- [ ] Team sign-off: "Fire drill PASS, ready for canary"
- [ ] **Pass Criteria:** All team members ≥ 4/5 confidence

---

## Phase 3: Pre-Flight Checklist Validation (2026-09-01 to 2026-09-08)

### Weekly Checkpoint: 2026-09-01 (Monday, -7 days from gate)
- [ ] Verify parity divergence rate < 0.01% (not increasing)
- [ ] Verify latency p99 ≤ 100ms (no regression)
- [ ] Verify 5xx rate ≤ 0.01% (no reliability issues)
- [ ] Verify memory ≤ 256MB per-instance (no leak signal)
- [ ] Verify audit trail complete and rotated to B2
- [ ] Confirm team assignments final
- [ ] Confirm fire-drill schedule locked (2026-09-08 14:00-16:00)
- [ ] **Action:** If any item concerning → escalate to owner immediately

### Pre-Gate Preparation: 2026-09-05 (Friday, -3 days)
- [ ] **Traffic-Controller:** Dry-run ramp script in staging (50%→60%→75%)
  - [ ] Confirm load balancer rules apply cleanly
  - [ ] Confirm traffic split verified via dashboard
  - [ ] Confirm no stuck connections
  
- [ ] **Metrics-Lead:** Verify SLO thresholds locked in monitoring
  - [ ] p99 budget: 100ms (locked)
  - [ ] parity threshold: 1% (locked)
  - [ ] 5xx threshold: 1% (locked)
  - [ ] memory threshold: 512MB (locked)
  - [ ] No edits allowed after this date
  
- [ ] **Escalation-Lead:** Verify alert routing
  - [ ] Send test alert 2026-09-05 14:00
  - [ ] Measure time-to-ack (target < 5 min)
  - [ ] Verify: PagerDuty → Slack → email → phone routing
  - [ ] Document results
  
- [ ] **Murakumo-Owner:** Confirm Rust fleet readiness
  - [ ] Staging: both Rust + cljc available
  - [ ] Production: Rust at 50% traffic (Phase 2 stable)
  - [ ] Rust graceful drain capability tested in staging
  
- [ ] **Escalation-Lead:** Publish GO-NO-GO decision template
  - [ ] MONTH-3-GO-NO-GO-DECISION-TEMPLATE.edn ready
  - [ ] Template located in /90-docs/deployment/

### Gate Day Morning: 2026-09-08 (Friday, morning)
- [ ] Final metric checks (no overnight regression)
- [ ] Confirm fire-drill attendees ready and briefed
- [ ] Confirm gate meeting attendees available (Platform Lead, owner, ops-lead, metrics-lead, escalation-lead)
- [ ] Verify network connectivity to monitoring systems

### 2026-09-08 Pre-Flight Checklist Items (16:00 Gate)

**Item 1: Parity Validation**
- [ ] Parity divergence < 0.01% over 7-day sentinel
- [ ] Evidence: Prometheus murakumo/parity-checker metrics
- [ ] Status: PASS / FAIL

**Item 2: Latency & Performance SLO**
- [ ] p99 ≤ 100ms (50% traffic, stable)
- [ ] p95 ≤ 95ms
- [ ] p50 ≤ 70ms
- [ ] No GC pause outliers > 30ms
- [ ] Evidence: Grafana murakumo-mesh-latency dashboard
- [ ] Status: PASS / FAIL

**Item 3: Reliability & Error Rates**
- [ ] HTTP 5xx rate ≤ 0.01% (< 1 per 10K requests)
- [ ] No panic / crash / SIGSEGV in JVM logs
- [ ] No connection resets > 0.01%
- [ ] Graceful load spike handling
- [ ] Alert noise < 2 per day
- [ ] Evidence: Prometheus murakumo/error-rate metric
- [ ] Status: PASS / FAIL

**Item 4: Memory & Resource Utilization**
- [ ] Per-instance heap ≤ 256MB post-GC (at 50% traffic)
- [ ] No memory leak over 7-day window
- [ ] GC frequency < 2 per minute
- [ ] No Out-of-Memory errors
- [ ] Evidence: JVM metrics + heap dump analysis
- [ ] Status: PASS / FAIL

**Item 5: Audit Trail & Compliance**
- [ ] All canary requests logged (Phase 1-3)
- [ ] Audit log rotated to Backblaze B2
- [ ] 90-day retention policy confirmed
- [ ] Parity assertion results logged
- [ ] Rollback trigger conditions logged
- [ ] Evidence: B2 audit-logs/ directory
- [ ] Status: PASS / FAIL

**Item 6: Team & Infrastructure Readiness**
- [ ] On-call team trained (runbook review complete)
- [ ] Fire-drill executed successfully (3/3 scenarios PASS)
- [ ] PagerDuty escalation policy tested
- [ ] Graceful shutdown tested in staging
- [ ] Rollback scripts deployed & tested
- [ ] Team confidence ≥ 4/5 (from fire-drill debrief)
- [ ] Evidence: Fire-drill log + team sign-off
- [ ] Status: PASS / FAIL

**Item 7: Go/No-Go Decision Gate**
- [ ] All 6 items above PASS
- [ ] No blocking issues
- [ ] Rollback procedure validated
- [ ] Team trained & on-call assigned
- [ ] Owner approval captured
- [ ] Evidence: This decision record (MONTH-3-GO-NO-GO-DECISION.edn)
- [ ] Status: PASS / FAIL

---

## Final Gate Decision: 2026-09-08 16:00-16:45 JST

**Decision Gate Meeting:**
- [ ] Attendees: Platform Lead, murakumo-owner, ops-lead, metrics-lead, escalation-lead
- [ ] Agenda: Review all 7 checklist items
- [ ] Duration: 45 min (combined with M5 Week 14 gate close)
- [ ] Decision Rule: All 7 items must PASS for GO decision
- [ ] Options: **GO** / **NO-GO** / **SLIP**

### If GO Decision:
- [ ] Create MONTH-3-GO-NO-GO-DECISION.edn (decision record)
- [ ] Post in Slack #prod-gates-wave5: "GO decision: canary starts 2026-09-11 09:00 JST"
- [ ] Escalation-lead: notify team (email + Slack)
- [ ] Canary-lead: send pre-canary team briefing (2026-09-10)
- [ ] Traffic-controller: finalize ramp script deployment
- [ ] Metrics-lead: activate dashboards, lock SLO thresholds
- [ ] On-call: confirm overnight shifts assigned
- [ ] **Next Event:** 2026-09-11 09:00 JST — Canary Stage 1 execution begins

### If NO-GO Decision:
- [ ] Create MONTH-3-GO-NO-GO-DECISION.edn (decision record, document failure reason)
- [ ] Post in Slack: "NO-GO decision: [reason]. Canary delayed to 2026-09-18."
- [ ] Schedule root cause analysis meeting (2026-09-09)
- [ ] Identify 1-week mitigation plan
- [ ] Retest on 2026-09-15
- [ ] Retry pre-flight gate on 2026-09-18
- [ ] **Next Event:** 2026-09-18 16:00 JST — Retry pre-flight gate (or escalate if issue unresolved)

### If SLIP Decision:
- [ ] Create MONTH-3-GO-NO-GO-DECISION.edn (document reason for slip)
- [ ] Post in Slack: "SLIP decision: canary delayed to 2026-09-18"
- [ ] Identify specific additional work needed to gain confidence
- [ ] Owner approval required for slip
- [ ] **Next Event:** 2026-09-18 (revised pre-flight gate)

---

## Post-Gate Handoff (If GO)

**2026-09-08 17:00 JST — Execution Readiness Handoff**
- [ ] Decision record created (MONTH-3-GO-NO-GO-DECISION.edn)
- [ ] Team notification sent (Slack + email)
- [ ] On-call rotation confirmed (PagerDuty, Google Calendar)
- [ ] Monitoring dashboards activated
- [ ] Alert rules verified (5 automatic triggers ready)
- [ ] Ramp scripts deployed (load balancer ready)
- [ ] Graceful drain procedure ready (staging tested)

**2026-09-11 09:00 JST — Canary Execution Start**
- [ ] Stage 1 begins (50% → 75% traffic ramp)
- [ ] All team roles standby (canary lead, traffic controller, monitoring lead, on-call)
- [ ] Real-time SLO monitoring dashboard live
- [ ] PagerDuty alerts armed
- [ ] First stage gate: 2026-09-12 09:00 JST (Stage 1 → Stage 2 decision)

---

## Documentation Status

**Core Documents (All Ready):**
1. ✓ MONTH-3-PRE-FLIGHT-CHECKLIST.edn (7-item validation checklist)
2. ✓ MONTH-3-CANARY-EXECUTION-TIMELINE.edn (4-stage execution plan)
3. ✓ MONTH-3-TEAM-ASSIGNMENTS.edn (role assignments, on-call rotation)
4. ✓ MONTH-3-AUTOMATIC-ROLLBACK-TRIGGERS.edn (4 automatic triggers)
5. ✓ MONTH-3-MANUAL-ROLLBACK-DECISION-TREE.edn (decision scenarios)
6. ✓ MONTH-3-GRACEFUL-RUST-DRAIN.edn (drain procedure)
7. ✓ MONTH-3-POST-CUTOVER-VALIDATION.edn (48h validation checklist)
8. ✓ MONTH-3-EXECUTION-COORDINATION-SUMMARY.edn (master summary)

**Supporting Documents:**
- ✓ MONTH-3-PRE-FLIGHT-READINESS-PLAN.edn (this pre-flight prep guide)
- ✓ MONTH-3-GO-NO-GO-DECISION-TEMPLATE.edn (template to fill after gate)
- ✓ MONTH-3-FINAL-CHECKLIST-2026-09-08.md (this checklist)

**To Be Created During Canary:**
- MONTH-3-GO-NO-GO-DECISION.edn (after gate decision)
- Stage gate decision logs (Slack threads)
- Metrics snapshots (Grafana archived)
- Post-cutover results (MONTH-3-CUTOVER-COMPLETE.edn)

---

## Success Criteria Summary

| Phase | Success Criteria | Due Date | Owner | Status |
|-------|------------------|----------|-------|--------|
| Phase 1 | All 5 core roles assigned + backups confirmed + availability locked | 2026-08-25 | Platform Lead | TBD |
| Phase 2 | Fire-drill 3/3 scenarios PASS + team confidence ≥ 4/5 | 2026-09-08 14:50 | Canary Lead | TBD |
| Phase 3 | All 7 pre-flight checklist items PASS by gate | 2026-09-08 16:00 | Platform Lead | TBD |
| **Overall** | **GO decision at pre-flight gate** | **2026-09-08 16:45** | **Platform Lead** | **TBD** |

---

## Key Dates & Deadlines

| Date | Event | Time | Owner | Status |
|------|-------|------|-------|--------|
| 2026-07-21 | Pre-flight readiness plan published | — | Claude Code | ✓ Done |
| 2026-07-25 | Escalation Lead assigned (TBD priority) | — | Platform Lead | ⏳ Pending |
| 2026-08-15 | Core role assignments finalized | — | Platform Lead | ⏳ Pending |
| 2026-08-25 | Team availability confirmed + locked | — | Escalation Lead | ⏳ Pending |
| 2026-09-01 | Weekly pre-flight readiness checkpoint (-7 days) | 09:00 | Platform Lead | ⏳ Pending |
| 2026-09-05 | Dry-run ramp script, verify alerts, lock SLO thresholds | — | Traffic Controller / Metrics Lead | ⏳ Pending |
| 2026-09-08 14:00 | Fire-drill execution (3 scenarios) | 14:00-16:00 | Canary Lead | ⏳ Pending |
| 2026-09-08 15:30 | Pre-gate team briefing | 15:30-16:00 | Escalation Lead | ⏳ Pending |
| **2026-09-08 16:00** | **PRE-FLIGHT GATE DECISION** | **16:00-16:45** | **Platform Lead** | **→ GO/NO-GO/SLIP** |
| 2026-09-08 17:00 | Decision recorded, team notified | — | Escalation Lead | ⏳ Pending |
| **2026-09-11 09:00** | **CANARY EXECUTION START (if GO)** | **09:00 JST** | **Canary Lead** | **→ Stage 1** |
| 2026-09-14 22:00 | Cutover complete (100% cljc traffic) | — | Canary Lead | ⏳ Pending |
| 2026-09-15 14:00 | Post-cutover final decision (COMPLETE / CAVEATS / ABORT) | — | Canary Lead | ⏳ Pending |
| 2026-09-20 16:15 | Rust decommission (if validation PASS) | — | Ops Lead | ⏳ Pending |

---

## Escalation Contacts

**Primary Channels:**
- Slack: `#prod-gates-wave5` (all team members)
- Email: `murakumo-owner@gftd.group`, `platform-lead@gftd.group`
- PagerDuty: `prod-gates-wave5` alert policy (escalate if no ack > 5 min)

**If Blocking Issue Found:**
1. Alert escalation-lead immediately (Slack)
2. Escalation-lead notifies canary-lead
3. Canary-lead (owner) decides: investigate / defer / NO-GO
4. Document in Slack thread with decision rationale

---

## Prepared By

**Document:** MONTH-3-FINAL-CHECKLIST-2026-09-08.md  
**Author:** Claude Code Agent (Haiku 4.5)  
**Date:** 2026-07-21  
**Purpose:** Master checklist for Month 3 pre-flight gate preparation and execution  
**Status:** ACTIVE — Use this checklist daily from 2026-07-21 through 2026-09-08 gate

---

## How to Use This Checklist

1. **Phase 1 (2026-07-21 to 2026-08-25):** Focus on team assignments
   - Assign all TBD roles by 2026-08-15
   - Confirm availability by 2026-08-25
   - Record in manifest/fleet-db.edn

2. **Phase 2 (2026-09-08 14:00-16:00):** Execute fire-drill
   - Run 3 scenarios in sequence
   - Debrief at end
   - Confirm team confidence ≥ 4/5

3. **Phase 3 (2026-09-01 to 2026-09-08):** Validate pre-flight checklist
   - Weekly checkpoint 2026-09-01
   - Pre-gate prep 2026-09-05
   - Gate day 2026-09-08

4. **Gate Meeting (2026-09-08 16:00-16:45):** Make GO/NO-GO decision
   - Review all 7 items
   - Decide: GO / NO-GO / SLIP
   - Create decision record

5. **Post-Gate (if GO):** Hand off to execution team
   - Notify team
   - Activate monitoring
   - Ready for 2026-09-11 canary start

---

**Last Updated:** 2026-07-21  
**Next Review:** 2026-09-01 (weekly checkpoint)
