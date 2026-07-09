# Phase 3.A — 内部統制 ドシエ (3y → 1.5y 圧縮計画)

CPM の新ボトルネック (`17_internal_control`, 3y) を 1.5y に圧縮する具体計画。

## 圧縮の根拠 (既存アセット)

Gftd Japan には既に「内部統制の素」が大量にある:

| Asset | Status | 内部統制への寄与 |
|---|---|---|
| **ISO/IEC 27001:2022** | 登録済 | 情報セキュリティ統制 (Annex A 93 controls) |
| **ISO 9001:2015** | 登録済 | プロセス管理・継続改善 |
| **Pマーク 17004887** | 登録済 | 個人情報統制 (JIS Q 15001) |
| **経産省 SSS 024-0013-20** | 登録済 (国内 159 社のみ) | サイバー優良企業認定 |
| **1Password Gftd Japan vault** | live (ADR-2604292100) | 認証・秘密情報統制 |
| **G Workspace OAuth broker** | live (ADR-2605131700) | アクセス管理統制 |
| **9-actor 法務クラスタ** | 5 actor live | 規程・コンプライアンス基盤 |
| **W Protocol + Signal Protocol E2E** | live | データ統制・暗号化 |
| **MoneyForward 会計** | live (TOTAL 水鳥 監修) | 財務統制 |
| **Magatama runtime + Pregel** | live | 業務プロセス統制 (BPMN-as-actor) |

**結論**: J-SOX 簡易版を新規構築するのではなく、既存統制を **fmagatama actor 上にマッピング** すれば 1.5y で完成可能。

## アプローチ: ISMS-as-Source-of-Truth + Family Office 専用統制

### Phase 1 (0-3 ヶ月): ISMS Annex A の Family Office 用 reframing

ISO 27001 の Annex A (93 controls) のうち、Family Office 化 (定款新 6 項目) に必要なものを抽出:

| Annex A 章 | Family Office 用関連性 |
|---|---|
| A.5 Organizational | 役員任期 10y 化との整合 (ADR-2605111000 ②) |
| A.6 People | 中村 COO + k.bakshi CLO 役割再定義 |
| A.7 Physical | 1Password vault に集約 |
| A.8 Technological | W Protocol + atproto |

### Phase 2 (3-9 ヶ月): 監査法人 scoping

- **方針**: 大手は不要。**中小監査法人で十分** (Family Office = 非上場 + 自己勘定 only)
- **候補**: TOTAL 水鳥 が紹介可能 / 佐藤公認会計士事務所 福本 (zeimu19@yashin.co.jp)
- **scope**:
  - 財務諸表監査 (年次)
  - リスクレジスタ (資産管理リスク + 承継リスク)
  - 内部監査憲章

### Phase 3 (9-18 ヶ月): 内部監査の制度化

- 中村明子 COO 主導
- 監査計画 (年次) + 是正措置 (四半期)
- Magatama 上で内部監査 actor を実装 (audit.gftd.ai 新規)

## 並走可能項目

- **Family Office 化** (`18_family_office_conversion`, 0.25y) — 株主総会 + 登記
- **lawfirm 9-actor 拡大** (`21_gftd_lawfirm_vertical`, 1y) — 法務統制基盤
- **反訴戦略** (`26_counter_litigation`, 1y) — リスクレジスタの主要項目

## 受益

| Phase 完了後 | 効果 |
|---|---|
| 0-3 ヶ月 | ISMS-Family-Office マッピング表 → 監査法人へ提示可 |
| 3-9 ヶ月 | 監査法人決定 + 第 1 回監査契約 |
| 9-18 ヶ月 | 第 1 回監査完了 + 内部監査制度 live → **内部統制ノード 完了** |

## ノード est_years 更新案

```python
"17_internal_control": Goal(
    "17_internal_control",
    "内部統制 (ISMS 既登録活用; Family Office 用 reframing)",
    "year", "infra", 1.5, 0.20,  # 3.0y → 1.5y
)
```

CPM 上の効果: 7.50y → **6.00y** (中間ボトルネックが Family Office 並走可能になる)
