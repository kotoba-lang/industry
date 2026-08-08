# クリーニング営み — ISIC 9601 idle tycoon

An idle shop-management game whose rules are a transcription of
[`cloud-itonami/cloud-itonami-isic-9601`](https://github.com/cloud-itonami/cloud-itonami-isic-9601)
— the garment-care governed actor (LaundryOps-LLM ⊣ Garment Care Governor).

This directory is **staging**. Its canonical home is
`network-awai/network-isekai:public/games/itonami/isic-9601/`, alongside the eight
cloud-itonami job simulators from ADR-2607031000; the layout here mirrors that path so
the port is a copy. See `90-docs/adr/2608100000-cloud-itonami-isic-9601-laundry-idle-tycoon.edn`
for what is done, what is not, and why.

## The point of it

An idle tycoon is a game about automating everything. This shop **structurally cannot
automate its last two stations**, and that is the whole design:

- `laundry.phase/phases` keeps `:actuation/apply-cleaning-process` and
  `:actuation/return-garment` out of every phase's `:auto` set — including phase 3, whose
  `:auto` is exactly `#{:garment/intake}`.
- `laundry.governor/high-stakes` enforces the same invariant independently, so two layers
  agree.

So washing a real garment and handing it back to its owner stay on the player's finger
forever, however big the shop grows. Hiring approvers automates the paperwork and never
touches those two. `test/logic_test.cljs`'s `actuation-never-auto-at-any-phase` and
`hired-approver-never-clears-actuation` fail if that ever stops being true.

## What maps to what

| game | actor |
|---|---|
| the five stations, in order | `laundry.phase/write-ops` |
| shop tiers 0–3 | `laundry.phase/phases` |
| which stations run unattended | each phase's `:auto` set |
| the six ways a garment gets held | `laundry.governor`'s six HARD checks |
| 確度 below 60% escalates | `laundry.governor/confidence-floor` |
| 洗濯表示 conflict flag | `laundry.registry/cleaning-process-forbidden-by-care-label?` |
| 差し戻す | the approval workflow resuming `{:approval {:status :rejected}}` |
| 監査台帳 | the actor's append-only audit ledger |

The skill the game teaches is the third check. The advisor proposes a cleaning process;
the garment's own care label is ground truth. Approving a conflicting plan at 取扱方法
is safe — the governor catches it at 洗浄 and the garment is never ruined — but it costs
the customer's trust. Reading the label and rejecting the plan costs only time.
`careless-play-loses-to-the-care-label` pins both halves of that: blind approval loses the
run, and not one forbidden process is ever applied even while losing.

## The street — 「営みの街」

`world.cljc` is the map the shop stands on: eight districts, **each of them a real
`cloud-itonami` governed actor**, locked until the business next door has closed its
audit. It is what makes this a game about *cleaning* rather than about laundry — the
subject widens from clothes to cars, animals, buildings, industrial plant, sewers, waste
and contaminated ground.

| ISIC | district | subject | the op that never automates | why |
|---|---|---|---|---|
| 9601 | クリーニング | 衣類 | `apply-cleaning-process` / `return-garment` | 実際に衣類を処理し、客に返す |
| 4520 | 洗車・整備 | 自動車 | `flag-safety-concern` | roadworthiness の判断に触れる |
| 9609 | ペットケア | 動物 | `actuation/finalize-referral` | 実在の人物を引き合わせる |
| 8121 | 建物清掃 | 建物 | `flag-safety-concern` | 作業員の身体に関わる |
| 8129 | 産業清掃 | プラント | `flag-safety-concern` | hazmat / 密閉空間 |
| 3700 | 下水 | 排水 | `flag-safety-concern` | 公衆衛生 |
| 3811 | 廃棄物収集 | ごみ | `dispute/request` | 相手のある紛争行為 |
| 3900 | 汚染浄化 | 土壌 | `flag-contamination-concern` | 土地と住民に関わる |

Every row was read out of that repo's own `phase.cljc` and `governor.cljc` on 2026-08-08;
`world/district-evidence` records where, so a reader can check rather than trust.

**Reading eight sibling actors side by side turns up the thing the single-shop game could
only assert: every one of them keeps at least one operation out of every phase's `:auto`
set, permanently — nine such operations across the street, each for its own reason.** The
player meets that boundary once in the laundry and then finds it again in every business
they unlock. The map is not eight variations on a theme; it is eight independent
confirmations of the same argument.

Only 9601 has a playable board today (`:playable?` in `world/status`). The other seven are
map entities with their real op tables attached.

## 3D is the authoritative view

`world3d.cljc` emits the **canonical `kami.webgpu` render-IR** — the EDN both GPU backends
consume. CLAUDE.md's 3D rule is repo-wide and mandatory: WebGPU + WGSL first, WebGL 2.0 +
GLSL ES 3.00 fallback, one render-IR, no second engine and no Canvas2D standing in for a 3D
viewport. `kami.webgl/pick-backend` chooses the runtime; there is one scene description.

The reference art is chunky flat-coloured boxes, which is exactly the IR's unit:
`ir/instance` takes a ground position, a size and a colour and the executor instances
cuboids. A shopfront is a body, an awning, a sign board and a roof block; a padlock is a
body and a shackle. No meshes, no textures.

The street is laid out as two rows of four facing the camera, far row offset half a column.
That is a requirement, not a style — from one fixed high angle two shops on the same view
ray put one entirely behind the other, and a building you cannot see is one you cannot tap.
`tapping-a-shop-resolves-to-that-district` taps each shop at its own projected centre and
demands that shop back, so any layout where one hides another fails the suite.

`world.cljc` (2D sprite IR) remains as the fallback view and still passes its own tests.

### What is and is not verified

**Verified:** the IR against the real engine's camera and picking math on the JVM, and —
since `bin/render.cljs` — the frame actually drawing in real WebGL 2.0, through the
engine's own GLSL, with pixels read back (`preview/street.png`).

**Still outstanding**, and part of CLAUDE.md's completion criteria for 3D: **WebGPU pixels**
(the path runs and validates, but the device is lost before anything is drawn — see above),
the **shadow pass**, and a **Pages smoke test**.

## KAMI SDK — what was missing

The map renders through `kotoba-lang/sprite2d`, and three things a board game cannot work
without were not in the package. They are implemented and tested upstream; the commit is
staged here as `sdk-patches/0001-sprite2d-board-support.patch` because pushing to
`kotoba-lang/sprite2d` needs repo access this session did not have.

**`kotoba-lang/webgpu`** — `sdk-patches/0002-webgpu-pick-and-camera-fit.patch`

| gap | why a tap-driven 3D scene needs it |
|---|---|
| `kami.webgpu.pick` | the render-IR mapped world→screen and nothing mapped back. Resolving a tap meant the app re-deriving the camera transform — including the non-obvious fact that an instance's `:pos` is its **ground** point while its box sits half a height above — so every app got a slightly different answer from the screen, and the error reads as a UI bug rather than as duplicated math. Pure `.cljc`, so both backends get the same picking. |
| `ir/fit-distance` / `ir/fit-rig` | a rig's `:distance` is a constant, and the horizontal field of view is the vertical one widened by the aspect. A framing tuned on 16:9 puts half the street off both sides of a portrait phone **and reports nothing** — the shops are simply not on screen. |
| `submission/pack-globals` | `kami.webgl`'s own source states the 60-float G block layout and says "computed by the CALLER" — and nothing in the engine computed it. Every consumer had to rebuild sixty floats in the right order, including the three places the camera position is smuggled into the `.w` lanes of `sun_dir`/`sun_col`/`sky`, which no shader source mentions. Get one lane wrong and the frame still renders, just lit wrongly. |

The load-bearing test is `pick-agrees-with-projection`: it projects each instance's own
centre with the same matrices the executor builds and requires the pick at that pixel to
return that instance. A picker with the wrong up vector, the wrong depth convention, or no
half-height lift still returns plausible hits — just the wrong ones, near the frame edges.

**`kotoba-lang/sprite2d`** — `sdk-patches/0001-sprite2d-board-support.patch`

| gap | why a board needs it |
|---|---|
| `:text` primitive | text existed only in the transient screen-space fx layer. A building's name and ISIC code belong to the building and move with it. |
| camera `:fixed` / `:fit` | the default camera follows a `"player"` entity, and silently anchors at world origin when there is none — indistinguishable from a correctly centred board until the board isn't at the origin. `:fit` also re-solves the scale per viewport. |
| `layout/pick` (+ `sprite-bounds`) | `draw-list` mapped world→screen and nothing mapped back, so every tap-driven game had to re-derive the camera transform, as a guess, since sprite extents were not exposed. |

Three further defects surfaced while doing it and are fixed in the same patch: `clojure
-M:test` did not start at all (an unconditional `:cljs`-only require took the suite down
before any assertion ran), the JVM stubs that `test/sprite2d_test.clj` has always asserted
about were never written, and `kotoba.sprite2d.layout` was a verbatim **fork** of
`kami.sprite2d.layout` rather than the facade the README claims — so the layout tests
exercised the copy while the painter used the original.

`test/world_ir_test.clj` runs this game's map IR against the real SDK. Revert the patch and
it does not compile.

## Files

| file | what it is |
|---|---|
| `src/itonami/isic_9601/logic.cljc` | the whole rule set: a pure `state + event -> state` reducer. No I/O, no atoms, no interop. |
| `test/logic_test.cljs` | 63 checks (nbb) |
| `test/balance.cljs` | tuning probe — plays four seeds and reports what killed each run |
| `preview/ui.cljs` | browser shell, compiled by squint; holds no rules, draws only `summary` |
| `preview/build.cljs` | squint → esbuild → one self-contained `preview/index.html` |
| `preview/smoke.cljs` | headless-Chromium check that the built page actually plays |
| `src/itonami/isic_9601/world.cljc` | the street: district registry, unlock ladder, and the 2D sprite render-IR |
| `src/itonami/isic_9601/world3d.cljc` | the authoritative view: the canonical `kami.webgpu` render-IR |
| `bin/kuriningu.cljs` | CLI — `play` / `street` (pure game, no engine needed) |
| `bin/render.cljs` | CLI — the 3D street through real WebGL 2.0, to a PNG |
| `test/world3d_test.clj` | 16 tests / 530 assertions, JVM, against the real `kami.webgpu.ir` + `pick` |
| `test/world_ir_test.clj` | 17 tests / 102 assertions, JVM, against the real `kotoba.sprite2d.layout` |
| `sdk-patches/` | the upstream `sprite2d` commit, staged until it can be pushed |
| `game.edn` | network-isekai game metadata |

Sources live once, under `src/`. They used to exist twice — a flat copy for a
network-isekai guest that the port was assumed to want, plus the namespace-shaped path nbb
and squint need — kept in sync by hand. That assumption is unverified (network-isekai is
private and its loader has not been read), and carrying a hand-synced duplicate to satisfy
a guess is worse than flattening at port time, when the loader is actually known.

## From the command line

Both halves run in a terminal, and neither is a separate implementation of anything.

```bash
npm run street                         # the eight districts and what never automates
npm run play                           # 1500 turns of the reference strategy
npm run play -- --script "tick*30 verify screen clean return"   # scripted
npm run play -- --seed 7 --turns 800

npm run render                         # WebGPU first, WebGL 2.0 fallback
npm run render -- --backend webgpu     # WebGPU only, report the failure
npm run render -- --backend webgl2     # WebGL 2.0 only
npm run render -- --width 1280 --height 720 --cleared 3
```

`play` drives `logic/reduce-event`, the same reducer the browser preview and the future
guest run, so a scripted run is an executable description of a real game rather than a
simulation of one. `--script` accepts `tick intake verify screen clean return reject renew
phase buy-*`, with `tick*40` for repeats.

`render` builds the canonical render-IR, packs it with the engine's own
`submission/pack-instances` and `pack-globals`, and draws it in headless Chromium through
`webgpu/fixtures/glsl/lit.{vert,frag}` — the GLSL the WebGL 2.0 backend actually uses.
**This is the WebGL 2.0 end-to-end check** CLAUDE.md's 3D rule asks for, in a form that
runs without a screen. It is not a second renderer: no geometry, matrices, lighting or
shading are authored in `bin/render.cljs`, only GL plumbing.

Two limits, printed on every run: the **shadow pass is not run** (a 1×1 fully-lit depth
texture is bound instead, so the image is the lit pass without shadowing), and the GPU is
**SwiftShader** because this container has no hardware one — which still exercises the real
GLSL compiler and the real GL state machine.

### Falling back

**What counts as "it drew something" is the whole check.** The first version asked for
*more than zero non-background pixels*, and a blank WebGPU frame passed it — a blank canvas
is black or transparent, not the sky colour, so every pixel of an empty frame counts as
drawn. `auto` duly reported `used webgpu · 1440000 non-background · 1 distinct colours`.
The test is now *more than one colour*: a scene of 149 coloured boxes cannot be one colour.

`device.lost` alone is not enough either — it resolves asynchronously, so reading it right
after submit sometimes sees the loss and sometimes does not. The pixels are the reliable
evidence, which is the argument for verifying rather than asking, and it applies to the
verifier too.

The default is `--backend auto`, and it means what it says: **try WebGPU, and fall back on
the evidence.** In this container that produces

```
fallback WebGPU → WebGL 2.0: device lost — unknown: A valid external Instance reference no longer exists.
         got as far as adapter → device → wgsl-compiled → canvas-configured → buffers → bindgroup → pipeline → submitted → read-back
used    webgl2
pixels  1013400 non-background · 92 distinct colours
```

The engine's own `kami.webgl/pick-backend` would **not** have fallen back here. It tests
whether `navigator.gpu` exists, which is a different question from whether the browser can
draw — and this container is exactly the case that separates them: the property is present,
`requestAdapter()` succeeds, `requestDevice()` succeeds, and the device dies on submit. A
caller routed by `pick-backend` gets a blank canvas and never retreats, because nothing
asked whether anything was drawn.

`sdk-patches/0003-webgl-honest-backend-selection.patch` adds the missing question:
`backend-from-probe` (pure, so the policy is testable without a GPU, and so the *reason*
survives into logs) and `select-backend!` (async — it acquires an adapter and a device, and
optionally submits a trivial frame, before answering). `pick-backend` stays, documented as
a hint rather than a decision; a synchronous function cannot await an adapter, so it cannot
be fixed in place.

### The WebGPU path

`--backend webgpu` runs the same frame through `fixtures/lit-shader.wgsl`. The two shaders
declare an **identical `struct G` and identical vertex/instance locations** — one is
transpiled from the other — so `pack-globals` and `pack-instances` feed both unchanged.
That is the render-IR contract holding in practice rather than on paper.

How far it gets here, printed as stages:

```
adapter → device → wgsl-compiled → canvas-configured → buffers → bindgroup → pipeline → submitted → read-back
FAILED  device lost — unknown: A valid external Instance reference no longer exists.
```

**Everything except the pixels is verified.** The canonical WGSL compiles with no messages,
and — the part worth having — `createRenderPipeline` validates the shader's `@group(0)`
bindings and all eight vertex/instance locations against the layout the engine's packers
produce. A mismatch would fail there, before any pixel exists.

**The pixels are not obtainable in this container.** Dawn drops its instance immediately
after `submit`, under every flag combination tried (`--use-angle=swiftshader`,
`--use-webgpu-adapter=swiftshader`, `VulkanFromANGLE`, `--in-process-gpu`,
`--single-process`, `--disable-gpu-sandbox`) — including on a three-line clear-to-red
shader with `layout: 'auto'`. It is the environment, not the frame.

One thing worth writing down because it cost a detour: **WebGPU is only exposed in a secure
context.** Loaded from `about:blank`, `navigator.gpu` is not adapterless — it is *absent*,
which reads exactly like a browser without WebGPU support. The renderer serves its page from
`http://127.0.0.1` for that reason alone.

### What running it from a terminal found

Three defects that the browser preview could not show, because a person looking at a board
always reads the board that is in front of them:

- **Renewing a valid certification charged ¥90.** The button only invites a press while
  the certification is lapsed, so nobody ever pressed it early — but a script that calls it
  every turn paid the fee every turn and the shop never climbed past phase 1. Now a no-op.
- **The reference strategy decided on stale state.** It inspected the board *before* the
  tick and acted *after* it, so it approved the plan it meant to reject, one turn late.
- **The road was buried in the ground.** An instance's `:pos` is the point it stands on and
  the box extends *up*, so a 1-unit ground slab standing at y=-0.5 has its top at +0.5 and
  swallows a road at 0.02. The first render showed a street with no road on it, and
  nothing anywhere reported an error.

## Run the tests

```bash
npm install                       # esbuild / nbb / squint / playwright

npm test                          # 63 unit checks
npm run balance                   # what a competent run looks like across seeds
npm run build                     # -> preview/index.html (self-contained, ~41 KB)

PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers \
  npx nbb --classpath node_modules preview/smoke.cljs   # real-browser gate

# the 3D street, against the real canonical stack (needs webgpu checked out)
west update --fetch smart webgpu
clojure -M:gpu-test

# the 2D fallback map (needs sprite2d checked out)
west update --fetch smart sprite2d
clojure -M:ir-test
```

Open `preview/index.html` in a browser to play. Nothing is fetched at runtime.

## Constraints the code is written under

- **Pure, restricted subset.** Plain maps/vectors/keywords and pure functions; no
  `defrecord`/`defprotocol`/`atom`/multimethod, no host interop, no `Math/random`. That
  intersection is what squint and the kami-clj guest subset both accept.
- **Keyword-as-function is avoided.** `(mapv :key xs)` throws under squint, where a keyword
  compiles to a plain string. Use `(mapv (fn [x] (:key x)) xs)`.
- **The RNG is threaded through state** (MINSTD, seeded from `:seed`), so a run is
  reproducible and nothing reads a clock. The multiplier is 16807 rather than the more
  familiar 1103515245 because the latter overflows a float64 mantissa against a 2^31
  modulus, which would make nbb and the browser drift apart.
- **The UI is cljs compiled to JS**, not hand-written `.mjs` — CLAUDE.md, 2026-07-14.
- **Styling is the `--hig-*` token contract** (skill `kotoba-uiux`). `preview/style.css`
  defines the tokens locally as a stand-in for `jp-go-dds.tokens/bridge-css`; the port
  swaps the definitions and keeps the rules. Note the known gap: the real bridge carries
  colour and type tokens but not `--hig-spacing-*`, `--hig-radius-*` or
  `--hig-text-*-size`, which need adding upstream rather than re-deriving.
