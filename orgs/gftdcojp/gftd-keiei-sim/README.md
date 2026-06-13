# gftd-keiei-sim — gftdcojp 経営シミュレーションゲーム

実在の会社 **gftdcojp** の運営を経営シミュレーションゲームとして進める。
あなたは **意思決定者(CEO)** として判断・可視化し、**LLMエージェント社員**（営業 /
エンジニアリング / 財務 / CEO補佐）が毎ターン提案を出す。承認/却下の判断が
KPI に反映され、意思決定は台帳に記録される。

```
実データ (m365-archive/facts)            あなた (意思決定者)
   people / crm / projects / decisions       │ 承認 / 却下
        │ seed                                ▼
        ▼                              ┌──────────────┐
  kotoba-datomic ──snapshot/brief──▶  │ Webダッシュボード │
   (状態 SSoT)        │                └──────────────┘
        ▲             ▼                        ▲ SSE
        │     kotoba-clj 社員エージェント        │
   意思決定台帳 ◀── (defgraph + llm-infer) ──── 提案カード
                     │ 実LLM (kotoba-llm)
                     ▼
              WasmExecutor (kotoba-runtime)
```

## 技術スタック (指定どおり)

| 層 | 技術 | 役割 |
|----|------|------|
| 状態 SSoT | **kotoba-datomic** | 会社の事実(orgs/projects/pipeline)・意思決定台帳・ターン履歴を Datalog で保持 |
| 社員 | **kotoba-clj** | `defgraph`(LangGraph風) + `llm-infer` の Clojure→WASM エージェント (`agents/*.clj`) |
| 実行/推論 | **kotoba-runtime** `WasmExecutor` + **kotoba-llm** `HttpInferEngine` | clj社員を WASM 実行し、実LLMで提案生成 |
| 可視化 | **ClojureScript** + Gather風2Dオフィス (サーバ権威 + SSE) | 社員エージェントが2Dキャラとしてデスクで働き、提案を吹き出しで表示 |

### ダッシュボード (ClojureScript) のビルド

フロントは **ClojureScript**（公式コンパイラ、shadow-cljs不要）。`src/cljs/gftd/app.cljs`
を `web/app.js` に :simple 最適化でコンパイルする:

```sh
clojure -M:cljs          # web/app.js を生成 (単一ファイル)
```

Gather.town 風の 2D オフィスで、営業/開発/財務/CEO補佐の4エージェントが
デスクで「働く」アニメーションを見せ、ターンを進めると 💭→吹き出しで提案を出す。
あなた(👑 CEO)が各吹き出しの承認/却下を判断する。コンパイル済み `web/app.js` は
コミット済みなので `cargo run` だけで動く。

kotoba は別ワークスペース (`../../etzhayyim/kotoba`) を **path dep** で参照する。

## 実行

```sh
cd gftdcojp/gftd-keiei-sim
cargo run                 # http://localhost:8787
```

初回は wasmtime 等のコンパイルで数分。起動時に実 facts をパースするため
（people.edn 928KB / crm.edn 804KB）数秒かかる。

### 実LLM接続 — gemma4 e4b (ローカル Ollama)

社員エージェントは **ローカル Ollama の gemma4 (e4b)** で動く。Ollama が起動して
いれば `cargo run` するだけで自動接続する（届かなければスタブにフォールバック）。

```sh
ollama serve            # 127.0.0.1:11434
ollama pull gemma4      # 8.0B / E4B / Q4 (vision+audio+tools+thinking)
cargo run               # 自動で gemma4 に接続
```

既定: `KOTOBA_INFERENCE_URL=http://localhost:11434` / `KOTOBA_INFERENCE_MODEL=gemma4:latest`
（環境変数で上書き可）。`GFTD_SIM_STUB=1` で強制的にスタブ。

gemma4 は thinking モデルのため、OpenAI互換 `/v1/chat/completions` だと思考が
`reasoning` に流れ `content` が空になる。本実装は Ollama ネイティブ `/api/chat` に
`think:false` を渡して結論だけを取得し、Markdown装飾・前置きを整形してから提案
カードに表示する（`src/infer.rs`）。

