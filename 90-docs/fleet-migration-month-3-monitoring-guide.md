# Month 3 Fleet Migration: Live Monitoring & Dashboard Guide

**Execution Period**: 2026-08-18 to 2026-09-18  
**Status**: Execution roadmap for Strangler-Fig Month 3 (ADR-2607072100)  
**Owner**: Jun Kawasaki, Ops Team

---

## Overview

This document establishes the monitoring infrastructure, dashboard layout, and alert escalation procedures for the final cljc fleet cutover (Month 3, ADR-2607072100). The monitoring system observes traffic migration from 50% → 100% over Weeks 1–2, with automated rollback triggers and manual decision gates at each step.

### Key Metrics (Primary SLO Observables)

| Metric | Phase 1 Budget | Phase 2 Budget | Phase 3 Budget | Escalation |
|--------|---|---|---|---|
| **p50 latency (ms)** | ≤80 | ≤70 | ≤70 | warning @ 80 |
| **p95 latency (ms)** | ≤120 | ≤95 | ≤95 | warning @ 110 |
| **p99 latency (ms)** | ≤150 | ≤100 | ≤100 | **critical @ 105** |
| **Throughput loss (%)** | ≤2 | ≤1 | ≤0 | warning @ 3% |
| **JVM heap peak (MB)** | ≤256 | ≤384 | ≤256 | warning @ 400 |
| **GC pause p99 (ms)** | ≤50 | ≤30 | ≤30 | warning @ 40 |
| **Parity failure rate (%)** | ≤0.01 | ≤0.05 | ≤0.01 | **critical @ 0.1** |
| **HTTP 5xx rate (%)** | ≤0.01 | ≤0.1 | ≤0.01 | **critical @ 1.0** |

---

## Dashboard Architecture

### Platform
- **Metrics Ingestion**: murakumo-metrics (Prometheus + Grafana)
- **Log Aggregation**: murakumo/audit logs → B2 + DataLad
- **Refresh Interval**: 10 seconds (real-time during traffic shifts)
- **Retention**: 4 weeks (Month 3 complete + buffer)

### Dashboard Layout (Grafana)

**Tab 1: Traffic Migration Overview**

```
┌──────────────────────────────────────────────────────────────────┐
│ 🔴 TRAFFIC SPLIT (Gauge)                 ⏱ Real-time (10s)     │
│                                                                   │
│    cljc%: [████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░]  50%          │
│    rust%: [░░░░░░░░██████████░░░░░░░░░░░░░░░░░░░]  50%          │
│                                                                   │
│  Current: 50% cljc / 50% rust                                   │
│  Next gate: +10% (60%) @ 2026-08-21 10:00 JST                  │
│  SLO: maintain SLO at each step or revert                       │
└──────────────────────────────────────────────────────────────────┘
```

**Tab 2: Latency & Throughput**

```
┌──────────────────────────────────────────────────────────────────┐
│ 📊 HTTP Request Latency (p50/p95/p99)        Period: last 24h   │
│                                                                   │
│  p50: [line graph, target ≤70ms]                                │
│  p95: [line graph, target ≤95ms]                                │
│  p99: [line graph, RED ALERT @ >105ms for >5min]  ← CRITICAL   │
│                                                                   │
│  Threshold indicators:                                           │
│  - Green zone: ≤100ms                                           │
│  - Yellow zone: 100-110ms                                       │
│  - Red zone: >110ms (auto-revert triggered)                     │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ 📈 Request Throughput (req/sec)              Period: last 24h   │
│                                                                   │
│  cljc pool: [line graph]                                        │
│  rust pool: [line graph, baseline]                              │
│  loss %: [annotation, alert @ >3%]                              │
│                                                                   │
│  Target: throughput loss < 1% during ramp                       │
└──────────────────────────────────────────────────────────────────┘
```

**Tab 3: Parity Validation (Week 1 only)**

