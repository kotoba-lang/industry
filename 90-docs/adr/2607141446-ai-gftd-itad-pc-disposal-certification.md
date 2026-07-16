# ADR-2607141446: ai-gftd-itad — ITAD（PC 廃棄証明）事業と gftd.co.jp LP

**Status**: accepted, deployed (itad.gftd.ai live — closing 2026-07-14, 残件は Follow-ups)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki

## Context

法人が PC を廃棄するとき、本当に欲しいのは「捨てること」ではなく
**「安全に捨てたと証明できること」**。2019 年の神奈川県庁 HDD 転売事件以降、
データ消去の証跡・廃棄証明書は法人の情報セキュリティ監査・個人情報保護法対応・
廃棄物処理法（産業廃棄物管理票 / マニフェスト）対応の実務要件になった。
一方で中小企業向けの ITAD（IT Asset Disposition）は「回収します」で止まる業者が
多く、**シリアル単位で追跡でき、後から検証できる証明書**を出すサービスは少ない。

gftd.co.jp（gftdcojp org のコーポレートドメイン）を受け皿に、日本語・日本の
法人向けに分かりやすい ITAD + 廃棄証明の LP と業務基盤を計画する。

## Decision

新規 project **`gftdcojp/ai-gftd-itad`**（private、org 既定 visibility）を起こし、
west manifest に登録する。

### 1. 事業（サービス設計）

- **提供物**: ①PC・IT 機器の回収（集荷/持込）②データ消去（NIST SP 800-88
  Rev.1 準拠の Clear/Purge、または物理破壊）③**廃棄証明書 + データ消去証明書の
  発行**（機器シリアル・ストレージシリアル単位）。
- **差別化**: 証明書を **append-only の datoms 台帳**から発行する。証明書番号
  （`GFTD-ITAD-<yymm>-<seq>`）ごとに、対象資産・消去イベントの canonical EDN
  から計算した検証ハッシュを載せ、後日 `gftd.co.jp/itad/verify/<cert-id>` で
  台帳と突合できる（改ざん不能性が product そのもの）。
- **対象顧客**: 中小企業の総務・情シス、PC 入替を伴うリース満了・オフィス移転・
  M&A・廃業。1 台からでも受ける（フォームの最小単位 = 1 台）。
- **価格（案・LP には「例」表記）**: 回収 0 円〜（台数・所在地による）、
  データ消去証明 1 台 ¥1,500〜（税別）、物理破壊 1 台 ¥2,500〜、
  オンサイト消去は別見積。※確定価格はオーナー決裁。
- **要許認可（オーナー action、LP 公開前に確認）**: 産業廃棄物収集運搬業許可
  （または許可業者との提携）、古物商許可（再販・リユースをやる場合）。
  LP・証明書に許可番号を載せる欄は placeholder（`準備中`）とし、**捏造しない**。

### 2. 技術（cljs + Datomic 互換 datoms 設計）

repo 全体のランタイム優先順位（kotoba wasm > clojurewasm > ClojureScript > nbb、
JVM/bb は compat）に従い、**第一の実行経路は ClojureScript / nbb**。「Datomic で
設計」は **Datomic 互換スキーマ（`:db/ident`/`:db/valueType`/`:db/cardinality`/
`:db/unique`）を EDN 正本に持ち、DataScript（cljs）にも Datomic（JVM、compat）にも
そのまま transact できる**形で満たす — BMC（`canvas-ledger.edn`）・design-quality
（`design-quality-ledger.edn`）と同型のパターン。

- `schema.cljc` — lead / order / asset / erasure / certificate の Datomic 互換
  スキーマと tx-data ビルダ（純関数）。
- `ledger.cljc` — append-only イベント台帳（1 行 1 EDN map、追記のみ・手編集禁止）。
- `cert.cljc` — 証明書番号採番、canonical EDN 文字列化、検証ハッシュ（hash fn は
  host 注入 — browser: SubtleCrypto / node: crypto、コアは純関数）。