未起動ならスタブ推論でループ自体は完全に動作する（オフラインデモ可）。

## 遊び方

1. **次の四半期へ** を押す → 四半期バーンが引かれ、4社員が現状況を読んで提案する。
2. 各 **提案カード** の「承認する / 却下する」を判断。承認すると KPI に反映され、
   `kotoba-datomic` の **意思決定台帳** に記録される。
3. 現金がマイナスになると **倒産**。ランウェイ・士気を睨みながら経営を続ける。

初期状態と社員briefは **実 gftd データ由来**:

| 取り込み | データ源 | 用途 |
|----------|----------|------|
| 社内人員 | `people.edn` `:person/role :internal` | headcount (実数 215名) → 月次バーン |
| 実売上/コスト累計 | `invoice-terms.edn` `:amount_jpy` (発行=gftd issuer / 受領=gftd billed_to) | 累計売上(¥4.5億) / コスト(¥3.2億) |
| 主要商談 | `contract-terms.edn` 相手先 `:party_b` + `:amount_jpy` | 実社名つきパイプライン (株式会社にっぱん 等) |
| 直近の実活動 | `events.edn` / `triage-log.edn` の 2026 分 | 社員briefに実会議/実inboundを注入 |
| プロジェクト | `projects.edn` | 開発責任者brief |
| 台帳初期履歴 | `decisions.edn` | 過去の実意思決定 |

巨大ファイルは行単位で `{...}` 行だけを選択パースするため起動は速い
（`messages.edn`/`threads.edn` は未使用）。

## 構成

```
src/
  main.rs      起動・配線 (seed → datomic → agents compile → server)
  model.rs     KPI と提案、役割ごとの経営効果 (apply_effect)
  seed.rs      m365 facts → 初期KPI + datomic シード
  agents.rs    clj社員のコンパイル・実LLM配線・brief生成・ターン実行
  server.rs    axum: /api/state /api/turn/advance /api/proposal/:id/{approve,reject} /api/events(SSE)
agents/        社員エージェント (sales / engineering / finance / ceo_advisor).clj
web/           ダッシュボード (index.html / style.css / app.js)
.cargo/        祖先の wasm32 強制を host ターゲットへ上書き
```

## 画面構成 (タブUX)

情報過多を避けるため「1画面=1目的」のタブ構成。常時 KPI ヘッダ + タブ切替:

- 🏢 **オフィス**: ゲーム本体 (社員2Dキャラ・提案承認・議事録)
- 📅 **カレンダー**: 実 M365 予定を日付別アジェンダ表示 + 会議準備サマリ
- 📨 **受信トレイ**: 実 M365 メール一覧 (未読強調) + トリアージ
- 🧠 **インテリジェンス**: 関係グラフ・市場・リード・ファネル・更新リスク・依存・ダイジェスト
- 📊 **経営**: KPI時系列・実財務/不良債権・意思決定台帳

タブ状態は re-frame app-db の `:tab`。

## インテリジェンス層 (kotoba-datomic がエンジン)

ダッシュボードを開くだけでなく、実 facts から **会社のインテリジェンスを datomic 上で
構築・前進**させる。**分析ロジックは Clojure (babashka) `analysis/intel.bb`** に置き、
Rust (`src/intel.rs`) は「bb を実行して出た intel EDN を datomic に transact し、Datalog で
読み返す」オーケストレーションだけを担う（スコア計算式は一切 Rust に持たない）。

`analysis/intel.bb` が実 facts から導出するもの:

- **latent (潜在リード)** + **商談確度スコア(0-100)**: 接触量 + 直近性 + 関与人数を
  合成 (`confidence`)。離反リスク (`churn`: 旧活発だが直近途絶) も判定。
  → `:gftd.intel/kind :latent-lead` (linkedin.com 確度100 / rokes.exchange 離反 等)。
- **契約更新リスク**: `auto_renew=false` or 満了日ありの契約を `:renewal-risk` 化
  (iChain株式会社 満了2023-02-28 等)。
