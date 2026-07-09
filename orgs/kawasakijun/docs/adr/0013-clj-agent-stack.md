# ADR-0013: portable Clojure エージェントスタック (langchain/langgraph/comfyui/browser-use/computer-use -clj)

- **Status**: Accepted / Implemented(5 リポジトリ公開・全テストグリーン)
- **Date**: 2026-06-12(langchain-clj / langgraph-clj は 2026-06-11)
- **Deciders**: 河崎純真 (j.kawasaki@gftd.co.jp)
- **Context tags**: clojure, cljc, wasm, kotoba-clj, datomic, datalog, llm-agent, langchain, langgraph, comfyui, browser-use, computer-use
- **Related**: ADR-0001(orchestrator)、ADR-0002(Pregel DAG)、ADR-0010(EDN 事実層 + Datalog ビュー — 本スタックの状態表現の原型)、ADR-0012(同パターンの org 適用)
- **Implementation**: `orgs/com-junkawasaki/{langchain,langgraph,comfyui,browser-use,computer-use}-clj/`
- **SSoT (machine-readable)**: `orgs/kawasakijun/clj-stack.edn`(スタック意味論)+ ルート `deps.edn`(ワークスペース配置)

## Context

エージェント基盤の主要 OSS(langchain / langgraph / ComfyUI / browser-use /
Anthropic computer-use)はいずれも Python ランタイム前提で、状態は
オブジェクト/pickle/JSON に分散する。ワークスペースの原則は

1. **Clojure WASM で動く前提** — kotoba-clj(orgs/etzhayyim/kotoba,
   Clojure→WASM compiler)を含む任意ホスト(JVM / SCI / CLJS)で同一コードが動くこと、
2. **Datomic API 前提** — 状態・履歴・キャッシュは EAV ファクト(ADR-0010 の
   「1事実1表現、ビューは Datalog」)であること。

この 2 前提で本家スタックを Clojure に再実装する。

## Decision

### 1. 5 リポジトリ・本家と同じ積層

```
browser-use-clj      computer-use-clj          ← エージェント層
        └────────┬────────┘
          langgraph-clj          comfyui-clj   ← オーケストレーション層
                └───────┬───────────┘
                  langchain-clj                ← 基盤層 (依存ゼロ)
```

| repo | 本家 | 提供物 | license (本家準拠) |
|---|---|---|---|
| langchain-clj | langchain-core | LCEL Runnable / message / prompt / ChatModel (Anthropic adapter) / tool / parser / datom チャット履歴 / **langchain.db**(Datomic API 互換ミニ EAV ストア + Datalog + pull + as-of) | MIT |
| langgraph-clj | langgraph | StateGraph + Pregel superstep / channel reducer / interrupt / checkpointer(datom 化・time travel)/ create-react-agent | MIT |
| comfyui-clj | ComfyUI | ノード型レジストリ / API format 互換ワークフロー / content-addressed キャッシュ付きトポロジカル実行(キャッシュ・run 履歴 = datom)/ prompt queue | GPL-3.0 |
| browser-use-clj | browser-use | IBrowser protocol / 番号付き要素表現 / アクションレジストリ / 操作ログ datom | MIT |
| computer-use-clj | computer-use-demo | IComputer protocol / computer tool action 語彙 / sampling loop / 操作ログ datom | MIT |

依存は git deps(`io.github.com-junkawasaki/* {:git/tag …}`)。
各リポジトリの `:dev` alias がワークスペース内 local checkout に override。

### 2. ランタイム規約(全リポジトリ共通)

- 全コード `.cljc`、サードパーティ依存ゼロ(スタック内 git deps のみ)。
- JVM interop / スレッド / core.async / wall clock / 乱数なし。
- **I/O・重い処理はホスト能力注入**: HTTP/JSON(`anthropic-model
  {:http-fn …}`)、ブラウザ(`IBrowser`)、デスクトップ(`IComputer`)、
  拡散モデル等(`comfyui.std/host-fn-node`)。ライブラリは protocol を
  消費するだけで、Playwright / xdotool / fetch / 推論はホストが実装する。

### 3. 状態は datom(ADR-0010 L1 の適用)

| 事実 | スキーマ | 例のビュー (Datalog) |
|---|---|---|
| チャット履歴 | `langchain.memory/memory-schema`(:thread/:msg) | スレッド横断の tool エラー一覧 |
| graph checkpoint | `langgraph.checkpoint/checkpoint-schema` | resume / time travel / 「中断中の全スレッド」 |
| ノードキャッシュ + run 履歴 | `comfyui.exec/exec-schema`(:cache/:run/:exec) | 「run 7 で再実行されたノードと理由」 |
| ブラウザ操作ログ | `browseruse.agent/log-schema`(:session/:action) | 「エージェントが訪れた全 URL」 |
| デスクトップ操作ログ | `computeruse.agent/log-schema`(:session/:caction) | 「ctrl+s を押した全セッション」 |

ストアは `langchain.db`(内蔵ミニ実装)だが、上位層は
`{:q :transact! :db :pull :entid}` の関数マップ(`langchain.db/api`)
越しにしか触らないため、本物の Datomic Local / DataScript に差し替え可能。
→ エージェント実行履歴が personal warehouse / m365-archive(ADR-0012)の
datom と **同一表現**になり、join できる。

### 4. SSoT の分担

- ルート `deps.edn` — ワークスペース**配置**(path / submodule / remote / 一行説明)。
- `orgs/kawasakijun/clj-stack.edn` — スタック**意味論**(層・依存辺・名前空間・
  注入されるホスト能力・提供 datom スキーマ・版)。goals.edn と同様、
  ツーリングが読む側の machine-readable SSoT。
- 各リポジトリ `docs/adr/0001-architecture.md` — 本家との対応表と
  リポジトリ固有の決定。

## Consequences

- 同一エージェントコードが JVM(テスト実行系)と kotoba-clj 等の WASM ホストで動く。
- 実行履歴・キャッシュ・チェックポイントが Datalog で監査可能(L4 attention
  ファイアウォール(ADR-0010)の入力源としてエージェント由来の事実を扱える)。
- 非スコープ(各 ADR 参照): Datalog rules / Send API / トークン単位
  ストリーミング / 実ブラウザ・デスクトップドライバ / 拡散モデルノード。

## Addendum: 長期耐久 loop の扱い (2026-06-28)

Claude Code 型の kotoba code agent は、長時間にわたり repository 観察、tool
実行、編集、検証、checkpoint 復帰を続ける可能性がある。これは
`langgraph-clj` の **内部無限ループ**として実装しない。設計上は
**有界 StateGraph run を durable outer loop が反復する**。

- 内側: `create-react-agent` / StateGraph。`recursion-limit`、checkpoint、
  `interrupt-before/after` を持つ 1 tick の有界実行。
- 外側: kotoba code runtime / host。lease、cadence、sleep、crash recovery、
  token/tool/spend budget、stale worker 回収、governor 判定を持つ長期 supervisor。
- 永続化: `:checkpoint/*` に加え、`:agent.loop/*`、`:agent.tick/*`、
  `:agent.lease/*`、`:agent.budget/*`、`:agent.event/*`、
  `:agent.governor/*` を datom として積む。
- 安全境界: 外部副作用は idempotency key 付き tool call として記録し、各 tick
  境界と privileged tool 前に governor を通す。

従って「長期耐久 loop」は許容するが、`.cljc` ライブラリは clock/thread/sleep/process
を持たない。継続性は kotoba/Datomic checkpoint と host supervisor の責務である。
