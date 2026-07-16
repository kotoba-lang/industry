# ADR-2607062200: gftdcojp — second wave of legacy `*.gftd.ai` host retirement (ipfs, atproto) + murakumo.cloud/kotobase.net consolidation status

## Status
Active (2026-07-06). Follow-up to ADR-2607062130 (dispatcher.gftd.ai retirement).

## Context

Owner directive: identify other `*.gftd.ai` hosts no longer in real use and prune
them, with a stated target architecture — `kotobase`/`ipfs`-related traffic
consolidates onto `kotobase.net`; `llm`/`comfyui`/generation traffic onto
`murakumo.cloud`; `atproto` traffic onto `aozora.app`.

Investigated (research agents, read-only) before touching anything live. Findings
and decisions below.

## Decisions taken today

1. **`ipfs.gftd.ai` — retired.** Confirmed zombie: its Worker script
   (`ai-gftd-ipfs-proxy`) served a 530 on real content lookups and its source repo
   (`gftdcojp/ai-gftd-ipfs-proxy`) no longer exists on GitHub at all. Deleted via
   Cloudflare API: the `ipfs.gftd.ai/*` route, the already-scriptless orphaned
   `ipfs-origin.gftd.ai/*` route, and the `ai-gftd-ipfs-proxy` Worker script itself.
   **Known gap, not silently papered over**: `kotobase.net` (the stated
   consolidation target) does not currently serve public IPFS retrieval either in
   its production deployment (`storage_mode: backend`, `/ipfs/:cid` → 404) — its
   own docs *name* `ipfs.gftd.ai` as the retrieval gateway. So there is currently
   **no live public IPFS retrieval gateway** for kotobase content. Owner explicitly
   accepted this (host was already non-functional; deleting it loses nothing that
   wasn't already lost) — flagged as follow-up: enable `KOTOBASE_STORAGE_MODE=b2`
   in production, or stand up a new Kubo/gateway backend, before anything depends
   on public retrieval again.

2. **`atproto.gftd.ai` — retired, fully.** Initially only the `atproto.gftd.ai/*`
   route was cut (script `ai-gftd-pds-2603241700` left deployed, since it also
   served `mcp.gftd.ai`, a separate live AgentGateway route). Owner then
   explicitly confirmed deleting the whole script too, accepting that this also
   takes `mcp.gftd.ai` down. Final state: both routes deleted, the
   `ai-gftd-pds-2603241700` script itself deleted (had to pass `force=true` — it
   had an active `*/5 * * * *` cron trigger blocking a plain delete). Verified:
   both `atproto.gftd.ai` and `mcp.gftd.ai` now unreachable (no route/script).

   Additionally, on the owner's explicit instruction, removed the dead source
   code for 7 never-deployed atproto/PLC-ecosystem Worker scaffolds from
   `ai-gftd-apps-gftdcojp` (`actor-resolver`, `browser-host`, `moderation`,
   `pds-tail-archiver`, `plc-directory`, `routing-gateway`, `relay` — confirmed
   via the live Cloudflare account that none of them had a deployed script) —
   [PR #1515](https://github.com/gftdcojp/ai-gftd-apps-gftdcojp/pull/1515),
   merged. That PR also removed the `plc-directory-tests`/`routing-gateway-drift`
   CI jobs that tested those directories, dropped `atproto/wrangler.jsonc`'s now-
   dangling `tail_consumers` binding to the deleted `pds-tail-archiver` service,
   and regenerated 4 contract-registry files that a pre-push hook found stale
   (pre-existing drift — a lexicon added upstream without regen — unrelated to
   this change, fixed honestly rather than bypassing the hook).

   This was investigated as a **migration** (move gftdcojp's existing actor
   DIDs/blobs onto `aozora.app`, a different existing PDS) before being downgraded
   to a straight retirement. The migration was found infeasible with either side's
   current implementation, not just inconvenient:
   - `atproto.gftd.ai`'s own `com.atproto.sync.*` endpoints (`listRepos`,
     `getRepo`, `describeRepo`) were **already broken in production** independent
     of this decision (`"createKyselyDb is prohibited in CF Workers"`, ADR-2605111200).
   - No real per-actor DID/repo structure existed to migrate — all shinshi blobs
     lived under one shared pseudo-identity (`"anonymous"`), not per-character DIDs.
   - `aozora.app`'s PDS (a from-scratch ClojureScript reimplementation, not a
     bluesky-social/pds fork) does not implement `com.atproto.repo.importRepo` or
     DID:PLC operation signing/submission at all.
   - DID method mismatch: `atproto.gftd.ai` actors are `did:web`, `aozora.app`
     actors are `did:key` — `did:key` documents have no mutable `service` field,
     so the standard "repoint a DID's service at a new PDS" mechanism the AT
     Protocol migration spec relies on does not apply here regardless of tooling.
   - At least 9 other services hardcode `atproto.gftd.ai` as upstream beyond
     shinshi: `plc-directory`, `actor-resolver`, `browser-host`, `email-relay`,
     `relay`, `moderation`, `routing-gateway`, `pds-tail-archiver`, and the
     `sheets`/`docs`/`calendar`/`drive`-compat + `lawfirm` products (all under
     `50-infra/cloudflare/workers/*` in `ai-gftd-apps-gftdcojp`).

   **Owner made an explicit, informed decision to retire anyway**, accepting that
   this immediately breaks: shinshi.club's gallery image display (the fix shipped
   earlier today in this same session — `listAuthorFeed`'s `thumbUrl`/`fullUrl`
   compute `https://atproto.gftd.ai/xrpc/com.atproto.sync.getBlob?...`), and all 9+
   other dependent services listed above. None of those 9+ services' code was
   touched in this session — only the Cloudflare route was cut. Follow-up: each
   dependent service's owner needs to either point at a real replacement PDS (once
   one exists with compatible DID/repo semantics) or be formally decommissioned
   too; this ADR does not resolve that, it only records that the shared upstream
   is now gone.

