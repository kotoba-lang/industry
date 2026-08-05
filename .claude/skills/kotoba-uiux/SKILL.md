---
name: kotoba-uiux
description: Build web / local-app UI in cljc on this workspace's BASE design system, jp-go-dds (デジタル庁デザインシステム), with the shared --hig-* token contract bridged onto it. Also covers the legacy kotoba-ui/liquid-glass stack, which remains only where it has not been migrated. Use whenever you are about to write ANY frontend/UI/page/site code in this monorepo — a new site, a new app screen, a redesign, a landing page, an admin console, or when the user says a design is "いまいち/not refined/ダサい". Read this BEFORE writing the first line of HTML/CSS/hiccup. Also use when reviewing UI code for design-system conformance.
---

# kotoba-uiux — the paved road to refined UI

## The base is `jp-go-dds` (owner decision, 2026-08-05)

**New UI is built on `kotoba-lang/jp-go-digital-design-system`** — the デジタル庁
デザインシステム (DADS) mirror — not on liquid-glass. Measured when the decision was
made: **170 repos already depended on jp-go-dds, 12 on kotoba-ui.** DADS was already the
workspace's design language; this makes it official and stops new work landing on the
minority stack.

```clojure
(require '[jp-go-dds.core :as dds]      ; button / select / table / chip-label / …
         '[jp-go-dds.page :as page]     ; ->page (charset / viewport / theme-color)
         '[jp-go-dds.tokens :as tokens]) ; bridge-css — the --hig-* contract on DADS
```

Three things to know before writing any of it:

1. **The `--hig-*` token contract still holds.** `tokens/bridge-css` redefines every
   `--hig-*` custom property on top of DADS primitives, so view/SVG/CSS written to the
   contract follows DADS **unmodified**. That is what made kami-genko / kami-app-daw /
   kami-app-nle move bases without touching their stylesheets. Keep writing
   `var(--hig-spacing-4)`, not a DADS primitive, unless you are styling a `dads-*`
   component itself.
2. **An app that takes DADS as its base has no `shitsuke.hig` underneath.** An unmapped
   token resolves to *nothing* — `padding: var(--hig-spacing-4)` collapses, silently. If
   you need a token the bridge does not carry, add it to `hig->dads` upstream; do not
   re-derive it in app CSS (the bridge's own docstring: the contract breaks the moment a
   second app derives its own).
