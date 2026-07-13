---
id: adr-2607122200-kotoba-lang-ui-hig-semantic-layer-topology
title: "ADR-2607122200: kotoba-lang UI スタックに HIG semantic 層・cascade-layer 契約・shell/theme 層を追加し、普通の agent でも洗練された UI を既定で出せるトポロジーに整える"
status: accepted
date: 2026-07-12
deciders:
  - Jun Kawasaki
doc_type: adr
topic: kotoba-lang-ui-design-system
authoritative: true
authoritative_for:
  - shitsuke.hig（Apple HIG semantic token 層 — 11 text styles / semantic colors
    light+dark / system palette / spacing / radius / base CSS）を kotoba-lang UI の
    typography・color の SSoT とすること
  - CSS cascade-layer 契約（`@layer kotoba.hig, kotoba.glass;` にライブラリ CSS を格納し、
    app CSS は unlayered のまま常に勝つ）を specificity 戦争の恒久解とすること
  - kotoba-ui を「再 export facade」から「shell（page/app-shell/hero/section/stack/grid）
    + theme（単一 override map）+ ->page（one-call SSR）を持つ実エントリ」に昇格すること
  - agent 向け paved road（kotoba-ui/docs/agent-guide.md + superproject skill
    `.claude/skills/kotoba-uiux/`）を新規 frontend 作業の必読契約とすること
  - 「app コードに raw hex / ad-hoc font-size / liquid-glass__* への compound-selector
    上書きを書かない」という review 基準
related:
  - adr-2607022800-kotoba-lang-default-uiux-appkit-uikit-interface-fundamentals
  - adr-2607011900-kotoba-lang-liquid-glass-ui
  - adr-2606301900-kotoba-lang-shitsuke-design-system
  - adr-2607101000-net-babiniku-zeta-ai-uiux-redesign
supersedes: []
superseded_by: []
last_verified: 2026-07-12
---

# ADR-2607122200: kotoba-lang UI スタックに HIG semantic 層・cascade-layer 契約・shell/theme 層を追加し、普通の agent でも洗練された UI を既定で出せるトポロジーに整える

**Status**: accepted — landed（実装 SHA は末尾 References の landed 表を参照）
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki

## Context

オーナー指摘: 「https://net-babiniku.pages.dev/ などのデザインがいまいち洗練されて
いない。appkit / kotoba-ui / uikit / liquid-glass-ui を把握して、cljc で最も Apple HIG
に基づいた非常に洗練された UI/UX の web / local app が Sonnet などの普通の agent でも
作れるように全体のトポロジーを整えて」。

4 並列の調査（appkit+uikit / kotoba-ui+liquid-glass-ui / net-babiniku / UI 隣接 25 repo
+ deps.edn 依存グラフ）で判明した現状:

- **層構造自体は既に正しい**（ADR-2607022800）: `css`/`html`（L0、org 全体で ~23/~20
  consumer）→ `shitsuke`（構造+dual-render）→ `liquid-glass-ui`（material、53 tests、
  活発）→ `kotoba-ui`（単一 require facade）→ `appkit`/`uikit`（platform 既定値）。
  browser-engine 系（`cssom`/`htmldom`/`dom-gpu`→`browser`）は意図的に別系統。
- **しかし「デザインシステムの芯」が欠けている**。shitsuke.tokens は v1 の最小集合
  （8 色 + 5 text style）、liquid-glass は `:ink`+accent のみで、**HIG 級の
  typography scale・semantic color（light/dark）・spacing 体系がどこにも無い**。
  だから app は hex を発明する（net-babiniku: `#0b0c10` `#7ee787` `#f0883e` …）。
- **page/layout の層が無い**。hero・section・nav+content shell・responsive grid を
  組む部品が無いので、app が `.layout`/`.hero` 等の CSS を手書きする。
- **cascade 契約が無い**。ライブラリ CSS が実行時に app の `<style>` より後に同
  specificity で注入されるため、net-babiniku の index.html は **412 行のインライン
  CSS の大半が specificity 戦争**（`.top-nav` が liquid-glass 移行後一度もマッチして
  いなかった事故、`.liquid-glass__toolbar.app-toolbar` 等の compound-selector 上書きの
  堆積、white-on-gradient の WCAG 違反を後追い修正した記録がコメントに残る）。
- **エントリが機能していない**。kotoba-ui の consumer はゼロ。net-babiniku は
  liquid-glass を直 require し、`lg/text-field` の keystroke 喪失バグ（毎回、全
  フィールド、本番）を踏んで手書き `<input>` にフォークしていた。
- **agent 向けの正典レシピが無い**。知識が 6 repo + 複数 ADR に分散しており、普通の
  agent が「明白な道」を辿ると ad-hoc CSS に落ちる構造だった。

つまり「いまいち洗練されない」のは各 app の腕の問題ではなく、**洗練が既定値として
出てこないトポロジーの問題**。本 ADR はそこを埋める。

