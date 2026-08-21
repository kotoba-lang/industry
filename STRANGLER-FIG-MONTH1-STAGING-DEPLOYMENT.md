# Strangler-Fig Month 1 Staging Deployment

**Strangler-FPattern Rollout for kotoba-server cljc Mesh**

- Reference: [ADR-2607072100](../90-docs/adr/2607072100-kotoba-server-fleet-deployment-strangler-fig.edn)
- Status: **ACTIVE — Month 1 Staging Phase**
- Timeline: 2026-07-20 to 2026-08-17 (4 weeks)
- Owner: Jun Kawasaki

---

## Quick Start

### 1. Provision Staging Infrastructure

```bash
cd <superproject-root>

# Deploy 3 cljc nodes in staging pool
nbb deploy/month-1-staging-deployment.cljs provision

# Expected output:
#   ✓ Staging pool: staging-cljc-month1 (3 nodes)
#   ✓ Canary traffic: 5%
#   ✓ Parity checker: enabled
#   ✓ Metrics collection: configured
#   ✓ Rollback automation: armed
#   ✓ SLO dashboard: ready
```

### 2. Start Canary Traffic & Monitoring

```bash
# Enable 5% traffic to staging
nbb deploy/month-1-staging-deployment.cljs canary-start

# Start parity checker (validates responses)
nbb deploy/parity-checker-service.cljs \
  --rust-pool-url http://localhost:8000 \
  --staging-pool-url http://localhost:8001 \
  --sample-rate 1.0 \
  --output metrics/parity-validation.log

# Start rollback automation (autonomous safeguards)
nbb deploy/rollback-automation.cljs monitor \
  --metrics-endpoint http://localhost:9090/api/v1/query \
  --check-interval-sec 30 \
  --dry-run false
```

### 3. Monitor SLO Dashboard

Open dashboard:
```
http://localhost:3000/d/strangler-fig-month1
```

**Live metrics**:
- p99 latency (target ≤150ms)
- Parity pass rate (target ≥99.99%)
- HTTP 5xx rate (target ≤0.01%)
- Memory per node (target ≤256MB)
- GC pause time (target ≤50ms)

### 4. Check Real-Time Status

```bash
# Parity validation progress
tail -f metrics/parity-validation.log | jq '.parity-pass?'

# Rollback automation status
nbb deploy/rollback-automation.cljs status

# Deployment status
nbb deploy/month-1-staging-deployment.cljs status
```

---

## Directory Structure

```
deploy/
├── month-1-staging-deployment.cljs    ← Main orchestration script
├── parity-checker-service.cljs        ← Parity validation service
├── rollback-automation.cljs           ← Automatic rollback engine
├── slo-dashboard-config.edn           ← Dashboard configuration
├── MONTH-1-OPERATIONS-GUIDE.md        ← Operational procedures (Week 1-4)
├── INCIDENT-RESPONSE.md               ← Incident procedures (if needed)
├── staging-pool-nodes.edn             ← Node configuration
└── jvm.options                        ← JVM tuning (GC, memory)

metrics/
├── parity-validation.log              ← Real-time parity checks (append-only)
├── rollback-audit.log                 ← Rollback events and decisions
└── baseline-window-*.edn              ← Weekly baseline snapshots

reports/
├── phase-1-final.md                   ← Phase 1 completion report
├── incident-<date>.md                 ← Incident analysis (if triggered)
└── baseline-analysis.edn              ← Statistical baseline analysis

DEPLOYMENT-STATUS.md                   ← Current deployment snapshot
DEPLOYMENT-SUMMARY.edn                 ← Deployment manifest
README.md                              ← This file
```

---

## Month 1 Timeline & Gates

| Week | Milestone | Status | Date |
|------|-----------|--------|------|
| **W1** | Infrastructure provisioned, 5% canary active | ✓ Ready | 2026-07-20 |
| **W2** | Baseline locked, parity validated | → Next | 2026-08-03 |
| **W3** | Extended validation, SLO confirmation | → Next | 2026-08-09 |
| **W4** | Phase 1 complete, Phase 2 approval | → Next | 2026-08-17 |

### Week 2 Decision Gate (2026-08-03)

**Questions**:
- Baseline metrics stable ±5%? ✓
- Parity pass rate ≥99.99%? ✓
- All SLO targets met? ✓
- Zero rollback triggers? ✓

**Owner Decision**: Go to Phase 2 ✓ / Hold / Abort

### Week 4 Final Gate (2026-08-17)

**Questions**:
- All SLO targets sustained 7 days? ✓
- Parity <0.01% failure rate? ✓
- No panics/crashes? ✓
- Ready for Phase 2 traffic ramp? ✓

**Owner Decision**: Go to Phase 2 traffic ramp ✓ / Extend / Abort

---

## SLO Targets (Month 1)

| Metric | Target | Budget | Note |
|--------|--------|--------|------|
| **p99 Latency** | ≤150ms | +50ms overhead | hard budget |
| **Parity Pass** | ≥99.99% | ≤0.01% failure | content-hash match |
| **HTTP 5xx** | ≤0.01% | 0.01% | unhandled exceptions |
| **Memory/Instance** | ≤256MB | 256MB peak | post-GC stable |
| **GC Pause p99** | ≤50ms | 50ms | ZGC/Shenandoah eval |
| **Throughput Loss** | ≤2% | vs baseline | staging vs prod |

