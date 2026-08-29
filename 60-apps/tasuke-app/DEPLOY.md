# 出す — 現在地と、残っている 1 つ

`public/` は 1 文書 + 1 bundle の静的ファイルで、置けばそのまま動く。
**まだどこにも置いていない。** この session に Cloudflare の credential が
1 つも無いため（env 0 件 / `~/.wrangler` 無し / kagi・op CLI 無し、実測 2026-08-29）。

検証できたところまで:

```
$ npx wrangler deploy --dry-run
✨ Read 4 files from the assets directory .../public
Total Upload: 0.31 KiB
--dry-run: exiting now.
```

設定は妥当。足りないのは credential だけ。

## 経路 A — Cloudflare（このワークスペースの配信面）

credential のある session / 手元で:

```bash
npm install && npx shadow-cljs release app && clojure -M:gen-page
npx wrangler deploy            # tasuke-first-response.<account>.workers.dev
```

または `npm run deploy`（build → page → wrangler deploy）。

### ⚠ workers.dev だけで出すと、発端の gap は閉じない

`scripts/gen-surface-index.cljs` を読んで実測した 2 点:

1. **索引が walk するのは `orgs/<org>/<repo>` だけ。** root の `60-apps/` は
   走査対象外なので、この設定は **tasuke へ移送するまで索引に載らない**。
2. **`hosts-of` は `routes[].pattern` と `route` しか読まない。** つまり
   `*.workers.dev` に出しただけでは host が 0 件のままで、索引は今までどおり
   「tasuke に開ける場所は無い」と答え続ける。

この app が生まれた発端がまさにそれ（surface 索引に tasuke の host が 1 件も
無かった）なので、**閉じるには custom domain を決めて `routes` に書く**必要がある。
ドメインの決定はオーナーのものなので、placeholder は置いていない —— 置けば索引は
serve していない host を載せることになり、それは索引を嘘にする。

deploy して host が決まったら:

```bash
# wrangler.jsonc に routes を足してから
nbb scripts/gen-surface-index.cljs      # superproject root で
```

⚠ 本番 deploy の前に `git merge --ff-only origin/main` を通すこと。deploy には
fast-forward 検査が無く、最後に実行した人が勝つ（root CLAUDE.md の実インシデント）。

## 経路 B — GitHub Pages

`public/` をそのまま公開 repo に置き、Settings → Pages → Deploy from a branch。
リポジトリ作成と push はこの session からでもできるが、**Pages を有効化する
トグルは API では触れない**ので、いずれにせよ人の 1 クリックが要る。
`cloud-itonami/cloud-itonami.github.io` が既にこの形で動いている。

## どちらでも変わらないこと

fragment routing（`#plan`）なので **SPA rewrite を設定しない**。
`not_found_handling: single-page-application` にすると存在しないパスまで 200 で
index を返し、壊れたリンクが動いているように見える。この app に他のパスは無い。
