# ADR-2607132200: etzhayyim 国家非依存社会保障基盤 — システムダイナミクス構造分析・スコアリング・逆トポロジーソート

**Status**: accepted
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki（ユーザー指示「では設計実装実現」→「次にどう動くのが近いか」への回答として
「system dynamics を分析して, score, 構造分析, 逆トポロジーソート」と明示）
**Scope**: `orgs/etzhayyim/*`(mission charter とその parallel-substrate 各コンポーネント)、
`orgs/etzhayyim/com-etzhayyim-yosoku`(System Dynamics governed simulation actor)

## Context

2026-07-13 の調査 agent 監査（本 ADR の直前セッション）で、etzhayyim の
「既存の地上の国家に依存しない、blockchain・分散型統治・AIエージェント・LLM・
ロボティクスによる社会保障基盤」構想は、`orgs/etzhayyim/root/90-docs/adr/
2605192100-etzhayyim-mission-charter.md` §1.12「国家機能の Parallel Substrate 化」
として**憲章レベルでは作り込まれているが、実装は R0 スキャフォールド〜R3a
（junbi 準備金）程度に留まり、実通貨・実Council投票・実ケア対象者・実ロボットに
触れる直前で一貫して意図的にゲートされている**ことが判明した(mimamori:
「synthetic seed only、live legs G7-gated」、kuni-umi: 「S0、no robots
dispatched」、junbi: 「CBDC/fiat 実起動は ops/legal act であり code ではない」)。

ユーザーはこの現状認識を受け、次の一手として「ロードマップADRを書く」ではなく
「system dynamics を分析して, score, 構造分析, 逆トポロジーソート」を明示的に
選択した。これは cloud-itonami の ISIC/ISCO 逆トポロジーソート計画
(ADR-2607121000)と同型の手法を、**静的な業種表ではなく実際にシミュレート可能な
System Dynamics モデル**として etzhayyim の post-nation-state 基盤に適用する
ことを意味する。ちょうど etzhayyim には `com-etzhayyim-yosoku`(ADR-2607072630)
という「governed シナリオ・シミュレーション actor」が既に存在し、
`kotoba-lang/org-oasis-open-xmile`(XMILE)の実シミュレーションエンジン +
ScenarioGovernor(独立検閲者)を備えている——この既存インフラを**そのまま
使う**ことが、新規に何かを作るより誠実な検証手段になる。

## Decision

### 1. モデル定義: `yosoku.models/etzhayyim-substrate-rollout`

`com-etzhayyim-yosoku` に新規モデルを追加した(PR: 下記 Artifacts)。7 stock で
mission charter の parallel-substrate 各層の rollout 成熟度を表現し、
依存辺 **u → v = 「v の成長は u のストック水準を運転入力として必須とする」**
を、`MIN(1, upstream/threshold)` 項として各 flow の方程式に直接埋め込む
——構造分析が「文書」ではなく「実行可能なシミュレーション」になる。

| Stock | 意味 | 初期値(2026-07-13 実測) |
|---|---|---|
| `CouncilSeats` | Council 議席充足(統治正統性) | 1 (5議席中1のみ充足) |
| `TreasuryCapacity` | junbi 準備金パイプライン成熟度 | 70 (R3a — 最成熟) |
| `IdentityCoverage` | did:web/did:plc/SBT 発行カバレッジ | 2 (synthetic seed) |
| `AgentCapacity` | 実(非mock) LLM-agent 行政処理能力 | 5 (大半 mock-advisor) |
| `SocialCoverage` | 実(非synthetic) mimamori/hagukumi/BHI 給付到達 | 1 (R1-offline が最成熟) |
| `RoboticsUnits` | 実配備ロボティクス台数(正規化) | 0 (kuni-umi S0) |
| `PublicTrust` | 実給付が生む信頼(Council正統性へフィードバック) | 5 |

4つの**自己賦課ポリシーゲート**(aux 定数、シナリオパッチ可能)が本モデルの
主眼で、各層が「実装が無いから止まっている」のではなく「意図的に閉じている」
ことを数値化する:

| ゲート | 意味 | 初期値 |
|---|---|---|
| `LegalActivationGate` | junbi の CBDC/fiat 実起動(「ops/legal act であり code でない」) | 0.05 |
| `RealLLMWiringGate` | mock-advisor → 実 Murakumo 推論への配線 | 0.1 |
| `G7ConsentGate` | mimamori の自己申告「live legs G7-gated」 | 0.05 |
| `DispatchGate` | kuni-umi の自己申告「S0、ロボット未展開」 | 0.0 |

`ComplianceFloor`/`RegulatoryCap` は governor.cljc 既定の protected-variable
(mission charter の「国家転覆ではない」という自己制約のスタンドイン)——
`:scenario/propose` パッチは何者が提案しても構造的に触れない。

### 2. 逆トポロジーソート(依存 DAG からの構築順序)

flow の依存構造そのものが DAG を成す:

```
CouncilSeats ─┐                                   ┌──────────────┐
              ├─▶ IdentityCoverage ─▶ AgentCapacity ─▶ SocialCoverage ─▶ RoboticsUnits
TreasuryCapacity(×LegalGate) ┘         (×LLMGate)      (×G7Gate)          (×DispatchGate)
                                                              │                  │
                                                              └──── PublicTrust ◀┘ (フィードバック → CouncilOnboardRate)
```

根(被依存の最深部)から順に並べる逆トポロジーソートは:

- **Wave 0(統治・通貨の根)**: `CouncilSeats` + `TreasuryCapacity` — Council 議席充足と
  junbi 準備金。他の全層が推移的にこれに依存する。
- **Wave 1(身分証明)**: `IdentityCoverage` — `LegalActivationGate` を開く(= junbi
  CBDC/fiat 実起動という ops/legal act を取る)ことが前提。
- **Wave 2(AI行政基盤)**: `AgentCapacity` — `RealLLMWiringGate` を開く(= 実
  Murakumo 推論配線)ことが前提。
- **Wave 3(社会保障の実給付)**: `SocialCoverage` — `G7ConsentGate` を開く
  (= mimamori/hagukumi の go-live 同意)ことが前提。
- **Wave 4(ロボティクス物理層)**: `RoboticsUnits` — `SocialCoverage` の実績
  (信頼の蓄積)と `DispatchGate`(kuni-umi の N≥2 witness attestation 前提)を
  要する。

cloud-itonami(ADR-2607121000)は production/robotics(wave3)を human-services
(wave4)より先に置いたが、本モデルでは順序が逆——**kuni-umi 自身の設計が
「配備前に実績信頼(SocialCoverage)を要求する」ため**、ドメイン差として
意図的に採用する(混同しないこと)。Wave 0 を飛ばして Wave 3/4 から着手する
提案は、cloud-itonami ADR と同じ理由(依存辺の逆走)で却下する。

### 3. ベースライン・シミュレーション(t=0→60, 月次)

現状のゲート値のまま 60 期実行した結果(`xmile.execute/run`, 検証 0 errors/0 warnings):

| Stock | t=60 |
|---|---|
| CouncilSeats | 4.40 |
| TreasuryCapacity | 91.07 |
| IdentityCoverage | 16.59 |
| AgentCapacity | 10.45 |
| SocialCoverage | 1.56 |
| RoboticsUnits | 0.00 |
| PublicTrust | 2.76 |

ゲートが薄い層(CouncilSeats/TreasuryCapacity)は着実に伸びる一方、
SocialCoverage/RoboticsUnits はほぼ横ばい——**「実装力不足ではなく自己賦課
ゲートによる意図的停止」という定性的監査結果を、数値シミュレーションが
再現した**。

### 4. ゲート別レバレッジスコア(単独開放, 逆トポロジーソートの経験的裏付け)

各ゲートを単独でベースラインの0.05〜0.1相当から0.9へ「開放」する
`:scenario/propose` パッチを ScenarioGovernor 経由(mock-advisor → govern →
escalate → 承認 → commit)で提案・実行し、t=60 の `SocialCoverage +
RoboticsUnits` の増分をスコアとした:

| ゲート | disposition | Δ(SocialCoverage+RoboticsUnits) |
|---|---|---|
| `G7ConsentGate` | commit(escalate→承認) | **+9.06** |
| `DispatchGate` | commit(escalate→承認) | +8.01 |
| `LegalActivationGate` | commit(escalate→承認) | +3.85 |
| `RealLLMWiringGate` | commit(escalate→承認) | +1.30 |

単独開放では G7ConsentGate(mimamori 実給付の同意)が最大レバレッジ。

### 5. 全ゲート同時開放(over-additivity ——単独スコアの罠)

4ゲートを同時に開放すると t=60 は CouncilSeats 4.65 / TreasuryCapacity 91.07 /
IdentityCoverage 86.67 / AgentCapacity 89.38 / **SocialCoverage 86.50 /
RoboticsUnits 78.29** / PublicTrust 102.34。合成デルタは
`(86.50+78.29)-1.56 = 163.23` で、単独スコアの単純合計(22.22)の **約7.3倍**。

これは構造分析としての本 ADR の核心の発見: **4ゲートは代替財ではなく強い
補完財**——単独開放では下流 stock が次の閉じたゲートにすぐ律速されるため
(例: DispatchGate だけ開いても RoboticsUnits は `SocialCoverage` にも
律速される)、ROI 最大化は「どれか1つを選ぶ」のではなく Wave 0→4 の
**順序どおり全ゲートを揃える**ことにある。これが逆トポロジーソートの
実証的裏付けであり、単独レバレッジスコアだけで優先順位を決めるのは誤り。

### 6. ガバナンス実証 + 副産物のバグ修正

- 敵対的検証: `ComplianceFloor` を書き換える `:scenario/propose` を提案 →
  `disposition = :hold`(hard reject、人間の承認をもってしても上書き不可)。
  mission charter の「国家転覆ではない」という自己制約が、governor 層で
  構造的に破れないことを確認した。
