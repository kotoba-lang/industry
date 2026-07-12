---
id: adr-2607022800-kotoba-lang-default-uiux-appkit-uikit-interface-fundamentals
title: "ADR-2607022800: shitsuke + liquid-glass-ui + kotoba-ui を kotoba-lang の default UI/UX design として確立し、Apple Interface Fundamentals に倣った appkit/uikit 二層バインディングを追加する"
status: accepted
date: 2026-07-02
deciders:
  - Jun Kawasaki
doc_type: adr
topic: kotoba-lang-ui-design-system
authoritative: true
authoritative_for:
  - shitsuke（構造）+ liquid-glass-ui（視覚）+ kotoba-ui（統合エントリ）を kotoba-lang
    配下フロントエンドの default UI/UX design とする位置づけ（ADR-2607011900 の
    「他の skin と並立する一選択肢」という位置づけの更新）
  - Apple "Interface fundamentals" の分類（Layout / Typography / Color & Materials /
    Navigation / Controls / Motion）を kotoba-ui docs の正典 taxonomy として採用すること
  - appkit（desktop / dense-data 向け binding）と uikit（touch / mobile / card 向け
    binding）という二層構成の役割分担と、両者が shitsuke/liquid-glass-ui を直接改変
    しないという契約の継承
  - 既存 7 プロダクトサイトの暫定分類（appkit / uikit / 未確定）が「設計方針の例示」
    であり、網羅的な移行監査ではないという scope 境界
related:
  - adr-2607011900-kotoba-lang-liquid-glass-ui
  - adr-2606301900-kotoba-lang-shitsuke-design-system
  - orgs/kotoba-lang/shitsuke
  - orgs/kotoba-lang/liquid-glass-ui
  - orgs/kotoba-lang/ui（kami-engine HUD、本ADRとは無関係・比較のため言及のみ）
  - orgs/kotoba-lang/kotoba-ui（本ADRで新設方針を定める新規repo）
supersedes: []
superseded_by: []
last_verified: 2026-07-02
---

# ADR-2607022800: shitsuke + liquid-glass-ui + kotoba-ui を kotoba-lang の default UI/UX design として確立し、Apple Interface Fundamentals に倣った appkit/uikit 二層バインディングを追加する

**Status**: accepted — landed (2026-07-02), `kotoba-ui`/`appkit`/`uikit`
pushed to `kotoba-lang` GitHub org as public repos, CI green against the
published `shitsuke`/`liquid-glass-ui`/`kotoba-ui` siblings (not just local
`:local/root`): `github.com/kotoba-lang/kotoba-ui`,
`github.com/kotoba-lang/appkit`, `github.com/kotoba-lang/uikit`
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607011900 で `liquid-glass-ui`（`shitsuke` 上の共通 liquid-glass 視覚スキン）
は設計・scaffold 済みだが、その Alternatives では「kotoba-lang フロントエンドが選べる
視覚スキンの一つ（フラット skin や OS-native skin と並立しうる）」という位置づけに
留まっていた。各プロダクト repo での実採用は個別 follow-up として明示的に先送りされ、
kotoba 言語全体としての "default" 宣言はまだ存在しない。

今回、以下を材料に UI/UX 設計統合の方針を更新する:

- `https://kotoba-lang.github.io/kotoba/eda/#formats`（EDA flow workbench）
- `https://kotoba-lang.github.io/slides/`（EDN → PPTX スライドエディタ）
- `https://itonami.cloud/#funnel`（ビジネスコックピット / ファネルダッシュボード）
- `https://gftd.ai/`（GFTD Chat）
- `https://isekai.network/#how`（AI ゲーム開発プラットフォーム）
- `https://kotobase.net/`（API プロダクト、curl ファースト）
- `https://manimani.cloud/`
- `https://github.com/kotoba-lang/liquid-glass-ui`
- `https://developer.apple.com/documentation/technologyoverviews/app-design-and-ui`
  （Interface fundamentals / UIKit / AppKit / SwiftUI の関係を説明する Apple 公式ページ）

