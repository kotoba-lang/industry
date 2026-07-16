---
id: adr-2606250956-browser-agent-clj-genspark-style-super-agent
title: "ADR-2606250956: browser-agent-clj — genspark 風 super-agent（自前 browser 所有）を cljc/cljs + Datomic で設計"
status: accepted
doc_type: adr
topic: super-agent-runtime
authoritative: true
last_verified: 2026-06-25
implemented: 2026-06-25
implementation:
  repo: com-junkawasaki/browser-agent-clj
  submodule: orgs/kotoba-lang/browser-agent
  pinned: c7bf2b2
  landed_via: "com-junkawasaki/root PR #71 (merge 2fdc7eb)"
authoritative_for:
  - genspark 風「汎用 super-agent」を com-junkawasaki に OSS 部品として新設する判断
  - agent が *自前で browser を所有・管理する*（owned/managed browser）設計
  - Mixture-of-Agents supervisor / planner / tool registry / Datomic memory の層構造
  - 既存 clj スタック（langchain-clj → langgraph-clj → browser-use-clj / computer-use-clj）への載せ方
  - フレームワーク(com-junkawasaki) と デプロイ実体(etzhayyim) の三組織境界
related:
  - orgs/kotoba-lang/langchain      # 0-dep foundation + Datomic-compat store
  - orgs/kotoba-lang/langgraph      # StateGraph / checkpoint / create-react-agent
  - orgs/kotoba-lang/browser-use    # IBrowser host capability + indexed-element ページ表現
  - orgs/kotoba-lang/computer-use   # desktop 操作 sub-agent（IComputer host capability）
  - orgs/kotoba-lang/kotoba             # CLJ→WASM ランタイム（実行ホスト候補）
  - orgs/kawasakijun/docs/adr/0020-three-org-taxonomy.edn
supersedes: []
superseded_by: []
---

# ADR-2606250956: browser-agent-clj — genspark 風 super-agent（自前 browser 所有）を cljc/cljs + Datomic で設計

**Status**: accepted — **実装済み・main マージ済み**（2026-06-25）
**Date**: 2026-06-25
**Deciders**: Jun Kawasaki

> **実装サマリ（2026-06-25）**: `com-junkawasaki/browser-agent-clj` を新設し
> （MIT, public）、superproject に submodule 登録（`orgs/kotoba-lang/browser-agent`,
> pin `c7bf2b2`）。本体 P0–P3 + 自前 browser 層を `.cljc` で実装、**end-to-end
> テスト 19 assertions / 0 failures**、オフライン mock デモ動作。詳細は末尾
> 「実装状況」を参照。

## Context

genspark のような「汎用 super-agent」（1 つの指示から自律的に計画し、Web を
ブラウズし、検索・コード実行・成果物生成までを複数の専門エージェントの協調
=Mixture-of-Agents で完遂するプロダクト）を、com-junkawasaki に **OSS の再利用
部品**として作りたい。repo 名は **`browser-agent-clj`**（owner 決定）。

genspark の肝の一つは **AI Browser を自前で持つ**こと——ユーザーのタブを横から
操作するだけでなく、**agent 専用の browser（独自プロファイル・cookie・タブ・
セッション）を所有し、ライブで覗け、必要なら人が割り込める**。本 ADR では
これを第一級の設計要素として据える。

幸い、この org には genspark を構成する部品がほぼ揃っており、新規モノリスでは
なく**既存の層の上にオーケストレーション層 1 枚 + 自前 browser 層**を足すのが
正しい。

| 既存 submodule | 役割 | browser-agent-clj での再利用 |
| --- | --- | --- |
| `langchain-clj` | 0-dep foundation（Runnable/LCEL, message, model, tool, memory, **Datomic 互換 store** `db.cljc`） | LLM 抽象・tool 抽象・EAV store の土台 |
| `langgraph-clj` | StateGraph + Pregel superstep + interrupt + **checkpointer（in-memory / Datomic）** + `create-react-agent` | supervisor / planner / sub-agent の実行基盤 |
| `browser-use-clj` | `IBrowser` host capability + indexed-element ページ表現 + action log を datom 化 | **web sub-agent のコア**。本 repo はここに *managed provider* を足す |
| `computer-use-clj` | `IComputer` host capability + Anthropic computer-use 語彙 | **desktop 操作 sub-agent** |
| `kotoba` | CLJ→WASM ランタイム | sub-agent / tool / coder exec を WASM サンドボックスで実行する選択肢 |

