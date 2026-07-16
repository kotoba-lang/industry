# ADR-2607101200: ENGI/EN 相互信用通貨 — kotoba-dht基盤フル復活(v2、将来パス)

**Status**: proposed(将来パス。着手条件を満たすまで実装しない)
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki

## Context

ADR-2607101100(ENGI kotobase-native設計、v1)は、kotobase.net の実測された
制約(自グラフのみ書込み・datoms経由の読出し・gossipなし)の上で、原設計
(`kotoba-lang/kotoba/docs/ADR-engi-mutual-credit-on-chain.md`, R0〜R9)の
本質(net-zero・非発行・双方確定・監査ベースの不正検知)を保ったまま
数日規模で実装可能な形に再設計したものである。

v1 が明示的に諦めている性質は3つ:

1. **push型 warrant 伝播**(R7-R9: 署名済み warrant が gossip で mesh 全体に
   伝わり、K/2 の異なる validator の到達で自動追放)。v1 は「取引前に pull で
   確認」に留まる — 攻撃者が warrant 発行前の短い窓で複数の被害者に対して
   同時に二重支払いを試みるケースへの抑止力が原設計より弱い。
2. **非EN chain content の fork 同期**(原設計の「deferred」項目1: EN以外の
   source chain エントリの fork は bitswap `want_since` による per-DID
   チェーン同期が必要 — R5/R8 の「gossip記録からEN forkだけを検知する」
   簡易法が使えない)。
3. **手数料の相手署名化**(原設計の「deferred」項目2: 手数料を真の双方署名
   transfer にするには同期的な countersigning ハンドシェイクが必要 —
   R6は手数料の永続化・復元可能性のみ解決し、双方署名までは踏み込んでいない)。

本ADRは、上記3性質(および ADR-2607022600 Wave 5 が対象とする DHT 全般)が
実際に必要になった場合の**フル Holochain 同型基盤の CLJC 再実装ロードマップ**
を示す。**着手条件を満たすまで実装しない**(ADR-2607022600 Wave 5 の
「先回りして作らない」方針を継承)。

## Decision

### 1. 着手条件(いずれかを満たすまで着手しない)

- v1(ADR-2607101100)の運用で実際に **pull型検知の間隙を突いた二重支払いの
  実害**が観測される。
- EN 以外の用途(ADR-2607101000 が挙げた kotoba-word/kotoba-lattice 等)が
  同じ source-chain + warrant + gossip 基盤を要求し、**複数の消費者**が
  同時に立つ(ENGI単独のための先行投資にしない)。
- kotobase.net 自体の書込み権限モデルが将来変わり(例: 委任スコープの
  多者間 grant が実装される、ADR-2607022600 Wave 4 「CACAO depth-2
  delegation」)、双方向書込みが可能になった場合 — v1の「片側ずつ書く」
  制約が外れ、原設計により忠実な実装が現実的になる。

### 2. CLJC 再実装マップ(R0〜R9 → 独立小規模ライブラリ)

ADR-2607022600 の移行方針(`kotoba-lang/<name>` 配下の小さな独立 `.cljc`
ライブラリとして切り出す、`multiformats`/`prolly-tree`/`quad-store` と
同じ粒度)をそのまま踏襲する。

