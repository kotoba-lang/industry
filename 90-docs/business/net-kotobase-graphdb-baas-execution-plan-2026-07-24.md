# net-kotobase GraphDB BaaS execution plan

**As of:** 2026-07-24  
**Status:** active execution baseline  
**North Star:** external non-owner customers using and paying for a
content-addressed graph workspace, with verified recurring gross profit.

## Goal

Build net-kotobase into a GraphDB BaaS whose paid unit is a graph workspace,
not raw object storage.

Target monthly pricing:

| Tier | Price | Target customer |
|---|---:|---|
| Developer Standard | **¥2,980/month** | Individual developer or one small production graph |
| Team Pro | **¥19,800/month** | Team workspace, larger workload, migration and operational support |
| Regulated | **¥100,000/month and up** | Dedicated tenant, audit, key custody and contractual controls |

These are target prices to validate with external buyers. They do not authorize
live sale until the commercial go/no-go gates are green.

## Base business target

Conservative per-customer monthly gross-profit assumptions:

| Tier | Customers | GP/customer | Monthly GP |
|---|---:|---:|---:|
| Developer Standard | 30 | ¥2,860 | ¥85,800 |
| Team Pro | 8 | ¥18,600 | ¥148,800 |
| Regulated | 1 | approximately ¥80,000 | approximately ¥80,000 |
| **Total** | **39** |  | **approximately ¥314,600/month** |

```text
Annual gross profit = ¥314,600 × 12
                    = ¥3,775,200
                    ≈ ¥3.78M
```

The model must be replaced with observed storage, query, support, onboarding
and compliance costs as paid tenants arrive.

### Executable economics result — 2026-07-24

Run:

```text
cd orgs/gftdcojp/net-kotobase/clj-edge
clojure -M:economics
clojure -M:economics-test
```

The executable model reproduces 39 customers, JPY 347,800 monthly revenue, JPY
314,600 monthly GP and JPY 3,775,200 annual GP. It keeps modeled assumptions
separate from observations.

At initial planning conversion assumptions, 30/8/1 requires approximately:

- 100 activated Developer Standard prospects at 30% activated-to-paid;
- 32 activated Team Pro prospects at 25%;
- 10 Regulated discoveries at 10%.

The current live funnel is 1,221 visitors, 3 signups and zero checkout. Holding
that 1/407 visitor-to-signup rate plus modeled 40% signup-to-activation and 30%
Standard activation-to-paid would require approximately 101,750 visitors for
30 Standard customers. Therefore traffic scaling is not the next bottleneck;
activation correctness and founder-led Team Pro design partners come first.

Base monthly GP sensitivity to costs is:

| Cost vs initial assumption | Monthly GP | Annual GP |
|---:|---:|---:|
| 1x | JPY 314,600 | JPY 3,775,200 |
| 2x | JPY 281,400 | JPY 3,376,800 |
| 5x | JPY 181,800 | JPY 2,181,600 |

The resulting priority is hosted pin/retrieve, Team Pro design partners,
Regulated discovery, then Developer Standard self-serve funnel scaling.

Public GitHub acquisition currently provides no design-partner pipeline:
`gftdcojp/net-kotobase` and `kotoba-lang/kotobase` each have zero stars and
zero forks; net-kotobase's only human contributor is the owner. The 1,221 web
visitors therefore cannot be treated as developer-community demand. The next
10 conversations require founder-led outbound or existing external
relationships, using `docs/DESIGN-PARTNER-OFFER.md`; organic GitHub growth is
not the immediate acquisition plan.

## Product wedge

The initial product is not a general replacement for every operational
database. It is one content-addressed fact history with:

- Datomic-shaped datoms and native Datalog;
- immutable history and graph commits identified by CID;
- structured SPARQL and Cypher adapters over the same fact model;
- Git object/ref projection into datoms;
- tenant-scoped CACAO authorization;
- durable graph blocks and audit/export evidence;
- portable local and hosted IStore contracts;
- API, agent and MCP access to the same tenant graph.

The first buyer segments are:

1. AI agent and application teams that need durable memory and provenance;
2. compliance, evidence and PLM/data teams;
3. teams currently combining a graph DB, object storage and custom provenance;
4. regulated buyers that need dedicated operation and an auditable boundary.

## Architecture decision

net-kotobase becomes the primary store for graph-native domain state:

- entities, relationships and classifications;
- immutable facts and decision history;
- content, model and document provenance;
- business evidence and approval history;
- queryable audit and knowledge graphs.

It is not initially the primary store for:

- Stripe payment and webhook idempotency;
- entitlement and billing status;
- login and session state;
- realtime message delivery or presence;
- high-contention counters and coordination;
- support and refund workflow state.

Those transactional boundaries remain on D1 or Durable Objects. R2 holds
artifacts, exports and backups; KV is limited to caches, catalogs and
low-frequency configuration. A durable outbox connects transactional commits
to graph updates and supports replay without making customer checkout depend
on net-kotobase availability.

## Internal design partners

### cloud-itonami

Use net-kotobase as the graph primary for:

- company, buyer, opportunity and product relationships;
- ISIC, UNSPSC and LEI facts;
- proposal, approval and execution history;
- agent decisions and evidence;
- commercial funnel and implementation provenance.

The dogfood proof must issue real hosted writes and reads. Mirroring logs
without making a business query from the hosted graph does not count.

### club-shinshi

Use net-kotobase for:

- creator, character, artifact and provenance relationships;
- consent receipts and moderation evidence;
- companion memory and recommendation grounds.

Keep payment, entitlement, age/safety current state, chat delivery and
settlement on strongly consistent managed storage.

## Current evidence baseline

Observed before execution:

- kotoba-lang/kotobase remote contract suite: 34 tests / 146 assertions green;
- Promise-returning IStore ClojureScript path green;
- net-kotobase edge Worker: approximately 248 boundary checks green;
- CF-Wasm graph engine: 76 tests / 142 assertions green;
- local YCBench: 2,000 entities / 4,000 datoms, semantic parity and Git
  projection green, technical score 100/100;
- latest warm distinct reads: approximately 0.24–0.49 seconds, improved from
  approximately 1.6 seconds;
- hosted Datalog and tenant Datom writes exist;
- hosted structured SPARQL/Cypher and KG routes are not yet implemented on the
  current backend;
- service is alpha and does not offer a customer SLA;
- recorded funnel: 3 signups, zero checkout starts, zero activated external
  graph tenants, zero paid subscriptions;
- real Stripe test-mode checkout-to-entitlement evidence and commercial release
  remain incomplete.

Local semantic success must not be reported as hosted or commercial success.

### Hosted cloud-itonami dogfood result — 2026-07-24

The reproducible proof command is:

```text
cd orgs/gftdcojp/cloud-itonami
clojure -M:dev:kotobase-dogfood <run-id>
```

It uses a dedicated actor and `itonami/graphdb-baas-dogfood` database, submits
10 sanitized activation nodes / 50 business datoms, runs three Datalog
queries, and emits a secret-free receipt containing the deterministic graph
CID. Its local XRPC contract test is green: 1 test / 9 assertions.

Live production evidence initially failed, and the investigation established:

- three schema-less runs received successful transact responses but immediate
  and repeated reads returned zero rows by both graph CID and authenticated
  `db_name`;
- the graph CID was consistently
  `bafyreic2s4nyoryhrwaytevit3ij7nq5sn2paaphbfzbdxm22tzvddcydy`;
- sending schema and data as two transactions on one CACAO made the second
  call return 401 because hosted CACAO nonces are single-use;
- combining the five-attribute schema and 50 facts into one atomic transaction
  passed, but reads still returned zero rows, including with a newly minted
  read session;
- therefore “facts written” is not claimed: the receipt records
  `facts-submitted`, and the activation gate remains failed.

The root cause of the empty application-level queries was the client response
decoder, not persistence. The production edge returns query cells under
`rows`, with JSON numbers/booleans already decoded; the existing
`langchain.kotoba-db` client read only `rows_edn` and attempted to EDN-decode
every cell. After accepting both response keys and native scalar cells, hosted
run `proof-20260724-hosted-green-candidate` proved:

- one commit containing 56 datoms (5 schema attributes plus 50 business facts
  and identity material);
- commit CID
  `bafyreic2cxyhp7rqfxzlrksprawk7bivi2ruvjhudqhacnxzweqbtyqwu4`;
- all three Datalog query classes successful;
- direct hosted `datomic.datoms` read of the same graph successful;
- graph CID and authenticated `db_name` resolve to the same graph.

The remaining activation failure is CID pin/retrieval:

- the standard `/pins` API returns 502 `PINNING_FAILED` because its upstream
  returns an invalid JSON content type;
- XRPC `pinCreate` returns 404 `NotFound`;
- `ipfs.gftd.ai` returns Cloudflare 530 and `kotobase.net/ipfs/<cid>` returns
  502 `archive gateway unavailable`.

Thus hosted transact/query coverage is green, while durable public pin and
retrieval coverage remains red. SDK flows requiring multiple writes must also
mint a fresh CACAO per write or batch them atomically because CACAO nonces are
single-use.

A tenant-scoped native graph-pin control path is now implemented locally. A
pin request includes `meta.graph`; the edge verifies that the requested commit
CID occurs in that graph's authenticated `datomic.log` before storing the pin
record in tenant state. Create, get and delete lifecycle coverage is green in
`test:native-control`, including the existing IPLD receipt path. The
cloud-itonami dogfood client now sends the graph identity with both PSA and
legacy XRPC pin requests. Its focused contract remains green at 1 test / 9
assertions.

Production Worker version
`84cc7df0-14ab-45cb-b6f4-914133455ebb` deployed the bounded change from a
clean `origin/main` worktree. Public production and B2 smoke checks passed.
Run `proof-20260724-native-pin-production-v4` then proved:

- 50 submitted business facts and all three hosted Datalog query classes;
- graph CID
  `bafyreic2s4nyoryhrwaytevit3ij7nq5sn2paaphbfzbdxm22tzvddcydy`;
- commit CID
  `bafyreiasciyxzpzyrk2cf75xoy2hxmbwju4pwdxcobadmob5lktpm5lgha`;
- PSA create 202 and tenant-scoped PSA get 200;
- the same commit CID present in `datomic.log` at t=20;
- `datomic.tx` retrieval at t=20 with `found=true` and the same CID.

The cloud-itonami internal activation gate is therefore green. Public
IPFS-gateway availability is still a separate archive/export capability and
must not be claimed as green. The deploy source and evidence are retained in
`gftdcojp/net-kotobase` PR #219.

## Execution loop

```text
OBSERVE
  → SCORE one revenue or activation bottleneck
  → SELECT one bounded action (WIP=1)
  → EXECUTE
  → VERIFY hosted technical evidence and external funnel evidence
  → UPDATE price, cost, reliability and conversion priors
  → DECIDE scale | continue | change | stop
  → RECORD
  → OBSERVE
```

Each run is limited to 14 days or 20 person-hours, whichever comes first.
Code completion alone does not increase the business score.

## Phase 1 — 30-day validation

### Hosted product gates

1. Deploy one isolated cloud-itonami tenant graph.
2. Complete an authenticated hosted flow:
   `transact → Datalog query → CID commit → retrieve by CID`.
3. Connect structured SPARQL and structured Cypher adapters to the hosted
   backend without claiming full grammar or wire-protocol compatibility.
4. Connect the minimum KG ingest/query path needed by the design-partner use
   case.
5. Produce a reproducible audit bundle containing query, basis, result and CID.
6. Resolve the remote-main component catalog failures and dependency
   vulnerabilities required by the release gate.

### Activation definition

A tenant is activated only when, within 24 hours, it:

- ingests at least 50 facts;
- completes at least 3 successful queries;
- pins at least 1 graph commit by CID.

Internal activation is dogfood evidence, not external demand.

### External evidence gates

