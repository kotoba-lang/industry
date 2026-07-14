# ADR-2607122200: 衣食住 scaffold 起票 — ISIC 1010/1311/1410/4100 blueprint 衛星 + registry :blueprint 昇格

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-{1010,1311,1410,4100}`（新規）,
`orgs/kotoba-lang/industry`（registry 昇格）, `manifest/west.yml`（industry pin 前進）

## Context

ADR-2607122100（労働解放 SD モデル）の再計算 Track A は、衣食住を「生産着手」
でなく「scaffold 起票」として L0（gate 無し・最安）に前倒しした。ADR-2607121000 も
C10-12 食品・4100 建築の scaffold 欠落を follow-up として明記していた。

実態調査（2026-07-12）: registry には食品（101-120 group + 1010 等 class）・
繊維衣類（131-143 + 1311/1410 等）・4100 のエントリ自体は `:spec` で存在したが、
`:repo` は **存在しない旧 gftdcojp C/F-prefix repo への stale URL**
（`gftdcojp/cloud-itonami-C1010` 等、GitHub 上に実在せず）で、blueprint 衛星 repo は
ISIC 10-14 / 4100 に **1 本も無かった**。また `maturity-of` の `:blueprint` tier
（"blueprint repo published"）は fleet-wide 0 件だった。

## Decision

1. **代表 class 4 本の blueprint 衛星を発行**（cloud-itonami org, public,
   AGPL-3.0-or-later, maturity `:blueprint`）:
   - `cloud-itonami-isic-1010` — Community Meat Processing（食）⊣ `:meat-processing-governor`
   - `cloud-itonami-isic-1311` — Community Textile Spinning（衣・上流）⊣ `:textile-spinning-governor`
   - `cloud-itonami-isic-1410` — Community Apparel Manufacturing（衣・下流）⊣ `:apparel-governor`
   - `cloud-itonami-isic-4100` — Community Building Construction（住）⊣ `:building-construction-governor`
   governor keyword は 4 つとも fleet-wide 一意（grep 検証済み）。各 README は
   honest-default で「actor 未実装・wave-3 robotics-gated（ADR-2607011000）・
   Track A 弾込め（ADR-2607122100）」を明記。中身は blueprint.edn + README +
   LICENSE + community files のみ（src/test を持たない = :implemented を僭称しない）。

2. **registry 昇格**（`kotoba-lang/industry`）: 4 entry を `:spec` → `:blueprint`、
   stale URL を実在衛星へ差し替え、`:business-id` を `cloud-itonami-isic-<code>`
    慣例へ、operating-states 先頭の placeholder `:spec` を `:intake` へ。
   `maturity-summary` の `:blueprint` tier 36 → 40（**fleet 初の明示的
   :spec→:blueprint 昇格**。従来の 36 は :repo 推論による暗黙 tier）。
   テスト 15 tests / 933 assertions green、clj-kondo 0 errors。
   main 着地: `6ca68c92`（feature branch → サーバ側マージ、branch 削除済み）。

3. **west pin 前進**: industry `5ffdc774` → `6ca68c92`（API single-entry commit
   `2bf7896d`、blob SHA 楽観ロック、pin == 子 repo main tip 検証済み、純前進）。

4. **スコープ外（明示）**: actor 実装・robot 統合・EMS。これらは gate.robotics
   開門後（Track C）。1010/1311/1410/4100 以外の食品・繊維 class 群
   （102-108/110/1312/1391 等）の衛星化は、この 4 本のパターン複製として
   需要が見えた時点で行う。

## Consequences

- (+) 衣食住 3 領域すべてに実在する blueprint 衛星が最低 1 本ずつ立ち、
  ADR-2607121000 の scaffold 欠落 follow-up のうち食・住 + 衣を解消。
- (+) `:blueprint` tier が実運用に入り、`:spec →: blueprint → :implemented` の
  maturity ladder が ISIC 側でも全段実在するようになった。
- (+) SD モデル（ADR-2607122100）の `enabler.ishokuju-scaffolds` が :done になり、
  Track A の先頭が treasury 完遂 + wasm 工場に絞られた。
- (−) blueprint は事業仕様であって収益でも実装でもない。wave-0 の R1 断点
  （外部テナント/実収益）が最優先であることは変わらない。
- (−) 2100 医薬・6201/6311 自業種の scaffold 欠落は未解消のまま（別 batch）。

## Artifacts

- https://github.com/cloud-itonami/cloud-itonami-isic-1010 （initial `40cc7d2`）
- https://github.com/cloud-itonami/cloud-itonami-isic-1311 （initial `974470e`）
- https://github.com/cloud-itonami/cloud-itonami-isic-1410 （initial `e1eb811`）
- https://github.com/cloud-itonami/cloud-itonami-isic-4100 （initial `cff24d7`）
- `kotoba-lang/industry` main `6ca68c92` / superproject west.yml `2bf7896d`
- 本 ADR とペアの `.edn`

## References

- ADR-2607122100（労働解放 SD モデル — Track A L0 の根拠）
- ADR-2607121000（逆トポソート 5-wave — scaffold 欠落 follow-up の出典）
- ADR-2607011000（robotics premise — wave-3 gate と governor 契約）
- ADR-2607012100（blueprint 衛星の cloud-itonami org 帰属）
