# Monthly Action Board — 直近 12 ヶ月 (2026-06 〜 2027-05)

## 重要な戦略転換 (Phase 2.2 — 実態反映)

実態に合わせて再構築:
- **COMMONS 宿泊業ノード 削除** (やらない)
- **Gftd 営業を law + anime 2 軸に分割** (`21_gftd_lawfirm_vertical`, `21b_gftd_anime_vertical`)
- **Etzhayyim は既存運用** (`22_etzhayyim_ops`; org/monorepo は 2026-05 から live)
- **JK 株式会社 = 改称済 private holding** (`23_jk_holding_governance` + `10_jk_holding_revenue`):
  医療 / 研究 / 資産管理 の 3 軸 only
- **Gftd Japan = vendor**, **Etzhayyim = principal** の境界明確化 (ADR 2605152100)

| ノード | 内容 | 状態 |
|---|---|---|
| `21_gftd_lawfirm_vertical` | LegalTech / e-discovery / 訴訟支援 | 着手 |
| `21b_gftd_anime_vertical` | Ghost Hacker IP + 制作支援 + 配信 | 着手 |
| `22_etzhayyim_ops` | 9 領域 (blockchain/baien/bpmn/lexicon/pregel/atproto/ameno/open-data/governance) | **既存運用中** |
| `23_jk_holding_governance` | 医療 / 研究 / 資産管理 ガバナンス | 整備中 |
| `25_magatama_sdk` | Magatama actor framework + Pregel SDK 公開 | 既存リポ |
| `24_cyber_treaty` | US×JP Cyber Treaty | 提案段階 |

→ **理論最短 19.50y、S2 シナリオで 13.72y、S3 で 10.0y**。
**再婚 ES=2y / 子供 EF=10y** (CP から外れている)。

## 新クリティカル経路

```
Ghost Hacker 残巻シナリオ (2.0y)
  → Gftd アニメ IP バーティカル (2.0y)
  → Gftd 月次黒字化 (1.5y)
  → 借入返済加速 (7.0y)
  → Gftd IPO or 財団化 (7.0y)
```

各月のアクション形式:
- **CP**: クリティカル経路ノード (優先度最高)
- **REV**: 売上加速 (Critical だが並走可能)
- **PAR**: スラックあり並走

各月のアクションは以下のフォーマット:
- **CP**: クリティカル経路ノード (優先度最高、遅延禁止)
- **SLACK**: スラックあり、CP の合間にこなす
- **SENSE**: 自動化されたモニタリング (KPI センサ)

## 2026-06 (Ghost Hacker + Gftd law/anime + Etzhayyim 運用)

| Item | Owner | Domain | Status check |
|---|---|---|---|
| **CP** Ghost Hacker 第3巻シナリオ ドラフト | 河崎 | fiction | 章立て完成 |
| **REV** Gftd 法律事務所バーティカル: AMT/ZeLo 経由で LegalTech 案件提案 | 河崎+k.morioka | infra | 提案 2 件 |
| **REV** Gftd アニメ IP バーティカル: Ghost Hacker 制作スタジオ打診 | 河崎 | infra | スタジオ候補 3 件 |
| **REV** Etzhayyim 9 領域の運用継続 (既存 monorepo) | 河崎 | spirit | git activity |
| **REV** Cyber Treaty 提案書 → 経産省 SSS 担当窓口 | 河崎 | infra | 1 次窓口アポ |
| **PAR** JK株式会社 ガバナンス整理 (医療/研究/資産管理 3 軸の決算分離) | 河崎+加田 | social | フォーマット策定 |
| **PAR** アイシステム送金履歴 取得依頼 → TOTAL 水鳥 | 河崎 | social | 6/10 依頼メール |
| **PAR** Lean4 mathlib v4.25 互換性検証 | 河崎 | physics | `lake build` 緑 |
| **PAR** Magatama Pregel SDK README + Quickstart | 河崎 | spirit | etzhayyim/root commit |
| Paidy 自動引き落とし設定 | 山田 | infra | 残債務ゼロ |
| 渋谷こころのクリニック 月1 | 河崎 | health | 通院記録 |
| のどか 受験塾の中間面談 | 河崎 | relations | 親としての時間確保 |

