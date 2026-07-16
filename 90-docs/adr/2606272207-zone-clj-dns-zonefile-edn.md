---
id: adr-2606272207-zone-clj-dns-zonefile-edn
title: "ADR-2606272207: zone-clj — DNS ゾーンファイル (RFC 1035) を EDN/Clojure データとして扱う再利用ライブラリ。model(records vector) + validate + 自前 minimal zone-file reader/emitter + structural diff(godaddy-dns-clj 同期用)。third-party dep ゼロの portable .cljc"
status: proposed
doc_type: adr
topic: library-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - DNS ゾーンファイル (RFC 1035 subset) を Clojure で第一級の EDN データとして表現する正準モデルの定義
  - zone-clj の責務境界(モデル/検証/ゾーンファイル I/O/diff)の設計
  - godaddy-dns-clj と連携する structural diff (add/remove/changed) の設計指針
  - 大容量・非可搬な DNS ライブラリ依存を避けるための minimal reader + emit 戦略
  - DNS 成果物の 3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/kotoba-lang/zone                         # 本 ADR のライブラリ
  - orgs/kotoba-lang/godaddy-dns                  # zone-clj の diff を消費する DNS API クライアント
  - orgs/kotoba-lang/org-omg-bpmn                         # 同型の再利用 kernel(minimal reader の先例)
  - orgs/kotoba-lang/org-omg-dmn                          # 姉妹 kernel(same zero-dep .cljc 方針)
supersedes: []
superseded_by: []
---

# ADR-2606272207: zone-clj — DNS ゾーンファイルを EDN/Clojure で扱う再利用ライブラリ

**Status**: proposed（実装済み・テスト緑。godaddy-dns-clj との結線は別 PR）  
**Date**: 2026-06-27  
**Deciders**: Jun Kawasaki

## Context

DNS ゾーン管理(GoDaddy / Cloudflare 等のレジストラ API 経由)において、
ゾーンファイル(RFC 1035)とレジストラ API レスポンスを「同じ EDN データ構造」で
扱いたい需要がある。既存の Clojure DNS ライブラリは

1. JVM 専用の重い依存(dnsjava 等)を引き、CLJS/SCI では動かない。
2. レコードを文字列や独自型に閉じ込め、Clojure の `assoc`/`diff`/Datomic と
   噛み合わない。

本リポの方針(shallow 既定・大容量 dep 回避・portable `.cljc`・host-injected ports —
bpmn-clj / dmn-clj の先例)に沿う、**ゾーンファイルを素の EDN として扱う軽量ライブラリ**
が無かった。

## Decision

`com-junkawasaki/zone-clj` を新設する。**third-party 実行時依存ゼロ**、全 namespace
`.cljc`(JVM/CLJS/SCI)。責務を 3 層に分離する:

- **`zone.model`** — zone-as-EDN の正準モデル。ゾーンは `:zone/origin` /
  `:zone/ttl` / `:zone/records` の flat map。records は `:zone/name` /
  `:zone/ttl` / `:zone/class` / `:zone/type` / `:zone/rdata` を持つ map の
  vector。rdata は型ごとに namespaced キー(A→`:zone/address`, MX→`:zone/pref` +
  `:zone/exchange`, SOA→7 フィールド, 等)。クエリ(`records-of-type` /
  `by-name`)と **structural diff**(`diff old new → {:zone/added :zone/removed
  :zone/changed}`)を提供。diff は `[name type rdata]` を identity key とし、
  単一レコードの rdata 変化を add+remove ではなく `:zone/changed` として報告
  (godaddy-dns-clj の PATCH 最適化に対応)。

- **`zone.validate`** — 構造検証。`{:zone/severity :zone/code :zone/id :zone/msg}`
  の vector を返す純関数。error(SOA 欠落 / SOA 複数 / CNAME 排他性違反 /
  MX・SRV・SOA rdata 欠損)と warn(FQDN 値のトレーリングドット欠落)を分離、
  `valid?` は error 無しで真。

- **`zone.zone`** — ゾーンファイル文字列 ⇄ model。**自前の minimal
  reader/emitter**(行指向・`$ORIGIN` / `$TTL` ディレクティブ・`@` apex・
  空オーナー継承・ttl/class フレキシブル順序・`;` コメント保護・SOA 複数行
  `( … )` 括弧結合)で RFC 1035 well-formed subset を dep 無しで往復。
  `emit-str` は `[name type]` 順で決定論的に出力、round-trip を保証:
  `(= (emit-str z) (emit-str (parse-str (emit-str z))))`。

`resources/zone/example.com.zone` にリアルなサンプル(SOA/NS/A/AAAA/CNAME/MX/TXT)
を同梱し、テストがリソースから直接 parse する形で資料性と CI 検証を兼ねる。

## Rationale

- **データ第一**: ゾーンが EDN なので生成・差分・バージョニング・Datomic 格納が自明。
  レジストラ API の JSON レスポンスも同じ `:zone/*` スキーマに正規化できる。
- **依存ゼロ × 可搬**: 重い DNS dep を避ける本リポ方針と WASM/SCI host での実行要件を
  両立。ゾーンパーサは「RFC 1035 well-formed subset 自前」で過剰実装を避ける。
- **godaddy-dns-clj との責務分離**: zone-clj はモデルと diff を提供するだけで
  API 認証・HTTP を持たない。godaddy-dns-clj は diff 結果を消費して PATCH/POST/DELETE
  を呼ぶ。kernel と adaptor の分離は koe-clj / bpmn-clj の先例に沿う。
- **bpmn-clj と同型**: deps.edn シェイプ・namespace 命名規則・validate API・README
  構造を統一し、org 内の一貫性を高める。

## Consequences

- 自前ゾーンリーダは RFC 1035 well-formed subset 限定。BIND の一部拡張構文
  (INCLUDE, generate 等)は非対応。逸脱時は zone テキストを前処理してから
  `parse-str` に渡すか、レジストラ API の JSON を直接 `:zone/*` に正規化する
  アダプタを godaddy-dns-clj 側に置く。
- TXT の複数 quoted-string 連結 (`"abc" "def"`) は `"abc def"` に正規化される
  (RFC 1035 準拠の挙動だが、元の複数トークン構造は失われる)。
- diff の `:zone/changed` は `[name type]` グループが両辺とも 1 レコードの場合のみ。
  複数 MX 等は add+remove で表現される。godaddy-dns-clj がこれを正しく扱う。

## Verification

`clojure -X:test` 緑(12 tests / 29 assertions)。バンドル resource の parse、
emit→parse round-trip、CNAME 排他エラー、SOA 0/2 両エラー、MX rdata 解析、
records-of-type / by-name クエリ、diff の add/remove/change 全3方向を確認。
