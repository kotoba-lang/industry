# ADR-2607022900: aiueos native adapter（kototama tender の実行部）を Rust/wasmtime ではなく JVM/Clojure + Chicory で構築する — ADR-2607022700/2607022400 の「native adapter = Rust」枠組みを実行層に限り更新する

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607022400 は kototama の runtime 設計に Solo5 tender パターンを採用し、
ADR-2607022700 は native adapter（decision subprocess `bb decide` を呼ぶ側）を
Rust で書く前提でモジュール計画を立てた。どちらも「Wasm を実際にホストする層
（host.rs/runtime.rs 相当）は native/Rust でなければならない」という前提に
立っていた——`.kotoba` は Wasm へコンパイルされる側であり、他の Wasm をホスト
できないから、という ADR-2607022200 の論拠に基づく。

この前提を native adapter 着手の直前に再検証した。`kotoba-lang/kotoba` の
`deps.edn` に **`com.dylibso.chicory/wasm` + `com.dylibso.chicory/runtime`**
（pure-JVM Wasm runtime、native toolchain 不要）が既にあり、`kotoba.wasm-exec`
（`docs/ADR-kotoba-wasm-clj-execution.md`、accepted・実装済み、2026-07-02）が
これを使って実際に「コンパイル済み Wasm バイナリを Chicory でロード・実行し、
host-import を Clojure クロージャで実装し、結果を受け取る」を実装・検証済み
だった。実測: `wasm-binary-actually-executes` テストが green、ADR 本文にも
`kotoba wasm run` の実 CLI 実行例が記録されている。

**この事実は「Wasm をホストする側は native でなければならない」という前提を
崩す。** Wasm 実行エンジン（wasmtime 相当）を **JVM 上で pure Clojure として
動かせる**なら、host.rs が担っていた「host-import を提供し capability gate を
かけて実行する」役割全体を、Rust を介さず `aiueos-cljc-contract` 自身（CLJC）
の延長として実装できる。

## Decision

### 1. Chicory/JVM を aiueos native adapter の実行層として採用する

ADR-2607022700 の「native adapter は decision subprocess にのみ問い合わせ、
capability を自分で判定しない」という原則は**そのまま維持**する。変わるのは
「native adapter が Rust である必要」という前提だけ——**執行（Wasm 実行）自体を
JVM/Chicory 上の Clojure コードで行い、decision subprocess を別プロセスに
分ける必要すら無くなる**（`aiueos.broker` の判定と Chicory の実行が同一 JVM
プロセス内で完結できる）。

- host-import 関数（`log-write` `clock-monotonic` `random-bytes` `topic-publish`
  `topic-poll` `topic-take` `topic-count`）は、生ハードウェアを一切要さない
  ため、**Chicory の host function として Clojure クロージャで直接実装できる**。
  `topic-*` は既存の `aiueos.topic`（pure/immutable pub/sub bus）にそのまま
  委譲できる。
- `pci-config` `dma-map` `irq-subscribe` `mmio-map`（device-access quartet）は
  生ハードウェアアクセスを要するため**引き続き native/adapter 側の責務**。
  ただし「native」の意味が変わる: JVM の `java.lang.foreign`（Foreign
  Function & Memory API、Java 22 で正式化、21 は preview）を使えば、これすら
  **Rust を介さず JVM から直接**扱える可能性がある——本 ADR では未検証・未決定
  のまま将来課題として残す（Consequences 参照）。

### 2. ADR-2607022700 のモジュール計画を更新する

| 旧計画（ADR-2607022700） | 新計画（本 ADR） |
|---|---|
| `host.rs`/`runtime.rs` を Rust で復元・改修 | **Chicory 経由の JVM/Clojure 実行層**として `aiueos-cljc-contract`（または隣接 namespace）に新規実装。Rust の host.rs/runtime.rs は復元しない |
| decision subprocess（`bb decide`）を native adapter が別プロセスとして呼ぶ | 同一 JVM プロセス内で `aiueos.broker` の判定 → Chicory 実行、を直結できる（`bb decide` の EDN-over-stdio 経路は**別ホスト言語向けの汎用インターフェースとして温存**——Node/Python 等、JVM でない adapter からはこちらを使う） |
| `bin/aiueos.rs`（CLI）を Rust で復元・改修 | CLI も JVM/Clojure（`bb`/`clojure` ベース）で実装可能。argv 解析・file I/O は Rust 特有の要件が無い |
| `virtio.rs` を低信頼度のまま書き直す | 変更なし——device-access quartet の生アクセス部分は依然 native 課題。ただし「native」が「Rust」を意味するとは限らなくなった（`java.lang.foreign` 経路が未検証のまま残る） |

