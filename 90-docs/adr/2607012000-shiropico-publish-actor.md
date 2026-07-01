# ADR 2607012000: SHIRO & PICO cut publish actor (shiropico.*, co-located in ai-gftd-ghosthacker-shiropico)

## Status
Accepted

## Context

Following the "should this be an actor?" question from the earlier
mangaka/animeka investigation: `com-etzhayyim-tsumugu` already proves this
pattern works for manga (Spirit in Physics) — self-sovereign CACAO identity,
containment node (proposal-only), independent PolicyGovernor, StateGraph
`intake → advise → govern → decide → commit|hold|approval`, append-only
ledger. Its own repo is fully materialized (23 tests / 62 assertions,
live-tested against kotobase.net). Given ADR-2607011816 established that
`ai-gftd-animeka`, not `ai-gftd-mangaka`, is SHIRO & PICO's structurally
correct production engine (`work → episode → scene → cut` domain, matching
an anime not a manga), the natural next actor is one for SHIRO & PICO built
on animeka's domain shape.

Two design questions needed resolving before writing code:

1. **Where does the actor's coscientist quality loop come from?** tsumugu's
   coscientist runs a multi-strategy bonus-tag tournament over manga
   storyboard panel text fields (`:colorNote`/`:narration`/`:description`).
   Investigating animeka's actual domain (`clj/src/animeka/domain.cljc`)
   found no equivalent — a cut's render spec is a direct prompt/negative/
   seed, not derived from several competing free-text sources to tournament
   over. Inventing a fictional multi-candidate strategy space to mirror
   tsumugu's shape would not be grounded in anything real about how cuts
   are actually specified.
2. **Where does the code live — a new `com-etzhayyim-*` actor repo (matching
   tsumugu's precedent), or inside `ai-gftd-ghosthacker-shiropico`?** Initial
   work started building `orgs/etzhayyim/com-etzhayyim-michibiki` (mirroring
   tsumugu's placement exactly: `repos.edn`'s own taxonomy assigns
   `etzhayyim` the role "agent-centric — com-etzhayyim-* organism actors").
   Explicit direction during this session redirected it: the actor should
   live inside `ai-gftd-ghosthacker-shiropico` (the content repo,
   ADR-2607011816), not as a separate actor-identity repo.

## Decision

### Domain-grounded advisor, not a fabricated tournament

`shiropico.advisor` does one real render via `shiropico.render`
(`genapp.comfy`-backed) and derives its proposal's confidence from what
*actually happened*: `1.0` if the render reached a configured ComfyUI
gateway (`:source "gateway"`), a fixed `0.3` if it degraded to the offline
placeholder (`:source "stub"`) — below the PolicyGovernor's `0.4` confidence
floor, so a stub render always escalates for human review, never silently
auto-commits. This is a smaller, more honest containment node than
tsumugu's coscientist: no invented candidate-strategy vocabulary, just a
real render outcome as the trust signal. `tsumugu.coscientist`'s
generate/review/rank/evolve/meta-review loop shape (itself ported from
`com-etzhayyim-ibuki`) remains available to port later if/when SHIRO & PICO
grows an equivalent multi-candidate specification step worth tournamenting
over — nothing here forecloses that.

### Co-located with content, not a separate actor repo

`shiropico.*` lives at `orgs/gftdcojp/ai-gftd-ghosthacker-shiropico/clj/`
(a sibling `clj/` subdirectory next to the content data, mirroring
`ai-gftd-mangaka`'s and `ai-gftd-animeka`'s own `clj/` + data layout), not
as a new `orgs/etzhayyim/com-etzhayyim-shiropico`-style repo. This means:

- No new GitHub repo, no new manifest registration — the actor rides on
  `ai-gftd-ghosthacker-shiropico`'s existing west.yml entry; only its pin
  needed advancing.
- The publish actor's lifecycle (episode additions, cut backlog) stays
  coupled to the content it publishes, one repo, one issue tracker.
- Diverges from `tsumugu`'s placement (a separate `com-etzhayyim-*` actor
  repo publishing content that itself lives in a different org/repo,
  `org-spirit-in-physics-comics`) — that split precedent is not treated as
  universal; each work's actor placement is a per-work call.

