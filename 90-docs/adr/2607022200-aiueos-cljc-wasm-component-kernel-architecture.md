# ADR-2607022200: aiueos を「CLJC 意味論 + Wasm Component Model 境界 + kernel（native adapter）」の三層として固定し、kotoba-lang 言語設計への準拠を明文化する

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

aiueos は当初（ADR-0001〜0008、`aiueos/90-docs/adr/`）単一の Rust crate として設計され、
manifest/policy/broker/audit のロジックも Rust 側に置かれていた。ところが直近
（2026-07-01〜02）で `aiueos/README.md` は「aiueos は Rust runtime crate をもう持たない」
と書き換わり、manifest・policy 判定・broker plan・audit receipt・
`aiueos/component` Wasm Component Model 境界の**意味論の正本は `aiueos-cljc-contract`
（CLJC）に移った**。しかし、この pivot 自体を決定として固定した ADR がこれまで無く、
0001〜0008 と、CLJC 移行前の Rust ソースパスを参照したまま open になっている
2606290900（signer lifecycle gap）・2606290930（capability bridge gap）が、
新旧どちらの実装基盤を前提にしているか曖昧なままだった。

一方、`kotoba-lang/kotoba-lang` は言語設計として以下を既に ADR で確立している:

- **`ADR-kotoba-lang-profile`** — 言語契約は EDN プロファイル（`lang/profile.edn`）・
  fixtures・docs であり、特定コンパイラ実装（`kotoba-clj`）や Rust パッケージングから
  独立している。
- **`ADR-safe-capability-language`** — 最安全は「強い言語」でなく「攻撃されても
  何もできない実行環境」。capability confinement（deny-by-default・capability gate・
  effect gate・subset gate・reproducible build）が第一原理で、Rust ownership 型の
  強さより優先される。
- **`docs/lang/capability-values.md`** — 「resource string ≠ capability」。capability は
  class・action set・resource constraint・issuer/delegation ref・expiry・
  grant-binding id・receipt id を持つ scoped-authority object であり、opaque な
  i64 handle として host-call 時に解決される。
- **`ADR-kotoba-package-cid-lock`** — package 参照は name+version だけでは
  non-conforming。repo-identity CID・signed manifest CID・source-tree CID・
  publisher DID 署名・granted capability set まで pin する。
- **`ADR-kotoba-lang-foundational-stdlib`** — zero-third-party-dep `.cljc` の
  4 層 stdlib（Layer1 data → Layer2 cap/effect(`kotoba-lang/wit`) → Layer3 I/O →
  Layer4 tooling）。`kotoba-lang/wit` は WIT（WebAssembly Interface Types）を
  plain EDN データとして表現し、deny-by-default の mintable capability token
  （`{:wit/capability "kap:kv/get" :wit/effects #{:read}}`）を提供する。
- **`docs/rust-migration-inventory.md`** — 「Kotoba の言語・プロトコル意味論は Rust を
  source of truth として書いてはならない。Rust は明示的にアダプタ専用と定めたリポジトリ、
  または legacy 参照の `kotoba-v2025` にのみ残ってよい」という org 全体の方針。
  `aiueos` 自身もこの inventory で「Rust runtime removed; CLJC/EDN contracts remain」
  と記録されている。

すでに `aiueos-cljc-contract/resources/aiueos/component_boundary.edn` は
`:aiueos/adapter :wasm-component-model` を宣言し、import/export を型付き capability
ポートとして定義済みで、実質的に Wasm Component Model 境界として実装されている。
だが「CLJC が正本 / Wasm Component Model が境界 / kernel は将来の native adapter に
過ぎない」という全体アーキテクチャと、それが kotoba-lang の言語設計にどう従うのかを
一箇所に固定した文書が無かった。ADR-2606271304（aiueos vs Zephyr positioning）は
「Zephyr との違い」を扱うが、「kotoba-lang 言語設計との整合」は扱っていない。

## Decision

### 1. 三層アーキテクチャとして固定する

```
Layer 3: kernel（現状: host OS 上の broker + wasmtime runtime。
                 将来: native microkernel, Phase 6/7）
           ↑ capability 名で呼ばれるだけ。意味論の正本には決してならない（adapter-only）
Layer 2: Wasm Component Model 境界
           = aiueos-cljc-contract/resources/aiueos/component_boundary.edn
           （:aiueos/adapter :wasm-component-model、import/export は
             kotoba-lang/wit 語彙の capability token で型付け）
Layer 1: CLJC/EDN 意味論の正本
           = aiueos-cljc-contract/src/aiueos/contract.cljc
           （manifest / policy / grant / audit / run-plan / run-receipt の
             validator・スキーマ。実行ロジックを持たない純粋データ検証）
```

