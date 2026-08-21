# Month 3 Rollback Procedure — kotoba-server Fleet Emergency Recovery

**Status**: READY FOR EXECUTION  
**Prepared**: 2026-07-21  
**Target**: < 15 min to complete emergency rollback

---

## Executive Summary

This document defines the complete rollback procedure for Month 3 canary deployment (2026-09-11 onward). Two types of rollback are defined:

1. **Automatic Rollback** (autonomous, no owner confirmation)
2. **Manual Rollback** (owner decision)

All rollbacks must complete within **15 minutes**, ensuring production stability and minimal user impact.

---

## Rollback Overview

### What is Rollback?

**Rollback** = revert traffic from cljc mesh nodes (staging/canary fleet) back to Rust kotoba-server (production fleet). This includes:
- Stopping traffic to cljc fleet
- Resuming traffic to Rust fleet
- Gracefully closing cljc connections (if needed)
- Confirming Rust fleet health + ready to serve

### When to Rollback?

- **Automatic**: Performance regression, parity divergence, reliability issues, capacity exhaustion
- **Manual**: Owner decision (business priority, application fix needed, team readiness gap)

### Rollback Triggers

See detailed triggers in **Automatic Rollback Triggers** section below.

---

## Automatic Rollback Triggers

These conditions trigger **autonomous rollback** without requiring owner confirmation (though alert is sent).

### Trigger 1: Latency Regression

**Condition**:
```
p99 latency > (baseline p99 + 100ms) 
  sustained for > 5 minutes
```

**Example**:
- Baseline p99 = 100ms (from Phase 2 pre-flight validation)
- Trigger threshold = 200ms
- If p99 observed = 210ms for ≥ 5 min → ROLLBACK

**Action**: Revert traffic to previous stage traffic percentage
- Stage 1 (75% cljc) → revert to Phase 2 (50% cljc / 50% Rust)
- Stage 2 (90% cljc) → revert to 75% cljc / 25% Rust
- Stage 3a (99% cljc) → revert to 90% cljc / 10% Rust
- Stage 3b (100% cljc) → revert to 99% cljc / 1% Rust

**Timeline**: < 2 min (traffic change is immediate)

**Automation**: `murakumo/rollback.cljs` monitors Prometheus `drama_profile_http_latency_p99` metric, evaluates sustained duration, triggers revert.

---

### Trigger 2: Parity Divergence

**Condition**:
```
parity failure rate > 1% 
  sustained for > 1 minute
```

**Example**:
- Expected parity pass rate = 99.99% (≤ 0.01% failure)
- Trigger threshold = 1% failure rate = 10,000 divergence per 1M requests
- If observed > 10,000 per 1M for ≥ 1 min → ROLLBACK

**Action**: Full rollback to Phase 2
- Immediate traffic revert: 100% Rust (0% cljc)
- This is a full revert, not incremental step-back

**Timeline**: < 2 min

**Automation**: `murakumo/parity-checker.cljs` detects divergence, evaluates sustained count, triggers full revert.

**Rationale**: Parity divergence indicates data consistency issue or semantic mismatch. Not salvageable by traffic reduction — must exit cljc entirely.

---

### Trigger 3: Reliability Failure (HTTP 5xx Spike)

**Condition**:
```
HTTP 5xx error rate > 1% 
  sustained for > 2 minutes
```

**Example**:
- Expected 5xx rate = ≤ 0.01%
- Trigger threshold = 1% = 10,000 errors per 1M requests
- If observed > 10,000 per 1M for ≥ 2 min → ROLLBACK

**Action**: Revert traffic to previous stage
- Stage 1 (75% cljc) → Phase 2 (50% cljc / 50% Rust)
- Stage 2 (90% cljc) → 75% cljc / 25% Rust
- Stage 3a (99% cljc) → 90% cljc / 10% Rust
- Stage 3b (100% cljc) → 99% cljc / 1% Rust

**Timeline**: < 2 min

