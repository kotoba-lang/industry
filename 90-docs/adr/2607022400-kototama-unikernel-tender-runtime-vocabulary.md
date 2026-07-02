# ADR-2607022400: kotoba-lang（言語）/ kototama（Wasm 実行 runtime）/ aiueos（OS layer）の用語を確定し、kototama の runtime 設計に Solo5 tender パターンを採用する

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607022200 は aiueos を「CLJC 意味論 + Wasm Component Model 境界 + kernel（native
adapter）」の三層として固定したが、Layer 3（kernel）を「native adapter」とだけ呼び、
内部構造・命名を詰めていなかった。並行して、`kotoba`/`kototama`/`aiueos` の三語の役割
定義が **リポジトリ間でズレていた**:

- `aiueos/90-docs/adr/0001-aiueos-phase0-capability-os.md`（accepted, 2026-06-25、古い）:
  `kototama = Kotoba/CLJ host contract + safe execution facade`
- `kotoba-lang/kotoba/docs/ADR-kotoba-shell-aiueos-safe-kotoba.md`（proposed、より古い草稿）:
  `kotoba wasm : how kotoba becomes executable`（kototama という語を使っていない）
- `kotoba-lang/kotoba/docs/ADR-kotoba-shell-aiueos-safety-clj.md`（**accepted-in-progress**,
  2026-07-01 更新、最新かつ正典）:
  ```
  kotoba              = 言語 + データベース + 意味論的基盤
  kototama(kotoba-clj)= Kotoba/CLJ -> Wasm component + 安全言語ゲート
  aiueos              = component OS / broker / capability graph
  kotoba-shell        = app shell / WebView / native capability provider / packager
  ```

さらに `kototama/README.md` 自身の自己記述は「Kototama is the CLJC authority layer for
actor/organism host capability contracts」であり、上記のどちらとも微妙に異なる（実行
runtime というより host capability contract の権威レイヤーとしての記述）。

一方、`kototama`（Wasm ホスティング＋ host-ABI＋capability gate 執行）は Wasm sandbox
そのものを提供する側であり、**sandbox 化できない唯一の native レイヤー**（ADR-2607022200
の Layer 3）である。mythos 級攻撃者を前提にするなら、ここが最優先で最小化・硬化すべき
攻撃対象になる。過去に unikernel（Solo5/Nanos/Unikraft 等）を参照した設計調査は本
monorepo に **存在しなかった**（全文検索で unikernel/Solo5/MirageOS/unikraft/IncludeOS/
single address space/libOS は全てゼロヒット。唯一近い言及は kotoba-shell の非責務
リストにある「microVM」1箇所のみ）。本 ADR で初めてこの調査を行い、決定に反映する。

## Decision

### 1. 用語を確定する（`ADR-kotoba-shell-aiueos-safety-clj.md` を正典として採用）

```
kotoba-lang（言語 + コンパイラ）
  = 言語仕様（.kotoba/.cljc/.clj source contract）+ 意味論的基盤（kotoba-edn）
    + 安全言語ゲート（eval/require/reflection/ambient IO/raw memory 拒否。
      実装は kotoba-clj、旧 aiueos/src/safe.rs は redundant — ADR-2607022200 で確認済み）
    + capability/effect gate（policy-derived imports、宣言 ⊇ 使用）
    + Kotoba/CLJ -> Wasm component へのコンパイル

kototama（Wasm 実行 runtime）
  = kotoba-lang がコンパイルした Wasm component を **ホストする側**。
    wasmtime（または相当のエンジン）+ aiueos:host ABI + capability gate 執行
    + HostCaps/RuntimeLimits + import-surface validation。
    「言語を安全にする」のではなく「安全な Wasm を実行環境に載せる」レイヤー。

aiueos（OS layer）
  = component supervisor + capability broker + policy reasoner
    （本セッションで CLJC 化済み: policy/graph/surface/manifest/signing/audit/topic/
    broker/cli）。kototama の runtime インスタンスに「何を・どんな capability で」
    載せるかを決定する側。kototama 自体をホストしない — kototama は aiueos の
    決定を実行する native な土台。
```

