# Month 3 Pre-Flight Checklist (Due: 2026-08-17)

**Execution Start**: 2026-08-18 (Monday 10:00 JST)  
**Checklist Owner**: Ops-lead  
**Review Deadline**: 2026-08-17 (Sunday 23:59 JST)  
**Final Approval**: Jun Kawasaki

---

## Phase 1: Month 2 Metrics Validation

### Parity Validation (Required)

- [ ] Parity checker has been running continuously during Month 2 (50% traffic)
- [ ] Parity failure rate < 0.01% across all 4 weeks
  - Week 1 (50% canary): [metric value: _______]
  - Week 2 (50% traffic): [metric value: _______]
  - Week 3 (50% traffic): [metric value: _______]
  - Week 4 (50% traffic): [metric value: _______]
- [ ] Top 5 parity failures documented and root-cause analyzed (if any)
- [ ] cljc responses byte-match Rust for >99.99% of requests
- [ ] No outstanding parity divergence issues (all fixed or accepted as known-good)

### Latency Validation (Required)

- [ ] p50 latency measured at 50% traffic: _______ ms (target ≤ 70ms)
- [ ] p95 latency measured at 50% traffic: _______ ms (target ≤ 95ms)
- [ ] p99 latency measured at 50% traffic: _______ ms (target ≤ 100ms)
- [ ] No latency spikes > 120ms sustained for > 5 min
- [ ] GC pause distribution captured (p99 ≤ 30ms for selected algorithm)
- [ ] Full 4-week latency histogram archived (for baseline comparison)

### Throughput Validation (Required)

- [ ] Throughput loss vs Rust baseline < 1% (measured over 4 weeks)
  - Rust baseline req/sec: _______ req/sec
  - cljc 50% traffic req/sec: _______ req/sec
  - Loss %: [(rust - cljc) / rust * 100] = _______ %
- [ ] No sustained throughput degradation observed
- [ ] Connection refused or timeout errors < 0.01%

### Memory Validation (Required)

- [ ] JVM heap peak per instance ≤ 384MB (observed at 50% traffic)
  - naphtali: _______ MB
  - simeon: _______ MB
  - [... other 3 cljc nodes]
- [ ] No memory leak trend detected (heap stabilizes after GC)
- [ ] Memory leak test (4-hour soak at sustained load): [PASS / FAIL]
- [ ] GC pause events: _______ full GCs in 4 weeks (target < 1 per week)

### Reliability Validation (Required)

- [ ] HTTP 5xx error rate ≤ 0.1% across 4 weeks: _______ %
- [ ] Panic/crash/unhandled exception count: _______ (target: 0)
- [ ] Database (kgraph) connection pool health: [PASS / FAIL]
- [ ] No zombie processes observed on cljc nodes

### GC Tuning (Required)

- [ ] GC algorithm selected: [ ] ZGC [ ] Shenandoah [ ] G1GC
- [ ] Baseline GC pause distribution established (histogram saved)
- [ ] JVM options documented:
  ```
  -Xmx512m -Xms256m [algorithm-specific flags]
  ```
- [ ] GC tuning rationale documented in Month 2 report

---

## Phase 2: Monitoring & Dashboard Readiness

### Grafana Dashboard Setup (Required)

- [ ] Grafana dashboard "Fleet Migration - Month 3 Live" created
- [ ] 6 tabs configured:
  - [ ] Tab 1: Traffic Split (%) — gauge showing cljc_percent
  - [ ] Tab 2: Latency (p50/p95/p99) — line charts with thresholds
  - [ ] Tab 3: Parity Checker (Week 1) — divergence rate + pass/fail counts
  - [ ] Tab 4: JVM Health — heap + GC pause histogram
  - [ ] Tab 5: Error Rate — 2xx/4xx/5xx stacked bar
  - [ ] Tab 6: Component Performance — drama-profile instantiation

- [ ] Refresh interval set to 10 seconds
- [ ] All data sources (murakumo-metrics Prometheus) tested and live
- [ ] Dashboard permissions: [read access: ops-team, dev-team] [edit access: ops-lead]

### Alerting Setup (Required)

- [ ] PagerDuty integration configured
  - [ ] Routing key for ops-on-call rotation: [_____________]
  - [ ] Escalation policy: ops-lead → infra-team → owner

