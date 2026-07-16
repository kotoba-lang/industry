---
id: adr-2607083200-kami-engine-cae-rename-batch
title: "ADR-2607083200: kotoba-lang/{aero,crash,motor,vphysics,echem,cae-solver} を kami-engine-* へ rename（reverse-domain の根拠が実在しないため sibling prefix 統一で代替）"
status: accepted
doc_type: adr
topic: kotoba-lang-repo-boundaries
authoritative: true
last_verified: 2026-07-08
authoritative_for:
  - 6 車両CAE reduced-order ライブラリの GitHub owner/repo 名の canonical mapping
  - "reverse domain" 命名を採用しない理由（外部規格・団体の実在確認プロセス）
related:
  - 90-docs/adr/2606301100-kotoba-lang-engine-repo-migration.md
  - 90-docs/adr/2606272330-cae-shared-libs-and-seeds.md
  - orgs/kotoba-lang/kami-engine-aero
  - orgs/kotoba-lang/kami-engine-crash
  - orgs/kotoba-lang/kami-engine-motor
  - orgs/kotoba-lang/kami-engine-vphysics
  - orgs/kotoba-lang/kami-engine-echem
  - orgs/kotoba-lang/kami-engine-cae-solver
  - orgs/kotoba-lang/kami-engine-vehicle-designer
supersedes: []
superseded_by: []
---

# ADR-2607083200: kami-engine-* CAE rename batch

- Status: accepted (2026-07-08)
- Deciders: Jun Kawasaki

## Decision

`kotoba-lang/{aero,crash,motor,vphysics,echem,cae-solver}` を
`kotoba-lang/kami-engine-{aero,crash,motor,vphysics,echem,cae-solver}` へ
GitHub rename する。ADR-2606301100 で `kami-cfd → kami-engine-cfd` /
`vehicle-design-actor → kami-engine-vehicle-designer` が既に kami-engine
namespace へ編入されており、この6 repoはその2つの直接の兄弟（同じ
ADR-2606272330 の「共有3 + seed4 + kami-cfd + vehicle-design-actor」体制の
一部）なので、同じ prefix に揃える。

Canonical mapping:

| before | after |
|---|---|
| `kotoba-lang/aero` | `kotoba-lang/kami-engine-aero` |
| `kotoba-lang/crash` | `kotoba-lang/kami-engine-crash` |
| `kotoba-lang/motor` | `kotoba-lang/kami-engine-motor` |
| `kotoba-lang/vphysics` | `kotoba-lang/kami-engine-vphysics` |
| `kotoba-lang/echem` | `kotoba-lang/kami-engine-echem` |
| `kotoba-lang/cae-solver` | `kotoba-lang/kami-engine-cae-solver` |

## Context

オーナーから「com-junkawasaki org 側にある車両CAE 6 repo を kotoba-lang 側へ
reverse domain で管理する」依頼を受けた。調査の結果:

1. **org 移設自体は既に完了済み**だった。6 repoは2026-06-30前後の
   "org reconcile" 作業で `com-junkawasaki/*-clj` → `kotoba-lang/<name>`
   （`-clj` suffix 落とし）へ既に GitHub rename・public 化・west 登録済み。
   `orgs/com-junkawasaki/` 配下に残っていた6ディレクトリはrename前の
   detached HEAD 残骸（untracked、working tree clean、内容は移設先に
   全履歴含有）で、cleanup 対象と確認しdelete した。同時に同型の残骸
   （`langgraph-clj`/`langchain-clj`/`datom-clj` — これらも既に
   `kotoba-lang/{langgraph,langchain,datom}` へ移設済み）も発見・削除した。
2. **"reverse domain" の根拠となる公式規格・団体は実在しない**ことを確認した。
   6 repoの実装（aeroのcomponent buildup Cd法、crashのenergy-balance
   crushモデル、motorのair-gap shear sizing、vphysicsのroad-load式、
   echemのPEM分極曲線モデル、cae-solverのsolver dispatch契約）はいずれも
   README に "clean-room" と明記された自社開発の縮退モデルで、SAE/
   NHTSA/IEC 等の特定規格を実装したものではない。既存の
   `org-<body>-<spec>` 命名規約（W3C/OMG/IETF 等の外部spec移植専用）を
   ここに適用すると、実態と異なる規格準拠の印象を与えるため不採用とした。
3. 代替として、既に同じ ADR-2606301100 で kami-engine namespace へ編入
   済みの直接の兄弟 repo（`kami-engine-cfd` / `kami-engine-vehicle-designer`）
   と同じ prefix に揃えることで、命名の一貫性という当初の目的
   （「ちゃんと管理する」）を満たす。

## Consequences

- west manifest の canonical path は `orgs/kotoba-lang/kami-engine-*` へ移る
  （`manifest/repos.edn` の `:path-overrides` に6件追加、`:extra-projects`
  の6entryを in-place で改名）。
- 6 repo間の相互依存（aero/crash → vphysics, cae-solver; motor/echem →
  cae-solver）の `deps.edn` 座標をすべて新名称へ更新。`kami-engine-vehicle-designer`
  の `deps.edn` も同時に修正（既存の陳腐化した `com-junkawasaki/*-clj`
  座標／`:local/root` 相対パスが、2026-07-02 の別修正 commit 5203f45
  経由で `:git/url`+`:sha` 方式に切り替わっていたのを踏まえ、そのURL/
  座標名だけを新名称へ更新。SHAはrenameで変わらないため不変）。
- 旧 GitHub URL は GitHub redirect として残る（ADR-2606301100 と同じ扱い）。
- `kotoba-lang/cad`（成熟度スコアリング層。ジオメトリカーネルではない）と
  `kotoba-lang/brep`（実際のBREPカーネル）の命名が紛らわしい前例
  （brep README 参照）があるため、今後この6 repo 名が「kami-engine-*」に
  揃ったことで、kami-engine namespace = ゲームエンジン/車両設計サブシステム
  群、という理解がしやすくなる。

## 却下案

- **reverse-domain 命名（例: `org-sae-aero`）**: 上記の通り、外部規格への
  準拠を実装していないため、規格団体名を repo 名に冠すると実態を誤認させる。
- **rename せず現状維持**: kami-cfd/vehicle-design-actor という直接の兄弟が
  既に kami-engine-* へ編入済みであり、この6 repoだけ取り残すと
  namespace の一貫性が崩れる。
