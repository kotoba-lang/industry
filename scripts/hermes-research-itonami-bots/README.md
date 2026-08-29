# `hermes-research-itonami-bots`

Seven scheduled Hermes bots build a worldwide research-ecosystem ontology and the itonami actors that collect and analyse it.

| bot | daily JST | scope |
|---|---:|---|
| `research-world-schema` | 11:10 | Hyakka ontology, admission, query/readback |
| `research-output-source` | 12:10 | works, projects, studies, datasets, software |
| `research-events-source` | 13:10 | societies, conferences, organizers, venues |
| `research-funding-source` | 14:10 | grants, funders, sponsors, sponsorships |
| `research-impact-analysis` | 15:10 | versioned influence observations |
| `itonami-research-collector` | 16:10 | collection actor contracts |
| `itonami-research-impact` | 17:10 | auditable analysis actor contracts |

`research-scope.edn` is authoritative. Worldwide is a coverage goal, not a completeness claim. Source bots admit at most two sources per run. Every bot has an isolated worktree, opens at most one PR, and cannot merge, deploy, publish, contact parties, make financial commitments, or hand-edit Hyakka ledgers.

The ontology keeps work versions, event editions, venues, awards, sponsorship roles, corrections/retractions, and time-windowed impact observations distinct. It explicitly rejects researcher rankings and the equations citation = support, funding = endorsement, attention = impact, or correlation = causation.

Verify with `python3 scripts/hermes-research-itonami-bots/verify_bundle.py` and Python compilation. The Hermes dashboard is `http://127.0.0.1:9119/cron`.
