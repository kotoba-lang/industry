# Phase 5 — 契約・サブスク・アカウント整理計画

Gmail から ingest した既存サービスを `subscriptions_state.jsonld` に整理。
本ドキュメントは **解約・整理の実行計画**。

## 集計サマリ (2026-05-18)

| Verdict | 件数 | 内訳 |
|---|---:|---|
| KEEP | 8 | Anthropic Max / RunPod / Cloudflare / GitHub / Calendly / Servcorp / Hugging Face / 株主優待・無料 |
| **REVIEW** | **14** | **要本人判断 (解約候補多数)** |
| CANCEL | 0 | (REVIEW から確定後ここに移動) |
| EXPIRED | 5 | Amazon Prime / DeStor / HPL / Anifusion / Google Workspace / American Sailing |

**推定月額支出**: 約 **¥319,000 / 月** (= 約 ¥3.8M / 年)

## 解約優先順位 (高 → 低)

| # | サービス | 推定月額 | 理由 |
|---|---|---:|---|
| 1 | **Anthropic Pro** | ¥3,000 | Anthropic Max ($200/月) と重複 |
| 2 | **ChatGPT Plus** (mikaeru Apple) | ¥3,000 | Anthropic Max で代替可 |
| 3 | **One IBC UK** (JunKawasaki Limited) | 年 ~$500 | UK 法人継続意義?Family Office で集約? |
| 4 | **Vultr** (compute) | ~¥161,000 | 用途確認 — 不要なら大幅削減 |
| 5 | **Squarespace domains** 棚卸し | ¥1,480/件 | commons.earth など使ってないドメイン |
| 6 | **NordAccount** | ?? | 3 製品のうち 1 つに統合 |
| 7 | **Clerk** | ?? | atproto/DID 移行で不要化の可能性 |
| 8 | **HafH** | ?? | 月 1 泊未満なら CANCEL |
| 9 | **Hume AI Creator** | ?? | 用途未確認 |
| 10 | **NotaHotel NAC** | — | buyback 進捗確認 |
| 11 | **Rentio** | ?? | 実利用要否 |
| 12 | **ピッコマ** | ¥980/件 | 都度判断 |

## 即実行可能な整理 (Week 1)

```
Day 1:
  ☐ Anthropic Pro 解約 (https://console.anthropic.com → Subscription)
  ☐ ChatGPT Plus 解約 (Apple Account mikaeru → サブスク管理)
  ☐ ピッコマ 解約 (Apple Subscription)

Day 2:
  ☐ Squarespace に login → ドメイン棚卸し
    → commons.earth は不要なら release
    → 他に不使用ドメインがないか確認
  ☐ NordAccount 確認 (どの製品か特定 → 必要 1 つに集約)

Day 3:
  ☐ HafH 利用履歴確認 → ≦ 月1なら解約
  ☐ Hume AI dashboard → usage 確認
  ☐ Anifusion / American Sailing / Amazon Prime → EXPIRED 確認

Day 4:
  ☐ Vultr console → instance リスト
    → Gftd 用 / 個人用 を分離
    → 不要 instance 停止
  ☐ Clerk dashboard → MAU 確認、低活用なら廃止

Day 5:
  ☐ One IBC UK → 河崎個人で renewal 要否判断
    (Family Office 化との整合性で k.bakshi CLO に相談)
  ☐ NotaHotel NAC buyback 進捗確認 (support@notahotel.com)
  ☐ Rentio 利用履歴確認
```

## 中期 (Week 2-4)

```
Week 2:
  ☐ Servcorp 契約条件レビュー (JK Family Office 化と整合)
  ☐ Google Workspace 2026-05-03 cancellation 確認
    → junkawasaki.com / jk.luxury / gftd.ai のメール影響有無
  ☐ OpenRouter 支出 audit (週 5 件超の receipt 確認)

Week 3:
  ☐ クレジットカード明細 突合
    → Gmail に無い隠れサブスク発見
    → American Express / Yahoo / ダイナース / 楽天カード
  ☐ Apple Subscription 全件確認 (mikaeru / jun784)

Week 4:
  ☐ 解約結果まとめ → subscriptions_state.jsonld 更新
  ☐ 月額支出ベースライン再計測
  ☐ Living System の KPI に追加: subscriptions.monthly_spend
```

## Living System への組み込み

`reverse_topo_pregel.py` に新ノード追加:

```python
"29_subscription_audit": Goal(
    "29_subscription_audit",
    "サブスク・契約・アカウントの整理 (Phase 5)",
    "month", "infra", 0.25, 0.10,
),
```

- 上流: なし (即開始可能)
- 下流: なし (独立タスク)
- Slack: 大 (CP 外)
- Living System で月次レビュー: `subscriptions_state.jsonld` を毎月 re-ingest

### KPI Sensor 追加

```python
# living/collectors/subscriptions.py (new)
def collect() -> dict:
    """Gmail から billing/receipt メールをスキャンして月次支出を計上"""
    return {
        "subscriptions.monthly_spend_jpy": ...,
        "subscriptions.new_signups_last_30d": ...,
        "subscriptions.cancel_in_progress": ...,
    }
```

### Action 追加

```python
# 解約推奨があれば自動的に LogNote
if state["subscriptions.review_count"] >= 14:
    actions.append(LogNote(
        target="local",
        payload={"text": "Phase 5 subscription audit 進行中: 14件 REVIEW"},
    ))
```

## 監視指標

| KPI | 計測 | 閾値 |
|---|---|---|
| 月額支出 (JPY) | Gmail billing 集計 | 月次 -10% を目標 |
| REVIEW 件数 | `subscriptions_state.jsonld` | 4 週で 14 → 0 |
| CANCEL 完了率 | 解約成功 / 計画数 | 90%+ |
| 隠れサブスク発見 | クレカ突合 | 月 1 件まで |

## 期待効果

| 項目 | 試算 |
|---|---:|
| 月額削減 (低めの見積もり) | ~¥50,000 |
| 月額削減 (Vultr 整理込) | ~¥100,000 |
| 年間削減 | ~¥600k–¥1.2M |
| 認知負荷削減 | 高 (請求メール削減) |

## DAG 反映

新ノード `29_subscription_audit` 追加で、Living System が月次でこれを track。
完了後の re-audit を 6 ヶ月後に自動 schedule。

## 不要アカウント = 不要 attack surface

セキュリティ観点:
- 使ってないアカウントが侵害されるリスク
- credential reuse の被害が広がる
- 不要なメール · 通知の noise が増える

→ 整理は **セキュリティ衛生 (hygiene)** でもある。
