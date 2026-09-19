# hermes-openrouter-gates — progress bot for the two OpenRouter provider applications

Owner instruction 2026-09-19: after two OpenRouter provider applications
(slug `murakumo`, slug `awai`) were submitted ahead of their own submission
gates (owner override, see below), set up a hermes bot profile to track
progress toward those gates instead of relying on someone re-reading the ADR.

## Background — why the gates exist and why they were overridden anyway

- `adr-2609011842-awai-japanese-models-openrouter-provider-application`
  (accepted) defines the role split (developer=awai.network, inference
  provider=murakumo.cloud) and, in §9, a submit gate: do not submit to
  OpenRouter until a long list of readiness items is evidenced (trained
  checkpoint, model card, evals, production endpoint, uptime, pricing,
  data policy).
- `90-docs/business/openrouter/awai-murakumo-provider-application.edn` is the
  paste-ready draft; its own opening line says "Do not submit until the
  relevant release and commercial gates are evidenced," and it lists 8
  concrete remaining-submission-gates items, none evidenced as of 2026-09-19.
- On 2026-09-19 the owner was asked (in chat) whether to hold, submit anyway
  with explicit in-development framing, or first audit an earlier submission,
  and chose to submit anyway. Both applications were filed through
  `https://openrouter.ai/providers/apply/form`. The `awai` one states
  verbatim in its Extra Details field: "STATUS: IN DEVELOPMENT, not yet
  production-ready ... do not route production traffic to awai-network/basho
  or awai-network/hokusai yet."
- This is recorded as a one-time owner exception, not a repeal of §9. Both
  documents were updated in place with dated evidence entries the same day.

## What this bot does

`progress_scan.cljk` is a **decision-free measurement** script, same pattern
as `scripts/hermes-pr-queue/`: it does not submit anything, does not flip any
readiness flag, and does not edit the ADR or the routing contract. It prints
one line per gate item:

- `DONE` / `PENDING` when the check ran and got an answer
- `UNKNOWN` when the live check itself failed (never silently folded into
  `PENDING` — a check that could not run is not the same as a gate that is
  not met, CLAUDE.md's evidence-floor rule)

It checks, each run:
1. `90-docs/deployment/awai-murakumo-basho-hokusai-routing.edn` — artifact
   training state for Basho/Hokusai, and the `:contract/advertise-models?`
   flag (must stay `false` until every readiness gate is evidenced).
2. A live anonymous `POST /v1/chat/completions` with `model=awai-network/basho`
   against `api.murakumo.cloud` — expected today: blocked with
   `self_model_requires_identity` (HTTP 402, confirmed 2026-09-19; an earlier
   manual note in the ADR guessed HTTP 400 without capturing it and was wrong,
   corrected in the same evidence entry).
3. A live `GET /v1/models` check of `awai-network/hokusai`'s `:kind` — today
   it is the free `image` wrapper, not the fine-tuned `video` model the ADR
   describes; this line flips to `DONE` only once that changes.
4. Each item in `routing.edn`'s `:readiness-gates :advertise-in-models` list
   (fine-tuned-artifact-readback, license/rights review, model card,
   Japanese/safety evals, direct smokes, murakumo public contract, usage
   accounting, price/capacity/data-policy publication, representative
   uptime) — these have no automated evidence source yet, so they always
   print `PENDING` until someone records evidence and this script is taught
   to read it. That is a known limit of the bot, not a passed gate.
5. `awai-murakumo-provider-application.edn`'s `:doc/status` (expect
   `:submitted-ahead-of-gates` until the doc's own 8 remaining-submission-gates
   items are each evidenced and the status is updated to something like
   `:submitted-and-evidenced`).

If every live check fails (network down, endpoint moved), it prints `REFUSED`
and exits 2 — not 0, not 1. A run that could not reach `api.murakumo.cloud` is
not the same as a run that measured no progress.

```bash
kbb --backend sci scripts/hermes-openrouter-gates/progress_scan.cljk
```

## Hermes wiring

Profile: `openrouter-gates` (script-only job; no agent model is configured,
so this does not hit the `provider 'openrouter-free' credential missing`
trap documented in `scripts/hermes-cron-jobs/README.md` for fresh agent
jobs). Cron job `gate-scan`, daily, delivers the script's stdout to
Bot Chat `openrouter-gates` for a human to read — it does not act on its own
output. Re-register on a new terminal per
`scripts/hermes-cron-jobs/README.md`'s "新端末での re-register 手順", using
this script's path as `script:`.

## What this bot must never do

- Never call the OpenRouter apply form again (no re-submission, no "fixing"
  the application on its own initiative).
- Never flip `:contract/advertise-models?` in routing.edn.
- Never mark a `readiness-gates` item as done from inference; only an owner
  or a dedicated verification script with its own evidence should do that,
  and this script should then be taught to read that evidence, not to assert
  it.
