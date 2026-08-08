# クリーニング営み — ISIC 9601 idle tycoon

An idle shop-management game whose rules are a transcription of
[`cloud-itonami/cloud-itonami-isic-9601`](https://github.com/cloud-itonami/cloud-itonami-isic-9601)
— the garment-care governed actor (LaundryOps-LLM ⊣ Garment Care Governor).

This directory is **staging**. Its canonical home is
`network-awai/network-isekai:public/games/itonami/isic-9601/`, alongside the eight
cloud-itonami job simulators from ADR-2607031000; the layout here mirrors that path so
the port is a copy. See `90-docs/adr/2608100000-cloud-itonami-isic-9601-laundry-idle-tycoon.edn`
for what is done, what is not, and why.

## The point of it

An idle tycoon is a game about automating everything. This shop **structurally cannot
automate its last two stations**, and that is the whole design:

- `laundry.phase/phases` keeps `:actuation/apply-cleaning-process` and
  `:actuation/return-garment` out of every phase's `:auto` set — including phase 3, whose
  `:auto` is exactly `#{:garment/intake}`.
- `laundry.governor/high-stakes` enforces the same invariant independently, so two layers
  agree.

So washing a real garment and handing it back to its owner stay on the player's finger
forever, however big the shop grows. Hiring approvers automates the paperwork and never
touches those two. `test/logic_test.cljs`'s `actuation-never-auto-at-any-phase` and
`hired-approver-never-clears-actuation` fail if that ever stops being true.

## What maps to what

| game | actor |
|---|---|
| the five stations, in order | `laundry.phase/write-ops` |
| shop tiers 0–3 | `laundry.phase/phases` |
| which stations run unattended | each phase's `:auto` set |
| the six ways a garment gets held | `laundry.governor`'s six HARD checks |
| 確度 below 60% escalates | `laundry.governor/confidence-floor` |
| 洗濯表示 conflict flag | `laundry.registry/cleaning-process-forbidden-by-care-label?` |
| 差し戻す | the approval workflow resuming `{:approval {:status :rejected}}` |
| 監査台帳 | the actor's append-only audit ledger |

The skill the game teaches is the third check. The advisor proposes a cleaning process;
the garment's own care label is ground truth. Approving a conflicting plan at 取扱方法
is safe — the governor catches it at 洗浄 and the garment is never ruined — but it costs
the customer's trust. Reading the label and rejecting the plan costs only time.
`careless-play-loses-to-the-care-label` pins both halves of that: blind approval loses the
run, and not one forbidden process is ever applied even while losing.

## Files

| file | what it is |
|---|---|
| `logic.cljc` | the whole rule set: a pure `state + event -> state` reducer. No I/O, no atoms, no interop. |
| `src/itonami/isic_9601/logic.cljc` | the same file on a namespace-shaped path, so nbb and squint can require it |
| `test/logic_test.cljs` | 63 checks (nbb) |
| `test/balance.cljs` | tuning probe — plays four seeds and reports what killed each run |
| `preview/ui.cljs` | browser shell, compiled by squint; holds no rules, draws only `summary` |
| `preview/build.cljs` | squint → esbuild → one self-contained `preview/index.html` |
| `preview/smoke.cljs` | headless-Chromium check that the built page actually plays |
| `game.edn` | network-isekai game metadata |

`logic.cljc` exists twice on purpose: the flat copy is what ports into network-isekai
(whose guests are flat files), and `src/itonami/isic_9601/logic.cljc` is the same bytes on
the path a namespace loader needs. **Edit the flat one and copy**; a check that they match
belongs in the fleet-CI gate when this lands.

## Run it

```bash
npm install                       # esbuild / nbb / squint / playwright

npm test                          # 63 unit checks
npm run balance                   # what a competent run looks like across seeds
npm run build                     # -> preview/index.html (self-contained, ~41 KB)

PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers \
  npx nbb --classpath node_modules preview/smoke.cljs   # real-browser gate
```

Open `preview/index.html` in a browser to play. Nothing is fetched at runtime.

## Constraints the code is written under

- **Pure, restricted subset.** Plain maps/vectors/keywords and pure functions; no
  `defrecord`/`defprotocol`/`atom`/multimethod, no host interop, no `Math/random`. That
  intersection is what squint and the kami-clj guest subset both accept.
- **Keyword-as-function is avoided.** `(mapv :key xs)` throws under squint, where a keyword
  compiles to a plain string. Use `(mapv (fn [x] (:key x)) xs)`.
- **The RNG is threaded through state** (MINSTD, seeded from `:seed`), so a run is
  reproducible and nothing reads a clock. The multiplier is 16807 rather than the more
  familiar 1103515245 because the latter overflows a float64 mantissa against a 2^31
  modulus, which would make nbb and the browser drift apart.
- **The UI is cljs compiled to JS**, not hand-written `.mjs` — CLAUDE.md, 2026-07-14.
- **Styling is the `--hig-*` token contract** (skill `kotoba-uiux`). `preview/style.css`
  defines the tokens locally as a stand-in for `jp-go-dds.tokens/bridge-css`; the port
  swaps the definitions and keeps the rules. Note the known gap: the real bridge carries
  colour and type tokens but not `--hig-spacing-*`, `--hig-radius-*` or
  `--hig-text-*-size`, which need adding upstream rather than re-deriving.
