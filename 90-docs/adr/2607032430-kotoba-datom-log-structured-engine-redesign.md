# ADR-2607032430: kotoba Datom engine 再設計 — log-structured per-actor substrate（novelty log + 償却 index fold）, browser-first, IPNS-signed heads

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

2026-07-03、etzhayyim の 321 actor / sub-actor を app-aozora に identify する
mass deploy の最中に kotobase engine（`kotobase.aozora.app`）が破綻した。症状は
`error code: 1102`（Cloudflare Worker の CPU 時間超過）。並行度を下げても、graph が
育つにつれ逐次でも恒常的に失敗する段階に達した。実装レベルの根本原因を2本の調査で
確定した:

1. **stateless write cycle = O(graph)。** `kotobase-engine` の書き込みは
   `hydrate-db → transact → commit!`。`hydrate-db` は snapshot の spo tree を
   **全量** cold-scan して hot db を作り直し（O(graph)）、`commit!` は
   `quad-store/commit!` を `prev=nil` で呼んで **prolly-tree 4本を毎回スクラッチから
   全再構築**する（re-flatten + re-sort + re-build + 全ノード put!、O(graph)）。
   **1 datom の write でも graph 全体を作り直す。** read が 22–30s に達し、write が
   CPU 予算を超えたのはこれ。
2. **prolly-tree に incremental insert が無い。** full build のみ。content-defined
   chunking で content-addressed な structural sharing（storage 節約）はあるが、
   compute は毎回全ノード再ハッシュ・再エンコード。「変わらない部分木を再利用する」
   高速路が無い。
3. **commit-dag の head が O(N)。** `head = (last (chain …))` で tip を渡しても
   genesis まで全履歴を walk して tip を返す。「最新 state を得るだけ」で全履歴走査。
4. **単一 operator graph への集約。** PDS が単一 `OPERATOR_SECRET` で全 actor の
   `createRecord` を署名し `canonical-graph(operator, yoro-social)` 1 graph に着地。
   全員のデータ + relay firehose が1つに積もり、上記 O(graph) の graph が
   「全 actor 合計」になっていた。

一方 **canonical doctrine は既に正しい**。ADR-2605312345 は「**kotoba Datom log =
first-class canonical state**」を宣言している。Datomic の本質は log（novelty）と
index の分離 — write は log への append、index は非同期に log を fold して得る派生。
**現行実装はこれを裏切り、log を毎回 index に畳んで snapshot 化している**（log が
無く、write = index rebuild になっている）。

**ADR-2607032300 は layer 分離（authority / firehose / AppView, per-actor graph）で
「どこに置くか」を正した。** 単一 operator graph を撤廃し、権威データを per-actor
graph に、cross-actor 索引を再構築可能な AppView に分ける。これは正しい。だが
ADR-2607032300 自身が明記するとおり、engine 内部の「1 commit = O(graph) rebuild」
構造そのものには踏み込まず、**「incremental-insert は真に重いアクターが出た時の
長期最適化に格下げ」と保留**した。per-actor 化で個々の graph は小さくなるが、
重いアクター（toritsugi の 148 children を束ねる集約、大量 dataset holder、
firehose を供給する AppView shard）では同じ O(graph) の壁に再び当たる。

要件（owner directive, 2026-07-03）:「kotoba-lang, kotobase, prolly-tree, datomic,
eavt, ipld, ipns などを調査して、**cljc で browser 上でも動く、secure かつ非常に
大きいスケールでも対応できる最も美しい設計**を再設計せよ」。

## Decision

**engine 内部を log-structured Datom substrate に再設計する。** ADR-2607032300 の
layer 分離の上で、各 per-actor graph を担う engine 実装を次の構造にし、doctrine
（ADR-2605312345）の log/index 分離を engine level で結実させる。

### D1. Novelty log + 償却 index fold（write を O(tx) に）

commit-dag の `state` は opaque（`{state, prev, seq}` の `state` は何でも入る、
commit-dag は無変更で良い）。この `state` を次の shape にする:

```clojure
{:indexed   <snapshot-cid>   ; prolly 4本 root（logical time :indexed-t まで畳込済）
 :indexed-t <t0>
 :novelty   [<tx-cid> ...]   ; snapshot 以降の未畳込 tx block の CID 列（新しい順）
 :t         <t>}             ; 現在の logical time
```

- **write** = tx の datom 群を dag-cbor block に put!（content-addressed）→ その CID を
  `:novelty` 先頭に prepend → commit-dag に append。**O(|tx|)**。hydrate しない、
  prolly を rebuild しない。
- **read** = `:indexed` snapshot の `cold-datoms`（既に range-pruned 実装済み、
  スケール安全）に、`:novelty` の datom を memory で merge。novelty は fold 閾値
  以下に保たれるので merge コストは小さく有界。retraction は novelty の
  `:added false` datom が snapshot の値を上書きする（Datomic の as-of 意味論）。
- **fold（compaction）** = `:novelty` の長さ（または累積 datom 数）が閾値 K を超えたら、
  background で `:indexed` + `:novelty` を畳んで新 snapshot を build し、`:novelty` を
  空に、`:indexed-t` を前進させる。**full rebuild を K write に1回に償却** →
  write あたり実効 **O(graph_shard / K)**。
- fold は content-addressed ゆえ **決定的**: 同じ（snapshot, novelty）からは誰が・
  いつ畳んでも同じ snapshot CID。server が cron で回しても、browser が idle 時に
  回しても、結果 CID は一致。冪等で分散可能。

これで write cost が現行 **O(total_graph)** から **O(|tx|) + 償却 O(graph_shard / K)**
に落ちる。per-actor（graph_shard 小）× amortize（1/K）の相乗で、CF Worker の CPU
予算に構造的に収まる。**prolly-tree の incremental insert は不要になる** —
ADR-2607032300 が「格下げ」した難題を、log-structured という別経路で回避する
（difficult な部分木再利用でなく、easy な log append + 償却 rebuild で解く）。

### D2. Per-actor content-addressed Datom log = identity の収束（builds on ADR-2607032300）

- 各 actor の graph（`kotobase/db/<actor-did>/<db>`、既存の canonical-graph 導出）は
  「**署名された content-addressed Datom log**」。
- **actor = shard = AT Protocol repo(per-DID) = Radicle RID = kotoba graph =
  IPNS signed head** が一点に収束する。この一致が本設計の美しさの核心 —
  5つの一見別々の設計思想が per-actor content-addressed log-structured Datom
  という単一の構造に畳まれる。
- 単一 operator graph を撤廃（ADR-2607032300 と一致）。write 競合は自分の shard 内
  のみ = 単一 writer（本人）= 競合が構造的に消滅。

### D3. IPNS-signed mutable head（trustless head resolution）

- 可変点は per-actor head 一点。これを **IPNS record（actor 自身の did:key で署名、
  seq 単調増加）** にする。現行の Durable Object lock（`KotobaseTenantLock`）を置換。
- どの gateway / peer からも **署名検証付きで head を取得**できる = trustless。
  「AUTHORITY は鍵由来 IPNS 名への署名であってサーバではない」（etzhayyim CLAUDE.md）
  を初めて実装で満たす（現状は未実装、roadmap Wave 3.5 / kotoba-client docstring に
  follow-up として残っていたものを本設計の一部に格上げ）。
- **firehose**（ADR-2607032300 の層2）= 各 per-actor head の IPNS 前進イベント列。
- **CAS は per-actor head の seq 単調性で成立**。shard 分離で単一 writer ゆえ、
  ADR-2607022330 add.4 で応急実装した head-CAS/リトライは per-actor authority write
  には不要になる（CAS/pruning は ADR-2607032300 のとおり AppView / handle 索引という
  「大きな単一 writer / derived」層にのみ残す）。
- **Base L2 = trust anchor**（ADR-2605312345）: IPNS head が指す commit-DAG root を
  Base L2 に anchor し、trust の連鎖を閉じる。

### D4. Browser-first（injected block store, Merkle-verified reads）