- [ ] Alert rules provisioned in Prometheus:
  - [ ] p99 latency > (baseline + 100ms) for 5min → CRITICAL
  - [ ] Parity divergence > 1% for 1min → CRITICAL
  - [ ] HTTP 5xx > 1% for 2min → CRITICAL
  - [ ] Memory peak > 400MB → WARNING
  - [ ] GC pause p99 > 40ms → WARNING

- [ ] Slack integration configured:
  - [ ] Channel #fleet-migration-ops created
  - [ ] All alerts routed to channel
  - [ ] Custom alert formatting (include runbook links)

- [ ] Alert testing completed:
  - [ ] Trigger critical alert (simulate p99 breach) → page received?
  - [ ] Trigger warning alert (simulate memory warning) → Slack message received?
  - [ ] Alert suppression working (acknowledge alert → no repeat page for 1 hour)?

### Logging & Audit Trail (Required)

- [ ] Murakumo audit logs configured (event log to B2 + local rotation)
- [ ] Parity checker logs routed to B2 DataLad (retention: 4 weeks)
- [ ] Application logs (kotoba-server, cljc nodes) aggregated:
  - [ ] Logging level set to INFO (WARN for noisy subsystems)
  - [ ] Log rotation configured (1GB per file, 30 files max)
  - [ ] Log compression to B2 scheduled (weekly)

- [ ] Metrics retention confirmed:
  - [ ] Prometheus scrape interval: 10 seconds
  - [ ] Local retention: 4 weeks (sufficient for Month 3 + analysis)
  - [ ] Export to B2 automation: [enabled / scheduled]

---

## Phase 3: Murakumo Control Plane Readiness

### Traffic Split Configuration (Required)

- [ ] Current traffic split (should be 50% cljc / 50% rust at Month 2 end):
  ```
  murakumo traffic-status
  → Expected: cljc_percent = 50, rust_percent = 50
  ```

- [ ] Murakumo routing layer tested:
  - [ ] Dry-run: shift traffic 50% → 60% → 50% (revert successful?)
  - [ ] No client-side errors during dry-run
  - [ ] Rollback script execution time < 5 seconds

- [ ] Traffic split automation ready:
  ```
  murakumo set-traffic-split --cljc-percent <value>
  ```
  - [ ] Command works for values: 50, 60, 75, 90, 99, 100
  - [ ] Command is idempotent (same value twice = no-op)

### Fleet Status (Required)

- [ ] cljc pool (production): 10 nodes, all healthy
  ```
  murakumo fleet-status | grep -E "cljc|production"
  ```
  - Node list: [list all 10 nodes]
  - All nodes: HEALTHY
  - All nodes: responding to health checks

- [ ] Rust pool (production): 7 nodes, all healthy
  ```
  murakumo fleet-status | grep -E "rust|production"
  ```
  - Node list: [list all 7 nodes]
  - All nodes: HEALTHY
  - All nodes: responding to health checks

- [ ] Network connectivity verified:
  - [ ] cljc nodes reachable via Tailscale SSH
  - [ ] Rust nodes reachable via Tailscale SSH
  - [ ] latency < 100ms between nodes (Tailscale)

### Health Check Configuration (Required)

- [ ] Health check endpoint: GET /health (both cljc and rust nodes)
- [ ] Health check interval: 10 seconds
- [ ] Health check timeout: 5 seconds
- [ ] Unhealthy threshold: 3 consecutive failures
- [ ] Health check tested manually:
  ```
  for node in naphtali simeon judah ...; do
    curl -X GET http://$node:8077/health
    # Expected: 200 OK {status: "healthy"}
  done
  ```

---

## Phase 4: Graceful Shutdown Procedure Validation

### Rust Fleet Shutdown Script (Required)

- [ ] Script location: `murakumo-family/murakumo/bin/stop-server.sh`
- [ ] Script tested on 1 staging Rust node (not production yet)
  - [ ] Health check disabled
  - [ ] Connection drain timeout: 30 seconds
  - [ ] Process shutdown: SIGTERM → process exit
  - [ ] Port 8077 verified free (netstat)
  - [ ] Node recovery: restart service successfully

- [ ] Rust shutdown procedure documented:
  - [ ] 5 phases defined (health-check disable → connection drain → process stop → port verify → archive)
  - [ ] Timeout values confirmed (10sec per node, 30sec drain)
  - [ ] Rollback steps documented (restart kotoba-server, return traffic)

