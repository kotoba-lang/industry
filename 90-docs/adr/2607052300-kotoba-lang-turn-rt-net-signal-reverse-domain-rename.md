# ADR-2607052300: kotoba-lang — rename `turn`/`rt`/`net`/`signal` to reverse-domain external-spec names

**Status**: accepted — landed (2026-07-05)
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/{turn,rt,net,signal}` (CLJC reimplementations of the Rust
`kotoba-turn`/`kotoba-rt`/`kotoba-net`/`kotoba-signal` crates removed by `kotoba-lang/kotoba`
PR #259, ADR-2607022900-era policy: new implementations are CLJC/EDN-first)

## Context

While designing ADR-2607052000 (`kami-engine-sdk` Svelte→CLJS migration plan), the first
concrete increment landed was extending `kotoba-lang/turn`'s `kotoba.turn.credential`
namespace with a room/player-scoped mint/verify variant matching `kami-engine-sdk`'s
`turn.ts` contract. This surfaced the four bare, un-prefixed repo names `turn`/`rt`/`net`/
`signal` as candidates for the reverse-domain, `org-<standards-body>-<spec>` naming
convention already established for external-spec ports (`org-openusd`, `org-materialx`,
`org-khronos-{gltf,glb}`, `org-w3-{webauthn,webgpu,aria}`, `org-ietf-oauth2`,
`org-openid-oidc`, `org-oasis-saml`, `org-ros` — see ADR-2607051200 "kotoba-lang の
`*ui*` 系リポジトリ名衝突を解消する" for the precedent's own execution shape, which this
ADR follows) — bare protocol-acronym names collide in intent with the reverse-domain
family and don't self-document which external spec (if any) each repo actually implements.

Each repo's own existing ADR doc (`docs/ADR-kotoba-{turn,rt,net,signal}-*.md`) was read in
full before deciding a name, specifically to avoid the failure mode the org-taxonomy rule
already guards against elsewhere (e.g. `com-apple-{touchid,faceid}` correctly using the
vendor `com-` scheme instead of a standards-body `org-` scheme because Apple's
LocalAuthentication framework is proprietary, not a published open spec) — overclaiming
spec conformance in a repo's *name* is worse than a boring bare name, since the name itself
becomes the (mis)documentation.

## Decision

| Old name | New name | Rationale |
|---|---|---|
| `turn` | `org-ietf-turn` | Implements the **message layer of IETF RFC 8656 (TURN)** — ephemeral-credential mint/verify (coturn `use-auth-secret` scheme), STUN RFC 8489 codec, MESSAGE-INTEGRITY, FINGERPRINT. A clean, unambiguous external-spec port; same `org-ietf-oauth2` precedent (RFC-numbered IETF spec). |
| `signal` | `org-signal` | Implements the **Signal Protocol** (X3DH → Double Ratchet, sender-keys group ratchet) as published by the Signal Foundation (`signal.org`). Same shape as `org-openusd`/`org-materialx` — the spec-owning org's own name *is* the qualifier, no further `-<spec>` suffix needed (there's only one "Signal Protocol"). |
| `rt` | `org-w3-webrtc-signaling` | **Not** a port of any single numbered external spec — its own ADR (`docs/ADR-kotoba-rt-signaling-relay.md`) is explicit that this is kotoba's own room-membership/message-routing contract (pure `join`/`leave`/`route-message`), deliberately **not** a WebSocket server or a spec implementation. Named after the broader WebRTC signaling concept (W3C) it relays SDP/ICE blobs for, on owner's direction, while its own doc-comment continues to make clear the routing logic itself is custom, not spec-derived. |
| `net` | `io-libp2p` | **Not** a libp2p port either — its own ADR (`docs/ADR-kotoba-net-p2p-semantics.md`) explicitly states "re-implementing the entire libp2p stack... is not realistic," and only loosely models GossipSub/Bitswap *semantics* as simplified pure-data functions. Named per owner's direction after libp2p's actual domain (`libp2p.io`) — a literal-TLD reverse-domain form (`io-<name>`), distinct from the `org-<body>-<spec>` standards-body form and from the compat-catalog's unconditional `com-<vendor>` form (ADR-2607041500 "etzhayyim `*-compat` catalog"). This is the **first repo using the `io-` literal-domain prefix**; not yet a doctrine for every `.io`-domained dependency, just this one, owner-confirmed case.

Two of the four (`rt`, `net`) are explicitly **not** external-spec ports per their own ADRs —
their new names describe the *domain/concept* they relate to, not spec conformance they
don't have. This distinction is deliberate and should not be flattened in future renames:
a repo's name should never claim more spec conformance than its own ADR documents.

## Execution

Same procedure as ADR-2607051200's ui-family rename:

1. **GitHub repo rename** (`gh repo rename`) — `kotoba-lang/org-ietf-turn`,
   `org-w3-webrtc-signaling`, `io-libp2p`, `org-signal`. GitHub preserves a redirect from
   each old name.
2. **Local checkout**: moved `orgs/kotoba-lang/<old>` → `orgs/kotoba-lang/<new>`, remote
   URL retargeted to the new GitHub path.
3. **Dependent repos**: none found (`grep` across `orgs/**/deps.edn` for
   `kotoba-lang/{turn,rt,net,signal}` or matching `:local/root` paths returned zero hits) —
   no `deps.edn` updates needed anywhere, unlike the ui-family rename's `wasm-ui`/
   `kami-ui-sdk` dependents.
4. **`manifest/repos.edn`**: added 4 entries to `:path-overrides` (old path → new path,
   same mechanism as the ui-family rename and the `aiueos`/`kami-engine`/etc. org-transfer
   entries already there) and updated the corresponding 4 lines in `:extra-projects` to the
   new paths.
5. **`manifest/west.yml`**: `nbb scripts/gen-west-manifest.cljs --entry
   org-ietf-turn,org-w3-webrtc-signaling,io-libp2p,org-signal` generated the 4 new entries
   with server-side pin verification OK; the old 4 entries (not auto-deleted by `--entry`,
   same caveat the ui-family ADR notes) were removed manually. Net project count unchanged
   (1462 → 1462) — a pure rename, not an add/remove.
   - **Caught before landing**: the local `org-ietf-turn` checkout's pin would have landed
     one commit stale (missing the room/player-scoped-credential increment that was merged
     to `kotoba-lang/turn`'s `main` earlier the same session) — fast-forwarded the local
     checkout to `origin/main` before regenerating that one entry.
   - **Caught before landing**: a concurrent session pushed an unrelated `claude-lmstudio`
     manifest entry to `main` mid-operation. Rebuilt the surgical rename diff against the
     latest GitHub tip (not the slightly-stale local working copy) so that entry wasn't
     silently dropped by this ADR's commit — same class of race documented in this
     project's `git-cleanup-conflict` skill and `CLAUDE.md`'s concurrent-agent guidance.
6. Landed via the GitHub Contents API single-entry-commit path directly to `main` (per
   `manifest/repos.edn`'s `:manifest-workflow`), not a branch/PR — same as the etzhayyim
   compat-catalog migration's stated precedent for this class of manifest change.

## Consequences

- No behavior change — this is a name-only rename; `kotoba.turn.credential`'s newly-added
  `mint-credential-scoped`/`verify-credential-scoped` (this session's earlier increment,
  commit `2d90bddc`) ship under the new `org-ietf-turn` name from this point forward.
- `kami-engine-sdk`'s `turn.ts` does not yet delegate to a compiled bridge from
  `org-ietf-turn` — that remains explicit follow-up per ADR-2607052000, unaffected by this
  rename.
- Two of the four repos (`rt`, `net`) now carry names that could be misread as claiming
  full external-spec conformance (WebRTC, libp2p) they explicitly disclaim in their own
  ADRs. Anyone consuming these repos should read the repo's own `docs/ADR-*.md` before
  assuming spec completeness from the name alone — the name signals *domain*, not
  *conformance*, for these two specifically.

## References

- ADR-2607052000 (`kami-engine-sdk` Svelte retirement / CLJS migration plan — the context
  that surfaced this rename)
- ADR-2607051200 (`kotoba-lang` の `*ui*` 系リポジトリ名衝突を解消する — the execution-shape
  precedent this ADR follows)
- ADR-2607041500 (`etzhayyim` `*-compat` catalog → `kotoba-lang`, reverse-domain naming —
  the sibling `com-<vendor>` convention, unconditional prefix regardless of actual vendor
  TLD; distinct from this ADR's `org-<body>-<spec>` and `io-<domain>` conventions)
- `kotoba-lang/org-ietf-turn`, `org-w3-webrtc-signaling`, `io-libp2p`, `org-signal`: each
  repo's own `docs/ADR-kotoba-{turn,rt,net,signal}-*.md` (unchanged by this rename — the
  scope/rationale documented there is what this ADR's naming decision is based on)
