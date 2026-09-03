# `hermes-geopol-power-bots`

Two scheduled Hermes bots grow wiki.kotobase.net (network-awai/app-hyakka) with
source-mandatory, interpretation-free facts about relations among states,
governments, and companies.

| bot | schedule (Asia/Tokyo) | responsibility |
|---|---:|---|
| `geopol-relation-schema` | `20 6 * * *` | relation corpus, schema, connector, and query/readback |
| `geopol-relation-source` | `20 7 * * *` | primary official sources: procurement awards, appointments, ownership filings, sanctions listings, partnerships, treaties |

## Boundary

`geopol-power-scope.edn` is the authoritative boundary. The graph records
**observable events with sources** — it never records power rankings,
influence scores, allegiance/ideology inference, predictions, or narrative
analysis of power dynamics. Readers do the analysis; the wiki stores the
sourced facts. An allegation or charge never implies guilt; people appear only
as role-holders named by an official record.

Sources are primary official only: gazettes, procurement/tender portals,
regulator filings, first-party company IR/press releases, court records,
official agreement texts. News reporting is inadmissible as fact. Each run
adds at most two non-duplicate sources across at most two entities.

## Evidence and isolation

Same pattern as `hermes-global-legal-bots` / `hermes-magnesium-systems-bots`:
the evidence script (`geopol_power_evidence.py` via wrapper `gp_wiki_evidence.py`)
refuses to run without the scope file, injects its SHA-256 as `scope_sha256`,
and measures only tracked text and Hyakka schema tokens. It never chooses a
source or schema design. Bots work in the dedicated Hyakka bot worktree
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
to `~/.hermes/scripts/`. Reuse the Hyakka worktree, then create the two jobs
with `z-ai/glm-5.3-flash`, provider `openrouter-free`, reasoning `low`, local
delivery, and the corresponding evidence wrapper and prompt. The dashboard is
`http://127.0.0.1:9119/cron`.
