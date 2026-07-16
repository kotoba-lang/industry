# ADR-2607150200: cloud-murakumo-market-intel に SEC EDGAR 上場企業ファンダメンタルズの ingestion pipeline を追加し、market-analyst の grounding-gate を拡張する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

ADR-2607150100 と対になる決定。オーナーの指示「公開企業などは一定間隔で
情報をsource, irからingestしてEDNにしてDatomic, DataScriptでqueryできる
ように」に対応する。`cloud-murakumo-market-intel`（ADR-2607122000、Visual
Capitalist "All of the World's Money and Markets" 元ネタの14 factの静的
2022年スナップショット、Datomic(langchain.db)⊣DataScript 2バックエンド）に、
実データソースからの動的ingestionを初めて追加する。

事前確認済みのオーナー判断: 対象は**SEC EDGAR全上場企業(8000社超)**、
定期ingestionで生成した新データは**サーバ側merge APIで自動マージ**する運用。

## Decision

### 1. `data/schema.edn` — 新規 `:company/*` 属性群 + `:market/isic`

既存の flat-EAV/refなし方針を継続。新規属性: `:company/cik`
（10桁ゼロ埋め文字列、`:db.unique/identity`）、`:company/name`,
`:company/ticker`, `:company/sic`（SEC直接報告値）, `:company/isic`
（SIC→ISICクロスウォーク由来、optional）, `:company/isic-confidence`
（`:isic`を書く時は常に`:derived`——2段クロスウォークは断定しない誠実さ）、
`:company/revenue-usd`, `:company/assets-usd`, `:company/net-income-usd`,
`:company/shares-outstanding`, `:company/fiscal-period`, `:company/as-of`,
`:company/confidence`（XBRL直接値のため常に`:measured`）, `:company/source`,
`:company/source-url`, `:company/fetch-etag`（ingestion pipeline専用の
運用メタデータ、sourced factではないと明記）。既存`:market/*`には
`:market/isic`（string, optional）を1属性追加し、14 factのうち対応が明確な
5カテゴリにbackfillした（曖昧な対応は付けない誠実さを継続）。

### 2. `src/market_intel/feed.cljc` — SEC EDGAR 実HTTP接続

`cloud-itonami-isic-6311/src/marketdata/feed.cljc`（ECB/EIA/FRED接続の
先例、`#?(:clj ...)` I/O分離・env caller注入・operation-ready request map
を返す設計）を同型で踏襲。`fetch-company-tickers`
(`sec.gov/files/company_tickers.json`)、`fetch-company-facts`
(`data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json`)、`fetch-submissions`
(`data.sec.gov/submissions/CIK{cik}.json`)。`User-Agent`ヘッダ必須
（env `SEC_EDGAR_USER_AGENT`、caller注入）、10 req/秒のレート制限。

**実装中に発見・修正した実データ由来の不具合**（想定ベースの実装では
見つからなかったもの、実際にlive fetchして初めて判明）:
- タグ選択の**stale-tag bug**: 「優先順位リストの先頭から見つかった値を
  無条件採用」だとApple社のASC 606適用後に更新が止まった旧`us-gaap:Revenues`
  タグを「最新値」として誤って採用してしまう。全taxonomy tagのうち
  **最新日付のものを選ぶ**方式に修正し、優先順位リストは同日タイの
  tie-break専用に限定。
- **anchor-date bug**: `dei:EntityCommonStockSharesOutstanding`の
  cover-page基準日がrevenue/assets/net-incomeの決算期末日より後にずれる
  ケースがあり、`:company/as-of`は財務諸表系conceptのみを基準にするよう修正。
- **roster順序bug**: `company_tickers.json`の数値文字列キーJSONオブジェクトは
  Clojureで`PersistentHashMap`にパースされ、iteration順がSECのファイル順
  ではなくhash順になる（実測確認: `(:0 :4 :1 :2 :3)`のような順で返る）。
  数値indexでsortして修正。
- **conditional GETの実際の挙動**: `data.sec.gov`のcompanyfacts/submissions
  APIは ETag/Last-Modified を一切返さず `If-Modified-Since` も無視する
  （常に200を返す、実際に同一CIKを2回fetchして確認済み）。一方
  `company_tickers.json`（別ホスト）は実際に304を返す（これも実測確認）。
  この非対称性は`feed.cljc`のdocstring・README・ingestパイプラインに
  明記した。`:company/fetch-etag`の配線自体は残したが、現状
  data.sec.gov側2エンドポイントに対してはリクエスト数削減効果が無いことを
  正直に記録。
- **CIK型不整合**: `companyfacts`のtop-level `cik` は素の整数、
  `submissions`は既にゼロ埋め文字列。`:company/cik`はどちらの自己申告値も
  使わず、呼び出し側が渡す正準値から常に構築するようにした。

### 3. `src/market_intel/ingest.clj` — JVM専用ingestionエントリポイント

`seed.clj`と同じ「host固有ローダーが`.cljc` storeにplain dataを渡す」分離を
継続。書き込み前サニタイズ（必須フィールド欠落・負のrevenue/assets・HTTP
エラー率閾値超過のいずれかでingestion全体を失敗させ何もcommitしない、
isic-6311のtolerance-gateと同じ精神をLLM無しの決定論的パイプラインに適用）。

### 4. `.github/workflows/company-facts-refresh.yml` — 週次cron + 自動マージ

diffが`data/company-facts.edn`のみ(schema/src変更なし)ならサーバ側merge API
で自動マージ、それ以外は安全側に倒し失敗させる設計。

