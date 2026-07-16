# ADR-2607101200: Product vocabulary lock — kotoba = language, kototama = `.kotoba` WASM runtime

**Status**: accepted  
**Date**: 2026-07-10  
**Deciders**: Jun Kawasaki  
**Supersedes messaging only**: clarifies product copy; does not overturn ADR-2607022400 (which already defined this stack)

## Context

ADR-2607022400 already fixed:

```text
kotoba-lang / kotoba  = language + compiler (safe Kotoba → Wasm)
kototama              = Wasm execution runtime (tender)
aiueos                = OS / capability broker
```

In practice, docs and the language CLI still presented **`kotoba wasm run`**
(Chicory inside the language repo) as a primary execute path, which made
“kotoba” look like both language and runtime.

Owner lock (2026-07-10): **kotoba = language; kototama = `.kotoba` WASM runtime.**

## Decision

1. **Canonical compile:** `kotoba wasm emit` (aliases `safe-build` / `build`) in
   `kotoba-lang/kotoba`.
2. **Canonical execute:** `kototama` CLI `run guest.wasm` (and browser host under
   kototama / wasm-webcomponent).
3. **Language-repo execute** (`kotoba wasm run`, `run --engine wasm`, interpreter
   `run`) is **compat / debug only**; must not grow new tender features.
4. READMEs of both repos open with the two-line role split and point at each other.

## Consequences

- Agents and humans use emit in kotoba, run in kototama.
- Follow-up hard boundary: quarantine `kotoba.wasm-exec` from default language path.
- Stack ADR-2607022400 remains architectural SSoT; this ADR is the product-copy lock.
