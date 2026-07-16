# ADR-2607070100: kotoba-lang/com-reddit — model Reddit's real domain instead of the generic placeholder

**Status**: accepted
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/com-reddit`

## Context

`kotoba-lang/com-reddit` already existed — relocated 2026-07-05 from
`etzhayyim/root/20-actors/reddit-compat` per ADR-2607041500's mechanical
~1,027-repo migration (any library/substrate code belongs in `kotoba-lang`).
That migration was purely structural: it moved files and fixed `deps.edn`,
it did not touch domain content. As a result `com-reddit`'s schema/actor
modeled `Channel, Message, User, Room, Stream, Webhook` — a generic
chat/streaming-platform template with **zero Reddit-specific semantics**
(no Subreddit, Post, Comment, Vote, or OAuth concept), and `manifest.json`
had `"verified": null`, confirming it was never hand-checked against
Reddit's real API.

This surfaced while preparing Reddit as a zero-cost distribution channel for
cloud-murakumo(Sora)'s GTM (r/LocalLLaMA, r/selfhosted, r/homelab —
`90-docs/business/cloud-murakumo-gtm-country-plan.md`). The owner asked for
`com-reddit` to become a real "clean-room API-compat actor" matching the
heavier pattern already established by `kotoba-lang/com-stripe` /
`kotoba-lang/com-anthropic` (Datomic schema + openapi.json + manifest.json +
kotoba-WASM metadata), not a thin API-client wrapper (the lighter
`org-anthropic-mcp` shape was the other option considered and rejected).

## Decision

Replace the six generic placeholder entities with Reddit's actual domain,
keeping every structural/mechanical file (`deps.edn`, `nbb.edn`, file layout,
generic CRUD handlers) exactly as the `com-stripe`/`com-anthropic` pattern
prescribes — only the domain content changes:

- **`Subreddit`**: `name`, `title`, `description`, `subscriberCount`, `over18`.
- **`User`**: `username`, `karma`, `verified`.
- **`Post`**: `subredditId` → Subreddit, `authorId` → User, `title`,
  `selftext`, `url`, `score`, `numComments`, `isSelf`.
- **`Comment`**: `postId` → Post, `parentId` → **Comment** (self-reference,
  for nested replies), `authorId` → User, `body`, `score`.
- **`Vote`**: `userId` → User, `targetType`, `targetId`, `direction`. Reddit
  models voting as an action (`POST /api/vote`), not a stored resource, but
  this actor family needs every entity to carry the same CRUD/audit surface,
  so `Vote` is modeled as a first-class entity anyway.

**Id prefixes follow Reddit's own `fullname` convention** where one exists
(`t5_`=subreddit, `t2_`=account, `t3_`=link, `t1_`=comment — per
[Reddit's API docs](https://www.reddit.com/dev/api#fullnames)) instead of
the generic `<entity>_<hex>` scheme the placeholder used — a small but real
authenticity detail for a "clean-room API-compatible" claim. `Vote` has no
real Reddit `thing` type, so it keeps a made-up prefix (`reddit_vot`).

Reddit's real OAuth2 token endpoint (`POST /api/v1/access_token`,
script/installed-app flow) is **documented but deliberately not modeled as a
route** — it isn't a CRUD resource, and modeling it as a route would break
the `routes = 5 × entity-specs` invariant this actor family's
`route-surface` test asserts. Noted in both the README and a code comment
instead.

`openapi.json`/`manifest.json` were regenerated (via a one-off, uncommitted
local script — not part of the repo) to exactly match the original
generator's mechanical shape: same per-entity CRUD paths/schemas, same MCP
tool list shape, same `capabilities.supplychain`/`socialpost` boilerplate.
All generic handlers (`handle-create/list/get/update/delete`, `paginate`,
`apply-filters`, `expand`, coercion, validation) were untouched — they
already folded purely over `entity-specs`, so a schema swap needed zero
handler changes. `test/reddit/main_test.cljc` was likewise untouched — it's
schema-driven (`dummy`/`full-record` synthesize valid data from
`:required`/`:coerce`), so it exercises the new entities with no per-entity
test code.

## Consequences

- `com-reddit` is now an honest reflection of its name — a Reddit-shaped
  clean-room actor, not a relabeled chat-platform template with
  `"verified": null`.
- Kept fully consistent with the `com-stripe`/`com-anthropic` mechanical
  pattern (same file layout, same generic-handler architecture, same
  metadata shape) rather than diverging into a bespoke thin-client design —
  future `com-*` migrations in this family stay predictable.
- The WASM/Datomic/IPFS execution story remains aspirational metadata (as it
  already was for `com-stripe`/`com-anthropic` — no actual WASM binary or
  Datomic connection exists in any of these repos yet); this ADR does not
  change that.
- Not in scope: actually calling Reddit's real API to post the GTM content
  (a separate, manual step — see `docs/gtm-launch-runbook.md` in
  `gftdcojp/cloud-murakumo`).

## Verification Notes

2026-07-06: `nbb test` and JVM `clojure -M -e "(require 'reddit.main-test)
(reddit.main-test/-main)"` both pass — 5 tests, 77 assertions, 0 failures,
0 errors. `openapi.json`/`manifest.json` validated as well-formed JSON.
