# ADR-2607051800: net-babiniku — a new gftdcojp product, AI VTuber/VRM companion chat, distinct from network-isekai but sharing the kami engine

## Status
Proposed

## Context

User direction (2026-07-05): design `gftdcojp/net-babiniku` as a **separate** repo from
`gftdcojp/network-isekai`, using the **same kami engine**, shaped as a **VRM/VTuber-type**
app in the spirit of [isekaizero.ai](https://www.isekaizero.ai/).

Research on isekaizero.ai (ISEKAI ZERO, ARX MEDIA SDN BHD, mobile app, #1 Games/#2
Entertainment on the App Store as of May 2026): an **interactive AI story/roleplay** app —
chat with LLM-driven characters, choices shape the narrative, an experimental "Visual Novel
Mode" adds background images + **character images with changing expressions/poses** + **per-
character TTS voice**. Backend is multi-LLM (Deepseek 3.2 default, switchable to
Gemini/Grok), dual-currency (free Mana / paid Arcane credits). Its character presentation is
2D (visual-novel-style portraits with expression swaps), not a live 3D avatar.

`network-isekai` (`90-docs/adr/2607032400-network-isekai-primary-consolidation.md`,
`90-docs/adr/2607041430-network-isekai-reagent-spa-consolidation.md`) is the existing
kami-engine web product: an **open, "CodePen for games"** platform — every game/dance-
stage/asset is public, forkable, content-addressed EDN in Datomic via kotoba. It already has
a VRM feature (`:dance` stages — `kami.dance`/`kami-live` vocabulary, `isekai.stage.cljc` +
`isekai.vrm-bridge.cljc` hand-rolled against `kami-web` wasm directly), but VRM avatars there
are one mode among several (games/dance/assets/generate/studio), not the product's identity,
and everything is public/forkable by design — wrong shape for private AI-companion chat
sessions.

Separately, `kotoba-lang/kami-engine-sdk` (`@etzhayyim/kami-engine-sdk`, Svelte 5,
three.js-free as of ADR-2605264300) already packages exactly the pieces a VTuber-style app
needs as a reusable SDK, built **after** network-isekai's own vrm-bridge glue was written:
- `VrmViewer` + `createVrmEngine` — a live VRM avatar rendered end-to-end via the Rust+wgpu
  WASM `KamiWasmExports` surface (no three.js, no DOM-side render loop to hand-write).
- headless builders for **morph** (facial expression blend-shapes), **bone/motion** (pose/
  gesture), **voice** (TTS), and **emotion** control.
- `./webvr` — a **headless, choice-based scenario runner** (`createIncidentVrEngine`,
  langgraph/Pregel StateGraph) with a pluggable `onScene` callback for rendering. Built for
  cyber-drill's incident-response training drills (which supply their own three.js renderer
  via `onScene`), but the shape — headless turn/state engine + swappable renderer — is
  exactly what an isekaizero.ai-style "choices matter" branching conversation needs.

No existing gftdcojp product combines `webvr`'s choice engine with `VrmViewer` as the
`onScene` renderer. `ai-gftd-isekai` (isekai.gftd.ai) was checked and is unrelated — a
Minecraft-voxel-sandbox × creature-collection game sharing kami-engine's WebGPU renderer and
the "isekai" name only coincidentally.

## Decision

Create **`gftdcojp/net-babiniku`**: a private, single-purpose product — chat with an
LLM-driven character persona whose body is a **live-rendered 3D VRM avatar** (VTuber
presentation) instead of isekaizero.ai's 2D visual-novel portraits, reusing `kami-engine`'s
render substrate via the SDK rather than reimplementing it.

1. **Org/visibility:** `gftdcojp` (human-centric/business scope, per `repos.edn` `:orgs`),
   **private** (per repos.edn `:orgs :visibility` policy for gftdcojp), plain-git child repo
   registered in `manifest/repos.edn` `:extra-projects` → `manifest/west.yml`.

2. **Frontend stack: Svelte 5 via `@etzhayyim/kami-engine-sdk`, not network-isekai's
   ClojureScript/reagent.** `VrmViewer`/`createVrmEngine` already is the live-avatar render
   surface this product needs; network-isekai's `isekai.vrm-bridge.cljc`/`isekai.stage.cljc`
   predate the SDK and hand-roll the same glue directly against `kami-web` wasm. No reason
   for a second app to redo that work in CLJS when the SDK packages it for exactly this.

