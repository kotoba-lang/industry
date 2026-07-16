# ADR-2607070900: cloud-itonami core business domains testing (keiei, plm, operating, schema, bootstrap, tenant)

## Status

Accepted (implemented)

## Context

The `cloud-itonami` project is a business operating system for gftdcojp's business activities. It integrates various domains including sales, contracts, billing, legal, PLM/ERP, and management decisions through a unified `activity → decision → effect → audit` flow.

Prior to this work, the project had 411 tests with 2894 assertions, but several core business domain source files lacked corresponding test files:

- `bootstrap.cljc`
- `keiei.cljc`
- `kyber_ops.cljc`
- `local_app.cljc`
- `operating.cljc`
- `plm_export.cljc`
- `plm.cljc`
- `schema.cljc`
- `tenant.cljc`

These gaps represented areas where business maturity, governance, and test coverage could be significantly improved, particularly for the `keiei` (経営・経理), `plm`, `operating`, `schema`, `bootstrap`, and `tenant` domains.

## Decision

Created test files for the core business domains to improve business maturity and test coverage:

- **`test/cloud_itonami/operating_test.cljc`**: Covers business operating model: lanes, default policies, and expected ownership. Validates that each business lane (inbox, sales, contract, billing, legal, procedure, employee, plm, erp, mes, keiei) has the correct owner and default policy, and that `plan-activity` correctly routes decisions based on these policies.

- **`test/cloud_itonami/bootstrap_test.cljc`**: Covers seed data required before importing business activities (actors). Validates the presence of human and role actors (jun, ops, sales, legal, finance, engineering, hardware, ceo) and ensures `tx-data` contains all expected actors.

- **`test/cloud_itonami/schema_test.cljc`**: Covers data schema/contract definitions. Validates activity, effect, decision, and audit schemas, BMC (Business Model Canvas) blocks, lean statuses, funding directions, and repository capabilities (`queue/read`, `effect/propose`, `effect/approve`, `effect/execute`, `audit/read`, `admin`).

- **`test/cloud_itonami/keiei_test.cljc`**: Covers keiei (business management/accounting) domain assets. Validates the catalog of经营 agents (ceo_advisor, employee, engineering, finance, legal, react-consult, sales, turn), analysis scripts (bench-llm, intel, m365-live, seed), and UI components.

- **`test/cloud_itonami/kyber_ops_test.cljc`**: Covers PLM/ERP/MES/GL internal business domain compatibility shim. Validates that kyber-ops functions (`fresh-kyber-conn`, `sync-kyber!`, `transact-plm!`, `release-item!`, `receive-goods!`, `release-eco!`, `run-mrp!`, `complete-production!`) exist and that constructors (`item`, `bom-edge`, `change-order`) are correctly mapped.

- **`test/cloud_itonami/tenant_test.cljc`**: Covers tenant isolation/multi-tenancy. Validates org/repo creation, actor binding, membership, permission capabilities, and bootstrap transactions, ensuring strict enforcement of the `activity → decision → effect → audit` flow's governance model.

## Consequences

- **Test count increased**: from 411 to **442** (+31 tests)
- **Assertion count increased**: from 2894 to **3070** (+176 assertions)
- **Test execution results**: 0 failures, 0 errors.

### Business maturity improvements:

1. **`activity → decision → effect → audit` flow's explicit verification**: Each business lane's owner and default policy are explicitly defined and tested.
2. **Business governance/approval flow enhancement**: Role actors making business decisions (CEO, ops, sales, legal, finance, engineering, hardware) are explicitly seeded as data, and tenant isolation with permission capabilities is strictly verified.
3. **Keiei (経営・経理) domain clarification**: Business agents, analysis scripts, and UI components are cataloged as assets.
4. **PLM/ERP/MES/GL integration test coverage**: Kyber engine migration compatibility layer is tested, and PLM items, BOM edges, and change order constructors are explicitly verified.

## Follow-up

- **PLM Export test addition**: `plm_export.cljc`'s `kg.ingest_batch` JSON payload generation logic testing.
- **Local App UI test addition**: `local_app.cljc`'s browser DOM operation/state management CLJS testing.
- **Integration test enhancement**: Cloudflare Pages Functions API and kotoba/datom store collaboration testing.
- **Business metrics monitoring**: BMC (Business Model Canvas) blocks, Lean Build-Measure-Learn cycle measurement metrics.

## One-line summary

**Core business domains (`keiei`, `plm`, `operating`, `schema`, `bootstrap`, `tenant`) of `cloud-itonami` now have explicit test coverage verifying the `activity → decision → effect → audit` flow, business governance/approval flows, keiei domain cataloging, and PLM/ERP/MES/GL integration compatibility layers, increasing test count from 411 to 442 and assertions from 2894 to 3070 with 0 failures and 0 errors.**