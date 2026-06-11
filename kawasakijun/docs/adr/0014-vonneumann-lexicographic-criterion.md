# ADR-0014: 意思決定基準 — von Neumann maximin × 辞書式優先順位 (wellbecoming → 孫・子供)

- Status: Accepted (2026-06-11, 本人宣言に基づく)
- 関連: ADR-0010 (life graph), ADR-0012 (dyad/worst-case), ADR-0013 (engi), goals.edn, queries/attention.edn

## 本人宣言 (criterion の SSoT)

> 優先順位は wellbecoming (自身の今ここの感覚として良い感覚か、wellness 8次元を高い状態で
> 維持できているか)、孫、子供 (のどか)。

## 決定: 辞書式 maximin

von Neumann の maximin (最悪値の最大化) を、単一スコアではなく**辞書式 (lexicographic)** に適用する:

```
Tier 0: wellbecoming の床      max_a min_s  felt-sense / wellness-8 floor
Tier 1: 孫・子供 (のどか) の床   Tier 0 同値の中で  max_a min_s  nodoka-guardrail
Tier 2: W(τ) = αI + βR + γO    Tier 0/1 同値の中で従来の最適化
```

要点:
1. **平均ではなく床を見る。** felt sense は「直近7日の最小値」、wellness 8次元は「最も低い次元の値」
   で評価する。良い週の平均で悪い日を覆い隠さない — これが maximin の本義。
2. **上位 tier の床を削る行動は、下位 tier でどれだけ得でも選ばない。**
   例: 収益や訴訟品質のための徹夜は Tier 2 の利得で Tier 0 の床を割るので不可。
3. **下位 tier は上位 tier の手段になれる** (収益→養育の安定) が、逆は不可。
4. Tier 0 が Tier 1 より先なのは利己ではなく構造 (酸素マスク原則): 単一障害点 (ADR-0012) で
   ある本人の床が割れると、のどかの安全基地も同時に崩れる。

## wellness 8次元 → 既存資産へのマッピング

| 次元 | 現在の支え (goals/engi/dyads) | センサー (kpi/metric) |
|---|---|---|
| physical 身体 | 11_health_steady, Dr.トレーニング(:keep), 睡眠 | :wellness.physical (+apple_health.sleep) |
| emotional 感情 | felt sense 日次, 河合俊雄的資源, 夢分析の系譜 | **:wellbecoming.felt-sense (日次 1-5)** |
| social 社会 | のどか週次枠, 美加恵チャネル, 櫻井(ヨット), relationship/decay | :wellness.social (+nodoka.time) |
| intellectual 知性 | physics/fiction (07/09), kotoba, moex | :wellness.intellectual |
| spiritual 霊性 | etzhayyim (22), いけばな(休眠), spirit-in-physics | :wellness.spiritual |
| occupational 職業 | gftd (08/21/21b), 28_crypto_victim | :wellness.occupational |
| financial 経済 | 29_subscription_audit, 14_loan, engi 削減 | :wellness.financial |
| environmental 環境 | 神宮外苑居住 (のどか生活圏), 下田(海) | :wellness.environmental |

## 実装

1. `:goal/tier` (0/1/2; 省略時 2) — goals.edn に付与:
   - **Tier 0**: `00_wellbecoming_self` (新設 guardrail), `11_health_steady`
   - **Tier 1**: `00_nodoka_wellbeing`, `15_remarriage`, `16_more_children` (子・孫世代の形成)
   - Tier 2: その他すべて (訴訟・事業・研究 — 本人評価 hyp/lingling-bounded-loss と整合)
2. **attention/queue は (tier, due) で並ぶ** — 期限が同じなら上位 tier が先。
3. felt sense の記録: `personal/facts/kpi.jsonl` に
   `{"metric": "wellbecoming.felt-sense", "value": 4, "at": "2026-06-11T22:00:00+09:00"}`
   を日次1行 (手動 or living system)。loader が kpi datoms 化し、
   `:guardrail/wellbecoming-floor` ビューが直近7日の min を返す。
4. 月次: wellness 8次元の自己評価 (各 1-5) を同じ kpi.jsonl へ。最低次元 = その月の床。
5. dyad の worst-case・engi の判定も本基準で読み直す (例: Uber One :reduce の根拠は
   financial でなく physical/emotional の床)。

## 帰結

- 良: 「何を先にやるか」が好み・気分・声の大きい締切ではなく宣言された基準で決まる。
  床の計測 (felt sense 1行/日) というほぼゼロコストの習慣が全システムの最上位入力になる。
- 悪: felt sense は自己申告で操作可能 (虚偽申告のインセンティブはないが、記録忘れで床が
  見えなくなる)。リマインダ自動化 (living system) が前提。
