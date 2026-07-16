# ADR-2607161800: animeka — standalone BMC/Lean-Loop 成熟度反復トラッキングの開始

## Status

Accepted, implemented（2026-07-16）。Iteration 1 は `gftdcojp/ai-gftd-animeka` main
`186e2115`（`docs/bmc-lean-loop-log.md`）。

## Context

オーナー指示（/loop「成熟度を向上」10分周期）を受けた反復改善ループの立ち上げ。
CLAUDE.md の BMC 節の必須確認を実施した結果:

- 共有 BMC システム（`70-tools/bmc/`、ADR-2607021500/1600/1700）の登録プロダクトは
  gftdcojp 11 プロダクト（`maturity-scores.md` as-of 2026-07-15 は 12 行）で、
  **animeka は登録外**。base datoms への新規登録は人間レビューを要する決定で
  routine/agent が自動でやってはいけない（ADR-2607021600「書き換え禁止」）。
- 登録外プロダクトの先例は **standalone パターン**: `local-murakumo`
  （ADR-2607121600）、`net-babiniku`（ADR-2607122300）— 対象 repo 自身に
  `docs/bmc-lean-loop-log.md` を append-only で積み、superproject に開始決定 ADR を
  置く。animeka は独自 ADR 番号体系を持たないため local mirror は不要。
- 付随して実測した tooling 劣化: `GFTD_ROOT=$PWD nbb 70-tools/bmc/bin/gftd.cljs
  products` が nbb v1.2.x + Node 26 環境で `Could not find namespace: babashka.fs`
  で失敗する（`maturity-scores.md` の生成は cloud routine 側で継続中のため
  スコアは読める）。修理は本 ADR のスコープ外の follow-up として記録のみ。

## Decision

1. **animeka の成熟度反復は standalone パターンで追跡する**（共有 BMC への登録は
   しない — するなら人間レビューの別決定）。正本は
   `ai-gftd-animeka/docs/bmc-lean-loop-log.md`、`## Iteration N — <date>` を
   append-only で積む。既存 iteration の編集・削除禁止、**捏造ゼロ**（未計測値は
   「unknown」と明記。outcome/metric を推定で埋めない）。
2. **Iteration 1（棚卸し）の要点**: active 8 作品（正規化 2026-07-17 済み)、graph
   層 co-scientist ハーネス常設（ADR-2607161730）、fleet 実測済み — 一方で
   validation=0、launched=unknown（animeka.gftd.ai が実測 curl timeout、公開到達性
   未確認）、revenue=0。**H1: published episode 1 本を実カット付き end-to-end で
   視聴可能にすれば launched/grounding が実測で上がり validation 計測が可能になる**。
3. 反復の駆動はセッションの /loop（10分周期）。各 iteration は「小さく・検証可能・
   実測ベース」の増分のみをログに追記する。

## Consequences

- (+) 既存共有システムと衝突する独自ログの乱立（ADR-2607124500 の実害パターン）を
  避けつつ、animeka の成熟度前進が監査可能な形で残る。
- (+) H1 が「アニメを高品質に生成する」という製品目標と直結しており、ReAct 品質
  ハーネス（ADR-2607161730）の live 検証（vision judge 実測）と同じ経路で進められる。
- (−) 10分周期のセッションループはセッション終了で止まる。永続化するなら cloud
  routine 化（/schedule）が別途必要 — 現時点ではオーナーの明示があるまでしない
  （`itonami-react-growth-hourly` 等の既存 routine の `--product` リストに animeka を
  足すのも base 登録相当の判断なのでしない）。
- (−) `gftd` CLI の nbb 実行が壊れている件は未修理（follow-up。cloud routine は
  自己修復して動いている可能性が高く、壊れているのはローカル実行経路）。

## References

- `orgs/gftdcojp/ai-gftd-animeka/docs/bmc-lean-loop-log.md`（main `186e2115`）
- 先例: `90-docs/adr/2607121600-*`（local-murakumo）、`90-docs/adr/2607122300-*`
  （net-babiniku）、実害事例: ADR-2607124500
- 共有システム: ADR-2607021500/1600/1700、`90-docs/business/maturity-scores.md`
- 関連: ADR-2607161730（animeka co-scientist ReAct quality harness）
