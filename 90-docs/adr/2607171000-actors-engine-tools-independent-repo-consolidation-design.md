# ADR-2607171000: 20-actors 全独立リポ化 + engine/tools 分割の設計

## Status

Proposed（設計・実現性評価）。オーナー指示「20-actors は全て独立 repo になるように設計して、また engine, tool なども repo を分割できるか確認して」「compat 系は kotoba-lang org または etzhayyim org に移行済みでは? 調査確認して」を受けた設計。実行は本 ADR 合意後にフェーズ分割で行う（bulk 実行は下記の硬い前提を満たすまで不可）。先例: yabai の consolidation（ADR-2607170900）。分割ツール: ADR-2606231200（`bb actor:publish`）。

## 進捗ログ（2026-07-16 実行）

| 増分 | 内容 | commit（etzhayyim/root） | 状態 |
|---|---|---|---|
| Phase 1 core | compat vendored コピー **844 件除去** + `COMPAT-MOVED.md`（844 行マッピング） | `13a9871` | ✅ landed |
| Phase 2 #1 | 陳腐化 compat-corpus **py 装置 23 件 prune** + 死んだ bb.edn task 2 削除 | `d5969d5` | ✅ landed |

**方針転換（オーナー指示 2026-07-16）**: Python は deprecated・prune 対象、load-bearing なものは cljs(nbb) で再実装。

**残务（多セッション）**:
- **Phase 1 tail**: 未移行 compat **181**（s\* 中心の移行バッチ tail）を `bb actor:publish` で kotoba-lang `com-*` 化 → 除去。hand-deepened `salesforce/stripe` 2 は個別判断で保持中。
- **Phase 2 本体**: 残り py **1242**（70-tools 1099 + 20-actors 143）を per-file 分類（obsolete → prune / load-bearing → nbb 再実装）。耐久 SSoT（`00-contracts/schemas/cleanroom-*.json/edn`）は保持。wave11 の cljc contract テストは無傷（Phase 1 は cljc をゼロ除去）、py driver が要るなら nbb 版を作る。
- **Phase 0/2/3/4** は設計どおり（共有ライブラリ git-dep 化 → 非 fleet actor 173 除去 → fleet runner 改修 → 未 split 67）。
- **engine/tools split-now**: leaf 群（`kotoba_iso20022`/`legal-*-wasm-guest`/`baien-wasm-ternary`/`kami-apps`/`etzhayyim-py`/`clj-murakumo-langchain`）は未独立リポ化で新規作成が必要。

## Addendum 2026-07-16（Phase 1 実行時の追加ブロッカー — 実行前検証で発覚）

`/loop 5フェーズを進めて` の Phase 1（compat 844 の stale コピー除去）を実行しようとした際、**設計時に「低リスク」と評価した前提が不十分だったことが判明**。除去前検証（`os.listdir` 消費者 + drift）で以下を確認し、bulk 除去は**保留**した:

1. **コーポラ存在依存の消費者が 6-7 本**: `70-tools/{register_cleanroom_actors,evaluate_maturity,build_capability_indexes,cognitive_actor_injector,langgraph_maturation_agent,auto_pilot_orchestrator}.py` が `os.listdir(20-actors)` で `endswith("-compat")` を列挙する。ns-require はゼロ（Agent B 調査どおり）だが、**ディレクトリ存在**に依存する。特に `register_cleanroom_actors.py` は actors-v1 kotoba graph の登録 seed を生成。いずれも CI/bb.edn 非配線（one-shot）だが、除去すると再生成時にコーポラが縮む。
2. **loose verify タスク**: `bb verify-wave:test/report` → `70-tools/verify_wave11.py`（CI 非配線だが bb.edn task 実在）。1000-actor コーポラを対象とする。
3. **drift 実在**: サンプルした compat（8th_wall/aave）は独立リポ（kotoba-lang `com-*`）と **4 ファイル差分**（README/deps.edn/`.well-known/did.json`/manifest 等 identity・metadata 系）。generated ゆえ非 load-bearing の可能性が高いが、除去前に per-batch で「独立リポ側が authoritative かつ vendored 固有の実質変更なし」を確認する必要がある。

