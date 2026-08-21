# Strangler-Fig Month 3: Final Fleet Migration — Execution Summary

**Period**: 2026-08-18 to 2026-09-18  
**Status**: Execution plan (ready for Month 3 deployment)  
**Related ADR**: ADR-2607072100 (kotoba-server fleet deployment & strangler-fig strategy)  
**Owner**: Jun Kawasaki

---

## Executive Summary

This document summarizes the complete Month 3 execution roadmap for the final cljc fleet migration (Strangler-Fig pattern). The goal is to migrate 100% of traffic from the existing Rust kotoba-server fleet (7 nodes) to the new cljc mesh nodes (10 nodes) over a 2-week period, with weekly traffic gates, automated rollback triggers, and final Rust fleet decommissioning.

### Key Outcomes

| Outcome | Timeline | Status |
|---------|----------|--------|
| **Traffic migration**: 50% → 100% cljc | Week 1-2 (Aug 18–Aug 31) | Planned |
| **Weekly validation gates** | Days 1, 3, 4, 5, 6, 8 | 6 gates total |
| **Rust fleet decommissioning** | Week 2, Days 1-2 (Aug 25-26) | Graceful shutdown |
| **Performance baseline capture** | Week 2, Day 5 (Aug 28) | Post-cutover |
| **Month 3 completion** | Week 2 end (Aug 31) | Production stable |

---

## Month 3 Timeline (2-Week Execution)

### Week 1: Traffic Ramp-Up (Aug 18-Aug 24)

**Phase**: Final Validation Sentinel → Incremental Traffic Migration

| Day | Date | Action | Success Criteria | Decision Gate |
|-----|------|--------|------------------|---|
| **1** | Mon 08-18 | Owner decision gate (Month 2→Month 3) | Parity <0.01%, p99 ≤100ms, GC tuned | **GO / NO-GO** |
| | | Metrics checkpoint review | Month 2 baselines stable | |
| **2-3** | Tue 08-19 | Murakumo routing preparation | Dry-run passed, dashboards live | |
| | | Traffic split dry-run | Rollback scripts tested | |
| **4** | Wed 08-20 | Prepare 50%→60% traffic configuration | Config deployed, monitoring green | |
| **5** | Thu 08-21 | **GATE 1**: 50% → 60% cljc traffic | 24h stability: SLO + parity OK | Proceed to 75%? |
| **6** | Fri 08-22 | **GATE 2**: 60% → 75% cljc traffic | 24h stability: SLO + parity OK | Proceed to 90%? |
| **7** | Sat 08-23 | **GATE 3**: 75% → 90% cljc traffic | 24h stability: SLO + parity OK | Proceed to 99%? |
| **8** | Sun 08-24 | **GATE 4**: 90% → 99% cljc traffic | 24h stability: SLO + parity OK | Proceed to 100%? |

**Week 1 Success Criteria**:
- Traffic reaches 99% cljc by end of week
- All SLO metrics within budget at each step
- No unrecovered exceptions or panics
- Rollback procedures tested and working
- Rust graceful drain staged for Week 2

### Week 2: Final Cutover & Rust Shutdown (Aug 25-Aug 31)

**Phase**: 100% Cutover → Rust Fleet Decommissioning → Baseline Capture

| Day | Date | Action | Success Criteria | Decision Gate |
|-----|------|--------|------------------|---|
| **1** | Mon 08-25 | Confirm 72h stability at 99% (Fri-Sun) | All metrics green, no anomalies | |
| | | **GATE 5**: 99% → 100% cljc (final cutover) | Traffic shifted, no 5xx errors | Execute cutover? |
| | | Rust graceful shutdown initiated | Health checks disabled, drain timeout 30sec | |
| **2** | Tue 08-26 | Rust fleet shutdown continuation | All 7 nodes offline, ports free | |
| | | Archive workdirs to B2 + DataLad | Archives verified, checksums match | |
| | | Fleet-db updated | Rust nodes marked "decommissioned" | |
| **3** | Wed 08-27 | Rust fleet archive completion | All logs/configs archived, teams notified | |
| **4** | Thu 08-28 | Performance baseline capture | 24h profile: p99/throughput/memory (post-cutover) | |
| | | Capacity planning | Headroom ≥50%, scaling triggers identified | |
| **5** | Fri 08-29 | Post-cutover monitoring validation | All alerts working, escalation paths tested | |
| **6** | Sat 08-30 | Parity checker archive | Logs → B2, murakumo config updated | |
| **7** | Sun 08-31 | **GATE 6**: Month 3 completion | 100% traffic stable 1 week, all baselines captured | ✅ Complete |

**Week 2 Success Criteria**:
- 100% traffic on cljc fleet for full 7 days
- Rust fleet fully decommissioned and archived
- All SLO metrics stable and within budget
- Performance baseline and capacity plan finalized
- All alerts tuned and escalation paths validated

