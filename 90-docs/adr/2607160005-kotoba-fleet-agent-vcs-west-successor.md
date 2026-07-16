---
id: adr-2607160005-kotoba-fleet-agent-vcs-west-successor
title: "ADR-2607160005: kotoba-fleet — AI agent フリートのための west 後継 VCS 設計（kotoba-git/kotoba-rad + Radicle + Cursor/Zed 知見のゼロベース統合）"
status: accepted
doc_type: adr
topic: kotoba-fleet-agent-vcs-west-successor
authoritative: true
last_verified: 2026-07-16 (Phase 0 implemented and verified same day; see addendum)
authoritative_for:
  - "（accepted 時に）west manifest 体制の後継となる fleet-db / signed-pin / agent-namespace / governed-canonicalization の設計"
related:
  - 90-docs/adr/2607072200-kotoba-git-kotoba-rad-content-addressed-vcs.md
  - 90-docs/adr/2606280300-kotoba-rad-git-sovereign-repo.md
  - 90-docs/adr/2606271500-west-manifest-over-vcstool.md
  - 90-docs/adr/2607022900-west-pin-remote-verification.md
  - 90-docs/adr/2606302100-shallow-depth-west-hybrid-amendment.md
  - 90-docs/adr/2607011345-agent-worktree-west-topdir-fix.md
  - 90-docs/adr/2607050500-git-stash-shared-checkout-guard.md
  - 90-docs/adr/2607061600-kotoba-issue-ledger-shared-libs.md
supersedes: []
superseded_by: []
---

# ADR-2607160005: kotoba-fleet — AI agent フリートのための west 後継 VCS 設計

**Status**: accepted（2026-07-16 オーナー承認「ではフェーズを進めて」。Phase 0 実装済み — 下記 addendum）
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki（指示: 「west より効率的な git version 管理を kotoba-lang/kotoba, kotobase, radicle を元に zero から設計して、大量の repo を AI agent が効率的に作業する。Cursor の VCS や Zed の VCS なども調査」）

## Context

### 現行 west 体制の実測スケールとペインポイント（2026-07-16 調査）

- **規模**: 5 org / **west.yml ~1,669 project entries**（302 KB, 8,441 行。
  `manifest/README.md` の「35 project」は 48 倍陳腐化）。checkout 総量 ~40 GB、
  分布はバイモーダル（357 repo が <50 MB の軽量 cljc lib、15 repo が ≥200 MB。
  最大 manimani 16.2 GB）。west 1.5 の `west update` は **serial**（`-j` なし）。
- **構造的な事故クラス（全て実発生）**:
  1. **local working HEAD からの pin 生成 → 静かな pin 退行/未push pin**。
     `gen-west-manifest.cljs` はローカル HEAD で pin するため、子が遅れている・
     未 push だと壊れた pin を書く。44-repo 事故（`90852b86`、ADR-2607022900）、
     Wave-1 退行（ADR-2607021230: 21 pin 中 3 退行 + 2 unreachable + 3 未存在）。
  2. **shallow(depth 1) と pin 駆動 checkout の構造的相性の悪さ**。pin ≠ tip で
     checkout 失敗（2026-06-30 に 7 project）、graft 境界での ancestry 誤判定
     （純前進を diverged/forced と誤検出）→ 全ての判定を GitHub compare API に
     退避する運用になった（ADR-2606302100）。
  3. **west topdir 自動発見**により superproject 内 worktree が偽隔離になり
     共有 `orgs/` を書き換える（ADR-2607011345）。
  4. **共有 checkout + repo-global stash スタック**が並行 agent で危険
     （一晩で stash 20 個堆積、他人の stash を positional pop、ADR-2607050500）。
  5. **ガードが convention 止まり**: `.claude/hooks/` の guard 群は文書化された
     運用規約で、機械的には未インストール。強制は CI + 生成器内検証のみ。
  6. **302 KB の生成 YAML を編集の正経路にする無理**: single-entry API PUT +
     optimistic lock という緩和策自体が、行指向テキストを DB 代わりに使っている
     症状。repos.edn も 61 KB 単一行 datomized blob。

  本質的な観察: **今の運用ルール（server-side pin 検証・単一 entry 最小 diff・
  FF-only・force-push 禁止・worktree 隔離）は、あるべきプロトコル不変条件を
  人間向け文書と CI で後付けエミュレートしている**。設計をゼロからやり直すなら、
  これらを protocol layer の不変条件に落とすべきである。

