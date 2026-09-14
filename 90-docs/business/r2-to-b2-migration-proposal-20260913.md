# R2 → B2 移行（A, C, B の順で実施）
2026-09-13 実測に基づく。全数値は GraphQL Analytics API / wrangler / B2 native API の実クエリ。

## 着地状況（2026-09-13）

| Plan | 状態 | 着地 |
|---|---|---|
| C-1/C-2 (Worker の B2 primary rung) | **LANDED** | net-kotobase/ipfs merge `0d13841`（PR #67）|
| Worker への B2 secrets 設定 + 0.24.2 再deploy | **LANDED** | 2026-09-13、`b2_configured=true` / `b2_primary=false`（legacy rung順、挙動不変を /_app/meta と bytes 200 で確認） |
| R2 S3 token 再発行 | **LANDED** | オーナー発行 → kagi `CLOUDFLARE_R2_ACCESS_KEY` に保存、`aws s3api` で両 bucket 読み取り確認 |
| C-3 sync (ipld/ ipni/ ipns/) | **進行中** | rclone copy、B2 側 listing 発見で総量 ~24.6k objects に増加 |
| 切替 (KOTOBASE_ORIGIN_B2=1) | **LANDED** | 2026-09-14 04:05 — deploy version `ce8d5a13`。`b2_primary=true`、サンプル 2 block の sha256 が R2↔B2↔両zone で一致、404 control OK。latency: 大 block で +0.7s（CF cache で hot は吸収） |
| ⚠ **A のHard blocker** | **BLOCKED（恒久）** | **B2 S3 PutObject は If-None-Match も If-Match も NotImplemented（2026-09-13 実測）。HeadCAS / retention lease / compaction lease が B2 で動かない** — merkle-lsm の mutable 層は R2 に残すしかない。immutable block のみ移すと head 読みが毎回 R2 を叩くため ops 節約はほぼゼロ |
| A (local-murakumo flip) | **中止** | 上記 blocker により。retention trio S3 backend（4a75ff）は B2 対応準備として残置（B2 が条件付き PUT を実装したら使える） |
| B (merkle-lsm の rclone copy) | **standby copy として完了予定** | hot path には使えないが disaster recovery コピーとして価値あり |

## 実測: B2 条件付き PUT の不在

```
aws s3api put-object --if-none-match '*'  → NotImplemented
aws s3api put-object --if-match <etag>    → NotImplemented
（bucket: kotobase-cf-wasm-production、B2 S3 endpoint s3.us-west-004、2026-09-13）
```

kotobase-peer の `cas-head!` / `cas-retention-root!` は R2 のみを想定した
MERKLE_S3_CONDITIONAL_HEAD ゲート付き実装 — B2 では HEAD CAS（graph publication の
唯一の可変点）と retention lease が成立しない。engine の CAS 機構自体の変更（別方式:
compare-and-swap via object versioning 等）は engine surgery で別 ADR を要す。

## 0. 実測サマリ（なぜ移行するのか）

R2 全口座の実請求（過去30日、2026-08-14 → 09-13）:

| 項目 | 実測 | 月額 |
|---|---|---|
| Class A (書き込み) | 3,112,825 ops（無料枠 1M 超過） | $9.51 |
| Class B (読み取り) | 43,941,738 ops（無料枠 10M 超過） | $12.22 |
| ストレージ | Standard 192.8 GB + IA 20.6 GB | $3.10 |
| **合計** | | **~$24.8/月** |

元凶の内訳（Class B トップ2）:
1. **kotobase-merkle-lsm**: GET 33,574,924（670.6 GB）+ PUT 2,472,910 → **~$20/月**
   - traffic 出所: `local-murakumo` Worker（murakumo gateway、`*/5` cron + 全 bot route）
   - kx = local-murakumo.merkle-store → kotobase-peer Merkle-LSM → R2 binding `MERKLE_BUCKET`
2. **kotobase-graph-database-production**: GET 7,688,111（19.7 GB）+ PUT 350,850 → **~$3.7/月**
   - traffic 出所: net-kotobase-ipfs Worker + graph DB 本体

