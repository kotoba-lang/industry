---
name: kotoba-uiux
description: Build refined, Apple-HIG-quality web / local-app UI in cljc using the kotoba-lang design-system stack (shitsuke.hig tokens → liquid-glass-ui material → kotoba-ui single entry → appkit/uikit). Use whenever you are about to write ANY frontend/UI/page/site code in this monorepo — a new site, a new app screen, a redesign, a landing page, an admin console, or when the user says a design is "いまいち/not refined/ダサい". Read this BEFORE writing the first line of HTML/CSS/hiccup. Also use when reviewing UI code for design-system conformance.
---

# kotoba-uiux — the paved road to refined UI

The full recipe lives in `orgs/kotoba-lang/kotoba-ui/docs/agent-guide.md` — **read that
file next** (fetch the repo with `west update --fetch smart kotoba-ui` if it is not
checked out). This SKILL.md is the contract summary; the agent-guide is the how-to with
worked examples.

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
cd orgs/kotoba-lang/design-quality && bb score /path/to/rendered.html --min 95
```

(or `nbb -m design-quality.cli score ...`). Exit 1 below `--min` — wire it as a CI
gate like kotoba-ui's self-scoring test. The LLM-judge layer
(`.claude/workflows/design-quality-score.js`) is the complementary subjective arm.

## Review checklist (when auditing UI code)

- [ ] requires only kotoba-ui/appkit/uikit  - [ ] zero raw hex / px font sizes
- [ ] zero `.liquid-glass__*` compound-selector overrides  - [ ] layout via shell
- [ ] one theme map  - [ ] HIG text styles only  - [ ] light AND dark verified
- [ ] app CSS unlayered and small (< ~50 lines is healthy)

Full worked example + do/don't table: `orgs/kotoba-lang/kotoba-ui/docs/agent-guide.md`.