### 外部調査の要点

**Radicle (Heartwood, 1.9.x, 2026-07 時点)** — 移植すべき 3 つのアイデア:
1. **per-writer ref namespace**: 1 repo = 1 共有 object DB + peer ごとの
   `refs/namespaces/<nid>/…`。各 peer は自分の namespace のみ書ける
   （shared-write モデル自体が存在しない）。
2. **sigrefs**: peer の全 ref スナップショットへの Ed25519 署名を commit chain
   として積む。**fast-forward のみ受理** = 改竄・巻き戻し不能な push 台帳。
   教訓: 1.7.0 以前の署名は parent を覆っておらず replay 攻撃が成立した
   （2026-03 開示）→ 署名 payload に parent CID を必ず含める。
3. **canonical ref = 純関数**: `refs/heads/main` は push 先ではなく
   「(namespaces × allow-set × threshold) の純関数」として全ノードが同一計算
   する（crefs rules = policy-as-data、identity doc の delegate quorum で統治）。
   AI agent fleet への写像は「worker は自分の namespace に push、Governor の
   quorum 鍵が canonical を前進」— threshold >1 は分散意思決定でなく
   鍵漏洩への defense-in-depth として効く。
   制約: LFS なし（sidecar 前提 = 本 repo の DataLad/B2 方針と一致）、CI は
   broker+adapter で発展途上、鍵 rotation 未実装、~8k repos / 600+ nodes の
   実績（数百 repo は実証済み envelope 内）。

**Cursor (2.0, 2025-10)** — 分離は **worktree-per-agent（local）と fresh VM
（remote）の 2 択に業界収束**（shadow workspace / kernel FS proxy は放棄）。
盗むべき運用ディテール: `.cursor/worktrees.json` 型の**宣言的 per-worktree
setup hook**、**worktree 自動 GC（interval + マシン上限、default 6h/25個）**、
conversation-scoped **checkpoint（git 外の ephemeral snapshot、自動掃除）**、
**best-of-N（同一プロンプトに 8 agent × 8 worktree → 1 個採用）を第一級
プリミティブに**。land-back は常に branch push + review-before-merge で、
agent 間 merge を自動では行わない。

**Zed / DeltaDB (2026-06 waitlist)** — 唯一「トポロジでなくセマンティクス」を
変えに行っている試み: 作業を stable identity 付き fine-grained delta の
operation log として記録、conflict-free replicated worktree（human+agent 同時
編集で収束保証）、会話と編集を並べて versioning。**git は「CI と外界接続の
ための projection」に降格**。ただし収束保証は syntactic merge のみで semantic
conflict は残るため、review-before-merge UX は維持される。

### 手元に既にある部品（kotoba-lang スタック、全て実テスト済み）

- **kotoba-git / kotoba-rad**（ADR-2607072200）: CID/DAG-CBOR の blob/tree/
  commit + arrangement-native object model、`ref-policy/fast-forward?`、
  RID（genesis CID）、hash-chained identity journal（chain 上）、Ed25519
  delegate、sigref、`authorize-push?` / `authorize-push-cacao?`（CACAO 委譲鎖、
  attenuation 検証込み）、p2p signed head-announce の E2E 収束テストまで済み。
  未配線（production surface なし）。
- **identity 三点セット**: `org-ietf-ed25519`（seed→did:key）、
  `org-chainagnostic-cacao`（委譲鎖 + attenuation + nonce anti-replay、147
  assertions）、`tech-ipfs-specs-ipns`（鍵=名前、signed head record +
  **monotonic sequence + rollback 拒否** — ref 更新プリミティブそのもの）。
- **datom plane**: `datom`/`prolly-tree`/`arrangement`/`chain`/`kotobase-peer`、
  kotobase-server の 21-method XRPC（`asOf`/`since`/`history`/`txRange`/`log`/
  `sync` = time-travel + tx-log surface）、`IStore` streams（append + seq cursor）。
- **governance 部品**: `kotoba-issue`/`kotoba-ledger`（issue→proposal→review→
  merge→audit、risk-tier 付き auto-merge、ADR-2607061600）、actor パターンの
  Governor ⊣ worker 構図、`kototama` actor:host ABI（sandboxed agent が
  sign / log-write できる deny-by-default 能力面）。
