# Strangler-Fig Month 3 Fleet Migration: Execution Summary Report
## ADR-2607072100 Implementation Roadmap

**Report Date**: 2026-07-21  
**Phase**: Month 3 Preparation & Execution Planning  
**Timeline**: 2026-09-11 to 2026-09-30 (planned execution)  
**Owner**: Jun Kawasaki

---

## Executive Summary

This report documents the Month 3 execution plan for the final phase of the Strangler-Fig kotoba-server fleet migration from Rust (9 nodes) to cljc (6+ nodes).

**Key Milestones:**
- **2026-09-11**: Sentinel phase begins (50% traffic lock, 7-day stability window)
- **2026-09-17**: Go/No-Go gate (owner decision to proceed with traffic migration)
- **2026-09-18 to 2026-09-28**: Traffic ramp 50% → 75% → 90% → 99% (3-day windows)
- **2026-09-29**: Final cutover 99% → 100% (implicit, Rust drain completes)
- **2026-09-30**: Rust fleet fully decommissioned, cljc-only production ready

**Expected Outcome:** 100% traffic on cljc mesh nodes, Rust infrastructure decommissioned, production baseline captured for post-deployment stabilization (Phase 4+).

---

## Current State Assessment

### Phase 2 Entry Requirements (as of 2026-07-21)

This report assumes Phase 2 (Month 2, 2026-08-01 to 2026-08-31) has been successfully completed:

```
Phase 2 Completion Checklist (To be verified by 2026-09-08):
  [?] Traffic ramp: 5% → 50% successful (staged increments)
  [?] SLO gates maintained throughout ramp (p99 ≤ 100ms, parity ≤ 0.05%)
  [?] GC tuning evaluation complete (ZGC vs Shenandoah selected)
  [?] Node pool capacity validated (≥6 nodes for 50% load)
  [?] Audit trail: 2-month complete logs to B2+DataLad
  [?] Team trained on Month 3 procedures (rollback, drain, cutover)
```

**Phase 2 Exit Gate** (2026-09-08): All items must be ✓ to proceed.

### Deployment Infrastructure Status

| Component | Status | Notes |
|-----------|--------|-------|
| cljc node pool (6 nodes) | Ready | Staging → production transition |
| Rust fleet (9 nodes) | Ready | Graceful drain procedure armed |
| murakumo control plane | Ready | Traffic split automation tested |
| B2 credentials | Ready | Archival capacity: ≈500GB available |
| Monitoring dashboards | Ready | Grafana: kotoba-server-fleet-migration |
| On-call rotation | TBD | Need 24/7 ops coverage 2026-09-11 to 2026-09-30 |
| Incident playbooks | Ready | rollback, drain, parity-failure documented |

---

## Month 3 Execution Plan Overview

### Phase 3.1: Final Validation (Week 1, 2026-09-11 to 2026-09-17)

**Objective**: Establish production confidence in 50% traffic load before proceeding to larger ramp.

**Timeline:**
- **2026-09-11 (Day 1)**: Sentinel starts, traffic locked at 50% ✓
- **2026-09-11 to 2026-09-17 (Days 1-7)**: Daily metrics review + stability checks
- **2026-09-17 (Day 7, 1500 UTC)**: Go/No-Go gate (owner decision)

**SLO Checklist (all must hold for 7 consecutive days):**
```
✓ p99 latency ≤ 100ms (hard gate)
✓ parity failure rate ≤ 0.01% (hard gate)
✓ HTTP 5xx ≤ 0.01% (hard gate)
✓ Zero unrecovered panics/crashes
✓ Memory peak ≤ 256MB per instance
✓ GC pause p99 ≤ 30ms
✓ Alert noise < 2 false positives/day
```

**Decision Logic:**
- **GO (proceed)**: All SLO gates pass → Ramp to 75% on 2026-09-18
- **NO-GO (abort)**: Any hard gate fails → Revert to Phase 2 debugging, reschedule Month 3

### Phase 3.2: Traffic Migration (Week 1-2, 2026-09-18 to 2026-09-29)

**Objective**: Incrementally shift traffic from Rust (1% remaining) to cljc (99%) with automated rollback guards.

**Ramp Schedule:**

