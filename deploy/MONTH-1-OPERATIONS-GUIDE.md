# Strangler-Fig Month 1 Operations Guide

**Reference**: ADR-2607072100 Phase 1 (Week 1–4)  
**Status**: Month 1 Staging Deployment  
**Owner**: Jun Kawasaki  
**Period**: 2026-07-20 to 2026-08-17

---

## Overview

Month 1 deploys the **strangler-fig pattern** for kotoba-server migration from pure Rust to hybrid Rust + cljc mesh nodes. This document covers the operational procedures, monitoring, and decision gates for the 4-week staging phase.

### What We're Deploying

- **3 cljc mesh nodes** in staging pool (Mac-mini hardware, kototama.tender runtime)
- **Drama-profile guest** (.kotoba-compiled WASM, running in Chicory)
- **5% canary traffic** routed to staging via murakumo HTTP dispatch
- **Parity validation service** comparing Rust and cljc responses
- **Baseline metrics collection** (2-week window)
- **Automated rollback safeguards** (5 autonomous triggers)
- **Live SLO dashboard** tracking phase 1 performance budgets

### Expected Outcomes

**Phase 1 Exit Criteria** (end of Week 4):

- [ ] 3 staging nodes healthy (uptime >99.5%)
- [ ] Parity pass rate ≥99.99% (≤0.01% divergence)
- [ ] p99 latency ≤150ms (baseline+50ms)
- [ ] Throughput loss <2% vs prod
- [ ] Memory stable ≤256MB per instance
- [ ] HTTP 5xx rate ≤0.01%
- [ ] Zero unrecovered exceptions / panics
- [ ] Baseline metrics locked (ready for Phase 2 ramp)

---

## Week 1: Infrastructure & Baseline Setup

### Week 1 Deliverables

**Infrastructure**:
- [ ] 3 staging cljc nodes registered in fleet-db.edn
- [ ] drama-profile component compiled to WASM
- [ ] Each node boots JVM, loads Chicory, ready to serve <15sec
- [ ] HTTP routes registered: GET /health, POST /mesh/http/drama-profile

**Canary & Metrics**:
- [ ] 5% traffic split active in murakumo dispatch
- [ ] Parity checker service running, writing validation logs
- [ ] Prometheus scrape targets configured for staging pool
- [ ] SLO dashboard accessible and updating

**Automation**:
- [ ] Rollback automation armed (5 triggers configured)
- [ ] Audit log directory created, first events logged
- [ ] Alert thresholds set

### Week 1 Operations Checklist

#### Day 1–2: Provision Staging Pool

```bash
# 1. Register 3 nodes in fleet
nbb deploy/month-1-staging-deployment.cljs provision

# Expected output:
#   ✓ Staging pool: staging-cljc-month1 (3 nodes)
#   ✓ Canary traffic: 5%
#   ✓ Parity checker: enabled
#   ✓ Metrics collection: collecting
#   ✓ Rollback automation: configured
#   ✓ SLO dashboard: ready

# 2. Verify node health
nbb deploy/month-1-staging-deployment.cljs health

# Expected: All 3 nodes return HTTP 200 /health
```

#### Day 3–4: Start Canary Routing

```bash
# 1. Enable 5% canary traffic
nbb deploy/month-1-staging-deployment.cljs canary-start

# 2. Start parity checker service (long-running)
nbb deploy/parity-checker-service.cljs \
  --rust-pool-url http://localhost:8000 \
  --staging-pool-url http://localhost:8001 \
  --sample-rate 1.0 \
  --output metrics/parity-validation.log

# 3. Start rollback automation monitor (long-running)
nbb deploy/rollback-automation.cljs monitor \
  --metrics-endpoint http://localhost:9090/api/v1/query \
  --check-interval-sec 30 \
  --dry-run false
```

#### Day 5–7: Baseline Stabilization

