# ADR-2607011600: Clojure コード組織の呼称は「library」/「namespace」、「package」は使わない

Status: Accepted
Date: 2026-07-01

## Context

`kotoba-lang` 配下に多数の `.cljc` capability library（`banking`, `card`,
`swift`, `eth-crypto`, `cacao`, `did`, `vc`, …）が増えており、ADR やコード
コメント、README で「この単位は何と呼ぶべきか」が曖昧になりがちだった。
Java/Python 由来の「package」という語が紛れ込むと、Clojure の実際の単位
（`deps.edn` で配布される **library** と、コード内の **namespace**）と混同する。

さらに `kotoba-lang/ooxml` は OOXML コンテナ構造そのものを指す
`:package`（`ooxml/package-kind`, `ooxml/ensure-content-type` 等）という
featureを持っており、「Clojureのpackage」という語をコード組織の意味で
使うと、この OOXML package（ドメイン語彙として正しい）と衝突して読者を
混乱させる。

## Decision

- **配布単位（リポジトリ/deps.edn artifact）は "library"（lib）と呼ぶ。**
  `group/artifact` 座標、Clojars/git deps、west project はすべて library。
- **コード内の分割単位は "namespace"（ns）と呼ぶ。** `(ns foo.bar)` の
  `foo.bar` がnamespace。コンパイル後にJVM上のJavaパッケージへ写像されるのは
  `gen-class`/interop向けの実装詳細であり、Clojureの語彙としては表に出さない。
- **"package" という語は、ドメイン語彙として本来 package を指す文脈
  （例: `kotoba-lang/ooxml` の OOXML package、`kotoba-lang/card` の
  ISO 8583 message package 等）以外では使わない。** Clojure/CLJCコードの
  組織単位を指して「package」と書かない。
- ADR・README・コードコメントでこの区別を守る: 「〜 library」「〜 namespace」
  であって「〜 package」ではない（OOXML等の正当なpackage文脈を除く）。

## Consequences

- 新規 ADR やドキュメントで、Clojureの配布単位/コード単位を指す際は
  必ず library/namespace を使い、package は使わない。
- 既存の "package" 記述（`ooxml.package`, `card` の ISO 8583
  message package 等）は正当なドメイン語彙なのでそのまま維持する
  （このADRはそれらを変更しない）。
- 新しい寄稿者/agentへの一貫したガイダンスとして機能する
  （命名の揺れによる混乱を予防）。
