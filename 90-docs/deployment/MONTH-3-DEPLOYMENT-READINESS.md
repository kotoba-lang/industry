# Month 3 Deployment Readiness Checklist
## Pre-Execution Validation (Target: 2026-09-08)

**Date Created**: 2026-07-21  
**Phase**: Month 2 Exit → Month 3 Entry  
**Owner**: Jun Kawasaki (Deployment Lead)

---

## Phase 2 Completion Gate

**Critical**: All Phase 2 items must be green before Month 3 execution begins.

### 2.1 Traffic Ramp Completion

- [ ] Traffic successfully ramped: 5% → 10% → 25% → 50%
- [ ] Each ramp step completed with 3-day stability window
- [ ] All traffic spike events logged with timestamps
- [ ] No emergency rollback occurred during ramp
- [ ] Documentation: `manifest/month-2-traffic-ramp-log.edn`

### 2.2 Performance SLO Validation

- [ ] p50 latency sustained ≤ 70ms (over 7-day window)
- [ ] p95 latency sustained ≤ 95ms
- [ ] p99 latency sustained ≤ 100ms (HARD GATE)
- [ ] Throughput loss < 1% vs Rust baseline
- [ ] GC pause p99 ≤ 30ms (sustained)
- [ ] No memory leak detected (4-week heap profile analysis)
- [ ] Latency baseline comparison: `metrics/month-2-slo-validation.json`

### 2.3 Parity Validation

- [ ] Parity failure rate ≤ 0.05% (canary 50% traffic)
- [ ] Content-hash comparison automated and passing
- [ ] HTTP status code identity verified (both 200 or both 400, not divergent)
- [ ] Datom assertion comparison green (kgraph-assert/query)
- [ ] Zero application-level parity violations detected
- [ ] Parity check logs: `metrics/month-2-parity-validation.edn`

### 2.4 Reliability & Crash Reports

- [ ] Zero unrecovered panics/crashes during Phase 2
- [ ] HTTP 5xx rate ≤ 0.1% (canary monitoring)
- [ ] cljc node pool: no spontaneous restarts (kube restart count = 0)
- [ ] Rust pool health: untouched, health checks passing
- [ ] Alert noise: < 5 false positives per day
- [ ] Crash analysis report: `metrics/month-2-reliability-report.edn`

### 2.5 Node Pool Capacity

- [ ] cljc pool size: ≥ 6 nodes (stable for 50% traffic)
- [ ] Each node JVM startup time: < 15 seconds
- [ ] Node pool scaling: automatic & tested (if applicable)
- [ ] Memory per instance: ≤ 384MB peak (post-GC stable)
- [ ] CPU utilization: ≤ 70% at 50% traffic
- [ ] Capacity plan: `manifest/node-pool-capacity-50pct.edn`

### 2.6 GC Tuning Evaluation (Month 2 Action Item)

- [ ] ZGC vs Shenandoah evaluated
- [ ] Selected GC algorithm baseline locked
- [ ] `-XX:+UseZGC` or `-XX:+UseShenandoah` configured in `jvm.options`
- [ ] GC pause spikes: zero > 100ms sustained events
- [ ] GC evaluation report: `metrics/month-2-gc-tuning-report.edn`

### 2.7 Audit Trail & Logging

- [ ] All Month 2 requests/responses logged (sample rate 100% for canary)
- [ ] Audit trail B2 upload: complete (container size ≈ 100-200GB)
- [ ] Log rotation configured (prevent disk overflow)
- [ ] Restore-from-audit test passed (verify data integrity)
- [ ] Audit trail manifest: `90-docs/deployment/month-2-audit-trail.edn`

---

## Month 3 Pre-Execution Setup (2026-09-08)

### 3.1 Infrastructure Readiness

- [ ] cljc node pool ready (6 nodes, all healthy)
- [ ] Rust fleet ready for graceful drain (9 nodes, all healthy)
- [ ] murakumo control plane responsive
- [ ] B2 credentials valid (test upload/download)
- [ ] DataLad working (test annex add/get)
- [ ] Monitoring dashboards deployed & live

### 3.2 Configuration Lock

- [ ] `manifest/fleet-db.edn` pinned to known-good version
- [ ] SLO targets frozen: `manifest/slo-targets.edn`
- [ ] Rollback scripts tested: `murakumo/rollback.sh`
- [ ] Drain scripts tested: `murakumo/drain-rust-fleet.sh`
- [ ] Alert rules deployed to monitoring stack
- [ ] On-call escalation contacts verified