Rust・JavaScript・Python・Svelte・将来の native microkernel は、すべて **Layer 1/2 の
消費者（adapter）** であって意味論の正本にはならない
（`aiueos-cljc-contract/README.md`: "Rust, JavaScript, Python, Svelte, or
host-specific code may consume these contracts as adapters/providers elsewhere,
but they are not authority here."）。これは `rust-migration-inventory.md` の
org 全体方針をそのまま aiueos に適用したものであり、ADR-0006 で示された
「Phase 6 microkernel は native（Rust/Zig）」という将来計画とも矛盾しない —
**microkernel が native で書かれることと、それが意味論の正本になることは別**で、
本 ADR は後者を明確に否定する。

### 2. kotoba-lang 言語設計への準拠点を具体化する

| kotoba-lang の原則 | aiueos への適用 |
|---|---|
| capability は resource string でなく型付き opaque handle（`docs/lang/capability-values.md`） | aiueos manifest の capability 文字列（`net/fetch`, `topic/publish` 等）は `kotoba-lang/wit` の `{:wit/capability ... :wit/effects #{...}}` 語彙に将来一本化する方向とし、2606290930（capability bridge gap）の解決方向として採用する |
| package/component は CID + DID 署名で pin（`ADR-kotoba-package-cid-lock`） | aiueos の signed manifest（ADR-0003、現状は flat な ed25519 signer registry）は、`kotoba-rad-git-sovereign-repo` 系の CID/DID lock モデルへ寄せる方向とし、2606290900（signer lifecycle gap）の解決方向として採用する |
| 4 層 zero-dep `.cljc` stdlib（`ADR-kotoba-lang-foundational-stdlib`） | `aiueos-cljc-contract` は Layer 2（cap/effect）相当の位置づけとし、`kotoba-lang/wit` を re-implement せず依存する。aiueos 固有の語彙（manifest/policy/audit の形）だけを追加する |
| capability confinement が第一原理、言語の強さより優先（`ADR-safe-capability-language`） | aiueos の deny-by-default 能力モデル・IOMMU 必須の DMA ルール（ADR-0001/0002）は、この原理の OS レイヤでの具体化として位置づけを再確認する。既存決定の変更ではなく整合の明文化 |
| Rust は adapter 専用、意味論の正本にしない（`rust-migration-inventory.md`） | Layer 3（現状の broker/runtime、将来の native microkernel）は恒久的に adapter 実装であり続ける。将来 microkernel を Rust/Zig で書いても本方針は変わらない |
| CLJC は JVM/SCI/CLJS/GraalVM/kotoba-Wasm 可搬（org 全体の `.cljc` 方針） | `aiueos-cljc-contract` は zero third-party runtime dep を維持し、ホスト（JVM/ブラウザ/kotoba-Wasm）を問わず同一の validator を使う |

### 3. 既存 ADR の位置づけ再確認（decided vs deferred）

| フェーズ | 状態 | 備考 |
|---|---|---|
| 0001 capability-component 基盤 | **decided/実装済**（記述は Rust 時代のままだが、意味論は本 ADR で CLJC 正本に更新） | kernel は明示的に未着手と明記済み |
| 0002 host ABI + topic bus | **decided/実装済** | named per-topic capability・real driver は引き続き deferred |
| 0003 signed manifests | **decided/Phase1 実装中** | flat signer registry。CID/DID 化は本 ADR の 2606290900 解決方向で follow-up |
| 0004 code-as-data admit | **decided/Phase2 実装中** | 変更なし |
| 0005 multi-surface providers | proposed（Phase3, 未実装） | 変更なし |
| 0006 scheduler/IO quota | proposed（Phase4, 未実装） | 「preemptive RT は Phase6 microkernel」の記述は本 ADR の Layer3=adapter-only 方針と整合 |
| 0007 virtual computer surface | **decided/実装済**（超プロジェクト ADR-2606290740 で確認） | Layer2/3 実装例として位置づけ |
| 0008 language runtimes as wasm components | **decided/実装済**（2026-07-01 accepted） | 非 kotoba 言語ランタイムも Layer2 の Wasm component であることを既に規定済み。本 ADR の三層モデルと整合 |
| kernel（native microkernel, 実 MMIO/DMA/IRQ） | **明示的に未着手**（Phase 6/7） | 本 ADR は「着手するとしても adapter-only」という制約のみを固定し、実装や着手時期は約束しない |

### 4. 既存 ADR との関係

- **ADR-2606271304**（aiueos vs Zephyr positioning）— 変更なし。本 ADR は
  「kotoba-lang 言語設計との整合」を扱う直交する軸で、Zephyr 比較を補完する。
