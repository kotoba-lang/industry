# ADR-2607171100: 番号レイヤ (00-〜90-) を west multirepo の運用ルールへ再設計 — layer = repo 境界 + メタデータ、旧 root は解体

**Status**: accepted（運用ルール確定。Phase 0 freeze + Phase 1 dedup tranche 1 +
Wave 3 lint は 2026-07-17 実行済み。Wave 2 は保留 — 下記）
**Date**: 2026-07-17
**Deciders**: Jun Kawasaki (+ Claude, オーナー指示により設計・実行)
**Scope**: superproject（`manifest/` 運用全体）、`orgs/etzhayyim/root`、
`orgs/gftdcojp/ai-gftd-apps-gftdcojp`、および番号ディレクトリを内包する全子リポ
**Builds on**: ADR-2606271500（west manifest 採用）、ADR-2606272237（api-single-entry
workflow）、ADR-2607022900（pin remote verification）、ADR-2607102200 addendum 6
（`:archived` group 隔離）、ADR-2607171000（engine split-now —
`40-engine/kami-apps` → `etzhayyim/kami-apps` 移設、`kami-apps-MOVED.md` tombstone 前例）、
`manifest/kotoba-workspace.edn`（`:ownership :repo-per-library`）
**Related**: `manifest/kotoba-boundaries.edn`（cell-prefix-routes = actor 抽出の対応表）、
etzhayyim/root PR #3129、ai-gftd-apps-gftdcojp PR #1520、
`90-docs/2607171100-phase1-dedup-worklist.csv`（残作業 207 行）

## Context

単一 monorepo 時代の番号レイヤ規約（`00-contracts / 10-protocol / 20-actors /
30-graph / 40-engine / 50-infra / 60-apps / 70-tools / 80-data / 90-docs`）が、
west manifest monorepo（`manifest/west.yml`、1,751 projects、`repos.edn` →
`gen-west-manifest.cljs` 生成）へ移行した現在も、旧 root リポジトリの
**ディレクトリ構造として**残存している:

- `orgs/etzhayyim/root`（github.com/etzhayyim/root、計 ≈1.7GB）:
  00-contracts 73M / 10-protocol 6.4M / 20-actors 74M / 30-graph 308K /
  40-engine 3.2M / 50-clients 4K（空残骸）/ 50-infra 444M / **60-apps 954M
  （576 entries、大半 `etzhayyim-project-*` scaffold）** / 70-tools 92M /
  80-data 49M / 90-docs 54M
- `orgs/gftdcojp/ai-gftd-apps-gftdcojp`（gftdcojp の旧 root）:
  00-contracts / 10-protocol / 20-actors{defense, jp-ashiba, livecam, magatama,
  oshikatsu, shinshi, smishing, stripe} / 50-infra / 60-apps / 70-tools /
  80-data / 90-docs
- 内部に番号 dir を持つ単体 app repo: `app-aozora`、`app-aozora-boundary`、
  `club-shinshi`、`com-etzhayyim-kenchi`（実測、grandfathered）

一方で抽出は既に部分進行している: `orgs/etzhayyim/com-etzhayyim-*` は約 180 repo
あり、**`root/20-actors` の 242 dir 中 170 は抽出済み counterpart が存在する
二重実体**。gftdcojp も `gftd-*-actor`（audio/avatar/illust/motion/rig/sculpt/
talent/voice）や `ai-gftd-*` として個別 repo 化が進む。engine は
ADR-2607171000 が `kami-apps` を「独立リポ + git rev 依存（path 依存禁止）+
MOVED tombstone」の型で先行移設済み。

問題は 5 点:

1. **二重実体** — 同一 actor が root 内 dir と個別 repo の両方に居て、どちらが
   正か機械判定できない（20-actors で 170 件）。
2. **番号レイヤ規律の執行不能** — レイヤはディレクトリ命名規約でしか表現されて
   おらず、multirepo では「00 ← 10 ← 20 …」の依存方向を守らせる仕組みが無い。
3. **checkout コスト** — root 1.7GB が west update のたび全員に付いてくる。
4. **境界ルールの分裂** — `kotoba-workspace.edn` は `:repo-per-library` を宣言
   済みだが kotoba-lang org にしか適用されておらず、etzhayyim/gftdcojp には
   旧規約が事実上残る。
