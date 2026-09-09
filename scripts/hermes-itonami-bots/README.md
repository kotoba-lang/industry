# `hermes-itonami-bots` — two scheduled bots that raise the cloud-itonami fleet

The versioned copy of the Hermes cron bots that work on the two numbers the
fleet is judged by: **maturity** (how good the repos that exist are) and
**coverage** (which classes have no repo at all). Hermes lives in someone else's
repository (`NousResearch/hermes-agent`) and its jobs live in a local SQLite
database, so nothing here is committed there; this directory is the reviewable
original, and installing means copying it into `~/.hermes/scripts/` and creating
the jobs.

| bot | schedule | raises |
|---|---|---|
| `itonami-ingest-scout` | `40 11 * * *` | **maturity** — `axis-ingest` on one repo, by giving it a regulatory source register whose every citation was fetched in that run |
| `itonami-coverage-scout` | `40 23 * * *` | **coverage** — one or two entries in `90-docs/coverage/itonami-coverage.datoms.edn`, naming the authority a service in an uncovered class would answer to |

Same shape as `hermes-hyakka-bots`, for the same reason (ADR-2608271450):

```
itonami_evidence.py ──► measurements ──► the bot proposes ──► itonami-verify-proposal.cljs ──► PR
   (no decisions)                          (murakumo)                  (decides)
```

## Why maturity and coverage are one arrangement and not two

They are the same question asked at two scales, and raising either one blind
makes the other worse. A fleet that only raises maturity gets very good at the
industries it already picked; a fleet that only raises coverage accumulates
repos that cite nothing. So both bots read one report, and the report carries
both signals with the same discipline: a list the bot may only choose **from**.

## The three things this adds that the existing loop did not have

**1. The tick cannot see unlanded work, and now the report can.**
`scripts/itonami-maturity-improve-tick.cljs` ranks by leverage from the west pin
and the maturity datoms. Neither can see a branch that was pushed and never
merged. Measured 2026-08-27: the tick named `cloud-itonami-iso3166-jpn-meti` and
its `axis-ingest`, and the register it was asking for had been written the day
before, pushed as `agent/maturity-meti-ingest`, and left for nineteen hours. A
worker who trusted the tick would have written a second copy of the same 29
sources. So the collector asks GitHub, per candidate, and prints
`ALREADY PUSHED, NOT MERGED` — because *not done* and *done and not landed* are
different jobs.

It prints `UNKNOWN` when GitHub was not asked. An empty list and an unanswered
question are the same shape otherwise, and only one of them is *none*.

**2. Coverage was never written down.** Asked "which industries and governments
are not covered", the answer had to be computed by hand from the UN mirrors and
`west.yml`. It is now computed every run, and the part that is *not* derivable —
which authority a service in that class must answer to — is what the coverage
bot contributes and what the gate fetches.

**3. The gate keeps the in-repo suites honest.** For `:sources` the real
verification lives in the target repo: `verify-facts.cljs` re-fetches every
entry and `mutation-check.cljs` breaks `facts.edn` fourteen ways, each break
required to be caught **by its own named reason**. The cheap way past that is to
weaken one of them in the same commit. Byte-comparing against a sibling would be
wrong — host declarations legitimately differ per ministry, so a correct
adaptation and a sabotage look alike — so the gate demands evidence of
discrimination instead: **≥12 mutations with `not-caught=0`, and ≥5 passing
verifier self-tests.** Weakening the verifier makes mutations stop being caught;
weakening the suite drops it below the floor.

## Two roots, on purpose

| | |
|---|---|
| `~/github/com-junkawasaki` | **read only.** The only checkout where `orgs/` is populated, so the ISIC and COFOG mirrors live here. Other sessions work in it, and CLAUDE.md forbids writing to it. |
| `~/.itonami/worktrees/itonami-growth-bot` | the bot may branch and commit here. Re-synced to `origin/main`, detached, every run. |

Child-repo work happens in a **clone**, not in `orgs/<org>/<repo>` — that path is
a shared working tree, and another session switching branches in it silently
reverts uncommitted edits (ADR-2607011345).

The worktree is outside the superproject deliberately. Inside it, west walks up,
finds the real `.west/`, and operates on the shared `orgs/` while appearing
isolated — `WEST_TOPDIR` does not fix it.

## The three exit codes, in both tools

```
itonami-growth-evidence.cljs   0 a report        ·        · 2 could not measure
itonami-verify-proposal.cljs   0 accepted        · 1 rejected · 2 could not judge
```

`2` is reached with `process.exit`, not by setting `exitCode` and throwing —
under nbb that reports **1**, which is the code this family uses for *measured,
and found something*. A refusal that reports 1 is indistinguishable from a
finding (ADR-2608271450 decision 3).

**On these hosts, 1 and 2 are not a formality.** The METI-family sites answer
`403` for a path that does not exist *and* when they decline to talk to an
automated client, and `www.meti.go.jp` serves an AWS WAF challenge as a **202 at
the requested URL**. A gate that reads any of those as *the register is wrong*
sends whoever reads it to edit a register that was correct. So `403`, `429`,
`5xx` and a challenge body are all refusals here; `404` and `410` stay
rejections, because there the host answered clearly.