**Phase 1 の改訂前提**: bulk 除去の前に (a) 上記コーポラツールを「manifest / 独立リポ列挙」駆動に移行するか one-shot 生成物として明示 retire、(b) `verify_wave11.py` を独立リポ基準に再配線、(c) drift の 4 ファイル差分が identity 再生成のみであることを batch 検証。これらが済むまで compat 除去は実行しない。→ **Phase 1 は「低リスク最大物量」ではなく「コーポラツール移行が先行する中リスク」に格上げ。** 真に無ブロッカーの着手先は engine/tools の split-now leaf 群（`kotoba_iso20022`/`legal-*-wasm-guest`/`baien-wasm-ternary`/`kami-apps`/`etzhayyim-py`/`clj-murakumo-langchain`、Agent C 調査で source 消費者ゼロ）。

## Context — 現状は「新規作成」でなく「重複除去」

調査で判明した最重要事実: **「20-actors を独立リポ化」は約 80% が既に完了している。** 独立リポは既に存在し west 登録済みで、`orgs/etzhayyim/root/20-actors/` に残るのは drift した stale な vendored コピー群（yabai と同型の二重存在）。

### 定量（`20-actors` 1267 非 symlink dirs）

| 区分 | 総数 | 独立リポ有（削除可能な stale コピー） | 未 split |
|---|---|---|---|
| compat → **kotoba-lang** `com-<base>` | 1027 | **845** | 182 |
| actor → **etzhayyim** `com-etzhayyim-<name>` | 240 | **173** | 67 |
| **計** | 1267 | **1018** | **249** |

- **compat の移行先は kotoba-lang org**（`<base>-compat` → `com-<base>`）。実在確認: `com-stripe`/`com-adobe`/`com-aave`/`com-anthropic` は PUBLIC・直近 push・west 登録済み（manifest に `com-*` 1047 エントリ）。
- **actor の移行先は etzhayyim org**（`com-etzhayyim-<name>`、manifest に 181、GitHub に 188）。
- vendored コピーは独立リポと **drift**（例: `stripe-compat` vs `kotoba-lang/com-stripe` は README/deps.edn 相違）。

### 二重存在を生んだ原因

分割ツール `bb actor:publish`（`70-tools/src/etzhayyim/actor_publish.cljc`、ADR-2606231200）は独立リポ作成・west 登録まではするが、**`20-actors/<name>` の vendored コピーを削除しない**。yabai の consolidation（ADR-2607170900、削除 + MOVED marker）が唯一の重複除去先例で、これを一般化するのが本設計。

### compat の性質（ADR-260607）

1027 の `-compat` は `scaffold_wave*.py` による**機械生成**（600→1000 波）。テンプレート均一・相互依存ゼロ・runtime caller ゼロ・browser-WASM 実行（`manifest.json: "kind":"compat","exec":"browser-local|donated-mesh"`）。cells.edn に不在。fleet 非稼働。hand-deepened は `salesforce-compat`/`stripe-compat` のみ。

## 削除安全性を左右する結合（重要）

`bb.edn :paths` が唯一の classpath（`deps.edn` は 1.5MB の registry SSoT で classpath でない）。`:paths ["20-actors" "20-actors/kotodama/src" "70-tools/src" "70-tools" ...]`。