```
┌──────────────────────────────────────────────────────────────────┐
│ 🔍 Parity Checker Results              Canary window: last 24h   │
│                                                                   │
│  Divergence rate: [gauge, target ≤0.01%]                        │
│  Pass count: [annotation, 999,950 requests]                     │
│  Fail count: [annotation, 50 requests]                          │
│  Failure root cause: [dropdown, e.g. "kgraph-query mismatch"]   │
│                                                                   │
│  Status: 🟢 GREEN (99.995% parity)                              │
│  Alert: RED @ >0.1% sustained 1min                              │
└──────────────────────────────────────────────────────────────────┘
```

**Tab 4: JVM Health**

```
┌──────────────────────────────────────────────────────────────────┐
│ 🔧 JVM Memory & GC                        Period: last 7 days   │
│                                                                   │
│  Heap usage: [line graph]                                       │
│  Heap peak: [annotation per phase, target ≤256-384MB]           │
│  GC pause distribution: [histogram, p99 ≤30ms]                  │
│  Full GC count: [counter, alert if >2 per hour]                 │
│                                                                   │
│  GC algorithm: [ZGC/Shenandoah] (selected Month 2)             │
│  Memory leak detection: [trend indicator, stable green]         │
└──────────────────────────────────────────────────────────────────┘
```

**Tab 5: Error & Reliability**

```
┌──────────────────────────────────────────────────────────────────┐
│ ⚠️ HTTP Status Codes (5min window)                               │
│                                                                   │
│  2xx (success): [stacked bar, >99.8%]                           │
│  4xx (client error): [stacked bar, <0.2%]                       │
│  5xx (server error): [stacked bar, alert @ >1%]  ← CRITICAL    │
│                                                                   │
│  5xx rate by pod/node: [table, drill-down]                      │
│  Top 5xx errors: [log snippet, most recent 10]                  │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ 🔴 Exception & Panic Tracker                  Period: last 24h   │
│                                                                   │
│  Unhandled panics: 0 (target: 0)                                │
│  OutOfMemory errors: 0                                          │
│  Timeout errors: [count, trend]                                 │
│  Other exceptions: [breakdown by type]                          │
│                                                                   │
│  Latest panic: [timestamp, stack trace preview]                 │
└──────────────────────────────────────────────────────────────────┘
```

**Tab 6: Component Performance**

```
┌──────────────────────────────────────────────────────────────────┐
│ ⚡ drama-profile Component (Chicory)     Period: last 24h       │
│                                                                   │
│  Instantiation latency (p95): [gauge, target ≤100ms]            │
│  Instance creation success rate: [percentage, target 99.99%]    │
│  Concurrent instances: [gauge, peak observed]                   │
│  Cache hit rate: [percentage, trend]                            │
│                                                                   │
│  Compilation time (WASM): [histogram per instance]              │
│  First request after load: [latency histogram, p95]             │
└──────────────────────────────────────────────────────────────────┘
```

---

## Automated Alerts & Escalation

### Alert Definitions

#### 1. **Critical: p99 Latency Regression**
- **Trigger**: p99 > (baseline + 100ms) sustained for ≥ 5 minutes
- **Severity**: 🔴 CRITICAL
- **Action**: Automatic rollback to previous traffic % (e.g., 90% → 75%)
- **Notification**: Page on-call + owner (via Slack + PagerDuty)
- **Escalation**: If latency doesn't recover within 10 min of rollback → escalate to infrastructure team

**Message Template**:
```
🔴 CRITICAL: p99 Latency Regression Detected
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Current p99:    {current}ms
Threshold:      {threshold}ms (baseline {baseline} + 100ms)
Sustained:      {duration} (required ≥5min)
Traffic:        {cljc_pct}% cljc / {rust_pct}% rust

🔄 AUTO-REVERT INITIATED: traffic → {reverted_pct}%

Action Required:
1. Check GC logs: jvm gc pause > 100ms?
2. Check node CPU: any sustained >80%?
3. Check network I/O: any spikes?
4. If unresolved in 10 min → escalate to @infra-team

Dashboard: [link to Grafana]
```

