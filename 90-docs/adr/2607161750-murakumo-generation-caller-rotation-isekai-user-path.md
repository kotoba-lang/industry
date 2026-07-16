# ADR-2607161750: Murakumo generation caller-gate rotation and the isekai.network user-facing photo→3D path

**Status**: accepted, implemented (live-verified end-to-end)
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (owner directive「gap を解消」), Claude
**Related**: network-isekai ADR-0057, net-babiniku ADR-0004 (2026-07-15 addenda),
ADR-2607131645 (murakumo direct voice), gftdcojp/cloud-murakumo README「Generation Backend」

## Context

An investigation of "can an end user on isekai.network drive asset generation
through Murakumo?" found three gaps: (1) no authenticated path from the public
Generate page to the token-gated generation backend (an unlanded draft dispatched
directly from the browser and would 401), (2) unverified GPU-backend liveness,
and (3) the generation caller-gate secret (`MURAKUMO_TOKEN_SECRET` on the
`murakumo-generation-proxy` Worker) was minted ephemerally by
net-babiniku's `scripts/provision-generation.sh` — generated, piped into
`wrangler secret put`, and deliberately wiped — so **no retrievable copy of the
value existed anywhere**, making it impossible to onboard a second caller
(network-isekai) without a rotation.

Also observed while mapping blast radius (recorded for future sessions):

- The deployed `murakumo.cloud` **site** Worker is older than the generation
  proxy code in `cloud-murakumo` main (its capability payload has no
  `generation` key); the live generation entrypoint is the dedicated
  `generation.murakumo.cloud` Worker (`wrangler.generation.jsonc`) →
  `murakumo-generation.gftd.ai` (JVM upstream).
- `api.murakumo.cloud` (local-murakumo) is a separate service with separate
  auth (`MURAKUMO_PROXY_TOKEN` holders: babiniku scene/chat, shinshi-growth-actor,
  local-manimani, the isekai playtest critic's `MURAKUMO_CLAUDE_TOKEN`).

## Decision

1. **Scope-minimal rotation.** Rotate only the `generation.murakumo.cloud`
   verifier (`MURAKUMO_TOKEN_SECRET` on Worker `murakumo-generation-proxy`) and
   its callers' `MURAKUMO_CALLER_SECRET` (net-babiniku Pages, network-isekai
   Pages — all set back-to-back in one run). The `murakumo.cloud` site Worker's
   own `MURAKUMO_TOKEN_SECRET` (chat/inference gate) was deliberately left
   untouched so existing chat-scope tokens keep working. `MURAKUMO_GENERATION_TOKEN`
   (Worker→JVM upstream bearer) was not touched either.
2. **The secret value is now stored.** Canonical storage is the kagi vault:
   item `MURAKUMO_GENERATION_TOKEN_SECRET`, compartment `gftdcojp`
   (`orgs/kotoba-lang/kagi`, OS-Keychain unlock). Future caller onboarding
   reads it from kagi instead of forcing another rotation. The
   `secrets-location-map` skill is updated in the same commit.
3. **network-isekai user path** (its ADR-0057): a same-origin Pages Function
   `/api/murakumo-generation` mints a fresh 60-second `mk1` capability
   (scope `generation`, sub `isekai-pages`) per upstream call — identical
   convention to babiniku's `murakumo-auth.js` — validates input (model3d only,
   ≤12 MiB image data URL, whitelisted params), rate-limits dispatch 5/h/IP via
   the existing D1 `rate_limit` table, and rewrites all upstream URLs
   same-origin. `isekai.gen` gained a photo file input; the result lands as a
   publishable `:model3d`/`:glb` asset.

## Live verification (2026-07-16)

Full user path exercised against production: `POST
https://isekai.network/api/murakumo-generation` with a test PNG → job
`bf9d5475a963403081aa4f0a6c5f056a` queued → running → **done in ~3.5 min** →
artifact streamed through the same-origin URL: valid GLB (magic `glTF`),
1,662,620 bytes, SHA-256 `bc7d4b49…aede9` **matching the upstream
contentHash**, license cc0. This also empirically answers gap (2): the GPU
fleet behind `murakumo-generation.gftd.ai` is provisioned and serving.

## Consequences / follow-ups

- babiniku's running Pages deployment may hold the pre-rotation caller secret
  until its next deploy (Pages secrets bind at deployment); its generation
  feature is session-gated and freshly provisioned, so exposure is minimal.
  Any ad-hoc long-lived generation-scope tokens minted from the old secret are
  dead; re-mint via `MURAKUMO_TOKEN_SECRET=$(kagi get
  MURAKUMO_GENERATION_TOKEN_SECRET) clojure -M:token issue <sub> generation <ttl>`
  in `cloud-murakumo`.
- The deployed `murakumo.cloud` site Worker remains stale (no `/api/v1/generation`
  route, `upstream.anthropic` null); apex's default generation endpoint
  (`murakumo.cloud/api/v1/generation`) therefore still 401s at the chat gate —
  redeploying the site Worker from cloud-murakumo main is a separate follow-up.
- network-isekai west pin advanced to `a2c4764`; `kami-engine-sdk` pin advanced
  to `baa5429` (network-isekai main requires its `kami.benchmark`
  performance-plan API). The **shared checkouts** of network-isekai,
  kami-engine-sdk, webgpu, gpu, webgl and org-w3-webgpu in this superproject
  are stale and/or carry uncommitted WIP (network-isekai's checkout sits on a
  divergent 6-commit line, base 228 commits behind main, plus a large unlanded
  murakumo-wired draft of `isekai.gen`) — cleanup/retirement per
  `git-cleanup-conflict` is future work, deliberately not done here.
- diffusion/tts/music stages on the Generate page remain Modal skeletons and
  are labeled honestly; wiring them to Murakumo `:apps :generation` stages
  (image/voice/music exist upstream) is a candidate follow-up.
