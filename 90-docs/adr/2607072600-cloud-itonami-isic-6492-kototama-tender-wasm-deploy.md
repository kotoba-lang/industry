---
id: adr-2607072600-cloud-itonami-isic-6492-kototama-tender-wasm-deploy
title: "ADR-2607072600: cloud-itonami-isic-6492 の DTI affordability check を .kotoba へ移植し kototama.tender(JVM/Chicory) 経由で実行確認 — kototama.tender ホストでの初の非フィクスチャ actor ロジック実行、かつ host import ゼロ(純粋演算)の初事例"
status: accepted
doc_type: adr
topic: cloud-itonami-isic-6492-kototama-tender-wasm-deploy
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - "cloud-itonami-isic-6492(与信)の credit.registry/compute-debt-to-income-ratio + affordability-ceiling(0.43) を .kotoba wasm-compilable subset へ移植した実例が orgs/cloud-itonami/cloud-itonami-isic-6492/wasm/ に存在すること"
  - "kotoba wasm emit の実際のWASMコード生成対象(compile-wasm-expr)が対応する演算子の確定リスト: 特殊形式 do/let/if、および + - * quot / rem mod = < > <= >= zero? not inc dec のみ — pos?/neg?/and/or/when は非対応(unsupported-op)。tree-walking interpreter(eval-form)は pos?/neg?/not をサポートするが、wasm生成側とは異なるサブセットであることの実測確認"
  - "kototama.tender(JVM/Chicory)経由で、kotoba-lang/kototama自身のデモfixture(sha256-hex/gen-keypair)以外の、他repoの実business logicをホストした初の実例であること(ADR-2607062330 addendum 5 の対象範囲を拡張)"
  - "host import を一切要求しない(純粋演算のみの).kotoba モジュールが実際にコンパイル・実行できることの実測確認(ADR-2607072530のllm-infer capability経由の事例とは異なる、capability不要パスの実証)"
  - "同一の .kotoba 成果物(affordability.wasm)が kototama.tender(JVM/Chicory)と wasm-webcomponent(Node.js)の両ホストで動作し、かつ実 murakumo fleet ノード(asher)上での実行確認も得ていること(Addendum参照)"
related:
  - 90-docs/adr/2607062330-kototama-tender-chicory-execution-runtime.md
  - 90-docs/adr/2607072530-cloud-itonami-kototama-wasm-llm-infer-poc-isic-6511.md
  - 90-docs/adr/2607071320-cloud-itonami-credit-6492-coverage.md
supersedes: []
superseded_by: []
---

# ADR-2607072600: cloud-itonami-isic-6492 の DTI affordability check を kototama.tender へ wasm デプロイ

**Status**: accepted
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（オーナー指示「cloud-itonami の kototama wasm デプロイ」を受けて着手）

## Context

セッション冒頭の調査で、cloud-itonami（銀行/決済業者actor群）は実コードを持つが `.kotoba`→WASM→kototama デプロイの実績は無い、と判明していた（`orgs/gftdcojp/cloud-itonami` および `orgs/cloud-itonami/*` の各 ISIC vertical repo に `.kotoba` ファイル・`kototama`参照・`.wasm` 成果物ゼロ）。オーナーから「cloud-itonami の kototama wasm デプロイ」を明示的に選択され、着手した。

