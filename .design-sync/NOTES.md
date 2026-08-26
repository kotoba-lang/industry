# design-sync — この workspace では実行しない

**結論（2026-08-26）: `/design-sync` の同期対象はこの workspace に存在せず、今後も作らない。**
正本は `90-docs/adr/2608260900-svelte-react-refactor-to-cljs-reframe.edn` と CLAUDE.md の
「Svelte / React で UI を著述しない」節。

Claude Design が消費するのは **React の component library** である
（skill の `non-storybook/SKILL.md` Scope 節: *Scope: React design systems. Both
`_ds_bundle.js` and the previews render via React - a non-React DS has nothing for the
claude.ai/design agent to build with.*）。

## 実測した現在地（2026-08-26 の全走査。再走査の前にこれを読むこと）

| 候補 | 実体 | 使えるか |
|---|---|---|
| `kotoba-lang/jp-go-digital-design-system` (DADS) | 既定の design system。`.cljc` 20 component、`package.json` 無し、HTML 文字列 + CSS を出す。deps.edn 参照 640 件 | 不可 — React ではない |
| `kotoba-lang/svelte-design-system` (`@etzhayyim/design-system`) | 唯一の packaged な多 component DS。**Svelte 5** 55 component、`@digital-go-jp/design-tokens` 依存。`.storybook/` と `.stories.*` は **0 件**（devDeps に Storybook 9 addon は在る） | 不可 — Svelte |
| `kotoba-ui` / `liquid-glass-ui` / `uikit` / `appkit` / `shitsuke` / `css` / `html` / `byoubu-ui` / `mokuroku-ui` / `kotoba-component` | legacy・base の `.cljc` | 不可 |
| `react` 依存 48 package | **全て `private: true` の application**。library entry（exports / main / module）を持つものは 0 件 | 不可 |
| `.tsx` / `.jsx` 368 件（worktree 除外後） | Next.js の app 内 component（`app/page.tsx`・`JobWizard.tsx` 等） | 不可 — app であって library ではない |

west 全 4,237 project を**名前でも**検索済み。唯一の近似 `com-radixx` は Radix UI ではなく
航空座席予約システム。

## 二度踏まないための注意

- **`react` 依存の有無で判断しない。** shadow-cljs の app は reagent/re-frame が React を
  描画バックエンドに使うので `react` に依存する。`manimani-experience-ui` と `kami-genko` は
  `.tsx`/`.jsx` を 1 本も持たない。
- **`.claude/worktrees/` を除外する。** 除外前は React が 754 件に見えたが、386 件は
  使い捨て worktree 2 本への同一 193 件の複製だった。
- **`find orgs -maxdepth 3 -name package.json` では足りない。** `etzhayyim/root` のような
  入れ子 superproject の sub-package はもっと深い。最初にこれで「React は無い」と誤結論しかけた。
- **Svelte → React の bridge を書かない。** 技術的には Svelte 5 の custom element 化 +
  React wrapper で可能だが、(1) skill が禁じる reimplementation に近づき (2) design agent が
  吐く React はこの workspace が出荷する cljc/hiccup に写らないので skill の存在意義
  （*designs map 1:1 onto code their engineers can ship*）が壊れ (3) **退役させると決めた
  スタックを固定化する**。

## それでも走らせる条件

React の design system を**新たに正式採用した**場合だけ。その時は上記 ADR を
`superseded` にしてから。
