# ADR-2607092100: elastic hashing の kotoba / kotobase への適用性評価 — 不採用、分散 read path は fetch 数（novelty bloom filter）で最適化する

- **Status**: accepted（分析に基づく不採用決定 + 代替方向の採択。個別実装は別タスク/別 ADR）
- **Related**: `orgs/kotoba-lang/{kotoba, kotobase, kotobase-peer, arrangement, prolly-tree}`、
  ADR-2607050500（`visible?` first-class effect）、ADR-2607061200（datalog staged roadmap）

## Context

オーナー依頼（2026-07-09）: elastic hashing の紹介記事
（medium.com/@py.js.blog、実体は Farach-Colton / Krapivin / Kuszmaul
**"Optimal Bounds for Open Addressing Without Reordering"**, FOCS 2024,
arXiv:2501.02305）を踏まえ、kotoba / kotobase で使うとより効率的になりうるか。
追加質問として「kotobase は分散 storage + EAVT datomic index chain だが、
分散 storage の query/index 効率化に活用できないか」。

### 論文の要点

対象は **open addressing（単一フラット配列 + プローブ列）で既存要素を並べ替えない**
テーブル。空き率 δ（負荷率 1−δ）に対し:

- **elastic hashing**（非 greedy）: 挿入の期待プローブ数 amortized O(1)、
  worst-case expected O(log δ⁻¹) — Yao の 1985 年予想（greedy uniform probing の
  Θ(δ⁻¹) が最適）を反証。
- **funnel hashing**（greedy）: worst-case O(log² δ⁻¹) w.h.p.（greedy では最適）。

利得レジームは「**固定容量配列を 97〜99%+ まで詰める**」場面（δ→0）に集中する。

### 実装調査（2026-07-09、実コード精査）

**kotoba**: 自前の map/set 実装はゼロ（全て host `clojure.lang` HAMT）。
`kgraph`（`src/kotoba/kgraph.clj:24-95`）の EAVT ストアは `[e a v]` ベクタの
線形 filter スキャン（O(n)、index 無し）。`kotoba wasm` subset には map 型が
存在せず、guest にハッシュ構造は一切コンパイルされない（文字列定数は静的
オフセット、関数は element section の位置 index）。SHA-256 / FNV-1a /
splitmix64 は content addressing・疑似 CID・ゲーム RNG 用途でテーブル配置には不使用。

**kotobase**: hot db は 4-covering index `:spo/:pso/:pos/:ocp` のネスト host HAMT
（`arrangement/core.cljc:30-81`）。cold index は prolly-tree =
content-addressed **sorted** B-tree（boundary-bits=8 → 平均 fanout ~256、
比較ベース木降下 + prefix range-pruning）。永続化は
`{"indexed" Link "novelty" [Link…]}` の commit chain で **LSM 同型**
（snapshot + 追記列）。blind index は成分ごとの HMAC-SHA256 で prefix 構造を
保存し sorted tree に格納。datalog join（`arrangement/datalog.cljc:244-326`）は
nested-loop。唯一のキャッシュは `(atom {})` の cid→bytes host map。
**手書きの open-addressing 構造はどこにも存在しない。**

## Decision

**elastic hashing は不採用**（適用対象が存在せず、分散 content-addressed EAVT
とは前提が構造的に衝突する）。理由は 5 点:

1. **適用対象ゼロ** — 両コードベースとも自前の open-addressing テーブルを
   持たない。HAMT は trie でありプローブ列の概念自体が無い。
2. **コストモデルが 10⁵〜10⁶ 倍ズレる** — 分散読みの単位は block fetch
   （R2/IPFS、~10-100ms）で probe（~ns）ではない。支配項は逐次依存 fetch 数で、
   prolly-tree は fanout ~256 により 10⁶ datom でも深さ 2-3。
3. **挿入順依存 vs content addressing** — reordering しない open addressing は
   レイアウトが挿入順に依存する（それが前提条件そのもの）。kotobase snapshot の
   「同じ db → 同じ root CID」という history-independence（構造共有・dedup・
   差分同期・検証可能性の要）と正面衝突する。**chain/commit モデルでは失格。**
4. **利得レジームが発生しない** — 分散 index は詰まる前に chunk 分割・rebalance
   するため δ→0（97-99%+ 充填の固定容量）を設計上作らない。
5. **EAVT は range scan が本体** — `datoms :eavt/:aevt/:avet` / `scan-prefix` は
   prefix 範囲走査であり、ハッシュ配置は順序を破壊する。導入しても point-lookup
   専用の追加 index にしかならず、置き換え不能。

### 代替として採択する方向（「分散 query/index をハッシュで速くする」の正答）

投資先は probe 数でなく **fetch 数**。効果順に:

| # | 施策 | 期待効果 |
|---|---|---|
| 1 | **commit ノードに novelty block ごとの bloom/ribbon filter（数百バイト）を同梱**。point query 時に「含まれ得ない block の fetch をゼロ化」。kotobase の snapshot+novelty は LSM 同型で、LSM で実証済み。filter は集合に対し決定的に構築でき content addressing と矛盾しない | 分散 point-read の fetch 数を桁で削減（最も割が良い一手） |
| 2 | boundary-bits チューニング（8→7 で平均 512 entries/chunk）+ multi-get バッチ化で木降下の逐次 fetch 数を削減 | 深さ 1 段分の latency 削減（block サイズと実測トレードオフ） |
| 3 | `arrangement.datalog` の join を binding 集合が大きい時に **hash join** へ切替（通常の host map で十分、elastic 不要） | 大結果集合での漸近改善 |
| 4 | 順序不要・point のみの大規模分散マップが必要になったら **IPLD HAMT**（Filecoin state tree 方式。決定的で挿入順依存が無い） | heads registry 等の point lookup |

実装着手はそれぞれ別タスク/別 ADR（本 ADR は不採用決定と方向付けのみ）。

### 将来メモ（funnel hashing が候補に載り得る唯一のスポット）

論文のレジームが字義通り一致し得るのは (1) `kotoba wasm` ランタイムに native map
型を実装する時の線形メモリ内フラットテーブル、(2) ローカル block cache を
固定サイズ常駐 cache に作り替える時、の 2 箇所のみ。着手時の候補リストに
funnel hashing（greedy で実装が素直）を入れる程度の位置づけ — 実測では
Swiss table / Robin Hood 系が定数・キャッシュ局所性で勝つ公算が高い。

## Consequences

- elastic hashing 導入のためのコード変更は行わない（現時点でアクション無し）。
- `kotoba/kgraph.clj` の O(n) 線形スキャンは本件と無関係の既知の改善余地
  （普通の index を 1 枚張るだけで桁改善）— 着手する場合は別タスク。
- 分散 read path 最適化の第一手は novelty bloom filter 同梱（上表 #1）。着手時は
  filter フォーマット・偽陽性率・commit ノード肥大のトレードオフを別 ADR で設計。
- 記事ソース（Medium）はペイウォールで全文未取得だが、対象論文
  （arXiv:2501.02305）は特定済みで、分析は論文側の内容に基づく。