1. **classpath レイアウト**: `20-actors` が flat に載るため、**actor dir 名 = top-level namespace**（`20-actors/sukashi/cell.cljc` → `sukashi.cell`）。独立リポは `<name>/` サブディレクトリ構造を classpath 上に再現しないと `<name>.methods.*` が解決しない（yabai も `yabai/methods/` を保持）。compat は例外で `src/` self-rooted（再現不要）。
2. **共有ライブラリ（全 split が要る）**: `kotoba.datom`（36 actor が使用、`20-actors/kotodama/src`）、`etzhayyim.ie-flow.{metrics,score,gate-adapter}`（`70-tools/src`）、`moyai.ledger`。**これらを先に git-dep ライブラリ化しないと、依存する actor を split できない。**
3. **actor 間結合（co-split 必須の塊）**: energy クラスタ（`energy_order` → mio/yudane/toi/tawami/okibi、`mio` → yudane/toi/tawami/okibi）は全て fleet セル。他に credits→shomei(9)、ainori→todoke、tokigusuri→hokorobi 等。hub: danjo・shomei・kotodama・energy 群。
4. **fleet セルの解決断裂（硬い前提）**: runner（`lite_runner.cljc fire-cell`）は cells.edn の `:module "<name>.cell"` を `(require)` で解決し、失敗すると python fallback → それも失敗すると**セルが毎 tick 静かに error 化**（クラッシュせず）。actor を `20-actors` から外すと `<name>.cell` が解決不能に。**yabai が安全だったのは cell module が `kotodama.primitives.yabai_murakumo`（別）で `yabai.cell` でなかったため。** 13 の fleet セル actor（chie/iriai/kafun/kaname/mimamori/mio/moyoshi/okibi/sukashi/tawami/toi/tsubasa/yudane）は **runner の classpath を git-dep 化するまで削除不可。**

## Decision

### A. 20-actors — フェーズ分割の consolidation

**Phase 0（前提整備）**: 共有ライブラリを独立化する — `kotoba.datom`（kotodama）、`etzhayyim.ie-flow.*`（70-tools）、`moyai.ledger` を git-dep として消費可能にする。これ無しに依存 actor は split/consolidate できない。

**Phase 1（compat の stale コピー除去、低リスク・最大の物量）**: 独立リポ（kotoba-lang `com-<base>`）が存在する **845 の `-compat` vendored コピーを `20-actors` から除去**。安全ゲート（自動化）: ①west 登録済み独立リポが存在 ②`cell.cljc` 無し（fleet 非稼働）③runtime caller ゼロ（compat は保証済み）④独立リポが vendored コピー以上に新しい（drift 照合、hand-deepened な salesforce/stripe は個別確認）。除去は per-batch で `MOVED` marker を残すか、compat は生成物なので `20-actors/COMPAT-MOVED.md` の集約 marker + 除去。**未移行 182 は `bb actor:publish` で kotoba-lang `com-<base>` 化してから除去。**

**Phase 2（非 fleet actor の stale コピー除去）**: 173 の split 済み actor のうち **cells.edn に無いもの**を、yabai と同じ per-actor consolidation（drift 照合 → 削除 → MOVED marker）で除去。

**Phase 3（fleet セル actor、要 runner 改修）**: 13 の fleet セル actor は、先に runner を改修してから consolidate:
- `deploy_node.py` + runner 起動を、各 fleet actor 独立リポを node に配置しその `<name>/` レイアウトを classpath に加える方式に変更（git-dep + マルチルート classpath）。
- energy クラスタは co-split（5 actor 一括 + 相互 git-dep）。
- 改修・検証（remote fire 確認）後に vendored コピー除去。

**Phase 4（未 split 67 actor）**: `bb actor:publish` で `com-etzhayyim-<name>` 化 → 上記フェーズに合流。

### B. `bb actor:publish` ツールの修正

split と同時に vendored コピー除去 + MOVED marker を出すオプションを追加（yabai ADR-2607170900 の手順を内蔵）。これにより今後の split が二重存在を生まない。drift 照合ゲートも組み込む。

### C. engine/tools 分割（依存方向: 両者は leaf、split 可能）

`40-engine`・`70-tools/src` は leaf（engine は build 成果物として消費、tools/src は root bb.edn のみが消費）。分割順:

**40-engine**:

| subdir | 判定 | 理由 |
|---|---|---|
| kotoba_iso20022 / legal-aid-wasm-guest / legal-comms-wasm-guest / baien-wasm-ternary / kami-apps | **split-now** | 自己完結（Cargo/pyproject）、source 消費者なし、成果物のみ消費 |
| root-router | split-later | `50-infra/k8s/*.yaml` が名前 hardcode → 2 yaml re-point 要 |
| svelte（design-system/auth） | split-later | `60-apps/*/tailwind.config.js` が dist を deep-relative import + pnpm workspace → named npm package 化 + import 書換要 |
| cluster / llm | split-later | `50-infra/cluster/murakumo` / `ameno` actor との重複解消が先 |

**70-tools**:

| group | 判定 | 理由 |
|---|---|---|
| etzhayyim-py / clj/murakumo-langchain | **split-now** | 完全自己完結、消費者なし |
| etzhayyim-cli / e7m family / lexicon-to-* / cdn / baien 訓練群 | split-later | pnpm-workspace + 少数 ref の re-point で可 |
| **70-tools/src**（244 cljc） | keep-in-root（or split with heavy re-point） | root bb.edn の `:paths`/`:extra-paths` 多数に配線、最高摩擦 |
| scripts / integration-tests / config / loose 保守スクリプト | keep-in-root | monorepo 自体を対象とする plumbing |

**前提整備**: `pnpm-workspace.yaml:65-77` の除去済み submodule（`40-engine/kotoba`, `kami-engine`）への stale ref を先に掃除。

## Consequences

**Good**: source of truth 一本化、drift 撲滅。約 80% は既存リポ活用で新規作成不要。leaf の engine/tools は低リスクで段階分割可能。

**Risk / 硬い前提**:
- **fleet セル 13 actor は runner classpath 改修が完了するまで削除不可**（削除すると heartbeat が静かに error 化）。Phase 3 の前提。
- 共有ライブラリ（`kotoba.datom` 他）の git-dep 化が Phase 0 の前提。未了だと依存 actor が壊れる。
- compat 845 の drift 照合は物量大（per-repo 比較）。hand-deepened（salesforce/stripe）は独立レビュー。
- compat を「1027 個別リポ」として維持するか再集約するかは別論点（生成物ゆえ regenerable、個別 isolation の便益は薄い）。ただし既に 845 が個別リポとして存在するため、本設計の即時対象は vendored コピー除去のみ。

**未確定（要オーナー判断）**: (a) fleet runner 改修（Phase 3）の着手可否、(b) 未 split 249 を全部 split するか（特に compat 182）、(c) compat の個別リポ維持 vs 再集約、(d) engine/tools の split-now 群を今実行するか。

## Alternatives considered

1. **1267 を一括 bulk 除去** — 却下。fleet セル 13 が静かに死ぬ + 共有ライブラリ未整備で依存 actor 破綻。フェーズ分割必須。
2. **compat を 1027 個別リポに split** — 実質済み（845 存在）だが、生成物ゆえ本来は grouped/regenerable が妥当。再集約は別 ADR。即時対象は vendored 除去のみ。
3. **engine/tools を触らない** — 却下（オーナーが分割可否確認を明示指示）。leaf ゆえ split-now 群は安全。

## References

- ADR-2607170900（yabai consolidation — 本設計の一般化元）
- ADR-2606231200（`bb actor:publish` sovereign split ツール、`70-tools/src/etzhayyim/actor_publish.cljc`）
- ADR-260607（clean-room 600→1000 compat 生成、`scaffold_wave*.py`）
- classpath: `orgs/etzhayyim/root/bb.edn :paths`
- 共有ライブラリ: `20-actors/kotodama/src/kotoba/datom.cljc`、`70-tools/src/etzhayyim/ie_flow/*`、`50-infra/etzhayyim-moyai-credit/src/moyai/ledger.cljc`
- fleet runner: `50-infra/cluster/murakumo/cell-runner/{lite_runner.cljc,cells.edn,deploy_node.py}`
- engine/tools 判定の根拠 grep は本 ADR 調査ログ（fan-out agent 3本）
