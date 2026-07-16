---
id: adr-2606272130-vehicle-design-actor-bev-fcev
title: "ADR-2606272130: vehicle-design-actor — 車をゼロから設計する clean-sheet 車両設計 actor。生成 concept proposer を PhysicsGovernor(質量閉包+エネルギー収支+パッケージング)で封じ、同一要件から BEV / FCEV の2アーキを sizing する。robotaxi-actor(VLA を SafetyGovernor で封じる)の設計版ミラー"
status: proposed
doc_type: adr
topic: actor-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - 「車をゼロから設計する」first-class actor(vehicle-design-actor)の設計判断
  - 生成 concept proposer を信頼境界の内側に封じ、物理的に閉じた設計だけを release する不変条件
  - BEV / FCEV を「エネルギー系モデルだけが分岐する」単一グラフで sizing する分割
  - 質量スパイラルを有界不動点として1ノード内に encapsulate し 1 run = 1 設計パスにする決定
related:
  - orgs/com-junkawasaki/vehicle-design-actor                    # 本 ADR の actor
  - orgs/com-junkawasaki/vehicle-design-actor/docs/adr/0001-architecture.md  # actor-local ADR(詳細)
  - orgs/com-junkawasaki/robotaxi-actor                          # 設計版ミラー元(VLA を SafetyGovernor で封じる)
  - orgs/kotoba-lang/langgraph                           # StateGraph runtime(superstep + interrupt + checkpoint)
  - orgs/com-junkawasaki/kami-engine/kami-vehicle                # 物理シム土台(soft-body, battery/tank ノード) ← 将来の検証連携先
  - orgs/etzhayyim/root/20-actors/hydrogen_electrolysis          # 水素製造側 actor(本 actor は車載 FC 設計側)
supersedes: []
superseded_by: []
---

# ADR-2606272130: vehicle-design-actor — concept proposer を PhysicsGovernor で封じた clean-sheet 車両設計 actor（BEV / FCEV）

- Status: proposed (2026-06-27)
- 配置: `orgs/com-junkawasaki/vehicle-design-actor`（共通 = com-junkawasaki org）
- 鏡像: robotaxi-actor ADR-0001 の**設計版ミラー**。詳細は actor-local
  [`docs/adr/0001-architecture.md`](../../orgs/com-junkawasaki/vehicle-design-actor/docs/adr/0001-architecture.md)。

## 課題

ワークスペースには車を**運転/運行**する actor（robotaxi-actor、autoware-/tesla_fsd-
等の compat）と、車を**シミュレート**する土台（kami-vehicle の soft-body 物理）は
あったが、**車をゼロから設計する** actor は無かった。とくに「BEV と FCEV それぞれで
エネルギー系まで sizing する」clean-sheet 設計の一次成果物が欠けていた。

## 決定

robotaxi の構図（賢いが安全を知らない VLA を SafetyGovernor で封じる）をそのまま
設計に移す。**賢いが物理を守らない概念生成器（LLM / 生成 CAD）を PhysicsGovernor で
封じる**。単一の不変条件:

> DesignProposer は、PhysicsGovernor が閉じていない設計を決して release しない。

PhysicsGovernor は保存則を独立に強制する: **質量閉包**（質量スパイラルの不動点。
過大要求は発散 → reject＝設計版 MRC）/ **エネルギー収支**（路面荷重 → powertrain 別
経路効率でストア容量）/ **パッケージング**（ストア体積 ≤ 包絡）/ GVWR・ストア質量率。
全反復は1ノード内に有界 encapsulate し、**1 graph run = 1 設計パス**で監査可能。

BEV / FCEV は `vdesign.powertrain` だけが分岐（glider・路面荷重・包絡は共通）。
結果、同一の 500 km セダン要件から「重いがコンパクトな BEV（~67 kWh / ~1480 kg）」
「軽いが体積逼迫の FCEV（~3 kg H₂ / ~1230 kg）」が落ち、1500 km 市街 BEV は質量
スパイラル発散で reject される。

## 帰結

- StateGraph 境界（`:propose → :govern` エッジ）が信頼境界。提案器は将来そのまま
  実 LLM / 生成 CAD ノードに差し替え可能で、governor とグラフは無改修。
- 物理定数は `vdesign.powertrain/tech`・`vdesign.proposer/classes` に集約（~2026
  量産技術）。検証は `closure_contract_test.clj`（22 assertions, green）。
- west manifest 未登録の local actor（robotaxi-actor と同状態）。pin を進める段で
  `manifest/repos.edn` に追加する。
- 将来連携: 設計 spec を kami-vehicle に流して soft-body 物理で検証する経路、
  水素は hydrogen_electrolysis（製造側）と対になる車載 FC（消費側）として接続。