#### 2. **Critical: Parity Failure (Week 1 Only)**
- **Trigger**: Parity failure rate > 1% sustained for ≥ 1 minute
- **Severity**: 🔴 CRITICAL
- **Action**: Full automatic revert to 0% cljc (100% Rust fallback)
- **Notification**: Page on-call + owner + dev team (Slack + PagerDuty)

**Message Template**:
```
🔴 CRITICAL: Parity Divergence Detected
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Divergence rate: {rate}% (threshold ≤0.01%)
Sustained:       {duration} (required ≥1min)
Sample divergence: [cljc response] vs [Rust response]
  Input:  {input_json}
  cljc:   {cljc_output_json}
  Rust:   {rust_output_json}

🔄 FULL REVERT INITIATED: traffic → 0% cljc (100% Rust)

Action Required:
1. Compare cljc vs Rust responses (detailed logs in parity-checker audit)
2. Identify root cause: (a) kgraph query mismatch? (b) JSON serialization? (c) app logic?
3. Fix on staging pool, re-test parity, resume migration

Root cause category: [dropdown for triage]
Logs: [link to B2 parity-checker-logs-{date}.tar.gz]
```

#### 3. **Critical: HTTP 5xx Error Spike**
- **Trigger**: HTTP 5xx rate > 1% sustained for ≥ 2 minutes
- **Severity**: 🔴 CRITICAL
- **Action**: Full automatic revert to 0% cljc (100% Rust)
- **Notification**: Page on-call + owner (Slack + PagerDuty)

**Message Template**:
```
🔴 CRITICAL: HTTP 5xx Error Spike
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
5xx rate: {rate}% (threshold ≤1%)
Sustained: {duration} (required ≥2min)
Request volume: {req_count} req/sec

Top 5xx errors:
1. {error_type_1}: {count} ({percentage}%)
2. {error_type_2}: {count} ({percentage}%)
3. {error_type_3}: {count} ({percentage}%)

🔄 FULL REVERT INITIATED: traffic → 0% cljc (100% Rust)

Action Required:
1. Check exception logs: panic? OOM? resource exhaustion?
2. Check nodes: any crashed? any saturated (CPU/memory)?
3. If OOM → revert + increase JVM Xmx + re-test
4. If panic → capture stack trace, debug on staging pool

Logs: [link to murakumo/logs/kotoba-server-{node}-{date}.log]
```

#### 4. **Warning: Memory Pressure**
- **Trigger**: JVM heap peak > 400MB per instance sustained
- **Severity**: ⚠️ WARNING
- **Action**: Page ops-lead (email + Slack, no PagerDuty unless escalated)
- **Notification**: Slack channel #fleet-migration-ops

**Message Template**:
```
⚠️ WARNING: JVM Memory Pressure
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Heap peak: {heap_mb}MB (budget ≤384-400MB)
Per-instance sustained: {duration}
Traffic load: {cljc_pct}% cljc / {req_sec} req/sec

Possible causes:
1. Memory leak in component instantiation?
2. Heap fragmentation during GC?
3. Unexpected large object allocation?

Recommended Actions:
1. Monitor trend: is heap still growing? or stable?
2. Trigger heap dump if >512MB (heap dump tool)
3. If continues to grow → escalate to critical alert

Heap dump: [if available, link to {date}-heapdump.hprof]
```

#### 5. **Info: Daily Alert Noise Report**
- **Trigger**: Nightly (23:00 JST)
- **Severity**: 📊 INFO
- **Action**: Summarize false positives, alert tuning recommendations

**Report**:
```
📊 Daily Alert Summary (2026-08-21)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total alerts fired: 5
True positives: 2 (latency spike, resolved in 10min)
False positives: 3 (network blip, metric collection lag, etc.)

False positive rate: 3/5 = 60%
Target: <20% (month 3 success threshold: <10%)

Recommendations:
1. Increase p99 threshold grace from +100ms to +110ms? (too strict)
2. Extend sustained duration from 5min to 7min? (too lenient)
3. Check for metric collection jitter (+/-5%)

Tuning decisions: [approve/defer]
```

