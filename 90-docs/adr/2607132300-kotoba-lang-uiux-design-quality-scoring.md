---
id: adr-2607132300-kotoba-lang-uiux-design-quality-scoring
title: "ADR-2607132300: kotoba-lang design-system (uikit/appkit/kotoba-ui/liquid-glass-ui) UI/UX 品質を EDN で数値化する design-quality-score システムを導入する"
status: accepted
date: 2026-07-13
deciders:
  - Jun Kawasaki（「uiux を...数値的に出したいのですが」→「workflow としてやってみて」
    →「次、update adr, rule. 実際に sample uiux を作って score 順に並べてみて」→
    「これをさらに cosientist のアプローチで kaizen」→「今の kotoba-lang/kotoba-studio,
    kotoba-creative-studio も同様に score 評価、分析」→「next, kaizen」→「next」→
    「closing」）
related:
  - adr-2607022800-kotoba-lang-default-uiux-appkit-uikit-interface-fundamentals
  - orgs/gftdcojp/network-isekai/90-docs/adr/0007-ux-coscientist-kaizen.md（移植元）
  - 90-docs/adr/2606141500-keiei-arbor-coscientist-engine.md（co-scientist パターンの原典）
  - orgs/kotoba-lang/uikit
  - orgs/kotoba-lang/appkit
  - orgs/kotoba-lang/kotoba-ui
  - orgs/kotoba-lang/liquid-glass-ui
  - orgs/kotoba-lang/shitsuke
  - orgs/kotoba-lang/kami-studio
  - orgs/kotoba-lang/kami-creative-studio
supersedes: []
superseded_by: []
last_verified: 2026-07-13
doc_type: adr
topic: kotoba-lang-ui-design-system
authoritative: true
authoritative_for:
  - "90-docs/design-quality/design-quality.datoms.edn を design-quality スコアリングの
    schema/catalog 正本とする位置づけ（DataScript/Datomic transactable EDN）"
  - "90-docs/design-quality/design-quality-ledger.edn を append-only スコアイベント台帳
    の正本とする位置づけ（手編集禁止、追記のみ）"
  - ".claude/workflows/design-quality-score.js を再実行可能な正本ワークフローとする位置づけ"
  - "90-docs/design-quality/audit.cljc を deterministic fitness function の正本とする
    位置づけ（LLM/browser不要、regex ベース、isekai.ux.audit 移植）"
  - "90-docs/design-quality/coscientist.cljc + 90-docs/design-quality/coscientist/
    iteration-NN.md を Co-Scientist kaizen loop（Generate→Reflect→Rank→Evolve→Meta）の
    正本とする位置づけ"
  - "新規に design-quality/UI品質数値化の仕組みを作る前に、この ADR と 90-docs/design-quality/
    の既存ledgerを必ず確認する、という再発防止規則（BMC/Lean Loop と同型の教訓）"
---

# ADR-2607132300: kotoba-lang design-system UI/UX 品質を EDN で数値化する design-quality-score システムを導入する

**Status**: accepted — closed（2026-07-13、4 addenda 完了、詳細は末尾 §Closing summary）。
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki

## 2026-07-13 Addendum — Co-Scientist kaizen pass

「これをさらに cosientist のアプローチで kaizen」という指示を受け、この repo に既存の
Co-Scientist パターン（`90-docs/adr/2606141500-keiei-arbor-coscientist-engine.md` が原典、
実装インスタンスは `orgs/gftdcojp/network-isekai` の `ADR-0007 — UI/UX quality as a
measured, self-improving loop`）を design-quality システムへ移植・適用した。isekai 側の
founding rule（「計測されないメトリクスは劇場（theater）」）に倣い、既存の `:llm-judge`
層（3-judge panel、主観採点）を補完する**決定論的 fitness function**を追加した:

- **`90-docs/design-quality/audit.cljc`**（`isekai.ux.audit` 移植、LLM/browser 不要、
  regex ベース、pure `.cljc`、bb で実行可能）— viewport / safe-area / dynamic-viewport /
  tap-targets / focus-visible / reduced-motion / overflow-guard / color-scheme /
  responsive / semantics の9軸を、実際にレンダリング済みの4サンプルページの HTML+CSS
  ソースに対して直接スコアする。
