---
id: adr-2606302000-kotoba-fleet-agent-coordination
title: "ADR-2606302000: kotoba Datom log を調整基板にした 20-agent fleet 並行開発（lease + governor-drain）"
status: proposed
doc_type: adr
topic: kotoba-fleet-coordination
authoritative: true
last_verified: 2026-06-30
authoritative_for:
  - 複数 PC・多数 terminal の Claude Code agent を git conflict なしに並行運用する調整層を定義する
  - 調整(coordination)を git の共有可変 ref から kotoba append-only Datom log に引き上げる
  - 作業排他を lock サーバではなく lease datom の楽観 claim（409 retry）で実現する
  - git への materialize を repo 単位 governor の単一 writer に直列化する（actor 不変条件の転用）
  - 既存 west / repos.edn / 楽観 single-entry commit / murakumo fleet を壊さず上に一段足す
related:
  - CLAUDE.md
  - manifest/repos.edn
  - orgs/kotoba-lang/kotoba/README.md
  - orgs/kotoba-lang/kotoba-code/README.md
  - orgs/kotoba-lang/murakumo/README.md
  - orgs/kotoba-lang/datom/README.md
  - 90-docs/adr/2606272237-manifest-workflow-single-entry-commit.md
supersedes: []
superseded_by: []
---

# ADR-2606302000: kotoba Datom log を調整基板にした 20-agent fleet 並行開発

**Status**: proposed
**Date**: 2026-06-30
**Deciders**: Jun Kawasaki

## Context

現状は west manifest（`repos.edn` を SSoT に ~50 child repo へ partition）+ radicle +
GitHub で、分散的に git 管理している。目標は **2 台の PC で計 ~20 個の terminal agent
（Claude Code）を開き、並列・並行に開発しつつ conflict を出さない**こと。

git で conflict が出るのは「並列だから」ではなく、**共有可変 ref（`main`）に対する
行ベース 3-way merge** という git の構造に起因する。20 agent が単一 `main` を奪い合うと
ロック競合 + テキスト merge marker になる。west で repo を割ったのは正しい第一歩だが、
**調整(coordination)がまだ git の上にある**のが限界。

一方、基盤はすでに揃っている:

- **kotoba** — content-addressed distributed Datalog（Datom[CID/T] × EAVT × CACAO）。
  1 actor = 1 Ed25519 鍵 = 鍵由来 IPNS graph、depth-1 自己 mint（CLAUDE.md）。
- **kotoba-code** — model-neutral agentic coding agent。全 turn-history を Datom log に
  as-of で積む＝**resumable / auditable / fleet-shared checkpointer**。
- **murakumo** — Mac fleet の kotoba WASM mesh control plane。Tailscale/DID で全ノードを
  1 view に畳む。durable outer-loop（lease / tick / budget / crash-recovery）の語彙を持つ。
- **datom-clj** — kotoba Datom-log（EAVT, Datomic-isomorphic）の表現。zero-dep `.cljc`。
- **actor パターン** — 知能ノードは proposal のみ返し、独立 governor が検閲して可決/拒否。
  単一不変条件「governor が拒否する書込を actor は決して行わない」。append-only 監査台帳。
- **楽観 single-entry commit**（ADR-2606272237）— west.yml の pin 前進を tip blob SHA 一致
  PUT で行い、tip がずれれば 409 で弾く＝conflict が構造的に発生しない経路。

足りないのは **work 分配 + lease の調整層**だけ。本 ADR はそれを定義する。

## Decision

**「kotoba で git をやめる」のではなく「git を末端の materialize 層に降格し、調整を
kotoba Datom log に引き上げる」。** conflict を解消の対象でなく **発生しない構造**にする。

### 三層で conflict を消す

| 層 | git の挙動 | kotoba の挙動 |
|---|---|---|
| **命名** | 単一可変 ref `main` を全員 write | 1 agent = 1 鍵 = 1 IPNS graph。各自の namespace に書く。`main` は派生ビューに降格 |
| **データ** | ファイル上書き → 行 merge | append-only datom（CID/T）。追記は set-union で単調・決定的 CID。marker が出ない |
| **commit** | force / 手動 merge | 楽観 single-entry（tip ずれたら 409 → retry）。conflict が構造的に起きない |

### トポロジ