**重要な運用上の制約**: `gftdcojp` orgはGitHub Actionsが**組織全体で無効化**
されている（`gh api orgs/gftdcojp/actions/permissions` で確認済み）ため、
このworkflowファイルは**現状トリガーされない**。定期実行を実際に機能させる
には、GitHub Actions以外の経路（このClaude Codeセッション基盤のスケジュール
実行機構、または`gftdcojp`のActions有効化のいずれか）が別途必要——本ADRの
follow-upとして明記し、workflowファイル自体は将来Actionsが有効化された時
そのまま機能する設計として残す。

### 5. `cloud-murakumo-market-analyst` — grounding-gateの拡張

新規 `src/analyst/citation.cljc`（`resolve-citation`）— `:cites`の各idを
`market-intel.store/category`→ダメなら`company-of`の順で解決を試みる
（型で早期分岐せず両方試すフォールバック方式、将来id形式が変わっても
静かに壊れない設計）。正規化fact shape `{:kind :id :label :value-usd
:as-of :confidence :source :fact}`を返す。`src/analyst/policy.cljc`の
grounding/confidence-transparency/staleness の3チェックすべてと、
`src/analyst/advisor.cljc`の4呼び出し箇所（compare/rank/report/llm-advisor）
をこのヘルパー経由に統一。新しいanalysis operationは追加していない——
既存の`:market/compare`/`:market/rank`/`:market/report`のままで
market/company混在citationを扱える。

## Consequences

- (+) `data/company-facts.edn` に実在28社（SEC EDGAR実データ、live-verified）
  を初期投入済み。8000社超のフル投入は最初の`workflow_dispatch`実行
  （Actions有効化後）に委ねる、と正直にスコープを分けた。
- (+) `market-intel.store`に`companies`/`company-of`/`companies-by-isic`
  + `company-pull-pattern`追加。`LangchainDbStore`/`DataScriptStore`両方で
  company entityのクエリ一致を確認済み（14個の既存`:market/*`カテゴリと
  同一connに共存しても影響が無いことも確認済み）。
- (+) market-analystのgrounding-gateがmarket/company混在citationを正しく
  通過/拒否することを実データ（Apple Inc., CIK `0000320193`）で確認済み
  ——実在market category vs 実在company factの比較commit、および
  fabricated cikでのHARD `:grounding`違反の両方を実地確認。
- (+) `clojure -M:test`（market-intel）: 21 tests / 116 assertions。
  `clojure -M:dev:test`（market-analyst）: 18 tests / 64 assertions。
  いずれも0 failures、lint 0 errors。
- (-) `:company/market-cap-usd`はR0では持たない——XBRL companyfactsには
  株価が無く時価総額算出には価格フィードが要る。捏造を避け
  fundamentals(revenue/assets/net-income/shares outstanding)のみに
  意図的に限定。将来`cloud-itonami-isic-6311`のライセンス済み株価フィードと
  `kotoba.securities.pricing`型の橋渡しで派生計算する経路をfollow-upとする。
- (-) `:company/isic`はSIC→ISICクロスウォークのbest-effort値であり、
  精度は`:derived`扱いで正直に低く申告している。
- (-) **週次cronは現状発火しない**（`gftdcojp` orgのActions無効化、上記）。
  実際の定期実行機構の確立が明確なfollow-upとして残る。
- superproject への反映: 本ADR + `manifest/west.yml`の
  `cloud-murakumo-market-intel`/`cloud-murakumo-market-analyst`両entryの
  pin前進のみ（`--entry`ずつの最小diff、fast-forward検証OK）。

## 代替案と不採用理由

- **SEC daily/quarterly full-indexファイルをパースしてdelta検出**: 当初
  検討したが、固定幅テキストのパース複雑性・正確性の不確実性に対し、
  実装してみるとdata.sec.gov自体がconditional GETを一切honorしないことが
  判明し、どのみち削減効果を得るには別の設計が要ることが分かった。今回は
  「常に全社を叩くが、company_tickers.jsonレベルでは304を活用できる」
  という現実的なスコープに留め、過剰に複雑な仕組みを作らなかった。
- **`:company/market-cap-usd`を株価APIから補完して今回入れる**: 新たな
  価格フィードソースの選定・ライセンス問題を持ち込むことになり、
  isic-6311の既存の「operatorがライセンス済みフィードを供給する」境界と
  重複・矛盾しかねない。fundamentals-onlyの誠実スコープを優先し不採用。
- **時価総額算出のため今回isic-6311と直接連携する**: `kotoba.securities
  .pricing`が既にisic-6311への橋渡しパターンを持っているため、将来
  同型の橋渡しをmarket-intel側にも作ることは可能だが、スコープが本ADRの
  対象(SEC EDGAR fundamentals ingestion)を超えるため見送った。

## References

- `orgs/gftdcojp/cloud-murakumo-market-intel` PR #1（merged、
  commit `fe52cbbb0b2b881d6339190297b67037216f440c`）
- `orgs/gftdcojp/cloud-murakumo-market-analyst` PR #1（merged、
  commit `5d8303965f842832a5b6ea24d3105e67d659b73b`）
- `src/market_intel/feed.cljc` + `ingest.clj` + `store.cljc`
  （`companies`/`company-of`/`companies-by-isic`）
- `src/analyst/citation.cljc`（`resolve-citation`）+ `policy.cljc` +
  `advisor.cljc`
- `90-docs/adr/2607122000-cloud-murakumo-market-intel-dataset.md`
  （データセット新設、本ADRが拡張する対象）
- `90-docs/adr/2607122030-cloud-murakumo-market-analyst-actor.md`
  （actor新設、本ADRが拡張するgrounding-gate）
- `90-docs/adr/2607150100-cloud-itonami-isic-8291-gleif-lei-ingestion.md`
  （対になる決定、GLEIF LEI on-demand接続）