## Decision

### D1. `shitsuke.hig` — Apple HIG semantic token 層（SSoT）を shitsuke に新設

`src/shitsuke/hig.cljc`（additive。既存 `shitsuke.tokens` v1 は不変）:

- **11 text styles**（Apple HIG 公表値: large-title 34/41 … caption2 11/13、SF 系
  + Hiragino/Noto JP フォールバックの text/display/mono スタック）
- **semantic colors light+dark**（label 4 階層 / system-background 3+grouped 3 /
  separator / fill 4 階層 / placeholder / tint。UIKit 公表値そのまま）
- **system palette light+dark**（red…gray6 の 18 色）
- **spacing（4pt grid）/ radius scale / hairline**
- emitter: `--hig-*` CSS vars（`:root` + `@media prefers-color-scheme: dark` +
  `data-appearance` 属性による強制 light/dark）、element 既定の `base-css`
  （body/h1-h4/a/code/hr/::selection/:focus-visible/reduced-motion）、
  `.hig-<style>` utility classes、一括 `hig-css`。

### D2. cascade-layer 契約 — `@layer kotoba.hig, kotoba.glass;`

ライブラリ CSS はすべて named cascade layer 内に emit する（shitsuke.hig →
`kotoba.hig`、liquid-glass-ui → `kotoba.glass`）。**app CSS は unlayered のままにする
ことで、注入順・specificity に関係なく常にライブラリに勝つ**（CSS 仕様: unlayered >
layered）。これで compound-selector 上書きという类の app 側 CSS が構造的に不要になる。
liquid-glass-ui の `inline-style`/`layered-css` が layer 宣言 + wrap を emit する。
`component-rules`（EDN data）は不変なので既存テスト・consumer の視覚は変わらない。

### D3. kotoba-ui を実エントリに昇格 — `shell` + `theme` + `->page`

- `kotoba-ui.shell`: `page`（完全な HTML document hiccup: viewport/color-scheme/style
  注入順込み）、`app-shell`（nav-bar + content + optional sidebar の responsive
  scaffold）、`hero`、`section`、`stack`（v/h、spacing token）、`grid`（responsive
  card grid）、`spacer`。HIG "Layout" の実装。app が layout CSS を書く必要を消す。
- `kotoba-ui.theme`: `{:accent … :appearance :auto|:light|:dark …}` の**単一 map** →
  shitsuke.hig / liquid-glass tokens への override に変換。app に hex を書かせない
  唯一の theming 入口。
- `kotoba-ui.core/->page`: view + theme → 完全な HTML 文字列（SSR/nbb one-call）。
  browser 側は同じ view を shitsuke の reagent seam で mount。
- 既存の 32 alias 再 export は不変（後方互換）。

### D4. appkit / uikit の役割は不変

ADR-2607022800 のまま（platform trait 既定値のみ）。docs から agent-guide への相互
リンクだけ追加。

### D5. agent paved road

- **`kotoba-ui/docs/agent-guide.md`** — 正典レシピ（worked example、do/don't、
  review checklist）。
- **superproject skill `.claude/skills/kotoba-uiux/SKILL.md`** — frontend 作業の
  トリガーで必読。8 の non-negotiable rules（単一エントリ / raw hex 禁止 /
  specificity 戦争禁止 / shell 起点 / theme 単一 map / HIG text styles / a11y /
  SSR-first dual-render）。
- **CLAUDE.md に「UI/UX 標準」節**（skill へのポインタ）。
- review 基準: app コードの raw hex・ad-hoc font-size・`liquid-glass__*` compound
  上書きは design-system gap のシグナルであり、上流にトークン/部品を足して解決する。

### D6. 実バグの上流修正（paved road を舗装する前提作業）

- `lg/text-field`/`lg/text-area` の keystroke 喪失バグ（net-babiniku が本番で踏み、
  手書き `<input>` へフォークした原因）を liquid-glass-ui 側で修正 + 回帰テスト。
- `demo.clj` の `clojure.java.io` require 欠落、`resources/liquid_glass/specular.js`
  への stale コメント参照を修正。

### D7. スコープ境界と follow-up

- **net-babiniku の実移行（shell/theme への載せ替え、412 行インライン CSS の解体）は
  follow-up**（本 ADR はトポロジー整備まで。移行はこの新契約のドッグフーディング
  第 1 号として別 PR で行う）。
- `css`/`html` を直接使う既存 ~23 consumer への強制移行はしない（L0 は正当な substrate。
  「新規 frontend」だけが D5 の contract に従う）。
- browser-engine 系スタック（cssom/htmldom/dom-gpu）は本 ADR の対象外・別系統のまま。

## Consequences

- (+) 「普通の agent が明白な道を辿ると HIG 準拠の洗練された UI が出てくる」構造に
  なる: skill → agent-guide → `->page` + shell + theme で、typography/色/dark mode/
  a11y/motion が既定で正しい。
