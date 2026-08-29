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

その後 `manifest/` の surface 索引を再生成し（`nbb scripts/gen-surface-index.cljs`）、
**host が索引に載って初めて「開ける場所がある」と言える** —— この app が生まれた
そもそもの発端は、tasuke の host が索引に 1 件も無かったことだった。

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