作業中、**並行セッションが同種の作業をより大きなスコープで既に main へ着地させていたことを発見した**（ADR-2607072530、2026-07-07 17:04 着地 — 本ADR起票時点で本セッションの作業と時間的にほぼ並行）。ADR-2607072530 は cloud-itonami-isic-6511（生命保険underwriting）を対象に、新設の `llm/infer` capability（id 225）を kotoba-core-contracts に追加し、`kototama.tender`(JVM)と `wasm-webcomponent`(Node.js)の両ホスト実装を用意した上で、**murakumo fleet の実ノード(asher)へ Node.js 経由で実配備**した（JVM がフリート全ノードに存在しないため）。同ADRは「このモノレポで初めての、実fleetノード上での `.kotoba`→wasmコンパイル成果物の実行確認」と明記しており、これは正しい——**本ADRはその実績と競合しない**: 対象verticalが異なり(6492与信 vs 6511生保)、ホスト経路が異なり(本ADRは kototama.tender/JVM のみ、ADR-2607072530 の実機配備は wasm-webcomponent/Node.js)、capability要件が異なる(本ADRはhost importゼロの純粋演算、ADR-2607072530は新設llm-infer capability経由)。`cloud-itonami-isic-6511` 自身の `deps.edn` を確認したところ kototama への依存は無く、その repo 自身のテストスイートに `kototama.tender` 経由の実行確認は含まれていない（`kototama` 側の31 tests/60 assertionsは新設capability自体の単体テストであり、isic-6511固有のwasmモジュールをkototama.tender でホストするテストではない）。したがって「`kototama.tender`(JVM/Chicory)経由での、kototama自身のfixture以外のactor実行」という点に限れば、本ADRが実測上その初例である。

また、独立に発見した `.kotoba` コンパイラの実バグ（`pos?`/`neg?`/`and`/`or`/`when` が WASM コード生成側で `unsupported-op` になる — tree-walking interpreter は `not`/`zero?`等をサポートするが生成側は狭いサブセット）は、ADR-2607072530 が独立に発見した `and`/`or`/`when` の同一バグと一致する。2セッションが独立に同じ実測結果へ到達したことは、このコンパイラの制約が repro 可能な事実であることの追加確証になる。

## Decision

**`credit.registry/compute-debt-to-income-ratio` + `affordability-ceiling`(0.43、Reg Z 12 CFR §1026.43 ATR/QMルールの一般的な適格性しきい値を参考にした与信可否判定)を `.kotoba` の wasm-compilable subset へ移植し、`kotoba wasm emit` で実WASMバイナリへコンパイルし、`kototama.tender`(JVM/Chicory)でホストして実行確認した。**

### 移植内容（`orgs/cloud-itonami/cloud-itonami-isic-6492/wasm/affordability.kotoba`）

- `{:keys [...]}` map destructuring → 素の位置引数3つ(existing-debt/requested-amount/annual-income)。wasm生成側はmapを扱えない。
- `(pos? annual-income)` → `(> annual-income 0)`（`pos?`はwasm生成側 unsupported-op）。
- 3つの `throw`/`ex-info` precondition guard → 削除（WASM exportは JVM例外を投げられない。falsy値 `0` を返す形に置換）。
- 比率を計算して `0.43` と比較 → `100 * (existing-debt + requested-amount) <= 43 * annual-income` という整数の相互乗算に置換（除算・浮動小数点を完全に回避。`kotoba-card`/`kotoba-banking` が採る「金額は最小通貨単位の整数」という既存規約と整合）。

コンパイル結果: 143バイトの実WASMバイナリ（`\0asm` マジック確認済み）、host import 0件、export `main`（0-arity、i32戻り値）。`main` は承認されるべきケースと却下されるべきケースの両方を内部でハードコードして判定し、両方が正しく判定された場合のみ `1` を返す自己検証型の設計（パラメータ化された呼び出しABIは未整備——follow-up）。

### 実行確認

1. `kotoba wasm run`（`kotoba-lang/kotoba` CLI自身のランタイム）で `main` が `1` を返すことを確認。
2. `orgs/cloud-itonami/cloud-itonami-isic-6492/test/wasm/affordability_test.clj` で `kototama.tender/run-main`（`[]` importを要求、`(contract/host-caps {})` — capability grant無し）を使い、同じ結果を確認。cloud-itonami-isic-6492 の既存 32 tests（credit.* 5 namespaces）と合わせて全て green（129 assertions）。
3. `clojure -M:lint`（clj-kondo）errors 0 / warnings 0。

### manifestへの登録は行っていない