- engine は cljc。block store は injected port `{:put! (fn [cid bytes]) :get-fn (fn
  [cid] bytes)}`。実装を差し替えるだけ: **server = R2 / B2**、**browser = IndexedDB
  + gateway fetch**、**test = in-memory**。engine コアは不変。
- browser は自分の shard を IndexedDB に持ち、**signed IPNS head → prolly Merkle
  proof で trustless read**。`kotoba-client/hydrate-via-blocks` が既に CID mismatch
  reject を実装済み（fetch した block を再ハッシュして CID 不一致なら拒否）。
- write は browser で novelty append → did:key 署名 → gateway push。fold は browser
  idle でも server でも（D1 の決定性ゆえ結果 CID 一致）。
- 「browser 上でも動く」= **同一 engine, port 差し替えのみ**。全 primitive は既に
  JVM/cljs byte-identical CID を出す（multiformats/dag-cbor が portable 化済み。
  ただし nbb でなく real shadow-cljs での検証必須 — ADR-2607022600 add.3）。

### D5. Security（capability writes, signed chain, encrypted attributes）

- **write 認可**: CACAO / UCAN capability（既存 `kotoba-lang/cacao`、resources
  `kotoba://can/<cap>` + `kotoba://graph/<cid>`）。graph は issuer did から導出される
  ため、他人の shard への write は構造的に不可能（tenant write gate test 済み）。
- **integrity**: `commit-dag/verify-chain`（署名 + seq gap 検出、既存）+ read 時の
  prolly Merkle proof。
- **confidentiality**: datom の値 V を `com.etzhayyim.encrypted.*` の
  **XChaCha20-Poly1305 envelope**（ADR-2605181100）で暗号化して格納。CID は
  ciphertext 上で計算するので Merkle 整合はそのまま、AAD が ciphertext を CID に
  bind（record-swap 防止）。per-recipient 鍵は Signal keyWrap（`kotoba-lang/signal`
  既存）。**暗号化≠忘却**（Tier-1 principle, 永久記憶と両立）。

## Architecture

```
                Base L2  (trust anchor, ADR-2605312345)
                     │ anchors commit-DAG root
             IPNS signed head  (per-actor, did:key, seq↑)          ← D3
                     │ resolves to
   commit-dag  state = { :indexed <snap-cid>  :novelty [tx…]  :t } ← D1 (opaque state)
             ┌───────┴───────────────────┐
     prolly snapshot (4 idx)        novelty log (dag-cbor tx blocks)
             │ cold-datoms               │ memory merge (retraction 上書き)
             │ (range-pruned, 既存)       │
             └───────────┬───────────────┘
                    kqe query  (EAVT/AEVT/AVET/VAET routing, 既存)
                         │
   block store port:  R2/B2 (server) ‖ IndexedDB+gateway (browser) ‖ mem (test)  ← D4
   auth: CACAO capability   integrity: verify-chain + Merkle   conf: XChaCha20   ← D5

   actor = shard = AT-Proto repo = Radicle RID = kotoba graph = IPNS head        ← D2
```

## Cost analysis

| | 現状（今夜 1102 破綻） | 再設計 |
|---|---|---|
| write / commit | **O(total_graph)**（hydrate + 4 prolly full rebuild） | **O(\|tx\|)**（novelty append） |
| index fold | 毎 write | K write に1回（**償却 O(graph_shard / K)**） |
| keyed read | O(path) cold + merge | 同（cold は既存 range-pruned、novelty 小） |
| head 取得 | O(N) chain walk to genesis | **O(1)**（IPNS head 直接） |
| graph size / actor | 全 actor 集約（巨大） | **per-actor（小）** |
| write 競合 | 単一 hot graph race → CAS 必須 | per-actor seq 単調（**競合消滅**） |
| browser | 不可（wasm server 依存） | **first-class**（同 engine, IndexedDB port） |

実効 write cost = `O(|tx|) + amortized O(graph_shard / K)`。`graph_shard` は per-actor
（小）、`K` は fold 閾値。今夜の `O(total_graph) / write` から2つの独立軸（sharding と
amortization）で桁で改善し、CF Worker CPU 予算に収まる。

