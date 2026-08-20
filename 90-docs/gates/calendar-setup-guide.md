# Wave 5 M5–M6 Production Gates — Calendar Setup Guide

**Status:** Ready for calendar admin + team leads to execute starting 2026-08-15  
**Target Completion:** By 2026-08-18 (kickoff day)

---

## Overview

This guide walks team leads and calendar administrators through creating recurring calendar invites for the Wave 5 M5–M6 production gates execution.

**Two recurring meetings:**
1. **Monday 09:00 — Weekly Progress Review** (30 min, repeats for 6 weeks)
2. **Friday 16:00 — Weekly Gate Review & Decision** (45 min, repeats for 6 weeks)

Both run from Week 11 (2026-08-18) through Week 16 (2026-09-29).

---

## Meeting 1: Weekly Progress Review

### Calendar Invite Details

| Field | Value |
|-------|-------|
| **Meeting Title** | Weekly Progress Review — Wave 5 M5–M6 Gates (Week [N]) |
| **Date** | Mondays, starting 2026-08-19 |
| **Time** | 09:00 AM – 09:30 AM JST |
| **Recurrence** | Weekly, every Monday, 6 weeks (ends 2026-09-22) |
| **Duration** | 30 minutes |
| **Location** | Zoom: [TBD — add link here] OR In-person: [Conference Room TBD] |
| **Attendees** | All team leads + Metrics lead (see list below) |
| **Video Conference** | Zoom (link in description) |
| **Description** | See below |

### Attendee List (Mandatory)

- Team Lead M5-Core
- Team Lead M5-Integration
- Team Lead M6-Platform
- Metrics Lead
- Execution Lead (optional, monitoring)

### Meeting Description

```
📋 WEEKLY PROGRESS REVIEW — WAVE 5 M5–M6 GATES

This is the Monday morning checkpoint for Week [N] of M5–M6 production gates execution.

DURATION: 30 minutes
FOCUS: Triage, status update, metrics health, blocker identification (NO decisions made here)

AGENDA:
─────────────────────
(5 min)  Metrics Health Check — review 5-metric dashboard (build-times, coverage, deploy-success, blocker-detection, velocity)
(10 min) Team Progress Review — M5-Core, M5-Integration, M6-Platform status updates (what's complete, what's in progress, what's at risk)
(10 min) Blocker Triage — identify new P1/P2 blockers, escalation needed?
(5 min)  Velocity Trending — are we on track to complete critical path by Friday?

OUTCOMES:
──────────
- Updated Trello board (move completed cards to "Done" list)
- Updated blocker list (Trello + Jira)
- Slack summary posted to #prod-gates-wave5 (by 10:00 AM)
- Forecast for Friday gate review

MATERIALS:
───────────
- Grafana dashboard: https://dashboard.internal/wave5-m5m6
- Trello boards: trello-m5-[role]-wave5
- Jira projects: M5W5, M5IW5, M6W5
- Escalation protocol: https://github.com/com-junkawasaki/root/blob/main/90-docs/gates/escalation-protocol-reference.edn

DECISION RULE: None (Monday is triage only, not decision)
NEXT MEETING: Friday 16:00 (this week's gate decision)

Questions? Reply to this invite or ping #prod-gates-wave5
```

### How to Add to Calendar (Google Calendar / Outlook)

#### Option A: Google Calendar (Admin or Team Lead)

1. Go to Google Calendar → "+ Create" button
2. Fill in:
   - **Title:** Weekly Progress Review — Wave 5 M5–M6 Gates
   - **Date:** Monday, 2026-08-19
   - **Time:** 09:00 AM – 09:30 AM
   - **Timezone:** JST (Asia/Tokyo)
   - **Guests:** [Paste attendee emails, separated by commas]
   - **Description:** [Paste description from above]

3. Click "Does not repeat" → select "Custom" → set up recurrence:
   - **Repeats:** Weekly
   - **Every:** 1 week
   - **On:** Monday
   - **End date:** Friday, 2026-09-22 (or Week 16 end)

4. Add video conference: "Google Meet" or Zoom link (if using Zoom, paste in description)

5. Click "Save" and "Send to guests"

#### Option B: Microsoft Outlook (Admin or Team Lead)

1. Go to Outlook Calendar → New Event
2. Fill in:
   - **Title:** Weekly Progress Review — Wave 5 M5–M6 Gates
   - **Date & Time:** Monday 09:00 AM – 09:30 AM (JST)
   - **Recurrence:** Weekly, every Monday, for 6 weeks (set end date: 2026-09-22)
   - **Location/Video:** [Zoom link or Teams meeting link]

