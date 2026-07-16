# ADR-2607051900: network-isekai and net-babiniku — integration of twitch, youtube, osm broadcasting features

## Status

Accepted

## Context

Both `gftdcojp/network-isekai` and `gftdcojp/net-babiniku` are kami-engine-based products that render live 3D VRM avatars or dance stages, but with different use cases:

- **network-isekai**: An open, "CodePen for games" platform with VRM dance stages (`kami.dance`/`kami-live` vocabulary, `isekai.stage.cljc` + `isekai.vrm-bridge.cljc`). It has live presence features using BroadcastChannel or WebSocket for cross-device sync, and mentions of WebRTC mesh + spatial audio in the comments.

- **net-babiniku**: An AI VTuber/VRM companion chat product with a live-rendered 3D VRM avatar (VTuber presentation). It has a governor for content moderation and a render loop for VRM avatars, with private, per-user chat sessions.

Both products currently lack live broadcasting capabilities to external platforms like Twitch, YouTube Live, and OpenStreetMap (OSM) live streams or 3D spatial broadcasts. Users want to broadcast their network-isekai dance stages or net-babiniku VRM avatar chats to these platforms.

Existing YouTube upload functionality is already available in:
- `kotoba-lang/youtube-upload`: Python client for YouTube Data API v3 upload surface
- `kotoba-lang/com-youtube`: ClojureScript/Java client for YouTube Data API v3 upload surface (OAuth2 refresh-token exchange + resumable video upload, captions, thumbnails)

However, these are for **video upload**, not **live streaming**. Live streaming to Twitch, YouTube Live, and other platforms typically uses RTMP/RTMPS or SRT protocols to ingest streams to a streaming server, which then distributes to viewers.

## Decision

### 1. Broadcasting architecture: separate streaming service from rendering apps

Both `network-isekai` and `net-babiniku` will **not** implement direct RTMP/SRT/Webrtc streaming clients. Instead, they will:

1. **Export a WebRTC or WebSocket video stream** from the browser's WebGPU render loop
2. **Use a separate streaming service** (Cloudflare Stream, Mux, or a self-hosted live streaming server like OBS Studio + Nginx-RTMP or MediaSoup/Janus for WebRTC) to handle the RTMP/RTMPS/SRT/Webrtc ingestion and distribution

