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

Both run on a **free** OpenRouter model, chosen by measurement each week and
installed by a third job. The fleet is the last entry in the fallback chain, not
the primary (ADR-2608272100). Neither a model id nor a provider is written into
the jobs by hand — `hermes cron edit` is driven by the resolver.

| bot | schedule | grows |
|---|---|---|
| `hyakka-model-refresh` | `40 8 * * *` | nothing — it keeps the other two on a model that still exists |

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

## The model, and why nothing here names one

`stealth/ox-alpha` was the most-used model on OpenRouter and served zero
endpoints four days later. Writing a free model id into these jobs would be
ADR-2608271450's pinned-`qwen3.6-35b-a3b` bug one tier down, and the free tier
moves faster than the fleet ever did.

So `resolve_free_model.cljs` asks OpenRouter which models are free right now,
filters on a floor stated in `free-model-policy.edn` (context, tool support),
and then **probes** the survivors rather than ranking them. Every proxy for
capability lies: sorting the free tier by context descending on 2026-08-27 put
a 2.6B model above a 550B one.

The probe is the three things a hyakka bot actually does — emit a tool call
with exact arguments, obey an exact-output instruction, write parseable EDN.
Fail the first and the other two are not spent.

```
                    ┌ 3/3 → primary
models API ─ floor ─┤ 3/3 → fallback_providers[…]     ┐ each a different
(no key)            └ else → named in the receipt     ┘ provider
                                                        then the fleet, last
```

**`busy` is not `cannot`.** Most of the free tier answers 429 on any given
afternoon — nine of fifteen in one run here. A rate limit, a 403, a 5xx, and an
upstream error delivered *inside a 200* are all recorded as unavailable and
score nothing; only a model that answered gets a number. That distinction was
wrong three times while this was written, and each time the receipt read as
though the free tier had been measured and found wanting.

`resolve_free_model_test.cljs` pins it, and requires the real namespace rather
than copying the classifier — the first version copied it, and stayed green
when the classifier was broken.

```bash
nbb --classpath scripts/hermes-hyakka-bots \
    scripts/hermes-hyakka-bots/resolve_free_model_test.cljs   # 17 cases
nbb scripts/hermes-hyakka-bots/resolve_free_model.cljs --list        # candidates, no key
nbb scripts/hermes-hyakka-bots/resolve_free_model.cljs --check-config
nbb scripts/hermes-hyakka-bots/resolve_free_model.cljs --if-stale --write --jobs a,b
```

## Whose name is on the bill

OpenRouter identifies the calling app from two request headers — `HTTP-Referer`
(the link) and `X-OpenRouter-Title` (the display name; `X-Title` is documented
as also accepted) — and shows that identity on its app leaderboard and in the
account's activity.

Hermes fills both in **with its own name** (`_OR_HEADERS_BASE` in
`agent/auxiliary_client.py`: `https://hermes-agent.nousresearch.com` /
`Hermes Agent`). So there is no neutral default here: unset, every call this
account pays for is credited upstream. `:attribution` in the policy names the
app instead, and the resolver renders it into two places, because they reach
different clients:

| written to | reaches |
|---|---|
| `providers.<n>.extra_headers` | the main turn — matched by `base_url`, applied last, survives a credential swap |
| `model.extra_headers` | auxiliary calls (context compression, session titles), which build their own client and merge only this one |

All three header names are written, including the legacy `X-Title`, because
that is the one Hermes's default occupies — setting only the new name would
leave `X-Title: Hermes Agent` on the wire beside it, and which of two
conflicting titles OpenRouter believes is not a thing to assume. Resolved
against Hermes's own code on 2026-08-31, the wire carries
`HTTP-Referer: https://itonami.cloud` and both title headers reading
`Itonami By KotobaLabs`. `X-OpenRouter-Categories` is left as Hermes sets it;
it is a marketplace category, not an identity.

The generated Hermes configuration enables OpenRouter response caching with a
300-second TTL. This is separate from provider prompt caching: only byte-for-byte
identical successful requests are replayed, while changing bot/tool context gets
a different cache key. Hermes disables response caching when retrying an empty
completion, so an unusable cached response is not replayed indefinitely.

`0` installed or already current · `1` nothing free passed, the fleet stays
primary · `2` **REFUSED**, it could not find out. 1 and 2 must not collapse:
*no free model works* is a measurement, *I could not look* is not.

