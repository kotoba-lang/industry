# ADR-2607112200: cloud-itonami-isic-6311 に実フィード接続を追加し、kotoba-lang/securities から実際に消費させる

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

ADR-2607111500 で `cloud-itonami-isic-6311`(マルチアセット market-data
集約・ホスティング actor)を新設したが、当時の `marketdata.llm` は
デターミニスティックなモックのままで、`:quote/ingest` は常に呼び出し側が
手渡した price/source をそのまま通すだけだった。「収集(collect)・保持
(hold)・更新(update)」のうち「収集」が実際の外部フィードに接続されて
いなかった。加えて、この actor の R0 出典カタログ(ECB FX参照レート・US
EIA・FRED)が実在するにもかかわらず、それを実際に消費する側
(`kotoba-lang/securities`)にも配線が無かった — `securities` の README は
「no real market data... an operator supplies their own licensed price
feed」と明記しており、この actor がまさにその "operator が供給する OSS
選択肢の一つ" になり得るはずだった。

オーナーから「実データ接続を進め、securities への実配線も」との指示を受け、
両方を実装した。

## Decision

### 1. `src/marketdata/feed.cljc` — R0 自由公式ソース3種への実HTTP接続

ECB euro FX reference rates(無キー)・US EIA Open Data・FRED
Case-Shiller HPI の3ソースそれぞれに `fetch-*` 関数を追加した。各関数は
I/O + パースまで行い、`marketdata.operation/build` にそのまま渡せる
`:quote/ingest` request map を返す(生のHTTPレスポンスを渡さない)。取得
したデータも**通常のリクエストと全く同じ経路**で MarketDataGovernor を
通る — フィードが壊れた/桁を間違えた値を返しても、tolerance-gate/
source-provenance-gate が普段どおり hold にする(実際に `clojure
-M:feed:dev:run-feed` で ECB の生きたエンドポイントに接続し、ガバナンス
込みで動作することを本セッションで確認済み)。

`langchain.jvm` の http-kit/jsonista reference impl と同じ JVM専用
(`#?(:clj ...)`)シームを踏襲し、これらの依存は `deps.edn` の
`:test`/`:feed` alias にのみ存在し `:deps` には入れない(kotoba wasm /
clojurewasm / ClojureScript / nbb 経路を汚染しない)。`cross-rate` と
`*-ingest-request` 系のシェイピング関数は BigDecimal `with-precision`
ではなく plain double 演算にし(`marketdata.policy` の tolerance-gate が
既に採用している判断と同じ)、ポータブルな `.cljc` のまま保った。API キー
(`EIA_API_KEY`/`FRED_API_KEY`)は `marketdata.feed` 自体からは一切 env を
読まない — 呼び出し側(新設した `marketdata.feed-demo`、素の `.clj`)が
読んで明示的に渡す、`scripts/b2-creds.cljs` と同じ injected-credential の
作法。パース関数(XML/JSON → data)は実際にライブ取得した ECB レスポンス
をフィクスチャとして使い、オフラインでユニットテストする
(`test/marketdata/feed_test.clj`、捏造スキーマ禁止の作法どおり)。

### 2. デフォルト phase の fail-open 修正(並行セッションの修正を継承)

実装作業中、別の並行セッションが `cloud-itonami-isic-6311` の
`default-phase`(旧: `3` supervised-auto)が「`:phase` を省略した呼び出し
が黙って最大自律性を得てしまう」fail-open バグであることを検出・修正
(`1` assisted-ingest へ)しており、既に `main` にマージ済みだった
(`gftd-talent-actor` とその多数の兄弟 actor で同一パターンが検出・修正
された流れの一環)。本セッションはこの修正を正として引き継ぎ、影響を
受けた `test/marketdata/policy_contract_test.clj` の governor-focused
テスト群を `:phase 3` を明示するよう調整し(`phase_test.clj` の既存の
parameterization と同じ作法)、`marketdata.sim` のデモも `:phase 3` を
明示して完全な governed contract を示すよう修正した。

### 3. `kotoba.securities.pricing` — securities からの実消費

`kotoba-lang/securities`(positions/trades/fund-NAV の純粋データ計算
ライブラリ。ネットワーク/IOを一切持たない設計方針)に、
`cloud-itonami-isic-6311` への**任意の**橋渡し namespace
`kotoba.securities.pricing` を追加した。`reference-price`/
`position-with-market-price` は実際の `OperationActor` に対して
`:disclosure/query` を送る — 他の subscriber と全く同じ経路で、tenant/tier
契約が無ければ nil、対象銘柄が取引停止中なら人間承認へ escalate して nil
(「internal caller だから governor を迂回する」抜け道は無い)。