この 2 bucket を移すと残余の R2 使用は全て無料枠内（Class B 残 268万 < 1,000万、Class A 残 29万 < 100万）に収まり、**R2 ops 請求は $0**。

| | R2 現状 | B2 移行後 |
|---|---|---|
| 2 bucket のストレージ (148.5 GB) | $2.23/月 | $1.03/月 ($6.95/TB) |
| 2 bucket の ops | ~$23.9/月 | **$0** (Class A/B/C 無料、2026-05-01 発効) |
| B2→CF egress | — | **$0** (Bandwidth Alliance) |
| **合計** | **~$26/月** | **~$1/月** |

## Plan A: kotobase-merkle-lsm（最大の元凶、~$20/月）

### 実測した構造
- `kotobase-peer/src/kotobase_peer/object_store/worker.cljk` は既に **dual backend 設計**:
  - `MERKLE_BUCKET`（R2 binding）または `MERKLE_S3_ENDPOINT/BUCKET/ACCESS_KEY_ID/SECRET_ACCESS_KEY`
    （sigv4 → B2 S3 endpoint で動く、kotoba-lang/sigv4 署名）
  - **dual 対応済み（9 関数）**: put-block / get-block / put-object / get-object /
    find-entities / get-head / cas-head / compare-and-exchange-head（wrapper）ほか
  - **R2 binding 専用（48 関数）**: retention-root trio（get/cas/release — **kx 読みが毎回取る**）、
    GC 一式、database backup/restore 一式、list/delete 系
- merkle-store の読みは reader-retention lease を取るため、**retention trio の dual 化が
  hot path 移行の必須条件**（"currently requires an R2 binding" がソース内のエラーメッセージ）

### 作業手順（提案）
1. `object_store/worker.cljk`: retention-root trio（get/cas/release-retention-root）に
   b2-config branch を追加（既存 9 関数と同じパターン。テストも同じ枠で）
2. GC / backup / restore 系 48 関数は **soak 中は R2 に残してよい**（cron 頻度で低頻度、
   冷たい経路。後日 dual 化 or R2 残しを文書化）
3. 新 B2 bucket（例 `kotobase-merkle-lsm-b2`、allPrivate）+ bucket スコープの application key
   （capabilities: listFiles/readFiles/writeFiles/deleteFiles）
4. データ sync（Plan B と同じ rclone 経路、R2 S3 token 再発行後）
5. **bench を先に**: `kotobase-peer/bench` の既存 receipt 枠で B2 endpoint 実測。
   現在 ~46 読み/秒が R2 binding (~10-30ms/block) → B2 HTTPS fetch (~100-200ms/block) に
   なるため、p95 回帰の定量が必須。bot 応答が 1 リクエストあたり複数 block を読むため
   持ち上がり幅は block 数に比例する
6. local-murakumo: `MERKLE_S3_*` を設定し `MERKLE_BUCKET` binding を外して deploy
7. soak → R2 bucket 削除

### リスク
- latency（上記 5）。B2 側で Class B が無料なので CF cache / バッチングで吸えるが、
  merkle-lsm は MVCC merge で大量の小 block を読む。**bench で閾値を超えたら、
  merkle-lsm は R2 残しで graph-prod だけ移行**（その場合の saving は ~$4/月）が退避案。

## Plan C: kotobase-graph-database-production（最小変更、~$4/月）

### 実測した構造
- R2: 1,002,293 objects / 81.64 GB。名前空間: `ipld/{cid}` + `ipni/head` + `ipns/{k51}`
- B2 kotobase-cf-wasm-production: 748,808 objects / 91.80 GB。
  名前空間: `ipld/{cid}`（738,944 — **R2 より約 257k block 古い**）+ blocks/ + shadow/ +
  `ipns/{graph}.json` + nonces/ + owners/。**`ipni/` は丸ごと無い**
- 重複 CID は byte-identical（8,371,113 bytes のサンプル、sha256 一致を実測）
- net-kotobase/ipfs Worker には b2.cljk（read-only SigV4 client）が既に在り、
  gateway.cljk の **rung 2**（R2 miss 後のフォールバック）として配線済み
