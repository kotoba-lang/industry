# ADR-0002: Pregel DAG における過剰モデリング検出と除去パターン

- Status: Accepted
- Date: 2026-05-18
- Deciders: Jun Kawasaki
- Predecessor: ADR-0001 (com-junkawasaki top-level orchestrator)
- Related: `kawasakijun/phase2_critical_path.py`, `kawasakijun/phase2_acceleration.py`

## 1. Context

`reverse_topo_pregel.py` の DAG を Phase 2.0 (初期) → Phase 4.G (最新) で
**5 世代** にわたり改訂した結果、ベースライン期間が **19.25y → 5.75y** に
短縮された (-13.50y, -70%)。この劇的な短縮は **CPM + acceleration 分析を
通じた過剰モデリングの発見・除去** によるもの。

### 1.1 全 Phase の数値

| Phase | Baseline | 主要除去 / 追加 |
|---|---:|---|
| 2.0 | 19.25y | 初期 DAG (過剰ブロッカー多数) |
| 2.1 | 17.50y | (14→15), (12→13), (12→11), (11→15), (06→10) エッジ除去 |
| 2.2 | 19.50y | 実態反映 (Etzhayyim 既存運用、Gftd vendor 性質) |
| 2.3 | 12.00y | IPO ノード削除 → Family Office 化 (ADR-2605111000) |
| 2.4 | 11.25y | animeka migrated + パートナー資力前提 |
| 2.5 | 7.50y | **反訴 + Rokes 反転** (個人請求 → 法人債務化) |
| 3.0 | 7.00y | 内部統制 ISMS 既登録活用 |
| 4.0 | 6.25y | Ghost Hacker 圧縮 + Living System 自動化 |
| **4.E** | **5.75y** | zenos 削除 + Wheeler-DeWitt を別 repo へ |

### 1.2 同じパターンの繰り返し

各 Phase で発見された問題は **同じ 4 種類** に分類できる:

| Pattern | 例 | 影響 |
|---|---|---|
| **A. 過剰ブロッカー** | 借入完済 → 再婚 (経済前提が再婚を強制ブロック) | CP を不当に延長 |
| **B. 立ち上げ前提** | Etzhayyim 立ち上げ (実は既存運用) | est_years 過大評価 |
| **C. 戦略的ノード違い** | IPO (実は Family Office) | 7y → 1y の差 |
| **D. 被害者ポジション** | 借入返済 + Rokes 被害 (実は反訴 + 事業化) | フレーム反転 |

## 2. Decision

**4 つのアンチパターンと修復ルール** を以下に定式化する。
新 DAG ノードの追加・既存ノードのレビュー時にこのチェックリストを適用する。

### 2.1 Pattern A: 過剰ブロッカー検出

**症状**: ノード X → ノード Y のエッジがあるが、Y は X 完了を待たずに着手できる。

**検出基準**:
- Y が「人生イベント」(再婚 / 子供 / 健康 / 出会い) かつ
- X が「経済・法的状態」(借入返済 / 訴訟 / IPO) で
- 現実の人々が X 完了前に Y を実施している

**除去ルール**: そのエッジを削除。`gap_analysis.md` の §依存関係 DAG に
削除理由を記録。

**例**:
- ❌ `14_loan_acceleration → 15_remarriage` (再婚は経済を待たない)
- ❌ `11_health_steady → 15_remarriage` (健康は再婚の前提でない)
- ❌ `12_legal_offload → 13_meeting_partners` (出会いは訴訟を待たない)

### 2.2 Pattern B: 立ち上げ前提の過大評価

**症状**: ノード X が「立ち上げ」前提で est_years が過大 (1.5-2y)、
**実態は既に運用中**。

**検出基準**:
- そのノードに対応する GitHub repo / 法人 / プロダクトが既に存在
- 必要なのは「拡大」「次フェーズ」のみ

**修復ルール**: est_years を **0.5-1y** に短縮、ノード名を「立ち上げ」から
「運用拡大」に rename。

**例**:
- `22_etzhayyim` (2y, 立ち上げ) → `22_etzhayyim_ops` (1.5y, 運用拡大)
  - 実態: `etzhayyim/root` org が 2026-05-15 から live、9 領域稼働
- `21b_gftd_anime_vertical` (2y, 立ち上げ) → (1.5y → 0.75y, 復旧→運用)
  - 実態: animeka.gftd.ai は RunPod Serverless v9si0sflsm0gh0 で migration 完了
- `21_gftd_lawfirm_vertical` (1.5y, 立ち上げ) → (1y, 9-actor 拡大)
  - 実態: lawfirm.gftd.ai は T2 live (12 actor DIDs)

### 2.3 Pattern C: 戦略的ノード違い

