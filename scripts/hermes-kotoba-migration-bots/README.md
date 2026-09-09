# `hermes-kotoba-migration-bots` — governed JVM → Kotoba migration fleet

The versioned copy of the Hermes cron bot that advances the `kotoba-clj-to-kotoba`
migration (ADR-2608261100, ADR-2607279200) one vertical slice at a time. Hermes lives
in someone else's repository (`NousResearch/hermes-agent`) and its jobs live in a local
SQLite database, so nothing here is committed there; this directory is the reviewable
original, and installing means copying it into `~/.hermes/scripts/` and creating the
job.

| bot | schedule | does |
|---|---|---|
| `kotoba-migration-scout` | `15 5 * * *` | moves one product-semantics slice from `.clj`/`.cljc` to `.kotoba`/`.cljk`; never migrates host mechanisms |
| `kotoba-cli-build-scout` | `52 1 * * *` | takes one repo measured as `:jvm-build`, adds a reversible `kotoba compile` / JVM-free `amu compile` route, and retains the JVM oracle |
| `kotoba-cli-build-verifier` | `52 3 * * *` | independently re-runs compile, oracle, parity, suite, JVM-process, rollback, and diff checks on open build-scout PRs; never edits or merges |

These are roles, not duplicate workers. The semantic scout and build scout have
different candidate sets and different seen ledgers. The verifier consumes only PRs
whose title starts `[bot][kotoba-cli-build]`, so two bots never claim the same unit of
work. Schedules avoid the fleet's recurring `05/10/15/20/25/30/35/40/45/50` minute
bands and leave two hours between build and verification.

The detector excludes the frozen bootstrap quartet (`kotoba`, `amu`, `kototama`,
`aiueos`): removing their JVM bootstraps is an architectural tranche, not an
autonomous per-repo build PR. Build proposals may add an existing repo-native task or
an NBB `.cljs` command, but never a new shell/Python/MJS operational script.

```
kotoba_migration_evidence.py ──► one candidate ──► the bot migrates ──► verify-kotoba-migration.cljs ──► PR
   (no decisions)                                    (murakumo/whatever                (decides)
                                                        the fleet resolves)
```

## Why this one is shaped differently from `hyakka-*-bots` and `itonami-*-bots`

Both of those bots propose *into* a domain that already had an objective, mechanical
gate before the bot existed: a URL either returns 200 or it doesn't; a property either
has a refused-extraction receipt or it doesn't. The bot's whole job was picking good
candidates inside bounds someone else had already made checkable.

Clj → Kotoba migration doesn't have that pre-built oracle. "Does this compiled artifact
still do what the Clojure it replaces did" is not answerable by fetching a URL — it has
to be answered by actually running both and comparing, which is exactly what the
`kotoba-clj-to-kotoba` skill's own procedure says a human does by hand (step 6,
"parity"). So this bot's gate doesn't re-derive correctness itself; it requires the
migration to bring its own evidence — a parity test, that references the migrated
namespace, that the target repo's own test command demonstrably ran — and refuses to
grade anything it can't check that way. See `verify-kotoba-migration.cljs`'s docstring
for exactly what it checks and why each check is there (each one was verified to
actually reject a broken case before this was written up as landed — the workspace rule
against gates that never turn red).

**This means the blast radius of a bad proposal is higher than the other two bots'.** A
bad hyakka source proposal is a bad URL — caught by an HTTP fetch. A bad kotoba
migration that slips past a weak gate would be *working Clojure replaced by
subtly-wrong Kotoba*, consumed by whatever required the original namespace. That is why
this bot's gate insists on a parity test **the target repo's own suite actually ran**
(grepped for in the test command's own output, not just a green exit code — an exit 0
from a command that silently did nothing looks identical to one from a command that
passed, and the gate treats that difference as load-bearing) rather than trusting
compile success alone.

## Two roots, but not the same shape as the other two

| | |
|---|---|
| `~/github/com-junkawasaki` | **read only.** `orgs/kotoba-lang/` is fully checked out here (1,983 repos measured 2026-08-28 — unusually complete for a west sparse checkout). `candidates.cljs` scans it. Never edit inside it. |
| `~/.itonami/worktrees/kotoba-migration-bot/run-<timestamp>/<repo>` | **a fresh clone, every run, of whatever repo this run's candidate lives in.** |

The other two bots keep one persistent worktree of one fixed target repo and `git fetch
+ checkout --detach origin/main` it each run. This bot's candidates come from ~2,000
different repos, a new one picked each run — there is no single repo to keep a
persistent worktree of, so there is nothing to reuse across runs, and (this is the part
that matters) **nothing to leave dirty for the next run to trip over.**

