---
id: adr-2607175000-kotobase-datalad-annex-persistence
title: "ADR-2607175000: kotobase.net を DataLad/git-annex の永続化バックエンドにする（content-addressed block store as external special remote）"
status: accepted
doc_type: adr
topic: kotobase-datalad-annex-persistence
authoritative: true
last_verified: 2026-07-17
authoritative_for:
  - 大容量バイナリ（動画/音声/モデル重み/データセット）を DataLad + git-annex で扱い、kotobase.net の content-addressed block store を git-annex external special remote として永続化する設計
  - kotoba-lang/kotoba-annex（external special remote 実装）の契約と、kotobase-server に必要な blob XRPC 面
  - ai-gftd-dougaka-kagaku の生成物（合成音声・scene 画像）永続化の driving use case
related:
  - 90-docs/adr/2607165000-ai-gftd-dougaka-kagaku-science-sim-pipeline.md
  - 90-docs/adr/2607051000-kotobase-store-injectable-seam.md
supersedes: []
superseded_by: []
---

# ADR-2607175000: kotobase.net を DataLad/git-annex の永続化バックエンドにする

**Status**: accepted（設計 + 実装 + 実 git-annex 検証。kotobase.net への実書込は Follow-up）
**Date**: 2026-07-17
**Deciders**: Jun Kawasaki（指示:「永続化を進めて, また kotobase.net で直接 datalad を永続化できるように」）

## Context

- ai-gftd-dougaka-kagaku（ADR-2607165000）は動画素材（合成音声 wav 全10本 22.5MB、
  scene SVG）を生成できるが、**現状すべて scratchpad にしか無く永続化されていない**
  （セッションを閉じると消える）。ADR-2607165000 の永続化設計は B2 blob + D1 だが未実装。
- workspace の大容量バイナリ標準は **DataLad + git-annex + Backblaze B2 S3 special
  remote**（`m365-archive` が先例。git-annex/datalad は install 済み）。
- オーナー指示は「kotobase.net で**直接** DataLad を永続化できるように」。
  kotobase-server（`kotobase.server.handler`）は **content-addressed block store**
  を注入 seam として持つ（ADR-2607051000）: `put!(cid,bytes)` / `get-fn(cid)` /
  `head-put!(graph,chain)` / `head-get(graph)` + encrypt/decrypt。
- **git-annex の key（`SHA256E-s<size>--<hash>`）はそれ自体が内容ハッシュ** なので、
  kotobase の content-addressed block store に 1:1 で対応する — annex key = block cid。
  両者はどちらも content-addressed なので、git-annex external special remote →
  kotobase block store は自然な接続。

## Decision

**新規 lib `kotoba-lang/kotoba-annex`**（git-annex external special remote、nbb）を
起こし、大容量バイナリを content-address で block store（directory / kotobase.net /
B2）に永続化する。block store は **注入 seam**（kotobase-server と同じ設計思想）で、
プロトコルエンジンは store 契約だけに依存し実体を差し替える。

### 1. store 契約（注入 seam）

```
:store!   (fn [key bytes]) -> truthy     ; annex key で bytes を保存
:retrieve (fn [key])       -> bytes|nil
:present? (fn [key])       -> :yes|:no|:unknown
:remove!  (fn [key])       -> truthy
:init!    (fn [])          -> truthy      ; INITREMOTE
:prepare! (fn [config])    -> truthy      ; PREPARE
```

### 2. 実装と検証状況

- `kotoba.annex.protocol`（純 `.cljc`）— git-annex external special remote
  プロトコルのパーサ/応答生成。IO なし、単体テスト済み。
- `kotoba.annex.directory`（`.cljs`）— ローカルディレクトリ block store。
- `bin/git-annex-remote-kotobase.cljs` — git-annex が起動する実行体。
- **実 git-annex v10 で end-to-end 検証済み**（directory store）:
  `initremote` / `copy --to`（STORE）/ `drop` / `get --from`（RETRIEVE）/ `fsck`
  すべて ok。block は annex key（`SHA256E-s191020--…`）で保存され content 整合も確認。