5. **登録手順の不在** — 「engine/actor を新設するときどこに置くか」の答えが
   org ごとに違う（root 内 dir に足すのか、個別 repo を切るのか）。

## Decision

### D1. レイヤは「ディレクトリ」をやめ「repo 境界 + メタデータ」に住み替える

番号 00–90 の**分類軸そのものは廃止しない**。住む場所を 3 つに移す:

1. **repo 粒度**: 1 レイヤ内の 1 コンポーネント = 1 repo（`:repo-per-library`
   を全 org に一般化）。
2. **west groups**: 各 project は `groups: [<org>, <layer-group>]` の 2 軸を持つ。
   layer-group は `gen-west-manifest.cljs` が `manifest/layers.edn`（D6）の
   routing から生成する。`west update --group-filter +layer-actor` のような
   層単位の操作が可能になる。
3. **依存方向 DAG**: 番号の大小関係が担っていた「上位層は下位層に依存してよい、
   逆は禁止」を D3 の layer DAG として明文化し、lint で執行する。

レイヤ対応表（旧 dir → 新運用）:

| 旧 dir | layer group | repo 命名規約 | 例 |
|---|---|---|---|
| 00-contracts | `layer-contracts` | `<org>-contracts`、`*-lexicons`、schema/policy 束 | `etzhayyim-contracts` |
| 10-protocol | `layer-protocol` | プロトコル名そのまま 1 repo | `xrpc`、`did-etzhayyim`、`warifu` |
| 20-actors | `layer-actor` | etzhayyim: `com-etzhayyim-<domain>`（既存規約）/ gftdcojp: `gftd-<x>-actor` | `com-etzhayyim-aburi`、`gftd-voice-actor` |
| 30-graph | `layer-graph` | graph コンポーネント名 | `kagami`、`kg-appview`、`kg-projector` |
| 40-engine | `layer-engine` | engine 名（kami-* 系は既に個別 repo） | `kami-engine`、`kami-apps` |
| 50-infra | `layer-infra` | `*-infra`、deploy/authz/PDS 系 | `etzhayyim-atproto-pds-clj` |
| 60-apps | `layer-app` | `app-*` / `*-app` / product 名 | `app-aozora` |
| 70-tools | `layer-tools` | `*-tools`（workspace 横断 tool は superproject `70-tools/` に残留可） | — |
| 80-data | `layer-data` | `*-datoms` / 大容量は DataLad（`datalad` group、B2 annex） | `global-energy-datoms` |
| 90-docs | `layer-docs` | ADR は superproject `90-docs/adr` に集約（既存慣行）。org 固有 doc は各 repo 内 `90-docs/` | — |

`50-clients`（4K の空残骸）は対応 layer を作らず削除。client SDK 類は
`layer-protocol`（*-client）に吸収する。

### D2. repo **内部**の番号 dir は `90-docs/` と `80-data/` の 2 つだけ許可

- `90-docs/`（とくに `90-docs/adr/`）は repos-with-adr 慣行として既に定着 — 残す。
- `80-data/` は fixture / 小容量データ置き場として許可（大容量は DataLad へ）。
- **`00-` 〜 `70-` を新規 repo 内に作ることは禁止**。コードのレイヤ分割が
  必要になった時点で、それは repo 分割のシグナルである（D5 の登録フローへ）。
- superproject root 自身の `70-tools/` `90-docs/` は workspace 装置として例外
  （manifest 運用スクリプト・ADR 集約の置き場。旧 monorepo の名残ではない）。
- 既存単体 app repo（app-aozora 等 4 repo、`layers.edn`
  `:grandfathered-internal-dirs` に実測登録）の内部番号 dir は Phase 4 で
  低優先の平坦化対象。新規追加のみ即時禁止。

### D3. 参照は west import + deps で行う（vendoring / path 依存の禁止）

**west 側（repo の登録と materialize）:**

