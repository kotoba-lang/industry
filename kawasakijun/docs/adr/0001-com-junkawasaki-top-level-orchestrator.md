# ADR-0001: com-junkawasaki = Top-Level Life-Planning Orchestrator

- Status: Accepted
- Date: 2026-05-18
- Deciders: Jun Kawasaki
- Implementation: `kawasakijun/living/`, `kawasakijun/reverse_topo_pregel.py`
- Related repos: spirit-in-physics, etzhayyim/root, gftdcojp/ai-gftd-apps-gftdcojp,
  260208-spirit-in-physics

## 1. Context

河崎純真個人の人生計画 (28 ノード DAG) を運用するに当たり、複数のリポジトリ間で
責務を分離する必要がある。

| Repo | Role |
|---|---|
| `com-junkawasaki` | 個人ホームページ + 物理形式化 + **人生計画** |
| `spirit-in-physics` | Wheeler-DeWitt / σ₈-H₀ tension / arXiv 投稿 |
| `etzhayyim/root` | 宗教法人 9 領域 (artificial organism ecosystem) |
| `gftdcojp/ai-gftd-apps-gftdcojp` | Gftd Japan vendor monorepo (338 apps) |
| `260208-spirit-in-physics` | Ghost Hacker manuscript + KDP pipeline |

各リポは既に独自の agent (Claude Code / Codex / CI bot) を持ち、独立に開発が進む。
しかし全体最適化 (W(τ) maximization) は単一の計画主体が必要。

## 2. Decision

**`com-junkawasaki` を「最上位 orchestrator」**として位置付ける:

1. **Sensors (read-only)**: Gmail / Calendar / Photos / git / Drive / animeka /
   MoneyForward / Apple Health から KPI を収集
2. **Pregel state**: `reverse_topo_pregel.py` の 28 ノード DAG 上で状態管理
3. **Actions (write-only, indirect)**:
   - `gh issue create` → downstream repo の agent が picking
   - `gcal event create` → 個人 Calendar
   - `gmail draft` → 個人 inbox
   - Magatama actor dispatch (future)

**この repo は downstream のコードに直接触らない**。
すべて issue / event / draft 経由で間接的に依頼する。

### Boundary diagram

```
                ┌────────────────────────────────────┐
                │ com-junkawasaki (THIS)             │
                │  • Pregel state                    │
                │  • DID: did:web:junkawasaki.com    │
                │  • Sensors → state → Issues out    │
                └────────────────────────────────────┘
                                │  (issues, drafts, events)
        ┌────────────┬──────────┼──────────┬────────────────┐
        ↓            ↓          ↓          ↓                ↓
  spirit-in-     etzhayyim/   gftdcojp/   260208-       (local nodes)
  physics        root         monorepo    spirit
   ↑ #16          (agent      ↑#1281      (agent
                  TBD)        ↑#1282       TBD)
```

### Downstream agent contract

各 downstream repo の agent は以下を満たす:

1. ラベル `kawasakijun-orchestrated` の issue を watch
2. 該当ノードを実装 (PR + tests)
3. 完了したら issue close + 構造化コメントで `completion: true`
4. orchestrator は `gh api` で issue state を読み、Pregel `completion` を更新

## 3. Rationale

### 3.1 単一の計画主体

W(τ) = α·I + β·R + γ·O は全 domain 横断の最適化関数であり、これを各 downstream
が局所的に最大化しても全体最適にならない。**最上位 orchestrator が必要**。

### 3.2 関心の分離

| Concern | Owner |
|---|---|
| Strategy (どの順番でやるか) | com-junkawasaki |
| Tactics (どう実装するか) | downstream agent |

orchestrator は「**何を**、**いつ**」を決め、downstream は「**どう**」を決める。

### 3.3 Idempotent + 監査可能

すべての発行 issue にラベル `kawasakijun-orchestrated` を付与。`repos.yaml` で既存
issue を declare し、重複発行を防ぐ。`.audit.jsonl` で全 emission を記録。

### 3.4 Pregel BSP の自然な適用

`reverse_topo_pregel.py` の super-step が「24h」に対応し、cron で日次実行。
各 super-step で:
1. 全 sensor を collect
2. ready_nodes() を抽出
3. 各 ready node について repo を resolve
4. 該当 repo に open issue がなければ作成
5. local nodes は LogNote / Calendar block / Email draft

## 4. Consequences

### 4.1 Positive

- **全体俯瞰 + 一貫した優先付け** が可能
- **downstream agent の自律性** を保ちつつ全体最適
- **過剰モデリング検出** が容易 (CPM で slack を可視化)
- **idempotent**: 再実行で重複なし
- **監査ログ**で意思決定の追跡可

### 4.2 Negative

- com-junkawasaki が **single point of failure**
- repos.yaml の **手動メンテ** が必要 (新 repo / 新 node 追加時)
- downstream agent が **completion を正しく報告しない** とドリフトする
- **OAuth トークン** などのシークレットを Living System が持つ必要 (実 sensor 化時)

### 4.3 Mitigations

- com-junkawasaki SPOF → **state は git 管理**、別環境で再起動可
- repos.yaml ドリフト → **重複検出ロジック** + `gh issue list` で実態を re-sync
- completion 不報告 → **stale detection** で再 ping
- シークレット → **1Password Gftd Japan vault** に集約 (ADR-2604292100)

## 5. Implementation status

- ✅ `kawasakijun/living/` 全ファイル commit 済 (`c879587f`)
- ✅ `--dry-run` 動作確認 (9 actions planned, 既存 #16/#1281/#1282 をスキップ)
- ⏳ Sensor 実装 (gmail/calendar/animeka/MF/photos は stub)
- ⏳ Cron 設定 + Discord/Slack 通知
- ⏳ Magatama actor 統合 (XRPC 経由)
- ⏳ Completion 取り込み (issue close → Pregel state 反映)

## 6. References

- `kawasakijun/living/README.md` — 詳細アーキテクチャ
- `kawasakijun/living/manifest.yaml` — actor identity
- `kawasakijun/living/repos.yaml` — node ↔ repo routing
- `gftdcojp/ai-gftd-apps-gftdcojp/90-docs/adr/2605152100-etzhayyim-github-org-boundary.md`
  — etzhayyim (principal) / Gftd Japan (vendor) の boundary 先行例
- `gftdcojp/ai-gftd-apps-gftdcojp/90-docs/adr/2605111000-gftd-japan-family-office-conversion.md`
  — Family Office 化 (本 ADR と接続する CPM ノード)