```bash
# Monitor metrics in real-time
watch -n 1 'tail -20 metrics/parity-validation.log'

# Check SLO dashboard
open http://localhost:3000/d/strangler-fig-month1

# Expected during Week 1:
# • p99 latency: ~120-130ms (establishing baseline)
# • parity pass: 99.99%+
# • memory: 200-240MB (stabilizing)
# • 5xx rate: <0.01%

# Review daily metrics summary
tail -5 metrics/parity-validation.log
```

### Week 1 Troubleshooting

**Issue**: Staging nodes failing to boot

```bash
# Check JVM startup logs
journalctl -u murakumo-staging-1 -n 100

# Verify Chicory can load WASM
nbb test-chicory-load.cljs --wasm-path bin/drama-profile.wasm
```

**Issue**: Parity divergence detected

```bash
# Review divergence in detail
grep "DIVERGENCE" metrics/parity-validation.log | head -10

# Inspect first divergence
cat metrics/parity-validation.log | \
  grep "hash-mismatch\|status-mismatch" | \
  head -1 | jq .

# Trigger comparison dump (for debugging)
# Compare request/response bodies between pools
```

**Issue**: Latency spike >150ms in staging

```bash
# Check memory usage
nbb deploy/rollback-automation.cljs status

# Monitor GC pause times
grep "jvm_gc_pause_ms" metrics/parity-validation.log | tail -20

# If spike is transient (<5min):
#   → Continue monitoring, likely GC pause
# If sustained (>5min):
#   → Investigate application logic, may indicate cljc slowness
```

---

## Week 2: Baseline Validation Checkpoint

### Week 2 Deliverables

**Baseline Locked**:
- [ ] p99 latency stable ±5% for 3 consecutive days
- [ ] Parity pass rate ≥99.99% over 7-day window
- [ ] Memory profile shows no growth trend (stable gc cycles)
- [ ] Latency delta (staging - rust) <50ms sustained

**GC Evaluation**:
- [ ] Test ZGC and Shenandoah on staging nodes
- [ ] Measure pause times, throughput impact
- [ ] Select baseline GC algorithm for Phase 2

**Analysis**:
- [ ] Review parity-validation.log for patterns (if any divergences)
- [ ] Document any anomalies or transient spikes
- [ ] Confirm all SLO targets met

### Week 2 Checkpoint Gate

```bash
# Run comprehensive baseline analysis
nbb deploy/baseline-analysis.cljs \
  --parity-log metrics/parity-validation.log \
  --output-dir reports/

# Expected analysis output:
# • metrics-baseline.edn: locked p50/p95/p99 thresholds
# • parity-summary.edn: pass rate, divergence analysis
# • memory-profile.edn: heap usage trends
# • gc-tuning-recommendations.edn
```

**Week 2 Owner Review Meeting**:

Agenda:
1. Baseline metrics review (p99 latency, parity, memory)
2. GC tuning evaluation results
3. Divergence analysis (if any found)
4. Go/No-Go decision for Phase 2

**Decision**:

- [ ] **Go**: Baseline stable, parity 99.99%+, p99 ≤150ms → Advance to Phase 2
- [ ] **Hold**: Re-baseline for 1 more week (rare, <5% expected)
- [ ] **Abort**: Critical issue found → Return to staging debugging (very rare)

---

## Week 3–4: Extended Validation & Phase 1 Completion

### Week 3: Continued Monitoring

```bash
# Maintain 5% canary traffic
# Parity checker and rollback automation continue running

# Daily checks:
watch -n 60 'echo "=== DAILY STATUS ===" && \
  echo "Parity:" && \
  tail -1 metrics/parity-validation.log && \
  echo "Rollback triggers:" && \
  nbb deploy/rollback-automation.cljs status | grep -A5 "Sustained"'

# Weekly comparison (Week 3 vs Week 2):
diff reports/metrics-baseline.edn reports/metrics-week3.edn
```

### Week 4: Phase 1 Completion Review

**Final Verification** (7-day stability window):

