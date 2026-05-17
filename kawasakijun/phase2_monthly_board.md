# Monthly Action Board — 直近 12 ヶ月 (2026-06 〜 2027-05)

CPM 分析 (`critical_path.md`) より、最初の 24 ヶ月で攻めるべきは
**クリティカル経路 #1–#3** (アイシステム → 預かり資金突合 → 経理整合性証明)。
これら 3 件は累計 1.25 年 (=15 ヶ月) — 直近 12 ヶ月で **完了確実**。

各月のアクションは以下のフォーマット:
- **CP**: クリティカル経路ノード (優先度最高、遅延禁止)
- **SLACK**: スラックあり、CP の合間にこなす
- **SENSE**: 自動化されたモニタリング (KPI センサ)

## 2026-06 (CP #1 着手)

| Item | Owner | Domain | Status check |
|---|---|---|---|
| **CP** アイシステム送金履歴 取得依頼 → TOTAL 水鳥 | 河崎 | social | 6/10 までに依頼メール送信 |
| **CP** Lean4 mathlib v4.25 互換性検証 | 河崎 | physics | `lake build` 緑 |
| Paidy 自動引き落とし設定 | 山田 | infra | 6/末 残債務ゼロ |
| 渋谷こころのクリニック 月1 | 河崎 | health | 通院記録 |
| Ghost Hacker 第3巻シナリオ着手 | 河崎 | fiction | 章立てドラフト |
| のどか 受験塾の中間面談 | 河崎 | relations | 親としての時間確保 |

## 2026-07 (CP #2 着手)

| Item | Owner | Domain | Status check |
|---|---|---|---|
| **CP** 2021年 預かり資金移動 全件突合 (MF×220415) | 河崎+加田 | social | スプレッドシート完成 |
| Gftd Japan 6 月度クロージング → 営業案件 +3 件 | k.morioka | infra | パイプライン金額 |
| ADR-0003 ドラフト (5層整合性証明) | 河崎 | physics | git commit |
| Ghost Hacker 第3巻 作画パイプラインに投入 | 河崎 | fiction | 1 シーン完成 |
| 夏季の海外短期出張検討 (出会いの場) | 河崎 | relations | 候補地確定 |

## 2026-08 (CP #2 完了)

| Item | Owner | Domain | Status check |
|---|---|---|---|
| **CP** 2021 全件突合 → クロスチェック → ZeLo 共有 | 河崎+ZeLo | social | フォルダ最新化 |
| **CP** 経理整合性証明書 ドラフト (TOTAL + ZeLo 連名) | 水鳥+神尾 | social | 草案完了 |
| 海外滞在 (DEF CON 等の併用検討) | 河崎 | infra | DEF CON 34? |
| Ghost Hacker 第3巻 完成度 50% | 河崎 | fiction | |

## 2026-09 (CP #3 完了)

| Item | Owner | Domain | Status check |
|---|---|---|---|
| **CP** 経理整合性証明書 確定版 | 水鳥+神尾 | social | サイン入り |
| **CP** LingLing 準備書面 (証明書を引用) | ZeLo | social | 期日 1 週前提出 |
| Gftd 上半期決算 → 黒字進捗確認 | k.morioka | infra | P/L 提出 |
| ADR-0003 完了 + 公開 PR | 河崎 | physics | merge |

## 2026-10 (CP #4 着手)

| Item | Owner | Domain | Status check |
|---|---|---|---|
| **CP** LingLing 準備書面 4 期日対応 | 河崎+ZeLo+AMT | social | 期日メモ |
| Gftd Japan 内部統制 (J-SOX 簡易版) 初期化 | 河崎 | infra | 監査法人スコーピング |
| Ghost Hacker 第3巻完成 → KDP 投入 | 河崎 | fiction | 出版済 |
| Wheeler-DeWitt 検証論文 アウトライン | 河崎 | physics | 査読者候補リスト |

## 2026-11

| Item | Owner | Domain | Status check |
|---|---|---|---|
| **CP** LingLing 期日対応 (2 期) | ZeLo | social | 議事録 |
| のどか 受験本番期 (1月入試) サポート | 河崎 | relations | 親として最優先 |
| Gftd Q3 営業案件 +5 件 | k.morioka | infra | パイプライン金額 |

## 2026-12

| Item | Owner | Domain | Status check |
|---|---|---|---|
| **CP** LingLing 中間総括 | ZeLo+AMT | social | 戦略再評価 |
| 年末税務 (TOTAL) | 水鳥 | social | 申告完了 |
| Ghost Hacker 第4巻シナリオ着手 | 河崎 | fiction | 章立て |
| Hilton Odawara 年末リトリート | 河崎+のどか | health | 1/3-1/5 |

## 2027-01〜2027-05 (LingLing 決着期)

| Month | Critical milestone |
|---|---|
| 2027-01 | LingLing 結審 (推定) / 評価書面 |
| 2027-02 | 結論ドラフト / 控訴判断 |
| 2027-03 | 結審 — 弁護団リソース解放 (CP #5 へ) |
| 2027-04 | COMMONS 宿泊業 営業 CF 着手 (CP #5) |
| 2027-05 | 再婚に向けた出会い設計 (CP #6 着手) |

## モニタリング指標 (Pregel state に流す)

| KPI | 計測 | 閾値 (青信号) |
|---|---|---|
| `LingLing.brief_done` | Gmail から "準備書面" + sent count | ≥ 1/month |
| `accounting_proof.signed` | Drive で 'integrity_proof' タグ | exists |
| `gftd.cashflow_monthly` | MoneyForward API | >= ¥4.7M (=月販管費) |
| `paidy.overdue` | Gmail "支払期日を過ぎ" count | = 0 |
| `health.clinic_visit` | Calendar "渋谷こころ" | 1/month |
| `nodoka.time_with` | Calendar "のどか" + camera time | ≥ 8h/week |
| `physics.commits` | git log kawasakijun + 2604-linde | ≥ 4/month |

## 依存外しの判断ポイント (acceleration.md 参照)

- **2026 末**: LingLing 状況次第で `LingLing → COMMONS revenue` エッジを 切る判断 (S1 加速)
- **2027 中**: 再婚を経済から切り離すか判断 (S1/S2 分岐点)
- **2027 末**: 子供を再婚から切り離すか判断 (養子検討)