共通設計原則（既存スタックが全て守っている）を継承する:

1. **全 namespace は `.cljc`** — SCI / cljs / GraalVM / kotoba-clj / JVM で動く。
2. **I/O は host capability として注入** — ライブラリ本体は HTTP・JSON・時刻・
   乱数・ブラウザを直接触らない。WASM 前提。
3. **状態は Datomic API 経由で永続化** — `langchain.db` 互換 EAV store。実行ログ・
   計画・記憶・**browser セッション**・チェックポイントすべてが datom = Datalog で
   照会可能。`as-of` で time-travel できる（genspark にない監査・再現性の強み）。

### 三組織境界（ADR-0020）

- **フレームワーク = com-junkawasaki**（ユーザー指定とも一致）。
- **使命を持つデプロイ実体 = etzhayyim**（特定ミッションで自律稼働する organism）。
- **企業が操作する画面 = gftdcojp**（顧客向けに商用化する場合のみ）。

## Decision

com-junkawasaki に新規 OSS submodule **`browser-agent-clj`** を新設。genspark 風
super-agent を **既存 clj スタックの上のオーケストレーション層 + 自前 browser 層**
として設計する。ライセンス **MIT**。フロントは同一 `.cljc` ロジックを共有する
**ClojureScript SPA**。

### 0. 自前 browser を持つ（owned / managed browser）— genspark AI Browser 相当

設計原則「browser は注入される host capability（`IBrowser`）」は**維持しつつ**、
本 repo が **既定の managed provider とライフサイクル管理・ライブ配信**を同梱する。
すなわち「注入もできるが、何も挿さなければ agent が自分の browser を立ち上げて所有
する」。

- **`browser/provider.cljc`** — 既定の `IBrowser` 実装群（host capability として
  注入される実体）。
  - JVM: Playwright/CDP で **agent 専用 Chromium をプロセスとして起動・所有**
    （独自 user-data-dir = プロファイル/cookie/履歴を agent が保持）。
  - cljs: ブラウザ内 `IBrowser`。UI が動くタブ、または `window.open` した
    **agent 所有タブ**を操作。
  - kotoba-WASM: CDP/MCP 越しのリモート browser。
- **`browser/session.cljc`** — browser を **Datomic の永続エンティティ**として所有。
  `:browser/session`（プロファイル・cookie ジャー・開いているタブ・現在 URL）を
  datom 化。task をまたいでセッションを再開でき、「agent がいま何を見ているか」を
  Datalog で照会できる。マルチタブ・マルチセッション（並列ブラウズ）に対応。
- **`browser/live.cljc`** — agent の browser 画面を **UI へライブ配信**
  （CDP screencast / 定期スクリーンショットを host capability の transport で
  ストリーム）。ユーザーは agent のブラウジングを**リアルタイムで覗き**、
  langgraph `interrupt` で**割り込んで手動操作 → agent に返す**（take-over /
  human-in-the-loop）。これが genspark「AI Browser を眺めながら任せる」UX。
- **境界**: `browser-use-clj` は browser を**注入される能力**として純粋に保つ
  （WASM/テストは `mock-browser` のまま）。`browser-agent-clj` はその上で
  **既定の browser を所有・管理・配信**する責務を持つ。役割分離を崩さない。

### 1. 層構造

```
┌──────────────────────────────────────────────────────────────┐
│  browser-agent-ui (cljs / reagent + re-frame)                  │  ← ブラウザ
│  task 入力 / 計画ツリー / **agent browser ライブビュー(take-over)** │
│  / live イベントストリーム / 成果物表示                          │
└───────────────▲──────────────────────────────────────────────┘
                │ SSE/WS + screencast（host capability で注入される transport）
┌───────────────┴──────────────────────────────────────────────┐
│  browser-agent-clj  (.cljc — JVM / WASM / cljs 共有)            │
│  ┌────────────┐  ┌───────────┐  ┌──────────────────────────┐  │
│  │ supervisor │→ │  planner   │→│  sub-agent fleet          │  │
│  │ (MoA route │  │ (decompose │  │  web / desktop / research │  │
│  │  + aggreg) │  │  + replan) │  │  / coder / author        │  │
│  └────────────┘  └───────────┘  └──────────────────────────┘  │
│  **owned browser**: provider + session(datoms) + live stream    │
│  tool registry (langchain.tool + MCP bridge)                   │
│  memory: episodic / semantic / plan / browser ── all datoms     │
└───────────────▲──────────────────────────────────────────────┘
                │ Runnable / StateGraph / checkpoint
        langgraph-clj  ──  langchain-clj  ──  Datomic API store
```

