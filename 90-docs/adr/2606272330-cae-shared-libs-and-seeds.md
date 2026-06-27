---
id: adr-2606272330-cae-shared-libs-and-seeds
title: "ADR-2606272330: 専用 CAE 物理を clean-room な *-clj / kami-* として EDN/CLJ・datom 化。共通ライブラリは目的・タイプごとに分割(datom-clj=表現 / vphysics-clj=車両物理 / cae-solver-clj=ソルバ契約)し、その上に reduced-order seed(aero/crash/motor/echem)を載せ、kami-cfd(Rust D2Q9 LBM)を高忠実度 :lbm backend にする。aero の実計算 Cd を vehicle-design-actor の設計ループへ注入"
status: proposed
doc_type: adr
topic: actor-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - 専用 CAE(空力/クラッシュ/モーター電磁/FC電気化学)を clean-room reduced-order として clj 化する方針
  - 共通ライブラリを目的・タイプごとに分割する設計判断(datom-clj / vphysics-clj / cae-solver-clj)
  - CAE ソルバを単一 `solve` multimethod 契約(:solver/kind)で backend 差し替え可能にする決定(:rom ↔ :lbm)
  - aero-clj の実計算 Cd を vehicle-design-actor の :aero ノードで設計ループへ注入する配線(#2)
  - kami-cfd を Rust D2Q9 LBM の高忠実度 :lbm backend とする決定(#4)
related:
  - orgs/com-junkawasaki/datom-clj          # 共有: kotoba Datom ログ(EAVT)表現
  - orgs/com-junkawasaki/vphysics-clj       # 共有: 車両物理(road-load/aero/range)
  - orgs/com-junkawasaki/cae-solver-clj     # 共有: solve multimethod 契約(backend dispatch)
  - orgs/com-junkawasaki/aero-clj           # seed: 空力 Cd(:rom-buildup)+ 設計ループ
  - orgs/com-junkawasaki/crash-clj          # seed: クラッシュ(:rom-crash)
  - orgs/com-junkawasaki/motor-clj          # seed: モーター(:rom-motor)
  - orgs/com-junkawasaki/echem-clj          # seed: FC 電気化学(:rom-fc)
  - orgs/com-junkawasaki/kami-cfd           # 高忠実度: Rust D2Q9 LBM(:lbm backend)
  - orgs/com-junkawasaki/vehicle-design-actor  # :aero ノードで実 Cd を注入(#2)
  - 90-docs/adr/2606272130-vehicle-design-actor-bev-fcev.md
  - 90-docs/adr/2606272230-vehicle-design-sim-verify-datafied-process.md
supersedes: []
superseded_by: []
---

# ADR-2606272330: CAE 共通ライブラリ(目的別分割)+ reduced-order seed + kami-cfd(:lbm)

- Status: proposed (2026-06-27)
- 文脈: 「Isaac/Cosmos では空力 CFD・クラッシュ FEA・モーター電磁・燃焼は解けない」→
  専用 CAE を kami-engine/*-clj として clean-room・EDN/CLJ・datom 化する方針の実装。

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

### 3. aero の実 Cd を設計ループへ注入(#2)

vehicle-design-actor に `:aero` ノードを追加し、aero-clj の計算 Cd で proposer の固定 prior
を上書き → エネルギー sizing が実際の抗力に応答(セダン BEV curb 1483→1489 kg)。

### 4. kami-cfd を高忠実度 :lbm backend にする(#4)

Rust D2Q9 lattice-Boltzmann(BGK, 運動量交換で断面 Cd)。同一 `[:solver :kind]` 契約の
`:lbm` として、reduced-order `:rom-buildup` と差し替え可能(blunt > streamlined を解像で再現)。

## 帰結

- 8 リポジトリを新設・push(datom-clj/vphysics-clj/cae-solver-clj/aero-clj/crash-clj/
  motor-clj/echem-clj/kami-cfd)。全テスト green、kami-cfd は `cargo test` green。
- west.yml に 8 project を登録(本コミットの west.yml 再生成には、作業ツリーに既にあった
  in-flight な etzhayyim manifest 展開も含まれる。`groups: [etzhayyim]` + `-etzhayyim`
  group-filter で既定 off=west update は触れない。手書きせず生成器出力をそのままコミット)。
- 高忠実度バックエンドの clj↔Rust 配線(subprocess/FFI/WASM 経由の :lbm method)は次段。

## 却下案

- **単一 cae-clj に集約**: 目的が混ざる。表現/物理/ソルバ契約は寿命も利用者も異なるため分割。
- **各 actor に CAE を直書き**: 重複・非共通化。契約 + 共有 lib で seed を量産可能にする。
