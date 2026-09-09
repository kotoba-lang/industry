# `hermes-magnesium-systems-bots`

Four scheduled Hermes bots turn the magnesium-hydrogen-PEMFC electric-drive concept into reviewable software, manufacturing-system designs, and sourced equipment records.

| bot | schedule (Asia/Tokyo) | responsibility |
|---|---:|---|
| `mg-kotoba-design` | `15 2 * * *` | one composable Kotoba CAD/CAE/system-library contract |
| `mg-itonami-design` | `15 3 * * *` | one cloud-itonami manufacturing actor/contract |
| `mg-equipment-schema` | `15 4 * * *` | Hyakka equipment corpus, ontology, connector, and query/readback |
| `mg-equipment-source` | `45 5 * * *` | verified first-party manufacturer and new/used dealer sources |

The authoritative requested boundary is [`system-scope.edn`](system-scope.edn). It preserves the replaceable Mg/MgH2 cartridge boundary, the six manufacturing cells plus MES, first-generation outsourcing of MgH2 synthesis and MEA manufacture, target repositories, wiki fields, source policy, and safety boundaries. The manufacturer names supplied with the concept are discovery seeds only; they become facts only after a current owner-controlled page or API is fetched and admitted.

## Shape

The installed evidence scripts only measure repository presence, revisions, tests, topic-file counts, and Hyakka schema tokens. They do not choose a design:

```text
system-scope.edn + evidence -> one bot proposal -> target-repo tests/gates -> topic branch -> PR
```

All child-repository changes happen in fresh clones. The populated checkout at `~/github/com-junkawasaki/orgs/**` is read-only. Hyakka uses its existing isolated worktree because its relative dependencies require that layout. Every bot may open at most one PR per run and may never push main, force-push, merge, purchase equipment, make a financial commitment, or operate hazardous machinery.

The wiki work is deliberately split. `mg-equipment-schema` must land the corpus and deterministic connector/readback path first. `mg-equipment-source` refuses to add sources until that path exists on `origin/main`, and accepts only manufacturer-controlled pages/APIs or a dealer's own inventory. Marketplace user listings and price aggregators are not evidence.

## Verify

```bash
python3 scripts/hermes-magnesium-systems-bots/verify_bundle.py
python3 scripts/hermes-magnesium-systems-bots/mg_kotoba_evidence.py
python3 scripts/hermes-magnesium-systems-bots/mg_itonami_evidence.py
python3 scripts/hermes-magnesium-systems-bots/mg_wiki_evidence.py
```

Like the other Hermes bot families, failures print `REFUSED` into the prompt instead of silently producing an empty report.

## Install

Copy this directory's Python and prompt files plus `system-scope.edn` to `~/.hermes/scripts/`. Create or reuse these worktrees:

```bash
git -C ~/github/com-junkawasaki worktree add --detach ~/.itonami/worktrees/magnesium-systems-bot origin/main
git -C ~/github/com-junkawasaki/orgs/network-awai/app-hyakka worktree add --detach ~/.itonami/worktrees/hyakka-growth-bot origin/main
```

Create the four cron jobs with Hermes from `~/.hermes/hermes-agent/venv/bin/hermes`, using `z-ai/glm-5.3-flash`, provider `openrouter-free`, local delivery, the appropriate evidence script and prompt, and the workdirs above. The model/provider pin is deliberate for this fleet and is expected to be rotated with the OpenRouter credential.

The dashboard is served by the installed Hermes gateway at `http://127.0.0.1:9119/cron`.
