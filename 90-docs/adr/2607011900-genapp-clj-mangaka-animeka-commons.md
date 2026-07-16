# ADR 2607011900: genapp-clj — shared LangGraph generation-app scaffolding for mangaka/animeka

## Status
Accepted

## Context

`ai-gftd-animeka` was ported from `ai-gftd-mangaka`'s shape ("the anime
sibling of mangaka" — same CLAUDE.md language), and its `deps.edn` says so
outright: "Pins mirror mangaka." Comparing the two `clj/src/{mangaka,animeka}`
trees confirmed the porting left real duplication, not just shared upstream
deps:

| File | mangaka lines | animeka lines | diff lines (name-normalized) | verdict |
|---|---|---|---|---|
| `blob.cljc` | 61 | 60 | 25 | byte-identical logic (docstring-only diff) |
| `checkpoint.cljc` | 40 | 39 | 22 | byte-identical logic (docstring-only diff) |
| `comfy.cljc` | 164 | 162 | 120 | same shape, config differs (node-type name, default dims, env vars, api-key-required?) |
| `llm.cljc` | 100 | 94 | 107 | same shape, provider differs (Anthropic vs murakumo/OpenAI-compatible), plus mangaka-only vision-* fns |
| `runtime.cljc` | 41 | 28 | 39 | thin glue wiring app-specific store to blob/checkpoint — left alone |
| `store.cljc` | 321 | 270 | 532 (~all) | genuinely domain-specific (page/panel vs work/episode/scene/cut schema) — **not** extracted |

`langgraph-clj` / `comfyui-clj` / `langchain-clj` themselves were already
shared (identical pinned SHAs in both `deps.edn`s) — the gap was one layer
up: the app-scaffolding built *on* those libraries (blob store, graph
checkpointer, ComfyUI gateway client, LLM chat-model wiring) was duplicated
rather than shared.

This is the same shape of problem ADR-2606282100 (mangaka render-commons vs
SIP work-split) already solved once, for the 2D-panel-generation layer
inside `kami-engine` (`kami-mangaka-render-clj` extracted from
`sip.render`, config injected via `:mappers`). That ADR's own "ghosthacker"
follow-up line ("re-define ghosthacker as a second work reusing Tier 1")
anticipated exactly this: a second LangGraph generation app reusing a first
app's generic layer.

## Decision

Extract the near-identical layer into a new repo, `kotoba-lang/genapp-clj`,
with four namespaces:

- `genapp.blob` — content-addressed blob store (protocol + mem/fs impls).
  Ported **verbatim** (zero config needed — the two originals were
  byte-identical).
- `genapp.checkpoint` — latest-only LangGraph checkpointer over a
  `langchain.db` conn. Ported **verbatim**.
- `genapp.comfy` — ComfyUI txt2img rendering via `comfyui-clj`. Config-driven:
  `:node-type` / `:default-ckpt` / `:default-width` / `:default-height` /
  `:require-api-key?` (mangaka needed both base+key; animeka only needed a
  base) / env-var name lists (animeka tries a pod-specific var before
  falling back to a shared one) / `:placeholder` (stub SVG sizing/label).
  Dependency inversion — same pattern as `kami-mangaka-render-clj`'s
  `:mappers` in ADR-2606282100.
