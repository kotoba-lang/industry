# `hermes-realestate-bots`

Six scheduled Hermes bots build a worldwide, provenance-preserving ontology of
real property, land rights, recorded transactions, valuations, encumbrances and
real-estate investment vehicles into
[`network-awai/app-hyakka`](https://github.com/network-awai/app-hyakka) — the
sourced claim graph served at `wiki.kotobase.net` — plus one bounded
cloud-itonami analysis actor.

| bot | daily JST | scope |
|---|---:|---|
| `realestate-ontology` | 00:35 | the Hyakka ontology slice, admission and query/readback |
| `realestate-registry-source` | 01:35 | land registries, cadastres, registrars, title and tenure |
| `realestate-transaction-source` | 02:35 | recorded transactions, official valuations, price indices |
| `realestate-investment-source` | 03:35 | REITs, funds, managers and disclosed holdings |
| `realestate-procedure-source` | 04:35 | purchase procedure, taxes, restrictions, permissions |
| `itonami-realestate-analysis` | 05:35 | auditable cloud-itonami observation contracts |

`realestate-scope.edn` is authoritative. It is versioned here and read from the
workspace when present, so the bundle a run was judged against is the one in
git, not a drifted copy under `~/.hermes/scripts/`.

## The shape, and why it is this shape

A model asked *what is this wiki missing about property?* will answer. It will
name registries that read like real registries and price series that read like
real price series, and both failure modes are fluent. So the model is never
asked with nothing in front of it, and it never gets the last word:

```
realestate_evidence.py ──► measurements ──► the bot proposes ──► app-hyakka's gate ──► PR
    (no decisions)                                                  (decides)
```

`realestate_evidence.py` holds no judgement. It syncs each bot's dedicated
worktree to `origin/main`, reads the tracked text there, and reports whether
that bot's vocabulary tokens are present. Its exit code is load-bearing in one
direction: Hermes injects stdout into the prompt, so an empty report reaches
the model as *nothing is missing* — the one answer that must never be produced
by failure. Every refusal prints a REFUSED banner and still exits 0, because a
bot has to **run and be told it is blind** rather than be silently skipped. It
also prints `files_measured=`, because a scan that read nothing and a scan that
read files saying nothing otherwise produce the same empty token table.

The source bots run **after** the ontology bot for the same reason the tokens
exist: until the slice lands, `present=no` is the honest answer, and rule 1 of
every source prompt turns that into a stop.

## What this corpus is not allowed to conflate

The epistemic boundaries in the scope are the substance of the thing. Property
data is unusually good at producing a number that is true of *something* and
then being read as a number about *the property in front of you*:

- a **listing asking price** is not a transaction price;
- a **recorded transaction price** is not current market value;
- an **official or assessed valuation** is a tax figure, not a market price;
- a **price index** is not a property-level price and cannot be applied to one;
- an **automated valuation** is not a verified valuation;
- **registered title** is not beneficial ownership;
- a **mortgage record** is not current debt outstanding;
- a **portfolio disclosure** is an observed claim at its reporting date;
- a **zoning designation** is not permission to build, and a planning
  permission is not a completed building;
- **areas** differ by measurement standard and **amounts** are nominal at their
  own date — neither is comparable without a stated basis;
- **missing is unmeasured**, and worldwide is a coverage goal, not a
  completeness claim.

## The privacy boundary, and why it is stricter than the sources

Land registries in several jurisdictions publish the names of individual
owners. That a source publishes something does not make it admissible here:
this plane releases claims under CC0, irrevocably, and a CC0 claim naming a
natural person as the owner of a dwelling cannot be withdrawn.

So the corpus holds **legal entities and public professional roles only** — no
natural-person owner identification, no personal residential-address linkage,
no occupancy or household data, no mortgage-borrower identity, no personal
wealth, and no neighbourhood desirability ranking. A property address is a
property of the property; a person attached to it is not.

## What the bots cannot do

Each bot has an isolated worktree, takes at most two sources and two
jurisdictions per run, opens at most one PR, and cannot merge, deploy, publish,
contact parties, solicit, bid, offer, trade, allocate capital, make financial
commitments or hand-edit Hyakka ledgers. None of them gives investment advice
or values a property.

**Opening no PR is a correct run.**

## Verifying

```bash
python3 scripts/hermes-realestate-bots/verify_bundle.py     # 0 pass · 1 the bundle drifted
python3 ~/.hermes/scripts/realestate_ontology_evidence.py   # what a bot would be told, now
```

`verify_bundle.py` checks that every boundary sentence above is still in the
prompt that carries it, that the scope still names its own boundaries, and that
every wrapper selects a scope the shared module actually defines — a wrapper
naming a scope that does not exist would refuse at argparse time, nightly, and
read as an ordinary refusal. It was landed only after being made to fail: a
dropped privacy boundary, a softened price-index sentence and a mistyped scope
each produced exit 1 naming exactly the thing broken.

## Installing

```bash
cp scripts/hermes-realestate-bots/realestate_evidence.py       ~/.hermes/scripts/
cp scripts/hermes-realestate-bots/*_evidence.py                ~/.hermes/scripts/
cp scripts/hermes-realestate-bots/*.prompt.md                  ~/.hermes/scripts/
cp scripts/hermes-realestate-bots/realestate-scope.edn         ~/.hermes/scripts/

for b in realestate-ontology realestate-registry-source realestate-transaction-source \
         realestate-investment-source realestate-procedure-source; do
  git -C orgs/network-awai/app-hyakka worktree add --detach ~/.itonami/worktrees/$b-bot origin/main
  ( cd ~/.itonami/worktrees/$b-bot && npm install )
done
git worktree add --detach ~/.itonami/worktrees/itonami-realestate-analysis-bot origin/main
```

The hyakka worktrees must sit under `~/.itonami/worktrees/`, not in `/tmp`:
app-hyakka's `deps.edn` carries the source path
`../../kotoba-lang/kotobase-client/src`, and `~/.itonami/kotoba-lang` is what makes
that resolve. In a flat `/tmp` worktree the build fails with *the required
namespace "kotobase.client" is not available* — which is exactly the failure a
caller reads as *the tests ran*.

Then create the jobs (`hermes cron create … --model murakumo-main --provider
custom --deliver local`, one per row of the table above) and let
`hyakka-model-refresh` point them at the resolved free model — it covers every
agent-driven job by default, so these were picked up without being named.

Dashboard: `http://127.0.0.1:9119/cron`.
