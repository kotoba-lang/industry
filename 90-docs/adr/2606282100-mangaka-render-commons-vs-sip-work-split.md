# ADR-2606282100: mangaka render commons vs SIP work-specific split

- **Status**: accepted — Phase 1 + scene-clj landed (2026-06-28), tests green
- **Date**: 2026-06-28
- **Context tags**: mangaka, manga-generation, render-pipeline, clj, cljc, kami-engine, kami-app-sip-clj, ghosthacker, anchors-edn, dependency-inversion
- **Related**: `orgs/com-junkawasaki/org-spirit-in-physics-comics/docs/adr/0002-game-and-render-pipeline.md` (clj/Datomic render pipeline), `orgs/etzhayyim/root/90-docs/adr/2605141200-mangaka-3d-scene-pregel-kami-sdk.md` (mangaka 3D scene facade), `orgs/com-junkawasaki/kami-engine/kami-app-sip-clj/src/sip/render.clj`

## Context

「マンガ生成」は本来 **作品非依存の共通基盤**であるべきだが、現状 2D パネルの
prompt 合成 + image-gen 呼び出しの汎用ロジックが **個別作品アプリ `kami-app-sip-clj`
の `sip.*` 名前空間に閉じ込められている**。`sip` という命名は症状で、共通部分に
個別作品（Spirit in Physics = SIP）が入り込んでいる。3 リポを調査して現状を確定した。

### 現状の地図（誰がどの層か）

| リポ / モジュール | 言語 | 層 | 汎用 / 作品固有 | 状態 |
|---|---|---|---|---|
| `kami-engine/kami-mangaka-scene` | Rust + PyO3 | 3D scene composition facade（VRM+render+postfx） | **汎用 100%** | 現役（`mangaka.etzhayyim.com` / `lg_mangaka.compose_scene_3d` から呼ばれる） |
| `mangaka.gftd.ai`（`manga-layouts.ts` の `GRAPHIC_NOVEL_TEMPLATES`） | TS | ページ/コマ割りテンプレート | **汎用** | 現役。`sip.page` はこの port |
| `ghosthacker` | TS(SvelteKit)+Go | 旧・生成パイプライン（汎用レイアウト + OpenRouter画像生成 + 作品EDN） | エンジン+データ混在 | **廃止**（kami-app-sip-clj に置換） |
| `mangaka-ghosthacker-assets` | DataLad/git-annex | ghosthacker の style実験バイナリ | **作品固有アセット** | 現役（B2実体） |
| `kami-app-sip-clj/src/sip/render.clj` | clj | prompt 合成 + image-gen 呼び出し | **汎用と固有が混在** | 現役（本ADRの核心） |
| `kami-app-sip-clj/resources/render_anchors.edn` | edn | キャラ/環境/巻色/style | **作品固有データ** | 現役 |
| `org-spirit-in-physics-comics` | edn+nbb | SIP漫画版の site generator | **作品固有** | 現役 |

要点: **「mangaka」は汎用マンガ生成プラットフォームの名前として既に確立している**
（`mangaka.gftd.ai` / `mangaka.etzhayyim.com` / `kami-mangaka-scene` / `lg_mangaka`）。
3D 側（`kami-mangaka-scene`）は手本どおり作品非依存に切れている。**取り残されているのは
2D パネルの prompt 合成と image-gen の層だけ**である。

### 診断（事実ベース、file:line）

`sip.render`（351行）のうち約 300 行は完全に作品非依存なのに `sip.*` に居る:

| 汎用なのに sip 名前空間にある | 場所 |
|---|---|
| `render!`（image-gen `/generate` HTTP, 任意の拡散モデル, provenance記録） | `sip/render.clj:190-211` |
| `dims` / `aspect->dims`（コミック寸法） | `sip/render.clj:42-47` |
| `take-budget`（CLIP 77トークン予算） | `sip/render.clj:133-139` |
| `subject-count` / `framing`（booru主体・カメラ→タグ） | `sip/render.clj:121-129` / `:76-95` |
| `compose` の骨格（style-first concat） | `sip/render.clj:141-171` |
| `Durable` / `LocalCas` / `KotobaHttp`（CAS層） | `sip/store.clj:82-161` |
| ページ/コマ割りテンプレート（`mangaka.gftd.ai` の port） | `sip/page.clj` |

さらに悪いのは、`compose` に作品固有値が **コードとして** 埋まっている点（データであるべき）:

```clojure
;; sip/render.clj:112-119 — env-key にキャラ名が regex で焼き込まれている
(re-find #"事務所|office|デスク" l) :schwa-office     ; ← Schwa(キャラ)のオフィス
(re-find #"キッチン|アパート" l)    :tamaki-apartment  ; ← Tamaki(キャラ)のアパート
:else :water-city                                    ; ← デフォルトが「水の都」
```

