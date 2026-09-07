# `hermes-clojure-stdlib-migration-bots` — governed clojure.* → kotoba-lang.* rewire fleet

The versioned copy of the Hermes cron bot that continues
`adr-2809061500-clojure-namespace-to-kotoba-stdlib` — rewiring `(:require [clojure.<ns>
...])` call sites across `orgs/kotoba-lang/` to their first-party `kotoba-lang.*`
replacement, one repo × one namespace at a time. Hermes lives in someone else's
repository (`NousResearch/hermes-agent`) and its jobs live in a local SQLite database,
so nothing here is committed there; this directory is the reviewable original,
installing means copying it into `~/.hermes/scripts/` and creating the job.

| bot | schedule | does |
|---|---|---|
| `clojure-stdlib-migration-scout` | `27 6 * * *` | rewires one repo's usage of one `clojure.*` namespace (string/test/edn/set/walk/pprint/java.shell) to its `kotoba-lang.*` replacement; never touches `clojure.java.io` |

## This is NOT `hermes-kotoba-migration-bots` — read that distinction before touching either

| | `hermes-kotoba-migration-bots` | `hermes-clojure-stdlib-migration-bots` (this one) |
|---|---|---|
| ADR | 2607279200 / 2608261100 | 2809061500 |
| moves | product semantics, `.clj`/`.cljc` → **new** `.kotoba`/`.cljk` file | an *existing* `.clj`/`.cljc` file's `(:require ...)`, one external namespace → its `kotoba-lang.*` replacement |
| file count change | adds a file | edits a file (and `deps.edn`) in place |
| gate's core question | does the compiled artifact do what the Clojure it replaces did | is the old namespace actually gone, the new one actually present, actually declared as a real dependency, and does the repo's own suite still pass |
| seen-ledger | `~/.gftd/hermes-kotoba-migration-bot/seen.edn` | `~/.gftd/hermes-clojure-stdlib-migration-bot/seen.edn` |

Same shape (decision-free scanner ranks → LLM classifies and does the work → an
independent adversarial gate decides → PR), deliberately copied from that family
because the shape is right. **Different program.** Do not merge the two bots' scripts,
seen-ledgers, or PR title prefixes — that is precisely how two bots end up claiming the
same unit of work.

```
clojure_stdlib_migration_evidence.py ──► one (repo,ns) ──► the bot rewires ──► verify-clojure-stdlib-migration.cljs ──► PR
   (no decisions)                          candidate           (re-verifies the target          (decides)
                                                                 library's CURRENT tip first)
```

## Scope — what `candidates.cljs` will and will never hand out

Seven namespaces, from `adr-2809061500-clojure-namespace-to-kotoba-stdlib`:

| `clojure.*` | → | `kotoba-lang.*` (repo) |
|---|---|---|
| `clojure.string` | → | `kotoba.string` (`kotoba-lang/string`) |
| `clojure.test` | → | `kotoba.lang.test` (`kotoba-lang/test`) |
| `clojure.edn` | → | `kotoba.lang.edn` (`kotoba-lang/edn`) |
| `clojure.set` | → | `kotoba.lang.coll` (`kotoba-lang/coll`) |
| `clojure.walk` | → | `kotoba.lang.coll` (`kotoba-lang/coll`) |
| `clojure.pprint` | → | `kotoba.lang.fmt` (`kotoba-lang/fmt`) |
| `clojure.java.shell` | → | `kotoba.lang.process` (`kotoba-lang/process`) |

**`clojure.java.io` is EXCLUDED, structurally — not filtered out after scanning, never
in the scanned set at all.** Its migration is only a PROPOSED design
(`90-docs/adr/2809070100-clojure-java-io-capability-boundary-proposal.edn`, status
`"proposed"`, not accepted) — real `clojure.java.io` is ambient-OS free functions,
while `kotoba-lang/fs` + `kotoba-lang/io` are deliberately capability-injected, which
means every one of its 3,320 call sites needs restructuring, not an import swap. That
ADR names its own rollout order (loud-failure scripts/tests first, the
concurrency-sensitive subprocess-piping and the production Ed25519 signing-key file in
`cloud-itonami-app` last) and explicitly says accepting the design proposal is not
authorization to start the implementation. Until an owner accepts that ADR, this bot
must not attempt this namespace on its own — both the scanner (never scans for it) and
the gate (hard-rejects it if a proposal ever names it anyway) enforce this
independently, so one of the two failing open does not open the door.