## 2026-07 (Gftd 受注 + Ghost Hacker 作画)

| Item | Owner | Domain | Status check |
|---|---|---|---|
| **CP** Ghost Hacker 第3巻 作画パイプライン投入 (1 シーン完成) | 河崎 | fiction | epub 出力 |
| **REV** Gftd 法律事務所案件 クローズ +1 (LegalTech / e-discovery) | k.morioka | infra | 受注金額 |
| **REV** Gftd アニメ IP: 制作委員会組成 検討 | 河崎 | infra | パートナー 2 件 |
| **REV** Etzhayyim baien / ameno / atproto 同時進捗 | 河崎 | spirit | 90-docs commits |
| **REV** Cyber Treaty 経産省ヒアリング | 河崎 | infra | 議事録 |
| **PAR** 2021年 預かり資金移動 全件突合 (MF×220415) | 河崎+加田 | social | スプレッドシート完成 |
| **PAR** ADR-0003 ドラフト (5層整合性証明) | 河崎 | physics | git commit |
| 夏季の海外短期出張 (DEF CON 34 / Etzhayyim 視察 / 出会いの場) | 河崎 | relations | 旅程確定 |
| 夏季の海外短期出張 (DEF CON 34 / Etzhayyim 拠点視察 / 出会いの場) | 河崎 | relations | 旅程確定 |

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
| `gftd.revenue_monthly` | MoneyForward API / 売上集計 | ≥ ¥12.8M (年末計画値) |
| `gftd.sales_pipeline` | Salesforce / SFDC 件数 | ≥ 8 active |
| `etzhayyim.formed` | オランダ商工会議所登録 | exists |
| `jk_wellness.customers` | 顧客 LTV / 月次顧客数 | ≥ 200 月 |
| `cyber_treaty.contact` | 経産省 / 米大使館 メール往復 | ≥ 1/month |
| `LingLing.brief_done` | Gmail から "準備書面" + sent count | ≥ 1/month |
| `accounting_proof.signed` | Drive で 'integrity_proof' タグ | exists |
| `gftd.cashflow_monthly` | MoneyForward API | >= ¥4.7M (=月販管費) |
| `paidy.overdue` | Gmail "支払期日を過ぎ" count | = 0 |
| `health.clinic_visit` | Calendar "渋谷こころ" | 1/month |
| `nodoka.time_with` | Calendar "のどか" + camera time | ≥ 8h/week |
| `physics.commits` | git log kawasakijun + 2604-linde | ≥ 4/month |
| `meeting_partners.events` | Calendar "出会い系/合コン/同窓会/海外出張" | ≥ 2/month |

## 依存外しの判断ポイント (acceleration.md 参照)

すでに以下の過剰エッジは Phase 2.1 で除去済み:
- ~~14_loan → 15_remarriage~~ (再婚は経済を待たない)
- ~~12_legal → 13_meeting~~ (出会いは訴訟を待たない)
- ~~12_legal → 11_health~~ (健康は訴訟を待たない)
- ~~11_health → 15_remarriage~~ (再婚は完璧な健康を要求しない)
- ~~06_lingling → 10_commons_revenue~~ (宿泊業は並走可能)

残りの判断ポイント:
- **2026 末**: Etzhayyim / Cyber Treaty の立ち上がり次第で借入返済加速の est_years を再評価
- **2027 中**: IPO を借入完済前に並走するか (S3 シナリオ)
- **2027 末**: 子供を再婚から切り離すか (養子経路の確度確認)

## S3 シナリオ (Aggressive: 10 年圏)

`acceleration.md` の S3 を採用すると **10.00 年** で全 24 ノード完了:

```
売上 4 軸を 0.6 倍速で達成 (1.5y → 0.9y)
+ 借入返済 7y → 3.5y
+ IPO を借入完済前に並走 (7y → 3.5y, parallel)
+ JK Wellness と COMMONS 宿泊業を並走
```

→ 河崎氏 44 歳で全達成、子供達成 39 歳 (現実的)