- (+) specificity 戦争が仕様レベルで消滅（unlayered app CSS が常勝）。net-babiniku の
  412 行のような防衛的 CSS は原理的に不要になる。
- (+) SSoT が一箇所（shitsuke.hig）になり、brand theming は override map 一枚で済む。
- (−) 層が 1 つ増える（shitsuke.hig）。ただし additive で既存 consumer は無変更。
- (−) `@layer` は Safari 15.4+/Chrome 99+/Firefox 97+ 前提（2026 年時点で実質全域）。
- (−) kotoba-ui が「純 facade」でなくなる（shell/theme という実ロジックを持つ）。
  ADR-2607022800 の「独自ロジックを持たない」契約をこの範囲で更新する。

## Alternatives Considered

- **各 app の手書き CSS を都度磨く**: 却下。net-babiniku で 51 ラウンドの kaizen を
  やっても構造問題（specificity 戦争・トークン不在）は解けなかった実績がある。
- **HIG トークンを liquid-glass-ui に置く**: 却下。typography/semantic color は
  material（glass）に依存しない構造層の資産で、glass を opt-out した flat skin でも
  使えるべき。shitsuke が正位置。
- **新 repo `hig` を切る**: 却下。shitsuke の README が「dark mode / typography は
  extension point」と明記しており、charter 内。repo 増殖はエントリの曖昧化に逆行。
- **`!important` や注入順制御で specificity 問題を解く**: 却下。`@layer` が仕様上の
  正解で、app 側の自由度を奪わない。
- **Tailwind 等外部 CSS フレームワーク導入**: 却下。EDN-native（css.core）+ zero-dep
  `.cljc` という既存原則と衝突し、runtime 優先順位ルール（kotoba wasm 系）にも合わない。

## Landed（2026-07-12。全 PR サーバ側マージ、pin は verify-west-pins 5/5 OK）

| repo | 変更 | main SHA | west pin commit |
|---|---|---|---|
| shitsuke | `shitsuke.hig`（PR #3、52 tests/251 assertions） | `3010910` | `67fb4b99` |
| liquid-glass-ui | `@layer kotoba.glass` + text-field keystroke fix（PR #3、59 tests/632 assertions。根本原因: reagent の async-safe controlled-input 機構は `:value`+`:on-change` でのみ作動し、`:value`+`:on-input` では毎 input 後に stale 値へ DOM が巻き戻る。textarea の `:value`-as-content 追随停止も同時修正） | `b85af88` | `9035309e` |
| kotoba-ui | shell/theme/->page + docs/agent-guide.md（PR #1、18 tests/104 assertions、CI green） | `2494990` | `9a065372` |
| appkit | agent-guide への docs リンク | `17ff4db` | `e26c7afa` |
| uikit | agent-guide への docs リンク | `e637761` | `3710c98` |

net-babiniku の実移行（D7 の follow-up 第 1 号）は同日完了: PR #148（main
`d4f648d`、pin `07f43990`）で index.html `<style>` 420→115 行、views.cljs は
kotoba-ui.core + uikit.core のみ、theme は nbb build-time 静的生成、実 Chromium
19/19 検証、本番 https://net-babiniku.pages.dev/ 反映済み（詳細は
ADR-2607101000 の 2026-07-12 migration-landed addendum）。

shitsuke 側 follow-up（liquid-glass PR #3 が記録）: `shitsuke.components/input|textarea` に
同じ `:on-input` バグが残存、`shitsuke.hiccup/->html` は textarea `:value` を content として
SSR すべき。main の west-pin-verify CI は本件以前から全 commit で failure（既知の
github.token 制約）— ローカル `verify-west-pins.cljs` で 5/5 OK を確認済み。

## References

- 90-docs/adr/2607022800-kotoba-lang-default-uiux-appkit-uikit-interface-fundamentals.md
- orgs/kotoba-lang/shitsuke（`src/shitsuke/hig.cljc`、branch `ui-hig-tokens`）
- orgs/kotoba-lang/liquid-glass-ui(`@layer kotoba.glass` + text-field fix、branch `ui-cascade-layer`)
- orgs/kotoba-lang/kotoba-ui（shell/theme/->page + docs/agent-guide.md、branch `ui-shell-theme`）
- .claude/skills/kotoba-uiux/SKILL.md
- https://developer.apple.com/design/human-interface-guidelines/typography（text style 公表値）
- https://developer.apple.com/design/human-interface-guidelines/color（semantic color 公表値）
- net-babiniku/public/index.html（412 行インライン CSS の specificity 戦争の実録）
- net-babiniku/src/babiniku/ui/views.cljs 51-87 行（lg/text-field keystroke バグの実録）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>

## Addendum (2026-07-13): appkit 実証例 landed — itonami.cloud cockpit 移行 + shitsuke 上流バグ根治