### 2. Supervisor = Mixture-of-Agents（genspark の核）

`supervisor.cljc` は langgraph `StateGraph` で **router → 専門 sub-agent →
aggregator** の超循環を回す。

- **router**: 現在の plan step を見てどの sub-agent / model に委譲するか決める
  （cheap model でルーティング、難所だけ強 model）。
- **sub-agent**: それぞれ独立した `create-react-agent` グラフ。langgraph の
  **subgraph** として埋め込み（checkpoint・interrupt が伝播）。
- **aggregator**: 複数出力を統合し矛盾を cross-check（factuality 機構）。
- **MoA**: `:models {:router … :worker … :critic …}` で Anthropic/他社/ローカルを
  差し替え自由（model は `langchain.model/ChatModel` 抽象）。

### 3. Planner = plan-and-execute（長時間自律）

`planner.cljc`: task → **todo を datom 化**（`:plan/step`）→ supervisor で実行 →
結果を見て **replan**。中断点は langgraph `interrupt`（承認待ち / resume / browser
take-over を同一機構で）。

### 4. Sub-agent fleet

| sub-agent | 土台 | host capability | 備考 |
| --- | --- | --- | --- |
| `web` | browser-use-clj | **既定で本 repo の owned browser** を使用 | deep-research・予約・フォーム |
| `desktop` | computer-use-clj | `IComputer`（xdotool/VNC/MCP） | OS 操作 |
| `research` | langgraph react-agent | `search-fn` / `fetch-fn` 注入 | 多経路検索→精読→出典付き統合 |
| `coder` | langgraph react-agent | `exec-fn`（kotoba-WASM サンドボックス推奨） | コード生成・実行・検証 |
| `author` | langgraph react-agent | `render-fn` | slides/sheets/doc/page 生成 |

新 sub-agent は「tool map を足す」だけで追加可（browser-use-clj と同じ拡張性）。

### 5. Tool registry + MCP bridge

`tools.cljc` が全 tool を集約。`mcp.cljc` が MCP サーバの `list_tools`/`call_tool`
を `langchain.tool` に写像し、genspark の「80+ tools」級を注入で拡張。

### 6. Datomic データモデル（要点スキーマ）

`langchain.db` 互換。1 conn に merge（既存 `browseruse/log-schema` と合流）。

```clojure
(def browser-agent-schema
  {;; --- task / session ---
   :task/id        {:db/unique :db.unique/identity}
   :task/prompt    {} :task/status {}                  ; :running :done :failed
   ;; --- owned browser session（自前 browser の所有状態）---
   :browser/id        {:db/unique :db.unique/identity}
   :browser/task      {:db/valueType :db.type/ref}
   :browser/profile   {}                               ; user-data-dir / プロファイル鍵
   :browser/cookies   {}                               ; cookie ジャー（暗号化想定）
   :browser/current   {}                               ; 現在 URL
   :browser/tabs      {}                               ; 開いているタブ一覧
   :browser/status    {}                               ; :open :idle :handed-over :closed
   ;; --- plan（replan で追記。履歴は as-of で残る）---
   :plan/task {:db/valueType :db.type/ref} :plan/step {} :plan/goal {}
   :plan/status {} :plan/assignee {}                   ; :todo/:doing/:done/:skipped, 委譲先
   ;; --- delegation（MoA 判断ログ）---
   :deleg/plan {:db/valueType :db.type/ref} :deleg/agent {} :deleg/model {} :deleg/result {}
   ;; --- action（sub-agent 実行 = browser-use-clj 既存スキーマを継承）---
   :action/session {:db/valueType :db.type/ref}
   :action/step {} :action/name {} :action/input {} :action/result {} :action/url {}
   ;; --- memory（semantic：事実・実体・出典）---
   :mem/task {:db/valueType :db.type/ref} :mem/kind {} :mem/key {:db/index true}
   :mem/value {} :mem/source-url {}
   ;; --- artifact（成果物）---
   :artifact/task {:db/valueType :db.type/ref}
   :artifact/kind {} :artifact/title {} :artifact/body {} :artifact/mime {}})
```

