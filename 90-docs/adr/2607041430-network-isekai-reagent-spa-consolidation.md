# ADR-2607041430: network-isekai — consolidate to a single reagent/re-frame SPA shell; retire per-route static HTML + the missing `_worker.js`

## Status
Superseded (2026-07-08) — the specific decision below (single reagent SPA
shell + client-side router, retiring per-page static HTML) was never
implemented; the motivating bug and the reagent-adoption goal were both
resolved through narrower, different changes instead. See Addendum.

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

## Addendum (2026-07-08): maturity audit — Decision §1/§2/§4 not implemented; superseded by narrower fixes shipped under a different ADR

A maturity pass on `gftdcojp/network-isekai` re-checked this ADR's own
acceptance criteria against the live repo (`origin/main` @ `b7388f1`) rather
than against the general impression that "reagent work happened since." A
prior audit had flagged this ADR as still `Proposed` despite apparently
substantial implementation, citing commit `4cedf3e` ("convert every page's
UI chrome to reagent", PR #110). Re-reading that commit and its predecessor
PR #106 against this ADR's actual Decision items shows the opposite: **this
ADR's specific architectural decision was not built**, and a materially
different, narrower fix shipped in its place.

**What this ADR decided** (§Decision): collapse `index.html`/`play.html`/
`dance.html`/`assets.html`/`generate.html`/`studio.html` into **one**
`public/index.html` reagent root (§1), add a **client-side re-frame router**
that dispatches a `:view` in app-db (§2), retire the per-page static-HTML +
`_worker.js` architecture, and replace it with a **universal**
`` /* /index.html 200 `` `_redirects` fallback so "adding a new game/stage no
longer requires touching any routing config" (§4, Consequences
`:extensibility`). Explicitly rejected as Alternative 2: "keep the
multi-static-HTML-document architecture and add reagent views inside each
independently."

**What is actually in production, verified directly**:
- `public/index.html`, `play.html`, `dance.html`, `assets.html`,
  `generate.html`, `studio.html` are still six separate static HTML
  documents. There is no single SPA shell and no `:view`/route dispatch of
  any kind — `grep -rn ":view" src/isekai/` finds nothing resembling a
  router (only unrelated WebGPU `:view` texture-view keys in
  `isekai/stage.cljc`). The two reagent apps that do exist
  (`src/isekai/ui/{app,db,events,subs,views,modes}.cljs`) implement
  ADR-0003's *different* multimode CAD/game/modeling/animation editor shell,
  not this ADR's page router.
- The multi-document architecture was not just kept but *formalized*:
  `src/isekai/site/build.cljc` (`nbb render-site`) code-generates every
  `public/*.html` shell from its own `.cljc` source
  (`isekai.site{,.assets,.generate,.studio,.play,.dance,.preview}`), citing
  ADR-2607022800 (kotoba-lang's UI/UX design-system ADR), not this one.
- `public/_redirects` still lists explicit per-org-prefix rewrite rules
  (`/gftd/* /play 200`, `/itonami/* /play 200`, `/studio/gftd/* /studio 200`,
  `/studio/itonami/* /studio 200`) with its own comment "Add a line here
  whenever a new org namespace is added under `public/games/` or
  `public/stages/`" — exactly the manual per-route maintenance burden this
  ADR's `:extensibility` consequence set out to eliminate. There is no
  `` /* /index.html 200 `` universal fallback.
- The routing bug this ADR was written to fix (individual `/<org>/<game>`
  pages not showing the game) was fixed by PR #106
  ("fix(routing): restore /<org>/<game> pretty-URL routing", commit
  `1eeb2e6`, branch tellingly named `feat/spa-reagent-consolidation`) via
  exactly those per-org `_redirects` rules — a scoped patch, not the
  universal client-router fallback this ADR called for. PR #106's own
  description states plainly: "the URL-parsing bootstrap in each of
  `play.html`/`dance.html`/`studio.html` is plain inline JS (not re-frame)
  ... `/play`/`dance` boot into `isekai.web`/`isekai.stage`, which are plain
  CLJS (direct DOM manipulation), not re-frame" — i.e. at that point the
  multi-document, non-reagent architecture was deliberately kept, not
  replaced.
- The later reagent-adoption commit `4cedf3e` (PR #110, "convert every
  page's UI chrome to reagent (ADR-2607022800)") converts each static page's
  *widgets in place* — `rdom/render` mounting `status-view`/`editor-view`/
  `buttons-view` etc. into fixed DOM ids (`#status-mount`, `#editor-mount`,
  `#btns-mount`, ...) that the still-separate static HTML documents declare
  (`src/isekai/web.cljc:38-39`: "Mounted once at boot into play.html's
  static containers"; `src/isekai/stage.cljc:487-488`: "Mounted once at boot
  into dance.html's static ids"). This satisfies a *different* ADR's mandate
  ("every interactive piece of the page-embedded shell scripts becomes a
  reagent component", ADR-2607022800) while explicitly preserving the
  per-page static-HTML-document structure — i.e. it implements this ADR's
  own **rejected Alternative 2** ("multi-static-HTML-document architecture
  + reagent views inside each"), not its actual Decision.

**Disposition**: given (a) the routing bug is already fixed in production
via the narrower PR #106 fix, (b) the team has since invested further in
the opposite direction (per-page codegen + widget-level reagent mounts,
each with its own CI gate — `nbb visual`/`nbb dance`/`nbb assets`/etc. — under
ADR-2607022800), and (c) a full retroactive rewrite into one SPA shell would
be a repo-wide, high-risk rearchitecture of a live production site
(isekai.network) that would conflict with all of that subsequent work, this
ADR's Decision is **not** being retroactively implemented. It is marked
**Superseded** by the narrower fixes actually shipped (PR #106 for the bug,
ADR-2607022800/PR #110 for the reagent-chrome goal), rather than Accepted,
because the specific architecture it prescribed was not built and is no
longer the direction the codebase is moving in. The one concrete gap that
remains versus this ADR's stated benefit — `_redirects` still needs a
manually-added line per new org namespace — is a small, low-risk, low-value
item at current scale (2 org namespaces total to date) and is left as an
explicit, undone follow-up rather than bundled into this audit.
