# ADR-2607023200: kotoba-lang/ipld・p2p 新設、tag-42 canonical 化の実施、Datomic 参照の EAVT 設計

**Status**: accepted（ipld / p2p / tag-42 移行は実装・検証・push 済み。§6 EAVT 設計のみ proposed）
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

オーナー指示「kotoba-lang/ipld, p2p, datom repo を作って、より綺麗に整理」
（方針確認済み: ①net は意味論のまま残し p2p を新設 ②ipld は canonical 層として
既存 4 repo を改修 ③EAVT は Datomic 設計を参考に本 ADR へまとめる）。

前提となる棚卸しは ADR-2607022600（DB crates CLJC 移行ロードマップ）。そこで
確認された既知のギャップのうち、本 ADR は次の 2 つを**実際に閉じた**:

1. **plain-CID-string 問題**: prolly-tree / quad-store / commit-dag /
   kotoba-client は「child/commit への参照は CBOR tag-42 の真の IPLD リンク
   ではなくプレーンな CID 文字列（`cbor.core` が CBOR tag 未実装のため）」
   という honesty note を各 README に明記して出荷されていた。
2. **P2P 実体の不在**: `kotoba-lang/net` は gossip/bitswap の**意味論のみ**
   （「ソケットを開かない・dial しない・ハンドシェイクしない」と自己申告）で、
   commit chain を実際にピア間同期するプロトコルはどこにも無かった。

## Decision

### 1. dag-cbor に CBOR tag（major type 6）を追加

`kotoba-lang/dag-cbor` `9c60d583`。明示的な `Tagged` ラッパー
（`tagged`/`tagged?`/`tag-number`/`tag-value`）のみ — プレーンなデータが暗黙に
tag になる／tag がプレーンに劣化する経路は無い。codec 汎用（tag-42 の意味論は
ipld 側）。フィールドアクセスはアクセサ経由のみ（nbb が deftype の `.-field`
未実装である既知問題の再発防止）。

### 2. kotoba-lang/ipld 新設（canonical IPLD 層）

`fe67f8e4`（public）。multiformats（CIDv1 組み立て）+ dag-cbor（canonical
CBOR + tag）を合成し、DAG-CBOR 仕様のリンク規律を一箇所で持つ:

- リンク = tag 42 が `0x00（identity multibase prefix）++ バイナリ CID` の
  byte string を包む — `ipfs dag get` / go-ipld-prime / js dag-cbor が期待する
  wire form そのもの（テストで `d82a582500 01711220…` のバイト列を直接検証）。
- ブロック内で合法な tag は 42 **のみ**（DAG-CBOR 仕様）: `decode` は他 tag で
  throw、`encode` は生の `cbor/tagged` と非文字列 map key で throw。
- アプリデータ上のリンクは明示的な `Link` ラッパー（`link`/`link?`/`link-cid`）。
- `links`（深い汎用リンク収集）が hydrate/GC の唯一の walk になる。
- storage は従来どおり `put!`/`get-fn` 注入（`put-node!`/`get-node`/`node->block`）。

### 3. tag-42 移行（既存チェーン 6 repo）

| repo | SHA | 変更 |
|---|---|---|
| prolly-tree | `8da6d1e1` | internal node の children が `[max-key Link]` に。境界判定は CID 文字列のまま（tree shape 不変、encoding のみ変更） |
| quad-store | `ecb99b8f` | commit block の index-roots / prev が Link に（空 index は null） |
| commit-dag | `05cc0473` | prev が Link に（genesis は `""` でなく null）。state は opaque のまま（呼び出し側が Link を入れれば walk 可能） |
| kotoba-client | `92cbadab` | `ipld-hydrate/missing-cids`（汎用 tag-42 walk）新設。prolly 固有の `children` walk は廃止し `prolly-hydrate` は委譲 facade に |
| kqe | `9901532a` | pin bump のみ（hot query は無変更） |
| kotobase-engine | `60d922b9` | chain state を `(ipld/link snapshot-cid)` に。chain→snapshot→index→tree が途切れない単一リンクグラフになった |

**全 CID が変わる破壊的変更**だが、本番 kotobase.net は削除済み Rust エンジンの
wasm ビルドで動いており（ADR-2607022600 Consequences）、旧フォーマットの
ブロックを消費する系は存在しない — クリーンブレークと判断。