- `genapp.llm` — ChatModel offline-mock fallback + JSON/EDN
  structured-output parsing + vision message-building. The model *provider*
  (Anthropic direct vs murakumo's OpenAI-compatible gateway) stays
  app-specific, injected as a `build-live` thunk; the shared shape (mock
  fallback echoing `[mock <model-id>] <last message>`, `parse-structured`
  JSON→EDN fallback + fence-stripping, `complete-json`, `vision-content` /
  `vision-text` / `vision-json`) lives in genapp.

`runtime.cljc` and `store.cljc` were **not** extracted: `runtime.cljc` is
thin glue wiring an app-specific store to the (now-shared) blob/checkpoint,
too small and too coupled to be worth a facade of its own; `store.cljc` is
genuinely domain-specific (mangaka's page/panel datom schema vs animeka's
work/episode/scene/cut schema, ~90% line-diff) — extracting it would mean
forcing two different domains through one schema, the exact anti-pattern
ADR-2606282100 flagged and rejected for `render_anchors.edn`-style
per-work data.

### Facade pattern (no call-site changes)

Each app keeps its four original namespaces (`mangaka.blob`, `mangaka.comfy`,
etc.) as thin facades over `genapp.*`, preserving their **exact original
public function names and arities** — every existing caller elsewhere in
each app (graph nodes, tests) is unchanged. E.g. `mangaka.comfy/render-panel`
still exists and behaves identically; internally it now calls
`genapp.comfy/render` with mangaka's baked-in config
(`{:node-type "MangakaKSampler" :default-width 832 :default-height 1216 ...}`).
Same shape `sip.render` used as a facade over `kami.mangaka.render` in
ADR-2606282100.

### Verification

Both apps' full test suites pass unchanged after the refactor:
- `ai-gftd-mangaka`: 82 tests / 560 assertions, 0 failures/errors.
- `ai-gftd-animeka`: 5 tests / 32 assertions, 0 failures/errors.
- `genapp-clj` itself: 20 tests / 44 assertions (ported the generic-logic
  subset of `mangaka.blob-test`/`mangaka.comfy-test` plus new
  `genapp.llm-test` coverage, including both `:require-api-key?` branches
  explicitly since that's new injected behavior, not present as a branch in
  either original file).

mangaka's cljs Studio UI (`mangaka.cljs.app`/`mangaka.cljc.api`) was checked
and does not transitively require any of the four touched namespaces (it's
a pure browser `fetch` client against `/xrpc/*`) — the refactor has no cljs
build impact.

### Drive-by fix

Both apps' `:dev` alias `:local/root` override paths were wrong (`../../../../../com-junkawasaki/...`,
5 levels up) for this monorepo's actual nesting
(`orgs/gftdcojp/ai-gftd-{mangaka,animeka}/clj/`) — they resolved to nothing,
meaning `:dev` silently fell back to git-fetching the pinned deps instead of
using local checkouts. Fixed to 3 levels up
(`../../../com-junkawasaki/...`), which is what let this refactor actually
be tested locally via `-M:dev:test` in the first place.

## Consequences

- A recipe fix (e.g. a ComfyUI error-handling edge case, or a
  `parse-structured` fence-stripping bug) now lands once in `genapp-clj` and
  reaches both apps, instead of drifting between two hand-copied files.
