# ADR-2607022800: kotoba-lang org — dependency/relationship map

**Status**: accepted
**Date**: 2026-07-02
**Data SSoT**: `2607022800-kotoba-lang-org-dependency-map.edn` (this document is the
narrative companion; the `.edn` is the queryable source of truth)

## Context

The owner asked for two things together: (1) import all ~359 `kotoba-lang`
GitHub org repos into the west manifest, and (2) organize their
dependencies/relationships in an `adr.edn`.

Investigating (1) first changed the shape of (2). A basename-vs-`path:`
comparison between the live GitHub org (363 repos as of 2026-07-02) and
`manifest/west.yml` found that **358 of 363 were already correctly
registered** — most through a long, careful history of per-cluster
`:extra-projects` additions, each landed with its own ADR and, critically,
with an inline comment in `manifest/repos.edn` explaining *why* the repo
exists and what it relates to. Only **5 repos were a genuine gap**
(`cad`, `eda`, `genome`, `mst-projector`, `lab` — real, pre-existing GitHub
repos that had simply never been added), and this ADR's companion commit
registers them (see `:gap-sweep-2026-07-02` in the `.edn`).

The remaining 4 unmatched names are not gaps:
- `cnc-toolpath` is a self-documented duplicate of `cnc` awaiting an owner
  disposition call.
- `kami-autodrive`, `kami-shugyo`, `kami-vehicle` are size-0 repos pushed
  within minutes of this ADR being written — active work-in-progress from a
  concurrent session restoring `kami-engine` crates (ADR-2607010930). Per the
  standing rule against touching another session's in-flight work, these are
  left alone; they'll presumably be registered by that session when ready.
- `com-junkawasaki/kototama` was GitHub-transferred to `kotoba-lang/kototama`,
  but the local checkout and `:path-overrides` entry haven't been updated
  together. Adding the override alone would make the generator silently
  **drop** the entry (no working checkout at the new canonical path, no
  existing `west.yml` entry there either to fall back to) — this needs the
  checkout relocated in the same change as the override, deferred as a
  separate, more careful piece of work.

So the real remaining ask was narrower than "import 359 repos": it was
**"make the tribal knowledge that already exists legible."** `repos.edn`
turned out to already contain ~44 comment-documented clusters recording real
architectural decisions (ADR references, split lineages, explicit
dependencies, and — notably — an entire naming-collision-avoidance discipline
where restoration efforts renamed away from a crate's natural short name to
avoid colliding with an unrelated, older repo). That knowledge was scattered
across 700+ lines of inline comments in a file whose primary job is being a
west generator input, not a readable map. This ADR consolidates it.

## Decision

Rather than fabricate a from-scratch, exhaustive dependency graph across all
363 repos (expensive, and mostly false precision — most repos are leaf
libraries with zero documented cross-repo relationship), this ADR:

1. **Mines every existing cluster comment** in `manifest/repos.edn`'s
   `:extra-projects` into one normalized `:clusters` vector (44 entries) in
   the companion `.edn`, each carrying its theme, spawning ADR id(s), member
   repo list, and any explicitly stated relationship.
2. **Ground-truths a dependency subgraph** for the crypto/CBOR/CID/CRDT/MST
   substrate — the repos most other things build on — by reading real
   `deps.edn` files (`:local/root` and `io.github.kotoba-lang/*` git-sha
   coordinates) rather than trusting comments alone.
3. **Extracts the full naming-collision table**: every case where a
   `kami-engine`-crate restoration renamed away from its natural short name
   because an unrelated, pre-existing `kotoba-lang/<short-name>` repo already
   held it.
4. **Flags, but does not silently fix,** three small documentation
   inconsistencies found along the way (see `:open-questions` in the `.edn`)
   — none affect west/CI correctness today, so they're recorded rather than
   edited into a live, in-flight file.

## The 4-org taxonomy

