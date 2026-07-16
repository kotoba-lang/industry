---
id: adr-2607161325-kotoba-delta-op-log
title: "ADR-2607161325: kotoba-delta — kotoba/kotobase 上の DeltaDB 相当（署名付き離散 op-log、text CRDT 非採用）"
status: accepted
doc_type: adr
topic: kotoba-delta-op-log
authoritative: true
last_verified: 2026-07-16
authoritative_for:
  - "kotoba-lang/kotoba-delta が agent 編集の operation log（署名・parent-covering・explicit conflict・secrets admission・会話リンク・決定的 git projection）を所有する"
  - "「text CRDT を作らない」判断の境界（人間のリアルタイム共同編集が要件化したら再評価）"
related:
  - 90-docs/adr/2607160005-kotoba-fleet-agent-vcs-west-successor.md
  - 90-docs/adr/2607072200-kotoba-git-kotoba-rad-content-addressed-vcs.md
supersedes: []
superseded_by: []
---

# ADR-2607161325: kotoba-delta — DeltaDB 相当を kotoba ネイティブに

**Status**: accepted（実装・E2E 検証済み）
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki（「kotoba, kotobase として deltadb 相当を作れるよね?」→「ok, do it.」）

## Context

ADR-2607160005 P4 research（2026-07-16）の結論: DeltaDB は beta 未出荷・
format 未公開で、HN の主要争点（secrets の永久保存 / CRDT 収束は textual で
semantic conflict は残る / authorization 不在）が本フリートに直撃する。
一方 kotoba スタックには IStore streams・content-addressing・ed25519/CACAO・
time-travel query・fleet-vcs の署名 ledger が既にあり、被覆率が高い。

## Decision

**kotoba-lang/kotoba-delta** を新設し、「Datomic-for-code-edits」として
DeltaDB の価値（fine-grained delta・stable identity・会話並記・git 降格）を
kotoba ネイティブに実装する。**意図的な差分**:

1. **text CRDT 非採用** — 書き手は agent、編集は離散 op（Edit/Write/Remove）。
   順序は log の全順序（transactor 型）が与え、収束は構成上自明。
2. **explicit conflict** — `:old-not-found` / `:ambiguous-old`（Edit-tool
   意味論: old は一意出現が条件）を first-class 値として返し replay を停止。
   silent convergence をしない。
3. **署名 + parent-covering** — 全 op は did:key actor の Ed25519 署名、
   `:op/parent` = 直前 accepted op の hash（fleet.pin と同じ replay-CVE 対策）。
4. **secrets admission** — 秘密情報パターン（PEM/AWS/GitHub/sk-/汎用
   key=value）は log に入る**前**に reject。
5. **会話リンク** — `:op/turn` で prompt→行 provenance。
6. **決定的 git projection** — 固定 identity + 最終 op 時刻で commit を作り、
   同一 log → 同一 commit SHA。

## Verification（2026-07-16、実 Ed25519 / nbb）

- ユニット 2 tests / 13 assertions green（chain admission: genesis/連鎖/
  parent-mismatch/tamper/no-grant/secret、replay: 決定性 + conflict 3 種）。
- E2E: op 2 件（会話 turn 付き）記録 → secret op が `:secret-material` で
  reject（exit 1）→ verify OK → **projection 2 回が同一 commit SHA
  `a917a3c5cc31`** → log 改竄で verify が `:bad-signature`（exit 1）。
  この「projection 決定性」は ADR-2607160005 P4 で DeltaDB 採用ゲートに
  予約した性質そのもので、自前実装では満たせることを実証。

## What this ADR does NOT decide

- kotobase `IStore`/`code_graph` への永続化接続、S 式構造 anchor
  （definition CID + form path）、fleet-vcs signed head への op-log head
  組み込み、agent harness からの自動 capture — いずれも follow-up。
- 人間のリアルタイム共同編集（text CRDT）が要件化した場合の再評価。
