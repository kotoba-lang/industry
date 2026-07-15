# ADR-2607033000: itonami 事業レイヤ — credits 経済 / 暗号決済(Base+Safe) / needs駆動 fleet オーケストレーション

**Status**: accepted — 実装・テスト・デプロイ済み(api.murakumo.cloud / app.itonami.cloud / relay.gftd.ai / itonami.cloud)、cloud-murakumo-fleet + murakumo main へ全反映済み、west pin 前進済み
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

`kaizen, business 情報`(毎 firing ごとに分散推論プラットフォームを1つ改善し、
事業指標を収集報告する定期運用)のもと、複数 firing にわたって murakumo
(分散推論の計算基盤)の上に **itonami 事業レイヤ**を積み上げた。ADR-2607022000
(murakumo exo 型分散推論)・ADR-2607022300(itonami private tenant / kotoba-rad)
がこのスタックの以前の意思決定を記録している。本 ADR は、cloud-murakumo Worker
(`cloud-murakumo.itonami`)を舞台に一連の firing で確立した **credits 経済・
暗号資産決済レール・fleet オーケストレーション**の設計判断を1つにまとめて記録する。

全体の関係: **murakumo = Compute(分散推論の実行基盤)、itonami = その Compute を
職業別 AI サービスとして売る事業レイヤ、itonami.cloud = 既存の Kotobase business
cockpit** で、murakumo は cockpit の「Compute」セクションとして統合される
(別アプリではなく同一 design system の first-class セクション)。

## Decision

### 1. credits 台帳 — 1つの append-only 署名フィードに5種別

すべては `infer.runs`(append-only, actor-signed)の pure fold。
`credits-fold` が残高に畳む。credits を動かすのは5つのエントリ種別:

- **earn** `:run/shares` — shard 保有 / swarm work への配当(murakumo operator が settle)
- **grant** `:run/grant` — 無料トライアル 200cr(fiat/treasury なし。`POST /itonami/trial`、冪等)
- **topup** `:run/topup` — 購入 credits(暗号決済の**確認後のみ** mint)
- **spend** `:run/spend` — 推論への debit(`POST /infer/spend`、残高不足は 402)
- **pending** `:itonami/pending-topup` — 未確認の暗号決済 claim。**mint しない**

`did:key` が口座。フィードは改竄検知可能だが**合意チェーンではない**。

### 2. KV 結果整合の吸収規約(横断的発見)

Worker の Cloudflare KV は**読み書き結果整合**で、書込直後の読取は反映されない
ことがある。加えて js→clj の round-trip で **namespace が strip され**
(`:run/grant` → `"grant"`)、**key が keywordize される**。よって全 fold は
①名前空間ありと bare の両キーを読む ②did を `name` で比較する。トライアル冪等性・
spend は**結果整合**(JVM LocalStore では厳密)。この規約を守らないと live で
「0 pending」「冪等性不発」「二重付与」を招く(実際に各所で発生・修正済み)。

### 3. 暗号資産のみの決済レール(fiat 廃止)

オーナー指示により有償レールは**暗号資産(USDC)のみ**。fiat processor を持たない。
買い手が**自分のウォレット**から treasury **Safe(Gnosis マルチシグ)**へ USDC を
送金し、プラットフォームは**鍵を持たず資金も動かさない**。credits はオンチェーン
確認後のみ mint。

- `GET /itonami/pay/quote` — 支払リクエスト(金額・Safe アドレス・chain)。
  **`TREASURY_ADDR` 未設定なら 503**(誤送金防止)
- `POST /itonami/pay/claim {did,usd,tx}` — **pending 記録のみ**(付与なし)
- `GET /itonami/pay/status?did=` — 買い手が pending/confirmed を確認
- **検証** `itonami/verify-payment`(純・注入 tx レコード): 宛先=Safe(大小無視)/
  全額 / 確認数 ≥3 を全通過した時のみ confirm。偽・過少・確認不足は hold。
  `pending-payments` は確認済み tx を除外(冪等・二重 mint なし)

### 4. Base L2 + 単一 Safe(コスト対応)

