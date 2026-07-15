# ADR-2607151950: `etzhayyim/gov.sec.edgar` — SEC EDGAR XBRL companyfacts の生レスポンスを保全する DataLad アーカイブを新設

**Status**: accepted
**Date**: 2026-07-15
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

ADR-2607150200で`cloud-murakumo-market-intel`にSEC EDGAR company-fundamentals
ingestion pipelineを追加したが、同ADRの調査で確認した通り、このpipelineは
「fetch → parse → 要約fact(`:company/*`)のみ永続化、生レスポンスは破棄」する
設計だった。オーナーから「永続化して」との指示を受け、生レスポンス(raw JSON)を
別途保全するアーカイブが必要と判断した。

事前にオーナーから「isic-8291や既存コードに同種のものが無いか」と問われ調査した
結果、**既存の仕組みは無かった**（`cloud-itonami-isic-8291`はon-demand
live-lookupのみでアーカイブしない設計、`cloud-murakumo-market-intel`は
fetch直後にparseし生レスポンスを保持しない設計）。一方、`etzhayyim/
org.worldbank.api`・`org.un.unstats`・`org.ourworldindata`という、外部APIの
生JSON snapshotをDataLad datasetとして保全する既存パターンがこのworkspaceに
既に確立されていた。

## Decision

`org.worldbank.api`と**構造を完全に一致**させた新規standalone plain-git repo
`etzhayyim/gov.sec.edgar`（public、`repos.edn :orgs :visibility`の
etzhayyim既定）を新設した。

- `.gitattributes`は`org.worldbank.api`と同一の3行（`annex.backend=MD5E`、
  `.git*`除外、`annex.largefiles=((mimeencoding=binary)and(largerthan=0))`）。
  **`datalad create -c text2git`規約により、JSON等テキストファイルは通常の
  gitオブジェクトとしてそのままcommitされ、annex化されない**——バイナリのみが
  B2行きになる設計で、このrepoでも同じ挙動を意図的に踏襲した。
- `raw/companyfacts/CIK<10桁ゼロ埋め>.json`に、`cloud-murakumo-market-intel`
  の`data/company-facts.edn`に既に実在する28社と**同一CIKセット**の生
  companyfacts JSON（`data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json`)を
  加工・要約せずそのまま保存した。全28社ともHTTP 200で取得成功。
- `raw/source-catalog.edn`（`org.worldbank.api`と同型のshape）に、CIK・
  ティッカー・企業名・path・SHA-256・取得日を記録。SHA-256は全28件、実ファイルから
  再計算し一致を確認済み（捏造なし）。
- README.mdに、`cloud-murakumo-market-intel`との関係（要約層は別repo）と、
  この repo との自動連携がまだ実装されていないことを明記。

### B2 special remote は今回確立できなかった（正直に報告）

`scripts/datalad-b2-init.cljs`を実行したが、`manifest/repos.edn`に登録されて
いる唯一のbucket(`gftdcojp-m365-annex`)は既に`m365-archive`の special remote
（別UUID）に占有されており、git-annexが新規datasetによるbucket claimを拒否した。
確認したところ、環境で解決できたB2キーはbucket-scoped（account-level
Master keyは1Passwordにしか無く、非対話セッションの`op read`がhangしたため
今回は解決できなかった）。**`m365-archive`のannex branch履歴を`--sameas`で
巻き込んでこの無関係なpublic archiveに混ぜる回避策は意図的に取らなかった**
（無関係なsensitive datasetのmetadataを混在させるべきではないため）。

結果、`datalad push --to b2`は「Unknown push target」で失敗した。**ただし
これは実害が無い**——`text2git`規約によりJSONは既にすべてplain gitオブジェクト
としてcommit・pushされており、B2はそもそも空振り(no-op)になる想定だった。
さらに確認したところ、**手本にした`org.worldbank.api`自体にも稼働中のB2
special remoteは設定されていない**——このworkspaceのtext2git系DataLad
アーカイブ群は、実運用上はGitHub pushだけが実際の永続化経路になっている
ことが判明した。B2 special remoteの確立（新規bucket作成、account-level
Master keyの解決）は明示的なfollow-upとする。

## Consequences

- (+) SEC EDGAR XBRL companyfactsの生レスポンス28社分が、加工前の生データとして
  再現可能な形で永続化された。SHA-256による改ざん検知も可能。
- (+) `org.worldbank.api`と同じ構造のため、このworkspaceの既存パターンとの
  一貫性を保った。
- (+) B2 special remote不在という限界を隠さず記録した——`org.worldbank.api`
  含む既存repoにも同じ限界があることも合わせて判明・記録。
- (-) `cloud-murakumo-market-intel`のingestion pipeline（週次クラウド
  routineでスケジュール済み、ADR-2607150200）とのライブ連携は**今回は実装
  していない**——今後の実行でこのアーカイブへ自動的に生データが追加される
  ようにはなっていない。明示的なfollow-up。
- (-) 28社分のみ。SEC EDGAR全上場企業(8000社超)へのフル投入も明示的な
  follow-up。
- (-) B2 special remote未確立のため、GitHub以外の冗長化経路が無い（ただし
  上記の通り既存の同型repoも同じ状態）。
- superproject への反映: 本ADRのみ。`etzhayyim/gov.sec.edgar`は
  `org.worldbank.api`等と同じ慣例によりstandalone plain-gitで
  `manifest/repos.edn`/`west.yml`には登録しない。

## 代替案と不採用理由

- **`cloud-murakumo-market-intel`の`data/`配下に生JSONも一緒に置く**:
  market-intelは「要約factをDatomic/DataScriptでqueryできるようにする」ことが
  目的の薄いdatasetであり、生JSON(28社で115MB)を同居させると本来の目的から
  外れ、`org.worldbank.api`→`global-energy-datoms`型の「raw/統合を別repoに
  分離する」既存パターンとも矛盾する。不採用。
- **`m365-archive`のB2 special remoteをこのrepoでも再利用(`--sameas`)**:
  無関係でsensitiveな既存datasetのannex metadataに、無関係なpublic archiveを
  混在させることになり、境界が汚染される。1Password Master keyの非対話解決
  問題を回避する誘惑があったが、正直に「未確立」と報告する方を選んだ。
- **B2アップロードが必須という前提で作業を止める**: text2git規約下では
  JSON自体は既にgit historyで永続化されており、B2はバイナリ用の追加冗長化
  レイヤーに過ぎない。B2無しでもrepoの主目的（生データの再現可能な保全）は
  達成されているため、B2確立を待たずにこのADRを起票した。

## References

- `etzhayyim/gov.sec.edgar`（commit `deacca7`、`raw/companyfacts/` 28ファイル
  + `raw/source-catalog.edn`）
- `orgs/etzhayyim/org.worldbank.api`（構造の直接の手本）
- `90-docs/adr/2607150200-cloud-murakumo-market-intel-sec-edgar-company-facts.md`
  （本ADRの前段、要約factのみ保存する設計の原点）
- `scripts/datalad-b2-init.cljs` + `scripts/b2-creds.cljs`（B2セットアップの
  正経路、今回はbucket占有により未完了）
