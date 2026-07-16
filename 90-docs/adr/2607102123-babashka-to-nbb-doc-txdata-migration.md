# ADR-2607102123: Babashka を `nbb` へ統一し、ADR／migration EDN を tx-data 化する

## Status

Accepted — 2026-07-10.

## Context

リポジトリには Babashka を前提としたスクリプト参照が、実行コードだけでなく
ADR、migration ledger、EDN の本文にも残っていた。運用スクリプトの正本を Node 上の
`nbb` に統一する方針と、履歴ドキュメントの実行手順が一致していなかった。

また、ADR／migration の EDN は map、既存の tx-data、構文不備の旧形式が混在していた。
そのままでは DataScript/Datomic に一律で transact できず、横断クエリの対象にならない。

## Decision

1. ADR／migration を含む `90-docs/` 内の Babashka コマンド参照を `nbb` に置換する。旧
   Babashka スクリプト参照は対応する `.cljs` に更新する。
2. `90-docs/adr/*.edn` と `90-docs/migration/*.edn` は、トップレベルを
   `[{ :db/id … }]` の entity-map tx-data に揃える。既存の Datomic schema transaction
   だけから成るファイルは有効な transaction として維持する。
3. 構文不備で機械的に変換できない旧 EDN は捨てない。` :adr/raw-edn` に原文を保存し、
   `:adr/normalization-status :raw-preserved` とエラー情報を持つ queryable entity として
   救済する。
4. `manifest/edn-datomize.cljs` を nbb 互換に保ち、属性定義は
   `manifest/schema.edn` へ追加する。Datomic 固有のインストール属性を使わないため、
   同じ tx-data を DataScript にも渡せる。

## Consequences

- ADR／migration の全文・コマンド例で Babashka コマンドは残さない。
- ADR 517 件と migration 5 件は DataScript/Datomic の transaction input として扱える。
- 救済された旧文書は raw 本文を保持するため、内容を失わずに `:adr/id` や
  `:adr/normalization-status` で検索できる。
- 入れ子の複合値は既存の正規化器に従い EDN 文字列 blob として保存される。属性単位の
  クエリは可能で、詳細値は必要に応じて `edn/read-string` で再構成する。

## Verification

```bash
git grep -I -n -w 'b''b' -- 90-docs
# no output

nbb --classpath . manifest/edn-datomize.cljs adr-dir 90-docs/adr
nbb --classpath . manifest/edn-datomize.cljs adr-dir 90-docs/migration
```

The normalization pass reports zero remaining parse errors.