---

## Traffic Migration Gates (Detailed)

### Gate 1: 50%→60% (Thu 08-21)

**Objective**: Validate cljc pool can handle 60% traffic  
**Trigger Time**: 10:00 JST  
**Preparation**: Day before (Wed 08-20), dry-run completed  

**Execution**:
```
10:00  Set traffic-split cljc-percent = 60
10:01  Monitor immediately: p99, parity, 5xx for 60 sec
10:02+ Observe for anomalies (spikes, crashes)
→ Stabilization window: 24 hours (Thu 10:00 to Fri 10:00)
→ Check every 1 hour: metrics stable?
```

**Success Criteria**:
- [ ] p99 latency ≤ 105ms (baseline 100 + 5ms grace)
- [ ] Parity divergence < 0.02% (pass rate > 99.98%)
- [ ] Throughput loss < 1%
- [ ] Memory per-instance ≤ 400MB
- [ ] HTTP 5xx rate ≤ 0.1%
- [ ] No new exceptions/panics

**If Failed**:
- Auto-revert to 50% (if latency/parity/5xx thresholds breached)
- Investigate root cause, fix, re-test

**If Passed**:
- Proceed to Gate 2 (60%→75%)

---

### Gate 2-4: 60%→75%, 75%→90%, 90%→99% (Fri-Sun 08-22 to 08-24)

**Same structure as Gate 1**, executed on consecutive days:
- Fri 08-22: 60%→75%
- Sat 08-23: 75%→90%
- Sun 08-24: 90%→99%

Each gate follows the same checklist and success criteria.

---

### Gate 5: 99%→100% Final Cutover (Mon 08-25)

**Objective**: Shift last 1% traffic and initiate Rust shutdown  
**Trigger Time**: 10:00 JST  
**Preparation**: Confirm 72-hour stability at 99% (Fri-Sun all green)  

**Execution**:
```
10:00  Announce 'Final Cutover Event' on Slack
10:00  Set traffic-split cljc-percent = 100 (Rust pool = 0)
10:01  Monitor: p99, throughput, 5xx for 60 sec
10:02+ Verify no client-side errors
→ Activate Rust graceful shutdown (see decommissioning procedure)
```

**Success Criteria**:
- [ ] 100% traffic redirected to cljc pool
- [ ] No HTTP 5xx errors on cutover
- [ ] p99 latency ≤ 105ms
- [ ] All in-flight Rust requests complete (30-sec drain)

**If Failed**:
- Emergency revert to 50% Rust (within 5 min of cutover)
- Investigate critical issue, schedule re-attempt

**If Passed**:
- Proceed to Week 2 Rust fleet decommissioning

---

### Gate 6: Month 3 Completion (Sun 08-31)

**Objective**: Confirm 100% cljc production-ready, Month 3 done  
**Checklist**:
- [ ] 100% traffic stable for full 1 week (Mon-Sun)
- [ ] All SLO metrics within budget
- [ ] No unrecovered exceptions
- [ ] Rust fleet fully decommissioned
- [ ] Performance baseline captured
- [ ] Capacity plan generated
- [ ] Alerts validated

**Decision**: ✅ Month 3 COMPLETE → Proceed to Phase 4 planning

---

## Monitoring & Dashboards

### Live Dashboard (Grafana)

**Refresh Interval**: 10 seconds  
**Tabs**:
1. **Traffic Split (%)**: Real-time cljc-percent gauge
2. **Latency & Throughput**: p50/p95/p99 + req/sec
3. **Parity Checker** (Week 1 only): Divergence rate + pass/fail counts
4. **JVM Health**: Heap, GC pauses, memory leaks
5. **Error Rate**: 2xx/4xx/5xx status codes, exceptions
6. **Component Performance**: drama-profile instantiation latency

### Automated Alerts

| Severity | Trigger | Action | Escalation |
|----------|---------|--------|---|
| 🔴 CRITICAL | p99 > baseline+100ms for 5min | Auto-revert (-15%) | Page on-call + owner |
| 🔴 CRITICAL | Parity > 1% for 1min | Full revert (0%) | Page on-call + owner + dev-team |
| 🔴 CRITICAL | 5xx > 1% for 2min | Full revert (0%) | Page on-call + owner |
| ⚠️ WARNING | Memory > 400MB sustained | Page ops-lead | Escalate if > 512MB |
| ⚠️ WARNING | GC pause p99 > 40ms | Page ops-lead | Review GC tuning |

### Manual Decision Gates (No Automation)

- **Day 1**: Owner go/no-go (Month 2 baseline review)
- **Day 8**: Owner decision to execute final cutover
- **End**: Owner sign-off on Month 3 completion

---

## Rollback Procedures

### Automatic Rollback (No Owner Confirmation)