```
        ┌────────────────── kotoba Datom log (1 論理 log) ──────────────────┐
        │  append-only / content-addressed / as-of / 両 PC に IPNS+CID 複製   │
        │  :work/*  :lease/*  :proposal/*  :claim/*  :receipt/*              │
        └───▲──────────────────────▲───────────────────────────▲───────────┘
   claim(楽観)│append           append│                    drain │(単一 writer)
        ┌────┴─────┐         ┌────────┴────────┐         ┌───────┴────────┐
        │ agent A  │ ...×20  │ agent N         │         │ Governor       │
        │kotoba-code│        │自分の worktree   │         │ per repo       │
        │自分の鍵   │        │ or WASM pod     │         │ → git/west pin │
        └──────────┘         └─────────────────┘         └────────────────┘
            PC1 ×10                 PC2 ×10                どちらか1台
   ── murakumo がこの 20 ノードを Tailscale/DID で 1 fleet に畳む ──
```

### conflict を「起きない」にする 3 仕掛け

1. **Lease で排他（lock サーバ無し）。** 作業単位（repo / module / file-set）を
   `:lease/*` datom で claim。claim は「append して 409 が返らなければ取得」。20 agent が
   disjoint な lease を持てば同じファイルに二人触れない。TTL + crash で再 lease。
2. **提案と materialize の分離（actor 不変条件の転用）。** agent は `:proposal/*`
   （file write の意図）を追記するだけ。Governor が gate して git に落とす。
   「governor が拒否する書込を actor は決して行わない」＝ git レベルの race が原理消滅。
3. **worktree / WASM pod 隔離。** 各 agent は自分の git worktree（or kotoba WASM pod）で
   編集。FS 衝突ゼロ。merge は `git merge` でなく Datom log + 楽観 commit。

### west との接続

`repos.edn` はそのまま **partition の SSoT**として生きる。変わるのは pin 前進の駆動だけ:
「人間/各 agent が API を叩く」→「Governor が log の accepted proposal を drain して
single-entry commit（ADR-2606272237 の正経路）する」。`gen-west-manifest.cljs --check` と
`pin == repo HEAD` の不変条件は維持。既存ガードレールを壊さず上に queue を一段足す。

## 同一ファイル競合の扱い（明示）

lease 粒度を file-set にし、**同一ファイルは同時に 1 lease のみ**を既定にする。どうしても
共有が必要な構造化資産（EDN/datom 表現）は append-only set-union で自然 merge。非可搬な
テキスト（コード本体）は lease で直列化し、楽観 claim 競合は 409 → 別 work へ再配分。
「黙って勝つ」side は作らない（last-writer-wins をコードに適用しない）。

## 足りない 1 ピース = `kotoba-fleet`（仮）coordinator

実装は小さい。**Datom スキーマ + 3 ループ**:

- `:work/*` `:lease/*` `:proposal/*` `:claim/*` `:receipt/*` スキーマ（datom-clj に追加）
- **claim ループ**（agent 側）: 楽観 lease → kotoba-code 実行 → proposal 追記
- **governor drain ループ**（repo 単位）: proposal → gate → west single-entry commit → receipt
- **fleet view**（murakumo 拡張）: 20 lease の TTL / 進捗 / crash 再 lease を 1 view に

新規 actor の標準スキャフォールド（ADR → `.cljc` 正本 → child repo → west 登録 →
RAD identity）にそのまま乗る。

## Maturity

- **F0** — datom スキーマ（work/lease/proposal/claim/receipt）を datom-clj に定義、contract test。
- **F1** — claim ループ（楽観 lease + TTL + crash 再 lease）。単一 PC で 2〜3 agent PoC。
- **F2** — governor drain（proposal → gate → west single-entry commit → receipt）。git 単一 writer。
- **F3** — worktree / WASM pod 隔離を agent ランタイム（kotoba-code）に統合。
- **F4** — murakumo fleet view 拡張、2 PC × ~20 agent への横展開、log の IPNS/CID 複製運用。

## Consequences

- git は「末端の materialize 層」に降格し、20 並列 writer を見なくなる（conflict 構造消滅）。
- 全 agent の turn-history / proposal / receipt が append-only Datom log に載り、
  resumable・auditable・time-travelable（kotoba-code の as-of がそのまま fleet 監査になる）。
- 既存の west / repos.edn / 楽観 single-entry commit / murakumo を破壊せず上に積む（後方互換）。
- 新たな単一障害点は governor だが、repo 単位で分割され lease で隔離されるため局所化される。
- coordinator 自体が新 child repo + west entry + RAD identity を要する（actor 完了条件）。
