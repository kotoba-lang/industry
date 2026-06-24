# Living System — Top-Level Orchestrator

`com-junkawasaki` is the **highest-priority repository** in the kawasakijun stack.
This module reads KPIs from sensors, advances the Pregel state, and **emits issues
or requests to downstream repositories**. The actual execution happens in each
downstream repo's own agent (Claude Code / Codex / custom).

## Boundary

```
                    ┌───────────────────────────────────┐
                    │   com-junkawasaki (this repo)      │
                    │   = orchestrator / planner / top   │
                    │   (reads sensors, emits requests)  │
                    └───────────────────────────────────┘
                                    │
        ┌─────────────────┬─────────┼──────────────┬────────────────┐
        ↓                 ↓         ↓              ↓                ↓
  spirit-in-physics  etzhayyim/root  gftdcojp     260208-spirit   ...
    (physics agent)  (organism agent) (vendor agent) (story agent)
```

**This repo NEVER touches downstream code directly.** It only:
1. Reads sensors (Gmail / Calendar / git / Drive / animeka / MoneyForward)
2. Updates Pregel state (`reverse_topo_pregel.py` 互換)
3. Emits actions:
   - `gh issue create` → downstream repo's agent picks it up
   - `gcal event create` → personal calendar
   - `gmail draft` → personal inbox
   - Magatama actor dispatch (future)

## Files

| File | Purpose |
|---|---|
| `manifest.yaml` | top-level actor identity (DID, super_step_interval) |
| `repos.yaml` | downstream repo registry: node → repo routing |
| `collectors/` | read-only KPI sensors |
| `actions.py` | action emission (write-only) |
| `dispatcher.py` | decides which actions to emit based on state |
| `run.py` | entry point (cron-friendly, `--dry-run` default) |

## Run

```bash
# Dry run (recommended for first invocation)
python kawasakijun/living/run.py --dry-run

# Production (actually creates issues / events / drafts)
python kawasakijun/living/run.py --execute
```

## Cron suggestion

```cron
# Daily Pregel super-step at 06:00 JST
0 6 * * * cd /Users/junkawasaki/github/com-junkawasaki && \
  kawasakijun/.venv/bin/python kawasakijun/living/run.py --execute >> /tmp/kawasakijun.log 2>&1
```

## Downstream agent contract

Each downstream repo MUST have an agent (Claude Code, GitHub Actions bot, or
similar) that:

1. Watches for new issues labeled `kawasakijun-orchestrated`
2. Implements the requested change (PR + tests)
3. Closes the issue when done
4. Reports `completion: true` in a structured comment

The orchestrator (this repo) reads issue state via `gh api` and updates
Pregel `completion` accordingly.

## Safety

- **Default mode is `--dry-run`** (prints what would happen, makes no API calls)
- **All issues are tagged `kawasakijun-orchestrated`** for easy revert
- **Idempotent**: re-running creates no duplicate issues (checks existing)
- **Rate limited**: max 5 issues / repo / day
