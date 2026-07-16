# ADR-2607100100: app 互換性と第一 runtime の順序 — kotoba wasm > clojurewasm > ClojureScript > nbb（JVM / nbb は降格）

- **Status**: accepted（オーナー指示 2026-07-10。CLAUDE.md 同日改訂済み）
- **Related**: ADR-2607100030（addendum 2 が本規則の模範実装 —
  `kotoba.kami-host` の ClojureScript 前提化）、CLAUDE.md
  「`.cljc` / `.kotoba` ランタイム優先順位」節（2026-07-06 初版 →
  2026-07-07 改訂 → 本 ADR で 2026-07-10 改訂）。

## Context

2026-07-07 改訂の優先順位（kotoba wasm > clojurewasm > ClojureScript >
nbb、JVM 最後の手段）は存在したが、(1) それが「app の互換性と第一の
runtime の順序」であることが明文でなく、(2) `nbb`（babashka）の位置付けが
未定義だった。実際、kami-survivors の初回実装（ADR-2607100030）は host を
JVM `.clj` + Chicory 前提で書いてしまい、事後に ClojureScript 前提へ
組み替える手戻りが発生した（addendum 2）。

## Decision（repo wide）

1. **app の互換性と第一の runtime の順序は
   `kotoba wasm runtime` → `clojurewasm` → `ClojureScript` → `nbb`。**
   新しい app / library / `.cljc` / `.kotoba` は、この順で第一級 runtime
   を決め、reader-conditional・依存選定・テストの正本を揃える。
2. **`JVM` と `nbb` はその下に降格（app runtime としてはどちらも最後の
   手段）。** 上位 runtime で動くものを JVM / nbb 前提で書かない。JVM / nbb
   にしか無い経路（Chicory テストハーネス、既存 JVM 専用 lib への互換層
   など）は「互換 (compat) 層」として明示的に隔離し、設計の前提にしない。
3. **スコープは app / library の runtime 選定。** リポジトリ運用 tooling
   （`scripts/*.cljs`・`.claude/hooks/*.cljs`・west 拡張）はインフラであり
   一斉移行しない（移行するなら対象を決めて別 ADR。新規スクリプトは
   nbb で書けるなら nbb を優先）。既存 JVM 専用 lib のリトロアクティブな
   書き直しもしない（従来どおり対象を決めて ADR 化してから）。

## Consequences

- CLAUDE.md 当該節を 2026-07-10 改訂として更新（本 ADR と同時 landing）。
- 判断に迷ったら kotoba wasm → clojurewasm → cljs → nbb →（降格: jvm/nbb）
  の順で「今実際に動く経路」を確認して選ぶ。clojurewasm は 2026-07-10
  時点で host-import を要する guest をホストできない（upstream Phase-16
  待ち、ADR-2607100030 addendum 2 の実測）ため、その形の app は当面
  ClojureScript に落ちる。
- 模範実装: `kotoba.kami-host`（portable `.cljc` core、第一経路 =
  ClojureScript（browser ESM / nbb）、`:clj`/Chicory は互換専用と明記）。

## Addendum（2026-07-10、オーナー指示「rust は使わないで、これはちゃんと
rule に」— スコープ境界の明記）

Ghost Hacker ゲームポートフォリオ（ADR-2607023200）のFLOW向け実描画統合を
調査した際、既存の参考実装 `kami-app-animeka-timeline`
（`orgs/etzhayyim/root/40-engine/kami-apps/`）が「新規 Rust crate を書き、
`#[wasm_bindgen]` エントリポイント + カスタムレンダーパイプラインを
実装する」形だったため、同型の新規 Rust crate 作成が当然の次の一手のように
見えた。オーナーはこれを明確に却下: **本ADRの runtime 優先順位
（kotoba wasm → clojurewasm → ClojureScript → nbb）はいずれも「app/game
側」コードの選び方であり、Rust エンジン本体（`kami-render`/`kami-app`等）は
所与のインフラとして消費するだけの対象。個別アプリの描画要件を満たすために
新しい Rust crate を書き起こす選択肢は、この優先順位チェーンに含まれない。**

`kami-app-animeka-timeline` は本規則の明文化前のパターンであり、以後の
新規タスクでこれを模倣しない。描画が要る app/game は、既存 Rust エンジンが
既に露出済みの WASM/JS 境界があればそれをそのまま呼ぶだけに留め、無ければ
上記優先順位の範囲内で実現方法を探す——それでも描画ニーズを満たせない場合、
「Rust を新規に書く」ことで穴を埋めない。スコープを絞る（例: 当面は
DOM/CSS の視覚表現に留める）か、対象を決めて別途 ADR 化しオーナー判断を
仰ぐ。CLAUDE.md 当該節に同日中に同内容を追記済み。

## Addendum（2026-07-10、運用 tooling の実装完了）

Decision §3 で別 ADR 化を要した運用 tooling の対象を、オーナー指示により
**ルートの `scripts/`、`manifest/`、`70-tools/bmc/`** に確定した。対象の
`sh`/`nbb` スクリプトは `.cljs` へ移し、固定版 `nbb` を `package.json` と CI
で導入した。Babashka/JVM 固有の process・filesystem・JSON・curl 操作は
`scripts/nbb_compat` の Node.js 互換層に集約した。

West の Python 拡張 `west_annex.py` は `WestCommand` API に縛られるため、
Python のまま温存せず `manifest/west_annex.cljs` に置換した。従来の
`west annex-get/drop` は廃止し、等価な明示 CLI
`nbb manifest/west_annex.cljs annex-get|annex-drop [project …]` を正規経路と
する。`manifest/west-commands.yml` は Python 拡張を登録しない。

例外として `70-tools/scripts/rsi/` は PyTorch/TRL/PEFT と GPU 学習を実行する
Python ML パイプラインであり、Node/nbb への置換は同等性のある単純な runtime
移行ではない。オーナー指示により、この RSI パイプラインとその Python tests は
Python のまま維持する。これは app runtime の例外ではなく、外部 ML runtime を
明示的に必要とする運用 workload のスコープ除外である。
