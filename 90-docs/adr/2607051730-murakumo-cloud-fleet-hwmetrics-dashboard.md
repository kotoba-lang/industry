# ADR-2607051730: murakumo.cloud — live CPU/GPU/mem/disk fleet dashboard

**Status**: closed
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/murakumo`, `orgs/gftdcojp/local-murakumo`

## Context

Following ADR-2607051431 (murakumo.cloud's first live model deployment), the
ask was to visualize real-time CPU/GPU/storage utilization for the gad head +
Apple M4 mesh fleet on murakumo.cloud. No hardware-metrics collection existed
at all: `murakumo.infer/probe-node` only reads memory/disk *totals* over SSH
(for shard-planning), never utilization %; `/itonami/perf` is hardcoded
benchmark data; `/infer/fleet` is an anonymized append-only stream with no
live producer.

## Decisions

**Precision and placement** — asked the user two consequential questions
before touching production machines: full-precision `powermetrics`/root vs. a
no-root approximation, and a persistent daemon on gad vs. an on-demand manual
command. Chose full precision + daemon-on-gad. Checked first and found
passwordless `sudo powermetrics` already provisioned on every Apple M4 mesh
node — no new sudoers change was needed to honor that choice.

**Collector** (`orgs/kotoba-lang/murakumo/tools/hwmetrics-report/hwmetrics-report.sh`)
reads gad's own CPU (`/proc/stat` delta)/GPU (`/sys/class/drm/card1/device/
gpu_busy_percent` + VRAM counters, unprivileged) locally, then SSHes each of
`fleet.edn`'s 10 mesh nodes for `sudo -n powermetrics --samplers cpu_power,
gpu_power` + `vm_stat`/`sysctl`/`df`. **Caught a real bug before shipping**:
serial SSH against a roster where 4/10 nodes are routinely offline cost 21s
wall-clock (4×4s ConnectTimeout alone) — parallelized all ~11 probes as
background jobs, dropping it to ~2-4s.

**Surprise finding**: every worker reported ~90-100% CPU, consistently,
across repeated samples. Suspected a `powermetrics` short-sample measurement
artifact at first (HW active residency isn't the same thing as Unix CPU-idle
%) — but `top -l` independently confirmed the same ~90-100%. The same 10
Apple M4 minis also run `xmrig` as a standing Monero-mining fleet (see the
`uriage` skill). The high CPU is genuinely real, continuous mining load, not
a bug — exactly the kind of resource contention this dashboard exists to
surface.

**API + dashboard** (`orgs/gftdcojp/local-murakumo`): `POST /infer/hwmetrics`
(Bearer `MURAKUMO_METRICS_TOKEN` gated, auth lives only in `worker.cljs` —
the pure `routes.cljc` layer has no header/env access, same split as the
`/v1/messages` bridge) overwrites a single `hwmetrics/latest` KV doc — not an
append-only stream, since the collector posts every ~10s forever and only
the current reading is ever shown. `GET /infer/hwmetrics` is public JSON;
`GET /infer/hwmetrics/ui` is a new SSR page (`local-murakumo.infer-view/
fleet-page`) with its own independent inline vanilla-JS poller (5s) — kept
separate from the existing reagent/re-frame `/infer/ui` app rather than
threading a second state slice through it. Added a JVM test (`hwmetrics`
deftest); full suite is 26 tests / 202 assertions green.

**Secret provisioning**: generated `MURAKUMO_METRICS_TOKEN`, stored in
1Password (`gftd.murakumo/METRICS_TOKEN`, vault `gftdcojp`), set as the
Cloudflare Worker secret via `wrangler secret put`, and placed in a
root-only (`0600`) `/etc/murakumo-hwmetrics.env` on gad.

**systemd on gad**: `murakumo-hwmetrics.service` (oneshot) +
`murakumo-hwmetrics.timer` (every 10s), enabled and verified running —
`journalctl` showed clean cycles (404 before the Worker deploy landed, 201
after).

Both changes landed on `main` via feature branch + GitHub API server-side
merge (`gh api .../merges`), per this repo's no-rebase/no-local-merge
workflow, from worktrees outside the superproject root.

## Consequences

- `https://api.murakumo.cloud/infer/hwmetrics/ui` shows live per-node
  CPU/GPU/mem/disk for gad + the Apple M4 mesh, refreshing every 5s, with
  offline nodes clearly badged instead of silently missing.
- Surfaces a previously invisible fact: the inference workers are
  concurrently mining Monero, so their near-saturated CPU numbers reflect
  real contention, not idle headroom.
- First hardware-utilization telemetry in either repo (prior surfaces were
  static or anonymized) — the pattern to extend if per-node tok/s
  attribution is wanted later.

## Follow-ups not done

- No historical retention/sparklines — only the single latest snapshot is
  stored; would need a bounded ring buffer for trend charts.
- `hwmetrics-report.sh`'s `WORKERS` array duplicates `fleet.edn`'s `:host`
  list by hand instead of being generated.
- No alerting on node flap/offline — this is read-only visualization, not
  monitoring.

## Addendum 2026-07-05, part 1 — GPU as the default metric

Swapped GPU above CPU in both the SSR card (`local-murakumo.infer-view/
node-card`) and the live JS poller's `renderNode` — GPU now renders first,
CPU second. GPU is the meaningful utilization signal for an inference
fleet; CPU is dominated by the concurrent `xmrig` mining load (see the
collector decision above), so leading with CPU was visually prioritizing
the less relevant number. Pure view-ordering fix, no schema/route change.

## Addendum 2026-07-05, part 2 — offline-node investigation

User asked why `dan`/`joseph`/`levi`/`simeon` show offline on the
dashboard. Direct SSH from the operator machine timed out for all four
(consistent with the dashboard's `reachable:false`); cross-checked
independently against `tailscale status` (outside the hwmetrics collector's
own SSH path entirely) — all four show `offline, last seen 1d ago` at the
Tailscale layer itself, while the other 6 mesh nodes (asher, benjamin, gad,
issachar, judah, naphtali, zebulun) show active/reachable. **Verdict**: the
dashboard is reporting correctly — these nodes are genuinely powered
off/asleep, not an SSH quirk or a dashboard bug. Reconfirms ADR-2607051431's
existing note that not all 10 `fleet.edn` nodes are kept awake at once. No
fix needed.
