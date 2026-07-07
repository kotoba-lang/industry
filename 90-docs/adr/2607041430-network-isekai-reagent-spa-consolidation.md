# ADR-2607041430: network-isekai — consolidate to a single reagent/re-frame SPA shell; retire per-route static HTML + the missing `_worker.js`

## Status
Proposed

## Context

Investigating a user report ("individual org/repo pages like isekai.network/gftd/drive
don't show the game") found the root cause: `gftdcojp/network-isekai`'s GitHub-style
pretty URLs (`/<org>/<game>`, e.g. `/gftd/drive`, `/gftd/royale`) are supposed to be
rewritten by a Cloudflare Pages Worker (`public/_worker.js`, per `play.html`'s own boot
script comment and `public/_redirects`'s comment "Routing is handled by `_worker.js`")
into `play.html`'s content, without changing the address bar. That file **does not
exist anywhere in `main`'s history** — verified via production HTTP behavior
(`/gftd/drive` returns 200 with content byte-identical to `/`, i.e. the marketing
landing page, not `play.html`; any nonexistent path returns the same; `/play` alone
correctly serves `play.html`) and via `git log --all -- public/_worker.js` (the file
only ever existed on `manifest-rev`, a branch never merged into `main`; `wrangler.toml`
still documents a `StageRoom` Durable Object class as living in that file).

Structurally, `network-isekai` is currently several independent static HTML documents
(`index.html` marketing LP, `play.html` editor, `dance.html` VRM dance stage,
`assets.html` Asset Hub, `generate.html` AI generation, `studio.html`), each with its
own inline `<script type=module>` bootstrap, glued together by a single Worker file
that must correctly route every current and future game/stage/page. That Worker being
silently absent is a single point of failure that broke *every* pretty-URL route at
once, with nothing catching it.

Separately, the web/UI layer is already partially on reagent/re-frame: `deps.edn`
depends on `re-frame 1.4.3` + `reagent 1.2.0`; `src/isekai/ui/{db,events,subs,views,
app}.cljs` follow the standard re-frame app-db/events/subs/views split; `segment_
toggle.cljs` and `play_ai.cljs` are reagent components mounted into specific DOM ids
inside the static HTML. But several surfaces remain vanilla DOM-script bootstraps:
`play.html`'s own inline boot script (kototama load + `isekai.web/boot` dispatch),
`dance.html`/`isekai.stage.cljc` (a `defonce` atom plus raw `getElementById`/
`querySelector` DOM manipulation), and (by the same pattern, not yet individually
audited) `assets.html`/`generate.html`/`studio.html`.

User direction (2026-07-04): the game/stage rendering loop should stay on
`kotoba-lang/kami-engine` + `kami-webgpu` (render-IR, WASM compile) — that part of the
architecture is correct and untouched by this ADR. The web/UI layer should be reagent
end-to-end ("web部分は全画面をreagent").

## Decision

1. **Collapse the separate static HTML documents into one SPA shell** (a single
   `public/index.html`, or the minimum shadow-cljs build output requires) whose
   `<body>` mounts one reagent root.
2. **A client-side re-frame router** inspects `window.location.pathname` at boot and
   dispatches an event that sets `:view` in app-db to one of `:home | :play | :dance |
   :assets | :generate | :studio` (plus route params `:org`/`:game` for `:play`),
   generalizing the `/<org>/<game>` parsing logic that already exists inline in
   `play.html`'s boot script.
3. Each current page becomes a reagent view component under that dispatch, reusing
   existing logic rather than rewriting it:
   - `:play` wraps the existing kototama-load + `isekai.web/boot` sequence inside a
     reagent `:component-did-mount`/`:component-will-unmount`, replacing `play.html`'s
     inline `<script type=module>`.
   - `:dance` wraps `isekai.stage.cljc`'s loop the same way, replacing `dance.html`'s
     direct DOM script; the `kami.dance`/`kami.webgpu` render loop itself stays outside
     re-frame (matches `deps.edn`'s own comment: "re-frame app-db = UI/ephemeral state
     only").
   - `:home`, `:assets`, `:generate`, `:studio` become reagent components fed by
     re-frame subs, replacing their current static-HTML + vanilla-script hydration.
4. **`public/_worker.js` is NOT resurrected.** Instead, `public/_redirects` gets an
   explicit `/* /index.html 200` SPA-fallback rule, formalizing the behavior Cloudflare
   Pages was already observed applying by default. Every pretty URL (existing and
   future games/stages) serves the same SPA shell; the client router renders the right
   view. This removes the single-point-of-failure server-side routing dependency
   entirely — adding a new game/stage no longer requires touching any routing config.
5. `wrangler.toml`'s `StageRoom` Durable Object (currently documented as living in the
   now-nonexistent `public/_worker.js`) needs a new home — tracked as a follow-up
   (Cloudflare Pages Function under `functions/`, or the standalone-worker sketch
   already sketched in `wrangler.toml`'s own comments). Not blocking this ADR's
   consolidation; live-presence falls back to `BroadcastChannel`-only (the already
   documented behavior when `/api/stage-room` 501s).

## Consequences

- Fixes the reported bug (individual org/repo pages not showing the game) as a
  structural side effect of simplifying the architecture, not as a standalone patch —
  the class of bug (Worker silently missing/wrong) becomes impossible once routing is
  client-side.
- Every future game/stage/page addition becomes "add a reagent view + a route table
  entry", with no server-side routing file to keep in sync.
- One-time migration cost: `play.html`/`dance.html`/`assets.html`/`generate.html`/
  `studio.html`'s existing vanilla bootstrap logic must be ported into reagent
  lifecycles without regressions. `play_ai.cljs`/`segment_toggle.cljs` are already
  reagent and should need the least rework; `isekai.stage.cljc`'s live-presence
  (BroadcastChannel/WS) and `play.html`'s kototama/kami-webgpu boot sequence need
  care.
- Convention to hold during implementation: the canvas/WebGPU surface is owned
  entirely by a `:component-did-mount` (reagent renders it once; `kami-webgpu` writes
  to it directly every frame; reagent must never re-render that subtree), matching how
  `deps.edn` already separates UI state (re-frame) from render/game state (render-IR,
  outside re-frame).

## Alternatives Considered

1. **Just resurrect/rewrite `public/_worker.js` to restore the old per-route
   content-rewrite behavior.** Rejected — treats the symptom, not the structural
   fragility (one worker file was a silent single point of failure for every existing
   and future game route, and nothing caught it breaking). Also doesn't address the
   user's separate reagent-unification direction.
2. **Keep the multi-static-HTML-document architecture and add reagent views inside
   each independently.** Rejected — doesn't fix the routing bug (still depends on a
   working Worker to route to the right static document) and keeps N separate
   bootstrap entry points instead of one shell.

## References

- `90-docs/adr/2607032400-network-isekai-primary-consolidation.md` (network-isekai is
  the primary/canonical repo)
- `gftdcojp/network-isekai`: `public/play.html`, `public/dance.html`, `public/
  _redirects`, `wrangler.toml`, `src/isekai/stage.cljc`, `src/isekai/ui/*.cljs`
- deps.edn comment: "Multi-mode editor UI (ADR-0003): re-frame app-db = UI/ephemeral
  state only" (network-isekai's own ADR-0003, internal to that repo)