Authority: ADR-2606302300. `kotoba-lang` = pure-CLJC language substrate,
consumed by every other org. `etzhayyim` = agent-centric, public-interest
organism actors. `gftdcojp` = human-centric business products. `com-junkawasaki`
= transitional foundation, shrinking toward data-only as its `-clj` libraries
finish migrating kotoba-lang-ward. Full detail in `.edn` `:orgs`.

## Cluster catalog (summary)

The full 44-cluster catalog lives in the `.edn`'s `:clusters` vector. In
broad strokes:

- **Foundational stdlib** (P0/P1/P2/M5 tiers): `async`, `device`, `coll`,
  `json`, `spec`, `wit`, `scheduler`, `store`, `lint`, `text`, `log`, `fmt`,
  `lsp`, `test`, `fs`, `http`, `io`, `time`, etc.
- **AT Protocol / content-addressed data substrate**: `atproto`, `mst`,
  `crdt`, `commit-dag`, `prolly-tree`, `quad-store`, `kqe`, `kotoba-client` —
  the `kotoba-store`/`kotoba-query`/`kotoba-runtime` CLJC restoration lineage
  (ADR-2607010930 Phase 6), explicitly reusing `multiformats`/`dag-cbor`/`cacao`.
- **etzhayyim-sdk relocation batch** (8 repos, 3 ADR waves): `kami-nv-compat`,
  `hinshitsu`, `pqh`, `ipfs`, `checkpointer`, `base-l2`, `witness-quorum`,
  `atproto-client` — the former `etzhayyim-sdk` monorepo split apart.
- **kami-engine crate restoration** (by far the largest family, ~150+ repos
  across many waves — ADR-2607010930): everything from `kami-app-*` domain
  apps to the WebGPU DSL/runtime split (~55 repos) to the scene/gameplay
  block (~50 repos, uncommented but contextually the same effort) to the
  EDA/CAD restoration cluster (`brep`/`fea`/`pcb`/`dft`/`spice`) to the
  mangaka `*-clj` domain crates (22 repos, ADR-2607010930, following the
  `kami-genko` precedent).
- **Vertical capability libs**: ISIC (`swift`, `banking`, `phone`, `card`,
  `retail`, `logistics`, `property`, `labor`, `robotics`), ISCO-08
  (`occupation`), Bitcoin (`btc-crypto`, `btc-mining`, `mining-pool`,
  `wallet`).
- **Product/actor repos**: `giemon` (robot line, depends on `robotics`),
  `arxiv`, `security`, `gftdcojp/ai-gftd-newscaster`, various
  `etzhayyim/com-etzhayyim-*` organism actors.

## Naming-collision-avoidance discipline

The most structurally interesting finding: `kami-engine` crate restorations
never blindly claim the crate's natural short name. Eleven documented cases
renamed away from a collision with an unrelated, older `kotoba-lang/<name>`
repo:

| crate | rejected name | actual name | why |
|---|---|---|---|
| kami-cam | `cam` | `cnc` | `cam` = unrelated camera-rig repo |
| kami-rt | `rt` | `raytrace` | `rt` = real-time media transport (ADR-2607011700) |
| kami-text | `text` | `glyph` | `text` = foundational stdlib repo |
| kami-cad | `cad` | `brep` | `cad` = unrelated industrial CAD/CAM workbench |
| kami-cae | `cae-solver` | `fea` | `cae-solver` = unrelated ROM/LBM CFD contract |
| kami-eda | `eda` | `pcb` | `eda` = unrelated OpenSTA/OpenROAD-class kernel |
| kami-verify | `verify` | `model-checking` | too generic/collision-prone |
| kami-si | `si` | `signal-integrity` | ambiguous vs SI units |
| kami-pkg | `pkg` | `ic-packaging` | npm/Debian package connotation |
| kami-ip | `ip` | `ip-xact` | IP address vs intellectual property |
| kami-flow | — | `kami-flow` (kept, no rename needed) | **correction, 2026-07-02**: `kami-flow` was *initially* misjudged as an unrelated Node.js/Cypher graph-ingest tool (that description actually belongs to an unrelated `graph/` subdirectory sharing the same `kami-engine/kami-flow/` folder path). Its own `src/lib.rs` is a genuine RTL→PnR→GDSII→Verify/Power/DFT/SI/Yield→STA→DRC/LVS→Signoff orchestrator; it has since been restored to `kotoba-lang/kami-flow`, wiring together 7 already-restored sibling EDA crates (`rtl`/`pnr`/`model-checking`/`power`/`dft`/`signal-integrity`/`yield`). See ADR-2607010930 Phase 7. |

