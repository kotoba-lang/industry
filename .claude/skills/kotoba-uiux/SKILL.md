---
name: kotoba-uiux
description: Build web / local-app UI in cljc on this workspace's BASE design system, jp-go-dds (デジタル庁デザインシステム), with the shared --hig-* token contract bridged onto it. Also covers the legacy kotoba-ui/liquid-glass stack, which remains only where it has not been migrated. Use whenever you are about to write ANY frontend/UI/page/site code in this monorepo — a new site, a new app screen, a redesign, a landing page, an admin console, or when the user says a design is "いまいち/not refined/ダサい". Read this BEFORE writing the first line of HTML/CSS/hiccup. Also use when reviewing UI code for design-system conformance.
---

# kotoba-uiux — the paved road to refined UI

## The base is `jp-go-dds` (owner decision, 2026-08-05)

**New UI is built on `kotoba-lang/jp-go-digital-design-system`** — the デジタル庁
デザインシステム (DADS) mirror — not on liquid-glass. Measured when the decision was
made: **170 repos already depended on jp-go-dds, 12 on kotoba-ui.** DADS was already the
workspace's design language; this makes it official and stops new work landing on the
minority stack.

```clojure
(require '[jp-go-dds.core :as dds]      ; button / select / table / chip-label / …
         '[jp-go-dds.page :as page]     ; ->page (charset / viewport / theme-color)
         '[jp-go-dds.tokens :as tokens]) ; bridge-css — the --hig-* contract on DADS
```

Three things to know before writing any of it:

1. **The `--hig-*` token contract still holds.** `tokens/bridge-css` redefines every
   `--hig-*` custom property on top of DADS primitives, so view/SVG/CSS written to the
   contract follows DADS **unmodified**. That is what made kami-genko / kami-app-daw /
   kami-app-nle move bases without touching their stylesheets. Keep writing
   `var(--hig-spacing-4)`, not a DADS primitive, unless you are styling a `dads-*`
   component itself.
2. **An app that takes DADS as its base has no `shitsuke.hig` underneath.** An unmapped
   token resolves to *nothing* — `padding: var(--hig-spacing-4)` collapses, silently. If
   you need a token the bridge does not carry, add it to `hig->dads` upstream; do not
   re-derive it in app CSS (the bridge's own docstring: the contract breaks the moment a
   second app derives its own).
