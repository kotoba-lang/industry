---
id: adr-2606281000-num-clj-gpu-compute-substrate
title: "ADR-2606281000: GPU 数値計算 substrate を clean-room な num-clj として新設 — zero-dep 全 .cljc のコア(array/CSR/IBackend/CPU 参照実装/公開API)+ 注入式 GPU バックエンド。可搬主軸は WGSL compute(wgpu が Metal/Vulkan(ROCm)/DX12/WebGPU へ per-GPU コンパイル)、最速が要る所だけ cuBLAS/MPS/rocBLAS を同一 IBackend 契約裏に。kudaki/nagare の Krylov/要素カーネルは num-clj backend を注入するだけで GPU 化(可搬カーネルは無改造)"
status: proposed
doc_type: adr
topic: actor-design
authoritative: true
last_verified: 2026-06-28
authoritative_for:
  - GPU 数値計算ライブラリを clean-room・portable-core + injected-backend として clj 化する方針
  - 可搬主軸を WGSL compute(WebGPU/wgpu)とし、ベンダーnative(CUDA/Metal/ROCm)を同一 IBackend 契約裏の opt-in 高速路とする決定
  - num-clj を CAE ソルバ(kudaki/nagare)の共有 GPU substrate とし、可搬カーネルへ backend 注入で GPU 化する配線方針
  - backend 契約テスト `GpuBackend ≡ CpuBackend`(MemStore≡DatomicStore 同型)で正しさを担保する決定
related:
  - orgs/kotoba-lang/num             # 新設: GPU 数値計算 substrate(IBackend + CPU 参照 + WGSL)
  - orgs/kotoba-lang/kudaki          # 消費者: 陽解法 FEA(要素 GEMM / 集中質量)
  - orgs/kotoba-lang/nagare          # 消費者: 有限体積 CFD(Krylov SpMV/AXPY/dot)
  - orgs/com-junkawasaki/kami-webgpu         # 同系: 宣言的 WebGPU レンダ(num-clj は GPU compute 側)
  - orgs/com-junkawasaki/kami-engine         # 同系: Rust wgpu エンジン(render+physics)
  - orgs/kotoba-lang/kami-engine-cae-solver      # 契約: solve multimethod(将来 GPU backend を別 kind で)
  - 90-docs/adr/2606272350-kudaki-nagare-highfidelity-fea-cfd-backends.md
supersedes: []
superseded_by: []
---

# ADR-2606281000: num-clj — 注入式 GPU バックエンドを持つ可搬数値計算 substrate

- Status: proposed (2026-06-28)
- 文脈: kudaki-clj / nagare-clj は zero-dep 全 .cljc(JVM/SCI/cljs/GraalVM/WASM 可搬)
  ゆえに価値があるが、純Clojure・スカラ・シングルスレッドで **GPU 最適化が皆無**。
  「Metal/CUDA/ROCm それぞれに最適化した計算ライブラリ」を足したい。一方、org の GPU
  資産は wgpu/WebGPU 系(kami-webgpu=宣言的レンダ、kami-engine=Rust wgpu)で、**GPU
  *compute*(BLAS/tensor)ライブラリは不在**。

## 課題

GPU を入れると native/FFI 依存が生まれ、kudaki/nagare の WASM 可搬性と actor のポート
注入設計が壊れる。しかし検証スケールを超える問題には速度が桁違いに足りない。**可搬性を
保ったまま GPU 速度を選べる**構造が要る。

## 決定

### 1. portable-core + injected-backend(seam は単一 `IBackend`)

num-clj のコア(`num.array` の NDArray / `num.sparse` の CSR / `num.protocol` の
`IBackend` / `num.core` 公開API / `num.cpu` 純Clojure参照実装)は **zero-dep 全 .cljc**。
`handle` は backend ごとの不透明バッファ(CPU=double-array、WebGPU=GPUBuffer、CUDA=device
ptr)。同一プログラム(`matmul/spmv/axpy!`…)が backend 差し替えだけで CPU↔GPU を走る。
`MemStore ‖ DatomicStore` / cae-solver `[:solver :kind]` と同型の注入パターン。

### 2. 可搬主軸 = WGSL compute(WebGPU/wgpu)、native は opt-in 高速路

WGSL compute shader を1本書けば wgpu が各GPUの native ISA(**MSL**=Apple / **SPIR-V**
=Vulkan→AMD(ROCm系)/NVIDIA / **DXIL**=D3D12 / **WebGPU**=ブラウザ)へコンパイル。
「各GPU最適化」はドライバの per-GPU codegen が担い、こちらは**良いカーネルを一度**書く
(16×16 共有メモリタイル GEMM、scalar-CSR SpMV、AXPY、tree reduce)。最速が要る時だけ
`:cuda`(cuBLAS/cuSPARSE)/`:metal`(MPS)/`:rocm`(rocBLAS) を**同一 IBackend 裏**に
opt-in(JVM/native 限定)。num-clj は native コードを一切持たず、`:wgsl` 実行は
host 注入の `IGpuDevice` ポート(ブラウザは `navigator.gpu`、native は wgpu binding)。

### 3. スコープ = BLAS レベル + 疎行列

ndarray/CSR + level-1(axpy/scal/dot/nrm2)+ elementwise/reduction + level-2/3
(gemv/gemm)+ sparse(SpMV)。消費者(nagare の Krylov、kudaki の要素/組立)が必要と
する最小十分集合。autodiff tensor フレームワーク(JAX風)は別物・別ライブラリ。

### 4. 正しさ = backend 契約テスト `GpuBackend ≡ CpuBackend`

CPU 参照実装が oracle。`num.contract/verify` の同一スイートを任意 backend に流し、f32
許容で一致を要求(`MemStore ≡ DatomicStore` 同型)。本セッションで CPU backend は
**全契約 green(4 tests / 17 assertions)**。

## 帰結

- 1リポ新設(num-clj)。コア+CPU参照は zero-dep 全 .cljc・テスト green。WGSL は
  shader(タイル GEMM/CSR SpMV/AXPY/reduce)+ `IGpuDevice` ポート + dispatch-plan を
  同梱(live device 配線は S1 次段)。
- **kudaki/nagare は可搬カーネル無改造のまま GPU 化できる**: `linsolve` の PCG/BiCGStab を
  device SpMV/AXPY/dot へ、要素 GEMM を device GEMM へ、backend 注入で差し替え(S3)。
  これは cae-solver の `[:solver :kind]` で「GPU 加速 backend」を別 method 登録する筋。
- native 速度を、WASM 可搬性を捨てずに**選べる**(GPU 無し host は CPU backend で全機能動作)。
- west 登録・GitHub remote 作成・push は CLAUDE.md 方針通り**ユーザー承認後**(本 ADR では未実施)。

## 却下案

- **単一ベンダー backend(CUDA専)**: Apple/AMD/ブラウザを排除し WASM 可搬性を破壊。
  WGSL-first なら1カーネルで全到達。
- **フル autodiff tensor(JAX風)**: 大きく直交。消費者は BLAS+sparse が要件。後付け可。
- **コアに native コード**: 兄弟ソルバの存在理由(cljs/WASM 可搬性)を失う。native は
  注入 backend のみに隔離。
