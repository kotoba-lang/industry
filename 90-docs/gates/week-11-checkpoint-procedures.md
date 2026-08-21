# Wave 5 M5–M6 Production Gates — Week 11 Checkpoint Procedures

**First Week Detailed Walkthrough (2026-08-18 to 2026-08-25)**

**Status:** Ready for kickoff 2026-08-18  
**Audience:** Team leads, metrics lead, execution lead

---

## Week 11 Overview

| Activity | Date | Time | Duration | Attendees |
|----------|------|------|----------|-----------|
| **KICKOFF MEETING** | Sun 2026-08-18 | 09:00 AM | 60 min | All |
| **Monday Progress Review** | Mon 2026-08-19 | 09:00 AM | 30 min | Team leads + metrics |
| **Friday Gate Review** | Fri 2026-08-22 | 16:00 (4 PM) | 45 min | All |

**Success Criteria (Week 11):**
- ✅ Kickoff meeting held, consensus achieved
- ✅ All boards (Trello + Jira + Slack) live and accessible
- ✅ Metrics pipeline collecting data (Grafana dashboard refreshing)
- ✅ Monday checkpoint completed on-time, notes in Jira
- ✅ Friday gate review completed, Go/No-Go decision made
- ✅ Zero gate halts due to missing infrastructure (only real blockers)

---

## Pre-Checkpoint: Kickoff Meeting (2026-08-18 09:00 AM)

### Timing

- **Invite opens:** 08:55 AM (Zoom link active, testing)
- **Meeting start:** 09:00 AM (agenda begins)
- **Meeting end:** 10:00 AM (60 min, +Q&A if time)
- **Post-meeting:** 10:00–10:10 AM (tool access verification)

### Agenda (Detailed)

**(Refer to: 90-docs/gates/kickoff-meeting-slides.md for full speaker notes)**

```
09:00–09:02  Welcome & Housekeeping
             ├─ Introduce execution lead
             ├─ Confirm recording enabled
             └─ Explain Zoom chat behavior (reactions for consensus)

09:02–09:10  Gate Schedule Review (8 min)
             ├─ Weeks 11–16 Gantt chart walkthrough
             ├─ Weekly sync points: Monday 09:00 + Friday 16:00
             └─ SLA expectations: 4h alert, 2h critical, same-day gate decision

09:10–09:15  Metrics Pipeline Overview (5 min)
             ├─ 5 core metrics (build-times, coverage, deploy-success, blocker-detection, velocity)
             └─ Targets + alert routing

09:15–09:25  Metrics Pipeline Demo (10 min)
             ├─ Open Grafana dashboard (live)
             ├─ Walk through each metric panel
             ├─ Show blocker queue table
             └─ Demo alert test → watch for Slack message

09:25–09:35  Escalation Protocol (10 min)
             ├─ 3-level tree (L1 → L2 → L3)
             ├─ SLA targets (1h / 2h / 4h)
             └─ Alert channels (Slack, email, PagerDuty)

09:35–09:40  Escalation Scenario Walkthrough (5 min)
             ├─ Walk through a live escalation scenario (build failure Wed 14:00)
             └─ Practice decision tree: L1 triage → L2 impact check → L3 halt decision

09:40–09:50  Weekly Checkpoint Rhythm (10 min)
             ├─ Monday 09:00: triage + status (no decision)
             ├─ Friday 16:00: gate review + Go/No-Go decision (consensus)
             └─ Weekly artifacts (gate memo, metrics snapshot, next week critical path)

09:50–09:55  Board & Tool Setup (5 min)
             ├─ Confirm Trello board access
             ├─ Confirm Jira project access
             └─ Confirm Slack #prod-gates-wave5 channel

09:55–10:00  Consensus Confirmation & Closing (5 min)
             ├─ Thumbs-up poll: "Do all teams understand and commit?"
             ├─ Recording confirmation: will archive in Slack
             └─ Next checkpoint: Monday 2026-08-19 09:00

10:00–10:10  Tool Access Verification (Post-Meeting)
             ├─ Quick 1-1s with any team leads who need access help
             └─ Record any blockers for resolution by Monday
```

### Pre-Meeting Setup (by 08:55 AM)

