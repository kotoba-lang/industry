# Metrics Pipeline Deployment — Quick Reference

**Status**: ✅ READY FOR DEPLOYMENT  
**Deployment Window**: 2026-08-08 → 2026-08-10  
**Target Kickoff**: 2026-08-18 09:00 JST  
**Prepared**: 2026-07-21

---

## Phase 1: Prometheus Deployment (2026-08-08, 12:00 UTC)

**Owner**: Metrics Lead + Infra Lead  
**Duration**: ~3 hours (12:00–15:00 UTC)  
**Artifact**: `deploy/prometheus.yml` + `deploy/prometheus-rules.yml`

### Quick Checklist
- [ ] Monitoring cluster has >= 50 GB free storage
- [ ] Network routing to port 9090 open
- [ ] API tokens collected (CI, Jira, CloudFlare)
- [ ] Deploy prometheus.yml to `/etc/prometheus/prometheus.yml`
- [ ] Deploy prometheus-rules.yml to `/etc/prometheus/rules/`
- [ ] Restart Prometheus: `sudo systemctl restart prometheus`
- [ ] Health check: `curl -s http://localhost:9090/-/healthy` → 200
- [ ] Verify targets: `curl -s http://localhost:9090/api/v1/targets` → 5 UP
- [ ] Test 5 collectors:
  - `nbb scripts/collect-build-metrics.cljs` ✅
  - `nbb scripts/collect-coverage-metrics.cljs` ✅
  - `nbb scripts/collect-deploy-metrics.cljs` ✅
  - `nbb scripts/collect-blocker-metrics.cljs` ✅
  - `nbb scripts/collect-velocity-metrics.cljs` ✅
- [ ] Verify data in Prometheus:
  ```bash
  curl -s 'http://localhost:9090/api/v1/query?query=build_duration_seconds' | jq '.data.result | length' > 0
  ```
- [ ] Alert rules loaded: `curl -s http://localhost:9090/api/v1/rules` → groups >= 2

### Sign-Off Template
```
Phase 1 Complete: _______________
Date/Time: 2026-08-08 _____:_____ UTC
Metrics Live Count: ___ / 5
Sign-off: Metrics Lead __________ + Infra Lead __________
```

### Rollback (if critical failure)
```bash
sudo systemctl stop prometheus
sudo cp /etc/prometheus/prometheus.yml.backup /etc/prometheus/prometheus.yml
sudo systemctl start prometheus
# Notify #prod-gates-wave5
```

---

## Phase 2: CI Gate-Bot + Grafana (2026-08-09, 12:00 UTC)

**Owner**: CI Lead + Metrics Lead  
**Duration**: ~3 hours (12:00–15:00 UTC)  
**Artifacts**: `.github/workflows/production-gates-bot.yml` + `deploy/grafana-dashboard-wave5-m5m6.json`

### Quick Checklist
- [ ] GitHub Actions secrets configured:
  - [ ] `PROMETHEUS_URL` = `http://monitoring-cluster:9090`
  - [ ] `GRAFANA_API_KEY` (from Grafana admin)
  - [ ] `JIRA_API_TOKEN` (from Jira)
  - [ ] `CI_API_TOKEN` (GitHub Actions)
  - [ ] `CLOUDFLARE_API_TOKEN`
  - [ ] `PAGERDUTY_KEY`
  - [ ] `SLACK_WEBHOOK_URL`
  - [ ] `METRICS_DB_URL`
- [ ] Push workflow to main:
  ```bash
  git checkout main && git pull origin main
  git add .github/workflows/production-gates-bot.yml
  git commit -m "feat: deploy production gates bot workflow"
  git push origin main
  ```
- [ ] Manual trigger test:
  - GitHub UI: Actions → "Production Gates Bot" → "Run workflow"
  - Select `metric_type: "all"`
  - Wait for completion ✅
- [ ] Deploy Grafana dashboard:
  ```bash
  curl -X POST "https://grafana.internal/api/dashboards/db" \
    -H "Authorization: Bearer $GRAFANA_API_KEY" \
    -H "Content-Type: application/json" \
    -d @deploy/grafana-dashboard-wave5-m5m6.json
  ```
- [ ] Verify dashboard:
  ```bash
  curl -s -H "Authorization: Bearer $GRAFANA_API_KEY" \
    "https://grafana.internal/api/dashboards/uid/wave5-m5m6" | jq '.dashboard.title'
  # Expected: "Wave 5 M5-M6 Production Gates — Live Metrics"
  ```
