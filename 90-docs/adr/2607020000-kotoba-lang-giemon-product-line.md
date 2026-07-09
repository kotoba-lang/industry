# ADR-2607020000: Giemon 製品ラインを kotoba-lang/giemon へ集約

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2605142200（Giemon ブランド確立）と ADR-2605142300（Giemon Kaigo 介護応用）は
3D ビューア WASM 実装として `40-engine/kami-engine/kami-app-giemon/src/lib.rs` を
参照していたが、実体は `orgs/kotoba-lang/kami-engine` の working tree に
`kami-app-giemon` Rust crate（procedural mesh 生成 + `kami-genesis`
Articulation3dConfig によるアーム/人型/UGV 構築、1457 行）として存在するのみで、
`.cljc kotoba` 側の正本は無かった。

一方 `orgs/kotoba-lang/kami-contracts` の provider catalog
（`provider_catalog.edn` / `provider_boundaries.edn` /
`provider_split_repos.edn`）は `kami-app-giemon` / `kami-app-giemon-factory`
を汎用デモ群 `:app-fixtures`（`:split-to
"orgs/kotoba-lang/kami-app-fixtures"`、rule
`:convert-to-edn-fixtures-or-delete`）に分類していた。Giemon は
`ai-gftd-apps-gftdcojp` 側で 2 ADR を持つ実製品ブランドであり、使い捨てデモと
同列の分類は実態に合わない。

また `kotoba-lang/robotics`（ADR-2607011000）は Giemon 専用ではなく、
cloud-itonami の 26 ISIC vertical blueprint 全てが `:robotics` 必須
capability として依存する汎用 mission/action/safety-governor lib であり、
Giemon 固有のキネマティクス/製品データを robotics にリネーム/統合するのは
その汎用性を壊す。

## Decision

1. **新規 `kotoba-lang/giemon`**（public, Apache-2.0, 純 `.cljc`）を作成し、
   Giemon 製品固有の正本を集約する:
   - `kotoba.giemon` — Otete/Hitogata/Caterpillar 製品レジストリ（ADR-2605142200
     の製品表をデータ化）。
   - `kotoba.giemon.kinematics` / `kotoba.giemon.arm` — Rodrigues 回転による
     関節チェーン forward kinematics + トルク余裕検証（`giemon_arm6` fixture の
     ヘッダコメントが要求する「設計要求(`:joint/limit :effort`)と実機定格
     (`:joint/actuator :cont-nm`)を単一箇所で検証する」を実装）。
   - `kotoba.giemon.viewer` — `giemon.gftd.ai/viewer.htm` 3D ビューアの
     scene-IR 契約（ADR-2607010000 の kami.app 移行パターンにおける
     「app logic を .cljc へ」の正本側）。
   - `kotoba.giemon.governor` — `kotoba-lang/robotics` の mission/action/gate
     契約を Giemon Kaigo（ADR-2605142300）向けに薄くラップ（安全等級既定値、
     転倒検知アラートは常に `:safety-critical`）。
   - `fixtures/giemon_arm6/{giemon_arm6.edn,giemon_arm6.urdf}` を
     `kotoba-lang/kami-engine` から移設（Otete アームの正本。Hitogata/
     Caterpillar は ADR-2605142200 時点で `:in-design` のため articulation
     fixture 無し）。
   - 依存: `io.github.kotoba-lang/robotics`（mission/action/governor）+
     `html`/`css`（operator console）。robotics 自体は変更しない。

2. **kami-contracts provider catalog の再分類**: `kami-app-giemon` /
   `kami-app-giemon-factory` を `:app-fixtures` の `:legacy-crates` から除外し、
   `:split-to "orgs/kotoba-lang/giemon"` の専用 family/kind
   （authority = `orgs/kotoba-lang/giemon`、
   dependency-repos = `["orgs/kotoba-lang/robotics"]`）を追加する。対象:
   `provider_catalog.edn` / `provider_boundaries.edn` /
   `provider_split_repos.edn`（`orgs/kotoba-lang/kami-contracts`）+
   `90-docs/migration/kami-provider-catalog.edn`（superproject migration
   ledger）。

3. **manifest 登録**: `manifest/repos.edn` に `orgs/kotoba-lang/giemon` を
   追加し `bb scripts/gen-west-manifest.bb` で `manifest/west.yml` を再生成。

## Consequences

- (+) Giemon 固有のキネマティクス/製品メタデータと、cloud-itonami 全業種が
  依存する汎用 robotics governor 契約が分離される。
- (+) provider catalog が Giemon の実態（実製品、2 ADR あり）を反映する。
- (−) `kami-app-giemon` の実際の procedural mesh 生成コード（Rust,
  wgpu/kami-render 依存）は本 ADR の範囲では移植していない。
  `kotoba.giemon.viewer` の scene-IR が、将来その Rust コードを薄い render
  adapter へ縮小する際の入力契約になる（ADR-2607010000 の
  authority/adapter 分離）。
- (−) `kami-app-giemon` Rust crate 自体の削除/実装縮小は
  `orgs/kotoba-lang/kami-engine` 側の別作業（本 ADR 時点で該当 crate は
  working tree 上で未コミット削除済み — 本 ADR はその作業に関与しない）。

## References

- ADR-2605142200 — Giemon オープンハードウェアブランド確立
- ADR-2605142300 — Giemon Kaigo 介護応用プラットフォーム
- ADR-2607011000 — cloud-itonami の robotics 前提設計（`kotoba-lang/robotics`
  の汎用性の根拠）
- ADR-2607010000 — runtime・SDK・OS substrate を kotoba-only 正本へ寄せる
  （`kami.app` 移行パターン）
- `orgs/kotoba-lang/kami-contracts/resources/kami/provider_catalog.edn`
- `90-docs/migration/kami-provider-catalog.edn`
