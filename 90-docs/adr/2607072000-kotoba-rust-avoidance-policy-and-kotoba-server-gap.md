# ADR-2607072000: 「Rust が必要な実装は全て cljc」の標準方針化 + kotoba-server（本番 mesh runtime）の未着手を名指しする

**Status**: accepted (policy statement + gap identification; no migration work in this ADR)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（指示: 「rust が必要な実装は全て cljc で設計実装」）
**Scope**: リポジトリ全体の方針宣言 + `orgs/com-junkawasaki/kotoba`（本番 fleet
が実行する Rust crate tree）の棚卸し補完

## Context

ADR-2607071900（murakumo cross-node apply）で、cross-node auction という
「Rust(kotoba-lattice) を拡張したくなる機能」を **murakumo 側の cljc/clj
control plane だけで実装**した。オーナーはこれを一般化し、「Rust が必要そうな
実装は全て cljc で設計・実装する」ことを標準方針にせよと指示した。

この方針を「実装しろ」と機械的に受け取ると、`orgs/com-junkawasaki/kotoba` の
Rust ワークスペース（kotoba-server/kotoba-lattice/kotoba-clj/kotoba-runtime/
kotoba-datomic/kotoba-auth/kotoba-crypto/kotoba-net/kotoba-store/kotoba-ipfs/
kotoba-dht 等、約30 crate）は**今まさに murakumo が Mac-mini fleet の全ノードに
`kotoba-server` バイナリとして配備し実行している本番ランタイム**であるため、
無計画に着手すると本番 mesh を壊しかねない。着手前に「今どこまで cljc 化が
進んでいて、何が本当に手つかずか」を正確に棚卸しする必要があった（過去に
本 assistant が「実装済みと誤認して後で訂正」を繰り返した反省が
ADR-2607072400 aiueos⊣kototama にも記録されている——同じ轍を踏まない）。

### 棚卸しの結果（Explore agent による実地調査、2026-07-07）

