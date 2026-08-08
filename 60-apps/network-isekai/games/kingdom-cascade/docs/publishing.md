# Publishing Kingdom Cascade

Three surfaces, one bundle. The web build is the artefact; iOS and Android
wrap it with `kotoba-shell`.

    src/**.cljc  ──shadow-cljs──▶  web/dist  ──┬──▶  isekai.network (Pages)
                                               ├──▶  .ipa  (App Store)
                                               └──▶  .aab  (Play)

## What has actually been done

Being precise about this matters more than the runbook itself.

| Step | State |
|---|---|
| Game logic, levels, solver | **done** — 79 tests / 2183 assertions green under nbb |
| Level set gate | **done** — 3/3 levels won by the greedy solver inside budget |
| Render-IR emitter | **written, not compiled against the real renderer** |
| shadow-cljs web build | **not run** — needs the KAMI stack dependency coordinates |
| `kotoba-shell app scaffold` | **not run** |
| `.ipa` / `.aab` | **not produced** |
| App Store / Play upload | **not attempted** |

The session that wrote this had GitHub access scoped to
`com-junkawasaki/root`, so `network-awai/network-isekai`,
`kotoba-lang/kami-engine` and `kotoba-lang/shell` were all unreachable, and
the container is Linux with no Xcode and no Android SDK. Every unrun step
above is blocked on one of those two facts, not on missing design.

Note also that no upload has ever been done from this workspace by anyone:
ADR-2608072000 records `store request` / `release submit` as executable but
without a single successful run behind them. The first submission will be the
first submission.

## Step 1 — lift into network-isekai

This directory is laid out to move verbatim:

    60-apps/network-isekai/games/kingdom-cascade/
      → <network-isekai>/games/kingdom-cascade/

`src/isekai/games/kingdom_cascade/**` merges into that repo's existing
`src/isekai/` tree; nothing else in it is touched. `sample.edn` is picked up
by the genre-benchmark suite (ADR-2607140900 D1).

## Step 2 — the web build

The build needs the KAMI 2D stack. Those namespaces are, from the 2026-07-01
kami-webgpu split:

| namespace | repo |
|---|---|
| `kotoba.sprite2d` | `kotoba-lang/sprite2d` |
| `kotoba.webgpu` | `kotoba-lang/webgpu` |
| `kotoba.webgl` | `kotoba-lang/webgl` |
| `kotoba.input` | `kotoba-lang/input` |
| `kotoba.audio` | `kotoba-lang/audio` |

**Take the dependency coordinates from network-isekai's own `deps.edn`, not
from here.** They are deliberately not written into `shadow-cljs.edn` in this
directory: guessing a version and having it resolve to something is worse
than an obvious hole, because a wrong pin fails at runtime rather than at
build time.

    npx shadow-cljs release game
    # → web/dist/js/main.js + index.html

Renderer wiring lives entirely in
`src/isekai/games/kingdom_cascade/render_ir.cljc`. If upstream spells a key
differently, that file is the only one to change — nothing else in the game
knows a renderer exists.

## Step 3 — scaffold the native projects

    kotoba-shell app scaffold --manifest shell/app.edn --target ios
    kotoba-shell app scaffold --manifest shell/app.edn --target android

Scaffolding writes the capability policy from `shell/app.edn` into a JSON
asset that the Swift and Java bridges evaluate in-process. That is what keeps
the CLI's decision and the shipped app's decision the same; a policy only the
CLI enforces is not a policy. A bridge that cannot read the asset denies
everything.

Verify before going further, because these are the two things that have
silently gone wrong before:

- **The app names itself.** `CFBundleName` and `CFBundleDisplayName` must both
  read "Kingdom Cascade". Unset, `Info.plist` falls back to `$(PRODUCT_NAME)`
  and the home screen says "KotobaShell" while the app's own UI says the right
  thing. Android's `android:label` has always been correct.
  - If a permission sheet still shows an old name on a device that saw an
    earlier build, that is `usernotificationd`'s per-device cache, not the
    plist. Check the home-screen label instead.
- **The policy denies what it should.** Try a `http/fetch` from the packaged
  app and confirm it is refused.

## Step 4 — dev loop

    kotoba-shell app build --target ios       # simulator SDK, unsigned
    kotoba-shell app build --target android   # assembleDebug
    kotoba-shell app run --target ios

`app build` is for the simulator and the emulator. It cannot produce anything
installable, by design — that is why packaging is a separate command.

## Step 5 — package for distribution

    kotoba-shell app package --target ios --team-id <APPLE_TEAM_ID>
    kotoba-shell app package --target android

`app package` refuses unless signing is configured: `team-id-required` on
iOS, `keystore-required` on Android, with zero build steps executed. Both an
unsigned `.ipa` (installs on nothing) and an unsigned `.aab` (Play rejects it)
otherwise come out with exit 0, and the exit code is the only place that
difference is visible to a caller.

So before this step: fill in `:apple/team-id` in `shell/app.edn`, and put the
Android keystore where Gradle finds it. **Read both from a credential tool** —
kagi or Keychain — and do not paste them into a file that gets committed.

Confirm the outputs rather than trusting them:

    codesign --verify --deep --strict <Payload>/KingdomCascade.app
    jarsigner -verify -verbose app-release.aab

## Step 6 — store submission

    kotoba-shell store request --target ios
    kotoba-shell release submit --target ios --track testflight

Untrodden ground, as above. Expect to debug it.

## Parity check

The same level, seed and move list must give the same `core/digest` on all
three surfaces. `digest` is a polynomial rolling hash, deliberately not
`clojure.core/hash`, because that is free to differ between Clojure and
ClojureScript — which would make the check silently vacuous.

    npx nbb --classpath src play.cljs resources/levels/kc-001.edn --quiet

Take the digest that prints and compare it with what the browser build and
the packaged app produce for the same greedy playthrough. As of this writing
only the nbb number exists.
