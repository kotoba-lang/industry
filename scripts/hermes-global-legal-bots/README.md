# `hermes-global-legal-bots`

Five scheduled Hermes bots expand Hyakka's worldwide public legal knowledge in bounded, reviewable increments.

| bot | schedule (Asia/Tokyo) | responsibility |
|---|---:|---|
| `legal-world-schema` | `10 6 * * *` | corpus, ontology, source admission, and query/readback |
| `legal-legislation-source` | `10 7 * * *` | official bills, laws, regulations, amendments, and gazettes |
| `legal-cases-source` | `10 8 * * *` | official courts, dockets, opinions, orders, judgments, and appeals |
| `legal-profession-source` | `10 9 * * *` | official lawyer registers and judicial rosters/appointments |
| `legal-news-source` | `10 10 * * *` | publisher-owned legal news and official releases |

`world-legal-scope.edn` is the authoritative boundary. “Worldwide” is a measured coverage goal, never a claim that every jurisdiction, language, court, or professional has been collected. Each source run adds at most two sources across at most two jurisdictions.

## Evidence and isolation

Each bot has its own detached Hyakka worktree under `~/.itonami/worktrees/`. The evidence wrapper refuses a dirty worktree, fetches `origin`, synchronizes to `origin/main`, measures only tracked source/config/test text (excluding generated catalog data), and injects the scope hash and missing/present tokens. It never chooses a source or schema design.

The shared `~/github/com-junkawasaki/orgs/**` checkout is read-only. Bots create topic branches, run focused tests, and may open one PR. They may not push main, force-push, merge, deploy, publish, hand-edit knowledge ledgers, contact people, or give legal advice.

## Source and privacy boundary

- Legislation: official legislature, official gazette, or official legislation registry.
- Cases: official judiciary, court, docket, or prosecutor source. Allegation and charge never imply guilt.
- Professionals: official bar/regulator registers and official judicial rosters/appointment records only. No home contact details, private biography, inferred ideology, or personal scoring.
- News: publisher-owned pages/feeds and official releases. The graph records that a publisher reported a claim; it does not convert reporting into a judicial holding. Full copyrighted articles are not republished.

Unavailable, blocked, ambiguous, or unlicensed sources are refused. Search snippets, generated summaries, people-search brokers, third-party wiki prose, and control bypasses are forbidden.

## Verify

```bash
python3 scripts/hermes-global-legal-bots/verify_bundle.py
PYTHONPYCACHEPREFIX=/tmp/global-legal-pyc python3 -m py_compile scripts/hermes-global-legal-bots/*.py
```

## Install

Copy this directory's Python, prompt, and EDN files to `~/.hermes/scripts/`. Create five detached Hyakka worktrees named by `global_legal_evidence.py`, then create the five jobs with Hermes using `z-ai/glm-5.3-flash`, provider `openrouter-free`, reasoning `low`, local delivery, and the corresponding evidence script/worktree.

The dashboard is `http://127.0.0.1:9119/cron`.