- **鍵レイアウト不一致**: b2.cljk は `{prefix}/objects/{cid}` を読むが、B2 の実データは
  top-level `ipld/{cid}`。生オブジェクト GET のみが今日実際に B2 を打っている

### 作業手順（提案）
1. b2.cljk の key 布局を `ipld/{cid}` に合わせ、`ipni/head` と `ipns/{k51}` を追加
2. gateway.cljk: rung 順序を「B2 → R2」に反転（env flag `KOTOBASE_ORIGIN_B2=1` で切替、
   R2 は soak 中の裏経路として残す）
3. sync（下記 Plan B）: B2 に既に 738,944 あるので **実コピーは約 257k objects**
4. 検証: `{cid}.ipfs.kotobase.net` と `{cid}.ipfs.yataverse.com` の sha256 収束 +
   404 control（既存 skill kotobase-bytes-plane の手順 6）
5. soak 7 日 → `KOTOBASE_BLOCKS` binding を外す → R2 bucket を空けて削除

### 注意
- `ipni/head` は publisher が書く pointer。B2 rung が主経路になったら publisher 側も
  B2 に書くか、head のみ R2 残しにするかを決める必要がある（rung 2 に ipni が無いのは
  これが理由）

## Plan B: データ sync（A/C 共通の前提）

### 阻害要因（実測）
- kagi vault の `CLOUDFLARE_R2_ACCESS_KEY` は **SigV4 SignatureDoesNotMatch で死んでいる**
  （rclone と aws cli の両方で実測）— token が失効 or 保管時に破損
- wrangler OAuth には R2 の object list/purge 権限がない（list コマンド自体が無い）

### 必要なオーナー作業（1 つだけ）
Cloudflare dashboard → R2 → Manage API Tokens で
`kotobase-graph-database-production` と `kotobase-merkle-lsm` の両方（または account スコープ）
に **object read 権限**のある S3 API token を作り、kagi に保存。

### sync 本体（オーナー着手後に agent 実行）
```bash
rclone copy --config <conf> \
  r2:kotobase-graph-database-production/ipld \
  b2:kotobase-cf-wasm-production/ipld \
  --s3-chunk-size 8M --checkers 8 --transfers 4
```
- B2 側に既にある 738,944 は rclone が size/etag check で skip（約 257k objects のみコピー）
- コスト: R2 GET は無料枠内、B2 PUT/ingress 無料 → **sync 自体 $0**
- merkle-lsm 用は同様に `r2:kotobase-merkle-lsm → b2:kotobase-merkle-lsm-b2`（2,955,018 objects / 66.9 GB、
  全量コピー。夜間バッチで 2-3 回に分割）

## 実施順序とゴーアイドの切り方

1. **C を先に着地**（変更が最小、b2.cljk が既に在る、失敗時の退避が容易）
2. **B の token 再発行をお願いしてから C-3 の sync**（C-1/C-2 は token 無しで進められる）
3. **A は bench を通ってから**（latency の実測が出るまで local-murakumo の flip はしない）
   - bench が p95 許容内なら全量移行、超えたら merkle-lsm は R2 残し（saving ~$4/月のみ）

## 検証（完了条件）

- [ ] 両 zone で同 CID の sha256 収束（bytes 検証、既存 skill 手順）
- [ ] 404 control の一致（不在 CID も同一 Worker 経由と証明）
- [ ] `/_app/meta` の `b2_configured: true` / `r2_configured` の最終値
- [ ] GraphQL Analytics で R2 Class A/B が無料枠内に落ちたことの確認（翌月）
- [ ] B2 側の ops 請求 $0 の確認（Class D 2,500/day を超えないこと）

---
測定の出所: GraphQL Analytics API（r2OperationsAdaptiveGroups / r2StorageAdaptiveGroups、
2026-08-14T00:00:00Z–2026-09-13T23:59:59Z）、`wrangler r2 bucket info`、
B2 native API（b2_list_file_names 全件走査 75 pages / 748,808 objects）、
ソース読解（kotobase-peer object_store/worker.cljk、local_murakumo/merkle_store.cljk、
net-kotobase/ipfs gateway.cljk）。
