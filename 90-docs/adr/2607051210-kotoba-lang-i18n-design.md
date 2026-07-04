# ADR-2607051210: kotoba-lang/i18n — portable cljc i18n library

**Status**: accepted (implemented)
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki

## Context

The owner asked how i18n is designed across the `kotoba-lang` org and
whether the [paraglide-js](https://inlang.com/m/gerre34r/library-inlang-paraglideJs)
approach would be effective, wanting ~5 approaches compared. Investigation
found:

- **No dedicated i18n framework existed in `kotoba-lang`.** The only hits
  were `kotoba-v2025`'s `020-language` crates (the *kotoba programming
  language*'s own parser/LSP — unrelated to human-language translation) and
  an archived example (`kotoba-v2025/_archive/251006/examples/densha/
  locales/{en,ja,es}.json`): flat-key JSON dictionaries paired with `.jsonld`
  RDF wrappers, never wired into a live app.
- **The real i18n design already lives one org over, in `etzhayyim`, in two
  layers.** (1) `etzhayyim-project-i18n` (`i18n.etzhayyim.com`) — a
  thin-edge-Worker-facade actor (AgentGateway MCP → pod LangServer → Python
  `kotodama/ingest/i18n.py`) doing LLM batch/on-demand translation with a
  translation-memory (TM) cache, human-approval widget workflow (quality
  score written back to TM), placeholder preservation, a 200+ language
  registry (tiered, RTL-flagged), and sqlmesh coverage/quality analytics.
  (2) Client-side, `@inlang/paraglide-js` is scaffolded into most new
  SvelteKit appviews (calendar/forms/docs/sheets/kyber-*) but **not wired
  up** (no vite plugin, no `project.inlang`, no `messages/`) — dead weight.
  `spirit-in-physics/apps/web` is the one **fully wired, production**
  example: `@inlang/paraglide-sveltekit` + vite plugin generating
  `src/lib/paraglide/{messages,runtime}.js` from `project.inlang/
  settings.json` (7 langs incl. `ar` RTL) + `messages/{lang}.json`,
  SvelteKit `hooks.server.ts` `reroute()`/`handle()` for locale routing,
  components calling `m.abstract()` etc from `$lib/paraglide/messages.js`.
- Conclusion given to the owner: paraglide's compile-time/tree-shaken
  accessor model is already validated in this ecosystem and is a good fit
  for **static UI chrome**; it does not replace the etzhayyim TM/LLM service,
  which solves a different problem (**runtime translation of content that
  doesn't exist at build time** — posts, chat messages, user content).

The owner then asked for this design to be **implemented as
`kotoba-lang/i18n`, in `.cljc`, integrated with cljs/re-frame/reagent** —
i.e., bring the validated paraglide-style idea into the kotoba-lang cljc
ecosystem rather than depending on a JS-only, SvelteKit-specific library,
and make it interoperate with the existing etzhayyim TM service rather than
duplicate it.

## Decision

New repo `kotoba-lang/i18n` (public, per org default), following the
established small-library conventions in this org (`dot`/`jsonlogic`/`toml`:
zero third-party runtime deps, `.cljc`-only, cognitect test-runner) plus
`shitsuke`'s host-independent re-frame/reagent seam pattern (proven by
`liquid-glass-ui`/`kami-mangaka-reader-clj`) for the parts that need
reactivity.

### Boundaries

| namespace | role | deps |
|---|---|---|
| `i18n.core` | runtime registry (`register!`/`set-locale!`/`t`): interpolation + select-map (plural) dispatch, source-locale fallback, never-throws (`"{missing:k}"`) | zero |
| `i18n.plural` | CLDR-*lite* plural-category rules (curated families: other-only/one-other/french/slavic/polish/arabic/hebrew), not full CLDR | zero |
| `i18n.registry` | language metadata (name/native-name/dir/tier); curated tier-1 (25) + tier-2 default table, `load!` to extend from a live registry | zero |
| `i18n.messages` | `defmessages` macro — the paraglide equivalent | zero |
| `i18n.tm-import` | pure data-shape bridge to/from etzhayyim TM service's flat JSON | zero |
| `i18n.re-frame` | `:i18n/*` event/sub registration over `shitsuke.re-frame.core` | `shitsuke` |
| `i18n.reagent` | `root-attrs` (RTL), `locale-links` + `wire-lang-switch!` | `shitsuke` |

### `defmessages`: paraglide's compile-time model, adapted

paraglide compiles each source-locale message key into a tree-shakeable,
type-checked JS function. `defmessages` does the JVM/cljs-compiler
equivalent: a macro reads an EDN catalog **once, at macroexpansion time**
(which runs on the JVM for both `clj` and `cljs` targets) and emits one
`defn` per key (`:app/title` → `app-title`). The difference from paraglide:
the generated function does not bake in the string — it calls
`i18n.core/t`, which resolves against whatever locale is active at
runtime. What's fixed at compile time is only the **key set** (unknown key
= compile error, the same typo-safety paraglide gives); translated text
stays a runtime lookup, so `set-locale!` works without a rebuild. This
matches how paraglide itself is actually used in `spirit-in-physics` (keys
fixed by `project.inlang`, text swapped by locale routing) while fitting
Clojure's "data + generic functions over it" idiom instead of a
Vite-plugin/codegen-to-disk step.