- **shitsuke の同型 keystroke バグを根治**（PR #4、main `15b5337`、pin `dd8085f7`）:
  `shitsuke.components` の form control 群を `:on-change` 契約へ（caller の `:on-input`
  API は維持・reattach）、textarea `:value` を属性化、`shitsuke.hiccup/->html` は
  textarea の `:value` 属性を escaped content として SSR する特殊処理を追加。
  これで ADR-2607101000 系で記録した keystroke バグ族は上流・下流とも全て解消。
- **appkit（desktop-dense）実証例第 1 号: itonami.cloud public cockpit 移行完了**
  （gftdcojp/cloud-itonami PR #393、main `64f85c4`、pin `fcb96bde`、本番デプロイ・
  live 検証済み — HTML 中 raw hex 0、theme.css 配信）: index.html 820→485 行、
  raw hex 34→0、`kotoba-ui.core`+`appkit.core` 単一エントリ（appkit/panel
  thick/flat ×16）、theme `{:accent "#0f766e"(既存 brand teal) :accent-dark
  "#14b8a6" :appearance :auto}`（light/dark 両対応）、theme.css は既存 JVM `:site`
  生成エントリポイントから emit（生成の原子性優先。nbb 別スクリプトより適合）。
  実 Chromium 36/36（light/dark × 1440/320px、320px 横スクロールなし、glass +
  HIG typography、hydration、JS エラー 0）。
  net-babiniku（uikit/dark SPA）と合わせて **uikit・appkit 両バインディングの
  実証が完了**。
- 移行中の発見: checked-in の public/index.html と cljc ソースが**双方向に乖離**
  していた（live のみに mamori case-room、ソースのみに ADR-0022 jobs 節）。union
  merge で両方保全し、live 側 JS の `data.mamori-effects`（減算としてパースされ
  ReferenceError）も実 API キー `mamoriEffects` へ修正。`local/index.html` の同種
  stale と scoped routes（`public/isco-1212/`・`public/marketplace/` の独自 chrome）
  は follow-up として PR #393 に記録。
- 次候補（2026-07-13 調査のランキング）: ①kotoba EDA workbench（最高インパクト、
  2D canvas + reagent 結合で大）②slides（既に shitsuke tokens、最小コスト）。
  gftd.ai chat は greenfield（移行対象ではない）、manimani.cloud は frontend 実体
  なし、isekai.network は WebGPU+collab 結合で高リスク・uikit 重複。
- upstream 改善メモ: `kotoba-ui.shell/grid` が `:id` opt を受けないため JS 対象
  グリッドで app-CSS ミラーが要った — shell 側で opts passthrough を広げる余地。

## Addendum (2026-07-13, 第2弾): EDA workbench + slides 移行完了 — 7 サイト計画の主要 appkit 群が全て paved road 上に

前 addendum の次候補①②と upstream 改善を一括実施した:

- **kotoba-ui.shell に `:id`/`:class`/`:attrs` root-attr passthrough**（PR #2、main
  `423d292`、pin `8be66e94`）: 全 scaffold で JS/enhancer フックを app-CSS ミラー
  なしで付与可能に。後方互換は pre-change 出力との byte-identical テストで担保。
  agent-guide 更新済み。
- **slides editor 移行**（PR #5、main `b4d9d6e`、pin `7e780aed`、Pages 反映済み）:
  chrome hex 36+2→0、appkit/panel thick+flat、theme `{:accent "#496B9A"(brand
  steel blue) :accent-dark "#7FA3CF" :appearance :auto}`、theme は既存 `:pages`
  生成で docs/main.css へ。実 Chromium 25/25（SSR marker + 19 data-act + 21 id
  保全、8連打 keystroke 喪失ゼロ、320px OK）。副修正: `[hidden]` display guard
  （EDN モードで visual pane が残る既存バグ）。**発見**: この repo の
  `data-kotoba-render="ssr"` は静的 marker で cljs hydration adapter は org 内に
  未実在（no-JS artifact が仕様。selftest が main.js 不在を assert）— dual-render
  の browser 側は host-adapter 待ちが正しい現状。
- **EDA workbench 移行**（eda PR #1 → main `644ff3f`、pin `e0eb0416`；kotoba
  PR #302 → main `1ab3c9d`、pin `29fc5034`、Pages 反映済み）: **調査時の source
  map の訂正** — workbench SPA の正本は eda repo でなく kotoba repo の
  `docs/eda/`（`kotoba_eda_app.cljs` 1176 行 + build）で、eda repo の site.cljc は
  別の docs ページ。両方移行した。chrome hex 34→0、`:root` 手書き 13 props→0
  （`--eda-*` 4 個は HIG token 上の color-mix 派生のみ）、canvas 描画色は 1 つの
  `canvas-colors` map に集約（描画ドメイン色として exempt、flow highlight は
  `--hig-color-tint` を live 参照）。theme `{:accent "#2563eb" :accent-dark
  "#60A5FA" :appearance :auto}`。実 Chromium 28/28（4 canvas タブ非空描画、
  policy-gate/run-full-flow/file-intake 実操作、light の dense text は
  secondary-label 3.44:1 → color-mix 補正で ≥4.5:1）。artifact/source 乖離は
  byte-identical で無し。shadow-cljs は `:deps {:aliases [:cljs]}` 化で kotoba-ui
  `.cljc` を browser bundle と共有（dual-render 一本化）。