「agent がいま開いている全タブ」「この task が訪れた全 URL」「ある実体について
集めた事実と出典」「計画が何回 replan されたか」すべてが **Datalog 1 本**で照会でき、
`as-of` で任意時点に巻き戻せる。チェックポイント（resume / time-travel /
take-over）は langgraph-clj の Datomic checkpointer をそのまま使う。

### 7. cljs フロントエンド

- `browser-agent-ui`（reagent + re-frame、shadow-cljs）。ロジックの `.cljc` を共有。
- transport（SSE/WS + screencast）は host capability 注入。
- 画面: task 入力 / 計画ツリー / **agent browser ライブビュー（覗く＋take-over）** /
  delegation ストリーム / 成果物プレビュー / 監査タブ（Datalog 結果）。

## Repo scaffold（提案）

```
browser-agent-clj/
  deps.edn                 # langgraph-clj / langchain-clj / browser-use-clj を :git/tag 依存
  LICENSE                  # MIT
  README.md
  src/browseragent/
    supervisor.cljc        # MoA: router → sub-agent subgraph → aggregator
    planner.cljc           # plan-and-execute + replan（plan を datom 化）
    fleet.cljc             # sub-agent 定義（web/desktop/research/coder/author）
    browser/
      provider.cljc        # 既定 IBrowser 実装（JVM Playwright / cljs in-browser / WASM CDP）
      session.cljc         # owned browser を Datomic 永続化（プロファイル/cookie/tabs）
      live.cljc            # screencast を UI へライブ配信 + take-over(interrupt)
    tools.cljc             # tool registry 集約
    mcp.cljc               # MCP ↔ langchain.tool ブリッジ
    memory.cljc            # semantic memory（datom 読み書き + Datalog ヘルパ）
    schema.cljc            # browser-agent-schema（上記）
    run.cljc               # トップレベル run（host capability 注入の入口）
    events.cljc            # UI 向けイベント整形（.cljc 共有）
  ui/src/browser_agent_ui/ # reagent + re-frame（cljs）
  examples/                # mock browser/host で動く end-to-end デモ
  test/
  docs/adr/0001-architecture.md   # genspark → browser-agent-clj 対応表
```

## ロードマップ（段階導入）

- ✅ **P0 — supervisor MVP**: router→web sub-agent→aggregator を langgraph
  StateGraph で。browser-use-clj を web sub-agent として組み込み。mock で e2e。
- ◐ **P1 — owned browser**: `browser/provider`+`session`+`live` 実装済み
  （mock provider 同梱・session を `:browser/*` datom 化・live frame 発火・
  take-over ラッチ）。**JVM Playwright provider はスケッチのみ（未実装、次段）**。
- ✅ **P2 — planner + memory**: plan-and-execute、`:plan/*`/semantic memory を
  datom 化、replan フック。
- ✅ **P3 — fleet**: web / research / coder / author sub-agent（後3者は langgraph
  ReAct + 注入 host fn）。
- ◐ **P4 — cljs UI**: reagent ビュー + 共有 `events.cljc` + shadow-cljs/HTML を
  scaffold 済み。**ビルド配線（npm/shadow）は未実施。**
- ☐ **P5 — MCP bridge + MoA 拡張**: `mcp.cljc`（MCP 橋渡し）と multi-model
  ルーティング/cross-check は未着手。
- ☐ **P6 — etzhayyim デプロイ**: 特定ミッションの organism として常駐稼働。

## genspark 機能 → 本設計の対応

