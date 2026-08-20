---
name: computer-use
description: Drive the real macOS desktop/browser via `orgs/kotoba-lang/computer-use` (computer-use-clj) when Codex-in-Chrome MCP is unavailable/disconnected, when the user explicitly says "computer-use" / "kotoba-lang/computer-use", or when a task needs native macOS app control beyond a browser tab (System Events, cliclick, screencapture). Use PROACTIVELY before sending any simulated keystroke (cmd+key macros, cliclick typing) on this machine — this desktop runs many concurrent Codex sessions in parallel terminal panes that compete for OS focus, and blind keystroke automation has previously typed into the wrong pane.
---

# computer-use (kotoba-lang/computer-use)

Guardrailed macOS desktop/browser automation, built on
`orgs/kotoba-lang/computer-use` (`computeruse.*` namespaces, Anthropic
computer-use action vocabulary, `.cljc`). Use this instead of
`mcp__claude-in-chrome__*` when that extension isn't connected, or when the
user asks for this library by name.

## Hazard: this desktop runs many parallel agents — focus is not yours alone

This machine typically has several Codex sessions running at once in
separate terminal panes/windows. **OS-level keyboard focus can move between
the moment you check `frontmost-application` and the moment your keystrokes
actually arrive** (JVM/clojure startup alone takes seconds). A real incident:
`computeruse.macos`'s cmd+L → type-URL → Return sequence was dispatched after
confirming Chrome was frontmost, but by the time the keystrokes fired, focus
had moved to a different terminal pane running an unrelated Codex
session — the URL got typed into that pane's prompt and submitted as a
message instead of navigating Chrome.

**Rules, in priority order:**

1. **Prefer direct app scripting over simulated keystrokes whenever the
   target app exposes one.** For Chrome/Safari, set/read the tab URL
   directly — this has no keyboard-focus dependency at all:
   ```bash
   osascript -e 'tell application "Google Chrome" to set URL of active tab of front window to "https://example.com"'
   osascript -e 'tell application "Google Chrome" to get URL of active tab of front window'   # verify
   ```
   Only fall back to `computeruse.macos`'s cliclick/System-Events keystroke
   path (mouse clicks, typing into a specific form field, scrolling) when the
   target app has no scriptable dictionary for the action you need.
2. **If you must simulate keystrokes**, re-verify frontmost immediately
   before dispatch, keep the action sequence as short as possible, and
   re-verify the *result* afterward via a read-only check (AppleScript
   `get URL`, not just a screenshot — a screenshot of this machine's main
   display is usually a wall of terminal panes, not the target app, because
   the target app is often on a different monitor/space):
   ```bash
   osascript -e 'tell application "System Events" to get name of first application process whose frontmost is true'
   ```
3. **Never assume a screenshot proves what you intended happened.** Confirm
   via the app's own accessible state (URL, window title, file content) when
   available.

## Absolute boundary: no credentials, no state-changing actions, ever

This applies regardless of which vault backs the credential
(`kotoba-lang/kagi`, `op`/1Password CLI, `kagitaba`'s 1Password-import glue).
Do not resolve a secret and type it yourself, even through
`computeruse.vault`'s `type_secret` design (host-layer resolution keeps the
plaintext out of the prompt/log, but the *action* of authenticating on the
user's behalf is still off-limits). Do not click Submit / Register / Create
Account / Accept Terms / any state-changing control. Stop and hand off to the
user for:

- entering an email, password, API key, OTP, or recovery code
- accepting terms of use / privacy policy / marketing consent
- any account-creation or other legally-binding submission

This mirrors the existing guardrail pattern in `src/computeruse/ngc.cljc` —
extend that pattern for new sites rather than weakening it. When a
state-changing decision point is reached in an agent-loop task, gate it
through `computeruse.hil/approval-tool` (`request_human_approval`), which
only accepts compact summary fields (never screenshots/page content), so the
user reviews a plain-language description before approving in a native
macOS alert.

## Invocation

```bash
cd orgs/kotoba-lang/computer-use
clojure -M:dev:examples -m <example-ns>   # local checkouts, no network
clojure -M:examples -m <example-ns>       # :git/sha coordinates, needs network
```

`:dev` overrides `io.github.kotoba-lang/langgraph` and `…/langchain` to
`../langgraph` / `../langchain` — sibling paths under `orgs/kotoba-lang/`,
where both checkouts actually live, so `:dev:examples` resolves offline.
Verified 2026-08-08: `clojure -Spath -A:dev:examples` puts
`orgs/kotoba-lang/langgraph/src` and `…/langchain/src` on the classpath.

Without `:dev`, `:examples` resolves langgraph by `:git/sha` over the network.
That pin is deliberate and **not** a stale tag — see the `deps.edn` header
comment: `:git/tag "v0.2.0"` predates langgraph's org rename and drags in the
old `io.github.com-junkawasaki/langchain-clj`, which shadows
`io.github.kotoba-lang/langchain`'s `langchain.model` namespace and drops
`openai-model`. Every example then dies with `No such var: model/openai-model`.
Do not "upgrade" that `:git/sha` to a tag until a tag exists past the rename.

> Until 2026-08-08 this section said to use plain `:examples` and *avoid*
> `:dev:examples`, because the local roots used to point at
> `orgs/com-junkawasaki/langgraph-clj`. That org/name is gone (the `-clj`
> suffix ban, 2026-07-10) and `deps.edn` was rewritten; the warning inverted.

## Building a new guardrailed task

Follow `src/computeruse/ngc.cljc` as the template for a new site/task:

- a `free-registration-url`-style constant for the one allowed destination
- a `*-system-prompt` that: takes a screenshot before every decision,
  forbids typing any credential, and requires `request_human_approval`
  before every state-changing control, naming them explicitly
- an `approval-request` fn mapping named decisions to compact
  `{:id :title :summary :action :impact}` maps (no page content)
- a `prepare-*!` fn for the navigation-only path (safe without a vault —
  prefer the direct-AppleScript approach above over this when the target is
  Chrome/Safari)
- wire it into `examples/<name>.clj` with a `-main`, matching
  `examples/ngc_free_org.clj` / `examples/vultr_ip_allow.clj` /
  `examples/sumitclub_meisai.clj`

Key namespaces: `computeruse.computer` (IComputer protocol + mock),
`computeruse.tool` (action vocabulary → dispatch), `computeruse.agent`
(langgraph sampling loop + Datomic action log), `computeruse.macos` (real
host: screencapture/osascript/cliclick, JVM-only), `computeruse.vault`
(op/bw/mock — reference resolution, never raw secrets in the log),
`computeruse.hil` (`request_human_approval` tool adapter).
