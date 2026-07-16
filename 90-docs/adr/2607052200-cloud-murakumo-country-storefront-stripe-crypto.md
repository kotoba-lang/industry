# ADR-2607052200: cloud-murakumo(Sora) — 国別ストアフロント LP を Stripe Checkout(先行)+ crypto placeholder(将来)で出す

**Status**: accepted
**Date**: 2026-07-05
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/cloud-murakumo`(murakumo.cloud 公開 SPA)
**Builds on**: ADR-2606272300(GPU cloud)、ADR-2606272330(public site)、
ADR-2607030030(推論経済 GTM — 一般ユーザーの fiat→credits は Stripe と既に決定)
**Related**: ADR-2607052100(shinshi crypto payout rail 規制レビュー — commingling
リスクの教訓を本 ADR の crypto 設計に反映)、`orgs/gftdcojp/local-murakumo/docs/business.md`
(稼働中の USDC/Gnosis-Safe crypto rail — 将来の実装対象パターン)

## Context

先行セッションで、日本・中国・米国・EU の GPU/PC 購入に伴う節税制度（日本の少額減価償却
特例・中小企業経営強化税制、中国の固定资产一次性税前扣除、米国の Section 179 + Bonus
Depreciation、EU（独 1 年償却など加盟国ごと）の投資促進税制）を確認した。cloud-murakumo
(Sora, `orgs/gftdcojp/cloud-murakumo`)は Modal 等価の分散 GPU cloud（ADR-2606272300）で、
その公開面 murakumo.cloud（ADR-2606272330、CLJS pure UI IR + DOM adapter、Cloudflare
Workers 配信）は既に landing/playground/studio の 3 route を持つ。ここに **国別ストアフロント
LP**（jp/cn/us/eu）を追加し、節税文脈込みの訴求文言を出し、Stripe Checkout(card/分割)で
即決済できるようにする。

対象商品は二本立て（ユーザー確定）:

1. **credits**（ADR-2607030030 の一般ユーザー fiat→credits。Stripe は既定路線）
2. **GPU/PC 実機のオーナーシップ枠**（投資家/法人が実機を購入し cloud-murakumo にリースして
   稼働益を得る、日本のオペレーティングリース型節税スキームに近い商品。節税トークとの直結）

crypto 決済は **UI プレースホルダのみ**（ボタンは表示するが disabled、"coming soon"）。
実装はしない。理由は 2 つ:

- ADR-2607052100 が指摘した **commingling リスク**（1 つの treasury アドレスに無関係な
  事業ラインの入金が混ざると、片方へのコンプライアンス凍結がもう片方も道連れにする）。
  cloud-murakumo(Sora) は `local-murakumo` と同じ「gftd→buyer 一方向」の形なので commingling
  の型そのものは shinshi ケースと違うが、実装するなら **local-murakumo の treasury Safe を
  再利用せず、Sora 専用の別 treasury にする**のが正しい設計（同 ADR の recommendation #2 の
  一般化）。
- `local-murakumo/docs/business.md` に **実際に稼働中の crypto rail**（USDC → Gnosis Safe
  multisig、quote→claim→Etherscan 検証→mint、Base L2、鍵は運営が持たず buyer 自身の
  wallet から送金）が既にある。ゼロから設計せず、このパターンを Sora 専用 treasury で
  再利用するのが Phase 2 の実装方針。今回は「decision すべきはこの後追い実装であって、
  ボタンだけ今出すことではない」ため placeholder に留める。

## Decision

### 1. ルーティング — `#store` / `#store/<country>`

既存の hash routing(`#landing` / `#console` / `#studio`)に `#store`(国選択 index)と
`#store/jp` `#store/cn` `#store/us` `#store/eu`(国別 LP)を追加する。SPA 内の 1 ビュー
関数(`storefront`)を country パラメータで parametrize し、per-country ビューを複製しない
(既存の `views.cljc` pure IR 方針を継承)。`nav()` に "Store" リンクを追加。

初期表示国は `navigator.language` から推定（ja→jp, zh→cn, en 以外の欧州言語→eu, それ以外→us）
するが、ユーザーは常に手動でタブ切替できる。

### 2. カタログは pure `.cljc`（Worker も JS 手書きから CLJS に統一）

`resources/murakumo.edn`(運用フリート topology、ops が再デプロイ無しで編集したいデータ)
と違い、storefront の SKU/価格/節税 note は `scheduler.cljc` の `gpu-catalog` と同じ
「pure `.cljc` に焼き込み、実行時 fetch なし」パターンを採る。`cloud-murakumo.storefront`
(新規 `.cljc`)を **唯一の正本**にし、ブラウザ UI バンドル(`:app`)と Cloudflare Worker
バンドル(`:worker`)の **両方が同じ名前空間をコンパイル**するので、表示価格と課金価格が
コンパイル単位で一致する(API 経由の二重取得は無い)。