- **ADR-2606290740**（computer surface, isolated computer-use）— 実装済みの
  Layer2/3 の具体例として位置づけを再確認。パスの記述が旧 org taxonomy
  （`orgs/com-junkawasaki/aiueos/...`）のままな点は別途 follow-up。
- **ADR-2606290900**（signer lifecycle gap）/ **ADR-2606290930**（capability
  bridge gap）— 依然 open。本 ADR は解決の**方向性**（CID/DID lock・
  kotoba-lang/wit capability token への統合）を確定させるが、実装そのものは
  follow-up として残す。両 ADR が参照する Rust ソースパスは CLJC pivot 後の
  ものに更新が必要。

## Consequences

- (+) 「aiueos の正本はどこにあるか（CLJC）」「境界は何か（Wasm Component
  Model / component_boundary.edn）」「kernel は何であって何でないか（native
  adapter・意味論の正本ではない）」が一つの ADR に固定される。
- (+) 2606290900 / 2606290930 の未解決ギャップに、kotoba-lang 側で既に確立済みの
  CID-lock・capability-value モデルという具体的な解決方向が与えられる（実装は
  別途 follow-up）。
- (+) 将来 Phase 6/7 で microkernel に着手する際、「native だが adapter 専用」
  という制約が事前に固定されているため、Rust/Zig 実装が誤って意味論の正本に
  なることを設計段階で防げる。
- (+) `aiueos/90-docs/adr/0001〜0008` を書き換えずに、CLJC pivot という
  事後的な意味論変更を後方互換に説明できる（各 ADR は accepted 当時のフェーズ
  記録として有効なまま）。
- (−) 本 ADR はアーキテクチャの位置づけの固定であり、2606290900 /
  2606290930 の実装そのもの・kernel Phase6/7 着手・0005/0006 の実装完了は
  含まない。いずれも follow-up。
- (−) `aiueos/90-docs/adr/0001〜0007` の一部記述（Rust ソースパス・
  `Broker::admit` 等の Rust API 表記）は歴史的記録として残るため、CLJC 正本と
  読み合わせる際に「当時は Rust 実装だった」という文脈補足が要る。

## References

- `com-junkawasaki/orgs/kotoba-lang/aiueos/README.md`
- `com-junkawasaki/orgs/kotoba-lang/aiueos/90-docs/adr/0001-aiueos-phase0-capability-os.md`
- `com-junkawasaki/orgs/kotoba-lang/aiueos/90-docs/adr/0002-host-abi-topic-bus.md`
- `com-junkawasaki/orgs/kotoba-lang/aiueos/90-docs/adr/0003-signed-manifests.md`
- `com-junkawasaki/orgs/kotoba-lang/aiueos/90-docs/adr/0004-code-as-data-admit.md`
- `com-junkawasaki/orgs/kotoba-lang/aiueos/90-docs/adr/0005-multi-surface-providers.md`
- `com-junkawasaki/orgs/kotoba-lang/aiueos/90-docs/adr/0006-scheduler-and-io-quota.md`
- `com-junkawasaki/orgs/kotoba-lang/aiueos/90-docs/adr/0007-virtual-computer-surface.md`
- `com-junkawasaki/orgs/kotoba-lang/aiueos/90-docs/adr/0008-language-runtimes-as-wasm-components.md`
- `com-junkawasaki/orgs/kotoba-lang/aiueos-cljc-contract/README.md`
- `com-junkawasaki/orgs/kotoba-lang/aiueos-cljc-contract/src/aiueos/contract.cljc`
- `com-junkawasaki/orgs/kotoba-lang/aiueos-cljc-contract/resources/aiueos/component_boundary.edn`
- `com-junkawasaki/orgs/kotoba-lang/kotoba-lang/docs/adr/ADR-kotoba-lang-profile.md`
- `com-junkawasaki/orgs/kotoba-lang/kotoba-lang/docs/adr/ADR-safe-capability-language.md`
- `com-junkawasaki/orgs/kotoba-lang/kotoba-lang/docs/adr/ADR-kotoba-package-cid-lock.md`
- `com-junkawasaki/orgs/kotoba-lang/kotoba-lang/docs/adr/ADR-kotoba-lang-foundational-stdlib.md`
- `com-junkawasaki/orgs/kotoba-lang/kotoba-lang/docs/lang/capability-values.md`
- `com-junkawasaki/orgs/kotoba-lang/kotoba-lang/docs/rust-migration-inventory.md`
- `com-junkawasaki/orgs/kotoba-lang/wit/README.md`
- ADR-2606271304（aiueos vs Zephyr positioning）
- ADR-2606290740（computer surface, isolated computer-use）
- ADR-2606290900（kotoba-aiueos key lifecycle / revocation gap）
- ADR-2606290930（kotoba-aiueos capability bridge gap）
