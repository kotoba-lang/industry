# ADR-2607012115: kotoba-shell — CLJC restoration scaffold (`kotoba-lang/shell`)

Status: Accepted

## Context

manimani's native app shell was Tauri v2 with Rust commands. Its intended
successor, `crates/kotoba-shell` — proposed in `docs/ADR-kotoba-shell-aiueos-safe-kotoba.md`
and `docs/ADR-kotoba-shell-aiueos-safety-clj.md` (both `kotoba-lang/kotoba`,
2026-06-28, Status: Proposed) — had a real, substantial implementation by
2026-06-29: an 11,179-line `crates/kotoba-shell/src/lib.rs`, a working macOS
WKWebView dev runner, and a full CLI (`kotoba shell check/plan/dev/build/...`).
manimani was its explicit MVP dogfood target (both ADRs' §9 "MVP" / §11
"manimani への適用").

Both the Tauri foundation and its replacement are now gone. `kotoba-lang/kotoba`
PR #259 ("Remove legacy Rust workspace", `604896171b`, 2026-07-01 10:45 JST)
deleted the entire legacy Rust workspace in one commit, including
`crates/kotoba-shell` itself alongside `kotoba-auth`/`kotoba-core`/
`kotoba-datomic`/`kotoba-edn`/`kotoba-ipfs`/`kotoba-ipns-record`/`kotoba-cli` —
the same crates manimani's `tauri/src-tauri/Cargo.toml` depends on.

Unlike two adjacent restoration efforts that happened the same day:

- kami-engine's deleted Rust crates got a full restoration plan
  (ADR-2607010930 Phase 4): 63 new `kotoba-lang/{repo}` scaffold repos
  (`os`, `app`, `core`, `bridge`, ...).
- Some of `kotoba-lang/kotoba`'s *own* PR #259 casualties were already
  restored as real CLJC ports, not just scaffolds: `kotoba-rt` → `kotoba-lang/rt`,
  `kotoba-turn` → `kotoba-lang/turn`, `kotoba-net` → `kotoba-lang/net`,
  `kotoba-signal` → `kotoba-lang/signal` (registered in `manifest/repos.edn`
  `:extra-projects`, see the "kotoba-lang media/networking substrate" comment
  block there).

...`kotoba-shell` had **no restoration path anywhere**. Verified 2026-07-01:
zero matches for `kotoba-shell` or `aiueos` in a GitHub code search across the
entire `kotoba-lang` org, no matching repo name among all 243 org repos, no
open PR/branch reviving it. This left manimani with no working native launch
path at all — confirmed by directly attempting to launch it (Tauri: the
already-compiled debug binary opens a menu-bar app but a blank dashboard window,
because its own already-compiled Swift dev-runner artifact predates this same
deletion; a from-scratch `tauri dev` rebuild fails immediately on the missing
`kotoba-auth` path dependency).

## Decision

Scaffold `kotoba-lang/shell` as a zero-dependency portable `.cljc` placeholder,
following the exact convention used for the kami-engine Phase-4 restorations
(`deps.edn` + README + `.gitignore` + `src`/`test` skeleton, scaffold-only
status), and register it in `manifest/repos.edn` `:extra-projects` +
`manifest/west.yml`.

This intentionally does **not** port the Rust implementation in this pass. It
reserves the name/path and records the gap in writing so the restoration
doesn't fall through the cracks a second time. When restoration actually
happens, it should follow the higher-fidelity precedent already set by
`kotoba-lang/rt` (a real ported contract with tests and a documented scope
boundary, not just an empty scaffold): start with the portable core — manifest
parsing, the `ShellPlan` model, target/capability resolution — before any
native WebView/Xcode/Gradle packaging, per ADR-2607010000's four-layer
discipline (EDN/CLJC is the authority; native code only executes
already-validated requests).

## Scope

In scope now: repo scaffold + manifest registration only.

Out of scope (follow-up work, tracked here so it isn't lost):

- The actual CLJC port of manifest parsing / `ShellPlan` / capability model /
  dev-session generation.
- Native WKWebView/Xcode/Gradle host adapters (Rust, Swift, or otherwise) that
  execute validated requests from that CLJC authority — including the exact
  bug that crashed the last real dev-mode run twice today (17:19–17:22 JST,
  `Swift WKWebView runner exited with status signal: 6` then `15`): the
  generated dev runner calls `UNUserNotificationCenter.current()` from an
  unbundled `swift <script>` process, which has no valid `mainBundle` and
  throws `NSInternalInconsistencyException: bundleProxyForCurrentProcess is
  nil`. Any future native adapter must guard that call (e.g. skip/stub when
  `Bundle.main.bundleIdentifier == nil`).
- `kotoba-auth`, `kotoba-core`, `kotoba-datomic`, `kotoba-edn`, `kotoba-ipfs`,
  `kotoba-cli`, `kotoba-ipns-record` — the rest of PR #259's casualties are
  *also* still unrestored. Out of scope for this ADR; noted for whoever picks
  them up next.

## Consequences

- manimani still has no working native launch path today. The nearest working
  alternative, verified 2026-07-01: serve `tauri/dist/index-reframe.html` +
  `main.js` over localhost and open it in a regular browser — confirmed
  rendering real content with zero console errors.
- Reserves `kotoba-lang/shell` so future restoration work has a home and
  doesn't collide with kami-engine's identically-shaped scaffold effort.

## Related

- `docs/ADR-kotoba-shell-aiueos-safe-kotoba.md`, `docs/ADR-kotoba-shell-aiueos-safety-clj.md`
  (`kotoba-lang/kotoba`) — original design + the now-deleted Rust implementation
- `docs/rust-crate-migration.md` (`kotoba-lang/kotoba`)
- ADR-2607010930 (kami-engine Phase-4 restoration pattern this scaffold mirrors)
- `kotoba-lang/rt`, `kotoba-lang/turn`, `kotoba-lang/net`, `kotoba-lang/signal`
  (sibling PR #259 restorations; higher-fidelity precedent for actual
  restoration work)