これに伴い、手書き JS だった `worker.js`(OpenAI/Anthropic プロキシのみ実装)を
**`src/cloud_murakumo/site_worker.cljs` に置き換える**。`orgs/gftdcojp/local-murakumo`
の `worker.cljs`(shadow-cljs `:target :esm` → `dist/worker.js`)と同じ形。既存の
`:app` ブラウザビルドに加えて `shadow-cljs.edn` に `:worker` ビルドを追加し、
`wrangler.jsonc` の `main` を `./dist/worker.js` に変更する。

- `POST /api/store/checkout {country, skuId}` — **client が amount を送らない**。
  Worker が `cloud-murakumo.storefront` から国+SKU に対応する価格・通貨・
  payment_method_types を引き、Stripe REST API
  (`https://api.stripe.com/v1/checkout/sessions`)を **raw fetch**(SDK 無し、既存の
  OpenAI/Anthropic proxy と同じ流儀)で呼んで Checkout Session を作り `{url}` を返す。
  secret は `env.STRIPE_SECRET_KEY`(wrangler secret、リポジトリにコミットしない)。
  拡張 payment method(affirm/klarna 等)が Stripe 側で拒否された場合は `card` のみへ
  フォールバックして再試行する(financing note はあくまで「対応国のみ・審査次第」で
  あって保証ではないため)。
- 中国(`cn`)は Stripe が CNY 決済通貨を持たないため、**決済通貨は USD**（Alipay/WeChat
  Pay の `payment_method_types`）とし、表示は人民元換算の参考価格である旨を明記する。

### 3. 節税 note の文言方針（法的助言ではない）

各国 LP に「節税に関する一般的な制度紹介」パネルを置くが、**税務・法務アドバイスではない
旨の免責文言を必ず併記**し、「詳細は顧問税理士/会計士にご確認ください」で締める。

- **JP**: 少額減価償却資産の特例(中小企業者等、30万円未満・年300万円まで即時損金)、
  一括償却資産(10〜20万円、3年均等)、中小企業経営強化税制(即時償却 or 税額控除)。
- **CN**: 固定资产一次性税前扣除政策(単価500万元以下、延长継続中)。
- **US**: Section 179 即時控除、Bonus Depreciation(2025-01-19以降取得分100%)。
- **EU**: 統一制度なし。例としてドイツの PC ハード/ソフト耐用年数1年(実質即時償却)。

financing note: クレジットカード決済(Stripe)に加え、対応国では Stripe 経由の分割払い
(Affirm=US, Klarna=EU)を商品ページに表示。大口のリース/ローンは "お問い合わせ"(sales
contact)に誘導し、本 ADR の範囲では自動化しない。

### 4. crypto は disabled placeholder

各 SKU カードに "Pay with crypto — coming soon" ボタンを disabled で置く。クリックしても
何も起きない(または tooltip で説明)。実装しない理由と将来方針(local-murakumo の
quote→claim→verify→mint パターンを Sora 専用 treasury で再利用)をコード内コメント一行と
本 ADR に残す。

## Consequences

- murakumo.cloud は「読む landing / 触る playground / 買う storefront」の 3 面を 1 バンドル
  で持つ。表示・計算・価格が worker.js という単一の権威点から出るため、client 改ざんで
  価格が変わるリスクがない。
- crypto は将来 Sora 専用 treasury で実装する前提を ADR に残すことで、shinshi ケースの
  commingling 教訓を先取りして踏まない。
- GPU/PC オーナーシップ枠商品は節税トークと直結する訴求だが、免責文言必須という制約を
  明文化したことで、無許可のまま税務断定表現に踏み込むリスクを防ぐ。
- Stripe 未設定(`STRIPE_SECRET_KEY` 未設定)の間は checkout endpoint が明示的にエラーを
  返す(`murakumo.edn` の `deploy` 前 gate と同じ fail-closed 方針)。

## Verification Notes

2026-07-05 実装検証:

- `npm run release`(shadow-cljs advanced, `:app` + 新規 `:worker` の 2 build):
  `[:app] Build completed. (64 files, 9 compiled, 0 warnings)` /
  `[:worker] Build completed. (47 files, 1 compiled, 0 warnings)`(`env` に `^js` 型
  ヒントを付けて `.-ASSETS`/`.fetch` の infer-warning 3 件を解消)。
- `dist/worker.js`(ESM, 270 行)を Node 26 で直接 import し、`worker.fetch(request, env,
  ctx)` を fake env(`ASSETS.fetch` スタブ)で呼んで確認:
  `GET /api/v1` → 200 capability payload、未知パス → `ASSETS.fetch` に正しく
  フォールスルー、`POST /api/store/checkout`(`STRIPE_SECRET_KEY` 未設定)→ 503
  fail-closed、未知 sku/country → 400。**実 Stripe API への課金コールは行っていない**
  (secret 未設定のため到達しない設計どおり)。
