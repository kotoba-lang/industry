---
id: adr-2607141900-cloud-itonami-cljc-actors-kotoba-incompatibility-narrow-slice-porting-policy
title: "ADR-2607141900: cloud-itonami の .cljc actor は kotoba/kototama/kotoba-lang-compiler(.kotoba) と非互換であることを確定し、narrow-slice porting のみを承認パターンとする"
status: accepted
date: 2026-07-14
deciders:
  - Jun Kawasaki（「今の clouditonami repo では cljc を中心にしていますが、
    kotoba-lang/kotoba, kototama, compiler の .kotoba 拡張子でもちゃんと
    動くかな?」→（分析結果を受けて）「では kotoba, kototama,
    kotoba-lang/compiler を前提にした adr にまとめて」）
related:
  - 90-docs/adr/2607141600-kotoba-kotoba-lang-compiler-kototama-aiueos-consolidation-experiment.md（5リポジトリ責務境界、この ADR の前提）
  - 90-docs/adr/2607072530-cloud-itonami-kototama-wasm-llm-infer-poc-isic-6511.md（先行実証: 「そのまま wasm化することは不可能」の一次記録）
  - 90-docs/adr/2607072600-cloud-itonami-isic-6492-kototama-tender-wasm-deploy.md（先行実証: narrow-slice porting が実際に動いた唯一の実例）
  - orgs/cloud-itonami/cloud-itonami-isic-3811（本 ADR のための新規コード調査対象、wastecollect actor）
  - orgs/kotoba-lang/kotoba, orgs/kotoba-lang/kototama, orgs/kotoba-lang/compiler
supersedes: []
superseded_by: []
last_verified: 2026-07-14
doc_type: adr
topic: cloud-itonami-kotoba-compatibility
authoritative: true
authoritative_for:
  - "cloud-itonami の .cljc actor（langgraph-clj StateGraph + LLM advisor +
    独立Governor + atom/defrecord ベースの store）は、kotoba/kototama/
    kotoba-lang/compiler の `.kotoba` 実行系と現状非互換であるという確定診断
    （2つの独立した調査——cloud-itonami チーム自身の ADR-2607072530/2607072600
    と、本 ADR のための isic-3811 コード調査——が同じ結論に到達）"
  - "cloud-itonami で `.kotoba` を使う場合の唯一の承認パターンは narrow-slice
    porting（actor全体でなく、governor の純粋判定ロジック等の狭い一部分だけを
    手で `.kotoba` サブセットへ書き直す。ADR-2607072600 の isic-6492
    affordability 実例が現状唯一の実証済み前例）という位置づけ"
  - "`kotoba wasm emit` が実際に受理する演算子集合（`do`/`let`/`if` +
    `+ - * quot / rem mod` + `= < > <= >=` + `zero? not inc dec` のみ、
    `when`/`and`/`or`/`pos?`/`neg?` は未対応・main は0-arity固定）を、
    今後の narrow-slice porting 作業が参照すべき実測済み制約として記録する
    位置づけ"
  - "cloud-itonami の約900件の blueprint（ISIC/ISCO/COFOG/UNSPSC 等）に対し、
    `.kotoba` へのwholesale移植を行わないという不実施の決定"
---

# ADR-2607141900: cloud-itonami の .cljc actor は kotoba/kototama/kotoba-lang-compiler(.kotoba) と非互換であることを確定し、narrow-slice porting のみを承認パターンとする

**Status**: accepted
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki

## Context

ADR-2607141600 で kotoba-lang 内部の `.kotoba`/Wasm 実行系5リポジトリ
（kotoba-lang/kotoba-lang/compiler/kototama/aiueos）の責務境界を整理した
直後、オーナーから「cloud-itonami は `.cljc` 中心だが、`.kotoba` 拡張子
（kotoba/kototama/compiler 経由）でもちゃんと動くか」という具体的な適用可能性
の問いを受けた。

**調査1（本 ADR のための新規調査）**: `orgs/cloud-itonami/cloud-itonami-isic-3811`
（wastecollect actor、実装済み・8 `.cljc` ファイル）を直接読み、以下を確認した:

- `deps.edn` が `io.github.kotoba-lang/langgraph`（sibling org のサードパーティ
  ライブラリ）に依存し、`llm.cljc`/`operation.cljc` が `langchain.model`/
  `langchain.db`/`langgraph.graph` を `:require` している。
- `store.cljc` が `(defrecord DatomicStore ...)` と `(atom (assoc ...))`
  （`MemStore`）を使用——`compiler/` の `forbidden-heads` は `atom`/`ref`/
  `volatile!` を名指しで禁止しており、`defrecord` はどちらの実装
  （`kotoba/` の CLAUDE.md サブセット、`compiler/frontend.clj` の受理形式）
  にも存在しない。
- `#?(:clj ... :cljs ...)` reader-conditional は使っているが、分岐の中身が
  上記の禁止構文のままなので、`.cljc` という拡張子自体が受理される
  （profile v3 の compatibility extension）ことと、中身の文法が `.kotoba`
  サブセットに収まることは別問題である。

**調査2（既存の一次資料の再発見）**: cloud-itonami チーム自身が既に同じ問いを
2026-07-07 に検証済みだった:

- `ADR-2607072530`（isic-6511, llm-infer capability PoC）は「既存の
  langgraph-clj+langchain-clj 製actorをそのまま wasm化することは不可能と判明」
  と明記し、`.kotoba` に元々存在しない LLM 呼び出し用 capability
  （`llm/infer`, id 225）を `kotoba-core-contracts` に新設して初めて
  PoC が成立したことを記録している。同ADRはさらに、murakumo fleet
  （Mac mini 10台）の実行系が frozen Rust / JVM `kotoba.wasm-exec` /
  `kototama.tender` の3系統に分裂しており、全ノードに JVM があるわけでも
  ない、という運用面の分裂も記録している。
- `ADR-2607072600`（isic-6492, 信用審査 actor）は、governor の判定ロジック
  のうち `compute-debt-to-income-ratio` + `affordability-ceiling(0.43)`
  という**純粋計算部分だけ**を `.kotoba` へ**手で書き直し**、
  `kototama.tender`（JVM/Chicory）・`wasm-webcomponent`（Node.js）・
  実 murakumo fleet ノード（asher）の3経路で動作確認した——これが
  現状唯一の「`.kotoba` が実際に cloud-itonami のロジックを動かした」
  実例だが、"actor がそのまま動いた" のではなく "狭いスライスを移植した"
  結果である。同ADRは実装の実際の受理演算子集合も記録している:
  `do`/`let`/`if` + `+ - * quot / rem mod` + `= < > <= >=` +
  `zero? not inc dec` のみ。`when`/`and`/`or`/`pos?`/`neg?` は**未対応**
  （2つの独立した ADR で再現確認済みの実バグ——CLAUDE.md や
  `kotoba-lang/kotoba` の README・CLAUDE.md が記載する「サブセットに
  含まれる」という記述は設計意図であって、`kotoba wasm emit` の実装と
  一致していない）。`main` は0-arity固定で、実引数は export 済み
  linear memory の決め打ちオフセット（0/4/8、リトルエンディアン i32）
  経由で渡す ABI が必要——`kotoba wasm emit` が有引数 `main` を
  `:main-arity` で無条件拒否するための回避策。

2つの独立した調査（cloud-itonami チーム自身によるものと、本セッションによる
もの）が同じ結論に到達したため、これを ADR として確定する。

## Decision

1. **cloud-itonami の `.cljc` actor は `.kotoba` と非互換であることを確定
   する。** actor 全体（langgraph-clj StateGraph + LLM advisor + 独立
   Governor + atom/defrecord ベースの store）をそのまま `.kotoba` へ
   コンパイル・移行する取り組みは行わない。
2. **`.kotoba` を cloud-itonami で使う場合の唯一の承認パターンは
   narrow-slice porting とする**: actor 全体ではなく、governor の純粋な
   判定ロジック（例: 与信の affordability check）のような、外部依存・
   可変状態・LLM呼び出しを一切含まない小さな決定関数だけを対象に、
   `.kotoba` の実際の受理サブセットへ**手で書き直す**。ADR-2607072600
   の isic-6492 実例をテンプレートとする。