| Date | From | To | Window | Status |
|------|------|----|---------|----|
| 2026-09-18 | 50% | 75% | Step 1 (immediate) | Scheduled |
| 2026-09-19 to 2026-09-20 | 75% | 75% | Stability window (2 days) | Scheduled |
| 2026-09-21 | 75% | 90% | Step 2 (immediate) | Scheduled |
| 2026-09-22 to 2026-09-24 | 90% | 90% | Stability window (3 days) | Scheduled |
| 2026-09-25 | 90% | 99% | Step 3 (immediate) | Scheduled |
| 2026-09-26 to 2026-09-28 | 99% | 99% | Stability window (3 days) | Scheduled |
| 2026-09-29 | 99% | 100% | Final cutover (implicit via Rust drain) | Scheduled |

**Automatic Rollback Triggers (autonomous, no human intervention required):**

1. **Latency Regression**: p99 > baseline+100ms sustained 5+ min → revert to previous step
2. **Parity Failure**: divergence > 1% sustained 1+ min → full revert to Rust (100%)
3. **Reliability**: HTTP 5xx > 1% sustained 2+ min → full revert to Rust
4. **Memory**: peak > 512MB sustained → revert + scale up node pool

---

### Phase 3.3: Rust Fleet Graceful Shutdown (2026-09-25 to 2026-09-30)

**Objective**: Drain Rust fleet safely, archiving logs/state before decommissioning.

**6-Phase Drain Procedure:**

#### Phase A: Disable Health Checks (2026-09-25, 1600 UTC)
- Stop health-check daemon on all 9 Rust nodes
- Signal load balancer that nodes are draining (no new connections)
- Expected: Traffic drops to 1% (in-flight only)

#### Phase B: Drain Connections (2026-09-26 to 2026-09-28, 72h timeout)
- Monitor active connections on Rust pool
- Wait for in-flight requests to complete (30s timeout per request)
- Force-drain if timeout exceeded

#### Phase C: Stop Rust Nodes (2026-09-29, after drain)
- Gracefully stop kotoba-server processes (systemctl stop)
- Verify all processes terminated
- Expected: 0 running kotoba-server instances

#### Phase D: Verify Stopped (2026-09-29, after stop)
- Confirm no kotoba-server processes (pgrep = 0)
- Confirm port closed (nc -z = 1)
- Confirm systemd inactive
- Expected: 100% silent/offline

#### Phase E: Archive to B2 + DataLad (2026-09-29 to 2026-09-30)
- Collect logs from each Rust node
- Upload to B2: `kotobase-archive/rust-fleet/2026-09-29/`
- Register with DataLad (git-annex pin)
- Archive size: ≈ 50-100GB (4 weeks of logs)

#### Phase F: Config Update (2026-09-30, 1200 UTC)
- Update `manifest/fleet-db.edn`: `fleet-active: [cljc-staging]`
- Disable Rust pool: `rust-pool: disabled`
- Regenerate `manifest/west.yml`
- Deploy configuration update
- Expected: All new deployments exclude Rust nodes

---

## Success Criteria & Exit Gates

### Phase 3.1 Exit (2026-09-17)

```
[✓] 7-day sentinel @ 50% traffic: all SLO gates pass
[✓] Parity failure rate < 0.01%
[✓] Zero panics / crashes / unhandled exceptions
[✓] Alert noise < 2 false positives/day
[✓] Owner approves go/no-go decision
[✓] Audit trail: complete week of logs to B2
```

### Phase 3.2 Exit (2026-09-29)

```
[✓] Traffic migration: 50% → 99% complete (each step passed 3-day window)
[✓] No automatic rollback triggered
[✓] cljc pool: handling 99% traffic successfully
[✓] Rust pool: 1% traffic (in-flight only), drained to 0 active connections
[✓] SLO maintained throughout migration: p99 ≤ 100ms, parity ≤ 0.01%
```

### Phase 3.3 Exit (2026-09-30)

```
[✓] Final cutover: 100% traffic on cljc mesh nodes
[✓] Rust fleet graceful shutdown: all 6 phases complete
[✓] Rust nodes: fully offline (no processes, no ports, no systemd services)
[✓] Archive: logs/state backed up to B2+DataLad
[✓] Config: cljc-only production mode active
[✓] Monitoring: dashboards transitioned to cljc-only
[✓] Audit trail: Month 3 complete logs archived
```