**Automation**: `murakumo/rollback.cljs` monitors Prometheus `drama_profile_http_5xx_rate` metric.

**Rationale**: 5xx spike indicates unhandled exceptions, panics, or resource exhaustion in cljc fleet. Reducing traffic may allow recovery.

---

### Trigger 4: Memory Exhaustion

**Condition**:
```
JVM heap peak memory per instance > 512 MB
  at any observed time (not averaged)
```

**Example**:
- Expected peak = 256 MB (stable) to 384 MB (peak during ramp)
- Trigger threshold = 512 MB
- If any instance measured > 512 MB → ROLLBACK

**Action**: Revert traffic + scale down cljc node pool
- Revert to Phase 2 (50% cljc / 50% Rust)
- Scale cljc nodes from N → N-2 (reduce capacity to lower memory pressure)
- Investigate memory leak in staging

**Timeline**: < 5 min (includes node scaling)

**Automation**: `murakumo/rollback.cljs` monitors Prometheus `jvm_memory_heap_used_bytes` metric (per instance), triggers revert + scale.

**Rationale**: Memory exhaustion indicates leak or pathological allocation. Reducing traffic + node count prevents OOM crashes.

---

### Automatic Rollback Implementation (murakumo/rollback.cljs)

```clojure
(ns murakumo.rollback
  (:require [prometheus.client :as prom]
            [slack.api :as slack]))

;; Monitor loop (runs every 30 seconds)
(defn evaluate-rollback-conditions []
  (let [metrics (prom/query-latest
                  {:latency_p99 "drama_profile_http_latency_p99"
                   :parity_fail_rate "parity_checker_failure_rate"
                   :http_5xx_rate "drama_profile_http_5xx_rate"
                   :memory_peak "jvm_memory_heap_used_bytes{instance=~\"cljc-.*\"}"})
        violations (detect-sustained-violations metrics)]
    (doseq [{:keys [trigger] :as violation} violations]
      (when (should-auto-rollback? violation)
        (execute-rollback violation)))))

(defn detect-sustained-violations [metrics]
  ;; Query Prometheus history for sustained duration
  ;; Returns list of violations with trigger type + duration
  (let [latency (prom/query-range "drama_profile_http_latency_p99" 
                                  :duration "5m" :step "10s")
        parity (prom/query-range "parity_checker_failure_rate" 
                                 :duration "1m" :step "10s")
        errors (prom/query-range "drama_profile_http_5xx_rate" 
                                 :duration "2m" :step "10s")
        memory (prom/query-latest "jvm_memory_heap_used_bytes")]
    (concat
      (when (sustained-above? latency (+ baseline-p99 100) 300) ;; 5 min
        [{:trigger :latency-regression :duration-ms 300000}])
      (when (sustained-above? parity 0.01 60) ;; 1 min
        [{:trigger :parity-divergence :duration-ms 60000}])
      (when (sustained-above? errors 0.01 120) ;; 2 min
        [{:trigger :error-rate-spike :duration-ms 120000}])
      (when (some #(> % 512) (values memory))
        [{:trigger :memory-exhaustion :duration-ms 0}]))))

(defn execute-rollback [{:keys [trigger current-traffic-pct]}]
  (case trigger
    :latency-regression
    (do
      (slack/notify "#prod-gates-wave5" 
                    "🚨 Auto-rollback: Latency regression detected (p99 > threshold)")
      (revert-traffic-to-previous-stage current-traffic-pct)
      (audit-log :rollback :trigger trigger :timestamp (now)))
    
    :parity-divergence
    (do
      (slack/notify "#prod-gates-wave5" 
                    "🚨 Auto-rollback: Parity divergence (> 1%, full revert)")
      (set-traffic-split {:cljc-pct 0 :rust-pct 100})  ;; Full revert
      (pagerduty/alert "prod-gates" "Parity divergence, full rollback triggered")
      (audit-log :rollback :trigger trigger :timestamp (now)))
    
    :error-rate-spike
    (do
      (slack/notify "#prod-gates-wave5" 
                    "🚨 Auto-rollback: Error rate spike (5xx > 1%)")
      (revert-traffic-to-previous-stage current-traffic-pct)
      (audit-log :rollback :trigger trigger :timestamp (now)))
    
    :memory-exhaustion
    (do
      (slack/notify "#prod-gates-wave5" 
                    "🚨 Auto-rollback: Memory exhaustion (heap > 512MB)")
      (scale-down-cljc-nodes)  ;; N → N-2
      (set-traffic-split {:cljc-pct 50 :rust-pct 50})  ;; Phase 2
      (pagerduty/alert "prod-gates" "Memory exhaustion, scaled down + reverted")
      (audit-log :rollback :trigger trigger :timestamp (now)))))

(defn revert-traffic-to-previous-stage [current-pct]
  (let [prev-pct (get {75 50, 90 75, 99 90, 100 99} current-pct)]
    (set-traffic-split {:cljc-pct prev-pct :rust-pct (- 100 prev-pct)})))
```