`manifest/repos.edn`（line 1284 のコメント）に明記の通り、`cloud-itonami-isic-6492` を含む cloud-itonami-* blueprint repo群は **west project ではない**（他の cloud-itonami-* blueprint repo と同様）。よって本ADRの変更は cloud-itonami-isic-6492 自身の repo への push のみで完結し、superproject の `manifest/west.yml`/`repos.edn` への変更は無い。

## Consequences

- `kototama.tender`（JVM/Chicory）経由で、kototama自身のfixture以外の実business logicが動くことを実測で示した——ADR-2607062330 addendum 5 の「証明済みなのは kotoba-lang/kototama 自身の fixture のみ」という限定を、別repoのactorロジックについて初めて破った。
- host import ゼロ（純粋演算のみ）の `.kotoba` モジュールが実際にコンパイル・実行できることを確認——ADR-2607072530 の llm-infer capability 経由の事例とは独立した、capability不要パスの実証。
- `.kotoba` コンパイラの `pos?`/`neg?`/`and`/`or`/`when` 非対応は、ADR-2607072530 との独立発見により再現性が確認された実バグ。本ADRはコンパイラ本体の修正はしない（ADR-2607072530 も同様にfollow-up扱い）。

## What this ADR does NOT decide

- `.kotoba` コンパイラの `pos?`/`neg?`/`and`/`or`/`when` 対応化（follow-up、ADR-2607072530 と共通）。
- パラメータ化された呼び出しABI（現状は2シナリオをハードコードした自己検証のみ）。
- cloud-itonami-isic-6492 の他のgovernorロジック（HARD違反チェック等、mapを扱う複雑な部分）のwasm化。

## Addendum (2026-07-07, same day): murakumo fleet 実機(asher)への配備、完了

上記「What this ADR does NOT decide」に記載していた「murakumo fleet 実機への配備は未着手」を解消した。

`orgs/cloud-itonami/cloud-itonami-isic-6492/wasm/verify_node.mjs` として、ADR-2607072530 が確立した経路（`kotoba-lang/wasm-webcomponent` の `actor-host.js`、plain Node.js、JVM不要）を踏襲したスクリプトを作成。`affordability.wasm` は host import ゼロのため、ADR-2607072530 の `underwriting_decision.wasm`（`log-write`+`llm-infer` 要求、5バイトのシナリオ入力をメモリに書き込む）より単純——capability grant無し、入力バイト書き込み無しで `main()` を直接呼ぶだけで済んだ。

手順: ローカルで `orgs/kotoba-lang/wasm-webcomponent/src/`（21ファイル）と本ADRの成果物一式を west と同じ sibling-checkout レイアウトのまま `rsync` で `asher:/tmp/` へ転送（`ssh asher "node --version"` で v26.4.0 稼働中——ADR-2607072530 が残置した Node.js が今も生きていることを確認済み）→ `ssh asher "node wasm/verify_node.mjs"` を実行 → ローカル実行と完全一致する `{"result": 1, "ok": true}` を得た → 転送した一時ファイルのみ削除（Node.js自体は前ADRの方針を継続し残置）。

これにより、本ADRの kototama.tender(JVM) 側の実行確認と合わせて、cloud-itonami-isic-6492 の同一 `.kotoba` 成果物が **JVM/Chicory ホストと Node.js/wasm-webcomponent ホストの両方、かつ実 murakumo fleet ノード上** で動くことを確認した。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-6492/wasm/affordability.kotoba`, `wasm/affordability.wasm`, `wasm/README.md`, `wasm/verify_node.mjs`
- `orgs/cloud-itonami/cloud-itonami-isic-6492/test/wasm/affordability_test.clj`
- `orgs/kotoba-lang/kotoba/src/kotoba/runtime.clj`（`compile-wasm-expr`、実際のWASMコード生成対象演算子）
- `orgs/kotoba-lang/wasm-webcomponent/src/actor-host.js`（Node.jsホスト実装）
- ADR-2607062330（kototama.tender、Chicory実行基盤）
- ADR-2607072530（cloud-itonami-isic-6511、llm-infer capability、murakumo fleet実機配備の先例）
