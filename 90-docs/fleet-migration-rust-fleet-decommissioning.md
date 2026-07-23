# Rust Fleet Decommissioning Procedure (Month 3, Week 2)

**Execution Date**: 2026-08-25 to 2026-08-26 (Week 2, Days 1-2)  
**Executor**: Ops Team (infra-lead + ops-lead)  
**Owner Approval**: Jun Kawasaki  
**Related ADR**: ADR-2607072100 (Strangler-Fig Fleet Migration)

---

## Overview

This procedure describes the graceful shutdown and decommissioning of the existing Rust kotoba-server fleet (7 nodes) during Month 3, Week 2, after traffic has been successfully migrated to the cljc mesh nodes (50%→100%, Week 1-2).

### Fleet Inventory (Rust kotoba-server Nodes)

| Node Name | Host | Role | Status | Action |
|-----------|------|------|--------|--------|
| naphtali | naphtali | pin, compute | production | SHUTDOWN |
| simeon | simeon | pin, compute | production | SHUTDOWN |
| judah | judah | pin, compute | production | SHUTDOWN |
| zebulun | zebulun | pin, compute | production | SHUTDOWN |
| levi | levi | pin, compute | production | SHUTDOWN |
| joseph | joseph | pin, compute | production | SHUTDOWN |
| issachar | issachar | pin, compute | production | SHUTDOWN |
| dan | dan | pin, compute | production | SHUTDOWN |
| benjamin | benjamin | pin, compute | production | SHUTDOWN |
| asher | asher | pin, compute, relay | production | SHUTDOWN |

**Note**: The 3 staging cljc nodes (part of Month 1-2 parallel-run) are NOT decommissioned; they become the production pool post-cutover.

### Decommissioning Rationale

1. **Traffic fully migrated to cljc**: By end of Week 1 (Monday 2026-08-25), traffic split is 100% cljc / 0% rust.
2. **No fallback requirement**: Rust fleet is no longer needed as a safety net (cljc parity validated over Month 1-3).
3. **Infrastructure reclamation**: Free up Mac mini hardware for other workloads (optional expansion later).
4. **Clean state**: Avoid zombie processes or stale configurations.

---

## Pre-Decommissioning Checklist (Week 1, Friday 2026-08-24)

Verify readiness 48 hours before shutdown:

- [ ] Traffic split is stable at 99% cljc (end of Friday)
- [ ] All SLO metrics within budget (p99 ≤ 100ms, 5xx ≤ 0.1%)
- [ ] Parity validation complete (no divergence detected)
- [ ] Owner decision gate passed (Go for Week 2 cutover)
- [ ] Rust fleet health checks currently enabled (baseline state)
- [ ] Graceful shutdown script (`murakumo-family/murakumo/bin/stop-server.sh`) tested on staging node
- [ ] Backup procedures confirmed:
  - [ ] murakumo workdir (~/murakumo-kotoba-server-run) accessible on all nodes
  - [ ] B2 upload credentials available
  - [ ] DataLad archive space confirmed (≥ 50GB free)
- [ ] All team members notified (Slack announcement + calendar invite for Day 1-2)
- [ ] Escalation path reviewed (who to contact if something fails)

---

## Decommissioning Procedure (Week 2, Days 1-2)

### Phase 1: Health Check Disable (Monday 2026-08-25, 10:00-10:15 JST)

**Objective**: Prevent new connections to Rust nodes (mark unhealthy in murakumo routing).

**Step 1.1**: Disable health checks on murakumo control-plane

```bash
# On murakumo control-plane (or via murakumo API)
ssh murakumo-control-plane

# Verify current fleet state
murakumo fleet-status

# Disable health checks on all 7 Rust nodes
murakumo set-node-unhealthy --pool rust --all

# Verify health check disabled (should show "unhealthy")
murakumo fleet-status | grep -E "naphtali|simeon|judah|zebulun|levi|joseph|issachar|dan|benjamin|asher"
```

**Expected output**:
```
naphtali    (rust pool)    unhealthy    8077/4001    pin, compute
simeon      (rust pool)    unhealthy    8077/4003    pin, compute
...
```

**Step 1.2**: Verify routing layer response

```bash
# From any client, attempt to route to Rust pool (should fail gracefully)
curl -X GET http://murakumo.tailscale:8076/health

# Expected: Connection refused or LB drops request
# (murakumo router should only route to cljc pool now)
```

**Success Criteria**:
- [ ] All 7 Rust nodes marked "unhealthy" in murakumo control-plane
- [ ] murakumo router confirms 0 nodes in Rust pool
- [ ] No client-side errors from routing layer (graceful failover to cljc)

