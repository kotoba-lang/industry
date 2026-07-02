# ADR-2607022600: sales/marketing ファネル・プロセス層（gftd.funnel）

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

BMC framework（ADR-2607021600）は canvas / gate 評価器（ADR-2607022100）/ ReAct
loop / collect まで揃い、「各 product の最もリスキーな**収益仮説**を測って循環させる」
段階に到達した。次段の要求（オーナー指示 2026-07-02）: 「次の product を **sales
marketing process** まで進められる段階に」。gate は単一仮説の検証で、獲得→収益の
**ファネル全体を回す営業/マーケの motion** は未モデル化だった。

## Decision

`70-tools/bmc/src/gftd/funnel.cljc` を **gate の上位層**として追加（gate と同型の
pure .cljc engine）:

1. **funnel-spec = 段階の順序列**（AARRR 型）。各 product に
   `[{:key :label :metric [path] :benchmark r} ...]`。連続する 2 段が転換ステップを
   なし、`:benchmark` は「前段→当段」の期待転換率（業界水準の下限目安）。計測値は
   product の metrics edn（gate/collect と同じ emitter 経路）から取る。

2. **evaluate-funnel** → 各段 count・各ステップの転換率・benchmark との gap・
   **bottleneck**（実測済かつ benchmark を最も下回るステップ）・**未計測段**。
   未計測段があっても false bottleneck を出さない（gate の
   `:needs-when-unmeasurable` と同じ「測れない＝準備項目」思想）。

3. **proposals**（governor-ready、既存 governor をそのまま通る）:
   - bottleneck → **channels ブロックに GTM アクション**（段別 playbook:
     acquisition=landing CTA/価格 A/B・SEO・招待導線 / activation=onboarding 摩擦削減・
     価格明確化 / revenue=trial→paid nudge・tier 見直し）
   - funnel snapshot → **metrics ブロック**に運用指標として記録
   - 未計測段 → **solution ブロックに計器 to-do**（funnel emitter で当段を出力）

4. **CLI**: `<cli> funnel show [--product P]`（現況表示）/
   `funnel analyze [--product P|--all]`（bottleneck→GTM 提案を governor 経由で ledger へ、
   react と同じく dedup 拒否で exit しない＝schedule 反復に耐える）。

5. **最初の適用先 = net-kotobase**（billing 稼働済・GTM doc 既存）。funnel-spec =
   landing 訪問 → signup → checkout 開始 → paid(active sub)。実測（`funnel show`）:
   landing 248 → signup 0（転換 0% < 目標 3%）で **bottleneck = 獲得段**と正しく特定。
   apex/manimani/itonami/club-shinshi にも spec を定義。

## Consequences

- (+) 「sales marketing process」= observe(funnel metrics)→think(bottleneck 特定)→
  act(段別 GTM アクションを governor 経由 ledger)→audit の kaizen サイクルが、gate と
  同じ actor パターンで全 product 共通に回せる。捏造ゼロ（実測 emitter 値のみ）。
- (+) gate（1 仮説の validate）と funnel（獲得ファネルの growth）が分離・補完。
  net-kotobase の現 bottleneck が「acquisition（訪問はあるが signup 0）」と明示された。
- 検証: `bb 70-tools/bmc/run-tests.bb` = 11 tests / 51 assertions green（funnel
  evaluation・bottleneck・missing・proposals cycle を追加）。
- (−) follow-up: (a) 各 product の **funnel emitter**（signup/checkout/activation の
  実計測）を product repo に配線（net-kotobase は signup→checkout 導線 + telemetry）、
  (b) daily routine（bmc-business-operate-daily）に `funnel analyze` を追加して
  獲得 motion も日次循環に載せる、(c) bmc-clj への split（ADR-2607021600 と同じ）。

## References

- ADR-2607021600（BMC CLI / ReAct loop）/ ADR-2607022100（gate 評価器）
- ADR-2607022200（各 product の gate emitter 経路）
- `70-tools/bmc/src/gftd/funnel.cljc` / `70-tools/bmc/README.md`
- CLAUDE.md「Actors」節（proposal ⊣ governor ⊣ 不変台帳）
