---
id: adr-2606272350-kudaki-nagare-highfidelity-fea-cfd-backends
title: "ADR-2606272350: 高忠実度 CAE バックエンドを clean-room な pure-.cljc ソルバとして新設 — kudaki-clj(砕き=LS-DYNA級 陽解法非線形構造/クラッシュ FEA, :explicit-fea)と nagare-clj(流れ=OpenFOAM級 有限体積 CFD, :fvm)。どちらも zero-dep・全 .cljc(JVM/SCI/cljs/GraalVM/kotoba-WASM 可搬)で、cae-solver の単一 solve 契約に reduced-order seed(crash-clj/aero-clj)と差し替え可能な高忠実度メソッドとして載る"
status: proposed
doc_type: adr
topic: actor-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - 高忠実度 CAE(クラッシュ FEA / 空力・熱 CFD)を clean-room・pure-.cljc・zero-dep として clj 化する方針
  - kudaki-clj を crash 領域の高忠実度 `:explicit-fea` バックエンド(crash-clj `:rom-crash` の対)とする決定
  - nagare-clj を aero/熱 領域の高忠実度 `:fvm` バックエンド(aero-clj `:rom-buildup` / kami-cfd `:lbm` の同胞)とする決定
  - 両ソルバの S0 検証スコープ(陽解法中央差分 + 有限体積 PISO)と多段ロードマップ(S0→S4)
related:
  - orgs/kotoba-lang/kudaki           # 新設: 陽解法 FEA(:explicit-fea backend)
  - orgs/kotoba-lang/nagare           # 新設: 有限体積 CFD(:fvm backend)
  - orgs/kotoba-lang/kami-engine-cae-solver       # 共有: solve multimethod 契約(backend dispatch)
  - orgs/kotoba-lang/kami-engine-crash            # seed: クラッシュ(:rom-crash)— kudaki の reduced-order 対
  - orgs/kotoba-lang/kami-engine-aero             # seed: 空力(:rom-buildup)— nagare の reduced-order 対
  - orgs/com-junkawasaki/kami-cfd             # 高忠実度: Rust D2Q9 LBM(:lbm)— nagare(:fvm)の同胞
  - orgs/com-junkawasaki/vehicle-design-actor # 設計クロージャの sim-verify 消費者
  - 90-docs/adr/2606272330-cae-shared-libs-and-seeds.md
  - 90-docs/adr/2606272230-vehicle-design-sim-verify-datafied-process.md
supersedes: []
superseded_by: []
---

# ADR-2606272350: kudaki-clj(:explicit-fea)+ nagare-clj(:fvm)— 高忠実度 CAE バックエンド

- Status: proposed (2026-06-27)
- 文脈: ADR-2606272330 が CAE を「単一 `cae.solver/solve` 契約 + reduced-order seed +
  高忠実度バックエンド(kami-cfd `:lbm`)」に整理した。reduced-order の crash-clj は
  自身の docstring で **「validated kami-cae explicit FEA が同じ契約に `:explicit-fea` を
  登録する」** と既に対を予告している。本 ADR はその高忠実度の対を 2 本実装する。

## 課題

reduced-order seed（crash-clj/aero-clj）は設計ループを閉じるには十分速いが、検証には
忠実度が足りない。クラッシュは薄肉構造の塑性座屈、空力は剥離・後流が本質で、これらは
エネルギー収支 ROM では解像できない。一方 ADR-2606272330 の高忠実度バックエンドは現状
**kami-cfd(Rust LBM, 空力のみ)** だけで、(a) クラッシュ FEA の高忠実度が欠落し、
(b) clj↔Rust 配線が未了(同 ADR「次段」)で、ブラウザ/WASM/SCI ホストでそのまま走らない。

## 決定

### 1. kudaki-clj(砕き)— LS-DYNA 級 陽解法非線形構造ソルバ = crash の `:explicit-fea`

半離散運動方程式 `M a = F_ext − F_int(u)` を、**集中(対角)質量** + **中央差分** で陽に
積分する。質量が対角なので毎ステップの線形ソルブが不要(`a = M⁻¹(F_ext − F_int)`)、非線形
（大回転・塑性流動・接触）は `F_int` を差し替えるだけ — グローバル剛性の再分解が無い。全体は
要素内力カーネル上の純粋な fold で表せ、1 run = 1 有界シミュレーション（CFL ステップ予算が
ループ境界）という actor 規律に合致する。crash-clj の `:rom-crash` と同じ CAE 契約に
**`:explicit-fea`** を登録する(高忠実度の対)。

### 2. nagare-clj(流れ)— OpenFOAM 級 有限体積 CFD ソルバ = aero/熱 の `:fvm`

