# ADR-2607161000: kotoba-lang/secret-resolve — shared env→1Password→Keychain resolution lib

**Status**: accepted
**Date**: 2026-07-16
**Scope**: `kotoba-lang` organization — new repo `secret-resolve`; consumers
`scripts/b2-creds.cljs` (com-junkawasaki/root) and
`kotoba-lang/com-backblaze-secure`.

## Context

ADR-2607152322 (`kotoba-lang/com-backblaze-secure`) built its own
`env → 1Password → Keychain` credential resolver, duplicating logic already
present in this superproject's `scripts/b2-creds.cljs`. Both copies used the
exact same spec shape (`manifest/repos.edn`'s `:b2 :credentials`:
`{:order [...] :env {...} :1password {...} :keychain {...}}`), independently
hand-copied rather than shared.

Building `com-backblaze-secure` surfaced two real, reproducible bugs in that
logic, confirmed empirically in that session:

1. `security find-generic-password -s <service> -g` prints the item's
   password in cleartext as part of its human-readable attribute dump.
2. Node's `child_process.execFileSync` **inherits a child's stderr straight
   through to the caller's own stderr** unless `:stdio` is explicitly
   overridden — Node's own documented default, easy to miss. Combined with
   (1), this means every call to the "combined single-item Keychain"
   resolution path leaked the plaintext secret onto whatever was watching
   the calling process. This is exactly how a B2 application key leaked
   into a chat transcript in that session.
3. (Separately) a stale `op` CLI session does not fail fast on `op read` —
   it hangs indefinitely on an interactive re-auth prompt a non-TTY child
   can never answer.

`com-backblaze-secure`'s own copy was fixed in place. A workspace-wide
search (this session) confirmed **`scripts/b2-creds.cljs` has the identical
bug** — it is on the exact same `security ... -g` + un-scoped `execFileSync`
(via `scripts/nbb_compat.cljs`'s `sh`, itself built on `spawnSync` with no
`:stdio` override) path, and is invoked by `manifest/west_annex.cljs` for
every DataLad `annex-get`/`annex-drop`. The same search also found this
exact `execFileSync`-without-`:stdio` shape once more, independently,
inside `com-backblaze-secure/b2_cli.cljs` — a third hand-copy of a fix that
should have had one home from the start.

Separately, the search surfaced that `manifest/west_annex.cljs`'s call to
`nbb scripts/b2-creds.cljs --json` passes no `--classpath`, and
`scripts/b2-creds.cljs` cannot load `clojure.java.shell` without one —
meaning this path was already broken independent of the leak bug (confirmed
by direct reproduction: `nbb scripts/b2-creds.cljs --json` from repo root
fails with `Could not find namespace: clojure.java.shell`).

## Decision

Extract the resolution logic into a new shared library,
**`kotoba-lang/secret-resolve`**, and make both known duplicates depend on
it instead of carrying their own copy:

- `secret-resolve.resolver` (`.cljc`, pure, no I/O) — the ordered-fallback
  walk over an injected `{source-key (fn [ref] value)}` map. Portable and
  unit-tested on the JVM against fake sources, no subprocess calls
  involved.
- `secret-resolve.exec` (`.cljs`, Node-only) — the safe `execFileSync`
  wrapper: explicit `:stdio ["ignore" "pipe" "pipe"]` (fixes bug 2 for
  every caller, permanently, in one place), a default 5s timeout (fixes
  bug 3), and a generous `maxBuffer`.
- `secret-resolve.sources` (`.cljs`) — concrete `env`/`op read`/
  `security find-generic-password` source functions built on
  `secret-resolve.exec`, generalizing the "combined single item" vs.
  "separate item per field" Keychain layouts already in use.
- `secret-resolve.core` (`.cljs`) — `resolve1`/`resolve-map!` convenience
  wiring the above as the default source map.

`scripts/b2-creds.cljs` is refactored to call this library instead of
carrying its own `from-env`/`from-1password`/`from-keychain`, and its
invocation (and `manifest/west_annex.cljs`'s subprocess call into it) is
fixed to pass the `--classpath` this library requires — resolving the
pre-existing classpath breakage as a necessary consequence of wiring in an
external dependency, not a separate unrelated change.

`kotoba-lang/com-backblaze-secure`'s `credentials.cljs` is refactored to
depend on `secret-resolve.core` instead of its own copy; its `b2_cli.cljs`
keeps its own `run`/subprocess wrapper for the `b2` CLI itself (a different
concern — invoking the B2 data-plane tool, not resolving *which* secret to
hand it) but nothing there needs to change since it never touched
`security`/`op` directly.

## Consequences

- One implementation, one place to fix the next bug in this class instead
  of three.
- `scripts/b2-creds.cljs` and `manifest/west_annex.cljs`'s DataLad annex
  path stop leaking secrets to stderr and gain the same hang protection
  `com-backblaze-secure` already had.
- New consumers (any future repo needing env/1Password/Keychain
  resolution) depend on this library rather than re-copying the pattern a
  fourth time.
- A workspace-wide search (this session) also found two *other*,
  out-of-scope instances of the same unguarded-`execFileSync` class of bug
  — `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/ops_keys.cljc` (`kagi
  get`/`kagi add` calls with no `:stdio` override) and an independently
  hand-rolled (already-safe, differently-shaped) fix in
  `orgs/gftdcojp/local-manimani/cljs/server.cljs`. Neither is touched by
  this ADR — they belong to different repos/teams and are reported
  separately rather than fixed unilaterally.

## Verification

- `secret-resolve`'s own `test/`: ordered fallback, throw-free `nil` on
  total miss, fall-through on a throwing source, `resolve-map!` naming the
  failed field in `ex-data` — all against fake sources, no subprocess
  calls.
- Manual smoke test (this session): `secret-resolve.core/resolve-map!`
  against the real `gftdcojp-m365-annex` Keychain entry resolves both
  fields correctly with **zero** secret material appearing on stderr
  (confirmed by grepping a separately-captured stderr stream for known
  substrings of the real secret).
- Manual smoke test: a spec pointing at a nonexistent 1Password item fails
  in ~5s with a clear `ex-data`-bearing error, not a hang.
- `nbb manifest/west_annex.cljs annex-get`'s underlying `nbb
  scripts/b2-creds.cljs --json` call succeeds with the corrected
  `--classpath` (previously reproduced failing with `Could not find
  namespace: clojure.java.shell`).