- `python3 -m http.server` で `public/` を静的配信: `/`=200, `/js/main.js`=200,
  `/murakumo.edn`=200(ADR-2606272330 と同じ smoke パターン)。
- 未検証(2026-07-05 時点): 実 Chrome での目視レンダリング(拡張未接続)、実
  `STRIPE_SECRET_KEY` を使った実 Checkout Session 作成、Cloudflare 本番 deploy。

**2026-07-06 追記 — 本番 go-live 検証済み:**

- `STRIPE_SECRET_KEY` を 1Password(`gftdcojp/Stripe Live API Keys`)から
  `op read | wrangler secret put` で直接設定(値はチャット/ログに一切出さず、
  オーナーの明示承認後に設定)。`npx wrangler secret list` で登録確認。
- `npm run release` → `npx wrangler deploy` で `dist/worker.js`(CLJS
  site_worker)+ `public/` を本番デプロイ(275 files, 2.86s)。
- 本番エンドポイントを直接叩いて確認:
  `GET https://murakumo.cloud/api/v1` → 200 capability payload(旧 worker.js
  ではなく新 CLJS Worker が応答していることを確認)。
  `POST https://murakumo.cloud/api/store/checkout {"country":"us","skuId":"credits-starter"}`
  → 200、実際の `cs_live_...` Stripe Checkout Session URL を取得(実 Stripe API
  への実課金コールが本番で機能することを確認)。`skuId:"does-not-exist"` → 400
  (validation は本番でも保持)。
  `/`・`/js/main.js`・`/murakumo.edn` → 200(静的配信も deploy 後に正常)。
- **同日追記 — test-mode での完全な end-to-end 購入検証**: オーナーが Stripe
  ダッシュボードで test-mode secret key を発行(1Password には live key のみ
  存在)。Worker secret を一時的に test key へ差し替え → `#store/us` の
  Checkout Session を実ブラウザ(claude-in-chrome)で開き、Stripe テストカード
  (`4242 4242 4242 4242`)で購入を完了 → `murakumo.cloud/#store/us?status=success`
  へのリダイレクトと "Payment complete — thank you." バナー表示を確認。
  カード名義(ローマ字)必須フィールドを含むフォーム全体・成功リダイレクトの
  status パース(`core.cljs`)まで実証。直後に Worker secret を live key へ
  戻し、`cs_live_...` セッションが再び発行されることを確認(値はチャット/ログに
  一切出していない)。
- **未検証のまま残る**: 実際の**live mode でのカード決済**(実課金。オーナー
  自身が行う方針——`docs/gtm-launch-runbook.md` Gate 0 は「対応済み」に更新済み
  だが、real-money の最終確認はまだ)、実 Chrome での通常ページ
  (landing/console/studio)の目視レンダリング。

**2026-07-06 追記2 — QA で発見・修正した本番事故、分割払い撤回、PR チェックポイント:**

- 分割払い(Affirm/Klarna/Alipay/WeChat Pay)は Stripe アカウント側で未有効化
  と判明し、オーナーが非対応を決定。`storefront.cljc` の
  `:financing-payment-method-types` 分岐を撤去し全国 `[:card]` に統一、
  ブログ/GTM プラン/go-live チェックリストの該当記述も削除・修正。
- QA(react loop)で本番事故を2件発見・即修正・再デプロイ: (1)
  `public/blog/*.html` が作業ツリー上で生 markdown に上書きされ本番配信される
  事故が2回(原因不明、本リポジトリに同期スクリプトは存在せず)、(2) US 記事の
  CTA が未実装の `curl murakumo.cloud/join | sh`(404、ADR-2607030030 の
  Phase 2/3 ビジョン)を誤って案内。`#console` playground(スライダー操作で
  実際にスケジューラが再計算することを実機確認済み、72 in-flight→4 replica)
  への案内に差し替え。
- nav の "Source" リンクが private repo(`gftdcojp/cloud-murakumo`)への 404
  だったため `gftdcojp/local-murakumo`(オーナーが public 化予定)へ変更。
- US HN/X 投稿コピーをブランチ+PR 経由でマージ(`gftdcojp/cloud-murakumo`
  PR #8、technical EDN-datom/`:vllm`/`:mlx-moe` の訴求へ調整)— このチェック
  ポイント以降、この ADR に関わる変更は PR 作成→レビュー→main マージの経路も
  使う(直接 push 一本槍ではない)。
- 関連: `kotoba-lang/com-reddit` を ADR-2607070100 で Reddit 実ドメイン
  (Subreddit/User/Post/Comment/Vote)に実装し直し、Reddit 配信チャネルの
  信頼性(clean-room actor が汎用プレースホルダーのままでは「本当に動くのか」
  という疑義を招く)を担保。
