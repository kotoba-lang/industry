---
id: adr-2607062100-app-aozora-vertical-video-feed
title: "ADR-2607062100: app-aozora — TikTok 型縦動画フィード (/videos, 動画タブ)"
status: accepted
doc_type: adr
topic: app-aozora-vertical-video-feed
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - app-aozora cljs SPA の /videos ルート・動画タブ・縦 snap スクロール動画フィードの設計
  - 動画フィードのデータ源チェーン (appview getVideoFeed → 公式 thevids → What's Hot client filter) と cursor のソース固定則
  - 再生可能 (playable) 判定 — app.bsky.embed.video#view / recordWithMedia#view / app.aozora.embed.video (record 直 URL)
  - app.aozora.live.* live 枠フック (:live? フラグ) の予約 — 実装は ADR-2606271500 の media plane に従属
related:
  - 90-docs/adr/2606271500-kotoba-stage-obs-live-aozora.md   # OBS→aozora ライブ配信 (設計済・media plane 未実装)
  - orgs/gftdcojp/app-aozora/90-docs/adr/0075-yoro-engagement-wellbecoming.md  # 関心駆動 ranking (将来の並び順)
  - 90-docs/adr/2607021400-app-aozora-liquid-glass-cacao-signup.md  # SPA shell / liquid glass 構造
supersedes: []
superseded_by: []
---

# ADR-2607062100: app-aozora — TikTok 型縦動画フィード

**Status**: accepted（実装済み。同日 app-aozora main に着地）
**Date**: 2026-07-06
**Deciders**: Jun Kawasaki（オーナー指示「では設計、実装して」— 2026-07-06 の
「app-aozora に TikTok のような動画メイン menu と配信は設計されているか →
どちらも UI としては無い」調査への続き）

## Context

- app-aozora の primary app は cljs SPA（reagent + re-frame, shadow-cljs,
  `60-apps/appview/cljs`。旧 SvelteKit 実装は archive/parity）。動画は
  Bluesky 型「投稿への添付」(svelte 側 PostComposer の uploadVideo) しか存在せず、
  動画がメインの閲覧体験（TikTok 型縦フィード）は route も設計も無かった。
- ライブ配信は ADR-2606271500 (kotoba-stage) が設計済みだが、2026-07-01 追記の
  とおり media plane 実装は現存せずゼロからのやり直し待ち。本 ADR はそれを
  再実装しない — フィード側に live 枠のフックだけ予約する。
- cljs SPA には既に: getDiscoverFeed → What's Hot fallback のフィード degradation
  パターン (`state/feed.cljc`)、optimistic like/repost、i18n (kotoba-lang/i18n
  defmessages)、タブ dock (liquid-glass, z-50) がある。新フィードはこれらに同型で載せる。

実測（2026-07-06、設計判断に使った事実）:

- 公式 Bluesky 動画フィード生成器 `thevids`
  (`at://did:plc:z72i7hdynmk6r22z27h6tvur/app.bsky.feed.generator/thevids`,
  likeCount ≈ 7.0k) は public.api.bsky.app 経由で **500 (Upstream server
  responded with a 500 error)** を返すことがある — 単一 fallback では不足。
- What's Hot は 30 件中 2 件程度が `app.bsky.embed.video#view`
  (playlist = video.bsky.app の HLS .m3u8 + thumbnail + aspectRatio) を持つ —
  client filter + 有界 cursor 追いで「まばらだが枯れない」最終 fallback になる。
- committed `public/css/tailwind.css` は生成物だが生成スクリプトは repo に無い
  (README の `pnpm css` / build-css.mjs は現存しない) — 新規 utility class は
  増やせないため、新ページ固有のスタイルは inline :style にする。
- HEAD の `package.json` は runtime deps (scheduler 等) を欠き fresh
  `pnpm install` からの build が不能だった（共有 checkout に同修正の未コミット
  WIP が存在 — 本実装で commit 化）。

## Decision

### 1. /videos ルート + 動画タブ（ホームの隣、6 タブ目）

- router: `/videos` → `:videos`。tab dock に film アイコンの「動画 / Videos」
  タブを home の直後に追加。
- shell: `:videos` は immersive — header と liquid-glass content panel を
  スキップし、fixed inset-0 の黒レイヤ (z-30) を全画面に敷く。floating tab
  dock (z-50) はそのまま上に浮く（TikTok 同様、動画上に半透明 dock）。

### 2. データ源チェーンと cursor のソース固定則

```
1. :appview   com.etzhayyim.yoro.feed.getVideoFeed   (appview 側 video filter — 未実装、失敗して良い)
2. :thevids   app.bsky.feed.getFeed feed=thevids     (公式動画フィード)
3. :whats-hot app.bsky.feed.getFeed feed=whats-hot   (mixed feed + client filter)
```

- 初回ページのみチェーンを歩き、**成功したソースを db に固定して以降の
  load-more は同一ソースへ直行**する（cursor は発行したフィードにしか意味が
  無い — ソースを跨いで cursor を渡すのはバグ）。refresh でチェーンを再歩行。
- どのソースの応答も pure 関数 `yoro-ui.kotoba.video/feed-items->video-posts`
  を通し、**再生可能な投稿だけ**が UI に到達する。まばらなページ（0 件）は
  cursor を最大 4 回まで自動で追う（false empty state の回避、上限で暴走防止）。
- appview `getVideoFeed` のサーバ実装は follow-up（現状はチェーンの先頭で
  失敗して degrade するだけ。実装されたらゼロ変更で primary に昇格する）。

### 3. playable 判定（pure cljc `yoro-ui.kotoba.video`）

| embed | playable | src |
|---|---|---|
| view `app.bsky.embed.video#view` | ✅ | `:playlist` (HLS) |
| view `app.bsky.embed.recordWithMedia#view` の `:media` が video#view | ✅ | 同上 |
| record `app.aozora.embed.video`（native aozora、record に直 URL） | ✅ | `:playlist` / `:src` |
| record `app.bsky.embed.video`（blob ref のみ、view 無し） | ❌ 落とす | — |

- `app.aozora.embed.video` は aozora native 動画投稿（self-hosted / IPFS
  gateway 直 URL）用にこの ADR が予約する record embed。kotobase 行にも
  `:yoro.post/record` full JSON 経由で乗る。
- `live?` = record `$type` が `app.aozora.live.*` prefix — ADR-2606271500 の
  live レキシコンが実装されたら LIVE バッジ/interleave に使う予約フラグ。
  フィード item shape は今日から `:live?` を持つ（UI 側にバッジ実装済み）。

### 4. 再生（`yoro-ui.pages.videos`）

- 縦 snap スクロール（scroll-snap-type "y mandatory"、1 投稿 = 1 viewport、
  scroll-snap-stop always）。scrollTop から active index を算出し、末尾 3 枚
  以内で load-more。
- HLS は native (Safari, canPlayType) → hls.js (MSE) → 直 src の順。hls.js は
  npm dep として bundle（CDN 不使用の repo 方針どおり）。mp4/webm 直 URL は
  そのまま `<video src>`。
- autoplay は muted で開始（ブラウザポリシー）。mute はフィード全体の状態で、
  スワイプを跨いで保持。play/pause は React に任せず、playback atom
  `{:els {index el} :active :muted?}` を中央の `sync-playback!` が掃引する
  （re-frame subs が render 側の正、page render が atom へミラー — reactive
  context 外の app-db 読みを作らない）。
- 右レール: like / comment(スレッドへ) / repost / mute。like・repost は既存の
  `:feed/toggle-like` / `:feed/toggle-repost`（optimistic set 共有）を再利用。

### 5. スコープ外（follow-up）

- appview 側 `com.etzhayyim.yoro.feed.getVideoFeed` のサーバ実装（video filter
  + ADR-0075 の関心駆動 ranking の適用）。
- cljs SPA からの動画アップロード（svelte PostComposer parity）。
- ライブ配信 media plane（ADR-2606271500 の再着手。本フィードは `:live?` の
  interleave 先が既にある状態で待つ）。

## Verification (2026-07-06)

- `pnpm test`: 44 tests / 124 assertions green（video projection の pure test
  9 ケース + router `/videos` を含む）。
- `shadow-cljs compile app` / `release app`（advanced）: 0 warnings。
- headless Chrome (`--dump-dom`, SPA fallback server) で `/videos` 実走:
  appview 失敗 → thevids 500 → What's Hot fallback の実チェーンを通り、
  `<video>` 3 枚（video.bsky.app の poster + playlist src）、著者 overlay、
  動画タブ active を確認。スクリーンショット取得済み。
- HEAD package.json の欠落 runtime deps（scheduler / @scure/base / cborg /
  multiformats）を commit 化 — fresh install からの build が通ることを確認。

## Notes

- 生成 CSS (public/css/tailwind.css) に無い utility はページ内 inline :style
  で書いた（生成器が repo に無い間の方針。css 生成が復活したら置換可）。
- 実装 commit: gftdcojp/app-aozora `videos-feed-vertical` branch → main
  server-side merge。superproject は本 ADR + west pin 前進で追従。
