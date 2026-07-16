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

## Addendum (2026-07-16, same day): Phase 1 — signed pins + admission gate、dogfood 済み

- **実装**（kotoba-fleet-vcs `aa09639236`）: `fleet.pin`（pure cljc）—
  parent-covering Ed25519 署名付き head record（署名 payload に前 record の
  hash を含む。Radicle 1.7.0 sigrefs replay CVE の教訓を day 1 適用）と
  純関数 admission gate。受理不変条件は ①署名者権限（Phase 1 は wildcard
  grant keyring `manifest/fleet-keys.edn`、CACAO 委譲鎖は Phase 2）
  ②署名検証 ③sequence 厳密単調（rollback 拒否）④parent hash 一致（replay
  拒否）⑤上流 default branch 到達性 ⑥**value 前進**（旧 pin → 新 pin が
  ahead。behind/diverged は reject）。⑤⑥はサーバ側 GitHub API 判定・
  unverifiable のみ fail-open（verify-west-pins と同一意味論）。nbb CLI は
  node:crypto Ed25519（keygen / 署名）、signer id は `ed25519:<pubkey-hex>`
  （did:key 化は Phase 2）。テスト 6 tests / 36 assertions green
  （rollback / replay / unauthorized / tamper / unreachable / value
  regression / fail-open の reject matrix 含む）。
- **dogfood で本物のギャップを 1 件発見・修正**: 初版 gate は sequence
  単調のみspecial、「seq は前進するが value が古い commit を指す」署名済み
  regression を通してしまった（この設計が殺すべき事故クラスそのもの）。
  Rule 2（value-advance）を gate に移植して修正（`aa09639236`）。
- **実運転（superproject、実 GitHub API 判定込み）**: `manifest/fleet-db.edn`
  （1,736 repos）+ `manifest/fleet-db.ledger.edn` を seed。signed 経路で
  kotoba-fleet-vcs 自身の pin を seq 1（518ca77e→7c656067）、seq 2
  （→aa09639236、parent-covering 連鎖）と前進させ、**rollback 試行
  （→518ca77e）が `:value-regression` で REJECT（exit 1）されることを実機
  確認**。projection 等価性: signed 経路が書いた west.yml は、直後に
  `gen-west-manifest.cljs --entry` を走らせても **SHA 完全一致**（byte
  同一）— 既存生成器との互換を保ったまま、pin 書き込みが「生成」から
  「署名付き transact」に置き換わったことの証明。
- **staging の境界（Phase 1 はここまで）**: west.yml は引き続き従来経路
  （gen --entry / API single-entry）でも書ける dual-write 期。fleet-db を
  唯一の書き込み口にする flip（CI で projection 一致を強制、gen 系を
  読み取り専用化）は、fleet 全体の運用切替なので owner 判断のもと Phase 1.5
  として別途。keyring の私鍵は session-local（scratchpad、0600）— 恒久鍵の
  1Password 移設と did:key 化・CACAO 化は Phase 2。

## Addendum (2026-07-16, same day): Phase 2 — agent identity + governed land-back、E2E dogfood 済み

実装は kotoba-fleet-vcs `7b81682fe4`（+ dogfood merge `c5055ce73790`）。
テスト 9 tests / 52 assertions green。

- **identity**: `fleet.did` — ed25519 did:key encode/decode（base58btc /
  multicodec 0xed01、pure cljc bignum、nbb/JVM 両対応）。`fleet keygen` は
  did:key を出力。signer id は Phase 1 の `ed25519:<hex>` から did:key へ。
- **grants**: `fleet.grant/verify-chain` — owner root → agent の委譲鎖
  （root trust / linkage child.iss==parent.aud / attenuation
  child.resources⊆parent（trailing-* covers）/ expiry / per-link 署名）。
  **cacao-clj と同意味論の fleet-native encoding**（nbb で動かすため。
  CAIP-122 CACAO wire format への揃えは encoding-only の follow-up）。
