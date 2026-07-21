# Month 3 Strangler-Fig Fleet Migration Execution Plan
## ADR-2607072100 | Final Validation & Cutover

**Date**: 2026-09-11 to 2026-09-18  
**Duration**: Week 3-4 of Month 3 timeline  
**Status**: Ready for Phase 3 execution  
**Owner**: Jun Kawasaki (ops lead)  
**Scope**: 50% → 100% traffic migration, Rust fleet graceful shutdown, 100% cljc cutover  

---

## Executive Summary

Month 3 Week 1-2 covers the final validation gate and traffic migration phases. This document tracks:

1. **Final Validation (2026-09-11 to 2026-09-17)**: 1-week sentinel @ 50% traffic
2. **Traffic Migration (2026-09-11 to 2026-09-18)**: 50% → 75% → 90% → 99% → 100%
3. **Rust Fleet Graceful Shutdown**: 6-phase drain (health-check disable → connection drain → stop → verify → archive → config update)
4. **Production Cutover Validation**: All requests on cljc mesh, Rust infrastructure decommissioned

---

## Phase 3.1: Final Validation (Week 1, 2026-09-11 to 2026-09-17)

### Precondition: Phase 2 Completion Gate

Before entering Phase 3, verify Phase 2 completion checklist:

```
[✓] Traffic ramp: 5% → 50% successful, SLO maintained at each step
[✓] Parity failure rate: ≤ 0.05% (canary 50% window)
[✓] Latency sustained: p99 ≤ 100ms (optimized baseline)
[✓] Node pool: capacity sufficient (≥6 nodes for 50% load)
[✓] Alerts noise: < 5 per day false positives
[✓] GC tuning: tested ZGC / Shenandoah, baseline selected
```

### 3.1.1 Stability Sentinel Checkpoint

**Duration**: 2026-09-11 (day 1) to 2026-09-17 (day 7)

Hold 50% traffic steady for 1 full week. Collect metrics:

| Metric | Target | Action if Miss |
|--------|--------|---|
| p50 latency | ≤ 70ms | Investigate GC pause patterns |
| p95 latency | ≤ 95ms | Review thread contention |
| p99 latency | ≤ 100ms | **FAIL: Trigger rollback** |
| Parity failure | ≤ 0.01% | **FAIL: Trigger rollback** |
| HTTP 5xx rate | ≤ 0.01% | **FAIL: Trigger rollback** |
| Memory peak (per instance) | ≤ 256MB | Review heap profile |
| GC pause p99 | ≤ 30ms | Acceptable variance |
| Crash / panic | 0 (none) | **FAIL: Trigger rollback** |
| Alert noise (false positives) | < 2 per day | Tune alerting rules |

### 3.1.2 Daily Review & Escalation

Each day (2026-09-11 through 2026-09-17):

```bash
# 0900 UTC: Gather 24h metrics
murakumo/metrics-summary.sh --pool cljc --window 24h > day-N-metrics.json

# 1000 UTC: Compare to baseline
nbb manifest/metrics-compare.cljs \
  --baseline month-2-baseline.json \
  --current day-N-metrics.json \
  --threshold-p99 100 \
  --threshold-parity 0.01

# 1030 UTC: Check for panics/crashes
murakumo/instance-health-check.sh --pool cljc | jq '.crashes, .panics'

# 1100 UTC: Verify Rust pool health (sanity check - should be un-touched)
murakumo/instance-health-check.sh --pool rust-prod | jq '.status'

# Owner decision by 1200 UTC:
# - All green: continue to next day
# - Any FAIL thresholds: escalate to Phase 2 debugging OR abort Phase 3
# - Any warnings: plan mitigation for day N+1
```

### 3.1.3 Go/No-Go Gate (End of Day 7, 2026-09-17, 1500 UTC)

**Checklist for owner approval**:

```
[✓] All metrics within SLO for 7 consecutive days
[✓] Parity failure rate < 0.01%
[✓] No panics / crashes / unhandled exceptions
[✓] Alert noise < 2 per day
[✓] cljc node pool capacity stable (no OOM kills, no node restarts)
[✓] Rust pool untouched, health checks passing (ready for graceful drain)
[✓] Traffic at exactly 50%
[✓] Audit trail complete (all request/response logs captured to B2)
```

