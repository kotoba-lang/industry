# ADR-2607022900: aiueos native adapter（kototama tender の実行部）を Rust/wasmtime ではなく JVM/Clojure + Chicory で構築する — ADR-2607022700/2607022400 の「native adapter = Rust」枠組みを実行層に限り更新する

**Status**: accepted, implemented
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

**Follow-up 2（2026-07-02、fuel/gas metering・生ハードウェアアクセスの再調査）**:

- **fuel/gas metering**: Chicory の "Resource Control API" は本稿執筆時点
  （1.5.0〜1.7.5 まで確認）でも未出荷——ただし **命令レベル fuel を今日から
  実装できる実在の DIY フックがある**: `Instance.builder(...)
  .withUnsafeExecutionListener((instruction, stack) -> ...)`
  （`ExecutionListener`）が Wasm 命令 1 個ごとに発火する。ここでカウンタを
  持たせて閾値超過で例外を投げれば、真の命令レベル fuel が実装できる。
  ただし: (a) Chicory 公式ドキュメントが明示的に "unsafe" "extremely risky"
  "experimental" "we might drop it at a later stage" と警告している**非正式
  API**（issue #895/#896 によれば、公式にサポートされる実行制限手段は
  thread-interrupt/`ExecutorService` によるウォールクロックタイムアウトの方）、
  (b) **インタプリタ経路限定**——Chicory の AOT コンパイラ（`compiler` モジュール、
  `AotMachine`）はこのフックを経由しない命令ループを使うため、将来 `aiueos.execute`
  が性能目的で AOT に切り替えると効かなくなる。「fuel は今日から prototype
  可能」だが「安全に永続利用できる正式 API ではない」——`Chicory 側の実装待ち`
  という従来の記述はやや不正確で、正しくは「非公式フックでの prototype は可能、
  公式 API 化は待ち」。
- **生ハードウェアアクセス（`java.lang.foreign` 経路）**: この設問自体が
  **フレーミング誤り**だったと判明。Solo5 の実アーキテクチャ（本 ADR が採用した
  tender パターンの元ネタ）を確認したところ、`hvt`（ハードウェア仮想化 tender）
  では **tender 側が KVM 特権で guest のメモリ/デバイスマッピングを設定し、
  guest（unikernel）自身は生 MMIO に一切触れない**——guest は
  `solo5_net_write` のような narrow hostcall を発行するだけで、tender がそれを
  仲介する。`spt` では guest はさらに seccomp で制限された非特権プロセスとして
  動く。**つまり生 MMIO/DMA へのアクセスは元々「Rust か Java か」という言語の
  問題ではなく、「特権レベル」の問題**——root＋`/dev/mem`（最近の Linux では
  `CONFIG_STRICT_DEVMEM` で年々制限強化）か、KVM/hypervisor 権限か、カーネル
  自身であることが必須で、非特権のユーザースペースプロセスはどの言語であっても
  生 MMIO に直接触れない。`java.lang.foreign`（Java 22 で正式化。本リポジトリの
  CI が pin する Java 21 では **preview のみ**、`--enable-preview` 必須）は
  Rust の `libc` バインディングと同等の `mmap()`/`ioctl()` 呼び出しを行う能力は
  確かにある——なので「特権レイヤーの実装言語として Java 22+ か Rust か」は
  今や実際に開いた選択だが、**「特権/hypervisor 協調レイヤーそのものを書く」
  という本質的な仕事は言語に関わらず未着手のまま**。本 ADR の
  「Rust vs `java.lang.foreign`」という設問設定を、「特権/hypervisor 協調 tender
  レイヤーが必要（言語は問わない）」に訂正する。

以上を踏まえた更新版の推奨: fuel は `withUnsafeExecutionListener` を使った
prototype に着手する価値があるが、非公式 API 依存・インタプリタ限定という
制約を実装コメントに明記すること。生ハードウェアアクセスは
「Rust か Java か」ではなく「特権/hypervisor 協調レイヤーの不在」が真のブロッカー
であり、Java 21→22 への JDK バンプ判断とは独立して、まず特権レイヤーの設計
自体（KVM ベースの最小 VMM か、root+`/dev/mem` ドライバか）が必要——これは
本 ADR のスコープを大きく超える別課題として、明確に blocked のまま残す。

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