### 3.3 Team Readiness

- [ ] Owner (Jun Kawasaki): available for entire Month 3 window
- [ ] Ops team trained on rollback procedures
- [ ] On-call rotation: 24/7 coverage 2026-09-11 to 2026-09-30
- [ ] Incident playbooks reviewed and accessible
- [ ] Communication channels established (Slack, email, pager)

### 3.4 Documentation Review

- [ ] This checklist signed off
- [ ] Month 3 Execution Plan reviewed: `MONTH-3-STRANGLER-FIG-EXECUTION-PLAN.md`
- [ ] Rollback procedures understood by ops team
- [ ] SLO definitions agreed upon
- [ ] Success criteria clear to all stakeholders

---

## Phase 2 Completion Sign-Off

**Reviewed by**: Jun Kawasaki  
**Date**: [To be filled at Phase 2 end]

| Item | Sign-Off | Date |
|------|----------|------|
| Traffic ramp 5% → 50% complete | [ ] | |
| All SLO gates passing | [ ] | |
| Parity failure < 0.05% | [ ] | |
| Reliability threshold met | [ ] | |
| Audit trail complete | [ ] | |
| Team trained & ready | [ ] | |

---

## Month 3 Go/No-Go Decision (2026-09-08)

**Checklist**: All Phase 2 items ✓ AND all Pre-Execution Setup items ✓

**Owner Decision**: 

- [ ] **GO**: Proceed to Month 3 on schedule (2026-09-11)
- [ ] **NO-GO**: Delay Month 3, resolve issues, reschedule

**Decision Date**: _______________  
**Owner Signature**: _______________

---

## Post-Decision Actions

### If GO (proceed to Month 3)

```bash
# 1. Lock Phase 2 metrics as baseline for Month 3
cp metrics/month-2-slo-validation.json metrics/month-3-baseline.json
cp metrics/month-2-parity-validation.edn metrics/month-3-baseline-parity.edn

# 2. Prepare Month 3 execution logs
nbb manifest/fleet-ops-log.cljs \
  --event "month-3-go-decision" \
  --decision "approved" \
  --timestamp "2026-09-08T12:00:00Z"

# 3. Alert team
echo "Month 3 execution approved. Sentinel phase begins 2026-09-11 06:00 UTC" | mail -s "Strangler-Fig Month 3 GO" ops@murakumo.cloud

# 4. Final checklist
echo "Month 3 Deployment Readiness: APPROVED" > deployment-status.txt
git add deployment-status.txt
git commit -m "Month 3 Strangler-Fig: GO decision signed off (2026-09-08)"
```

### If NO-GO (delay and reschedule)

```bash
# 1. Document NO-GO reason
nbb manifest/fleet-ops-log.cljs \
  --event "month-3-nogo-decision" \
  --reason "[fill in reason]" \
  --timestamp "2026-09-08T12:00:00Z"

# 2. Create remediation plan
echo "Month 3 NO-GO Remediation Plan" > month-3-remediation.md
# ... document issues and timeline

# 3. Reschedule
echo "Month 3 deployment rescheduled to [NEW DATE]" | mail -s "Strangler-Fig Month 3 Rescheduled" ops@murakumo.cloud

# 4. File ticket
gh issue create --title "Month 3 Strangler-Fig Rescheduled" \
  --body "Reasons: [insert]\nNew target date: [insert]\nLink: month-3-remediation.md"
```

---

## Metrics Data Location

All metrics collected during Phase 2 should be available at:

```
├── metrics/
│   ├── month-2-slo-validation.json
│   ├── month-2-parity-validation.edn
│   ├── month-2-reliability-report.edn
│   ├── month-2-gc-tuning-report.edn
│   └── month-2-traffic-ramp-log.edn
├── 90-docs/deployment/
│   └── month-2-audit-trail.edn
└── manifest/
    ├── node-pool-capacity-50pct.edn
    ├── slo-targets.edn (locked)
    └── fleet-db.edn (pinned)
```

---

## Emergency Contacts

| Role | Name | Phone | Slack |
|------|------|-------|-------|
| Deployment Lead | Jun Kawasaki | +81-... | @jun |
| Ops Team Lead | [TBD] | [TBD] | [TBD] |
| On-Call Engineer | [rotation] | [pager] | #incident |

---

**Document Version**: 1.0  
**Last Updated**: 2026-07-21  
**Status**: Awaiting Phase 2 Completion