**Triggered by**: Sustained metric breach for specified duration

**Actions**:
1. **Latency regression**: Revert to previous safe traffic % (e.g., 90%→75%)
2. **Parity divergence**: Full revert to 0% cljc (100% Rust)
3. **5xx error spike**: Full revert to 0% cljc (100% Rust)
4. **Memory exhaustion**: Full revert to 0% cljc, increase node pool size, re-test

**Notification**: Async (email + Slack to ops-lead, no page if sustained < 10 min)

### Manual Rollback (Owner Decision)

**Triggered by**: Owner judgment on business priority, performance miss, or application-level issue

**Actions**:
1. **Application-level parity fix**: Return to staging pool, fix, re-test, resume
2. **Performance budget miss** (acceptable range): Escalate SLO, continue without revert
3. **Business priority shift**: Pause Month 3, schedule re-attempt later

---

## Rust Fleet Decommissioning (Week 2, Days 1-2)

### Overview

**Timeline**: Monday 08-25 to Tuesday 08-26 (2 days, 1 day intensive)  
**Nodes Affected**: 7 (naphtali, simeon, judah, zebulun, levi, joseph, issachar, dan, benjamin, asher)  
**Executor**: Ops Team (infra-lead + ops-lead)  
**Owner Approval**: Jun Kawasaki  

### Decommissioning Steps

1. **Phase 1 (10:00-10:15)**: Disable health checks (murakumo marks Rust nodes unhealthy)
2. **Phase 2 (10:15-10:50)**: Graceful connection drain (30-sec timeout for in-flight requests)
3. **Phase 3 (10:50-11:10)**: Process shutdown (SIGTERM → systemctl stop kotoba-server)
4. **Phase 4 (11:10-11:20)**: Port verification (port 8077 free on all nodes)
5. **Phase 5 (11:20-17:00)**: Workdir archive (→ B2 + DataLad, checksums verified)
6. **Phase 6 (Day 2)**: Fleet config update (manifest/fleet-db.edn marked "decommissioned")

### Success Criteria

- [ ] All 7 Rust nodes offline (no kotoba-server processes)
- [ ] All ports 8077 free
- [ ] All workdirs archived to B2 (verified checksums)
- [ ] manifest/fleet-db.edn updated + committed
- [ ] murakumo fleet-status shows 0 Rust nodes (10 cljc active)
- [ ] murakumo traffic-status shows 100% cljc / 0% rust

### Rollback (If Decommissioning Fails)

- **Node won't stop**: Restart kotoba-server, return traffic to 50%, investigate
- **Archive upload fails**: Retry (B2 idempotent), keep workdir on nodes
- **Traffic routes to Rust**: Force-kill processes, escalate to infrastructure team

See detailed procedure in `90-docs/fleet-migration-rust-fleet-decommissioning.md`.

---

## Performance Baselines & Capacity Planning

### Post-Cutover Baseline (Week 2, Day 5 — Aug 28)

**Objective**: Establish cljc-only fleet performance normal (for future optimization)

**Metrics Captured** (24-hour window):
- p50/p95/p99 latency (histogram)
- Throughput (req/sec, moving average)
- JVM memory (heap usage, GC pause distribution)
- CPU utilization per node
- Network I/O (bytes/sec)
- Component instantiation latency (drama-profile)

**Targets**:
- p99 latency ≤ 100ms (hard budget)
- Throughput ≥ Rust baseline (no regression)
- Memory stable (no leak detected)
- GC pause p99 ≤ 30ms (ZGC/Shenandoah tuned)

**Output**: `murakumo/metrics/cljc-only-baseline-2026-08-28.edn`

### Capacity Planning (Week 2, Day 5)

**Objective**: Estimate sustained load headroom

**Calculation**:
```
Current load at cutover:   L₀ req/sec (baseline)
Current pool size:        10 cljc nodes
Headroom threshold:       50% (can handle 1.5x current)

Required pool size for 1.5x: 10 * 1.5 = 15 nodes (5-node scale-up)
Decision: If headroom < 50% at cutover → immediate plan for 3-node addition
```

**Scaling Triggers**:
- If sustained load reaches 70% of current capacity → add 2 nodes
- If sustained load reaches 85% of current capacity → add 3 nodes

**Output**: `90-docs/fleet-migration/capacity-plan-2026-08-28.edn`

---

## Documentation Outputs

