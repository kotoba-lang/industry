# Proposal: Bluesky !ad federate バイラル導線 (DRAFT — 送信不可, approval-required)

- authored: shinshi-mktg, 2026-09-07 (cron)
- status: **DRAFT only** — 外部投稿は hil-policy 上 approval-required。本稿は提案であり送信しない。
- type: organic acquisition (paid 全封鎖・Google organic 微少のため、唯一 scalable な増面)

## Why this is the highest-leverage lever (metrics 2026-09-04, 7d)

- `:top-paths` 最大は `/xrpc/ai.gftd.apps.shinshi.listAuthorFeed` = **841 req/24h** —
  AT プロトコルの author-feed 表面が既に実トラフィックの主軸。
  ここを社内 seed ではなく **federate 増幅**で活用するのが本提案。
- organic: general 310 / adult 6 views, search-source visit = 1 → Google はほぼゼロ。
- funnel: visitors 371 → chatters 71 → scenes 161 → paying 0。
  paying=0 は payment/rails 課題で shinshi-eng 管轄。集客側で今打てる最大は**流入の増幅**。
- ただし deploy CI broken (#89, CLOUDFLARE_API_TOKEN unset) が全 SEO merge を塞いでいる。
  → 本バイラルは **client-side / AT 経由**で deploy に依存しない利点。SEO PR より即効。

## Mechanic (要当該プラットフォーム仕様の実地確認)

1. 公式アカウントで各タレント/scene の 18+ ゲート付き teaser を投稿。
2. `!ad` 系のコミュニティ増幅 (ラベラー/フィード連携) に参加 —
   先行事例の仕様 (コマンド、参加条件、ペナルティ) を operator が確認してから着手。
3. shinshi.club の author-feed / `/xrpc/*.listAuthorFeed` (841 req) を CTA に接続し、
   teaser 閲覧者 → プラットフォーム内プロフィール → site (age-gate) へ導線。
4. 規制: 18+ gate を必ず通過後に scene 露出。地域コンプラ (output-filter) を維持。

## Draft post (Japanese, 1 例)

> 新 scene 公開。chatter 数人がリアルタイムで接続中。
> 18+ 確認後のみ内容表示 → (リンク: shinshi.club 該当 profile / age-gate)
> (#シンシ #新作)
> 18+ 年齢確認必須。一部地域からはアクセス不可。

## KPI (計測可能)

- 7d で author-feed 経由の新規 unique 増分 (対 baseline listAuthorFeed 841)
- teaser → site age-gate → scene view の転換
- **速やかに読むべき前提**: 外部増幅の実仕様は operator 実地確認必須。未確認部分は本稿に含めない。

## Next step (operator / human)

- deploy CI の CLOUDFLARE_API_TOKEN 供給 (#89) → 並行して SEO PR #91 を go-live
- 本バイラルの実証 1 本を approval の上で投稿 (この cron は送信しない)

## Environment note for this run
- cron 実行時の terminal/git/gh/search_files がダウン (sandbox)。PR 提出不可のため提案として提出。
- read_file/write_file のみ可用。本 draft は 90-docs に永続化。