1. **`orgs/kotoba-lang/kotoba` は `com-junkawasaki/kotoba` の cljc 移植版
   ではない。** 元々ほぼ同名の Rust crate tree（kotoba-core/kotoba-query/
   kotoba-auth/kotoba-graph/kotoba-vm/kotoba-llm/kotoba-runtime/kotoba-clj/
   kotoba-ingest/kotoba-server/kotoba-store/kotoba-custody/kotoba-git/
   kotoba-turn/kotoba-net/kotoba-dht、約38万行）を持っていたが、**2026-07-01
   に PR #259 で丸ごと削除済み**。現存するのは `src/kotoba/{launcher,
   wasm_exec,kgraph,host_providers,package_admission,cap_table,runtime}.clj`
   という薄い CLJ ランチャー/アダプタのみで、CLI セマンティクスは
   `kotoba-lang/kotoba-lang` に委譲している。README/CLAUDE.md も
   crate 表を「historical Rust design record」と明記済み。
2. **既存の棚卸し ADR は `2607022600`（kotoba-database-crates-cljc-migration-
   roadmap）だが、これは `kotoba-lang/kotoba` の（既に削除済みの）Rust tree
   を対象にしたもの**——ただし crate 名がほぼ同一（kotoba-core/kotoba-query/
   kotoba-graph/kotoba-auth/kotoba-signal/kotoba-crypto/kotoba-dht 等）なので、
   `com-junkawasaki/kotoba` 側の対応するデータベース系 crate の現状評価としても
   **事実上そのまま使える**。Status は `proposed`（計画のみ、実装は伴わない
   旨を自ら明記）。
3. **`.cljc`/`.kotoba` ランタイム優先順位を定めた ADR-2607062330
   （kototama-tender-chicory-execution-runtime、CLAUDE.md にも転記）は
   スコープが `kototama.tender`（WASM ゲスト実行層、Solo5 tender パターン）に
   **限定**されている。`kotoba-server` の HTTP/gossipsub/mesh 責務の代替には
   触れていない。****
4. **`kotoba-server` 自体（HTTP dispatch・gossipsub lattice・WASM component
   host・trigger 発火）は、上記どの ADR にも「対象内」とも「対象外」とも
   書かれていない——単純に不在。** murakumo の README/fleet.edn/provision
   コードは、今も `cargo build --release -p kotoba-server --features
   p2p,realtime-wasm,webrtc` でビルドした **Rust バイナリ**を fleet 全ノードに
   LaunchAgent として常駐させている（`MURAKUMO_KOTOBA_DIR=orgs/
   com-junkawasaki/kotoba`）。ADR-2607071900 で実装した cross-node apply も
   結局この Rust `kotoba-server` へ WASM component を deploy する経路を使う。

### 棚卸し結果の要約表（`com-junkawasaki/kotoba/crates/*` → cljc 対応）

| crate | cljc 対応 | 状態 |
|---|---|---|
| kotoba-core（CID/frame/Prolly） | `kotoba-lang/{multiformats,dag-cbor,prolly-tree}` | 部分〜ほぼ完了 |
| kotoba-query（Datalog/Arrangement） | `kotoba-lang/{quad-store,kqe}` | 部分完了（hot のみ、SPARQL/fixpoint 未） |
| kotoba-graph（Quad/CommitDag） | `kotoba-lang/commit-dag` | 部分完了（quad-store との配線未） |
| kotoba-store / store-web | 無し | 未着手 |
| kotoba-auth（CACAO） | `kotoba-lang/cacao` と `kekkai/cacao.cljc` が重複並存 | 部分・要統合 |
| kotoba-signal | `kotoba-lang/signal` | ほぼ完了 |
| kotoba-crypto | `kotoba-lang/{crypto,ed25519}` | 完了 |
| kotoba-dht | `kotoba-lang/net`（gossip/bitswap 断片） | 断片のみ |
| kotoba-vm（Pregel） | 無し | 未着手 |
| kotoba-llm | 無し | 未着手（別 ADR 要） |
| kotoba-ingest | 無し | 未着手（別 ADR 要） |
| kotoba-custody | 無し | 未着手（設計方針のみ既述、別 ADR 要） |
| kotoba-git / kotoba-rad | N/A | 独立ロードマップ（ADR-2606280300） |
| kotoba-kotodama | N/A | Actors パターンで個別リポジトリへ移行中 |
| kotoba-runtime / kotoba-clj（WASM コンパイラ/ホスト） | N/A（本ADRの対象外） | kototama/aiueos ADR 系列が担当（実行層のみ） |
| **kotoba-server**（HTTP/gossipsub/mesh、murakumo が本番配備） | **無し** | **どの ADR にも記載なし — 本 ADR で初めて名指し** |

## Decision

### 1. 標準方針として明文化する（今後の全実装に適用）

**kotoba スタック内で「Rust でないと実装できなさそう」と思える新規機能・
既存ギャップの解消は、まず cljc（`.cljc`/`.clj`/`.cljs`）での設計・実装を
検討する。** 具体的な優先順位は CLAUDE.md 既定の
`kotoba wasm > clojurewasm > cljs > nbb > jvm` に従う。Rust への新規実装は、
cljc で実現不能な理由（性能要件・既存 Rust 資産との密結合・ネイティブ
capability 依存等）を明記した上で個別 ADR の判断とする — デフォルトは cljc。
ADR-2607071900（cross-node apply を murakumo/cljc のみで解決）がこの方針の
最初の適用例であり、今後の判断の参照点とする。

### 2. 既存 Rust crates の遡及的書き換えは wholesale では行わない

CLAUDE.md の `.cljc`/`.kotoba` ランタイム優先順位節が既に定める原則
（「上位の選択肢が実在するようになった今もリトロアクティブに書き直さない
——移行する場合は対象を決めてから着手する」）を、`com-junkawasaki/kotoba` の
crate 全体にもそのまま適用する。**本 ADR は「全部書き直せ」という指示では
なく、「棚卸しを正確にし、各 crate の移行要否・優先度を可視化する」ところ
までを担う。** 個々の crate の実移行は、この表を参照して個別 ADR を都度
起票して進める（ADR-2607022600 が既に同じ様式で Wave 0–5 のロードマップを
DB エンジン系について定めており、それを流用する）。

### 3. kotoba-server は最優先かつ最高リスクの残課題として名指しする

上記棚卸しのとおり、**`kotoba-server`（HTTP dispatch・gossipsub lattice・
WASM component host・trigger 発火）だけが、他のどの crate ともスコープが
被らずに完全に手つかず**である。これは「Rust が必要な実装」の中で
実質的に唯一、**今すぐの cljc 全面代替が正当化できない**候補でもある:

- 本番 Mac-mini fleet 全ノードで常駐実行中（murakumo `provision`/LaunchAgent
  経由）——書き換え失敗時の影響範囲が本番 mesh 全体。
- gossipsub（libp2p）・WASM component hosting（wasmtime 相当）・HTTP
  dispatch という、性能/並行性要求の高い責務の集合体。
- cljc/JVM 側に相当する実装が今のところ影も形もない（kototama.tender は
  WASM **ゲスト実行**層であって、mesh の HTTP/gossipsub/配置责务そのものの
  代替ではない——ADR-2607062330 のスコープ外）。

**よって本 ADR では kotoba-server の cljc 移行に着手しない。** 移行するので
あれば、①段階的カットオーバー手順（新旧並走 → parity 検証 → 切替、
ADR-2606231200 の kotobase.net cutover と同型の手順）、②性能要件の実測、
③失敗時のロールバック手段、を含む**専用の個別 ADR**を別途起票してから
着手する。本 ADR はその判断のための材料（現状棚卸し）を確定させるところ
までを役割とする。

## Consequences

- (+) 「Rust が必要な実装は全て cljc」という指示が、無計画な wholesale
  rewrite ではなく、①今後の新規実装への標準方針、②正確な現状棚卸し、
  ③最優先ギャップ(kotoba-server)の名指し、という実行可能な形に分解された。
- (+) ADR-2607022600 の棚卸し（DB エンジン系 crate、Wave 0–5 ロードマップ）
  が `com-junkawasaki/kotoba` 側にもそのまま参照できることを確認・記録した
  ——今後この領域（Prolly/Arrangement/CommitDag/Datalog/CACAO/Signal）に
  手を入れる際は同 ADR の Wave ロードマップに従う。
- (−) kotoba-server（最も価値が高いはずの対象）は本 ADR でも未着手のまま
  残る。次のアクションを取るなら、性能要件と段階的カットオーバー手順を
  伴う専用 ADR を起票することを推奨する（本 ADR はその前提を整えた）。
- (−) kotoba-llm / kotoba-ingest / kotoba-custody / kotoba-store は
  ADR-2607022600 でも「対象外・別 ADR 要」のままで、本 ADR でも同様に
  対象外とする(必要になった時点で個別 ADR)。

## Related

- ADR-2607071900（murakumo cross-node apply, cljc-only）: 本方針の最初の
  適用例・きっかけ。
- ADR-2607022600（kotoba-database-crates-cljc-migration-roadmap）: DB エンジン
  系 crate の棚卸しと Wave ロードマップ（`com-junkawasaki/kotoba` 側にも
  事実上流用可能と確認）。
- ADR-2607062330（kototama-tender-chicory-execution-runtime）+ CLAUDE.md
  `.cljc`/`.kotoba` ランタイム優先順位節: WASM ゲスト実行層のスコープ
  （kotoba-server 全体の代替ではない）。
- ADR-2607072400（aiueos⊣kototama decision/execution boundary）: 「実装済みと
  誤認して後で訂正」を繰り返さない、という反省の先例（本 ADR が同じ轍を
  踏まないよう Explore agent による実地調査を先行させた根拠）。
- ADR-2606280300（kotoba-git/kotoba-rad ロードマップ）、CLAUDE.md Actors
  パターン（kotoba-kotodama の個別リポジトリ移行）: 本 ADR の対象外として
  参照のみ。