- **`90-docs/design-quality/coscientist.cljc`**（`isekai.ux.coscientist` 移植、
  langchain-clj 依存なしの簡略版 — offline/heuristic Generate のみ、LLM Generate path は
  follow-up）— Generate（findingごとに1仮説）→ Reflect（risk）→ **Rank**（Elo
  round-robin、K=32、predicted-gain が judge）→ Evolve（consumer-fixable かつ低risk な
  仮説を1 batch に統合）→ Meta（iteration-NN.md 生成）。

**実測結果（Iteration 01、`90-docs/design-quality/coscientist/iteration-01.md`）**:
baseline **71.55/100**。3-judge LLM panel は同じ4ライブラリの clarity/deference/depth/
consistency を軒並み4.0–5.0/5と採点していたが、**3体のjudgeとも下記の具体的ギャップを
一つも指摘していなかった**（まさに「計測されないメトリクスは劇場」の実例）:

- `tap-targets`（w=0.13）: `.liquid-glass__button`/`.liquid-glass__icon-button` に明示的
  `min-height:44px` が無い（padding依存のみ）
- `dynamic-viewport`（w=0.09）: `.kotoba-shell__app` が `min-height:100vh` のみで
  `dvh` フォールバックが無い
- `safe-area`（w=0.13）: `env(safe-area-inset-bottom)` のみ対応、top edge（nav-bar）未対応
- `color-scheme`（w=0.06）: `prefers-color-scheme` dark override はあるが
  `<meta name=theme-color>` が無い
- `viewport`（w=0.10）: `viewport-fit=cover` 未指定
- `responsive`（liquid-glass-ui showcase のみ、w=0.07）: max-width media query 無し

Elo ランキング（測定済み headroom が judge、LLM debate 不使用）で consumer-fixable かつ
低riskな4仮説（tap-targets/dynamic-viewport/safe-area/color-scheme）を batch
`design-quality-kaizen-1` として即座に出荷（unlayered app CSS、`90-docs/design-quality/
samples/generate-samples.cljs` の `:head` opt 経由 — 共有 `orgs/kotoba-lang/*` checkout
は一切変更していない）。再監査で実測: **71.55 → 90.38/100（+18.83、3サンプル各
71.14→96.25）**。変更していない liquid-glass-ui showcase page が 72.77 のまま横ばいな
ことが、この delta が測定ノイズでなく実際のCSS変更由来であることの control。

`viewport`（`kotoba-ui.shell/page` が viewport meta タグを1つだけ hardcode しており
consumer側から `:head` opt で追加できない）と `responsive`（liquid-glass-ui showcase 自体
の編集が必要）は、共有 checkout への編集を要するため Iteration 01 では適用せず follow-up
として記録していた。

**Iteration 02（`90-docs/design-quality/coscientist/iteration-02.md`）で実際に着地させた**:
`orgs/kotoba-lang/kotoba-ui` と `orgs/kotoba-lang/liquid-glass-ui` それぞれに superproject
外の isolated `git worktree` を切り、既存テストスイート（kotoba-ui 20 tests/130
assertions、liquid-glass-ui 59 tests/632 assertions、いずれも green）で確認した上で
push・`gh api .../merges` でサーバ側マージ、`nbb scripts/gen-west-manifest.cljs --entry
<name>` で pin 前進（`--check` wholesale regen はしない、per-entry 最小 diff のみ）。

このラウンドで **coscientist loop 自体の実バグを出荷前に検知**した: 自動生成された
roadmap（`heuristic-hypothesis`）は axis 単位で汎用的な fix 文言を持つのみで、対象ページの
実際の markup を検証していなかった。liquid-glass-ui showcase page（`docs/index.html`、
`kotoba-ui.shell` を一切経由しない）に対し `.kotoba-shell__app{min-height:100dvh}` という
存在しないセレクタを提案していた（`grep -c kotoba-shell__app docs/index.html` = 0）—
そのまま出荷していれば「効果ゼロの死んだCSSを足しただけ」になっていた。実際の markup を
確認してから正しいセレクタ（`body`、実在する `.liquid-glass__nav-bar`）で修正し出荷。

最終実測（3ラウンド合計）: **71.55 → 90.38（iter 01）→ 95.16 → 100.00/100（iter 02、
0 findings、4ページ全て収束）**。`coscientist.cljc` の `iteration-md` に isekai 側と同型の
「収束（converged）」分岐を追加済み — 次回このrubricのまま再実行しても空テーブルにならず
「0 findings」を正直に報告する。Iteration 03 の種は: (1) この
`heuristic-hypothesis`の「対象ページの実markupを検証しない」欠陥そのものの修正
（axis単位でなくpage内容を見て仮説生成する）、(2) WCAG 1.4.3 contrast-ratio軸の追加、
(3) 実 screenshot-critic lens の Generate stage への配線、(4) `.liquid-glass__nav-bar`
safe-area-top padding を demo page限定でなく `liquid-glass.style` 本体に昇格するかの検討。