**Follow-up 3（2026-07-02、fuel prototype 実装）**: Follow-up 2 で「今日から
prototype 可能」と判定した `withUnsafeExecutionListener` 経由の命令レベル fuel を
`aiueos.execute` に実装・実測検証済み（`aiueos-cljc-contract#7`）。`:aiueos/limits
{:fuel N}` を Wasm 命令ごとに発火するリスナーでカウントし、超過時に
`:aiueos.execute/fuel-exceeded {:limit :count}` で実行を中断する。実測: `fuel 1`
で shell から `clojure -M -m aiueos.launcher run` を実行し、実際に
`{:limit 1, :count 2}` で中断・topic-bus が空のまま返ることを確認。**ただし
non-guarantee のまま**——`withUnsafeExecutionListener` は Chicory 公式ドキュメント
が明示的に unsafe/experimental/将来削除の可能性ありと警告する非公式 API で、かつ
インタプリタ経路限定（将来 AOT コンパイラに切り替えると効かなくなる）。「fuel gap
は解決済み」ではなく「非公式APIでのprototypeが動くことを実証した」段階であり、
将来 Chicory が正式な Resource Control API を出荷したら、そちらへの移行を検討する
こと。生ハードウェアアクセス（特権/hypervisor協調tenderレイヤーの不在）は依然
完全に未着手のまま。

**Follow-up 4（2026-07-02、CLI コマンド網羅完了・移行状況の総括）**:
`aiueos.launcher`（本 ADR/ADR-2607022700 のモジュール計画表が「JVM/Clojure で
実装可能」とした CLI）に `up`（`aiueos-cljc-contract#8`）を配線し、
`verify`/`run`/`admit`/`inspect`/`surface`/`audit`/`up` の 7 コマンドが実際に
動作する状態になった。`up` は `aiueos.graph/boot-order`（provider が consumer
より先）でシステムの全コンポーネントを起動し、`:aiueos/wasm` を持つものは
`aiueos.execute` で実際に実行、持たないもの（純粋な capability provider）は
決定のみ。deny または quota/fuel 超過で停止するコンポーネントがあれば、
そこでブート全体を止める（依存先が壊れているコンポーネントより後ろを起動しない）。
依存サイクルは実行前に検出して報告する。実測: 2 コンポーネントの system.edn
（fs = provider、app = fs の capability を import しつつ実 wasm も持つ consumer）
を shell から `up` 実行し、fs → app の順で起動、app の topic-publish 呼び出しが
実際に topic bus に反映されることを確認。

**この時点での Rust 依存削減の到達点**: 「aiueos の native adapter は Rust で
書く」という ADR-2607022700 時点の前提は、実行層（旧 host.rs/runtime.rs =
wasmtime 相当）・CLI 本体（旧 bin/aiueos.rs）ともに JVM/Clojure へ完全に
置き換わった。残る唯一の本質的ギャップは生ハードウェアアクセス
（device-access quartet の真の MMIO/DMA/PCI/IRQ）で、これは Follow-up 2 で
訂正した通り「Rust か Java か」ではなく「特権/hypervisor 協調 tender レイヤー
そのものが未着手」という、言語選択とは独立した別課題として残る
（`virtio.rs` の書き直しもこれに連動して保留）。adapter-only 系 6 コマンド
（`sign`/`check`/`compile`/`hash`/`image`/`vm`）は元々 kototama/kotoba-clj
委譲・鍵管理ツール・native provisioning が正しい設計であり、意図的に移行対象外。

**Follow-up 5（2026-07-02、manifest契約の未強制フィールド監査・実装）**:
「宣言・検証はされるが実際には使われない」ギャップを体系的に監査し、2件の実装を
追加した:

1. **`:aiueos/publishes`/`:aiueos/subscribes`（トピックID許可セット、
   `aiueos-cljc-contract#9`）**: `aiueos.manifest`が検証・導出していたのに
   `aiueos.execute`のtopic-*ホスト関数がどこにも参照していなかった——付与された
   コンポーネントが宣言していないトピックIDにも自由にアクセスできてしまう
   **実セキュリティギャップ**だった。`assert-topic-allowed!`で4つのtopic-*
   host関数全てに強制を追加、`:aiueos.execute/topic-forbidden`で中断する。
   実測: shellから実際に`{:op :publish :topic-id 1}`で中断確認済み。

2. **`:aiueos/schedule`（period/priority、`aiueos-cljc-contract#10`）**:
   `normalize-schedule`が`{:period-cycles :deadline-cycles :priority}`を
   導出していたのに、`up-command`は常に全コンポーネントを無条件実行していた。
   `aiueos.manifest/due-this-cycle?`（cycle基準の周期判定）と
   `aiueos.graph/priority-boot-order`（同depth内でpriority順に並べ替え——
   `depths`のdocstringが元々想定していたユースケースだが未実装だった）を実装し、
   `up`に`--cycle N`を追加。デフォルト（cycle省略）は従来通り全起動で後方互換。
   実測: shellから`--cycle 1`で周期外のコンポーネントがスキップされ、
   `--cycle 3`で再度起動されることを確認済み。