依存方向は `ADR-kotoba-shell-aiueos-safety-clj.md` §5 のまま一方向固定:
`kotoba-shell → aiueos → kototama/kotoba-clj → kotoba-edn`。

旧 `aiueos/90-docs/adr/0001` の `kototama = Kotoba/CLJ host contract + safe execution
facade` という表現は**この ADR により superseded**（ファイル自体は編集せず、歴史的記録
として残す。superseded の事実だけ本 ADR に記録する）。`kototama/README.md` の
自己記述（"CLJC authority layer for actor/organism host capability contracts"）は
kototama が実装する**契約**の記述として正しいが、kototama の**役割**（Wasm 実行
runtime）を言い表す一次語彙としては本 ADR の定義を優先する。

### 2. kototama の runtime 設計に Solo5 の tender パターンを採用する

unikernel 先行事例調査（Solo5 / Nanos / Unikraft / Mewz / urunc / seL4、web 調査）の
結論:

| 対象 | TCB最小化の仕組み | Wasm ホスティングとの適合 |
|---|---|---|
| **Solo5** | guest と hardware/hypervisor アクセスを **tender**（極小の仲介プロセス、hvt=hw仮想化 or spt=seccomp 7 syscall のみ）に分離。guest は application manifest で論理デバイスを宣言 | **最適**。tender が「境界の外」、guest（+ ランタイム）が「境界の内」という分離が aiueos の manifest 駆動 capability モデルと直接対応 |
| **Nanos** | VMM（Firecracker/QEMU）依存、tender 相当の専用抽象なし | Wasm 統合は薄い（interpreter を1アプリとして同梱する程度） |
| **Unikraft** | microlibrary を静的リンクし必要な OS primitive だけを含める。WAMR を第一級統合（`unikraft/app-wamr`） | Wasm エンジン自体を **kernel image に焼き込む**（guest と runtime が同一バイナリ） |
| **Mewz** | Wasm/WASI を AoT コンパイルし kernel の WASI 実装に直接リンク（syscall 境界すら無い） | 同上、エンジンが TCB の内側に入る |
| **seL4** | 形式検証済み capability microkernel。driver は非特権プロセスとして capability-gated IPC 経由 | driver-as-component という発想は aiueos と近いが、形式検証は現時点で non-claim（SECURITY.md の `high-assurance` プロファイルまで保留） |

**Unikraft/Mewz 型（runtime を kernel image に焼き込む）は不採用**。wasmtime +
host-ABI + capability gate 執行コードそのものが「境界の外側」であるべき aiueos の
設計と逆行する（焼き込むと wasmtime の脆弱性がそのまま TCB 内の脆弱性になる）。

**Solo5 の tender パターンを採用**: `kototama = tender`、`aiueos が起動する Wasm
component 群 = guest`。

- manifest の `:aiueos/imports`/`:aiueos/device`/`:aiueos/requires #{:iommu}` 等
  （既に `aiueos.policy`/`aiueos.manifest`/`aiueos.graph` で CLJC 化済み）が、Solo5 の
  application manifest（論理デバイス宣言）に相当する **kototama tender 起動時の
  入力**になる。
- Solo5 の `hvt`（hardware-virtualized）/`spt`（seccomp sandboxed process）の二択が、
  kototama の**選択可能な隔離強度**の雛形になる: 高強度デプロイ（`regulated`/
  `high-assurance` プロファイル）は hvt 相当（別 VM/軽量ハイパーバイザ）、`research`/
  `sensitive-local` は spt 相当（seccomp 限定 host process）で足りる、という段階分けが
  既存の `docs/deployment-profiles.md` の profile 体系とそのまま整合する。
- 既存の kernel-provided capability 名（`mmio/map` `dma/map` `irq/subscribe`
  `pci/config`）は Solo5 tender の「guest が触れる論理デバイス」に一対一で対応させる
  ——これは新規決定ではなく、ADR-0001/0002 が最初から意図していた「kernel-provided
  unsafe adapter」を Solo5 の実装パターンで具体化しただけ。

