# ADR-2607060200: aozora 投稿詳細ページの空白/低速 — handle→DID 未解決 + AppView フルスキャン

**Status**: accepted
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki

## Context

`https://aozora.app/profile/<handle>/post/<rkey>` の投稿詳細ページが表示されない
（ヘッダーのみで本文が完全に空白）／ロードが完了しない、という報告(具体例:
`yukkuri-nist-csf-pilot/post/1783067608178`、`yukkuri-publisher/post/1783067913683`)
を受けて調査。ブラウザで live 再現し、appview.aozora.app の XRPC レスポンスと
ソースを突き合わせた結果、**独立した2つの原因**が判明した。

### 原因1: `getPostThread` は DID キーだが、フロントは handle をそのまま埋め込んでいた

`60-apps/appview/cljs/src/yoro_ui/pages/post_thread.cljc`(旧) の `fetch-thread!` は
`(str "at://" handle "/app.bsky.feed.post/" rkey)` で URI を組み立てて
`getPostThread` に渡していたが、appview 側の post 索引は **DID ベース**でしか
引けない。実測: `at://<handle>/...` → `{"thread":{}}`（空）、
`at://<did>/...` → 投稿本体が正しく返る。appview は標準の
`com.atproto.identity.resolveHandle` を実装しておらず(`MethodNotFound`)、
`getProfile`/`getAuthorFeed` も handle 指定では動かない(DID 指定でのみ動作)。

さらに二次バグとして、`post-thread-page` の render `cond` が `(:thread state)`
を単純 truthy 判定しており、ClojureScript では空 map `{}` も truthy なため、
本来の「投稿が見つかりません」に落ちずに `[thread-node {}]` を描画 →
`thread-node` 内の `(when (map? post) ...)` が `nil` を返し、**エラー表示すら
出ずに完全な空白**になっていた。

### 原因2: AppView が毎回 db 全体をフルスキャンしていた

`40-engine/cljs/appview/src/aozora/appview/feed.cljc` の `get-post-thread`/
`get-author-feed`/`get-profile`（他 10 関数も）は `scan-yoro`（`:eavt` の
フルスキャン）経由で **毎リクエスト db 全体を rehydrate** していた。これは
ADR-2607022330 addendum で報告された「kotobase wasm worker が
`components_edn`/`limit` を無視し毎回全体を rehydrate する」問題への対症だったが、
**同 addendum 3/4 で worker 側は既に修正済み**（`kotobase-cljc-worker` が
`{:index :components :limit}` を honor）。AppView 側のコードだけがこの改修に
追随しておらず、フルスキャンを続けていた。

## Decision

### Fix 1 — handle→DID 解決 + render 判定修正（app-aozora PR #48, squash `4d5063b`）

`post_thread.cljc` に `resolve-did!` を追加: 既に `did:` 始まりならそのまま、
そうでなければ `com.etzhayyim.yoro.actor.searchActors`（`:q` = handle、既存の
実装済みエンドポイント）で実 DID を検索し、一致する actor の `:did` を使って
URI を組み立てる。見つからなければ元の handle にフォールバック（挙動非破壊）。
render `cond` は `(:thread state)` → `(get-in state [:thread :post])` に変更し、
空 thread を正しく「投稿が見つかりません」に落とす。

新規テスト `test/yoro_ui/pages/post_thread_test.cljc`（実本番 XRPC の
nsid/param 形状で検証）+ 実ブラウザ(Playwright/Chrome)での live 確認
（両報告 URL とも投稿本文が正しく表示されることを確認）。

### Fix 2 — AppView narrow reads（app-aozora PR #47, squash `d415f9a` + follow-up `31a6de8`）

`get-post-thread`/`get-author-feed`/`get-profile` を、`aozora.pds.repo/fetch-index`
+ `fetch-entity`（ADR-2607022330 addendum 3 で PDS 側に導入済みの narrow read
パターン）と同型の `:avet`/`:eavt` 絞り込みに置き換え。必要な entity だけを
取得し、`aozora.appview.scan/scan`（現状維持、reconciliation ロジック無改造）に
流し込む。`get-timeline`/`discover`/`ranked`/`searchActors`/`likes`/`reposts`/
`followers`/`follows` は「全件列挙」が本質的に必要なため対象外（`scan-yoro` のまま）。

レビュー（8観点の多角並列レビュー + 検証）で判明した新規失敗モード
「`hydrate-entities` の無制限 `Promise.all` が、人気アクター（大量フォロワー）
や大量返信スレッドで Cloudflare Workers の subrequest 上限（無料枠50）を
超えてハードエラーになりうる」を追加修正（`31a6de8`）: 25件ずつのチャンクに
分けて逐次実行するよう変更（総取得量・正しさは不変、ピーク同時実行数だけ抑制）。

## 検証

- `40-engine/cljs` 全体テスト: 243 tests / 1048 assertions、0 failures/errors
  （feed_test.cljc のみでなく pds/appview/relay/kotobase-client 全体）
