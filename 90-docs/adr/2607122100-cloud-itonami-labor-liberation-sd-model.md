# ADR-2607122100: 労働解放 system dynamics モデル — ADR 集約の datafy と逆トポソート再計算（DataScript queryable）

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki
**Scope**: `90-docs/business/labor-liberation-sd-model.edn`, `scripts/labor-liberation-sd.cljs`

## Context

オーナー問い（2026-07-12）: 「etzhayyim, cloud-itonami として全世界から労働を解放する
ためにまだ足りていないものは? 衣食住から cloud-itonami, robotics, giemon が進めるのが
いいかな?」——続けて「adr をまとめて, system dynamics, 逆トポロジーソートで最適な順序を
再計算して, これは edn でデータ化して datascript, clj で query して計算を導出できるように」。

ADR-2607121000（ISIC/ISCO 逆トポソート 5-wave）は**価値捕捉**の目的関数
（V = TAM × 推移的被依存度 × 自動化可能度 × 資産くさび）で rollout 順序を固定したが、
散文 + `kotoba.industry.wave` の wave 割当としてのみ存在し、
(1) 関連 ADR 13 本に散った依存構造・ゲート・フィードバックループが単一の機械可読
モデルになっていない、(2) **労働解放 dW/dt** を目的関数にした場合の順序が導出できない、
(3) system dynamics（reinforcing/balancing loop、断点、リードタイム）が順序計算に
入っていない、という3つの欠落があった。

## Decision

### 1. モデルの正本 = `90-docs/business/labor-liberation-sd-model.edn`

関連 ADR 13 本（2606271700 / 2607011000 / 2607012000 / 2607020000 / 2607031500 /
2607051621 / 2607062330+2400 / 2607072530+2600 / 2607062210 / 2607110600 /
2607111000 / 2607121000 / foreign 2605142200+2300）の要約・寄与ノードを `:adrs` に、
系全体を **41 nodes / 91 edges / 7 loops / 2 gates** に datafy した。

- **辺の定義は ADR-2607121000 を踏襲**: `:requires` = 「v の操業は u の産出を必須運転
  入力とする」。加えて `:enables`（build-time/チャネル前提: scaffold・wasm 工場・
  fixture・7810 置換チャネル）と `:flows`（SD ストック流入、トポソート対象外）を区別。
- **目的関数を載せ替え**: W = 解放された労働時間ストック。
  score(n) = unlocked-W(n) × automatability-now × (0.5+0.5×wedge) × loop-boost × gate-factor。
  unlocked-W = 自身 + 推移的被依存ノードの labor-share 総和（解放レバレッジ）。
- **system dynamics**: reinforcing 4 本（R1 収益フライホイール = **broken、断点
  first-external-tenant** / R2 複製学習 / R3 robot 艦隊 τ=18mo / R4 解放の伝達 7810）、
  balancing 3 本（B1 信頼 gate / B2 安全 governor / B3 資本 runway = binding）。
  loop-boost は断点ノードに最大加点（そこを塞ぐのが最高レバレッジ）。
- 数値（labor-share / automatability / wedge / lead-time）は全て**桁感**。
  ADR-2607121000 の constraint（正確な調査は各 wave 着手時の個別 ADR）を踏襲。

### 2. 導出器 = `scripts/labor-liberation-sd.cljs`（nbb + npm datascript）

モデルを実 DataScript にロードし（属性変換は `manifest/edn-query.cljs` と同一方針）、
`verify`（参照整合 + DAG 非循環）/ `layers`（Kahn 逆トポソート層）/ `rank` / `loops` /
`plan`（3 トラック実行順序）/ `q`（任意 datalog）を query から導出する。
検証: verify OK（41/91/7/2/13、DAG acyclic）。

### 3. 再計算結果（2026-07-12 baseline）

**TRACK A 主戦線**（gate 無し、層→score 順に直列）:
treasury 完遂(L0) → **衣食住 scaffold 起票(L0)** → wasm 工場(L1, boost1.6) →
6419/6492 配線(L1) → 6511 配線(L1) → market-entry API(L1) → L1 自己登録(L1, boost1.4) →
mamori(L1) → telecom-data/6201(L2) → 法人設立×compliance(L2) →
**外部テナント1社(L2, boost2.0 = R1 断点)** → … → L2 protocol fee(L3) →
**7810 置換取引所(L3)** → 物流編成(L3) → ISCO 認知職(L4) → 商流・小売(L4)。

**TRACK B 長リード enabler**（lead ≥ 6mo、今日着手・並行）:
robot.fleet(18mo) ← Hitogata 設計(12mo) ← **Hitogata fixture+BOM(9mo)** /
EMS 量産経路(9mo)。critical chain 上、Wave 3 が gate されていても enabler は今日始める。

**TRACK C gate 待ち**: gate.robotics の背後に衣食住の生産側
（農業 W=0.26 / 建設 / 食品 / 繊維 / 一般製造）、gate.trust の背後に Wave 4
（医療介護 / 教育 / 宿泊外食）。

### 4. ADR-2607121000 との関係 — supersede しない

wave 順序（根→衣食住生産→対人）は**再計算でも保存された**（却下条項は有効のまま）。
本 ADR が変えるのは順序の「解像度」:
1. **衣食住は「生産着手」でなく「scaffold 起票」として L0 に前倒し**（gate 無し・安い。
   2607121000 の follow-up 扱いから Track A 先頭群へ昇格）。
2. **長リード robotics enabler の today-start**（fixture/EMS/艦隊は τ=18mo の R3 を
   将来閉じるために gate と無関係に今日着手）。
3. **7810 労働市場の昇格**: 労働解放 dW/dt の目的関数では、解放の伝達機構（R4）として
   金融残り 16 classes（労働シェア極小、収益は R1 で別途担保）より上位に来る。
4. 順序の正当化が散文でなく **query 導出可能**になった（wave 割当と本モデルの層が
   矛盾したら `verify`/`layers` で機械検出できる）。

## Consequences

- (+) 「まだ足りていないもの」が機械可読に固定された: R1 断点（外部テナント/実収益）、
  wasm 工場（配線 2/388）、Hitogata fixture+BOM（人型の実 BOM ゼロ）、衣食住 scaffold
  （ISIC 10-14/4100 = 0 本）、7810 置換取引所。
- (+) 目的関数の差し替え（価値捕捉 ↔ 労働解放）がデータ編集だけで再計算できる。
- (−) 数値は桁感。labor-share の産業/職業レンズは重複計上（合計 1.0 にしない）。
- (−) モデルは手動更新。registry（646/436）との自動同期は将来の follow-up
  （`kotoba.industry.wave` との突合を verify に足すのが自然な次の一歩）。
- (−) npm datascript の制約により属性は裸文字列（keyword 型情報は失われる —
  `manifest/edn-query.cljs` と同じ既知の制約）。

## Artifacts

- `90-docs/business/labor-liberation-sd-model.edn`（新規 — モデル正本）
- `scripts/labor-liberation-sd.cljs`（新規 — 導出器）
- 本 ADR とペアの `.edn`

## References

- ADR-2607121000（逆トポソート 5-wave — 本モデルの入力・辺定義の出典）
- ADR-2607051621（P0 クリティカルパスの正本）/ ADR-2607011000(robotics 前提)
- ADR-2607020000 / 2607062210（giemon 製品ライン・embodiment 発注の実態）
- ADR-2607111000（cloud-mamori — 信頼ストック供給源）
- `manifest/edn-query.cljs`（npm datascript ロード方針の先行実装）