- flat な生成 `manifest/west.yml` を **per-org submanifest + `self: import:`**
  に分割する:

  ```
  manifest/west.yml          # 骨格: remotes / defaults / group-filter / self.import
  manifest/orgs/etzhayyim.yml    # generated — etzhayyim org の projects のみ
  manifest/orgs/gftdcojp.yml     # generated — 同上
  manifest/orgs/kotoba-lang.yml  # …org ごと
  ```

  生成器は引き続き `gen-west-manifest.cljs` 一本（source of truth は
  `repos.edn` + git の事実、手書き禁止は不変）。`--entry` の最小 diff は
  「当該 org ファイル 1 つの当該 entry 行」にさらに局所化され、org を跨ぐ
  並行編集が構造的に衝突しなくなる。pin 検証（ADR-2607022900:
  exists-upstream / reachable / fast-forward）と api-single-entry workflow
  （ADR-2606272237）はファイルが分かれるだけで不変。
- **project-level import（子 repo が自分の manifest を持つ分散型）は不採用**。
  pin が子 repo 側に散らばると verify-west-pins の single-writer モデルが壊れ、
  fast-forward 検証・optimistic lock（blob SHA 一致 PUT)が成立しない。
  import は「manifest repo 内のファイル分割（self.import）」に限定する。

**deps 側（コードの参照）:**

- **workspace 内の Clojure 依存は相対 `:local/root` で参照する。**
  `orgs/<org>/<repo>` という west の materialize パスが :local/root の ABI
  である（cloud-itonami の `../../kotoba-lang/*` 前例）。したがって west path
  の変更は breaking change として扱い、rename は manifest workflow
  （kenchi-actor→kenchi-clj 前例）に従う。
- **バージョンは deps に書かない。** workspace snapshot における唯一の版は
  west pin（revision SHA）である。単一バージョン原則。
- **workspace 外で消費される repo / 単独 CI** は `:git/url` + `:git/sha` 座標
  を使い、**sha は west pin と一致させる**（検証: `verify-layer-deps.cljs` の
  将来拡張）。Rust は kami-apps 前例どおり git rev 依存とし path 依存を禁止。
- **ソース vendoring 禁止**は `:no-library-source-vendoring`
  （kotoba-workspace.edn）を全 org に拡大適用。

**依存方向 DAG（旧番号順序の後継、`layers.edn` に機械可読で持つ）:**

```
layer-contracts  ← layer-protocol ← {layer-actor, layer-graph, layer-engine} ← layer-app
layer-infra      … どの層からも依存されない（deploy 対象を知る側）
layer-tools      … 同上（開発時のみ）
layer-data       … 被依存のみ（データは誰にも依存しない）
layer-docs       … 依存なし・被依存なし
```

執行: `scripts/verify-layer-deps.cljs`（本 ADR と同時に配置）— 各 repo の
`deps.edn` から `:local/root` エッジを抽出し、(a) layer DAG 違反、
(b) repo 内 `00-`〜`70-` dir（frozen/grandfathered 以外）、
(c) `:local/root` が `orgs/` の外を指す壊れ、の 3 点を lint する。
`--report` でレポートのみ（exit 0）。

### D4. 旧 root 2 つの解体 — 4 Phase

**Phase 0 — freeze（実行済み)**: `etzhayyim/root` と `ai-gftd-apps-gftdcojp` の
番号 dir 配下への新規コード追加を禁止。両 repo の CLAUDE.md 冒頭に宣言し、
CI guard（`.github/workflows/layer-freeze-guard.yml` — 新規追加ファイルが
番号 dir 配下なら fail、`*-MOVED.md` と削除は許可）を配置。

**Phase 1 — dedup（tranche 1 実行済み)**: `20-actors/<name>` ↔
`com-etzhayyim-<name>` の対応ごとに、root 側 dir の全 blob（git SHA、path
非依存）が counterpart repo の remote default branch tip に包含されるかを
機械判定。包含されるものは root 側 dir を削除して `<name>-MOVED.md` tombstone
を置く（kami-apps-MOVED.md の型）。root 側にしか無い blob を含むものは
port 待ち worklist へ（`90-docs/2607171100-phase1-dedup-worklist.csv`）。

**Phase 2 — extract（残り actor + 他レイヤ)**: 履歴ごと移す。

1. `git filter-repo --subdirectory-filter <dir>`（または subtree split）で
   履歴付き抽出