## 2026-07-13 Addendum 2 — kami-studio / kami-creative-studio を同様に評価（非対象ライブラリへの拡張）

「今の kotoba-lang/kotoba-studio, kotoba-creative-studio も同様に score 評価、分析」という
指示を受けた。**実在するのは `kotoba-lang/kami-studio` / `kotoba-lang/kami-creative-studio`**
（`gh repo list kotoba-lang` で確認、"kotoba-studio"/"kotoba-creative-studio" という名前の
repo は存在しない）— この2つとして進めた。両者とも `orgs/kotoba-lang/` 配下に既に手動 clone
されていたが（`git remote` は正しく `kotoba-lang/kami-studio` 等を指す）、**manifest には
未登録**（`west.yml`/`repos.edn` いずれにも entry 無し、`git status` 上 untracked）。

重要な発見: **この2つは `kotoba-ui`/`liquid-glass-ui`/`uikit`/`appkit` を一切使っていない**
（`kami-studio` の deps.edn は `kotoba-lang/html`+`kotoba-lang/css` のみ、`kami-creative-studio`
は reagent+shadow-cljs+`kotoba-lang/kisekae`+Google `<model-viewer>`）。したがって既存の
`:lint` 層（token-compliance 等、kotoba-ui のトークン体系に特化）はそのままでは適用不能。
代わりに以下の2層を適用した:

1. **`design-quality.audit`（決定論的、9軸、layer `:audit` として新規に catalog 化）** —
   実際にビルド済みの `public/index.html` に対して実測: `kami-studio` **50.75/100**
   （viewport-fit=cover欠如、safe-area皆無、focus-visible皆無、theme-color/color-scheme
   皆無、responsive breakpoint皆無、`<html lang>`欠如）、`kami-creative-studio`
   **67.72/100**（同様のsafe-area/viewport-fit/responsive欠如、ただしfocus-visibleと
   semanticsはOK）。
2. **`:llm-judge` 層（3-judge panel、rubric を調整版で適用）** — kotoba-ui sibling family
   との比較（`consistency`軸）が成立しないため、rubric から sibling-convention 参照を外し
   「自己一貫性（internal consistency）」に再定義して適用（judge prompt は再利用せず
   一回限りの adapted 版、`.claude/workflows/` には保存していない）。結果:
   `kami-studio`（n=3全軸）clarity 4.07 / deference 4.33 / depth 3.23 / consistency 4.57 /
   token-discipline **3.00**。`kami-creative-studio`（token-discipline軸のみ1judge失敗で
   n=2、他4軸もn=2）clarity 4.00 / deference 4.00 / depth 3.50 / consistency 4.00 /
   token-discipline **3.00**。

**両アプリで一致した一番の signal**: `token_discipline` が両方とも **3.00/5**（既存4ライブラリ
の 4.5〜4.93 と比べ顕著に低い）— 3体の judge 全員が「CSS custom properties/token 層が無く、
raw hex literal を都度書いている（ただし palette 自体は小さく一貫して再利用されてはいる）」
と指摘。決定論的 audit の `color-scheme` 軸（両方とも theme-color/color-scheme 欠如）も同じ
方向を指す。**両方の signal が独立に同じ結論（トークン層の不在）を指しており、
「kotoba-ui.theme/shitsuke.tokens の採用が両アプリの改善で一番レバレッジが高い」という
具体的な推奨につながる**。

`design-quality.datoms.edn` に `:lib/kami-studio` / `:lib/kami-creative-studio`
catalog entry と `:axis/*`（`:audit` layer、9軸）を追加、`design-quality-ledger.edn` に
30行（audit 18 + llm-judge-mean 10、`design-quality-studios-20260713-0709` run）を追記。
個々の judge 生スコアはこの回の workflow 実装が mean/stdev のみを返す設計だったため
（元の4ライブラリ用 workflow と異なり raw per-judge score を保持していない）、ledger には
mean 行のみ記録 — 次回はこの点を揃えるべき。manifest への正式登録（west.yml/repos.edn）は
今回のスコープ外（「score 評価、分析」の指示範囲内に留めた、登録は別判断）。

