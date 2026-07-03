# ADR-2607032300: aozora 自己主権3層 — per-actor authority / firehose / AppView（共有 authoritative graph 撤廃）

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

app-aozora の kotobase read が壊滅的に遅かった件（ADR-2607022330 addendum 4）を
追い込んだ結果、急性の原因は worker の R2 prefix 潰れ（`goog.object/get` で修正済み）
だったが、その下に**慢性のアンチパターン**が露出した:

> **全アクターの全 write と relay firehose が、たった1つの operator graph
> （yoro-social）に集約されている。**

PDS が単一 `OPERATOR_SECRET` で全 `createRecord` を署名し
`canonical-graph(operator, yoro-social)` に着地させるため、次が連鎖する:

- **contention** — 単一 hot graph の lost-update race。だから並行セッションが
  CAS を実装する羽目になった（ADR-2607022330 add.4: `d465543`/`0f9389f`）。
- **bloat** — 全員のデータ＋外部 firehose が1 graph に積もり、どの keyed read
  （getBackup/getAccount/resolveHandle）も他人のデータを walk する。
- **O(n)/commit rebuild** — `commit!` が毎回4 index tree を全量 rebuild するため、
  1 datom の write でも単一巨大ツリーを作り直す。read が 22-30s に達したのもこれ。

一方 kotobase/CACAO は**元から自己主権 per-actor graph 設計**（CLAUDE.md:「actor
ごとに鍵を発行し、その鍵由来 IPNS 名がその actor の graph … owner hand-off も
共有 token も要らない」）。そして **ADR-2607022330 の key-backup / device-link で
各ユーザーは既に可搬な自分の鍵を持つ**＝ per-actor 署名の前提が揃った。

さらに **AppView 層は既に部分的に存在**する（ADR-2607021400: `yoro-social-v1`
projection、`com.etzhayyim.yoro.*` XRPC、`getAuthorFeed`）。問題は authoritative
write をこの派生層でなく operator graph に混ぜていたこと。

## Decision

**権威(authority)と集約(aggregation)を分離し、3層に配線し直す。**

| 層 | 何を持つ | 性質 | writer |
|---|---|---|---|
| **1. Authority（per-actor graph）** | 自分の post/like/follow/key-backup | 小・署名済み・自己主権 | **本人の鍵のみ（単一）→ 競合なし** |
| **2. Firehose** | 各 per-actor commit-dag head の前進（署名イベント列） | append-only stream | — |
| **3. AppView（派生索引）** | feed / 通知 / thread / 検索 / count | **派生・再構築可能・shard 可能** | aggregator（少数 / sharded） |

原則:

- **cross-actor（"graph-over"）なデータは AppView にのみ置く。authoritative な
  共有 graph には決して入れない。** cross-actor データは「真実」ではなく per-actor
  の真実に対する**射影**だから。
- **AppView の各項目は `at://` + CID で著者の署名済み per-actor graph に検証可能に
  戻れる。** 派生だが「信頼するが検証できる」。権威は常に per-actor 側。
- **authoritative な共有 operator graph は撤廃する。** 本質的にグローバルで
  authoritative な索引は **handle→did の1つだけ**（最小限）に切り詰める。

## Consequences

- **CAS / pruning / incremental-insert の居場所が正される。** これらは全て
  「大きな単一 writer / derived」向けの道具。**AppView（派生・再構築可能）と
  handle 索引**に移せば低リスクで正当。**権威層はこの複雑さから解放**される。
  → 並行セッションの CAS work は *AppView / handle 索引に限定すれば正しい投資*、
    per-actor authoritative write には *不要*。
- **「肥大したら reset」が設計上正当な操作になる。** AppView は派生なので壊れたら
  firehose 再生で作り直せる。権威データ（per-actor graph）は消えない。
- **read が本質的に速い。** getBackup/getAccount は per-actor の極小 graph を読む
  → pruning は必須でなく bonus。feed/通知は AppView の 1 lookup。
- **write が本質的に速い。** 各 actor は自分の小さな graph に直列に書く
  → O(n) rebuild が per-actor 分（小）に有界。incremental-insert は「真に重い
    アクターが出た時」の長期最適化に格下げ。
- **eventual consistency**（firehose 遅延）を cross-actor view で受け入れる
  — feed/通知には許容範囲（ATProto と同じ割り切り）。
- **backfill**: 新 AppView は firehose 再生 / per-actor graph クロールで初期化。

## Wiring / migration（順序）

1. **PDS write 署名を actor 鍵へ.** クライアントが *ユーザー自身の鍵*（key-backup
   で保持）で CACAO 署名 → worker は既存の `canonical-graph(issuer, db_name)` で
   **ユーザー自身の graph** に着地。PDS は per-actor secret を持たず validator/relay
   に降格。
2. **read を per-actor graph へ.** `getBackup(did)`/`getAccount(did)` は *その did
   の graph*（`canonical-graph(did, …)`）を読む。
3. **relay-cron を AppView 供給へ付け替え.**（現在 off）外部 firehose ingest を
   operator authoritative graph でなく **AppView（`yoro-social-v1` projection）**へ。
4. **feed / 通知 / thread を AppView 索引化**（`com.etzhayyim.yoro.*`）。各項目は
   著者 per-actor graph に検証可能に link back。
5. **handle→did を専用の最小グローバル索引に.** CAS + pruning はここに集約。

## Relationship

- **builds on** ADR-2607022330（key-backup / device-link = per-actor 鍵の可搬性 =
  本 ADR の前提）。
- **re-scopes** ADR-2607022330 addendum 4 の CAS work（`d465543`/`0f9389f`）:
  共有 authoritative graph でなく AppView / handle 索引の道具として位置づけ直す。
- **integrates** ADR-2607021400（`yoro-social-v1` / `com.etzhayyim.yoro.*` AppView）
  を「3層のうち第3層」として正式に位置づける。
- **supersedes** 暗黙の「単一 operator yoro-social を authoritative graph とする」設計。

## 一行まとめ

**自分のもの = per-actor 自己主権 graph（小・署名・競合なし）。graph-over なもの =
firehose から作る AppView（派生・再構築可能・自由に最適化）。権威と集約を混ぜない。**
