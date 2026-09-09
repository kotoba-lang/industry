# `hermes-hyakka-coverage-bots` — two scheduled bots that raise measured coverage

The versioned copy of the Hermes cron bots that widen two of hyakka's connectors
(`nvd-cve` vulnerability data, `overpass-osm` addresses) one small, verified step at
a time. Hermes lives in someone else's repository (`NousResearch/hermes-agent`) and
its jobs live in a local SQLite database, so nothing here is committed there; this
directory is the reviewable original.

| bot | schedule | raises |
|---|---|---|
| `vuln-coverage-scout` | `30 4 * * *` | `nvd-cve`'s `:max-cves` — the corpus's own documented lever |
| `osm-coverage-scout` | `30 16 * * *` | one new small `:overpass-osm` bbox per run |

```
hyakka_coverage_evidence.py ──► current state ──► the bot proposes ──► verify-coverage-proposal.cljs ──► PR
   (no decisions)                                    (murakumo/whatever)         (decides, by actually running it)
```

## Why the gate re-runs the connector instead of reading the diff

Unlike hyakka's other two gates (a URL either 200s or it doesn't; a property either
has a refused-extraction receipt or it doesn't), a config number or a bbox can look
completely reasonable and still fetch nothing or fail admission — the only way to
know a `:max-cves` bump or a new bbox actually produces admitted data is to run the
connector at the proposed value and read the result. So the gate copies the real
config, applies exactly the proposed change, and runs
`resident_ingest.cljs --only <id> --no-llm` against a throwaway output directory.
`entities= 0` or a `WARN <id> ...` line is a rejection regardless of what the diff
looks like.

## Two roots, isolated from the other hyakka bot families

| | |
|---|---|
| `~/github/com-junkawasaki` | **read only.** |
| `~/.itonami/worktrees/hyakka-coverage-bot` | this family's own worktree — NOT shared with `hyakka-source-scout`/`hyakka-ontology-scout`'s `hyakka-growth-bot` worktree, and NOT shared with `itonami-*-bots`' worktree either. |

Sharing a worktree across bot families is exactly the failure mode that blocked
`hyakka-source-scout` for ~11 hours (2026-08-28): an interrupted run's uncommitted
diff sat in the shared worktree and every subsequent fire failed the same
`git checkout` refusal until someone found it by hand. A dedicated worktree per
family means an interrupted run in one family cannot block another.

## What each bot's one lever is, and what it deliberately is not

**`vuln-coverage-scout`** only ever raises `:max-cves` on the `nvd-cve` source.
`hyakka.corpus.vuln/coverage`'s own `:missing` list names two real, larger gaps —
historical window backfill, and KEV/EPSS exploitation signals — but both are new
connector code, not a config knob, and are out of scope for this bot. Someone
building those should be a `kotoba-clj-to-kotoba`-style slice of new collector
code with its own gate, not squeezed into this one's proposal shape.

**`osm-coverage-scout`** only ever adds a new bbox, never enlarges an existing one.
`collect-overpass!`'s own docstring says why: a bbox that's small enough to review
by eye stays that way as coverage grows by count of bboxes, not by area of any one
of them.

## Installing

```bash
cp scripts/hermes-hyakka-coverage-bots/hyakka_coverage_evidence.py ~/.hermes/scripts/
cp scripts/hermes-hyakka-coverage-bots/vuln-coverage-scout.prompt.md ~/.hermes/scripts/
cp scripts/hermes-hyakka-coverage-bots/osm-coverage-scout.prompt.md ~/.hermes/scripts/

git -C orgs/network-awai/app-hyakka worktree add --detach \
  ~/.itonami/worktrees/hyakka-coverage-bot origin/main

H=~/.hermes/hermes-agent/venv/bin/hermes
W=~/.itonami/worktrees/hyakka-coverage-bot
$H cron create "30 4 * * *" "$(cat ~/.hermes/scripts/vuln-coverage-scout.prompt.md)" \
   --name vuln-coverage-scout --script hyakka_coverage_evidence.py \
   --deliver local
$H cron create "30 16 * * *" "$(cat ~/.hermes/scripts/osm-coverage-scout.prompt.md)" \
   --name osm-coverage-scout --script hyakka_coverage_evidence.py \
   --deliver local
```

No `--workdir` — the evidence script itself syncs the bot's own worktree, the same
pattern `itonami-*-bots` uses, so this job doesn't take the `TERMINAL_CWD` writer
lock that workdir-bearing jobs serialize behind.

Schedules are twelve hours apart and once daily each, matching the conservative
"measure a real run before raising the cadence" approach used for every bot in this
family so far — `coverage_growth_evidence.cljs` alone is fast (reads local files),
but the gate's own live verification run (a real NVD fetch or a real Overpass
query) has a cost neither bot should be firing faster than daily until that cost is
measured.

## What these bots are not allowed to do

- `vuln-coverage-scout`: never propose anything but `:raise-max-cves` on `nvd-cve`;
  never jump more than +50; never lower or repeat the current value.
- `osm-coverage-scout`: never enlarge an existing bbox; never exceed the area
  ceiling; never duplicate an already-configured `:id`.
- Both: never edit the gate or the evidence collector; never push to `main`, never
  force-push, never edit someone else's PR.

**Opening no PR is a correct run** if the gate rejects every value/area tried in
that run — report the rejection reason and stop, per every other bot in this
workspace's fleet.

## Watching them

```bash
$H cron list
$H cron runs <job-id>
$H cron incidents
```

To see what a run would be told this minute, without running it:

```bash
python3 ~/.hermes/scripts/hyakka_coverage_evidence.py
```

Gate correctness was verified against a real `origin/main` checkout before this
was written up as landed: 6 cases (a good raise, a no-op raise, an oversized step,
a good new bbox, an oversized bbox, a duplicate id) — each rejects for its own
stated reason, and both "good" cases actually fetched real data (163 CVE-derived
claims at `:max-cves 8`; 65 claims for a Shinjuku-area bbox) rather than just
returning exit 0.