**症状**: ノード X (大 est_years) を仮定しているが、本人の意思は別ノード Y
(小 est_years)。

**検出基準**:
- ノード名が「typical な大企業ゴール」(IPO / 上場 / 海外進出)
- 本人の状況 (家族 + 私的) に合うのは別ゴール

**修復ルール**: 該当ノードを **削除 → 置換**。ADR を vendor monorepo に
追加して根拠を残す。

**例**:
- ❌ `18_ipo_or_foundation` (7y, decade) — IPO
- ✅ `18_family_office_conversion` (0.25y, month) — Family Office 化
  - 根拠: ADR-2605111000 (Gftd Japan vendor monorepo)
  - 効果: -7.50y (Phase 2.2 → 2.3)

### 2.4 Pattern D: 被害者ポジション反転

**症状**: ノードが「受動的被害 / 債務」として設計されている。
**実態は能動的な機会 (反訴 / 事業化) になり得る**。

**検出基準**:
- ノードに「被害」「返済」「対応」が含まれる
- 河崎が **当事者・専門知識・自己実証ケース** を持つ

**修復ルール**: ノードを **2-3 個に分割**:
1. 元ノード (est_years 圧縮)
2. 反転ノード (反訴 / 起訴 / 追求)
3. 事業化ノード (同種被害者向け SaaS / 成功報酬)

**例**:
- `era/rokes-hack` (被害) → 分解:
  - `27_rokes_attacker_pursuit` (追跡, 1y)
  - `28_crypto_victim_lawfirm` (事業化, 1.5y, W=0.40)
- `14_loan_acceleration` (7y, 個人返済) → (2y → 1y, 法人債務化 + 反訴)
- 借入 / Servcorp / Paidy / LingLing / 鹿大 → 一律「反訴前提」(`26_counter_litigation`)

## 3. Rationale

### 3.1 CPM が早期に異常を可視化

`phase2_critical_path.py` の Slack 列で、**ある domain (例: relations) が
全部 slack=0** だと過剰ブロッカーの可能性を示唆する。

```
Phase 2.0 の relations 系 CP = 5 nodes (relations 全部 CP)
  → 異常: 関係性ノードが全部直列にブロックされている
Phase 4.E の relations 系 CP = 0 nodes
  → 健全: 並走できる
```

### 3.2 Acceleration シミュレーションで実証

`phase2_acceleration.py` で「エッジを切ったら何年縮むか」を網羅し、
**寄与の大きいエッジ削除候補 = 過剰ブロッカー** とほぼ完全に一致した。

### 3.3 自己反転 (Pattern D) のレバレッジ

被害者ポジションを事業化に転換すると:
- 自己実証ケースが **最強のマーケティング**
- 既存の専門能力 (CEH / 9-actor 法務クラスタ) が直接転用可
- 同種被害者の market size が大きい (NEM / Mt.Gox / Liquid / Coincheck …)

Rokes ケースで Phase 2.5 が **-3.75y** を生んだのはこのパターンの威力。

## 4. Consequences

### 4.1 Positive

- DAG の **継続的健全化** メカニズムが確立
- 新 PR / 新 node 追加時に **チェックリスト化** 可能
- 過剰モデリングが **早期発見** される
- 被害者 → 事業化のフレーム反転が **再現可能** に

### 4.2 Negative

- ノード分割が増えると **管理コスト** 上昇
- 「反訴前提」を取りすぎると **訴訟コスト** が増える可能性
- Pattern A の「過剰ブロッカー」判断は **主観的**

### 4.3 Mitigations

- ノード数 30+ になったら `living/repos.yaml` の routing を再 audit
- 反訴は **個別判定** (`phase3_counter_litigation_memo.md` のような個別ドシエ)
- Pattern A 判定は **本人 + 弁護士 (ZeLo / AMT)** のレビュー必須

## 5. Application checklist

新 DAG ノード追加時 / 既存ノードレビュー時に以下を確認:

- [ ] Pattern A: このノードの prerequisite は「論理的必須」か?「文化的習慣」か?
- [ ] Pattern B: 該当する repo / 法人 / プロダクトは既に存在するか?
- [ ] Pattern C: このノード名は本人の **本当のゴール** か?
- [ ] Pattern D: もしこれが被害 / 受動なら、反転可能か?

## 6. References

- `kawasakijun/gap_analysis.md` — 全 phase の DAG 進化
- `kawasakijun/critical_path.md` — 最新 CPM
- `kawasakijun/acceleration.md` — what-if 分析
- `kawasakijun/phase3_counter_litigation_memo.md` — 反訴の個別判定
- `kawasakijun/phase3_crypto_victim_product.md` — Pattern D の代表例
