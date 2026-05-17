# kawasakijun/ — 河崎純真 活動・経路スタック

`/origins` (Ghost Hacker フィクション素材) には個人プロフィールは無いため、
このディレクトリで河崎純真個人の **profile / activities / roadmap** を構造化し、
`pregel_planner.py` で今後の経路を計算する。

## 構造

| ファイル | 役割 |
|---|---|
| `profile.jsonld` | 基本属性、ロール、価値関数 W(τ) の重み |
| `activities.jsonld` | これまでの活動を τ スケール (秒→世代→宇宙) でタグ付け |
| `roadmap.jsonld` | 今後の計画。各 τ レイヤーに目標とドライバを置く |
| `pregel_planner.py` | LangGraph Pregel グラフ。τ を super-step、活動領域を actor |

## 設計の元

`0.md` の τ スケール CMMI プロセス（Comprehend → Nexus → Generator → Projector）と、
W(τ) = α·I(τ) + β·R(τ) + γ·O(τ)、𝒲 = ∫ w(τ) W(τ) d ln τ を踏襲する。

- **I(τ)** 予測情報 (自己の未来予測可能性)
- **R(τ)** 関係持続 (人間関係グラフのジャッカード × 連結度)
- **O(τ)** 秩序 × 適応 (エントロピー × 行動多様性)

## Pregel モデル

- **vertex** = 活動領域 (physics / fiction / research / infra / health / relations / spirit)
- **edge** = 領域間の影響 (例: physics → fiction、研究 → infra)
- **super-step** = τ ∈ {day, week, month, year, decade, generation, cosmos}
- **message** = (impact_on_I, impact_on_R, impact_on_O, cost)
- **halt** = W(τ) が目標下限を満たし、改善余地が ε 未満

各 super-step で全 vertex が同時にメッセージを処理、自己状態を更新、
次 τ へ送信。LangGraph の `StateGraph` で実装。

## 使い方

```bash
pip install langgraph langchain-core
python kawasakijun/pregel_planner.py --tau month --horizon 12
```

出力: 各 τ の推奨アクション列、W(τ) 推移、ボトルネック vertex。

## 更新フロー

1. `activities.jsonld` に新規イベントを追記 (git コミットから自動補完可)
2. `roadmap.jsonld` の目標を Signal で更新
3. `pregel_planner.py` を再実行し、経路を再計算