- A third LangGraph generation app in this style (there is prior-art
  precedent for at least one more — `dogaka`/`gameka` per
  `ai.gftd.apps.cine.*`'s "shared with mangaka and dogaka" line in
  animeka's CLAUDE.md) starts from `genapp-clj` + a config map instead of
  copy-pasting a third time.
- New dependency edge: both apps now depend on `kotoba-lang/genapp-clj` in
  addition to their existing `com-junkawasaki/{langgraph,comfyui,langchain}-clj`
  pins. Consistent with the stated org-migration direction
  (`repos.edn :orgs "com-junkawasaki" :note`: "汎用基盤 -clj も最終的に
  kotoba-lang へ全移行") — this is a new shared lib landing directly in
  `kotoba-lang` rather than `com-junkawasaki` first.
- `store.cljc` duplication (the largest file, ~90% different) remains
  unaddressed — deliberately: it encodes each app's actual domain, not
  shared infrastructure.

## Alternatives Considered

1. **Leave the duplication** (status quo): rejected — the two files were
   already provably diverging in small ways (mangaka gained
   `vision-score`/`vision-json`, animeka gained env-var fallback chains)
   that a future bug-fix would have to remember to port twice, or silently
   wouldn't.
2. **Merge `store.cljc` too, with a generic schema**: rejected — the two
   domains (page/panel vs work/episode/scene/cut) are not the same shape;
   forcing one schema would either lose animeka's cut-level fields or bloat
   mangaka's page schema with unused columns. Matches ADR-2606282100's
   rejection of "generic anchors + generic compose" for the same reason.
3. **Put the extraction under `com-junkawasaki` (matching the two apps'
   existing `-clj` deps) instead of `kotoba-lang`**: rejected per
   `repos.edn`'s own stated migration direction — new generic `-clj` libs
   land in `kotoba-lang` now, `com-junkawasaki`'s remaining `-clj` libs are
   mid-migration out, not a landing zone for new ones.
4. **Name it after one consumer (`kotoba-lang/mangaka-clj` or similar)**:
   rejected — this is exactly the naming anti-pattern
   ADR-2607011816's animeka-consumer correction and ADR-2606282100 both
   already fixed once (a generic library named after a single consumer).
   `genapp` names what it actually is: generic app-scaffolding for
   LangGraph generation apps.

## References

- ADR-2606282100 (mangaka render-commons vs SIP work-split — the
  dependency-inversion precedent this extraction follows)
- 90-docs/adr/2607011816-ghosthacker-shiropico-standalone-repo.md (sibling
  ADR from the same investigation, corrected mangaka→animeka as
  SHIRO & PICO's intended consumer)
- `orgs/kotoba-lang/genapp-clj` (**deleted**, see Correction below)
- `orgs/gftdcojp/ai-gftd-mangaka` PR #1 / `orgs/gftdcojp/ai-gftd-animeka` PR #1

## Correction (2026-07-01, same day): genapp-clj split apart and deleted

Direct feedback on `genapp-clj` itself, immediately after it shipped:
`comfy`/`llm` don't belong bundled with `blob`/`checkpoint` (different
concerns — ComfyUI/LLM tool wrappers vs generic LangGraph storage
primitives), and `genapp` isn't a real/generic-enough name. Investigating
turned up something more specific: `kotoba-lang/comfyui` and
`kotoba-lang/langchain` already existed as the **migrated successors** of
`com-junkawasaki/comfyui-clj` and `com-junkawasaki/langchain-clj` (confirmed
via `git log` — `kotoba-lang/langgraph`'s tip commit is literally
`"chore(reconcile): org-rename → kotoba-lang"`; `kotoba-lang/comfyui`'s tip
SHA `2a653b1` was byte-identical, at that point, to what `genapp-clj` had
just pinned from `com-junkawasaki/comfyui-clj`) — i.e. `genapp-clj` had
built on the **stale** org without noticing the real migration target
already existed, one repo away.

**Verified compatible before touching anything**: `diff`'d every file
`genapp.comfy`/`genapp.llm` actually required (`comfyui.{node,std,queue,
exec}`, `langchain.{model,message,db,kotoba_db}`) between the
`com-junkawasaki` and `kotoba-lang` copies — all byte-identical except
`langgraph.checkpoint` (kotoba-lang added a backward-compatible `:db/id`
field). Ran genapp-clj's own test suite against `kotoba-lang`-sourced deps
before splitting anything, to separate the "safe to swap orgs" question
from the "split the bundle" question.

**Split, three ways, each to its natural home**:

- `genapp.comfy` → `comfyui.gateway` in `kotoba-lang/comfyui` (PR #1) — the
  JVM http-kit/jsonista I/O adapter for `comfyui.exec`'s pure engine,
  living right next to it.
- `genapp.llm` → `langchain.jvm` in `kotoba-lang/langchain` (PR #6) — the
  JVM host-fn reference implementation `langchain-clj`'s pure core
  deliberately doesn't ship (model adapters take `:http-fn`/`:json-write`/
  `:json-read` as injected capabilities for JVM/CLJS/SCI/WASM portability).
- `genapp.blob` + `genapp.checkpoint` (the genuinely domain-agnostic half,
  the two files that were byte-identical between mangaka/animeka
  originally) → new repo `kotoba-lang/langgraph-store` (no `-clj` suffix —
  `kotoba-lang` doesn't use that suffix convention, unlike
  `com-junkawasaki`'s `langgraph-clj`/`comfyui-clj`/`langchain-clj`).

In both `comfyui` and `langchain`, the new JVM-only namespace's http-kit/
jsonista deps were added to the **`:test` alias only**, not the main
`:deps` — `langchain`'s own deps.edn documents a "zero third-party runtime
dependencies by design" invariant (every namespace `.cljc`, runs on JVM/
CLJS/SCI/WASM) that adding http-kit unconditionally would have broken for
every non-JVM consumer. Every actual consumer (mangaka, animeka, shiropico)
already declares http-kit/jsonista itself for its own server needs, so
nothing downstream needed the main `:deps` polluted.

**Completed the kotoba-lang migration for consumers, while here**:
mangaka/animeka/shiropico's `deps.edn` now point `langgraph`/`comfyui`/
`langchain` at `kotoba-lang`, not `com-junkawasaki` — the migration
`repos.edn`'s `:orgs` taxonomy note already declared as the direction but
these three apps hadn't yet made. Also fixed mangaka's/animeka's `:dev`
`:local/root` paths in the same pass (previously wrong for
`com-junkawasaki` targets too, same off-by-2-levels bug documented in
ADR-2607011816's mangaka/animeka commons work).

**`kotoba-lang/genapp-clj` deleted** (`gh repo delete`) once all three
consumers' facades were repointed and every test suite re-verified green:
mangaka 82/560, animeka 5/32, shiropico 19/49 (all unchanged from before
either the original extraction or this correction — the facade pattern
absorbed two consecutive relocations with zero call-site changes).
`manifest/repos.edn`/`west.yml` updated to match (genapp-clj entry removed,
langgraph-store added, consumer + comfyui/langchain pins advanced) via the
same GitHub API single-entry method as ADR-2607011816, for the same
reasons (shallow-clone false-positive risk, concurrent fleet checkouts).