### 3. 既知の未解決ギャップ（Chicory の現状の限界）

- **fuel/gas metering が Chicory に無い**: wasmtime の fuel 相当の命令数バジェット
  機構は Chicory の Resource Control API がまだ "in development"
  （公式ドキュメント確認済み）。`aiueos.manifest` の `:aiueos/limits {:fuel N}`
  は**現時点で執行時に強制できない**——manifest の宣言止まりで、実際の
  暴走実行を止める仕組みが無い。これは real gap として明記する（黙って
  「解決済み」と扱わない）。
- **memory-pages 制限**はコンパイル済みモジュール自身が宣言する範囲でしか
  効かない。manifest 側の `:memory-pages` を独立した実行時上限として強制する
  経路は未検証。

**Follow-up（2026-07-02、`aiueos-cljc-contract`）**: `:aiueos/quota
{:host-calls N :publishes N}`（ADR-0006、命令数ではなく host 関数呼び出し回数の
per-run 上限）は `aiueos.execute` に実装・実運用検証済み——各 host-import
呼び出しを `count-and-check!` でラップし、上限超過時は Chicory の `.apply` 越しに
`ex-info` を投げて実行を中断する（`run-if-granted` が捕捉し
`:aiueos.execute/quota-exceeded {:kind :limit :count}` を返す。offending call 自体の
副作用は発生しない）。実測: shell から `clojure -M -m aiueos.launcher run
manifest.edn --edn`（`:aiueos/quota {:publishes 0}`）で実際に中断・
`:aiueos.execute/topic-bus` が空のまま返ることを確認。**ただしこれは fuel（命令数）
とは別物** —— host 関数呼び出しの「回数」だけを数える粗い上限であり、1回の
host 呼び出し内部での無限ループや大量計算は防げない。fuel/gas metering の
欠落は依然 real gap のまま。

## Consequences

- (+) native adapter の**大部分**（device-access quartet を除く全て）が Rust
  を経由せず JVM/Clojure で完結できる可能性が開けた。ADR-2607022200/2607022400
  が定めた「意味論は CLJC」の範囲が、事実上「執行も含めほぼ全域」まで拡張
  される。
- (+) decision subprocess（プロセス境界）と execution（Wasm実行）を同一 JVM
  プロセスに同居させられるため、`bb decide` の per-invocation shell-out
  レイテンシ（ADR-2607022700 で「V1として許容、later最適化」と記した課題）が
  JVM ホストの adapter では実質解消する。
- (+) `kotoba.wasm-exec` は kotoba-lang/kotoba 側で既に実装・テスト済みの
  資産であり、ゼロから書く必要がない——ただし aiueos 側は kotoba-lang/kotoba
  の他の巨大な依存（KOTOBA データベース本体等）を引きずらないよう、
  Chicory への直接依存として独立実装するか、慎重に切り出す必要がある
  （aiueos-cljc-contract の「軽量・依存最小」という既存方針を壊さない）。
- (−) **fuel/gas metering の欠落は real gap**。Chicory 側の対応を待つか、
  自前で命令数カウントの仕組みを足す必要がある。これを解決しないまま
  「実行できるから安全」と早合点しないこと。
- (−) device-access quartet（pci/dma/irq/mmio）の生アクセスは依然未解決。
  `java.lang.foreign` 経路の検証は本 ADR のスコープ外、次の follow-up。
- (−) 本 ADR は方針転換の決定であり、実装（aiueos 側での Chicory 統合、
  host-import クロージャの実装、fuel gap への対処方針）は follow-up。

## References

- ADR-2607022700（native adapter 設計。本 ADR は execution 層の言語選択のみ
  更新し、「native adapter は capability を自分で判定しない」という原則は
  維持する）
- ADR-2607022400（kototama = Solo5 tender パターン。tender の**実行部**が
  JVM/Chicory になっても、「境界の外側で最小限に留める」という tender の
  設計思想自体は変わらない）
- ADR-2607022200（三層アーキテクチャ、CLJC 意味論の正本原則）
- `kotoba-lang/kotoba` `docs/ADR-kotoba-wasm-clj-execution.md`
  （Chicory 統合の accepted・実装済み ADR）
- `kotoba-lang/kotoba` `src/kotoba/wasm_exec.clj` /
  `test/kotoba/wasm_exec_test.clj`
- Chicory: https://github.com/dylibso/chicory ,
  https://chicory.dev/docs/usage/runtime-compiler/
- `aiueos-cljc-contract/src/aiueos/topic.cljc`（topic-* host-import の
  実装先として再利用可能）
