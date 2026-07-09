# ADR-2607072345: `kotoba wasm run`'s host-import surface stops being all stubs

## Status

Accepted (implemented)

## Context

Owner directive (2026-07-06/07): raise the maturity of aiueos, kototama,
and kotoba-lang, in that priority order. Independent research agents
assessed each against its own stated vision (not an external ideal):
aiueos ~15-35% (revised upward after finding a real, subprocess-tested
decision service the first pass had missed by reading a stale local
checkout), kototama ~45%, kotoba(-lang) ~20-25%. kotoba's single biggest
concrete gap: of the ~32 host imports its `capability_contract.edn`
declares, only `kgraph-*` (4 ops) had a real implementation anywhere.
Every other declared import — `notify-show`, `clipboard-read/write/
write-str`, `http-fetch`, `keychain-read/write`, `fs-read/write`,
`log-write`, `clock-monotonic`, `random-bytes`, `topic-publish/poll/
take/count`, and (after a separate, same-week landing) kototama's
`gen-keypair`/`sign`/`verify`/`sha256-hex`/`http-post`/`log-read` — was
wired in `kotoba.launcher/wasm-run-result*` as `kotoba.wasm-exec/
stub-host-function`: an unconditional 0 return, regardless of the
guest's arguments. A `.kotoba` program could declare and call any of
these, pass a static capability check, compile to a real `.wasm`
binary, and run — and nothing it asked for would ever actually happen.

## Decision

### Real implementations for 22 of the ~26 non-kgraph imports

New `kotoba.wasm-exec/default-host-state` (injectable atoms: clipboard,
keychain, notifications, an append-only log, a per-topic-id i64 queue
map, plus a sandboxed temp-directory filesystem root) and
`real-op-effects`/`real-host-functions`, wired into `wasm-run-result*`
in place of the stub for these ops:

- `notify-show` — appends `{:code :at}` to an observable log (the wire
  ABI's single i32 arg carries no message text to actually display; this
  is the honest limit of what that shape can record, not a shortcut).
- `clipboard-read`/`write`/`write-str` — a real in-memory clipboard.
- `keychain-read`/`write` — a real in-memory key→value store.
- `fs-read`/`fs-write` — real filesystem I/O, confined to a sandbox root
  via `safe-path` (canonicalizes and rejects anything that would resolve
  outside the root — a `..`-escape attempt is a plain denial, not an
  exception, and never touches the real filesystem outside the sandbox).
- `http-fetch`/`http-post` — real `java.net.http.HttpClient` requests.
- `log-write`/`log-read` — a real append-only in-memory log.
- `clock-monotonic` — `System/nanoTime`.
- `random-bytes` — `java.security.SecureRandom`.
- `topic-publish`/`poll`/`take`/`count` — a real per-topic-id queue of
  raw i64 messages (the wire ABI has no byte-buffer for this pub/sub
  surface, only `[:i32 :i64]`/`:i64`, so 0 doubles as both "empty" and a
  legitimately published 0 — an existing tradeoff the wire ABI already
  makes elsewhere, not new here).
- `gen-keypair`/`sign`/`verify`/`sha256-hex` — `kotoba-lang/ed25519` +
  JDK `MessageDigest`, the identical algorithm choices `kototama.tender`
  already proved (new `io.github.kotoba-lang/ed25519` dependency, same
  pin `kototama` uses).

Only the device-access quartet (`pci-config`/`dma-map`/`irq-subscribe`/
`mmio-map`) keeps `stub-host-function` — permanently host/hypervisor-only
(no JVM process can honor it), not a placeholder awaiting an
implementation, matching `kototama.tender`'s identical scoping decision.

Guarded through the existing `kotoba.lang.capability-host/guard-call`
machinery, unchanged in kind from `kgraph-*`'s existing fail-closed
pre-flight + per-call + receipted dispatch (`guard-kgraph-call` renamed
to `guard-host-call`, since it was never actually kgraph-specific).
Crypto/network/filesystem effects are wrapped in `try`/`catch`,
returning `-1`/`0` on a malformed-input exception rather than letting it
escape uncaught through Chicory and crash the whole `wasm run` process —
a host function is directly reachable from untrusted guest memory
contents, so a guest passing garbage-length arguments must get a
well-defined answer, not take the host down.

### A real, previously-undetected bug found and fixed along the way

