# Wave 5 M5–M6 Production Gates Execution Framework

**Status:** ✅ **ACTIVATION COMPLETE — Ready for Week 11 Kickoff (2026-08-18)**

---

## Quick Start (30 seconds)

1. **Read First:** [`EXECUTION-KICKOFF-REPORT-WAVE5-M5M6.md`](#execution-kickoff-reportwave5-m5m6md) (executive summary, all details)
2. **Check Status:** [`ACTIVATION-STATUS-WAVE5.md`](#activation-statuswave5md) (what's done, what's pending, pre-kickoff checklist)
3. **For Team Leads:** Start with the [Weekly Checkpoint Rhythm](#weekly-checkpoint-structuredn) section
4. **For Metrics Team:** See [Metrics Collection Pipeline](#metrics-collection-pipelinedn)
5. **For Escalation Lead:** See [Escalation Protocol Reference](#escalation-protocol-referencedn)

---

## Document Index

### Core Strategy & Execution

| Document | Purpose | Audience |
|----------|---------|----------|
| **[EXECUTION-KICKOFF-REPORT-WAVE5-M5M6.md](./EXECUTION-KICKOFF-REPORT-WAVE5-M5M6.md)** | Complete narrative of gate execution framework (schedule, boards, metrics, escalation, checkpoints) | All (executives, team leads, platform) |
| **[ACTIVATION-STATUS-WAVE5.md](./ACTIVATION-STATUS-WAVE5.md)** | Current status + pre-kickoff staging checklist (what's done, what's pending) | Project leads, main coordinator |
| **[wave-5-m5-m6-execution-kickoff.edn](./wave-5-m5-m6-execution-kickoff.edn)** | Machine-readable master schedule (dates, SLAs, deployment plan) | Systems + CI/CD |

### Execution Boards & Workflow

| Document | Purpose | Audience |
|----------|---------|----------|
| **[weekly-execution-boards-template.edn](./weekly-execution-boards-template.edn)** | Trello board lists, Jira workflow templates, shared dashboard spec | Team leads, Jira admin, Trello users |
| See Also | Trello boards (created by team leads by 2026-08-15) | M5-Core, M5-Integration, M6-Platform teams |
| See Also | Jira projects (M5W5, M5IW5, M6W5) created by 2026-08-15 | Jira users, team leads |
| See Also | Shared dashboard at `https://dashboard.internal/wave5-m5m6` (live 2026-08-09) | All (read-only access) |

### Metrics Collection & Monitoring

| Document | Purpose | Audience |
|----------|---------|----------|
| **[metrics-collection-pipeline.edn](./metrics-collection-pipeline.edn)** | Prometheus setup, alert routing, Grafana dashboard panels, deployment checklist | Metrics lead, DevOps, infrastructure team |
| Deployment | Prometheus (2026-08-08), CI bot (2026-08-09), Grafana (2026-08-09) | Ops team |
| Smoke Test | Pre-kickoff validation (2026-08-10) | Metrics lead |

### Escalation & Decision-Making

| Document | Purpose | Audience |
|----------|---------|----------|
| **[escalation-protocol-reference.edn](./escalation-protocol-reference.edn)** | SLA tree (L1/L2/L3), alert routing, decision rules, 5 escalation scenarios, training | Escalation lead, phase owners, platform lead |
| **[wave5-escalation-ledger.edn](./wave5-escalation-ledger.edn)** | Append-only log of all escalation events (auto-populated) | Metrics lead, escalation lead (read/archive) |

### Weekly Checkpoints & Reporting

| Document | Purpose | Audience |
|----------|---------|----------|
| **[weekly-checkpoint-structure.edn](./weekly-checkpoint-structure.edn)** | Monday 9 AM progress review + Friday 4 PM gate decision frameworks, templates, quality gates | Team leads, metrics lead, phase owners |
| **[weekly-reports/WEEKLY-REPORT-TEMPLATE.edn](./weekly-reports/WEEKLY-REPORT-TEMPLATE.edn)** | Template for each week's execution report (metrics, decision, blockers, risks) | Execution lead, main coordinator |
| **[weekly-reports/wave5-m5m6/](./weekly-reports/wave5-m5m6/)** | Archive of completed weekly reports (Week 11–16) | Historians, post-mortems |

---

## Timeline at a Glance

### Pre-Kickoff Staging (2026-08-08 to 2026-08-17)

```
2026-08-08: Deploy Prometheus collector
2026-08-09: Deploy CI gate-bot + alert router + Grafana dashboard
2026-08-10: Smoke test all metrics (pre-kickoff validation)
2026-08-15: Team onboarding complete (Trello + Jira + Slack access)
2026-08-16: Pre-kickoff sync call (Q&A + dry-run escalation scenario)
2026-08-17: Final readiness sign-off from main coordinator
```

### Production Gates Execution (Weeks 11–16)

```
🎯 Week 11 (2026-08-18): Kickoff + M5 Critical Path Baseline
   • 09:00 Kickoff meeting (60 min, consensus decision)
   • 09:00 First Monday review (triage + forecast)
   • 16:00 First Friday gate decision (Go/No-Go consensus)

📊 Weeks 12–14 (2026-08-25 to 2026-09-08): M5 Phase
   • Every Monday 09:00: Progress review (30 min)
   • Every Friday 16:00: Gate decision (45 min)

📊 Weeks 15–16 (2026-09-15 to 2026-09-22): M6 Phase
   • Same Monday + Friday rhythm
   • 2026-09-08: M5 close, M6 handoff readiness check
```

---

## Key Concepts

### Weekly Checkpoint Rhythm

**Monday 09:00 AM (30 min):** Progress Review & Blocker Triage
- Metrics health check
- Team progress review (% complete)
- Blocker triage + escalation if needed
- Velocity trending
- **Output:** Progress notes + updated blockers + Slack update
- **Decision:** None (this is triage + forecast only)

**Friday 16:00 (4 PM) (45 min):** Gate Review & Go/No-Go Decision
- Gate readiness assessment (per team)
- Critical path verification
- Cross-team dependency check
- Escalation summary (if P1 blockers)
- **Go/No-Go decision (consensus)**
- Next week critical path prep
- **Output:** Gate decision memo + metrics snapshot + escalation log (if any)

### Decision Rule

**Consensus:** All teams + phase owners + platform lead must agree on Go/No-Go decision.
- If consensus unachievable in 1h discussion → escalate to L3 (platform-lead authority)
- Platform-lead decides, documents override rationale

### SLA Targets

| Alert Type | Response SLA |
|------------|--------------|
| Warnings (build-times, coverage) | 4 hours |
| Critical (deploy-failure, blocker SLA breach) | 2 hours |
| Gate-halt signal (2+ teams blocked) | 15 minutes |

### 5 Core Metrics

1. **Build Times (p99):** Target < 12 min (collection: 15 min cadence)
2. **Test Coverage:** Target ≥ 92% (collection: 1h cadence)
3. **Deployment Success:** Target ≥ 99.5% (collection: 1h cadence)
4. **Gate-Blocker Detection:** Target < 2 min alert latency (collection: 5 min cadence)
5. **Team Velocity:** Story points vs. forecast (collection: 1h cadence)

---

## Roles & Responsibilities

### Team Leads (M5-Core, M5-Integration, M6-Platform)
- ✅ Attend Monday 09:00 progress review
- ✅ Attend Friday 16:00 gate review
- ✅ Maintain team's Trello board (update cards by Friday 16:00)
- ✅ Track blockers in Jira (status, ETA, escalation)
- ✅ Participate in consensus decision (Friday gate)
- 📞 First responder to L1 escalations (own team's blockers)

### Metrics Lead
- ✅ Deploy + maintain Prometheus, CI bot, alert router, Grafana
- ✅ Provide metrics snapshot (pre-Monday, pre-Friday)
- ✅ Respond to missing data / collection failures
- ✅ Attend checkpoints (provide data, triage metric breaches)

### Phase Owners (M5 & M6)
- ✅ Attend Friday gate review
- ✅ Verify cross-team dependencies
- ✅ Second-level escalation responder (L2 authority)
- ✅ Authorize emergency mitigations (if needed)

### Platform Lead
- ✅ Attend Friday gate review (as needed)
- ✅ Third-level escalation responder (L3 authority)
- ✅ Gate-halt decision maker (if consensus fails)
- ✅ Stakeholder communication (delays, risks)

### Execution Lead (Claude Code Agent)
- ✅ Orchestrate kickoff meeting + weekly checkpoints
- ✅ Generate weekly execution reports
- ✅ Monitor escalation ledger + SLA compliance
- ✅ Escalate infrastructure issues (if metrics pipeline fails)
- ✅ Report weekly to main coordinator

### Escalation Lead
- ✅ Arm alert routing (Slack, email, PagerDuty)
- ✅ Monitor escalation ledger (SLA tracking)
- ✅ On-call during weeks 11–16
- ✅ Coordinate L1 → L2 → L3 escalation chain

---

## Setup Checklist (Due 2026-08-17)

### Infrastructure (Due 2026-08-10)
- [ ] Prometheus collector deployed + scraping all targets
- [ ] CI gate-bot workflow deployed + active
- [ ] Alert router armed (Slack, email, PagerDuty)
- [ ] Grafana dashboard live + panels rendering
- [ ] Smoke test passed (all metrics collected, alerts routed)

### Boards & Tracking (Due 2026-08-15)
- [ ] Trello boards created (3 teams: M5-Core, M5-Integration, M6-Platform)
- [ ] Jira projects instantiated (3 projects: M5W5, M5IW5, M6W5)
- [ ] Slack channel #prod-gates-wave5 created + team access granted
- [ ] Contact directory filled in (emails, phones, Slack handles)

### Team Readiness (Due 2026-08-17)
- [ ] All team leads trained on escalation protocol (15 min read + Q&A)
- [ ] All team leads trained on checkpoint rhythm (live walkthrough)
- [ ] All team leads have access to Trello + Jira + Slack
- [ ] Metrics lead confirmed access to Prometheus + Grafana
- [ ] Pre-kickoff sync call held (2026-08-16, Q&A + dry-run)

### Final Approval (Due 2026-08-17)
- [ ] Main coordinator readiness sign-off
- [ ] Contingency / rollback plans reviewed with team
- [ ] No unresolved questions or concerns

---

## Common Questions

### Q: What if a team cannot attend Friday gate review?
**A:** Async update (Jira comment + Slack) is acceptable, but requires team-lead signature. Phase-owner may substitute. Time zone issues? Schedule Mon/Fri at +/- 1h if needed.

### Q: What if metrics are missing on Friday 16:00?
**A:** Proceed with Go/No-Go based on team assessment + last-known metrics. Document in gate memo: "Metrics unavailable — decision based on team report." Escalate infrastructure issue to metrics-lead.

### Q: What if Friday decision is "No-Go"? Do we retry on Monday?
**A:** No. No-Go extends gate by 48h (decision to retry Monday, if blocker resolved). If blocker unresolved after 48h → escalate to L2/L3.

### Q: What if gates halt during Week 11 due to missing tooling?
**A:** Activate contingency: static SLA values (hard-coded fallback), manual decision-making. Rollback plan documented in ACTIVATION-STATUS-WAVE5.md.

### Q: Where do I report issues with the process?
**A:** Slack #prod-gates-wave5 (sync), or email escalation-lead@gftd.group (async). Feedback collected in weekly team satisfaction survey.

### Q: How do I appeal a gate decision?
**A:** Only platform-lead can override consensus (L3 authority). Rationale must be documented in Jira + escalation ledger.

---

## Success Criteria

### Week 11 Gate Execution
- ✅ Kickoff meeting held (2026-08-18 09:00) with unanimous consensus
- ✅ All boards (Trello + Jira) live + team-accessible
- ✅ Metrics pipeline collecting data (all 5 metrics active)
- ✅ First Monday review completed (2026-08-19 09:00)
- ✅ First Friday gate decision published (2026-08-22 16:00)
- ✅ Zero gate-halts due to missing infrastructure/metrics/clarity

### Weeks 12–16 Gate Execution
- ✅ 100% on-time checkpoint completion (Mon + Fri)
- ✅ SLA compliance ≥ 95% (alert response, decision latency)
- ✅ Zero escalation due to process ambiguity (clarity maintained)
- ✅ Dashboard freshness < 1 min refresh (p99 < 30 sec)
- ✅ Team satisfaction score ≥ 3.5/5 (weekly survey)

---

## Related Documents (Outside This Folder)

- **Fleet Database:** `manifest/fleet-db.edn` (project pins, dependency graph)
- **West Manifest:** `manifest/west.yml` (child repo checkout refs)
- **ADR Register:** `90-docs/adr/` (architecture decisions, precedents)
- **Business Model Canvas:** `90-docs/business/` (product strategy, if relevant)
- **Workspaces/Actors:** `orgs/` (individual team repos, code checkouts)

---

## Feedback & Iteration

**Post-Wave 5 Review:** 2026-09-29 (one week after gate execution complete)
- Lessons learned summary
- Process improvements for future waves
- Update to this framework based on feedback

**Contact:** main-coordinator@gftd.group

---

## Document Versions

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-07-20 | Claude Code Agent | Initial framework + kickoff report |
| TBD | TBD | TBD | Post-execution improvements |

---

## Quick Links

- 🎯 **Kickoff Report:** [EXECUTION-KICKOFF-REPORT-WAVE5-M5M6.md](./EXECUTION-KICKOFF-REPORT-WAVE5-M5M6.md)
- 📊 **Activation Status:** [ACTIVATION-STATUS-WAVE5.md](./ACTIVATION-STATUS-WAVE5.md)
- ⏰ **Checkpoint Framework:** [weekly-checkpoint-structure.edn](./weekly-checkpoint-structure.edn)
- 🚨 **Escalation Protocol:** [escalation-protocol-reference.edn](./escalation-protocol-reference.edn)
- 📈 **Metrics Pipeline:** [metrics-collection-pipeline.edn](./metrics-collection-pipeline.edn)
- 📋 **Trello/Jira Templates:** [weekly-execution-boards-template.edn](./weekly-execution-boards-template.edn)
- 📅 **Weekly Reports:** [weekly-reports/](./weekly-reports/)
- 💬 **Escalation Ledger:** [wave5-escalation-ledger.edn](./wave5-escalation-ledger.edn)

---

**Generated:** 2026-07-20  
**Status:** ✅ READY FOR PRODUCTION GATES EXECUTION  
**Next Milestone:** Kickoff Meeting (2026-08-18 09:00 AM)  

---
