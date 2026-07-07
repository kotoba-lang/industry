---
id: adr-2607072000-gftdcojp-net-kotobase-ipfs-gateway-split
title: "ADR-2607072000: gftdcojp — net-kotobase-ipfs, dedicated IPFS/IPNS retrieval Worker (replaces net-kotobase MIGRATION.md Phase 3's dead-Kubo-pod plan)"
status: accepted
doc_type: adr
topic: net-kotobase-ipfs-gateway-split
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - "ipfs.gftd.ai / ipfs.kotobase.net public IPFS retrieval surface: owned by the new gftdcojp/net-kotobase-ipfs repo/Worker, not net-kotobase itself and not any Vultr-hosted Kubo pod"
  - "net-kotobase/MIGRATION.md Phase 3 is superseded by this ADR: the original Phase 3 (reuse the ipfs-origin-proxy -> kubo backend behind ipfs.gftd.ai) assumed a backend that no longer exists"
related:
  - 90-docs/adr/2606120930-murakumo-fleet-stateless-k3s-vultr-winddown.md
authoritative_note: "the Vultr winddown ADR lives in ai-gftd-apps-gftdcojp/90-docs/adr/, not this superproject"
supersedes: []
superseded_by: []
---

# ADR-2607072000: net-kotobase-ipfs — dedicated IPFS/IPNS retrieval Worker

**Status**: accepted (repo created, code real and unit-tested, not yet deployed)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki (instruction: "ipfs は gftcojp/net-kotobase-ipfs として ipfs に統合" — consolidate IPFS retrieval as `gftdcojp/net-kotobase-ipfs`, integrated with the kotobase.net IPFS surface)

## Context

`ipfs.gftd.ai` was served by a Cloudflare Worker (`ai-gftd-ipfs-proxy`, source
repo `gftdcojp/ai-gftd-ipfs-proxy` no longer exists at all) whose `GET
/ipfs/<cid>` already returned HTTP 530 — its backend was a self-hosted Kubo
pod on the Vultr VKE cluster, permanently decommissioned org-wide
(`ai-gftd-apps-gftdcojp/90-docs/adr/2606120930-murakumo-fleet-stateless-k3s-vultr-winddown.md`).
Its dead Cloudflare route and Worker script were deleted directly this
session.

`gftdcojp/net-kotobase` (`kotobase.net`, a live, well-documented Cloudflare
Worker BaaS/pinning service) does NOT currently serve public IPFS retrieval:
`GET /ipfs/:cid` returns 404 `"not archived and no gateway configured"` in
production (`KOTOBASE_STORAGE_MODE=backend`, and `KOTOBASE_IPFS_GATEWAY_URL`
is unset). But:

