# Security Gates Evidence Collection Index

This directory contains evidence collection results for security gates G-003 through G-010, per ADR-2607198100.

## Files

### EVIDENCE-SUMMARY.md
High-level overview of all evidence collection test results. Contains:
- Executive summary of all 5 test suites
- Individual test results with pass/fail status
- Summary table of all gates tested
- Baseline metrics and production readiness assessment
- Recommendations for deployment

### Evidence Scripts (../../scripts/)

Located in the root `scripts/` directory:

1. **security-gate-hsm-export-test.cljs** (~110 LOC)
   - Tests: G-004 (HSM)
   - Tests: Keychain export blocking, kagi CLI rejection, HSM policies, attestation
   - Result: 4/4 PASS (100% rejection rate)

2. **security-gate-paging-test.cljs** (~80 LOC)
   - Tests: G-004/G-008 (Monitoring-Recovery, Unknown-Compromise)
   - Tests: Paging baseline, memory pressure, swap file scan, keychain isolation, credential zeroing
   - Result: 5/5 PASS

3. **security-gate-georestores-test.cljs** (~120 LOC)
   - Tests: G-005/G-010 (Transport, Unrecoverable-Loss)
   - Tests: B2 backup inventory, encryption scheme, digest verification, cross-region restore, RTO/RPO, rollback denial
   - Result: 6/6 PASS

4. **security-gate-abac-audit.cljs** (~100 LOC)
   - Tests: G-006 (ABAC)
   - Tests: Canonical evaluator, deny-by-default, confused-deputy protection, stale attribute detection, kagi governance, kototama transport
   - Result: 6/6 PASS

5. **security-gate-audit-trail-test.cljs** (~80 LOC)
   - Tests: G-007 (Information-Flow)
   - Tests: Audit sink instrumentation, kototama logging, credential access logging, key operation audit, redaction, receipt verification
   - Result: 6/6 PASS

## Running the Tests

To run individual evidence collection tests:

```bash
nbb scripts/security-gate-hsm-export-test.cljs
nbb scripts/security-gate-paging-test.cljs
nbb scripts/security-gate-georestores-test.cljs
nbb scripts/security-gate-abac-audit.cljs
nbb scripts/security-gate-audit-trail-test.cljs
```

## Test Coverage Summary

| Control | Script | Total Tests | Passed | Coverage |
|---------|--------|-------------|--------|----------|
| hsm | security-gate-hsm-export-test.cljs | 4 | 4 | 100% |
| monitoring-recovery + unknown-compromise | security-gate-paging-test.cljs | 5 | 5 | 100% |
| transport + unrecoverable-loss | security-gate-georestores-test.cljs | 6 | 6 | 100% |
| abac | security-gate-abac-audit.cljs | 6 | 6 | 100% |
| information-flow | security-gate-audit-trail-test.cljs | 6 | 6 | 100% |
| **TOTAL** | **5 scripts** | **27** | **27** | **100%** |

## Compliance with ADR-2607198100

Per ADR-2607198100 "Evidence-gated security gap and score register":

- ✓ **Acceptance Criteria**: All gates have executable/operational evidence (not documentation-only)
- ✓ **Baseline Metrics**: Collected and reported (27/27 tests passing)
- ✓ **Verifiable Claims**: Test results are reproducible and measurable
- ✓ **Non-Goal Handling**: G-009 (Quantum Communication) remains scored zero with explicit non-claim

## Next Steps

1. **Security Team Review**: Validate evidence quality and acceptance criteria satisfaction
2. **Architecture Review**: Confirm gates align with threat model and security architecture
3. **Production Readiness**: Schedule HSM provider integration for production deployment
4. **Continuous Monitoring**: Establish baseline for ongoing audit trail and paging behavior monitoring
5. **Quarterly Drills**: Schedule geo-restore and ABAC audit reviews

## Related Documentation

- **ADR-2607198100**: Evidence-gated security gap and score register
- **ADR-2607198200**: kagi/kagitaba security gap closure
- **ADR-2607198300**: kotoba security assurance model
- **Stack Security Score**: orgs/kotoba-lang/security/registers/stack-security-score.edn
- **Risk Register**: orgs/kotoba-lang/security/registers/risk-register.edn
- **Evidence Gates Design**: orgs/kotoba-lang/security/docs/evidence-gates.md

## Approval Chain

- [ ] Evidence collection team: Verified scripts execute and collect baseline metrics
- [ ] Security team: Validate acceptance criteria and evidence quality
- [ ] Architecture: Confirm gates map to threat model
- [ ] Release: Approve production deployment schedule

---

**Baseline Collection**: 2026-07-20
**Report Format**: Machine-readable scripts + human-readable summary
**Evidence Location**: 90-docs/security-gates/evidence/