WebFetch でこれらを調査した結果、**大半がクライアントサイドレンダリングの SPA で
あり、静的 HTML 取得では実際の配色・フォント・CSS トークンといった視覚仕様までは
取得できなかった**（`gftd.ai` と Apple の `app-design-and-ui` ページはタイトルのみ、
`manimani.cloud` は取得時点で HTTP 404）。取得できた範囲でも、各サイトが共有トーク
ンやマテリアル体系を持っている形跡はなく、それぞれ独立した ad-hoc スタイルで実装
されていると見られる。したがって本 ADR は「各サイトの現行デザインを逆算して仕様化
する」のではなく、**既に設計済みの `shitsuke` + `liquid-glass-ui`（Apple Liquid Glass
に範を取った完成済みのマテリアル体系）を正とし、それを kotoba 言語の既定 UI/UX に
格上げする**方針を取る。

`orgs/kotoba-lang/ui`（package 名 `kotoba.ui`）は既に repo として存在するが、中身を
読むと **stub ではなく、`kami-engine` の WebGPU ゲームキャンバス上に DOM オーバーレイ
の HUD（EDN spec → `:panel`/`:bar`/`:minimap`/`:text` ウィジェット）を描画する、
CI green・専用テスト付きの完成した実装**だった（`mount!`/`render!` はブラウザ
ClojureScript 専用で `:clj` 側は明示的に `throw` する設計）。これは kami-engine
という具体的な consumer に紐づく狭い責務の repo であり、shitsuke/liquid-glass-ui とは
無関係の独自 DOM 生成コードを持つ。**この repo を default design のシングルエントリ
namespace に転用することはしない**（kami-engine の既存利用を壊すため）。ユーザーが
挙げた「kotoba-ui」は、名前は似ているがこの既存 `orgs/kotoba-lang/ui` とは別物として
扱い、新規 repo `orgs/kotoba-lang/kotoba-ui` を default design のシングルエントリ
namespace として新設する。`orgs/kotoba-lang/` 配下には `kotoba-ui`/`uikit`/`appkit` に
相当する repo はまだ存在しない。また `kotoba-lang` org の外にも
`gftdcojp/ai-gftd-*`・`cloud-itonami/*`・`com-junkawasaki/manimani`・`etzhayyim/*` など
関連プロダクト repo が多数存在するが、それら全てへの適用可否まで踏み込むことは本 ADR
のスコープ外とする（ADR-2607011900 が置いた「ライブラリ設計・scaffold のみを完了条件
とし、個別採用は follow-up」という境界を踏襲する）。

## Decision

### 1. shitsuke + liquid-glass-ui + kotoba-ui を default UI/UX design とする

ADR-2607011900 の Alternatives で「並立しうる一選択肢」としていた位置づけを更新し、
**kotoba-lang 配下で新規にフロントエンドを起こす際、明示的な理由なく別スキンを選ぶ
のではなく、まず shitsuke（構造）+ liquid-glass-ui（マテリアル）+ kotoba-ui（統合
エントリ）を既定値として使う**、と宣言する。他スキン採用の余地（フラット skin /
OS-native skin 等）自体は ADR-2607011900 の Alternatives のまま残すが、それは
明示的な opt-out として扱う。

### 2. Apple Interface Fundamentals を kotoba-ui docs の taxonomy として採用する