Wiring these ops through `guard-call` for the first time immediately hit
`{:kotoba.host/denied :unsupported-kind}` / `:malformed-requested` for
every one of them. Root cause, in `kotoba-lang/kotoba-lang`'s
`kotoba.lang.capability-values/effect-for-kind`: this map is a second,
separate registration `kotoba.runtime/op->kind`'s kind keyword must
ALSO appear in for `guard-call` to accept it at all. 15 kinds were
missing — all 9 aiueos default kernel capabilities (`log-write`,
`clock-monotonic`, `random-bytes`, `topic-publish`, `topic-subscribe`,
`pci-config`, `dma-map`, `irq-subscribe`, `mmio-map`, registered in
`op->kind` since ADR-2607022700) plus this ADR's own 6 new actor-host
kinds. Every one of the 9 aiueos ops had been silently un-runnable at
capability-guarded RUN time since the day they were added — not just
newly, not just this ADR's fault — because nothing had ever actually
guard-called them: `kotoba.aiueos-kernel-caps-test` only ever exercised
`wasm emit`'s STATIC compile-time gate (which validates a `:capability`
string directly against a policy's granted-capabilities set — it never
touches `op->kind` or `effect-for-kind` at all). Fixed in
`kotoba-lang/kotoba-lang#12`: added all 15 missing entries, plus a test
proving `intersect-grants` no longer denies any of them as
`:unsupported-kind`. `kotoba`'s `kotoba-lang` pin bumped to the fix.

### Test discipline: real compiled `.kotoba` guests, real side effects

New `test/kotoba/real_host_providers_test.clj`, one minimal `.kotoba`
demo + granting policy per op (reusing existing demos — `demo_notify`,
`demo_providers`, kototama's `demo_actor_host_*` — where one already
existed). Each test compiles the source, instantiates it against a real
`default-host-state`, runs it through the actual Chicory `Instance`, and
asserts on the REAL observable effect: a file genuinely readable back
off disk at the sandbox path, a real HTTP server (JDK's built-in
`HttpServer`, zero extra deps, bound to a fixed local port since a
`.kotoba` source file can't embed a dynamically-chosen one) actually
receiving the guest's request and body, two `SecureRandom` draws
genuinely differing, a real queue's publish/poll(peek)/take(remove)/
count semantics holding across four separately-compiled single-op
guests sharing one `state` map (mixing this op's i32 and others' i64
results in one `.kotoba` `let` hits this compiler's WASM type
validator — one op per compiled guest sidesteps it, not a workaround for
anything wrong with the real implementation). 144 tests / 780
assertions green, `clj-kondo` clean.

## Consequences

- (+) `kotoba wasm run` now does what a `.kotoba` program that declares
  these capabilities and gets a granting policy actually asks for —
  previously true only for `kgraph-*`.
- (+) A real, silent 9-capability regression (every aiueos kernel
  capability un-runnable at guarded run time since ADR-2607022700) is
  fixed, not just newly-avoided for this ADR's own additions.
- (+) The fail-closed/receipted/malformed-input-safe design discipline
  `kgraph-*` already established extends uniformly across the whole
  provider surface, not just the ops that happened to get built first.
- (−) `notify-show`'s single-i32 wire shape still can't carry a message
  string — unchanged by this ADR, a pre-existing ABI limit.
- (−) `http-fetch`/`http-post` are synchronous, blocking Chicory host
  calls with no timeout beyond the existing fuel limit (instruction
  count, not wall-clock) — same tradeoff `kototama.tender`'s `http-post`
  already accepted.
- (−) The device-access quartet remains permanently unimplementable at
  this layer; a privileged/hypervisor host is still a separate, unstarted
  effort (unchanged scope from `kototama.tender`'s ADR-2607022900).

## Follow-up

- Work stream 2 (per the owner's stated priority): a real
  `aiueos`-broker-decides → `kototama.tender`-enforces adapter (the
  decision-subprocess wire protocol `aiueos.decide` already implements
  and subprocess-tests, per the corrected aiueos maturity finding —
  nothing on kototama's side shells out to it yet).
- Work stream 3: build aiueos's `.cljc` execution layer (currently
  deliberately execution-free per `aiueos.broker`'s own docstring; the
  retired Rust broker was the only implementation that ever ran a real
  wasmtime-hosted component, and it isn't being replaced in `.cljc` by
  design, only its *decisions* are).

## One-line summary

**`kotoba wasm run` replaces `stub-host-function` with genuine behavior
for 22 of its ~26 declared host imports (real clipboard/keychain/
filesystem/HTTP/crypto/topic-queue/notification-log, `kotoba-lang/
ed25519` + JDK crypto matching `kototama.tender`'s own choices) —
finding and fixing, along the way, that all 9 pre-existing aiueos kernel
capabilities had been silently un-runnable at guarded run time since
ADR-2607022700 because `kotoba-lang/kotoba-lang`'s `effect-for-kind`
never registered their kinds, undetected because nothing had ever
actually exercised real guarded execution against them before.**