3. Add attendees (invite all email addresses)
4. Add description from above
5. Click "Send"

#### Option C: Manual Calendar Invites (For teams using other tools)

Send individual .ics files:

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Wave 5 M5-M6 Gates//EN
BEGIN:VEVENT
UID:weekly-progress-review-week-[N]@gftd.group
DTSTART:20260819T000000Z
DTEND:20260819T003000Z
RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=6
SUMMARY:Weekly Progress Review — Wave 5 M5–M6 Gates (Week [N])
DESCRIPTION:[Paste description from above]
ORGANIZER:claude@code.ai
ATTENDEE;ROLE=REQ-PARTICIPANT:team-lead-m5-core@gftd.group
ATTENDEE;ROLE=REQ-PARTICIPANT:team-lead-m5-integration@gftd.group
ATTENDEE;ROLE=REQ-PARTICIPANT:team-lead-m6-platform@gftd.group
ATTENDEE;ROLE=REQ-PARTICIPANT:metrics-lead@gftd.group
END:VEVENT
END:VCALENDAR
```

---

## Meeting 2: Weekly Gate Review & Decision

### Calendar Invite Details

| Field | Value |
|-------|-------|
| **Meeting Title** | Weekly Gate Review & Decision — Wave 5 M5–M6 (Week [N]) |
| **Date** | Fridays, starting 2026-08-22 |
| **Time** | 16:00 (4 PM) – 16:45 (4:45 PM) JST |
| **Recurrence** | Weekly, every Friday, 6 weeks (ends 2026-09-29) |
| **Duration** | 45 minutes |
| **Location** | Zoom: [TBD] OR In-person: [Conference Room TBD] |
| **Attendees** | Team leads (3) + Phase owners (2) + Platform lead (1) + Metrics lead (1) |
| **Video Conference** | Zoom (link in description) |
| **Description** | See below |

### Attendee List (Mandatory)

- Team Lead M5-Core
- Team Lead M5-Integration
- Team Lead M6-Platform
- Phase Owner M5
- Phase Owner M6
- Platform Lead
- Metrics Lead
- Execution Lead (optional, monitoring)

### Meeting Description

```
🎯 WEEKLY GATE REVIEW & DECISION — WAVE 5 M5–M6 GATES

This is the Friday afternoon checkpoint for Week [N]. We make the Go/No-Go decision for the week.

DURATION: 45 minutes
FOCUS: Gate readiness assessment, metrics review, escalation summary, consensus decision (Go/No-Go)
DECISION RULE: Consensus (all team leads must agree)

AGENDA:
────────────────────
(10 min) Gate Readiness Check — critical path % complete per team (M5-Core, M5-Integration, M6-Platform)
(10 min) Critical Path Verification — dependencies confirmed? All items on track?
(5 min)  Cross-Team Dependency Check — M5 → M6 handoff ready? Platform infra stable?
(5 min)  Escalation Summary — are there unresolved P1 blockers? SLA status?
(5 min)  Gate Decision — consensus check: all teams Go? [Go/No-Go/Hold]
(10 min) Post-Gate Logistics — archive Trello cards, snapshot metrics, announce decision

OUTCOMES:
──────────
- Gate Decision Memo (Jira issue type: gate-readiness-check) → Go/No-Go with rationale
- Weekly Metrics Snapshot (Jira issue type: weekly-metrics-snapshot) → 5-metric values + trending
- Escalation Summary (Jira issue type: blocker-escalation, if any) → active blockers + SLA status
- Next Week Critical Path (Trello cards created for Week [N+1])
- Slack Notification → #prod-gates-wave5 (decision + KPI summary, by 17:00)
- Email Notification → gates-escalation@gftd.group (decision memo, by 17:00)

MATERIALS:
──────────
- Grafana dashboard: https://dashboard.internal/wave5-m5m6
- Trello boards: trello-m5-[role]-wave5
- Jira projects: M5W5, M5IW5, M6W5
- Escalation protocol: https://github.com/com-junkawasaki/root/blob/main/90-docs/gates/escalation-protocol-reference.edn
- Weekly reports: https://github.com/com-junkawasaki/root/tree/main/90-docs/gates/weekly-reports/wave5-m5m6

DECISION RULE:
───────────────
"Does your team agree we are Go for this week's gate?"

✅ All team leads must answer YES (or escalate their blocker to phase owner for resolution)
❌ If any team lead says NO, we do NOT go — instead, we escalate the blocker

