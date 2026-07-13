# ADR-2607140500: etzhayyim-substrate-rollout モデル拡張 — 契約法/土地/教育/紛争解決レイヤーの追加(ADR-2607132200 amendment)

**Status**: accepted
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki(ADR-2607132200 マージ後「では実際に進めて」→ 次の優先を問う
質問に「モデルを拡張する(コード範囲)」と回答)
**Amends**: ADR-2607132200
**Scope**: `orgs/etzhayyim/com-etzhayyim-yosoku`

## Context

ADR-2607132200 は `yosoku.models/etzhayyim-substrate-rollout` を7 stockで構築したが、
Consequences節で「mission charterの§1.12表にある『土地』『教育』『秩序維持』等は
明示的にスコープ外」と follow-up を明記していた。ADR-2607132200 の2本のPR
(`com-etzhayyim-yosoku` #1、`com-junkawasaki/root` #414)をマージし、
`manifest/west.yml` のpinを前進させた後、ユーザーに次の優先を問うたところ
「モデルを拡張する(コード範囲)」を選択した。本ADRはその follow-up を実施する。

## Decision

### 1. 追加した4 stock + 4 gate

`com-etzhayyim-yosoku` PR #2(マージ済み、詳細は Artifacts)で
`etzhayyim-substrate-rollout` に以下を追加した:

| 新規 Stock | 意味 | 初期値 | ゲート | ゲート初期値 |
|---|---|---|---|---|
| `ContractLawCoverage` | Solidity smart contracts + Constitution.sol 批准 | 15 | `ConstitutionRatificationGate`(Council がConstitution.solを拘束力あるものとして批准) | 0.1 |
| `LandTrustCoverage` | Religious-Corp Land Trust 不動産保有 | 0 | `LandAcquisitionGate`(実際の不動産資本投下) | 0.05 |
| `EducationCoverage` | open-* apps + agent fleet 教育到達 | 3 | `CurriculumGate`(教育コンテンツの認可) | 0.15 |
| `DisputeResolutionCoverage` | Council 裁定 + 透明性/監査証跡ベースのコンプライアンス | 2 | `AdjudicationAuthorityGate`(Council が拘束力ある裁定権限の責任を引き受ける) | 0.05 |

**安全設計上の明示的境界**: `DisputeResolutionCoverage` は `RoboticsUnits` と
**どちらの方向にも一切配線していない**——`AdjudicationExpansion`(前者への
inflow)の方程式は `RoboticsUnits` を参照せず、`FleetDeployment`(後者への
inflow)の方程式も `DisputeResolutionCoverage` を参照しない。charter の
「Transparent Religious Force」という表現は透明性・監査ベースであって物理的
強制力のケーパビリティではなく、本モデルもそれを表現しない——この非配線は
`dispute-resolution-coverage-is-never-wired-to-robotics-units` テストで直接
回帰検証している。`DisputeResolutionCoverage` は他の全stockと同じ
`(100 - X)` 飽和構造で 0-100 に収まり、別枠のエスカレーション経路を持たない。

### 2. 依存DAGの改訂と逆トポロジーソートの精緻化

新規4 stockを既存の依存構造に統合した結果、ADR-2607132200 のWave分けが
以下のように精緻化される:

- **Wave 0(根)**: `CouncilSeats`, `TreasuryCapacity` — 変更なし。
- **Wave 1**: `IdentityCoverage`(既存)に加え **`ContractLawCoverage`** が同列に
  追加——両者とも Wave 0 のみに依存する(`ContractLawRatification` は
  `CouncilSeats`/`TreasuryCapacity`のみを参照)。
- **Wave 2**: `AgentCapacity`(既存)、**`EducationCoverage`**(新規、
  `AgentCapacity`と相互に補強し合う——`AgentOnboarding`が`EducationCoverage`を
  ソフトブースト項として参照し、`EducationExpansion`が`AgentCapacity`を参照する
  stock媒介の相互ループ。flow/aux間の同時刻循環ではないため
  `xmile.validate`のalgebraic-loop検査には抵触しない、検証済み)、
  **`LandTrustCoverage`**(新規、Wave 0 + Wave 1の`ContractLawCoverage`に依存)、
  **`DisputeResolutionCoverage`**(新規、Wave 1の`ContractLawCoverage`と
  `PublicTrust`フィードバックに依存)。
- **Wave 3**: `SocialCoverage`(既存)——依存が `AgentCapacity`/`IdentityCoverage`
  に加え **`LandTrustCoverage`** にも拡大(物理的な介護拠点が前提)。
- **Wave 4**: `RoboticsUnits`(既存)——依存が `SocialCoverage`/`TreasuryCapacity`
  に加え **`LandTrustCoverage`** にも拡大(車両基地/デポが前提)。
- **横断フィードバック**: `PublicTrust` は `SocialCoverage`(0.4)+
  `RoboticsUnits`(0.3)+ **`DisputeResolutionCoverage`(0.3)** から供給される
  よう重み再配分し、Wave 0 の `CouncilOnboarding` へ還流する。

### 3. ベースライン・シミュレーション(t=60、11 stock)

| Stock | t=60 |
|---|---|
| CouncilSeats | 4.41 |
| TreasuryCapacity | 91.07 |
| IdentityCoverage | 16.61 |
| AgentCapacity | 10.55 |
| SocialCoverage | 1.04 |
| RoboticsUnits | 0.00 |
| PublicTrust | 3.77 |
| ContractLawCoverage | 27.08 |
| LandTrustCoverage | 2.96 |
| EducationCoverage | 4.65 |
| DisputeResolutionCoverage | 2.46 |

### 4. ゲート別レバレッジスコア(8ゲート、単独開放)

対象値 = SocialCoverage + RoboticsUnits + DisputeResolutionCoverage +
EducationCoverage + LandTrustCoverage + ContractLawCoverage の合計(t=60)。

| ゲート | disposition | leverage |
|---|---|---|
| `ConstitutionRatificationGate` | commit | **+54.30** |
| `LandAcquisitionGate` | commit | +39.43 |
| `LegalActivationGate` | commit | +11.23 |
| `AdjudicationAuthorityGate` | commit | +9.63 |
| `CurriculumGate` | commit | +7.87 |
| `RealLLMWiringGate` | commit | +3.88 |
| `G7ConsentGate` | commit | +0.62 |
| `DispatchGate` | commit | +0.24 |

**cloud-itonami ADR(2607121000)のV(code) = TAM × 推移的被依存度(fan-out) ×
自動化可能度 × 資産くさびとの経験的整合**: 最上位2ゲート
(`ConstitutionRatificationGate`/`LandAcquisitionGate`)は、いずれも
被依存stock数(fan-out)が他より多い——`ContractLawCoverage`は
`LandTrustCoverage`と`DisputeResolutionCoverage`の両方に直接必須入力を
供給し、`LandTrustCoverage`は`SocialCoverage`と`RoboticsUnits`の両方に
供給する。逆に最下位の`DispatchGate`(`RoboticsUnits`のみに供給)や
`G7ConsentGate`(`SocialCoverage`のみ、かつ`RoboticsUnits`自身のゲートは
まだ閉じたまま)はfan-out=1相当で単独レバレッジが小さい——fan-outが
レバレッジを予測するという原則が、ADR-2607132200 の4ゲート版に続いて
8ゲート版でも定量的に再現された。

### 5. 全8ゲート同時開放(over-additivityの再検証)

| Stock | t=60(all-open) |
|---|---|
| CouncilSeats | 4.57 |
| TreasuryCapacity | 91.07 |
| IdentityCoverage | 86.40 |
| AgentCapacity | 94.15 |
| SocialCoverage | 84.20 |
| RoboticsUnits | 71.48 |
| PublicTrust | 76.22 |
| ContractLawCoverage | 79.35 |
| LandTrustCoverage | 60.94 |
| EducationCoverage | 86.86 |
| DisputeResolutionCoverage | 52.49 |

合成デルタ = +397.13、単独レバレッジ合計 = +127.21、**比率 3.12倍**
(ADR-2607132200 の4ゲート版では7.3倍)。比率が下がったのは
「補完性が消えた」のではなく、**レバレッジが2つの高fan-outゲート
(`ConstitutionRatificationGate`/`LandAcquisitionGate`)に集中し、単独
スコアの時点で既に大きな値を捉えているため**——依然として全ゲートは
強い補完財であり(単独合計が合成合計の1/3にも届かない)、Wave順の
一括整備が単一ゲート最適化に優る、という ADR-2607132200 の結論は変わらない。

## Consequences

- (+) mission charter §1.12 の主要9行のうち、通貨/身分証明/契約法/紛争解決/
  公共財/土地/行政手続き/教育/ロボティクスに相当する9 stockがモデル化された
  (「秩序維持」は`DisputeResolutionCoverage`として、物理的強制力を持たない
  形で扱う——この限定は意図的かつ回帰テストで固定)。
- (+) fan-outがレバレッジを予測するという構造的知見が、4ゲート→8ゲートへの
  拡張でも再現され、cloud-itonami ADRの手法との経験的整合が一段と強まった。
- (+) `AgentCapacity`↔`EducationCoverage`のstock媒介相互ループが、
  `xmile.validate`のalgebraic-loop検査を正しく通過することを確認
  (flow/aux間の同時刻循環ではなくstock間の遅延結合であるため合法)。
- (−) 初期値・レート定数は依然としてorder-of-magnitudeの見積もりであり、
  実データからの較正ではない(ADR-2607132200と同じ限界)。
- (−) `DisputeResolutionCoverage`の非武装化(RoboticsUnits非配線)は本モデルの
  設計判断であり、charter原文の「Transparent Religious Force」が実際に
  何を指すかの完全な解釈ではない——本ADRはその解釈を矮小化する方向でのみ
  意図的にバイアスをかけている(モデルが強制力インフラの設計図になることを
  避けるため)。

## Verification

- `clojure -M:dev:test`(`orgs/etzhayyim/com-etzhayyim-yosoku`): 46 tests /
  2890 assertions all green(ADR-2607132200 時点は44/1910)。
- `clojure -M:lint`: 0 errors / 0 warnings。
- `xmile.validate/validate`: 0 errors / 0 warnings(11 stock全体、新規の
  stock媒介ループを含む)。
- 上記 §3-5 の数値は `xmile.execute/run` + 実際の `operation/build` +
  `langgraph.graph/run*`(8ゲート全てについてescalate→承認→commitの
  実パスを実行)で得た実測値。

## Artifacts

- `orgs/etzhayyim/com-etzhayyim-yosoku` PR #2(`feat/substrate-rollout-charter-rows`、
  マージ済み commit `0e980a8`):
  - `src/yosoku/models.cljc`(4 stock + 4 gate 追加)
  - `test/yosoku/models_test.clj`(新規テスト2件、うち1件は非武装化の直接回帰検証)
- `manifest/west.yml` の `com-etzhayyim-yosoku` pin を `5ead8fa` → `0e980a8`
  へ前進(GitHub API single-entry commit、diff は該当1行のみ)。

## References

- ADR-2607132200(本ADRが拡張する元ADR)
- ADR-2607121000(cloud-itonami ISIC/ISCO 逆トポロジーソート計画 —
  fan-out予測レバレッジの手法的先例、本ADRで再確認)
- `orgs/etzhayyim/root/90-docs/adr/2605192100-etzhayyim-mission-charter.md`
  §1.12(契約法/土地/教育/秩序維持の行のモデル化対象)