This follows the existing architecture pattern where:
- Rendering and game logic stay in the browser (kami-webgpu, WebGPU render loop)
- Backend services handle complex infrastructure (kotoba API, kotoba-db, etc.)
- No direct RTMP/SRT client implementation in the browser (browsers don't support RTMP/SRT natively)

### 2. WebRTC-based live presence and broadcasting

For `network-isekai`, the existing "live presence" feature in `isekai.stage.cljc` uses:
- `BroadcastChannel` by default (cross-tab/window sync on the same browser)
- WebSocket to a Cloudflare Durable Object room for cross-device sync (`?net=ws`)
- Comments mention "kami-rtc (WebRTC mesh + spatial audio) is the native transport for the same data"

This WebRTC foundation can be extended to support:
- **WebRTC broadcast**: Export the WebRTC stream to a WebRTC SFU (Selective Forwarding Unit) like Mediasoup, Janus, or LiveKit
- **WebRTC to RTMP/SRT bridge**: A separate service that takes WebRTC streams and converts them to RTMP/RTMPS/SRT for Twitch/YouTube Live

### 3. YouTube Live and Twitch integration via streaming URLs

For YouTube Live and Twitch broadcasting:

1. **User provides streaming URL and stream key**: Both platforms provide RTMP/RTMPS ingest URLs and stream keys for live broadcasting. These are typically in the format:
   - Twitch: `rtmps://live-api-s.twitch.tv/append/<stream-key>`
   - YouTube: `rtmp://a.rtmp.youtube.com/live2/<stream-key>`

2. **External streaming service handles RTMP/RTMPS ingestion**: A service like OBS Studio, Restream, or a custom RTMP server (Nginx-RTMP, SRS) receives the RTMP/RTMPS stream and distributes it to the platform.

3. **Browser exports a WebRTC or WebSocket video stream**: The browser's WebGPU render loop can export a video stream via:
   - `getDisplayMedia()` or `getUserMedia()` with a `CanvasCaptureMediaStreamTrack`
   - WebRTC `RTCPeerConnection` to a WebRTC SFU
   - WebSocket with a custom binary video format (e.g., H.264/NAL units or VP8/VP9 frames)

### 4. OSM (OpenStreetMap) live spatial broadcasting

For OSM-related broadcasting, this likely refers to:
- **Live 3D spatial data broadcasting**: Using protocols like 3D Tiles, Cesium Ion, or Mapbox GL JS to stream 3D spatial data
- **Live location tracking**: Broadcasting real-time location data to a map service
- **VRM avatar location in 3D space**: For network-isekai dance stages or net-babiniku avatars, this could mean mapping the avatar's 3D position to a spatial map

OSM itself doesn't have a "live streaming" protocol like Twitch or YouTube, but it does have:
- **Overpass API**: For querying live or near-live OSM data
- **OSM live data feeds**: Via services like Geofabrik or OSM Change Feed
- **3D spatial visualization**: Via Cesium, Mapbox, or MapLibre GL JS

For integration with network-isekai and net-babiniku, OSM live broadcasting would mean:
1. Exporting the 3D position/orientation of VRM avatars or dance stage elements
2. Mapping these to a 3D spatial visualization platform that supports OSM data
3. Using WebSockets or WebRTC to stream position data to a live map service

### 5. Implementation approach

#### For network-isekai:
1. **Extend `isekai.stage.cljc` live presence** to support WebRTC stream export
2. **Add a "Broadcast" button** to the dance stage UI that:
   - Captures the WebGPU canvas using `canvas.captureStream(30)`
   - Establishes a WebRTC connection using `RTCPeerConnection` with STUN servers
   - Sends the stream to the external service via WebRTC

#### For net-babiniku:
1. **Extend `babiniku.web` render loop** to support WebRTC stream export
2. **Add a "Live Stream" button** to the VTuber chat UI that:
   - Captures the VRM avatar render using `canvas.captureStream(30)`
   - Establishes a WebRTC connection using `RTCPeerConnection` with STUN servers
   - Sends the stream to the external service via WebRTC

#### For YouTube Live and Twitch:
1. **Provide a streaming guide** in the UI that explains how to:
   - Get an RTMP/RTMPS ingest URL and stream key from Twitch or YouTube Live
   - Use an external service (OBS Studio, Restream, or a custom RTMP server) to ingest the stream
2. **Integrate with a streaming service API** (optional, for advanced users):
   - Use Twitch's Helix API to manage live streams
   - Use YouTube Live Stream API to manage live broadcasts

#### For OSM spatial broadcasting:
1. **Export 3D position data** from the `kami.dance` or VRM render loop
2. **Map 3D positions to OSM coordinates** (latitude, longitude, altitude)
3. **Stream position data via WebSockets** to a live map service that supports OSM data

## Consequences

- (+) Both `network-isekai` and `net-babiniku` can support live broadcasting to Twitch, YouTube Live, and other RTMP/RTMPS/SRT-compatible platforms without implementing complex streaming clients in the browser.
- (+) WebRTC-based live presence and broadcasting can be extended from the existing `isekai.stage.cljc` live presence feature.
- (+) OSM spatial broadcasting can be supported by exporting 3D position data and mapping it to OSM coordinates.
- (-) Users will need an external streaming service (OBS Studio, Restream, or a custom RTMP server) to handle RTMP/RTMPS ingestion for Twitch and YouTube Live.
- (-) WebRTC stream export from WebGPU canvas requires browser support for `CanvasCaptureMediaStreamTrack` and WebRTC `RTCPeerConnection`.

## Alternatives Considered

1. **Implement RTMP/RTMPS client directly in ClojureScript**: Rejected — browsers don't support RTMP/RTMPS natively, and implementing a RTMP client in WebAssembly would be complex and redundant with existing streaming services.
2. **Use YouTube Data API v3 for live streaming**: Rejected — the YouTube Data API v3 is for video upload, captions, and thumbnails, not live streaming. YouTube Live uses a different RTMP/RTMPS ingest URL and stream key system.
3. **Integrate directly with Twitch Helix API for live streaming**: Partially accepted — the Twitch Helix API can be used to manage live streams (create, update, delete), but the actual video stream still needs to be sent via RTMP/RTMPS to the ingest URL provided by Twitch.

## References

- `90-docs/adr/2607032400-network-isekai-primary-consolidation.md`
- `90-docs/adr/2607041430-network-isekai-reagent-spa-consolidation.md`
- `90-docs/adr/2607051800-net-babiniku-vrm-vtuber-design.md`
- `gftdcojp/network-isekai`: `src/isekai/stage.cljc`, `src/isekai/vrm_bridge.cljc`
- `gftdcojp/net-babiniku`: `src/babiniku/web.cljs`, `src/babiniku/vrm-bridge.cljc`
- `kotoba-lang/youtube-upload`: Python client for YouTube Data API v3 upload surface
- `kotoba-lang/com-youtube`: ClojureScript/Java client for YouTube Data API v3 upload surface
- Twitch Streaming Documentation: https://dev.twitch.tv/docs/live/streaming/
- YouTube Live Streaming Documentation: https://support.google.com/youtube/answer/2853704
- WebRTC and Canvas Capture: https://developer.mozilla.org/en-US/docs/Web/API/CanvasCaptureMediaStreamTrack