| 原設計(Rust, 削除済み) | 対応する CLJC 新規ライブラリ(想定) | 相当する R段階 | 前提 |
|---|---|---|---|
| `source_chain.rs`(548行) | `kotoba-lang/source-chain` — per-DID 追記専用ハッシュ連鎖。`append`/`head`/`chain`/`verify-chain` | R0の基盤 | Wave 2(commit-dag)の `chain`/`verify-chain` を再利用できないか要検討 — 概念重複あり |
| `warrant.rs`(259行) | `kotoba-lang/warrant` — 署名済み違反申告の構造体・検証(`verify_warrant`相当) | R7 | ed25519(Wave 0完了) |
| `neighborhood*.rs`(817行) | `kotoba-lang/neighborhood` — validator選出・distinct-validator集計・K/2 閾値判定(`WarrantTally`相当) | R8 | gossip層 |
| gossip本体(`net.rs`の `p2p` feature) | `kotoba-lang/net` の gossip.cljc を拡張(現状 gossip/bitswap の断片のみ、ADR-2607022600 表で「未着手」) | R1, R8 | Wave 5 本体 |
| `engi_chain.rs`(1469行) | `kotoba-lang/engi`(または既存 `kotobase` エコシステム内に新設) — `TransferBody`/`MutualCreditTransfer`/`replay_balance`/`detect_fork`/`audit_peer_chain`/`detect_transfer_forks` をそのまま cljc に移植 | R0, R4, R5 | source-chain, warrant |
| `reputation.rs`(221行) | 信用限度・validator選出の重み付けに必要なら | 補助 | 低優先 |
| `replication.rs`(472行)・`availability_proof.rs`(392行) | データ可用性証明。ENGI単体には不要、kotoba-dht本体の可用性保証機能 | Wave 5本体 | 低優先(ENGI移植のスコープ外) |
| `settlement.rs`(291行) | 手数料の双方署名化(原設計 deferred項目2)に必要 | 未着手 | source-chain, warrant |
| `governance.rs`(394行)・`membrane.rs`(383行) | ネットワーク参加ルール・ガバナンス。ENGI単体には不要 | Wave 5本体 | 低優先 |

### 3. 段階的復活シーケンス(全部を一度に作らない)

1. **`source-chain`**: per-DID チェーンの追記・検証のみ(gossip無し、単一
   ノード内 or kotobase.net 経由で読出し)。commit-dag との概念重複を
   先に解消(ADR-2607022600 の既存宿題)してから着手。
2. **`engi` on source-chain**: v1(ADR-2607101100)の transfer-id 相関方式を
   source-chain の物理連鎖に置き換える。R0/R4/R5 相当のテストを移植。
   この時点で「非EN forkは検知できないが EN forkは検知できる」という
   原設計のR5と同等の状態になる。
3. **`warrant` + `neighborhood`**: 署名済み警告の生成・検証・K/2集計。
   まだ gossip 無しなので、pull型のまま(v1からの実質的な改善は
   「警告の構造がより厳密になる」程度)。
4. **gossip(`net` 拡張)**: push型伝播をここで初めて追加。この段階で
   ようやく原設計 R8/R9 相当の即応性を得る。
5. **`settlement`**: 手数料の双方署名化(deferred項目2)。

各段階は独立にリリース可能で、途中で止めても v1 からの後退にはならない
(段階1だけでも、原設計の「チェーンの物理連鎖」という性質を取り戻せる)。

## Consequences

- (+) v1(ADR-2607101100)が明示的に諦めた3性質への具体的な回復パスを
  文書化し、「必要になったら何を作ればよいか」を先に決めておく。
- (+) 段階的シーケンス(2-1〜2-5)により、フル復活を待たずに部分的な
  強化を選べる。
- (−) 本ADRは**着手しない前提の設計文書**であり、着手条件(§1)を
  満たさない限りコードは書かない。
- (−) `source-chain` と既存 `commit-dag`(Wave 2)の概念重複は、本ADR
  着手前に解消しておく必要がある宿題として明記する(ADR-2607022600
  Consequences で既に指摘されている宿題の延長)。
- (−) gossip層(net拡張)は ADR-2607022600 Wave 5 本体の一部でもあり、
  ENGI専用に先行実装せず、Wave 5 着手時に統合すること。

## References

- `orgs/kotoba-lang/kotoba/docs/ADR-engi-mutual-credit-on-chain.md` — 原設計
  R0〜R9(本ADRの復活マップの直接の参照元)
- ADR-2607101100(engi-mutual-credit-kotobase-native-design)— v1、本ADRが
  強化するベースライン
- ADR-2607101000(kotoba-orphaned-rust-crates-migration-roadmap-addendum)—
  本ADRの親文脈
- ADR-2607022600(kotoba-database-crates-cljc-migration-roadmap)Wave 5 —
  kotoba-dht基盤本体のロードマップ(本ADRが対象を絞って先取りする形)
