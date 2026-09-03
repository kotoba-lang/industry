# `hermes-geopol-power-bots`

Two scheduled Hermes bots grow wiki.kotobase.net (network-awai/app-hyakka) with
layered-epistemics records about relations among states, governments, and
companies — sourced facts, labeled secondary reports, and explicitly-labeled
model analysis/prediction, all connected in the datom plane (IPLD for bytes).

| bot | schedule (Asia/Tokyo) | responsibility |
|---|---:|---|
| `gp-relation-schema` / `gp-relation-analysis` | `15 * * * *` (hourly) | layered claim schema (fact → inference → prediction joins), then ≤2 model claims per run |
| `gp-relation-source` | `45 * * * *` (hourly, after schema run) | primary official sources AND labeled `:secondary-reported` records, ≤2 per run |

## Layered epistemics (the core discipline)

Every claim carries `:claim/layer` as a required indexed datom:

| layer | admitted from | carries |
|---|---|---|
| `:observed-fact` | primary official sources, fetched in-run | source URL, fetch timestamp, content hash / IPLD CID receipt |
| `:secondary-reported` | news / analyst / third-party database, fetched in-run | `:reported-claim/publisher`; records that the publisher REPORTED a claim; never upgrades |
| `:model-inference` | derived by the bot's model from stored claims | `:inference/basis` (1-ref datom collection) + `:inference/model` |
| `:model-prediction` | forward-looking model output | basis + model + `:prediction/as-of` + `:prediction/horizon` |

Layer upgrade is forbidden — no connector path rewrites `:claim/layer`
upward. When reality arrives, a prediction is verified by a NEW `:observed-fact`
datom, and a reader joins prediction → outcome through basis edges.

## Storage topology (ADR-2607311100 D2/D4)

Claims live in the datom plane (1-ref, joinable via Datomic Client API
`/api/*`); fetched source bytes live in the IPLD archive plane
(content-addressed blocks, CID-referenced only, never joined; full-text
publication is per source-license opt-in). Analysis claims join to their basis
through the same 1-ref edges as facts, so fact → inference → prediction is a
plain two-hop datom query.

## Source policy

Layered, not binary. Primary: gazettes, procurement/tender portals, regulator
filings, first-party IR/press releases, court records, official agreement
texts. Secondary (recorded AS secondary): news reporting, analyst/rating-agency
reports, third-party structured databases. Forbidden outright: search-snippet-only
"sources", generated summaries as sources, people-search brokers, unofficial
social posts. An allegation or charge never implies guilt; people appear only
as role-holders named by an official record.

Per run: ≤2 sources across ≤2 entities, ≤2 model claims, ≤1 PR.

## Evidence and isolation

Same pattern as `hermes-global-legal-bots` / `hermes-magnesium-systems-bots`:
the evidence script (`geopol_power_evidence.py` via wrapper `gp_wiki_evidence.py`)
refuses to run without the scope file, injects its SHA-256 as `scope_sha256`,
and measures only tracked text and Hyakka schema tokens (including the layered
vocabulary `claim/layer`, `model-inference`, `secondary-reported`,
`inference/basis`). It never chooses a source, schema design, or analysis
content. Bots work in the dedicated Hyakka bot worktree
(`~/.gftd/worktrees/hyakka-growth-bot`), never the shared `orgs/**` checkout;
one topic branch and at most one PR per run; never push main, force-push,
merge, deploy, contact anyone, or make financial commitments.

## Verify

```bash
python3 scripts/hermes-geopol-power-bots/verify_bundle.py
PYTHONPYCACHEPREFIX=/tmp/geopol-power-pyc python3 -m py_compile scripts/hermes-geopol-power-bots/*.py
python3 scripts/hermes-geopol-power-bots/gp_wiki_evidence.py
```

## Install

Copy this directory's Python and prompt files plus `geopol-power-scope.edn`
to the hyakka profile's `scripts/`. Reuse the Hyakka worktree, then create the
two jobs with `z-ai/glm-5.3-flash`, provider `openrouter`, reasoning `low`,
local delivery, workdir `~/.gftd/worktrees/hyakka-growth-bot`, and the
corresponding evidence wrapper and prompt. The dashboard is
`http://127.0.0.1:9119/cron`.

## `gp-review` — the merge gatekeeper

A separate Hermes profile (`gp-review`, job `gp-pr-review`, hourly `35 * * * *`)
reviews open `gp-schema-*` / `bot/gp-*` PRs against the five-point checklist
(`pr-review.prompt.md` / `SOUL.md`): layer discipline (runs
`verify_epistemics.cljs` itself), source labels, gates (new test failures must
reproduce on pristine `origin/main` to be acceptable), blast radius, scope
honesty. It squash-merges what passes with a review comment listing exactly
what it verified, and posts refusal reasons otherwise. It is the only family
actor allowed to merge. Evidence script: `gp_review_evidence.py` (open-PR
inventory, decision-free).