3. **DADS is light.** `page` takes `:dark? true` for this library's own inversion layer
   (not upstream's) — right for an editor that grades against a dark surround, and what
   kami-app-daw / kami-app-nle use.

**What DADS does not have**: an app-shell / editor frame, a segmented control, a list
with a trailing slot, an accent an app can choose. The first three are app CSS on the
token contract (see kami-genko); the fourth is the point — DADS ships デジタル庁ブルー
and an app does not pick its own.

## Build it as a single page (owner decision, 2026-08-08, ADR-2608080100)

**kotoba-lang UI is single-page apps: one document, one bundle, one mount.** Moving
between screens changes state, not location. If you are about to add a second HTML
file to an app, it is a **view**, not a document.

Measured on the two apps this rule came from: they had a second document for their
user-test dashboard, which meant React, cljs core and the whole design system were
**compiled and shipped twice** — 3.6 MB across the pair, 2.1 MB as one page. The
difference was not features. And with two app shells, only one of them followed the
DADS migration: the other went on serving a `<link>` to the `liquid-glass.css` that
migration had deleted, unstyled, for three days.

1. **Views are data; the nav is generated from them.** A view added to the dispatch
   and forgotten in the nav is dead code that looks live. Generating removes the
   possibility. The nav is `dds/button` with `:href` — real links that are still
   design-system controls, and **no app CSS for any of it**.
2. **Address views with the fragment, not a path.** On a static host (Pages, the
   cloud-itonami sites plane) `pushState` to `/user-test` gives a URL that works
   until someone reloads it and then 404s. Use `pushState` only where a server
   rewrite actually exists (a Worker's `not_found_handling: single-page-application`)
   — check, don't assume.
3. **Ship a `404.html`, and do not rewrite every unknown path to `./`** — `/x/y`
   would go to `/x/`, also missing, and the fallback redirects to itself forever.
   Send only the addresses that really moved; redirect relative (`./`) so one
   artifact is correct at any mount point.
4. **Assert that crossing a view does not load a document.** This is invisible in
   the source — the code reads the same whether the nav routes or navigates. Leave a
   value on `window`, cross, and check it survived. Wait on an element that exists
   *only* in the target view (waiting on something both views have returns before the
   crossing renders). Assert app state survives too; without that, the single page
   bought nothing.

5. **Viewable is half the rule.** A UI is not finished when it compiles — it is
   finished when there is one address a person can open and see it at. An app that
   builds a bundle and ships no document has nothing to open; an app that ships two
   has stopped being one page. Both are the same failure of *viewable as a single
   page*, from opposite sides, and neither is visible in the source.

**Check it — this is no longer prose only.** From the superproject root:

```bash
nbb scripts/verify-single-page-app.cljs --root . --findings
```

exit 0 clean · 1 findings · **2 refused** (it could not tell a registered checkout
from a stale one, and says so rather than reporting clean). It reports
`multi-document` for a repo with two script-loading documents and `no-document` for
a `:target :browser` build with none; `404.html` is exempt, because a static host
*needs* one and reporting it would punish following the rule. Registered as
`:verify-single-page-app` in `manifest/orgs-detectors.edn`, where the SSR/OG
marketing surfaces are carried as `:accepted` with a date and an exit condition.

Its blind spot, stated rather than implied: `no-document` asks for shadow-cljs
`:target :browser`, so an **`:esm` app that loses its only document is not caught** —
`:esm` is also every library's target, and without the narrowing the class reported
232 of 285 repos, nearly all correct by design. `multi-document` does cover `:esm`.

**Exception**: pages that need SSR/OG for crawlers (ADR-2606290000). Don't bind an app
and a marketing surface with one rule.

**No shared router yet.** Two apps each hold a ~60-line `route.cljc` (view table +
`fragment->view` + `nav` pure, listener behind `#?(:cljs)`); `.cljc` so addressability
is testable without a browser. **The extraction trigger is the third app** — and not
into `jp-go-dds`, since routing is neither markup nor CSS. Read those two first.

`dds-ext-*` (container / section / grid / stack / row / card) is the library's own
non-upstream layout layer. Extend it upstream rather than re-deriving layout in app CSS.

## Legacy: kotoba-ui / liquid-glass

Still correct for the ~12 repos that have not moved (`kotoba-lang/app-*`,
`cloud-itonami/kaisya`, `lawfirm`, `gftdcojp/apex`). Everything below this line describes
that stack and stays true for them. **Do not start new UI on it.**

The full recipe lives in `orgs/kotoba-lang/kotoba-ui/docs/agent-guide.md` — read that
file next if you are working in one of those repos. This SKILL.md is the contract
summary; the agent-guide is the how-to with worked examples.

## Status: this stack is migrating to `.kotoba` (owner decision 2026-07-27, ADR-2607270100 §10)

`css` / `html` / `shitsuke` / `liquid-glass-ui` / `kotoba-ui` are **not** a permanent `.cljc`
layer — they are targets for `.kotoba` migration, in that dependency order. They need **no
capabilities** (`->page` returns a string, so `kotoba/pure` covers them).

**Do not start the real cutover yet, and do not treat string-only SSR as the target.**
ADR-2607279200 Delivery #6 and the migration plan say verbatim: *"Do not make string-only SSR
the final abstraction. Start cutover when the shared logical value and both required renderers
for that tranche are qualified."* Kotoba has no recursive value types **today**, but plan W4
schedules them with explicit node/depth/byte budgets and states that *"handles are not the
application programming model"* — so do not generalise flat/parent-pointer node sets as the way
to write Kotoba UI. Anything written before W4 is an oracle-backed experiment, not the API.
Write new `.kotoba` on the `compile` path, never the legacy emitter — see CLAUDE.md.

**Until a repo has actually migrated, every rule below still applies unchanged**, and app code
must not require a mid-migration repo directly.

## The stack (dependency direction is law)

```
css / html                 L0 substrate (EDN→CSS / hiccup→HTML)
shitsuke (+ shitsuke.hig)  L1 structure + HIG semantic tokens (SSoT)
liquid-glass-ui            L2 material skin (@layer kotoba.glass)
kotoba-ui                  L3 THE single entry: core + shell + theme + ->page
appkit / uikit             L4 platform traits (desktop-dense / touch-card)
your app                   L5
```

## Non-negotiable rules (each one is a distilled production failure)

1. **Apps require `kotoba-ui.core` (+ `appkit.core` for desktop-dense or `uikit.core`
   for touch/mobile) — never `liquid-glass.*` or `shitsuke.*` directly.** Direct
   requires are an opt-out that needs a written reason (ADR-2607122200).
2. **Never write a raw hex color, px font-size, or font-family in app code.** Every
   color/type/spacing/radius decision is a `--hig-*` or `--liquid-glass-*` CSS var or a
   theme-map override. If a value you need has no token, that is a design-system gap —
   add the token upstream, don't inline the value.
3. **Never fight library CSS with specificity.** All library CSS ships inside
   `@layer kotoba.hig, kotoba.glass;` and app CSS is **unlayered, so it always wins** —
   compound selectors like `.liquid-glass__toolbar.app-toolbar` are dead weight
   (net-babiniku accumulated ~412 lines of such fights before this contract existed).
4. **Start every page from `kotoba-ui.shell`** (`page`/`app-shell`/`hero`/`section`/
   `stack`/`grid`) — do not hand-write layout CSS (`.layout`, `.hero`, grid templates).
   If shell lacks a pattern you need, extend shell upstream.
5. **Theme = one map** (e.g. `{:accent "#FF3CAC" :appearance :dark}`) passed to
   `kotoba-ui.theme`/`->page`. Dark mode comes from `color-scheme` + tokens — never
   hand-write a dark palette.
6. **Typography = the 11 HIG text styles** (`large-title`…`caption2`, `.hig-*` utility
   classes or `--hig-text-*` vars). Headings/body/captions never get ad-hoc sizes.
7. **Accessibility is part of "refined"**: keep the components' ARIA roles, ≥4.5:1
   contrast (tokens already guarantee it), `:focus-visible` ring intact, respect
   `prefers-reduced-motion` (the base layer already does — don't undo it).
8. **SSR-first, dual-render**: views are pure `.cljc` hiccup; `kotoba-ui.core/->page`
   renders the full document server-side/nbb; the same view mounts in the browser via
   shitsuke's reagent seam. Runtime order per repo rule: kotoba wasm > clojurewasm >
   ClojureScript > nbb (JVM/bb are compat-only).

## Measure it (unmeasured UI quality is theater)

After building or changing a page, score the rendered HTML with the deterministic
HIG/WCAG audit (`kotoba-lang/design-quality`, ADR-2607132300):

```bash
cd orgs/kotoba-lang/design-quality && nbb -m design-quality.cli score /path/to/rendered.html --min 95
```

Exit 1 below `--min` — wire it as a CI gate like kotoba-ui's self-scoring test.
The LLM-judge layer (`.claude/workflows/design-quality-score.js`) is the
complementary subjective arm. (Babashka/`bb` is retired; ADR-2607173000.)

## Review checklist (when auditing UI code)

- [ ] requires only kotoba-ui/appkit/uikit  - [ ] zero raw hex / px font sizes
- [ ] zero `.liquid-glass__*` compound-selector overrides  - [ ] layout via shell
- [ ] one theme map  - [ ] HIG text styles only  - [ ] light AND dark verified
- [ ] app CSS unlayered and small (< ~50 lines is healthy)

Full worked example + do/don't table: `orgs/kotoba-lang/kotoba-ui/docs/agent-guide.md`.

---

# 付録 — CLAUDE.md から移した本文（2026-09-08、ADR-2609081000）

CLAUDE.md の「UI/UX 標準」「UI/UX 品質の数値化」節は肥大化のためこちらへ移した。
CLAUDE.md 側には skill を読まなくても効く不変条件だけが残っている。以下は逐語。

## UI/UX 標準 — 基本 design system は `jp-go-dds`（repo-wide mandatory、2026-08-05）

**このリポジトリ群で web / local app の UI を書く時は、コードを書き始める前に Skill
ツールで `kotoba-uiux` を呼ぶ。** そして**新規 UI の基盤は
`kotoba-lang/jp-go-digital-design-system`（デジタル庁デザインシステム = DADS）で
あって liquid-glass ではない**（オーナー判断 2026-08-05）。

判断時の実測: **DADS 依存 170 repo / kotoba-ui 依存 12 repo**。DADS は既にこの
ワークスペースの共通言語で、この決定はそれを追認し、新しい仕事が少数派スタックに
着地するのを止めるもの。

```clojure
(require '[jp-go-dds.core :as dds]       ; button / select / table / chip-label / …
         '[jp-go-dds.page :as page]      ; ->page
         '[jp-go-dds.tokens :as tokens])  ; bridge-css — --hig-* 契約を DADS の上に
```

- **`--hig-*` トークン契約はそのまま生きる。** `tokens/bridge-css` が全 `--hig-*`
  を DADS primitive の上に再定義するので、契約で書かれた view / SVG / CSS は**無改造で**
  DADS に追従する。実際 kami-genko / kami-app-daw / kami-app-nle は app CSS を 1 行も
  変えずに基盤を移した。`dads-*` コンポーネント自体を触る時以外は、DADS primitive
  ではなく `var(--hig-spacing-4)` を書き続けること。
- **DADS を基盤にした app の下には `shitsuke.hig` が居ない。** 橋渡しに無いトークンは
  **何にも解決しない**（`padding: var(--hig-spacing-4)` が黙って消える）。足りなければ
  上流の `hig->dads` に足す —— app CSS で再導出しない（bridge 自身の docstring:
  「2つ目のアプリが再導出した瞬間に契約は壊れる」）。
  **再実測（2026-08-08）: bridge は 71 個を運ぶ —— `--hig-color-*`(18) /
  `--hig-text-*`(22) / `--hig-spacing-*`(11) / `--hig-palette-*`(9) /
  `--hig-radius-*`(7) / `--hig-font-*`(3) / `--hig-hairline`。**
  2026-08-05 版のこの節は「27 個で spacing / text-size / radius は 1 つも無い」と
  書いていたが、その後 upstream の `e671277`「bridge the rest of the `--hig-*`
  contract」が入って解消している。**したがって `padding: var(--hig-spacing-4)` も
  `font-size: var(--hig-text-footnote-font-size)` も `--hig-radius-xs` も、
  DADS 基盤でそのまま書いてよい** —— 旧記述に従って `em` 相対や DADS primitive を
  直接書くと、いま在る契約から不要に外れる。
  **残っている本物の穴は `--hig-palette-*` の 6 色**（teal / mint / indigo / brown /
  gray2-6）。DADS に対応する色相が無いので意図的に載せていない。bridge の docstring は
  「載せなければ `shitsuke.hig` の既定値が効く」と書いているが、**DADS 基盤の app の
  下に `shitsuke.hig` は居ない**（`jp-go-dds.page` は bridge も HIG も自動では入れず、
  app が `:app-css` で `tokens/bridge-css` を渡す）ので、そこでは**何にも解決しない**。
  カテゴリ色にこの 6 つを使っている view は移行前に確認する。
  確認コマンド（`grep` は行内 1 件しか数えないので使わない）:
  `clojure -M -e "(require '[jp-go-dds.tokens :as t]) (println (count t/hig->dads))"`。
- **DADS は light。** `page` の `:dark? true` はこのライブラリ独自の反転層（上流には
  dark palette が無い）。暗い環境で色を見る editor 向けで、kami-app-daw / -nle が使う。
- **DADS に無いもの**: app-shell / editor frame、segmented control、trailing slot 付き
  list、**app が選べる accent**。前 3 つは token 契約で書く app CSS（実例 kami-genko）、
  4 つ目は「無い」のが仕様 —— DADS はデジタル庁ブルーを配り、app は自分の色を選ばない。
- `dds-ext-*`（container / section / grid / stack / row / card）は上流に無い layout 補助。
  app CSS で layout を再導出せず、ここを上流拡張する。

### UI は single-page app で建てる（repo-wide mandatory、2026-08-08、ADR-2608080100）

**オーナー指示（2026-08-08）「daw, nle どちらも single page app となるようにして、
これは kotoba-lang の repo wide に single page app を前提にした デザインルールに」。**
kotoba-lang の web / local app UI は **1 文書・1 バンドル・1 mount** を既定とする。
画面の移動は state の変更であって location の変更ではない。

- **2 つ目の HTML を作りたくなったら、それは view であって document ではない。**
  実測（ADR-2608080100）: kami-app-nle / kami-app-daw は「エディタ」と「ユーザテスト
  集計」で 2 文書 2 バンドルを持ち、**React・cljs core・design system を 2 回
  コンパイルして 2 回配っていた**（3.6 MB → 1 文書にして 2.1 MB）。差は機能ではない。
  さらに app shell が 2 箇所にあったので、**片方だけが DADS 移行に追従して、もう
  片方は削除済みの `liquid-glass.css` を link したまま無スタイルで配信されていた。**
- **view は data として持ち、nav をそこから生成する。** dispatch に足して nav に
  足し忘れた view は「live に見える dead code」になる。表から生成すれば構造的に
  起きない。nav は `dds/button` に `:href`（= 実際のリンクでありながら DADS の
  control）。**この規則のために app CSS を足さない。**
- **addressability は fragment（hash）で与える。pushState を既定にしない** ——
  静的ホスト（Pages / cloud-itonami sites plane）では `/user-test` は **reload
  されるまで動く URL** で、reload した瞬間に 404 になる。server rewrite を持つ
  経路（Worker の `not_found_handling: single-page-application`）では pushState を
  選んでよいが、**その rewrite が実在することを確かめてから**。
- **静的ホストには `404.html` を置く。ただし未知のパス全部を `./` へ rewrite
  しない** —— `/x/y` は `/x/` へ飛び、そこも無いので **fallback が自分自身へ
  無限にリダイレクトする**。移動した実アドレスだけを対応 view へ送る。
  redirect 先は相対 `./`（同じ artifact が任意の mount point で正しくなる）。
- **「document を読み込んでいない」ことは機械で確かめる。ソースからは観測
  できない** —— nav が router link でも素の href でもコードは同じに読める。
  `window` に値を置き、view をまたぎ、まだそこにあることを確認する。
  待つ対象は**その view にしか無い要素**にする（両 view にある `main h1` を待つと
  crossing の描画前に返る。実測で踏んだ）。app 固有 state が crossing を越える
  ことも確かめる（これが無いと single page にした利益が無い）。
- **「見られる」ことは規則の半分である**（2026-08-26、オーナー指示「uiux は
  singlepage app として見れるようにしてね」）。UI は**コンパイルが通った時点では
  終わっていない** —— 人が開ける address が 1 つあって、そこに見えて、初めて
  終わりである。bundle を作って document を 1 枚も出さない app は開くものが無く、
  2 枚出す app は 1 page であることをやめている。**同じ失敗の裏表**で、どちらも
  ソースからは見えない（nav が router link でも素の href でもコードは同じに読める）。
- **これは prose だけの規則ではなくなった。** superproject root で:

  ```bash
  nbb scripts/verify-single-page-app.cljs --root . --findings   # 0=clean 1=findings 2=REFUSED
  ```

  `multi-document`（script を読む document が 2 枚以上）と `no-document`
  （shadow-cljs `:target :browser` なのに document が 0 枚）を報告する。
  **`404.html` は違反ではない** —— 静的ホストでは規則が要求するものなので、
  報告すれば規則を守った側を罰することになる。registry は
  `manifest/orgs-detectors.edn` の `:verify-single-page-app` で、SSR/OG の
  marketing surface は `:accepted`（日付・理由・解除条件つき）で持つ。
  **既知の盲点**: `no-document` は `:target :browser` を要求するので、
  **`:esm` の app が最後の document を失っても捕まえない**（`:esm` は library
  全部の target でもあり、絞らずに測ると 285 中 232 が出て、その大半は設計どおり
  正しい）。`multi-document` は `:esm` も見る。
- **例外は SSR/OG が必要な公開ページ**（ADR-2606290000）。app と marketing
  surface を同じ規則で縛らない。分けるなら理由を書く。
- **もう 1 つの例外は「生きた credential の隣にある local 面」**（ADR-2608231200、
  2026-08-23）。`kagi ui` は **bundle を 1 本も出さず**、server-rendered な 1 文書 +
  `default-src 'none'` で建っている —— vault を開いた session の隣のページに対して
  「この script に何ができるか」への一番安い正しい答えは *script が無いこと*だから。
  失うのは mount だけ（1 操作 = 1 描き直し。loopback で数ミリ秒）で、この規則が守ろうと
  している不変条件——1 文書・1 shell・1 stylesheet・views をデータから生成——は全部残る。
  **これを「SPA 化し忘れ」として直さない。** 公開 app には従来どおり SPA 規則が効く。
  ⚠ 同 ADR の実測: **`Referrer-Policy: no-referrer` を付けたページは、自分自身への
  same-origin form POST に `Origin: null` を送る。** Origin を検査する POST 面を持つ
  ページでこれを付けると全 action が拒否され、しかも HTTP client は test が渡した
  Origin を送るので**テストは緑のまま**。`same-origin` にする。
- **router はまだ共有ライブラリに無い。** 2 app が同型の `route.cljc`（約 60 行、
  view 表 + `fragment->view` + `nav` を pure に持ち、listener だけ `#?(:cljs)`）を
  各自持っている。**抽出の trigger は 3 つ目の app** —— routing は markup でも CSS
  でもないので `jp-go-dds` には置かない。3 つ目を書くときは先にここを見ること。

**legacy（kotoba-ui / liquid-glass）** は未移行の約 12 repo（`kotoba-lang/app-*`、
`cloud-itonami/kaisya`・`lawfirm`、`gftdcojp/apex`）でのみ引き続き正。**新規 UI を
これで始めない。** 旧スタックの規約（`kotoba-ui.core` 単一 require、raw hex 禁止、
`@layer kotoba.hig, kotoba.glass` の外で app CSS が勝つ、layout は `kotoba-ui.shell`
から）は該当 repo ではそのまま有効。詳細は ADR-2607122200 と
`orgs/kotoba-lang/kotoba-ui/docs/agent-guide.md`。

### Svelte / React で UI を著述しない。既定は cljs + reagent + re-frame + jp-go-dds（repo-wide mandatory、2026-08-26、ADR-2608260900）

**オーナー指示（2026-08-26）「svelte, react は全て cljs, reframe などに refactor」
「jp-go-dds をデフォルトの デザインシステムに」。**

- **新しい `.svelte` / `.tsx` / `.jsx` を書かない。** UI は `.cljc` / `.cljs` で書き、
  状態は **reagent + re-frame**（`shitsuke.re-frame.core` / `shitsuke.reagent.core` の
  host seam が既に在る。新しく作らない）、見た目は **`jp-go-dds`** に載せる。
  既存の 1,379 ファイル（実測 2026-08-26）は移行対象で、順序と期限は未決定。
- ⚠ **これは `react` / `react-dom` を package.json から剥がす指示ではない。**
  reagent / re-frame は React を描画バックエンドに使うので、shadow-cljs の app が
  `react` に依存しているのは**正常**であり移行後も残る。退役するのは
  **著述面（ソースファイルの拡張子）**であって依存ではない。実測 2026-08-26:
  `react` 依存 48 package のうち `manimani-experience-ui` と `kami-genko` は
  `.tsx`/`.jsx` を 1 本も持たず、**既に適合済み**。依存だけを見て「React repo」と
  数えない。
- **数える時は `node_modules` と `.claude/worktrees/` の両方を除外する。** 除外前は
  React が 754 件に見えたが、うち 386 件は使い捨て worktree 2 本に同じ 193 件が
  複製されていたもの。除外を間違えた計測は、移行が進んだように見せる。
- **設計言語は既に一致している。** `svelte-design-system`（55 component）は
  `@digital-go-jp/design-tokens` に依存しており、DADS の token で描かれた Svelte 実装。
  移行で変わるのは実装言語であって design language ではない。ただし
  **`jp-go-dds` は 20 component**（実測 2026-08-26）で BottomSheet / Carousel /
  DatePicker / Dialog / Drawer / Toast / Fab 等は対応が無い —— **「DADS で足りる」と
  丸めない**。足りない分は jp-go-dds への上流拡張か `shitsuke.components` で組む。
- **`/design-sync`（claude.ai/design 同期）はこの workspace で実行しない。** あの skill は
  *React design systems* 専用で（`non-storybook/SKILL.md` の Scope 節）、ここには React の
  design system が存在せず、**今後も作らないと決めた**。Svelte DS を custom element 経由で
  bridge しても、design agent が吐く React はこの workspace が出荷する cljc に写らない。
  **退役させると決めたスタックを、bridge を書いて固定化しない。**

## UI/UX 品質の数値化 — design-quality-score（2026-07-13、ADR-2607132300）

**`uikit`/`appkit`/`kotoba-ui`/`liquid-glass-ui` の UI/UX 品質を数値で把握・比較したい
ときは、新しい仕組みをゼロから作る前に `90-docs/design-quality/` の既存 EDN を必ず
確認する。** 正本は `90-docs/design-quality/design-quality.datoms.edn`（schema +
`:lib/*`/`:axis/*`/`:sample/*` catalog、DataScript/Datomic にそのまま transact/query
可能）+ `90-docs/design-quality/design-quality-ledger.edn`（append-only スコアイベント、
BMC の `canvas-ledger.edn` と同型、1行1 EDN map、手編集禁止・追記のみ）。両ファイルは
既定の sparse-checkout から除外されている可能性がある —
`git sparse-checkout add 90-docs/design-quality` で明示的に取得してから触る。

- **3層スコアリング**: (1) `:lint` 層（0–1、grepベース決定論的 — token-compliance /
  dark-mode-coverage / single-entry-discipline）(2) `:llm-judge` 層（1–5、Apple HIG
  由来 clarity/deference/depth + consistency + token-discipline、**3体の独立judgeの
  平均+標準偏差**を記録 — 単一judgeは合意の弱い箇所を隠す、実際 liquid-glass-ui の
  deference 軸で judge 間 stdev 0.50 の disagreement が実測された）(3)
  `:sample-visual` 層（1–5、`90-docs/design-quality/samples/` の実レンダリング済み
  サンプルページをスクリーンショットして視認採点。初回は3-judge panelでなく
  オーケストレータ単発1パス・hero/nav部分のみ視認という限界あり、ledger note に
  明記済み — 数値を見るときはこの層の note を必ず読み、library score 層と同等の
  厳密さがあるかのように扱わない）。
- **再実行**: `Workflow({name: 'design-quality-score'})`（`.claude/workflows/
  design-quality-score.js` に保存済み、lib score 層のみ再実行し ledger に追記する。
  sample-visual 層は現状ワークフロー化されておらず手動パス — 3-judge visual panel
  化は follow-up、ADR-2607132300 Alternatives 参照）。
- **サンプルページの再生成**: `nbb --classpath "orgs/kotoba-lang/shitsuke/src:
  orgs/kotoba-lang/css/src:orgs/kotoba-lang/liquid-glass-ui/src:orgs/kotoba-lang/
  kotoba-ui/src:orgs/kotoba-lang/uikit/src:orgs/kotoba-lang/appkit/src"
  90-docs/design-quality/samples/generate-samples.cljs`（`kototama/web/generate.cljs`
  と同型の nbb multi-dir `--classpath` パターン。ライブラリの `.cljc` を編集も破壊も
  しない、読み取り専用の消費者として使う）。
- **この macOS 環境でブラウザを操作するときの既知ハザード**: 多数の並行 Claude Code
  セッションが同一マシン上でフォーカスを奪い合う（`computer-use` skill既知）。
  Chrome は既定で「Apple Events からの JavaScript の実行」が無効なので
  `execute javascript` 経由のスクロールは失敗する — キー入力に頼らず
  `set URL of active tab of front window` / `target_app` screenshot の
  app-scripting 経路のみで完結させる。
- **repo-wide resource governor（mandatory）**: `orgs/` / `projects/` を含むworkspace全体で
  高負荷buildは同時1本に制限する。`shadow-cljs release` / `vite build` / `next build` /
  `cargo build` / `wash build` 等を直接起動せず、必ず
  `node /Users/junkawasaki/github/com-junkawasaki/scripts/resource-guard.mjs run build -- <command>`
  を使う。deployはscope `deploy`を使う。lockはPID・cwd・開始時刻を保持し、live ownerが
  いる二本目をexit 2で拒否し、dead ownerのstale lockだけを回収する。browser probeは
  `finally`でcloseし、残留掃除はrootの`npm run browser:cleanup`（60分超の
  `agent-browser-chrome-*`限定）を使う。superproject rootで無制限な`find .` / `du`を
  実行しない。

  ⚠ **`browser:cleanup` が回収するのは disk であって CPU ではない。** 実装
  （`resource-guard.mjs` の `cleanupBrowser`）は `os.tmpdir()` 直下の
  `agent-browser-*` **ディレクトリを `fs.rmSync` するだけ**で、**プロセスは 1 つも
  殺さない**。上の「`finally` で close し、残留掃除は cleanup を使う」という並びは
  これを process reaper のように読ませるが、そうではない —— **close し損ねた
  browser は、cleanup を何度回しても回り続ける。**

  実測 2026-08-13: Chrome for Testing の GPU helper が **2 日 15 時間、それぞれ
  CPU 105%** で回っており（load average 109 の主因）、一方 `os.tmpdir()` 配下の
  `agent-browser-*` は **0 件**だった —— このマシンの probe browser は
  `~/.agent-browser/browsers/` に profile を持つので、cleanup は**何も見つけずに
  成功する**。「cleanup を回したから残留は無い」と読めるが、実際には測っていない。

  **CPU を食っている probe を止める必要があるときは、`ps` で実測してから扱う。**
  親が生きている browser は別セッションが使っている可能性があるので、勝手に
  kill せずオーナーに報告する（孤児かどうかは `ps -o ppid=` で親を辿れば分かる）。
- **Co-Scientist kaizen loop（2026-07-13追記）**: `:llm-judge` 層（主観採点、単一judge
  やLLM panelは「計測されないメトリクス＝劇場」になりうる — 実測: liquid-glass-ui等の
  4ライブラリを3-judge panelが clarity/deference/depth等で軒並み4.0–5.0/5と採点した裏で、
  tap-target min-height欠如・dvhフォールバック欠如・safe-area片側未対応・theme-color
  meta欠如という4つの具体的ギャップを3体とも一つも指摘していなかった）を補う
  **決定論的 fitness function** が `90-docs/design-quality/audit.cljc`（LLM/browser不要、
  regexベース、`orgs/gftdcojp/network-isekai` の `isekai.ux.audit`／ADR-0007 からの移植）
  として存在する。Co-Scientist loop 本体（Generate→Reflect→Rank(Elo)→Evolve→Meta）は
  `90-docs/design-quality/coscientist.cljc`（同 `isekai.ux.coscientist` 移植、
  langchain-clj依存なしのoffline/heuristic版）で、`nbb` から `kaizen-cycle` を呼ぶと
  `90-docs/design-quality/coscientist/iteration-NN.edn` を生成する。この co-scientist
  パターン自体の原典は `90-docs/adr/2606141500-keiei-arbor-coscientist-engine.edn`。
  **UI/UXに限らず「品質を測って改善ループを回したい」タスクでは、まず
  `orgs/gftdcojp/network-isekai` の `90-docs/coscientist/` と `ADR-0007` 系（同type の
  ADRが `ai-gftd-shinshi`/`ai-gftd-yukkuri`/`ai-gftd-apps-gftdcojp` 等にも複数存在、
  `grep -rl coscientist 90-docs/adr` で一覧できる）を確認し、ゼロから設計しない。**

