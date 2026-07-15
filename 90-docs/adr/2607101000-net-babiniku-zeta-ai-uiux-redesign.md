# ADR-2607101000: net-babiniku — zeta-ai-modeled UI/UX redesign (web) + mobile/store direction

## Status

Web IA redesign: done, deployed-ready (this PR). Mobile (Tauri 2 iOS/Android) and store
submission (App Store / Google Play, gftdcojp): decided direction, not yet built — tracked
as follow-up work, not claimed complete here.

## Context

User direction (2026-07-10): "net-babiniku を zeta ai と同じ uiux に。ただ babiniku は kami
engine で vrm 前提" — redesign net-babiniku's UI/UX to match zeta-ai (zeta-ai.io/en, Scatter
Lab's AI-character chat/roleplay app — 500k+ characters, Home/Chat/Create/My Page top nav,
hashtag-tagged character-card grid), while keeping babiniku's own differentiator: a live
kami-engine-rendered VRM avatar (not zeta's static character art) as the render substrate.
Follow-up direction in the same session: also publish to iOS/Android under the gftdcojp
org (both Apple Developer Program and Google Play Console already contracted), technical
approach Tauri 2 + cljs/re-frame/reagent (not React Native — would require abandoning the
kami-webgpu/reagent stack this repo and network-isekai already share), and content policy:
13+ age rating, no adult content (already true of this repo's SFW-only LLM chat tier per
ADR-2607070800).

### Where the product stood before this ADR

Milestones 0–4 (see README.md) had a real containment architecture (governor HARD/SOFT
gate, monetization/embodiment honest-default HARD-holds), a real LLM chat backend
(murakumo-backed, 準備中 until its token is provisioned), and real live VRM avatars for
every roster character — but the page itself was a single always-visible scroll: hero →
pricing → every character's full card (chat transcript + demo buttons + tip-claim +
edit/export/delete, all expanded) → a collapsed containment/audit-ledger section. There
was no navigation, no character-discovery grid, and no separation between "browse
characters" and "chat with one" — an engineering-dashboard shape, not a consumer app's.

### zeta-ai's actual IA (verified via WebSearch + WebFetch of zeta-ai.io/en; the Chrome
extension was not connected this session, so pixel-level visual detail — exact colors/
fonts — was not directly observed, only structural/textual content)

Top nav: Home / Chat / Create / My Page, plus a category-pill filter bar. Main content:
a responsive grid of character cards (portrait thumbnail, follower/engagement count, name,
one-line tagline, several hashtag-style genre/trait tags). This is a browse-then-chat
IA — the grid is for discovery, a separate screen is for the actual conversation.

## Decision

### 1. Adopt zeta-ai's four-tab IA, keep every existing containment gate unchanged

