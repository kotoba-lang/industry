# ADR-2607070400: app-aozora — manga.gftd.ai の作品ごと Work Actor profile を aozora.app に実装

**Status**: accepted (implemented this session)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

- ADR-2607021700 は「各作品が独立した actor/profile を持ち、その作品 actor が
  social post を mint し、manga viewer がその embed view となる」Work Actor
  モデルを将来方針として記録した（当時はシリーズ・ブランドレベルの
  `ghosthacker.gftd.ai` のみ）。
- ADR-2607062200（wave2）でオーナーのターゲットアーキテクチャが明文化された:
  **atproto トラフィックは `aozora.app` に集約**。`*.gftd.ai` の atproto ホスト
  （`atproto.gftd.ai`）は退役済み。作品 actor の identity を新規に gftd.ai 配下へ
  増やすのは逆行になる。
- manga.gftd.ai（Worker `mangaka-reader`、D1 `ai-gftd-mangaka` + R2）は現役の
  公開リーダーで、2026-07-06 時点の公開作品は 4 つ:

  | reader slug | 作品 | 話 | ページ | ジャンル |
  |---|---|---|---|---|
  | `halfgram-1` | 0.5グラムの宇宙 | 第1話 水ようかん | 7 | 日常 / 科学グルメ |
  | `zankyo-1` | 残響のカルテット | 第1話 遺品 | 7 | 音楽 / 超常ドラマ |
  | `yamainu-1` | 山犬と硯 | 第1話 起筆 | 8 | 和風伝奇アクション |
  | `gh-arc0-1` | Ghost Hacker | arc0-1-origin | 45 | cyber-thriller |

- 実測（2026-07-06、`appview.aozora.app`）: live kotobase graph（`yoro-social`）に
  `did:web:ghosthacker.gftd.ai` の profile も post も**存在しない**
  （`getProfile` → `{"profile":null,"error":"not found"}`、`searchActors?q=ghost` → 空）。
  既存ブランド actor はあくまで静的 `ghosthacker-manga-tx.edn` 内の projected
  identity であり、**サーバ側に壊すものは何もない**。
- kotobase transact は「Invalid array buffer length」障害（ADR-2607021700 記載、
  投稿系 tx が 500）が未解消前提で扱う — サーバ graph への profile 書込に
  依存しない実装が必要。

## Decision

**作品（work）単位の actor profile を aozora.app 配下の projected identity として
設計し、静的レジストリ + SPA で今すぐ表示可能にする。kotobase graph への transact は
同一データで後追いできる形にする。**

1. **命名/ID**: 作品ごとに handle `"<work>.manga.aozora.app"`、DID
   `did:web:<work>.manga.aozora.app`。work 名は reader slug から話数を落とした
   作品キー（`halfgram` / `zankyo` / `yamainu` / `ghosthacker`）。`*.manga.` の
   サブスコープでユーザー handle（`<name>.aozora.app`）との衝突を構造的に回避。
   PDS の `resolve-handle` は registry 不在時 `did:web:<handle>` に fail-open
   するため追加配線なしで解決する（`aozora.pds.repo/resolve-handle`）。
2. **SSoT は pure cljc レジストリ**: `aozora.appview.manga-actors`
   （`40-engine/cljs/appview/src/`）に 4 actor の定義
   （did/handle/displayName/description/avatar/tags/work メタ + canonical URL）と
   projection を実装。emit するのは
   - `:yoro.profile/*` entity（`:yoro.profile/actorType "agent"` を含む — 作品
     actor は人工 organism）
   - 作品ごと 1 件の `:yoro.post/*` entity。`:yoro.post/record` に
     `app.bsky.embed.external {uri,title,description}` を含むフル record JSON を
     持たせる（ADR-2607021700「manga = canonical URL + external embed」、および
     `aozora.appview.scan/post-row` が `:yoro.post/record` を優先する既存設計に
     整合）。embed uri は Ghost Hacker のみ aozora ネイティブビューア
     `https://aozora.app/manga/ghosthacker`、他 3 作品は
     `https://manga.gftd.ai/work/<slug>`（現状唯一のビューア）。
3. **今の配信はコンパイル時焼き込み**: レジストリ cljc を SPA の classpath に
   共有し（`60-apps/appview/cljs/deps.edn` に `40-engine/cljs/appview/src` を
   追加）、SPA の profile page dispatcher に manga work-actor 分岐を追加。
   `/profile/<work>.manga.aozora.app` は `yoro-ui.pages.work-actor-profile` が
   レジストリから直接描画する（server graph 不要 = kotobase transact 障害に
   非依存。fetch も無し）。appview 側に本物の profile が現れたら dispatcher の
   分岐を外すだけで移行できる。
4. **導線**: `/manga/ghosthacker` ヘッダに作者 byline
   （→ `/profile/ghosthacker.manga.aozora.app`）。検索ページの discover
   （空クエリ）と Actors タブに「manga works」節を追加し、4 actor すべてを
   client-side でヒットさせる（`match-actors`）。
5. **Ghost Hacker の旧 identity**: 静的 `ghosthacker-manga-tx.edn` に埋まる
   `did:web:ghosthacker.gftd.ai`（ページ post の author / at:// URI）は当面
   そのまま（live graph に存在せず実害なし）。tx 再生成時に
   `ghosthacker.manga.aozora.app` へ差し替えるのを follow-up とする。

### 意図的に今やらないこと（follow-up）

- **鍵付き actor 化**: CACAO 原則（actor ごとに Ed25519 did:key を発行し自分の
  graph を own する、CLAUDE.md / cloud-itonami 手本）に対し、本実装は鍵なしの
  projected identity。作品 actor が自分で post を mint する段階で
  `load-or-create-identity!` パターンの鍵発行 + `createAccount` に昇格する。
  レジストリに `:keyed? false` を明示して現状を正直に記録。
- **kotobase graph への transact**: 「Invalid array buffer length」解消後、
  `manga-actors/actors->tx` をそのまま `yoro-social` db に流せば appview の
  `getProfile`/`searchActors`/`getAuthorFeed` がサーバ側で解決する。
- **`did:web` の .well-known/did.json 配信**（`<work>.manga.aozora.app` サブ
  ドメイン）: 外部クライアントからの DID 解決が必要になった時点で。
- **manga.gftd.ai 自体の退役/統合**: 3 作品の本文はまだ D1/R2 + reader Worker が
  正。aozora ネイティブビューアへの移設は別スコープ。

## Consequences

- (+) 4 作品すべてが aozora.app 上に profile を持ち、作品 = actor という
  Work Actor モデルの第一段が実装された（ADR-2607021700 の Ideal 方向）。
- (+) identity が aozora.app 配下に揃い、wave2 の集約方針と整合。gftd.ai 配下の
  atproto identity を新規に増やさない。
- (+) データは kotobase tx 形式そのもの — サーバ graph 復旧後の移行は transact
  1 回で済み、SPA 側は appview 優先に切り替えるだけ。
- (−) profile はまだ「鍵なし」— 作品 actor 自身による post mint / follow graph
  参加はできない（見た目上の feed は静的 post カード）。
- (−) 静的レジストリと manga.gftd.ai の D1 の間に二重管理が生まれる（作品追加時は
  レジストリ更新が必要）。reader の `/api/works` 相当の自動同期は将来課題
  （現 Worker に JSON API は無く、HTML が正）。