## 2026-07-13 Addendum 3 — kami-studio / kami-creative-studio を実際に kaizen（第2の監査手法バグを出荷前に発見）

「next, kaizen」という指示を受け、Addendum 2 で計測した findings を実際に両repoへ着地
させた（`90-docs/design-quality/coscientist/iteration-03.md`）。

着手前に、**もう一つの実監査手法バグ**を発見した: `kami-creative-studio` の実CSSは
`public/index.html` に inline ではなく別ファイル `public/css/ui.css` に `<link>` 経由で
存在する。`design-quality.audit` の `score-page` は1文字列しか受け付けないため、
`index.html` だけを監査した Addendum 2 のスコアは実際のスタイルを一切見ていなかった。
`index.html`+`ui.css` を連結して再監査すると **66.59/100**（67.72ではなく）— 集計値の
差は小さいが findings の構成は大きく変わった: `overflow-guard`/`responsive` は偽陰性
（実CSSに `overflow:hidden` と2つの `@media` breakpoint が既に存在）、逆に
`reduced-motion` は真の未検出finding（`transition:width .25s` が実在、reduced-motion
guardが皆無）として新たに浮上した。

さらに、この app は**クライアントサイドレンダリング（CSR）**であるため、
`tap-targets`/`focus-visible` 軸の判定式（`<button`等のリテラルHTMLタグ文字列を探す）が
**構造的に誤検出する**: 実際のbutton/input要素はreagent hiccup経由でJS実行時にのみ生成され、
静的ファイルには一切現れない。判定式は「該当タグが見つからない→該当なし→満点」という
分岐に落ちるが、これは**偽陽性**（本当は該当要素が大量に存在するのに、監査ツールが
静的解析できないだけ）。実際にソースを直接 grep して手動検証した結果: `tap-targets` は
たまたま実態として問題無し（`$button`に既に`min-height:44px`あり）だったが、
`focus-visible` は**真のgap**だった（`$input`のみ`:focus`（`:focus-visible`ではない）を
持ち、`$button`/`$trait-card`/`$file`は focus 表示が皆無）。**この2軸はCSRアプリに対して
信頼できないという既知の限界として文書化**（iteration-03.md参照、`design-quality.audit`
自体の今後の改修対象）。

**着地させた修正**（両方とも superproject 外の isolated `git worktree`、main同期後、
push前に検証、`gh api .../merges` でサーバ側マージ、worktree/branch cleanup 完了）:
`kami-studio`（`83edddf`→`c6143d6`、テストスイート無し・3行の純hiccupなので再render+
再監査で検証）— viewport-fit/safe-area全4辺/focus-visible/theme-color+color-scheme/
overflow-x guard/responsive breakpoint/lang属性の7findings全修正、**50.75→100.00/100**。
`kami-creative-studio`（`6fc4a1c`→`7c0439e`、既存テストスイート6 tests/23 assertions
green維持）— viewport-fit/color-scheme/top-bar+bottom-bar双方のsafe-area/手動検証した
真のfocus-visible gap/`prefers-reduced-motion`グローバルresetを修正。`npm install`+
`shadow-cljs release`(0 warnings)+pages buildのフルローカルビルドで実際にコンパイル済み
CSSへ反映されたことを確認した上で再監査、**66.59→100.00/100**。

正直に記録する限界: `kami-creative-studio`の`$top`（固定54px高さのヘッダー）に
`padding-top:env(safe-area-inset-top)`を足すと（`box-sizing:content-box`のデフォルトの
ため）ヘッダー自体の描画高さは外側に伸びる形になり内容は潰れないが、`$workspace`の
`calc(100vh - 54px)`はこの追加分を知らないため、notch付き端末では workspace grid の
最下部が数十px程度 `overflow:hidden` で見えなくなる可能性がある——「完璧な修正」として
偽装せず、iteration doc に follow-up として明記した。manifest登録は今回も対象外
（各repo自身へのpush/mergeは登録の有無と無関係に実施済み）。

## 2026-07-13 Addendum 4 — Addendum 3 で見つけた監査ツール自体のバグを実際に直す（`score-site`）