---

## Rollback Safeguards (Automatic)

Triggered autonomously, **no owner approval required**:

| Trigger | Condition | Action |
|---------|-----------|--------|
| Latency Regression | p99 > 250ms × 5min | Reduce traffic 5% → 2.5% |
| Parity Divergence | >1% × 1min | Full revert to Rust |
| Reliability Degradation | 5xx >1% × 2min | Full revert to Rust |
| Memory Exhaustion | heap peak >512MB | Reduce traffic + expand pool |

**Audit**: All rollback events logged to `metrics/rollback-audit.log`

---

## Troubleshooting

### Parity Divergence Detected

```bash
# 1. Review divergence details
grep "DIVERGENCE" metrics/parity-validation.log | head -5

# 2. Analyze first mismatch
cat metrics/parity-validation.log | \
  grep "hash-mismatch\|status-mismatch" | head -1 | jq .

# 3. If it's a cljc bug:
#    → Fix in staging environment
#    → Re-run parity validation
#    → If fixed, resume traffic

# 4. If automatic rollback fired:
#    → Full revert to Rust (0% cljc traffic)
#    → Investigate root cause
#    → Update ADR if it's a known limitation
```

### Latency Spike

```bash
# 1. Check memory and GC
grep "jvm_gc_pause_ms\|jvm_heap_peak_mb" metrics/parity-validation.log | tail -10

# 2. If GC pause coincides with spike:
#    → Normal, expected during baseline collection
#    → GC tuning (ZGC/Shenandoah) will improve this Month 2

# 3. If latency sustained (>5min):
#    → Check Chicory compilation time
#    → Check WASM runtime overhead
#    → Investigate application logic
```

### Memory Growing Unbounded

```bash
# 1. Check heap trend
tail -100 metrics/parity-validation.log | \
  grep "jvm_heap" | jq '.value' | sort -n | tail -20

# 2. If growth detected:
#    → Memory leak suspected
#    → Automatic rollback fires at 512MB
#    → Investigate in staging environment

# 3. Tools for debugging:
#    → JVM heap dump: jmap -dump:live,file=heap.bin <pid>
#    → Analyze with Eclipse MAT or JProfiler
```

---

## Documentation

**Complete operations guide**: [MONTH-1-OPERATIONS-GUIDE.md](deploy/MONTH-1-OPERATIONS-GUIDE.md)  
**Deployment status**: [DEPLOYMENT-STATUS.md](DEPLOYMENT-STATUS.md)  
**Deployment manifest**: [DEPLOYMENT-SUMMARY.edn](DEPLOYMENT-SUMMARY.edn)

**Key sections**:
- Week 1: Infrastructure & baseline setup
- Week 2: Baseline validation checkpoint
- Week 3–4: Extended validation & Phase 1 completion
- Troubleshooting & incident response
- Rollback procedures (automatic & manual)

---

## Related References

### ADRs
- [ADR-2607072100](../90-docs/adr/2607072100-kotoba-server-fleet-deployment-strangler-fig.edn) — Strangler-fig strategy (this deployment)
- [ADR-2607072000](../90-docs/adr/2607072000-kotoba-rust-avoidance-policy-and-kotoba-server-gap.edn) — Rust avoidance policy
- [ADR-2607082400](../90-docs/adr/2607082400-kotoba-server-cljc-realization-component-model-wall.edn) — Component model realization
- [ADR-2607071900](../90-docs/adr/2607071900-murakumo-cross-node-apply-cljc-auction-gap-closed.edn) — Murakumo coordination
- [ADR-2607062330](../90-docs/adr/2607062330-kototama-tender-chicory-execution-runtime.edn) — Chicory runtime

### Repos
- [kotoba-lang/murakumo](../orgs/kotoba-lang/murakumo/) — Fleet orchestration
- [kotoba-lang/kototama](../orgs/kotoba-lang/kototama/) — WASM runtime & Chicory host
- [kotoba-lang/kotoba](../orgs/kotoba-lang/kotoba/) — Language compiler

---

## Support & Escalation

**Deployment Owner**: Jun Kawasaki (jun@gftd.group)  
**On-Call**: Murakumo ops team  
**Escalation**: Contact owner if critical issue  

**Quick Escalation**:
1. Review [MONTH-1-OPERATIONS-GUIDE.md](deploy/MONTH-1-OPERATIONS-GUIDE.md)
2. Check `nbb deploy/rollback-automation.cljs status`
3. Contact on-call ops
4. If needed, contact Jun Kawasaki directly

---

## Status & Next Steps

**Current Status**: ✓ Deployment ACTIVE — Week 1 baseline collection

**Next Steps**:
1. ✓ Provision infrastructure & start canary traffic
2. ✓ Monitor parity validation & SLO dashboard
3. → Week 2 checkpoint: baseline locked
4. → Phase 2 approval: traffic ramp 5% → 50%

**Expected Timeline**:
- Week 2 (08-03): Baseline validation gate
- Week 4 (08-17): Phase 1 completion, Phase 2 approval
- Month 2: Traffic ramp to 50%
- Month 3: Final cutover to 100% cljc

---

**Last Updated**: 2026-07-20  
**Version**: 1.0  
**Status**: ACTIVE
