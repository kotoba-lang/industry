# ADR-2607051600: yukkuri real-production stack goes live — murakumo ComfyUI gateway, VOICEVOX engine, first real NIST video

## Status

Accepted.

## Context

Following ADR-2607043000 (`kotoba-lang/com-youtube` + `kotoba-lang/jp-hiroshiba-voicevox`,
real HTTP clients for `ai-gftd-yukkuri`'s plan-only pipeline graphs), this session took
the stack from "clients exist, nothing live" to "a real NIST explainer video was
actually produced end-to-end" — on `main-2.local`, the murakumo fleet's own
operator/control machine.

## Decision

### 1. `kotoba-lang/murakumo` — ComfyUI now reachable over HTTP, not SSH-loopback-only

Four PRs landed on `kotoba-lang/murakumo`:

- **PR #7**: `murakumo.infer.gateway` — `POST /v1/images/generations`
  (OpenAI-images-compatible, matches `kotoba-lang/comfyui`'s
  `comfyui.gateway/render-via-gateway` contract) backed by the existing
  SSH-loopback `murakumo.infer.media/run-job!` dispatch.
- **PR #8**: `POST /workflows/run` — generic ComfyUI API-format workflow
  passthrough (`murakumo.infer.media/run-custom-workflow!`), for callers with
  their own custom node graphs (`yukkuri.comfy`'s background/character/
  scene-composite builders) that the simple txt2img shape can't express.
- **PR #9** (bug found via a live end-to-end test): `generate-image!`/
  `pick-any-node!` passed a `nil` `:model/checkpoint` to `schedule/pick`
  whenever the caller omitted `:model` — `schedule/eligible?` treats `nil`
  as "any node running this engine is eligible", so a plain image request
  could land on a video-only node (LTX-Video / SVD img2vid checkpoint)
  instead of an actual txt2img model. Both endpoints now default to a real
  checkpoint name (`animagine-xl-4.0.safetensors`, overridable via
  `MURAKUMO_DEFAULT_IMAGE_CKPT`).
- **PR #10**: `deploy/com.murakumo.infer-gateway.plist.tmpl` — a per-user
  macOS LaunchAgent keeping the gateway resident on the operator machine
  (survives logout/reboot, self-heals on crash). Installed and verified live
  on `main-2.local`; needed an explicit `PATH` in `EnvironmentVariables`
  since a LaunchAgent's default environment has none of an interactive
  shell's (`tailscale` is invoked by bare command name in
  `murakumo.fleet/tailscale-status`).

Confirmed live: 5-6 of the fleet's 10 Mac-mini nodes online at any given
time; `zebulun`/`benjamin` hold `animagine-xl-4.0.safetensors` (real SDXL
txt2img); real end-to-end renders average **~5-8 minutes/image** on this
Apple Silicon hardware (no dedicated GPU — CPU/Metal inference). The
gateway is reachable both via `localhost` and the operator machine's
Tailscale IP (`100.108.223.94:8790`) — no additional tunnel needed for any
consumer already on the tailnet; a *public*-internet-reachable path
(Cloudflare Tunnel) was deliberately NOT set up this session (a separate,
larger exposure decision).

### 2. VOICEVOX Engine 0.25.2 — deployed for real, not just a client library

`jp-hiroshiba-voicevox` (ADR-2607043000) had a real client but nothing to
call. This session downloaded the official `VOICEVOX/voicevox_engine`
0.25.2 macOS-arm64 native release (`gh release download`, ~1.8 GB `.7z`,
extracted with `7z`) to `~/.murakumo/voicevox-engine/run`, bound to
`127.0.0.1:50021`, and persisted it as another per-user LaunchAgent
(`com.murakumo.voicevox-engine`, same pattern as the ComfyUI gateway).
Verified end-to-end: `jp-hiroshiba-voicevox`'s `voicevox.synthesize/synthesize!`
(fetched fresh via its `:git/sha` coordinate, not a local checkout) produced
real WAV audio; `ai-gftd-yukkuri`'s `yukkuri.exec.synthesize-voice` (PR #5,
also fetched fresh via git dep) correctly resolved Shiro (style_id=2,
四国めたん) / Pico (style_id=3, ずんだもん) per-line style_ids through
`yukkuri.channels`'s real channel identity and produced real audio for both.

### 3. First real NIST video produced: `cyber-ep-055`

Using the already-scripted `content/cyber-nist-csf-yougo.edn` series (26
episodes, landed via `ai-gftd-yukkuri` PR #3), episode 1
("「アサーション」を"主張"と訳すと事故る話【NIST CSF PR.AA-04】", 6 scenes,
26 lines) was produced through a one-off local pipeline script (not yet a
committed graph executor — see Follow-ups):

1. All 26 lines synthesized via the live VOICEVOX engine (emotion-aware
   style_id switching: 四国めたん/ずんだもん base + happy/surprised/serious
   variants).
2. 6 scene background images generated via the murakumo ComfyUI gateway
   (`animagine-xl-4.0`, 1280x720), 2 concurrent across `zebulun`/`benjamin`,
   ~25 min wall-clock total.
3. Per-line captions burned onto each scene's background via Pillow (this
   machine's Homebrew `ffmpeg` build has no `--enable-libfreetype`/`libass`
   — `drawtext`/`subtitles` filters are unavailable, confirmed via
   `ffmpeg -filters`; PIL was the workaround, not a `.cljc`/repo change).
