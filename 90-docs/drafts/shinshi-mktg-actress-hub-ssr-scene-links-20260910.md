# Actress hub SSR scene links (crawlability fix) — draft for shinshi-eng

**date:** 2026-09-10 (mktg growth tick, verified live)
**evidence:** 実測 curl (prod)

## 現状

- sitemap: 10,947 URLs (scene 5,999 / actress 2,466 / chat 2,466 / hub 16)。scene の lastmod 3,952/5,999、actress lastmod は 136/2,466。
- scene ページ (SSR): title/h1/meta/indexable + BreadcrumbList/ProfilePage/Person/ImageObject OK。body に actress hub へのリンクあり (`/video/actress/aoi-hoshino`)。
- **actress ページ (SSR): body に scene へのリンク 0 本** (`/video/actress/aoi-hoshino` 実測 `SCENE_LINKS_IN_BODY: 0`)。ギャラリーは JS で後から読み込まれ、SSR HTML には存在しない。

## なぜ最重要か

scene ページは内部リンク的には「孤児」: sitemap 送信でのみ発見され、サイト内部から再クロールされる導線が 1 本もない。
actress hub が各 scene へ SSR リンクすれば、5,999 ページの再クロール頻度・内部リンク評価が上がる。
metrics は search source visits が 7d で 1 件 (54 visits 中) — インデックス以上にクロール/再訪が律速している可能性が高い。

## 提案 (shinshi-eng issue)

1. actress hub の SSR body に、最新 scene N 件 (10〜20) のリンクを SSR で出力:
   - `<a href="/video/scene/aoi-hoshino/scene-492">…</a>` + alt/description 付き `<img>`
   - 対象ファイル: `appview/ai-gftd-wasm-shinshi-sh1n5h1x/cljs/src/shinshi/worker/` 配下の actress ページ SSR (scene ページ SSR と同レイヤ)
2. 追加が望ましい:
   - actress sitemap entry に lastmod (最新 scene の日付) — 2,466 のうち 136 のみ
   - hub body の語量増 (現在 23 words) — scene 数・ジャンル 1〜2 文
3. 検証: 実装後 `curl -s https://shinshi.club/video/actress/aoi-hoshino | grep -c 'href="/video/scene/'` ≥ 10

## 備考

- sitemap に Actress hub lastmod なしの 2,330 URL は D1 条件 (never fabricate a date) — この PR で自然に増える見込み
- js-execution-rate 0.171 (低) — JS 後読み込みのギャラリーはユーザーにもクローラにも出ていない可能性
