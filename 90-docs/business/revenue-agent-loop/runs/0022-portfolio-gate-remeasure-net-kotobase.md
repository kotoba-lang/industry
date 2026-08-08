# Run 0022 — portfolio gate re-measurement (net-kotobase, club-shinshi, net-babiniku)

**Status:** completed — hypothesis refuted; recommendation reversal confirmed
**Started / ended:** 2026-08-08
**Owner:** agent
**Mode:** cash-first
**Prior run:** 0021 (2026-08-08) — cloud-itonami gate re-measurement

## Hypothesis

Run 0021 found that four of cloud-itonami's six red gates had been closed 15 days
earlier without the table being updated, and ADR-2608080200 recorded that the other
three products had not been re-measured. The working hypothesis was that
**net-kotobase would come out ahead of cloud-itonami**, because its operator is
Gftd Japan K.K. — a domestic Japanese company — so cloud-itonami's one remaining
red (AWAI's foreign-company registration and tax position) is structurally absent
there.

## Result

**Half right, and the half that was right does not matter.**

The foreign-company red genuinely does not apply to net-kotobase: `terms.md` §
operator names Gftd Japan 株式会社, GranTokyo South Tower, Corporate Number
1011101086505 — a Japanese corporation, no foreign registration question.

But net-kotobase is **behind cloud-itonami on every documentary gate**, and the
2026-07-24 record understated that too — in the other direction.

| Gate | net-kotobase measured 2026-08-08 | cloud-itonami 2026-08-08 |
|---|---|---|
| Terms | **still DRAFT.** `terms.md` line 3: `> **DRAFT — This is not legal advice. Review by qualified counsel is required before publication.**` Not on the product domain at all — `kotobase.net/legal/terms/` is a real 404; the only link is a GitHub blob | owner-approved, effective 2026-07-24, live at `/legal/terms/` |
| Privacy | **does not exist.** `kotobase-docs/privacy.md` → 404. No privacy link on `kotobase.net` root | live, 16,902 B |
| DPA | **does not exist.** `kotobase-docs/dpa.md` → 404 | live, 10,047 B, incorporated by Terms §7.1 |
| 価格 | `/pricing` is live and describes three plans by operational-risk boundary, but **carries no price**. No `¥`, no figure anywhere on the page. The ¥980/mo Standard in SCORECARD is a proposal, unpublished | same weakness (no published price page), but currency/cycle/tax fixed in Terms §6.1/§6.4 |
| 税・返金・解約 | five unresolved `[CONFIRM: …]` markers in `terms.md` — minimum age, current prices, billing cycle, tax handling, **refund policy** | tax closed (§6.4 exclusive + reverse charge); refund/cancellation absent — the one gap the two share |
| 決済レール | no `/api/billing/status` endpoint | `mode:live`, `missing:[]`, `readyForEntitlement:true` |
| 外国会社登記・税務 | **n/a** — domestic operator | **red** — the only remaining hard blocker |

net-kotobase trades cloud-itonami's one red for a DRAFT that its own banner says
must not be published before counsel review, plus a privacy policy and a DPA that
have to be written from nothing. Both products need counsel; only one of them has
its documents finished and published.

## club-shinshi / net-babiniku — not measured, and why

`shinshi.club` and `babiniku.net` return a byte-identical app shell for every path
tried (2,035 B and 1,918 B respectively, including `/legal/terms/` and `/pricing`).
They are client-rendered, so their legal surface cannot be established over plain
HTTP the way cloud-itonami's and net-kotobase's can. **Measuring them needs a real
browser and is not done here.**

Recording why this is a lower priority than it looks: both products' reds are
**product-safety** reds — adult content, age assurance, payout/refund conditions
for creator billing (run 0001 preflight). Documentary gates go stale because work
closes them silently; product-safety gates do not close silently. The staleness
that run 0021 found is specific to the class of gate that a commit can close.

## Decision

1. The run 0021 recommendation reversal — smallest path moves from net-kotobase to
   cloud-itonami — is **confirmed by measurement**, not merely by inference from
   cloud-itonami's side. The prior version's stated reason for choosing
   net-kotobase (avoids the AWAI foreign-company question) is factually correct and
   is outweighed.
2. `COMMERCIAL-GO-NO-GO.md`'s product-decision row for net-kotobase is updated with
   dated measured evidence. It stays **no-go**, but for materially different and now
   verified reasons.
3. club-shinshi / net-babiniku rows are marked explicitly as **not re-measured**,
   with the reason, rather than silently carrying 2026-07-24 values.
4. A qualification is added to cloud-itonami's Terms/Privacy row: it is
   **owner-approved**, which is what that gate requires, but no counsel review is
   recorded for it either. The Unblock-proof list already tracks counsel review
   separately and still shows it unmet — no gate is overstated, but the distinction
   is now visible in the row itself rather than only two sections down.

## Capital state

Unchanged. T1 ceiling JPY 300,000, committed JPY 0, spent JPY 0. This run spent
JPY 0, contacted no customer, and moved no tranche.

## Next

Unchanged from run 0021: the four owner actions in `COMMERCIAL-GO-NO-GO.md`
§ "Smallest path". This run removes one open question (whether a different product
is a shorter path — it is not) without moving any of the four.
