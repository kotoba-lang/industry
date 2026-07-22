# Execution Lead Quick Reference Card
**Wave 5 M5–M6 Production Gates Distribution Timeline**  
**Prepared:** 2026-07-21  
**For:** Execution Lead (Interim: platform-lead@gftd.group)

---

## Key Contact Directory

| Role | Email | Phone | Notes |
|------|-------|-------|-------|
| **Execution Lead** | platform-lead@gftd.group | [TBD] | Owner: Jun Kawasaki (jun@gftd.group) |
| **Metrics Lead** | metrics-lead@gftd.group | [TBD] | Dashboard/pipeline owner |
| **Escalation Lead** | escalation-lead@gftd.group | [TBD] | SLA/protocol owner |
| **Platform Lead** | platform-lead@gftd.group | [TBD] | Level 3 decision authority |
| **Phase Owner M5** | m5-phase-owner@gftd.group | [TBD] | Workstream lead |
| **Phase Owner M6** | m6-phase-owner@gftd.group | [TBD] | Workstream lead |
| **Team Leads (M5-Core, M5-Integ, M6-Plat)** | [Distribution list] | [TBD] | See team-notifications-email-templates.md |

---

## Email Send Schedule (At a Glance)

```
2026-08-01 09:00 JST  → Email 1: Kickoff Invitation
2026-08-09 09:00 JST  → Email 2: Pre-Kickoff Staging Confirmation
2026-08-15 09:00 JST  → Email 3: Kickoff Reminder + Zoom Link
2026-08-18 15:00 JST  → Email 4: Post-Kickoff Consensus Confirmation
2026-08-19 16:00 JST  → Email 5: Weekly Status (1st time)
2026-08-22 16:30 JST  → Email 6: Gate Decision (1st time)

(Then weekly: Monday 16:00 for Email 5, Friday 16:30 for Email 6)
```

---

## Critical Dates & Deadlines

| Date | Event | Action Required |
|------|-------|-----------------|
| **2026-07-22** | Exec lead assignment deadline | Confirm real lead OR activate interim |
| **2026-07-31** | Slack channel live | Channel created, members invited, pins posted |
| **2026-08-01** | Email 1 send (ACTIVATION) | Send kickoff invitation to all stakeholders |
| **2026-08-10** | Zoom link deadline | Generate link for kickoff + pre-kickoff sync |
| **2026-08-15** | Email 3 send | Kickoff reminder + Zoom link (3 days before kickoff) |
| **2026-08-18** | KICKOFF MEETING | 09:00 AM JST, 60 min, record enabled |
| **2026-08-18** | Email 4 send | Post-kickoff (15:00), send consensus confirmation |
| **2026-08-19** | First checkpoint | Monday progress review (Email 5 at 16:00) |
| **2026-08-22** | First gate decision | Friday gate review (Email 6 at 16:30) |

---

## Pre-Activation Checklist (by 2026-08-01 08:00 JST)

- [ ] **Exec lead assignment confirmed** (real or interim)
- [ ] **Manifest canonical verified** (run: `nbb scripts/gen-west-manifest.cljs --check`)
- [ ] **Email templates reviewed** (no [TBD] in 6 templates)
- [ ] **Contact distribution lists confirmed** (all 8 recipients validated)
- [ ] **SMTP/email path tested** (send test email)
- [ ] **Slack channel plan** (ready to create by 2026-07-31)
- [ ] **Zoom license available** (meeting creation confirmed)
- [ ] **Calendar system ready** (Google Calendar / Outlook tested)
- [ ] **Kickoff materials verified** (7/7 deliverables present)
- [ ] **Escalation protocol ready** (reviewed by exec lead)

---

## Zoom Meeting Setup Checklist (by 2026-08-10)

For **Kickoff Meeting (2026-08-18 09:00 JST)**:
- [ ] Create Zoom meeting (60 min duration)
- [ ] Set meeting ID and passcode
- [ ] Enable recording
- [ ] Set waiting room: OFF (direct join)
- [ ] Enable co-host: ON (escalation lead can co-host if needed)
- [ ] Enable chat: ON (for Q&A)
- [ ] Copy meeting ID, passcode, and join URL
- [ ] Populate Email 3 template with these details
- [ ] Send test invite to yourself
- [ ] Confirm audio/video working