- **project (再生候補)**: file-count 大の休眠プロジェクトを `:revival` 化。
- **依存 (売上集中)**: `gftd → 取引先` の売上エッジを `:gftd.dep/*` 化。

**③ messages/threads からの確度精緻化**: `threads.edn`(44MB) をリード企業ドメインを
含む行だけ部分パース(全読み回避・1.4秒)し、未返信スレッド数(=商談モメンタム)を集計。
確度に加点する (rokes.exchange 未返信204件 → 確度+15 等)。

**① 社員エージェント自身が intel を kqe で読む**: サーバが intel 要約を
`sim/intel` quad に投影し、各 clj 社員が `(kqe-get-objects "sim/intel" "all"
"sim.intel/brief")` で読んで LLM プロンプトに織り込む(社員が datomic の知能を直接参照)。

**② 商談ステージのゲーム化**: 潜在リードは `new → engaged → qualified → won` の
ファネルを進む。ターン進行で確度上位リードが `engaged` へ、営業提案の承認で次段へ
前進する (`:gftd.progress/*` datom)。ダッシュボードにファネル件数を表示。

Rust 側の責務:

- **KPI は datomic クエリ由来**: headcount / pipeline を Datalog `q` で集計。
- **ステージ進行**: append-only `:gftd.progress/*` datom を積み、Datalog の畳み込みで
  現ステージを復元 (datomic の事実ログとして商談が進む)。

**多角的な関係グラフ (メール/threads/invoice/契約/contact/市場)**: `analysis/intel.bb`
が各取引先について以下を合成する:

- **path-weight (0-100)**: 接触量(crm) + 未返信モメンタム(threads) + 直近性 + 商流金額
  の合成 → 関係グラフのエッジ太さ。
- **rel-type**: invoice/契約の語幹マッチで `顧客(customer)/仕入先(vendor)/提携(partner)/
  見込(lead)` を判定 (rokes.exchange は ¥2,007万の商流マッチ → customer)。
- **market**: ドメインから `web3 / finance / public / jp-corp / global / academia` を分類し、
  セグメント別に org 数・接触量を集計 (`:gftd.market/*`)。

ダッシュボードの「🧠 インテリジェンス」パネル: **関係グラフ(SVG: 線=path-weight・
色=市場・リング=関係種別/離反)** + 市場分析 + path-weight/商流つきリード + 商談ファネル +
更新リスク + 再生候補 + 売上集中。

**不良債権(売掛金)分析**: `analysis/intel.bb` が gftd 発行請求のうち支払期限を大きく
超過したもの(:due が1年超前)を債務先別に集計し `:gftd.intel/kind :bad-debt` 化
(セールスリンク ¥4,308万 等 計¥1.3億)。実財務パネルに回収懸念として表示し、財務/法務の
brief(kqe)にも注入する。

### Rust は薄く、ロジックは Clojure

facts→状態の導出も Clojure に集約した。`analysis/seed.bb` が実 facts から初期 World
状態と datomic シード datom を 1 つの EDN マップで出力し、Rust(`src/seed.rs`)は
それを解析して `transact` するだけ。スコアリング(intel.bb)・初期化(seed.bb)・社員
(kotoba-clj)・ダッシュボード(ClojureScript)が Clojure 系で、Rust は datomic ストア /
WASM 実行 / HTTP サーバ の最小ランタイムに徹する。

dev 品質ゲート: `clojure -M:build`(警告=エラー) / `clojure -M:lint`(clj-kondo) /
malli による app-data 形状検証(`src/cljs/gftd/schema.cljs`, dev のみ)。

### 実 Microsoft 365 (Outlook) ライブ接続

抽出済み静的facts(extract-facts.py由来)に加え、**現在の M365 にライブ接続**できる。
`analysis/m365-live.bb` が `m365` CLI(ログイン済みセッション)から Graph アクセストークンを
取得し、受信トレイ直近・今後の予定(14日)・未読件数を取得(読み取り専用・メタデータのみ、
本文は取らない)。ヘッダの「📡 M365同期」(`POST /api/m365/sync`)で取得し、サイドの
「📡 ライブ M365」パネルに表示。電話番(予定件数)/メール担当(未読件数)のアンビエント社員も
ライブ値に連動する。

