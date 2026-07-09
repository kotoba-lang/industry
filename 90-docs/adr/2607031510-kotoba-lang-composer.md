# ADR-2607031510: `kotoba-lang/composer` — AI music composition request/pipeline as pure data contracts

Status: Accepted, core contract implemented (2026-07-07)

Date: 2026-07-03

## Context

`ai-gftd-yukkuri` (60-apps/ai-gftd-project-yukkuri, mirrored at
`orgs/gftdcojp/ai-gftd-yukkuri`) defines a `composer` actor
(`did:web:yukkuri.gftd.ai:actor:composer`) whose job is BGM generation via a
cross-project invoke of `ai.gftd.ongakuka.compose`. That logic today lives
inline inside the yukkuri pipeline: any other video/audio-producing actor
(the anime/truecrime/pachinko yukkuri channels already share this need; a
future non-yukkuri actor wanting a soundtrack would have the same need) has
to re-derive the `ai.gftd.ongakuka.*` lexicon shapes from scratch — there is
no shared, reusable model of "what does a composition request/track/stem
look like" outside of the raw lexicon JSON
(`00-contracts/lexicons/ai/gftd/ongakuka/{compose,track,stem,style,
generation,regenerate}.json`).

This mirrors the gap that motivated `kotoba-lang/youtube-upload` ("extracted
from `ai-gftd-project-yukkuri`'s `upload_youtube.py` so other
video-producing actors can reuse it") — except `youtube-upload` was a
**live third-party API client** (OAuth + HTTP to YouTube Data API v3),
accepted as a deliberate `.cljc`-convention exception because there is no
way to model a third-party wire protocol as pure data without also driving
the HTTP calls.

`ongakuka.compose` is different: it is an **in-house, already-lexicon'd**
cross-project XRPC surface. Per the repo-creation-time placement check
(ADR-2606302300 step 1, as applied in `kotoba-lang/webrtc`,
ADR-2607011700): a candidate that only *models* the request/response/status
shapes as EDN records + a pure state-transition reducer — zero network I/O,
zero vendor SDK — passes the layer test and belongs in `kotoba-lang`. The
actual XRPC call (network I/O, auth, retries) stays with the app-level actor
that owns the deployment, exactly as `kotoba-lang/webrtc` models signaling
negotiation as a pure reducer while leaving `RTCPeerConnection` /
transport to the host runtime.

**Naming note (avoid confusion):** `ai.gftd.ongakuka.compose`'s own internal
pipeline is documented as `lyricist→composer→vocalist‖arranger→mixer→critic`
— i.e. ongakuka already has an internal stage *called* "composer" (the
melody/arrangement-skeleton stage). `kotoba-lang/composer` is **not** a
model of that one internal stage; it models the **outer** composition
request/track/stem/generation contract that any external caller (yukkuri or
otherwise) uses to talk to ongakuka as a whole. Same category of
same-word-different-referent trap as `provisioning` (IAM) vs `provisions`
(contract clause) covered in the NIST CSF glossary work this session — flagged
explicitly so a future reader doesn't conflate "the kotoba-lang lib" with
"ongakuka's internal composer stage."

## Decision

Add `kotoba-lang/composer`: a `.cljc` capability library modeling AI music
composition requests, tracks, stems, style references, and generation
events as EDN records with validators, plus a pure pipeline-status reducer
— mirroring the `ai.gftd.ongakuka.*` lexicon shapes 1:1 so any consumer
constructs valid requests / interprets responses without re-deriving the
lexicon, and mirroring the `kotoba.webrtc.session` "host injects every
concrete capability" split for the actual network call.

- `kotoba.composer` — compose-request record: `title` / `lyrics` (required)
  / `style` (required) / `style-ref-uri` / `language` / `bpm` /
  `duration-sec` (5–600, default 90) / `model-id` / `seed` / `stems?`
  (boolean), with a validator mirroring `ai.gftd.ongakuka.compose`'s input
  schema exactly.
- `kotoba.composer.track` — track record: `title` / `lyrics` / `style` /
  `style-ref-uri` / `language` (BCP-47) / `bpm` (30–300) / `duration-sec`
  (5–600) / `status` / `blob-key` / `mime-type` / `project-id` / `model-id`
  / `seed` / `created-at`, mirroring `ai.gftd.ongakuka.track`.
- `kotoba.composer.stem` — stem record: `track-uri` / `kind`
  (`:vocal`/`:chorus`/`:drums`/`:bass`/`:guitar`/`:keys`/`:synth`/
  `:strings`/`:fx`/`:other`/`:instrumental`/`:fullmix`) / `blob-key` /
  `mime-type` / `duration-sec` / `loudness-lufs` / `actor-did` /
  `created-at`, mirroring `ai.gftd.ongakuka.stem`.
- `kotoba.composer.style` — style-reference record: `name` / `kind`
  (`:prompt`/`:embedding`) / `prompt` / `embedding-blob-key` /
  `embedding-dim` / `embedding-model` / `license`
  (`:permissive`/`:own`/`:licensed`/`:unknown`), with a validator that
  **rejects publishing an `:unknown`-license embedding as a record** — the
  same copyright invariant already stated in `ai.gftd.ongakuka.style`'s
  lexicon description and in yukkuri's CLAUDE.md copyright-invariants
  section (BGM/SFX must clear a license gate).
- `kotoba.composer.generation` — generation/audit record: `target-uri` /
  `stage` (`:lyric`/`:compose`/`:vocal`/`:arrange`/`:mix`/`:review`/
  `:regenerate`) / `actor-did` / `model-id` / `params` / `prompt-tokens` /
  `completion-tokens` / `audio-sec` / `inference-ms` / `credits-consumer` /
  `credits-operator` / `node` / `status` (`:ok`/`:rejected`/`:failed`) /
  `reject-reason` / `created-at`, mirroring `ai.gftd.ongakuka.generation` —
  a consistent audit/metering shape any composing actor can emit into.
- `kotoba.composer.pipeline` — pure status-transition reducer over
  `kotoba.composer.track`: states `:queued → :lyric → :compose → :vocal →
  :mix → :review → :published | :rejected | :failed` (mirroring
  `ai.gftd.ongakuka.track`'s `status` enum, and matching ongakuka's own
  internal `lyricist→composer→vocalist‖arranger→mixer→critic` staging). An
  `apply-event : track × event → {:track next-track :effects [...]}`
  function, effects being `:request-lyrics` / `:request-composition` /
  `:request-vocal` / `:request-arrangement` / `:request-mix` /
  `:request-review` for the host to actually dispatch (the host owns the
  `ai.gftd.ongakuka.compose`/`regenerate` XRPC call — same separation of
  concerns as `kotoba.webrtc.session`).
- `kotoba.composer.ui` / `kotoba.composer.export` — optional read-only
  operator dashboard (`kotoba-lang/html` + `kotoba-lang/css`) and CSV/JSON
  export, matching the `phone`/`banking`/`card`/`swift`/`webrtc` house
  pattern. Deferred to a follow-up wave if not needed immediately (same
  P0/P1/P2 staging as the foundational-stdlib rollout).

## Consequences

- Any composing/video-producing actor (yukkuri's cyber/anime/truecrime/
  pachinko channels today; any future gftdcojp/etzhayyim actor that wants a
  soundtrack) depends on `kotoba-lang/composer` to construct valid
  `ai.gftd.ongakuka.compose` requests and interpret `track`/`stem`/
  `generation` responses, instead of re-deriving the ongakuka lexicon by
  hand in each actor.
- The actual XRPC call to `ai.gftd.ongakuka.compose`/`regenerate` (network
  I/O, auth, retry/backoff) stays in the app-level actor that owns the
  deployment (yukkuri's `did:web:yukkuri.gftd.ai:actor:composer`) — this
  library does not perform the cross-project invoke itself, matching the
  `kotoba-lang/webrtc` precedent (host injects `RTCPeerConnection`
  equivalent).
- If `ongakuka`'s lexicon changes shape, `kotoba-lang/composer`'s records
  need a matching update — the library is intentionally schema-coupled
  (mirrors, does not reinvent, the lexicon) rather than a generic
  "any-audio-pipeline" abstraction, to avoid speculative generality.
- `deps.edn`: no cross-`kotoba-lang` deps for the core namespaces (pure data
  + reducer); `kotoba.composer.ui` depends on `kotoba-lang/html` +
  `kotoba-lang/css` if that namespace is included. `:test`/`:lint` aliases
  match house convention (cognitect test-runner + clj-kondo).
- Does not touch yukkuri's live `composer` actor wiring — adopting the
  library into yukkuri (replacing its inline request-building with
  `kotoba.composer`'s validated constructors) is a separate follow-up, not
  bundled into this ADR.

## Addendum (2026-07-07): core contract implemented

`kotoba-lang/composer` created and registered (`manifest/repos.edn
:extra-projects`, west pin verified server-side). Implemented: `kotoba.composer`
(compose-request), `.track`/`.stem`/`.style`/`.generation` (response-side
records mirroring the corresponding lexicons exactly), `.pipeline`
(`apply-event : track × event → {:track :effects}`, mirroring
`kotoba.webrtc.session`'s shape — `:vocal` status requests both
`:request-vocal` and `:request-arrangement`, matching ongakuka's own
parallel `vocalist‖arranger` stage). `kotoba.composer.style` enforces the
copyright invariant as designed: `publishable?` rejects an `:embedding`-kind
style whose license is `:unknown` **or absent** (never defaults to
permitted) — a `:prompt`-kind style has no license gate.

22 tests / 140 assertions, 0 failures; clj-kondo 0 warnings; CI green.
`kotoba.composer.ui`/`.export` (optional operator dashboard/CSV export) and
adopting the library into yukkuri's live actor wiring remain deferred
follow-ups, per this ADR's own P0/P1/P2 staging and Consequences section.
