# `hermes-startup-capital-bots`

Seven scheduled Hermes bots build a worldwide, provenance-preserving startup and venture-capital ontology plus a bounded cloud-itonami analysis actor.

| bot | daily JST | scope |
|---|---:|---|
| `startup-capital-ontology` | 00:25 | Hyakka ontology, admission and query/readback |
| `startup-company-source` | 01:25 | companies and time-bounded startup observations |
| `venture-fund-source` | 02:25 | VC firms, managers, GPs, fund vehicles and closes |
| `venture-lp-source` | 03:25 | explicitly disclosed institutional LP commitments |
| `venture-manager-source` | 04:25 | public professional fund-manager roles |
| `venture-round-source` | 05:25 | financing rounds, investments, portfolios and exits |
| `itonami-capital-analysis` | 06:25 | auditable cloud-itonami network analysis contracts |

`capital-scope.edn` is authoritative. Worldwide is a coverage goal, not a completeness claim. The ontology separates brands from legal entities, venture firms from fund vehicles, GPs from managers, LP commitments from NAV/ownership, announcements from completed cash flows, and observed portfolio pages from timeless holdings.

Official registries, regulators, filings and party first-hand disclosures are admissible. News and licensed commercial aggregators are discovery-only. Search snippets, scraped directories, social posts, inferred LPs/ownership and personal wealth are forbidden. Public professional roles are allowed; personal contacts, home addresses, family and sensitive-trait inference are not.

Each bot has an isolated worktree, handles at most two sources/jurisdictions per run, opens at most one PR and cannot merge, deploy, publish, contact parties, solicit, trade, allocate capital, make financial commitments or hand-edit Hyakka ledgers. Verify with `python3 scripts/hermes-startup-capital-bots/verify_bundle.py`. Dashboard: `http://127.0.0.1:9119/cron`.