This is also exactly why this ADR's own gap-sweep registration of `cad` and
`eda` (cluster 44) is unrelated to the `brep`/`pcb` renames — those renames
exist *because* `cad`/`eda` are real, older, different repos.

## Ground-truthed dependency graph (substrate hub)

Read directly from `deps.edn` in the 17 locally-checked-out hub repos, not
inferred from comments:

```
ed25519 ─┬─▶ cacao (local/root)
         ├─▶ pqh
         └─▶ witness-quorum

dag-cbor ─┬─▶ cacao (local/root)
          ├─▶ pqh
          ├─▶ commit-dag
          └─▶ prolly-tree

multiformats ─┬─▶ checkpointer
              ├─▶ commit-dag
              ├─▶ prolly-tree
              └─▶ quad-store

mst ─▶ checkpointer
pqh ─▶ checkpointer
prolly-tree ─▶ quad-store
eth-crypto ─▶ base-l2
```

Zero-dep leaves: `ed25519`, `dag-cbor`, `multiformats`, `did`, `atproto`,
`mst`, `eth-crypto`, `crdt`. Notably, `commit-dag` has **no** compile-time
dependency on `prolly-tree` despite both belonging to the same
database-crates roadmap (ADR-2607022600) — a commit wraps an opaque `state`
value, so the coupling is architectural, not a code dependency.

