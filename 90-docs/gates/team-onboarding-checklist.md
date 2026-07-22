# Wave 5 M5–M6 Production Gates — Team Onboarding Checklist

**For: Team Leads, Phase Owners, Platform Lead, Metrics Lead**

**Status:** Ready for distribution starting 2026-08-01  
**Completion Deadline:** 2026-08-17 (1 day before kickoff)

---

## Overview

This checklist ensures every stakeholder is ready for the 2026-08-18 kickoff. Complete all items by 2026-08-17 EOD.

**Estimated Time:** 3–4 hours per team lead (spread across 2 weeks)

---

## Phase 1: Foundation (Week of 2026-08-05)

### 1. Receive Kickoff Invitation Email

- [ ] **Date:** ~2026-08-01
- [ ] **From:** claude@code.ai
- [ ] **Action:** Read kickoff invitation (Email #1 from team-notifications-email-templates.md)
- [ ] **Confirm:** Your email + Slack handle match the attendee list
- [ ] **Confirm:** You can attend 2026-08-18 09:00 AM JST (60 min)
- [ ] **Confirm:** Calendar conflicts for Monday 09:00 + Friday 16:00 (recurring, 6 weeks)?
  - If conflict: reply ASAP to find alternative time
- [ ] **Save:** Kickoff invitation in calendar (even if no formal invite sent yet)

### 2. Read Background Documentation (Read in This Order)

#### A. Execution Kickoff Report
- [ ] **Document:** 90-docs/gates/EXECUTION-KICKOFF-REPORT-WAVE5-M5M6.md
- [ ] **Time:** 15–20 min
- [ ] **What to learn:** 6-week overview, infrastructure, metrics pipeline, escalation protocol
- [ ] **Key takeaway:** "I understand we're running structured gates for 6 weeks with weekly checkpoints"

#### B. Activation Status
- [ ] **Document:** 90-docs/gates/ACTIVATION-STATUS-WAVE5.md
- [ ] **Time:** 10–15 min
- [ ] **What to learn:** What's ready, what's not, critical path to kickoff
- [ ] **Key takeaway:** "I know which infrastructure is live and what's staging"

#### C. Escalation Protocol (CRITICAL)
- [ ] **Document:** 90-docs/gates/escalation-protocol-reference.edn
- [ ] **Time:** 15 min (skim for structure, read 3-level tree carefully)
- [ ] **What to learn:** L1/L2/L3 responsibilities, SLA targets, alert channels
- [ ] **Key takeaway:** "When an alert fires, I know who responds and within what timeframe"

#### D. Weekly Checkpoint Structure
- [ ] **Document:** 90-docs/gates/weekly-checkpoint-structure.edn
- [ ] **Time:** 10 min (skim)
- [ ] **What to learn:** Monday vs. Friday meeting rhythm, artifacts, templates
- [ ] **Key takeaway:** "Monday is triage, Friday is decision. I know what each meeting outputs"

#### E. Decision Rule & Escalation Summary
- [ ] **Document:** 90-docs/gates/decision-rule-and-escalation-summary.md
- [ ] **Time:** 10 min (quick reference, bookmark it)
- [ ] **What to learn:** Gate decision rule (consensus), escalation tree, scenarios
- [ ] **Key takeaway:** "Consensus = all teams say YES. Any team says NO = escalate blocker, not gate"

**Reading Summary:**
- Total time: ~1 hour
- Skip: `.edn` files (EDN notation might look unfamiliar; just skim structure)
- Focus on: Architecture, decision rules, SLAs, alert channels
- Bookmark: decision-rule-and-escalation-summary.md (carry this mentally into kickoff)

### 3. Confirm Calendar Availability

- [ ] **Monday 09:00 AM (JST):** Can you attend 30 min weekly progress review?
  - If YES: Block calendar now
  - If NO: Reply to execution-lead@code.ai immediately with conflicts

- [ ] **Friday 16:00 (4 PM JST):** Can you attend 45 min weekly gate review?
  - If YES: Block calendar now
  - If NO: Reply to execution-lead@code.ai immediately with conflicts

- [ ] **Note:** Both meetings repeat for 6 weeks (2026-08-19 through 2026-09-29)

- [ ] **Zoom or in-person?** Confirm your preferred location
  - Zoom link will be sent ~2026-08-10
  - In-person location: TBD (check with team coordinator)

---

## Phase 2: Tool Access (Week of 2026-08-05 to 2026-08-15)

### 4. Confirm Trello Board Access

- [ ] **Board Name:** `trello-m5-[core|integration|platform]-wave5`
- [ ] **Action:** Go to trello.com, search for board by name
- [ ] **Expected Result:** You can view + edit the board
- [ ] **If BLOCKED:** Email metrics-lead@gftd.group immediately with error
- [ ] **Expected board structure:**
  - 🎯 Week N Critical Path
  - 🚨 Blockers & Risks
  - 📊 Metrics This Week
  - ✅ Done (This Week)
- [ ] **First action:** Take a screenshot of the board (familiarize yourself)

### 5. Confirm Jira Project Access

- [ ] **Project Key:**
  - M5-Core team: `M5W5`
  - M5-Integration team: `M5IW5`
  - M6-Platform team: `M6W5`

- [ ] **Action:** Go to jira.gftd.group, navigate to project by key
- [ ] **Expected Result:** You can view + create issues
- [ ] **If BLOCKED:** Email metrics-lead@gftd.group with error

- [ ] **Test:** Create a dummy issue to verify permissions
  - Type: `gate-readiness-check`
  - Title: "Test: [Your Name]"
  - Description: "Testing Jira access for wave5 gates"
  - Status: Set to "Open" → "In Progress"
  - **Delete this test issue after verifying it works**

- [ ] **Workflow states you'll see:**
  - Open → In Progress → Blocked → Ready for Gate → Gate Approved → Done

### 6. Confirm Slack Channel Access

- [ ] **Channel:** `#prod-gates-wave5`
- [ ] **Action:** Go to Slack, search for channel by name
- [ ] **Expected Result:** You can view + post messages
- [ ] **If BLOCKED:** Email metrics-lead@gftd.group with error

- [ ] **First action:** Mute notifications (you'll get A LOT of alerts during gate weeks)
  - Click channel name → Notification settings
  - Set to: "Only mentions + direct messages" OR "Muted"

- [ ] **Watch for:** Alert messages during Week 11 (all metrics alerts go here first)

### 7. Confirm Access Summary

- [ ] **By 2026-08-15:** All three access points confirmed (Trello, Jira, Slack)
- [ ] **If ANY are blocked:** Report to metrics-lead@gftd.group TODAY
- [ ] **Contingency:** If access is delayed, you can still participate in kickoff (share screen with someone who has access)

---

## Phase 3: Pre-Kickoff Training (Week of 2026-08-10 to 2026-08-15)

### 8. Review Decision Rule (Critical)

- [ ] **Document:** 90-docs/gates/decision-rule-and-escalation-summary.md (read again, more carefully)
- [ ] **Focus on:** "Gate Decision Rule" section (top of document)
- [ ] **Memorize:** 
  - ✅ All teams say YES = GO
  - ❌ Any team says NO = Escalate blocker (not gate)
  - ⏳ If blocked = Phase owner decides in 2h, Platform lead in 4h
- [ ] **Practice:** Ask yourself: "If my team says No-Go on Friday 16:00, what do I do?"
  - Answer: "Escalate the specific blocker to phase owner, let them decide gate impact"
- [ ] **Do NOT:** Vote on No-Go. Escalate blocker.

### 9. Review Escalation Protocol (Critical)

- [ ] **Document:** 90-docs/gates/escalation-protocol-reference.edn (read 3-level tree section)
- [ ] **Memorize SLAs:**
  - L1 (you): 1 hour to respond or escalate
  - L2 (phase owner): 2 hours to respond or escalate
  - L3 (platform lead): 4 hours to make final decision
- [ ] **Alert channels:** Slack #prod-gates-wave5 (primary), email, PagerDuty (if critical)
- [ ] **Practice scenario:** "An alert fires at 14:00 on Wednesday. What do I do?"
  - Answer: "Read alert in Slack. Triage within 15 min. If fixable in 30 min, fix it. If not, escalate to phase owner by 15:00."
- [ ] **Do NOT:** Wait past your SLA. Escalate proactively.

### 10. Review Weekly Checkpoint Rhythm

- [ ] **Monday 09:00:** Triage + status. NO DECISIONS.
  - Metrics check, team progress, blocker list, velocity forecast
  - Duration: 30 min
  - Output: Jira notes + Slack update
  - Your role: Report team status (2 min) + answer blocker questions

- [ ] **Friday 16:00:** Gate readiness + consensus decision.
  - Critical path check, metrics review, escalation summary, Go/No-Go consensus
  - Duration: 45 min
  - Output: Gate decision memo + metrics snapshot + next week prep
  - Your role: Report team readiness + vote (consensus rule)

- [ ] **Remember:** No decisions Monday. Friday = final gate decision.

### 11. Attend Pre-Kickoff Sync Call (2026-08-16)

- [ ] **Date:** Friday 2026-08-16 (TBD time, ~30 min)
- [ ] **Purpose:** Q&A on escalation, dry-run scenario, tool check
- [ ] **Attendance:** Optional but HIGHLY RECOMMENDED
- [ ] **Agenda:**
  - Quick Q&A on escalation protocol
  - Live simulation: blocker escalation scenario
  - Tool access verification
  - Address last concerns
- [ ] **Calendar invite:** Will be sent ~2026-08-10

### 12. Print & Bookmark Key Documents

- [ ] **Print & carry with you:**
  - 90-docs/gates/decision-rule-and-escalation-summary.md (1 page, single-sided)
  - Escalation SLA quick reference (cheat sheet, at end of that document)

- [ ] **Bookmark in browser:**
  - Grafana dashboard: https://dashboard.internal/wave5-m5m6
  - Escalation protocol: 90-docs/gates/escalation-protocol-reference.edn
  - Weekly checkpoint template: 90-docs/gates/weekly-checkpoint-structure.edn

- [ ] **Bookmark in Jira:**
  - Your team's project (M5W5, M5IW5, or M6W5)
  - Search: "gate-readiness-check" (gate decision memo template)

- [ ] **Bookmark in Slack:**
  - #prod-gates-wave5 channel (save as favorite)

---

## Phase 4: Final Preparation (2026-08-17 and 2026-08-18)

### 13. Receive Calendar Invites & Zoom Link

- [ ] **Expected by:** 2026-08-15 (calendar) / 2026-08-10 (Zoom link)
- [ ] **Calendar invites:** 2 recurring meetings (Mon 09:00 + Fri 16:00)
- [ ] **Action:** Accept calendar invites
- [ ] **Zoom link:** Should be in calendar invite or Slack #prod-gates-wave5
- [ ] **Test:** Click Zoom link on 2026-08-18 by 08:55 AM
  - Audio/video working?
  - Name displays correctly?
  - Can you see screen sharing?

### 14. Receive Kickoff Reminder Email

- [ ] **Expected by:** 2026-08-15
- [ ] **From:** claude@code.ai (Email #3 from team-notifications-email-templates.md)
- [ ] **What to do:** Confirm you have:
  - [ ] Trello board access ✅
  - [ ] Jira project access ✅
  - [ ] Slack #prod-gates-wave5 access ✅
  - [ ] Escalation protocol read ✅
  - [ ] Calendar blocked (Mon 09:00 + Fri 16:00) ✅
- [ ] **If ANYTHING missing:** Reply immediately

### 15. Day-Before Prep (2026-08-17)

- [ ] **Review:** Decision rule (1 min read)
- [ ] **Skim:** Escalation protocol (SLA section only, 2 min)
- [ ] **Prepare:** Bring printed "decision rule" document to kickoff (if in-person)
- [ ] **Confirm:** Zoom link works (if remote)
- [ ] **Block time:** 2026-08-18, 09:00–10:10 AM (60 min kickoff + 10 min tool help)
- [ ] **No urgent meetings:** 2 hours after kickoff ends (should be free)

### 16. Morning Of (2026-08-18)

- [ ] **Wake up on time 😅**
- [ ] **Join Zoom:** 08:55 AM (5 min early)
  - Test audio/video again
  - No distractions nearby
  - Have water/coffee ready
- [ ] **Bring:**
  - Printed decision rule document (if in-person)
  - Pen + notebook (for notes)
  - Jira/Slack open on second screen (to follow along)
- [ ] **Mindset:** This is a working session. Bring questions. Expect to participate actively.

---

## Knowledge Check (by 2026-08-17)

**Before attending kickoff, verify you can answer these:**

### Q1: Gate Decision Rule
**Question:** Your team is ready to go, but M5-Core says they have a blocker. What do you do?
- [ ] A. Vote No-Go for the entire gate ❌
- [ ] B. Escalate their blocker to phase owner, then continue with gate consensus ✅
- [ ] C. Wait until their blocker is fixed ❌

**Correct answer:** B. Escalate blocker, not gate.

### Q2: Escalation SLAs
**Question:** An alert fires at 14:00 on Wednesday. You're L1 (team lead). What's your deadline to escalate if you can't fix?
- [ ] A. 14:30 (30 min) ❌
- [ ] B. 15:00 (1 hour) ✅
- [ ] C. 16:00 (2 hours) ❌

**Correct answer:** B. L1 SLA is 1 hour.

### Q3: Weekly Rhythm
**Question:** What's the purpose of Monday 09:00 checkpoint?
- [ ] A. Make Go/No-Go decision ❌
- [ ] B. Triage + status update (decision happens Friday) ✅
- [ ] C. Resolve all blockers ❌

**Correct answer:** B. Monday is triage only. Friday is decision.

### Q4: Alert Channels
**Question:** A P1 blocker (2h SLA) fires on Tuesday. Where does it go?
- [ ] A. Slack #prod-gates-wave5 + Email gates-escalation@gftd.group ✅
- [ ] B. PagerDuty only ❌
- [ ] C. DM your phase owner ❌

**Correct answer:** A. Slack + email for P1 escalations.

### Q5: Consensus
**Question:** Friday 16:00 gate review. 2 teams say Go, 1 team says No-Go. What happens?
- [ ] A. Majority wins, gate is Go ❌
- [ ] B. Escalate the No-Go team's blocker to phase owner ✅
- [ ] C. Gate is held until all agree ❌

**Correct answer:** B. Escalate blocker, don't halt entire gate.

---

## Knowledge Check Answer Key

| Q | Expected Answer | Your Answer | ✅ or ❌ |
|---|---|---|---|
| 1 | B | _ | _ |
| 2 | B | _ | _ |
| 3 | B | _ | _ |
| 4 | A | _ | _ |
| 5 | B | _ | _ |

**If 5/5 correct:** You're ready for kickoff! 🎉  
**If 4/5 correct:** Good, just review that one topic before kickoff.  
**If < 4/5 correct:** Re-read escalation protocol + decision rule. Attend pre-kickoff sync call (2026-08-16).

---

## Onboarding Completion Checklist

### By 2026-08-05 EOD
- [ ] Received kickoff invitation
- [ ] Confirmed attendance (no calendar conflicts)
- [ ] Started reading background documentation

### By 2026-08-10 EOD
- [ ] Finished reading all background docs (1 hour)
- [ ] Confirmed Trello + Jira + Slack access
- [ ] Watched Grafana dashboard

### By 2026-08-15 EOD
- [ ] Read escalation protocol carefully (SLAs + alert channels)
- [ ] Read decision rule document (consensus rule, escalation tree)
- [ ] Passed knowledge check (5/5 answers correct)
- [ ] Attended pre-kickoff sync call (if available)
- [ ] Received calendar invites + confirmed in calendar
- [ ] Received Zoom link + tested connection

### By 2026-08-18 09:00 AM
- [ ] Joined Zoom 5 min early
- [ ] Brought notes + decision rule document
- [ ] Ready to participate in kickoff

---

## Contact Information

**For tool access issues:**
- Metrics Lead: metrics-lead@gftd.group

**For escalation protocol questions:**
- Escalation Lead: escalation-lead@gftd.group

**For general questions or concerns:**
- Execution Lead: claude@code.ai

**For urgent blockers (before kickoff):**
- Slack: #prod-gates-wave5 (message @claude-code-agent)

---

## FAQ

### Q: Can I attend remotely?
**A:** Yes. Zoom link will be in calendar invite. Test connection beforehand.

### Q: What if I can't make Monday 09:00 or Friday 16:00?
**A:** Contact execution lead ASAP. We need to find a time that works for all 8+ attendees. These meetings are not optional (gates require consensus).

### Q: Do I need to read every `.edn` file?
**A:** No. Focus on `.md` files (readable) + skim `.edn` files for structure. Escalation protocol is the critical one.

### Q: What if I don't understand escalation protocol?
**A:** Come to pre-kickoff sync call (2026-08-16) or email escalation-lead@gftd.group. This is the most important skill for the next 6 weeks.

### Q: How long is the kickoff meeting?
**A:** 60 min (+ 10 min optional tool help after). Expect it to run until 10:10 AM.

### Q: Will there be a recording?
**A:** Yes. Recorded and archived in #prod-gates-wave5 Slack thread. But please attend live (we need consensus).

### Q: What if I'm running late on 2026-08-18?
**A:** Let execution lead know immediately (Slack DM). We'll wait up to 5 min. No more than that.

---

## Post-Onboarding: Week 11 Execution

After completing this checklist, you're ready for:

1. **2026-08-18 09:00** — Kickoff meeting
2. **2026-08-19 09:00** — Week 11 Monday progress review
3. **2026-08-22 16:00** — Week 11 Friday gate review

For detailed procedures on Week 11 checkpoints:
- See: 90-docs/gates/week-11-checkpoint-procedures.md

---

**Generated:** 2026-07-21  
**Status:** Ready for distribution starting 2026-08-01  
**Completion Target:** 2026-08-17 EOD  
**Next Review:** 2026-08-18 (post-kickoff, confirm all onboarding complete)