前提: `m365 login`(デバイスコード/MFA, `m365-archive/bin/setup-auth.sh` 参照)で
Microsoft 365 CLI にサインイン済みであること。未ログイン時は同期はスキップされる。

ライブ M365 の活用:

- **briefへの注入**: 同期したライブ情報(未読件数・直近予定)を `snapshot_quads` の intel
  ブリーフに織り込み、社員エージェントが kqe で読んで「今日の予定/未読」を踏まえ提案する。
- **メールトリアージ(メール担当エージェント)**: 「📨 メールトリアージ」で gemma4 が
  ライブ受信トレイから重要・要対応メールを抽出し推奨アクションを提示 (`POST /api/m365/triage`)。
- **会議準備サマリ**: 「📅 会議準備」で gemma4 が今後の予定の準備事項・論点を生成
  (`POST /api/m365/meeting-prep`)。

### 時系列・要約・対話・レポート

- **KPI時系列(#1)**: 各ターンの現金/売上/パイプラインを `:sim.turn/*` datom に記録し、
  ダッシュボードで SVG スパークラインとして推移表示。
- **商談要約(#2)**: `analysis/intel.bb` が取引先のスレッド件名履歴を `:deal-digest` 化。
  「要約」ボタンで gemma4 が商談状況と次の一手を要約 (`POST /api/summarize/:org`)。
  (メール本文は facts に無いため件名列を素材にする)
- **社員間ディスカッション深化(#3)**: 機能部門4名が並列提案 → **財務が他部門案を批評する
  反論ラウンド** → CEO補佐が統括、の3フェーズを議事録(🗣️)として表示。
- **経営レポート自動生成(#4)**: 「📊 経営レポート」ボタンで gemma4 が KPI推移+意思決定履歴
  から複数四半期の経営レポートを生成 (`POST /api/report`)。
- **並列推論**: 機能部門の提案を `spawn_blocking` で同時実行。
- **受注売上自動計上**: 商談ファネルが受注(won)に到達した時点で売上を自動計上。

### 豊富な actor (バーチャルオフィスの社員たち)

- **提案者 (kotoba-clj LLMエージェント)**: 営業 / 開発 / 財務 / **法務** / CEO補佐。
  毎ターン会議卓に集まり、intel を kqe で読んで提案する。
- **アンビエント社員 (役割別ライブ情報を表示しながら働く)**: 総務(更新確認件数) /
  庶務(取引件数) / 電話番(会議件数) / メール担当(inbound件数) / 営業担当(追客中リード)。

### ビルド (intel)

`analysis/intel.bb` は babashka スクリプト。サーバ起動時に Rust が自動実行する
（`bb` が PATH か `/opt/homebrew/bin/bb` か `~/.local/bin/bb` にあればよい）。単体実行:

```sh
bb analysis/intel.bb ../m365-archive/facts   # intel datom の EDN を stdout に出力
```

## 設計メモ

- 社員の `kqe-assert!` による提案 quad は **即コミットしない**。`InvokeResult` で
  回収し、あなたが承認したときだけ `kotoba-datomic` に transact する
  （human-in-the-loop ゲート）。
- 提案カード本文は clj社員が返す CBOR `{"ok": <提案文>}` を表示。
- KPI のライブ集計は高速性のためサーバ内に保持し、各ターン/意思決定を datomic に
  監査記録する（KPI も Datalog で読み返せる）。
- `WasmExecutor::execute` は同期 + 内部で LLM スレッドを起こすため、
  axum 側では `spawn_blocking` から呼ぶ。

## 拡張余地 (今後)

- 実LLMの提案から構造化アクション(数値効果)を抽出して `apply_effect` を動的化
- スナップショット quad を社員が `kqe-query` で直接読む（現状は brief 経由）
- kami-engine (`kami-game` economy) でのリッチな 3D/2D 可視化
- messages/threads の大規模 facts を使った商談スコアリング