- 10 external buyer conversations;
- at least 5/10 repeating the graph + provenance problem;
- 3 activated external tenants;
- 1 paid Developer Standard or Team Pro tenant;
- 1 Regulated discovery call;
- measured willingness to pay at ¥2,980 / ¥19,800 / ¥100,000+;
- median time to first hosted graph commit below 10 minutes.

### Stop and change rules

- Fewer than 5/10 interviews share the problem:
  narrow the wedge to Git-verifiable AI memory or compliance evidence.
- Activation but no checkout:
  change packaging, price presentation or paid audit/retention value before
  adding query languages.
- No external activation after 3 concierge attempts:
  stop self-serve polish and diagnose onboarding and hosted correctness.
- Reliability or isolation failure:
  stop external onboarding and fix that gate only.

## Phase 2 — first repeatable revenue

Target:

- 10 Developer Standard;
- 2 Team Pro;
- one credible Regulated pipeline opportunity;
- 30-day active-graph retention measured by tier;
- observed infrastructure and support cost per tenant;
- first gross-margin reconciliation against the pricing model.

Required operating metrics:

- activated tenants;
- weekly active graphs;
- successful and failed ingest/query counts;
- p50/p95/p99 latency by operation and tier;
- same-graph contention and retry rate;
- replication/outbox lag;
- stored bytes and retained history;
- support and onboarding hours;
- MRR, fees, marginal cost and gross profit;
- upgrade, downgrade, cancellation and retention.

## Phase 3 — Base composition

Scale only after Phase 1 and Phase 2 evidence:

- Developer Standard: 30 active paid customers;
- Team Pro: 8 active paid customers;
- Regulated: 1 active contract at ¥100,000/month or more;
- monthly gross profit approximately ¥314,600;
- annualized gross profit approximately ¥3.78M.

Do not count owner purchases, test payments, internal dogfood, fixture tenants,
unverified checkout starts or unsigned payment claims.

## Commercial gates

Before live paid sale:

- prices, tax treatment, renewal, cancellation and refund terms approved;
- Terms, Privacy, DPA and processor facts match the implemented data flow;
- operator and merchant/collection boundary are valid;
- Stripe test mode proves checkout, signed webhook, entitlement, renewal and
  cancellation;
- support/refund owner is recorded;
- Regulated claims are limited to controls actually implemented and
  contractually reviewed;
- recovery and rollback evidence exists for the offered tier.

### Commercial implementation result — 2026-07-24

The repository previously contained a dangerous price split: the product UI,
Terms draft, counsel packet and configured Stripe Price ID still described
Standard at JPY 980, while the active business target and YCBench required JPY
2,980. The implementation now:

- aligns product source and generated pages to Developer Standard JPY 2,980,
  Team Pro JPY 19,800 and Regulated JPY 100,000/month and up;
- keeps Team Pro assisted and Regulated contractual rather than enabling them
  as unapproved self-serve checkout tiers;
- updates the Terms draft, counsel packet and pricing ADR addendum;
- adds an exact fail-closed Worker gate,
  `KOTOBASE_COMMERCIAL_RELEASE=standard-2980-v1`;
- proves locally that an absent/wrong release gate creates no Stripe session;
- proves checkout metadata, signature-verified webhook fulfillment,
  cancellation ordering and the 300-second replay window in the Stripe E2E.

`worker/test/stripe_e2e_test.cljc` and the six site-route checks are green.
Live release remains red until the Stripe catalog, merchant boundary and
counsel approval are verified. The release variable must remain unset.

### Stripe account and merchant-boundary result — 2026-07-24

Direct Stripe API evidence supersedes the earlier assumption that configured
Price IDs existed:

- both configured Worker Price IDs are absent in the authenticated account in
  both test and live mode;
- the account initially contained zero test/live Products, Prices and webhook
  endpoints;
- a test-only catalog was created for JPY 2,980 Standard, JPY 19,800 Pro and
  JPY 100,000 Regulated, all monthly;
- a real Stripe test Checkout Session for Standard returned JPY 2,980 total,
  subscription mode and the tenant/tier fulfillment metadata;