- **これで paved road 上の実証: uikit=net-babiniku、appkit=itonami cockpit +
  slides + EDA workbench の 4 プロダクト**。残 follow-up: kotobase.net
  landing（小・低優先）、isekai.network（WebGPU 結合・要別判断）、gftd.ai
  （greenfield）、cloud-itonami scoped routes、slides の browser hydration
  adapter（新機能扱い）、mono font token の追加（slides が opt-out で手書きした
  `#deck-edn` の font stack を token 化する）。

## Addendum (2026-07-13, 第3弾): 残 follow-up 消化 — mono token・kotobase.net landing・itonami scoped routes

- **`--hig-font-*` トークン**（shitsuke PR #5、main `35099a7`、pin `d56e171a`）:
  text/display/mono の 3 スタックを `:hig/font` token group として CSS vars 化、
  base-css は var 参照へ（resolved 値同一をテストで担保）、`.hig-mono` utility 追加。
  slides の opt-out 手書きを retire（PR #6、main `bbbb180`、pin `c15b4615`）。
- **kotobase.net landing 移行**（net-kotobase PR #193、main `6769173`、pin
  `8662df15`、Worker デプロイ・live 検証済み）: 手書き 122 行/15 hex →
  `->page` + shell 生成（chrome hex 0）、uikit binding、theme
  `{:accent "#0f766e" :accent-dark "#5eead4" :appearance :auto}`。in-flight branch
  `feat/kotobase-site-cljc-html-css` は既 merge（PR #144）と判明し、その
  pipeline 構造は継承・styling 層は paved road で置換。**drift 修正**: 廃止済み
  ipfs.gftd.ai gateway を宣伝し続けていた production copy を、`site_page.cljc` の
  修正済みソースから再生成して解消。これで ADR-2607022800 の 7 サイト計画のうち
  実移行可能な対象はすべて paved road 上（残: isekai=WebGPU 要別判断、
  gftd.ai=greenfield、manimani=frontend なし）。
- **cloud-itonami scoped routes**（PR #395、main `0385f05`、pin `2ac1185b`、
  Pages デプロイ・live 検証済み）: `/isco-1212` は手書き HTML → `:site` 生成へ
  昇格（WebAuthn/CACAO スクリプトは byte-identical 抽出、23 の getElementById
  契約保全）、`/marketplace` は markup を `site/marketplace.cljc` に共有化
  （nbb 生成者の役割は data のみに）。chrome hex 15+16→0。`local/index.html` の
  乖離は一方向（未コミットのソース編集）と判明し union-merge で
  `local_shell.cljc` を byte-identical に整合（テーマ移行はせず — dev-http 静的
  root で theme.css 経路なし、理由付き現状維持）。**ブラウザ検証が実バグ 2 件を
  検出・修正**（`#authed-sections` の id specificity が `.hidden` toggle に勝ち
  サインイン前にゲート区画が見えていた / `box-sizing` 欠落）。
  **危険な罠も記録**: 共有 `industry` checkout が 3 entries 遅れており、ローカル
  registry からの再生成は ISIC 3 件を静かに落とすところだった — 生成系は
  GitHub HEAD の registry を正とすること。
- pin 検証 4/4 OK（shitsuke/slides/net-kotobase/cloud-itonami、+ mono 分含め
  本 batch 計 5 commit）。

## Addendum (2026-07-13, 第4弾): 未反映棚卸しの上位3件を消化 — kotobase 全ルート・murakumo.cloud 全面・x402 ライブラリ fan-out

2026-07-13 の 6 リポ監査（cloud-itonami / cloud-manimani / ai-gftd-apex /
net-kotobase / cloud-murakumo / nexus-x402）で判明した未反映 surface のうち
推奨順 1〜3 を実施:

- **net-kotobase の off-road HTML 3 ルート**（PR #194、main `ef9a438`、pin
  `ef986dc5`、Worker デプロイ・live 検証済み）: `/signup`（獲得導線。funnel/
  authn/checkout JS は verbatim 保全 + `{{AUTHN_URL}}` slot）、`/admin`
  （= ADR-2607022800 の「kotobase.net console」の実体）、`/explore`（動的部は
  既存 escape-html 経路のまま静的 shell に slot 挿入。XSS プローブが escape
  されたまま描画されることを実ブラウザで確認）。theme map を `site_theme.cljc`
  に一本化し landing 含む 4 ページが同期。hex 36→0。**a11y 実測修正**: HIG
  secondary-label は light で ~3.4:1（AA 未満）— token 由来 `color-mix` で
  ≥4.69:1 化し landing も再生成（この修正パターンは EDA・x402 でも独立に
  必要になった = HIG 公表値の既知の罠として記録）。
