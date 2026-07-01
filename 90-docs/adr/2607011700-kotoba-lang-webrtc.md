# ADR-2607011700: `kotoba-lang/webrtc` — WebRTC signaling/session as pure data contracts

Status: Accepted
Date: 2026-07-01

## Context

There is no video-call / WebRTC library anywhere in the tree. The closest
relatives are `kotoba-lang/phone` (E.164/SIP/CDR/SMS records) and
`kotoba-lang/koe` (a voice-session kernel of ports + a dialog loop, every
concrete capability injected by the host) — neither covers video/media
negotiation.

Per ADR-2606302300's repo-creation-time placement check (amendment
2026-07-01), step 1 (layer test) asks whether a candidate is a pure
technical layer: `.cljc`, zero network I/O, zero vendor SDK, records/
protocol/data-model only. A library that only *models* WebRTC signaling
(SDP/ICE/session-state/room-membership) as EDN — the same "records, not
wire format" approach `kotoba-lang/card` and `kotoba-lang/swift` already
take for ISO 8583 and SWIFT MT — passes that test and belongs in
`kotoba-lang`. An actual signaling server, TURN/STUN deployment, or browser
`RTCPeerConnection`/`getUserMedia` integration would fail step 1 and require
the step-2 3-axis/Charter Rider classification (gftdcojp vs etzhayyim)
instead; that is explicitly **out of scope** here.

## Decision

Add `kotoba-lang/webrtc`: a `.cljc` capability library modeling WebRTC
signaling and session state as EDN, no network/media I/O, no codec, no
transport — mirroring the `card`/`swift`/`phone` house style.

- `kotoba.webrtc` — ICE candidate records (RFC 8445: foundation, component,
  transport, priority, address, port, candidate-type, related address/port)
  and SDP session/media-description records (RFC 8866-shaped: origin,
  media kind, direction, codecs, mid, ICE ufrag/pwd, DTLS fingerprint) with
  validators. Records, not the wire format — same rationale as `card`/`swift`.
- `kotoba.webrtc.session` — a pure signaling negotiation reducer: states
  (`:idle` → `:have-local-offer` / `:have-remote-offer` → `:connecting` →
  `:connected` → `:disconnected` / `:failed` / `:ended`, mirroring
  `RTCPeerConnection.signalingState`/`connectionState`) and an
  `apply-event` function that takes a session + event and returns the next
  session plus a list of *effects* (`:send-offer`, `:send-answer`,
  `:send-ice-candidate`, …) for the host to actually perform — the same
  "host injects every concrete capability" pattern as `koe`'s dialog loop,
  applied to call signaling instead of voice dialog.
- `kotoba.webrtc.room` — multi-party room membership (participants, tracks,
  join/leave) as pure functions over an EDN room record, for SFU-style
  multi-party calls.
- `kotoba.webrtc.stats` — `RTCStats`-shaped quality records (packets lost,
  jitter, round-trip time) with a pure quality classifier
  (`:good`/`:degraded`/`:poor`) for observability.
- `kotoba.webrtc.ui` / `kotoba.webrtc.export` — read-only operator
  dashboard (`kotoba-lang/html` + `kotoba-lang/css`) and CSV/JSON export,
  matching the `phone`/`banking`/`card`/`swift` house pattern.

## Consequences

- Fills the "video call" gap identified for `kotoba-lang` without expanding
  scope into an actual signaling server, TURN/STUN deployment, or browser
  integration — those remain separate, future decisions subject to
  ADR-2606302300 step 2. The actual media/transport pipeline is out of
  scope here by design.
- A host runtime (browser ClojureScript app, or a future gftdcojp/etzhayyim
  video-call actor) can consume `kotoba-lang/webrtc` for negotiation-state
  and room-membership logic while supplying its own
  `RTCPeerConnection`/`getUserMedia`/signaling-transport bindings — same
  separation of concerns as `koe`.
- `deps.edn` depends on `kotoba-lang/html` + `kotoba-lang/css` (dashboard),
  `:test`/`:lint` aliases match the house convention (cognitect test-runner
  + clj-kondo).