---

## Manual Rollback Triggers

These conditions require **owner decision** (murakumo-owner or platform-lead approval).

### Trigger: Application-Level Parity Fix Needed

**Scenario**: Parity checker finds kgraph query semantics mismatch (not infrastructure issue).

**Decision Process**:
1. **Detection**: Parity divergence detected in Stage 1 or Stage 2
2. **Triage**: Investigate root cause
   - If infrastructure issue (memory, latency, etc.) → auto-rollback
   - If application issue (kgraph semantics, query logic) → manual review needed
3. **Owner Decision**: murakumo-owner decides
   - **Option A**: Rollback to Phase 2, fix in staging, re-validate
   - **Option B**: Continue with reduced SLO (accept divergence, investigate post-cutover)

**Execution** (if Option A):
```bash
# Slack: @murakumo-owner approved manual rollback to Phase 2
# Reason: kgraph query semantics mismatch in drama-profile component

nbb murakumo/rollback.cljs \
  --to-stage phase2 \
  --reason "application-level kgraph fix needed" \
  --owner-approval "2026-09-11 11:00 JST (@murakumo-owner Slack approval)"
```

**Timeline**: < 5 min to execute, + 3–5 days to fix in staging

---

### Trigger: Performance Budget Miss (Acceptable Range)

**Scenario**: p99 latency = 110ms (target 100ms), within acceptable margin but exceeding hard budget.

**Decision Process**:
1. **Detection**: p99 latency sustained at 110ms (above hard budget, within +10% margin)
2. **Owner Decision**: murakumo-owner decides
   - **Option A**: Escalate SLO to 110ms, continue with monitoring
   - **Option B**: Rollback, investigate, re-optimize

**Execution** (if Option A - escalate SLO):
```bash
# Slack: @murakumo-owner approved SLO escalation
# New p99 SLO: 110ms (instead of 100ms)
# Reason: Acceptable variance during ramp, GC tuning underway

# Update monitoring alert threshold
nbb murakumo/update-slo.cljs --metric p99-latency --new-threshold 110ms
```

**Timeline**: < 2 min to adjust alert threshold

---

### Trigger: Business Priority Change

**Scenario**: Urgent production issue in Rust fleet requires immediate attention.

**Decision Process**:
1. **Alert**: Ops team detects issue in Rust production fleet
2. **Owner Decision**: murakumo-owner + platform-lead decide
   - **Option A**: Pause canary, resolve Rust issue, resume canary next day
   - **Option B**: Continue canary (issue independent of cljc fleet)

**Execution** (if Option A - pause):
```bash
# Slack: @platform-lead approved canary pause
# Reason: Critical production issue in Rust fleet (details: ...)
# Pause duration: < 24 hours, resume 2026-09-12 or 2026-09-13

nbb murakumo/pause-canary.cljs \
  --reason "production-issue-rust-fleet" \
  --owner-approval "2026-09-11 (@platform-lead Slack approval)"
  
# Current traffic state frozen until resume
# Monitoring continues, alerts armed
```