3. **今後の porting 作業は、ドキュメント上の期待サブセットでなく実測済みの
   制約を参照する**: `do`/`let`/`if` + `+ - * quot / rem mod` +
   `= < > <= >=` + `zero? not inc dec` のみが実際に動く。`when`/`and`/
   `or`/`pos?`/`neg?` は使わない（同等のロジックを `if`/比較演算子の
   組み合わせで書き直す）。`main` は0-arityのまま、実引数は
   linear memory 経由で渡す ABI を使う。
4. **LLM呼び出しを伴う advisor ロジックは porting 対象から除外する**——
   `llm/infer` capability（ADR-2607072530 で新設済み）を使えば技術的には
   可能だが、cloud-itonami の advisor/governor 分離という設計思想
   （advisor は信頼されない提案のみ、governor が独立に検閲）と、
   `.kotoba` の狭い capability-checked cell という設計思想は別物であり、
   本 ADR のスコープでは advisor 側の porting は対象外とする。
5. **cloud-itonami の約900件の blueprint（ISIC/ISCO/COFOG/UNSPSC 等）に
   対する `.kotoba` への wholesale 移植は行わない。** 実装が進むたびに
   個別に「この actor のこの決定ロジックを porting する価値があるか」を
   判断する、cloud-itonami 側の個別判断に委ねる。

## Consequences

正: `.kotoba` の適用範囲について、ドキュメント上の期待でなく2つの独立した
実測に基づいた明確な境界線ができた——今後 cloud-itonami の新規actor実装で
「`.kotoba` 化できるか」を都度ゼロから調査する必要がなくなる。狭い決定
ロジックの porting パターン（isic-6492）と、実際の演算子制約・ABI規約が
1箇所（本ADR + 参照先ADR）に記録された。

負: cloud-itonami の actor は当面 JVM Clojure（`.cljc` on JVM/cljs/nbb）の
ままであり、`.kotoba` の capability-confinement（deny-by-default、型付き
capability、fuel/memory limit）の恩恵を actor 全体としては受けられない。
murakumo fleet への配備は kototama.tender/JVM 経路に依存したまま
（frozen Rust / JVM kotoba.wasm-exec / kototama.tender の3系統分裂は
本ADRでは解消しない、別課題）。

## Alternatives Considered

- **`.kotoba` の言語サブセットを拡張して cloud-itonami の actor が動くように
  する**: 却下。`atom`/`defrecord`/外部ライブラリ依存/LLM呼び出しを
  受理するように `.kotoba` を拡張することは、`.kotoba` の存在意義である
  capability-confinement（deny-by-default、T1/T2/T3 の安全性ラダー）
  そのものと矛盾する——ADR-2607141600 が既に確認した通り、`.kotoba` は
  意図的に狭いサンドボックス言語であり、汎用アプリロジックの受け皿として
  設計されていない。
- **cloud-itonami の全 actor を書き直して `.kotoba` ネイティブにする**:
  却下。langgraph-clj StateGraph + advisor/governor 分離という設計は
  cloud-itonami の中核アーキテクチャであり、`.kotoba` の実行モデル
  （0-arity `main`、capability-injected host import のみ、状態は
  呼び出し間で保持しない）と根本的に形が異なる——書き直しは実質的に
  別アーキテクチャの新規実装になり、投資対効果が見合わない。
- **何もドキュメント化せず、都度 ADR-2607072530/2607072600 を読み直す
  運用にする**: 却下。2つの独立した調査が同じ結論に達したという事実
  自体が強いシグナルであり、今後の porting 判断の起点として1箇所に
  まとめる価値がある（ADR-2607141600 と同型の判断）。

## References

- 90-docs/adr/2607141600-kotoba-kotoba-lang-compiler-kototama-aiueos-consolidation-experiment.md
- 90-docs/adr/2607072530-cloud-itonami-kototama-wasm-llm-infer-poc-isic-6511.md
- 90-docs/adr/2607072600-cloud-itonami-isic-6492-kototama-tender-wasm-deploy.md
- orgs/cloud-itonami/cloud-itonami-isic-3811/deps.edn, src/wastecollect/{llm,operation,store}.cljc
- orgs/kotoba-lang/kotoba/README.md（`.kotoba` の言語意図・safe-Kotoba 三ゲート）
- orgs/kotoba-lang/compiler/src/kotoba/compiler/frontend.clj（`forbidden-heads` 等の実際の受理文法）
