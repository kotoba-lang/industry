# ADR-2607043000: `kotoba-lang/com-youtube` + `kotoba-lang/jp-hiroshiba-voicevox` — real HTTP clients for yukkuri's plan-only ports

## Status

Accepted.

## Context

`ai-gftd-project-yukkuri`'s recent Python→`.cljc` migration (ADR/PR
`gftdcojp/ai-gftd-yukkuri#4`) ported all 27 pipeline graphs as **pure,
no-external-IO plan builders** (`clj/README.md`'s stated design: "intentionally
performs no external D1, YouTube, browser, LLM, VOICEVOX, B2, or ffmpeg
calls"). Two of those ports specifically need a real HTTP execution
counterpart to become actually useful:

- `yukkuri.graphs.upload-youtube`/`publish-youtube`/`update-youtube-metadata`
  build YouTube Data API v3 request bodies (OAuth-refresh plan, videos.insert
  metadata, captions/thumbnails plans) but never call Google's servers.
- `yukkuri.voicevox` builds VOICEVOX synthesis job descriptions (style_id
  resolution, emotion switching, credit-line rendering) but never calls a
  VOICEVOX engine.

Two existing repos are adjacent but not directly reusable as-is:

- `kotoba-lang/youtube-upload` already extracts a real, working Python
  `httpx` client from yukkuri's original `upload_youtube.py` — but its own
  README explicitly recommends wrapping it (RPC/subprocess bridge) rather
  than rewriting in Clojure, since kotoba-lang's `.cljc` convention would
  otherwise force every `.cljc` consumer through a cross-process bridge.
- No VOICEVOX client existed anywhere in the tree; the closest prior art is
  a one-off local script (`orgs/com-junkawasaki/yukkuri-assets-nist-csf-cis-scs/
  scripts/synthesize.py`) with a hand-copied emotion→style_id table.

By explicit owner request, this ADR does the Clojure rewrite anyway (rather
than the recommended wrap), so `ai-gftd-yukkuri`'s new `.cljc` graphs have
same-ecosystem real clients instead of bridging into Python subprocesses.

## Decision

Create two new public `kotoba-lang` repos, following existing naming
conventions (`com-<vendor>` reverse-domain for company APIs — see
`kotoba-lang/com-cloudflare`; a new `jp-<author>-<project>` convention for
individually authored Japanese OSS, since VOICEVOX has no corporate domain
to reverse):

- **`kotoba-lang/com-youtube`** — `youtube.client` (OAuth2 refresh + injectable
  `java.net.http` transport) / `youtube.videos` (resumable `videos.insert`
  init+PUT, `videos.update`) / `youtube.captions` (multipart `captions.insert`)
  / `youtube.thumbnails` (`thumbnails.set`). Re-derives the exact request/
  response shapes (header names, status checks, multipart boundary format)
  from `kotoba-lang/youtube-upload`'s proven Python client rather than
  reinventing the API. 8 tests / 35 assertions, all against a stub `:http-fn`
  (never a live account).
- **`kotoba-lang/jp-hiroshiba-voicevox`** — `voicevox.client` (`audio_query!`/
  `synthesis!`/`version!`, injectable transport) / `voicevox.speakers`
  (style_id catalog, emotion→style table, `resolve-style-id`, credit-line
  rendering — ported from `yukkuri.voicevox`'s already-correct catalog) /
  `voicevox.synthesize` (`synthesize!`: text→WAV bytes, combining the two).
  6 tests / 28 assertions, all against a stub `:http-fn`.

Both follow the same design as `kotoba-lang/com-cloudflare`: pure `.cljc`
request/response shaping, one injectable `:http-fn` (`{:url :method :headers
:body} -> {:status ...}`) for the real JVM `java.net.http` transport, MIT
license, no credentials held or defaulted by the library (callers always
pass tokens/keys explicitly).

Neither is wired into `ai-gftd-yukkuri` yet — that's a separate follow-up
(the plan-only graphs need an execution layer added that calls these new
clients with real credentials/bytes, which the owner has not yet supplied).

## Consequences

- (+) `ai-gftd-yukkuri` (and any future kotoba-lang/gftdcojp project needing
  YouTube upload or VOICEVOX synthesis) has one tested, reusable real client
  for each, instead of re-deriving the HTTP boilerplate or bridging into
  Python.
- (+) `jp-hiroshiba-voicevox` establishes the `jp-<author>-<project>` naming
  convention for future individually authored Japanese OSS client libraries.
- (−) `kotoba-lang/youtube-upload` (Python) now has a parallel Clojure
  reimplementation with independent maintenance — its own README's
  "wrap, don't rewrite" advice was explicitly overridden here.
- (−) Still requires: a live, reachable VOICEVOX engine (none exists today —
  the 2026-05-31 k8s `voicevox-engine` Helm deploy is confirmed dead,
  ADR-2607031540) and real YouTube OAuth credentials (operator-provided,
  per `ai-gftd-yukkuri/docs/youtube-upload-setup.md`) before either library
  can be exercised against production. Wiring `ai-gftd-yukkuri`'s graphs to
  actually call these clients is a separate follow-up.

## Artifacts

- `kotoba-lang/com-youtube` (public, MIT, pure `.cljc`, 8 tests/35 assertions green)
- `kotoba-lang/jp-hiroshiba-voicevox` (public, MIT, pure `.cljc`, 6 tests/28 assertions green)
- `manifest/repos.edn` `:extra-projects` entries for both (this commit)

## References

- `gftdcojp/ai-gftd-yukkuri` PR #4 (py→cljc migration this ADR follows up on)
- `kotoba-lang/youtube-upload` (Python client this rewrites, against its own advice)
- `kotoba-lang/com-cloudflare` (the injectable-`:http-fn` template both new repos follow)
- ADR-2607031540 (yukkuri render path → cloud-murakumo; VOICEVOX dead-cluster finding)