**Go decision**: Owner approval → proceed to Phase 3.2 (traffic migration)  
**No-Go decision**: Revert to Phase 2 state, debug issues, reschedule Phase 3

---

## Phase 3.2: Traffic Migration (Week 1-2, 2026-09-11 to 2026-09-18)

### Timeline Overview

```
Day 1 (2026-09-11): Sentinel starts (Phase 3.1, 50% locked)
Day 8 (2026-09-18): Sentinel day 7 + Go/No-Go decision @ 1500 UTC
    ↓ GO approved
Day 8 (2026-09-18): 50% → 75% (ramp step 1)
Day 9 (2026-09-19): Stability window (3 days at 75%)
Day 10 (2026-09-20): Stability window
Day 11 (2026-09-21): 75% → 90% (ramp step 2)
Day 12-14 (2026-09-22 to 2026-09-24): Stability window
Day 15 (2026-09-25): 90% → 99% (ramp step 3)
Day 16-18 (2026-09-26 to 2026-09-28): Stability window
Day 19 (2026-09-29): 99% → 100% (final cutover)
```

### 3.2.1 Traffic Ramp: Step 1 (50% → 75%)

**Date**: 2026-09-18, 1600 UTC (after Go decision)

```bash
# Check current state
murakumo/traffic-status.sh

# Execute ramp
murakumo/set-traffic-split.sh --cljc-percent 75 --Rust-percent 25 \
  --migration-phase 3 --step 1

# Log event
nbb manifest/fleet-ops-log.cljs \
  --event "traffic-ramp-step-1" \
  --from-pct 50 --to-pct 75 \
  --timestamp "2026-09-18T16:00:00Z"

# Verify split
murakumo/traffic-status.sh | grep -E "cljc.*percent|rust.*percent"
```

**Stability window**: 2026-09-19 to 2026-09-20 (3 days)

Each day during stability window:

```bash
# 0900 UTC: Metrics snapshot
murakumo/metrics-summary.sh --pool cljc --window 24h > day-N-metrics.json

# 1000 UTC: Sanity checks
- p99 latency ≤ 105ms (allow 5ms increase)
- parity failure < 0.05% (canary window)
- HTTP 5xx ≤ 0.1%
- No new crashes/panics

# 1100 UTC: Rust pool status
murakumo/instance-health-check.sh --pool rust-prod | jq '.status'

# 1200 UTC: Owner decision
# - Green: proceed to next ramp step
# - Yellow: extend stability window 1 more day
# - Red: REVERT to 50% (automatic rollback trigger)
```

### 3.2.2 Traffic Ramp: Step 2 (75% → 90%)

**Date**: 2026-09-21

```bash
murakumo/set-traffic-split.sh --cljc-percent 90 --Rust-percent 10 \
  --migration-phase 3 --step 2

nbb manifest/fleet-ops-log.cljs \
  --event "traffic-ramp-step-2" \
  --from-pct 75 --to-pct 90 \
  --timestamp "2026-09-21T16:00:00Z"
```

**Stability window**: 2026-09-22 to 2026-09-24 (3 days)

### 3.2.3 Traffic Ramp: Step 3 (90% → 99%)

**Date**: 2026-09-25

```bash
murakumo/set-traffic-split.sh --cljc-percent 99 --Rust-percent 1 \
  --migration-phase 3 --step 3

nbb manifest/fleet-ops-log.cljs \
  --event "traffic-ramp-step-3" \
  --from-pct 90 --to-pct 99 \
  --timestamp "2026-09-25T16:00:00Z"
```

**Stability window**: 2026-09-26 to 2026-09-28 (3 days)

**Critical observation**: At this point, Rust pool is receiving only 1% of traffic. Prepare for graceful shutdown.

### 3.2.4 Final Cutover (99% → 100%)

**Date**: 2026-09-29, 1000 UTC