- **kotobase `code_graph`**: content-addressed causal commits + 明示的 3-way
  merge conflict + pin/revoke event + 非破壊 GC — 「コードの content-addressed
  VCS」の先行実装。

## Decision（proposed design）

**`kotoba-fleet` — west manifest 体制を、kotobase datom DB を正本とする
5 プレーン構成で置き換える。** git そのものは（DeltaDB と同じ判断で）捨てず、
**CI・GitHub・外界との interop projection に降格**させる。協調・pin・権限・
監査の正本は datom/merkle プレーンに移す。

```
┌ 5. sync/transport ── 並列 materialize、後に p2p seeding（GitHub は mirror 化）
├ 4. land-back ─────── agent namespace push + Governor quorum canonicalization
├ 3. workspace ─────── lazy materialization / worktree GC / checkpoint / best-of-N
├ 2. identity ──────── agent = Ed25519 did:key、CACAO 委譲鎖、sigref 監査鎖
└ 1. manifest ──────── fleet-db（kotobase datoms）: repo entity + signed pin
        ↕ projection
   west.yml / GitHub refs（互換期間中の生成物・interop 面）
```

### Plane 1 — manifest: repos.edn + west.yml → fleet-db（kotobase datoms）

- repo は datom entity になる: `:repo/name`（unique identity）、`:repo/org`、
  `:repo/path`、`:repo/groups`、`:repo/heavy?`、`:repo/datalad?`、
  `:repo/archived?`、`:repo/remote`、そして **`:repo/pin`**。
  既存の `*.datoms.edn` + append-only ledger パターン（BMC canvas-ledger と
  同型）をそのまま踏襲し、`kotobase-peer` の commit chain に載せる。
- **pin = ipns.head 形の signed head record** `{:name <repo> :value <sha>
  :sequence n :valid_until t}` + 署名。受理条件は protocol 不変条件として:
  ①署名者が当該 repo への pin-advance 権限（CACAO grant）を持つ
  ②sequence が単調増加（**rollback は署名検証と同じ層で拒否** — 今日の
  verify-west-pins Rule 2 の protocol 化）
  ③`:value` が上流 default branch から到達可能（admission gate。今日の Rule 1）。
- **「local working HEAD から生成」という概念自体を消す**: pin は「生成」
  されるものではなく「署名付きで transact される」もの。wholesale 再生成
  commit という事故クラス（44-repo 事故）は表現不能になる。
- 衝突は optimistic tx（kotobase の revision compare / 409 リトライ）で処理。
  302 KB YAML の textual merge も single-entry PUT の手順芸も不要になる。
- クエリが第一級になる: 「agent X の working set」「pin が upstream より
  遅れている repo」「heavy かつ datalad な repo」は datalog クエリ
  （`asOf`/`since`/`history` で pin の時系列監査も無償）。
- **互換期間中、west.yml は fleet-db からの生成 projection**（方向が今と逆:
  今は YAML が正で EDN が源、移行後は DB が正で YAML が読み取り専用ビュー）。
  `--check` は「projection が DB と一致するか」の検証に単純化される。

### Plane 2 — identity/authority: agent = 鍵、権限 = CACAO 委譲鎖

- **agent 1 体 = Ed25519 keypair 1 つ = did:key**（Radicle の NID と同型。
  発行は offline/permissionless — agent N 体目の起動にレジストリ不要）。
  actor パターンの RAD identity journal（`80-data/kotoba-rad/*.identity.
  journal.edn`）と同一線上に統合する。
- **権限は CACAO 委譲鎖で attenuate して配る**: owner root → Governor →
  agent。grant 例: `push:repo/kotoba-lang/*:refs/agents/<did>/*`（自分の
  namespace のみ）、`pin-advance:repo/<name>`。`cacao.core/verify-chain` の
  `covers?` が sub-delegation の権限昇格を既に構造的に禁止している。
  expiry を必須にする（漏洩鍵の失効手段。Radicle の「rotation 未実装、quorum
  で identity doc 編集」より軽い）。
- **agent の全 ref 変更は sigref chain（FF-only、署名付き）として記録** =
  per-agent append-only push 台帳。Radicle 1.7.0 replay CVE の教訓により、
  署名 payload は必ず parent（前 sigref の CID）を含む形にする
  （kotoba-rad.sigref を拡張。「parent-covering signatures を day 1 から」）。

