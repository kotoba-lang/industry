# ADR-2607011000: cloud-itonami の robotics 前提設計と ISIC section coverage 21/21

**Status**: accepted
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki

## Context

`cloud-itonami-{ISIC}` は ISIC Rev.5 分類ごとの OSS open business blueprint を
発行する枠組み。blueprint は `blueprint.edn`(業務仕様) ↔ `kotoba-lang/industry`
(ISIC→必要技術) ↔ `kotoba-lang/technology`(技術→実装 repo) ↔ `kotoba-lang` 純
cljc lib の 4 層で完結する（`execution-plan`/`readiness` で検証可能）。

二つの設計決定が本 ADR で成立した:

1. **Section coverage の残り(T/U)を閉じ、全 21 section を覆盖**する。
2. **全 itonami を robotics 前提で設計する** — 物理領域作業は robot が行い、
   actor は action を提案し、独立 governor がそれを gate する。

従来 coverage は 7/21(33%)。通信(6190)・銀行(6419)・カード(6619)の 3 vertical は
lib(swift/banking/phone/card) + registry + blueprint で完成済みだったが、残り
14 section は未設計で、robotics 前提も宣言されていなかった。

## Decision

### 1. ISIC section coverage 21/21

未カバー section 各 1 件の代表 class を industry registry に追加した:
A(0162 農業支援) B(0810 採石) F(4211 建築) G(4711 小売) H(4920 貨物)
I(5510 宿泊) L(6810 不動仲介) M(7110 建築士) N(7810 職業紹介) O(8411 公共行政)
R(9101 図書館) S(9511 ICT修理) T(9700 家事雇用) U(9900 域外機関支援)。
G/H/L/T には独自プロトコル lib を新設し、blueprint repo も発行した
(retail/logistics/property/labor)。result: 21/21 section, 26 industry entry
(うち repo 付 15 / registry-only 11), 19 technology entry。

### 2. Robotics 前提 — `:robotics` を全 itonami の必須 capability に

全 cloud-itonami vertical は **robot が物理領域作業を行う** 前提で設計する。
robotics 契約は新 lib `kotoba-lang/robotics` に純データで定義する:

- **mission** — 1 mission = 1 bounded operation。durable outer loop(lease/tick/
  budget/governor/crash recovery)が mission を反復し、単一 mission は内部ループしない
  (langgraph-clj StateGraph の 1 run = 1 operation 原則と整合)。
- **action** — robot actuation の提案。kind(`:sense`/`:move`/`:grasp`/`:actuate`/
  `:emit`)と safety-class(`:none`..`:safety-critical`)を持つ。
- **governor gate** — governor は action の safety-class が許可集合に含まれるかを
  判定し、`:high`/`:safety-critical` は human sign-off(interrupt-before)へ回す。
  **governor は自らハードウェアを駆動しない**（policy, not control）。
- **safety-stop** と **telemetry-proof** — 停止理由と、robot sensing を audit ledger
  に繋ぐ証拠レコード。

単一の不変条件: **governor が拒否する action を actor は決してハードウェアへ
dispatch しない。** これが既存の actor 3 例(robotaxi-actor / gftd-talent-actor /
ai-gftd-itonami)の「知能ノードを封じ込め + 独立 governor」構図の、物理作動版である。

industry registry の全 26 entry の `:required-technologies` に `:robotics` を一括付与
し、機械可読かつ `readiness` で検証可能にした（全 entry が `:robotics` を含まないと
ready にならない）。各 blueprint.edn は `:itonami.blueprint/robotics true` を持ち、
README は "Robotics premise" 節で領域 robot と governor 名を明示する。

## 新規 repo（本 ADR の成果物）

- lib (kotoba-lang org, public, Apache 2.0, 純 cljc, テスト緑):
  `swift` `banking` `phone` `card` `retail` `logistics` `property` `labor`
  `robotics`（計 9）
- blueprint repo (gftdcojp org, public, AGPL-3.0):
  `cloud-itonami-4711` `-4920` `-6810` `-9700` `-9900`（計 5）
- registry: `kotoba-lang/technology` に `:retail :logistics :property :labor
  :robotics` 追加、`kotoba-lang/industry` に 9700/9900 + 12 section entry 追加、
  全 entry に `:robotics` 必須化。

## Consequences

- (+) 全 21 ISIC section を覆盖。industry/technology registry は 26 entry / 19 tech、
  `execution-plan`/`readiness` が全件解決することを検証済。
- (+) robotics 前提が機械可読（registry の `:robotics` 必須）+ 各 blueprint の
  `:robotics true` flag + README 記述で三重に表明される。governor gate は
  `kotoba-robotics/gate` で純データ検証可能。
- (+) 既存の actor pattern（封じ込め + 独立 governor + 不変台帳）の物理作動版が
  定義され、LLM 駆動 actor が公共空間で robotics を安全に運用できる根拠が示された。
- (−) 既存 6 blueprint(6419/6190/6619/4711/4920/6810) は robotics 前題の retrofit
  （flag + README 節）で対応。registry-only の 11 section は blueprint repo 未作成。
- (−) `:robotics` 必須化に伴い `kotoba-lang/industry` test 1 件を `:robotics` 含む
  available 集合に更新した。
- superproject 登録: `manifest/repos.edn` と `manifest/west.yml` に上記 9 lib を
  登録する。blueprint repo 群は既存 cloud-itonami-* の慣例通り repos.edn 未登録
  （standalone）。

## References

- 既存 actor 3 例: robotaxi-actor / gftd-talent-actor / ai-gftd-itonami
- langgraph-clj ADR-0001 (Pregel superstep + interrupt + Datomic checkpoint)
- `kotoba-lang/robotics` README の safety model 表
- 本 ADR とペアの `.edn`

## Addendum (2026-07-01, ADR-2607012100)

「新規 repo」節の blueprint repo(`cloud-itonami-4711` `-4920` `-6810` `-9700`
`-9900` を含む ISIC 由来 26 件)は `gftdcojp` org, public として発行したが、
ADR-2607012100 により `cloud-itonami` org へ transfer 済み(visibility は public の
まま不変)。上記本文の「gftdcojp org」表記は移管当時の事実として保持し書き換えない
— 現在の所属 org は ADR-2607012100 を参照。