3. **DADS is light.** `page` takes `:dark? true` for this library's own inversion layer
   (not upstream's) — right for an editor that grades against a dark surround, and what
   kami-app-daw / kami-app-nle use.

**What DADS does not have**: an app-shell / editor frame, a segmented control, a list
with a trailing slot, an accent an app can choose. The first three are app CSS on the
token contract (see kami-genko); the fourth is the point — DADS ships デジタル庁ブルー
and an app does not pick its own.

`dds-ext-*` (container / section / grid / stack / row / card) is the library's own
non-upstream layout layer. Extend it upstream rather than re-deriving layout in app CSS.

## Legacy: kotoba-ui / liquid-glass

Still correct for the ~12 repos that have not moved (`kotoba-lang/app-*`,
`cloud-itonami/kaisya`, `lawfirm`, `gftdcojp/apex`). Everything below this line describes
that stack and stays true for them. **Do not start new UI on it.**

The full recipe lives in `orgs/kotoba-lang/kotoba-ui/docs/agent-guide.md` — read that
file next if you are working in one of those repos. This SKILL.md is the contract
summary; the agent-guide is the how-to with worked examples.

## Status: this stack is migrating to `.kotoba` (owner decision 2026-07-27, ADR-2607270100 §10)

`css` / `html` / `shitsuke` / `liquid-glass-ui` / `kotoba-ui` are **not** a permanent `.cljc`
layer — they are targets for `.kotoba` migration, in that dependency order. They need **no
capabilities** (`->page` returns a string, so `kotoba/pure` covers them).

**Do not start the real cutover yet, and do not treat string-only SSR as the target.**
ADR-2607279200 Delivery #6 and the migration plan say verbatim: *"Do not make string-only SSR
the final abstraction. Start cutover when the shared logical value and both required renderers
for that tranche are qualified."* Kotoba has no recursive value types **today**, but plan W4
schedules them with explicit node/depth/byte budgets and states that *"handles are not the
application programming model"* — so do not generalise flat/parent-pointer node sets as the way
to write Kotoba UI. Anything written before W4 is an oracle-backed experiment, not the API.
Write new `.kotoba` on the `compile` path, never the legacy emitter — see CLAUDE.md.

**Until a repo has actually migrated, every rule below still applies unchanged**, and app code
must not require a mid-migration repo directly.

## The stack (dependency direction is law)

```
css / html                 L0 substrate (EDN→CSS / hiccup→HTML)
shitsuke (+ shitsuke.hig)  L1 structure + HIG semantic tokens (SSoT)
liquid-glass-ui            L2 material skin (@layer kotoba.glass)
kotoba-ui                  L3 THE single entry: core + shell + theme + ->page
appkit / uikit             L4 platform traits (desktop-dense / touch-card)
your app                   L5
```

## Non-negotiable rules (each one is a distilled production failure)

1. **Apps require `kotoba-ui.core` (+ `appkit.core` for desktop-dense or `uikit.core`
   for touch/mobile) — never `liquid-glass.*` or `shitsuke.*` directly.** Direct
   requires are an opt-out that needs a written reason (ADR-2607122200).
2. **Never write a raw hex color, px font-size, or font-family in app code.** Every
   color/type/spacing/radius decision is a `--hig-*` or `--liquid-glass-*` CSS var or a
   theme-map override. If a value you need has no token, that is a design-system gap —
   add the token upstream, don't inline the value.
3. **Never fight library CSS with specificity.** All library CSS ships inside
   `@layer kotoba.hig, kotoba.glass;` and app CSS is **unlayered, so it always wins** —
   compound selectors like `.liquid-glass__toolbar.app-toolbar` are dead weight
   (net-babiniku accumulated ~412 lines of such fights before this contract existed).
4. **Start every page from `kotoba-ui.shell`** (`page`/`app-shell`/`hero`/`section`/
   `stack`/`grid`) — do not hand-write layout CSS (`.layout`, `.hero`, grid templates).
   If shell lacks a pattern you need, extend shell upstream.
5. **Theme = one map** (e.g. `{:accent "#FF3CAC" :appearance :dark}`) passed to
   `kotoba-ui.theme`/`->page`. Dark mode comes from `color-scheme` + tokens — never
   hand-write a dark palette.
6. **Typography = the 11 HIG text styles** (`large-title`…`caption2`, `.hig-*` utility
   classes or `--hig-text-*` vars). Headings/body/captions never get ad-hoc sizes.
7. **Accessibility is part of "refined"**: keep the components' ARIA roles, ≥4.5:1
   contrast (tokens already guarantee it), `:focus-visible` ring intact, respect
   `prefers-reduced-motion` (the base layer already does — don't undo it).
8. **SSR-first, dual-render**: views are pure `.cljc` hiccup; `kotoba-ui.core/->page`
   renders the full document server-side/nbb; the same view mounts in the browser via
   shitsuke's reagent seam. Runtime order per repo rule: kotoba wasm > clojurewasm >
   ClojureScript > nbb (JVM/bb are compat-only).

## Measure it (unmeasured UI quality is theater)

After building or changing a page, score the rendered HTML with the deterministic
HIG/WCAG audit (`kotoba-lang/design-quality`, ADR-2607132300):

```bash
cd orgs/kotoba-lang/design-quality && nbb -m design-quality.cli score /path/to/rendered.html --min 95
```

Exit 1 below `--min` — wire it as a CI gate like kotoba-ui's self-scoring test.
The LLM-judge layer (`.claude/workflows/design-quality-score.js`) is the
complementary subjective arm. (Babashka/`bb` is retired; ADR-2607173000.)

## Review checklist (when auditing UI code)

- [ ] requires only kotoba-ui/appkit/uikit  - [ ] zero raw hex / px font sizes
- [ ] zero `.liquid-glass__*` compound-selector overrides  - [ ] layout via shell
- [ ] one theme map  - [ ] HIG text styles only  - [ ] light AND dark verified
- [ ] app CSS unlayered and small (< ~50 lines is healthy)

Full worked example + do/don't table: `orgs/kotoba-lang/kotoba-ui/docs/agent-guide.md`.