### Plane 3 — workspace: 隔離・実体化・掃除（Cursor/Zed の運用知見）

- **worktree-per-agent は維持**（業界収束点であり CLAUDE.md 既定と一致）。
  ただし手作業規約から **workspace manager** に昇格させる:
  - **lazy materialization**: agent は working set を datalog クエリで宣言し
    （例: 「kotoba-ui とその依存閉包」）、該当 repo だけを signed pin の
    commit で sibling-path workspace に実体化する。**global topdir という
    概念を持たない**ので west の topdir 誤認クラスは表現不能になる。
    1,669 repo の全展開は不要になる（今日の実運用も事実上そうしている）。
  - **宣言的 setup hook**: repo ごとに `workspace.edn`（`.cursor/
    worktrees.json` の EDN 版）で checkout 後処理（deps、`.env` copy、
    codegen）を宣言。nbb で実行（sh 新規作成禁止ルールに従う）。
  - **自動 GC**: interval + マシン上限（Cursor default: 6h / 25 個）で
    worktree/checkpoint を回収。merge 済み branch・worktree の削除まで含めて
    「着地後の後片付け」を機械化（stash・worktree 無限増殖の症状を根絶）。
  - **checkpoint**: agent の編集前状態を git 外の ephemeral snapshot として
    conversation-scoped に保存（Cursor 型）。undo は commit より細粒度・安価
    であるべきで、git 履歴を汚さない。scratchpad 配下、自動掃除。
  - **best-of-N を第一級に**: 「N 個の使い捨て workspace を spawn し 1 個
    採用」を workspace manager の primitive にする（安価な実体化 + 積極 GC が
    前提条件で、上 2 項がそれを満たす）。
- **stash は語彙から消す**: WIP 退避は agent namespace への signed commit のみ
  （名前・所有者・履歴が付く）。共有 checkout という概念自体が縮退する
  （superproject 本体 checkout は「fleet-db の projection の 1 つ」になる）。

### Plane 4 — land-back: namespace push + governed canonicalization

- **agent は canonical ref（main / pin）をどこにも直接書けない**。書くのは
  自分の namespace（bridge 期は GitHub 上の `agents/<keyid>/<task>` branch）
  だけ。canonical の前進は:
  1. agent が patch record（kotoba-issue の proposal、COB 相当）を提出
  2. Governor が policy 評価（テスト green・pin 検証・risk tier。
     `kotoba-issue.gate` の risk-tiered auto-merge をそのまま使う）
  3. **quorum の鍵が canonical ref + fleet-db pin を単一 tx で前進**
     （Radicle の「canonical = 純関数」: threshold k は鍵漏洩耐性）
- これで「hooks が実は未インストール」問題が消える: 規約や hook で
  「書くな」と言うのではなく、**書く能力を持たない**（capability enforcement）。
  44-repo 事故は「起こさない運用」から「起こせない構造」になる。
- agent 間の merge は自動化しない（Cursor/Zed と同判断）: 隔離による回避 +
  review-before-merge。DeltaDB 型の operation-log merge は Phase 4 の研究項目。

### Plane 5 — sync/transport: 並列 materialize、のち p2p

- **bridge 期: GitHub は object transport + interop/CI 境界として維持**
  （canonical refs を GitHub にも投影するので、既存の gh CLI・Actions・
  人間の GitHub UI はそのまま動く — Radicle の canonical refs が「git tooling
  を生かすための投影」であるのと同じ理屈）。
- `fleet sync` = 「working set の entity 集合を signed pin で実体化」。
  repo ごとに独立なので**全面並列**（serial west update の置換。pin が
  署名済み sequence 付きなので「fetch 順序」や「dirty skip」の概念が消える）。
  shallow 問題も消える: pin の SHA を直接 `fetch --depth 1 origin <sha>` する
  のが常に正しい（tip との関係を推測しない。ancestry 判定は署名鎖が担う）。
- **Phase 3 で p2p seeding**: kotoba-lang/p2p の signed head-announce
  （E2E 収束検証済み）でフリート機間の直接複製。GitHub rate limit
  （2026-07-02 事故の 30 件は transient rate-limit）から独立する。
  GitHub は「mirror の 1 つ」に降格。