- `form.cljc` — 申込フォームのモデル・バリデーション（純 cljc、LP とワーカーで共用）。
- `lp.cljc` — LP ビュー（純 cljc hiccup、kotoba-ui + uikit のみ require）。
- `worker.cljs` — Cloudflare Worker: `POST /api/itad/lead`（バリデーション →
  台帳イベント化 → LINE 通知）。deploy は follow-up。
- `web/generate.cljs` — nbb SSR で `dist/index.html` を生成
  （`kototama/web/generate.cljs` と同じ multi-dir `--classpath` パターン）。

### 3. LP（gftd.co.jp、日本人向けにわかりやすく）

kotoba-uiux skill / ADR-2607122200 の paved road に従う: `kotoba-ui.core` +
`uikit.core`（consumer/touch-first）のみ require、raw hex・ad-hoc font-size 禁止、
layout は shell、theme は 1 map。構成:

1. Hero — 「パソコンの廃棄、証明書までワンストップ。」CTA: フォーム / LINE 相談。
2. 課題 — 「捨てるだけ」のリスク（HDD 転売事件、個人情報保護法、マニフェスト）。
3. サービスの流れ — 申込（フォーム or LINE）→ 集荷 → データ消去 → 証明書発行の 4 step。
4. 特徴 — 改ざんできない台帳 / シリアル単位の追跡 / 1 台から / 明朗価格。
5. 料金（例） / FAQ。
6. 申込フォーム（会社名・氏名・メール・電話・台数・機器種別・希望日・備考 +
   hidden UTM）+ LINE 代替導線。
7. Footer — 会社情報、許可番号 placeholder。

生成 HTML は `design-quality` の決定論 audit（`bb score --min`）で採点してから
出荷（unmeasured page is theater、ADR-2607132300）。**gftd.co.jp 本番への deploy は
本 ADR のスコープ外**（既存コーポレートサイトの置換有無はオーナー判断 —
root に置くか `/itad` 配下かを含め follow-up）。

### 4. マーケティング（LINE / Facebook / Google）

- **LINE**: LINE 公式アカウント（Messaging API）。LP の第 2 CTA を友だち追加に
  し、フォーム未完了層を LINE 相談で回収。ワーカーからの lead 通知も LINE push。
- **Google**: 検索連動（想定 KW: 「パソコン 廃棄 法人」「PC 廃棄 証明書」
  「データ消去 証明書」「pc 処分 データ消去」）+ gtag でフォーム送信/LINE 追加を
  conversion 計測。
- **Meta (Facebook/Instagram)**: 総務・情シス向けリード獲得広告 + Meta Pixel。
- 計測タグは `resources/itad.edn` の `:marketing` 設定に ID が入った時だけ LP に
  差し込む（placeholder の偽 ID を出荷しない）。UTM はフォーム hidden に取り込み
  lead datom に永続化（チャネル別 CPA を台帳から集計可能）。

### 5. BMC / Lean Loop

共有 BMC システム（`70-tools/bmc`）の base datoms への登録は人間レビュー要件
（ADR-2607021600）なので **本 ADR では登録しない**。オーナー承認後に
`gftd products` の対象へ追加し、それまでは repo 側 README の仮説メモで運用。

## Consequences

- (+) 「証明書 = 検証可能な台帳の射影」という data-first 設計が、そのまま営業上の
  差別化（改ざん不能）になる。
- (+) 純 cljc コアなので LP（SSR/browser）・Worker・将来の管理画面で同一コードを共用。
- (−) 許認可（産廃収集運搬・古物商）と確定価格・gftd.co.jp 配置はオーナー決裁待ち。
  LP は placeholder を明示し、公開はその後。
- (−) Worker の実 deploy・LINE 公式アカウント開設・広告アカウント接続は follow-up。

## Addendum 1 — itad.gftd.ai で公開（2026-07-14 オーナー指示）