- [ ] Test Slack integration:
  ```bash
  curl -X POST $SLACK_WEBHOOK_URL \
    -H 'Content-Type: application/json' \
    -d '{"text": "🧪 Test Alert from Metrics Pipeline"}'
  # Expected: Message in #prod-gates-wave5 < 2 min
  ```
- [ ] Test PagerDuty (if enabled)
- [ ] Test email routing

### Sign-Off Template
```
Phase 2 Complete: _______________
Date/Time: 2026-08-09 _____:_____ UTC
Workflow: [ ] Deployed [ ] Tested [ ] Active
Grafana Panels Live: ___ / 8
Slack Integration: [ ] Working
PagerDuty Integration: [ ] Working
Email Integration: [ ] Working
Sign-off: CI Lead __________ + Metrics Lead __________
```

---

## Phase 3: Smoke Test (2026-08-10, 10:00 UTC)

**Owner**: Metrics Lead + Escalation Lead  
**Duration**: 120 min (10:00–13:00 UTC, +1h contingency)  
**Checklist**: `90-docs/gates/smoke-test-checklist-20260810.edn`

### 8-Phase Smoke Test Overview

| Phase | Name | Duration | Checks |
|-------|------|----------|--------|
| 1 | Pre-Test Setup | 15 min | 5 checks |
| 2 | Prometheus Scrape Config | 20 min | 6 checks |
| 3 | Metrics Collection & Freshness | 30 min | 10 checks |
| 4 | Grafana Dashboard Validation | 20 min | 4 checks |
| 5 | Alert Routing & Notifications | 15 min | 5 checks |
| 6 | CI Gate-Bot Workflow Validation | 10 min | 4 checks |
| 7 | End-to-End Data Flow | 15 min | 4 checks |
| 8 | Escalation Protocol Health Check | 10 min | 3 checks |

### Success Criteria
- ✅ All 8 phases PASS (>= 90% checks per phase)
- ✅ All 5 metrics live in Prometheus + Grafana
- ✅ Alert delivery < 2 min (Slack, PagerDuty, email)
- ✅ Dashboard refresh p99 < 30 sec
- ✅ Data freshness within SLA for all metrics
- ✅ Escalation protocol verified

### If Smoke Test FAILS
1. Document failure (which phase, which check)
2. Note timestamp + error message
3. Root cause analysis
4. Fix in staging
5. Re-run smoke test
6. If can't fix by 2026-08-10 16:00 UTC: **Escalate to platform-lead**

### Sign-Off Template
```
Smoke Test Complete: _______________
Date/Time: 2026-08-10 _____:_____ UTC
All Phases PASS: [ ] Yes [ ] No
Metrics Live: ___ / 5
Alert Latency: ____ ms
Dashboard Refresh: [ ] OK
Escalation Protocol: [ ] Ready
Sign-off: Metrics Lead __________ + Escalation Lead __________
Platform Lead Approval: __________ (Date: ________)
```

---

## Pre-Kickoff Validation (2026-08-17, 09:00 JST)

**Owner**: Metrics Lead + Platform Lead  
**Duration**: 2 hours

### Checklist
- [ ] Grafana dashboard LIVE, all panels displaying fresh data
- [ ] All 5 metrics: build, coverage, deploy, blockers, velocity
- [ ] CI gate-bot workflow ready for automatic execution
- [ ] Team-leads trained on dashboard + escalation protocol
- [ ] Alert routing tested and working
- [ ] On-call rotation confirmed
- [ ] Metrics freshness within SLA (build: 15min, coverage: 1h, blockers: 5min)

### Go-No-Go Decision
**Decision Maker**: Platform Lead  
**Decision Deadline**: 2026-08-17 15:00 JST  
**Criteria**: All prerequisites met + smoke test PASS  
**If GO**: ✅ Ready for 2026-08-18 kickoff  
**If NO-GO**: Escalate issues, assess impact on kickoff

---

## Key Contacts

| Role | Email | Slack | Phone |
|------|-------|-------|-------|
| Metrics Lead | metrics-lead@gftd.group | #prod-gates-wave5 | — |
| Escalation Lead | escalation-lead@gftd.group | #prod-gates-wave5 | — |
| Platform Lead | platform-lead@gftd.group | #prod-gates-wave5 | — |
| Infra Lead | infra-lead@gftd.group | — | — |
| CI Lead | ci-lead@gftd.group | — | — |

**Escalation Channel**: `#prod-gates-wave5` (Slack)  
**Email Escalation**: `gates-escalation@gftd.group`  
**PagerDuty Service**: `prod-gates`

---

## Critical Paths & Dependencies