## Investigated, explicitly NOT touched (not ready / not safe)

- **`gemma.gftd.ai`** (dead, 530; used by `ai-gftd-mamori`/`ai-gftd-media`/
  `ai-gftd-news` as `LITELLM_URL`): `murakumo.cloud` (the stated consolidation
  target) now has a real API shell (`/api/v1/chat/completions`, per
  ADR-2607052200 — an improvement since ADR-2607031540's finding 3 days ago) but
  **no upstream LLM configured** (`MURAKUMO_OPENAI_URL` unset) and **no `gemma`
  model in its serving catalog at all** (`resources/murakumo.edn`'s
  `:llm-serving` app only has `minimax-m27`/`kimi-k27`/`embed`/
  `qwen3-coder-next-moe`). Repointing now would trade one dead host for a
  live-but-empty one. Left as-is (already dead, already fails). Needs an actual
  GPU/model provisioning decision on the murakumo.cloud side first — out of scope
  for a code change.
- **`comfyui.gftd.ai`/`comfyui-fleet.gftd.ai`/`murakumo.gftd.ai`**: currently
  alive; not touched. `murakumo-serve.gftd.ai`/`maps-langserver.gftd.ai`/
  `lg-manimani.gftd.ai`/`jacob-status.gftd.ai` were checked and found to be dead
  but with **zero live references** in the real (non-stale-worktree) tree —
  false positives from ~7 abandoned agent worktrees (see below), nothing to prune.

## Also done this session (unrelated cleanup surfaced along the way)

- Removed 7 abandoned/orphaned agent worktrees inside
  `orgs/gftdcojp/ai-gftd-apps-gftdcojp/.claude/worktrees/` (6.6GB, git itself had
  already lost track of their metadata due to an old repo-path relocation) —
  archived to `.git/worktree-archive-20260706/*.tar.gz` (2.5GB compressed) before
  deleting, plus one `git worktree prune`-flagged `/tmp` worktree.
- Fixed a stale doc-only reference in `ai-gftd-news/CLAUDE.md` (said
  `kotoba-origin.gftd.ai`, a dead retired-app fossil; runtime already uses
  `kotobase.net` since 2026-06-11) — merged, no functional change
  ([ai-gftd-news#2](https://github.com/gftdcojp/ai-gftd-news/pull/2)).
- `kotoba-origin.gftd.ai` itself: confirmed dead, zero live consumers
  (`ai-gftd-kotobase` is a fully retired/superseded app, `MOVED.md.edn` only) —
  no Cloudflare-side route existed to delete, nothing further to do.

## Consequences

- Two more dead/legacy Cloudflare Worker routes removed with no loss of live
  functionality (ipfs.gftd.ai) or with owner-accepted, known breakage
  (atproto.gftd.ai, and everything depending on it).
- Real gaps now explicit rather than silently broken-and-undocumented: no public
  IPFS retrieval gateway; no working PDS for gftdcojp actor identity/blobs at all
  post-retirement (shinshi and 9+ other services need a real replacement PDS
  design — not scoped here); murakumo.cloud not yet a working LLM/generation
  backend.