「itad.gftd.ai でひとまず公開」の指示により、gftd.co.jp 配置の決裁を待たず
**https://itad.gftd.ai/ で本番公開した**（Worker `ai-gftd-itad`、version
`22fc4c52-6a6a-495c-8271-23dce16eac94`、custom domain 自動プロビジョン）。
構成は local-murakumo と同型: shadow-cljs `:esm` で `worker.cljs` をビルドし、
LP は assets（`dist/index.html`）、`POST /api/itad/lead` のみ Worker が受ける。
KV は `ITAD_LEADS`（lead 本体）+ `ITAD_LEDGER`（append-only 台帳イベント）。

実装知見: `:advanced` 最適化が `(.-ITAD_LEADS env)` / `(.put kv ...)` を munge
していた（ビルド成果物に文字列が現れないことで実測検出）→ `unchecked-get` /
`js-invoke` の文字列アクセスに変更して解決。

Live 検証（2026-07-14）: `GET /` → 200 text/html、invalid POST → 400 +
フィールド別エラー、valid POST → 200 `{ok true}` で KV に lead + 台帳イベントの
書き込みを実確認（検証用テストレコードは確認後に削除）。LINE 通知はトークン
未設定のため設計どおりスキップ（偽の成功を作らない）。

Follow-up 1（gftd.co.jp 配置）は「itad.gftd.ai からの移設 or 併設の決裁」に読み替える。

## Addendum 2 — 日本型 CV 特化 LP への全面改稿 + SVG 画像（2026-07-14 オーナー指示）

オーナー提示の参考 LP（恒栄 lp_03 / まもるくん pc-disposal / パソコン廃棄.com
certificate）を実際に読み、日本の CV 特化 LP の型で全面改稿して再デプロイした
（version `d64f7c11`）。採用した型: 不安→安心の心理遷移（悩みチェックリスト→
選ばれる 3 つの理由）、セクション末尾ごとの CTA 反復（まもるくん式「課題→解決→
根拠→CTA」モジュール）、○△× 比較表、対応機器グリッド、モバイル追従 CTA バー。
**証明書見本の提示は参考 3 サイトのいずれも欠いており差別化点**として SVG モックで
実装（記載項目 + 検証ハッシュ + 「見本」透かし）。**実績数値・認証バッジ・顧客の声は
保有していないため一切使わない（捏造ゼロ）** — 使える正直な訴求（回収 0 円〜 /
1 台から / NIST SP 800-88 / 検証可能な台帳）だけで構成。

画像: 生成モデル API 不可（`api.murakumo.cloud` images 404/503 実測）のため、
`art.cljc` に `--hig-*` トークン連動のインライン SVG（hero イラスト・証明書見本・
ステップ / 機器アイコン・比較マーク）として実装。ダークモード自動追従・raw hex
ゼロ・外部リクエストゼロ。

実装知見: ①比較表は自社列（○ 列）を先頭に置く — モバイルの横スクロールで自社列が
画面外に切れる実測。②liquid-glass checkbox の box（@layer 内 width:20px）が
headless/dark 環境で潰れて描画される実測 → app 層（unlayered）でサイズ・背景・
枠・間隔を明示する consumer fix。③Cloudflare 静的 assets はデプロイ直後に旧版
cache HIT の谷がある — 描画検証はキャッシュ切替を跨いで 2 回撮る。

design-quality audit 100.00 維持。live スクリーンショット（mobile 390px /
desktop 1280px、headless Chrome — フォーカス非奪取）で全セクション視認済み。

## Follow-ups

1. gftd.co.jp の配置決定（root 置換 or `/itad`）+ Cloudflare Pages/Workers deploy。
2. LINE 公式アカウント開設、`:marketing`/`:line` 設定への実 ID 投入。
3. 許認可の確認・取得（産廃収集運搬 / 古物商）、確定価格。
4. 証明書 PDF 生成と `verify/<cert-id>` エンドポイント実装。
5. BMC base datoms へのプロダクト登録（オーナーレビュー後）。