For **Pre-Kickoff Sync (2026-08-16, time TBD)**:
- [ ] Create Zoom meeting (30 min)
- [ ] Same settings as above
- [ ] Secure join URL and share in Email 1 (announce in Email 2)

---

## Email Template Location & Verification

**Main file:** `/Users/junkawasaki/github/com-junkawasaki/90-docs/gates/team-notifications-email-templates.md`

**Before each send, verify:**
- [ ] No [TBD] (except parametric [N] for recurring emails)
- [ ] All contact emails are correct
- [ ] URLs are live and accessible
- [ ] Dates are accurate
- [ ] Sender name/signature is correct
- [ ] BCC list includes all recipients (to prevent reply-all storms)

---

## Weekly Email Rhythm (Weeks 11–16)

### Every Monday 16:00 JST (Email 5: Weekly Status)
1. Attend Monday 09:00 progress review meeting
2. Collect metrics snapshot from Grafana dashboard
3. Gather status updates from team leads (M5-Core, M5-Integration, M6-Platform)
4. Fill in Email 5 template with:
   - [N] = current week number
   - Current metric values
   - Blocker list
   - Escalation status
5. Send before 16:00 JST

### Every Friday 16:30 JST (Email 6: Gate Decision)
1. Attend Friday 16:00 gate review meeting
2. Capture Go/No-Go decision and rationale
3. Collect metrics snapshot
4. Fill in Email 6 template with:
   - [N] = current week number
   - Decision status (GO / NO-GO / HOLD)
   - Completed items
   - Next week critical path
   - Active escalations
5. Send by 16:30 JST

---

## Escalation Protocol Quick Reference

**Level 1 Response (Team Leads):** 1 hour SLA  
→ When: Blocker identified during progress review  
→ Action: Triage + initial mitigation  
→ Escalate to L2 if: Cannot resolve within 1h

**Level 2 Response (Phase Owners):** 2 hour SLA  
→ When: L1 escalates  
→ Action: Technical decision authority  
→ Escalate to L3 if: Decision impacts schedule/scope

**Level 3 Response (Platform Lead):** 4 hour SLA  
→ When: L2 escalates (major decision)  
→ Action: Final decision authority + approval  
→ Document in: escalation-ledger.edn

**Logging:** All escalations must be logged in `/Users/junkawasaki/github/com-junkawasaki/90-docs/gates/wave5-escalation-ledger.edn` (append-only format)

---

## Key Document Locations

| Document | Path | Purpose |
|----------|------|---------|
| Email templates | `team-notifications-email-templates.md` | 6 email templates |
| Escalation protocol | `escalation-protocol-reference.edn` | L1/L2/L3 SLA rules |
| Weekly checkpoint | `weekly-checkpoint-structure.edn` | Mon/Fri meeting format |
| Kickoff slides | `kickoff-meeting-slides.md` | Agenda for 2026-08-18 |
| Metrics pipeline | `metrics-collection-pipeline.edn` | Prometheus/Grafana setup |
| Onboarding | `team-onboarding-checklist.md` | Team member prep |
| Board templates | `weekly-execution-boards-template.edn` | Trello/Jira setup |
| Decision rules | `decision-rule-and-escalation-summary.md` | Go/No-Go criteria |
| Calendar guide | `calendar-setup-guide.md` | Recurring meeting setup |
| Readiness check | `FINAL-DISTRIBUTION-READINESS-CHECK-2026-07-21.md` | Pre-send validation |
| Exec log | `DISTRIBUTION-EXECUTION-LOG-2026-07-21.edn` | Activation log (append-only) |
| This card | `EXECUTION-LEAD-QUICK-REFERENCE-2026-07-21.md` | Quick reference |

All files in: `/Users/junkawasaki/github/com-junkawasaki/90-docs/gates/`

---

## Slack Channel Setup (by 2026-07-31)

**Channel Name:** `#prod-gates-wave5`