- `60-apps/appview/cljs` 全体テスト: 33 tests / 91 assertions、0 failures/errors
- 実ブラウザ(claude-in-chrome)で本番 appview.aozora.app 相手にローカル fix 済み
  ビルドを live 検証 — 両報告 URL とも投稿本文表示を確認
- `kotoba-lang/browser-use`(Playwright + ローカル Ollama)での検証も試行 —
  ただし `playwright_browser.cljs` の `interactive-elements` が
  `a,button,input,textarea,select` のみを抽出する設計のため、投稿本文
  （非interactive要素）を観測できず偽陰性("blank"報告)が出た。これは
  browser-use-clj 側の観測範囲の限界であり、直接ブラウザ確認で反証済み
  （別途 browser-use-clj への feedback 候補として記録のみ、本ADRのスコープ外）。

## Consequences

- 投稿詳細ページの直リンク・アプリ内遷移が、handle を持つ actor（`did:key:...`
  そのものを handle として使っていない全 actor）について正しく機能するように
  なった。
- AppView の `get-post-thread`/`get-author-feed`/`get-profile` は narrow read に
  なり、ADR-2607022330 addendum 4 の実測（narrow read 0.15–0.3s、旧full-scanは
  15–38s or 間欠500）に近い改善が期待される（本番 live latency 実測は未実施、
  follow-up）。

## Known follow-ups（レビューで判明、本修正のスコープ外として明示的に残す）

1. **`profile.cljc` に同型の未修正バグが残る** — `load-profile!`/
   `load-author-feed!` が handle を DID 解決せず直接 `getProfile`/`getAuthorFeed`
   に渡しており、プロフィールページ単体訪問では同じ症状（空 stats/feed）が出る。
   `resolve-did!` は `post_thread.cljc` に private なため、profile.cljc 用には
   共有 namespace への切り出しが必要。
2. **`resolve-did!` は `searchActors`（`:limit 25`、`sort-by` 無し、部分一致）
   経由 — より正確な代替が既存**: PDS 側に `com.atproto.identity.resolveHandle`
   の実装（`aozora.pds.repo/resolve-handle`、exact-match AVET lookup）が既にある
   が、SPA の `at-appview-or-public` は appview host + public bsky fallback しか
   知らず、PDS host（`pds.aozora.app`）へのコールパスが無い。現状の
   `searchActors` 方式は「25件超の部分一致がある場合に本来の handle が
   結果から漏れうる」「大文字小文字を区別する exact match」という edge case を
   持つ（両報告アクターでは非該当のため実害なしを確認済み）。将来的には
   PDS の `resolveHandle` を呼べる interop パスを追加し切り替えるのが望ましい。
3. **`get-post-thread`/`get-author-feed`/`get-profile` の narrow read は、
   generic `:atproto.record/*` 形状で書かれた投稿（ADR-2607032300「per-actor
   cutover」write path 向けに `aozora.appview.scan/record-row->post-row` が
   変換対応済み）を発見できない** — `:yoro.post/*` 属性でしか絞り込んでいない
   ため。ただし実装追跡の結果、現状 `"app.bsky.feed.post"` を書き込む経路
   （`aozora.pds.encode/record->entity*`、`PER_ACTOR_DB` フラグの on/off 問わず）
   は全て `:yoro.post/*` 形状にマップしており、**この gap は現時点で到達不能
   （dead-code-adjacent）**と判断。ADR-2607032300 の cutover が本番で
   `"app.bsky.feed.post"` を generic-record 形状で書くように変わった場合は
   要修正。
4. **`fetch-index`/`fetch-entity` が `aozora.pds.repo` の既存 public 実装と
   重複**（feed.cljc 内のコメントが「mirrors aozora.pds.repo/...」と認めている）。
   `40-engine/cljs/shadow-cljs.edn` は appview/pds 両ビルドで既に同一
   `:source-paths` を共有しているため、共有 namespace への統合は低リスク。
   ADR-2607022330 addendum 2 で述べた kotobase-client 3重コピー事故
   （「fix が1コピーにしか入らない実害」）と同種のリスクを内包している。
5. **narrow read は 13関数中3関数のみ**（`get-timeline`/`discover`/`ranked`/
   `get-posts`/`get-likes`/`get-reposted-by`/`get-followers`/`get-follows`/
   `get-bsky-profiles`/`search-actors` は `scan-yoro` のまま）。特に
   home feed（`get-timeline` 系）は最もトラフィックが多いと推測されるため、
   そこが未対応のままだと全体レイテンシへの寄与は限定的。共有の
   "narrow keyed read" エントリポイントが無く、次の移行者は `get-post-thread`
   の実装から都度パターンを読み解く必要がある。

## Relationship

- **builds on** ADR-2607022330（kotobase 500 根因確定 + narrow keyed read
  パターンの導入、addendum 3/4）— 本ADRはそのパターンを AppView 側に適用。
- **touches** ADR-2607032300（per-actor authority/firehose/AppView）—
  follow-up 3 で言及した generic-record 投稿形状の扱いに関連。
