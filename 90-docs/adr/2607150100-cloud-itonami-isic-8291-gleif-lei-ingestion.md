# ADR-2607150100: cloud-itonami-isic-8291 に GLEIF LEI Registry を第2の live-data 接続として追加する

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

オーナーから「全世界の money and market をコード化・収集する repo」の発展として、
`cloud-itonami-*` の組織・業種・LEI entity に実データを追加する指示を受けた。
調査の結果、法人実体（company/official）を保持する唯一のハブは既に
`cloud-itonami-isic-8291`（dossier、ADR-2607110400、ADR-2607121000 の Wave 1
設計）であり、`src/dossier/facts.cljc` の出典カタログ（当時20エントリ）+
`src/dossier/companies_house.clj`（UK Companies House への唯一の実HTTP接続、
`:live-capable? true`）+ `src/dossier/live_store.cljc`（`LiveGbrStore` — local
seed データ優先、無ければ live fallback、キー未設定でも undecorated local
storeと同じ挙動でクラッシュしない decorator）という、実データ接続の確立済み
パターンを既に持っていることが分かった。

GLEIF（Global Legal Entity Identifier Foundation）はISO 17442 LEIの発行元
自身が提供する無料・APIキー不要の公式レジストリで、全世界2.7M+ entityを
カバーする——`facts.cljc` の既存20エントリがいずれも特定法域(日本/UK/独/エストニア
/米/EU/仏/中/印/韓/豪/加/伯 等)に紐付くのに対し、GLEIFは唯一「特定国に紐付かない
supranational」ソースであり、文字通り「全世界」を満たす初のエントリになる。

## Decision

`companies_house.clj`/`live_store.cljc` と**同型**のパターンで GLEIF を追加した:

1. **`src/dossier/gleif.clj`**（新規）— `org.httpkit.client`+`jsonista`、GLEIF
   `/lei-records` API（APIキー不要）。フィールドパスは実装前に実際に`curl`で
   本番APIを叩いて確認済み（捏造しない規律）。`find-lei-by-name` は GLEIF自身の
   `filter[entity.legalName]` がfuzzy/full-textであることを踏まえ、
   `companies-house/find-company-by-name` と同じ「完全一致のみ採用」規律を
   再適用。`->company` は `dossier.store` の company shape にマップし、
   `:id` は `lei-<LEIコード>`（`gbr-<company_number>`と同じ役割の接頭辞）、
   `:jurisdiction` はISO3166 alpha-2→alpha-3の小さなstatic mapで変換
   （`kotoba-lang/iso3166`にalpha-2→alpha-3ユーティリティが存在しないことを
   確認済み、無変換の国コードを捏造しない）。
2. **`src/dossier/store.cljc`** — company entityに新規一級属性 **`:lei`** を追加
   （DatomicStoreの`company->tx`/`pull->company`/`company-pull`/schema、
   demo dataにも明示的にdemoと分かる`:lei`値を1件追加）。
3. **`src/dossier/live_store.cljc`** — 新規 `LiveLeiStore` decorator（GLEIF
   fallbackは`company`/`company-by-name`のみ、GLEIFには役員データが無いため
   `officials-of`は素通し）。既存`LiveGbrStore`は無変更、`live-store`の
   0/1引数コンストラクタが両live sourceをchainするよう変更、既存の2引数
   `[local ch-fetch-fn]` arity（既存Companies Houseテストが使用）は無変更。
4. **`src/dossier/facts.cljc`** — `:global-gleif-lei` をcatalogの21件目として
   追加（`:jurisdiction :un`、`:un-sc-consolidated-list`と同じsupranational
   scoping）。既存の「20件で打ち止め、超過はfacts-testのguardを再交渉」という
   curation guardを、21件目の正当な理由（全世界を文字通りカバーする初のソース）
   とともに `<= 21` へ改定した——安易な緩和ではなく理由付きの1件追加。
   `coverage`関数の`:note`（"6 public primary sources"のまま stale だった）も
   実数に修正。

**スコープ（意図的な限定）**: GLEIF全2.7M件のbulk ingestionは行わない。
`cloud-murakumo-market-intel`（ADR-2607150200）がingestするSEC EDGAR上場企業
ユニバースに対応する範囲での**on-demand live lookup**のみ。全世界・全業種への
GLEIF sweepおよびbulk事前ロードは明示的なR1+ follow-upとし、この ADR の
スコープ外とする——isic-6311の「R0は自由公式ソース3種のみ」と同じ、捏造しない
誠実スコープの流儀。