4. `ffmpeg` assembled a per-line-duration image-sequence + the concatenated
   scene audio into 6 scene `.mp4`s, then concatenated all 6 into the final
   video.

Output: `~/Movies/yukkuri-nist-output/cyber-ep-055-assertion.mp4`
(1280x720, ~5m03s, H.264/AAC, 5.0 MB).

## Consequences

- (+) The two blockers ADR-2607043000 named ("no live VOICEVOX engine
  anywhere", "no live GPU backend") are both resolved for real, locally, on
  `main-2.local` — not simulated, not stubbed.
- (+) A real, complete (if visually simplified) NIST CSF explainer video
  exists as concrete proof the whole chain works: script → voice → image →
  assembly.
- (−) **No character overlay yet.** The video is background+caption only
  (no Shiro/Pico standing figures). A same-session attempt to generate
  character sheets via `yukkuri.comfy/character-plan` timed out (>10 min,
  no output produced) — root cause identified after the fact:
  `character-workflow`'s default checkpoint (`waiIllustriousSDXL_v160.safetensors`)
  doesn't match what's actually on the fleet (`animagine-xl-4.0.safetensors`);
  the ad-hoc test script didn't override `:ckpt`, so the submitted workflow
  likely referenced a nonexistent checkpoint file. Fix for next attempt:
  always pass `:ckpt "animagine-xl-4.0.safetensors"` (or query
  `murakumo.infer.media/checkpoints` first) when calling `character-plan`/
  `character-emotion-plan` against this fleet. Separately,
  `character-emotion-workflow`'s IP-Adapter nodes (`IPAdapterUnifiedLoader`/
  `IPAdapter`) are custom ComfyUI nodes not confirmed installed on the
  fleet's plain ComfyUI — `character-workflow` (no IP-Adapter dependency)
  is the safer starting point.
- (−) **No BGM yet.** No music-diffusion checkpoint is present on any
  fleet node today (`nbb murakumo infer media nodes` only shows
  image/video/img2vid checkpoints); `yukkuri.graphs.generate-bgm`'s
  `compose-request` (ongakuka XRPC) was not exercised, and its
  `local-synth-plan` is parameters-only (no real audio synthesis
  implemented anywhere yet). A real BGM pass needs either a local
  synthesis fallback (e.g., a simple ffmpeg/numpy-generated ambient loop)
  or standing up ongakuka reachability.
- (−) Background image quality/relevance was flagged by the owner as not
  fully satisfying — `animagine-xl-4.0` is an anime-*character* checkpoint;
  it renders single-object/character scenes well (the wax-sealed envelope
  came out well) but is not tuned for the abstract multi-element
  "infographic diagram" compositions several scenes' `:scene/visual`
  descriptions call for. Worth exploring: more detailed/constrained
  prompting, a different checkpoint better suited to flat infographic
  style, or moving diagram-heavy scenes to the SVG-based path
  `yukkuri.graphs.generate-visual` already supports (LLM-authored SVG,
  no diffusion model needed) instead of ComfyUI photorealistic/anime
  rendering.
- (−) The one-off production script (voice synthesis, image generation,
  PIL captioning, ffmpeg assembly) lives only in this session's scratchpad
  — it is NOT yet a committed `yukkuri.exec.produce`-style graph executor.
  Promoting it to a real, tested namespace (mirroring the `yukkuri.exec.*`
  pattern PR #5 established) is the natural next step before producing the
  remaining 25 episodes at any real scale.
- (−) YouTube publish is still blocked on operator-provided OAuth
  credentials (unchanged from ADR-2607043000) and public-internet exposure
  of the gateway is still an explicit non-default (tailnet-only today).

## Artifacts

- `kotoba-lang/murakumo` PRs #7, #8, #9, #10 (merged to main)
- `~/.murakumo/voicevox-engine/` (VOICEVOX Engine 0.25.2, macOS arm64,
  local install on `main-2.local`, not committed to any repo — a binary
  release artifact, not source)
- `~/Library/LaunchAgents/com.murakumo.infer-gateway.plist`,
  `~/Library/LaunchAgents/com.murakumo.voicevox-engine.plist` (installed,
  loaded, verified)
- `~/Movies/yukkuri-nist-output/cyber-ep-055-assertion.mp4` (first real
  produced video)

## References

- ADR-2607043000 (com-youtube / jp-hiroshiba-voicevox real clients — the
  blockers this ADR resolves)
- `gftdcojp/ai-gftd-yukkuri` PR #4 (py→cljc migration), PR #5 (real-exec layer)
- `kotoba-lang/murakumo` PRs #7/#8/#9/#10
- `content/cyber-nist-csf-yougo.edn` (the 26-episode NIST CSF series this
  produced episode 1 of)
