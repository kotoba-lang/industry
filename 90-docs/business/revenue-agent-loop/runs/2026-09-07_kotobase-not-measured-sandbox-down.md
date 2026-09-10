# Run 2026-09-07 — kotobase-stab / not-measured (sandbox down)

**Status:** stopped (could not measure — environment failed, not evidence)
**Started:** 2026-09-07 (JST)
**Ended:** 2026-09-07
**Owner:** agent (cron, kotobase-stab profile)
**Mode:** cash-first (external revenue still 0 per scorecard 2026-08-14)

## Outcome this run

**NOT-MEASURED, not CLEAN.**

Every measurement path failed in this cron session:

| Signal | Result |
|---|---|
| `nbb ... kotobase_lead_loop.cljs funnel-pulse` | Could NOT run — terminal backend returned empty output for every command (`echo`, `date`, `curl`) with exit 0. No funnel pulse recorded by the routine. |
| `curl -s https://kotobase.net/api/funnel` | Could NOT run — no HTTP possible from this session (terminal + web tools both down). |
| Stability check: `/`, `/signup` (expect 200); `/ipld/v1` (expect 400) | NOT-MEASURED — could not issue any HTTP request. |
| Web-extract fallback | Failed — web tool configured for `nous` gateway, gateway not entitled/unreachable. |

Rule applied: no reachable endpoint = `not-measured`, never `CLEAN`. No funnel numbers are reported because none were obtained. No score is raised on zero fresh evidence.

## Source of failure (1 line each)

- terminal backend: all commands (`echo`, `date`, `curl`, `nbb`) returned exit 0 with empty output → command output capture broken / sandbox not serving process I/O.
- file tools: `search_files` stat fails ("Terminal environment unavailable: could not stat ..."); `write_file`/`read_file` on explicit paths DO work → read/write layer functional, enumeration + process layer broken.
- So signal gathering (network) was fully blocked even though disk I/O worked.

## SCORE → SELECT (scorecard as observed 2026-08-14)

- net-kotobase standing score: **58/100**. External verified revenue = 0. Legal pages live but DRAFT/UNAPPROVED (counsel return not obtained) → **commercial go = no-go**.
- Standing queue: "net-kotobase named-accounts discovery"; Beekle form (`https://beekle.jp/contact`) needs human Chrome (Turnstile); lead-loop rule = **WIP=1 stays Beekle until a thank-you/success exists**; "Do not expand to 100 contacts on 0 replies."
- **Selected action this run: WIP is UNCHANGED = Beekle (unsent).** No new outreach target is surfaced because (a) lead-loop discipline keeps WIP on Beekle, (b) 0 external replies + 0 revenue forbids expanding the queue, (c) counsel legal gate not green forbids any paid solicitation.
- Score raised? No. There is no fresh evidence to justify any change. Nothing was deployed or sent.

## Bounded experiment (draft only — NOT executed, NOT sent)

Because measurement was impossible this run, the experiment is proposed for the next run when the network/toolchain is healthy:

- **Name:** `0037-net-kotobase-contact-url-verify`
- **One action:** before any outreach, run a live GET probe of the named-account contact paths to (re-)confirm current URLs/state — Beekle contact form, and for ELYZA and iASYS the `contact-path` from `net-kotobase-named-accounts.edn` (lead-loop requires re-confirming these URLs with a live GET before naming them; Helpfeel is permanently skipped).
- **Expected evidence within timebox:** current URL reachable / form state (Turnstile vs direct); Beekle `thank-you/success` state (advances WIP) or still unsent (WIP unchanged).
- **Hard gate:** no send, no captcha bypass, no paid solicitation while counsel legal is unapproved. Destination draft + recipient list only.
- **Stop if:** `/`, `/signup`, `/api/funnel`, `/ipld/v1` are unreachable or degrading (5xx) — fix stability/observability before outreach.

## Data-integrity incident to fix (caused by this run)

While probing for the metrics file I mistakenly used `write_file` (overwrite) instead of `read_file` on:
`/Users/junkawasaki/github/com-junkawasaki/90-docs/business/metrics/net-kotobase-funnel-pulses.edn`
The file now contains only a one-line comment. If it previously held appended funnel-pulse history (written by the funnel-pulse routine), that history was **clobbered**. Recommend restoring from git:
`git checkout -- 90-docs/business/metrics/net-kotobase-funnel-pulses.edn`
This is the top priority next action for the owner.

## Decision

`stop` for this run (could not measure). **Next single action (owner):** restore the funnel-pulse metrics file from git, then re-run this bot when the cron sandbox can execute terminal commands and reach the network. Keep WIP = Beekle; do not raise score; do not expand outreach; commercial go stays no-go until counsel approval.