- **cloud-murakumo（murakumo.cloud）全面移行**（PR #20、main `49eb919` + parse
  fix `5f9d6d4`、pin `75ff1854`、Worker+assets デプロイ・live 検証済み）:
  docs/blog 4 面を生成ページ化 + SPA は renderer 温存の token 載せ替え
  （cljc UI IR + DOM adapter・Stripe/x402 配線は byte 不変）。hex 66→0、
  重複 stylesheet 6→1 bundle、dark 専用→light/dark `:auto`。uikit binding、
  theme `{:accent "#3D5CCC"(brand indigo #7c9cff は white 上 2.6:1 で AA 落ち
  のため同 hue 暗色化) :accent-dark "#7C9CFF"}`。実 Chromium 80/80（slider
  操作でコスト再計算、320px 全 6 面 scrollWidth==320）。**発見**: main の
  `wrangler.jsonc` が移行以前から parse 不能（treasury var 削除時の孤立カンマ）
  で deploy 構成が壊れたままだった — 最小修正で解消（`5f9d6d4`）。
- **x402-directory ライブラリの HIG 化**（lib PR #1、main `97c5543`、pin
  `0745b82e`；nexus-x402 PR #2、main `a621ba0`、pin `d6c79a23`→`75ff…` 系列、
  x402.nexus デプロイ・live 検証済み）: runtime zero-dep 制約を維持したまま、
  `default-page-css` のパレットを shitsuke.hig から**生成**する方式
  （`scripts/gen_default_css.clj` + `:gen` alias、`;; gen:begin/end` marker に
  splice、`--check` で検証、WCAG レポート付き）。brand cyan `#0891b2` は
  white 上 3.7:1 で AA 落ち → 同 hue `#0E7490` に暗色化（dark は既存
  `#22C3E6` 10.0:1 のまま）。`:branding :css` seam・class 名・DOM 形状は不変
  なので全 facilitator が無変更で継承。
- 共有 `orgs/gftdcojp/cloud-murakumo` checkout には並行セッションの WIP
  （generation endpoint 配線 + 同カンマ修正）があり rule 通り温存
  （先方着地時に整合）。
- 残 gap（棚卸しより）: ai-gftd-apex（liquid-glass 直載せ→kotoba-ui 化、L +
  viewer S）、cloud-itonami `/local/` shell+keiei 画面（M、operator 面）、
  cloud-manimani landing（greenfield、要否オーナー判断）。

## Addendum (2026-07-13, 第5弾): apex chat + itonami local shell — 棚卸しの移行可能 surface を完遂

- **ai-gftd-apex（gftd.ai apex chat）移行**（PR #7、main `2d139e8`、pin
  `54e4de0a`、Pages デプロイ・live 検証済み）: liquid-glass 直載せ（opt-out 構成）
  → kotoba-ui + uikit 単一エントリへ。style.css 290→84 行、hex 7+13→0、runtime
  CSS 注入 → 静的 theme.css、手維持 dark パレット削除（`:auto` tokens が代替）。
  theme `{:accent "#1a56db"(既存 brand) :accent-dark "#0a84ff"}`。**main に潜んで
  いた実バグ 5 件を発見・修正**（送信/ストリーム済みメッセージが一切表示されない
  create-class の deref 位置バグ / panel 子要素の IFn 誤呼び出しクラッシュ / cljs
  `str/split` の capture-group 重複 / model-picker handler 未接続 / React 18
  createRoot + 非同期 dispatch での交互 keystroke 喪失 → `dispatch-sync` 化）。
  実 Chromium 26/26。共有 checkout の並行 WIP（viewer.js、未 commit）は deploy
  時に補完して本番から消さないよう保全。
- **cloud-itonami `/local/` shell + keiei 運営画面移行**（PR #396、main
  `41223c61`、pin `ae3c7cf9`。`public/` 出力は byte 同一のため再デプロイ不要）:
  手書きパレット 30 hex → 0（cockpit と同一 theme map を共有）。`local/` は独立
  静的 root（dev-http / file:// / Tauri）のため theme bundle は**インライン**
  （単一生成ファイルパターン）。`.keiei-screen__*` 等の class 契約・CLJS app の
  id ターゲットは全て不変。status 色は cockpit と同じ HIG palette 意味論
  （approve=green / reject=red / warn=orange）。実 Chromium で shell + keiei
  両画面 boot・light/dark・320px・20 サンプル ≥4.5:1 を確認。