**Organizer Checklist:**
- [ ] Zoom room open + recording armed
- [ ] Slides loaded in browser
- [ ] Grafana dashboard URL ready (pre-load in tab)
- [ ] Escalation scenario script printed (reference)
- [ ] Slack #prod-gates-wave5 open for alert demo
- [ ] Recording settings: auto-start + archive to Slack thread
- [ ] Chat instructions shared: "Use 👍 for YES, 🙅 for NO, questions in chat"

### Post-Meeting Logistics (by 10:10 AM)

**By 10:30 AM (same day):**
- [ ] Upload recording to Slack #prod-gates-wave5 (thread title: "Week 11 Kickoff Recording")
- [ ] Post slide deck link to #prod-gates-wave5
- [ ] Send email confirmation to all attendees (Email #4 from team-notifications-email-templates.md)
- [ ] Publish calendar invites for Monday + Friday (if not already sent)
- [ ] Note any blockers from tool access verification

---

## Monday Checkpoint (2026-08-19 09:00 AM)

### Purpose
Triage. Status. Blocker identification. **NO DECISIONS.**

### Pre-Meeting Prep (by 08:55 AM)

**Each team lead:**
- [ ] Check Grafana dashboard (screenshot latest metrics)
- [ ] Review Trello board (move completed cards to "Done")
- [ ] Pull active blocker list from Jira (status = Blocked)
- [ ] Calculate velocity: story points completed + remaining
- [ ] Prepare brief 2-min status: "What's complete? What's at risk?"

**Metrics lead:**
- [ ] Pull 5-metric snapshot from Grafana
- [ ] Note any anomalies (coverage drop? build slowdown?)
- [ ] Prepare trending analysis (up? down? flat?)

**Execution lead:**
- [ ] Start Jira issue: type = "monday-review-note" (to log all team updates)
- [ ] Open Google Doc (or Slack thread) for live notes

### Agenda (30 min)

```
09:00–09:05  Metrics Health Check (5 min)
             Metrics lead presents:
             ├─ Build p99: [value] (target < 12 min) [✅ / ⚠️ / 🔴]
             ├─ Coverage: [value]% (target >= 92%) [✅ / ⚠️ / 🔴]
             ├─ Deploy success: [value]% (target >= 99.5%) [✅ / ⚠️ / 🔴]
             ├─ Blocker detection: [value] sec (target < 2 min) [✅ / ⚠️ / 🔴]
             └─ Velocity: M5-Core [Xsp], M5-Integration [Xsp], M6-Platform [Xsp]

09:05–09:15  Team Progress Review (10 min)
             Each team lead (2 min each):
             ├─ M5-Core Lead:
             │  └─ "Completed: [list]. In progress: [list]. At risk: [list]."
             ├─ M5-Integration Lead:
             │  └─ "Completed: [list]. In progress: [list]. At risk: [list]."
             └─ M6-Platform Lead:
                └─ "Completed: [list]. In progress: [list]. At risk: [list]."

09:15–09:25  Blocker Triage (10 min)
             Execution lead + metrics lead:
             ├─ List all P1 blockers (2h SLA)
             ├─ List all P2 blockers (4h SLA)
             ├─ For each: owner, ETA, escalation needed?
             └─ Decision: Any escalations to kick off Monday?
                (If yes, escalate to phase owner immediately via Slack + email)

09:25–09:30  Velocity Trending & Forecast (5 min)
             Metrics lead + team leads:
             ├─ Burndown slope: are we on pace to complete critical path by Friday?
             ├─ Forecast: If current pace, will we hit Go/No-Go on Friday?
             └─ Risks: What could cause us to miss Friday gate?
```

### Outputs (by 10:00 AM)

1. **Jira Issue: Monday Review Note**
   - Type: `monday-review-note`
   - Project: [Team's project — M5W5, M5IW5, M6W5]
   - Title: "Week 11 Monday Progress Review"
   - Description: Team progress summary + blockers identified + velocity forecast
   - Status: Close when meeting adjourns

2. **Trello Board Update**
   - Move completed cards from critical path → Done list
   - Update blocker cards with current status + ETA
   - Update metrics card with latest Grafana values

3. **Slack Message** (post to #prod-gates-wave5 by 10:00 AM)
   ```
   📊 Week 11 Monday Review Complete
   
   Metrics Snapshot:
   • Build p99: [X min] (target < 12 min) [✅ / ⚠️]
   • Coverage: [X%] (target >= 92%) [✅ / ⚠️]
   • Deploy Success: [X%] (target >= 99.5%) [✅ / ⚠️]
   
   Status:
   • M5-Core: [X% critical path] — [status]
   • M5-Integration: [X% critical path] — [status]
   • M6-Platform: [X% critical path] — [status]
   
   Blockers:
   • P1 (2h SLA): [count] active
   • P2 (4h SLA): [count] active
   
   Forecast: On track for Friday Go/No-Go? [Yes / At risk / No]
   
   Next: Friday 16:00 gate review. All teams focused on closing blockers by EOD Thursday.
   ```

### Post-Meeting Escalations

If Monday meeting identifies blockers that need escalation:

1. **Immediate escalation (if P1):**
   - [ ] Team lead posts to #prod-gates-wave5: "P1 Blocker: [title] — Escalating to @phase-owner-[m5|m6]"
   - [ ] Email gates-escalation@gftd.group with blocker details
   - [ ] Add Jira issue link + ETA to resolve
   - [ ] Log in escalation ledger (auto or manual)

2. **Phase owner response (within 1h of escalation):**
   - Assess: Single-team or multi-team impact?
   - Decide: Retry / workaround / escalate to L3
   - Update Jira issue with decision
   - Notify team in Slack

3. **Tracking:**
   - Escalation ledger: 90-docs/gates/wave5-escalation-ledger.edn (append-only)
   - Format: 1 EDN map per line (timestamp, alert-type, severity, team, responder, decision, resolution-time, outcome)

---

## Friday Gate Review (2026-08-22 16:00)

### Purpose
Gate readiness assessment. Consensus decision. **Go/No-Go.**

### Pre-Meeting Prep (by 15:45)

**Each team lead:**
- [ ] Final metrics check (Grafana latest values)
- [ ] Final critical path status: % complete
- [ ] Prepare 3-min update: gate readiness summary
- [ ] Verify: no unresolved P1 blockers (or escalation path confirmed)
- [ ] Confirm: all artifacts ready for gate decision memo

**Phase owners:**
- [ ] Review team lead readiness summaries
- [ ] Prepare cross-team dependency assessment (any blocking dependencies?)
- [ ] Confirm: M5 → M6 handoff items on track (if applicable)

**Metrics lead:**
- [ ] Prepare metrics snapshot (5 metrics + trending analysis)
- [ ] Flag any anomalies or trending concerns
- [ ] Prepare trending chart for gate decision memo

**Platform lead:**
- [ ] Review escalation ledger for any unresolved L2/L3 decisions
- [ ] Prepare for gate-halt authority if needed
- [ ] Have contingency/rollback plans ready (if used)

**Execution lead:**
- [ ] Start Jira issue: type = "gate-readiness-check"
- [ ] Prepare gate decision template
- [ ] Set up Slack thread for live decision updates

### Agenda (45 min)

```
16:00–16:10  Gate Readiness Check (10 min)
             Each team lead (2 min each):
             ├─ M5-Core Lead:
             │  └─ "Critical path: [X%] complete. Status: [Go/No-Go]. Key metrics: [summary]."
             ├─ M5-Integration Lead:
             │  └─ "Critical path: [X%] complete. Status: [Go/No-Go]. Key metrics: [summary]."
             └─ M6-Platform Lead:
                └─ "Critical path: [X%] complete. Status: [Go/No-Go]. Key metrics: [summary]."

16:10–16:20  Critical Path Verification (10 min)
             Metrics lead + phase owners:
             ├─ Confirm: all items on critical path are either complete or on track
             ├─ Confirm: no unexpected dependencies blocking completion
             ├─ Confirm: rollback plans documented (if needed)
             └─ Metrics validation: all 5 metrics healthy?

16:20–16:25  Cross-Team Dependency Check (5 min)
             Phase owners + platform lead:
             ├─ M5 → M6 handoff items: ready?
             ├─ Platform infrastructure: stable?
             ├─ Any blocking dependencies across teams?
             └─ Staging environment: validation complete?

16:25–16:30  Escalation Summary (5 min)
             Escalation lead (if applicable):
             ├─ Any P1 blockers still open? (If yes, trigger L2/L3 decision immediately)
             ├─ Any escalations in progress? (Status?)
             ├─ SLA status: all escalations within SLA window?
             └─ Recommendation: Go or escalate blocker?

16:30–16:35  CONSENSUS DECISION: Go/No-Go (5 min)
             All attendees (team leads + phase owners + platform lead):
             
             Execution lead asks:
             > "Does your team agree we are **Go** for Week 11 gate?
             >  (All critical path complete, metrics healthy, blockers resolved or escalated)"
             
             Each attendee responds (round-robin or chat reactions):
             ├─ ✅ YES → "My team is Go"
             ├─ ❌ NO → "My team has a blocker [escalate to phase owner]"
             └─ ⏳ HOLD → "My team can proceed with caution (escalation-approved mitigation)"
             
             Expected outcome: All ✅ (unanimous)
             If any ❌: Escalate blocker to phase owner (don't vote on blocker)
             If any ⏳: Document mitigation + escalation approval in gate memo

16:35–16:45  Post-Gate Logistics (10 min)
             ├─ Decision memo: Execution lead publishes to Jira (gate-readiness-check)
             ├─ Metrics snapshot: Publish to Jira (weekly-metrics-snapshot)
             ├─ Escalation summary: Publish if any escalations active (blocker-escalation)
             ├─ Next week critical path: Execution lead + team leads create Trello cards for Week 12
             ├─ Archive: Move Week 11 done cards from Trello → Done list (retention archive)
             └─ Slack notification: Post decision + KPI summary to #prod-gates-wave5
                Email notification: Send decision memo to gates-escalation@gftd.group
```

### Outputs (by 17:00)

1. **Jira Issue: Gate Decision Memo**
   - Type: `gate-readiness-check`
   - Title: "Week 11 Gate Review — Go/No-Go Decision"
   - Description:
     ```
     ## Gate Decision: **[GO / NO-GO / HOLD]**
     
     ### Consensus
     All team leads agree: ✅ Unanimous (or ❌ Escalation pending — see below)
     
     ### Critical Path Completion
     - M5-Core: [X%] complete
     - M5-Integration: [X%] complete
     - M6-Platform: [X%] complete
     
     ### Metrics Snapshot
     - Build p99: [X min] (target < 12 min) [✅]
     - Coverage: [X%] (target >= 92%) [✅]
     - Deploy success: [X%] (target >= 99.5%) [✅]
     - Blocker detection: [X sec] (target < 2 min) [✅]
     - Team velocity: [M5-Core: X sp, M5-Integration: X sp, M6-Platform: X sp]
     
     ### Blockers
     - P1 (2h SLA): [count] resolved, [count] escalated
     - P2 (4h SLA): [count] resolved, [count] escalated
     
     ### Escalations (if any)
     [List escalations, resolution status, L2/L3 decisions]
     
     ### Next Week Prep
     Critical path for Week 12:
     - M5-Core: [list items]
     - M5-Integration: [list items]
     - M6-Platform: [list items]
     
     ### Rationale
     [1-2 sentences: why Go/No-Go]
     
     Decision made by: Platform lead (L3) / Phase owner (L2) / Team consensus (L1)
     Timestamp: [datetime]
     ```
   - Status: Transition to "Gate Approved"

2. **Jira Issue: Weekly Metrics Snapshot**
   - Type: `weekly-metrics-snapshot`
   - Title: "Week 11 Metrics Report"
   - Description:
     ```
     ## Metrics Report — Week 11
     
     ### Metric Values
     - Build Times (p99): [X min] (target < 12 min, SLA +/-)
     - Test Coverage: [X%] (target >= 92%, SLA +/-)
     - Deployment Success: [X%] (target >= 99.5%, SLA +/-)
     - Gate-Blocker Detection Latency: [X sec] (target < 2 min, SLA +/-)
     - Team Velocity: [M5-Core: X sp, M5-Integration: X sp, M6-Platform: X sp] vs. forecast [+/- X%]
     
     ### Trending
     - Build times: [↑ improving / → stable / ↓ degrading]
     - Coverage: [↑ improving / → stable / ↓ degrading]
     - Deploy success: [↑ improving / → stable / ↓ degrading]
     - Velocity: [↑ ahead of forecast / → on track / ↓ behind forecast]
     
     ### Anomalies
     [Any unexpected metric behavior? Root cause? Mitigation?]
     
     ### SLA Status
     - Alert response SLA: [X% met]
     - Critical response SLA: [X% met]
     - Gate decision latency: [X min] (target: same-day)
     
     Data source: Prometheus/Grafana
     Report generated: [datetime]
     ```
   - Status: Close when published

3. **Slack Notification** (post to #prod-gates-wave5 by 17:00)
   ```
   🎯 WEEK 11 GATE DECISION: **GO**
   
   ✅ All teams agree. Critical path complete. Metrics healthy.
   
   📊 Metrics Snapshot:
   • Build p99: [X min] (target: < 12 min) ✅
   • Coverage: [X%] (target: >= 92%) ✅
   • Deploy Success: [X%] (target: >= 99.5%) ✅
   
   🎯 Critical Path:
   • M5-Core: [X%] complete ✅
   • M5-Integration: [X%] complete ✅
   • M6-Platform: [X%] complete ✅
   
   📈 Next Week (Week 12):
   M5 midpoint assessment — all critical path items refreshed & ready.
   Detailed plan: see Trello boards + Jira (Week 12 critical path cards).
   
   Gate memo: https://jira.gftd.group/browse/[gate-decision-issue]
   Metrics report: https://jira.gftd.group/browse/[metrics-snapshot-issue]
   
   👉 Next checkpoint: Monday 2026-08-26 09:00 (Week 12 progress review)
   ```

4. **Email Notification** (send to gates-escalation@gftd.group by 17:00)
   - To: [all attendees]
   - Subject: "Week 11 Gate Decision: GO — Metrics & Next Week Forecast"
   - Body: (Use Email #6 template from team-notifications-email-templates.md)

5. **Trello Board Cleanup** (by 17:30)
   - Move all completed Week 11 cards to "Done" list
   - Archive "Done" list to clean board for Week 12
   - Create Week 12 critical path cards (ready for Monday)

6. **Documentation Archive** (by 17:30)
   - Save gate decision memo + metrics snapshot to: 90-docs/gates/weekly-reports/wave5-m5m6/week-11.edn
   - Save escalation ledger entries (auto-generated or manual append)
   - Update ACTIVATION-STATUS-WAVE5.md with Week 11 completion status

### Post-Gate: If No-Go or Hold

**If No-Go decision:**
1. Root cause: Which blocker(s) blocked the gate?
2. Mitigation: What's the plan to resolve by Monday?
3. Escalation: Phase owner + platform lead coordinate on rework
4. Communication: Notify stakeholders of 1-week delay
5. Next gate: Rescheduled for following Friday (2026-08-29) with fresh critical path

**If Hold decision (proceed with caution):**
1. Escalation-approved mitigation: Document what exception was approved
2. Risk acceptance: Phase owner + platform lead sign-off
3. Extra monitoring: Week 12 includes focused validation of Hold items
4. Post-gate retrospective: Schedule for 2026-08-29 (why did we need exception?)

---

## Week 11 Success Checklist

### Pre-Kickoff (by 2026-08-17)

- [ ] All infrastructure deployed + smoke tested
- [ ] Grafana dashboard live + panels rendering
- [ ] CI gate-bot workflow active + collecting metrics
- [ ] Alert routing tested (Slack + PagerDuty)
- [ ] All attendees have calendar invites (Mon 09:00 + Fri 16:00 recurring)
- [ ] All team leads confirmed access to Trello + Jira + Slack
- [ ] Zoom link created + tested (backup dial-in number ready)
- [ ] Slides finalized + speaker notes printed
- [ ] Escalation protocol read by all team leads

### Kickoff Day (2026-08-18)

- [ ] Zoom room open by 08:55 AM, recording armed
- [ ] Grafana dashboard pre-loaded + ready for demo
- [ ] Escalation scenario script ready
- [ ] Slack #prod-gates-wave5 channel open for alert demo
- [ ] All attendees present (or represented)
- [ ] Consensus achieved (thumbs-up poll result: unanimous ✅)
- [ ] Recording saved to Slack thread
- [ ] Tool access issues resolved (1-on-1s after meeting)

### Monday Checkpoint (2026-08-19 09:00)

- [ ] All team leads present
- [ ] Metrics lead present
- [ ] Grafana dashboard screenshot ready
- [ ] Jira blocker list pulled
- [ ] Trello board updated (completed cards moved)
- [ ] Meeting notes logged in Jira
- [ ] Slack status update posted by 10:00 AM
- [ ] Any P1 escalations kicked off immediately

### Friday Gate Review (2026-08-22 16:00)

- [ ] All attendees present (team leads + phase owners + platform lead + metrics lead)
- [ ] Final metrics check complete
- [ ] Critical path status confirmed (all items complete or on track)
- [ ] No unresolved P1 blockers (or escalation path confirmed)
- [ ] Consensus decision achieved (all ✅ for Go)
- [ ] Gate decision memo published to Jira
- [ ] Metrics snapshot published to Jira
- [ ] Slack notification posted by 17:00
- [ ] Email decision memo sent to gates-escalation@gftd.group
- [ ] Trello cards archived
- [ ] Week 12 critical path cards created

### Post-Friday (by 2026-08-25)

- [ ] All Week 11 artifacts archived (gate decision, metrics, escalations)
- [ ] Escalation ledger updated with all Week 11 escalations
- [ ] Week 11 debrief (optional): any process improvements noted?
- [ ] Week 12 critical path ready for Monday kickoff
- [ ] Calendar reminders sent for Week 12 (Mon + Fri recurring)

---

## Troubleshooting

### Issue: Team lead unavailable for Monday/Friday meeting

**Solution:**
- Designate alternate attendee (team member with gate authority)
- Notify execution lead + metrics lead 24h in advance
- Alternate must have same context + decision authority as team lead
- Log alternate attendance in Jira (for escalation ledger clarity)

### Issue: Grafana dashboard not responding during meeting

**Solution:**
- Have screenshot/image of latest metrics prepared (backup)
- Post metrics values to Slack as fallback
- Continue meeting using static data
- Escalate to metrics-lead after meeting (investigate dashboard outage)
- Document dashboard SLA breach in escalation ledger

### Issue: P1 blocker discovered Friday 15:45 (15 min before gate review)

**Solution:**
- Immediate escalation to L2 (phase owner)
- Phase owner has 15 min to assess + decide
- If solvable in 30 min: retry + reconvene gate review at 16:30
- If unsolvable by 16:00: escalate to L3 (platform lead)
- Platform lead decides: Go-with-escalation or No-Go
- Document emergency decision in gate memo + escalation ledger

### Issue: Consensus blocked (one team says No-Go)

**Solution:**
- Do NOT vote on No-Go blocker
- Ask: "What specific item is blocking your team?"
- Escalate that blocker to phase owner (not the gate)
- Phase owner assesses: single-team workaround or multi-team impact?
- Continue gate review for other teams while phase owner works blocker
- If phase owner resolves blocker before 17:00: gate can still be Go
- If blocker unresolved by 17:00: gate is No-Go (1-week delay)

---

## Week 11 Retrospective (Optional, 2026-08-25)

After Friday gate decision, optionally schedule 30-min retrospective:

**Questions to discuss:**
- What went well with the gate execution framework?
- What was harder than expected?
- Any process improvements for Week 12?
- Metrics accuracy: were targets realistic?
- Escalation clarity: did SLAs work as intended?
- Calendar/timing: any conflicts or scheduling issues?

**Output:**
- Quick notes in Slack #prod-gates-wave5
- Feed improvements into Week 12+ adjustments
- Share with platform lead + phase owners

---

**Generated:** 2026-07-21  
**Status:** Ready for Week 11 execution starting 2026-08-18  
**Next Review:** 2026-08-25 (post-Week 11 debrief)