### Overall Success (End of Month 3)

✓ 100% cljc fleet live in production  
✓ Rust infrastructure decommissioned  
✓ Zero production impact during migration  
✓ All rollback triggers tested (no actual rollback needed)  
✓ Complete audit trail for compliance  
✓ Performance baseline captured for Phase 4+ stabilization

---

## Monitoring & Observability

### Real-Time Dashboards (During Execution)

**Primary Dashboard**: Grafana "kotoba-server-fleet-migration"

```
Row 1: Traffic Split
  - cljc traffic % (target: 50 → 75 → 90 → 99 → 100)
  - Rust traffic % (target: 50 → 25 → 10 → 1 → 0)
  
Row 2: Latency (SLO Bands)
  - p50, p95, p99 latency (targets: 70, 95, 100 ms)
  - Rust vs cljc comparison (highlight if divergent)
  
Row 3: Reliability
  - HTTP 5xx rate (target: ≤ 0.01%)
  - Parity failure rate (target: ≤ 0.01%)
  - Panic/crash counter
  
Row 4: Resource Utilization
  - Memory per instance (peak trend, target: ≤ 256MB)
  - GC pause p99 (target: ≤ 30ms)
  - CPU utilization
  
Row 5: Rust Fleet Health (audit only)
  - Active connections (target: drain to 0 by 2026-09-29)
  - Process count (target: 0 by 2026-09-29)
  - Health check status (disabled 2026-09-25)
```

### Alert Rules (Auto-Escalation)

```
Severity: Critical (Page On-Call)
  - p99 latency > 100ms sustained 5 min
  - Parity failure > 1% sustained 1 min
  - HTTP 5xx > 1% sustained 2 min
  - Memory > 512MB sustained
  
Severity: Warning (Slack #incident)
  - p99 latency > 90ms (warning band)
  - Memory > 384MB (trend check)
  - Alert noise > 5 false positives/day
  
Severity: Info (Audit Log Only)
  - Traffic ramp executed
  - Rust node drained
  - Config updated
```

---

## Rollback Procedures

### Automatic Rollback (Triggered by monitoring)

If any hard-gate metric breaches sustained threshold:

```bash
# System autonomously executes:
murakumo/set-traffic-split.sh --cljc-percent <previous-safe-pct>

# Alert ops:
Severity: CRITICAL
Message: "Auto-rollback triggered: [reason]"

# Log event:
nbb manifest/fleet-ops-log.cljs \
  --event "automatic-rollback" \
  --trigger "[reason]" \
  --from-pct [current] \
  --to-pct [previous-safe]
```

**Consequences:**
- Traffic immediately reverted to last stable step
- cljc pool: request load drops
- Rust pool: receives additional traffic spike (expect p99 spike, OK)
- Investigation begins in parallel (root-cause analysis)

### Manual Rollback (Owner Decision)

If root cause identified as application-level issue:

```bash
# Owner executes:
murakumo/rollback-to-phase.sh --phase 2

# OR revert entire Month 3:
murakumo/rollback-to-phase.sh --phase 3.0
```

---

## Known Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **JVM GC pause spike** | p99 latency breach | ZGC/Shenandoah tuning; heap profile monitoring |
| **Rust node graceful drain hangs** | Deployment blocked | 72h timeout + force-drain fallback |
| **Parity divergence undiscovered** | Production bug leak | Automated parity checker; 0.01% threshold |
| **Ops team unavailability** | Decision delays | 24/7 on-call rotation; backup decision maker |
| **B2 upload failure** | Audit trail lost | DataLad fallback; local archive tar.gz backup |
| **Network partition (Rust ↔ murakumo)** | Stuck in-flight requests | 30s request timeout; force-drain after 72h |

---

## Communication Plan

### Pre-Execution (2026-09-08)

- [ ] Owner reviews Month 3 plan (this document)
- [ ] Team briefing: rollback procedures, on-call schedule
- [ ] Notify stakeholders: deployment window 2026-09-11 to 2026-09-30

### During Execution (2026-09-11 to 2026-09-30)

- **Daily standup**: 0900 UTC (metrics review + escalation)
- **Slack channel**: #kotoba-fleet-migration (real-time updates)
- **Incident escalation**: Page on-call if critical gate fails
- **Status page**: murakumo.cloud/status (public-facing)