`cloud-itonami-isic-6910` の `formation.corporate-intel` →
`cloud-itonami-isic-8291` の cross-reference と同型の分離を踏襲した:
`kotoba.securities.pricing` 自身だけが `marketdata.*` を require し、
`kotoba.securities`/`export`/`ui` コアは本 namespace への compile-time
依存を一切持たない。`deps.edn` の `:deps` にも同型に(alias でゲートせず)
追加した — `:local/root` の classpath entry 自体は IO ではないため、
「never require しない限りネットワーク/IOなし」という securities の
README の主張は変わらない。

## Consequences

- (+) `cloud-itonami-isic-6311` の「収集」が実データ接続で裏付けられた —
  ECB の生きたエンドポイントへの実接続を本セッションで確認済み(EIA/FRED
  はこのサンドボックスに無料APIキーが無いため未検証、`SKIPPED` として
  正直に報告するだけで偽装しない、`marketdata.feed-demo` の設計どおり)。
- (+) `kotoba-lang/securities` が初めて実際に `cloud-itonami-isic-6311`
  を消費する側になり、ADR-2607111500 で謳っていた「`:market-data`
  capability として他 blueprint から wholesale 消費できる」が実証された。
- (+) `cloud-itonami-isic-6311`: `clojure -M:dev:test` 46 tests / 177
  assertions、0 failures。`clojure -M:lint` エラー0・警告0。
- (+) `kotoba-lang/securities`: `clojure -M:test` 19 tests / 37
  assertions(旧24)、0 failures。`clojure -M:lint` エラー0・警告0。
- (-) EIA/FRED の実キーでのライブスモークテストは本セッションでは未実施
  (キー無し)。`docs/operator-guide.md` に実行手順を明記済みで、operator
  が自分のキーで検証する前提。
- (-) 株式・暗号資産・大半のコモディティは依然 operator の feed-license
  登録が必須(R0 の自由公式ソースは3種のみという ADR-2607111500 の
  スコープは変わらない)。
- superproject への反映: 本 ADR + `kotoba-lang/industry` は変更なし(既に
  実装昇格済み)、`manifest/west.yml` の `securities` entry pin 前進のみ
  (`--entry securities` の最小 diff)。`cloud-itonami-isic-6311` /
  `kotoba-lang/securities` はいずれも各リポジトリ側で直接 push/サーバ側
  マージ済み(前者は standalone plain-git、後者は west 管理の共有 checkout
  のため isolated worktree + `gh api .../merges` の正経路で着地)。

## 代替案と不採用理由

- **`kotoba.securities.pricing` を alias でゲート(`:deps` に直接置かない)**:
  securities の「no network, no I/O」という README の主張をより厳密に
  守れるが、`:local/root` の classpath entry 自体は IO ではなく、
  `cloud-itonami-isic-6910` の既存 precedent(`:deps` に直接追加、1
  namespace だけがrequire)と一貫性が取れなくなる。precedent との一貫性を
  優先し、`:deps` へ直接追加した。
- **`marketdata.feed` を securities 側からも直接 require させる**: 二重の
  依存境界(securities → isic-6311 → feed)を作らず securities から
  直接 HTTP を叩く案もあったが、それでは governor を経由しない「生の価格」
  を securities に持ち込むことになり、`kotoba.securities.pricing` が
  `OperationActor` の `:disclosure/query` を経由する設計の意味(tenant/tier
  契約・halted-instrument gate を必ず通す)が失われる。不採用。
- **EIA/FRED をこのセッションでキー取得までして検証**: サンドボックス外部
  へのアカウント登録が必要でスコープ外。正直に `SKIPPED` として報告し、
  operator 自身の検証手順として `docs/operator-guide.md` に明記するに
  とどめた。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-6311/src/marketdata/feed.cljc` +
  `feed_demo.clj` + `test/marketdata/feed_test.clj`
- `orgs/kotoba-lang/securities/src/kotoba/securities/pricing.cljc` +
  `test/kotoba/securities/pricing_test.cljc` + README.md
  「Consuming cloud-itonami-isic-6311 for reference pricing」節
- `90-docs/adr/2607111500-cloud-itonami-isic-6311-market-data-actor.md`
  (本 ADR の前段、actor 自体の新設)
- `orgs/cloud-itonami/cloud-itonami-isic-6910` の
  `formation.corporate-intel` → `cloud-itonami-isic-8291` cross-reference
  (`kotoba.securities.pricing` の依存分離パターンの直接の手本)
