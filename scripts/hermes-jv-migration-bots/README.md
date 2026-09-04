# hermes-jv-migration-bots

The change signal for `jv-migration-scan-watch`.

## What the runner asks of a monitor

`hermes_cli/cron.py` prints "(agent runs only on output change)" next to a
job's monitor and tracks `monitor_state.last_changed_at`. A monitor decides
nothing: it prints something deterministic, the runner diffs that against the
previous run's output, and the agent wakes only when they differ. Identical
output skips the turn entirely.

So this monitor is a stable digest of what `jv-migration-weekly` regenerates,
`90-docs/kotoba-stdlib-router/scan.edn`. No timestamp — anything that changes
every run wakes the agent every run, which is the same as having no monitor.

## Why the logic is here and the launcher is not

The runner executes `.sh` monitors via bash and **everything else via Python**.
A `.cljs` monitor cannot be invoked directly, so the profile carries a thin
`.py` launcher that calls `nbb` on this file. The job named
`jv-migration-scan-monitor.cljs` directly, which could never have run — and
the file did not exist either.

Measured 2026-09-04: four of the fleet's seven monitor-driven jobs had no
working monitor. Three of them (the canvas-watch trio) share a launcher that
delegates to `~/.gftd/canvas-signal.cljs`, which does not exist and has no
trace in any repo — it lived outside version control and vanished, taking
three monitors with it. **None failed loudly.** The job runs, the monitor
never signals, and the prompt's own instruction for the no-change case is to
answer `[SILENT]`. Three jobs watching nothing, quietly. That is why this one
is version-controlled.

## Refusal

A missing `scan.edn` prints `REFUSED` and exits 2 — neither the "unchanged"
output that would skip the turn nor a fake digest that would wake the agent
for the wrong reason. An absent input is not a measurement of no change.

## Verified

Both directions, 2026-09-04: appending one line to `scan.edn` changes the
output; restoring it returns the original digest; two consecutive runs on an
untouched file are byte-identical.