2. `gh repo create <org>/<name>` → push
3. workspace へ checkout: `orgs/<org>/<name>`
4. `nbb scripts/gen-west-manifest.cljs --entry <name>`（pin 検証つき登録）
5. root 側 dir 削除 + tombstone

  対象と行き先: `10-protocol/*`（xrpc, did-etzhayyim, warifu, signal* … 各 1 repo）、
  `40-engine/*` 残り（root-router, llm, cluster …）、`00-contracts`
  （etzhayyim-contracts 1 repo に束ねる — lexicon/schema/policy は単一版で
  配布する価値が高い）、`30-graph/*`（kagami, kg-appview, kg-projector）、
  `50-infra/*`（etzhayyim-* 単位）、`70-tools`（etzhayyim-tools 1 repo）、
  `80-data`（サイズで振り分け: 小 → repo、大 → DataLad + B2 annex）、
  `90-docs`（ADR は superproject `90-docs/adr` へ、設計 doc は行き先 repo へ随伴）。

**`60-apps`（954M / 576 entries）だけは全抽出しない**: `etzhayyim-project-*`
scaffold 群を「稼働中 / 参照価値あり / 死蔵」に仕分けし、稼働中のみ repo 化、
残りは `:archived` group の archive repo（ADR-2607102200 addendum 6 の隔離
機構で既定 `west update` 対象外）へ一括退避する。576 個の repo を機械的に
作ることはしない（west 側 1,751 → 2,300+ projects への膨張は登録・pin 検証
コストに見合わない）。

**Phase 3 — thin root**: 解体後の root は charter/governance
（CHARTER-RIDER.md, COUNCIL.md, MEMBERS.md, LICENSE, DONATE.md）+ README +
tombstone 群だけの「org の顔」リポとして残す。コードゼロ。将来的に不要なら
`:archived` へ。`ai-gftd-apps-gftdcojp` も同型（20-actors の残 dir は
Charter Rider §2 EXCLUDE マーク付き stub を含むためポリシー判断が先）。

**Phase 4 — 単体 app repo の内部平坦化（低優先)**: grandfathered 4 repo
（app-aozora / app-aozora-boundary / club-shinshi / com-etzhayyim-kenchi）の
内部番号 dir を `src/` 等へ。D2 の新規禁止だけ先行、平坦化は各 repo を触る
機会に順次。

### D5. 新規 engine / actor repo の登録フロー（標準 6 手順）

1. **命名**: D1 の layer 表に従う（actor なら `com-etzhayyim-<domain>` /
   `gftd-<x>-actor`）。layer が決まらない = 設計が未成熟のサイン。
2. `gh repo create <org>/<name>`（private/public は org 慣行に従う）
3. workspace へ checkout: `orgs/<org>/<name>`
4. `nbb scripts/gen-west-manifest.cljs --entry <name>` — pin 検証を通して
   最小 diff 登録
5. `manifest/layers.edn` の naming に合致することを確認（合致しない命名は
   routing 追記が必要）
6. 消費側 repo の `deps.edn` に相対 `:local/root` を追加
   （workspace 外消費があるなら `:git/url`+`:git/sha` alias も）

### D6. 機械可読な taxonomy: `manifest/layers.edn`（新設）

layer 一覧・repo 命名 routing・依存方向 DAG・repo 内 dir 許可リスト・
grandfather 実績・freeze 対象を 1 ファイルの EDN で持つ。
`gen-west-manifest.cljs`（groups 付与、Wave 2）と
`verify-layer-deps.cljs`（lint、配置済み）の共通入力。

## Consequences

- 二重実体のうち機械判定可能な 41 件は解消済み。「どちらが正か」は west
  manifest に登録がある側、と機械判定できるようになった。
- root checkout ≈1.7GB は Phase 2/3 完了時に数 MB の thin root になる。
  60-apps の仕分け（576 entries）が移行コストの最大の塊で、ここだけは人の
  判断（稼働中/死蔵）が要る。
- レイヤ規律（依存方向・命名・内部 dir 禁止）が lint で執行可能になった。
  初回全スキャン（1,960 repos / 1,542 エッジ）で **DAG 違反 0**、壊れた
  `:local/root` 3 本（cloud-itonami-isic-855、`../../../` の 1 階層過剰）を検出。
- 新設 repo が増える分、登録は D5 の 6 手順に固定化される（root に dir を
  足す逃げ道は Phase 0 の CI guard で塞がれる）。