- `net-kotobase/docs/USING-AS-A-PIN-SERVICE.md` names
  `https://ipfs.gftd.ai/ipfs/<cid>` as its retrieval gateway ("backed by the
  same store") — currently false in production.
- `net-kotobase/MIGRATION.md`'s **Phase 3** already planned an
  `ipfs.gftd.ai → ipfs.kotobase.net` cutover, but as written it assumed
  reusing the *same* `ipfs-origin-proxy → kubo` backend the now-deleted
  Vultr cluster hosted. That backend is permanently gone; Phase 3 as
  originally written cannot be executed.
- Separately, net-kotobase's own Worker already has a genuinely working,
  tested **Worker-native B2 archive mode**
  (`KOTOBASE_STORAGE_MODE=b2`, ADR-2606110003 in that repo): `GET /ipfs/*`
  and `GET /ipns/*` serve CAR/raw objects straight out of the same
  Backblaze B2 bucket net-kotobase already uses for CAR-on-B2 pin durability
  (ADR-2606042100 in that repo), falling back to a configurable public
  gateway. This mode has never been promoted to production — that repo's
  own `docs/ENVIRONMENT.md` explicitly requires "an explicit ADR and release
  entry" before production leaves `KOTOBASE_STORAGE_MODE=backend`, because
  flipping the *whole* Worker to `b2` mode also switches its tenant/pin/
  account control plane off the kotoba backend — a larger, separate decision
  than "make IPFS retrieval work."

So: there is a genuine functionality gap (nothing serves public IPFS
retrieval for this org today), a dead plan (Phase 3 as written), and an
already-built-but-dormant piece of the real fix (the B2 archive-serving
logic) all at once.

## Decision

**Split IPFS/IPNS retrieval into its own repo and Worker,
`gftdcojp/net-kotobase-ipfs`, rather than either (a) resurrecting the dead
Kubo-pod plan, or (b) flipping net-kotobase's whole production Worker to
`KOTOBASE_STORAGE_MODE=b2`.**

Rationale for a separate repo/Worker (not folding this into net-kotobase's
own production deploy):

1. **Blast-radius / privilege separation.** `ipfs.kotobase.net`'s only job
   is a public, unauthenticated `GET`. It should only ever need read-only B2
   credentials. net-kotobase's main Worker holds write-capable B2
   credentials, CACAO verification, Stripe billing, and tenant quota state —
   none of which the retrieval surface needs, and all of which would be
   needlessly exposed to the retrieval surface's larger, more adversarial
   (public, unauthenticated, high-volume) traffic pattern if they shared one
   Worker/route.
2. **Doesn't require the ADR-gated whole-Worker mode flip.**
   `KOTOBASE_STORAGE_MODE=b2` in net-kotobase's own Worker also changes how
   `/pins` and tenant XRPC are served (Worker-native B2 metadata instead of
   the kotoba backend) — a bigger, separate decision from "serve IPFS
   content." A dedicated retrieval-only Worker sidesteps that coupling
   entirely: it can go live without touching net-kotobase's production
   storage mode at all.
3. **Matches the existing sibling-repo convention** for kotobase
   components split out of the monolith for a single responsibility
   (`kotoba-lang/kotobase-cljc-worker`, `kotoba-lang/kotobase-browser-worker`,
   `kotoba-lang/kotobase-peer`) — each a small, independently deployable
   piece rather than one ever-growing Worker.
4. **Matches the owner's explicit naming instruction** ("gftcojp/net-kotobase-ipfs").

The new repo's B2-CAR → B2-raw → gateway-fallback → 404 dispatch logic,
SigV4 signing, content-type sanitization, and gateway SSRF guard are a
faithful, read-only-scoped **port** (copied, not depended-on) of
net-kotobase's own `clj-edge/src/kotobase/{b2,b2_write,proxy,sigv4}.cljc`
(the exact tested logic behind that repo's dormant `/ipfs/*`/`/ipns/*`
routes). Copying rather than depending-on keeps this repo single-purpose:
it doesn't need net-kotobase's CACAO/tenant/graph dependency chain (which
pulls in several further kotoba-lang repos as shadow-cljs source-paths)
just to serve `GET /ipfs/*`. The tradeoff is the two copies need to be kept
in sync if the upstream logic changes; if they drift, net-kotobase's copy —
with the larger, longer-running test suite — is canonical.

The new Worker is configured (via `KOTOBASE_B2_PREFIX`) to read from the
**same B2 bucket/prefix** net-kotobase already archives (or will archive)
into (`kotobase/worker-archive` by default), so it serves whatever
net-kotobase has archived without needing its own separate ingestion path.

## What this ADR does NOT decide

- **Whether/when to actually turn on archiving.** net-kotobase's production
  Worker runs `KOTOBASE_B2_ARCHIVE_CIDS=0` (metadata-only) as of this
  writing — it has never archived a CAR or raw object for any CID. Flipping
  that to `1` (and/or configuring a `KOTOBASE_IPFS_GATEWAY_URL` there so new
  pins get archived on write, and/or a backfill of existing pins) is a
  separate operational decision, needs net-kotobase's own ADR-gate per its
  `docs/ENVIRONMENT.md`, and is explicitly out of scope here. Without it,
  this new Worker has nothing real to serve yet, no matter how correctly it
  is deployed.
- **DNS/route cutover.** No route is bound in `net-kotobase-ipfs`'s
  `wrangler.jsonc` yet (commented out on purpose). No B2 credentials exist
  for this Worker. No `wrangler deploy` has been run. See that repo's
  README "Deploying this for real" checklist.

## Consequences

- `net-kotobase/MIGRATION.md` Phase 3 is marked superseded-by-this-ADR
  (pointing here) instead of describing the dead Kubo-pod plan as
  actionable.
- `net-kotobase/docs/USING-AS-A-PIN-SERVICE.md`'s retrieval section is
  corrected to describe actual current state (not yet live) instead of
  asserting a working gateway.
- `ai-gftd-apps-gftdcojp`'s now-doubly-dead `50-infra/vultr/ipfs/` and
  `ipfs-raw/` Helm charts (already-dead Vultr infra, now also superseded by
  this plan) are removed, along with any docs describing them as live.
- A follow-up (separate task, needs an operator with B2 console access and
  its own ADR in net-kotobase) is required before `ipfs.kotobase.net`
  actually serves real content: B2 read-only credentials, archiving turned
  on (or backfilled), and the route cutover itself.

## Verification

- `net-kotobase-ipfs`: `pnpm test` (9 tests / 28 assertions, faked `fetch`,
  no network) and `pnpm run build` (`shadow-cljs release worker`) both pass;
  `wrangler deploy --dry-run` validates the config without deploying.
- No `wrangler deploy` was run for `net-kotobase-ipfs` (never live).
