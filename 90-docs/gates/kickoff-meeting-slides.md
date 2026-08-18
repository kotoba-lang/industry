# Wave 5 M5–M6 Production Gates Kickoff Meeting
## Slides + Speaker Notes

**Date:** 2026-08-18 09:00 AM JST  
**Duration:** 60 minutes  
**Attendees:** Team leads (3) + Phase owners (2) + Platform lead (1) + Metrics lead (1) + Escalation lead (1)  
**Recording:** Yes (archived in #prod-gates-wave5)  

---

## SLIDE 1: Welcome & Agenda Overview (2 min)

**Title:** "Production Gates Wave 5 M5–M6 — Execution Kickoff"

**Visual:** Wave 5 logo + timeline graphic (weeks 11–16)

**Speaker Notes:**
- Welcome everyone. Today we're activating the M5–M6 production gates execution framework.
- This is our system for tracking critical path, metrics, blockers, and gate decisions over the next 6 weeks.
- By end of this meeting, we need consensus from everyone on the process, tools, and escalation chain.
- We'll cover: schedule, metrics pipeline, escalation protocol, weekly rhythm, and tool setup.
- Any questions about agenda before we start?

**Agenda Visual:**
```
09:00–09:10  Welcome + Gate Schedule
09:10–09:25  Metrics Pipeline Walkthrough (Demo)
09:25–09:40  Escalation Protocol (Live Scenario)
09:40–09:50  Weekly Checkpoint Rhythm
09:50–10:00  Board Confirmation & Closing
```

---

## SLIDE 2: Gate Execution Schedule (8 min)

**Title:** "Weeks 11–16: M5–M6 Gate Execution Timeline"

**Visual:** 6-week Gantt chart (weeks 11–16 with gate markers)

| Week | Date | Phase | Gate Label | Critical Focus |
|------|------|-------|-----------|---|
| **11** | 2026-08-18 | M5 | M5 Critical Path Baseline | Kick-off + alignment + first Go/No-Go |
| **12** | 2026-08-25 | M5 | M5 First Sync Point | Mid-phase assessment + dependencies |
| **13** | 2026-09-01 | M5 | M5 Mid-Phase Checkpoint | Blocker triage + velocity trending |
| **14** | 2026-09-08 | M5 → M6 | M5 Close / M6 Prep | M5 wrap + M6 handoff readiness |
| **15** | 2026-09-15 | M6 | M6 Launch | M6 critical path start |
| **16** | 2026-09-22 | M6 | M6 Final Sync | M6 close + Month 3 readiness |

**Weekly Sync Points:**
- **Monday 09:00 AM:** Progress review + blocker triage (30 min)
- **Friday 16:00 (4 PM):** Gate review + Go/No-Go decision (45 min)

**SLA Expectations:**
- Alert response: 4 hours (warnings: build-times, coverage)
- Critical response: 2 hours (deployment failures, P1 blockers)
- Gate decision: must be made by Friday 16:00 (same-day closure)

**Speaker Notes:**
- We have 6 weeks, 4 major gates, and 26 weekly checkpoints.
- Think of each week as: Monday is triage and status, Friday is decision.
- The decision rule is consensus — all team leads must agree on Go/No-Go, or we escalate.
- If something is broken on Thursday, we don't wait — escalate immediately to phase owner.
- Questions on timeline or sync points?

---

## SLIDE 3: Metrics Pipeline — Overview (3 min)

**Title:** "Metrics Pipeline: Prometheus → Grafana → Alerts"

**Visual:** Data flow diagram (CI → Prometheus → Grafana → Slack/PagerDuty)

**5 Core Metrics:**

| Metric | Target | Collection | Alert |
|--------|--------|------------|-------|
| Build Times (p99) | < 12 min | 15 min cadence | Breach → Slack |
| Test Coverage | ≥ 92% | 1h cadence | Drop → Team DM |
| Deployment Success | ≥ 99.5% | 1h cadence | Breach → PagerDuty |
| Gate-Blocker Detection | < 2 min alert latency | 5 min cadence | P1 SLA breach → escalation |
| Team Velocity | vs. forecast | 1h cadence | Slope trending down → Monday review |

**Dashboard:**
- **URL:** `https://dashboard.internal/wave5-m5m6`
- **Refresh Rate:** 1 minute (Grafana p99 < 30 sec)
- **Panels:** Gate status grid, metrics trending, blocker queue, velocity burndown, escalation timeline

**Speaker Notes:**
- Metrics-lead will walk through the live dashboard next.
- These 5 metrics are the language we use to talk about health.
- When build times exceed 12 min, we all see it in Slack within 2 min.
- Coverage drops below 92%? Alerts the team lead immediately.
- Deployment starts failing? PagerDuty goes off — that's L3 territory.
- The pipeline is automated — no manual collection. Metrics flow in from CI.
- Next slide will show the dashboard live.

---

## SLIDE 4: Metrics Pipeline Demo (5 min)

**Title:** "Live Dashboard Walkthrough"

**Demo Sequence:**
1. Open `https://dashboard.internal/wave5-m5m6` (pre-staged in browser tab)
2. Show "Gate Status" panel — 3 team status tiles (M5-Core, M5-Integration, M6-Platform)
3. Show "Metrics Trending" — 6-week line chart (build-times, coverage, deploy-success)
4. Show "Blocker Queue" — table of active blockers (SLA countdown column)
5. Show "Velocity Burndown" — story points completed vs. forecast per team
6. Point out Grafana data freshness indicator (timestamp)

**Grafana Alert Test:**
- If time permits, click "Test Alert" button → watch for Slack message in #prod-gates-wave5
- Message format: `[ALERT] Coverage < 92% — Team: M5-Core — SLA: 4h response`

**Speaker Notes:**
- This dashboard is your single source of truth for metrics health.
- Data is pulled from CI every 15 min (build times) to 1 hour (coverage).
- Grafana refreshes every 30 seconds, so what you see here is basically live.
- If you see a blocker appear in the queue, the alert has already gone to your team's Slack channel.
- Blockers in red are P1 (2h SLA). Yellow are P2 (4h SLA).
- Any questions on dashboard layout or metric definitions before we move on?

---

## SLIDE 5: Escalation Protocol (10 min)

**Title:** "Escalation Protocol: 3-Level Decision Tree"

**Visual:** Escalation tree diagram (L1 → L2 → L3)

**Escalation Levels:**

```
Level 1: Team Lead (First Responder)
├─ SLA: 1h response
├─ Action: Triage, attempt fix, update Jira
└─ Escalate to L2 if unresolved after 1h

Level 2: Phase Owner (Escalation Authority)
├─ SLA: 2h response (from initial alert)
├─ Action: Cross-team impact check, authorize mitigations
└─ Escalate to L3 if multi-team or gate-halt signal

Level 3: Platform Lead (Final Authority)
├─ SLA: 4h response (from initial alert)
├─ Action: Gate-halt decision, stakeholder communication
└─ Document in escalation ledger
```

**Alert Channels:**
- **Slack:** `#prod-gates-wave5` (all alerts, P0)
- **Email:** `gates-escalation@gftd.group` (P1 escalations)
- **PagerDuty:** `prod-gates` service (critical + SLA breaches)

**Alert Routing Rules:**
- **Build-times exceed 12 min** → Slack `#prod-gates-wave5` + team lead DM → 4h SLA
- **Coverage drops < 92%** → Slack + team lead DM → 4h SLA
- **Deployment success < 99.5%** → PagerDuty + Slack + phase owner → 2h SLA
- **P1 blocker unresolved > 2h** → Escalation → Phase owner + Platform lead

**Decision Rule:**
- **Consensus:** All team leads must agree on Go/No-Go for the gate to pass.
- If blocked: escalate to phase owners for decision authority.
- If multi-team impact: Platform lead makes final call.

**Gate Halt Protocol:**
- Triggered when 2+ teams are blocked on critical path.
- Platform lead has 15 min to decide: retry, workaround, or halt.
- Document decision in escalation ledger (append-only).

**Speaker Notes:**
- The escalation protocol is about clarity, not blame.
- When an alert fires, it's the team lead's job to triage: real issue or false positive?
- If it's real, you've got 1 hour to fix it or escalate.
- If after 1 hour you're still stuck, escalate to your phase owner.
- Phase owner decides: is this single-team (we work around) or multi-team (we halt and regroup)?
- If we're going to halt the gate, platform lead makes that call within 4 hours of alert.
- The escalation ledger records every alert, who responded, what they decided, and how long it took.
- This becomes our playbook for next month.
- Now let me walk through a live scenario — imagine a build failure on Wednesday.

---

## SLIDE 6: Escalation Scenario (5 min)

**Title:** "Live Scenario: Build Failure at 14:00 Wednesday"

**Scenario Setup:**
```
14:00 Wed: CI reports build failure (coverage drop to 88%, target 92%)
│
├─ ALERT: Slack fires immediately
│   "#prod-gates-wave5: [ALERT] Coverage < 92% — Team: M5-Core — SLA: 4h deadline (18:00)"
│   Assigned to: @team-lead-m5-core
│
├─ 14:15 Team Lead Response (Level 1)
│   ├─ Check Jira blocker issue (auto-created by CI bot)
│   ├─ Triage: "Coverage drop is real — regression in auth-module tests"
│   ├─ Action: Pull in auth module owner, attempt fix
│   └─ Update Jira: "Status = Blocked, ETA 16:00"
│
├─ 15:30 Still Blocked (1.5h in, 2.5h to deadline)
│   └─ Escalate to Phase Owner (Level 2)
│       ├─ Phase Owner reviews: "Single-team issue, M5-Core can handle"
│       ├─ Decision: "Proceed with Wednesday gate (metrics still acceptable)"
│       └─ Update gate memo: "Coverage dip accepted, trend is up"
│
└─ 16:00 Continue Monitoring
    └─ If still broken by 18:00 (SLA deadline), escalate to Platform Lead (L3)
```

**Decision Points:**
1. **14:15:** Is this a real blocker or test flake? (Team Lead decides)
2. **15:30:** Single-team or multi-team impact? (Phase Owner decides)
3. **18:00 (if still broken):** Do we halt the gate or proceed? (Platform Lead decides)

**Escalation Ledger Entry (auto-logged):**
```
{:event-id "esc-20260820-001"
 :timestamp "2026-08-20T14:00:00Z"
 :alert-type "test-coverage-drop"
 :severity "warning"
 :blocker-id "jira-M5W5-123"
 :team "M5-Core"
 :responder-l1 "team-lead-m5-core"
 :responder-l2 "phase-owner-m5"
 :decision "proceed-with-mitigation"
 :resolution-time "1h 45m"
 :outcome "coverage-regression-fixed-by-eod"}
```

**Speaker Notes:**
- This is how we move quickly while maintaining safety.
- The team lead owns the triage — they know the system best.
- Phase owner owns the decision on whether it affects the gate.
- Platform lead owns the gate-halt authority.
- Every escalation is logged, so we can review patterns.
- If build-times keep exceeding 12 min, we'll spot it after 3 weeks and investigate.
- Questions on escalation flow? Everyone clear on when to escalate?

---

## SLIDE 7: Weekly Checkpoint Rhythm (5 min)

**Title:** "Weekly Rhythm: Monday Triage, Friday Decision"

**Monday 09:00 AM — Progress Review (30 min)**

| Activity | Duration | Owner | Output |
|----------|----------|-------|--------|
| Metrics health check | 5 min | Metrics lead | Grafana snapshot |
| Team progress review | 10 min | Team leads | Updated Trello cards |
| Blocker triage | 10 min | Team leads | P1/P2 prioritization |
| Velocity trending | 5 min | Metrics lead | Burndown analysis |

**Artifacts:**
- Trello board: updated critical-path cards
- Jira: blocker status (P1 count, escalation trigger?)
- Slack: progress summary + blocker count

**Friday 16:00 (4 PM) — Gate Review (45 min)**

| Activity | Duration | Owner | Output |
|----------|----------|-------|--------|
| Gate readiness check | 10 min | Team leads | % complete per team |
| Critical path verification | 10 min | Metrics lead | Dependencies confirmed? |
| Cross-team check | 5 min | Phase owners | M5 → M6 handoff ready? |
| Escalation summary | 5 min | Escalation lead | Any P1s still open? |
| **Decision: Go/No-Go** | 10 min | All (consensus) | **Gate memo** |
| Next week prep | 5 min | Metrics lead | Week N+1 critical path |

**Artifacts:**
- Gate Decision Memo (Jira: gate-readiness-check)
- Weekly Metrics Snapshot (Jira: weekly-metrics-snapshot)
- Next Week Critical Path (Trello cards for Week N+1)
- Archive: Move done cards to archive
- Slack notification: decision + KPI summary

**Decision Rule Reminder:**
> "Does your team agree we are Go for the gate (all critical path complete, metrics healthy, blockers resolved or escalated)?"
> 
> **All team leads must say YES, or we don't go.**
> If blocked: escalate blocker, not the entire gate.

**Speaker Notes:**
- Monday is lightweight — 30 minutes, triage only.
- Friday is the full ceremony — 45 minutes, this is where we make the Go/No-Go call.
- The decision rule is consensus. That means if M5-Core lead says "No, we're not ready," we listen.
- We don't vote — consensus means "do we all agree we're safe to proceed?"
- If there's disagreement, we escalate the blocker to phase owner and table the decision for 2 hours (let them help resolve).
- By 16:00 Friday, we need a decision. If still blocked, we escalate to platform lead and they call it.
- Artifacts all go into Jira + Slack, so there's a public record.
- Questions on checkpoint flow?

---

## SLIDE 8: Board & Tool Setup (5 min)

**Title:** "Tool Setup: Trello, Jira, Slack"

**Team Boards (to be live by 2026-08-15):**

| Team | Trello Board | Jira Project | Slack Channel |
|------|--------------|--------------|---------------|
| M5-Core | `trello-m5-core-wave5` | M5W5 | #prod-gates-wave5 |
| M5-Integration | `trello-m5-integration-wave5` | M5IW5 | #prod-gates-wave5 |
| M6-Platform | `trello-m6-platform-wave5` | M6W5 | #prod-gates-wave5 |

**Each Trello Board has 4 lists:**
1. **🎯 Week N Critical Path** — items due Friday 16:00
2. **🚨 Blockers & Risks** — escalate if > 4h unblocked
3. **📊 Metrics This Week** — auto-populated from Prometheus
4. **✅ Done (This Week)** — completed, archive post-Friday

**Jira Workflow (per project):**
- **Open** → **In Progress** → **Blocked** (escalate!) → **Ready for Gate** → **Gate Approved** → **Done**

**Slack #prod-gates-wave5:**
- All alerts post here (build-times, coverage, deploy failures)
- All team leads monitor continuously (set notification level: high priority)
- Escalation decisions announced here (transparency)
- Weekly gate decision announced Friday 16:00

**Before We Leave This Meeting:**
- Confirm: all team leads have Trello + Jira + Slack access
- Confirm: you know how to create a Jira blocker issue (demo if needed)
- Confirm: you know to check #prod-gates-wave5 channel first thing Monday & Friday

**Speaker Notes:**
- Trello is your execution board — where your team tracks daily work.
- Jira is the formal record — gate decisions, escalations, metrics snapshots.
- Slack is the alarm bell — when something breaks, Slack fires first, then you check Jira for details.
- By 2026-08-15, all three should be live. If you're a team lead and your board isn't ready, let me know now.
- We'll do a quick access check right after this meeting.
- Any questions on tools before we close?

---

## SLIDE 9: Success Criteria & Next Steps (5 min)

**Title:** "Success Criteria & Closing"

**Execution Success Criteria (Week 11 onwards):**

✅ All boards live and team-accessible (by 2026-08-18)
✅ Metrics pipeline collecting data (by 2026-08-18)
✅ First checkpoint completed on-time (Mon 2026-08-19 09:00)
✅ First gate decision made by consensus (Fri 2026-08-22 16:00)
✅ Zero gate halts due to missing infrastructure/metrics (Week 11)
✅ All escalations logged and SLA'd (by 2026-08-25)

**This Week's To-Do (by 2026-08-17):**
- [ ] All team leads: confirm Trello + Jira + Slack access
- [ ] All team leads: read escalation protocol (15 min)
- [ ] Metrics lead: run smoke test (2026-08-10)
- [ ] DevOps: deploy CI gate-bot workflow (2026-08-09)
- [ ] All: attend pre-kickoff sync call (2026-08-16, TBD time)

**First Checkpoint Timeline:**
- **Monday 2026-08-19 09:00 AM:** Week 11 progress review (30 min)
- **Friday 2026-08-22 16:00:** Week 11 gate review + first Go/No-Go decision (45 min)

**Closing Consensus Check:**

> "Before we break: Does your team understand and commit to the gate execution framework (schedule, metrics, escalation, checkpoints)?"
>
> **Show thumbs-up in chat if YES.**

**Expected Outcome:** All attendees thumbs-up (unanimous agreement).

**Speaker Notes:**
- That's the framework. It's designed to be fast, transparent, and escalation-clear.
- We have 6 weeks to prove this works. If something breaks, we log it and fix it.
- The escalation ledger becomes our playbook for Month 3.
- I'll send out the recording and slide deck before end of day today.
- Pre-kickoff sync call on Friday 2026-08-16 at [TBD time] — Q&A and dry-run scenario.
- After this meeting, I'm staying for 10 min — come grab me if you need tool access help.
- Thank you all for your commitment to this execution. Let's make it clean.

---

## SLIDE 10: Q&A & Closing (remaining time)

**Title:** "Q&A + Tool Access Check"

**Open Questions:**
- Any clarity on escalation chain?
- Any concerns on metrics targets?
- Any tool access issues?
- Any schedule conflicts (Monday 09:00 or Friday 16:00)?

**Tool Access Verification (1 min check):**
- Team Lead M5-Core: Can you access `trello-m5-core-wave5`? (Jira M5W5?)
- Team Lead M5-Integration: Same check...
- Team Lead M6-Platform: Same check...

**Closing Remarks:**
- "This framework keeps us aligned without slowing us down."
- "When in doubt, escalate early — it's cheaper than finding out on Friday at 16:00."
- "The escalation ledger is our learning system — every escalation teaches us something."
- "6 weeks, 4 gates, 26 checkpoints. Let's execute cleanly."

**Send-Off:**
- "Meeting recording will be in #prod-gates-wave5 thread by EOD."
- "Slides + docs: [link to 90-docs/gates]"
- "Next meeting: Monday 2026-08-19 09:00 (Week 11 progress review)"
- "I'm available for 10 min after this if you need access help."

---

## Speaker Notes Summary

**Timing Check:**
- Welcome & Agenda: 2 min
- Schedule: 8 min (cumulative: 10 min)
- Metrics Overview: 3 min (cumulative: 13 min)
- Metrics Demo: 5 min (cumulative: 18 min)
- Escalation Protocol: 10 min (cumulative: 28 min)
- Escalation Scenario: 5 min (cumulative: 33 min)
- Checkpoint Rhythm: 5 min (cumulative: 38 min)
- Tool Setup: 5 min (cumulative: 43 min)
- Success Criteria: 5 min (cumulative: 48 min)
- Q&A: ~12 min (cumulative: 60 min)

**Pro Tips:**
- Have Grafana dashboard pre-loaded in browser (avoid connection delays)
- Have escalation scenario notes printed (read from script if nervous)
- Have Slack #prod-gates-wave5 open to show real alert format
- Encourage questions — this is not a presentation, it's a working session
- If Q&A runs long, schedule follow-up 1-1s with team leads who have specific concerns

---

**Generated:** 2026-07-21
**Status:** Ready for 2026-08-18 kickoff
