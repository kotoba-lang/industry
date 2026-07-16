# pricing-intelligence

Real competitor SaaS pricing observed for `cloud-itonami` vertical modules, as queryable
DataScript/Datomic EDN (same base+ledger convention as `90-docs/design-quality/` and the
portfolio BMC datoms in `90-docs/adr/2607021500-portfolio-bmc-lean.datoms.edn`).

- **`pricing-intelligence.datoms.edn`** — schema (`:cluster/*`, `:vertical/*`, `:product/*`,
  `:obs/*`, `:rec/*` attributes) + the stable catalog entities (7 clusters, 28 verticals, ~80
  competitor products). Hand-editable only for schema/catalog additions — not for observations.
- **`pricing-intelligence-ledger.edn`** — append-only facts, one EDN map per line: `:obs/*`
  price observations (which competitor, what price, how it was disclosed, source URL) and
  `:rec/*` recommended price bands per vertical. Do not hand-edit existing lines; only append
  new lines from a future re-benchmark run (new `:obs/run-id` / `:rec/run-id`).
- **`query.clj`** — JVM Clojure script that loads both files into a real `datascript.core` conn,
  transacts them, and runs example queries. This is the verified, runnable proof that the EDN is
  genuinely DataScript/Datomic-transactable, not just EDN shaped to look like it. Run from outside
  the superproject tree (see the script's own header comment for why — the superproject's root
  `deps.edn` is unrelated workspace-metadata EDN, not a Clojure deps manifest, and confuses
  `clojure.tools.deps` if invoked from inside the tree):

  ```sh
  cd /tmp && clojure -Sdeps '{:deps {datascript/datascript {:mvn/version "1.7.1"}}}' \
    -M /path/to/com-junkawasaki/90-docs/pricing-intelligence/query.clj /path/to/com-junkawasaki
  ```

## Scope

30 of the 295 ISIC+ISCO industry/occupation verticals `cloud-itonami` has blueprinted (see
`orgs/cloud-itonami/cloud-itonami-isic-*` / `cloud-itonami-isco-*`) — 28 from a pilot batch chosen
for spread across regulatory intensity, buyer size, and pricing-model diversity, plus two targeted
follow-ups added 2026-07-16: `isic-6399` (meta job-search / Indeed replacement) and `isic-7810`
(employment placement agency). Both are named in
`90-docs/business/cloud-itonami-vertical-maturity.md`'s "prefer product → business over new
verticals (6399 / 6310 (+7810))" priority; `isic-6399`'s pricing table was marked "illustrative"
pending real market anchors, and `isic-7810`'s had no pricing numbers at all yet. The other 301
`cloud-itonami-*` repos (iso3166 country/regulator, municipality, trade association, COFOG, GTIN,
UNSPSC) remain excluded: they target governments/institutions/classification codes, not verticals
with a natural "competitor SaaS" to benchmark against.

## Why this exists

Answers a concrete question: does cloud-itonami's designed revenue model (per-seat + per-agent-run
SaaS + vertical module subscription) have real market anchors to price against, and could that
become "dynamic, competitor-matched" pricing? Finding: no live price-matching engine exists
anywhere in these 30 markets — every real competitor prices on a static list, a meter tied to the
buyer's own usage, or a negotiated contract. ~41% of the ~100 competitor products surveyed publish
real pricing, ~40% are estimate-only (vendor hides the number, a third party guessed), ~19%
disclose nothing at all. What's realistically buildable is a periodic (e.g. quarterly)
re-benchmark loop — a new `:obs/run-id` appended to the ledger each cycle — feeding governor-gated
proposals into the existing Lean Loop canvas, not a live auto-reprice algorithm.

For `isic-6399`: the 6 real comparables surveyed (Madgex, JobBoard.io, JBoard, WP Job Manager,
Adicio/CareerCast, engage/en-japan) *validate* business-model.md's existing illustrative
¥50k-150k/月 managed-board band rather than overturning it — see `:rec/vertical
:vertical/isic-6399` in the ledger (`run-id "pricing-intel-20260716-02"`) for the full rationale.

For `isic-7810`: the 4 real comparables surveyed (Crelate, JobAdder, Zoho Recruit Staffing edition,
Bullhorn) all price per-recruiter-seat; converting a typical 3-5-staff agency's real spend on those
tools lands in the same ¥50k-150k/月 range independently found for isic-6399/6310, so this was
proposed as `isic-7810`'s first pricing band (not a validation of an existing one) — see
`:rec/vertical :vertical/isic-7810` (`run-id "pricing-intel-20260716-03"`).

## Re-running / extending

To re-benchmark: research the same (or updated) competitor set per vertical, then append new
`:obs/*` / `:rec/*` lines to the ledger with a fresh `run-id` and today's date — never edit or
remove existing lines. To add a vertical: add a `:vertical/*` catalog entry (and any new
`:product/*` entries) to the base file, then append its `:obs/*`/`:rec/*` facts to the ledger.