**cronでの定期実行は追加しない**。既存のCompanies House接続も定期実行を
持たず、on-demand live-lookupのみで完結している——法人registryデータは
「LLMが提案してGovernorが承認するfact」ではなく「公式レジストリを都度引く」
性質のものであり、`operation.cljc`/`policy.cljc`（DisclosureGovernor）は
変更していない。Governorが関わるのは`:disclosure/*`のクエリ側のみ。

**`facts.cljc`に既存の`:usa-sec-edgar`エントリ**（`:class :regulatory-filing`、
`:covers #{:company-registry :officers-psc}`）への live client 実装は本ADRの
スコープ外——SEC EDGARという同じ情報源でも、8291が使うのは登記/役員情報
（未実装のまま）であり、ADR-2607150200がingestするXBRL財務ファクト
（revenue/assets等）とはエンドポイントも用途も別。両者を混同しないよう
実装コメント上も明確に分離した。

## Consequences

- (+) `cloud-itonami-isic-8291` のR0出典カタログが20→21件、うち
  live-capableな実データ接続が1→2件（Companies House + GLEIF）。
- (+) GLEIF は「特定法域に紐付かない全世界カバレッジ」を持つ初のソースで、
  「全世界のmoney/marketをコード化」という当初の目的に文字通り資する。
- (+) 実接続確認: Apple Inc.の実LEI `HWUPKR0MPOU8FGXBT394` を
  `dossier.gleif/live-http-fn`→`lei-record`→`->company`の実コードパスで
  取得し、`:jurisdiction :usa` `:registration-no "806592"` `:status :active`
  に正しくマップされることを確認済み。`COMPANIES_HOUSE_API_KEY`未設定でも
  `(live-store)`がGLEIF経由で解決し、local data(`co-100`)は変わらず返る
  ことも確認済み。
- (+) 副次的に、`.github/workflows/ci.yml`のcheckoutパスが
  `com-junkawasaki/langgraph-clj`/`langchain-clj`(rename前の旧名)を参照した
  ままで2026-07-10以降mainの全commitで`test` CI jobが壊れていたバグを発見・
  修正した（`kotoba-lang/langgraph`/`langchain`に修正、`gh run list`で
  過去の壊れた実行履歴を確認済み）。
- (+) `clojure -M:dev:test`: 83 tests / 401 assertions、0 failures。
  `clojure -M:lint`: エラー0・警告0。
- (-) GLEIFはofficerデータを持たないため、`LiveLeiStore`は`officials-of`に
  カバレッジを追加しない（Companies Houseのみがofficer情報を持つ）。
- (-) SIC/ISIC等の業種コードとの紐付けはGLEIFレコード自体には無い
  （GLEIFは法人identity registryであり業種分類は持たない）——業種との
  接続はADR-2607150200側の`:market/isic`/`:company/isic`で別途行う。
- superproject への反映: 本ADRのみ。`cloud-itonami-isic-8291`は既存の
  `cloud-itonami-{ISIC}`慣例によりstandalone plain-gitで
  `manifest/repos.edn`/`west.yml`には登録されない。

## 代替案と不採用理由

- **GLEIF全件(2.7M)を事前bulk ingestしてDatomicに載せる**: このactorの
  既存設計思想（local優先+on-demand live fallback、キー無しでも壊れない
  誠実degradation）と根本的に異なる新パターンを持ち込むことになり、
  Companies Houseとの一貫性が失われる。またbulk ingestは今回のスコープ
  （SEC filer対応分）を大きく超える。不採用。
- **GLEIF接続にもcron定期実行を付ける**: 法人registryのlookupは「都度引けば
  最新」の性質であり、Companies Houseが定期実行なしで正しく機能している
  ことがそれを裏付ける。定期実行が必要なのは「値が変動し続け、都度引く
  コストが高い」公開企業の財務ファクト（ADR-2607150200）の方であり、
  混同すべきでない。不採用。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8291` PR #1（merged、
  commit `13ad354df92a2f944016ab136d733181ced8d68c`）
- `src/dossier/gleif.clj` + `src/dossier/live_store.cljc`
  （`LiveLeiStore`）+ `src/dossier/facts.cljc`（catalog 21件目）
- `src/dossier/companies_house.clj`（直接の手本、ADR-2607110400 addendum 5）
- `90-docs/adr/2607110400-cloud-itonami-isic-8291-corporate-compliance-intelligence-actor.md`
  （actor新設、法人実体を保持する唯一のハブという設計の原点）
- `90-docs/adr/2607121000-cloud-itonami-global-isic-isco-reverse-toposort-plan.md`
  （8291をWave 1の中核と位置づける設計）
- `90-docs/adr/2607150200-cloud-murakumo-market-intel-sec-edgar-company-facts.md`
  （対になる決定、SEC EDGAR財務ファクトの定期ingestion + market-analyst
  grounding-gate拡張）