### Workdir Archive Procedure (Required)

- [ ] Tar/gzip command tested:
  ```
  tar --gzip --exclude='*.log' -cf murakumo-kotoba-server-final-$(date +%s).tar.gz murakumo-kotoba-server-run/
  ```

- [ ] B2 upload credentials available:
  - [ ] B2_APPLICATION_KEY_ID: [_____________]
  - [ ] B2_APPLICATION_KEY: [_____________] (in 1Password/Keychain)
  - [ ] Upload destination: s3://kotobase.b2/murakumo-kotoba-server-final-*/

- [ ] DataLad archive setup tested:
  - [ ] `datalad init -d .` works
  - [ ] `datalad push --to origin-b2` works
  - [ ] Checksum verification: `shasum -c *.sha256` passes

- [ ] Archive restore tested:
  - [ ] Download archive from B2
  - [ ] Extract: `tar --gzip -xf murakumo-kotoba-server-final-*.tar.gz`
  - [ ] Checksum match verified
  - [ ] File permissions preserved

---

## Phase 5: Team Readiness & Communication

### Training & Runbooks (Required)

- [ ] On-call rotation confirmed:
  - [ ] Primary on-call: [_____________] (contact: ____________)
  - [ ] Secondary on-call: [_____________] (contact: ____________)
  - [ ] Tertiary on-call: [_____________] (contact: ____________)

- [ ] On-call runbooks reviewed:
  - [ ] Runbook 1: p99 Latency Alert (root-cause, 5-step investigation)
  - [ ] Runbook 2: Parity Divergence (root-cause, debug procedure)
  - [ ] Runbook 3: 5xx Error Spike (resource check, rollback decision)
  - [ ] Runbook 4: Memory Pressure (heap analysis, scaling procedure)

- [ ] Escalation matrix confirmed:
  - [ ] CRITICAL alerts → PagerDuty page + Slack #fleet-migration-ops
  - [ ] WARNING alerts → Email + Slack (no page)
  - [ ] INFO alerts → Daily digest in #fleet-migration-ops

### Stakeholder Notifications (Required)

- [ ] Calendar invites sent (all participants):
  - [ ] Week 1 daily standup (Mon-Sun 08-18 to 08-24, 30min each)
  - [ ] Week 2 daily standup (Mon-Sun 08-25 to 08-31, 30min each)
  - [ ] Owner decision gate (Mon-Tue 08-18 to 08-19, 1 hour)
  - [ ] Final cutover event (Mon 08-25, 1 hour)
  - [ ] Rust shutdown event (Mon-Tue 08-25 to 08-26, 2 hours)

- [ ] Slack channel #fleet-migration-ops created and members added:
  - [ ] ops-lead
  - [ ] monitoring-team
  - [ ] infra-team
  - [ ] dev-team (for parity issues)
  - [ ] Jun Kawasaki (owner)

- [ ] Pre-execution announcement scheduled:
  - [ ] Announcement 1 week before (2026-08-11): "Month 3 starting in 1 week"
  - [ ] Announcement 3 days before (2026-08-15): "Final checklist review"
  - [ ] Announcement 1 day before (2026-08-17): "Preflight checklist complete, ready to go"
  - [ ] Announcement at start (2026-08-18 09:00): "Month 3 execution starting"

---

## Phase 6: Infrastructure Readiness

### Node Health & Capacity (Required)

- [ ] All 10 cljc nodes disk space check (target: > 10GB free per node):
  - naphtali: _______ GB free
  - simeon: _______ GB free
  - [... list all 10]

- [ ] All 10 cljc nodes CPU baseline (target: idle < 10%):
  - naphtali: _______ % CPU
  - [... list all 10]

- [ ] All 7 Rust nodes network connectivity (Tailscale):
  - naphtali: [PASS / FAIL]
  - [... list all 7]

- [ ] Network bandwidth between cljc and Rust pool:
  - Latency: _______ ms (target: < 100ms)
  - Jitter: _______ ms (target: < 20ms)
  - Packet loss: _______ % (target: 0%)

### Database & External Dependencies (Required)