**Rollback** (if needed):
```bash
murakumo set-node-healthy --pool rust --all
# Wait 10 sec for health checks to resume
murakumo fleet-status | grep rust
```

---

### Phase 2: Connection Drain (Monday 2026-08-25, 10:15-10:50 JST)

**Objective**: Wait for in-flight HTTP requests to complete gracefully.

**Step 2.1**: Graceful shutdown timeout initiation

```bash
# On each Rust node (in sequence, not parallel, to minimize impact)
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  echo "Draining $node (30 sec timeout)..."
  
  # Initiate graceful shutdown signal (SIGTERM does NOT kill the process, just signals)
  # The kotoba-server should complete in-flight requests and exit within 30 sec
  ssh $node "pkill -TERM -f 'kotoba-server'" 2>/dev/null || true
  
  # Wait 30 seconds
  sleep 30
  
  # Check if process exited gracefully
  if ! ssh $node "pgrep -f 'kotoba-server'" >/dev/null 2>&1; then
    echo "✓ $node: graceful shutdown complete"
  else
    echo "⚠ $node: process still running after timeout"
  fi
done
```

**Step 2.2**: Monitor in-flight request completion

```bash
# On murakumo control-plane, monitor active connections
watch -n 5 'echo "Active requests to Rust pool:" && murakumo metrics-query "rate(http_requests_total{pool=\"rust\"}[30s])"'

# After ~30 seconds, rate should drop to 0
# Expected output:
# http_requests_total{pool="rust"} = 0.0 req/sec
```

**Success Criteria**:
- [ ] All in-flight requests to Rust nodes completed (rate ≈ 0 req/sec)
- [ ] No new requests initiated to Rust pool
- [ ] No hanging connections observed (netstat on nodes shows clean state)

**Rollback** (if connections hanging):
```bash
# Re-enable health checks and resume routing to Rust pool
murakumo set-node-healthy --pool rust --all
# Restart kotoba-server on nodes that didn't drain cleanly
ssh <node> "systemctl start murakumo-kotoba-server"
```

---

### Phase 3: Process Shutdown (Monday 2026-08-25, 10:50-11:10 JST)

**Objective**: Gracefully stop kotoba-server processes on all Rust nodes.

**Step 3.1**: Execute shutdown script on each node

```bash
# Iterate over all 7 Rust nodes
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  echo "Stopping kotoba-server on $node..."
  
  # Option A: Using systemctl (if murakumo-kotoba-server.service exists)
  ssh $node "sudo systemctl stop murakumo-kotoba-server" 2>/dev/null || {
    # Option B: Manual pkill (if systemctl not configured)
    echo "  Falling back to pkill -KILL..."
    ssh $node "pkill -KILL -f 'kotoba-server'" 2>/dev/null || true
  }
  
  # Verify process exited
  sleep 2
  if ssh $node "pgrep -f 'kotoba-server'" >/dev/null 2>&1; then
    echo "⚠ WARNING: $node: process still running, may need manual intervention"
  else
    echo "✓ $node: kotoba-server stopped"
  fi
done
```

**Alternative (if using Ansible)**: Use playbook to parallelize stops

```bash
# From murakumo-control-plane, run Ansible playbook
cd /path/to/murakumo-ansible-playbooks
ansible-playbook stop-kotoba-server.yml \
  --inventory hosts.ini \
  --extra-vars "target=rust_pool"

# Expected output:
# TASK [Stop murakumo-kotoba-server] ****
# ok: [naphtali]
# ok: [simeon]
# ... (all 7 nodes)
```

**Success Criteria**:
- [ ] All 7 Rust nodes confirm: `systemctl status murakumo-kotoba-server → inactive (dead)`
- [ ] No lingering `kotoba-server` processes (pgrep returns nothing)

