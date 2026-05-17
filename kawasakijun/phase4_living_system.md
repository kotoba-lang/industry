# Phase 4.D — 生命維持系 自動化 (B + C 統合)

「animeka 制作 (B)」「JK 1.34億 回収 (C)」「健康/関係性/家族の KPI 自動化」を、
**1 つの自律 Pregel ループ** に統合する。

## アーキテクチャ

```
                ┌─────────────────────────────────────┐
                │       kawasakijun Living System      │
                └─────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
   ┌─────────┐         ┌──────────┐          ┌──────────┐
   │ Sensors │         │  Pregel  │          │ Actions  │
   │ (input) │ ─────→  │  state   │  ─────→  │ (output) │
   └─────────┘         └──────────┘          └──────────┘
        │                     │                     │
        ├ Gmail               ├ vertices            ├ Calendar event create
        ├ Calendar            │  • physics          ├ Email draft
        ├ Photos              │  • fiction          ├ git commit (auto-PR)
        ├ git log             │  • social           ├ Discord/Slack ping
        ├ Drive               │  • health           ├ Notion / Obsidian sync
        ├ animeka API         │  • relations        ├ animeka workflow trigger
        ├ MoneyForward        │  • family           ├ pipeline /xrpc call
        ├ Calendar (のどか)    │  • spirit           ├ Magatama actor dispatch
        ├ banking APIs        │  • assets
        └ KAMI engine         │  • etzhayyim
                              └ edges: 影響伝播
```

## モジュール構成

### 1. animeka 制作スキーム (B 吸収)

`28_crypto_victim_lawfirm` の inversion: 河崎の Ghost Hacker IP を
**animeka pipeline に自動投入する組立ライン**。

```python
# kawasakijun/living/animeka_dispatcher.py
def on_new_scenario_commit(commit):
    """git push hook: scene.jsonld が新規/更新されたら animeka 投入"""
    if commit.touches("260208-spirit-in-physics/origins/episodes-*/scene.jsonld"):
        scene = parse_jsonld(commit.files)
        prompt = scene_to_danbooru_prompt(scene)
        # RunPod Serverless 呼び出し
        result = runpod_call(
            endpoint="v9si0sflsm0gh0",
            workflow=load_workflow("comfyui_panel.json"),
            prompt=prompt,
            network_volume="43k3uq9ldn",
        )
        pds_upload(scene.id, result.blob, blobCid=result.cid)
        return {"status": "panel_generated", "scene_id": scene.id}
```

### 2. JK 1.34 億 回収計画 (C 吸収)

**JK株式会社 (旧 COMMONS) → 河崎個人** への債務 ¥133,876,442。
回収 = 河崎個人がキャッシュを受け取るのではなく、**JK の運営する資産・収益で実質回収する**。

```
Strategy 1: 医療領域の収益化
  - 渋谷こころのクリニック (LaVie) との研究契約
  - JK の研究予算 → 河崎の医療データ研究費

Strategy 2: 研究領域 (Spirit-in-Physics) の収益化
  - 高 IQ GWAS の特許化 → 製薬企業へのライセンス
  - Wheeler-DeWitt の Lean4 形式化 → 学術出版収益

Strategy 3: 資産管理の運用益
  - eumo / esse-sense 株主優待 → JK 経由でリターン
  - オランダ Liendenhof 275 / メキシコ土地 の家賃収入
  - 暗号資産運用益

Strategy 4: 河崎個人への返済
  - Family Office 化後、JK → 河崎 への配当として段階的に返済
  - 法人税最適化 + 相続税対策と組み合わせ
```

**自動化ポイント**:
- JK 月次決算 → 余剰キャッシュを自動的に河崎個人口座へ振替
- MoneyForward API 経由で残債務 ¥133.87M を毎月リアルタイム更新
- 4-5 年で完済見込み (年 ¥25-30M ペース)

### 3. 健康・関係性・家族 KPI ループ