- `kotoba.annex.kotobase`（`.cljs`）— kotobase.net block store client。store 契約を
  blob XRPC 面にマップ。**mock fetch で単体テスト済み**。

### 3. kotobase.net への実書込に必要なもの（Follow-up、owner-gated）

- kotobase-server の**公開 XRPC 面は datomic 系**（datoms/transact/q/…）で、
  raw block put/get は注入 store の内部契約。git-annex から直接使うには
  kotobase-server に薄い **blob 面**を1つ足す:
  `ai.gftd.apps.kotobase.blob.{put,get,head,remove}`（store の put!/get-fn/head を
  そのまま公開、encrypt seam は既存を流用）。`kotoba.annex.kotobase` はこの契約に
  対して書いてある。
- これは (a) kotobase-server の小追加 + (b) deployable Worker（net-kotobase 等）への
  反映 + (c) 認証（CACAO / token）が要る。**認証情報は自分で入力しない**（安全床）。
  それまでは `store=directory`（検証済み）または B2 S3 special remote（m365 先例）を使う。

### 4. kagaku への適用（driving use case）

- `ai-gftd-dougaka-kagaku` に DataLad dataset を作り、合成音声・scene 画像・
  （将来の）render 済み動画を annex 管理し、kotobase remote（or directory/B2）へ
  copy する。大容量は git 履歴に入れない（skill large-binary-datalad と整合）。
- episode EDN / 台本 / .cljc は小さいので git 直（DataLad 対象外）。annex 対象は
  再生成コストの高い/大きい binary（wav・render 済みフレーム・動画）。

## Consequences

- (+) DataLad/git-annex の標準フローで大容量を永続化でき、content-address なので
  重複排除・整合検証が git-annex 標準で効く。**実 git-annex で動作実証済み**。
- (+) store 注入 seam で directory（今すぐ動く）/ kotobase.net（server 追加後）/
  B2（既存）を差し替え可能。段階導入できる。
- (+) annex key = kotobase block cid の対応が自然で、変換ロジックが薄い。
- (−) kotobase.net への実書込は server の blob 面 + deploy + 認証が必要（owner-gated）。
  それまでは directory / B2 で運用。
- (−) 暗号化（encryption=none で検証）。公開素材でなければ git-annex 暗号化 or
  kotobase の encrypt seam を有効化する設計判断が要る（follow-up）。

## Follow-ups

- kotobase-server に `ai.gftd.apps.kotobase.blob.{put,get,head,remove}` を追加 +
  deploy（net-kotobase）+ 認証。`kotoba.annex.kotobase` を実 kotobase.net で検証。
- kagaku に DataLad dataset を作り合成音声/scene を annex 管理、remote へ copy。
- 暗号化方針（encryption=shared/hybrid or kotobase encrypt seam）。
- CI（実 git-annex での STORE/RETRIEVE E2E を GitHub Actions で回す）。

## Alternatives Considered

1. **B2 S3 special remote だけ使う（m365 と同じ）** — 今すぐ動くが「kotobase.net で
   直接」というオーナー要件を満たさない。kotobase は content-addressed store を
   既に持つので、そこに載せる方が IPNS/Datomic と素材を同一データ面に統合できる。
   B2 は fallback として残す（併用可能、注入 seam で差し替え）。
2. **大容量を kotobase の datomic に datom として入れる** — datomic は構造化データ
   用で、数十 MB の binary を datom 化するのは block store の誤用。raw block 面
   （put!/get-fn）が正しい層。不採用。
3. **git-annex を使わず独自 asset store を書く** — content-address・重複排除・整合
   検証・部分取得を再実装することになる。git-annex がすべて持っているので車輪の
   再発明。git-annex external special remote が正しい抽象。不採用。