| Document | Purpose | Owner | Due |
|-----------|---------|-------|-----|
| `fleet-migration-month-3-execution-plan.edn` | Timeline, gates, procedures | ops-lead | 2026-08-18 |
| `fleet-migration-month-3-monitoring-guide.md` | Dashboards, alerts, escalation | monitoring-team | 2026-08-18 |
| `fleet-migration-rust-fleet-decommissioning.md` | Rust shutdown procedures | infra-team | 2026-08-18 |
| `month-3-week-1-daily-log.md` | Daily action log (Week 1) | ops-lead | 2026-08-24 |
| `month-3-week-2-daily-log.md` | Daily action log (Week 2) | ops-lead | 2026-08-31 |
| `cljc-fleet-performance-report-2026-08-28.md` | Post-cutover baseline | monitoring-team | 2026-08-28 |
| `capacity-plan-2026-08-28.edn` | Headroom and scaling plan | capacity-planning | 2026-08-28 |
| `rust-fleet-final-state-2026-08-26.edn` | Final state archive | ops-lead | 2026-08-26 |
| `month-3-final-completion-report.md` | Post-mortem and lessons learned | owner | 2026-08-31 |

**Location**: All documents in `90-docs/fleet-migration/` directory (or embedded in this worktree).

---

## Success Criteria (Final)

### Week 1 Completion

✅ Traffic at 99% cljc  
✅ All SLO metrics within budget  
✅ No unrecovered exceptions  
✅ Rollback procedures tested  
✅ Rust graceful drain staged  

### Week 2 Completion

✅ Traffic at 100% cljc  
✅ Rust fleet fully decommissioned  
✅ Performance baseline captured  
✅ Capacity plan finalized  
✅ All alerts tuned  
✅ Month 3 complete  

### Month 3 Success (Overall)

✅ **100% cljc fleet live in production**  
✅ **Parity validated at each gate** (< 0.01% divergence)  
✅ **Rust infrastructure fully decommissioned**  
✅ **Performance SLO maintained throughout migration**  
✅ **New cljc-only baseline established**  
✅ **Phase 4 planning ready** (other app migration)  

---

## Risks & Mitigations

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|---|
| **Latency regression at high traffic** | Medium | High | GC tuning (ZGC/Shenandoah), monitoring, auto-revert |
| **Parity bug discovered post-cutover** | Low | High | Comprehensive parity testing (Month 1-2), staged rollout |
| **Memory leak under sustained load** | Low | Medium | Heap profile monitoring, alert at 400MB threshold |
| **Rust shutdown delay (hanging connections)** | Low | Medium | 30-sec drain timeout, force-kill fallback |
| **B2 archive upload failure** | Very Low | Low | Retry with backoff, keep workdir on nodes as backup |
| **Monitoring dashboard outage** | Very Low | Medium | Manual metrics queries (Prometheus API), alert logs in Slack |

---

## Phase 4 Follow-Up (Out of Scope)

After Month 3 completion (Sept 2026+):

1. **Other Mesh App Migration**: kenchi (valuation), kotodama-bot (ingest/reply)
2. **Staging Pool Decommissioning**: (optional) Remove staging network segregation
3. **Gossipsub Upgrade**: (optional) Enable peer-to-peer mesh discovery on cljc nodes
4. **GC Tuning Optimization**: (continuous) Monitor and adjust ZGC/Shenandoah tuning

---

## Approval & Sign-Off

| Role | Name | Approval | Date |
|------|------|----------|------|
| **Owner** | Jun Kawasaki | ✅ Approved | 2026-07-20 |
| **Ops-lead** | [TBD] | Pending | 2026-08-15 |
| **Monitoring-team** | [TBD] | Pending | 2026-08-15 |
| **Infra-team** | [TBD] | Pending | 2026-08-15 |

---

## Related Documents

- **ADR-2607072100**: kotoba-server fleet deployment & strangler-fig strategy (accepted, 2026-07-20)
- **ADR-2607072000**: Rust avoidance policy and kotoba-server gap
- **ADR-2607082400**: kotoba-server cljc realization (component model wall)
- **ADR-2607071900**: murakumo cross-node apply (cljc auction gap closed)
- **ADR-2607062330**: kototama-tender-chicory execution runtime
- **Month 1-2 Status**: See `manifest/fleet-db.edn` (parity validation checkpoints)

---

## Questions & Escalation

**For Questions About**:
- **Month 3 Timeline**: ops-lead
- **Traffic Migration Gates**: monitoring-team
- **Rust Decommissioning**: infra-team
- **Performance SLO**: monitoring-team
- **Go/No-Go Decisions**: Jun Kawasaki (owner)

**Escalation Path** (if critical issue during Month 3):
1. Page on-call (PagerDuty)
2. Notify ops-lead + monitoring-team (Slack #fleet-migration-ops)
3. Escalate to owner (Jun Kawasaki) if decision needed (email + call)
4. Escalate to infrastructure-team if system-level issue (network, disk, etc.)

---

**Document Version**: 1.0 (2026-07-21)  
**Status**: Ready for Month 3 Execution (2026-08-18 start)  
**Last Updated**: 2026-07-21  
**Next Review**: 2026-08-17 (1 day before execution)