**`clojure.data.json` is also not in scope** — not because it's hard, but because
`adr-2809061500`'s own `:adr/consequences` log records it as **already complete
workspace-wide** before this bot's wave started (a parallel effort had already retired
every `clojure.data.json` / jsonista / cheshire coordinate). There is nothing left for
this bot to do there.

**Also excluded, structurally, every namespace, every run:**

- the frozen meta-repos `kotoba`, `kotoba-lang`, `amu`, `kototama`, `aiueos` — a rewire
  inside the bootstrap itself is an architectural tranche, not an autonomous per-repo
  swap, same reasoning `hermes-kotoba-migration-bots` gives for excluding the same four;
- any repo whose `deps.edn` contains `:local/root` — a fresh clone (which is exactly
  what this bot's run-parent worktree is, every run) cannot resolve a sibling path that
  only exists inside this west checkout;
- a fixed short list of `(repo, namespace)` pairs the ADR's own log already names as
  checked-and-not-viable, not merely unattempted (see `candidates.cljs`'s docstring —
  currently `sahai`/`clojure.walk` (needs `keywordize-keys`, which `kotoba-lang/coll`
  genuinely does not implement), `fleet`/`clojure.pprint` (stale fossil superseded by
  the maintained `sahai`), `inference`/`clojure.pprint` (deliberately depends on
  cljs-pprint's own quirky output as a test oracle — rewiring it breaks the thing it
  tests)). **Re-check the ADR's `:adr/consequences` field before trusting this list is
  still current — it is dated 2026-09-07 and the program is ongoing.**

**Unlike `hermes-kotoba-migration-bots`'s scanner, this one does NOT exclude `test/`
paths.** `clojure.test` lives almost entirely in test files; excluding them would make
that namespace structurally unpickable. This is the one deliberate divergence from the
sibling scanner's shape.

## The thing that has already bitten this exact program once — stale local checkout

Measured this session, building this bot: the local checkout under `orgs/kotoba-lang/`
of `kotoba-lang/test`, `kotoba-lang/coll`, and `kotoba-lang/process` were **all several
commits behind their own GitHub tips** — `test` was missing `deftest`/`is`/`testing`/
`are`/`run-tests` entirely (only the pre-existing property-testing layer was present
locally), `coll` was missing `subset?`/`superset?`/`select`/`project`/`join`/
`rename`/`index` and the unbounded `walk`/`prewalk`/`postwalk` family, and `process` was
missing a real `exec` spawn transport. A scanner (or a scout) that trusted the local
checkout instead of re-reading the target repo's actual current tip would have proposed
rewires against an API that didn't exist yet in what the candidate's own west pin
resolves to. `candidates.cljs`'s docstring records this so it isn't forgotten, and
`clojure-stdlib-migration-scout.prompt.md` makes re-checking the target's live GitHub
tip step 3 of the procedure, before a single line is written — **not** a "trust the
table, verify only if something looks off" step.

## What `verify-clojure-stdlib-migration.cljs` actually checks — and its honest current strength

Nine checks (full detail in the script's own docstring), each independently exercised
against a throwaway fixture repo before this was written up as landed — a good-case
proposal that passes all nine, and ten separate broken-case variants (old namespace
left behind, new namespace never added, dependency never declared, dependency declared
as `:local/root`, test count regressed, test command exits non-zero, declared test
count disagreeing with the command's own output, `clojure.java.io` attempted, a
meta-repo targeted, and a file that was never actually changed), confirmed one at a
time to produce exit 1 with the specific check named, and the good case re-confirmed
still exits 0 afterward.

`0` ACCEPTED · `1` REJECTED with the specific failing check named · `2` REFUSED, could
not judge (missing proposal keys, missing repo-root) — not a verdict on the rewire.

**Where this gate is weaker than its sibling, said plainly rather than oversold:**

- The sibling gate's step 4 (suite passes) greps the test command's own output for the
  literal declared parity-test namespace string — a strong, specific, hard-to-fake
  signal that a *particular new test* ran. This gate's step 9 only has a **short,
  best-effort list of regex patterns** for common test-runner output shapes (`Ran N
  tests`, `N tests, N assertions`, `Tests: N passed`) to extract a count and compare it
  against the proposal's own declared `:tests-after`. **If a repo's test runner's
  output doesn't match any of those patterns, the gate prints a WARNING and does NOT
  fail the run on that basis alone** — the declared `:tests-before`/`:tests-after`
  counts become self-reported and not independently verified for that run. This is a
  real, known gap, not a hidden one: across ~2,000 repos with `clojure.test`, `cljs.test`
  /shadow-cljs, `kaocha`, babashka test runners, and ad-hoc nbb runners all formatting
  differently, a single honest regex list cannot cover them all, and this gate would
  rather warn than guess wrong in either direction. **A future iteration should widen
  the pattern list as real runs surface real formats, and/or add a
  framework-fingerprinting step** (read `deps.edn`/`bb.edn` to know which test runner is
  in play before trying to parse its output) rather than one universal regex list.
- The one hard, unconditional backstop against that gap: **`:tests-after` may never be
  declared lower than `:tests-before`**, checked as pure proposal self-consistency,
  independent of whether the count could be confirmed from output at all. This does not
  catch a rewire that quietly deletes a test AND inflates both numbers to match, but it
  does catch the much more common accidental case (a rewire that breaks or removes a
  test and the count silently drops).
- The gate does not attempt to independently re-derive what "the repo's own test
  command" should be — it trusts the proposal's `:test-command` verbatim, same as the
  sibling gate does. A wrong or too-narrow test command (one that doesn't actually
  exercise the rewired file) would pass this gate. The prompt tells the scout to find
  the repo's *actual* test entrypoint (`deps.edn` `:test` alias, `bb.edn`,
  `package.json`), but nothing here independently verifies that claim.

## Model

Pinned to `z-ai/glm-5.3-flash` through `openrouter-free`, low reasoning effort — same
provider/model as `hermes-kotoba-migration-bots`' three jobs, for the same reason
(credentials live in the Hermes/1Password secret path, never in these versioned files).

## Installing

```bash
cp scripts/hermes-clojure-stdlib-migration-bots/clojure_stdlib_migration_evidence.py \
   ~/.hermes/scripts/
cp scripts/hermes-clojure-stdlib-migration-bots/clojure-stdlib-migration-scout.prompt.md \
   ~/.hermes/scripts/clojure-stdlib-migration-scout.prompt.md
cp scripts/hermes-clojure-stdlib-migration-bots/verify-clojure-stdlib-migration.cljs \
   ~/.hermes/scripts/

# candidates.cljs itself is NOT copied anywhere -- clojure_stdlib_migration_evidence.py
# invokes it straight out of this superproject checkout (READ_ROOT), the same way
# kotoba_migration_evidence.py invokes its own candidates.cljs. This is the reviewable
# original AND the one that actually runs; editing a copy elsewhere would not do
# anything.

mkdir -p ~/.gftd/worktrees/clojure-stdlib-migration-bot

H=~/.hermes/hermes-agent/venv/bin/hermes
$H cron create "27 6 * * *" "$(cat ~/.hermes/scripts/clojure-stdlib-migration-scout.prompt.md)" \
   --name clojure-stdlib-migration-scout --script clojure_stdlib_migration_evidence.py \
   --deliver local --model z-ai/glm-5.3-flash --provider openrouter-free \
   --reasoning-effort low
```

No `--workdir` is set, for the same reason as the sibling family: candidates come from
many different repos, chosen fresh each run, so there is no single fixed directory to
sync — the evidence script names `run-parent` in its own output and the prompt's step 1
clones into a fresh subdirectory of it.

**Profile: the default profile (no `HERMES_HOME` override).** This is where
`kotoba-migration-scout`, `kotoba-cli-build-scout`, and `kotoba-cli-build-verifier` are
already registered (confirmed live via `hermes cron list` before choosing) — same owner,
same kind of operation (reads this same superproject checkout, clones into
`~/.gftd/worktrees/...`, needs the same `openrouter-free` credential already configured
there), so there is no reason to create a new profile and split state that belongs
together. A new profile would also hit the documented `config.yaml` gotcha (a freshly
created profile's `providers:`/`secrets:` blocks are empty, so its first agent-mode job
always fails with `Unknown provider 'openrouter-free'` until someone copies those blocks
in by hand) for zero benefit.

**Schedule: once daily, `27 6 * * *`.** Chosen to avoid two things at once: the fleet's
recurring `05/10/15/20/25/30/35/40/45/50` minute bands (this bot fires at minute 27),
and the sibling family's own currently-registered times (`kotoba-migration-scout` fires
every 6 hours at minute 4 starting hour 2; `kotoba-cli-build-scout`/`-verifier` fire
every 4 hours near minutes 52/47 starting hours 1/3 — this bot's 6:27 falls in none of
those hour/minute combinations). Once daily, not more, because — same reasoning as the
sibling family's README — this bot clones a repo, potentially opens a second PR against
a *different* kotoba-lang repo if it finds an API gap, and runs an arbitrary target
repo's full test suite: a heavier and less predictable operation than a URL fetch, and
no real run's wall-clock has been measured yet to justify a faster cadence.