Added `:ui/route` to app-db (`:home`/`:chat`/`:create`/`:mypage`), a `:ui/navigate` event,
a `:ui/route` sub, and a sticky top-nav (`babiniku.ui.views/nav-bar-view`) with those four
tabs — matching zeta-ai's actual top nav labels/order. `babiniku.ui.views/root` switches on
the route instead of always rendering one long scroll. This is a **presentation-only**
change: every real gate (`babiniku.governor/review-turn`, `babiniku.monetization`'s
honest-default HARD-hold, `babiniku.embodiment`'s Otete-only gate) is dispatched through
exactly the same events as before — verified live (Playwright, headless Chromium with
`--enable-unsafe-webgpu --use-angle=swiftshader`, since default headless Chromium has no
GPU adapter): clicking a character's dialogue-demo button from the new Chat tab still
produces a real `:allowed` verdict in the audit ledger, now surfaced under My Page.

- **Home** (`home-view`): the zeta-style character grid. Each card
  (`character-grid-card-view`) shows an avatar-monogram (see below), name, tagline, real
  derived tags (subscription tiers, giemon body, "#created" if user-made — no fabricated
  genre data, honest-default), and a real message count from `:chat-transcripts`. Clicking
  a card calls the same `babiniku.stage/select-avatar!` every prior selection path used,
  then navigates to Chat.
- **Chat** (`chat-view`): the character currently on stage (`babiniku.stage/selected`),
  reusing `character-card-view` unchanged (transcript/input/demo buttons/tip-claim/edit-
  export-delete all still there) — this is a navigation change around an existing
  component, not a rewrite of it. No character on stage shows an honest empty state
  ("No character selected yet" + a link back to Home) instead of silently rendering
  nothing.
- **Create** (`create-view`): `create-character-view`, unchanged, as its own screen.
- **My Page** (`my-page-view`): `character-card-view` scoped to user-created characters
  only (management: edit/export/delete/tip-claim — the built-in four have none of those
  controls and are already reachable from Home/Chat), plus the containment/audit-ledger
  transparency section (`containment-section-view`) moved here from the bottom of the old
  single page. Same components, same gates, filed under a tab instead of always inline.

### 2. Avatar-monogram grid thumbnails, not fabricated portraits

zeta-ai's cards show a character portrait image. babiniku has no equivalent: every
character's actual appearance only exists as a live WebGPU-rendered VRM avatar (one
canvas, one avatar on stage at a time — `babiniku.web`'s render loop). Pre-rendering N
thumbnail images (or fetching every roster character's full `.vrm` file just to show a
grid before selection) was rejected as expensive and orthogonal to this redesign's scope.
Decision: a deterministic HSL-colored circular initial per `character-id`
(`avatar-color`/`avatar-monogram-view`) — an honest placeholder (the same convention as
Slack/Discord avatar fallbacks), not a claim about appearance the app can't back up. This
follows the same honest-default principle as the monetization/embodiment HARD-holds and
chat's never-fabricate-a-reply rule already in this codebase.

### 3. Stage repositioning is CSS-only, keyed off a `data-route` attribute

`#stage-wrap`/`#stage-canvas` (public/index.html) stay exactly the DOM nodes
`babiniku.web`'s WebGPU render loop already owns and never re-renders — this ADR does not
touch that ownership or the render loop itself. `babiniku.ui.views/root` stamps
`document.documentElement.dataset.route` on every render (a small, idempotent, deliberate
DOM side-effect — same pragmatic-effect-in-render spirit as this app's other direct DOM/JS
interop, e.g. `babiniku.broadcast/toggle!`). `public/index.html`'s CSS keys off
`html[data-route=...]` to hide the stage entirely on Create/My Page (neither needs it —
also switches those two routes to a full-width single column, even on desktop) and to
shrink its mobile-stacked height on Chat vs. Home. No JS/render-loop change was needed.

### 4. Mobile: Tauri 2 (not React Native), targeting gftdcojp App Store + Google Play

Confirmed via user direction this session (AskUserQuestion): both Apple Developer Program
and Google Play Console are already contracted under gftdcojp, and the mobile technical
approach is Tauri 2 wrapping the existing shadow-cljs/reagent/re-frame/kami-webgpu build —
explicitly *not* a React Native rewrite, which would mean abandoning the WebGPU render
substrate this repo shares with network-isekai. Local toolchain confirmed present and
capable (Xcode 26.6, Rust 1.96 + `tauri-cli` 2.11.3, Android SDK + Android Studio) — this
is buildable locally, not blocked on tooling. **Not built yet** — this ADR records the
decided direction; `cargo tauri android/ios init` scaffolding, on-device WebGPU
verification (the actual risk: iOS WKWebView's WebGPU support and Android System
WebView's are both new/flag-gated, unverified in this app specifically), and the
store-submission content-rating questionnaires (interactive web forms, no general API —
flagged to the user to complete themselves once drafted) are separate follow-up work
(tracked as open tasks, not claimed done by this ADR).

### 5. Content policy: 13+, no adult — already true, now also a store-metadata decision

net-babiniku's LLM chat tier is already SFW-only (`babiniku.persona`/`functions/api/
chat.js`, ADR-2607070800) — this direction doesn't change app behavior, it fixes the
age-rating/content-rating answers to submit alongside the mobile builds (13+/Teen, no
adult content declared) — tracked under the same follow-up task as store submission.

## Alternatives considered

- **Rebuild the whole page as a client-side router library (reitit/bidi, hash routing,
  etc.)** — rejected: a single `:ui/route` keyword in app-db plus a `case` in `root` is
  the entire routing surface this app needs (four fixed tabs, no deep-linkable per-
  character URLs requested), and a routing library is unjustified weight for that.
- **Give each character card a pre-rendered PNG thumbnail** (baked at build time from a
  headless WebGPU capture) — rejected for this pass: real engineering work (a build-time
  render-to-texture pipeline) disproportionate to a UI/UX redesign task; left as a
  possible follow-up if the avatar-monogram placeholder proves insufficient.
- **React Native for mobile** — rejected per explicit user direction and because it would
  mean maintaining a second render stack instead of reusing kami-webgpu/cljs.
- **Capacitor instead of Tauri for mobile** — considered (offered as a recommended default
  in AskUserQuestion) but rejected per explicit user direction in favor of Tauri 2, which
  the user specified directly (cljs/re-frame/reagent + Tauri).

## Consequences

- Every existing `nbb` gate (`governor`/`monetization`/`embodiment`/`character`/`persona`/
  `roster-sync`) and `clojure -M:lint` still pass unchanged — this ADR touched no domain
  `.cljc` namespace, only `babiniku.ui.{db,events,subs,views}` and `public/index.html`.
  Verified: `nbb governor`, `nbb character`, `clojure -M:lint`, `npx shadow-cljs release
  app` (clean compile, only pre-existing warnings in `babiniku.web`/an external `glb` lib,
  unrelated to this change), and a headless-Chromium Playwright pass across all four tabs
  (desktop + mobile viewport) with zero console errors, confirming the governor demo
  button still produces a real ledger entry end-to-end through the new nav.
- The old single-scroll page (`roster-view` + inline `root`) is fully replaced, not kept
  behind a flag — no user-facing regression is expected (every feature moved, none
  removed), but this is a real behavior change for any returning visitor's muscle memory.
- Mobile (Tauri scaffold, on-device WebGPU verification, store listing/rating
  questionnaires, actual submission) remains open follow-up work — this ADR does not
  claim an iOS/Android build or store listing exists yet.
- A future ADR should record the Tauri mobile shell's actual WebGPU verification result
  (works / needs a fallback renderer / needs a native plugin) once built — the single
  biggest technical risk this ADR identifies but does not resolve.

## Addendum (2026-07-10, same day): Tauri 2 mobile shell scaffolded; on-device WebGPU verification NOT completed

Following the web redesign above (merged/deployed, PR #60), `cargo tauri init` +
`cargo tauri android init` + `cargo tauri ios init` were run against net-babiniku's
existing shadow-cljs build (`src-tauri/`, identifier `cloud.gftd.babiniku`,
`frontendDist: "../public"`, `beforeDevCommand: npx shadow-cljs watch app`,
`beforeBuildCommand: npx shadow-cljs release app`) — both native projects
(`src-tauri/gen/android`, `src-tauri/gen/apple/app.xcodeproj`) generated successfully.
Full toolchain was installed and confirmed present: Android NDK 27, all four Android Rust
targets (`aarch64`/`armv7`/`i686`/`x86_64`-linux-android*), all three iOS Rust targets
(`aarch64-apple-ios`, `aarch64-apple-ios-sim`, `x86_64-apple-ios`), `ios-deploy`, and a
freshly-downloaded iOS 26.5 Simulator runtime. This is real, durable progress — not just
a stub — and is the honest state of this repo's mobile scaffold as of this addendum.

**What could NOT be verified this session, and why (environment, not app):** the actual
question this ADR's "biggest open risk" section asks — does kami-webgpu's WebGPU render
loop work inside Tauri's iOS WKWebView / Android System WebView — was not answered.
Attempting to answer it hit three separate failures, all traced to the SAME root cause
(confirmed via `top`: this session's host machine was under extreme concurrent load —
`Load Avg: 141.76, 320.77, 459.08`, "90 stuck" processes system-wide — consistent with
this repo's own documented parallel-agent/fleet operating model running many other
sessions on the same physical machine at the same time, not a fault in net-babiniku, Tauri,
or this scaffold):

1. An iOS Simulator device was created and reported `(Booted)`, but subsequent
   `xcrun simctl`/`CoreSimulator` XPC calls (even a plain `simctl list devices`) hung
   indefinitely and left several `simctl` client processes stuck in uninterruptible sleep
   — `cargo tauri ios dev` blocked on exactly this XPC call and was killed after being
   unresponsive for 10+ minutes.
2. The Android emulator (`Kotoba_Runtime_API_35` AVD, system image reinstalled clean after
   an earlier corrupted/partial install was found and fixed) booted far enough to report
   GPU vendor/renderer info (SwiftShader/ANGLE software rendering) but then crash-looped
   on `Failed to create window surface for DisplaySurfaceGl` and the emulator process
   itself died before Android's `sys.boot_completed` ever went true, in both windowed and
   `-no-window` headless modes.
3. `cargo check --target aarch64-apple-ios-sim` for the new `src-tauri` crate itself (a
   much lower bar than actually booting anything — pure cross-compilation) spawned dozens
   of `rustc`/`sccache` child processes that sat at 0% CPU for 10+ minutes even with the
   sandbox disabled for that one call — the CPU-starvation explanation above, not a
   toolchain defect (`sccache --show-stats` showed zero requests executed, i.e. nothing
   had even reached the compiler yet).

**Conclusion:** the scaffold is real and structurally complete; the toolchain is real and
fully installed; but this ADR still cannot claim WebGPU-in-Tauri-mobile-WebView works or
doesn't — that verification needs to be re-attempted on a less-contended machine (or a
dedicated CI runner / a real physical device, which sidesteps simulator/emulator
virtualization entirely). This is the single most important remaining unknown before any
app-store submission work is worth starting, and it is explicitly **not resolved** by this
addendum — do not treat the scaffold's existence as evidence the render path works on
mobile.

## Related

- `90-docs/adr/2607051800-net-babiniku-vrm-vtuber-design.md`
- `90-docs/adr/2607071600-net-babiniku-service-completion-design.md`
- `90-docs/adr/2607062200-net-babiniku-onlyfans-style-creator-monetization.md`
- `90-docs/adr/2607062210-net-babiniku-giemon-humanoid-embodiment-procurement.md`
- `90-docs/adr/2607070800-net-babiniku-llm-backend-claude-sonnet-5.md`
- `orgs/jk-luxury/net-babiniku/90-docs/adr/0001-net-babiniku-architecture.md`

## Addendum (2026-07-12): 上流トポロジー整備により本 ADR の CSS 戦術群は陳腐化予定（ADR-2607122200）

ADR-2607122200 が liquid-glass-ui / shitsuke / kotoba-ui 側に本 ADR 実装中に露見した
構造問題の恒久解を実装した:

- 本 ADR 実装が堆積させた **compound-selector 上書き
  （`.liquid-glass__toolbar.app-toolbar` 等）と「後注入ライブラリ CSS に負ける」
  問題は、ライブラリ CSS の `@layer kotoba.hig, kotoba.glass` 格納で構造的に不要**
  になった（app の unlayered CSS が常勝）。
- 本 ADR 実装が踏んだ **`lg/text-field` keystroke 喪失バグは上流で根治**
  （根本原因: `:value`+`:on-input` は reagent の controlled-input 安全機構を外す。
  liquid-glass-ui PR #3）。views.cljs の手書き `glass-text-field`/`glass-text-area`
  フォークは移行時に retire できる。
- hex 直書き（`#0b0c10`/`#7ee787`/`--pop-gradient` 等）は `shitsuke.hig` semantic
  tokens + `kotoba-ui.theme`（accent 1 map）で置換可能になった。

**net-babiniku の実移行（index.html の 412 行インライン CSS 解体 → kotoba-ui
shell/theme への載せ替え）は ADR-2607122200 D7 の follow-up として未着手**。着手時は
`kotoba-ui/docs/agent-guide.md` を正典とする。

## Addendum (2026-07-12, 同日): 実移行完了 — kotoba-ui shell/theme 載せ替え・本番デプロイ済み（ADR-2607122200 D7）

PR #148（main `d4f648d`、サーバ側マージ）で実移行が完了し、本番
https://net-babiniku.pages.dev/ に反映された:

- **index.html の `<style>` 420 行 → 115 行**。`.liquid-glass__*` compound-selector
  上書き・`--pop-*`・手発明 hex（`#0b0c10`/`#7ee787`/`#f0883e`）・手書き
  `.hero`/`.top-nav`/nav-tab/element 既定はすべて削除。残した app CSS は 2 ペイン
  stage レイアウト・状態色（`--hig-palette-green`/`--hig-palette-orange`）・brand
  wordmark flourish 等の app 固有分のみ（`--hig-*` var 経由、unlayered で常勝）。
- **views.cljs は `kotoba-ui.core` + `uikit.core` のみを require**。
  `glass-text-field`/`glass-text-area` フォークと `ensure-liquid-glass-style!`
  runtime 注入を retire、layout は `ui/app-shell`/`ui/hero`/`ui/section`/`ui/grid`/
  `ui/stack`、px font-size は `.hig-*` utility classes へ。
- **theme は build-time 静的生成**: `scripts/gen-theme-css.cljs`（nbb）が
  `{:accent "#FF3CAC" :appearance :dark}` から `public/css/theme.css`（600 行、
  `@layer kotoba.hig, kotoba.glass`）を生成し render-blocking `<link>` で読み込み
  （FOUC なし）。`npm run theme-css`、deploy チェーンに配線済み。
- **検証**: lint 0/0・bb domain gates 10/10・cljs 137/137・PR CI 4 jobs green。
  実ブラウザ（Playwright/実 Chromium）19/19 — 320px 全タブ横スクロールなし
  （scrollWidth==320）、active nav tab コントラスト 6.49:1、高速タイピング
  keystroke 喪失ゼロ（5ms/char まで確認）、dark `--hig` tokens 適用。
  本番反映確認済み（style 115 行・`data-appearance="dark"`・theme.css 56KB 配信）。
- 唯一の可視コピー追加: `ui/hero` の要求する h1 として brand 文字列
  「net-babiniku」（従来 h1 なし）。他のコピーは byte 同一。
- 注記: Playwright の headless *shell* は RAF を絞り reagent のバッチ再描画を
  飢餓させるため keystroke 喪失の偽陽性を出す — E2E は実 Chromium
  （`channel: "chromium"`）で行うこと。
- west pin 前進済み（`07f43990`、verify OK）。