### Ported mechanically from tsumugu, adapted only where the domain differs

| tsumugu (manga: chapter → panel) | shiropico (anime: episode → cut) | Change |
|---|---|---|
| `tsumugu.cacao` | `shiropico.cacao` | namespace rename + `default-db-name` "manga"→"anime" only — the crypto/SIWE/CBOR logic is already 100% domain-agnostic |
| `tsumugu.kotoba` | `shiropico.kotoba` | namespace rename only |
| `tsumugu.store` (chapter/panel) | `shiropico.store` (episode/cut) | schema fields renamed to match animeka's own domain granularity |
| `tsumugu.render` (wraps `kami.mangaka.render`) | `shiropico.render` (wraps `genapp.comfy` directly) | **not** a dependency on `ai-gftd-animeka` — a third independent `genapp-clj` consumer, own `ShiropicoKSampler` config |
| `tsumugu.mangallm` + `tsumugu.coscientist` | `shiropico.advisor` | simplified to a single grounded render + source-derived confidence (see above) — no coscientist port |
| `tsumugu.policy` | `shiropico.policy` | same hard/soft two-check shape; hard = no such cut, soft = stub render OR `:high-stakes?` cut (SHIRO & PICO's henshin-bank transformation sequence, shared across every episode per `SERIES-BIBLE.md`, is the concrete example) |
| `tsumugu.phase` | `shiropico.phase` | verbatim shape, `:panel/compose`→`:cut/render` op |
| `tsumugu.operation` | `shiropico.operation` | verbatim StateGraph topology, field renames only (panel-id→cut-id, chapter-id→episode-id) |

## Consequences

- 19 tests / 49 assertions green (`cacao_test`, `store_test`,
  `operation_test` — MemStore ≡ DatomicStore parity, phase 0/1/2 gating,
  approval/rejection, missing-cut/episode holds, high-stakes escalation).
  Offline-only (no live kotobase.net round-trip attempted) — matches
  tsumugu's own current state (still blocked on a server-side deploy per
  the investigation that preceded this ADR).
- `genapp-clj` (ADR-2607011900) now has a third real consumer beyond
  mangaka/animeka, validating its generality sooner than expected.
- No new actor identity/DID/repo to track in the fleet's actor roster —
  smaller operational surface than a fourth `com-etzhayyim-*` repo would
  have been.
- The coscientist-quality gap is explicit, not silently skipped: a stub
  render can never reach `:commit` without a human, so the missing
  tournament doesn't weaken the safety invariant, it just means every
  render needs a human look until a gateway is actually configured.

## Alternatives Considered

1. **Full tsumugu-shape port, including a fabricated multi-strategy
   coscientist tournament for cuts**: rejected — there is no real
   multi-candidate specification step in animeka's cut domain to
   tournament over; inventing one would be unverified, unjustified domain
   modeling under time pressure, not a grounded design.
2. **New `orgs/etzhayyim/com-etzhayyim-michibiki` actor repo** (tsumugu's
   exact placement precedent): built first, then redirected per explicit
   session direction to co-locate with `ai-gftd-ghosthacker-shiropico`
   instead. Superseded before merge — no trace left in west.yml/repos.edn.
3. **Wrap `ai-gftd-animeka`'s own registry handlers as the containment
   node** (reusing animeka's `generate-*` graphs instead of calling
   `genapp.comfy` directly): investigated and rejected — animeka's own
   generation graphs are not yet wired to call `animeka.comfy`/`animeka.llm`
   at all (confirmed via grep: zero external callers of either namespace),
   so routing through them would not produce any more real behavior than
   calling `genapp.comfy` directly, while adding an app-on-app dependency
   an actor shouldn't have.

## References

- ADR-2607011500 (Spirit in Physics as a self-sovereign atproto actor —
  the pattern this ADR reuses)
- ADR-2607011816 (ghosthacker-shiropico standalone repo — corrected
  animeka, not mangaka, as SHIRO & PICO's engine)
- ADR-2607011900 (genapp-clj commons extraction — `shiropico.render`'s
  third consumer)
- `orgs/etzhayyim/com-etzhayyim-tsumugu` (the ported precedent)
- `orgs/gftdcojp/ai-gftd-ghosthacker-shiropico` PR #2
