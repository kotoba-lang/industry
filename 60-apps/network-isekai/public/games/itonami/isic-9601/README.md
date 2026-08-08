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

## The street — 「営みの街」

`world.cljc` is the map the shop stands on: eight districts, **each of them a real
`cloud-itonami` governed actor**, locked until the business next door has closed its
audit. It is what makes this a game about *cleaning* rather than about laundry — the
subject widens from clothes to cars, animals, buildings, industrial plant, sewers, waste
and contaminated ground.

| ISIC | district | subject | the op that never automates | why |
|---|---|---|---|---|
| 9601 | クリーニング | 衣類 | `apply-cleaning-process` / `return-garment` | 実際に衣類を処理し、客に返す |
| 4520 | 洗車・整備 | 自動車 | `flag-safety-concern` | roadworthiness の判断に触れる |
| 9609 | ペットケア | 動物 | `actuation/finalize-referral` | 実在の人物を引き合わせる |
| 8121 | 建物清掃 | 建物 | `flag-safety-concern` | 作業員の身体に関わる |
| 8129 | 産業清掃 | プラント | `flag-safety-concern` | hazmat / 密閉空間 |
| 3700 | 下水 | 排水 | `flag-safety-concern` | 公衆衛生 |
| 3811 | 廃棄物収集 | ごみ | `dispute/request` | 相手のある紛争行為 |
| 3900 | 汚染浄化 | 土壌 | `flag-contamination-concern` | 土地と住民に関わる |

Every row was read out of that repo's own `phase.cljc` and `governor.cljc` on 2026-08-08;
`world/district-evidence` records where, so a reader can check rather than trust.

**Reading eight sibling actors side by side turns up the thing the single-shop game could
only assert: every one of them keeps at least one operation out of every phase's `:auto`
set, permanently — nine such operations across the street, each for its own reason.** The
player meets that boundary once in the laundry and then finds it again in every business
they unlock. The map is not eight variations on a theme; it is eight independent
confirmations of the same argument.

Only 9601 has a playable board today (`:playable?` in `world/status`). The other seven are
map entities with their real op tables attached.

## KAMI 2D SDK — what was missing

The map renders through `kotoba-lang/sprite2d`, and three things a board game cannot work
without were not in the package. They are implemented and tested upstream; the commit is
staged here as `sdk-patches/0001-sprite2d-board-support.patch` because pushing to
`kotoba-lang/sprite2d` needs repo access this session did not have.

| gap | why a board needs it |
|---|---|
| `:text` primitive | text existed only in the transient screen-space fx layer. A building's name and ISIC code belong to the building and move with it. |
| camera `:fixed` / `:fit` | the default camera follows a `"player"` entity, and silently anchors at world origin when there is none — indistinguishable from a correctly centred board until the board isn't at the origin. `:fit` also re-solves the scale per viewport. |
| `layout/pick` (+ `sprite-bounds`) | `draw-list` mapped world→screen and nothing mapped back, so every tap-driven game had to re-derive the camera transform, as a guess, since sprite extents were not exposed. |

Three further defects surfaced while doing it and are fixed in the same patch: `clojure
-M:test` did not start at all (an unconditional `:cljs`-only require took the suite down
before any assertion ran), the JVM stubs that `test/sprite2d_test.clj` has always asserted
about were never written, and `kotoba.sprite2d.layout` was a verbatim **fork** of
`kami.sprite2d.layout` rather than the facade the README claims — so the layout tests
exercised the copy while the painter used the original.

`test/world_ir_test.clj` runs this game's map IR against the real SDK. Revert the patch and
it does not compile.

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
| `world.cljc` | the street: district registry, unlock ladder, and the sprite2d render-IR |
| `test/world_ir_test.clj` | 17 tests / 102 assertions, JVM, against the real `kotoba.sprite2d.layout` |
| `sdk-patches/` | the upstream `sprite2d` commit, staged until it can be pushed |
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

# the map, against the real KAMI 2D stack (needs sprite2d checked out)
west update --fetch smart sprite2d
clojure -M:ir-test
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
