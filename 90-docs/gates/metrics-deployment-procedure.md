# Metrics Deployment Procedure — Wave 5 M5–M6 Production Gates

**Status**: READY FOR DEPLOYMENT  
**Target Deployment Timeline**: 2026-08-08 → 2026-08-10  
**Prepared**: 2026-07-21  

---

## Overview

This document defines the step-by-step deployment procedure for the Wave 5 M5–M6 metrics pipeline, starting with Prometheus (2026-08-08), followed by CI gate-bot + Grafana (2026-08-09), and ending with smoke testing (2026-08-10).

### Success Criteria
- Prometheus scraping metrics by 2026-08-08 12:00 UTC
- CI gate-bot workflow active by 2026-08-09 12:00 UTC
- Grafana dashboard live by 2026-08-09 17:00 UTC
- Smoke test PASSED by 2026-08-10 16:00 UTC
- Zero data gaps week 11–16 (2026-08-18 → 2026-09-29)

---

## Phase 1: Prometheus Deployment (2026-08-08)

### 1.1 Pre-Deployment Checklist

- [ ] Monitoring cluster has >= 50 GB free storage
- [ ] Network routing to Prometheus port 9090 is open
- [ ] Backup of existing Prometheus config completed
- [ ] All required API tokens collected:
  - `CI_API_TOKEN` (GitHub Actions API)
  - `JIRA_API_TOKEN` (Jira REST API)
  - `CLOUDFLARE_API_TOKEN` (CloudFlare Workers API)
- [ ] Monitoring cluster access credentials ready
- [ ] Deployment rollback procedure documented and tested

### 1.2 Configuration Deployment

1. **Copy Prometheus configuration files to monitoring cluster**:
   ```bash
   scp deploy/prometheus.yml monitoring-cluster:/etc/prometheus/prometheus.yml
   scp deploy/prometheus-rules.yml monitoring-cluster:/etc/prometheus/rules/wave5-rules.yml
   ```

2. **Verify configuration syntax**:
   ```bash
   ssh monitoring-cluster
   promtool check config /etc/prometheus/prometheus.yml
   # Expected: "SUCCESS: 0 rule files"
   ```

3. **Create secrets directory and populate with API tokens**:
   ```bash
   sudo mkdir -p /etc/prometheus/secrets
   sudo tee /etc/prometheus/secrets/ci-api-token > /dev/null <<< "$CI_API_TOKEN"
   sudo tee /etc/prometheus/secrets/jira-api-token > /dev/null <<< "$JIRA_API_TOKEN"
   sudo tee /etc/prometheus/secrets/cloudflare-api-token > /dev/null <<< "$CLOUDFLARE_API_TOKEN"
   sudo chmod 600 /etc/prometheus/secrets/*
   ```

4. **Set Prometheus data directory permissions**:
   ```bash
   sudo mkdir -p /prometheus/data
   sudo chown prometheus:prometheus /prometheus/data
   sudo chmod 755 /prometheus/data
   ```

5. **Restart Prometheus service**:
   ```bash
   sudo systemctl restart prometheus
   ```

6. **Verify Prometheus is operational**:
   ```bash
   # Check service status
   sudo systemctl status prometheus
   
   # Health check
   curl -s http://localhost:9090/-/healthy
   # Expected: 200 OK
   
   # Verify scrape targets
   curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets | length'
   # Expected: >= 5
   ```

### 1.3 Initial Metrics Collection

1. **Test build metrics collection manually**:
   ```bash
   export CI_API_TOKEN="<token>"
   export OWNER="com-junkawasaki"
   export REPO="root"
   export PROMETHEUS_URL="http://monitoring-cluster:9090"
   
   nbb scripts/collect-build-metrics.cljs
   # Expected: metrics-build.txt created with Prometheus metrics
   ```

2. **Verify metrics appear in Prometheus**:
   ```bash
   curl -s 'http://monitoring-cluster:9090/api/v1/query?query=build_duration_seconds' | jq '.data.result'
   # Expected: Non-empty array of metric values
   ```

3. **Repeat for remaining metrics**:
   ```bash
   nbb scripts/collect-coverage-metrics.cljs
   nbb scripts/collect-deploy-metrics.cljs
   nbb scripts/collect-blocker-metrics.cljs
   nbb scripts/collect-velocity-metrics.cljs
   ```

### 1.4 Verify Data Retention Policy

```bash
# Check storage configuration
curl -s http://monitoring-cluster:9090/api/v1/query?query=prometheus_tsdb_retention_limit_bytes

# Check actual usage
du -sh /prometheus/data
# Expected: < 50 GB
```

### 1.5 Enable Alert Rules

