# Run 0006 — net-kotobase Standard commercial closure

**Status:** continuing — owner decisions complete; counsel/E2E pending
**Started:** 2026-07-24
**Mode:** unblock one truthful self-serve sale

## Hypothesis

net-kotobase Standard is the shortest portfolio path to a green product because
Gftd Japan is already identified as operator, gross-margin assumptions exist,
and Stripe checkout/webhook/entitlement boundaries are implemented or designed.
Closing the commercial unknowns should cost less than changing the AWAI
contracting structure or resolving an adult-product safety boundary.

## Score

| Axis | 0–5 | Weighted | Evidence |
|---|---:|---:|---|
| P(payment ≤30d) | 2 | 8 | no external purchase intent yet |
| Speed to evidence | 3 | 6 | owner decisions and test-mode evidence are bounded |
| External demand | 1 | 2 | technical production evidence, no paid demand evidence |
| Payment readiness | 4 | 8 | Standard-only Stripe route and signed webhook logic build; real test-mode E2E unverified |
| 12m gross profit | 3 | 9 | proposed Standard ¥980, modeled ~88% GM |
| Repeat | 5 | 10 | monthly subscription |
| Margin | 4 | 4 | storage-cost model exists, not actual-customer measured |
| Learning | 5 | 5 | validates price, onboarding and buyer |
| Spillover | 4 | 4 | reusable Gftd Japan legal/payment boundary |
| Confidence | 2 | 4 | mostly repository plans, little external evidence |
| **Total** |  | **60/100** | hard gate currently red |

## Selected action

Prepare and obtain decisions for one product only: `net-kotobase Standard`.
Do not expand Pro/Regulated, add features, publish draft policies, deploy, or
accept payment during this run.

Required closure:

1. owner decides price/tax/billing/cancellation/refund/support;
2. price ADR is ratified or replaced;
3. implementation facts needed by privacy are verified;
4. counsel-approved terms/privacy are committed;
5. Stripe test mode checkout-to-entitlement and cancellation are verified;
6. owner records explicit live-sale approval.

## Funnel before

- qualified external buyer intent: 0 evidenced
- checkout start: 0 evidenced
- verified nonowner payments: 0
- recurring paid tenants: 0

## Success / continue / stop

- Success: every commercial gate is green and one truthful Standard offer may
  be shown to an external prospect.
- Continue: internally verifiable implementation facts or test-mode E2E remain.
- Stop: owner decisions or counsel approval remain after all internal evidence
  is assembled.

## Next executable step

Owner supplies the decisions below; blanks are intentionally not inferred.

| Decision | Owner answer |
|---|---|
| Standard price and currency | JPY 980/month |
| Tax-inclusive or tax-exclusive display | 税込 |
| Monthly and/or annual billing | 月払いのみ、期間の定めなし、自動更新 |
| Cancellation effective immediately or at period end | いつでも申請可、当期末終了、解約料なし |
| Refund rule and exceptions | 原則日割なし。法令、重複・誤請求、購入済み役務の未提供は例外 |
| Support contact and accountable person | hello@gftd.co.jp / Gftd Japan Customer Support |
| Minimum contracting age | 18 |
| Liability cap | 過去12か月の支払額。強行法規、故意・重過失は除外 |
| Terms-change notice method/period | emailまたはservice内表示で原則30日前。security/legal/harm防止は短縮可 |

Owner decisions were applied to the accepted pricing ADR, review-ready Terms,
and a draft commercial disclosure on 2026-07-24. They are commercial decisions,
not a representation that counsel has approved the documents.

## Internal evidence completed

On 2026-07-24 the standalone pure billing suite passed:

```text
clojure -Sdeps '{:paths ["src" "test"]}' -M -m kotobase.billing-test
Ran 4 tests containing 16 assertions.
0 failures, 0 errors.
```

This verifies the repository logic for checkout completion → paid tenant state,
subscription lifecycle → cancellation state, missing-tenant fail-closed
behavior, and paid metrics. It does **not** verify Stripe signature handling,
real test-mode Checkout, deployed KV persistence, or live entitlement.

An initial invocation through the repository's `:test` alias also ran the
broader component suite and exposed 3 pre-existing provider-source/artifact
catalog failures. Those failures are not evidence against the four standalone
billing tests, but they prevent treating the overall component suite as green.

Commercial implementation alignment completed on 2026-07-24:

- checkout allowlist changed from Standard/Pro to Standard only;
- Pro purchase controls were removed from signup/admin while planned copy remains;
- Standard now displays ¥980/month, tax included, monthly renewal and
  period-end cancellation;
- landing/signup/admin static documents and production Worker bundle regenerated;
- site rendering: 20 tests / 110 assertions passed;
- billing domain: 4 tests / 16 assertions passed;
- deployed-bundle route surface: 6 tests passed;
- production bundle built successfully; existing compiler warnings remain.

No deploy or external payment was performed. The remaining red gate is accurate
privacy/subprocessor/retention disclosure, counsel review, and a real Stripe
test-mode checkout/webhook/KV/cancellation trace.
