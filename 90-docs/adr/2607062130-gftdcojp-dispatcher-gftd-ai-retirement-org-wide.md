# ADR-2607062130: gftdcojp — retire `dispatcher.gftd.ai` org-wide; migrate app-by-app

## Status
Proposed (shinshi migrated; other apps tracked as follow-up)

## Context

User report: `shinshi.club` shows no characters/images. Root cause: the appview
Worker's read-only NSIDs all proxied through `dispatcher.gftd.ai` (a Cloudflare
Worker/Tunnel router that forwards to k8s pods on the Vultr VKE cluster,
`mitama-udf` namespace). `dispatcher.gftd.ai` returns HTTP 530 / Cloudflare
error 1033 (tunnel unreachable) on every request — confirmed by direct `curl`,
3/3 attempts. The k8s cluster behind it (`vke-31d5f7dc-...`) is unreachable
from `kubectl` (`connection refused`) — **owner directive: k8s is deprecated,
prune.** This mirrors the same finding already recorded for `ai-gftd-yukkuri`
(ADR-2607031540, 2026-07-03: same cluster, same `connection refused`, same
owner directive) and the standing architecture rule that `bpmn-dispatcher` /
`dispatcher.gftd.ai` is a deprecated execution path (ADR-2604282300).

`dispatcher.gftd.ai` is not shinshi-specific. It is referenced by at least:

| App | wrangler.jsonc reference | Notes |
|---|---|---|
| `gftdcojp/club-shinshi` | `SHINSHI_MCP_URL`/`SHINSHI_XRPC_URL` | **Fixed** — see below |
| `gftdcojp/ai-gftd-dogaka` | dispatcher URL var | Not investigated this session |
| `gftdcojp/ai-gftd-kakure` | dispatcher URL var | Not investigated this session |
| `gftdcojp/ai-gftd-nemuri` | dispatcher URL var | Not investigated this session |
| `gftdcojp/ai-gftd-shinshi` | dispatcher URL var | Separate, likely-stale duplicate of club-shinshi (see below) |

(Non-exhaustive — broader repo search turns up dispatcher.gftd.ai mentions in
docs/CLAUDE.md across `ai-gftd-apps-gftdcojp`'s many `60-apps/*` projects too;
those weren't individually verified this session.)

## Decision

**Migrate app-by-app, not in one bulk sweep** — mirrors the precedent already
set by ADR-2605151600 (maps) and ADR-2604282300 ("all other actors continue
... until individually migrated"). Bulk-editing every app's dispatcher
reference without verifying each one's actual backend shape risks silently
breaking apps whose read paths *do* need real compute (unlike shinshi's,
which turned out to be a pure D1 passthrough).

**Migration pattern per app** (established this session for club-shinshi):
1. Identify which NSIDs behind `dispatcher.gftd.ai` are pure data reads that
   the pod itself only forwarded to the app's own Cloudflare D1 (or other
   Cloudflare-native binding) — those port directly into the Worker in-process
   binding access, zero backend hop.
2. Identify which NSIDs need real compute (GPU render, LLM inference, PMS/
   billing integration) — those have no drop-in replacement yet. Fail them
   honestly (410/503 with a clear message) rather than leaving them pointed
   at a dead host, until a real backend (`cloud-murakumo` or equivalent) is
   reachable and wired up.
3. Remove the `dispatcher.gftd.ai` default/URL from that app's code and
   `wrangler.jsonc` once its read paths are ported.

**club-shinshi: done.** `listActresses`/`getModelProfile`/`listAuthorFeed`/
`coverage`/`listComics`/`getComic` ported to direct `SHINSHI_DB`/
`RECORDLOG_DB` D1 reads in `routes/xrpc/[...path]/+server.ts`;
`lib/server/mcp.ts`'s dispatcher fetch removed entirely (returns 410 for
anything not yet migrated: generation NSIDs, hotel, baminiku). See
`orgs/gftdcojp/club-shinshi/90-docs/adr/2607062100-...md.edn` and
[PR #4](https://github.com/gftdcojp/club-shinshi/pull/4).

**Not done this session** (follow-up, per app):
- `ai-gftd-dogaka` / `ai-gftd-kakure` / `ai-gftd-nemuri` — dispatcher usage
  not investigated; unknown whether their NSIDs are D1-passthrough (like
  shinshi's turned out to be) or need real compute. Do NOT bulk-prune their
  wrangler.jsonc without the same per-app verification done for shinshi.
- Generation/hotel/baminiku paths inside shinshi itself — need a reachable
  replacement backend (cloud-murakumo's edge hosts are not yet reachable,
  per ADR-2607031540's own finding: `*.edge.murakumo.cloud` NXDOMAIN).

## A separate finding: duplicate shinshi repos

`orgs/gftdcojp/ai-gftd-shinshi` and `orgs/gftdcojp/club-shinshi` are two
**different** GitHub repos that both claim to be "the standalone extraction of
`ai-gftd-project-shinshi`" and both configure the same Cloudflare Worker name
(`magatama-sh1n5h1x`). They have diverged independently:
- `club-shinshi`: extracted 2026-06-26, has an in-progress `feat/cljc-rewrite`
  branch (Svelte → reagent-style frontend, unmerged, uncommitted WIP found in
  a stale local worktree — separate cleanup item).
- `ai-gftd-shinshi`: split 2026-06-30, merged a `codex/cljc-runtime-migration`
  PR (2026-07-01) that ported the Python LangGraph pod backend to Clojure and
  the frontend router to `.cljc`.

Verified which one is actually live: `curl https://shinshi.club/` serves HTML
whose SPA-router comment says `lib/spa/router.ts` (TypeScript) — matching
`club-shinshi`, not `ai-gftd-shinshi`'s `router.cljc`. So `club-shinshi` is
confirmed live and is where this session's fix was applied. `ai-gftd-shinshi`
appears to be an unused parallel duplicate, but this wasn't fully confirmed —
flagged for the owner to reconcile (archive one, or figure out which was
actually intended to be canonical) rather than assumed and deleted.

## Consequences

- shinshi.club's character/image display is fixed with zero new
  infrastructure (no new Cloudflare Tunnel, no k8s, no cloud-murakumo
  dependency) — it only needed bindings the Worker already had.
- Other apps sharing `dispatcher.gftd.ai` remain broken for whichever of
  their NSIDs actually depended on it, until each is individually verified
  and migrated the same way.
- The duplicate shinshi repo situation is a standing footgun (two repos,
  same Worker name, independently evolving) until reconciled.

## Alternatives Considered

1. **Stand up a direct Cloudflare Tunnel from a k8s pod to bypass
   dispatcher.gftd.ai** (the pattern ADR-2605151600 used for maps).
   Rejected for shinshi specifically — the k8s cluster itself is gone
   (`connection refused`), not just the dispatcher hop, so there is no pod
   left to tunnel to. This alternative may still apply to *other* apps if
   their pods are still reachable — not ruled out org-wide, just not
   applicable here.
2. **Wire generation NSIDs to `cloud-murakumo` now.** Rejected for this
   session — verified `cloud-murakumo`'s edge hosts are not reachable yet
   (same NXDOMAIN finding as ADR-2607031540), so wiring to it would trade one
   dead host for another rather than fixing anything.