- [ ] kgraph datastore (if used by drama-profile): [ ] HEALTHY [ ] DEGRADED
- [ ] IPFS pinning (if used): [ ] HEALTHY [ ] DEGRADED
- [ ] Murakumo metrics backend (Prometheus): [ ] HEALTHY [ ] DEGRADED
- [ ] B2 storage: [ ] ACCESSIBLE [ ] CHECK CREDS
- [ ] Tailscale VPN: [ ] ALL NODES CONNECTED [ ] CHECK NODES: [_______]

---

## Phase 7: Documentation & Sign-Off

### Execution Plan Documents (Required)

- [ ] `90-docs/MONTH-3-EXECUTION-SUMMARY.md` — complete and reviewed
- [ ] `90-docs/fleet-migration-month-3-execution-plan.edn` — complete and reviewed
- [ ] `90-docs/fleet-migration-month-3-monitoring-guide.md` — complete and reviewed
- [ ] `90-docs/fleet-migration-rust-fleet-decommissioning.md` — complete and reviewed
- [ ] `90-docs/MONTH-3-PREFLIGHT-CHECKLIST.md` — this document, filled out

### Month 2 Reports (Required)

- [ ] Month 2 completion report: `90-docs/fleet-migration/month-2-final-completion-report.md`
- [ ] Month 2 parity validation report: `90-docs/fleet-migration/month-2-parity-validation.md`
- [ ] Month 2 performance baseline: `90-docs/fleet-migration/month-2-performance-baseline.edn`

### Team Sign-Offs (Required)

| Role | Name | Checklist Status | Approval | Date |
|------|------|---|---|---|
| **Ops-lead** | [TBD] | [ ] Complete | [ ] APPROVED | _____ |
| **Monitoring-team** | [TBD] | [ ] Complete | [ ] APPROVED | _____ |
| **Infra-team** | [TBD] | [ ] Complete | [ ] APPROVED | _____ |
| **Owner** | Jun Kawasaki | [ ] Reviewed | [ ] APPROVED | _____ |

---

## Final Go/No-Go Decision (2026-08-17)

**Checklist Status**:
- [ ] All Phase 1-7 items checked and PASS
- [ ] No blockers or risks identified
- [ ] Owner approval obtained

**Decision**:
- [ ] **GO** — Proceed to Month 3 execution (2026-08-18 10:00 JST)
- [ ] **NO-GO** — Delay Month 3 (reason: _______________)

**Owner Signature**: _________________________ **Date**: _______

---

## Appendix: Quick Reference (Print This)

### Daily Standup Checklist (Each Day during Month 3)

**Morning (08:00 JST)**:
- [ ] Check overnight metrics (Grafana dashboard)
- [ ] Verify all nodes healthy (murakumo fleet-status)
- [ ] Check for alerts overnight (PagerDuty, Slack)
- [ ] Review traffic split at start of day (murakumo traffic-status)

**Before Traffic Migration (09:30 JST)**:
- [ ] Traffic split dry-run (if first gate of the day)
- [ ] Verify rollback script ready
- [ ] Confirm monitoring dashboards live
- [ ] Announce on Slack: "Traffic migration starting in 30 min"

**During Traffic Migration (10:00 JST)**:
- [ ] Set traffic split (murakumo set-traffic-split --cljc-percent <value>)
- [ ] Monitor metrics for 60 seconds (watch for spikes)
- [ ] No anomalies detected? Continue monitoring
- [ ] Anomaly detected? Trigger rollback immediately

**During Stabilization (10:00 JST + 24 hours)**:
- [ ] Check metrics every 1 hour
- [ ] Log snapshot: timestamp, traffic %, p99 latency, 5xx rate
- [ ] Alert noise review (false positives?)

**Before Next Day Gate (17:00 JST)**:
- [ ] Confirm 24-hour stability
- [ ] Sign off on current step (decision gate)
- [ ] Schedule standup for next day
- [ ] Announce Slack: "Gate PASS" or "Gate needs investigation"

---

## Contacts (Emergency Only)

**During Month 3 Execution (Aug 18-31)**:
- **Ops-lead**: [phone: ___________] [email: ___________]
- **On-call**: PagerDuty (set in Phase 5)
- **Owner**: Jun Kawasaki [phone: ___________] [email: jun@gftd.group]

---

**Checklist Prepared By**: [___________] **Date**: ___________

**Final Approval**: Jun Kawasaki ✅ **Date**: 2026-08-17

---

*This checklist is due for sign-off by 2026-08-17 (23:59 JST). Incomplete or failing items must be escalated to owner immediately.*
