# ADR-2607121000: cloud-itonami 全世界展開 — ISIC/ISCO 逆トポロジーソート rollout 計画

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/industry`, `orgs/kotoba-lang/occupation`,
`orgs/gftdcojp/cloud-itonami`, `orgs/cloud-itonami/*`(衛星 blueprint 艦隊)

## Context

cloud-itonami は kotoba / kotobase / murakumo(WASM lattice + DID/CID overlay)/
kotoba-lang/treasury(USDC→Gnosis Safe 実稼働決済)の分散基盤の上で、全世界の
事業活動を LLM・agent・robotics で書き換える org である。現状の実態
(2026-07-12 監査):

- **稼働**: itonami.cloud は実在(Cloudflare Pages、CACAO/did:key 認証)だが
  実稼働は gftdcojp 単一テナント、実収益ゼロ。
- **資産**: `kotoba-lang/industry` に ISIC 646 entries(131 `:implemented` /
  494 `:spec`)、`kotoba-lang/occupation` に ISCO-08 436/436 unit groups
  (23 `:implemented` / 61 `:blueprint` / 353 `:spec`)。衛星 repo 388 本
  (src あり 185、compiled `.wasm` 到達は 6492 与信・6511 保険の 2 本)。
  iso3166 × 223 か国 compliance 層。全て itonami.cloud 未配線。
- **収益モデル**: ADR-2607051621 の 4 層(L0 AGPL 自己ホスト無料 → L1
  itonami.cloud ネットワーク登録 → L2 オンチェーン protocol fee → L3 デュアル
  ライセンス)は決定済みだが L1 の第三者自己登録フローは未開通。

646 の業種と 436 の職業を「どの順に agent 化するか」の全体順序が存在せず、
着手順が場当たりになるリスクがあった(実際には偶然、最深部の金融 2 verticals
が先行 wasm 化されており、これは本計画の順序と一致していた)。

## Decision

### 1. 価値関数と辺の定義

**V(code) = TAM × 推移的被依存度(fan-out) × 現時点自動化可能度 × 既存資産くさび**

依存 DAG の辺を **u → v =「v の操業は u の産出を必須運転入力とする」**と定義
する。**逆トポロジーソート = 被依存の根(全経済が推移的に依存するノード)から
並べる順序**を rollout の正順とする。根を押さえた者がその上の全ノードの取引に
決済・通信・compliance の面で接続するため、価値最大化の順序はこの逆順に一致する。

### 2. ISIC 5-wave(正本: `kotoba.industry.wave`)

| Wave | 名称 | ISIC divisions | 論理 |
|---|---|---|---|
| 0 | 貨幣・情報の根 | 61-66(+5820) | 決済・保険・通信・データは全 21 section の必須入力。treasury/murakumo が代替実装そのもの。既存 wasm 2 本(6492/6511)はここ |
| 1 | 統治・専門職・エネルギー | 69-74, 84, 99, 35-36, 06/09/19(+8291) | 法人設立(6910)× compliance(8291)× iso3166×223 か国 = market-entry gateway moat |
| 2 | 流通・調整・労働市場 | 45-47, 49-53, 68, 78/80-82 | 物流・商流・不動産・労働(7810 = ISCO 436 職業への橋) |
| 3 | 生産・建設(robotics) | 01-03, 05/07/08, 10-33(19除く), 37-39, 41-43 | robotics premise(ADR-2607011000)ゲート。coordination の agent 化後 |
| 4 | 対人サービス | 55-56, 85, 86-88, 90-98, 58-60, 75, 77/79 | 信頼ゲート最深・長期 TAM 最大(医療介護、日本の高齢化くさび) |

class 単位の例外: `5820`(software publishing)→ Wave 0、`8291`(compliance
intelligence)→ Wave 1。

### 3. ISCO 5-wave(正本: `kotoba.occupation.wave`)

| Wave | sub-majors | 論理 | 既存 curated blueprint |
|---|---|---|---|
| 0 | 24, 25, 35, 41-44(+261) | 純認知職 = LLM 第一波。ICT 職が全職業の自動化の根 | — |
| 1 | 01-03, 11-13, 21, 31 | 設計・統制 | 1321 |
| 2 | 33, 54, 83, 93(+432) | 調整・物流 | 4321, 8332, 9312 |
| 3 | 61-63, 71-75, 81-82, 92, 96 | robotics 生産技能 | 6112, 7126 |
| 4 | 14, 22-23, 26, 32, 34, 51-53, 91, 94-95 | 対人・信頼 | 2221, 3253, 5322 |

minor 単位の例外: `261`(legal)→ Wave 0、`432`(material-recording clerks)
→ Wave 2(4321 blueprint を ISIC 物流 wave に揃える)。

### 4. 価値ランキング Top 10(V 降順)

1. 6419+6492 銀行・与信(wasm 済、treasury が決済実体を保有)
2. 6511/6512 保険(wasm 済、underwriting governor 実装済)
3. 6611/6612 市場管理・仲介(kotoba log は本質的に取引台帳)
4. 6910×8291×iso3166 世界法人設立・compliance ゲートウェイ(223 か国資産の収益化)
5. 6190/6110 + 6201(**registry 欠落、要 scaffold**)— murakumo.cloud 自体の ISIC 登録
6. 3510/3512 電力(lattice の電力需要と垂直統合)
7. 5210/5224/4920 物流編成
8. 7810 労働市場(人→agent→robot の置換取引所)
9. 4690 商社(ADR-2607110600 起票済・実装未着)
10. 8610/8710/8810 医療介護(長期最大、日本くさび)

### 5. 実行フェーズ

- **P0(即時)**: ADR-2607051621 未了部を閉じる — treasury 切り出し完了、
  `/api/open-business` 動的化、self-mint CACAO 自己登録。**外部登録ゼロ・実収益
  ゼロを破る最初の 1 社がクリティカルパス**。並行して 6492/6511 パターンを
  Wave 0 金融残り 16 classes へ複製し、`.wasm` を murakumo lattice の `on-http`
  component として配置(衛星 repo ↔ itonami.cloud 未配線をここで解消)。
- **P1**: iso3166×223 を market-entry API 化、L2 protocol fee streaming 開始。
  registry の `:spec`→`:blueprint`→`:implemented` 前進を fee 対象に連動。
- **P2**: 物流・労働 agent(Wave 2)、ISCO Wave 0 職業の agent 化を 7810 経由で市場化。
- **P3-P4**: robotics premise で Wave 3、信頼実績を積んでから Wave 4。

### 6. 永続化(本 ADR で実装済み)

- `kotoba-lang/industry` に `kotoba.industry.wave`(pure .cljc、division/section
  全域 + class overrides)を追加。`execution-plan` / `maturity-roadmap` が
  `:wave` を返し、`wave-maturity-summary` が wave × maturity の進捗計を返す。
  テスト: registry 646 entries 全域性 + spot checks(15 tests / 932 assertions
  green、clj-kondo 0 errors)。
- `kotoba-lang/occupation` に `kotoba.occupation.wave`(同型、sub-major 全域 +
  minor overrides)を追加。curated 9 blueprints の wave 整合をテストで固定
  (14 tests / 881 assertions green)。

## Consequences

- (+) 646 業種・436 職業の全 entry が機械可読な rollout wave を持ち、
  `wave-maturity-summary` で「下位 wave から implemented になっているか」を
  常時監査できる。着手順の場当たり化を構造的に防ぐ。
- (+) 既存の実装分布(金融 wasm 2 本が Wave 0)が計画と整合していることを
  事後確認できた — 順序の正しさの実証サンプル。
- (−) wave は rollout 優先度であり品質 tier ではない。maturity ladder
  (:spec→:blueprint→:implemented)と直交する。混同しないこと。
- (−) TAM 概算(金融 ~$20T 収益プール等)は桁感の指標であり、正確な市場調査は
  各 wave 着手時の個別 ADR で行う。
- (−) 欠落 ISIC の scaffold(6201/6311 自業種、C10-12 食品 ~$8T、2100 医薬、
  4100 建築)は本 ADR のスコープ外の follow-up。
- (−) Wave 0 を飛ばして Wave 3/4 から着手する提案は、本 DAG の全依存辺を
  逆走するため、本 ADR を supersede しない限り却下する。

## Artifacts

- `orgs/kotoba-lang/industry/src/kotoba/industry/wave.cljc`(新規)
- `orgs/kotoba-lang/industry/src/kotoba/industry.clj`(`:wave` 統合)
- `orgs/kotoba-lang/industry/test/kotoba/industry_wave_test.clj`(新規)
- `orgs/kotoba-lang/occupation/src/kotoba/occupation/wave.cljc`(新規)
- `orgs/kotoba-lang/occupation/src/kotoba/occupation.cljc`(`:wave` 統合)
- `orgs/kotoba-lang/occupation/test/kotoba/occupation_wave_test.clj`(新規)

## References

- ADR-2607051621(murakumo AGPL 化 + 4 層収益モデル + treasury 切り出し)
- ADR-2607011000(robotics premise + ISIC 21/21)
- ADR-2607012000(ISCO-08 occupation blueprints、curated 9)
- ADR-2607031500(m6910 global incorporation actor)
- ADR-2607072530 / 2607072600(6511/6492 kototama wasm 実行実証)
- ADR-2606271700(cloud-itonami business OS 初版)
- 本 ADR とペアの `.edn`

## Addendum (2026-07-15): P3→P4 順序ゲートの amendment（ADR-2607152500）

§5 の「Wave 3 で robotics 信頼実績を積んでから Wave 4」は、オーナー明示指示により
**ADR-2607152500 で Wave3→Wave4 の前提条件のみ amend**（Wave 3 完了を待たず並行着手
を許可)。wave 定義・value function・逆トポロジーソートの根拠自体は不変。61%
defect 事故（ADR-2607152300）の教訓を踏まえた品質ガードレール（小バッチ・
verified-redo・自己申告不信用）を Wave 4 にも明文で継承した上での override。
Wave 4 第一号 flagship: ISIC 873（高齢者・障害者向け居住介護、ADR-2607152700）。
詳細は ADR-2607152500 を参照。