- **実バグ発見・修正**: `yosoku.governor/parameter-bound-violations` は、
  旧値が厳密に 0 の変数へパッチ(相対変化 = `##Inf`)を評価すると
  `(int ##Inf)` で `IllegalArgumentException` をスローしていた
  (`DispatchGate` 0.0→0.9 で実際に再現)。「ゼロ初期化されたゲートを開く」
  という、本モデルが直接行う操作そのものがクラッシュ経路だった。
  `pct-str` ヘルパーで無限大を `"∞"` として安全にレンダリングするよう修正し、
  `governor_test.clj`/`models_test.clj` に回帰テストを追加した。

### 7. 実行フェーズ(Wave → ops/legal act のマッピング)

- **P0**: Wave 0 の実務——Council 残り4議席の充足、junbi 準備金の対外報告体制
  整備(コードではなく人的プロセス)。
- **P1**: `LegalActivationGate` を開く意思決定(junbi の CBDC/fiat 実起動)を
  Council 決議として取る。IdentityCoverage の実発行を開始。
- **P2**: `RealLLMWiringGate` を開く(mock-advisor → 実 Murakumo 推論)。
  fleet/tashikame/kouhou 等の実LLM配線を優先。
- **P3**: `G7ConsentGate` を開く(mimamori/hagukumi の go-live 同意プロセス)。
  synthetic seed から実ケア対象者への移行。
- **P4**: `DispatchGate` を開く(kuni-umi の N≥2 witness attestation 実証後)。
  Wave 3 の実績(SocialCoverage)が前提。

## Consequences

- (+) etzhayyim の post-nation-state 構想が、憲章文書だけでなく**実行可能な
  governed シミュレーションモデル**として `com-etzhayyim-yosoku` に存在する
  ようになった——今後の「どのゲートを開くか」の議論を、定性的な印象論でなく
  シミュレーション結果で行える。
- (+) 逆トポロジーソートの構築順序(Wave 0→4)が、モデルの依存 DAG から
  導出された構造的帰結として得られ、cloud-itonami 方式との手法的整合が取れた
  (かつドメイン差として robotics/human-services の順序が逆になる理由も
  明示できた)。
- (+) 実際にツールを実運用に近い形で動かしたことで、`yosoku.governor` の
  実クラッシュバグを1件発見・修正できた(副産物だが実利益)。
- (−) パラメータ(成長率・閾値)はこの ADR の作成者による order-of-magnitude
  の見積もりであり、実測データからの較正ではない——cloud-itonami ADR の
  TAM 見積もりと同じ位置づけ(桁感の指標)。各 Wave 着手時に実データで
  再較正すべき follow-up。
- (−) 単独レバレッジスコア(§4)だけを見て優先順位を決めると誤る
  (§5 の補完性のため)——本 ADR を読む者は必ず §5 まで読むこと。
- (−) 本モデルは stock 7個・flow 8個の縮約モデルであり、mission charter の
  §1.12 表にある「土地」「教育」「秩序維持」等は明示的にスコープ外
  (follow-up でモデルを拡張する余地)。

## Verification

- `clojure -M:dev:test`(`orgs/etzhayyim/com-etzhayyim-yosoku`): 44 tests /
  1910 assertions all green。
- `clojure -M:lint`: 0 errors / 0 warnings。
- `xmile.validate/validate` on `etzhayyim-substrate-rollout`: 0 errors /
  0 warnings。
- 上記 §3-6 の全数値は `xmile.execute/run` + 実際の `operation/build` +
  `langgraph.graph/run*`(ScenarioActor の完全なグラフ実行、mock ではなく
  実 governor チェック)を通して得た実測値。

## Artifacts

- `orgs/etzhayyim/com-etzhayyim-yosoku` PR #1 (`feat/substrate-rollout-sd-model`):
  - `src/yosoku/models.cljc`(`etzhayyim-substrate-rollout` 追加)
  - `src/yosoku/governor.cljc`(`##Inf` フォーマットクラッシュ修正)
  - `test/yosoku/governor_test.clj`(回帰テスト追加)
  - `test/yosoku/models_test.clj`(新規)
- 本 `.md` とペアの `.edn`

## References

- `orgs/etzhayyim/root/90-docs/adr/2605192100-etzhayyim-mission-charter.md`
  §1.12(国家機能の Parallel Substrate 化 — 本 ADR がモデル化した対象)
- ADR-2607072630(`com-etzhayyim-yosoku` 新設——本 ADR が利用した actor 本体)
- ADR-2607021800(`etzhayyim-junbi-reserve-basket` — `TreasuryCapacity`/
  `LegalActivationGate` のモデル化対象)
- ADR-2607121000(cloud-itonami ISIC/ISCO 逆トポロジーソート計画 —
  本 ADR が踏襲した手法的先例)
- `orgs/etzhayyim/com-etzhayyim-mimamori`(`SocialCoverage`/`G7ConsentGate`
  のモデル化対象)
- `orgs/etzhayyim/com-etzhayyim-kuni-umi`(`RoboticsUnits`/`DispatchGate`
  のモデル化対象)
