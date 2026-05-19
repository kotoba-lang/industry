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

## Phase 5.5 — Wise アカウント整理 (2026-05-19)

「個人アカウントで Wise が使えない」報告から Gmail を 1y 遡って棚卸し。

### 棚卸し結果: 3 アカウント発見

| # | アカウント | 種別 | 状態 | 判定 |
|---|---|---|---|---|
| 1 | `shivs.galleon.0@icloud.com` | 個人 | 2025-07〜08 に送金中「追加情報不足」で2回連続キャンセル (#1625689464 / #1646900862) → 2026-02-03 パスワードリセット要求 → 以降取引メール途絶 | ❌ 使えない（凍結気味） |
| 2 | `fmj7kgsfnq@privaterelay.appleid.com` (Apple Hide My Email) | Business | KYC 未完。最新 2026-05-11「Action needed for your Wise Business account」CTA: `https://wise.com/kyc-flows/business/` | ⚠️ 手続けば使える |
| 3 | `j*****n@rokes.exchange` (旧法人時代) | 個人 | Wise 側で「無効化済み」。本人もメール受信不可。Wise サポート (2026-02-05 丸田) が「本来のあなたの個人アカウント」と確定 | ❌ 使えない（無効化／メール喪失） |

### 経緯
- 過去に GHELIA 法人開設時、経理経由で `rokes.exchange` の**個人アカウント**が併設で作成されていた（本人記憶なし）
- そのため新規 Apple Relay 個人は「重複」扱い → 個人情報未入力で凍結
- Wise 規約「1人1アカウント」のため、サポートは旧アカウントへの統合を指示
- 2026-02-18 に身分証セルフィー提出済、その後 2026-05-11 に Business アカウント側のセットアップ依頼

### 次アクション
1. ✅ Gmail 下書き作成（draft id: `r-2002889752981031239`） — 2026-05-11 スレッドへの返信、身分証再利用確認 + 個人重複2件の閉鎖依頼 + サポート介入依頼
2. ⏳ ユーザー本人で通常 Chrome から Business KYC 完了（Claude Chrome は wise.com を金融セーフティで全パス遮断、computer use も同様）
3. ⏳ 個人 `shivs.galleon.0` のログイン状態確認
4. ⏳ 1Password の Wise エントリ棚卸し（Touch ID 必須のため対話シェル要）

### 教訓 (Pattern E 候補)

> **Pattern E: "Identity-duplication blocker"** — 法人 / 経理が代行手続きで作った個人アカウントの存在を本人が知らず、後年の新規作成が規約違反でブロックされる。

検出基準:
- 過去に法人開設・代行手続き経験あり
- 「1人1アカウント」規約のサービス（Wise / Revolut / Stripe / Square ほか）
- 新規登録が「重複検出」「KYC 未完」で停止

修復ルール:
1. サポートに本人情報（氏名・生年月日・電話・複数メアド）を渡して**過去アカウント特定**
2. メアド変更フロー（電話本人確認＋セルフィー）で**統合**
3. 重複側を**正式閉鎖**してから新規利用再開

→ ADR-0002 Pattern A-D に **Pattern E を追記候補**。本件の解決完了後（Business KYC 通過後）に正式 ADR 化判断。