検証: 全 repo で JVM + **実 shadow-cljs**（nbb ではない）の両方を green にし、
kotobase-engine の e2e（500 quads → transact → commit!）で **JVM と node が
byte-identical な CID** を出すことを再実測
（`bafyreiaakutsdtndrl7e7emcmkp5hjsaaq2vu6prfelbgaglprvtdon63m`）。

### 4. kotoba-lang/p2p 新設（graph-sync プロトコル）

`4cca34d0`（public）。**net はそのまま**（意味論ライブラリ、方針確認済み）で、
p2p が合成層: gossip 意味論（fanout/dedup）+ bitswap `commits-since` +
commit-dag 検証 + kotoba-client の tag-42 hydrate。

- 5 メッセージ（head-announce / want-since / commits / want-blocks / blocks）
  の pure state machine `(handle node msg) → {:node :effects}`。副作用は注入
  された block store ports 経由のみ。transport は host 側
  （`kotoba.p2p.loopback` が決定的な in-memory 参照実装）。
- **tag-42 化の直接の配当**: chain prev / snapshot state / index roots /
  tree children が全部本物のリンクなので、「グラフを同期する」=「announce
  された head CID を収束まで hydrate して verify-chain」に潰れ、スキーマ別の
  転送ロジックが 1 行も無い。
- 安全性/liveness の分離: CID 不一致ブロックは skip（保存しない）、同一
  want-set が進捗なしで再来したら pending を破棄（lying peer は同期を
  止められるが状態は汚せない）、verify-chain が通らない chain は採用しない。
- e2e テスト: 実 kotobase-engine chain の 2 ノード収束（受信側 store 単独での
  prolly 読み出しまで）/ 3 ノード直列トポロジ（採用後 re-announce による
  hop-by-hop 伝播）/ 重複 announce の不活性 / lying peer の安全停止。
  JVM + 実 shadow-cljs 両 green。

副産物: p2p の cljs スイートが `kotoba-lang/net` の**実バグ 2 件**を検出・修正
（`9e850649` goog.crypt require 漏れ、`20d99d1e` :clj-only `format` 使用 —
どちらも「nbb では見えない実コンパイラ問題」の追加実例。ADR-2607022600
追記 3 の教訓を補強）。

### 5. datom の境界（repo は新設しない）

- **`kotoba-lang/datom` は現状維持**: CAE/vehicle-design 系 actor 群の
  データ表現ライブラリ（entity→`[e a v]` フラット化、39 行）。複数ドメインが
  依存しており、kotoba DB の EAVT とは役割が別。
- **kotoba DB 側の EAVT の正は quad-store 系列**（→ §6 の進化設計）。
  `kotoba-lang/kotoba` の `kgraph.clj`（インメモリ EAVT、63 行）は quad-store
  への一本化対象として retire 候補（follow-up、本 ADR では未実施）。

### 6. EAVT 設計（Datomic 参照、proposed）

現 quad-store は `(S,P,O)` の hot 4-index + full-snapshot commit。Datomic の
5-tuple `(E,A,V,Tx,Added)` へ寄せる設計を以下に固定する（実装は
ADR-2607022600 Wave 2/3 の後続として個別 ADR で着地させる）:

1. **Tx = commit-dag そのもの。** 別の tx log は作らない。ADR-2606041151
   「CommitDag IS the WAL」を継承し、`T` は commit の `seq`、tx entity は
   commit block の CID（= content-addressed な tx id）。
2. **delta commit。** commit block に `{"assertions" [...] "retractions" [...]
   "snapshot" <Link|null> "prev" <Link> "seq" n}` を積む。full snapshot は
   N commit ごとの checkpoint に降格（再生コスト O(replay) を有界化）。
   `as-of T` = 直近 checkpoint から T まで replay、`history` = chain walk、
   `since T` = T 以降の delta 連結。Datomic の time-travel API と同型。
3. **Added。** retraction は `retractions` 側に積む（tombstone）。現
   `retract-quad` の hot 挙動は不変。
