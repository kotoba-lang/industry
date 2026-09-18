# `hermes-akc-admin-ops` — the admin.kotoba.cloud operator-console ops bot

The versioned copy of the Hermes profile `akc-admin-ops` (owner direction
2026-09-18: 「admin.kotoba.cloud 担当の hermes profile bots も立てて」), the bot
that keeps the kotoba.cloud operator console in the state
app-kotoba-cloud's ADR-2609181500 describes: **dark outside its IP
allowlist, gated by name inside, named by nothing public.** The live
profile is terminal-local (`~/.hermes/profiles/akc-admin-ops/`); this
directory is the reviewable original, and the cron definitions are in
`scripts/hermes-cron-jobs/hermes-cron-jobs.json`.

| job | schedule | mode | what it does |
|---|---|---|---|
| `admin-gate-watch` (`db3a3cf7a52f`) | every 6 h | `--monitor-script admin_ops_monitor.py` | the STABLE evidence (gate per family, the apex's dark shape, the console's own refusals, the leak scan, the authn route, the operator registry). Unchanged bytes → the agent does not run. A change wakes it with the diff: it says what changed and what it means; a repo-side finding is one PR |
| `admin-access-review` (`a12d6d44f7bf`) | Mon 05:32 JST | `--script admin_ops_evidence.py` | the full report: the above plus this host's egress addresses, the secrets' NAMES on both Workers (`wrangler secret list`), the worktree vs origin/main, the gate's source / test / ADR present. One status line per check, the operator handles, then END |

```
admin_ops_evidence.py ──► measurements (JSON) ──► the bot reads SOUL.md and reports ──► at most ONE PR
   (decides nothing)        --monitor = the stable subset                              (never a secret)
```

## Why this bot exists, and what it can and cannot see

The console is dark outside `ADMIN_ALLOWED_IPS` — every path and method
answers a plain 404 before the session is read. So there is no outside
view of it to monitor: the only place it can be measured is a host inside
the list, and this bot runs on one (the owner's network, IPv4 + the IPv6
/64). That makes one finding more important than every other: **the gate
refusing this host** means the allowlist no longer covers the owner's
egress on that family — the owner's browser is refused too. Measured
2026-09-18 while landing the console: a v4-only allowlist admitted `curl -4`
and refused the browser, which had connected over IPv6. The evidence
script probes `/health` with `curl -4` and `curl -6` separately, and reports
the egress address each family measured, so the report can say exactly
which prefix the owner adds.

**The bot never changes a secret.** It reports the `wrangler secret put
ADMIN_ALLOWED_IPS …` command; the owner runs it. A console that can widen
its own allowlist is a console whose compromise widens it (ADR §3).

**The bot never reads the console's data.** `/v1/admin/*` is measured only
to its 401 (`sign-in-required`) and 403 (`origin-not-allowed`) — the
refusals by name that prove the gates inside the host are live. Users,
keys, requests and servers are the operator's, behind a passkey session
the bot does not have and must not ask for.

What the bot cannot hide, and does not report as a finding: the hostname
in DNS and in CT logs (a custom domain issues a certificate). What it does
scan: the shared `session.js` (the console is its own module, served only
inside), the enterprise twin catalog (which listed `host:admin` until
2026-09-18), sitemap / robots / llms.

## Evidence script

```
python3 scripts/hermes-akc-admin-ops/admin_ops_evidence.py            # full JSON report
python3 scripts/hermes-akc-admin-ops/admin_ops_evidence.py --monitor  # stable subset (byte-identical run to run while nothing changed)
```

Every network call names this tool (`User-Agent: akc-admin-ops-evidence/1`):
Cloudflare answers python-urllib's default agent with `403 error code:
1010`, which would have read as "the console is broken" on every check
(measured while writing it). `REFUSED` (`{"refused": "…"}`, exit 0) when the
worktree is missing or no live probe answered — blind, not green. The
`secrets` check needs the worktree's `node_modules` (wrangler) and the
wrangler OAuth session of the user the profile runs as; it reports NAMES
only, never a value.

## Two roots

| | |
|---|---|
| `~/github/com-junkawasaki` | read only (the shared checkouts; CLAUDE.md forbids writing) |
| `~/.gftd/worktrees/akc-admin-ops` | the bot's worktree of app-kotoba-cloud, detached at `origin/main`, outside the superproject (west topdir stays honest); `node_modules` is a symlink to the shared checkout's |

## Re-register on a new terminal

1. `hermes profile create akc-admin-ops --clone-from twin-ops` (config.yaml
   is the fleet's; then copy `twin-ops/config.yaml` verbatim — the clone
   re-serialises it), `cp SOUL.md` and `scripts/*.py` from here into the
   profile (`~/.hermes/profiles/akc-admin-ops/{SOUL.md,scripts/}`).
2. `git -C ~/github/com-junkawasaki/orgs/cloud-kotoba/app-kotoba-cloud
   worktree add --detach ~/.gftd/worktrees/akc-admin-ops origin/main` and
   symlink `node_modules`.
3. The two jobs from the cron ledger (`HERMES_HOME=~/.hermes/profiles/akc-admin-ops
   hermes cron create …` with the ledger's schedule, prompt, script /
   monitor-script, workdir, deliver **and the model / provider pin**
   `--model z-ai/glm-5.3-flash --provider openrouter`). Measured
   2026-09-18: registered without the pin, the first run resolved the
   profile default to provider `custom` on the OpenRouter base URL (400
   "not a valid model ID"), fell back to the blue route and sat in 504
   `inference-timeout` retries for 15 minutes; pinned like the sibling
   bots (twin-ops), the same job completed in under a minute.
4. `hermes cron run <id>` once each and watch `hermes cron runs` reach
   `completed` — listed is not running (the ledger README's rule).
