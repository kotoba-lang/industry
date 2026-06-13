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
| 可視化 | Web ダッシュボード (サーバ権威 + SSE) | kotoba-runtime-web と同じ「サーバが権威・ブラウザが描画」方式 |

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

## インテリジェンス層 (kotoba-datomic がエンジン)

ダッシュボードを開くだけでなく、実 facts から **会社のインテリジェンスを datomic 上で
構築・前進**させる (`src/intel.rs`):

- **KPI は datomic クエリ由来**: headcount / pipeline を Datalog `q` で集計
  （メモリではなく datomic が源泉）。
- **依存関係 (売上集中)**: `gftd → 取引先` の売上依存エッジを `:gftd.dep/*` datom 化。
- **latent (潜在リード)**: 接触量上位だが定型ベンダでない org を `:gftd.intel/kind
  :latent-lead` として datom 化 (d-standing.co.jp 14,637接触 等)。
- **project (再生候補)**: file-count 大の休眠プロジェクトを `:revival` として datom 化
  (From G Suite Drive 10,802ファイル 等)。
- **intel の前進**: ターンごとに次の潜在リードを `engaged` へ進める **append-only の
  `:gftd.progress/*` datom** を積む。現在ステージは progress イベントの畳み込みで
  Datalog から復元 (datomic の事実ログとして知能が深化)。

これらは社員briefにも注入され、ダッシュボードの「🧠 インテリジェンス」パネルに
Datalog クエリ結果として表示される。

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