「next」という短い指示を受け、Addendum 2/3 で繰り返し seed に上がっていた項目のうち
最もレバレッジが高いもの——**`design-quality.audit` の single-string 前提が実ページの
複数ファイル分割（`kami-creative-studio` の外部 `<link rel=stylesheet>`）を静かに
見落とすバグ**——を、別のページを追加監査するのではなくツール自体の修正として着手した。

`90-docs/design-quality/audit.cljc` に **`score-site`** を追加: ページを1文字列でなく
parts map（`{:html ... :css <string-or-vector> :js ...}`）で受け取り、HTML内の
`<link rel=stylesheet href=...>`（引用符あり/なし両対応）を検出、対応する `:css` が
渡されていなければ結果に `:incomplete? true` + 具体的なhref名を含む `:warning` を付与する
——Addendum 3 で人間が偶然気付いたミスを、次回からツール自身が検知する形にした。
`score-page`（旧来のsingle-string API）はそのまま内部実装として温存、`audit` は
per-page で `score-site` を呼ぶよう更新（既存呼び出し元は文字列を渡す限り無変更で動く）。

**回帰確認**: これまでこのsystemが監査した4サンプル+kami-studioの「100/100収束」claim が
実は同じバグの影響を受けていないか、コードを触る前に `grep -l "rel=\"stylesheet\""` で
全ファイルを確認——**該当ゼロ**（全て自己完結HTML）、遡って疑わしいスコアは無かった。

**副産物として見つけた別件**（バグではない）: 全6ページの再収束チェックを走らせたところ
`kami-creative-studio` が一時的に「5 findings 逆戻り」した表示になり焦ったが、原因は
superproject 共有 checkout の `orgs/kotoba-lang/kami-creative-studio/public/` が
git-ignore対象（build出力）で、Addendum 3 の修正が実際に着地したのは worktree 内の
ビルド成果物のみ（worktree削除時に一緒に消えた）——共有checkout側の `public/` は
修正前の古いローカルビルドのまま残っていただけだった（`git log` で main への merge
`7c0439e` は確認済み、ソース自体は正しく修正済み）。共有checkoutで
`shadow-cljs release`+pages buildを再実行して解消——「gitignore対象のbuild成果物は
git fetchで自動更新されない」という、地味だが再発しうる罠として記録。

**最終状態**: このsystemがこれまで監査した全6ページ（4サンプル+kami-studio+
kami-creative-studio、後者は新しい`score-site`経由で正しく`:css`供給）を通しで
再監査 —— **100.00/100、0 findings、`:incomplete?`は全ページで正しくnil**。
`90-docs/design-quality/coscientist/iteration-04.md`参照。

## Context

`orgs/kotoba-lang/{uikit,appkit,kotoba-ui,liquid-glass-ui}`（ADR-2607022800 で
default UI/UX design と定めた design system 一式）の UI/UX 品質を、感覚ではなく EDN
ベースで数値的に把握したいという要望が出た。Apple 含む大手企業の実際のアプローチを
調べた結果、以下が判明した:

- Apple HIG 自体は公開された数値スコアを持たない。実態は社内 design review board の
  合議（定性）。
- 業界の「定性を数値化する」定番手法は3系統: (1) 決定論的ツール — Lighthouse
  (Performance/Accessibility/Best Practices/SEO 各 0–100)、axe-core（a11y 違反数）、
  WCAG contrast ratio（計算式で厳密判定可能）。(2) 多評価者合議 — Nielsen Norman
  Group の heuristic evaluation（10原則×複数評価者の severity 0–4 を集計）、SUS
  （10問アンケート→0–100点、ユーザーテスト後）。(3) デザインシステムチーム独自の
  lint 系メトリクス（token adoption率、raw value使用数）。
- LLM agent での数値化は「LLM-as-judge」パターン（vision/コードを rubric に沿って
  採点）が実用的だが、単一 judge は主観ブレが大きいため複数 judge（3体以上）の
  平均+分散を見る judge panel 構成が定石。決定論的に出せる軸（contrast比・token使用率・
  a11y違反数）は LLM を使わず機械的に出す方が安く安定する。

この repo は BMC/Lean Loop（ADR-2607021500 系）で「base datoms（schema+catalog）+
append-only ledger（events）」という EDN 運用パターンを既に確立しており、DataScript/
Datomic でそのまま query 可能な形（namespaced keyword属性、`:db/ident`宣言）を志向する
という要望（「edn にして datascript, datomic で query できると良き」）とも合致する
ため、同型のパターンを design-quality スコアリングにも採用する。

## Decision

