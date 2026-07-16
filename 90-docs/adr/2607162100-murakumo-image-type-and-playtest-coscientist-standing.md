# ADR-2607162100: Murakumo image job type, secondary /v1/messages token, and standing playtest co-scientist

**Status**: accepted, implemented (live-verified end-to-end)
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (owner directive「gap を埋めて」/「do it」), Claude
**Related**: ADR-2607161750 (generation caller rotation + isekai user path),
network-isekai ADR-0057/0058/0060, kotoba-lang/murakumo PR #22,
gftdcojp/local-murakumo (ANTHROPIC_PROXY_TOKEN_2)

## Context

Three remaining gaps after ADR-2607161750 were said to need "someone else's
WIP" or owner direction. Owner directed filling them directly. All three are
now closed by landing the relevant shared-checkout WIP and building on it.

## Decisions

1. **Text→Image is a real generation type.** `kotoba-lang/murakumo`'s
   generation upstream (`scripts/hunyuan3d-generation-api`, deployed on the
   `gad` fleet node behind `generation.murakumo.cloud`) previously accepted only
   `model3d/voice/motion/effect/sound`. Added an `image` job type: validated
   prompt/model/size/steps/seed/negative_prompt → a ComfyUI
   CheckpointLoader→KSampler→VAEDecode graph (the same graph cloud-murakumo's
   `engine.cljc` and gad's `comfy_openai_bridge.py` use) → PNG artifact with
   sha256 contentHash + license metadata; `/healthz` advertises `image` only
   when `MURAKUMO_COMFY_URL` is set. Deployed to gad (ComfyUI on ROCm,
   `animagine-xl-4.0`); `/healthz` now lists `image`. The isekai proxy and
   Generate page's diffusion stage were wired through (network-isekai ADR-0058
   extension). **Every Generate-page stage is now real end-to-end.**

2. **Secondary /v1/messages token (`ANTHROPIC_PROXY_TOKEN_2`).**
   `local-murakumo`'s `api.murakumo.cloud/v1/messages` auth was split into a
   pure `local-murakumo.messages-auth` ns and now accepts an optional secondary
   token in addition to the shared `ANTHROPIC_PROXY_TOKEN`. This onboards the
   playtest co-scientist vision critic with a fresh **kagi-stored** token
   (item `MURAKUMO_CRITIC_TOKEN`, compartment `gftdcojp`) without rotating the
   primary out from under shinshi-growth-actor / local-manimani. It also solves
   the "op interactive-auth times out" blocker that made the critic token
   unretrievable for any scheduler. General slot: future `/v1/messages`
   consumers onboard the same way.

3. **Playtest co-scientist is a standing capability (network-isekai ADR-0060).**
   A one-command nbb wrapper manages the static server around the loop; a local
   LaunchAgent on `main-2` (`com.gftd.playtest-coscientist`, weekly Mon 05:00
   JST) drives it in a dedicated sibling worktree, sourcing the critic token
   from kagi and pushing winners to an owner-review branch. Local (not cloud)
   because the loop needs local Playwright + sibling-dep build + local-kagi
   token.

## Live verification (2026-07-16)

- **image**: `POST generation.murakumo.cloud/api/v1/generation {type:image,…}`
  → job done in ~15s → 1,557,867-byte PNG (valid `\x89PNG`, sha256-matched);
  and end-to-end through `isekai.network/api/murakumo-generation` → 2,028,228-
  byte PNG. Both content-hashed, license cc0.
- **secondary token**: the new kagi token authorizes `/v1/messages` (model
  replied "CRITIC READY"), a wrong token still 401s, primary callers unaffected.
- **standing loop**: full run against `gftd/drive` (directly and through the
  wrapper) — real Playwright headless-Chromium playthrough + real vision critic
  (`murakumo available? true`) + qa-governor + ledger + report; `scene.edn`
  restored byte-identically when no candidate beat baseline.

## Shared-checkout landings (this pass)

Closed the addendum-3 "unlanded WIP" follow-ups by landing each with tests +
byte-preserved `wip-snapshot-260716` archive branches, west pins advanced:
net-babiniku (generation stack, PR #170), ai-gftd-apex (generation client +
`generation.murakumo.cloud` endpoint, PR #9), cloud-murakumo (public generation
surface, PR #21), kotoba-lang/murakumo (generation API + image type, PR #22),
local-murakumo (secondary token). Remaining shared-checkout WIP (isekai modeler
line, cloud-murakumo video series) is preserved on `wip-snapshot-260716` remote
branches and left for its owners.

## Consequences

- All four isekai Generate stages (3D, image, speech, music) are real,
  user-invocable through one credential-free same-origin proxy.
- The quality/tuning co-scientist loop runs on a schedule with a resolved
  critic credential. This is still the *evaluation* loop, not generation-as-
  coscientist, and it is a developer/scheduled capability — a per-user in-
  browser playtest surface on isekai.network remains a separate product
  decision.
