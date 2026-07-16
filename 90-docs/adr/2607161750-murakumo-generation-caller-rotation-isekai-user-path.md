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

## 2026-07-16 addendum — follow-ups 1 & 2 closed, babiniku deploy-source trap recorded

- **murakumo.cloud site Worker redeployed from cloud-murakumo main** (version
  `f02f0c01-258a-414a-9ffa-ac231d6f372e`, built in a clean worktree at `1e7176f`,
  generation-proxy contract test passed pre-deploy). The capability payload now
  advertises `/api/v1/generation` with `generation-configured: true`,
  `generation-access-gated: true`; the route 401s without a token. Note the site
  Worker's generation gate verifies with the site Worker's own
  `MURAKUMO_TOKEN_SECRET` (the chat gate — a *different, still-unretrievable*
  value), not the kagi-stored generation caller secret; consumers should prefer
  `generation.murakumo.cloud` directly.
- **net-babiniku production redeployed with the new caller secret.** Two-step:
  a first redeploy from clean main `a558bee` **regressed production** — the
  entire Murakumo generation stack (`murakumo-auth.js`, character/motion/
  effect/sound/voice functions, ~15 files) turned out to be **uncommitted,
  untracked WIP in the shared checkout**; previous production deployments
  (source-labeled `fbb100f`) had been made from that dirty tree. Recovered by
  redeploying the shared checkout's `public/` + `functions/` byte-state as-is
  (no files modified), which restored every endpoint (`/api/character-generation`
  again session-gates 401, site 200) **and** bound the rotated
  `MURAKUMO_CALLER_SECRET` — strictly better than the pre-incident state, closing
  the "babiniku holds the pre-rotation secret" window.
- ⚠ **Trap for future sessions**: until babiniku's generation-stack WIP lands on
  its `main`, any "redeploy babiniku from main" removes those production
  Functions. Land the WIP (its owning agent's flow) before clean-main deploys.

## 2026-07-16 addendum 2 — speech/music wired (ADR-0058); apex endpoint finding

- **isekai.network Text→Speech / Text→Music are live** through the same
  `/api/murakumo-generation` proxy (network-isekai ADR-0058, merged `a4f625c`,
  pin advanced to `48a3103`). Upstream contract verified by probe: accepted
  types are exactly `model3d, voice, motion, effect, sound` (no image type —
  diffusion stays a skeleton); `voice` needs `input.text` + full BCP-47
  `params.locale` (`ja-JP`/`en-US`; bare `ja` rejected); `sound` needs
  `input.prompt` + `sound_kind ∈ {sfx, ambience, music}`. Production E2E:
  voice job `2eefca2f…` → 43,356-byte RIFF WAV; music job `f66f8327…` →
  192,044-byte RIFF WAV, both streamed through the same-origin artifact URL;
  bad locale rejected 400 at the proxy.
- **apex (B-3) retargeted to guidance, not a change**: ai-gftd-apex `main` has
  no HTTP generation client — its generation goes server-side through
  `cloud-murakumo.dispatch`. The `default-endpoint
  "https://murakumo.cloud/api/v1/generation"` sits in **uncommitted WIP** in
  the shared checkout (`src/gftd/apex/generation_api.cljc`,
  `src/gftd/chat/generation_client.cljs` — untracked). Guidance for whoever
  lands that WIP: point it at `https://generation.murakumo.cloud/api/v1/generation`
  and mint capabilities from the kagi-stored caller secret — the murakumo.cloud
  site route verifies with the *chat* gate secret, whose value is unretrievable,
  so tokens for it cannot currently be minted at all.

## 2026-07-16 addendum 3 — shared-checkout retirement (git-cleanup-conflict pass)

Ran the cleanup runbook over the four session-relevant shared checkouts. All WIP
was preserved before anything else: each checkout's full dirty+untracked state
was snapshotted **non-invasively** (temporary GIT_INDEX_FILE; working trees,
indices and branches untouched) to a pushed branch `wip-snapshot-260716` —
network-isekai `7f535b1a` (parent 8c4785a), net-babiniku `6bf84b66` (parent
fbb100f; includes ~88 MB of src-tauri/target build artifacts, rescue-branch
only), ai-gftd-apex `296590ec` (parent 1c5c467), cloud-murakumo `49eaf32e`
(parent 4ed0af4).

- **net-babiniku generation-stack WIP LANDED** (closes the addendum-2 trap):
  clean branch from origin/main + 3-way apply of the real WIP (231 files;
  src-tauri/target excluded and now gitignored), one generated-file conflict
  (public/index.html) regenerated via `npm run site`, dance files superseded by
  the concurrently-landed ReAct kaizen round (ADR-0009) resolved to main's side.
  Build + resource-guard/voice/reactions/character-generation suites green
  (resource-guard's failure out-of-tree is positional: the repo-local guard
  imports the superproject guard by relative path). Merged as PR #170; production
  redeployed from tracked main `fc8addb`; west pin advanced `4804151` →
  `fc8addb`. "Redeploy babiniku from main" is now safe.
- **network-isekai stash retired**: `wip-broadcast-stage-preserve` (WebRTC stage
  broadcast prototype) was unlanded → archived to
  `.git/stash-archive-260716/2299bc40….patch` and rescued to pushed branch
  `stash-rescue-260716-broadcast` (merge is owner's call; note `.-pc`/`.-stream`
  field access on a CLJS map likely needs keyword access), then dropped. The
  divergent modeler line remains preserved remotely on `agent/deps-host-fix` /
  `agent/deps-host-surface`; the shared checkout still sits on it with WIP —
  left untouched for its owner (snapshot branch covers loss risk).
- **ai-gftd-apex / cloud-murakumo**: WIP snapshot-preserved only; working trees
  left for their owners (cloud-murakumo's generation/LoRA work and apex's
  generation client are active series).
- **superproject stash left intact**: `concurrent-wip-repo-reality-verify-…`
  (created 18:01 today by a concurrent session — its owner's to retire).