- **sibling 鮮度修正**: `kotoba-lang/unspsc` の共有 checkout が 28 commits 遅れで
  cloud-itonami の `bb test` を壊していた（`kotoba.unspsc.product` 不在）→
  checkout 最新化 + pin 前進（`061432ed`、verify OK）。
- **これで 2026-07-13 の 6 リポ監査で挙がった「移行可能な未反映 surface」は
  すべて paved road 上**。残るのは意図的除外のみ: cloud-manimani landing
  （frontend 自体が不在 = greenfield、作る/作らないはオーナー判断待ち）、
  isekai.network（WebGPU+collab 結合、要別スコープ判断）、gftd.ai chat の
  custom domain 切替（apex は pages.dev 稼働中、ドメイン移行は別作業）、
  slides browser hydration adapter（新機能扱い）。

## Addendum (2026-07-13, 第6弾・完結): manimani.cloud landing 新設 + isekai.network chrome 移行 — 対象 surface 全消化

- **cloud-manimani landing（greenfield、オーナー指示 2026-07-13）**（PR #10、main
  `3cd0223`、pin `58343720`、Worker デプロイ・live 検証済み）: `manimani.cloud/` が
  JSON 404 → `->page` + shell + uikit の landing に。wrangler `[assets]` 追加で
  static 優先 → API fallthrough（`wrangler dev` で / = HTML、/health = JSON を実証）。
  llms.txt（kotobase 慣例の markdown twin）も新設。**コピーの誠実性**: 全主張を
  README/実 route に紐付け、`routes.cljc` にあるが `worker.cljs` 未配線の
  proposals/reviews は route 表に載せず Status で開示（`:site-test` が「live 9
  routes のみ掲載」+ 生成物鮮度を assert）。accent は local-manimani の実 UI が
  使う systemBlue 系（light は AA のため #0A66C2 に暗色化 / dark #0A84FF）。
- **network-isekai chrome 移行（旧 opt-out のオーナー逆転指示 2026-07-13）**
  （PR #148、main `6acff2c`、pin `43d2cff4`、Pages デプロイ・live 検証済み）:
  7 ページ shell（index/play/studio/dance/assets/generate/preview）の hex
  32/32/37/25/15/14/15 → 各2（`<meta theme-color>` のみ、var() 不可のため token
  値ミラー）。theme `{:accent "#0071e3" :accent-dark "#2997ff" :appearance :auto}`
  （light-first だった現デザインを維持しつつ dark を token で実装）。
  **canvas 安全性を baseline diff で実証**: 全ページ × light/dark × 1440/320 で
  canvas rect が移行前と byte 同一、canvas の祖先に backdrop-filter/filter/
  transform ゼロ、新規 console エラーゼロ（/js/app.js 404 は main と同一の
  既存事象）。deps.edn の「not the shitsuke/liquid-glass-ui」opt-out コメントは
  逆転の記録に書き換え。副修正: `#seg button.on` の white-on-white と studio
  非アクティブタブの2件のコントラストバグ。ux-audit gate 11/11 (100/100)。
- **これで 2026-07-12 の当初依頼から始まる一連の UI/UX トポロジー整備は、監査で
  特定した全 surface（12 プロダクト面）が paved road 上**: net-babiniku /
  itonami cockpit+scoped+local / slides / EDA workbench / kotobase.net 4 ルート /
  murakumo.cloud 全面 / x402.nexus(+lib fan-out) / apex chat+viewer /
  manimani.cloud（新設）/ isekai.network 7 ページ。
- 残 follow-up（小・別スコープ）: isekai の reagent chrome views の deep re-key、
  display-scale type tokens（>34px の hero clamp 用）、gftd.ai custom domain の
  apex 切替、slides hydration adapter、network-isekai repo の GitHub Actions
  有効化（bb-gates が CI で走らない — repo 設定判断）。

## Addendum (2026-07-13, 第7弾・最終): display tokens・slides live editor・isekai re-key・gftd.ai 世代交代 — 全 follow-up 完遂

- **`.hig-display1/2/3` fluid display tokens**（shitsuke PR #6、main `73a68a1`、
  pin `bd8b0d7f`）: `clamp()` ベースの流体 display scale（64/48/40px max、
  min=62.5%、line-height `calc(1em+4px)` で流体追従）。Apple 11-style 契約は不変
  （別 map で合成）。
- **slides browser hydration adapter**（PR #7、main `a579a07`、pin `1f66c87b`、
  Pages 反映済み — **本番の editor が実際に操作可能に**）: `slides.web.client`
  （React 18 createRoot replace-render、marker `ssr`→`live`）+ delegated
  `data-act` dispatch（`dispatch.cljc`、JVM 100% coverage）+ `enhance.cljc`
  （`:value` controlled fields に `:on-change` を client 側で付与 — SSR は fn attr
  を見ない）。keystroke 完全性は `dispatch-sync` + `r/flush` で 5ms burst 喪失ゼロ
  （plain dispatch と dispatch-sync 単体の両方が実測で欠落 — 記録）。drag/resize/
  nudge/Delete/undo/redo/zoom/EDN apply/DL/import まで配線。bundle 466KB
  (:advanced)。旧「no-JS artifact」設計の逆転は owner 指示によるものとして
  test/commit に明記。本番 E2E 20/20。
