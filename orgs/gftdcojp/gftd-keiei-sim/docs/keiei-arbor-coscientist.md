# 経営 = 永続仮説ツリー上の探索 — Arbor × AI Co-Scientist 設計

現状の **線形ターン**（社員が施策を1つ提案 → 財務反論 → CEO統括 → 人間承認 → `apply_effect`）を、
2つの自律研究フレームワークの枠組みに再設計する。

- **Arbor**（[RUC-NLPIR](https://ruc-nlpir.github.io/Arbor/) / [arXiv:2606.11926](https://arxiv.org/abs/2606.11926)） —
  長寿命 **Coordinator** + 短命 **Executor** + **Hypothesis-Tree Refinement (HTR)**。
  `Ideate → Select → Dispatch → Backpropagate → Decide(merge/prune/continue/pending/stop)` のサイクルで
  長期目標を**累積的探索**へ変換。held-out 検証で merge を判定。
- **AI Co-Scientist**（[Google Research](https://research.google/blog/accelerating-scientific-breakthroughs-with-an-ai-co-scientist/)） —
  専門エージェント連合（**Generation / Reflection / Ranking / Evolution / Proximity / Meta-review / Supervisor**）。
  **Elo トーナメント**でランク付け、generate-debate-evolve ループ、test-time compute scaling で自己改善。

## 中核となる発想の転換

> **経営判断 = 反証可能な「経営仮説」を、永続ツリー上で生成・批評・対戦・進化・検証し、merge するプロセス。**

「社員が施策を1つ出す」のをやめ、各戦略ベットを**仮説ノード**として永続ツリー（HTR）に載せる。
仮説は Co-Scientist のループ（生成→批評→Eloトーナメント→進化）で磨かれ、Executor が**シミュレーション
ロールアウト**で検証し、Arbor の Decide ゲートで merge（=台帳へ commit + KPI反映）される。
**人間(CEO)は merge ゲートに残る**（既存の承認/却下 UX そのもの）。

pruned/continued なノードもツリーに残り**組織記憶**になる（過去の意思決定 = 過去の探索木）。

## エージェント対応表（既存資産への写像）

| Arbor / Co-Scientist の役割 | keiei エージェント | 実装 |
|---|---|---|
| **Supervisor / Coordinator**（長寿命） | CEO補佐(経営参謀) | `turn.clj` → `coordinator.clj`。木上の戦略管理・どの部門に何を ideate させるか・resource 割当・Decide |
| **Generation** | 営業 / 開発 / 財務 / 法務 責任者（既存 `employee.clj`） | 親ノードを refine/correct/extend する子仮説を kqe intel 接地で生成 |
| **Reflection** | 既存「財務 critique」を一般化した Reviewer | 各仮説を 実現性 / リスク / 根拠接地 で批評。弱い枝は早期 prune |
| **Ranking（Elo トーナメント）** | 新規 `tournament.clj` | pending leaf 同士の self-play 討議 → 勝者判定 → Elo 更新（既存の単一反論をブラケット化） |
| **Evolution** | 新規 `evolution.clj` | 上位仮説を結合・先鋭化・欠陥修正して子ノード生成 |
| **Proximity** | 新規 `proximity.clj` | 類似仮説をクラスタ化・dedup（`intel.bb` を素材に重複枝を統合） |
| **Meta-review** | 新規 `meta_review.clj` → `sim.intel/learn` | 再発パターンを洞察に蒸留し次の Generation をバイアス（**backprop の受け皿**） |
| **Executor**（短命・隔離） | `World` クローン上の前進シミュレーション | `apply_effect` + N四半期フォワードシム → dev signal（KPIΔ）。merge候補は held-out シナリオで再検証 |

「Arbor の隔離 worktree」 = **`World` のクローン（commit しないサンドボックス）**。
「Arbor の dev signal」 = **シミュレートした KPI デルタ**。「held-out 検証」 = **ストレス/実facts再生シナリオ**。

## データモデル — datomic 上の Hypothesis Tree

既存の `:gftd.intel/*` `:gftd.progress/*` `:sim.turn/*` ・意思決定台帳の隣に新名前空間 `:keiei.htr/*`。

```
:htr/id          uuid
:htr/parent      ref        ; root = 経営目標(例: "18ヶ月ランウェイ維持しつつ売上2倍")
:htr/hypothesis  string     ; 反証可能な経営仮説
:htr/author      keyword    ; :sales :eng :finance :legal :evolution
:htr/status      keyword    ; :pending :testing :merged :pruned :continued
:htr/elo         long       ; トーナメント rating (初期 1200)
:htr/dev-score   double     ; Executor のシミュ KPIΔ (合成スコア)
:htr/holdout     double     ; held-out 検証スコア (merge 判定用)
:htr/worst-case  double     ; minimax 最悪ケース (倒産回避ガード, priority.cljs と共有)
:htr/evidence    [ref]      ; 接地した intel quad (社名/金額/件名)
:htr/insight     string     ; 蒸留した教訓 (backprop で祖先へ伝播)
:htr/artifact    string     ; 構造化アクション (apply_effect への入力)
```

ステージ進行は既存 `:gftd.progress/*` と同じ **append-only datom** で記録（Datalog 畳み込みで現状態復元）。
**merge = 既存の意思決定台帳へ transact + `apply_effect`**。つまり Arbor の merge = 現行の「承認」。

## 経営サイクル（線形 `turn.clj` を置換）

四半期ごとに Coordinator が **Arbor サイクル**を回し、内部を Co-Scientist のサブループで test-time scaling する。

1. **Ideate（Generation）** — Coordinator が親ノードを選ぶ（現best方向 + 未解決の代替）。各部門が
   kqe intel 接地で子仮説を 1–3 生成（親を refine/correct/extend）。Proximity が既存ツリーと dedup。
2. **Reflect** — Reviewer が各新仮説を批評（実現性/リスク/根拠）。弱い枝は即 prune。
3. **Rank（Elo トーナメント）** — pending leaf（前ターン生存分を含む）の総当たり self-play 討議。
   各対戦: 2仮説を judge LLM が両論で戦わせ勝者判定 → Elo 更新。**K ラウンド = test-time compute ノブ**。
4. **Select & Dispatch（Executor）** — 上位 Elo leaf を Executor へ。`World` クローンに artifact を適用し
   数四半期フォワードシム → dev signal（Δcash/Δrunway/Δpipeline/Δmorale, minimax 最悪ケース）。
   merge 候補は **held-out シナリオ**（ストレス/実facts再生）で再検証し過学習を防ぐ。
5. **Evolve** — Evolution が上位2仮説を結合・欠陥修正して子ノード生成。次サイクルでトーナメント再投入。
6. **Backpropagate** — 各ノードに score/insight を記録し、教訓を**祖先へ抽象化伝播**
   （`:htr/insight` + 統合 `sim.intel/learn`。後者は既に財務/社員が kqe で読む）。
7. **Decide（人間 merge ゲート）** — Coordinator が leaf 毎に merge/prune/continue/pending/stop を具申。
   **CEO(人間)が merge を承認**（既存 UX）。カードは 仮説 / Elo / dev-score スパークライン / held-out /
   minimax 最悪ケース / 親系譜 / 蒸留 insight を表示。merge → 台帳 transact + `apply_effect`。

```
                       ┌──────────────── Coordinator (長寿命) ────────────────┐
   経営目標(root)       │  Ideate → Reflect → Rank(Elo) → Dispatch → Evolve   │
       │ HTR            │     ▲                              │ Executor        │
       ▼                │     └──── Backprop(insight) ◀──────┘ (World clone)   │
  ┌─────────┐           └──────────────────────────┬───────────────────────────┘
  │ datomic │  ◀── :keiei.htr/* (永続ツリー)         ▼ Decide
  │  SSoT   │  ◀── 意思決定台帳 ◀── merge ◀── 👑CEO 承認ゲート (human-in-the-loop)
  └─────────┘  ◀── apply_effect → KPI
```

## test-time compute scaling（自己改善ノブ）

generation 数・トーナメント round 数・evolution 深さを**難易度/stakes**に連動。
低ランウェイ・大型商談の四半期 → ラウンド増。既存の OpenRouter 役割別モデルルーティング＋thinking と接続し、
`compute-budget`/turn を Coordinator が配分（Co-Scientist の Supervisor の worker queue / resource 割当に対応）。

## 既存資産の再利用（Rust は薄いまま）

| 既存 | 新しい役割 |
|---|---|
| `employee.clj` / `turn.clj` の observe-all(kqe) | Generation の**接地機構**として継続。`turn.clj` → tree-cycle graph (`coordinator.clj`) |
| `intel.bb` | 証拠源のまま。Proximity と evidence-link が利用。**ツリー frontier brief** を新規投影し社員が現探索状態を見る |
| `priority.cljs` の WSJF/MCDA/minimax | **Select ヒューリスティック**と dev/holdout 表示。minimax 最悪ケース = held-out リスク。Elo を共シグナルに追加 |
| 意思決定台帳 + `apply_effect` | **merge の sink** |
| `model.rs` フォワードシム | **Executor**。`World::clone` + `simulate(effect, quarters)` サンドボックス（Arbor 隔離 worktree = 非commit クローン） |
| 議事録(🗣️ SSE) | Generation→Reflect→Rank→Execute→Evolve の**各フェーズを SSE で実況** |

## 責務分界（ロジックは Clojure、Rust は最小ランタイム）

- **clj**: Arbor サイクル + Co-Scientist サブループ・討議・進化・Elo 算術
  （`coordinator.clj` / `tournament.clj` / `evolution.clj` / `reflection.clj` / `proximity.clj` / `meta_review.clj`）。
  「スコア計算は Rust に持たない」原則（`intel.bb` と同様）を踏襲し Elo も clj。
- **Rust**: WASM 実行 / HTR quad の transact・query / `World` クローン Executor サンドボックス / SSE / merge ゲート API。

## API / datomic サーフェス（追加）

```
POST /api/turn/advance        → Arbor サイクルを1回実行 (coordinator graph)。更新ツリー + merge候補カードを返す
GET  /api/htr                 → ツリー状態 (ノード/エッジ/Elo/score)
POST /api/htr/:node/merge     → Decide: merge = 承認 + apply_effect + 台帳 transact
POST /api/htr/:node/prune     → Decide: prune
POST /api/htr/:node/continue  → Decide: continue (次ターンで再探索)
GET  /api/events (SSE)        → ツリー変異をフェーズ毎にストリーム
```

ダッシュボードに **🌳 仮説ツリー** タブ（tree SVG: ノード=仮説 / 色=status / サイズ=Elo /
バッジ=dev・holdout / リング=最悪ケース）。🎯 優先順位タブは top leaf を供給。

## 段階的ロールアウト

1. ✅ **HTR スキーマ + ツリー永続化**: `:keiei.htr/*` datom、root=経営目標、各提案を leaf ノード化(`src/htr.rs`)。
2. ✅ **Executor サンドボックス**: `Kpis` クローン上で `htr::simulate` が runway 連動ホライズンをフォワードシムし
   dev-score / worst-case(held-out ストレス)を付与。提案カード・🌳タブに表示。
3. ✅ **Elo トーナメント**: `htr::tournament` が pending leaf(前ターン生存分含む)を round-robin self-play
   (3ラウンド)でランク付け。提案は Elo 降順(=Select 順序)に並ぶ。
4. 🔶 **Evolution(済) + Proximity / Meta-review(未)**: `turn.clj` の `node-evolve` が上位案を結合・先鋭化した
   「進化版」を leaf 化。dedup / 明示 backprop は今後。
5. ✅ **Decide ゲート + 🌳タブ**: 承認=merge / 却下=prune を HTR status イベントに記録。`/api/htr` +
   🌳仮説ツリータブ(Elo バー・dev/worst・continue/prune)で可視化。
6. 🔶 **held-out 検証(済) + compute-budget(部分)**: worst-case ストレスは実装。トーナメント round 数の
   stakes 連動(test-time scaling)は今後。

**実装状況**: 段階 1–3・5 と Evolution / held-out を実装済。Rust(`src/htr.rs`=Executor+Elo+永続化、
`src/server.rs`=サイクル配線、`src/model.rs`=evolution 効果)、clj(`agents/turn.clj`=Evolution ノード)、
ClojureScript(`views.cljs`/`subs.cljs`/`events.cljs`=🌳タブ)。実 API は `POST /api/htr/:id/{prune,continue}`
(merge は既存 `POST /api/proposal/:id/approve` が兼ねる)。各段は単体で価値を出し、既存の
「承認/却下で経営が進む」体験を壊さない。次は Proximity(dedup)・Meta-review の明示 backprop・
LLM judge トーナメント(現状は dev-score 決定論勝敗)・compute-budget の stakes 連動。