## 既存 primitive の再利用（最小変更で実装可能）

再設計は**新規 primitive をほぼ要さない** — 調査の結論として既存 CLJC 群は全て
正しく、必要なのは engine の write/read path の張り替えだけ:

- **無変更で使える**: `prolly-tree`(snapshot build。incremental 不要)、`quad-store`、
  `kqe`、`cold-datoms`(range-pruned)、`commit-dag`(state opaque)、`ipld`、`dag-cbor`、
  `multiformats`、`cacao`、`signal`、`mst`、`kotoba-client`(hydrate-via-blocks)。
- **変更が要るのは `kotobase-engine` のみ**: `commit!` を novelty-append に、read を
  snapshot+novelty merge に、`fold!`（compaction）を新設。commit-dag の `state` を
  `{:indexed :novelty :t}` に（commit-dag 自体は無変更、state は opaque）。
- **格上げ**: IPNS head 署名（現 follow-up）を本設計の必須要素に。

## Migration（段階、ADR-2607032300 の wiring に接続、各段独立着地・後方互換）

0. **doctrine 確定（本 ADR）** — engine internal 再設計を canonical に。
1. **novelty-log write path**（`kotobase-engine`）: `commit!` を novelty-append 化、
   read を merge 化、`fold!` 追加、tests（novelty 空 = 現状 snapshot-only と等価な
   ので後方互換）。
2. **per-actor graph 配線**（ADR-2607032300 step 1–2）: PDS write を actor 鍵署名、
   read を per-actor graph へ。
3. **IPNS head 署名**（D3）: Durable Object lock → IPNS record。firehose を IPNS
   前進イベント列に。
4. **browser engine**（D4）: IndexedDB block store port、Merkle-verified read、
   browser write + sign。
5. **encrypted attributes**（D5, ADR-2605181100 wiring）: XChaCha20 envelope を
   datom V に。

## Consequences

- write が O(tx) になり、今夜の 1102 破綻が**構造的に**解消（応急 CAS/リトライへの
  依存を断つ）。
- browser が first-class runtime に。ユーザーが自分の shard をローカルに持ち、
  trustless に読み書き。offline-first / local-first が自然に成立。
- 「actor = shard = repo = RID = graph = signed head」の収束で、AT Protocol /
  Radicle / IPFS / IPNS / Datomic の設計思想が単一構造に畳まれる（設計の美しさ）。
- ADR-2607032300 の「incremental-insert 格下げ」判断を尊重しつつ、log-structured で
  write O(tx) を達成（難しい部分木再利用を回避）。
- fold の決定性で server/browser どちらが compaction しても結果一致 = 分散
  compaction が可能。
- **trade-off**: read が snapshot + novelty の2ソース merge に（novelty 小なので
  実害は小、fold 閾値 K がチューニング項）。暗号化属性は述語 query に使えない
  （ciphertext。ADR-2605181100 と同じ既知制約、metadata leak は 2605181200）。
- **enforceability の正直な限界**: IPNS 署名検証と Merkle proof の cljs 実装は
  real shadow-cljs で検証必須（nbb は byte 不一致を隠す、ADR-2607022600 add.3）。

## Relationship

- **faithfully-implements** ADR-2605312345（Datom log = first-class canonical state;
  log/index 分離を engine level で結実）
- **builds-on** ADR-2607032300（per-actor authority / firehose / AppView layer;
  本 ADR は各 per-actor graph の engine 内部構造を規定する補完）
- **preserves** ADR-2605262130（no projection layer; kqe arrangements over
  content-addressed blocks）, ADR-2605181100（encrypted envelope）,
  ADR-2607022600（CLJC 移行 roadmap; 本 ADR は engine の target 構造を与える）
- **retires** ADR-2607022330 add.4 の per-actor authority write に対する head-CAS
  応急処置（CAS は AppView / handle 索引にのみ残す、ADR-2607032300 と一致）
