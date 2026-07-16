# ADR-2607122400: `cloud-itonami-isic-5820`/`6201` に集計ダッシュボード分析を追加し `kotoba-lang/crm` に `kotoba.crm.funnel` を新設

- Status: Accepted (2026-07-12)
- 関連: ADR-2607121900（`cloud-itonami-isic-5820`）、ADR-2607122100
  （`cloud-itonami-isic-6201`）、`kotoba-lang/crm`

## Context

Sales（5820）・Marketing（6201）・Service（6202、並行セッションが独立実装）
の3本柱が揃った時点で、オーナーから「新規ISICを増やさず、既存3本柱の機能を
レポート/ダッシュボード方向へ深化する」方針が示された。`cloud-itonami-isic-6202`
は本ADRのスコープ外（別セッションの成果物であり、今回は触れていない）。

各actorには既に `crm.report`/`report.cljc` という「1レコードを開示tier
ガード付きで render する」概念が存在するが、これは今回追加する「複数
レコードを横断集計する」概念とは別物であり、名前空間を分離した。

## Decision

1. `kotoba-lang/crm` に4番目の技術commons `kotoba.crm.funnel` を新設:
   `stage-counts`（現在stage別のスナップショット分布、全stageゼロ埋め）、
   `reached-counts`（各stageに到達した累積カウント。`kotoba.crm.pipeline`
   の forward-only 不変条件に依拠。exit-stageにいるentityは、明示的な
   `:reached-stage` factが無い限り**推測せず除外**）、`conversion-rate`
   （0除算は`nil`、fabricateしない）、`coverage`。21 tests / 89
   assertions、lint clean。
2. `cloud-itonami-isic-5820` に `crm.dashboard` を追加: pipeline funnel +
   conversion rate（`kotoba.crm.funnel`経由）+ revenue rollup
   （`kotoba.crm.revrec/recognized-revenue-to-date`の全active
   subscriptionへのground-truth合算、キャッシュ値を信用しない）。新規op
   `:pipeline/dashboard-query`を追加し、RBACで`:sales-manager`のみに制限
   （book-wide rollupは個々のaccount-holderの開示権限より広いため）。
   `Store`プロトコルに`all-accounts`を追加（後方互換）。37 tests /
   137 assertions、lint clean。
3. `cloud-itonami-isic-6201` に `marketing.dashboard` を追加（この
   actor初のaggregate-view）: lead lifecycle funnel + conversion rate、
   campaign送信/consent-rejection rollup（ConsentGovernorが既に生成する
   ledger factの集計、新規trackingの発明ではない）、lead-score
   distribution（`kotoba.crm.leadscore/recompute-score`で毎回
   ground-truth再計算、stored値と食い違うcontactは`:stale-contacts`で
   明示）。新規entitlement `:marketing/view-dashboard`を`policy.cljc`に
   追加し`:marketer`/`:marketing-manager`に付与。実装過程で
   `policy/hold-fact`が`:campaign-id`を落としていた実バグ
   （per-campaign集計を不可能にしていた）を発見・修正。38 tests /
   148 assertions、lint clean。
4. `cloud-itonami-isic-6202`（customer-service、並行セッション実装）は
   本ADRで一切変更していない。

## Consequences

- (+) `kotoba-lang/crm`が3件目・4件目の消費者（5820・6201の両方が
  `funnel`を利用）を得て、技術commons化の設計意図をさらに実証。
- (+) 両actorのdashboardは全て「保存値を信用せず再計算する」という
  fleet共通のground-truth規律を継承（revenue rollupはrevrec再計算、
  lead-score distributionはleadscore再計算）。
- (+) 5820の`Store`プロトコル拡張（`all-accounts`）はcontract test
  parityで検証済み、既存メソッドへの破壊的変更なし。
- (+) 6201の実装過程で発見したledger fact欠落バグ（`:campaign-id`）を
  出荷前に修正——他のactorでも今後同種の集計要求が来た際の教訓として
  記録。
- (+) 両dashboardとも新規opをRBAC制限し、既存の権限体系を拡張する形で
  ガバナンスを一貫させた（設計判断はそれぞれのADR-0001/DESIGN.mdに
  追記）。
- (-) 両dashboardともpoint-in-timeスナップショットのみ。時系列トレンド・
  コホート分析・stage滞留時間は`kotoba.crm.funnel`のR0スコープ外として
  明記、対象外。
- (-) 6202（customer-service）は今回のdashboard深化の対象外のまま
  （別セッションの成果物のため、triageなしに手を入れなかった）。

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| dashboard機能を既存`report.cljc`に統合 | ❌ | `report.cljc`は「1レコードのgoverned disclosure」という別概念。複数レコード横断集計と混ぜるとスコープが曖昧になる |
| funnel分析ロジックを各actor内にprivateで実装 | ❌ | オーナー指示「技術的な共通はkotoba-lang org に」に反する。5820と6201の両方が同じロジックを必要としており、再導出は無駄 |
| exit-stageのreached-countを何らかのデフォルトrank（0や最終stage）で推測 | ❌ | fleet全体の「推測しない」規律に反する。明示的`:reached-stage`facthが無い限り除外が正直な選択 |
| cloud-itonami-isic-6202にも同種dashboardを追加する | ❌（今回は見送り） | 別セッションの成果物であり、triageなしに手を入れることは避けた。必要なら別ADRとして担当セッションまたはオーナー判断で着手 |

## References

- ADR-2607121900、ADR-2607122100
- `kotoba-lang/crm`（`kotoba.crm.funnel`をこのbuildで追加）
- `cloud-itonami-isic-5820/docs/adr/0001-architecture.md`（追記addendum）
- `cloud-itonami-isic-6201/docs/DESIGN.md`（§9 Dashboard追記）

## Verification Notes

- `kotoba-lang/crm`: commit `a648f40c5c272e9bfba79f6b1407bb43f7402d16`、
  push済み。21 tests / 89 assertions、lint clean。
- `cloud-itonami-isic-5820`: commit `7f4ecb4699e9e1b709ebdf5fdd28d86bad45de8a`、
  push済み。37 tests / 137 assertions、lint clean。
- `cloud-itonami-isic-6201`: commit `24fe2f61f7f76a5c12fb145da578fb9721ff4f52`、
  push済み。38 tests / 148 assertions、lint clean。
