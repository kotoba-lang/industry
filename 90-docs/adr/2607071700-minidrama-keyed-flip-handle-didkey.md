# ADR-2607071700: minidrama keyed flip — handle→did:key 登録と初の keyed registry entry

**Status**: accepted (implemented, 本番検証済み)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki
**Scope**: `orgs/etzhayyim/com-etzhayyim-minidrama`, `orgs/gftdcojp/app-aozora`

## Context

ADR-2607070400 / ADR-2607071300 は creator/work actor を **projected identity**
（`:keyed? false`、did:web fail-open）として登録し、「鍵付き化（CACAO 自己発行
→ 自分の鍵で record mint → registry 反映）」を follow-up 系列とした。その前提が
minidrama で先に満ちた: `b07e2e5` で CACAO did:key + 実 Publisher、`93e73ec8` で
announce レグ（実 records が actor 自身の did:key repo に in-place）。しかし:

- PDS の handle registry に handle→did:key の bind が無く、`resolveHandle` は
  did:web に fail-open していた（実 records は raw did:key の handle で描画）。
- registry（`aozora.appview.creator-actors`）は did:web / `:keyed? false` のまま
  で、実態（did:key が実 records を所有）と乖離していた。
- SPA の creator actor dispatch は常に registry projection を描画し、実 records
  の server profile に到達する経路が無かった。

`:keyed?` フラグ自体はランタイムで未消費（正直さの記録）であり、flip の実体は
**handle 登録 + registry DID + dispatch 昇格**の3点である、という調査結果に基づく。

## Decision / Implemented

### 1. handle→did:key 登録（PDS、実施済み）

`com-etzhayyim-minidrama` に `minidrama.aozora/register-handle!` を追加
（merge `f54d7b20`）: `session-jwt!`（self-CACAO）→
`com.atproto.identity.updateHandle`。self-scoped（session DID しか自分の handle
を claim できない）なので、bind できること自体が鍵保持の証明。operator 実行
（`clojure -M:dev -m minidrama.deploy register-handle`）済みで、本番
`pds.aozora.app` の `resolveHandle minidrama.aozora.app` が
`did:key:z6MkfF8hVc4xtEdDudV1jJiyTtQDdmYBEXrhzGGaqYsHv16b` を返すことを確認。

### 2. registry flip（app-aozora、初の keyed entry）

`aozora.appview.creator-actors` の minidrama entry を
`:did did:key:z6MkfF8…` / `:keyed? true` に（merge `6b50ec1b`）。
animeka / dougaka は projected のまま（`:keyed?` は per-entry の意味に更新）。

### 3. keyed dispatch 昇格（SPA）

`profile-page` dispatcher: keyed creator actor は新設
`creator-actor-router-page`（`work-actor-router-page` と同型の resolve-first）
で `resolveHandle` → did:key なら **server profile（実 records）**を描画、
fail-open（未登録/障害）は従来の registry projection に fallback。
projected entry は従来どおり projection 直行（余計な resolve なし）。

### 4. テスト / デプロイ / 本番検証

- tests: keyed?/projected 分岐、feed-view author = did:key、`at://did:` 一般化。
  40-engine **279 tests / 1206 assertions** + SPA **55 tests / 161 assertions** green。
- SPA を本番デプロイ（aozora.app、Version `d2c23f15`）。
- 本番検証: `appview.aozora.app` の `getProfile(did:key)` →
  `{handle "minidrama.aozora.app", postsCount 3}`、`getAuthorFeed(did:key)` →
  実 post 3 件すべて friendly handle で帰属（raw did:key handle 描画が解消）。

## これで minidrama は「完全体」の keyed aozora actor

```
鍵      : Ed25519 did:key 自己発行 (.minidrama/identity.edn, gitignored)   b07e2e5
records : 自分の did:key repo に createRecord (profile/episode/announce)   93e73ec8
handle  : minidrama.aozora.app → did:key を PDS handle registry に bind    本 ADR
registry: :keyed? true + did:key (初の keyed entry)                        本 ADR
SPA     : keyed router で実 records の server profile を描画                本 ADR
mesh    : murakumo fleet に常駐 (reside facet)                             ADR-2607071500
```

## Consequences / Follow-ups

- animeka / dougaka の鍵付き化は同じ3点セット（`load-or-create-identity!` →
  updateHandle → registry flip）の複製で足りる。実 records を持つようになった
  時点で flip する（実態が先、フラグが後 — minidrama と同順）。
- registry projection の synthetic intro post は keyed 後も残る（server profile
  が資源不能な時の fallback 表示）。実 profile record が充実したら projection の
  縮退を検討（別 follow-up）。
- `createAccount` 昇格（PDS account record）は未実施。session/repo/handle が
  did:key で通っているため実害は無いが、account store の整合は
  ADR-2607070400 系列の残タスクとして残す。

## Related

- ADR-2607070400（work actor profiles — 鍵付き化 follow-up の原典）
- ADR-2607071300（creator actors 登録 + minidrama 設計）
- ADR-2607071500（minidrama mesh reside 配線）
- ADR-2606251700（app-aozora-pds self-sovereign CACAO auth: session DID == repo DID）

## 追記 (2026-07-07): createAccount 昇格完了 + PDS getAccount バグ修正

- **createAccount**: `minidrama.aozora/create-account!`（fresh self-CACAO proof →
  `com.atproto.server.createAccount`）を追加し operator 実行
  （minidrama `95fe036c`）。actor の `:atproto.account/*` datom が PDS に永続。
- **PDS バグ修正（app-aozora `647b542a`）**: PER_ACTOR_DB=1 下で `getAccount` の
  entity read は actor 自身の graph に federate するが、`create-account` は
  account entity（= handle-registry entity）を GLOBAL operator db に書くため、
  **fallback なしでは createAccount 直後の getAccount が常に AccountNotFound**
  （本番実測）。per-actor read が空の時だけ operator db を再読する fallback を
  実装（tests 280/1208 green）。PDS worker 本番デプロイ（Version `e486d080`）。
- **本番検証**: `getAccount?handle=minidrama.aozora.app` /
  `?did=did:key:z6MkfF8…` の両照会が
  `{did, handle, createdAt 2026-07-07T06:50:07Z}` を返す。
- これで follow-up 3 点のうち「createAccount 昇格」が完了。残りは
  animeka/dougaka の flip 複製と registry projection の縮退検討。
