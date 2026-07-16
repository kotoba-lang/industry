---
id: adr-2607087500-kami-genesis-shugyo-nvidia-isaac-rename
title: "ADR-2607087500: kotoba-lang/kami-genesis を com-nvidia-isaac-sim へ、kotoba-lang/kami-shugyo を com-nvidia-isaac-lab へrename — FMI/ISO 8373/ISO 10218ではなくNVIDIA vendor-API-compat基準を適用"
status: accepted
doc_type: adr
topic: kotoba-lang-cad-cam-integration
authoritative: true
last_verified: 2026-07-09
authoritative_for:
  - kami-genesis→com-nvidia-isaac-sim、kami-shugyo→com-nvidia-isaac-lab のrename決定と根拠
  - FMI/ISO 8373/ISO 10218がこの2件に適用されない理由
  - com-nvidia-isaac（既存の小さいschema-CRUD actor）とcom-nvidia-isaac-sim/-labの区別
related:
  - orgs/kotoba-lang/com-nvidia-isaac-sim
  - orgs/kotoba-lang/com-nvidia-isaac-lab
supersedes: []
superseded_by: []
---

# ADR-2607087500: kami-genesis/kami-shugyo → com-nvidia-isaac-sim/-lab rename

- Status: accepted (2026-07-09)
- Deciders: Jun Kawasaki

## Decision

`kotoba-lang/kami-genesis` を `kotoba-lang/com-nvidia-isaac-sim` へ、
`kotoba-lang/kami-shugyo` を `kotoba-lang/com-nvidia-isaac-lab` へGitHub
renameする。

- **kami-genesis → com-nvidia-isaac-sim**: クリーンルームの
  `isaacsim.core.api`（`World`/`Articulation`）+ PhysX 5（空間ベクトル
  剛体力学、GJK/EPA接触判定）API-surface facade。NVIDIA公式ドキュメント
  （`docs.isaacsim.omniverse.nvidia.com`）で`World`/`Articulation`/PhysX 5
  GPU dynamicsの実在を確認した。
- **kami-shugyo → com-nvidia-isaac-lab**: `isaaclab.envs.ManagerBasedRLEnv`
  （manager-based MDP RL環境）compat target。NVIDIA公式ドキュメント
  （`isaac-sim.github.io/IsaacLab`）で実在・仕様を確認した。

## Context

前セッションでFMI・ISO 8373（産業用ロボット語彙）・ISO 10218（産業用
ロボット安全要求事項）・民間団体規格の適用可能性を調査した際に発見した
`kami-genesis`/`kami-shugyo`について、続きの調査・判断を行った。

### なぜFMI/ISO 8373/ISO 10218は適用されないか

- **FMI**（Functional Mock-up Interface、co-simulation規格）:
  `kami-genesis`/`kami-shugyo`は`.fmu`ファイルのimport/exportや
  `modelDescription.xml`パースを一切実装していない。FMIは実装できる
  技術インターフェースだが、今回この2repoはそれを実装対象にしていない
  ——「実装してからrename」の原則（ADR-2607084200のbrep/STEPと同型）に
  従えば、FMI適用は新規スコープであり今回のrename根拠にはできない。
- **ISO 8373**（ロボット工学の語彙・定義規格）: これは実装可能な
  フォーマット/プロトコルではなく、純粋な用語集・定義の標準である
  （「robot」「end-effector」等の定義）。ソフトウェアとして「実装」
  する対象が存在しないため、reverse-domain命名の根拠にならない。
- **ISO 10218**（産業用ロボットの安全設計要求事項）: 認証・コンプライ
  アンス規格であり、リスクアセスメント・ハードウェア安全設計に関する
  要求事項の集合。ソフトウェアAPIとして実装するものではないため、
  同様に根拠にならない。

### なぜcom-nvidia-*（vendor-API-compat）が正しい基準か

`kami-genesis`/`kami-shugyo`のREADMEは元々「`isaacsim.core.api`/PhysX 5
API-surface facade」「`isaaclab.envs.ManagerBasedRLEnv` API-compat
target」と明記しており、WebSearchでNVIDIA公式ドキュメントに対して
この主張を検証した（記憶や推測でなく実際にドキュメントを確認）。
これは`ADR-2607041500`（etzhayyim `*-compat`カタログのkotoba-lang移行、
1,027repo、`com-nvidia-isaac`/`com-nvidia-cosmos`/`com-nvidia-drive`/
`com-nvidia-nvml`のpilot batchを含む）が確立した「vendor固有APIサー
フェスに対するclean-room実装はcom-<vendor>-*」という基準に正確に
一致する。

### com-nvidia-isaac（既存）との違い（衝突ではない）

`kotoba-lang/com-nvidia-isaac`は既に存在するが、これは
`etzhayyim/root/20-actors/nvidia_isaac-compat`から移行した**小さい
schema-CRUD actor**（kotoba Datom log上のCRUD + validationのみ、
OpenAPI由来）であり、`kami-genesis`（大規模な物理エンジン: RNEA/
CRBA/LDLᵀ・空間ベクトル代数・GJK/EPA・IK・LQR・熱FDM）や
`kami-shugyo`（RL環境フレームワーク: 8サブモジュール、約1480行）とは
**スコープも実体も別物**——ADR-2607041500自身がこの2つを「distinct,
larger」と明記して区別している。同名衝突を避けるため、NVIDIAの実際の
製品名（Isaac Sim / Isaac Lab）を反映した`-sim`/`-lab`サフィックスで
分離した。

## Consequences

- ローカルcheckoutディレクトリ移動 + `git remote set-url`。
  Clojureネームスペース（`genesis.*`/`shugyo.*`）は変更不要
  （com-nvidia-isaac-sim: 110テスト/2463アサーション、
  com-nvidia-isaac-lab: 46テスト/867アサーション、共に既存のまま
  green）。
- 依存repoなし（`orgs/*/*/deps.edn`を全走査して確認済み。唯一ヒットした
  `orgs/etzhayyim/root/deps.edn`はADRレジストリのEDNデータファイルで
  あり`tools.deps`依存宣言ではなかった——偽陽性）。
- `manifest/repos.edn`の`:path-overrides`に2エントリ追加、
  `:extra-projects`の該当2行を新名称へ更新。`manifest/west.yml`は
  `nbb scripts/gen-west-manifest.cljs --entry com-nvidia-isaac-sim,
  com-nvidia-isaac-lab`で新entry生成（サーバ側pin検証green）後、
  旧2entryを手動削除（`--entry`は追加のみのため）——差分は
  10行追加/10行削除の最小diffを維持。
- README冒頭にrename通知を追加（NVIDIA公式APIドキュメントへの
  実リンク付き）。

## 却下案

- **`com-nvidia-isaac`への統合/上書き**: スコープが全く異なる
  （小さいCRUD actor vs. 大規模物理エンジン/RLフレームワーク）ため、
  同名repoへの統合は実体を誤魔化すことになり不採用。
- **FMI/ISO 8373/ISO 10218ベースのreverse-domain命名**: 上記の通り、
  いずれも「実装できるインターフェース仕様」ではないか、今回の2repoが
  実際に実装していない対象であるため不採用。将来的にkami-genesisが
  FMU export/importを実装すれば、その時点で改めてorg-fmi-*相当の
  reverse-domain命名を検討する余地はあるが、それは新規スコープであり
  本ADRの対象外。