**Timeline**: < 2 min to pause, depends on issue resolution

---

### Trigger: Team Readiness Gap

**Scenario**: On-call engineer unavailable, team insufficient for safe canary operation.

**Decision Process**:
1. **Alert**: Ops lead detects staffing gap
2. **Owner Decision**: murawko-owner + ops-lead decide
   - **Option A**: Delay canary 24 hours (until sufficient team available)
   - **Option B**: Scale down canary scope (reduced traffic ramp, lower targets)

**Execution** (if Option A - delay):
```bash
# Slack: @murakumo-owner approved canary delay (24h)
# Reason: On-call engineer unavailable, insufficient staffing
# New start date: 2026-09-12 (or 2026-09-13)

nbb murakumo/delay-canary.cljs \
  --new-start-date "2026-09-12" \
  --reason "team-readiness-gap" \
  --owner-approval "2026-09-11 (@murakumo-owner Slack approval)"
```

**Timeline**: < 5 min to adjust timeline

---

## Rollback Procedures by Stage

### Stage 1 Rollback (50% → 75%)

**If Auto-Rollback Triggered**:

```bash
# Automated execution (no manual step required)
# murakumo/rollback.cljs detects trigger, executes revert

# Rollback action:
#   Traffic: 75% cljc → 50% cljc (revert to Phase 2 state)
#   Duration: < 2 minutes
#   cljc Fleet: remains running (no shutdown)
#   Rust Fleet: resumes 50% traffic

# Monitoring:
#   - Confirm Rust fleet health (all nodes healthy, traffic accepting)
#   - Confirm cljc fleet metrics stable (p99 latency drops back < 100ms)
#   - Parity checker continues running (validates divergence is gone)
```

**Alert Notification**:
```
Slack #prod-gates-wave5:
"🚨 Auto-rollback triggered: Latency regression
  Trigger: p99=210ms > threshold (100ms+100ms)
  Duration: sustained 5min
  Action: Reverting traffic 75%→50% (Phase 2 state)
  Time: 2026-09-11 11:15 JST
  Owner: @murawko-ops-lead monitoring recovery"
```

**Owner Action**:
1. Review Slack alert
2. Confirm rollback completed (traffic shows 50% cljc, 50% Rust)
3. Investigate root cause in parallel
   - Check GC logs (any pause > 50ms?)
   - Check cljc fleet logs (any exceptions?)
   - Check network (DNS, latency to Rust fleet?)
4. Decide: Fix & retry Stage 1, or escalate to platform-lead

---

### Stage 2 Rollback (75% → 90%)

**Procedure**: Same as Stage 1, but revert to 75% cljc

**Rollback Action**:
```
Traffic: 90% cljc → 75% cljc
Revert to: Stage 1 final state (stable)
Duration: < 2 minutes
```

**Escalation**: If Stage 2 rollback triggered, notify platform-lead for decision:
- **Option A**: Fix & retry Stage 2 (same 1-week timeline as before)
- **Option B**: Extend debugging (additional 3–5 days), abort current canary

---

### Stage 3a Rollback (90% → 99%)

**Procedure**: Same as Stage 1/2, but revert to 90% cljc

**Special Consideration**: Stage 3a is final traffic ramp before explicit cutover. If rollback triggered:
1. Revert to 90% cljc / 10% Rust
2. Pause before proceeding to Stage 3b (explicit cutover)
3. Extend monitoring window (additional 24–48 hours) before re-attempting 99%

---

### Stage 3b Rollback (100% → explicit cutover failure)

**If Rollback Triggered During Explicit Cutover**:

```
Condition: Auto-rollback triggered during 100% traffic transition
Action: Revert traffic 100% cljc → 99% cljc / 1% Rust
Duration: < 2 minutes
Rust Fleet: Resume 1% traffic, graceful drain cancelled
```

