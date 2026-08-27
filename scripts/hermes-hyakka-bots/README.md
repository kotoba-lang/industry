# `hermes-hyakka-bots` — two scheduled bots that grow wiki.kotobase.net

The versioned copy of the Hermes cron bots that widen
[`network-awai/app-hyakka`](https://github.com/network-awai/app-hyakka), the
sourced claim graph served at `wiki.kotobase.net`. Hermes lives in someone
else's repository (`NousResearch/hermes-agent`) and its jobs live in a local
SQLite database, so nothing here is committed there; this directory is the
reviewable original, and installing means copying it into `~/.hermes/scripts/`
and creating the jobs.

| bot | schedule | grows |
|---|---|---|
| `hyakka-source-scout` | `20 9 * * *` | **articles** — new ingest sources, each of which becomes archived bytes, then sourced claims, then items |
| `hyakka-ontology-scout` | `20 21 * * *` | **ontology** — new properties, but only ones the corpus already tried to state and could not |

Both run on `murakumo-main` through `https://api.murakumo.cloud/v1`, the public
alias, not a pinned model id (ADR-2607173100) and not the direct inference host
(ADR-2608251016).

## The shape, and why it is this shape

A model asked *what is this wiki missing?* will answer. It will name sources
that read like real URLs and properties that read like real gaps, and both
failure modes are fluent, and neither is visible in a diff. So the model never
gets asked that question with nothing in front of it, and it never gets the
last word:

```
hyakka_evidence.py ──► measurements ──► the bot proposes ──► verify_source_proposal.cljs ──► PR
   (no decisions)                        (murakumo)              (decides)
```

- **`hyakka_evidence.py`** syncs the bot's worktree to `origin/main` and runs
  `scripts/wiki_growth_evidence.cljs` there. Hermes runs cron `--script` files
  as bash or Python and nothing else, so this is Python for the same reason
  `plugins/dashboard_auth/did` is (ADR-2608197300 §5): the file holds no
  decision, so the language costs nothing.

  Its exit code is load-bearing in one direction. Hermes injects the script's
  stdout into the prompt, so an empty report reaches the model as *nothing is
  missing* — the one answer that must never be produced by failure. On any
  refusal it prints a REFUSED banner and still exits 0, because the bot has to
  **run and be told it is blind**, not be silently skipped.

- **`wiki_growth_evidence.cljs`** and **`verify_source_proposal.cljs`** live in
  app-hyakka. Neither re-implements admission: corpus policy comes from
  `hyakka.corpus.registry`, class admission from `hyakka.ingest`, and the set
  of valid connector kinds is parsed out of `collect!` rather than copied, so a
  kind added there and not here fails loudly instead of a valid proposal being
  rejected for naming a kind that exists.

The gate's two rules:

- a proposed **source** must fetch — status, bytes, content-type, measured at
  verification time — and its source classes must be admissible under the
  corpus policy it names;
- a proposed **property** must be corroborated by receipts already on disk:
  some extraction, against some archived source, must actually have tried to
  assert it and been refused. The tally is recomputed by the gate and the
  number the proposal claims is ignored.

The second is the load-bearing one. It means the ontology can only grow where
the corpus already pushed against its own edges, and a property nobody has ever
tried to state cannot be added by asking for it.

`0` accepted · `1` rejected, with the reason under each item · `2` **REFUSED**,
it could not judge. 2 is reached with `process.exit`, because setting
`exitCode` and throwing reports 1 — the code this family uses for *measured,
and found something*.

## Installing

```bash
cp scripts/hermes-hyakka-bots/hyakka_evidence.py    ~/.hermes/scripts/
cp scripts/hermes-hyakka-bots/*.prompt.md           ~/.hermes/scripts/

git -C orgs/network-awai/app-hyakka worktree add --detach \
  ~/.gftd/worktrees/hyakka-growth-bot origin/main
( cd ~/.gftd/worktrees/hyakka-growth-bot && npm install )

H=~/.hermes/hermes-agent/venv/bin/hermes
W=~/.gftd/worktrees/hyakka-growth-bot
$H cron create "20 9 * * *"  "$(cat ~/.hermes/scripts/source-scout.prompt.md)" \
   --name hyakka-source-scout   --script hyakka_evidence.py --workdir "$W" \
   --model murakumo-main --provider custom --deliver local
$H cron create "20 21 * * *" "$(cat ~/.hermes/scripts/ontology-scout.prompt.md)" \
   --name hyakka-ontology-scout --script hyakka_evidence.py --workdir "$W" \
   --model murakumo-main --provider custom --deliver local
$H gateway install     # without this, `cron list` shows the jobs and nothing fires
```

The worktree must sit under `~/.gftd/worktrees/`, not in `/tmp`: app-hyakka's
`deps.edn` carries the source path `../../kotoba-lang/kotobase-client/src`, and
`~/.gftd/kotoba-lang` is what makes that resolve. In a flat `/tmp` worktree the
test build fails with `The required namespace "kotobase.client" is not
available` — which is exactly the failure a caller reads as *the tests ran*.

The two schedules are twelve hours apart deliberately. Hermes serialises cron
jobs that declare a `workdir` behind one lock, and they share theirs.

## What these bots are not allowed to do

Encoded in the prompts, and the parts of it a prompt cannot enforce are
enforced by the gate:

- never propose a URL not fetched in that run;
- never `:third-party-wiki-prose` or `:user-generated` — every corpus here
  forbids them, because prose about a fact is not the fact;
- never touch `knowledge/ledger/` or `knowledge/receipts/`, which are the
  record of what was observed, not workspace;
- never push to `main`, never force-push, never edit the gate to make a
  proposal pass.

**Opening no PR is a correct run.** The ontology bot in particular is expected
to do nothing on most days: it acts only when the signal is non-empty, and the
signal only fills when extraction hits the edge of the vocabulary.

## Watching them

```bash
$H cron list                       # both jobs, next fire times
$H cron runs <job-id>              # durable execution attempts
$H cron incidents                  # failures that need acknowledging
tail -f ~/.hermes/logs/gateway.log
```

To see what a bot would be told this minute, without running it:

```bash
python3 ~/.hermes/scripts/hyakka_evidence.py
```

## The thing to check first when a bot does nothing

Look at the `models-seen` line and the `rejections by class` table in that
output. `extraction-failed` counts mean the LLM path is not reaching the fleet,
and when that happens the deterministic connectors keep succeeding, every tick
still exits 0, and the wiki still grows — so *extraction is broken* and *the
wiki is growing* look exactly alike from outside. That is how
`infer.murakumo.cloud` returning 401 for a pinned, no-longer-served model went
unnoticed; it is the failure this whole arrangement is shaped around.