- **大容量は現行どおり DataLad + B2 sidecar**（Radicle も LFS 非対応で
  sidecar 前提 — 現方針の妥当性を裏付ける）。heavy 15 repo の扱いは不変。

### 実装言語・配置

- fleet-db スキーマ/admission gate/workspace manager/`fleet` CLI は **nbb
  （.cljs）+ 共有 .cljc** で書く（repo 運用 tooling は nbb が正、sh/Rust 新規
  禁止ルールに従う）。核となる検証ロジックは `.cljc` にして kotoba-rad /
  kotobase-peer のテスト資産と同じ流儀で contract-test する。
- 新規 repo は `kotoba-lang/kotoba-fleet`（`-clj` suffix 禁止ルール準拠、
  命名はオーナー確認事項）。kotoba-git / kotoba-rad は変更最小
  （sigref の parent-covering 拡張のみ）。

## 移行フェーズ（west を止めずに）

- **Phase 0 — 読み取りモデル（低リスク・即効）**: repos.edn + west.yml を
  fleet-db に import（datoms + append-only ledger）。pin 前進は「fleet-db
  tx → 既存の single-entry API PUT を emit」の 2 相で west と整合を保つ。
  並列 `fleet sync`（nbb）を agent 向けの west update 代替として先行投入。
  ここまでは west 体制の正本を変えない。
- **Phase 1 — signed pins**: pin を signed head record 化し、
  verify-west-pins 相当を transact admission gate に移す。west.yml は
  fleet-db からの生成 projection に降格（`--check` は projection 一致検証）。
- **Phase 2 — agent identity + governed land-back**: agent 鍵発行と CACAO
  grant 配布、GitHub 上での namespace branch 運用 + Governor quorum による
  canonical 前進、workspace manager（lazy materialization / GC / checkpoint /
  best-of-N）。
- **Phase 3 — content-addressed object plane + p2p**: kotoba-git object
  model と p2p signed head-announce でフリート機間複製。GitHub を mirror 化。
- **Phase 4 — 研究（コミットしない）**: DeltaDB 型 operation log / CRDT
  worktree による intra-file 並行編集。DeltaDB の OSS 公開（beta 直前、
  2026-06 waitlist）を観測してから判断。

## What this ADR does NOT decide

- **Radicle-the-software の採用はしない**（heartwood ノードを立てない）。
  移植するのは per-writer namespace / sigref FF 鎖 / canonical-ref-as-pure-
  function の 3 アイデアのみ。理由: 既に同型のプリミティブが kotoba-lang
  スタックに実在しテスト済みであること、Radicle の未実装領域（鍵 rotation・
  fine-grained replication policy・LFS）がまさに本フリートの要件であること。
- **CRDT / operation-log merge は Phase 4 の研究項目**であり、本設計は
  worktree 隔離 + review-before-merge を維持する（Cursor/Zed と同判断）。
- **git の廃止はしない**。interop projection として維持（DeltaDB と同判断）。
- **DataLad/B2 大容量経路・heavy repo の shallow 運用は不変**。
- 各フェーズの着手・repo scaffold・命名（`kotoba-fleet` は仮称）は本 ADR の
  accepted 化とオーナー判断を待つ。

## Consequences

- 事故クラスの構造的消滅（起こせない化）: pin 退行・未 push pin（signed
  monotonic sequence + admission gate）、wholesale regen（pin は entity tx）、
  topdir 誤認（global topdir 概念の廃止）、stash 堆積（namespace commit のみ）、
  canonical への誤 push（capability enforcement）。
- shallow ancestry 誤判定問題は「判定しない」ことで消える: 真偽の正本が
  GitHub compare API から署名鎖に移る。
- 引き換えに新しい運用対象が増える: 鍵管理（agent 鍵の発行・expiry・失効。
  secrets-location-map への統合が必要）、fleet-db の可用性（bridge 期は
  ローカル EDN + ledger で可、Phase 3 で p2p 冗長化）、projection 整合の監視。
- kotoba-git/kotoba-rad が初めて production surface（自分たち自身の repo 運用）
  を持つ — dogfooding として最良の被検体になる。

## Verification

