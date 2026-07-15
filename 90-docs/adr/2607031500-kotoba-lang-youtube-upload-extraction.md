# ADR-2607031500: kotoba-lang/youtube-upload — extract yukkuri's YouTube client into a shared lib

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

`ai-gftd-project-yukkuri`'s `lg_yukkuri/graphs/upload_youtube.py` has working,
production-tested logic for the YouTube Data API v3 upload surface: OAuth2
refresh-token exchange, resumable `videos.insert`, `captions.insert`,
`thumbnails.set`, and per-channel credential isolation (to prevent one
channel's video cross-posting to another — a real 2026-06 incident). This
logic is entangled with yukkuri's D1 store and channel-resolution code, so no
other video-producing actor in this ecosystem can reuse it without copying
the HTTP-calling code wholesale.

Separately, yukkuri's `publisher` actor is being generalized to support
multiple publish destinations (aozora.app alongside YouTube — see the paired
ADR `yukkuri-multi-destination-publisher`), which is a natural point to pull
the YouTube-specific HTTP logic out into its own dependency rather than
duplicating it inside a new abstraction layer.

**Language choice.** `kotoba-lang` repos are conventionally Clojure/
ClojureScript `.cljc` (e.g. `cron`, `authenticator`, `gijiroku`, `cacao`).
This extraction is Python instead — a deliberate exception. The logic it
wraps runs inside yukkuri's Python LangGraph k8s pod; a `.cljc` port would
force every current and future Python caller through an RPC/subprocess
bridge to a JVM/nbb process for a straightforward stateless HTTP client, which
is disproportionate ceremony for what the code actually does. If a Clojure
consumer needs this later, the right move is a thin wrapper (subprocess or a
small sidecar), not a rewrite of the HTTP logic itself.

## Decision

1. New repo `kotoba-lang/youtube-upload` (public, matches org default for
   kotoba-lang), containing four pure async functions with no D1/yukkuri
   dependency: `refresh_access_token`, `upload_video`, `upload_caption`,
   `set_thumbnail`. Every non-2xx response raises `YouTubeUploadError(stage,
   status_code, detail)` — callers decide fatal vs. non-fatal per call
   (yukkuri's existing pattern: a failed thumbnail set doesn't block a
   successful video upload).
2. Credential resolution (per-channel token isolation), video-byte fetching
   (from B2/D1), and result persistence stay in yukkuri — this library only
   knows how to talk to Google's endpoints, so it has no opinion about where
   credentials or content come from.
3. `ai-gftd-project-yukkuri`'s `upload_youtube.py` is refactored to depend on
   this library for its HTTP calls (see the paired multi-destination ADR for
   how it's wired into the new `publish_destinations` graph).
4. Registered via the standard `manifest/repos.edn` `:extra-projects` →
   `nbb scripts/gen-west-manifest.cljs --entry` workflow.

## Consequences

- (+) Any future video-producing actor (mangaka, animeka, itonami games, …)
  can `pip install` this instead of re-deriving the resumable-upload dance.
- (+) Unit-testable in isolation (`httpx.MockTransport`) without spinning up
  yukkuri's D1/LangGraph stack — 10 tests cover the OAuth refresh, resumable
  upload happy/failure paths, captions, and thumbnails.
- (−) Breaks the `.cljc`-only convention for `kotoba-lang/*`. Documented here
  as a scoped, deliberate exception rather than a silent drift.
- (−) Two copies of "how resumable upload works" exist during the yukkuri
  refactor transition (old inline code vs. new library call) until the
  `upload_youtube.py` rewrite lands and is verified — mitigated by doing the
  library extraction and the yukkuri-side wiring in the same session.

## Alternatives Considered

- **Leave it inline in yukkuri, duplicate on demand.** Rejected — this is
  exactly the copy-paste-drift pattern that produced the 2026-06 cross-
  posting incident (two near-duplicate upload implementations already exist
  in yukkuri: `publish_youtube.py` legacy and `upload_youtube.py` current).
- **Port to `.cljc` immediately to match convention.** Rejected for now —
  disproportionate to the actual ask (yukkuri needs a working Python import
  today); revisit if/when a Clojure caller actually needs it.

## References

- `ai-gftd-project-yukkuri/lg/lg_yukkuri/graphs/upload_youtube.py` (source of
  the extracted logic)
- Paired ADR: `yukkuri-multi-destination-publisher` (aozora + YouTube as
  destinations)
- `manifest/repos.edn` `:manifest-workflow` (registration process)
- 本 ADR とペアの `.edn`