One loose end from this ADR's first pass has since been fixed (2026-07-02,
same day): `koe`'s `deps.edn` — both its main `:deps` entry and its `:dev`
`:override-deps` — referenced the stale `io.github.com-junkawasaki/
langgraph-clj`/`langchain-clj` coordinates (`langgraph-clj` was renamed to
`langgraph` and moved org to `kotoba-lang` before this ADR was written, but
`koe`'s own `deps.edn` was never updated to follow). Fixed to
`io.github.kotoba-lang/langgraph` at the same commit the `v0.2.0` tag
already pointed to (`133740f...` — confirming this was purely a stale
coordinate, not a version skew); `clojure -M:test` now passes (3 tests / 13
assertions). See `:phase-2-2026-07-02` in the `.edn`.

`eth-crypto`'s own `deps.edn` header still says it's "lift-ready" for
extraction back to `com-junkawasaki/eth-crypto-clj`, a small tension against
the general kotoba-lang-ward migration direction — left as-is (not this
ADR's call to make).

## Phase 2 (2026-07-02, same day) — `-clj` suffix cleanup + full repo re-audit

A follow-up pass, triggered by an owner request to re-verify duplicates and
dependency health across the (now 377-repo) org, found and fixed several
additional issues this ADR's first pass didn't cover:

### `-clj`-suffix rename sweep

The org's established convention (see the naming-collision table above, and
the pre-existing `aero-clj→aero`, `crash-clj→crash`, `datom-clj→datom`,
`echem-clj→echem`, `motor-clj→motor`, `vphysics-clj→vphysics`,
`langchain-clj→langchain`, `langgraph-clj→langgraph` renames from before this
ADR existed) is: when a `-clj`-suffixed repo migrates into `kotoba-lang`, the
suffix is dropped **unless** doing so would collide with an unrelated,
older, still-live `kotoba-lang/<short-name>` repo.

Applying this discipline to the 9 `-clj`-suffixed repos still live as of
2026-07-02 (7 from ADR-2607010930 Phase 7's `*-clj` monorepo split, plus 2
pre-existing):

| repo | action | reason |
|---|---|---|
| `engine-clj` → `engine` | **renamed** | no collision |
| `kami-mangaka-page-clj` → `kami-mangaka-page` | **renamed** | no collision |
| `kami-mangaka-reader-clj` → `kami-mangaka-reader` | **renamed** | no collision |
| `kami-mangaka-render-clj` → `kami-mangaka-render` | **renamed** | no collision |
| `kami-mangaka-text-clj` → `kami-mangaka-text` | **renamed** | no collision |
| `kami-app-sip-clj` → `kami-app-sip` | **renamed** | no collision |
| `kami-mangaka-scene-clj` | **kept** | collides with `kotoba-lang/kami-mangaka-scene` (the separate Rust/PyO3-origin 3D crate, ADR-2607010930 Phase 7) |
| `kami-engine-sdk-clj` | **kept** | collides with `kotoba-lang/kami-engine-sdk` (a pre-existing, unrelated Svelte 5 UI component mirror) |
| `kototama-clj` | **kept** | genuinely distinct project from `kotoba-lang/kototama` (the WASM-runtime compiler) — not a naming legacy, a real different thing |

Each rename was performed via `gh repo rename` (GitHub preserves history and
sets up an automatic redirect from the old name), followed by updating
`manifest/repos.edn`'s `:extra-projects` entry and `manifest/west.yml`'s
`name:`/`path:` fields to match.

### Dependent-repo dep fixes (post-rename)

3 repos had `deps.edn` `:git/url` coordinates pointing at the pre-rename
names, which would 404 on next resolve:

- `kami-app-sip` (deps on `kami-mangaka-render`, `kami-mangaka-page`)
- `kami-mangaka-reader` (deps on `kami-mangaka-text`)
- `kami-mangaka-page` (deps on `kami-mangaka-text`)

All 3 fixed to the new `:git/url`s (SHAs unchanged — renaming a GitHub repo
does not change any commit SHA), verified via `clojure -M:test` (all green
except `kami-app-sip`'s pre-existing, unrelated `sip.store`/`:datomic`-alias
gap, already documented in that repo's own history).

### Full-org dependency graph (quantitative, all 377 repos)

Distinct from this ADR's original "17 locally-checked-out hub repos" ground-
truth, this pass parsed `deps.edn` (both `:git/url` and `:local/root`
entries) across **all 362 repos that have a `deps.edn`** (362/377; the other
15 are non-CLJC or contract-only, e.g. `homebrew-kotoba`, `kami-engine`,
`kotoba-v2025`). Headline numbers (full graph in the `.edn`'s
`:full-org-dependency-graph`):

- **88 repos** declare at least one `kotoba-lang`-internal dependency; the
  other **274 (73%)** are genuinely standalone zero-dep leaves.
- **167 total dependency edges.**
- **21 connected clusters** of size ≥2 (the largest single one, 35 repos, is
  the office/appkit/OOXML family rooted at `css`/`html`/`shitsuke`; the
  second-largest, 21 repos, is the `kami-*-scene` family rooted at `scene`
  from ADR-2607010930 Phase 7).
- Most-depended-upon repos (in-degree): `css` (13), `html` (12), `scene`
  (11, all from Phase 7's `kami-*-scene` restorations), `shitsuke` (10),
  `langchain`/`langchain-clj`-*sic*-now-`langchain` (7).

### Known remaining gap — closed in Phase 3 (2026-07-02, same day)

A broader sweep for stale `-clj`-suffixed dependency coordinates (beyond the
`koe` fix above) initially flagged ~17 `deps.edn` files as possible
candidates. Re-verifying each against a fresh fetch found only **8 genuinely
still stale**: `kami-engine-vehicle-designer`, `authenticator`,
`kotoba-code`, `kekkai`, `computer-use`, `kotodama`, `kotoba-fleet`,
`godaddy-dns` — the rest (`browser-use`, `kagi`, `langgraph-store`, `kenchi`,
`cae-solver`, bare `langgraph`/`datom`/`vphysics`) turned out not to actually
contain the stale pattern; the original flagging grep had matched a looser
pattern than the real issue.

All 8 were fixed: `io.github.com-junkawasaki/<name>-clj` →
`io.github.kotoba-lang/<name>` (dropping `-clj`), preserving the original
`:git/tag` where one was pinned (SHA resolved to its full 40-hex form —
unchanged by the rename, since renaming a GitHub repo doesn't rewrite
history) or pinning to the target repo's current `main` HEAD where the
original only used an unversioned `:local/root`. `kami-engine-vehicle-
designer` was the deepest fix (7 deps: `langgraph`/`datom`/`vphysics`/
`aero`/`crash`/`echem`/`motor`, all converted from never-resolving
monorepo-sibling `:local/root` paths to real `:git/url`+`:sha` coordinates).

**Total: 8 repos fixed, 98 tests / 366 assertions passing, 0 failures.**
See `:phase-3-2026-07-02-stale-clj-dep-fix-sweep` in the `.edn` for the
full per-repo breakdown.

## Consequences

- The "359-repo import" is closed: 5 genuine gaps registered, 4 exceptions
  documented and correctly left alone.
- Future contributors have one place (`:clusters` in the `.edn`) to check
  "why does this repo exist and what's it related to" instead of grepping
  700 lines of `repos.edn` comments.
- Three small `repos.edn` comment inaccuracies are now flagged
  (`:open-questions`) for whoever next touches those regions — not fixed
  proactively, since `repos.edn` is a live, actively-edited file and none of
  the three affect generator correctness.
- This ADR is descriptive, not prescriptive: it does not change how new
  repos get registered (`:manifest-workflow` in `repos.edn` remains the
  authority for that) or reorganize `repos.edn` itself.

## Phase 4 (2026-07-02) — 全 382 repo の完全依存グラフを adr.edn に埋め込み

オーナー指示「382 repos の repo 依存関係を adr.edn で整理して」。Phase 2 は
サマリ統計と top-N 抜粋のみだったのに対し、本フェーズは **全 repo の deps.edn
を GitHub から新規取得**して導出した**完全な内部 adjacency をこの ADR の
`.edn` 自体に埋め込んだ**（`:phase-4-2026-07-02-full-edge-graph` —
`:adjacency-main` / `:adjacency-alias-only` / `:local-root-internal-edges`）。
ローカル checkout ではなく pushed reality を見ているので、同日登録の
ipld / p2p（ADR-2607023200）もグラフに入っている。

数字（Phase 2 の 377-repo 時点 → 本フェーズ 382-repo 時点）:

- deps.edn あり: 362 → **367**（無し 15 は不変の同一リスト）
- 内部 main 依存を持つ repo: 88 → **109**（`:local/root` の非 github 形式
  group — `org.kotoba-lang/num` 等 — を正規化して取り込んだ分も含む）
- 内部 main エッジ: 167 → **201**、加えて alias（test/dev）限定の内部依存
  15 repo を別掲
- 連結成分: 21 → **15**（最大 51 repo = css/html/shitsuke の office/UI 族）、
  **循環なし**（DFS で確認）
- 被依存 top: css 13 / langgraph 12 / html 12 / shitsuke 10 / coll 7 /
  json 6 / xml 6 / langchain 6 / eth-crypto 5 / chobo 5 / datom 5 / dsl-core 5

新たに見つかった実問題（`:findings`、修正は行わず記録のみ）:

1. **kagi → kagitaba が壊れている**: `io.github.kotoba-lang/kagitaba
   {:local/root "../kagitaba"}` だが kotoba-lang/kagitaba は GitHub に
   存在しない（ADR-2607023000 の同日 WIP、未 push）。fresh clone から
   kagi はビルド不能。kagitaba push 後に :git/sha で pin する follow-up。
2. **stale *-clj 座標の残り 3 件**: kototama-clj → com-junkawasaki/
   {langchain-clj, langgraph-clj}（Phase 2 は「名前が別物」として repo 名の
   rename 対象から除外したが、**deps 座標**は stale のまま）、signal →
   com-junkawasaki/ed25519-clj（Phase 2 の候補リスト自体から漏れていた）。
   fix-pattern は Phase 2/3 と同じ。
3. **:local/root 限定の内部エッジ 73 本 / 43 repo**: west superproject
   レイアウトでしか解決しない（standalone clone では壊れる）。一覧を
   `:local-root-internal-edges :by-repo` に収載。ほか `inference` と
   `kotoba` は group 名も非 github 形式（`org.kotoba-lang/…`、素の
   `kotoba-lang/…`）で、git 依存としては永遠に解決不能な座標。

## Phase 5 (2026-07-02) — Phase 4 findings の fix sweep

オーナー承認("do it")により Phase 4 の findings を修正した。

- **signal** (`36cb3c11`): `com-junkawasaki/ed25519-clj` → `kotoba-lang/ed25519`。
  pin SHA `ec077bca` は移行先 repo からも到達可能だったため **SHA 据え置きの
  純座標修正**(解決される tree はバイト同一)。23 tests green。
- **inference** (`d4b8de31`): `org.kotoba-lang/*`(git 依存として解決不能な
  group)+ `:local/root` → `io.github.kotoba-lang/{num,torch}` の main-HEAD
  pin。`:dev` alias に offline 用 `:local/root` override を温存。12 tests green。
- **kotoba** (`7096842d`): `:local/root ../../kotoba-lang/*` 4 本 → git pin。
  従来 **fresh clone では classpath 構築すら不能**だったが、full suite
  (109 tests / 613 assertions) が通るようになった。
- **cacao** (`c88333c7`, Phase 4 の findings に無かった実バグ): kotoba の修正が
  **公開 repo 2 つの namespace 衝突**を炙り出した — cacao と
  kotoba-lang/kotoba-lang の両方が `kotoba.cli` を出荷しており、アルファベット順
  classpath で cacao 側(identity CLI)が canonical な `kotoba.cli/dispatch` を
  黙って shadow する。monorepo の `:local/root` 時代は露見しなかったが、git pin
  化で `No such var: cli/dispatch` として顕在化。cacao 側を `cacao.cli` に
  rename(bin/bb.edn/test/README 追従、外部消費者なしを確認)。21 tests green。

**修正しなかったもの**: kototama-clj は **archived(read-only)** のため
push 不能(fix 自体はローカルで 40 tests green まで検証済み、unarchive されれば
そのまま適用可)。kagi → kagitaba は他セッションの未 push WIP のまま変わらず。

併せて、本日 push した全 12 repo(ADR-2607023200 の tag-42 チェーン 8 repo +
本フェーズ 4 repo)の **west pin を `--entry` 最小 diff で前進**(全件サーバ側
検証で pure fast-forward 確認済み)。

### Phase 5 追記 — kototama-clj の退役 (2026-07-02)

Phase 5 で「archived のため push 不能」と記録した kototama-clj は、オーナー判断
(「不要なら削除していい」) により **GitHub から削除・manifest から除籍**した。
削除前に検証: 382-repo 依存グラフで dependents ゼロ、org 横断 code search で
参照ゼロ、`kototama`(organism 契約 authority)・`kototama-cljc-contract`
(clj-wgsl scaffold)とは別物、ローカル 2 checkout とも WIP なし(west 側は
remote HEAD `a9134aff` と一致、これがディスク上に残る最後のコピー)。west.yml
は entry block 5 行のみの最小 diff で除籍(sparse worktree での wholesale 再生成
は環境依存の submodule リスト脱落を混ぜるため破棄した)。
