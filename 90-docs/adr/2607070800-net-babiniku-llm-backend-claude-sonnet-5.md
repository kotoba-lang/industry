# ADR-2607070800: net-babiniku LLM chat backend — Claude Sonnet 5, structured outputs, no self-fine-tune

## Status
Accepted

## Context

`net-babiniku` reached Milestone 2 (governor HARD/SOFT containment, creator monetization
honest-default, giemon embodiment gate) with no real chat feature at all — every "dialogue
proposal" the UI could run through `babiniku.governor/review-turn` was a hardcoded demo
button (`db/default-db`'s `:demo-proposals`). No LLM provider or model had ever been chosen.

User direction (2026-07-06/07): pivot to big decisions needing user input, specifically
**LLM backend selection**, framed as: "現在実チャットが一切存在しない（Milestone 0から一度も
未着手）。どのLLMプロバイダ/モデルを使うかを決めないとキャラクターの会話自体が作れない。
shinshi.clubの前例（自家fine-tune+Modal/vLLM）を踏襲するか、別のアプローチか。" The user was
asked (a) which model tier, choosing **Claude Sonnet 5**, and (b) whether to implement now,
choosing **yes**.

### `club-shinshi`'s precedent does not apply here

`club-shinshi` (a separate `jk-luxury` product, now owned by a different agent per this
session's standing instruction) self-fine-tunes and self-hosts an uncensored model (Modal/
vLLM, ADR-2606032100) because its paid tier includes NSFW roleplay a hosted API's safety
classifiers would refuse. `net-babiniku`'s chat feature, per this ADR, is **SFW-only** — the
still-open content-rating decision from ADR-2607062200 (paid tier "may include NSFW") is not
resolved by this ADR and is explicitly out of scope here; if/when that tier is greenlit, its
own ADR should decide whether to extend this hosted-API backend or reuse club-shinshi's
self-fine-tune pipeline, per ADR-2607062200's original follow-up note. For an SFW persona
chat, a hosted Claude model with a well-built system prompt is sufficient — self-hosting
would add real operational complexity (GPU provisioning, model hosting, fine-tune data
pipeline) with no capability net-babiniku's SFW tier actually needs.

### Why Claude, and why Sonnet 5

Anthropic's Claude API is the direct, officially-supported LLM surface for this kind of
per-turn structured chat (see the `claude-api` skill's SDK/structured-outputs guidance). Model
tiers considered:

- **Haiku 4.5** ($1/$5 per MTok): fastest, cheapest — the default recommendation for a
  latency/cost-sensitive real-time chat turn. Two caveats surfaced during the decision: (a)
  its minimum cacheable prompt is 4096 tokens (vs. ~2048 for several other tiers), so a terse
  persona system prompt wouldn't clear the caching threshold; (b) persona/roleplay
  consistency is a plausible weak point at this tier for a VTuber product where in-character
  voice matters.
- **Claude Sonnet 5** ($3/$15 per MTok, introductory $2/$10 through 2026-08-31): the user's
  choice. Meaningfully better roleplay/persona consistency, at a cost/latency premium the
  user judged acceptable given the small scale of a Milestone-3 chat feature. Supports
  structured outputs (`output_config.format`), the same feature Haiku 4.5 would have used.
- **Opus-tier**: not evaluated as the primary candidate — this is squarely a "low-cost,
  real-time chat turn" workload, not one where the `claude-api` skill's default-to-Opus
  guidance for code-authorship tasks applies; the user was asked to choose between the two
  cost-appropriate tiers directly.

## Decision

1. **Model: `claude-sonnet-5`.** No self-fine-tune, no self-hosting — a hosted Claude Sonnet
   5 call with a per-character system prompt is sufficient for this SFW-only tier.

2. **Structured outputs, not free-text parsing.** Every chat turn request sets
   `output_config.format` to a `json_schema` constraining the reply to
   `{dialogue, emotion, motion_cue}`, with `emotion`/`motion_cue` enums drawn directly from
   `babiniku.governor/default-policy`'s `:allowed-emotions`/`:allowed-motion-cues` — not a
   separately-maintained list. A schema-conformant reply can therefore only ever fail
   `babiniku.governor/review-turn` on **content** (a blocked term, an ambiguous content-tier),
   never on an emotion/motion-cue the gate doesn't recognize.

3. **Where the call lives: a Cloudflare Pages Function (`functions/api/chat.js`), never the
   browser.** `net-babiniku` deploys as a static site to Cloudflare Pages (`wrangler.toml`);
   the Anthropic API key must never reach client-shipped code. The Function holds
   `ANTHROPIC_API_KEY` as a Cloudflare secret (not yet provisioned — see Consequences) and is
   the only place in the codebase allowed to call `api.anthropic.com`.

4. **Official SDK, not raw HTTP.** Per the `claude-api` skill's requirement ("the official
   SDK is the default whenever a supported SDK exists"), `functions/api/chat.js` uses
   `@anthropic-ai/sdk` rather than a hand-rolled `fetch` against the Messages API. The
   Cloudflare Pages Functions runtime (Workers, fetch-based) is compatible with the SDK; there
   is no technical reason here to fall back to raw HTTP.

5. **Persona/schema logic lives in `.cljc` as the source of truth; the JS Function mirrors
   it.** `babiniku.persona` (new namespace) builds the system prompt string and the
   structured-output schema from a `babiniku.character` roster entry — pure, `nbb`-testable,
   no HTTP. `functions/api/chat.js` cannot run Clojure, so it carries a **manual, documented
   mirror** of the roster/persona/schema data (same duplication precedent already established
   by `babiniku.vrm-bridge`'s own docstring: a whole separate build pipeline isn't worth it
   for a handful of small, rarely-changed functions/data). `nbb persona` is the test gate for
   the `.cljc` side; the JS mirror's top comment names exactly which `.cljc` files/tests it
   must be kept in sync with.

6. **Richer persona bios, partly for caching economics.** Each roster character gained
   `:persona-bio`/`:speech-examples` fields (full backstory + voice examples, not just the
   existing one-line `:tagline`). This both improves in-character consistency and — because
   `cache_control: {type: "ephemeral"}` is placed on the system-prompt block, and a terse
   system prompt likely wouldn't clear Claude's minimum-cacheable-prefix floor — gives the
   cached system prompt enough length to actually be eligible for prompt-cache hits across
   repeated turns with the same character.

7. **Honest-default, no fabricated turns.** If `ANTHROPIC_API_KEY` is unset, the Function
   returns `503` with an explicit "not yet provisioned (準備中)" message — never a crash,
   never a fabricated reply — mirroring `babiniku.monetization`'s `UnprovisionedCapability`
   pattern (ADR-0084 precedent). The UI (`babiniku.ui.events`'s `:chat/error`) never appends a
   ledger entry for a failed fetch; a real chat turn only ever reaches the ledger by actually
   passing through `babiniku.governor/review-turn` via `:governor/propose`, the same gate
   every pre-existing demo button already used — nothing about this feature is a new bypass
   path.

## Consequences

- `ANTHROPIC_API_KEY` is **not yet provisioned** as a Cloudflare Pages secret for
  `net-babiniku` — the chat feature is live in code and deployed, but returns the honest 503
  in production until the key is added (follow-up, alongside the still-open `babiniku.net`
  DNS/1Password items already tracked this session).
- A real successful chat turn against a live key has **not** been verified end-to-end in this
  ADR's implementation — only the request path (via a deliberately invalid key reaching a
  genuine `401` from `api.anthropic.com`) and every honest-default/validation error path were
  verified. Verifying an actual successful reply is a follow-up once the key is provisioned.
- The still-open content-rating decision (ADR-2607062200's "3, 2" — free SFW / paid may-include
  NSFW) is untouched by this ADR; this backend serves the SFW tier only. If/when the paid tier
  is greenlit, a follow-up ADR must decide whether to extend this hosted-Sonnet-5 backend or
  reuse club-shinshi's self-fine-tune/Modal/vLLM pipeline for that tier specifically.
- `net-babiniku` now has an npm dependency on `@anthropic-ai/sdk` and a Cloudflare Pages
  Functions build target (`functions/api/`) in addition to its existing shadow-cljs static
  build.
- The roster/persona/schema duplication between `src/babiniku/{character,persona}.cljc` and
  `functions/api/chat.js` is a manual-sync surface (3 characters, 2 small enum sets) — `nbb
  persona`/`nbb governor` are the tests to run before changing either side.

## Alternatives Considered

1. **Self-fine-tune + self-host (club-shinshi's precedent).** Rejected for this tier — no
   NSFW content is in scope, so the operational complexity (GPU hosting, fine-tune data
   pipeline) buys nothing over a hosted Claude model with a good system prompt.
2. **Claude Haiku 4.5.** Considered as the cost/latency-optimal default; the user chose
   Sonnet 5 for better roleplay/persona consistency, judging the cost/latency premium
   acceptable at this product's current scale.
3. **Raw HTTP against the Messages API instead of `@anthropic-ai/sdk`.** Rejected — the
   `claude-api` skill requires the official SDK whenever one exists for the project's
   language, and Cloudflare Pages Functions (Workers runtime) are SDK-compatible; there was no
   technical blocker forcing raw HTTP here.
4. **Free-text dialogue parsing (regex/heuristics for emotion/motion-cue) instead of
   structured outputs.** Rejected — structured outputs let the schema itself enforce
   `babiniku.governor`'s exact vocabulary, eliminating an entire class of "unknown emotion/
   motion-cue" HARD-holds caused by the model not following free-text instructions precisely.
5. **A shared build artifact (e.g. shadow-cljs `:node-library` target) instead of a manually
   duplicated JS mirror of the persona/schema logic.** Rejected for this pass — coupling the
   Cloudflare Functions build to the shadow-cljs pipeline for 3 small character records and
   two enum lists is more machinery than the current scale justifies; same reasoning
   `babiniku.vrm-bridge`'s own docstring already gives for its own small-glue duplication.

## References

- `orgs/jk-luxury/net-babiniku/src/babiniku/persona.cljc`, `src/babiniku/character.cljc`
  (roster + `:persona-bio`/`:speech-examples`), `src/babiniku/governor.cljc` (`default-policy`
  vocabulary the schema is generated from)
- `orgs/jk-luxury/net-babiniku/functions/api/chat.js` (the Cloudflare Pages Function; top
  comment documents the manual mirror and what it must stay in sync with)
- `orgs/jk-luxury/net-babiniku/test/persona_test.clj` (`nbb persona` gate)
- `90-docs/adr/2607062200-net-babiniku-onlyfans-style-creator-monetization.md` (the still-open
  content-rating decision this ADR's SFW-only backend does not resolve)
- club-shinshi's self-fine-tune/Modal/vLLM precedent (ADR-2606032100, referenced but not
  duplicated here — out of scope, owned by a separate agent per this session's standing
  instruction)