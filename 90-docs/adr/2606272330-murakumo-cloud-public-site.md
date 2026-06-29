# ADR-2606272330: murakumo.cloud — 公開サイトを CLJS+Reagent+re-frame で Cloudflare に出す

**Status**: closed
**Date**: 2026-06-27
**Closed**: 2026-06-28
**Scope**: `orgs/gftdcojp/cloud-murakumo`(public web surface)
**Builds on**: ADR-2606272300(cloud-murakumo — GPU cloud)

## Context

`cloud-murakumo`(ADR-2606272300)は Modal 等価の分散 GPU cloud を `resources/murakumo.edn`
(SSoT)+ 純粋 `.cljc`(schema/spec/scheduler/vllm/runtime)で実装した。CLI(`clj -M:plan|
schedule|serve-cmd|deploy|doctor`)で配置・課金・autoscale を計算できる。

これを **`murakumo.cloud`** として公開する。ドメインは Cloudflare で取得済み。
フロントは指定どおり **ClojureScript + Reagent + re-frame**。

鍵となる気付き: **scheduler は純粋 `.cljc` なので、サーバ CLI と全く同じ関数を browser で
動かせる**。つまり公開サイトの「playground」は飾りではなく、`murakumo.edn`(SSoT)から
**実際の配置アルゴリズムをその場で実行**する。サイト表示と本番計算が定義上一致する。

## Decision

### 1. 単一 SPA(landing + live playground)

`cloud-murakumo.ui.*`(CLJS)を shadow-cljs で 1 つの `:browser` バンドルにする。

- `core.cljs`: init! / hash routing(`#landing` / `#console`)/ Reagent mount
- `events.cljs`: `murakumo.edn` を **静的アセットとして fetch** → `clojure.edn` で parse → db
- `subs.cljs`: 配置・使用率・課金・承認 effect を **共有 `.cljc`**(`scheduler`/`spec`/
  `runtime`)で算出。CLI の `-M:schedule` と同一結果
- `views.cljs`: ランディング(hero / EDN コード例 / GPU カタログ / なぜ data-first)と
  playground(関数ごとの in-flight スライダ → ライブ再配置・fleet 使用率バー・$/h・
  承認 effect)

re-frame の単方向データフロー: `{:spec :load :route}` の `:load`(fn-id→in-flight)を
スライダが書き換えるたび、subs が `plan-placements` を再計算して view が更新される。

### 2. SSoT は 1 つ(murakumo.edn)

`murakumo.edn` を二重持ちしない。`package.json` の `prebuild` が
`resources/murakumo.edn` を `public/murakumo.edn` へ **コピー**し、SPA は実行時に fetch する。
JVM CLI は `io/resource` で同じファイルを読む。fleet/apps/価格を変えるのは 1 ファイルだけ。

### 3. Cloudflare Workers static-assets で配信

ドメインが Cloudflare にあるので、`wrangler.jsonc` の **assets-only Worker**
(Worker script なし、`not_found_handling: single-page-application`)で `public/` を出す。
aozora(`app-aozora-svelte`)の `assets` バインド方式に揃える。custom domain は
`murakumo.cloud` / `www.murakumo.cloud`。

```sh
npm run release   # prebuild(edn copy) + shadow-cljs release(advanced)
wrangler deploy   # public/ を murakumo.cloud へ
# dev: npm run watch → http://localhost:8700
```

### 4. 計算は client、副作用は gate の先

公開 playground は **純粋計算のみ**(配置・見積り)。実 GPU 確保(deploy/scale-up=
財務 effect)はサイトからは起こさない。`runtime/reconcile` が出す effect は `:proposed`
として表示するだけで、実 transact は murakumo 制御面 + kotoba XRPC(`KOTOBA_URL`)の先。
公開面に秘密(LLM_KEY 等)は置かない(secret は参照のみ、ADR-2606272300)。

## Consequences

- `murakumo.cloud` は「読む landing」と「触る playground」を 1 バンドルで提供する。
  訪問者は EDN を編集する感覚で GPU 配置・課金を体験できる。
- サイトの数字 = CLI の数字 = 本番 scheduler。三者が同じ `.cljc` なので乖離しない。
- Cloudflare 配信は静的(Worker ロジックなし)。CDN edge から即配信、運用コスト最小。
- 将来 real fleet 状態(node 実在庫)を kotoba XRPC から読めば、同じ view が
  デモから実ダッシュボードへ昇格できる(subs の入力を fetch に差し替えるだけ)。

## Verification Notes

2026-06-27 の実装検証(node v26 / java 21 / shadow-cljs 2.28.20 / reagent 1.2.0 /
re-frame 1.4.3):

- `npm install` → 0 エラーで react/react-dom/shadow-cljs/wrangler 取得
- `npm run release`(advanced optimizations): **Build completed. 125 files, 54 compiled,
  0 warnings**。共有 `.cljc`(scheduler/spec/runtime/vllm)が browser バンドルに同梱
- 生成物 `public/js/main.js`(約 412K, advanced)。`prebuild` が `public/murakumo.edn`
  を生成
- 静的配信 smoke(`python3 -m http.server`): `/`=200, `/js/main.js`=200,
  `/murakumo.edn`=200
- playground が表示する配置は CLI と同一ロジック。検証済みの値(ADR-2606272300):
  `minimax-m27=80 kimi-k27=16 embed=20` → asagi/midori 満杯 + sora 0.75、**$86.72/h**
- 実ブラウザでの目視レンダリングは Chrome 拡張未接続のため未実施。advanced ビルドが
  0 warning で通り、描画ロジックは検証済み `.cljc` を呼ぶだけなので計算面は担保。
  実 GPU fleet / 実 kotoba mesh への接続、Cloudflare 本番 deploy は credential/DNS 設定後。

## Closure

2026-06-28 closing。CLJS+Reagent+re-frame の単一 SPA(landing + playground + Studio タブ)を
`murakumo.edn`(SSoT)から live 計算する形で実装し、advanced ビルド(0 warning)+ 静的配信
smoke まで検証済み。Studio タブで生成スタジオ(ADR-2606272330 gen)の modality 別カタログと
app 別課金を表示する。残: `wrangler login` + custom domain 紐付け(本番 deploy)、Chrome での
目視、実 fleet 状態の kotoba XRPC 取り込み(デモ→実ダッシュボード昇格)。
