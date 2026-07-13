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