### Must Complete by 2026-08-10
1. Prometheus deployed + 5 metrics collecting
2. Grafana dashboard live + all 8 panels rendering
3. Alert routing tested (Slack + PagerDuty + email)
4. Smoke test PASS with sign-offs
5. Deployment log complete

### Must Complete by 2026-08-17
1. Team training completed
2. Metrics freshness verified (all < SLA age)
3. CI gate-bot workflow ready
4. On-call rotation confirmed
5. Pre-kickoff validation PASS
6. Go-no-go approval from platform-lead

### Gate Kickoff (2026-08-18)
- **Time**: 09:00 JST
- **Attendees**: team-lead-m5, team-lead-m6, platform-owner, metrics-lead, escalation-lead
- **Prerequisite**: Metrics pipeline LIVE and operational

---

## Metrics Collection Cadence

| Metric | Interval | SLA | Collector Script |
|--------|----------|-----|-----------------|
| Build Times (p99) | 15 min | < 12 min | `collect-build-metrics.cljs` |
| Test Coverage | 1 hour | >= 92% | `collect-coverage-metrics.cljs` |
| Deployment Success | 1 hour | >= 99.5% | `collect-deploy-metrics.cljs` |
| Gate Blockers | 5 min | < 2 min to report | `collect-blocker-metrics.cljs` |
| Team Velocity | 1 hour | tracked | `collect-velocity-metrics.cljs` |

---

## Documentation

| Document | Location | Purpose |
|----------|----------|---------|
| Deployment Procedure | `90-docs/gates/metrics-deployment-procedure.md` | Step-by-step phases 1–3 |
| Pipeline Config | `90-docs/gates/metrics-collection-pipeline.edn` | System architecture + components |
| Smoke Test Checklist | `90-docs/gates/smoke-test-checklist-20260810.edn` | 8-phase validation plan |
| Escalation Protocol | `90-docs/gates/escalation-protocol-reference.edn` | Alert routing + SLA responses |
| Kickoff Coordination | `90-docs/gates/wave-5-m5-m6-execution-kickoff.edn` | Gate schedule + teams + metrics |
| Deployment Log | `90-docs/gates/METRICS-DEPLOYMENT-LOG-2026-08-08.edn` | Real-time tracking + sign-offs |
| Readiness Assessment | `90-docs/gates/METRICS-DEPLOYMENT-READINESS-2026-08-08.edn` | Artifact checklist + risk assessment |

---

## Quick Commands Reference

```bash
# Health check Prometheus
curl -s http://localhost:9090/-/healthy

# List scrape targets
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets | length'

# Query a metric
curl -s 'http://localhost:9090/api/v1/query?query=build_duration_seconds'

# Health check Grafana
curl -s http://grafana:3000/api/health | jq '.status'

# Verify dashboard
curl -s -H "Authorization: Bearer $GRAFANA_API_KEY" \
  "https://grafana.internal/api/dashboards/uid/wave5-m5m6" | jq '.dashboard.title'

# Test Slack webhook
curl -X POST $SLACK_WEBHOOK_URL \
  -H 'Content-Type: application/json' \
  -d '{"text": "Test message"}'

# Run all collectors (local testing)
for script in scripts/collect-*.cljs; do
  echo "Running $script..."
  nbb "$script"
done
```

---

## Contingency Procedures

### If Prometheus Fails
```bash
# Check service status
systemctl status prometheus

# Check logs
journalctl -u prometheus -n 50

# Restart
sudo systemctl restart prometheus

# Verify recovery
curl -s http://localhost:9090/-/healthy
```

### If Grafana Dashboard Not Loading
```bash
# Verify datasource
curl -s -H "Authorization: Bearer $GRAFANA_API_KEY" \
  "https://grafana.internal/api/datasources" | jq '.[] | select(.type == "prometheus")'

# Verify dashboard exists
curl -s -H "Authorization: Bearer $GRAFANA_API_KEY" \
  "https://grafana.internal/api/dashboards/uid/wave5-m5m6"

# Reload dashboard
# Manual: Grafana UI → Dashboard settings → Reload
```

### If Alert Routing Fails
```bash
# Test Slack manually
curl -X POST $SLACK_WEBHOOK_URL -H 'Content-Type: application/json' \
  -d '{"text": "Test alert"}'

# Verify Alertmanager running
systemctl status alertmanager

# Check PagerDuty integration
# Manual: PagerDuty UI → Integrations → Verify endpoint
```

---

**Last Updated**: 2026-07-21  
**Status**: ✅ READY FOR DEPLOYMENT  
**Next Action**: Execute Phase 1 on 2026-08-08 12:00 UTC