- 本 ADR は設計のみ。調査の実証根拠: west 実測値・事故 ADR 群（related 参照）、
  Radicle protocol docs / FOSDEM 2026 / 1.7.0 CVE 開示、Cursor 2.0 blog /
  worktrees docs / checkpoints docs、Zed DeltaDB 発表（2026-06）、
  kotoba-lang 各 repo のテストスイート（kotoba-rad 50 tests / 76 assertions、
  p2p E2E 収束テスト等、ADR-2607072200 記載）。
- Phase 0 の受け入れ基準（accepted 後）: fleet-db import が west.yml 1,669
  entries と bijective に往復すること、並列 `fleet sync` が現行 serial
  `west update` と同一 checkout 結果でウォールクロックを有意に短縮すること。

## Addendum (2026-07-16, same day): Phase 0 実装・検証・登録

オーナー承認（「ではフェーズを進めて」）を受け Phase 0 を実装した。

- **repo**: `kotoba-lang/kotoba-fleet-vcs`（public、pin `518ca77e5db8` で west
  登録済み）。**仮称 `kotoba-fleet` は名前衝突により `-vcs` role suffix に改名**
  — `kotoba-lang/kotoba-fleet` は既存の別レイヤ（lease + governor-drain +
  fleet-view の agent 並行実行コーディネーション基盤、ADR-2606302000、
  2026-06-30 作成・活発）。両者は同じ fleet 概念の兄弟: あちらが「実行を
  ぶつけない」係、こちらが「manifest / pin / sync（west 後継 VCS プレーン）」係。
  Phase 2 の Governor 統合はあちらの governor 実装との統合を検討する。
- **実装**（nbb 第一、core は pure `.cljc`、pure planner + injected runner）:
  `fleet.west`（gen-west-manifest 方言の parse/emit 全単射 — repo-path /
  userdata datalad・annex-remote・archived / clone-depth / submodules 対応）、
  `fleet.db`（repo entity + datoms schema + append-only pin ledger、
  canvas-ledger 同型）、`fleet.sync`（pure planner: pin SHA 直接 fetch、
  dirty は skip）、`bin/fleet.cljs`（import / check / stats / list / sync /
  pin-advance。pin-advance は 2 相 — ledger + db 更新→ west.yml 反映は既存の
  検証済み `--entry` 経路へ委譲）。
- **受け入れ基準の実測**（ユニットテスト 4 tests / 22 assertions green に加え）:
  - **全単射**: 実物 `manifest/west.yml`（当日時点 **1,732 entries**、設計時
    調査の 1,669 から自然増）を import → 再 emit で **byte-identical** を確認
    （`fleet check` OK）。stats も調査値と一致（heavy 17 / datalad 10 /
    archived 7 / orgs 6）。
  - **並列 sync**: 軽量 10 repo（datom / chain / prolly-tree / arrangement /
    kotobase-* / ed25519 / cacao / ipns）を scratchpad workspace に実体化 —
    `--jobs 8` で **6.0s** vs `--jobs 1` で **29.0s**（**4.8×**）。両 workspace
    とも **HEAD == pin を 20/20 確認**。再実行は 0.8s で全 `:noop`（冪等）、
    untracked ファイル投入で `:skip-dirty`（west 意味論の維持）。
  - 登録は `--entry kotoba-fleet-vcs` の最小 diff（repos.edn +1-1 /
    west.yml +5、サーバ側 pin 検証 OK）。`--check` の STALE は登録前から
    存在する他 repo のローカル checkout drift 由来（wholesale 再生成は
    しない — 本文の禁止事項どおり）。
- **副次観測（本 ADR の論拠を強める実事故）**: この登録作業の途中、main の
  tree が通常 commit の連鎖で **7 ファイルまで縮退する事故**に遭遇した
  （`119cc9d77c` が「docs(adr)」と称して 300 ファイル削除・0 追加 —
  sparse worktree からの commit 事故。force-push なし、全て FF）。並行
  セッションが `549407d8640`「fix(main): restore full tree after
  sparse-worktree tree collapse」で 230,089 ファイルを復旧済みであることを
  検証した（削除 300 path 全て復活を確認）。**「規約と hook では main を
  守れない、canonical への書き込みは capability で封じる」という本 ADR の
  中心主張の実証例**として記録する。Phase 2 の優先度を上げる根拠。
- 次: Phase 1（signed pins / admission gate — ipns.head 型 record 化、
  verify-west-pins 相当の transact 時 gate 移設、west.yml の生成 projection
  降格）。