| genspark | browser-agent-clj |
| --- | --- |
| **AI Browser を自前で所有**（独自プロファイル/セッション、覗ける、take-over） | `browser/provider`+`session`+`live`（owned managed browser + screencast + interrupt） |
| Mixture-of-Agents（複数 LLM + supervisor） | `supervisor.cljc`（router/aggregator + `:models` 注入） |
| Super Agent（自律マルチステップ） | `planner.cljc` + langgraph interrupt/checkpoint |
| agentic browsing | `web` sub-agent = browser-use-clj（owned browser 上で動作） |
| 80+ tools | `tools.cljc`（実装）+ `mcp.cljc`（MCP 橋渡し, P5 未着手） |
| 成果物生成（slides/sheets/page…） | `author` sub-agent + `:artifact/*` datoms |
| factuality / cross-check | aggregator の多数決・自己批判 + `:mem/source-url` 出典 |
| （独自）監査・再現性 | 全状態 datom 化 + Datomic `as-of` time-travel（browser セッションも含む） |

## 論点の決着（旧「採否が必要な論点」）

1. **repo 名** → **`browser-agent-clj`** に決定（owner）。`*-clj` 命名規則に整合。
2. **新規 submodule をいま作るか** → **作成済み**。CLAUDE.md どおり、衝突時は
   GitHub API でサーバ側にクリーン commit を起こして解決（`root` PR #71, merge
   `2fdc7eb`）。
3. **UI フレームワーク** → **re-frame** を採用（`ui/shadow-cljs.edn` に依存追加、
   現状の scaffold は reagent ratom。re-frame への置換は P4 で）。
4. **coder exec** → **kotoba-WASM サンドボックスを既定方針**として `:exec-fn`
   注入で受ける形に（実体注入は利用側、ライブラリは純粋）。
5. **owned browser の既定実体** → 当面 **`:mock` provider を既定**とし（テスト/
   オフライン）、**JVM Playwright provider は P1 残作業**（`provider.cljc` に
   スケッチ）。プロファイル/cookie 暗号化保存は Playwright 実装時に決める。**未決**。
6. **ライブビュー transport**（CDP screencast / SSE か WS か）→ host capability
   注入（`:emit-fn`/`:screenshot-fn`）に抽象化済みで、具体実装は **P4 で決定**。**未決**。

## 実装状況（2026-06-25）

- **repo**: `com-junkawasaki/browser-agent-clj`（MIT, public, init `c7bf2b2`）。
- **submodule**: `orgs/kotoba-lang/browser-agent` を `root` に登録、
  pin `c7bf2b2`（PR #71 / merge `2fdc7eb`）。
- **実装名前空間**: `run` `planner` `supervisor` `fleet` `memory` `schema`
  `events` + `browser/{provider,session,live}`（全 `.cljc`）。UI は
  `ui/`（reagent + shadow-cljs scaffold）。
- **検証**: `clojure -M:dev:test` → **19 assertions / 0 failures**
  （planner→MoA→fleet の e2e、自前 browser のナビゲーション＋session datom、
  semantic memory、artifact、`as-of` time-travel まで Datalog でアサート）。
  `clojure -M:dev:run` でオフライン mock デモ（イベントストリーム＋監査証跡）。
- **未実装（次段）**: JVM Playwright provider（P1）、cljs UI ビルド配線＋re-frame 化
  （P4）、`mcp.cljc` と multi-model ルーティング/cross-check（P5）。

## Consequences

- **＋** 既存 4 submodule を再利用、新規は薄いオーケストレーション層 + 自前 browser
  層に集約。`.cljc` 共有で server/WASM/browser を横断。**agent が自分の browser を
  所有**し、ユーザーは覗いて take-over でき、その browser セッションすら datom として
  Datalog 照会＆time-travel できる——genspark にない監査性・再現性。e2e テストで
  この一気通貫が動作することを実証済み。
- **－** owned browser は実体（Playwright/Chromium プロセス、cookie 永続）を抱えるぶん、
  WASM/テストの純粋性は `mock-browser` で担保しつつ provider 側にネイティブ依存が出る。
  現時点では Playwright provider は未実装で、実ブラウザ動作は次段。MoA・MCP は外部
  依存（model API / MCP サーバ）が増える。
- **中立** 実ブラウザ provider・transport は host capability 注入として抽象化済みで
  未決（P1/P4 で確定）。本 ADR は骨子を固定し、P0–P3 + 自前 browser 層を実装済み、
  残りは P4–P6 で段階導入する。