`orgs/kotoba-lang/kotoba-ui/docs/design.md`（follow-up で作成）は、Apple の
[App design and UI / Interface fundamentals](https://developer.apple.com/documentation/technologyoverviews/app-design-and-ui)
が採用する分類に倣い、次の章立てで shitsuke/liquid-glass-ui の実装がどの fundamentals
概念に対応するかをマッピングして説明する:

| Interface fundamentals category | kotoba-lang 側の対応 |
|---|---|
| Layout | `shitsuke.hiccup` 構造 + `liquid-glass.components` の `panel`/`toolbar`/`nav-bar` |
| Typography | `shitsuke.tokens` のテキスト系トークン + `liquid-glass.tokens :ink` |
| Color & Materials | `liquid-glass.tokens`（`:surface`/`:elevation`/`:specular`/`:accent`）= Apple "Liquid Glass" 相当のマテリアル体系 |
| Navigation | `nav-bar`/`tab-bar`/`menu`/`sheet` |
| Controls | `button`/`toggle`/`slider` など 29 コンポーネント（`liquid-glass-ui/docs/design.md` の表） |
| Motion | overlay enter/exit・spring settle・press morph（同 design.md「Motion & dynamic effects」節） |

この taxonomy は、新規コンポーネント追加時のレビューチェックリスト（「この
fundamentals category に対応する既存 token/component はどれか」）としても使う。

### 3. 新規 repo `kotoba-ui` を「default UI/UX design のシングルエントリ namespace」として新設する

既存の `orgs/kotoba-lang/ui`（`kotoba.ui`、kami-engine HUD 専用）とは別に、新規
repo `orgs/kotoba-lang/kotoba-ui`（package 名 `kotoba-ui.core`、`kotoba.ui` との
namespace 衝突を避ける）を、`shitsuke` と `liquid-glass-ui` を require して再 export
する薄い composition layer として新設した（**scaffold・実装済み、テスト green**。
`docs/adr/0001-kotoba-ui.md` 参照）:

- `kotoba-ui.core` は `shitsuke.hiccup`/`liquid-glass.tokens`/`liquid-glass.style`/
  `liquid-glass.components`（32 public fn 全て）を土台にした単一 require point であり、
  プロダクト repo は `kotoba-ui.core` だけを require すれば default design（shitsuke
  構造 + liquid-glass マテリアル）一式が手に入る、という契約。全 var は
  `(def button liquid-glass.components/button)` の形の直接 alias で、独自ロジックを
  一切持たない。
- `kotoba-ui.core` 自身は独自のトークン/CSS を持たない（shitsuke/liquid-glass-ui の
  再 export に徹する）— ADR-2607011900 の「shitsuke 非改変」契約をそのまま継承する。
- 既存 `orgs/kotoba-lang/ui`（kami-engine HUD）は本 ADR の対象外・無変更。両 repo は
  目的が異なる別物として並存する。

### 4. Apple UIKit/AppKit 分割になぞらえた appkit/uikit 二層バインディングを追加する

`kotoba-ui` の上に、画面形状ごとのデフォルト値セットを持つ 2 つの新規 repo を置いた
（**scaffold・実装済み、テスト green**）。実装時に `liquid-glass.components` の実際の
opts 契約を確認したところ、32 コンポーネント中 `:surface`/`:elevation` を受け取るのは
`panel`（両方）と `list-view`（`:surface` のみ）だけで、`toolbar`/`nav-bar`/`sheet`/
`alert` 等は固定の見た目で screen-shape ごとの差別化点が存在しないと判明した
（当初の Context で想定していたより実装対象は小さい）。appkit/uikit は実在するその
2 つのみをラップする:

- **`orgs/kotoba-lang/appkit`** — desktop / dense-data 向け binding。`panel`:
  `{:surface :thick :elevation :flat}`（密なコンテンツ上で視認性を保つ、パネルが
  並列配置されるため影なし）、`list-view`: `{:surface :thick}`。
- **`orgs/kotoba-lang/uikit`** — touch / mobile / card-first 向け binding。`panel`:
  `{:surface :clear :elevation :floating}`（スクロールするコンテンツの上に浮く
  カードとして見える）、`list-view`: `{:surface :regular}`（component 自身の
  デフォルトと同値）。

他の全コンポーネント（`button`/`toolbar`/`nav-bar`/`sheet`/`alert`/`toggle` 等）は
appkit/uikit でラップせず `kotoba-ui.core` から直接呼ぶ — 差別化点の無いコンポーネント
を空 alias でラップするのは無意味な間接層のため（各 repo の `docs/adr/0001-*.md`
参照）。

両 repo とも `kotoba-ui`（ひいては shitsuke/liquid-glass-ui）の上に乗るのみで、
それらを直接改変しない。Apple が SwiftUI（宣言的・クロスプラットフォーム）に対して
UIKit（タッチ）と AppKit（デスクトップ）をプラットフォーム形状ごとに分けている
構造を、`kotoba-ui`（宣言的統合層）に対する `uikit`/`appkit`（画面形状バインディング）
として写し取る。

### 5. 既存 7 プロダクトサイトの暫定マッピング（例示、網羅的移行監査ではない）

| サイト | 暫定分類 | 根拠 |
|---|---|---|
| kotoba EDA flow workbench | appkit | 多段ステージ・テーブル中心の dense technical dashboard |
| slides（EDN → PPTX エディタ） | appkit | Visual EDN エディタ、デスクトップ編集操作が主 |
| itonami.cloud（funnel / ビジネスコックピット） | appkit | ダッシュボード型、メトリクスカード + ファネル表 |
| kotobase.net（API プロダクト） | appkit | curl ファースト、開発者向けドキュメント/コンソール |
| gftd.ai（GFTD Chat） | uikit | 会話型 UI、カード/バブル中心が一般的 |
| isekai.network（AI ゲーム開発） | uikit | カード型ゲーム展示（open/fork/reuse）、モバイル寄りヒーロー構成 |
| manimani.cloud | 未確定 | 調査時点で HTTP 404、follow-up で再調査 |

各サイトの実際の移行（現行スタイルから shitsuke/liquid-glass-ui ベースへの置き換え）
は本 ADR のスコープ外とする（ADR-2607011900 と同じ境界）。

## Consequences

- (+) default 宣言により、新規 frontend repo が「どの UI を使うか」を毎回検討し直さ
  ずに済む。opt-out する場合のみ理由を明記すればよい構造になる。
- (+) Apple Interface Fundamentals の taxonomy が docs の共通語彙になり、`appkit`/
  `uikit` 双方の README・design.md を同じ章立てで書ける。
- (+) appkit/uikit の二分割により、今後追加される dense-table 系コンポーネントと
  card-grid 系コンポーネントの置き場所が明確になる（1 repo に両方の default 値が
  混在する事態を避けられる）。
- (−) `kotoba-ui`/`appkit`/`uikit` の 3 repo は scaffold・push・CI green まで完了した
  （`kotoba-lang` org、public、published sibling 相手の CI で確認済み）が、7 サイト
  の実移行（現行スタイルから置き換える作業）は全て未着手の follow-up。appkit/uikit
  が実際にラップするのは `panel`/`list-view` の 2 コンポーネントのみ（上記 §4
  参照）で、他のコンポーネントには screen-shape ごとの差別化が無い。
- (−) `gftd.ai`・`manimani.cloud`・Apple 公式 `app-design-and-ui` ページはクライアント
  サイドレンダリングのため、WebFetch では静的 HTML 以上の視覚情報（実際の配色・
  フォント・コンポーネント実装）を取得できなかった。上記の appkit/uikit マッピング
  は構造的な推測を含み、実移行時に現行デザインとのギャップ確認が必要。
- (−) `kotoba-lang` org 外（`gftdcojp/ai-gftd-*`・`cloud-itonami/*`・
  `com-junkawasaki/manimani`・`etzhayyim/*` 等）にも関連プロダクト repo が多数存在
  するが、本 ADR は `kotoba-lang` 配下の design system 契約のみを扱う。それらの repo
  が default design system を採用するかどうかは各 repo 側の判断であり、本 ADR は
  強制しない。

## Alternatives Considered

- **各プロダクトサイトの現行デザインを個別に分析し、site-specific token を
  liquid-glass-ui に逆輸入する**: 却下。上記の通り大半が SPA で実デザインの静的
  取得ができず、7 サイト分の逆算コストに見合わない。shitsuke/liquid-glass-ui は
  既に Apple Liquid Glass 系の完成した設計を持つため、そちらを正とする方が一貫性が
  高い。
- **appkit/uikit を単一の統合 layer（`kotoba-ui`）にまとめ、二分割しない**: 却下。
  EDA workbench のような dense desktop UI と isekai.network のような card-first
  mobile UI は `:surface`/`:elevation` の望ましいデフォルトが対照的（`:thick`
  多ペイン vs `:clear` 単一カラム）であり、1 つのデフォルトセットで両方を綺麗に
  表現できない。Apple が同様の理由で UIKit（タッチ）と AppKit（デスクトップ）を
  分けている前例に倣う。
- **本 ADR 執筆時点で uikit/appkit/kotoba-ui repo を git init して実装まで着手する**:
  初回は却下し ADR のみに留めたが、その後の follow-up ターンでユーザー確認を経て
  `kotoba-ui`/`appkit`/`uikit` の 3 repo を git init・scaffold・テスト green まで
  実施し、さらにユーザー確認の上で `kotoba-lang` GitHub org に public repo として
  push した（CI green、published sibling 相手に確認済み）。

## References

- `90-docs/adr/2607011900-kotoba-lang-liquid-glass-ui.md`
- `90-docs/adr/2606301900-kotoba-lang-shitsuke-design-system.md`
- `orgs/kotoba-lang/liquid-glass-ui/docs/design.md`（層ごとの API、Motion & dynamic
  effects）
- `orgs/kotoba-lang/liquid-glass-ui/README.md`
- `orgs/kotoba-lang/ui/README.md`（既存 repo、kami-engine HUD 専用。本 ADR の
  `kotoba-ui` とは別物 — 比較のため言及）
- `orgs/kotoba-lang/shitsuke/docs/design.md`
- `orgs/kotoba-lang/kotoba-ui/`（新設・scaffold 済み。`docs/design.md`、
  `docs/adr/0001-kotoba-ui.md`）
- `orgs/kotoba-lang/appkit/`（新設・scaffold 済み。`docs/design.md`、
  `docs/adr/0001-appkit.md`）
- `orgs/kotoba-lang/uikit/`（新設・scaffold 済み。`docs/design.md`、
  `docs/adr/0001-uikit.md`）
- `https://kotoba-lang.github.io/kotoba/eda/#formats`
- `https://kotoba-lang.github.io/slides/`
- `https://itonami.cloud/#funnel`
- `https://gftd.ai/`
- `https://isekai.network/#how`
- `https://kotobase.net/`
- `https://manimani.cloud/`（調査時点 HTTP 404）
- `https://github.com/kotoba-lang/liquid-glass-ui`
- `https://developer.apple.com/documentation/technologyoverviews/app-design-and-ui`

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>

## Addendum (2026-07-12): ADR-2607122200 による契約更新 — kotoba-ui は純 facade から実エントリへ

ADR-2607122200（kotoba-lang UI HIG semantic layer topology）が本 ADR の 2 点を更新した:

1. **§3 の「kotoba-ui は独自ロジックを一切持たない」契約を範囲限定で更新**。
   kotoba-ui は `kotoba-ui.shell`（page/app-shell/hero/section/stack/grid —
   HIG "Layout" の実装）、`kotoba-ui.theme`（単一 override map。app に raw hex を
   書かせない唯一の theming 入口）、`kotoba-ui.core/->page`（one-call SSR）を持つ
   実エントリになった（main `2494990`、PR #1）。既存 32 alias の再 export 契約は
   不変。正典レシピは `kotoba-ui/docs/agent-guide.md`、agent 向けトリガーは
   superproject skill `.claude/skills/kotoba-uiux/`。
2. **§2 の Interface Fundamentals 対応表の更新**: Layout 行は `kotoba-ui.shell`、
   Typography 行と Color & Materials 行の token 正本は `shitsuke.hig`（11 text
   styles / semantic colors light+dark / system palette、`--hig-*` vars）になった。
   liquid-glass.tokens は material（surface/elevation/specular/lens）の正本のまま。
   全ライブラリ CSS は `@layer kotoba.hig, kotoba.glass` 内に emit され、app CSS は
   unlayered で常に勝つ（specificity 戦争の恒久解）。

appkit / uikit の役割（platform trait 既定値のみ、panel/list-view の 2 ラップ）は
不変。両 README は agent-guide への誘導を追記済み（`17ff4db` / `e637761`）。
