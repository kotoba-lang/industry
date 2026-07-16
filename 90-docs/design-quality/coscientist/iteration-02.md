# design-quality Co-Scientist Iteration 02 — landing the deferred upstream fixes, and a real self-caught tooling bug

> Seed: iteration 01 shipped a consumer-level kaizen batch (71.55 -> 90.38/100) but
> deliberately deferred 2 findings that needed an edit inside a shared `orgs/kotoba-lang/*`
> checkout, per this repo's worktree-isolation + review policy (CLAUDE.md). Iteration 02
> lands those for real.
>
> Judge: `design-quality.audit` (deterministic, no LLM, no browser).

## Step 1 — land the 2 findings iteration 01 deferred

Both via an isolated `git worktree` outside the superproject (CLAUDE.md's shared-checkout
policy), each verified with the repo's own test suite before pushing, merged server-side
(`gh api .../merges`, no local rebase/force-push):

- **`kotoba-ui` `7935ffe`** — `kotoba-ui.shell/page`'s hardcoded `<meta name=viewport>` gained
  `viewport-fit=cover`. 20 tests / 130 assertions green.
- **`liquid-glass-ui` `56b0a25`** — `docs/index.html` (built from `demo.clj`'s `page-css`) got
  a `@media (max-width:600px)` breakpoint reducing `.demo-shell` padding / `.demo-header h1`
  size. 59 tests / 632 assertions green. Regenerated via the file's own documented
  `clojure -M -m liquid-glass.demo` entry point.

Both merged to their repos' `main`, `manifest/west.yml` pins advanced via
`nbb scripts/gen-west-manifest.cljs --entry <name>` (verified, minimal per-entry diff, no
wholesale regen), landed to `com-junkawasaki/root` main via the same server-side merge
pattern.

Re-measured after regenerating the 3 samples against the fixed `kotoba-ui`:

| page | iter-01 end | after landing |
|---|---|---|
| uikit-mobile-sample | 96.25 | **100.00** |
| appkit-desktop-sample | 96.25 | **100.00** |
| kotoba-ui-bare-sample | 96.25 | **100.00** |
| liquid-glass-ui-showcase | 72.77 | 80.64 (responsive fix only; still missing viewport/theme-color/dvh/safe-area) |
| **overall** | **90.38** | **95.16** |

## Step 2 — the loop tried to auto-generate a roadmap for what's left, and got a selector wrong

Running `coscientist/kaizen-cycle` again on this new baseline correctly found the 4 remaining
findings were now *entirely* on `liquid-glass-ui-showcase`. But `heuristic-hypothesis`'s fix
text is written generically **per axis**, not per page — it assumed every page goes through
`kotoba-ui.shell` (true for the 3 generated samples, the only pages iteration 01 ever touched)
and proposed `.kotoba-shell__app{min-height:100dvh}` and a `.liquid-glass__nav-bar` padding
fix without checking whether those selectors exist on the *actual* page it was targeting.

They don't, on this page: `docs/index.html` is built directly from `shitsuke.hiccup` +
`liquid-glass.components` in `demo.clj` — it never requires `kotoba-ui.shell` at all. A
`grep -c kotoba-shell__app docs/index.html` returned **0**. Shipping the roadmap's `dq-h1` as
literally worded would have been a no-op — CSS targeting a class that isn't on the page,
silently fixing nothing while the ledger claimed a fix shipped.

This was caught *before* shipping (`grep` the real markup first, same "the delta is the
proof, not the projection" discipline this whole loop is built on), not after. The correct
fixes for this specific page: `body{min-height:100vh}` is where the dvh fallback actually
belongs (not `.kotoba-shell__app`, which doesn't exist here); `.liquid-glass__nav-bar` *does*
exist (the page's own "NAV BAR" demo section renders one), so that part of the roadmap was
right. `viewport-fit=cover` and `theme-color` are page-structure-independent (just meta tags)
and were fine as generated.

**Follow-up recorded, not yet built**: `heuristic-hypothesis` should take the actual page
source as an argument and verify its proposed selector exists before emitting a hypothesis
(or downgrade confidence / flag "unverified" when it can't). Filed as the top item for
iteration 03.

## Step 3 — ship the corrected fixes

`liquid-glass-ui` `71f63ff` (worktree, tests green, merged, pin advanced): `viewport-fit=cover`
+ `<meta theme-color>` (light+dark) + `body{min-height:100dvh}` fallback +
`.liquid-glass__nav-bar{padding-top:env(safe-area-inset-top,0px)}` (the page's own pre-existing
`env(safe-area-inset-bottom)` usage on a different component was already correct — this covers
the second edge).

## Final measured state — converged

| page | iter-01 end | iter-02 end |
|---|---|---|
| uikit-mobile-sample | 96.25 | **100.00** |
| appkit-desktop-sample | 96.25 | **100.00** |
| kotoba-ui-bare-sample | 96.25 | **100.00** |
| liquid-glass-ui-showcase | 72.77 | **100.00** |
| **overall** | **90.38** | **100.00 (+9.62 this iteration, +28.45 total from the 71.55 iteration-01 baseline)** |

`design-quality.audit` finds **0 open findings** across all 4 pages. This is a converged
state on the current 9-axis rubric — not "nothing left to improve," just "nothing left this
specific fitness function can see." See seed below.

## -> Seed for iteration 03

(1) **Fix the tooling gap this iteration exposed**: `heuristic-hypothesis` (and `generate`)
should receive the actual page source per finding and verify the proposed selector/class
exists before proposing it, instead of trusting a static per-axis case table. (2) A WCAG 1.4.3
contrast-ratio axis (needs a colour parser over resolved `--hig-*` token values). (3) A real
screenshot-critic lens (vision LLM scoring actual rendered pixels) — this repo's earlier
`:sample-visual` single-pass review is a first step but isn't wired into this loop's Generate
stage yet. (4) Promote the `.liquid-glass__nav-bar` safe-area-top padding from a demo-page-only
fix into `liquid-glass.style` itself, since any real consumer's nav-bar has the identical gap
(the 3 kotoba-ui-based samples' fix came from `kotoba-ui.shell`, which the showcase page never
touches — worth checking whether other liquid-glass-ui consumers outside kotoba-ui hit this
too). (5) At 0 findings on the current rubric, the next real signal has to come from adding
axes, not more cycles on the same ones — re-running `kaizen-cycle` now would converge
immediately (see the `iteration-md` "converged" branch added this iteration for exactly this
case).