1. **Verify alert rules are loaded**:
   ```bash
   curl -s http://monitoring-cluster:9090/api/v1/rules | jq '.data.groups | length'
   # Expected: >= 2 (wave5_m5m6_gates, wave5_sla_compliance, wave5_team_metrics)
   ```

2. **Test alert rule evaluation**:
   ```bash
   # Verify at least one rule is evaluating correctly
   curl -s 'http://monitoring-cluster:9090/api/v1/query?query=ALERTS' | jq '.data.result | length'
   # Expected: >= 1 (at least some alerts should be active)
   ```

### 1.6 Deployment Sign-Off (Phase 1)

- [ ] Prometheus service running and healthy
- [ ] All 5 metrics appearing in Prometheus
- [ ] Data retention policy configured correctly
- [ ] Alert rules loaded and evaluating
- [ ] API token access working
- [ ] Backup strategy confirmed

**Sign-off by**: Infra Lead / Metrics Lead  
**Date & Time**: _____________

---

## Phase 2: CI Gate-Bot + Grafana Deployment (2026-08-09)

### 2.1 GitHub Actions Workflow Deployment

1. **Push CI workflow to main branch**:
   ```bash
   git checkout main
   git pull origin main
   git add .github/workflows/production-gates-bot.yml
   git commit -m "feat: deploy production gates bot workflow for wave5 m5m6"
   git push origin main
   ```

2. **Create GitHub Actions secrets** (GitHub UI: Settings → Secrets → Actions):
   - `PROMETHEUS_URL`: `http://monitoring-cluster:9090`
   - `GRAFANA_API_KEY`: (from Grafana admin)
   - `JIRA_API_TOKEN`: (from Jira API user)
   - `CI_API_TOKEN`: (from GitHub Actions)
   - `CLOUDFLARE_API_TOKEN`: (from CloudFlare dashboard)
   - `PAGERDUTY_KEY`: (from PagerDuty API)
   - `SLACK_WEBHOOK_URL`: (from Slack app)
   - `METRICS_DB_URL`: (internal metrics database)

3. **Push collector scripts**:
   ```bash
   git add scripts/collect-*.cljs
   git commit -m "feat: add metrics collector scripts for wave5"
   git push origin main
   ```

4. **Manually trigger workflow to verify**:
   - GitHub UI: Actions → "Production Gates Bot" → "Run workflow"
   - Select: `metric_type: "all"`
   - Wait for workflow to complete

5. **Verify workflow execution**:
   ```bash
   # Check workflow runs
   gh run list --workflow production-gates-bot.yml --status completed --limit 1
   
   # Check logs
   gh run view <run-id> --log
   # Expected: All collection jobs successful
   ```

### 2.2 Grafana Dashboard Deployment

