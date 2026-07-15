# ADR-2607032700: aozora per-actor 本番ロールアウト設計（Level B 自己主権化 + scale levers）

**Status**: accepted
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

per-actor 3層アーキテクチャ（authority=per-actor graph / firehose / AppView、
ADR-2607032300）は staging で**コード完了・live 検証済み**:

- staging cutover（`PER_ACTOR_DB=1` + fresh `YORO_DB_NAME=yoro-social-v2`）: read
  federation・AppView projection・`prepareWrite→commitSigned→getRepo` の write→read
  roundtrip すべて green。
- **D1 novelty-log（`commit!` = O(tx) append、fold は分離）**が本番デプロイ・E2E 検証済み
  （worker `9b76e276`）。write が O(graph) → O(tx) になった。
- datom model は language/DB/transport の全3層で datom-clj に統一（ADR-2607032500）。

残るは **本番ロールアウト**。この ADR は (1) 本番 enablement の手順、(2) Level B 自己主権化
（custody 移行）、(3) scale levers（#18 並列トランポリン ほか）を **3 フェーズ**に順序づける。

## Decision — 段階的ロールアウト

reversible・staged を貫き、**custodial per-actor（Level A）で本番化 → self-sovereign（Level B）
で custody 移行 → scale 最適化**の順。一度に全部やらない。

### Phase 1 — 本番 enablement（custodial per-actor, Level A）

現状 `actorkey` は operator master から per-actor 鍵を HKDF 派生する **custodial** モデル
（operator が任意 actor の鍵を再導出可能 = 今日の operator-key と同じ信頼境界）。この Level A で
まず本番化する（write が per-actor graph に散り contention/bloat/O(n) が消える利益を先取り）。

1. **データ方針**: **fresh-start**（本番も `yoro-social-v2` 相当の新名前空間で開始、旧
   `yoro-social` は非破壊で残置・archive）。既存 test data 廃棄 OK 方針と整合。もし本番に
   保持すべき real account があれば、**per-actor graph への再 transact（backfill）**経路を用意
   （firehose/AppView は projection で再構築可能）。
2. **段階投入**: Cloudflare gradual deployment で canary（一部 traffic）→ 全体。`PER_ACTOR_DB`
   は env フラグなので per-deploy で切替。
3. **監視**: read latency（getBackup/getAccount/getAuthorFeed）・write latency・**AppView
   projection lag**・**per-actor graph 数/サイズ分布**・**novelty 深さ（未 fold tx 数）**・
   error rate。閾値超で alert。
4. **fold 運用**: D1 の novelty は読むたび merge される（未 fold が増えると read の block fetch が
   増える → #18 と直結）。**cron/ops が定期 `do-fold`** を回し novelty を snapshot に compaction。
   content-addressed で冪等・任意 writer 安全。
5. **rollback**: `PER_ACTOR_DB=0` で shared db に即復帰（reversible）。yoro-social-v2 は残す。
6. **handle→did index**: 唯一の本質的グローバル authoritative 索引。専用の小さな index に切り出し、
   CAS + pruning + incremental をここに集約（他は per-actor で不要）。

### Phase 2 — Level B 自己主権化（custody 移行）

Level A の custodial 鍵（operator が派生可能）を、**actor 自身が保持する鍵**（key-backup、
ADR-2607022330）での**クライアント署名**に移す。operator は署名不能になり、真の自己主権
（CLAUDE.md kotoba-server 節「owner hand-off も共有 token も要らない」）に到達。

- **配線**: クライアント（appview cljs）が key-backup から復元した actor 鍵で **write CACAO を
  クライアント署名**（`kotobase.cacao` の byte-exact 経路をクライアントへ移植）。worker は既に
  `canonical-graph(issuer, db_name)` で issuer の graph に着地させるので、**actor-signed CACAO
  ならその actor の graph に構造的に着地**（サーバ変更は最小）。
- **PDS の降格**: PDS は per-actor secret を持たず **validator/relay** に降格。`actorkey`
  （custodial 派生）は移行期の fallback として残す。
- **migration（dual-accept）**: (a) operator-signed も actor-signed も受理 → (b) クライアントを
  全面 actor-signed に → (c) operator custody（master secret 由来の署名）を撤去。各段は reversible。
- **前提**: key-backup / device-link（ADR-2607022330）が本番で堅牢に動くこと（passkey-PRF wrap /
  QR device link / recovery phrase）。

### Phase 3 / cross-cutting — scale levers（必要に応じて）

per-actor + D1 で日常規模は既に速い。以下は**真の scale / 大 graph（AppView index）向けの保険**。

- **#18 worker トランポリン並列化**: `r2.cljc` の block-miss トランポリンは逐次 fetch。**D1 で
  relevance が増した** — read は snapshot tree + **未 fold novelty blocks を逐次 fetch** するため、
  fold 前に novelty が溜まるほど遅い。1 run で全 miss 収集 → 並列 fetch（O(depth) round）に。
  ⚠ `r2.cljc`/`handler.cljc` は D1/CAS で並行セッションが編集する領域 → **調整して実装**。
- **incremental prolly-tree insert**: D1 で `commit!` は O(tx) になったが `fold!`(compaction) は
  依然 O(graph) rebuild。真に重い AppView index には incremental insert（構造共有）が効く。
- **relay ingest の別 db 分離**: 外部 firehose は operator/AppView と別 db（Phase 1 で cron off 済み）。

## Consequences

- **順序が安全**: Level A（custodial, サーバ変更最小）で本番化 → Level B（custody 移行）→ scale。
  一度に全部やらないので各段が検証・rollback 可能。
- **contention/bloat/O(n) は Phase 1 で構造的に解消**（write が per-actor に散る）。
- **自己主権は Phase 2 で完成**（operator が署名不能）。
- **scale levers は on-demand**（per-actor + D1 で日常は足りる。#18 は novelty 深さ/大 graph が
  問題化したら）。
- 依存の一貫性は `check-foundation-deps.cljs`（CI）、pin 検証は `verify-west-pins.cljs`（CI）が維持。

## Follow-up（実装タスク）

- Phase 1: 本番 `PER_ACTOR_DB=1` + fresh 名前空間 + canary + 監視 dashboard + 定期 `do-fold` cron。
- Phase 1: handle→did 専用 index の切り出し。
- Phase 2: クライアント側 CACAO 署名（key-backup 鍵）+ dual-accept + custody 撤去。
- Phase 3: #18 並列トランポリン（並行セッションと調整）/ incremental fold。

## 一行まとめ

**custodial per-actor（Level A）で本番化 → key-backup 鍵のクライアント署名で自己主権（Level B）→
必要なら scale levers（#18 並列トランポリン等）。各段 reversible・staged。**