1. **3層スコアリング方式**を採用する:
   - **lint 層**（決定論的、0–1 スケール）: `:axis/token-compliance`（consumer-file の
     token参照 vs raw hex/px literal違反）、`:axis/dark-mode-coverage`（dark-mode
     token override の有無）、`:axis/single-entry-discipline`（sibling/consumer
     コードが意図した entry namespace 以外の下位層に直接依存していないか）。grep
     ベースで agent が実測し、推測値は使わない。
   - **llm-judge 層**（主観的、1–5 スケール、Apple HIG 由来）: `:axis/clarity`
     （HIG Clarity）、`:axis/deference`（HIG Deference）、`:axis/depth`（HIG Depth）、
     `:axis/consistency`（sibling 間の命名/形状一貫性）、
     `:axis/token-discipline-judged`（token使用の体系性、lintの定量指標を補完する
     定性判定）。各ライブラリを **3体の独立 judge** が実ソース+docsのみを読んで
     採点し、平均(`llm-judge-mean`)と標準偏差(`:eval/stdev`)の両方を記録する
     （judge 間の不一致自体が signal — 実際 liquid-glass-ui の deference 軸は
     stdev 0.50 で judge が割れた。詳細は §Findings）。
   - **sample-visual 層**（今回追加、単発・部分カバレッジ、正直に限定を明記）:
     実サンプル UI ページをレンダリングし screenshot を1回オーケストレータ自身が
     視認して同じ5軸で採点。3-judge panel ではなく、かつ Chrome の AppleScript
     JavaScript 実行がデフォルト無効なためスクロールできず、hero/nav 部分のみの
     視認になった限界を ledger note に明記する（詳細は §Sample pages と §Limitations）。
2. **EDN は DataScript/Datomic transactable な形で保存する**: base ファイル
   `90-docs/design-quality/design-quality.datoms.edn` に `:db/ident`宣言付き schema
   （`:lib/*` / `:axis/*` / `:sample/*` / `:eval/*`）+ catalog entity（4 lib + 8 axis +
   3 sample）、sibling の `90-docs/design-quality/design-quality-ledger.edn` に
   append-only のスコアイベント（1行1 EDN map、`canvas-ledger.edn` と同型）。
3. **再実行可能な Workflow として保存する**: `.claude/workflows/design-quality-score.js`
   （lint→judge→synthesize の3フェーズ、pipeline+parallel、judge 3体並列）。
   `Workflow({name: 'design-quality-score'})` で再実行すればスコアの新しい run が
   ledger に追記される（既存行は不変、上書きしない）。
4. **サンプル UI ページを実際に生成してスコア比較する**: `90-docs/design-quality/
   samples/generate-samples.cljs`（nbb, multi-dir `--classpath`, `kototama/web/
   generate.cljs` と同型のパターン）が、同一内容の "Team Dashboard" 画面を
   uikit/appkit/kotoba-ui(bare) の3種のplatform binding defaultで描画し、
   liquid-glass-ui 自身の既存 `docs/index.html` showcase と並べて比較する。

## Findings（初回実行、2026-07-13）

### Library scores（lint 0–1 / judge 1–5, judge mean）

| lib | token-compliance | dark-mode | single-entry | clarity | deference | depth | consistency | token-discipline |
|---|---|---|---|---|---|---|---|---|
| uikit | 1.00 | 0.00 | 1.00 | 4.73 | 4.43 | 4.00 | 5.00 | 4.50 |
| appkit | 1.00 | 0.00 | 1.00 | 4.77 | 4.63 | 3.60 | 5.00 | 4.50 |
| kotoba-ui | 0.91 | 1.00 | 1.00 | 4.50 | 4.15 | 3.75 | 4.75 | 4.60 |
| liquid-glass-ui | 0.71 | 1.00 | 1.00 (該当なし) | 4.57 | 3.33 (stdev 0.50) | 4.97 | 4.03 | 4.93 |

注目点: liquid-glass-ui の `deference` は judge 間で 2.8〜4.0 と割れた（デフォルトの
glass装飾＝specular/blur/rim-lightが「content優先」か「chrome先行」か判断が分かれた）。
これは単一judgeスコアでは隠れていた signal であり、3-judge panel の価値を裏付ける。

### Sample-page ranking（visual single-pass, 5軸単純平均）