3. **Turn engine: `kami-engine-sdk`'s `webvr` headless choice-scenario runner**
   (`createIncidentVrEngine`, Pregel/langgraph StateGraph), repurposed from incident-response
   drill content to conversational-roleplay content. net-babiniku is the SDK's **second**
   `webvr` consumer (after cyber-drill) and its **first** `VrmViewer`-as-`onScene`-renderer
   consumer (cyber-drill supplies its own vendor-private three.js scene) — validates the
   headless-engine/pluggable-renderer split works across two unrelated domains.

4. **Expression/motion mapping, data-driven like `kami.dance`'s pose-from-EDN:** each LLM
   turn emits `{dialogue, emotion, motion-cue}`; `emotion` drives the SDK's morph builder
   (facial blend-shape targets), `motion-cue` drives the bone/motion builder (idle/gesture
   pose) — the same "render state is a pure projection of data" philosophy `kami.dance`
   already uses for beat-synced dance poses, applied to conversation turns instead of a beat
   grid.

5. **Voice: the SDK's voice builder** drives per-character TTS, matching isekaizero.ai's
   per-character-voice Visual Novel Mode but spoken through a live avatar instead of a static
   portrait. net-babiniku stands up its **own** backend (LLM chat completion + TTS) —
   independent deploy from network-isekai's `backend/` (Modal GPU: TRELLIS/image/TTS/music),
   not a runtime dependency on it. Where the same *pattern* (Modal-GPU TTS service shape)
   applies, copy-adapt rather than depend cross-repo — the same "duplicate a small amount of
   glue rather than couple two independent apps" tradeoff `isekai.vrm-bridge.cljc`'s own
   doc-comment already made explicit for character-creator's gpu-adapter.

6. **AI-persona containment + governor, per the existing Actors doctrine (CLAUDE.md
   Actors section; precedent: `isekai.moderation.cljc`).** The LLM-persona node proposes a
   turn `{dialogue, emotion, motion-cue}` only; a separate `ContentGovernor` checks/filters
   it before anything is ever rendered to the VRM avatar or spoken via TTS. Every
   commit/hold is appended to an audit ledger. Non-negotiable for an AI-companion product —
   this class of app is a known moderation surface, and "babiniku" (バ美肉,
   embodying a VRM/VTuber persona) framing makes an explicit content gate more important, not
   less.

7. **Data model: private, not public/forkable.** Chat sessions, character personas, and
   per-user avatar customization are private product data (per-user store), unlike
   network-isekai's public content-addressed "everything is forkable" EDN model.
   Kotoba-style content-addressing may still be used internally for asset caching (e.g.
   avatar/voice asset dedup), but there is no public asset hub, no fork-by-URL UX.

## Consequences

- net-babiniku and network-isekai both render VRM avatars via the same underlying
  `kotoba-lang/kami-engine` (kami-web wasm / kami-webgpu, Rust+wgpu), satisfying "same kami
  engine" — but through different integration layers (SDK/Svelte vs hand-rolled CLJS glue),
  each fit to its own app's stack and audience.
- Validates `kami-engine-sdk`'s `webvr` + pluggable-`onScene` design as genuinely reusable
  across two unrelated domains (incident-response drills, AI-companion roleplay) — a design
  smell would be if only one consumer shape ever worked.
- New standalone backend (LLM chat + TTS + governor) and its own private data store to
  build; nothing here is inherited automatically from network-isekai's `backend/`.
- Follow-up: scaffold shape is network-isekai-like (README + `90-docs/adr` + package.json,
  ADR-driven), not `ai-gftd-isekai`'s dodaf `PROJECT.jsonld`/`OWNERS`/nanoid shape — net-
  babiniku is a kami-engine app in the same lineage as network-isekai, not part of the
  `ai-gftd-*`/`ai-gftd-apps-gftdcojp` product-registry line.
- Follow-up: exact LLM provider(s), pricing/currency model (if any), and moderation policy
  detail are product decisions out of scope for this architecture ADR.

## Alternatives Considered

