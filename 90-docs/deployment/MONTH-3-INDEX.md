# Month 3 Strangler-Fig Fleet Migration: Document Index
## Execution Materials (2026-09-11 to 2026-09-30)

**Prepared**: 2026-07-21  
**Authority**: ADR-2607072100 (Strangler-Fig deployment strategy)  
**Owner**: Jun Kawasaki

---

## Quick Navigation

### 1. Read First: Summary Report
**File**: `MONTH-3-STRANGLER-FIG-SUMMARY.md`  
**Length**: ~10 min read  
**Purpose**: Executive overview + decision framework  
**Sections**:
- Current state assessment
- Phase 3.1-3.3 execution overview
- Success criteria & exit gates
- Monitoring dashboards
- Rollback procedures
- Approval sign-off

**ACTION**: Owner reads this first to understand scope & make go/no-go decision.

---

### 2. Detailed Execution: Procedure Guide
**File**: `MONTH-3-STRANGLER-FIB-EXECUTION-PLAN.md`  
**Length**: ~20 min read + implementation  
**Purpose**: Step-by-step procedures for each phase  
**Sections**:
- Phase 3.1: Final Validation (7-day sentinel)
- Phase 3.2: Traffic Migration (ramp schedule, stability windows)
- Phase 3.3: Rust Fleet Graceful Shutdown (6-phase drain)
- Automatic rollback triggers + manual override
- Monitoring dashboard setup
- Execution log template

**ACTION**: Ops team reference this during execution. Follow procedures exactly.

---

### 3. Pre-Execution Checklist: Readiness Gate
**File**: `MONTH-3-DEPLOYMENT-READINESS.md`  
**Length**: ~5 min read + verification  
**Purpose**: Phase 2 completion gate + pre-flight checklist  
**Sections**:
- Phase 2 completion gate (all items must be ✓)
- Month 3 pre-execution setup verification
- Team readiness confirmation
- Go/No-Go decision template
- Post-decision actions (GO vs NO-GO)

**ACTION**: Complete by 2026-09-08. Owner signs off "GO" or "NO-GO".

---

### 4. Live Tracking: Execution Logs (EDN)
**File**: `MONTH-3-EXECUTION-LOGS.edn`  
**Purpose**: Machine-readable deployment event log  
**Usage**:
```bash
# Append new events during execution:
nbb manifest/fleet-ops-log.cljs \
  --event "traffic-ramp-step-2" \
  --from-pct 75 --to-pct 90 \
  --timestamp "2026-09-21T16:00:00Z"

# Archive weekly:
cp MONTH-3-EXECUTION-LOGS.edn \
   90-docs/deployment/month-3-execution-archive-$(date +%Y%m%d).edn
```

**ACTION**: Keep this file open during execution. Update events as they happen.

---

## Execution Timeline at a Glance

```
2026-09-08: Phase 2 completion gate (READINESS checklist sign-off)
2026-09-11: Sentinel phase starts (Phase 3.1, 50% traffic lock)
2026-09-17: Go/No-Go gate (owner decision 15:00 UTC)
            ↓ GO approved
2026-09-18: Traffic ramp step 1: 50% → 75%
2026-09-21: Traffic ramp step 2: 75% → 90%
2026-09-25: Traffic ramp step 3: 90% → 99% (Rust shutdown Phase A starts)
2026-09-29: Final cutover: 99% → 100% (Rust shutdown Phase C-D)
2026-09-30: Config update, Rust archive complete (Phase 3.3 final)
```

---

## Key Decision Points

### 1. Phase 2 → Phase 3 Gate (2026-09-08)

**What**: Owner reviews Phase 2 metrics and decides to proceed.  
**Document**: `MONTH-3-DEPLOYMENT-READINESS.md` (section "Phase 2 Completion Gate")  
**Decision Options**:
- **GO**: Proceed to Month 3 on 2026-09-11
- **NO-GO**: Delay, fix issues, reschedule

**Required for GO**: All Phase 2 items ✓ (traffic ramp, SLO gates, parity, reliability)

### 2. Sentinel → Ramp Gate (2026-09-17, 1500 UTC)

**What**: Owner reviews sentinel week metrics and approves traffic migration.  
**Document**: `MONTH-3-STRANGLER-FIG-EXECUTION-PLAN.md` (section "Phase 3.1.3 Go/No-Go Gate")  
**Decision Options**:
- **GO**: Proceed with traffic ramp (50% → 75% on 2026-09-18)
- **NO-GO**: Abort Month 3, revert to Phase 2 debugging

**Required for GO**: 7-day sentinel at 50% traffic, all SLO gates pass

### 3. Manual Rollback (Owner Decision, Any Time)

**What**: If root cause is application-level, owner can decide to revert.  
**Document**: `MONTH-3-STRANGLER-FIG-EXECUTION-PLAN.md` (section "Phase 3.3 Rollback Procedures")  
**Decision Options**:
- **Revert to previous ramp step**: 1-2 hour fix cycle
- **Abort Month 3**: 4-6 hour redesign cycle

**Automatic rollback** (no owner decision needed) triggers on hard-gate metrics (p99>100ms, parity>1%, 5xx>1%, memory>512MB).

---

## Critical Files (Version Control)

These files should be committed to main after Month 3 completion:

```
90-docs/deployment/
├── month-3-strangler-fig-2026-09-18.edn          (final execution log)
├── month-3-strangler-fig-archive-2026-09-30.edn  (weekly archives)
├── month-3-slo-report-final.json                 (performance metrics)
├── month-3-parity-validation-final.json          (parity stats)
└── month-3-postmortem-2026-10-01.md              (retro + lessons)
```

---

## Emergency Contacts

| Role | Name | Phone | Slack | Availability |
|------|------|-------|-------|---|
| Deployment Lead | Jun Kawasaki | +81-... | @jun | 2026-09-11 to 2026-09-30 |
| On-Call Engineer | [rotation] | [pager] | #incident | 24/7 |
| Backup Owner | [TBD] | [TBD] | @backup | If primary unavailable |

---

## Frequently Asked Questions (FAQ)

### Q: What if Phase 2 doesn't finish on time?
**A**: Reschedule Month 3 start date. Maintain 2-week preparation buffer before ramp.

### Q: What if an automatic rollback triggers during ramp?
**A**: System reverts traffic to previous safe step autonomously. Owner investigates root cause. No manual intervention required (unless decision to abort Month 3 entirely).

### Q: What happens to the Rust fleet after cutover?
**A**: Graceful shutdown over 5 days (2026-09-25 to 2026-09-30):
1. Disable health checks (LB stops routing)
2. Drain in-flight connections (72h timeout)
3. Stop processes
4. Archive logs to B2+DataLad
5. Config update (cljc-only production)

### Q: Can we pause at 75% or 90% traffic?
**A**: Yes. Use manual decision gate at any stability window to hold traffic steady. Continue when ready (no deadline pressure).

### Q: What's the expected max downtime?
**A**: Zero planned downtime. This is a zero-downtime canary ramp. If rollback triggers, transient impact <5 min (auto-revert). Total expected impact: 0 minutes.

### Q: How do we validate 100% cutover was successful?
**A**: Check success criteria checklist (Phase 3.3 exit gate). All 10 items must be ✓.

---

## Related ADRs & Documentation

| ADR | Title | Relevance |
|-----|-------|-----------|
| **2607072100** | Strangler-Fig deployment strategy | This is the main decision record |
| 2607072000 | Rust avoidance policy (Kotoba must be Clojure) | Context for why migration needed |
| 2607082400 | Component model & gossipsub walls (why not drop-in replacement) | Context for why staged rollout needed |
| 2607062330 | kototama-tender-chicory execution runtime | Technical foundation (WASM guest host) |

---

## Document Integrity Checklist

Before committing to version control:

- [ ] All dates are UTC (2026-09-XX format)
- [ ] All file paths are absolute or relative to superproject root
- [ ] All bash scripts are shell-agnostic (not bash-specific)
- [ ] All clojure code uses `nbb` (no `bb` babashka)
- [ ] No hardcoded credentials or secrets
- [ ] All references to Phase 1-2 are accurate (assume completed by 2026-09-11)
- [ ] Rollback procedures tested (dry-run)
- [ ] Monitoring dashboards deployed

---

## How to Use These Documents

### For Jun Kawasaki (Owner/Decision Maker)

1. Read: `MONTH-3-STRANGLER-FIG-SUMMARY.md` (10 min)
2. Review: `MONTH-3-DEPLOYMENT-READINESS.md` (Phase 2 completion gate, 2026-09-08)
3. Approve: Go/No-Go decision → sign off or reschedule
4. Monitor: Check dashboard daily (2026-09-11 to 2026-09-30)
5. Decide: Proceed to ramp or abort (gate 2026-09-17)
6. Archive: Save final logs to version control post-execution

### For Ops Team (Execution)

1. Study: `MONTH-3-STRANGLER-FIG-EXECUTION-PLAN.md` (20 min)
2. Drill: Test rollback procedures (dry-run, 2-3 hours)
3. Execute: Follow procedures step-by-step during deployment (2026-09-11 to 2026-09-30)
4. Track: Update `MONTH-3-EXECUTION-LOGS.edn` as events happen
5. Escalate: Page on-call if hard-gate metrics breach
6. Archive: Save execution logs weekly

### For Reviewers/Auditors

1. Context: Read `MONTH-3-STRANGLER-FIB-SUMMARY.md` (overview)
2. Procedures: Review `MONTH-3-STRANGLER-FIG-EXECUTION-PLAN.md` (detail)
3. Logs: Inspect `MONTH-3-EXECUTION-LOGS.edn` (what actually happened)
4. Metrics: Check `90-docs/deployment/month-3-slo-report-final.json`
5. Postmortem: Read `90-docs/deployment/month-3-postmortem-2026-10-01.md` (retro)

---

## Success Definition

Month 3 execution is successful when:

✓ Sentinel week (2026-09-11 to 2026-09-17) passes all SLO gates  
✓ Owner approves go/no-go decision (2026-09-17)  
✓ Traffic ramp (50% → 75% → 90% → 99%) completes without rollback  
✓ Final cutover (99% → 100%) on 2026-09-29  
✓ Rust fleet gracefully drained (all 6 phases complete)  
✓ cljc-only production ready (2026-09-30)  
✓ Complete audit trail archived  
✓ Post-deployment stabilization begins (Phase 4)  

---

**Index Version**: 1.0  
**Status**: Ready for execution (pending Phase 2 gate)  
**Last Updated**: 2026-07-21