**Rollback** (if process doesn't stop):
```bash
# Forcefully kill (SIGKILL)
ssh <node> "pkill -9 -f 'kotoba-server'"

# Restart if needed
ssh <node> "systemctl start murakumo-kotoba-server"
```

---

### Phase 4: Port Verification (Monday 2026-08-25, 11:10-11:20 JST)

**Objective**: Verify port :8077 (kotoba-server HTTP port) is released.

**Step 4.1**: Check port occupancy on all nodes

```bash
# Iterate over all nodes
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  echo "Checking port 8077 on $node..."
  
  ssh $node "netstat -an | grep 8077"
  
  # Expected: (empty output — port should be free)
  # If output is non-empty:
  #   LISTEN 0.0.0.0:8077 ← BAD (process still using port)
  #   TIME_WAIT 0.0.0.0:8077 ← OK (kernel cleanup in progress, will be free in 60 sec)
done
```

**Step 4.2**: Verify no processes on port 8077

```bash
# Alternative check using lsof (if available)
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  echo "lsof check on $node:"
  ssh $node "sudo lsof -i :8077" || echo "  (no processes using port 8077)"
done
```

**Success Criteria**:
- [ ] All nodes: port 8077 shows TIME_WAIT or (empty) status (not LISTEN)
- [ ] All nodes: `lsof -i :8077` returns nothing or only zombie processes

**Rollback** (if port still in use):
```bash
# Force kill process on that node
ssh <node> "sudo pkill -9 -f 'kotoba-server'"

# Wait 60 seconds for TIME_WAIT to clear
sleep 60

# Re-check
ssh <node> "netstat -an | grep 8077"
```

---

### Phase 5: Workdir Archive (Monday 2026-08-25 to Tuesday 2026-08-26, 11:20-17:00 JST)

**Objective**: Backup kotoba-server configuration, logs, and state to B2 + DataLad.

**Step 5.1**: Archive workdir on each node

```bash
# On each Rust node, archive the murakumo-kotoba-server-run directory
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  echo "Archiving workdir on $node..."
  
  ssh $node bash <<'EOF'
    cd $HOME
    timestamp=$(date +%s)
    tar --gzip \
      --exclude='*.log' \
      --exclude='*.tmp' \
      -cf "murakumo-kotoba-server-final-$timestamp.tar.gz" \
      murakumo-kotoba-server-run/
    
    # Verify archive created
    ls -lh murakumo-kotoba-server-final-$timestamp.tar.gz
    
    # Optional: Calculate checksum (for integrity verification)
    shasum -a 256 murakumo-kotoba-server-final-$timestamp.tar.gz > murakumo-kotoba-server-final-$timestamp.tar.gz.sha256
  EOF
done
```

**Step 5.2**: Upload archives to B2 (DataLad)

```bash
# Set up B2 credentials (environment variables)
export B2_APPLICATION_KEY_ID="<from 1Password/Keychain>"
export B2_APPLICATION_KEY="<from 1Password/Keychain>"

# Create DataLad dataset for archives
cd /tmp/murakumo-rust-fleet-archive
datalad init -d .

# Add each node's archive
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  echo "Downloading archive from $node..."
  timestamp=$(ssh $node 'ls murakumo-kotoba-server-final-*.tar.gz | head -1 | grep -oP "\d+" | tail -1')
  
  # SCP archive from node to local machine
  scp $node:~/murakumo-kotoba-server-final-$timestamp.tar.gz ./
  scp $node:~/murakumo-kotoba-server-final-$timestamp.tar.gz.sha256 ./
  
  # Add to DataLad
  datalad save -d . \
    -m "Rust node $node final archive" \
    murakumo-kotoba-server-final-$timestamp.tar.gz
done

# Publish to B2
datalad push -d . --to origin-b2

# Verify upload
datalad status
```

**Step 5.3**: Verify archives in B2

```bash
# List archives in B2
b2 list-file-versions kotobase-archive murakumo-kotoba-server-final-

# Expected output:
# murakumo-kotoba-server-final-1724067000.tar.gz (naphtali)
# murakumo-kotoba-server-final-1724067120.tar.gz (simeon)
# ... (one per node, ordered by timestamp)

# Verify checksums match
for archive in murakumo-kotoba-server-final-*.tar.gz; do
  shasum -c "$archive.sha256"
  # Expected: "$archive: OK"
done
```

**Step 5.4**: Optional: Delete workdir from nodes

This step is **optional** — leaving the workdir on nodes doesn't hurt, but frees up disk space.

```bash
# Option A: Keep workdir (for potential recovery) — DO THIS
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  echo "Keeping workdir on $node for potential recovery"
  # (no action)
done

# Option B: Delete workdir (reclaim disk space) — ONLY IF SURE
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  echo "Deleting workdir on $node (IRREVERSIBLE)..."
  ssh $node "rm -rf ~/murakumo-kotoba-server-run"
done
```

**Success Criteria**:
- [ ] All 7 archives uploaded to B2 (verified via `b2 list-file-versions`)
- [ ] All checksums verified (`shasum -c`)
- [ ] DataLad dataset metadata pushed to B2

**Rollback** (if archive upload fails):
- Retry upload (B2 is idempotent)
- Keep workdir on nodes until verified in B2

---

### Phase 6: Fleet Configuration Update (Tuesday 2026-08-26, 09:00-10:00 JST)

**Objective**: Update murakumo manifest and fleet-db to record Rust fleet decommissioning.

**Step 6.1**: Update manifest/fleet-db.edn

```clj
;; Before:
{:fleet/name "com-junkawasaki-kotoba-mesh"
 :fleet/port 8077
 :nodes
 [{:name "naphtali" :host "naphtali" ... :pool "rust" :status "production"}
  ... (7 Rust nodes, all status: "production")
  {:name "asher" :host "asher" ... :pool "rust" :status "production"}]}

;; After (Month 3, Week 2 decommissioning):
{:fleet/name "com-junkawasaki-kotoba-mesh"
 :fleet/port 8077
 :nodes
 [{:name "naphtali" :host "naphtali" ... :pool "rust-archive" :status "decommissioned" :decommission-date "2026-08-26" :archive-location "s3://kotobase.b2/murakumo-kotoba-server-final-{timestamp}.tar.gz"}
  ... (7 Rust nodes, all status: "decommissioned")
  {:name "asher" :host "asher" ... :pool "rust-archive" :status "decommissioned" :decommission-date "2026-08-26" :archive-location "..."}]
 :pools
 [{:pool-name "cljc-production" :count 10 :status "active" :description "cljc mesh nodes (production)"}
  {:pool-name "rust-archive" :count 7 :status "decommissioned" :description "Rust nodes (decommissioned 2026-08-26)"}]}
```

**Step 6.2**: Commit fleet-db update

```bash
cd /path/to/superproject

git add manifest/fleet-db.edn

git commit -m "chore: record Rust fleet decommissioning (2026-08-26)

Rust kotoba-server fleet (7 nodes) decommissioned after successful cljc migration.
- All nodes marked 'decommissioned' in fleet-db
- Archives stored in B2: murakumo-kotoba-server-final-{timestamp}.tar.gz
- Fleet now 100% cljc (10 cljc mesh nodes)

Related: ADR-2607072100 Month 3, Week 2"

git push origin main
```

**Step 6.3**: Notify team

```bash
# Slack announcement
curl -X POST https://hooks.slack.com/services/... \
  -H "Content-Type: application/json" \
  -d '{
    "text": "🎉 Rust fleet decommissioning complete (2026-08-26)",
    "blocks": [
      {"type": "section", "text": {"type": "mrkdwn", "text": "*Rust kotoba-server fleet decommissioned* 🔴→🟢\n\n• All 7 Rust nodes offline and archived\n• Archives in B2 (murakumo-kotoba-server-final-*.tar.gz)\n• Fleet now 100% cljc (10 nodes, production)\n• Month 3 migration complete\n\nSee: 90-docs/fleet-migration/month-3-final-completion-report.md"}},
      {"type": "divider"}
    ]
  }'
```

**Success Criteria**:
- [ ] manifest/fleet-db.edn updated and committed
- [ ] All Rust nodes marked "decommissioned" with timestamps
- [ ] Team notified via Slack

---

## Post-Decommissioning Verification (Tuesday 2026-08-26, 10:00-11:00 JST)

**Step 1**: Verify Rust nodes offline

```bash
# Ping all nodes (should be reachable on network)
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  ping -c 1 $node && echo "✓ $node: network reachable"
done

# Verify kotoba-server NOT running
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  if ssh $node "pgrep -f 'kotoba-server'" >/dev/null 2>&1; then
    echo "✗ $node: kotoba-server still running (ERROR)"
  else
    echo "✓ $node: kotoba-server not running"
  fi
done

# Verify port 8077 free
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  if ssh $node "netstat -an | grep -q ':8077.*LISTEN'"; then
    echo "✗ $node: port 8077 still in LISTEN state (ERROR)"
  else
    echo "✓ $node: port 8077 free"
  fi
done
```

**Step 2**: Verify murakumo fleet status

```bash
murakumo fleet-status

# Expected output:
# Fleet: com-junkawasaki-kotoba-mesh
# Pools:
#   cljc-production: 10 active nodes
#   rust-archive: 7 decommissioned nodes
#
# Active nodes (cljc):
# <list of 10 cljc nodes, all healthy>
```

**Step 3**: Verify 100% traffic on cljc pool

```bash
murakumo traffic-status

# Expected output:
# cljc-percent: 100
# rust-percent: 0
# Active routes: drama-profile (cljc only)
```

**Step 4**: Verify no client-side errors

```bash
# Query metrics for any 5xx errors to Rust pool (should be 0)
murakumo metrics-query "http_requests_total{pool='rust',status='5xx'}" | tail -1
# Expected: 0 (or metric doesn't exist, which is OK)

# Query any connection refused errors
murakumo audit-logs | grep -i "connection refused" | wc -l
# Expected: 0
```

---

## Rollback Procedure (If Decommissioning Fails)

### Scenario 1: Rust node won't stop gracefully

```bash
# Restart the node
ssh <node> "systemctl start murakumo-kotoba-server"

# Wait for it to rejoin fleet
sleep 10
murakumo fleet-status

# Re-enable in murakumo routing
murakumo set-node-healthy --pool rust --node <node>

# Return traffic split to 50% (temporary, investigate issue)
murakumo set-traffic-split --cljc-percent 50

# Notify owner, schedule root cause analysis
```

### Scenario 2: Archive upload to B2 fails

```bash
# Keep workdir on nodes (don't delete)
# Retry B2 upload with exponential backoff
while [ $retry_count -lt 5 ]; do
  datalad push -d . --to origin-b2 && break
  ((retry_count++))
  sleep $((2 ** retry_count))  # 2, 4, 8, 16, 32 seconds
done

# If still failing, escalate to infra-team (B2 availability issue)
```

### Scenario 3: Traffic somehow routes to Rust (shouldn't happen)

```bash
# Emergency stop: disable all Rust nodes immediately
for node in naphtali simeon judah zebulun levi joseph issachar dan benjamin asher; do
  ssh $node "pkill -9 -f 'kotoba-server'"
done

# Verify cljc pool handles 100% traffic (should happen automatically via routing layer)
murakumo metrics-query "rate(http_requests_total{pool='cljc'}[30s])" | tail -1
# Should show full throughput (same as total before decommissioning)

# Investigate routing layer (murakumo control-plane)
ssh murakumo-control-plane "journalctl -u murakumo-router -n 100"
```

---

## Documentation & Audit Trail

### Files to Update

1. **manifest/fleet-db.edn**: Record decommissioning (done above)
2. **90-docs/fleet-migration/rust-fleet-final-state-2026-08-26.edn**: Capture final state snapshot

```edn
[{:db/id -1
  :document/type "rust-fleet-final-state"
  :capture-date "2026-08-26T11:00:00+09:00"
  :nodes
  [{:name "naphtali" :host "naphtali" :port 8077 :final-commit "abc123def456" :archive-location "s3://kotobase.b2/murakumo-kotoba-server-final-1724067000.tar.gz"}
   ... (repeat for all 7 nodes)]
  :decommissioning-reason "Strangler-fig migration: cljc fleet validated, Rust fleet no longer needed"
  :expected-recovery-time "none (permanent decommissioning)"
  :recovery-procedure "Restore from B2 archive if urgent recovery needed (1-2 hours)"}]
```

3. **90-docs/fleet-migration/month-3-week-2-completion-report.md**: Post-decommissioning report

```markdown
# Month 3, Week 2 Completion Report

**Date**: 2026-08-26  
**Executor**: Ops Team  
**Owner**: Jun Kawasaki

## Decommissioning Summary

✅ **Rust Fleet Fully Decommissioned**

- **Nodes affected**: 7 (naphtali, simeon, judah, zebulun, levi, joseph, issachar, dan, benjamin, asher)
- **Decommissioning duration**: 1 day (2026-08-25 to 2026-08-26)
- **Traffic migration status**: 100% on cljc fleet
- **Archive status**: All workdirs archived to B2 DataLad
- **Incidents**: None

## Traffic Status

- **cljc pool**: 10 active nodes, 100% traffic
- **rust pool**: Offline, decommissioned
- **Performance**: p99 ≤ 100ms, throughput stable, 5xx ≤ 0.01%

## Next Steps

1. Capture performance baseline (Week 2, Day 5)
2. Finalize capacity plan
3. Begin Phase 4 planning (other app migration)
```

### Audit Log Entry

```edn
{:event/timestamp "2026-08-26T11:00:00+09:00"
 :event/type :rust-fleet-decommissioning-complete
 :executor "ops-lead"
 :nodes-decommissioned 7
 :archive-location "s3://kotobase.b2/murakumo-kotoba-server-final-{timestamps}"
 :traffic-status "100% cljc, 0% rust"
 :result :success
 :duration-hours 24}
```

---

## Contacts & Support

- **Primary**: ops-lead (ops@gftd.group)
- **Secondary**: infra-team (infra@gftd.group)
- **Owner**: Jun Kawasaki (jun@gftd.group)
- **Escalation**: If any step fails, immediately notify owner + escalate to infra-team

---

## Sign-Off

- **Procedure Reviewed**: Jun Kawasaki
- **Approved**: 2026-08-18 (4 days before execution)
- **Executed**: 2026-08-25 to 2026-08-26
- **Verified**: 2026-08-26 11:00 JST