- deps.edn の相対 `:local/root` が ABI になるため、west path の rename は
  これまで以上に「manifest workflow に従う breaking change」として扱う。
- layer 未解決 repo が 1,644/1,960（cloud-itonami-isic-* や kotoba-lang lib 群
  など naming glob 未定義）— DAG lint はこれらを素通しする。routing の拡充は
  今後の課題（kotoba-lang は kotoba-workspace.edn の規律が既にある）。

## Implementation Waves

- **Wave 1（完了 2026-07-17)**: `manifest/layers.edn` 配置、両 root の
  CLAUDE.md へ freeze 宣言 + `layer-freeze-guard.yml` CI guard
  （etzhayyim/root PR #3129、ai-gftd-apps-gftdcojp PR #1520、マージ済み。
  west pin も API single-entry で前進済み）。
- **Wave 2（保留)**: `gen-west-manifest.cljs` の self.import 分割出力 +
  layer group 付与。**実行保留の理由**: west.yml は並行セッションが
  API single-entry で能動編集中（本 ADR 実行中にも optimistic lock 409 を
  2 回観測）。分割はファイル形式の cutover なので、repos.edn
  `:manifest-workflow` 記載の手順・verify-west-pins.cljs・
  west_annex.cljs・関連 hook の同時更新を含む「静かな窓」での一括実施が必要。
- **Wave 3（完了 2026-07-17)**: `scripts/verify-layer-deps.cljs` 配置、
  初回全スキャン実施（結果は Consequences 参照）。lefthook / CI への配線は
  isic-855 の既存壊れ 3 本が解消されるまで保留（手動 / `--report` 運用）。
- **Wave 4（tranche 1 完了、残 worklist 化)**: Phase 1 dedup — 170 件中
  41 件削除 + tombstone。残 129 件（DIVERGED = root 側にのみ存在する blob
  あり）+ 72 件（NO_COUNTERPART）+ gftd 5 件（POLICY_HOLD）は
  `90-docs/2607171100-phase1-dedup-worklist.csv`。
- **Wave 5（未着手)**: Phase 2 extract、60-apps 仕分け、Phase 3 thin root 化。

## Verification Notes

2026-07-17 設計時点の事実確認:

- `manifest/west.yml` 1,751 projects / self.import 未使用（flat）を確認。
- `orgs/etzhayyim/root` の番号 dir 容量・`20-actors` 242 dir 中 170 抽出済み
  重複を `comm` で計測。
- kami-apps-MOVED.md（tombstone + git rev 依存 + west path 登録の型）を
  Phase 1/2 の前例として採用。

2026-07-17 実行記録:

- Phase 0 + Phase 1 tranche 1 は sibling worktree（`/tmp/etz-root-2607171100`
  等、ADR-2607011345 の topdir 分離に従い superproject の外）で実施し、
  PR 経由でマージ: etzhayyim/root #3129（merge bcb3b396）、
  ai-gftd-apps-gftdcojp #1520（merge 9d876b21）。
- dedup 判定は「root 側 dir の全 blob SHA ⊆ counterpart repo remote tip の
  blob SHA 集合」（path 非依存・gitlink 0 必須）。counterpart は各 repo の
  remote HEAD を fetch して比較（west 子リポの remote 名は `origin` でなく
  west remote 名である点に注意）。
- 41 dir 削除は tombstone 置換込みで 1 commit（4cca3fdcba）。freeze guard は
  tombstone 追加を許可するため self-consistent。
- west pin 前進は repos.edn `:manifest-workflow` の API single-entry
  （tip blob SHA 一致 PUT）で実施。compare API で fast-forward
  （behind_by:0）を事前検証。並行編集による 409 を 2 回観測し retry で成功
  （optimistic lock は設計どおり機能）。superproject commit dd775668 /
  ae4a4d22。
- gftd 20-actors の 5 dir は counterpart 未包含 + 「NOT MIGRATED — Charter
  Rider §2 violation (EXCLUDE)」マーク付き stub のため削除せず POLICY_HOLD。
- **未検証のまま残る**: DIVERGED 129 件の root 側差分の port（個別 repo への
  取り込み）、60-apps 576 entries の稼働中/死蔵仕分け、isic-855 の
  `:local/root` 修正、Wave 2 cutover。