### Post-Execution (2026-10-01)

- [ ] Post-deployment review (1 week stabilization check)
- [ ] Performance baseline report
- [ ] Incident retro (if any rollback occurred)
- [ ] Archive this execution log to version control

---

## Implementation Checklist

### Before 2026-09-11

- [ ] Phase 2 completion gate passed (all metrics ✓)
- [ ] Month 3 Execution Plan reviewed (`MONTH-3-STRANGLER-FIG-EXECUTION-PLAN.md`)
- [ ] Deployment Readiness Checklist signed off (`MONTH-3-DEPLOYMENT-READINESS.md`)
- [ ] Rollback procedures tested (dry-run)
- [ ] On-call rotation: 24/7 coverage confirmed
- [ ] Grafana dashboards deployed & verified
- [ ] Alert rules deployed to monitoring stack
- [ ] B2 credentials verified (test upload/download)
- [ ] Execution logs template prepared (`MONTH-3-EXECUTION-LOGS.edn`)

### During Execution (2026-09-11 to 2026-09-30)

- [ ] Sentinel week (2026-09-11 to 2026-09-17): daily metrics review
- [ ] Go/No-Go decision (2026-09-17): owner approval or abort
- [ ] Traffic ramps (2026-09-18 to 2026-09-29): each step + stability window
- [ ] Rust shutdown (2026-09-25 to 2026-09-30): 6-phase graceful drain
- [ ] Config update (2026-09-30): cljc-only production

### After Execution (2026-10-01+)

- [ ] Post-deployment stabilization (Phase 4, weeks 1-4)
- [ ] Performance baseline report published
- [ ] Audit trail archived to version control
- [ ] Incident postmortem (if applicable)
- [ ] Team retrospective + lessons learned

---

## Document References

| Document | Purpose | Location |
|----------|---------|----------|
| **ADR-2607072100** | Main decision record | `90-docs/adr/2607072100-kotoba-server-fleet-deployment-strangler-fig.edn` |
| **Month 3 Execution Plan** | Detailed procedures | `scratchpad/MONTH-3-STRANGLER-FIG-EXECUTION-PLAN.md` |
| **Deployment Readiness** | Pre-execution checklist | `scratchpad/MONTH-3-DEPLOYMENT-READINESS.md` |
| **Execution Logs (template)** | Live tracking | `scratchpad/MONTH-3-EXECUTION-LOGS.edn` |
| **SLO Targets** | Performance budget | `manifest/slo-targets.edn` |
| **Rollback Procedures** | Emergency manual | `murakumo/rollback-procedures.md` |
| **Monitoring Dashboard** | Real-time view | Grafana: "kotoba-server-fleet-migration" |

---

## Approval & Sign-Off

### Phase 2 Completion Gate (2026-09-08)

Owner reviews Phase 2 metrics and decides: **GO** or **NO-GO**

```
Owner: Jun Kawasaki
Decision: [ ] GO - Proceed to Month 3 execution on 2026-09-11
          [ ] NO-GO - Delay, reschedule (document reason)
Signature: ____________________
Date: ____________________
```

### Month 3 Execution Authority

This document authorizes execution of Month 3 deployment plan per ADR-2607072100:

```
Owner: Jun Kawasaki
Authority: Strangler-Fig fleet migration (cljc→production, Rust→drain)
Timeline: 2026-09-11 to 2026-09-30
Status: Approved for execution (pending Phase 2 gate)
Signature: ____________________
Date: ____________________
```

---

## Conclusion

The Strangler-Fig Month 3 fleet migration represents the final transition from Rust kotoba-server to cljc mesh nodes. This plan provides:

1. **Structured execution phases** with clear entry/exit gates
2. **Automated rollback triggers** to protect production
3. **Complete audit trail** for compliance and learning
4. **Team readiness** through training and procedures

Expected outcome: **100% cljc fleet live by 2026-09-30**, with Rust infrastructure fully decommissioned and production baseline captured for Phase 4+ stabilization.

---

**Document Version**: 1.0  
**Status**: Ready for Phase 2 Completion Gate  
**Last Updated**: 2026-07-21  
**Owner**: Jun Kawasaki