| rank | sample | visual mean | 使用 lib (panel/list) |
|---|---|---|---|
| 1 | appkit-desktop-sample | 4.28 | appkit（sidebar構成） |
| 2 | uikit-mobile-sample | 4.26 | uikit |
| 3 | kotoba-ui-bare-sample | 4.20 | kotoba-ui 素の default（platform binding なし） |
| 4 | liquid-glass-ui/docs/index.html（既存showcase） | 4.10 | liquid-glass-ui 単体、kotoba-ui theme非経由 |

liquid-glass-ui の既存 showcase が最下位なのは自然な結果: 意図的にマテリアルを
誇示する kitchen-sink デモであり `deference`（content優先）軸で 2.5 と低いのが
主因（`depth` は逆に 5.0 で最高）。「アプリ画面」としての適性と「マテリアルの
実演」としての適性は別軸であることが数値上も裏付けられた。

## Limitations（正直に明記する — no silent caps）

- **Lighthouse / axe-core / WCAG contrast の実数値計算は未実施**（今回は lint 層で
  代替の grep ベース指標を使用、liquid-glass-ui の `docs/index.html` 以外は今回まで
  レンダリング済み成果物が存在しなかったため）。
- **sample-visual 層は 3-judge panel ではなくオーケストレータ単発の1パス**。
  Chrome の「Apple Events からの JavaScript を許可」がデフォルト無効なため
  スクロールができず、hero/nav 部分のみを視認（cards/grid/list セクションは
  未視認）。またこの macOS 環境は多数の並行 Claude Code セッションが同一マシン上で
  フォーカスを奪い合うため（`computer-use` skill の既知ハザード）、キー入力
  ベースの操作は避け、AppleScript の app-scripting（`set URL of active tab` /
  `target_app` screenshot）のみに限定した。
- **kotoba-ui の judge3体中1体が StructuredOutput retry cap 超過で失敗**
  （n=2 で集計、ledger note に明記済み）。
- サンプルは3種類・1画面構成のみ（"Team Dashboard" 相当）。他の画面パターン
  （設定画面・フォーム・一覧詳細等）や light-mode表示は未検証。

## Consequences

正: UI/UX品質を「感覚」でなく再現可能な EDN facts として蓄積できる。DataScript/
Datomic でそのまま query 可能（例クエリは `design-quality.datoms.edn` ヘッダに
記載）。3-judge panel の分散が「意見の割れる箇所」を可視化する（liquid-glass-ui
deference の例）。Workflow として保存済みなので、次回以降は `Workflow({name:
'design-quality-score'})` で再実行するだけで新しい run が ledger に追記され、
経時変化を追える。

負: LLM judge score は再現性が完全ではない（同じprompt でも僅かにブレる）。
sample-visual 層は今回 single-pass・部分カバレッジであり、library score 層ほど
厳密ではない。Lighthouse/axe-core/実contrast計算は follow-up。

## Alternatives Considered

Lighthouse/axe-core だけで済ませる案は、4ライブラリとも今回まで実際にレンダリング
された成果物がほぼ存在せず（liquid-glass-ui の `docs/index.html` のみ例外）、
「ビルド済みページが無ければ計測できない」ため今回のスコープでは不十分と判断し、
lint+LLM-judge層で先に土台を作った。単一LLM judgeで済ませる案は、liquid-glass-ui
の`deference`軸で実際に judge間スコアが2.8〜4.0まで割れた実測結果が示す通り、
単一judgeでは合意の弱い箇所を隠してしまうため却下し、3-judge panel（平均+標準偏差
記録）を採用した。サンプルページを screenshot 込みで3-judge visual panel にする案は、
このmacOS環境が複数エージェントのフォーカス競合という既知ハザードを持つため
（`computer-use` skillに明記済み）、並行ブラウザ操作のリスクを避けオーケストレータ
単発視認に留めた——follow-upとして、フォーカス競合の心配がない隔離環境（headless
Chrome等）が使えるようになれば3-judge visual panelに拡張する。

## References

- 90-docs/adr/2607022800-kotoba-lang-default-uiux-appkit-uikit-interface-fundamentals.md
- 90-docs/design-quality/design-quality.datoms.edn
- 90-docs/design-quality/design-quality-ledger.edn
- 90-docs/design-quality/samples/generate-samples.cljs
- .claude/workflows/design-quality-score.js
- orgs/kotoba-lang/kototama/web/generate.cljs（nbb multi-dir --classpath の先例）
- orgs/kotoba-lang/liquid-glass-ui/src/liquid_glass/demo.clj（SSR hiccup→HTML の先例、JVM側）
- .claude/skills/computer-use/SKILL.md（並行セッションのフォーカス競合ハザード）