### 3. scheduler / memory モデルは検証済み・変更不要

- **scheduler**: 調査した unikernel は軒並み協調的・非プリエンプティブ・単一
  アドレス空間スケジューリングが標準（MirageOS の `lwt` 等）。aiueos の ADR-0006
  （deterministic cooperative cycle-based scheduler、wall-clock 不使用、非プリエン
  プティブ）は**既にこの規範と一致**しており、変更不要。
- **memory**: Unikraft の静的 microlibrary リンク、Solo5 の固定 application
  manifest は、いずれも demand-paged VM ではなく静的サイズ決定を規範とする。
  aiueos の `:aiueos/limits {:memory-pages :fuel}`（`aiueos.manifest/normalize-limits`
  で CLJC 化済み）は**既にこの規範と一致**しており、変更不要。

## Consequences

- (+) `kotoba-lang`/`kototama`/`aiueos` の役割定義がリポジトリ間で一つに揃う。
  以後の ADR・README はこの ADR の定義を正典として参照する。
- (+) kototama の native runtime 設計に、実在する・検証された参照実装
  （Solo5 tender/hvt/spt）という具体的な雛形が付いた。ゼロから設計判断をする
  必要がなくなる。
- (+) 既存の capability 名（`mmio/map` 等）・scheduler（ADR-0006）・memory limits
  設計が、後付けの unikernel 調査によって**裏付けられた**（変更ではなく検証）。
- (+) 「Unikraft/Mewz 型は不採用」という否定的決定も明文化され、将来
  「wasmtime を静的リンクして速くしよう」という提案が TCB を広げる方向だと
  気づかずに採用されるリスクを防げる。
- (−) 本 ADR は**設計方針の決定**であり、kototama を実際に Solo5 tender 上で
  動かす実装（tender バイナリ・application manifest 生成・hvt/spt 切替）は
  follow-up。現状の kototama はまだ host OS 上で直接動く前提のまま。
- (−) `aiueos/90-docs/adr/0001` の古い kototama 定義は本 ADR が superseded 宣言
  するのみで、ファイル自体は書き換えない（歴史的記録として保持）。読み合わせる
  際はこの ADR を優先すること。
- (−) seL4 の形式検証レベルの保証は依然 non-claim のまま（`high-assurance`
  プロファイルの前提条件が揃うまで）。tender パターン採用は TCB を小さくするが
  ゼロにはしない。

## References

- ADR-2607022200（aiueos の三層アーキテクチャ。本 ADR は Layer 3 の内部構造を
  具体化する形で refine する。supersede ではない）
- `com-junkawasaki/orgs/kotoba-lang/aiueos/90-docs/adr/0001-aiueos-phase0-capability-os.md`
  （旧 kototama 定義。本 ADR により superseded）
- `com-junkawasaki/orgs/kotoba-lang/kotoba/docs/ADR-kotoba-shell-aiueos-safety-clj.md`
  （accepted-in-progress、kotoba/kototama/aiueos/kotoba-shell 四語の正典）
- `com-junkawasaki/orgs/kotoba-lang/kotoba/docs/ADR-kotoba-shell-aiueos-safe-kotoba.md`
  （旧草稿、safety-clj に統合済み）
- `com-junkawasaki/orgs/kotoba-lang/kototama/README.md`
- Solo5: https://github.com/Solo5/solo5 、`docs/architecture.md`（tender/hvt/spt）
- Nanos: https://nanovms.com 、https://nanovms.com/dev/tutorials/running-wasm-unikernels
- Unikraft + WAMR: https://github.com/unikraft/app-wamr 、
  https://unikraft.cloud/blog/unikernels-and-wasm/
- Mewz: https://arxiv.org/html/2411.01129v1
- urunc: https://blog.cloudkernels.net/posts/wasm-urunc/
- seL4: https://sel4.systems
- `com-junkawasaki/orgs/kotoba-lang/aiueos/SECURITY.md`（`deployment-profiles.md` の
  research/sensitive-local/regulated/high-assurance 段階と hvt/spt の対応）
