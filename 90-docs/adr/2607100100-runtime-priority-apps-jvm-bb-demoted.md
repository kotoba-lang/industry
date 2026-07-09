# ADR-2607100100: app 互換性と第一 runtime の順序 — kotoba wasm > clojurewasm > ClojureScript > nbb（JVM / bb は降格）

- **Status**: accepted（オーナー指示 2026-07-10。CLAUDE.md 同日改訂済み）
- **Related**: ADR-2607100030（addendum 2 が本規則の模範実装 —
  `kotoba.kami-host` の ClojureScript 前提化）、CLAUDE.md
  「`.cljc` / `.kotoba` ランタイム優先順位」節（2026-07-06 初版 →
  2026-07-07 改訂 → 本 ADR で 2026-07-10 改訂）。

## Context

2026-07-07 改訂の優先順位（kotoba wasm > clojurewasm > ClojureScript >
nbb、JVM 最後の手段）は存在したが、(1) それが「app の互換性と第一の
runtime の順序」であることが明文でなく、(2) `bb`（babashka）の位置付けが
未定義だった。実際、kami-survivors の初回実装（ADR-2607100030）は host を
JVM `.clj` + Chicory 前提で書いてしまい、事後に ClojureScript 前提へ
組み替える手戻りが発生した（addendum 2）。

## Decision（repo wide）

1. **app の互換性と第一の runtime の順序は
   `kotoba wasm runtime` → `clojurewasm` → `ClojureScript` → `nbb`。**
   新しい app / library / `.cljc` / `.kotoba` は、この順で第一級 runtime
   を決め、reader-conditional・依存選定・テストの正本を揃える。
2. **`JVM` と `bb` はその下に降格（app runtime としてはどちらも最後の
   手段）。** 上位 runtime で動くものを JVM / bb 前提で書かない。JVM / bb
   にしか無い経路（Chicory テストハーネス、既存 JVM 専用 lib への互換層
   など）は「互換 (compat) 層」として明示的に隔離し、設計の前提にしない。
3. **スコープは app / library の runtime 選定。** リポジトリ運用 tooling
   （`scripts/*.bb`・`.claude/hooks/*.bb`・west 拡張）はインフラであり
   一斉移行しない（移行するなら対象を決めて別 ADR。新規スクリプトは
   nbb で書けるなら nbb を優先）。既存 JVM 専用 lib のリトロアクティブな
   書き直しもしない（従来どおり対象を決めて ADR 化してから）。

## Consequences

- CLAUDE.md 当該節を 2026-07-10 改訂として更新（本 ADR と同時 landing）。
- 判断に迷ったら kotoba wasm → clojurewasm → cljs → nbb →（降格: jvm/bb）
  の順で「今実際に動く経路」を確認して選ぶ。clojurewasm は 2026-07-10
  時点で host-import を要する guest をホストできない（upstream Phase-16
  待ち、ADR-2607100030 addendum 2 の実測）ため、その形の app は当面
  ClojureScript に落ちる。
- 模範実装: `kotoba.kami-host`（portable `.cljc` core、第一経路 =
  ClojureScript（browser ESM / nbb）、`:clj`/Chicory は互換専用と明記）。
