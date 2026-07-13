# design-quality Co-Scientist Iteration 01 — UI/UX kaizen (ported from isekai.ux ADR-0007)

> Seed: coscientist kaizen pass over the 4 design-quality sample pages, applying isekai.ux's ADR-0007 measured-loop pattern to this stack
>
> Run: 6 hypotheses (1 per audit finding) · Elo round-robin (K=32) · evolved -> 1 batch (4 consumer-fixable). Judge: `design-quality.audit` (deterministic, no LLM, no browser).

## Measured baseline (the honest metric, not opinion)

`design-quality.audit` over the 4 sample pages = **71.55 / 100**. Gaps below are weight-ranked by recoverable headroom — heaviest first. Note: this audit is independent of, and disagreed usefully with, the 3-judge `:llm-judge` panel in design-quality-ledger.edn — the LLM panel scored these same libraries 4.0-5.0/5 on every HIG axis (clarity/deference/depth/consistency), yet none of the 3 judges surfaced any of the concrete gaps below (missing theme-color, no dvh, incomplete safe-area, no explicit tap-target min-height). That is exactly this repo's 'unmeasured metric is theater' lesson (isekai ADR-0007, iteration-02) landing here.

| axis | pages | weight | finding |
|---|---|---|---|
| `tap-targets` | uikit-mobile-sample, appkit-desktop-sample, kotoba-ui-bare-sample | 0.13 | liquid-glass.style's .liquid-glass__button/.liquid-glass__icon-button rules have no explicit min-height ≥44px (sized by padding + line-height only) — sub-target taps on a phone |
| `dynamic-viewport` | uikit-mobile-sample, appkit-desktop-sample, kotoba-ui-bare-sample, liquid-glass-ui-showcase | 0.09 | kotoba-ui.shell's .kotoba-shell__app uses min-height:100vh without a dvh fallback — full-height app-shell layout jumps under mobile browser chrome |
| `safe-area` | uikit-mobile-sample, appkit-desktop-sample, kotoba-ui-bare-sample, liquid-glass-ui-showcase | 0.13 | only one safe-area-inset edge handled (bottom, via kotoba-ui.shell — the nav-bar/toolbar top edge is not padded) — cover all edges that meet the screen |
| `viewport` | uikit-mobile-sample, appkit-desktop-sample, kotoba-ui-bare-sample, liquid-glass-ui-showcase | 0.1 | viewport missing: viewport-fit=cover |
| `color-scheme` | uikit-mobile-sample, appkit-desktop-sample, kotoba-ui-bare-sample, liquid-glass-ui-showcase | 0.06 | kotoba-ui.theme wires prefers-color-scheme dark overrides but no <meta name=theme-color> — the browser UI chrome (status bar / address bar) won't match the page in dark mode even though the page content does |
| `responsive` | liquid-glass-ui-showcase | 0.07 | no max-width media query — a single fixed layout for phone and desktop |

## Roadmap (Elo-ranked — the measured fitness is the judge)

| # | id | fixable now? | effort | Elo | +gain | change |
|---|---|---|---|---|---|---|
| 1 | `dq-h1` | consumer CSS | S | 1273 | +7.12 | unlayered app CSS: .liquid-glass__button,.liquid-glass__icon-button{min-height:44px} |
| 2 | `dq-h2` | consumer CSS | S | 1244 | +7.08 | unlayered app CSS: .kotoba-shell__app{min-height:100dvh} (100vh stays as the no-dvh-support fallback, since unlayered CSS wins but doesn't remove the library rule) |
| 3 | `dq-h3` | consumer CSS | S | 1215 | +5.84 | unlayered app CSS: pad the nav-bar's top edge with env(safe-area-inset-top,0px) to cover the second edge |
| 4 | `dq-h4` | upstream lib | M | 1186 | +3.75 | kotoba-ui.shell/page hardcodes the single <meta name=viewport> tag with no :head override point for viewport-fit=cover — needs an UPSTREAM change to kotoba-ui.shell (or an opt to append viewport-fit), not fixable by adding a second meta tag at the consumer level |
| 5 | `dq-h5` | consumer CSS | S | 1156 | +2.70 | add <meta name=theme-color content="#0b0b10"> (+ a light-mode media variant) via the page's :head opt — kotoba-ui.theme already wires prefers-color-scheme, only the meta tag was missing |
| 6 | `dq-h6` | upstream lib | S | 1126 | +1.97 | add a max-width media query to orgs/kotoba-lang/liquid-glass-ui/docs/index.html's own page-css — this is the pre-existing showcase demo, not a generated sample, so it's an UPSTREAM liquid-glass-ui docs edit |

## Highest-leverage step shipped this iteration

Evolve -> ship the 4 low-risk, consumer-fixable additive fixes as one kaizen batch `design-quality-kaizen-1` (`dq-h1`, `dq-h2`, `dq-h3`, `dq-h5`): projected 71.55 -> **94.29 / 100**.

Everything else in the roadmap needs an edit inside a shared `orgs/kotoba-lang/*` checkout — deferred per this repo's CLAUDE.md (shared checkouts need worktree isolation + explicit review before landing, not a same-pass consumer fix).

## Shipped + verified (the kaizen delta is the proof, not the projection)

`dq-h1`/`dq-h2`/`dq-h3`/`dq-h5` were added to `90-docs/design-quality/samples/generate-samples.cljs`
as an unlayered `:head` CSS block (`kaizen-head`) passed to every sample's `->page` call, and
the 3 samples were regenerated (`nbb --classpath ... generate-samples.cljs`).
`orgs/kotoba-lang/liquid-glass-ui/docs/index.html` was deliberately left untouched — it is a
pre-existing file outside this workflow's generated samples, not a shared-library edit
covered by this batch.

Re-audit:

| page | before | after |
|---|---|---|
| uikit-mobile-sample | 71.14 | **96.25** |
| appkit-desktop-sample | 71.14 | **96.25** |
| kotoba-ui-bare-sample | 71.14 | **96.25** |
| liquid-glass-ui-showcase (untouched, control) | 72.77 | 72.77 |
| **overall (4 pages)** | **71.55** | **90.38 (+18.83)** |

The untouched showcase page staying flat at 72.77 is the control that confirms the delta on
the other 3 pages is real (caused by the shipped CSS, not measurement noise). Only
`viewport` (needs an upstream `kotoba-ui.shell/page` change) remains open on the 3 shipped
samples — exactly the one hypothesis (`dq-h4`) the batch correctly excluded as not
consumer-fixable. `liquid-glass-ui-showcase` keeps its other 4 pre-existing gaps, also
correctly excluded (untouched file).

## -> Seed for next iteration

(1) Land the upstream-only findings for real: kotoba-ui.shell/page needs a `:viewport` opt (or hardcode `viewport-fit=cover` outright) so consumers don't need a second conflicting meta tag; liquid-glass-ui/docs/index.html needs its own max-width media query. (2) Add a WCAG 1.4.3 contrast-ratio axis (needs a colour parser over the resolved --hig-* token values, not just presence-checking). (3) A real screenshot-critic lens (vision LLM scoring the ACTUAL rendered pixels) to catch what regex can't — this repo's earlier `:sample-visual` single-pass review is a first step toward that but is not yet wired into this loop's Generate stage. (4) Re-run `design-quality.audit` after shipping and after any upstream fix lands — the kaizen delta is the proof, not the roadmap.
