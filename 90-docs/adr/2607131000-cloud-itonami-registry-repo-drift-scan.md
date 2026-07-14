# ADR-2607131000: registry ⇄ 実 repo 乖離スキャン — 検出方法論と 5320/7830 の :implemented 同期

**Status**: accepted
**Date**: 2026-07-13
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/industry`, `orgs/cloud-itonami/cloud-itonami-isic-{5320,7830}`（checkout 追加）, `manifest/west.yml`

## Context

Loop tick 15 で isco-3521 の「blueprint repo は実在するのに registry は
`:spec`」という乖離を発見・修正した。この型の乖離が全域に何件あるかを
tick 17 で系統的にスキャンした（cloud-itonami org の全 647 repo リスト ⇄
ISIC 625 entry / ISCO 436 entry の突合）。

## 経過と教訓（正直な記録）

1. **初回スキャンは stale checkout に対して実行され、17 件の偽乾離を報告
   した。** 現 main では fleet が既に 15 件を「explicit `:maturity` を外し
   `:repo` から `maturity-of` が `:blueprint` を derive する」方式で同期済み
   だった。stale 前提の一括編集は **`{:id ...}(.*?):maturity :spec` の
   非貪欲 DOTALL regex が entry 境界を跨いで別 entry を汚染**し、テストの
   count-pin（`:blueprint` 期待値）が即座に検出 → **着地せず worktree ごと
   破棄**。count-pin テストは煩わしいが、まさにこの事故を止めた。
2. **現 main での再スキャン（entry block 分割方式）の真の乾離は 2 件のみ**:
   5320（courier/delivery）と 7830（human-resources provision）— 両方とも
   src+test を持つ実装 actor が GitHub に実在するのに registry は `:repo`
   リンク無しの `:spec` のままだった。

## Decision

1. **同期は実測してから**: 5320/7830 を `orgs/cloud-itonami/` に checkout
   し、**テストを実走**（5320: 40 tests/200 assertions green、7830: 33/115
   green）した上で `:implemented` + 実 repo リンクに同期。`:spec` からの
   直行昇格（blueprint を経ない）は既存 fleet 慣例（4620/2910 等）と同型。
   industry main `c3999a72`、implemented 144 → 146。pin 前進 `67ceeb11`。
2. **スキャン方法論を固定**: 乾離検出は (a) **必ず現 main の registry**
   （stale checkout 禁止 — fetch/ff してから）、(b) **entry block 分割**
   （`(?=\{:id ")` split）で自 entry 内のみ判定、(c) explicit `:maturity`
   が無い entry は `maturity-of` の derive 規則（`:repo` → `:blueprint`）
   を踏まえて判定する。
3. ISCO 側は乾離ゼロ（loop の各 batch が registry 同時更新で進んだため）。
4. 残課題（スコープ外）: `:spec` entry の stale URL 243 件（旧 gftdcojp
   C/F-prefix の aspirational link 群）。昇格時に個別修正する現行運用を
   維持し、一括 null 化はしない（大量 diff の割に情報を減らすだけ）。

## Consequences

- (+) registry の成熟度分布が実 repo 状態と一致（真の乾離 0 件に）。
- (+) 「テストを走らせてから :implemented を名乗る」を drift-sync にも適用。
- (−) 5320/7830 の checkout 追加でローカル satellite が 2 増（west 対象外の
  standalone 慣例どおり）。

## References

- ADR-2607122700 Addendum 5（isco-3521 の初出）/ Addendum 4（stale snapshot 教訓）
- `kotoba.industry/maturity-of`（derive 規則の正本）
