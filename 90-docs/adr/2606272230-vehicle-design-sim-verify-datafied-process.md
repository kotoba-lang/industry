---
id: adr-2606272230-vehicle-design-sim-verify-datafied-process
title: "ADR-2606272230: vehicle-design-actor を kami-engine の Isaac/CAE シムと CAM/4D 組立順に橋渡し。(a) released spec を kami-genesis(isaacsim互換)/kami-cae で構造・衝突検証し kami-shugyo 流 per-env DR で sim2real マージンを取る、(b) BOM→CAM(G-code)→4D 組立順を giemon-factory :seq パターンで生成し全て kotoba Datom ログに datom 化する"
status: proposed
doc_type: adr
topic: actor-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - vehicle-design-actor の released spec を後段で (a) シム検証 / (b) 製造工程化する2ステージ拡張の設計判断
  - 設計→Isaac/CAE シム検証→BOM/CAM/4D 組立順までを1グラフ1アクターで通し、検証も製造も datom 化する決定
  - 物理閉包(PhysicsGovernor)とは独立した第2の検閲器(SimGovernor)を置き、構造不合格を第2の MRC とする判断
  - BEV/FCEV で組立工程(:seq)がエネルギー系のところだけ分岐する工程モデル
related:
  - orgs/com-junkawasaki/vehicle-design-actor                       # 本 ADR の actor(拡張対象)
  - orgs/com-junkawasaki/vehicle-design-actor/docs/adr/0002-sim-verify-and-datafied-process.md  # actor-local ADR(詳細)
  - 90-docs/adr/2606272130-vehicle-design-actor-bev-fcev.md         # 前段(clean-sheet 設計アクター本体)
  - orgs/com-junkawasaki/kami-engine/kami-genesis                   # isaacsim.core.api 互換ソルバ(構造/多環境)
  - orgs/com-junkawasaki/kami-engine/kami-cae                       # CAE 解析面
  - orgs/com-junkawasaki/kami-engine/kami-shugyo                    # isaaclab RL + per-env ドメインランダム化(sim2real)
  - orgs/com-junkawasaki/kami-engine/kami-cam                       # CAM(toolpath/G-code/tool/stock)
  - orgs/com-junkawasaki/kami-engine/kami-app-giemon-factory        # 4D 組立順(construction.order.json :seq)パターン
  - orgs/com-junkawasaki/kami-engine/90-docs/adr/0034-isaac-compat-stack-maturation.md  # Isaac-compat 成熟(clean-room 不変条件)
  - orgs/etzhayyim/root/20-actors/nvidia_isaac-compat              # Datom ログ上の Isaac 互換 actor(配線先)
  - orgs/etzhayyim/root/20-actors/nvidia_cosmos-compat             # Datom ログ上の Cosmos/WFM 互換 actor(配線先)
supersedes: []
superseded_by: []
---

# ADR-2606272230: vehicle-design-actor — (a) Isaac/CAE シム検証 + (b) 全 datom 化された製造工程

- Status: proposed (2026-06-27)
- 前段: ADR-2606272130（clean-sheet 設計アクター本体）。詳細は actor-local
  [`docs/adr/0002-sim-verify-and-datafied-process.md`](../../orgs/com-junkawasaki/vehicle-design-actor/docs/adr/0002-sim-verify-and-datafied-process.md)。

## 課題

ユーザー問い「kami-engine の cosmos/isaac sim でのシミュレーションは? 組み立て工程も
全てデータ化されている?」に対し、シム基盤（kami-genesis/kami-shugyo/nvidia_*-compat）
と CAM（kami-cam）・4D 組立順（giemon-factory）は**存在するが設計アクターから未接続**
だった。物理的に閉じた spec を (a) シムで検証し、(b) 製造工程まで datom 化する橋が要る。

## 決定

released spec の後段に2ステージを足し、StateGraph を伸ばす（信頼境界はノード境界）:

- **(a) SimGovernor**（`vdesign.simverify`）: kami-genesis(`isaacsim.core.api`)/kami-cae
  の語彙で 20g クラッシュ構造 SF・パッケージ干渉・車軸荷重を評価し、**kami-shugyo 流
  per-env DR**（質量±8%/構造±6%/パルス±10%, 16 env, seeded で再現可能）で worst-case
  を取り sim2real マージンを保証。構造不合格は物理閉包済みでも `:rejected` に降格＝
  **第2の MRC**。clean-room（NVIDIA 非リンク、ADR-0034 と同じ不変条件）。
- **(b) ProcessPlanner**（`vdesign.process`）: BOM 展開 → kami-cam 語彙の CAM（実 G-code
  `G21 G90`…`M30`、Mill3Axis/EndMill 等）→ giemon-factory `:seq` 流の 4D 組立順。
  **BEV/FCEV はエネルギー系のところだけ分岐**（battery-pack/charge ↔ h2-tank/leak/fill）。
- **datom 化**（`vdesign.datom`）: 検証も製造も kotoba Datom ログ（EAVT, `nvidia_*-compat`
  と同形）へ。工程・工具・ボトルネックが Datalog クエリになる。

## 帰結

- 「設計→シム検証→組立工程まで全データ化」が1アクター1グラフで通る。デモは同一 500km
  セダンを BEV/FCEV とも close→sign-off→(a)PASS→(b)plan まで通す。
- テスト 11 / 47 assertions green（再現性・feasible 合格・BOM/CAM/G-code・組立順分岐・
  datom 化）。
- 実体配線（datom を kotoba-kqe に transact、シーンを実 kami-genesis/kami-cam へ）は
  次段。現状は self-contained な閉形式モデルで、信頼境界 `:verify`/`:process` は不変。
- west manifest 未登録のまま（前段 ADR と同様）。pin 前進時に `manifest/repos.edn` 追加。