### Plural/select: Fluent-inspired, not full ICU

Message values are either a plain `"{param}"` string or a select map
(`{:select :count :one "..." :other "..."}`), resolved by `i18n.plural`. This
mirrors what paraglide v2's own `match`/`when` syntax already borrows from
[Project Fluent](https://projectfluent.org/) — confirming the chat-level
comparison's conclusion that adopting Fluent itself alongside paraglide
would have been redundant. `i18n.plural` is an explicitly *simplified*
subset of real CLDR (integer arithmetic only; no `i`/`v`/`f`/`t` operand
distinctions for decimals) — good enough for count-style UI strings, not a
claim of full CLDR coverage. Arabic gets all six categories modeled exactly
(zero/one/two/few/many/other) since it's an explicit RTL target.

### Zero-dep core / shitsuke-dependent reactivity split

`i18n.core`/`plural`/`registry`/`messages`/`tm-import` declare zero
third-party deps (mirrors `dot`/`jsonlogic`/`toml`) so a JVM-only consumer
never pulls a UI framework transitively. Only `i18n.re-frame`/`i18n.reagent`
depend on `shitsuke` (git-pinned, `:local` alias override for monorepo dev
— same convention as `liquid-glass-ui`), reusing its host-independent
re-frame/reagent seam (real re-frame 1.4.3/reagent 1.2.0 in the browser,
shitsuke's synchronous mini-runtime on the JVM for SSR/tests) rather than
reimplementing it. `i18n.core/*locale` stays the single source of truth
`t` reads; the re-frame layer mirrors it into app-db (plus a
`:i18n/catalog-version` counter for async-loaded locale bundles) purely so
Reagent knows when to re-render — non-reactive callers (e.g.
`defmessages`-generated top-level functions used outside a subscribe)
still see locale switches immediately.

`i18n.reagent/locale-links` + `wire-lang-switch!` generalize the delegated
`a[data-lang]` click-listener already proven by
`kami-mangaka-reader/src/kami/mangaka/reader/app.cljs`'s
`wire-lang-switch!` (SSR emits inert markup; one document-level click
listener hydrates it) rather than inventing a new UI pattern.

### etzhayyim TM-service bridge, not a reimplementation

`i18n.tm-import` only does pure data-shape conversion (flat dotted-key JSON
`{"app.title" "Welcome"}` ⇄ namespaced-keyword catalog `{:app/title
"Welcome"}`, split on the last dot; `GetLanguageRegistry` response →
`i18n.registry/load!` entries) — it has no HTTP client of its own. The
intended production loop: `i18n.etzhayyim.com`'s `ExportMessages` becomes
each app's `i18n/messages/<locale>.edn`, consumed by `i18n.core`/
`defmessages`; the TM service stays the system of record for LLM
translation + human-approval QA, which this library does not attempt to
duplicate.

## Consequences

- kotoba-lang apps get a paraglide-equivalent compile-time message API
  without a JS/Vite/SvelteKit dependency, usable from plain Clojure (JVM
  SSR, scripts) as well as ClojureScript.
- Zero-dep core means `i18n.core` alone is safe to depend on from any
  `.cljc` library in the org without pulling reagent/re-frame; only apps
  that actually need reactive locale switching add `i18n.re-frame`/
  `i18n.reagent` (and, transitively, `shitsuke`).
- The plural-rule table is intentionally partial (curated languages only,
  simplified integer-only CLDR subset). Extending it for a new language is
  a small, isolated addition to `i18n.plural`'s `lang->family`/family-fn
  table — not a redesign.
- The default language registry (25 tier-1 + 13 tier-2 curated) is not the
  full 200+ list etzhayyim's `GetLanguageRegistry` exposes; apps needing
  the full list fetch it at runtime and call `i18n.registry/load!` via
  `i18n.tm-import/language-registry->entries` — the static table exists so
  the library still works fully offline/zero-dep by default.
- No automated ClojureScript compile check exists in this repo's CI (same
  as `shitsuke`/`liquid-glass-ui`/`dot` etc — only `clojure -M:test` on the
  JVM runs in CI). The `.cljc` reader-conditional `:cljs` branches
  (`i18n.messages`, `i18n.re-frame`, `i18n.reagent`) were structurally
  validated by mirroring shitsuke's proven seam pattern; full cljs
  compilation is exercised by real app builds (e.g. a shadow-cljs app in
  the style of `murakumo-studio`), not by this library's own CI.
- 36 tests / 83 assertions, 0 failures, both via `clojure -M:test` (real
  git-pinned `shitsuke` dependency, matches CI) and `clojure -M:local:test`
  (monorepo sibling checkout).

## One-line summary

New `kotoba-lang/i18n` repo: a zero-dep `.cljc` message registry + a
`defmessages` compile-time-accessor macro (paraglide's tree-shaken-function
idea, adapted to stay runtime-locale-switchable) + CLDR-lite plurals +
RTL/tier language registry + a pure data-shape bridge to the etzhayyim
TM/LLM translation service + a `shitsuke`-seam re-frame/reagent integration
generalizing kami-mangaka-reader's proven locale-switch UI pattern.