- **governed land-back（Plane 4 が実運転に）**: `fleet propose` は grant
  保持者であることを検証して land 提案を ledger に記録。`fleet govern` は
  ①**quorum pre-check（merge という副作用の前）** ②サーバ側マージ
  ③k-of-n Governor 署名の canonical pin advance（`admit-quorum`: quorum /
  sequence / parent / reachability / value-advance — 構造不変条件は Phase 1
  gate と同一、authority のみ多重署名化）。policy は
  `manifest/fleet-keys.edn` の `{:canonical {:allow #{did..} :threshold 2}}`
  — Radicle crefs の policy-as-data 移植。単鍵 pin chain は quorum イベントを
  chain 先頭として認識（混在 chain の連続性）。
- **E2E dogfood（実 GitHub、kotoba-fleet-vcs 自身）**: owner-root / gov1 /
  gov2 / agent1 の 4 鍵生成 → owner→agent1 に
  `land:orgs/kotoba-lang/kotoba-fleet-vcs` を委譲（attenuated、expiry 付き）→
  agent branch `agents/z6MkkA2L/docs-clarify-sync` を propose（chain 検証 OK、
  ledger seq 3）→ **gov1 のみの govern は pre-merge REJECT（merge 実行前に
  abort、副作用ゼロ）** → gov1+gov2 で server-side merge `c5055ce73790` +
  **quorum 2/2 の canonical pin advance（seq 3、parent-covering 連鎖継続）**。
  projection は直後の `gen --entry` で **SHA 完全一致**（等価性維持）。
  終了後 agent branch 削除（着地後の後片付け）。
- **Phase 2 の残（未実装、次の増分）**: workspace manager（lazy
  materialization の manager 化 / worktree 自動 GC / checkpoint /
  best-of-N）、鍵の 1Password 移設（secrets-location-map 準拠）、CACAO
  wire format 揃え、既存 kotoba-fleet（ADR-2606302000 の lease/governor）
  との Governor 統合。

## Addendum (2026-07-16, same day): Phase 1.5 flip staging + Phase 3a signed fleet head

実装は kotoba-fleet-vcs `cd422abcdb66`（pin は signed seq 4 で追従）。
テスト 10 tests / 60 assertions green。