- `nei-light-cues` / `nei-form`（`sip/render.clj:49-58`）— 登場人物 Nei の「光体↔肉体」判定
- `emotion->tag`（`sip/render.clj:97-105`）— 「静寂→serene」等の SIP 感情オントロジー

→ 新作品を追加すると、この関数群を fork して書き換えるしかない。これが「共通部分に
個別作品が入りすぎ」の実体。

### ghosthacker からの教訓

旧 ghosthacker(TS/Go) は **汎用レイアウトエンジン（`manga-layouts.ts`）と作品データ
（`260123-jump` の EDN）を分離していた**。Clojure 移行で全部を一つの作品アプリ `sip.*`
に畳み込んだ結果、その分離が失われた。**リライトで汎用エンジンが 1 作品に溶けた**のが
今の退行である。

## Decision

マンガ生成を **3 層**に整理し、汎用層を `kami-mangaka-*` family（既存の
`kami-mangaka-scene` と同じドメイン facade 系統）に引き上げる。境界は
**「コードでなくデータ + 注入（依存の逆転）」**で引く。

### 命名（kami-engine ツリーの規約に準拠）

kami-engine の crate 命名は 2 系統。**`kami-engine-*` はエンジン/SDK コアに予約**
（`kami-engine` / `kami-engine-clj` / `kami-engine-sdk` / `kami-engine-sdk-clj`）、
**`kami-<domain>-*` はドメイン facade**（`kami-character`/`kami-character-scene`,
`kami-atmosphere`/`kami-atmosphere-scene`, `kami-autodrive`/`kami-autodrive-scene` …）。
`mangaka` は character / atmosphere と同じドメインで、既に `kami-mangaka-scene`（3D
facade）が存在する。よって共通の 2D 生成層はその兄弟として `kami-mangaka-*` に置く
（`kami-engine-mangaka-*` はコア/ドメインの区別を壊すので採らない）。

```
Tier 1: 共通基盤 = kami-mangaka-* family
  kami-mangaka-scene (Rust/PyO3)      ← 3D scene                       ✓既に汎用
  kami-mangaka-scene-clj (clj 新規)   ← 3D scene の EDN authoring tier（→JSON-LD/composeScene3d）
  kami-mangaka-render-clj (clj 新規)  ← 2D compose骨格 / take-budget / framing / subject / render!
  kami-mangaka-page-clj   (clj, =manga-layouts.ts移植) ← コマ割り（未着手）
  kami-engine-sdk-clj                 ← Datomic ECS / Kotoba CAS
        ▲ inject mappers + load anchors
Tier 2: 作品バインディング = データ + 小さな写像
  render_anchors.edn   : characters / environments / volume-color / style-lead
  emotion->tag         : edn データ表（コードでなく）
  location->env        : edn データ表（regexにキャラ名を埋めない）
  character-form rule  : 「Nei=光体/肉体」を per-work callback
        ▲
Tier 3: 作品アプリ
  sip.world / sip.schema / sip.lore / sip.bible / sip.session
  org-spirit-in-physics-comics (site generator)
  ghosthacker-* (別作品として同じTier1を使う)
```

### 振り分け

**共通（`kami-mangaka-*` に出す）**: `render!` + provenance datom / `dims` /
`aspect->dims` / `take-budget` / `subject-count` / `framing` / `compose` の骨格
（写像は注入で受ける）→ `kami-mangaka-render-clj`。ページ・コマ割りテンプレート
（`sip.page`）→ `kami-mangaka-page-clj`。store・Kotoba CAS → `kami-engine-sdk-clj` へ。

**個別作品に残す（ただし“コード”でなく“データ＋注入”に格下げ）**: `render_anchors.edn` /
`emotion->tag`・`location->env` を edn データ表へ / `nei-form` の光体判定を per-work
callback へ / world・schema・lore・bible・storyboard・DTP個別調整・ゲームロジック。

### 鍵となる修正 — 依存の逆転

`compose` を「写像を引数で受ける汎用関数」にし、作品側は薄い facade にする:

```clojure
;; 共通 mangaka.render
(defn compose [{:keys [anchors panel mappers]}]
  ;; mappers = {:emotion->tags f, :location->env f, :character-form f}
  ...)

;; 作品 sip.render は facade
(defn compose [{:keys [anchors panel]}]
  (mangaka.render/compose
    {:anchors anchors :panel panel
     :mappers {:emotion->tags  sip-emotion-table   ; edn由来
               :location->env  sip-env-table       ; edn由来（キャラ名regexを排除）
               :character-form sip-nei-form}}))    ; Nei固有はここだけ
```

これで「レシピ（共通）を直せば全作品・全パネルに効く」「作品追加は anchors.edn +
写像表 + 数個の callback だけ」になり、共通層から `sip` という名前が消える。