**`--if-stale` reconciles even when nothing is stale.** *The model is still
free* and *the bots are on it* are different questions, and for one day only
the first was asked: the 08:40 run on 2026-08-28 reported `current`, exited 0,
and left two jobs pointing at the fleet, because the jobs were only ever
written on the install path. Pointing them costs no request and no probe, so
the current path does it too and prints a line per job.

### Two things that will bite

**The key never becomes a file.** It lives in the login Keychain under
`gftd.openrouter` and reaches Hermes through `secrets.command`, the same shape
`claude-zai` uses for `gftd.zai`. `providers.<n>.key_cmd` is the obvious fit
and does not work: on hermes v0.20.5 the main turn succeeds and every
auxiliary task then dies with `'CommandTokenSource' object has no attribute
'strip'`. Context compression is an auxiliary task.

**An OpenRouter key with no `auxiliary` block opens a billed lane.** Hermes's
default auxiliary fallback is `google/gemini-3.6-flash`, which is paid, and it
engages for background traffic as soon as a key is present. `free_only: true`
is the ceiling, and the resolver writes it.

## Installing

```bash
cp scripts/hermes-hyakka-bots/hyakka_evidence.py    ~/.hermes/scripts/
cp scripts/hermes-hyakka-bots/*.prompt.md           ~/.hermes/scripts/

git -C orgs/network-awai/app-hyakka worktree add --detach \
  ~/.itonami/worktrees/hyakka-growth-bot origin/main
( cd ~/.itonami/worktrees/hyakka-growth-bot && npm install )

H=~/.hermes/hermes-agent/venv/bin/hermes
W=~/.itonami/worktrees/hyakka-growth-bot
$H cron create "20 9 * * *"  "$(cat ~/.hermes/scripts/source-scout.prompt.md)" \
   --name hyakka-source-scout   --script hyakka_evidence.py --workdir "$W" \
   --model murakumo-main --provider custom --deliver local
$H cron create "20 21 * * *" "$(cat ~/.hermes/scripts/ontology-scout.prompt.md)" \
   --name hyakka-ontology-scout --script hyakka_evidence.py --workdir "$W" \
   --model murakumo-main --provider custom --deliver local
$H cron create "40 8 * * *" \
   --name hyakka-model-refresh --script refresh_free_model.py --no-agent --deliver local
$H gateway install     # without this, `cron list` shows the jobs and nothing fires
```

The refresh job is `--no-agent` on purpose: the script *is* the job. The case it
exists for is the one where the configured model has stopped answering, and a
job that needed a model to fix the model would be dead exactly then.

It points **every agent-driven cron job** at the model it resolved — not a list
of names, and not job ids. Ids change when a job is recreated; a name list is
worse, because it does not fail when a bot it has never heard of appears.

Measured 2026-08-28, the day after the switch: the table had grown from three
jobs to five. Another session had added `itonami-ingest-scout` and
`itonami-coverage-scout`, built to this same pattern against the cloud-itonami
fleet, and both were still on `murakumo-main` — one of them spent 57 minutes
and 1,857,045 input tokens there overnight. Nothing was broken and nothing
reported anything; the refresh simply had a two-name horizon.

So coverage is the default. A deliberate pin is expressed by opting out:

```bash
HYAKKA_MODEL_OPT_OUT=some-bot,another-bot   # names, comma-separated
```

Jobs that run **without** an agent carry no model and are skipped — this job is
one of them. Every run prints a `COVERS` block naming what it targeted and what
it skipped, because a refresh that quietly narrowed its own scope reads exactly
like one with nothing left to do.

⚠ The directory is still called `hermes-hyakka-bots`. It is no longer only
about hyakka.

`resolve_free_model.cljs` and `free-model-policy.edn` are copied to
`~/.hermes/scripts/` alongside the shim — the resolver finds its policy beside
itself, so the pair travels together. As with the prompts, this directory is
the reviewable original and the installed copies can drift; re-copy after
editing.

The worktree must sit under `~/.itonami/worktrees/`, not in `/tmp`: app-hyakka's
`deps.edn` carries the source path `../../kotoba-lang/kotobase-client/src`, and
`~/.itonami/kotoba-lang` is what makes that resolve. In a flat `/tmp` worktree the
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