## Watching it

```bash
$H cron list
$H cron runs <job-id>
$H cron incidents
tail -f ~/.hermes/logs/gateway.log
```

To see what a run would be told this minute, without running it:

```bash
python3 ~/.hermes/scripts/clojure_stdlib_migration_evidence.py   # scans ~13,700 files across ~2,000 repos
```

To re-verify the gate still discriminates after touching it: rebuild the fixture repo
described in this README's own development history (a throwaway git repo with a
baseline commit on a branch literally named `origin/main`, a working-tree rewire on
top, a `deps.edn` with the target coordinate, and a fake test-command script), and
confirm the good case exits 0 and each of the ten broken variants above exits 1 with
the specific check named. There is no `--self-test` flag — each check needs real file
content, a real git ref, and real `deps.edn` EDN to exercise, the same reason the
sibling family's gate has none either.

## What this bot is not allowed to do

Encoded in the prompt, and the parts a prompt cannot enforce are enforced by the gate
or the scanner:

- never attempt `clojure.java.io` — enforced twice, independently (scanner never scans
  for it; gate hard-rejects the namespace even if a proposal names it anyway);
- never touch the frozen meta-repos (`kotoba`, `kotoba-lang`, `amu`, `kototama`,
  `aiueos`) — enforced twice, independently, same shape;
- never touch this superproject's own `manifest/*` files — the whole procedure lives
  inside one cloned `orgs/kotoba-lang/<repo>` checkout;
- never edit `verify-clojure-stdlib-migration.cljs` or `candidates.cljs`;
- never claim compile or test success without having just run it;
- never push to `main`, never force-push, never edit someone else's PR;
- never propose a `(repo, namespace)` pair the scanner already flagged excluded —
  disagree and stop, don't silently substitute.

**Opening no PR is a correct run.** Most runs should end at "the target library's
current tip doesn't cover this call site yet", "no test infrastructure exists", or a
gate rejection with the specific reason printed — not at a forced rewire.

## The thing to check first when a run does nothing

Read the `SCANNED` line. If the repo×namespace candidate count is 0 or dropped sharply
from a prior run, either the program has genuinely run out of low-hanging call sites
(check the ADR's own `:adr/consequences` log — it may simply mean the remaining sites
are all `:local/root`-excluded, meta-repo, or on the deliberately-skipped list, which is
a correct and expected end state, not a bug) or an exclude heuristic is over-firing. If
a run repeatedly reports the same repo's runners-up because the top candidate is always
`ALREADY PUSHED, NOT MERGED`, that PR has been open a while and is worth reviewing by
hand.