### 実装フェーズ（小さく始める）

1. **`kami-mangaka-render-clj` 新設**: `render!` + `dims`/`aspect->dims` + `take-budget`
   を移送（純粋に汎用、~50行、依存は Java標準 + data.json）。`sip.render` は require。
   名前空間は `kami.mangaka.render`。
2. `emotion->tag` と `env-key` を **edn データ表に外出し**（コードから削除）。
   `env-key` のキャラ名 regex 除去をバグ温床退治として最優先。
3. `compose` を mappers 注入版へ（`kami.mangaka.render/compose`）。`sip.render` を facade 化。
4. `sip.page` を `kami-mangaka-page-clj`（`kami.mangaka.page`）として共通化（元が
   `mangaka.gftd.ai` 移植なので名実一致）。
5. ghosthacker を「廃止TS版の保存」でなく、**Tier1 を使う 2 つ目の作品**として再定義
   できるか検討（同じ engine + 別 anchors）。

## Status / implementation (2026-06-28)

`kami-engine` リポ内の兄弟 crate として landed（`../kami-engine-sdk-clj` と同列、
local/root deps のみ、west manifest 変更なし）。

- **`kami-mangaka-render-clj`**（Phase 1）— `kami.mangaka.render`: `dims` /
  `aspect->dims` / `framing` / `subject-count` / `take-budget` / `compose`
  （`:mappers` 注入版）/ `render!` を汎用化。**6 tests / 26 assertions green**。
- **`sip.render` の facade 化** — SIP 固有の `focal-character`(nei swap) / `env-key` /
  `emotion->tag`+`mood-tags` だけ残し、3 写像を `km/compose` に注入。datalevin の
  datom 層（anchors-tx/panel-tx/load!/render-one!/render-all!/-main）は不変。
  **SIP 全 suite 17 tests / 58 assertions green（regression なし）**。
- **`kami-mangaka-scene-clj`**（scene も clj に）— `kami.mangaka.scene`: Rust facade
  の public 型（Transform/CameraSpec/LightSpec/EnvironmentSpec + ShotGrammar/
  LightRole/Expression/FxKind）に忠実な EDN authoring DSL + three-point light
  preset + `expression-of` synonym 表 → `MangakaScene::from_jsonld` が読む JSON-LD
  へ projection（`->json`）。**6 tests / 34 assertions green**。
- 未着手: `kami-mangaka-page-clj`（`sip.page` 共通化）、ghosthacker の Tier1 再利用、
  `render_anchors.edn` の汎用/作品 split（style-lead 等の共通既定の外出し）。

## Consequences

- **正**: 共通レシピの一点修正が全作品に波及。作品追加コストが「データ + 写像」に縮む。
  3D 側（`kami-mangaka-scene`）と同じ綺麗な共通/個別境界に揃う。`env-key` の
  「キャラ名 regex」アンチパターンが構造的に消える。
- **負/コスト**: `kami-app-sip-clj` に新ライブラリ依存（`kami-mangaka-render-clj`）が
  増える。移送中は contract test（`MemStore ≡ DatomicStore` と同型に `sip.render ≡
  kami.mangaka.render + sip-mappers`）で regression を防ぐ必要がある。
- **置き場（決定）**: 3D 側が `kami-mangaka-scene` という独立ドメイン crate なので、
  対称性から `kami-mangaka-render-clj` / `kami-mangaka-page-clj` も **kami-engine ツリー
  配下の独立 crate**（`kami-mangaka-scene` の兄弟）とする。`kami-engine-sdk-clj` 内
  module には畳まない（SDK コアはエンジン抽象に予約、mangaka はドメイン）。
- **未決**: west manifest への新 crate 登録は GitHub API の単一 entry クリーン commit で
  行う（CLAUDE.md の manifest-workflow に従う、pin == repo HEAD を検証）。

## Alternatives Considered

1. **現状維持（`sip.render` のまま fork 運用）**: 作品追加ごとに compose を複製。
   レシピ修正が全 fork に伝播せず drift する。却下。
2. **作品固有値も含めて丸ごと共通化（巨大 anchors + 巨大 compose）**: 共通層に SIP の
   感情語・Nei・water-city を残したまま「汎用」を僭称することになり、命名の歪みが解消
   しない。却下。
3. **本ADR（汎用骨格を mangaka に出し、固有はデータ + 注入で逆転）**: 採用。

## References

- ADR-0002 (org-spirit-in-physics-comics: clj/Datomic render pipeline)
- ADR-2605141200 (mangaka 3D scene composition — Pregel + kami-mangaka-scene SDK)
- `kami-app-sip-clj/src/sip/render.clj` / `resources/render_anchors.edn`
- `ghosthacker/README.md`（廃止宣言）/ `ghosthacker/apps/web/src/lib/manga-layouts.ts`