---

## Manual Decision Gates (Weekly Review)

### Week 1 Decision Points

**Gate 1 (Day 1, Monday 2026-08-18)**: Month 2 → Month 3 Transition  
_Validator_: Owner + Ops-lead  
_Checklist_:
- [ ] Month 2 parity failure rate < 0.01% (all 4 weeks of 50% traffic)
- [ ] p99 latency ≤ 100ms consistently
- [ ] Memory per-instance ≤ 384MB peak
- [ ] HTTP 5xx rate ≤ 0.1%
- [ ] GC tuning (ZGC or Shenandoah) selected
- [ ] All monitoring dashboards live and reporting correctly

_Decision Options_:
- ✅ **GO**: Proceed to Week 1 traffic migration (50%→60%→75%→90%→99%)
- ❌ **NO-GO**: Abort Month 3, return to staging pool for fixes
- ⚠️ **GO-WITH-SLO-ESCALATION**: Proceed with relaxed budget (p99 ≤ 105ms)

_Expected Decision_: GO (or GO-WITH-SLO-ESCALATION if Month 2 metrics show <5% regression)  
_Owner Approval Required_: YES

---

**Gate 2 (Day 3, Thursday 2026-08-21)**: 50%→60% Stabilization  
_Validator_: Ops-lead + Monitoring  
_Checklist_:
- [ ] 24-hour stability at 60%: all SLO metrics nominal
- [ ] No new exceptions or panics
- [ ] Parity failure rate ≤ 0.02%
- [ ] Rollback tested (60%→50% revert successful)

_Decision Options_:
- ✅ **PROCEED**: Continue to 60%→75% step
- ⏸️ **HOLD**: Investigate anomaly, retry in 6 hours
- ↩️ **REVERT**: Return to 50%, troubleshoot

_Expected Decision_: PROCEED  
_Owner Approval Required_: NO (ops-lead autonomous)

---

**Gate 3 (Day 4, Friday 2026-08-22)**: 60%→75% Stabilization  
**Gate 4 (Day 5, Saturday 2026-08-23)**: 75%→90% Stabilization  
**Gate 5 (Day 6, Sunday 2026-08-24)**: 90%→99% Stabilization  

Same checklist as Gate 2, escalate decision to owner if metrics are borderline.

---

### Week 2 Decision Points

**Gate 6 (Day 1, Monday 2026-08-25)**: Final Cutover to 100%  
_Validator_: Owner + Ops-lead + Infra-team  
_Checklist_:
- [ ] 72-hour stability at 99% (Friday-Sunday all green)
- [ ] All SLO metrics within budget
- [ ] No unrecovered exceptions
- [ ] Rust graceful drain procedure staged and tested

_Decision Options_:
- ✅ **EXECUTE CUTOVER**: Shift to 100% cljc, initiate Rust shutdown
- ↩️ **EMERGENCY REVERT**: If any metric breach within 5min of cutover

_Expected Decision_: EXECUTE CUTOVER  
_Owner Approval Required_: YES

---

**Gate 7 (Day 3, Wednesday 2026-08-26)**: Rust Fleet Shutdown Verification  
_Validator_: Ops-lead + Infra-team  
_Checklist_:
- [ ] All 7 Rust nodes confirmed offline (systemctl status)
- [ ] Port 8077 free on all nodes
- [ ] No orphaned processes
- [ ] Logs archived to B2
- [ ] murakumo control-plane shows 0 Rust nodes

_Decision Options_:
- ✅ **CONFIRMED**: Rust fleet successfully decommissioned
- 🔧 **RESTART**: If node offline unexpectedly, restart and investigate

_Expected Decision_: CONFIRMED  
_Owner Approval Required_: NO