**Special Procedure**: Graceful Rust Drain Cancellation

If graceful drain has already started (Stage 3 traffic → 100%):
1. **Interrupt Drain**: Rust nodes resume accepting traffic (health check restored)
2. **Restore Traffic**: Redirect 1% of requests back to Rust pool
3. **Monitor**: Confirm Rust nodes recover (no cascading failures)
4. **Decision**: murawko-owner + platform-lead decide
   - Fix issue in staging, re-attempt Stage 3b
   - OR abort canary, return to Phase 2 for extended debugging

**Alert Notification**:
```
Slack #prod-gates-wave5 + PagerDuty prod-gates:
"🚨 CRITICAL: Auto-rollback during Stage 3 explicit cutover
  Trigger: [latency/parity/5xx/memory - specify]
  Action: Reverted traffic 100%→99% cljc, restored 1% Rust
  Graceful drain: CANCELLED, Rust nodes resuming traffic
  Time: 2026-09-13 14:45 JST
  Owner: @murawko-owner + @platform-lead decision needed"
```

---

## Rollback Execution Timeline

### T+0 (Detection)
- Prometheus alert fires (sustained violation detected)
- `murakumo/rollback.cljs` evaluates condition
- Slack notification sent to `#prod-gates-wave5`

### T+1–2 min (Execution)
- Traffic split adjusted (cljc % reduced, Rust % increased)
- Kubernetes pod routing updated (or DNS fallback if needed)
- Health checks update (Rust nodes confirm accepting traffic)

### T+2–5 min (Monitoring)
- Metrics refresh (confirm p99 dropping, parity rate normalizing)
- Rust fleet observability dashboard confirms node health
- Owner receives follow-up Slack notification (rollback successful)

### T+5–15 min (Post-Rollback Assessment)
- Investigate root cause (logs, metrics, exceptions)
- Document incident (Slack thread)
- Decide: Fix & retry, or abort

---

## Data Consistency During Rollback

### Key Principle

**Rollback does NOT require data synchronization**. The cljc fleet is stateless (kgraph-assert! and kgraph-query are read-only operations on shared state). Reverting traffic back to Rust is safe because:

1. **State is not replicated** between Rust and cljc fleets
2. **Requests are idempotent** (same input → same output, regardless of runtime)
3. **No pending writes** in cljc fleet that would be lost

### Validation After Rollback

After traffic is reverted to Rust:
1. **Parity checker**: Validate responses from both Rust and cljc match (for any remaining canary traffic)
2. **Data consistency check**: Query same dataset from both fleets, confirm identical results
3. **No data loss**: Confirm all requests processed before rollback are persisted

---

## Rollback Procedure Testing (Pre-Deployment)

### Fire Drill: Simulate Latency Regression Trigger

**Date**: 2026-09-08 (during pre-flight gate preparation)

**Procedure**:
1. **Setup**: Deploy test scenario in staging (50% traffic load)
2. **Trigger Injection**: Artificially inject GC pause > 100ms (via JVM flag)
3. **Observe**: Confirm p99 latency spike detected
4. **Auto-Rollback**: Verify rollback script executes (traffic split changes)
5. **Recovery**: Monitor metrics return to normal
6. **Sign-off**: Owner approves fire drill as successful

**Success Criteria**:
- ✅ Latency spike detected within 30 sec
- ✅ Auto-rollback executed within 2 min
- ✅ Traffic successfully reverted (50% → 25% cljc)
- ✅ Rust fleet resumed traffic without errors
- ✅ Metrics recovered within 5 min

### Fire Drill: Simulate Parity Divergence Trigger

**Date**: 2026-09-08

