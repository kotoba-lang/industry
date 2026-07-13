# design-quality Co-Scientist Iteration 04 — fixing the audit tool itself (score-site), not another page

> Seed: "next" — the highest-leverage carried-over item from iterations 02 and 03's seed
> lists, picked over adding a new axis or fixing another app: `design-quality.audit`'s
> single-string `score-page` silently undercounts any real page that splits assets across
> files (iteration 03's `kami-creative-studio` bug). That bug can recur on any future page
> this tool audits — worth fixing the tool once rather than re-discovering it by hand again.

## What shipped

**`score-site`** (`90-docs/design-quality/audit.cljc`) — a new entry point that takes a
page's parts (`{:html ... :css <string-or-[strings]> :js ...}`) instead of one
pre-assembled string, and — the actual point of this iteration — **tells you when it can't
be confident the score is complete**: `external-stylesheet-hrefs` scans the HTML for
`<link rel=stylesheet href=...>` (quoted or bare attribute values), and if any are found
with no `:css` supplied to cover them, the result carries `:incomplete? true` and a
`:warning` string naming exactly which hrefs are unaccounted for — instead of silently
returning a wrong number the way the original `kami-creative-studio` audit did.
`score-page` (the old single-string entry point) is unchanged and still used internally by
`score-site` after assembly; `audit` now calls `score-site` per page (so callers can pass
either a plain string or a parts-map, unchanged for every existing caller) and surfaces an
`:incomplete` list of any flagged page names at the top level.

Reproduced the exact iteration-03 bug as a test case and confirmed the tool now catches it
itself:

```
score-site {:html <kami-creative-studio's index.html>}                    ; :css omitted, the actual mistake
  -> overall 67.72, :incomplete? true,
     :warning "HTML references external stylesheet(s) css/ui.css but no :css was
               supplied to score-site — every CSS-dependent axis below may be
               undercounted ..."

score-site {:html <same> :css <public/css/ui.css>}                        ; correct call
  -> overall 66.59, :incomplete? nil
```

## Regression check

Confirmed the earlier "100/100 converged" claims for the 4 kotoba-ui-family sample pages
and `kami-studio` weren't retroactively suspect — none of them use an external
`<link rel=stylesheet>` (all self-contained single-file HTML), so they were never exposed to
this bug class. Verified via `grep -l "rel=\"stylesheet\""` across every previously-audited
file before touching any code, and via re-scoring all 5 through the new `score-site` path
(all still 100.00, `:incomplete?` correctly `nil` on every one).

## A second, unrelated thing this iteration caught: stale local build artifacts

Re-running the full multi-page convergence check initially showed `kami-creative-studio`
regressed back to 5 open findings — looked like the iteration-03 fixes had somehow been
lost. They hadn't: `public/` is gitignored in `kami-creative-studio` (build output, not
source), and the superproject's shared checkout at `orgs/kotoba-lang/kami-creative-studio/`
had a `public/` directory left over from *before* iteration 03's fix — nobody had rebuilt it
there since the fix was made and merged (the actual fix build+verify+audit all happened
inside the isolated worktree, which was deleted after merging, per the standing
worktree-per-task convention). Confirmed the fix really is in `main` (`git log` showed
commit `966d445`/merge `7c0439e`), rebuilt `public/` in the shared checkout
(`shadow-cljs release` + the pages build task), and re-confirmed 100.00/100 with fresh
output. Not a regression, but a reminder that gitignored build artifacts in a shared
checkout can silently drift stale — worth remembering before trusting a shared checkout's
local build output without rebuilding it first.

## Final state — all 6 pages this repo has ever audited, one convergence check

`design-quality.audit`, all 6 pages together = **100.00/100, 0 open findings**:
`uikit-mobile-sample`, `appkit-desktop-sample`, `kotoba-ui-bare-sample`,
`liquid-glass-ui-showcase`, `kami-studio`, `kami-creative-studio` (scored via the corrected
`score-site` multi-file path, `:incomplete?` correctly nil since `:css` was supplied).

## -> Seed for iteration 05

The tool-correctness backlog carried over from iterations 02-03 is now shorter: (1)
`heuristic-hypothesis`'s "doesn't verify selectors against real page content" bug (found in
iteration 02, still open — this iteration fixed a different tool bug, not this one). (2) A
WCAG 1.4.3 contrast-ratio axis (needs a real colour parser over resolved token/CSS values,
not presence-checking — carried since iteration 01). (3) Formalize the CSR blind spot on
`tap-targets`/`focus-visible` found in iteration 03 (still just documented, not fixed —
`score-site`'s new multi-file assembly doesn't help here since the issue isn't missing CSS,
it's markup that's never present in ANY static file for a client-rendered app). (4) At
100/100 converged across every page this system has ever touched, the next real signal has
to come from either a new axis, a new page, or fixing the two known tool gaps above — not
another kaizen pass on the same 6 pages with the same 9 axes.
