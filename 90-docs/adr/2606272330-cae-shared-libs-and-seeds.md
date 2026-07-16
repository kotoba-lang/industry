---
id: adr-2606272330-cae-shared-libs-and-seeds
title: "ADR-2606272330: 専用 CAE 物理を clean-room な *-clj / kami-* として EDN/CLJ・datom 化。共通ライブラリは目的・タイプごとに分割(datom-clj=表現 / vphysics-clj=車両物理 / cae-solver-clj=ソルバ契約)し、その上に reduced-order seed(aero/crash/motor/echem)を載せ、kami-cfd(Rust D2Q9 LBM)を高忠実度 :lbm backend にする。aero の実計算 Cd を vehicle-design-actor の設計ループへ注入"
status: accepted
doc_type: adr
topic: actor-design
authoritative: true
last_verified: 2026-06-28
authoritative_for:
  - 専用 CAE(空力/クラッシュ/モーター電磁/FC電気化学)を clean-room reduced-order として clj 化する方針
  - 共通ライブラリを目的・タイプごとに分割する設計判断(datom-clj / vphysics-clj / cae-solver-clj)
  - CAE ソルバを単一 `solve` multimethod 契約(:solver/kind)で backend 差し替え可能にする決定(:rom ↔ :lbm)
  - 4 物理(aero/crash/echem/motor)の実計算値を vehicle-design-actor の :aero / :cae-probe ノードと simverify に注入する配線(#2/#3)
  - kami-cfd を Rust D3Q19 LBM + Smagorinsky LES + free-slip 遠方境界 + 実ジオメトリ voxel 化の高忠実度 :lbm backend とする決定(#4)
  - kami-cfd の校正定数の扱い(物理/境界/ジオメトリは解決、残差はグリッド解像度=GPU 領域)
related:
  - orgs/kotoba-lang/datom          # 共有: kotoba Datom ログ(EAVT)表現
  - orgs/kotoba-lang/kami-engine-vphysics       # 共有: 車両物理(road-load/aero/range)
  - orgs/kotoba-lang/kami-engine-cae-solver     # 共有: solve multimethod 契約(backend dispatch)
  - orgs/kotoba-lang/kami-engine-aero           # seed: 空力 Cd(:rom-buildup)+ 設計ループ
  - orgs/kotoba-lang/kami-engine-crash          # seed: クラッシュ(:rom-crash)
  - orgs/kotoba-lang/kami-engine-motor          # seed: モーター(:rom-motor)
  - orgs/kotoba-lang/kami-engine-echem          # seed: FC 電気化学(:rom-fc)
  - orgs/kotoba-lang/kami-engine-cfd           # 高忠実度: Rust D2Q9 LBM(:lbm backend)
  - orgs/kotoba-lang/kami-engine-vehicle-designer  # :aero ノードで実 Cd を注入(#2)
  - 90-docs/adr/2606272130-vehicle-design-actor-bev-fcev.md
  - 90-docs/adr/2606272230-vehicle-design-sim-verify-datafied-process.md
supersedes: []
superseded_by: []
---

# ADR-2606272330: CAE 共通ライブラリ(目的別分割)+ reduced-order seed + kami-cfd(:lbm)

- Status: accepted (2026-06-27, 実装更新 2026-06-28)
- 文脈: 「Isaac/Cosmos では空力 CFD・クラッシュ FEA・モーター電磁・燃焼は解けない」→
  専用 CAE を kami-engine/*-clj として clean-room・EDN/CLJ・datom 化する方針の実装。
- 進捗: 共有 lib 分割 → reduced-order seed 4 種 → 4 物理を governor へ実接続 →
  kami-cfd を D2Q9 から D3Q19+LES+free-slip+実ジオメトリへ成熟させた(下記)。

## 課題

vehicle-design-actor と aero-clj で datom 表現と road-load 物理が重複していた。また
専用 CAE(空力/クラッシュ/モーター/FC)を足すたびに各 actor へ重複実装すると破綻する。
「ライブラリは目的・タイプごとに共通化」したい。

## 決定

### 1. 共通ライブラリを目的・タイプごとに分割(3つ、全て zero-dep・直交)

- **datom-clj** — kotoba Datom ログ(EAVT, Datomic 同型)表現。*データ表現*の責務のみ。
- **vphysics-clj** — road-load・SI 定数・aero force・range 感度。*車両物理の数式*のみ。
- **cae-solver-clj** — `solve` multimethod 契約。`[:solver :kind]` で *backend dispatch* のみ。

vehicle-design-actor と aero-clj を載せ替え、重複を撤去(vdesign.datom は :vdesign 束縛の
薄いビュー、powertrain の road-load/定数は vphysics 由来に)。

### 2. reduced-order CAE seed を契約の上に載せる

- **aero-clj**(:rom-buildup) 空力 Cd を成分 build-up で計算。
- **crash-clj**(:rom-crash) エネルギー収支クラッシュ → 減速度・応力 SF。
- **motor-clj**(:rom-motor) 空隙せん断サイジング → トルク密度・効率。
- **echem-clj**(:rom-fc) PEM 分極曲線 → セル電圧・LHV 効率(FCEV の「燃焼」相当)。

各 seed は `(defmethod cae.solver/solve :rom-* ...)` で同一契約に登録。

### 3. 4 物理の実計算値を設計の governor へ注入(#2/#3)

vehicle-design-actor のグラフに `:aero` と `:cae-probe` ノードを追加し、固定 prior/既定を
実計算値で上書き:

- **aero → 航続**: `:aero` が aero-clj の Cd で proposer の固定 Cd を上書き(BEV curb 1483→1489)。
- **motor → 推進系質量**: `:cae-probe` が motor-clj の `size-for-power`(目標 kW から逆サイジング:
  `D³=2P/(π·σ·λ·ω)`)で得たモーター質量で powertrain の固定 motor-kW-kg を上書き
  (110 kW→56 kg。BEV 推進 22→56 / FCEV 87→121 kg)。
- **echem → H2 消費(FCEV)**: `:cae-probe` が echem-clj の分極効率(η≈0.52)で既定 0.53 を上書き
  (H2 3.1→3.3 kg)。
- **crash → 構造 SF**: simverify の構造チェックを crash-clj(`:rom-crash`)に置換。56 km/h 正面衝突の
  エネルギー収支で減速度・レール応力 SF を実値化(従来の閉形式 proxy を撤去)。per-env DR も
  crush を再実行。

→ 4 物理すべてが spec を駆動。proposal を governor が検閲する不変条件は維持。

### 4. kami-cfd を高忠実度 :lbm backend に成熟(#4)

Rust LBM を段階的に成熟。同一 `[:solver :kind]`/`:dim` 契約のまま:

- **D2Q9(2D 断面)→ D3Q19(3D 車両 Cd, 前面積正規化)**。`:dim 2|3` で選択。
- **BGK → Smagorinsky LES**: 非平衡運動量フラックス Q_ab から渦粘性 ν_t=(Cs·Δ)²·S̄ を加え、
  高 Re で τ を ½ から離して安定化、かつ乱流の Re 非依存プラトーを再現(BGK は Re600 で NaN)。
- **無滑り壁 → free-slip 遠方境界(側/天)+ 路面のみ no-slip(鏡面反射 REFLECT_Y/Z)**: 閉じ込め
  による Cd 膨張を除去。
- **プリミティブ → 実ジオメトリ voxel 化**: 二値 STL パーサ + Ahmed body 生成 +
  `Body3::from_triangles`(列ごとレイ偶奇で中実化)。
- **並列化**: collide(per-cell)+ pull 形式 streaming を `std::thread`(zero-dep)で並列。
- **clj↔Rust 配線済み**: `aero.lbm`(JVM, subprocess)が `:lbm` を契約に登録。`cae.solver/solve`
  で `:rom-buildup ↔ :lbm` を差し替え可能。

## 帰結

- 11 リポジトリ体制(共有3 + seed4 + kami-cfd + vehicle-design-actor + 既存 langgraph 等)。
  全 clj テスト green、kami-cfd `cargo test` green(2D 3 + 3D LES 2 + mesh 3)。
- **校正の旅**: 残差(LBM 生値 ~1.8〜2.0 vs 実車 ~0.3)の原因を段階的に除去:
  blockage(29%→16%, free-slip 遠方境界)/ laminar→turbulent(LES, Re 非依存プラトー)/
  ジオメトリ(Ahmed body を voxel 化)。**Ahmed body 25° で校正後 ~0.22 vs 実験 ~0.29(±25%)**=
  認知された検証地形に乗った。残るは**グリッド解像度のみ=GPU 領域**(物理・境界・ジオメトリは解決)。
- west.yml 再生成コミットは作業ツリーの in-flight etzhayyim 展開を同梱しうる
  (`groups:[etzhayyim]` + `-etzhayyim` で既定 off=west update 不触)。手書きせず生成器出力。

## 却下案

- **単一 cae-clj に集約**: 目的が混ざる。表現/物理/ソルバ契約は寿命も利用者も異なるため分割。
- **各 actor に CAE を直書き**: 重複・非共通化。契約 + 共有 lib で seed を量産可能にする。
- **TRT 衝突演算子**: magic parameter が高 Re で ω⁻→0 を強制し逆に不安定化。LES を採用。
- **校正定数の即時撤廃**: 物理/境界/ジオメトリ改善後も残差はグリッド解像度由来。fine grid は
  GPU(wgpu)が必要で本サンドボックスでは検証困難。校正は明示・文書化して保持。

## 次段

- **GPU(wgpu)kami-cfd**: LBM は compute shader に好適。kami-engine の wgpu スタック再利用で
  fine/large grid → 校正定数の縮小・撤廃。または kami-cfd を kami-engine workspace へ統合。