---

**Gate 8 (Day 7, Sunday 2026-08-31)**: Month 3 Completion  
_Validator_: Owner + Ops-lead  
_Checklist_:
- [ ] 100% cljc traffic stable for full 1 week (Mon-Sun)
- [ ] All SLO metrics within budget throughout
- [ ] No unrecovered exceptions or crashes
- [ ] Rust fleet fully decommissioned and archived
- [ ] Performance baseline captured
- [ ] Capacity plan generated
- [ ] All alerts tuned and working

_Decision Options_:
- ✅ **MONTH 3 COMPLETE**: cljc fleet production-ready, proceed to Phase 4 planning

_Expected Decision_: MONTH 3 COMPLETE  
_Owner Approval Required_: YES

---

## Rollback Decision Tree

```
┌─────────────────────────────────────────────────────────────────┐
│ SLO Metric Observes Breach (actual > threshold)                 │
└──────────────────────────────────────┬──────────────────────────┘
                                       │
                         ┌─────────────┴─────────────┐
                         │                           │
                    ┌────▼────┐            ┌────────▼──────┐
                    │ Duration?│            │ Severity?     │
                    └────┬────┘            └────────┬──────┘
                         │                           │
              ┌──────────┴──────────┐      ┌────────┴──────────┐
              │                     │      │                   │
        ┌─────▼────┐          ┌────▼──┐  ┌▼────┐           ┌─▼────┐
        │ <threshold│          │ ≥     │  │     │           │      │
        │ duration? │          │threshold?
        └─────┬────┘          └────┬──┘  │CRIT?│           │WARN? │
              │                    │      └─────┘           └──────┘
         ┌────▼─────────┐    ┌────▼─────┐    │                │
         │No Rollback   │    │AUTO      │   YES              NO
         │(transient)   │    │ROLLBACK  │    │                │
         └──────────────┘    └──────────┘    │                │
                                              │                │
                                    ┌─────────▼────────┐ ┌──────▼──────┐
                                    │Page on-call +    │ │Page ops-lead│
                                    │owner (critical)  │ │(warning)    │
                                    └──────────────────┘ └─────────────┘
```

---

## Logging & Audit Trail

### Metrics Capture (Prometheus)

Every 10 seconds during Week 1–2:
- `murakumo_traffic_cljc_percent` (gauge)
- `http_request_duration_seconds` (histogram, quantiles 0.5/0.95/0.99)
- `http_requests_total{status=2xx,4xx,5xx}` (counter)
- `jvm_memory_used_bytes{area=heap}` (gauge)
- `jvm_gc_pause_seconds{quantile=0.99}` (histogram)
- `parity_checker_divergence_rate` (gauge, Week 1 only)

**Retention**: 4 weeks (2026-07-21 to 2026-08-31), then rotate to B2 archive.

### Audit Logs (Event Log)

Each traffic shift, rollback, or decision gate logged to `murakumo/audit/fleet-migration-month-3-{date}.log`:

```edn
[{:event/timestamp "2026-08-21T10:00:00+09:00"
  :event/type :traffic-shift
  :traffic/cljc-percent-before 50
  :traffic/cljc-percent-after 60
  :operator "ops-lead"
  :decision-gate "Gate 1: 50%→60%"
  :metrics-snapshot {:p99-ms 98 :parity-fail-rate 0.0095 :heap-mb 312}
  :result :success}]
```

---

## On-Call Runbook

### Scenario 1: p99 Latency Alert (>110ms)
1. **Immediate (0-2 min)**: Check GC logs on cljc nodes
   - `kubectl logs <pod> | grep GCPause | tail -20`
   - If pause > 100ms → GC tuning issue (zoom in on heap usage graph)
2. **Investigation (2-5 min)**: Check node resources
   - CPU utilization per node (target <70%)
   - Network I/O per node (any spikes?)
   - Disk I/O (any fsync stalls?)