This was found by running the gate, not by reading it: the first version called a
403 from `www.jpo.go.jp` a rejection, minutes after tripping that host's WAF with
its own traffic.

## The evidence shim exits 0 even when it refuses

Hermes injects a cron `--script`'s stdout into the prompt. An empty report
reaches the model as *there is nothing to raise* — the one answer that must
never be produced by failure, because this fleet's whole measurement problem is
that unmeasured and clean look alike. So `itonami_evidence.py` prints a REFUSED
banner and still exits 0: the bot must **run and be told it is blind**, not be
silently skipped.

## Installing

```bash
cp scripts/hermes-itonami-bots/itonami_evidence.py       ~/.hermes/scripts/
cp scripts/hermes-itonami-bots/ingest-scout.prompt.md    ~/.hermes/scripts/itonami-ingest-scout.prompt.md
cp scripts/hermes-itonami-bots/coverage-scout.prompt.md  ~/.hermes/scripts/itonami-coverage-scout.prompt.md

git -C ~/github/com-junkawasaki worktree add --detach \
  ~/.itonami/worktrees/itonami-growth-bot origin/main

H=~/.hermes/hermes-agent/venv/bin/hermes
W=~/.itonami/worktrees/itonami-growth-bot
$H cron create "40 11 * * *" "$(cat ~/.hermes/scripts/itonami-ingest-scout.prompt.md)" \
   --name itonami-ingest-scout   --script itonami_evidence.py --workdir "$W" \
   --model murakumo-main --provider custom --deliver local
$H cron create "40 23 * * *" "$(cat ~/.hermes/scripts/itonami-coverage-scout.prompt.md)" \
   --name itonami-coverage-scout --script itonami_evidence.py --workdir "$W" \
   --model murakumo-main --provider custom --deliver local
```

The gateway is already installed by the hyakka bots (`$H gateway install`);
without it `cron list` shows jobs and nothing fires.

**`90-docs/coverage/` is outside the superproject's sparse cone**, so it is
invisible to `ls`, `find` and `grep` until you ask for it — in the bot worktree
as well as in your own checkout:

```bash
git sparse-checkout add 90-docs/coverage
```

Same hazard as `70-tools/bmc`, and the same fix. A coverage bot that cannot see
the register it appends to would read the file as absent, which is the failure
this whole arrangement is shaped around.

As with the hyakka prompts, this directory is the reviewable original and the
installed copies can drift — re-copy after editing.

**These two are not on the free-model rotation.** `refresh_free_model.py`
resolves its targets from `HYAKKA_BOT_NAMES`, which names only the two hyakka
bots; these run on `murakumo-main`, the fleet alias (ADR-2607173100), which needs
no key. Adding them is one env var on the refresh job, and it is a decision about
what these bots are worth running on, not a default.

The schedules are twelve hours apart. Hermes serialises cron jobs that declare a
`workdir` behind one lock and these two share theirs; they are also clear of the
hyakka jobs (09:20 / 21:20 / 08:40), which hold a different lock but the same CPU.

## Watching them

```bash
$H cron list
$H cron runs <job-id>
$H cron incidents
tail -f ~/.hermes/logs/gateway.log
```

To see what a bot would be told this minute, without running it:

```bash
python3 ~/.hermes/scripts/itonami_evidence.py           # ~4 min: it runs the tick
```

And to check the gate still discriminates, before trusting that it accepts:

```bash
nbb --classpath ".:scripts/nbb_compat" scripts/itonami-verify-proposal.cljs --self-test
```

## What these bots are not allowed to do

Encoded in the prompts, and the parts a prompt cannot enforce are enforced by
the gate:

- never cite a URL not fetched in that run;
- never propose a class that is not on the measured gap list — the gate reads
  the UN mirrors and `west.yml` itself and ignores what a proposal claims;
- never weaken `verify-facts.cljs` or `mutation-check.cljs`, and never edit the
  gate;
- never write to `~/github/com-junkawasaki/orgs/**`, never edit `west.yml`;
- never create a repository — the coverage bot records where the gaps are;
  building the actor is `/itonami-os-connect`, which a human triggers;
- never push to `main`, never force-push.

**Opening no PR is a correct run.** The coverage bot in particular should do
nothing on days when no authority answers, and a refusal is not a rejection.

## The thing to check first when a bot does nothing

Read the `datoms-age-days` and `stale?` line at the top of the report. When the
maturity datoms are stale, the ranking is against measurements older than the
last landing — the tick will be asking for a re-measure, and the ingest bot's
candidate list is describing a fleet that has already moved. That is not a
failure, but a proposal built on it can duplicate work that landed since. The
re-measure is a human iteration (`/itonami-maturity-improve`, §5); these bots
deliberately do not run it, because a bot that re-measures 1,900 repos on a
schedule would spend every night doing the one thing that produces no change.