This step performs the 99% → 100% implicit cutover by draining Rust pool.

---

## Phase 3.3: Rust Fleet Graceful Shutdown (2026-09-25 to 2026-09-30)

### 6-Phase Drain Procedure

#### Phase A: Disable Health Checks (2026-09-25, 1600 UTC, after 99% ramp)

**Purpose**: Signal load balancer that Rust nodes are draining (no new connections)

```bash
# For each Rust node in pool:
for node in rust-node-{1..9}; do
  ssh "${node}" "systemctl stop kotoba-health-check"
  echo "Health check disabled on ${node}" | tee -a Month-3-execution.log
  sleep 5s
done

murakumo/register-drain-status.sh --pool rust-prod --status draining

# Verify: LB should immediately stop routing new traffic to Rust nodes
murakumo/traffic-status.sh
# Expected: cljc-percent=99, rust-percent=0 (implicit, in-flight only)
```

#### Phase B: Drain Connections (2026-09-26 to 2026-09-28, 72h timeout)

**Purpose**: Wait for in-flight requests to complete (max 30s timeout)

```bash
# Monitor drain progress
while true; do
  ACTIVE_CONNS=$(murakumo/active-connections.sh --pool rust-prod | jq '.total')
  echo "$(date): Active connections on Rust pool: ${ACTIVE_CONNS}" >> Month-3-execution.log
  
  if [ "${ACTIVE_CONNS}" -eq 0 ]; then
    echo "$(date): All connections drained on Rust pool" >> Month-3-execution.log
    break
  fi
  
  sleep 30s
done

# Timeout protection: if not drained after 72h, force-drain
DRAIN_START=$(date -u +%s)
DRAIN_TIMEOUT=259200  # 72 hours in seconds

while true; do
  NOW=$(date -u +%s)
  if [ $((NOW - DRAIN_START)) -gt ${DRAIN_TIMEOUT} ]; then
    echo "$(date): Drain timeout reached, forcing shutdown" >> Month-3-execution.log
    break
  fi
  
  ACTIVE=$(murakumo/active-connections.sh --pool rust-prod | jq '.total')
  [ "${ACTIVE}" -eq 0 ] && break
  sleep 60s
done
```

#### Phase C: Stop Rust Nodes (2026-09-29, after drain)

**Purpose**: Gracefully shut down kotoba-server Rust processes

```bash
# For each Rust node:
for node in rust-node-{1..9}; do
  echo "$(date): Stopping kotoba-server on ${node}" >> Month-3-execution.log
  
  ssh "${node}" "systemctl stop kotoba-server"
  
  # Wait for graceful shutdown (30s timeout)
  ssh "${node}" "sleep 5s && systemctl is-active kotoba-server" || true
  
  # Verify stopped
  STATUS=$(ssh "${node}" "systemctl is-active kotoba-server" 2>/dev/null)
  if [ "${STATUS}" = "inactive" ]; then
    echo "$(date): ✓ kotoba-server stopped on ${node}" >> Month-3-execution.log
  else
    echo "$(date): ✗ kotoba-server still running on ${node} - force-kill" >> Month-3-execution.log
    ssh "${node}" "killall -9 kotoba-server" || true
  fi
done
```

#### Phase D: Verify Rust Nodes Stopped (2026-09-29, after stop)

**Purpose**: Confirm all Rust nodes are truly offline

```bash
echo "$(date): Verification phase - checking all Rust nodes stopped" >> Month-3-execution.log

for node in rust-node-{1..9}; do
  # Check process
  RUNNING=$(ssh "${node}" "pgrep kotoba-server | wc -l" 2>/dev/null || echo "unknown")
  
  # Check port
  PORT_OPEN=$(ssh "${node}" "nc -z 127.0.0.1 8080; echo $?" 2>/dev/null || echo "error")
  
  # Check systemd
  STATUS=$(ssh "${node}" "systemctl is-active kotoba-server" 2>/dev/null || echo "error")
  
  echo "$(date): ${node} | process=${RUNNING} port=${PORT_OPEN} systemd=${STATUS}" >> Month-3-execution.log
done

# All should show: process=0, port=1 (closed), systemd=inactive
```