**Procedure**:
1. **Setup**: Deploy test kgraph query mismatch in staging
2. **Trigger Injection**: Modify cljc kgraph-query logic to return incorrect result
3. **Observe**: Confirm parity checker detects divergence
4. **Auto-Rollback**: Verify full rollback executes (0% cljc, 100% Rust)
5. **Confirmation**: Monitor divergence rate drops to 0%
6. **Sign-off**: Owner approves fire drill

**Success Criteria**:
- ✅ Divergence detected within 30 sec
- ✅ Full rollback executed within 2 min
- ✅ Traffic 100% → 0% cljc
- ✅ Rust fleet handling 100% without errors
- ✅ Parity rate recovered to 0%

---

## Post-Rollback Analysis

### Incident Report Template

**File**: `90-docs/deployment/MONTH-3-ROLLBACK-INCIDENT-[DATE-TIME].md`

```markdown
# Rollback Incident Report — [Date/Time]

## Summary
- **Trigger**: [latency-regression / parity-divergence / error-rate-spike / memory-exhaustion / manual]
- **Stage**: [Phase 2 / Stage 1 / Stage 2 / Stage 3a / Stage 3b]
- **Traffic Before**: [75% cljc / 90% cljc / etc.]
- **Traffic After**: [50% cljc / 75% cljc / etc.]
- **Duration**: [T+0 to T+X min]
- **Slack Alert**: [link to #prod-gates-wave5 thread]

## Root Cause
- **Analysis**: [Investigation findings]
- **Culprit**: [Component / Configuration / Dependency]
- **Why**: [Technical explanation]

## Remediation
- **Fix**: [Code change / Configuration adjustment / Dependency upgrade]
- **Testing**: [Staging validation plan]
- **Retry Timeline**: [When to re-attempt stage]

## Lessons Learned
- **Prevention**: [How to prevent similar incident]
- **Monitoring**: [New alert / metric to watch]

## Sign-Off
- **Investigator**: [Owner]
- **Approval**: [Platform Lead]
- **Date**: [Incident date]
```

---

## Rollback Abort Criteria

**ABORT canary entirely** (return to Phase 2, do not retry) if:

1. **Multiple rollback triggers** (> 2) within single stage
   - Example: Stage 1 experiences both latency regression AND parity divergence
   - Action: Escalate to platform-lead, request 1-week debugging extension

2. **Rollback failure** (traffic not successfully reverted)
   - Example: Kubernetes routing update fails, traffic stuck at 50% cljc
   - Action: Manual intervention, emergency fallback (direct DNS update)

3. **Data loss observed** post-rollback
   - Example: Requests lost during graceful drain cancellation
   - Action: Immediate investigation, potential data restoration from backup

4. **Team consensus STOP** (from owner + platform-lead)
   - If confidence in cljc fleet drops, abort and plan extended remediation

---

## Escalation Contacts

| Scenario | Contact | Response SLA |
|----------|---------|--------------|
| Auto-rollback fired | `#prod-gates-wave5` Slack | 2 min notification |
| Root cause unclear | murawko-owner@gftd.group | 30 min investigation |
| Multiple rollbacks | platform-lead@gftd.group | 1 hour decision |
| Abort decision needed | platform-lead + murawko-owner | 2 hour review |

---

## Appendix: Rollback Script Reference

### Command: Set Traffic Split

```bash
nbb murakumo/rollback.cljs --to-stage phase2
# Output: Traffic split updated: 50% cljc, 50% Rust
```

### Command: Full Emergency Revert

```bash
nbb murakumo/rollback.cljs --to-stage full-rust --emergency
# Output: Full revert executed (0% cljc, 100% Rust), PagerDuty alert fired
```

### Command: Graceful Drain Cancellation

```bash
nbb murakumo/rollback.cljs --cancel-graceful-drain --restore-rust-pool
# Output: Rust graceful drain cancelled, nodes restored to active state
```

---

**Prepared by**: Claude Code Agent  
**Date**: 2026-07-21  
**Status**: ✅ READY FOR EXECUTION  
**Test Status**: Fire drill scheduled for 2026-09-08