CONSENSUS CONFIRMATION:
As meeting closes, all attendees thumbs-up in chat (unanimous agreement) or 🚫 if escalation needed.

NEXT MEETING: Monday 09:00 next week (Week [N+1] progress review)

Questions? Reply to this invite or ping #prod-gates-wave5
```

### How to Add to Calendar

#### Option A: Google Calendar

1. Go to Google Calendar → "+ Create" button
2. Fill in:
   - **Title:** Weekly Gate Review & Decision — Wave 5 M5–M6 Gates
   - **Date:** Friday, 2026-08-22
   - **Time:** 16:00 (4 PM) – 16:45 (4:45 PM) JST
   - **Timezone:** JST (Asia/Tokyo)
   - **Guests:** [Paste all attendee emails]
   - **Description:** [Paste description from above]

3. Click "Does not repeat" → select "Custom":
   - **Repeats:** Weekly
   - **Every:** 1 week
   - **On:** Friday
   - **End date:** Monday, 2026-09-29 (or later, Week 16+ end)

4. Add video conference: "Google Meet" or Zoom
5. Click "Save" and "Send to guests"

#### Option B: Microsoft Outlook

1. Go to Outlook Calendar → New Event
2. Fill in:
   - **Title:** Weekly Gate Review & Decision — Wave 5 M5–M6 Gates
   - **Date & Time:** Friday 16:00 (4 PM) – 16:45 (4:45 PM) JST
   - **Recurrence:** Weekly, every Friday, for 6 weeks (end: 2026-09-29)
   - **Location/Video:** [Zoom link or Teams]

3. Add attendees (all email addresses)
4. Add description
5. Click "Send"

#### Option C: .ics File Template

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Wave 5 M5-M6 Gates//EN
BEGIN:VEVENT
UID:weekly-gate-review-week-[N]@gftd.group
DTSTART:20260822T070000Z
DTEND:20260822T074500Z
RRULE:FREQ=WEEKLY;BYDAY=FR;COUNT=6
SUMMARY:Weekly Gate Review & Decision — Wave 5 M5–M6 (Week [N])
DESCRIPTION:[Paste description from above]
ORGANIZER:claude@code.ai
ATTENDEE;ROLE=REQ-PARTICIPANT:team-lead-m5-core@gftd.group
ATTENDEE;ROLE=REQ-PARTICIPANT:team-lead-m5-integration@gftd.group
ATTENDEE;ROLE=REQ-PARTICIPANT:team-lead-m6-platform@gftd.group
ATTENDEE;ROLE=REQ-PARTICIPANT:phase-owner-m5@gftd.group
ATTENDEE;ROLE=REQ-PARTICIPANT:phase-owner-m6@gftd.group
ATTENDEE;ROLE=REQ-PARTICIPANT:platform-lead@gftd.group
ATTENDEE;ROLE=REQ-PARTICIPANT:metrics-lead@gftd.group
END:VEVENT
END:VCALENDAR
```

---

## Calendar Administration Checklist

### Step 1: Create Calendar Invites (Due 2026-08-15)

- [ ] **Monday 09:00 meeting (Progress Review):**
  - [ ] Create recurring invite (6 weeks, starting 2026-08-19)
  - [ ] Add 5 attendees (M5-Core lead, M5-Integration lead, M6-Platform lead, Metrics lead, Execution lead)
  - [ ] Add Zoom link or conference room location
  - [ ] Send invites to all attendees

- [ ] **Friday 16:00 meeting (Gate Review):**
  - [ ] Create recurring invite (6 weeks, starting 2026-08-22)
  - [ ] Add 8 attendees (3 team leads + 2 phase owners + platform lead + metrics lead + execution lead)
  - [ ] Add Zoom link or conference room location
  - [ ] Send invites to all attendees

### Step 2: Verify Calendar Blocks (Due 2026-08-17)

- [ ] All team leads have accepted Monday 09:00 invites
- [ ] All attendees have accepted Friday 16:00 invites
- [ ] Zoom links are active and working
- [ ] Video conference details are in all calendar invites
- [ ] Time zones are correctly set to JST (Asia/Tokyo)

### Step 3: Confirm with Stakeholders (Due 2026-08-18)

- [ ] Email team leads: confirm calendar invites received and on their calendars
- [ ] Email all attendees: links, timing, day-of instructions
- [ ] Set up backup video conference (in case primary fails)
- [ ] Share Zoom room recording settings (password, recording auto-start, archive location)

### Step 4: Archive & Documentation (Ongoing)