```bash
# 1. Verify SLO compliance over last 7 days
nbb deploy/phase-1-validation.cljs \
  --parity-log metrics/parity-validation.log \
  --metrics-window 7d

# Expected output:
#   ✓ p99 latency: 98–102ms (stable, <150ms budget)
#   ✓ Parity pass: 99.99%+ (>99.99% target)
#   ✓ Memory: 220–250MB peak (stable, <256MB budget)
#   ✓ 5xx rate: 0.001% (<0.01% budget)
#   ✓ No rollback triggers fired

# 2. Generate final report
nbb deploy/phase-1-completion-report.cljs \
  --output reports/phase-1-final.md
```

**Phase 1 Completion Checklist**:

- [ ] All metrics within SLO targets for 7 consecutive days
- [ ] Parity failure rate ≤0.01% (no unexplained divergences)
- [ ] Zero unrecovered exceptions in staging pool
- [ ] Audit trail complete and clean
- [ ] Memory leak detection passed (4-week heap profile)
- [ ] Rollback automation tested and ready for Phase 2
- [ ] GC tuning selected (ZGC/Shenandoah baseline)
- [ ] Owner approval signed off

**Week 4 Final Gate Meeting**:

Participants: Jun Kawasaki (owner), Ops team, Technical lead

Review:
1. Phase 1 final metrics report
2. Parity validation summary
3. Incident report (if any divergences found)
4. GC tuning recommendation
5. Rollback automation status (ready for Phase 2?)
6. **Go/No-Go for Phase 2** (traffic ramp 5% → 50%)

**Expected Decision**: **Go to Phase 2**

---

## Monitoring & Metrics

### SLO Dashboard Access

```
http://localhost:3000/d/strangler-fig-month1
```

**Dashboard Panels**:

| Panel | Metric | Target | Alert Threshold |
|-------|--------|--------|-----------------|
| p99 Latency | http_request_latency_p99_ms | ≤150ms | >200ms |
| Parity Pass | parity_success_rate_pct | ≥99.99% | <99.0% |
| HTTP 5xx | http_5xx_rate_pct | ≤0.01% | >0.1% |
| Memory | jvm_heap_peak_mb | ≤256MB | >512MB |
| Throughput | req/sec | baseline | -5% loss |
| GC Pause p99 | jvm_gc_pause_ms | ≤50ms | >100ms |

### Live Monitoring Commands

```bash
# Tail parity validation (real-time)
tail -f metrics/parity-validation.log | jq '.parity-pass?'

# Parity statistics (5min window)
tail -300 metrics/parity-validation.log | \
  jq -s 'map(select(.parity-pass?)) | length as $pass | \
         map(select(.parity-pass? | not)) | length as $fail | \
         {pass: $pass, fail: $fail, rate: (($pass / ($pass + $fail)) * 100)}'

# Latency trends
tail -100 metrics/parity-validation.log | \
  jq '.latency | .staging-ms' | \
  sort -n | tail -5

# Memory usage
tail -20 metrics/parity-validation.log | \
  grep "jvm_heap_peak_mb" | \
  jq '.value'
```

### Alert Conditions

Automatic alerts fired to ops team:

- **Severity: CRITICAL**
  - Parity divergence rate >1% for 1min
  - p99 latency >150ms for 5min
  - HTTP 5xx >1% for 2min
  - Memory >512MB sustained

- **Severity: WARNING**
  - p99 latency >140ms for 10min
  - Parity divergence >0.1% for 5min
  - GC pause >100ms any occurrence

---

## Rollback Procedures

### Automatic Rollback (No Approval Needed)

Triggered by autonomous safeguards, logged immediately:

```bash
# View rollback history
tail -50 metrics/rollback-audit.log

# Check current rollback status
nbb deploy/rollback-automation.cljs status
```

**Automatic triggers fire → revert → alert ops**:

1. **Latency Regression** (p99 >150ms × 5min)
   - Action: Reduce cljc traffic by 50%
   - Result: From 5% → 2.5% cljc, monitor 5min
   - If stable: hold. Else: continue reducing.