That is not a hypothetical concern for this family. `hyakka-source-scout`'s shared
worktree sat on an interrupted run's uncommitted diff for ~11 hours, and every fire in
between failed with the same `git checkout` refusal, silently, until someone happened
to look (2026-08-28 — see the recovery PR, `network-awai/app-hyakka#5`). A fresh clone
per run cannot develop that failure mode: a run that dies partway through leaves its own
timestamped directory dirty and abandoned, and the next run gets a brand new one that
knows nothing about it. The cost is a `git clone` instead of a `git fetch` — for a
single small repo, seconds — bought once, permanently, instead of paying it back as an
outage discovered by accident. `run-*` directories are never deleted automatically; they
accumulate as inert evidence of what each run touched, and are safe to `rm -rf` by hand
once you've checked `git status` in them.

## What `candidates.cljs` actually measures

Decision-free, like the other two evidence collectors: it ranks, it doesn't judge.
Every rule about what counts as a candidate lives here, not in the bot's prompt:

- **already migrated** — a sibling `<basename>.kotoba` or `<basename>.cljk` next to the
  `.clj`/`.cljc` file. Excluded, not scored.
- **host/mechanism path** — path segments like `/native/`, `/wasm/`, `/interop/`,
  `/jvm/`, `/chicory/`, `/host/`, `/bin/`, `/cli/`, `/tools/`, `/scripts/`. A **soft**
  exclude from the ranked pool — the LLM still does the real ADR-2607279200 four-way
  classification on whatever it's handed before touching it, and can reject the
  scanner's pick.
- **score** — line count + 8× interop markers (`(:import`, `#js`, `js/`, `System/`) +
  3× `(throw` forms. Lower is better: small, few interop calls, few exceptions to
  redesign as `[:result T E]`. This orders candidates toward tractable ones; it is not
  a verdict on migratability.
- **already in flight** — `gh pr list --repo kotoba-lang/<repo>` for the top candidate;
  if an open PR's branch or title mentions the file's basename, it's skipped as
  `ALREADY PUSHED, NOT MERGED` and the next-ranked candidate is tried. If `gh` itself
  fails (rate limit, network), the candidate is still handed to the bot with an explicit
  `⚠ … UNKNOWN` line — not silently treated as clear, per the same discipline
  `itonami_evidence.py` uses for `UNKNOWN` vs `none`.
- **recently proposed** (`~/.itonami/hermes-kotoba-migration-bot/seen.edn`, 14-day TTL) — a
  candidate this bot already picked recently isn't picked again while its PR (if any)
  is still fresh, even if `gh` couldn't confirm an open PR for it.

Measured 2026-08-28 against this checkout: 6,544 `.clj`/`.cljc` files across 1,983
kotoba-lang repos, 30 already migrated, 123 excluded as host/mechanism paths, leaving
6,061 candidates. First pick: a 10-line, 1-function, zero-interop file — the ranking
works as intended.

## What `verify-kotoba-migration.cljs` actually checks

Four things, in this order, each independently verified to reject its own failure case
before this was written up as landed:

1. **compiles** — `<amu-bin> compile <kotoba-path> --target js-browser --output
   <tmp>` must exit 0. `--target wasm32-browser` is also run but is informational only
   (ADR-2608650000: an unavailable native/wasm backend is an implementation gap, not
   grounds to reject a migration that's otherwise correct).
2. **parity test is new** — `git show origin/main:<parity-test-path>` must fail. A test
   that already existed before the migration isn't evidence the migration works.
3. **parity test self-documents and references the migration** — must contain the word
   "parity" and must contain the proposal's declared `:parity-test-ns`.
4. **the target repo's own test command ran it** — `:test-command` must exit 0, produce
   non-empty output, and that output must mention `:parity-test-ns`. An exit 0 from a
   command that silently ran nothing is not a pass; the gate has no way to fully parse
   every test framework across ~2,000 repos honestly (`clojure.test`, `cljs.test`,
   `kaocha`, ad-hoc nbb runners all format differently), so where it can't tell, it
   refuses to grade it a pass rather than guess.

`0` ACCEPTED · `1` REJECTED, with the specific failing check named · `2` REFUSED, it
could not judge (missing proposal keys, missing files, missing `amu-bin`) — not a
verdict on the migration.

## Model

All three jobs are explicitly pinned to `z-ai/glm-5.3-flash` through
`openrouter-free`, with low reasoning effort. The model/provider are job metadata;
credentials remain in the Hermes/1Password secret path and never appear in these
versioned files or prompts.

## Installing

```bash
cp scripts/hermes-kotoba-migration-bots/kotoba_migration_evidence.py \
   ~/.hermes/scripts/
cp scripts/hermes-kotoba-migration-bots/kotoba-migration-scout.prompt.md \
   ~/.hermes/scripts/kotoba-migration-scout.prompt.md
cp scripts/hermes-kotoba-migration-bots/verify-kotoba-migration.cljs \
   ~/.hermes/scripts/
cp scripts/hermes-kotoba-migration-bots/kotoba_cli_build_evidence.py \
   ~/.hermes/scripts/
cp scripts/hermes-kotoba-migration-bots/kotoba-cli-build-scout.prompt.md \
   ~/.hermes/scripts/
cp scripts/hermes-kotoba-migration-bots/kotoba_cli_build_verifier_evidence.py \
   ~/.hermes/scripts/
cp scripts/hermes-kotoba-migration-bots/kotoba-cli-build-verifier.prompt.md \
   ~/.hermes/scripts/

mkdir -p ~/.itonami/worktrees/kotoba-migration-bot

H=~/.hermes/hermes-agent/venv/bin/hermes
$H cron create "15 5 * * *" "$(cat ~/.hermes/scripts/kotoba-migration-scout.prompt.md)" \
   --name kotoba-migration-scout --script kotoba_migration_evidence.py \
   --deliver local --model z-ai/glm-5.3-flash --provider openrouter-free \
   --reasoning-effort low

$H cron create "52 1 * * *" "$(cat ~/.hermes/scripts/kotoba-cli-build-scout.prompt.md)" \
   --name kotoba-cli-build-scout --script kotoba_cli_build_evidence.py \
   --deliver local --model z-ai/glm-5.3-flash --provider openrouter-free \
   --reasoning-effort low

$H cron create "52 3 * * *" "$(cat ~/.hermes/scripts/kotoba-cli-build-verifier.prompt.md)" \
   --name kotoba-cli-build-verifier --script kotoba_cli_build_verifier_evidence.py \
   --deliver local --model z-ai/glm-5.3-flash --provider openrouter-free \
   --reasoning-effort low
```

No `--workdir` is set — unlike the other two bots, there is no single fixed directory
to sync; the evidence script names `run-parent` in its own output and the prompt's step
1 clones into a fresh subdirectory of it. (This also means this job does NOT share the
`TERMINAL_CWD` writer lock the workdir-bearing jobs serialize behind — see the west
manifest/CLAUDE.md notes on that lock if you're reasoning about cron scheduling
interaction across bots.)

Once daily, not more, until a real run's duration is measured. hyakka and itonami's
schedules were set from measured run durations (0.1–12 minutes, one 65-minute outlier);
this bot compiles Kotoba and runs an arbitrary target repo's full test suite, which is a
heavier and less predictable operation than fetching a URL or reading a config file —
guessing a faster cadence before a single real run's wall-clock is known would repeat
the mistake CLAUDE.md's git-operations section warns against elsewhere in this
workspace: don't set a cadence before you've measured what it costs.

## Watching it

```bash
$H cron list
$H cron runs <job-id>
$H cron incidents
tail -f ~/.hermes/logs/gateway.log
```

To see what a run would be told this minute, without running it:

```bash
python3 ~/.hermes/scripts/kotoba_migration_evidence.py   # scans ~6,500 files; can take ~2 minutes
```

And to check the gate still discriminates, before trusting that it accepts — there is
no `--self-test` flag on this one (each check needs a real compiled artifact / real
test-command output to exercise, unlike hyakka's URL-based gate), so re-run the fixture
by hand if you touch `verify-kotoba-migration.cljs`: build a throwaway git repo with a
fake `kotoba` binary (`exit 0` / `exit 1` toggle) and a fake test-command script, and
confirm each of the four checks above rejects its own broken case and the good case
still passes all four. That is how this gate was verified before being written up here.

## What this bot is not allowed to do

Encoded in the prompt, and the parts a prompt cannot enforce are enforced by the gate:

- never edit the original `.clj`/`.cljc` file — this adds a `.kotoba`/`.cljk`
  counterpart, it does not rewrite in place;
- never touch `verify-kotoba-migration.cljs` or `candidates.cljs`;
- never claim compile or test success without having just run it;
- never fold a slice into a decision-core/predicate-table reduction by default — only
  when a named backend genuinely can't admit the value shape, and only if it says so;
- never propose a file the scanner already flagged as migrated or host/mechanism —
  disagree and stop, don't silently substitute;
- never push to `main`, never force-push, never edit someone else's PR.

**Opening no PR is a correct run.** Most runs should end at classification, at "no test
infrastructure exists to satisfy the gate", or at a gate rejection with the specific
reason printed — not at a forced migration.

## The thing to check first when a run does nothing

Read the `SCANNED` line. If `candidates` is 0 or dropped sharply from a prior run, the
scanner's excludes may be over-firing (a heuristic getting stricter than intended looks
identical to "the migration is basically done" from outside). If a run repeatedly picks
the same repo's runners-up because the top candidate is always `ALREADY PUSHED, NOT
MERGED`, that PR has probably been open a while and is worth reviewing by hand rather
than left to keep shadowing every candidate under it.