```python
# kawasakijun/living/kpi_collector.py (phase2_kpi_sensors.py の進化版)

class KPIPipeline:
    def collect(self) -> dict:
        return {
            "physics.commits":   gh_count_commits(days=30),
            "ghosthacker.panels": animeka_count_panels(days=7),
            "gftd.revenue":      moneyforward.monthly_revenue(),
            "gftd.lawfirm_cases": lawfirm_actor.count_active_cases(),
            "crypto_victim.cases": crypto_victim_actor.count(),
            "health.clinic":     calendar_count("渋谷こころ", days=30),
            "health.sleep":      apple_health.avg_sleep(days=7),
            "nodoka.time":       calendar_count("のどか|nodoka", days=7),
            "meeting_partners":  calendar_count("会食|デート|海外出張", days=30),
            "etzhayyim.commits": gh_commits("etzhayyim/root", days=7),
            "magatama.releases": gh_releases("etzhayyim/root"),
        }

    def evaluate(self, state: dict) -> list[Action]:
        actions = []
        if state["ghosthacker.panels"] < 50:  # 週次目標
            actions.append(BlockCalendar("Ghost Hacker 作画", duration="120min"))
        if state["nodoka.time"] < 8:
            actions.append(BlockCalendar("のどかとの時間", duration="2h"))
        if state["meeting_partners"] < 2:
            actions.append(SuggestEvent("出会いの場 (会食 / 海外出張)"))
        if state["health.sleep"] < 7:
            actions.append(NotifyHealth("睡眠時間が不足")) 
        return actions
```

### 4. 自動アクション dispatch

```python
# kawasakijun/living/dispatcher.py

class ActionDispatcher:
    def dispatch(self, actions: list[Action]):
        for a in actions:
            if isinstance(a, BlockCalendar):
                gcal_create_event(a.title, a.duration)
            elif isinstance(a, SuggestEvent):
                gmail_draft(to=self.user_email, subject=a.title, body=a.suggestion)
            elif isinstance(a, AutoCommit):
                git_commit_and_push(a.repo, a.message, a.files)
            elif isinstance(a, MagatamaCall):
                xrpc_call(a.actor, a.nsid, a.payload)
```

## 運用フロー (1 日サイクル)

```
06:00  cron: KPI collection → state 更新
06:05  Pregel super-step → action list 生成
06:10  Calendar block 自動作成
06:15  Gmail draft 自動作成 (個別アウトリーチ)
06:20  Discord 通知 (河崎個人 + 中村 + 山田)
       ↓
       河崎が起床、Calendar / Gmail を確認
       ↓
       1 日の活動が KPI として収集される
       ↓
22:00  日次振り返り (Notion 自動生成 + Discord post)
```

## 実装スケジュール (3 ヶ月)

| Month | Module | 完成度 |
|---|---|---|
| 2026-06 | KPIPipeline.collect() | 100% (Gmail/Calendar/git/Drive) |
| 2026-06 | KPIPipeline.evaluate() | 50% (基本ルール) |
| 2026-07 | animeka_dispatcher | git hook + RunPod 呼出 |
| 2026-07 | jk_loan_tracker | MoneyForward API 連携 |
| 2026-08 | ActionDispatcher | Gmail / Calendar / Discord |
| 2026-08 | Daily review bot | Notion + Discord 統合 |

## Magatama Actor 統合

Etzhayyim の `20-actors/magatama/` の Pregel SDK を kawasakijun の
living system が呼び出す形式に。
**河崎個人の人生 = magatama 上の 1 actor**:

```yaml
# kawasakijun/living/manifest.yaml
actor:
  did: did:web:kawasakijun.jp.luxury
  type: T2 TS Native
  cluster: etzhayyim
  vertex_state: kawasakijun/profile.jsonld
  pregel_loop: kawasakijun/reverse_topo_pregel.py
  kpi_collectors:
    - gmail
    - calendar
    - git
    - drive
    - photos
    - moneyforward
    - apple_health
  action_dispatchers:
    - gcal
    - gmail
    - github
    - discord
    - runpod
  super_step_interval: 24h
```

## 受益

| 指標 | 手動 | 自動化後 |
|---|---|---|
| KPI 収集時間 | 30 min/日 | 0 min |
| Action 決定時間 | 60 min/週 | 0 min |
| 漏れ防止 | 主観 | センサ依存 |
| 1.34億 進捗可視化 | 月次 | リアルタイム |
| Ghost Hacker 進捗 | 主観 | panel/巻自動カウント |
| 娘との時間追跡 | 写真ベース手動 | Calendar + 顔認識自動 |

## 統合効果

B + C + 健康/関係/家族の自動化により:

- 河崎は **創作 (Ghost Hacker) と意思決定** にフォーカス可能
- 借入返済 (`14_loan_acceleration`) の est_years を 2y → 1y に短縮可能
- 関係性 (再婚 / 子供) の進捗が KPI で見える → ペース調整可能

CPM 上の効果見込み: 7.00y → **5.50y**