1. **Build it as a new mode/page inside `network-isekai`** (like `assets.html`/
   `generate.html` today). Rejected — different audience (private product vs open platform)
   and different data model (private chat sessions vs network-isekai's public forkable EDN);
   forcing "everything is public and forkable" onto private AI-companion conversations is
   architecturally wrong, not just a styling difference. Also would keep the ClojureScript/
   re-frame stack instead of the SDK's native Svelte packaging.
2. **Reimplement VRM rendering glue directly** (network-isekai's own
   `isekai.vrm-bridge.cljc` + `isekai.stage.cljc` pattern) instead of depending on
   `kami-engine-sdk`. Rejected — the SDK already packages `VrmViewer` + morph/bone/voice/
   emotion builders for exactly this; redoing it in CLJS+JS glue duplicates real, already-
   built work for no benefit.
3. **Reuse cyber-drill's three.js scene renderer** as the `webvr` `onScene` implementation.
   Rejected — cyber-drill's renderer is vendor-private and three.js-based; the SDK is
   explicitly three.js-free (ADR-2605264300) and `VrmViewer`'s native wgpu path is the
   better-fit, on-brand renderer for a VTuber avatar body.

## References

- `90-docs/adr/2607032400-network-isekai-primary-consolidation.md`
- `90-docs/adr/2607041430-network-isekai-reagent-spa-consolidation.md`
- `gftdcojp/network-isekai`: `src/isekai/vrm_bridge.cljc`, `src/isekai/stage.cljc`,
  `src/isekai/moderation.cljc`, `README.md` ("Not only games — VRM dance stages too")
- `kotoba-lang/kami-engine-sdk` (`com-junkawasaki/kami-engine-sdk` checkout): `README.md`
  (`VrmViewer`/`createVrmEngine`, `./webvr` `createIncidentVrEngine`, three.js-free
  ADR-0031/ADR-2605264300)
- isekaizero.ai research (2026-07-05 web search): interactive AI stories, choices-matter
  roleplay, Visual Novel Mode (2D character images + expression/pose swaps + per-character
  TTS), multi-LLM backend, Mana/Arcane dual currency
- `gftdcojp/ai-gftd-isekai` — checked, unrelated (voxel-sandbox game, name coincidence only)
- CLAUDE.md "Actors" section — containment + independent governor + append-only ledger
  doctrine, applied here to the LLM-persona/ContentGovernor split


## Amendment (2026-07-05): frontend stack corrected to ClojureScript + reagent/re-frame

Per direct user direction, decision #2 (Svelte 5 + `@etzhayyim/kami-engine-sdk`) and
decision #3 (`webvr`'s headless choice-scenario runner as the turn engine) are **superseded**
by: **ClojureScript + reagent/re-frame + `kami-webgpu` + `kotoba-lang/vrm`, matching
`network-isekai`'s own toolchain exactly** (same `deps.edn` library coordinates —
`kotoba-lang/kami-webgpu`, `kotoba-lang/vrm`, `re-frame/re-frame`, `reagent/reagent` — same
`:local/root` relative paths, since both repos sit at `orgs/<org>/<repo>` depth).

`net-babiniku/src/babiniku/vrm-bridge.cljc` is a direct adaptation of network-isekai's
`isekai.vrm-bridge.cljc` (duplicated, not depended-on — the file's own precedent already
covers exactly this tradeoff). The render loop (`babiniku.web`) uses `kami.webgpu`'s
render-IR EDN directly (`kami.webgpu/draw!`, `kami.webgpu.mesh/draw!`) instead of the SDK's
`VrmViewer` component — no Svelte, no `webvr` Pregel/StateGraph turn engine in this repo.

The containment-boundary decision (#6: LLM-persona proposes only, a separate governor is the
sole path to a renderable/speakable turn, append-only ledger) is **unchanged** — re-expressed
in `.cljc` (`babiniku.governor/review-turn`) instead of TypeScript, gated by `nbb governor`
(matching network-isekai's `isekai.moderation.cljc` + `nbb moderation` convention). The
private-data-model decision (#7) is also unchanged.

Deployed (Milestone 0, placeholder): a kami-webgpu render loop (a placeholder box by
default, a real live VRM avatar via `?vrm=<url>` through the adapted vrm-bridge) plus a
reagent/re-frame UI with a live governor demo, on Cloudflare Pages —
https://net-babiniku.pages.dev. No LLM backend, no TTS, no real chat yet.

This also **retires alternative #3** ("reuse cyber-drill's three.js scene renderer") as
moot — there is no `webvr`/SDK dependency left to supply an `onScene` renderer for.