- the live account has zero live Products/Prices, payouts are not enabled, and
  no live commercial release was enabled;
- the live Stripe merchant profile is `AWAI Network, L.L.C.` in the US, while
  the legal drafts name `Gftd Japan 株式会社`.

The test price/checkout gate is green and recorded in
`docs/evidence/stripe-test-catalog-2026-07-24.edn`. The live sale gate remains
red because the merchant/operator mismatch, payout readiness, live catalog,
webhook registration and counsel approval are unresolved. The owner must
provision a Gftd Japan Stripe merchant account before any live Product, Price
or checkout release is created.

The owner selected `Gftd Japan 株式会社` as both contracting operator and Stripe
merchant on 2026-07-24. The existing AWAI Network, L.L.C. Stripe account,
credentials and test catalog are prohibited from promotion into net-kotobase
live sale. The decision and exact account/catalog/webhook acceptance gates are
recorded in `docs/evidence/merchant-decision-2026-07-24.edn`.

Signup and admin source now add an explicit pre-Stripe confirmation containing
the exact JPY 2,980 tax-inclusive price, automatic renewal, charge and service
timing, cancellation, refund exceptions, operator and contact. Canceling it
does not call the checkout endpoint. Generated-page tests are green at 20
tests / 114 assertions and the compiled Worker route suite is green at 6
checks. This confirmation remains undeployed until the displayed operator
matches the selected Stripe merchant.

## Immediate ordered backlog

1. Recruit and record 10 external design-partner conversations, prioritizing
   Team Pro because 8 customers contribute more modeled GP than 30 Standard
   customers.
2. Activate three external tenants by concierge onboarding and measure time to
   first commit, support minutes and retained graph use.
3. Verify or create the JPY 2,980 Stripe test Price and complete the exact
   checkout-to-entitlement evidence; keep live commercial release closed.
4. Obtain the first paid Standard or Pro tenant and one Regulated discovery.
5. Add the outbox-backed production boundary as required by the first external
   integration.
6. Close only the structured SPARQL/Cypher or KG route gaps demanded by those
   activated design partners.
7. Replace modeled costs and probabilities with observed cohort economics.

## Operating scoreboard and next loop

| Gate | Target | Current verified state | Status |
|---|---:|---|---|
| Hosted transact | 50 facts | 50 business facts in a 56-datom commit | Green |
| Hosted query | 3 queries | 3/3 Datalog query classes | Green |
| Commit identity | 1 CID | commit and graph CIDs recorded | Green |
| Native graph pin | create/get/delete | local edge lifecycle green | Local green |
| Production pin/retrieve | 1 end-to-end receipt | v4 receipt, PSA + tx retrieval | Green |
| External targets identified | 10 | 10 public-evidence targets | Green |
| External conversations | 10 | 0 recorded | Red |
| External activations | 3 | 0 | Red |
| Paid tenants | 1 initial, then 30/8/1 | 0 | Red |
| Regulated discovery | 1 | 0 | Red |
| Stripe test catalog | 3 exact tier prices | exact JPY monthly Prices | Green |
| Stripe test checkout | Standard JPY 2,980 | real open test Session | Green |
| Live merchant/legal | one matching entity | AWAI LLC vs Gftd Japan | Red |

The active WIP is now external customer evidence. Hosted correctness is
sufficient for concierge onboarding; new speculative query features are not
the current bottleneck:

```text
production proof
  → 10 conversations
  → 3 concierge activations
  → first paid customer + regulated discovery
  → observe real cost/retention
  → revise offer and economics
  → scale toward Standard 30 / Pro 8 / Regulated 1
```

Every weekly review records four numbers: activated external tenants, paid
tenants by tier, monthly observed GP, and the single largest conversion or
reliability bottleneck. New backend features enter WIP only when they remove
that bottleneck or are repeatedly requested by activated buyers.

## Decision rule

The plan succeeds only when technical evidence and external economic evidence
advance together. net-kotobase should be used deeply enough to create a
credible customer proof, but payment, entitlement and realtime correctness
must not be made dependent on alpha graph infrastructure.