**Members to invite:**
- Team Lead M5-Core
- Team Lead M5-Integration
- Team Lead M6-Platform
- Phase Owner M5
- Phase Owner M6
- Platform Lead
- Metrics Lead
- Escalation Lead
- (Optional: owner Jun Kawasaki for visibility)

**Pins (top of channel):**
1. Escalation protocol (escalation-protocol-reference.edn link)
2. Weekly schedule (calendar + dates)
3. Decision rules (decision-rule-and-escalation-summary.md link)
4. Grafana dashboard (https://dashboard.internal/wave5-m5m6)
5. Archive link (90-docs/gates/weekly-reports/wave5-m5m6/)

**First message (by 2026-08-01):**
```
🎯 Welcome to Wave 5 M5–M6 Production Gates

This channel coordinates the 6-week M5–M6 production gates execution (Weeks 11–16).

📋 **Schedule**
- Kickoff: 2026-08-18 09:00 AM JST (Zoom link coming 2026-08-15)
- Weekly Progress Review: Mondays 09:00 AM
- Weekly Gate Decision: Fridays 16:00 (4 PM)

📚 **Key Documents** (pinned above)
- Escalation protocol (L1/2/3 SLA rules)
- Weekly checkpoint structure
- Decision rules (Go/No-Go criteria)

🚨 **Escalation Path**
P0 blocker? → Message here + notify escalation-lead@gftd.group (2h SLA)

📞 **Contacts**
- Execution Lead: platform-lead@gftd.group
- Metrics Lead: metrics-lead@gftd.group
- Escalation Lead: escalation-lead@gftd.group

Let's go build something great! 🚀
```

---

## If Something Goes Wrong

### Email fails to send
1. Check SMTP credentials
2. Verify distribution list (no bad email addresses)
3. Test with single recipient first
4. Check email client error logs
5. Escalate to: platform-lead@gftd.group + metrics-lead@gftd.group

### Zoom link unavailable by 2026-08-10
1. Check Zoom account active + license valid
2. Try creating new meeting
3. If Zoom unavailable: notify platform-lead ASAP
4. Fallback: Use Google Meet or alternative

### Team member can't access Slack/Jira/Trello
1. Check user invited + email matches
2. Request system admin to grant access
3. Notify in Email 1 / Email 2: "Reply if access fails"
4. Escalate: metrics-lead@gftd.group

### Kickoff attendance low
1. Send reminder 24h before (Email 3 already includes this)
2. Check calendar invites actually sent + accepted
3. Confirm Zoom link in attendees' inboxes
4. 2h before: send Zoom link in Slack #prod-gates-wave5

### Metrics not flowing to Grafana by 2026-08-09
1. Check Prometheus collector running
2. Check CI workflow (gate-metrics-collector.yml) enabled
3. Verify baseline metrics collected (target: 5+ days data)
4. Escalate to: metrics-lead@gftd.group

---

## Success Criteria

**Distribution Timeline Success = By 2026-08-18:**
- ✅ Email 1 sent (2026-08-01)
- ✅ Email 2 sent (2026-08-09)
- ✅ Email 3 sent (2026-08-15)
- ✅ Email 4 sent (2026-08-18)
- ✅ Kickoff meeting held (2026-08-18, 09:00 JST)
- ✅ Unanimous consensus (thumbs-up poll)
- ✅ All team members have tool access (Slack, Jira, Trello)
- ✅ Calendar invites sent for recurring meetings
- ✅ First checkpoint scheduled (Monday 2026-08-19)
- ✅ No distribution failures or escalations

**Ongoing Execution Success = Weeks 11–16:**
- ✅ Email 5 sent every Monday 16:00 (6/6 sent)
- ✅ Email 6 sent every Friday 16:30 (6/6 sent)
- ✅ All gate reviews completed on schedule
- ✅ Escalation protocol followed (SLAs met)
- ✅ Metrics pipeline health maintained
- ✅ No critical failures due to process/comms

---

**Print this card. Keep it handy. Reference it before each email send.**

Good luck. You've got this. 🚀

---

*Generated by Claude Code Agent*  
*Activation prepared 2026-07-21*  
*Archive: 90-docs/gates/EXECUTION-LEAD-QUICK-REFERENCE-2026-07-21.md*
