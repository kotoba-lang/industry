# ADR 2607011500: Spirit in Physics as a self-sovereign atproto actor (com-etzhayyim-tsumugu)

## Status
Accepted

## Context

The user wants to publish the "Spirit in Physics" manga (part of the Ghost
Hacker universe, content at `org-spirit-in-physics-comics`) as an AT Protocol
actor: generation via the `kami-mangaka-{render,page}-clj` commons, a
co-scientist-style quality loop before any panel/page is committed, and
publication that is discoverable on the wider network — with `aozora.app`
(the etzhayyim fleet's AT Protocol boundary) as one, but not the only,
consumer, since future targets (X, Instagram, LINE) are expected.

Two candidate publish topologies were considered:

1. **Push**: the actor calls `app-aozora`'s XRPC write endpoint directly,
   authenticated by a DID-signed request. Investigation of
   `orgs/gftdcojp/app-aozora`'s actual server code
   (`$lib/server/aozora-pds/index.ts`) found its write path is a
   single-owner `Bearer == env.PDS_ADMIN_TOKEN` check — not DID/CACAO
   verification — and there is no live multi-tenant actor-onboarding flow
   (the `account` table is unused; `kototama.emit`'s `:app-aozora` target
   still points at a placeholder `https://bsky.social`). Making a real
   external actor push into app-aozora's D1 store would mean adding new
   signature-verification code to a security-sensitive write path.
2. **Relay/pull**: the actor publishes *only* to its own self-sovereign
   graph (kotobase.net, per the existing `kotoba-server` CACAO pattern —
   see `orgs/gftdcojp/ai-gftd-itonami/src/itonami/cacao.clj` +
   `itonami/kotoba.clj`), and each downstream consumer (aozora.app, and
   later X/Instagram/LINE) independently subscribes to / crawls that graph
   and projects it into its own format. `app-aozora` already has exactly
   this shape of projector (`aozora.appview.manga`, pure EDN → kotoba tx →
   `:yoro.post/*` + `:gh.manga/*`) — currently fed by a hand-run JVM export
   of a static file; extending it to poll/subscribe the actor's live graph
   is a natural extension of existing code, not new write-auth surface.

## Decision

**Relay/pull.** The actor (`com-etzhayyim-tsumugu`) is publish-target-agnostic:

- **Identity**: self-issued Ed25519 → `did:key` → key-derived IPNS graph
  name, ported from `itonami.cacao`'s `load-or-create-identity!`. No owner
  hand-off, no shared token — the actor's graph is its own key by
  construction.
- **Store**: `kotoba-store {:identity me}` (ported from `itonami.kotoba`) —
  self-mints its own CACAO (`:cap/transact` on its own graph), durable
  content-addressed ledger on kotobase.net.
- **Generation**: containment node wraps `kami-mangaka-render-clj` /
  `kami-mangaka-page-clj`; proposal-only, never writes directly.
- **Quality loop**: `generate → review (charter gates) → rank (Elo) →
  evolve`, ported from `com-etzhayyim-ibuki`'s `methods/coscientist.cljc`
  (the pure-function generate/review/rank/evolve/meta-review skeleton is
  domain-agnostic; the manga-specific gates replace ibuki's
  parasitism/mechanism gates).
- **Governor**: independent `PolicyGovernor` node (content/copyright/style
  rules), StateGraph topology (`intake → advise → govern → decide →
  commit|request-approval|hold`) ported from `gftd-talent-actor`'s
  `operation.cljc`. Single invariant: only `:commit` writes, and only after
  the governor clears it.
- **Publish fan-out**: modeled on `kototama.emit`'s existing `targets` map
  shape — one canonical committed record, N independent downstream
  adapters. This ADR implements the `:aozora` relay adapter (subscribe/poll
  the actor's kotobase.net graph → `aozora.appview.manga` projection); `:x`
  / `:instagram` / `:line` are left as future target entries, requiring no
  change to the actor core.

## Consequences

- The actor never depends on `app-aozora`'s security model or deploy
  lifecycle to publish — it is fully self-sovereign from day one.
- `app-aozora` gains a relay/polling job instead of a new authenticated
  write surface — smaller and lower-risk than adding DID/CACAO
  verification to `$lib/server/aozora-pds/index.ts`.
- Adding a second work (`260123-jump`) or a second downstream platform
  later reuses the same actor skeleton / same `targets` extension point,
  respectively, without touching this actor's core.

## Alternatives considered

- Seed-data dump into `app-aozora`'s D1 (as already done, by hand, for one
  `260123-jump` arc under `did:web:ghosthacker.gftd.ai`): fastest to ship,
  but not self-sovereign (owner-authored, not actor-signed) and not
  reusable for a second work without repeating the manual export step.
  Rejected as the long-term path per explicit user direction ("not seed,
  the most beautiful path").
- DID-verified push into `app-aozora`: architecturally sound in the abstract
  (AT Protocol PDSs do accept authenticated writes), but this specific
  Worker's write path was built around a single static owner secret, so
  retrofitting it is a bigger, separate, security-sensitive project than
  extending the existing pull-based projector.