3. **Decision (5-10 min)**:
   - If transient (resolves in 5 min) → document in audit, no action
   - If sustained → revert traffic split by 15% and notify owner
4. **Follow-up**: Schedule root cause analysis (post-mortem)

### Scenario 2: Parity Failure Alert (>0.1%)
1. **Immediate (0-1 min)**: Halt traffic migration (auto-revert engaged)
   - Confirm revert executed: `murakumo traffic-status` (should be 0% cljc)
2. **Investigation (1-10 min)**: Compare cljc vs Rust response
   - Export parity-checker audit log: `aws s3 cp s3://kotobase.b2/parity-checker-logs-2026-08-21.tar.gz .`
   - Find first divergence: `jq '.[] | select(.diverged==true)' < logs.json | head -1`
   - Example divergence:
     ```json
     {
       "input": {"components": ["drama-profile"], "request": {...}},
       "cljc_response": {"status": "ok", "value": {...}},
       "rust_response": {"status": "error", "message": "kgraph-query failed"}
     }
     ```
3. **Root Cause**: Identify category
   - (a) **App-level bug**: cljc drama-profile or kotoba wrapper has logic error
   - (b) **Capability mismatch**: cljc missing kgraph operation that Rust supports
   - (c) **Data corruption**: differing cache state
4. **Action**: Notify dev team, return to staging pool for fix + re-test

### Scenario 3: 5xx Error Spike
1. **Immediate (0-1 min)**: Auto-revert engaged, traffic → 0% cljc
2. **Investigation (1-5 min)**: Check exception logs
   - `kubectl logs <pod> --tail=100 | grep -i exception`
   - Top 5xx types: (1) OutOfMemory? (2) FileNotFound? (3) NullPointerException? (4) TimeoutException?
3. **Resource Check**:
   - Memory: `jvm_memory_used_bytes` on heap graph (trending toward 512MB?)
   - File handles: `lsof | wc -l` (should be <5000)
   - Network sockets: `netstat -an | grep ESTABLISHED | wc -l`
4. **Recovery**:
   - If **OOM**: increase JVM Xmx (e.g., 512MB → 768MB), re-test on staging
   - If **file handle leak**: identify which component, fix, re-test
   - If **network timeout**: check murakumo router, DNS, or Rust pool health

---

## Post-Migration Monitoring (Week 3+)

After Month 3 cutover complete:

1. **Transition dashboards**: Remove parity-checker tab (no longer needed)
2. **Long-term monitoring**:
   - Daily performance review (p99 trend, memory stability)
   - Weekly capacity review (headroom > 50%?)
   - Monthly GC tuning review (pause distribution, full GC frequency)
3. **Cleanup**:
   - Archive parity-checker audit logs to B2 DataLad
   - Decommission staging pool (optional, keep for 1 month if potential rollback planned)

---

## Contacts & Escalation Matrix

| Alert Type | Severity | Primary | Secondary | Escalation |
|---|---|---|---|---|
| p99 latency regression | 🔴 CRITICAL | on-call | owner | infra-team (10min) |
| Parity divergence | 🔴 CRITICAL | on-call | owner + dev-team | product-lead (30min) |
| 5xx error spike | 🔴 CRITICAL | on-call | owner | infra-team (10min) |
| Memory pressure | ⚠️ WARNING | ops-lead | infra-team | owner (1hr) |
| GC pause spike | ⚠️ WARNING | ops-lead | — | performance-team (1hr) |

**Channels**:
- PagerDuty: Critical alerts (auto-page on-call rotation)
- Slack: #fleet-migration-ops (all alerts + decision gates)
- Email: ops@gftd.group (daily summary)

---

## Sign-Off & Approval

- **Monitoring Plan Approved**: Jun Kawasaki
- **Dashboard Ready**: 2026-08-20 (3 days before Month 3 start)
- **Alert Tuning Complete**: 2026-08-18 (1 day testing)
- **On-Call Training**: 2026-08-18 (runbook review)