## Closing summary（2026-07-13、「closing」指示によりclose）

4本のaddendumを経て、当初の「感覚でなくEDNで数値化したい」という要望から、
実際に6ページ・6リポジトリを横断する再現可能な計測+改善ループへ発展した。close時点の
状態を棚卸しする:

**正本ファイル（このADRが authoritative）**:
- `90-docs/design-quality/design-quality.datoms.edn` — schema + `:lib/*`(6件)/
  `:axis/*`(18件: lint 3 + llm-judge 5 + audit 9 + judged-consistency 1)/`:sample/*`(4件)
  catalog
- `90-docs/design-quality/design-quality-ledger.edn` — 157行、append-only スコアイベント
- `90-docs/design-quality/audit.cljc` — 決定論的 fitness function（`score-page`/
  `score-site`/`audit`、9軸、LLM/browser不要）
- `90-docs/design-quality/coscientist.cljc` — Co-Scientist kaizen loop（Generate→
  Reflect→Rank→Evolve→Meta、`kaizen-cycle`）
- `90-docs/design-quality/coscientist/iteration-{01,02,03,04}.md` — 実測ログ
- `.claude/workflows/design-quality-score.js` — 再実行可能workflow（lib score層のみ、
  `Workflow({name: 'design-quality-score'})`）
- CLAUDE.md「UI/UX 品質の数値化」節 — 次回セッションへの再発防止規則

**実際に着地した成果（6リポジトリ、いずれもmainへserver-side mergeし、worktree/branch
cleanup済み）**:
- `kotoba-lang/kotoba-ui` `423d292`→`7935ffe`（viewport-fit=cover）
- `kotoba-lang/liquid-glass-ui` `b85af88`→`71f63ff`（responsive+viewport-fit+
  theme-color+dvh+safe-area、2ラウンド）
- `kotoba-lang/kami-studio` `83edddf`→`c6143d6`（HIG/WCAG 7 findings 全修正）
- `kotoba-lang/kami-creative-studio` `6fc4a1c`→`7c0439e`（同5 findings、既存テスト
  green維持）
- `com-junkawasaki/root` の `manifest/west.yml` pin 2件前進（kotoba-ui/liquid-glass-ui、
  `--entry` 最小diff、検証済み）

**最終スコア**: このsystemがこれまで監査した全6ページ（4サンプル+kami-studio+
kami-creative-studio）— **100.00/100、0 open findings**（`design-quality.audit`
9軸、`:incomplete?` 全ページ正しくnil）。3-judge `:llm-judge` panel も4ライブラリ
+2アプリで実施済み（`clarity`/`deference`/`depth`/`consistency`/`token-discipline`）。

**見つけた実バグ3件**（すべて出荷前に自己検知、詳細は各addendum）:
1. `heuristic-hypothesis` が対象ページの実markupを検証せず、存在しないセレクタを
   提案しかけた（iteration 02、**未修正のまま残っている**——次回セッションへの
   最有力候補）。
2. `kami-creative-studio` の外部CSSファイル（`<link>`経由）を監査ツールが見落とし、
   スコアを暗黙に過小評価していた（iteration 03発見、iteration 04で `score-site` に
   よりツール自体を修正——**この class のバグは修正済み**）。
3. CSR（client-side-rendered）アプリに対する `tap-targets`/`focus-visible` 軸の
   構造的誤検出（実要素はJS実行時にのみ生成されるため静的解析できない）——
   **未修正、標準的な限界として文書化のみ**（iteration 03/04 seed参照）。

**未着手のまま残っている項目（次回セッションへの引き継ぎ、優先順）**:
1. `heuristic-hypothesis` の selector検証欠如（実際に死んだCSSを出荷しかけた実害あり）
2. WCAG 1.4.3 contrast-ratio 軸の追加（iteration 01から3回連続で seed に残存）
3. CSR blind spot の正式な修正（hiccup-aware pattern matching、または
   `:audit/confidence :low` フラグ）
4. `:sample-visual` 層の3-judge visual panel化（現状オーケストレータ単発視認のみ）
5. `manifest/west.yml`/`repos.edn` への `kami-studio`/`kami-creative-studio` 正式登録
   （今回は score/kaizen の範囲内に意図的に留めた、登録は別判断として保留）