**明示的に未実装のまま**: `:aiueos/limits :memory-pages`の独立した実行時上限
（コンパイル済みモジュール自身の宣言範囲でしか効かない）、
`:aiueos.manifest/deadline-cycles`（ADR-0006が意図的に持たないwall clockと、
Chicoryの同期・非プリエンプティブな実行モデルの間に本質的な非互換がある——
真のインクリメンタル/割り込み可能な実行機構を発明しない限り正しく実装できない）。

## Closing Summary（2026-07-02）

本 ADR の決定（native adapter の実行層 + CLI を Rust ではなく JVM/Clojure +
Chicory で構築する）は、5 件の follow-up を経て実装・実測検証が完了した:

| # | 内容 | PR | 状態 |
|---|---|---|---|
| 1 | `aiueos.execute`: Chicory による Wasm 実行（topic-publish 実証） | `aiueos-cljc-contract#2` | ✅ 実装・実測済み |
| — | device-access quartet の execution-layer 証明 | `aiueos-cljc-contract#3` | ✅ 実装・実測済み |
| — | `aiueos.launcher`: 実CLI（verify/run/admit） | `aiueos-cljc-contract#4` | ✅ 実装・実測済み |
| — | `aiueos.launcher`: inspect/surface/audit 配線 + `aiueos.cli`の`:audit`ギャップ修正 | `aiueos-cljc-contract#5` | ✅ 実装・実測済み |
| 2 | 研究: Chicory gas metering 状況 / MMIO の再フレーミング | — | ✅ 調査完了 |
| — | `:aiueos/quota`（host-call count cap） | `aiueos-cljc-contract#6` | ✅ 実装・実測済み |
| 3 | `:aiueos/limits :fuel`（命令レベル、非公式API prototype） | `aiueos-cljc-contract#7` | ✅ 実装・実測済み（non-guarantee） |
| 4 | `up`（マルチコンポーネント起動） | `aiueos-cljc-contract#8` | ✅ 実装・実測済み |
| 5 | `:aiueos/publishes`/`:subscribes`（トピックID許可セット） | `aiueos-cljc-contract#9` | ✅ 実装・実測済み |
| 5 | `:aiueos/schedule`（period/priority、cycle-based boot） | `aiueos-cljc-contract#10` | ✅ 実装・実測済み |

**到達点**: aiueos の native adapter（旧 host.rs/runtime.rs 相当の実行層）と
CLI 本体（旧 bin/aiueos.rs）は、Rust を一切経由せず JVM/Clojure だけで実装・
実行証明済み。`aiueos.launcher` は `verify`/`run`/`admit`/`inspect`/`surface`/
`audit`/`up` の 7 コマンドが実際に動作する。manifest が宣言する契約フィールド
（quota/fuel/topic 許可セット/schedule）は全て実際に強制されるところまで到達した。

**恒久的に残る未解決事項**（本 ADR のスコープでは解決しない、今後も blocked
のまま明記し続ける）:
- **fuel/gas metering の正式 API 化**: 現状は Chicory の非公式・実験的フック
  （`withUnsafeExecutionListener`）に依存した prototype。Chicory が公式
  Resource Control API を出荷したら移行を検討する。
- **生ハードウェアアクセス**（device-access quartet の真の MMIO/DMA/PCI/IRQ）:
  「Rust か Java か」ではなく「特権/hypervisor 協調 tender レイヤーそのものが
  未着手」という、本 ADR とは独立した大きな別課題。
- **`:aiueos.manifest/deadline-cycles`**: Chicory の同期・非プリエンプティブな
  実行モデルと ADR-0006 の wall-clock-free 設計原則の間に本質的な非互換があり、
  真のインクリメンタル/割り込み可能な実行機構を発明しない限り正しく実装できない。
- **`:aiueos/limits :memory-pages`** の独立した実行時上限（コンパイル済み
  モジュール自身の宣言範囲でしか現状効かない）。

本 ADR はこれにて実装完了として close する。上記の恒久的な未解決事項は、
着手する際に新しい ADR（生ハードウェアアクセス層の設計など、スコープが
本 ADR を大きく超えるもの）を起票すること。
