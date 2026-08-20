# Wave 5 M5–M6 Production Gates — Decision Rule & Escalation Summary

**Single-Page Reference (Print & Post)**

**Status:** Final, ready for kickoff 2026-08-18  
**Effective Date:** 2026-08-18 onwards  
**Audience:** Team leads, phase owners, platform lead (carry in pocket)

---

## Gate Decision Rule

### The Question

> "Does your team agree we are **Go** for this week's gate (all critical path complete, metrics healthy, blockers resolved or escalated)?"

### The Answer

- **✅ ALL team leads say YES:** Gate is **GO**
- **❌ ANY team lead says NO:** Gate is **NOT GO** (escalate blocker, don't escalate entire gate)
- **⏳ BLOCKED on decision:** Phase owner decides (2h SLA). Platform lead decides if multi-team (4h SLA).

### Decision Timeline

| When | Who | Decision | Deadline |
|------|-----|----------|----------|
| **Fri 16:00** | All team leads | Consensus check (Go/No-Go) | Same Friday 16:00 |
| **If blocked at Fri 15:30** | Phase owner | Escalate blocker, decide gate impact | Within 2h (by Fri 17:30) |
| **If still blocked at Fri 17:30** | Platform lead | Final gate-halt authority | Within 30 min (by Fri 18:00) |

### Outcomes

- **GO:** Continue to next week, all critical path items approved
- **NO-GO:** Hold week (retry mitigations), escalate root cause to phase owner, delay next week start by 24h
- **HOLD:** Proceed with caution (partial critical path), escalated risk accepted by phase owner

---

## Escalation Tree (3 Levels)

### Level 1: Team Lead (First Responder)

**Trigger:** Alert fires (build-times, coverage, blocker SLA breach, etc.)

**SLA:** 1 hour response time

**Responsibilities:**
- Triage: Real issue or false positive?
- Fix attempt: Can you resolve in ≤ 30 min locally?
- Update Jira blocker (status, ETA, owner contact)
- Notify team in Slack #prod-gates-wave5
- **Escalate to L2 if:** Unresolved after 1h OR decision needed immediately

### Level 2: Phase Owner (Escalation Authority)

**Trigger:** L1 escalation (blocker unresolved after 1h) OR multi-team impact suspected

**SLA:** 2 hours response time (from initial alert)

**Responsibilities:**
- Review team lead assessment
- Check cross-team impact (single vs. multi-team blocker?)
- Decide: retry / workaround / escalate to L3
- Authorize emergency mitigations (e.g., skip test, partial deploy)
- Update gate decision (Go/No-Go impact)
- **Escalate to L3 if:** Multi-team blocker OR gate-halt signal OR L2 cannot decide

### Level 3: Platform Lead (Final Authority)

**Trigger:** L2 escalation (multi-team or gate-halt signal) OR 4h SLA breach imminent

**SLA:** 4 hours response time (from initial alert)

**Responsibilities:**
- Final decision on gate halt or continuation
- Activate contingency plans if needed
- Communicate delay/risk to stakeholders
- Approve budget/resource exceptions (if applicable)
- Document decision in escalation ledger
- **Final Authority:** Only L3 can halt a gate

---

## Alert Types & Routing

| Alert Type | Severity | Primary Channel | SLA | Escalate If |
|------------|----------|-----------------|-----|-------------|
| Build-times p99 exceed 12 min | ⚠️ Warning | #prod-gates-wave5 + team lead DM | 4h response | Unresolved > 2h |
| Test coverage drops < 92% | ⚠️ Warning | #prod-gates-wave5 + team lead DM | 4h response | Coverage < 90% |
| Deployment success < 99.5% | 🔴 Critical | PagerDuty + #prod-gates-wave5 + phase owner | 2h response | Unresolved > 30 min |
| P1 blocker unresolved > 2h | 🔴 Critical | PagerDuty + escalation-lead DM | Immediate | Not escalated at 2h mark |
| Gate-halt signal (2+ teams blocked) | 🔴 CRITICAL | PagerDuty + all stakeholders | 15 min decision | Platform lead decides |

---

## SLA Clock & Response Timeline

### Alert Fires at Time T

```
T+0:00   Alert fires (Prometheus → Slack)
T+0:15   L1 (team lead) reads Slack + triages
T+0:30   L1 update: "Fixing locally" or "Need escalation"
T+1:00   L1 deadline: Must escalate if unresolved
         ↓
T+1:05   L2 (phase owner) reads escalation + assesses impact
T+1:30   L2 update: "Single-team workaround" or "Needs L3"
T+2:00   L2 deadline: Must escalate if multi-team or can't decide
         ↓
T+2:05   L3 (platform lead) reads escalation + makes decision
T+4:00   L3 deadline: Final decision (Go/No-Go/Hold) must be made

```

**Key Rule:** If you don't escalate at your level by your deadline, your escalation lead will escalate you (automatic).

---

## Escalation Channels

### Slack
- **Channel:** `#prod-gates-wave5`
- **Use for:** All alerts, triage, team leads posting status updates
- **Monitoring:** All team leads + metrics lead have notifications on "high priority"

### Email
- **Address:** `gates-escalation@gftd.group`
- **Use for:** P1 escalations (L1 → L2), L2 → L3 decisions
- **Recipients:** Team leads (auto-CC), phase owners, platform lead

### PagerDuty
- **Service:** `prod-gates`
- **Use for:** Critical alerts (deployment failure, P1 SLA breach, gate-halt signal)
- **Escalation Ladder:** Auto-escalates if not acknowledged within 15 min

---

## Decision Scenarios

### Scenario 1: Build Times Exceed 12 min (Wed 14:00)

```
14:00  Alert fires: build p99 = 15 min (target < 12 min)
       → #prod-gates-wave5: "[ALERT] Build times exceeded — Team: M5-Core — SLA: 4h deadline (18:00)"
       → Assigned to: team-lead-m5-core

14:15  Team Lead Response (L1):
       ├─ "Triage: Regression in auth-module compile. Fixing locally."
       └─ Jira: Status = In Progress, ETA 16:30

16:30  Update:
       └─ "Build times back to 11 min. Issue resolved."
           → Move Jira to Done
           → Slack: "✅ Resolved"

Friday 16:00 Gate Review:
       → Metrics snapshot shows brief spike Wed 14–16, returned to normal
       → Decision: "Go" (single-incident handled, metrics returned to baseline)
```

### Scenario 2: Coverage Drops < 92% (Wed 10:00) → P1 by SLA

```
10:00  Alert fires: coverage = 88% (target >= 92%)
       → #prod-gates-wave5: "[ALERT] Coverage < 92% — Team: M5-Core — SLA: 4h deadline (14:00)"
       → Assigned to: team-lead-m5-core

10:15  Team Lead Response (L1):
       ├─ "Triage: Auth-module tests failing (regression). Attempting fix."
       └─ Jira: Status = Blocked, blocked-by = auth-module test suite

11:30  Still Blocked (1.5h in, 2.5h remaining):
       └─ "Cannot identify root cause. Escalating."
           → Escalate to Phase Owner M5 (L2)
           → Jira: Status = Blocked, escalate-to = L2
           → Email: gates-escalation@gftd.group, @phase-owner-m5

12:00  Phase Owner Response (L2):
       ├─ "Assess impact: Single team (M5-Core) or multi-team?"
       ├─ "Impact: M5-Integration depends on auth-module, but has cached tests (can work around)"
       └─ Decision: "Single-team issue. M5-Core: fix locally. M5-Integration: proceed with caution (use mock)."
           → Jira: Status = Ready for Gate, acceptance = phase-owner-m5

13:30  Team Lead Feedback (L1):
       └─ "Cannot fix by Friday. Proposing: skip flaky auth test, merge with manual override."
           → Request phase owner approval for test-skip mitigation

14:00  Phase Owner Final Decision (L2):
       └─ "Approved: skip test for Friday gate (with manual override). P1: fix root cause by Monday."
           → Jira: Status = Gate Approved (with exception flag)

Friday 16:00 Gate Review:
       → Metrics show coverage exception (87% + manual override)
       → Decision: "Go with exception" (escalation-approved mitigation)
       → Next week critical path: "Fix auth-module test regression"
```

### Scenario 3: Multi-Team Blocker (Fri 15:00) → Gate Halt Signal

```
15:00  Alert fires: Deployment success drops to 95% (target >= 99.5%)
       → M6-Platform lead reports: "Our staging integration depends on M5 API finalization"
       → M5-Integration lead reports: "API not yet merged, waiting on M5-Core"
       → M5-Core lead reports: "API blocked on code review, 2+ days to fix"

15:15  L1 Escalations (all team leads):
       ├─ M5-Core: "Cannot complete API by Friday. Escalating to Phase Owner."
       ├─ M5-Integration: "Blocked on M5-Core. Escalating to Phase Owner."
       └─ M6-Platform: "Blocked on M5-Integration API. Escalating to Phase Owner."

15:30  Phase Owner M5 Response (L2):
       ├─ "Assess: Multi-team blocker confirmed (all 3 teams affected)"
       ├─ Decision: "Cannot resolve with L2 authority. Escalating to L3."
       └─ Email: gates-escalation@gftd.group, @platform-lead
           "3-team blocker: M5 API not ready by Friday. Request L3 decision on gate halt."

15:45  Platform Lead Response (L3):
       ├─ "Assess: Gate-halt signal. All 3 teams blocked on critical path dependency."
       ├─ Options: (1) Halt gate, delay start of M6 by 1 week
       │           (2) M5-Core: emergency code review + merge by Fri 20:00
       │           (3) M6-Platform: proceed without M5 API (mock integration)
       ├─ Decision: "Option 2 — Emergency code review. M5-Core: 4h window to complete API merge."
       └─ Escalation memo: "Platform-led code review scheduled Fri 16:00–17:00. Gate decision pending merge status."

16:00  Scheduled Friday Gate Review (holds for 1h):
       └─ All teams monitoring code review + merge
           Status updates every 15 min in #prod-gates-wave5

16:45  Code Review Completes:
       └─ "✅ API merged. Tests passing. M5-Integration can now integrate."
           Jira: Status = Gate Approved

17:00  Platform Lead Final Decision (L3):
       └─ "Gate is GO (with catch-up phase for M6 on Monday)."
           Escalation memo: "Emergency code review successful. All teams approved. Proceed."
       
Friday 16:00 Gate (Actual):
       └─ Decision: "Go (with L3-approved emergency mitigation)"
       → Metrics: Deployment success recovered to 99.6%
       → Escalation memo filed + analysis for next-month process improvement
```

---

## Escalation Ledger Entry (Template)

Every escalation is logged (append-only) in: `90-docs/gates/wave5-escalation-ledger.edn`

```edln
{:event-id "esc-20260820-001"
 :timestamp "2026-08-20T14:00:00Z"
 :alert-type "build-times-p99-exceed"
 :severity "warning"
 :blocker-id "jira-M5W5-123"
 :team "M5-Core"
 :responder-l1 "team-lead-m5-core"
 :responder-l2 "phase-owner-m5"
 :responder-l3 nil
 :escalation-path "L1 → resolved"
 :resolution-time "2h 30m"
 :outcome "issue-resolved-locally"
 :notes "Brief build regression in auth-module. Fixed by team lead. No gate impact."}
```

---

## Quick Reference Cheat Sheet

**📱 Bookmark This:**

```
GATE DECISION (Fri 16:00):
→ All teams say YES = GO
→ Any team says NO = Escalate blocker (not gate)
→ If L2 can't decide = L3 decides (4h SLA)

ESCALATION SLAs:
→ L1: 1h response
→ L2: 2h response (from alert)
→ L3: 4h response (from alert) + final authority

ALERT CHANNELS:
→ Slack: #prod-gates-wave5 (all alerts)
→ Email: gates-escalation@gftd.group (P1+)
→ PagerDuty: prod-gates (critical + SLA breaches)

WHEN TO ESCALATE:
→ If you can't fix in 1h, escalate to L2
→ If it's multi-team, escalate to L3
→ If gate-halt signal, L3 decides immediately

GATE DECISION AUTHORITY:
→ L1 (team lead): Triage + recommend
→ L2 (phase owner): Approve mitigations, assess multi-team impact
→ L3 (platform lead): FINAL gate-halt authority

```

---

**Print & post in your workspace. Refer to this every escalation.**

**Questions?** Ping #prod-gates-wave5 or reply to kickoff invitation.

---

**Generated:** 2026-07-21  
**Status:** Final, ready for production gates execution  
**Next Review:** 2026-08-25 (post-Week 11 debrief)