- [ ] Save .ics files in `90-docs/gates/calendar-invites/` for future reference
- [ ] Document Zoom meeting IDs and passwords (secure location, not in git)
- [ ] Set up calendar reminders for organizer (15 min before each meeting)

---

## Alternative: Manual Email Invites (If No Calendar Admin)

If using email instead of calendar tool, send an email with these details:

**Subject:** 📅 Recurring Calendar Invite: Wave 5 M5–M6 Gates — Weekly Meetings

**Body:**

```
Hi team,

Please add these to your calendars (recurring for 6 weeks):

MEETING 1: Weekly Progress Review
────────────────────────────────
📅 Every Monday, 2026-08-19 through 2026-09-22
⏰ 09:00 AM – 09:30 AM JST
📍 Zoom: [LINK] (or Conference Room TBD)
👥 Attendees: M5-Core lead, M5-Integration lead, M6-Platform lead, Metrics lead

Description: Triage + status + blocker identification (no decisions)

MEETING 2: Weekly Gate Review & Decision
──────────────────────────────────────
📅 Every Friday, 2026-08-22 through 2026-09-29
⏰ 16:00 (4 PM) – 16:45 (4:45 PM) JST
📍 Zoom: [LINK] (or Conference Room TBD)
👥 Attendees: Team leads (3) + Phase owners (2) + Platform lead + Metrics lead

Description: Gate readiness + decision (Go/No-Go, consensus rule)

All details in calendar invites (or reply to this email with any questions).

See you at the kickoff on 2026-08-18!

—
Execution Lead
```

---

## Timezone Conversion Reference

| Region | Time | UTC Offset |
|--------|------|-----------|
| **JST (Tokyo)** | 09:00 AM / 16:00 PM | UTC+9 |
| **IST (India)** | 05:30 AM / 12:30 PM | UTC+5:30 |
| **CET (Europe)** | 01:00 AM / 08:00 AM | UTC+1 |
| **EST (New York)** | 20:00 (prev day) / 03:00 AM | UTC-5 |
| **PST (LA)** | 17:00 (prev day) / 00:00 AM | UTC-8 |

**Tip:** Use [https://www.timeanddate.com/meeting](https://www.timeanddate.com/meeting) to find best time for distributed team.

---

## Cancellation & Rescheduling Policy

### If You Need to Cancel a Meeting

**Notice Required:** Minimum 24 hours before meeting

**Process:**
1. Notify Execution Lead immediately (email + Slack DM)
2. Reschedule for same day if critical (e.g., move Monday 09:00 to Monday 11:00)
3. Notify all attendees in calendar update + Slack #prod-gates-wave5
4. Document cancellation reason in escalation ledger

### If You Need to Reschedule Due to Conflict

**Notice Required:** By 2026-08-17 (before kickoff)

**Options:**
1. Propose alternative time (must work for all 8+ attendees)
2. Escalate to Platform Lead if no time works
3. Make meeting hybrid (some in-person, some async) if feasible

**Note:** Gate decisions must happen by Friday 16:00. If Friday meeting is rescheduled, it must be by Friday EOD.

---

## Post-Meeting Logistics

### Recording & Archive

- **Recording location:** Slack thread in #prod-gates-wave5
- **Archive location:** 90-docs/gates/weekly-reports/wave5-m5m6/week-[N].md
- **Retention:** 1 year

### Meeting Notes Distribution

After each meeting:
1. Metrics Lead posts summary to #prod-gates-wave5 (within 1h)
2. Execution Lead sends email to gates-escalation@gftd.group (within 2h)
3. Jira issues auto-created for decision memo + metrics snapshot (Friday only)

---

## FAQs

### Q: Can I attend async (recorded video)?
**A:** Monday OK (non-decision). Friday requires live attendance (consensus decision).

### Q: What if I have a conflict?
**A:** Contact Execution Lead immediately. We can't reschedule Friday gate (SLA deadline), but we can find alternatives for Monday if needed.

### Q: Do we meet on holidays?
**A:** No. If Monday or Friday falls on a holiday, we reschedule to the next available weekday (notify all attendees 48h in advance).

### Q: Can I invite others?
**A:** Only if escalation requires it (e.g., escalation lead joins Friday if P1 blockers). Otherwise, keep meetings to core attendee list for efficiency.

### Q: What if Zoom fails?
**A:** Dial-in number provided in calendar invite. If both fail, we reschedule within 2 hours (email all attendees).

---

**Generated:** 2026-07-21  
**Status:** Ready for calendar admin to execute by 2026-08-15  
**Next Review:** 2026-08-18 (post-kickoff, confirm all meetings active)