1. **Access Grafana admin panel** (https://grafana.internal/api/admin):
   ```bash
   # Get Grafana API key from admin
   GRAFANA_API_KEY="<your-api-key>"
   GRAFANA_URL="https://grafana.internal"
   ```

2. **Create datasource for Prometheus** (if not exists):
   ```bash
   curl -X POST "$GRAFANA_URL/api/datasources" \
     -H "Authorization: Bearer $GRAFANA_API_KEY" \
     -H "Content-Type: application/json" \
     -d '{
       "name": "Prometheus",
       "type": "prometheus",
       "url": "http://monitoring-cluster:9090",
       "access": "proxy",
       "isDefault": true,
       "jsonData": {},
       "secureJsonData": {}
     }'
   ```

3. **Deploy Grafana dashboard**:
   ```bash
   curl -X POST "$GRAFANA_URL/api/dashboards/db" \
     -H "Authorization: Bearer $GRAFANA_API_KEY" \
     -H "Content-Type: application/json" \
     -d @deploy/grafana-dashboard-wave5-m5m6.json
   ```

4. **Verify dashboard is accessible**:
   ```bash
   curl -s -H "Authorization: Bearer $GRAFANA_API_KEY" \
     "$GRAFANA_URL/api/dashboards/uid/wave5-m5m6" | jq '.dashboard.title'
   # Expected: "Wave 5 M5-M6 Production Gates — Live Metrics"
   ```

5. **Configure dashboard sharing** (Grafana UI):
   - Dashboard settings → Share
   - Enable public access with role: "Viewer" only
   - Generate link for team-leads: `https://grafana.internal/d/wave5-m5m6?from=now-7d&to=now`

### 2.3 Alert Router Configuration

1. **Connect Prometheus to Alertmanager** (if separate instance):
   ```bash
   # Edit Prometheus config
   vim /etc/prometheus/prometheus.yml
   
   # Add Alertmanager config (already in deployment file, verify):
   # alerting:
   #   alertmanagers:
   #     - static_configs:
   #         - targets:
   #             - 'localhost:9093'
   ```

2. **Verify Alertmanager routing**:
   ```bash
   curl -s http://localhost:9093/api/v1/alerts | jq '.data | length'
   # Expected: >= 0 (no errors)
   ```

3. **Test Slack integration**:
   ```bash
   # Send test alert via curl
   curl -X POST https://hooks.slack.com/services/YOUR/WEBHOOK/URL \
     -H 'Content-Type: application/json' \
     -d '{
       "text": "🧪 Test Alert from Metrics Pipeline — 2026-08-09"
     }'
   # Expected: Message appears in #prod-gates-wave5 within 2 seconds
   ```

4. **Test PagerDuty integration** (if enabled):
   ```bash
   # Create test incident via PagerDuty API
   curl -X POST "https://api.pagerduty.com/incidents" \
     -H "Authorization: Token token=$PAGERDUTY_API_KEY" \
     -d "{
       \"incident\": {
         \"type\": \"incident_reference\",
         \"title\": \"Test Alert - Metrics Pipeline Deployment\",
         \"service\": {\"id\": \"prod-gates\", \"type\": \"service_reference\"}
       }
     }"
   # Expected: Incident created successfully
   ```

### 2.4 Weekly Report Job Configuration

1. **Verify cron schedule for Monday 09:00 JST**:
   ```bash
   # GitHub Actions will trigger automatically based on schedule in workflow
   # Verify schedule is set correctly in .github/workflows/production-gates-bot.yml
   grep "cron: '0 0 \* \* 1'" .github/workflows/production-gates-bot.yml
   # Expected: Match found (0:00 UTC = 9:00 JST)
   ```

2. **Test weekly report generation manually**:
   ```bash
   nbb scripts/generate-weekly-report.cljs
   # Expected: weekly-metrics.json and weekly-report.md created
   ```

### 2.5 Deployment Sign-Off (Phase 2)

- [ ] GitHub Actions workflow deployed
- [ ] All GitHub Actions secrets configured
- [ ] Collector scripts executing successfully
- [ ] Grafana datasource connected
- [ ] Dashboard deployed and accessible
- [ ] Slack integration working
- [ ] PagerDuty integration working (if enabled)
- [ ] Weekly report job scheduled

**Sign-off by**: CI Lead / Metrics Lead  
**Date & Time**: _____________

---

## Phase 3: Smoke Testing (2026-08-10)

### 3.1 Smoke Test Execution

Execute the comprehensive smoke test checklist at: `90-docs/gates/smoke-test-checklist-20260810.edn`

**Execution Steps**:

1. **Phase 1: Pre-Test Setup (15 min)**
   - Verify Prometheus server running
   - Verify Grafana server running
   - Verify GitHub Actions secrets configured
   - Verify Slack channel access

2. **Phase 2: Prometheus Scrape Configuration (20 min)**
   - Verify all 5 scrape jobs configured
   - Verify all targets are UP

3. **Phase 3: Metrics Collection & Data Freshness (30 min)**
   - Run each collector script
   - Verify all metrics appear in Prometheus
   - Verify data freshness (age < SLA)

4. **Phase 4: Grafana Dashboard Validation (20 min)**
   - Load dashboard in browser
   - Verify all panels render
   - Verify auto-refresh working

5. **Phase 5: Alert Routing & Notifications (15 min)**
   - Trigger test alerts
   - Verify Slack/PagerDuty/Email notification
   - Verify alert content is correct

6. **Phase 6: CI Gate-Bot Workflow Validation (10 min)**
   - Trigger gate-readiness-check workflow
   - Verify outputs correct
   - Verify Jira/Slack posts

7. **Phase 7: End-to-End Data Flow (15 min)**
   - Verify 15-min build metrics cadence
   - Verify 5-min blocker metrics cadence
   - Verify SLA breach detection

8. **Phase 8: Escalation Protocol Health Check (10 min)**
   - Verify runbook accessible
   - Verify on-call rotation configured
   - Verify team contacts updated

### 3.2 Pass/Fail Criteria

**PASS**: All 8 phases completed with >= 90% checks PASS  
**FAIL**: Any phase with > 10% checks FAIL, or critical data freshness issue

### 3.3 Failure Recovery

If smoke test FAILS:

1. **Document failure**:
   - Note which phase failed
   - Capture timestamps and error messages
   - Save logs to `90-docs/gates/smoke-test-failure-2026-08-10.md`

2. **Root cause analysis**:
   - Identify root cause (config, API, network, resource)
   - Determine fix required

3. **Fix and re-test**:
   - Deploy fix to staging
   - Re-run smoke test
   - If PASS, deploy fix to production

4. **Escalate if needed**:
   - If fix time > 4 hours: Notify platform-lead
   - If can't fix by 2026-08-10 16:00: Delay kickoff decision

### 3.4 Smoke Test Sign-Off

**Checklist completion target**: 2026-08-10 14:00 UTC

- [ ] Phase 1–8 all PASS
- [ ] All metrics fresh (within SLA)
- [ ] All alerts routed correctly
- [ ] Grafana dashboard stable
- [ ] CI gate-bot responsive
- [ ] No data gaps detected

**Sign-off by**: Metrics Lead / Escalation Lead  
**Date & Time**: _____________  
**Approval**: Platform Lead  
**Date & Time**: _____________  

---

## Rollback Procedure

If critical issues are discovered after deployment:

### Immediate Rollback (< 30 min)

1. **Stop metric collection**:
   ```bash
   # Disable all collection jobs in GitHub Actions
   gh workflow disable production-gates-bot.yml
   ```

2. **Revert CI workflow**:
   ```bash
   git revert <commit-hash-of-workflow>
   git push origin main
   ```

3. **Revert Prometheus config** (if Prometheus was changed):
   ```bash
   ssh monitoring-cluster
   sudo systemctl stop prometheus
   sudo cp /etc/prometheus/prometheus.yml.backup /etc/prometheus/prometheus.yml
   sudo systemctl start prometheus
   ```

4. **Notify team**:
   ```bash
   # Post to #prod-gates-wave5
   Slack message: "⚠️  Metrics pipeline rollback initiated. Reason: [brief reason]. ETA to restore: [time]"
   ```

### Extended Rollback (30 min – 4 hours)

1. **Assess root cause** and fix in staging
2. **Re-deploy** with fix
3. **Re-run smoke test**
4. **Proceed** only if PASS

### Decision Point: Postpone Kickoff

If **smoke test cannot be completed by 2026-08-10 16:00 UTC**:

- [ ] Notify platform-lead and phase-owner
- [ ] Decision: Postpone kickoff to 2026-08-19 (next Monday)
  - OR: Proceed with manual metrics collection (degraded mode)
  - OR: Proceed with static SLA thresholds (no live metrics)

---

## Maintenance & Monitoring Post-Deployment

### Weekly Health Check (Every Monday 09:00 JST)

Run: `scripts/gate-health-check.cljs`

```bash
# Verify:
# - Prometheus scrape success rate >= 99%
# - All metrics < SLA age
# - Grafana panels loading (p99 < 2s)
# - Alert routing working
# - Weekly report generated and posted
```

### On-Call Rotation

**Metrics Pipeline On-Call**:
- **Primary**: Metrics Lead
- **Secondary**: Escalation Lead
- **Tertiary**: Platform Lead

**Escalation SLA**: 30 min to triage, 4h to resolve

### Backup & Disaster Recovery

**Prometheus Data Backups**:
- Frequency: Daily (midnight UTC)
- Retention: 30 days
- Location: `/backups/prometheus/`
- Restore procedure: `scripts/prometheus-restore.sh`

**Grafana Dashboard Backups**:
- Frequency: Daily (stored in git)
- Format: JSON
- Location: `deploy/grafana-dashboard-*.json`

---

## Contact & Escalation

- **Metrics Lead**: `metrics-lead@gftd.group`
- **Escalation Channel**: `#prod-gates-wave5` (Slack)
- **PagerDuty Service**: `prod-gates`
- **Wiki Runbook**: `https://wiki.internal/gates/runbook/`

---

## Appendix: Environment Variables Reference

```bash
# Prometheus & Monitoring
PROMETHEUS_URL=http://monitoring-cluster:9090
GRAFANA_API_KEY=<api-key-from-grafana-admin>

# GitHub & CI
CI_API_TOKEN=<github-actions-token>
OWNER=com-junkawasaki
REPO=root

# Jira
JIRA_URL=https://jira.internal
JIRA_USER=gates-bot
JIRA_API_TOKEN=<jira-rest-api-token>

# CloudFlare
CLOUDFLARE_API_TOKEN=<cloudflare-workers-api-token>
CLOUDFLARE_ACCOUNT_ID=<your-account-id>

# Alerting
PAGERDUTY_KEY=<pagerduty-api-key>
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/YOUR/WEBHOOK/URL

# Metrics Database
METRICS_DB_URL=jdbc:postgresql://metrics.internal:5432/gates_metrics

# Gate Configuration
WAVE=wave5-m5m6
GATE_PROJECTS=M5W5,M5IW5,M6W5
```

---

**Last Updated**: 2026-07-21  
**Ready for Deployment**: ✅  
**Deployment Window**: 2026-08-08 12:00 UTC  