#### Phase E: Archive to B2 + DataLad (2026-09-29 to 2026-09-30)

**Purpose**: Preserve Rust fleet logs/state for audit trail before decommissioning

```bash
# Collect logs from each Rust node
ARCHIVE_DATE=$(date +%Y%m%d)
ARCHIVE_DIR="rust-fleet-archive-${ARCHIVE_DATE}"

mkdir -p "${ARCHIVE_DIR}"

for node in rust-node-{1..9}; do
  echo "$(date): Archiving logs from ${node}" >> Month-3-execution.log
  
  # Collect logs
  ssh "${node}" "tar -czf /tmp/kotoba-logs.tar.gz /var/log/kotoba-server/" || true
  scp "${node}:/tmp/kotoba-logs.tar.gz" "${ARCHIVE_DIR}/${node}-logs.tar.gz" || true
  
  # Collect metrics snapshot
  ssh "${node}" "journalctl --since '2026-09-01' > /tmp/metrics.log" || true
  scp "${node}:/tmp/metrics.log" "${ARCHIVE_DIR}/${node}-metrics.log" || true
done

# Push to B2
nbb manifest/b2-upload.cljs \
  --local-dir "${ARCHIVE_DIR}" \
  --b2-bucket "kotobase-archive" \
  --b2-path "rust-fleet/${ARCHIVE_DATE}/" \
  --datalad-enable true

echo "$(date): ✓ Rust fleet archive uploaded to B2/DataLad" >> Month-3-execution.log
```

#### Phase F: Config Update (2026-09-30)

**Purpose**: Update deployment config to reflect cljc-only fleet

```bash
# Update manifest
sed -i '' \
  -e 's/fleet-active: \[rust-prod\]/fleet-active: [cljc-staging]/' \
  -e 's/rust-pool: enabled/rust-pool: disabled/' \
  manifest/fleet-db.edn

# Regenerate west.yml
nbb scripts/gen-west-manifest.cljs --entry murakumo
nbb scripts/gen-west-manifest.cljs --check

# Update routing config
nbb manifest/fleet-ops-log.cljs \
  --event "rust-pool-decommissioned" \
  --timestamp "2026-09-30T12:00:00Z" \
  --details "Rust fleet sunset complete. cljc-only production."

echo "$(date): ✓ Config updated for cljc-only production" >> Month-3-execution.log
```

---

## Automatic Rollback Triggers

These conditions trigger **autonomous rollback** (no owner confirmation required):

```clj
(defn- auto-rollback? [{:keys [latency-p99 parity-fail-rate http-5xx-rate memory-peak sustained-ms]}]
  (or
    ;; Trigger 1: Latency regression > baseline + 100ms for > 5 min
    (and (> latency-p99 (+ baseline-p99 100))
         (sustained-for :duration-ms 300000))
    
    ;; Trigger 2: Parity failure > 1% for > 1 min
    (and (> parity-fail-rate 0.01)
         (sustained-for :duration-ms 60000))
    
    ;; Trigger 3: HTTP 5xx > 1% for > 2 min
    (and (> http-5xx-rate 0.01)
         (sustained-for :duration-ms 120000))
    
    ;; Trigger 4: Memory peak > 512MB sustained
    (> memory-peak 512)))

(defn rollback-to-safe-state [trigger-reason current-state]
  ;; Revert traffic to previous stable step
  (murakumo/set-traffic-split 
    {:cljc-percent (get-safe-traffic-split current-state)})
  
  ;; Alert ops
  (send-alert {:severity :critical
               :message (str "Automatic rollback triggered: " trigger-reason)})
  
  ;; Log audit trail
  (append-audit-log 
    {:action :automatic-rollback
     :trigger trigger-reason
     :from-state current-state
     :to-state :previous-safe
     :timestamp (now)}))
```

**Rollback procedure**:

```bash
# Automatic system triggers rollback
murakumo/set-traffic-split.sh --cljc-percent <previous-safe-pct>

# Notify ops
echo "$(date): AUTO-ROLLBACK TRIGGERED - ${TRIGGER_REASON}" >> Month-3-execution.log
echo "Traffic reverted to ${PREVIOUS_PCT}% on cljc pool" >> Month-3-execution.log

# Root-cause analysis phase begins (back to Phase 2 or Phase 3.1)
```

---

## Monitoring Dashboard

Track live during execution:

```bash
# Real-time metrics (update every 60s)
watch -n 60 'murakumo/traffic-metrics.sh --pool cljc,rust-prod | jq .'

# SLO compliance
murakumo/slo-report.sh --start 2026-09-11 --end 2026-09-30 | tee Month-3-slo-report.json

# Audit trail
tail -f Month-3-execution.log
tail -f manifest/fleet-ops-log.edn
```

---

## Execution Log Template

Create file: `/Users/junkawasaki/github/com-junkawasaki/90-docs/deployment/month-3-strangler-fig-2026-09-18.edn`

```clojure
[{:deployment/id "month-3-strangler-fig-2026-09-18"
  :deployment/adr "adr-2607072100-kotoba-server-fleet-deployment-strangler-fig"
  :deployment/phase "3-final-validation-and-cutover"
  :deployment/date-start "2026-09-11"
  :deployment/date-end "2026-09-30"
  :deployment/status "ready-for-execution"
  :deployment/owner "Jun Kawasaki"
  
  :execution/timeline [
    {:date "2026-09-11" :event "sentinel-start" :traffic-pct 50 :status "in-progress"}
    {:date "2026-09-17" :event "go-no-go-gate" :decision "pending"}
    {:date "2026-09-18" :event "traffic-ramp-50->75" :traffic-pct 75 :status "pending"}
    {:date "2026-09-21" :event "traffic-ramp-75->90" :traffic-pct 90 :status "pending"}
    {:date "2026-09-25" :event "traffic-ramp-90->99" :traffic-pct 99 :status "pending"}
    {:date "2026-09-29" :event "final-cutover-99->100" :traffic-pct 100 :status "pending"}
    {:date "2026-09-30" :event "rust-shutdown-complete" :status "pending"}
  ]
  
  :metrics/slo-targets {
    :p50-latency-ms 70
    :p95-latency-ms 95
    :p99-latency-ms 100
    :parity-failure-pct 0.01
    :http-5xx-pct 0.01
    :memory-peak-mb 256
    :gc-pause-p99-ms 30
  }
  
  :rollback/triggers [
    "p99 latency > baseline+100ms sustained 5min"
    "parity divergence > 1% sustained 1min"
    "HTTP 5xx rate > 1% sustained 2min"
    "memory peak > 512MB sustained"
  ]
  
  :deployment/sign-off "pending-owner-approval"}]
```

---

## Success Criteria

Phase 3 execution is successful when:

```
[✓] Sentinel week (2026-09-11 to 2026-09-17): All SLO gates pass
[✓] Owner approve go/no-go decision (2026-09-17)
[✓] Traffic ramp: 50% → 75% → 90% → 99% (each step + 3-day stability)
[✓] Rust fleet graceful shutdown: All 6 phases complete
[✓] Final cutover: 100% traffic on cljc mesh nodes
[✓] Rust pool decommissioned: No processes, no health checks, archived
[✓] Config updated: cljc-only production mode active
[✓] Audit trail: Complete logs in B2 + DataLad
[✓] Monitoring: All dashboards transitioned to cljc-only
[✓] Post-deployment stabilization ready (Phase 4+)
```

---

## Related Documents

- **ADR-2607072100**: Main decision record (this plan's source authority)
- **Deployment checklist**: `manifest/deployment-readiness.md`
- **SLO targets**: `manifest/slo-targets.edn`
- **Monitoring dashboards**: Grafana → "kotoba-server-fleet-migration" dashboard
- **Audit trail**: `90-docs/deployment/month-3-strangler-fig-2026-09-18.edn` (this file)

---

**Last updated**: 2026-07-21  
**Status**: Ready for Phase 3 Execution (pending Phase 2 completion)  
**Owner**: Jun Kawasaki