4. **Index 対応。** `EAVT/AEVT/AVET ← spo/pso/pos`（命名は quad のまま）、
   `VAET ← ocp`。**`ref?` 述語は「値が `ipld/link?` か」に自然化**する —
   entity 参照を Link で書けば reverse index が自動で付き、かつ p2p hydrate
   がその参照を辿れる（アプリの参照とストレージのリンクが一本化される）。
   TEA 相当（tx→datoms）は §6-2 の delta commit 自体が担う。
5. **型付き値。** dag-cbor canonical プロファイル（int / string / bytes /
   bool / null / list / map / **Link**）を値域とする。EDN keyword・float は
   wire に持ち込まない（現 `v_edn` の pr-str は XRPC 表層の互換シリアライズに
   限定し、index キーには使わない）。
6. **Schema は datom で自己記述**（Datomic 同様 `:db/cardinality`
   `:db/unique` 等を同一グラフに積む）。cardinality-one の上書き・unique 制約
   は transact 時に enforce。follow-up。
7. **Entity id。** 外部 id 文字列（現状）または Link（content-addressed
   entity）。tempid 解決は engine 層（kotobase-engine）の責務。

### 7. 明示的 follow-ups（fabricate しない）

- p2p: **署名付き announce（CACAO）** — announce の鮮度は現状 verify-chain +
  CID 検証まで（safety は成立、freshness は未保証）。kotoba-client の
  IPNS-verify gap と同根で、CACAO 配線（ADR-2607022600 Wave 4）に合流。
- p2p: 実 wire transport（QUIC/WebRTC/WebTransport）と discovery
  （Kademlia 相当）は host adapter として別途。
- prolly-tree: **range diff 同期**（want-blocks が missing subtree 全転送 →
  共通部分 skip の Dolt/Noms 方式）。range-pruned scan / diff/merge / GC の
  既存 follow-up と同じ束。
- quad-store: §6 の delta commit / 型付き値 / cold query（ADR-2607022600
  Wave 2 の残件と同一線上）。
- `kgraph.clj` retire（§5）。
- net の cljs CI 追加（今回のバグ 2 件は p2p 側 CI が検出網。net 自身にも
  shadow-cljs ジョブを足すのが筋）。

## Consequences

- (+) 「IPLD です」が README の主張ではなく wire 事実になった（generic IPFS
  ツールで tree/commit が walk できる）。honesty note 2 件を実装で閉じた。
- (+) p2p 同期がスキーマ非依存の 1 プロトコルに潰れた（tag-42 の複利効果）。
- (+) manifest 登録: `orgs/kotoba-lang/ipld`（pin `fe67f8e4`）、
  `orgs/kotoba-lang/p2p`（pin `4cca34d0`）。west.yml は `--entry ipld,p2p` の
  最小 diff + サーバ側 pin 検証 OK。
- (−) 全 CID が変わった。旧 CID を記録した外部システムは存在しない前提
  （本番は Rust wasm 経路）だが、もし旧フォーマットのブロックが発掘されたら
  それは読めない（意図的に互換層を作らない）。
- (−) §6 は設計のみ。実装 ADR が別途必要（Wave 2/3 の残件と合流）。
- (±) 検証は「実 toolchain で」を徹底（nbb 不使用）。その過程で net の実バグ
  2 件を修正 — 「.cljc を名乗る JVM-only コード」はまだ残存しうる、という
  ADR-2607022600 の教訓の再確認。

## References

- ADR-2607022600（DB crates CLJC 移行ロードマップ / 棚卸し・訂正 3 本）
- ADR-2607010930 Phase 6（prolly-tree/quad-store/kqe/kotoba-client 初回 landing）
- ADR-2606041151（CommitDag = WAL）
- ADR-2607023100（murakumo × net p2p 意味論 — net を意味論層として使う別系統の設計。
  本 ADR の p2p はその上の合成層で、net の位置づけは両 ADR で一致）
- kotoba-lang repos（すべて main / public）:
  dag-cbor `9c60d583` / ipld `fe67f8e4` / prolly-tree `8da6d1e1` /
  quad-store `ecb99b8f` / commit-dag `05cc0473` / kotoba-client `92cbadab` /
  kqe `9901532a` / kotobase-engine `60d922b9` / net `20d99d1e` / p2p `4cca34d0`