2. **Parity Divergence** (>1% × 1min)
   - Action: Full revert to 100% Rust
   - Result: cljc staging pool drained gracefully, Rust handles 100%

3. **Reliability Degradation** (5xx >1% × 2min)
   - Action: Full revert to 100% Rust
   - Same process as parity divergence

4. **Memory Exhaustion** (heap peak >512MB)
   - Action: Reduce traffic + increase node pool size
   - Result: 5% → 2.5%, provision 2 more cljc nodes, re-test

### Manual Rollback (Owner Decision)

```bash
# Request manual rollback (for debugging or business decision)
nbb deploy/month-1-staging-deployment.cljs rollback

# Steps executed:
# 1. Set cljc traffic = 0%
# 2. Enable Rust pool health checks
# 3. Graceful drain (30sec timeout)
# 4. Log rollback event with owner approval
# 5. Send alert to ops
```

### Post-Rollback Analysis

```bash
# If rollback occurred, analyze root cause
nbb deploy/incident-analysis.cljs \
  --rollback-trigger <trigger-id> \
  --output reports/incident-<date>.md

# For application-level parity violations:
# → Fix in staging environment
# → Re-run parity validation
# → If fixed, resume canary traffic
# → Document fix in ADR ledger
```

---

## Decision Gates & Sign-Offs

### Week 1 Gate: Baseline Ready

**Questions**:
- [ ] All 3 nodes healthy and ready to serve?
- [ ] Parity checker running, collecting data?
- [ ] Metrics collected for ≥3 days?
- [ ] No critical errors in logs?

**Owner Sign-Off**: Jun Kawasaki

### Week 2 Gate: Baseline Locked

**Questions**:
- [ ] Metrics stable ±5% for ≥3 consecutive days?
- [ ] Parity pass rate ≥99.99% for 7-day window?
- [ ] GC tuning evaluated (ZGC vs Shenandoah)?
- [ ] All SLO targets met?

**Owner Sign-Off**: Jun Kawasaki

### Week 4 Gate: Phase 1 Complete → Phase 2 Go

**Questions**:
- [ ] All SLO targets met continuously for 7 days?
- [ ] Zero rollback triggers in final week?
- [ ] Parity <0.01% failure rate sustained?
- [ ] Ready to ramp traffic 5% → 50%?

**Owner Sign-Off**: Jun Kawasaki

**Next**: Phase 2 traffic ramp (Month 2)

---

## Related Documents

- **ADR**: `90-docs/adr/2607072100-kotoba-server-fleet-deployment-strangler-fig.edn`
- **Deployment Scripts**:
  - `deploy/month-1-staging-deployment.cljs` — provision/status
  - `deploy/parity-checker-service.cljs` — validation
  - `deploy/rollback-automation.cljs` — safeguards
  - `deploy/slo-dashboard-config.edn` — metrics
- **Runbooks**:
  - `deploy/MONTH-1-OPERATIONS-GUIDE.md` (this file)
  - `deploy/INCIDENT-RESPONSE.md` (if needed)

---

## Support & Escalation

**On-Call**: Ops team  
**Escalation Path**: Jun Kawasaki → kotoba-lang team lead

**Common Questions**:

**Q: What if parity divergence is detected?**  
A: Automatic rollback fires (1% threshold), full revert to Rust. Owner investigates root cause. If application-level bug: fix in staging, re-validate, resume.

**Q: Can we pause canary traffic for debugging?**  
A: Yes, use `nbb deploy/month-1-staging-deployment.cljs canary-pause` for manual pause. Auto-resumes after 1 hour unless extended.

**Q: What's the maximum acceptable latency delta (cljc vs Rust)?**  
A: 50ms budget (cljc p99 - Rust p99 < 50ms). If sustained >50ms: investigate Chicory/WASM overhead or app logic.

---

*Last Updated*: 2026-07-20  
*Version*: 1.0  
*Status*: ACTIVE — Month 1 Staging Phase