非構造コロケート有限体積。保存則を各セルで積分し発散定理で **面フラックスの和** に落とす。
各離散化作用素（`ddt/div/laplacian/grad`）が疎な **fvMatrix** を owner/neighbour 配列から
組み、**Rhie–Chow** 面補間でチェッカーボードを抑え、**PISO/SIMPLE** で圧力-速度を連成、
**PCG/BiCGStab** で線形系を解く。kami-cfd の `:lbm`(Rust)が空力 Cd の一方式なら、nagare は
**pure-.cljc の有限体積方式**で、同じ aero 契約に **`:fvm`** を登録する同胞。熱(対流拡散)も
同じ fvm 基盤で扱える。

### 3. どちらも zero-dep・全 `.cljc`(ポータビリティが設計判断の核)

BLAS/PETSc/MPI/native solver/ライセンスサーバを一切持たない。線形代数も時間積分も Krylov も
pure Clojure（double 配列上）。これにより *同一カーネル* が JVM / SCI / ClojureScript /
GraalVM / kotoba-clj(WASM)で走り、設計 actor は FFI 無しでポートとして注入できる。kami-cfd の
clj↔Rust 配線が「次段」で未了なのに対し、kudaki/nagare は**今この瞬間からホスト非依存で走る**。

### 4. CAE 契約への配線は薄い bridge defmethod（次段の S0.5）

カーネル自体は cae-solver-clj に依存しない（zero-dep を保つ）。`(defmethod cae.solver/solve
:explicit-fea …)` / `:fvm` は **case → ソルバ入力 → ドメイン結果**（crash なら `:SF/:decel-g/
:pass?`、aero なら `:Cd`）を写す薄い adapter とし、crash-clj/aero-clj 側または専用 bridge に置く。
これは kami-cfd の `:lbm` 配線が「次段」なのと同列で、本コミットには含めない。

## S0 実装と検証（本セッションで着地、tests green）

| repo | モジュール(`src/<ns>/*.cljc`) | 検証(`clojure -X:test`) |
|------|------------------------------|--------------------------|
| **kudaki-clj** | linalg / mesh / material(elastic + J2 radial-return) / element(truss + 1pt-hex + Flanagan–Belytschko hourglass) / contact(penalty) / integrate(中央差分・CFL・mass-scaling・energy ledger) / demo | **15 tests / 51 assertions / 0 failures**。波速 √(E/ρ) 一致(~8%)、J2 リターンマップが降伏面に着地、hex 一軸パッチテストが E を回収、Taylor バー塑性短縮 9.7%・エネルギー収支残差 0.75% |
| **nagare-clj** | mesh(非構造 polyMesh) / field(volField + BC) / fvm(grad/div/laplacian/ddt + Rhie–Chow) / linsolve(Jacobi/GS/PCG/BiCGStab) / solver(PISO) / demo | **7 tests / 81 assertions / 0 failures**。Poisson MMS が 2 次収束(誤差比 4.0)、Krylov が既知解を <1e-8 で回収、lid-driven cavity Re=100 が Ghia 中心線形状を再現(最小 u ≈ −0.187) |

各 repo の `docs/adr/0001-architecture.md` に S0→S4 の段階ロードマップ
（kudaki: 材料&接触 → シェル/ソリッド&hourglass → 陰解法統計 → `.k` deck 拡充 /
nagare: 定常 SIMPLE → スキーム&非直交補正 → 乱流 RANS → case-dir IO）を据えた。

## 帰結

- 2 リポジトリを新設(kudaki-clj / nagare-clj)。両者 zero-dep・全 `.cljc`・テスト green。
- crash/aero の高忠実度バックエンドが揃い、ADR-2606272330 の `[:solver :kind]` 差し替え図が
  reduced-order ↔ 高忠実度の両端で完成に近づく(配線 defmethod は S0.5 次段)。
- **west 登録は未了(次段)。** CLAUDE.md の方針通り、`manifest/repos.edn` への登録は
  GitHub API のクリーン single-entry commit で行い、それには GitHub remote の存在が前提。
  リモート repo 作成・push はユーザー承認後に実施する(本 ADR では行わない)。
- native 速度は無い。検証スケール（FEA は粗メッシュ・薄肉、CFD は 10³–10⁵ セル）であり、
  消費者が *設計クロージャ検証* であることと整合（認証ランや production LES ではない）。

## 却下案

- **kami-cfd(LBM)に空力を一本化**: LBM と有限体積は解像特性・実装言語(Rust vs cljc)・ホスト
  可搬性が異なる。pure-cljc の `:fvm` は WASM/SCI でそのまま走る独立価値があるため別実装。
- **crash を reduced-order のみで据え置き**: 薄肉塑性座屈は ROM では解けず、検証忠実度が不足。
  crash-clj が予告済みの `:explicit-fea` 対を実装して契約の両端を埋める。
- **native FEA/CFD への FFI**: ライセンス/ビルド/ホスト依存を持ち込み、actor のポート注入と
  WASM 可搬性を壊す。zero-dep pure-.cljc を堅持する。
