# ADR-2607141000: etzhayyim-substrate-rollout モデル拡張 — attestation層(kuni-umi Ed25519 N≥2 witness検証)の追加(ADR-2607132200 second amendment)

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki(「残る2つ、どちらを優先しますか?」に「fleetに実LLM pickerを新規実装」
「モデルをさらに拡張(attestation層など)」の両方を選択)
**Amends**: ADR-2607132200(第1 amendment: ADR-2607140500)
**Scope**: `orgs/etzhayyim/com-etzhayyim-yosoku`、`orgs/etzhayyim/com-etzhayyim-fleet`

## Context

ADR-2607140500 で7→11 stockへ拡張した後、ユーザーに残タスクの優先順位を問うたところ
「fleetへの実LLM picker新規実装」と「モデルのさらなる拡張(attestation層等)」の両方が
選択された。本ADRは後者(モデル拡張)を実施する——kuni-umi(惑星インフラ・
ロボティクスフリート)が自ら文書化しているEd25519 N≥2 witness attestation
(「国家検査官」を代替する暗号学的複数証人検証)は、2026-07-13時点の監査で
「設計+部分シムのみ、実配備ゼロ」と評価された機構であり、`etzhayyim-substrate-rollout`
モデルにまだ存在しなかった。前者(fleet実LLM picker)は別PRで並行実施済み
(`etzhayyim/com-etzhayyim-fleet` PR #1、`manifest/west.yml` pin `de108dc → 9be7aa3`)。

## Decision

### 1. 追加した1 stock + 1 gate

`com-etzhayyim-yosoku` PR #4(詳細は Artifacts)で `etzhayyim-substrate-rollout` に
以下を追加した:

| 新規 Stock | 意味 | 初期値 | ゲート | ゲート初期値 |
|---|---|---|---|---|
| `AttestationCoverage` | kuni-umi の Ed25519 N≥2 witness attestation インフラ(「国家検査官」代替の暗号学的複数証人検証) | 8 | `AttestationProtocolGate`(Council が N≥2 witness attestation プロトコルを拘束力あるものとして批准) | 0.05 |

依存: `IdentityCoverage`(witnessはDID/Ed25519身元が必要)+ `ContractLawCoverage`
(attestationプロトコルの法制化)——両者ともWave 1に属するため、`AttestationCoverage`
自体はWave 2に位置づけられる(`AgentCapacity`/`EducationCoverage`/`LandTrustCoverage`/
`DisputeResolutionCoverage`と同列)。

**構造上の重要な追加依存**: `AttestationCoverage` は `FleetDeployment`
(`RoboticsUnits`のinflow)に**直接**新たな依存辺を追加した——kuni-umi自身の設計が
「witness attestationインフラが実証されてから配備を拡大する」ことを要求しているため、
「国家検査官」代替機構そのものが先に稼働している必要がある、という構造をモデルに
組み込んだ(Wave 4の`RoboticsUnits`が依存する集合が `{SocialCoverage,
TreasuryCapacity, LandTrustCoverage, AttestationCoverage}` の4つに拡大)。

`TrustGain`(`PublicTrust`のinflow)の重みを再配分: `SocialCoverage`(0.3)+
`RoboticsUnits`(0.2)+ `DisputeResolutionCoverage`(0.2)+ `AttestationCoverage`(0.3)
——attestation自体が信頼検証メカニズムであるため相応の重みを与えた。

**安全設計上の明示的境界(前回amendmentからの継承)**: `AttestationCoverage` は
`DisputeResolutionCoverage` と**どちらの方向にも一切配線していない**——両者は
別々の信頼メカニズム(暗号学的検証 vs Council裁定)であり、混同しない設計を
回帰テスト(`attestation-coverage-is-never-wired-to-dispute-resolution`)で固定した。

### 2. ベースライン・シミュレーション(t=60、12 stock)

| Stock | t=60 |
|---|---|
| CouncilSeats | 4.43 |
| TreasuryCapacity | 91.07 |
| IdentityCoverage | 16.69 |
| AgentCapacity | 10.57 |
| SocialCoverage | 1.04 |
| RoboticsUnits | 0.00 |
| PublicTrust | 8.49 |
| ContractLawCoverage | 27.14 |
| LandTrustCoverage | 2.97 |
| EducationCoverage | 4.66 |
| DisputeResolutionCoverage | 2.78 |
| AttestationCoverage | 9.20 |

### 3. ゲート別レバレッジスコア(9ゲート、単独開放)

対象値 = SocialCoverage + RoboticsUnits + DisputeResolutionCoverage +
EducationCoverage + LandTrustCoverage + ContractLawCoverage +
**AttestationCoverage** の合計(t=60)。

| ゲート | disposition | leverage |
|---|---|---|
| `ConstitutionRatificationGate` | commit | **+55.51** |
| `LandAcquisitionGate` | commit | +39.47 |
| `AttestationProtocolGate` | commit | **+18.35**(新規、3位) |
| `LegalActivationGate` | commit | +14.60 |
| `AdjudicationAuthorityGate` | commit | +14.15 |
| `CurriculumGate` | commit | +7.91 |
| `RealLLMWiringGate` | commit | +3.90 |
| `G7ConsentGate` | commit | +0.62 |
| `DispatchGate` | commit | +0.07 |

`AttestationProtocolGate` は新規9ゲート中3位の高レバレッジ——`ContractLawCoverage`
という同じWave 1の根を共有しつつ、`AttestationCoverage`自身が`FleetDeployment`にも
直接供給する(fan-out=2: 自分自身の成長 + RoboticsUnits供給)ため、fan-out予測の
知見が三度目の独立モデル差分でも再現された。一方 `DispatchGate` のレバレッジは
+0.24(前回)→+0.07 へさらに低下——`RoboticsUnits`が`AttestationCoverage`という
もう1つの閉じたゲート越しの依存を新たに獲得したことで、`DispatchGate`単独では
以前にも増して動かせなくなった(補完性がさらに強まった実例)。

### 4. 全9ゲート同時開放

| Stock | t=60(all-open) |
|---|---|
| CouncilSeats | 4.63 |
| TreasuryCapacity | 91.07 |
| IdentityCoverage | 86.66 |
| AgentCapacity | 94.16 |
| SocialCoverage | 84.21 |
| RoboticsUnits | 71.26 |
| PublicTrust | 89.68 |
| ContractLawCoverage | 79.63 |
| LandTrustCoverage | 60.95 |
| EducationCoverage | 86.87 |
| DisputeResolutionCoverage | 58.35 |
| AttestationCoverage | 82.35 |

合成デルタ = +475.85、単独レバレッジ合計 = +154.59、**比率 3.08倍**
(前回amendment: 3.12倍、初版: 7.3倍)——ゲート数が増えるほど「高fan-outゲート数個に
レバレッジが集中し、比率自体は緩やかに低下する」傾向が一貫している。ただし
単独合計が合成合計の1/3にも届かない点は変わらず、依然として強い補完財である
という結論は維持される。

## Consequences

- (+) mission charter §1.12 の主要行に加え、kuni-umi独自の「国家検査官代替」設計
  (attestation)もモデル化され、`RoboticsUnits`の依存集合がより現実に即した
  ものになった(物理配備には土地・信頼実績・attestationインフラの3つが
  必要、という構造がモデルの数式そのものに刻まれている)。
- (+) fan-out予測レバレッジの知見が3つ目の独立差分(4ゲート→8ゲート→9ゲート)でも
  一貫して再現され、cloud-itonami ADR-2607121000のV(code)公式との経験的整合が
  さらに強まった。
- (−) `AttestationCoverage`の初期値(8)は、kuni-umiの「設計+部分シムのみ」という
  2026-07-13監査結果からのorder-of-magnitude見積もりであり、実測較正ではない
  (既存stockと同じ限界)。
- (−) `DispatchGate`単独の実効性がさらに低下したことは、ロボティクス配備が
  「単一の意思決定」では動かせない、複数ゲートの同時整備を要する領域である
  ことを一段と明確にした——次にロボティクス層の実行フェーズを検討する際は
  単独ゲート起動でなく複合整備を前提とすべき。

## Verification

- `clojure -M:dev:test`(`orgs/etzhayyim/com-etzhayyim-yosoku`): 60 tests /
  3155 assertions all green(前回amendment時点は58/2908)。
- `clojure -M:lint`: 0 errors / 0 warnings。
- `xmile.validate/validate`: 0 errors / 0 warnings(12 stock全体)。
- 上記 §2-4 の数値は `xmile.execute/run` + 実際の `operation/build` +
  `langgraph.graph/run*`(9ゲート全てについてescalate→承認→commitの実パスを
  実行)で得た実測値。

## Artifacts

- `orgs/etzhayyim/com-etzhayyim-yosoku` PR #4(`feat/attestation-layer`):
  - `src/yosoku/models.cljc`(`AttestationCoverage`/`AttestationProtocolGate`
    追加、`FleetDeployment`/`TrustGain`更新)
  - `test/yosoku/models_test.clj`(新規テスト2件)
- 並行実施(fleet実LLM picker、本ADRの範囲外だが同時期の関連成果):
  `orgs/etzhayyim/com-etzhayyim-fleet` PR #1(`fleet.advisor`新設、
  `manifest/west.yml` pin `de108dc → 9be7aa3`)。
- `manifest/west.yml` の `com-etzhayyim-yosoku` pin を(本ADR記載時点の最新)
  へ前進(GitHub API single-entry commit)。

## References

- ADR-2607132200(元ADR)、ADR-2607140500(第1 amendment)
- ADR-2607121000(cloud-itonami逆トポロジーソート計画 — fan-out予測レバレッジの
  手法的先例、本ADRで3度目の再確認)
- `orgs/etzhayyim/com-etzhayyim-kuni-umi`(`AttestationCoverage`のモデル化対象)