- **Phase 1.5（flip の吸収期運転開始）**: `fleet reconcile [--check]` が
  legacy 経路（gen --entry / API single-entry）の west.yml 書き込みを
  fleet-db に attributed ledger events（`:pin/reconcile-legacy` 等）として
  吸収。CI `.github/workflows/fleet-projection-verify.yml`（main の
  manifest/** push で発火）が drift を検出し自動吸収 commit を積む。
  **dogfood で即座に実 drift 8 件を検出・吸収**（他エージェントが legacy
  経路で進めた local-manimani / network-isekai / aiueos / compiler / gpu /
  org-w3-webgpu / render / webgl の pin — 吸収後 `--check` clean）。
  CLAUDE.md に fleet-db 節を追加（推奨経路 = 署名付き、legacy は CI 吸収、
  fleet-db/ledger/head の手編集禁止）。**hard flip（legacy 書き込みの拒否）
  は未実施** — 全 agent の書き込みが署名経路に移ってから。
- **Phase 3a（p2p の substrate）**: `fleet head [--verify]` — fleet-db
  内容全体への自己証明 signed head（sha256 content hash + monotonic
  sequence + parent-covering、`manifest/fleet-head.edn`）。これが
  kotoba-lang/p2p の signed head-announce（ADR-2607072200 で E2E 収束
  検証済み）がフリート機間で運ぶ record そのもの。CI は head の遅れを
  warning 報告（CI は署名鍵を持たないため re-announce しない — 意図的）。
  **P3b（未実施）**: 実 p2p 配線（複数機 seeding、GitHub の mirror 降格）、
  kotoba-git object plane への pin chain 投影。

## Addendum (2026-07-16, same day): Phase 2 残の workspace manager + Phase 4 research 判断

**Phase 2 残 — workspace manager 出荷**（kotoba-fleet-vcs `a3f416147eca`、
pin signed seq 5 / head seq 4、11 tests / 64 assertions）:
- `fleet ws-gc` — Cursor 2.0 の worktree GC を移植: age + マシン台数 cap、
  **dirty workspace は決して回収しない**、削除は oldest-first
  （`fleet.ws/gc-plan` は pure、E2E で dirty skip / cap 回収を実機確認）。
- `fleet checkpoint` — git 外の conversation-scoped snapshot（tar）+
  restore（workspace を byte 復元、checkout が pin に戻ることを確認）。
- 未了で owner 依頼: **鍵の 1Password 移設**（`op` CLI が interactive 認証
  timeout。session 鍵は scratchpad に 0600 のまま — `op signin` 後に
  fleet-keys.edn の did と対で移設するのが次アクション）。CACAO wire
  format 揃え・既存 kotoba-fleet governor 統合も未了。

**Phase 4 — research 完了、判断: 「観測継続・設計は依存させない」**
（2026-07-16 web 調査、詳細ソースは調査ログ）:
- **DeltaDB は beta 未出荷**: waitlist（2026-06-11）から 5 週間、公開 repo
  なし・format docs なし・実利用報告ゼロ（zed.dev/deltadb は signup の
  まま、zed-industries org に該当 repo なし、HN/X に first-hand 報告なし）。
  発表済みの主張（stable delta identity / conflict-free replicated
  worktrees / git は interop 層）以上の情報は存在しない。
- **HN の争点が本フリートに直撃**: ①operation 粒度の記録は **secrets を
  archive する新しい liability**（API key が「commit されないはずの中間
  状態」ごと保存される）②CRDT 収束は textual であり semantic conflict は
  残る（worktree 隔離を外せる根拠にならない）③**authorization は
  op-log の外**— 「何が canonical か」は署名 pin + quorum が引き続き担う
  （DeltaDB は signed operations / trust model について何も言っていない）。
- **git projection の決定性が採用ゲート**: 本設計の pin 検証はサーバ側
  git SHA を消費するため、DeltaDB→git projection が非決定的なら失格。
  format 公開後の最小実験（1 repo・agent 2-3 体・projection 決定性 /
  ledger への delta-ID 参照 / secrets redaction / storage 増加率）を ADR に
  予約。
- **現実的な近道は jj（jujutsu）**: op log は今日動く（Google 内部では
  cloud 化済み、agent 向け workspace-per-agent 運用の実例あり）。
  「colocated jj+git を 1 repo でパイロットし、git/GitHub を canonical の
  まま op-log の undo/audit 価値だけ測る」を **P4 の reversible な次の
  一歩**として推奨（DeltaDB format リスクゼロ）。着手は別判断。
- 反対材料も記録: Freestyle「AI agent に最良の VCS は依然 git」
  （custom snapshot 系は commit/branch/diff/merge を再発明するだけ、
  という批判）— 本設計が git を捨てず projection に降格した判断と整合。

## Addendum (2026-07-16, same day): gap 解消ラウンド

gap 棚卸し（同日）の推奨順 ①〜⑤ を処置した。

- **⑤ 並行書き込み競合 → 解消**（kotoba-fleet-vcs `c0bb0780434b`、pin
  signed seq 6）: `lock-db!`（mkdir-atomic、10s retry、>60s stale 破棄、
  process exit で解放）を pin-advance / govern / reconcile / 旧経路の
  4 mutation に配線。last-writer-wins race を封じた。
- **③ delta 自動 capture → 配線済み（opt-in）**:
  `.claude/hooks/delta-capture-post-tool.cljs` + `.claude/settings.json` の
  PostToolUse（Edit|Write）。**fail-open 設計** — `DELTA_CAPTURE=1` +
  `DELTA_KEY` があるセッションのみ記録し、それ以外・エラー時は常に exit 0
  （並行セッションを壊さない）。実テスト: opt-in で signed op（session id
  が `:op/turn`）が記録され、無効時は無音。secrets は delta の admission が
  弾く。
- **② GitHub 側 enforcement → 部分達成**: kotoba-fleet-vcs / kotoba-delta
  に ruleset `protect-main-structural`（main への deletion / non-FF を
  admin 含め拒否 = force-push 禁止の構造化）。**root（private）は現行
  プランで ruleset / branch protection とも 403** — 代替として
  `.github/workflows/tree-collapse-guard.yml`（tree が >40% 縮んだ push を
  検出し、親 commit から削除ファイルを additive に自動復元 + issue 起票。
  119cc9d77c 事故クラスの再発対策）。なお全 agent が同一 owner token で
  push する現運用では、GitHub 側で agent を区別する enforcement は
  原理的に不可能 — per-agent 認証（deploy key / App）が hard flip の
  前提条件になることを明記。
- **④ スケール実測**: cloud-itonami org 全 58 repos を jobs=12 で
  **13.3s** materialize（再実行 0.2s 全 noop）。heavy も pin SHA 直
  fetch + depth1 により **kototama 435MB→11MB / webmaster 301MB→49MB**
  で HEAD==pin。残: manimani 16GB 級・datalad・submodules repo の実測。
- **① 鍵の永続化 → 解消（1Password でなく kagi）**: `op signin` が二度
  interactive 認証 timeout したため、**kotoba-native の kagi
  （`orgs/kotoba-lang/kagi`、OS-Keychain unlock で無人・ADR-2606272330 が
  kotoba-lang 新規 secrets の正と定める）に 5 鍵を移設**（compartment
  `personal`: fleet-owner-key / -owner-root / -gov1 / -gov2 / -agent1）。
  CLI に `read-key`（`--key PEM | --kagi NAME`）を追加し pin-advance /
  govern（`--gov-kagi`）/ head を kagi 経路化。**dogfood: scratchpad 鍵に
  一切触れず kagi のみで pin seq 7→8 / head seq 7→8 を実走**。chain 継続性
  が session-local ファイルに依存しなくなった（最も脆い点の解消）。
  fleet-keys.edn に kagi 名の索引を記載、CLAUDE.md も kagi 経路に更新。
- 未処置のまま残る gap: ⑥ hard flip（per-agent 認証とセット）、
  ⑦ P3b（p2p 実配線）、kotobase 永続化（fleet-db blob / Datalog）、
  anchor / IStore / fleet-head への op-log 統合、CACAO wire format、
  kotoba-fleet governor 統合、CI 実走確認。

## Addendum (2026-07-16, same day): Phase 3b — fleet head gossip 配線

⑦ P3b の第一スライス（fleet head の機間複製）を実装（kotoba-fleet-vcs
`209088728e41`、12 tests / 76 assertions）:

- **`fleet.p2p`（pure cljc）**: `head->announce` が P3a signed head を
  **kotoba-lang/p2p のワイヤ形状** `{:type :head-announce :graph "fleet-db"
  :head-cid :seq :fleet-head}` に変換。`verify-announce` は trust set
  （keyring roots + canonical allow）+ ed25519 署名 + head-cid/seq の
  record 束縛を検証。`adopt` は seq 前進時のみ採用（monotonic、pin と同規則）。
  **fleet head をそのまま sigref として使う** — kotoba-rad.announce
  （ADR-2607072200）と同じ insight で、新しい署名 primitive を作らない。
  実 kotoba-lang/p2p ノードの `:sign-announce`/`:verify-announce?` フックと
  message 形状が一致するので相互運用可能。
- **dogfood（機間複製）**: machineA が fleet head を announce → machineB
  （空）が **seq 8 / cid 935a713 を gossip 経由で adopt** → 再送は downgrade
  せず → head-cid 改竄 announce は `:head-cid-mismatch` で REJECT、B の state
  は seq 8 のまま。**フリート機は GitHub を polling せず互いの fleet head を
  この経路で学ぶ（GitHub を mirror に降格）**。
- **本スライスの境界**: announce/verify/adopt のみ（単一 head record に必要な
  部分）。block-chasing（`want-since`/bitswap による kotoba-git object graph
  の実データ転送）と実ネットワークトランスポート（現状は message EDN の
  受け渡し）は後続スライス — fleet head の**真偽の合意**は本スライスで
  機間 replicable、object の**実体転送**は kotoba-git 統合時。

## Addendum (2026-07-16, same day): ⑥ per-agent identity + hard-flip switch

⑥ の per-agent 認証（hard flip の前提）を実装（kotoba-fleet-vcs
`a5c049da15f2`、13 tests / 80 assertions）:

- **`fleet enroll --agent NAME --grant PATH --registry fleet-agents.edn`**:
  各 agent セッション専用の ed25519 did:key を kagi に mint し、append-only
  の **agent registry**（`manifest/fleet-agents.edn`）に scoped grant 付きで
  登録。governance keyring（`fleet-keys.edn`、roots/canonical）は人間管理、
  registry は機械管理（base-datoms/ledger 分離と同型）。`pin-advance
  --registry` が registry の grant を keyring に merge（**衝突時は governance
  が勝つ** — enrolled 鍵は governance grant を上書きできない）。
- **E2E 実証**: session-7a14b1b8 を enroll → その agent 鍵（`7ee40d47…`、
  owner 鍵 `7414dd47…` とは別）で kotoba-fleet-vcs の pin を **seq 10→11 で
  前進**。ledger の `:event/signer` が agent 鍵を記録。**共有 owner 鍵に
  頼らず per-agent の署名で canonical 前進が回る**ことを確認。
- **hard-flip スイッチ**: `fleet reconcile --enforce` — drift を吸収でなく
  **REJECT**（legacy 書き込み禁止、fleet-db を唯一の writer に）。CI を
  `--enforce` に切り替えるのが cutover の一手。**ただし repo-wide の実 flip
  は全 writer セッションが enroll するまで保留**（gen --entry を使う他
  セッションを壊すため）。GitHub は全 agent が同一 owner token で push する
  以上 per-agent を区別できない —— per-agent auth は署名レイヤにしか置けない、
  という構造的事実がこのスライスの核心。実 cutover はオーナー判断 +
  全 enroll 完了の 2 条件待ち。

## Addendum (2026-07-16, same day): ⑯ kotobase 永続化 / ⑰ object plane / ⑱ delta anchor+IStore

goal 指定 4 項目の残り 3 つを実装（⑥ は上記）。**依存 5 ライブラリ
（kotobase-peer / arrangement / chain / prolly-tree / kotoba-git）はすべて
pure cljc で nbb ロード可能**（+ npm `@noble/hashes`）を実証した上で:

- **⑯ kotobase 永続化**（kotoba-fleet-vcs `d11b11c03ed5`）: `fleet.kdb` が
  fleet-db EDN read-model を実 datom plane（kotobase-peer）に射影。plain-fn
  クエリを Datalog（`kb/query`）に、~500KB EDN blob を content-addressed
  commit chain（`kb/commit!` → CID）に。contract test が datom-plane ==
  EDN-model を全クエリで実証（kotoba-lang 1389 / cloud-itonami 58 / heavy 17 /
  datalad 10 / revision / count 全一致）+ 永続化ラウンドトリップ
  （transact→commit!→hydrate、18 datoms、実 prolly-tree CID）。「YAML を
  批判して EDN blob を作った」自己矛盾の解消。
- **⑰ kotoba-git object plane**（`a321848945ed`）: `fleet.objects` が P3b の
  head-cid gossip に実 object graph 転送を追加。`missing-since` で受信側が
  欠く block だけ算出、CID 検証付きで unpack。demo: 増分 fetch A→B が delta
  3 objects のみ転送、B は v2 を再構成、forged block は cid-mismatch で REJECT。
- **⑱ delta anchor + IStore**（kotoba-delta `4d112c207b77`）: `delta.anchor`
  が op を行番号でなく定義（kind+name+content-hash）に anchor（コード移動に
  耐える、:unchanged/:moved/:edited/:gone）。`delta.op` v2 は `:op/anchor` を
  署名 payload に含む。`delta.store` が op-log を kotobase IStore stream
  （append + monotonic :seq、cursor resume、`KotobaseStore ≡ LocalStore`）で
  永続化、log-head + seq cursor が signed fleet head に折り込まれ manifest と
  編集 provenance を一署名で証明。全 demo/test green。

この 4 項目で fleet-vcs は「EDN prototype」から「kotoba datom plane +
content-addressed object transfer + per-agent 署名 + 構造 provenance」の
実装へ移行した。残: hard flip の実 cutover（全 enroll 待ち）、CI 実走確認、
実ネットワークトランスポート、CACAO wire format、kotoba-fleet governor 統合。

## Addendum (2026-07-16, same day): 日常ドライバ化を reverse-topological に前進（C→B→A→D）

「実際に管理できるか / private repo は」の問いへの実測回答と、残ギャップを
依存 DAG の葉→根で解消。**実測**: fleet sync は private repo でも動く
（`jk-luxury/club-shinshi` を clone、HEAD==pin 確認 — git fetch が owner の
SSH/gh 認証を使う）。pin 検証もローカル owner なら private strict。ただし
**CI の `github.token` は他 org の private 子を読めず fail-open**（west の
verify-west-pins と同じ既知制約）。この差を C→B→A→D で埋めた
（kotoba-fleet-vcs `593af8d563f8`、pin seq 13 / head seq 12、14 tests /
85 assertions）:

- **C（live query backend）**: `bin/query.cljs` が実 kotobase datom plane に
  任意 Datalog + canned を実行。多節 join（「heavy かつ datalad」→
  m365-archive）が回り、datom plane が contract-test 用の射影でなく**実際に
  引ける backend**になった。
- **B（p2p private visibility）**: `fleet.objects/pack` 5-arity が private repo
  の object を **allow-set 外の peer に配らない**（Radicle visibility model）。
  demo: allowed peer は pull 可、stranger は「not in visibility」で拒否、
  public は誰でも可。
- **A（CI strict pin verify）**: `fleet verify-pins` が CONFIRMED unreachable で
  exit 1、private が見えない :unknown は WARN。CI（`fleet-projection-verify.yml`）
  は `secrets.FLEET_PIN_TOKEN`（org-read PAT）があれば private も strict、
  無ければ github.token で fail-open。**PAT の provision は owner action**
  （west の WEST_PIN_VERIFY_TOKEN と同じ）。
- **D（staged hard flip）**: `reconcile --enforce-orgs kotoba-lang` が
  **kotoba-lang(public) の legacy 書き込みだけ REJECT**、混在/private org は
  吸収モードのまま。**live 実証**: kotoba-lang scope で in-scope drift を検出し
  FLIP VIOLATION、gftdcojp scope では別 org の drift だけ検出 — org 単位で
  拒否範囲が正しく絞られる。全 enroll + CI strict 完了後に段階拡大する設計。

**結論（現状の正直な位置）**: 「今すぐ west を捨てて fleet だけで全部」= まだ
No（hard flip は kotoba-lang public から段階導入中、CI strict は PAT 待ち、
kdb/objects/delta の一部はライブラリ+demo で日常パス未配線）。「pin 前進・
並列 sync・署名台帳・Datalog クエリの実ツールとして private 込みで使えるか」
= Yes（owner ローカル）。west とは**並走**し、org 単位で段階的に置換していく。

## Addendum (2026-07-16, same day): CI/CD の所在調査 + fleet native CI 配線

**問い**: kotoba-git/kotoba-rad 自体に CI/CD はどう含まれるか。**実測回答**:
VCS stack「自体」には CI ランナーは無い。stack は ref-shape policy
（kotoba-git.ref-policy）+ signed-ref attestation（kotoba-rad.sigref）+ push
authorization（push-gate）+ signed head-announce（announce）で意図的に止まる。
CI/CD プリミティブは一段上に、しかも **Radicle CI 型（broker + Job COB）で
なく Nix/Bazel 型（content-addressed derivation）** で分散している:

- **kotobase `code_graph`** が核: `put-execution-receipt!`（C4）—
  code-root × artifact × compiler-contract × package-lock × policy × grants ×
  outcome を束ね **required-effects（code graph から再計算）⊆ granted-effects**
  を検証する content-addressed 実行 provenance。`execute-code-root!`（C5、
  host-neutral coordinator、persist はしない）、`sync-code-root!`（verified
  artifact transfer）、`cache-put!/get`（ambient 結果を昇格させない hermetic
  test/analysis cache）。**scheduler も event trigger も cross-network job COB
  も無い** — 「いつ走らせるか」は持たない。
- **kototama** = capability-gated WASM 実行サンドボックス（ビルド/テストが
  実際に走る場）。ABI レベルでは receipt 化しない。
- **kotoba-lang/ci（ci-clj）** = GHA workflow を EDN でモデル化（job-DAG wave
  planner、pure）。ただし **VCS stack 未配線**。
- **hinshitsu** = 品質ゲート/evidence schema + 黙視（visual diff）。orchestrator
  ではない。
- **動いている唯一の broker は cloud-itonami の ops-runner**（ADR-2607141700、
  M0–M8 実装済み）: announce 購読/poll → merge を verify（sigref→CACAO→risk
  tier、fail-closed）→ per-kind handler（Resend/Stripe/deploy、at-most-once）
  → **execute-only 鍵（itonami-runner-bot）で署名した receipt/audit commit**。
  = Radicle CI 相当だが **consumer 層で合成**、VCS stack の再利用部品ではない
  （kotoba-git/rad の decoupling を保つため意図的に consumer に留めた）。

**fleet native CI 配線（実装、kotoba-fleet-vcs `9e6764bcc57e`、pin seq 14 /
head seq 13、15 tests / 90 assertions）**: fleet の CI を外部 GitHub Actions
から上記 native パターンへ移す第一歩。`fleet.ci` が pin 検証を **execution-
receipt 同型の署名付き content-addressed verification receipt** にする
（verdict = **required ⊆ passed**）。`fleet ci-verify` が pin 到達性チェックを
走らせ署名 receipt を append-only ログ（`manifest/fleet-ci.edn`）に記録、
:fail で exit 1。ops-runner パターン（verify → 署名 receipt）の fleet 版で、
receipt は `fleet/ci-receipts` IStore stream にも載る（delta op-log と同一
substrate）。**dogfood: public + private（club-shinshi）3 repo を検証、
pass、署名 receipt `0a1f37f78fa4`/`ab6db0608952`**。GHA workflow は署名鍵を
持てないため CI 側は report-only、署名 receipt は owner-side（kagi）で発行。
**残**: hinshitsu ゲートを kototama capability-sandbox で実行する部分
（現状は pin 到達性チェックのみ、receipt の check は hinshitsu evidence の
{:name :outcome} 形と互換）。

## Addendum (2026-07-16, same day): native CI 品質ゲート（capability-bound）

native CI の残りだった「ゲート実行」を実装（kotoba-fleet-vcs `804b60e3de04`、
pin seq 15）。`fleet ci-verify --gate 'name=cmd' [--gate-timeout ms]` が品質
ゲートを **capability-bound（timeout budget = kototama HostCaps の analog）**で
実行し、**hinshitsu-evidence 互換の check**（`{:hinshitsu/status
:hinshitsu/checks}`、hinshitsu を require せず plain-map shape で interop —
ops-runner / kotoba-rad.announce と同じ疎結合原則）を署名 receipt に食わせる。
**dogfood**: repo 自身のテストスイートを gate に走らせ pass（receipt に
`gate/tests exit 0`）、`exit 3` の gate は receipt :fail + exit 1 —
**ゲートが実際に gating する**ことを両パスで確認。**残**: kototama Chicory
tender による **literal WASM 封じ**（現状は timeout-bound subprocess）と
hinshitsu.mokushi（visual regression）ゲート — どちらも JVM 側 follow-up。
これで fleet native CI は「verify → 署名 content-addressed receipt（required
⊆ passed）+ capability-bound quality gate」まで到達し、GitHub Actions の
揮発ログを durable attestation に置換する形が一通り揃った。