Ethereum mainnet の USDC ガス($10 決済に $2-5)は少額課金の致命的障壁。決済 chain を
**設定可能**にし(`itonami/chains`: ethereum / base / arbitrum → USDC コントラクト +
ブロックエクスプローラ)、`PAY_CHAIN` で選択。**treasury Safe は EVM 全チェーンで
同一アドレス(CREATE2)** なので、`PAY_CHAIN=base` にするだけで受取先も資金も変えず
ガスが**数セント**に。オーナー確認の上 **Base に切替済み**
(Safe `0xA00366234D29d4F882088048c0B2fa0dB7302D4E`)。

### 5. 自動確認オペレータ(常駐)

`tools/verify-payments.clj`: pending を読み → Basescan `tokentx` で Safe への USDC
入金を取得 → `verify` → 確認済みのみ `crypto-topup-entry` を append。
API キーは **env → Apple Keychain → 1Password** で解決(`b2-creds.cljs` 慣例。値は
git に入れない)。**LaunchAgent で常駐化**(`--watch=60`)し、入金 → 確認 → 付与が
~60s ごとに全自動。読み取り専用(資金は動かさない)。

### 6. needs駆動 fleet オーケストレーション

- **匿名 fleet テレメトリ** `GET /infer/fleet` — opaque 非可逆 ID・バケット化
  ディスク・地理/ホスト情報なしのスナップショット(特定不可)
- **needs駆動再配置** `murakumo.infer.rebalance`(純): 匿名スナップショットの容量
  (16GB→10GB shard 上限)+ request mix → head/relay 1台予約 → 残りを text/media/
  postproc プールに needs 比例配分(largest-remainder + floor + ヒステリシス)
- **制御ループ** `nbb murakumo orchestrate`: `/infer/fleet` + `/infer/runs` を読み →
  rebalance → `/infer/placement` に公開

### 7. ファネルと成長ループ

`GET /itonami/funnel`: 試用 → 活性化(消費) → 課金、コンバージョン率付き。
コンソールに事業指標 / fleet 稼働 / ファネル / 対応モデル / 料金 / トライアル・
消費・購入 CTA を SSR。成長ループ: 貢献者↑ → 提供モデルクラス↑ → 需要↑ →
credit 流速↑ → treasury(Safe)↑ → ハード増強↑ → 貢献価値↑。

### 8. 品質ゲート(ブラウザ QA react loop)

`tools/qa/check.mjs`(Playwright headless): 公開サイトをロードし JS/console/network
エラー・要素・リンク・join フロー(実 credit 獲得まで)を検証、**12 エンドポイントを
意味的 assert 付き**で叩く(pay/quote は base+Safe+950cr、models≥7 等)。毎 firing の
回帰ゲート。実バグを複数捕捉(join-browser の async SyntaxError、`[:script [:hiccup/raw]]`
の非展開、spend の Worker 欠落、env munge、shadow-cljs stale build 等)。

## Consequences

- **決済の全経路が Base で自走**: 見積り → 送金(数セント)→ claim(pending)→
  常駐 verifier が確認 → 自動付与。鍵操作なし・安全弁(宛先/額/確認数)あり。
- **kotoba-native UI**: 全ページ hiccup(kotoba.html)+ css.core operator-theme で
  itonami cockpit と同一 design system。
- **オーナー操作の残り**: なし(TREASURY_ADDR/PAY_CHAIN 設定済み、API キーは Keychain、
  verifier 常駐済み)。API キーの 1Password/kagi 保存は認証待ち(Keychain には格納済み)。
- **未解決の構造課題**: 実トラフィックがまだ無く revenue は横ばい(全てデモ)。
  活性化率が低い(KV 遅延と摩擦)。fleet は 5/10 稼働(5台は物理再起動待ち)。

## 実装/デプロイ参照

- cloud: `cloud-murakumo.itonami`(business model 純)/ `routes.cljc` + `worker.cljs`
  (~20 エンドポイント)/ `infer_view.cljc`(SSR console)/ `tools/verify-payments.clj`
  / `deploy/com.murakumo.verify-payments.plist.tmpl` / `docs/business.md`(run book)
- murakumo: `murakumo.infer.rebalance` / `murakumo.infer.orchestrate` / `tools/qa`
- live: api.murakumo.cloud, app.itonami.cloud, relay.gftd.ai, itonami.cloud