- **network-isekai chrome deep re-key**（PR #150、main `776c6e2`、pin `d472bef4`、
  Pages 反映済み）: studio tabs/docks・play AI panel 等の reagent chrome を
  kotoba-ui components + `.hig-*` に、hero は新 `.hig-display` へ。renderer/
  collab/canvas 不変 — baseline-vs-after 28 runs × 2 で canvas rect byte 同一、
  console 404 は baseline 再実行でも再現する既存 flaky と実証、`bb ux-audit`
  100.0/100・findings 0。（作業 agent が session/weekly limit で 2 度中断 →
  main session が worktree を引き継ぎ、harness の typo（`.type'` interop）を
  修正して監査・着地まで完遂。）
- **gftd.ai 世代交代（owner 指示）**: apex（kotoba-ui 版 chat）が gftd.ai 本番に。
  方式: wrangler OAuth が zone DNS 書込 scope を持たないため、**プロキシ Worker
  `ai-gftd-apex-shell`**（ai-gftd-apex.pages.dev へ転送）を deploy し、既存
  zone route `gftd.ai/*` を旧 `ai-gftd-chat-shell`（Vite 版、
  ai-gftd-apps-gftdcojp 内にソース残存）から付け替え。DNS 不変・ロールバック =
  route の script を戻すだけ（route id `5dcd78ea…`）。以後 apex の Pages deploy
  がそのまま gftd.ai に反映。旧 Vite bundle の配信停止を live 確認。
- **network-isekai の GitHub Actions は org レベルで無効**（repo API では変更
  不可）— owner 判断は「CI 不要」（2026-07-13）。local bb gates が正式な検証経路。
- 一連の UI/UX トポロジー整備はこれで完遂: 基盤（hig tokens / @layer / shell /
  theme / paved road docs+skill）+ 13 surface の本番反映 + gftd.ai 世代交代。

## Addendum (2026-07-14): refresh sweep — 本番ポートフォリオ 73.8 → 98.6（13/14 surface が 100.0）

design-quality CLI による本番スコアカード（2026-07-13）が示した「移行時点の旧ライブラリ
で生成されたまま」のギャップ（viewport-fit / all-edge safe-area / dvh / theme-color /
44px tap-targets）を、全 surface の再生成+再デプロイで解消した:

- **着地 9 repo**: net-babiniku(100.0)・net-kotobase 4面(100.0)・cloud-manimani(100.0)・
  cloud-itonami 4面(100.0、4ソースに viewport-fit + media-gated theme-color pair を追記)・
  slides(100.0、docs shell を ->page 化 + 不要な html 直依存を除去)・ai-gftd-apex 2面
  (100.0)・network-isekai 7 shell(100.0)・nexus-x402(+x402-directory template に
  viewport-fit/theme-color pair/safe-area/overflow guard/≤480px responsive を追加、
  100.0)。すべて deploy 済み・live 再スコアで確認。**aggregate 73.8 → 98.6**。
- **残 1**: kotoba EDA workbench（80.6）— 箱の load average 200-800 が続き
  shadow-cljs の par-compile が繰り返しタイムアウトするため defer（再生成手順は
  確立済み: kotoba repo docs/eda の shadow build + `clojure -M:build`、head ソースに
  同じ meta 追記）。murakumo.cloud は並行セッションの本番上書き（別 landing を
  deploy）が未解決のため対象外のまま — repo main は移行版、live は非移行版という
  乖離が継続中（owner 調整待ち）。
- **インシデント記録（正直に）**: pin 前進時、merge 結果の短縮 SHA (8桁) から残り
  32 桁を**捏造した full SHA を 2 件 main に書いた**（network-isekai /
  x402-directory）。`verify-west-pins` が「pin が上流に存在しない」で即検出し、
  実 SHA で 5 分以内に修正（`6dbc5846`/`ba75771c`）。教訓: 短縮 SHA は必ず
  `gh api .../commits/main --jq .sha` で full 化する。検証 gate が
  ADR-2607022900 の設計意図どおり機能した実例でもある。
- **運用知見**: 並列 5 agent × JVM/shadow/Playwright はこの箱では load 800 に達し
  stall/TLS timeout を誘発 → 途中から直列化 + 完成品先行着地に切替。agent が
  「background 待ち」で turn を終える無限待ちパターンには「foreground で完遂せよ」
  の再指示が必要（3 agent で発生）。shadow-cljs の
  `aborted par-compile ... still waiting` は負荷起因で、`--config-merge
  '{:build-options {:par-timeout 300000}}'` で回避できた。
