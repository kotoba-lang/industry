# ADR-2607122600: scaffold batch #2 — ISIC 2100 医薬 blueprint 衛星（ADR-2607121000 follow-up の完了）

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-2100`（新規）, `orgs/kotoba-lang/industry`, `manifest/west.yml`

## Context

ADR-2607121000 の constraint が挙げた scaffold 欠落 follow-up は
「6201/6311 自業種・C10-12 食品・2100 医薬・4100 建築」の 4 件だった。
食/衣/住は ADR-2607122200（batch #1）で解消。tick 3 の実態調査で、
**6201/6311 自業種は fleet が既に `:implemented` で完了済み**（実 repo あり、
marketing-automation / market-data への narrowing note 付き）と判明し、
残る gap は **2100 医薬のみ**（stale な `gftdcojp/cloud-itonami-C2100` URL、
`:spec`、衛星ゼロ）だった。

## Decision

30 分 loop「成熟度, coverage を向上」の tick 3 として実施:

1. **`cloud-itonami/cloud-itonami-isic-2100`** — Community Pharmaceutical
   Manufacturing（public, AGPL-3.0, maturity `:blueprint`）を発行。
   `:pharma-manufacturing-governor`（fleet 一意）。honest-default: actor 未実装を
   明記、wave-3 robotics-gated、**batch release と pharmacovigilance 関連は常に
   `:safety-critical`**（製造 wave 中で最も厳しい safety posture — LLM が
   batch release することは永久にない）。
2. **registry 昇格**: 2100 `:spec → :blueprint`、stale URL を実在衛星へ差し替え。
   maturity-summary `:blueprint` 40 → 41。テスト 15 tests / 934 assertions green、
   clj-kondo clean。main `7e8a6027`（サーバ側マージ、branch 削除済み）。
3. **pin 前進**: industry `e8cbd563 → 7e8a6027`（API single-entry `ca29a172`、
   compare ahead 2 / behind 0）。共有 checkout FF 済み。

これで **ADR-2607121000 の scaffold 欠落 follow-up 4 件は全て解消**
（batch #1 = 食衣住、fleet = 自業種、batch #2 = 医薬）。

## Consequences

- (+) Wave 3 の主要 4 領域（食・衣・住 + 医薬）が全て blueprint 段階に到達。
  gate.robotics 開門時の弾は装填済み。
- (+) `:blueprint` tier 41 件 — maturity ladder の中間段が実運用として定着。
- (−) 2100 の actor 実装は wave-3 gate + GMP/薬機法クラスの per-法域 spec-basis
  調査が前提。blueprint はその器で、中身の法域調査は着手時の個別 ADR。

## Artifacts

- https://github.com/cloud-itonami/cloud-itonami-isic-2100
- `kotoba-lang/industry` main `7e8a6027` / superproject west.yml `ca29a172`
- 本 ADR とペアの `.edn`

## References

- ADR-2607121000（follow-up リストの出典）/ ADR-2607122200（batch #1 パターン）
- ADR-2607011000（robotics premise / :safety-critical 規律）
