---
id: adr-2606272213-ddl-clj-sql-schema-migration
title: "ADR-2606272213: ddl-clj — SQL DDL(CREATE TABLE) を EDN/Clojure データとして扱う再利用ライブラリ。model(table-keyed schema) + validate + 自前 minimal CREATE TABLE I/O + 純粋 schema-diff→migration。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - リレーショナル DDL(スキーマ)を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - ddl-clj の責務境界(モデル/検証/SQL I/O/diff→migration)の設計
  - 大容量・非可搬な SQL パーサ/ORM 依存を避けるための minimal CREATE TABLE reader/emitter 戦略
  - スキーマ成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/com-junkawasaki/ddl-clj                        # 本 ADR のライブラリ
  - orgs/com-junkawasaki/bpmn-clj                       # 同型の再利用 kernel(model+validate+I/O の先例)
  - orgs/com-junkawasaki/dmn-clj                        # 姉妹の決定表 kernel
supersedes: []
superseded_by: []
---

# ADR-2606272213: ddl-clj — SQL DDL を EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。migration を実 DB に適用する actor 側は別 PR）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

データベースのスキーマ(DDL)を成果物として扱う需要があるが、既存の migration ツールは
(1) 言語/フレームワーク固有の重い依存(ORM・migration ランナー)を引き、(2) スキーマを
SQL 文字列や独自 DSL の不透明な状態に閉じ込め、Clojure の `assoc`/`diff`/Datomic と
噛み合わない。本リポの方針(shallow 既定・大容量 dep 回避・portable `.cljc` — bpmn-clj /
dmn-clj の先例)に沿う、**SQL スキーマを素の EDN として扱う軽量ライブラリ**が無かった。

## Decision

`com-junkawasaki/ddl-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`(JVM/CLJS/SCI)。責務を4層に分離する:

- **`ddl.model`** — DDL-as-EDN の正準モデル。schema は **table-name-keyed map**(O(1)
  参照)、columns は document order を保つ ordered vector、primary-key / foreign-keys は
  first-class フィールド。threadable builder(`schema`/`table`/`column`/`add-table`)と
  query(`table-names`/`get-table`/`column-names`)。
- **`ddl.validate`** — 構造検証。`{:ddl/severity :ddl/code :ddl/id :ddl/msg}` の vector を
  返す純関数。error(table 内の重複カラム名 / 空のカラム型 / dangling FK ＝ ref-table か
  ref-columns が存在しない)と warn(primary key を持たない table)を分離、`valid?` は
  error 無しで真。
- **`ddl.ddl`** — SQL DDL ⇄ model。**自前の minimal tokeniser/emitter**。`parse-str` は
  `CREATE TABLE [IF NOT EXISTS] name ( … );` を1つ以上、大小文字非依存・`;` 終端・
  `--` 行コメント対応で往復する。カラム定義(`name TYPE [NOT NULL] [PRIMARY KEY]
  [DEFAULT <lit>]`)、`(n)`/`(p,s)` 付きの型(`VARCHAR(255)` / `NUMERIC(10,2)`)、
  table-level の `PRIMARY KEY (cols)` / `FOREIGN KEY (cols) REFERENCES t (cols)` を扱う。
  汎用 SQL パーサではない(対応 subset は README/docstring に明記)。`emit-str` は
  canonical な CREATE TABLE テキストを出力し parse↔emit が round-trip する。
- **`ddl.execute`** — **純粋 schema-diff → migration**。`diff old new` は順序付き ops
  vector(`:create-table` / `:drop-table` / `:add-column` / `:drop-column` /
  `:alter-column`)を返す。`emit-migration ops` は ops を `CREATE TABLE` /
  `DROP TABLE` / `ALTER TABLE ADD|DROP|ALTER COLUMN` の SQL テキストに変換する。

## Rationale

- **データ第一**: スキーマが EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  migration ツールの不透明な状態 blob を持たない。
- **依存ゼロ × 可搬**: 重い ORB/ORM/SQL パーサ dep を避ける本リポ方針と、WASM/SCI host
  での実行要件を両立。SQL I/O は CREATE TABLE subset の自前実装に絞り過剰実装を避ける。
- **純粋 diff**: schema 同士の差分が純関数なので、migration プレビュー・dry-run・CI での
  schema drift 検出がオフラインの fixture だけでテスト可能。
- **決定的出力**: table キー順・column 順を保つことで diff/emit が再現的。

## Consequences

- 自前 SQL reader は well-formed な CREATE TABLE subset 限定(ALTER/INDEX/VIEW、
  CHECK 制約、トリガ等は対象外)。限界は README/docstring に明記する。
- `:alter-column` は型/制約の置換を1 op として表現する(段階的な制約変更の細分化は将来課題)。
- 最初の消費者は別 org(公益=etzhayyim / 事業=gftdcojp)の actor が実 DB に対して
  migration を適用する(本ライブラリにドメインスキーマも DB ドライバも入れない)。

## Verification

`clojure -X:test` 緑(14 tests / 39 assertions)。CREATE TABLE → model(カラム/型/
NOT NULL/PK/DEFAULT)の parse、model↔SQL round-trip、FK parse、dangling FK と重複
カラムの validate error、PK 欠落の warn、`diff` の add-column / drop-table /
alter-column 検出、`emit-migration` の `ALTER TABLE … ADD COLUMN` 生成、複数文と
`IF NOT EXISTS` の parse を確認。
