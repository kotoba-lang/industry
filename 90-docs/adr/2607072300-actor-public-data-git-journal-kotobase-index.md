---
id: adr-2607072300-actor-public-data-git-journal-kotobase-index
title: "ADR-2607072300: actor 公開データの git-repo 常駐化 — kotoba-git 上の datom journal を一次ソースに、kotobase.net はその派生インデックス、DataLad は大容量バイナリのみ opt-in"
status: accepted
doc_type: adr
topic: actor-public-data-git-journal-kotobase-index
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - "An actor repo's own git history (specifically a committed EDN quad-log under `80-data/public/*.journal.edn`) is the authoritative source of an actor's PUBLIC data — not `kotobase.net`"
  - "`kotobase.net`/`kotobase-peer` is a derived, rebuildable read index over that git-resident journal, not the sole store, for any actor that adopts this pattern"
  - "DataLad (git-annex + B2) remains scoped to large-binary payloads a journal references by CID, extending the existing `m365-archive`/`mangaka-data` pattern per-actor on an opt-in basis — it is not a general replication layer for structured public data"
  - "`kotoba-rad`'s role is unchanged from ADR-2607072200 (identity/authorization only): it signs and gates the journal's commits/refs once an actor repo runs on real `kotoba-git` history, but this ADR does not expand kotoba-rad into a data store"
related:
  - 90-docs/adr/2607072200-kotoba-git-kotoba-rad-content-addressed-vcs.md
  - 90-docs/adr/2607032430-kotoba-datom-log-structured-engine-redesign.md
  - 90-docs/adr/2607022300-itonami-gftdcojp-private-tenant-kotoba-rad-git-storage.md
  - 90-docs/adr/2607051410-net-kotobase-distributed-storage-mesh-design.md
  - 90-docs/adr/2606282000-cloud-workers-kotobase-external-storage.md
  - orgs/etzhayyim/root/90-docs/adr/2606231200-kotoba-rad-sovereign-actor-identity.md
  - orgs/etzhayyim/root/90-docs/adr/2606251200-kotoba-rad-git-transport-binding.md
supersedes: []
superseded_by: []
---

# ADR-2607072300: actor 公開データの git-repo 常駐化 — git journal を一次ソース、kotobase.net を派生インデックスに

**Status**: accepted（設計方針の確定。実装は無し — 下記 Acceptance note）
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（本セッションでの質問「etzhayyim などの actor ごとの実データ保持は、public
データを git repo 自体に持ち、DataLad/Radicle/kotobase で完結する設計になっているか」への
調査結果 — 3層がまだ噛み合っていないと判明 — を受けて "do it" と指示）

**Acceptance note**: この ADR が「accepted」なのは **設計方針の確定** であり、実装完了では
ない。ここで決めるのはファイル配置・スキーマ・各層（git journal / kotoba-git / kotoba-rad /
kotobase-peer / DataLad）の役割分担であって、取り込みパイプラインのコードは本 ADR では
一切書いていない（下記「What this ADR does NOT decide」参照）。

## Context

本セッションの調査（ADR化前の実測）で、次の3層が **別々に存在し、まだ統合されていない**
ことを確認した:

- **actor の git repo には業務データが無い。** `orgs/etzhayyim/com-etzhayyim-cargo` の中身は
  `actor-manifest.jsonld`（cron/XRPCルート/`graph.query` ステップの *定義*）と
  `.well-known/did.json` のみ。`graph.query` は実行時に外部へ投げるクエリ定義であって、結果
  データはコミットされていない。
- **actor の実データ（"the graph"）は `kotobase-peer`/`kotobase.net` にしか無い。**
  `transact`/`q`/`pull`/`datoms`（`kotobase-peer/README.md`）経由の XRPC でしか到達できず、
  `git clone`/`west update` してファイルを読むだけでは業務データに到達できない。
- **`kotoba-rad` はデータストアでも転送層でもない。** RID/delegate署名/sigref/push-gate のみ
  （ADR-2607072200）。レプリケーション/ゴシップは明示的に「無い」
  （`kotoba-rad/README.md`）。
- **DataLad は `m365-archive`/`mangaka-data` の2つの大容量バイナリ専用**
  （`manifest/repos.edn` `:datalad`）で、actor の公開データへの拡張設計は無い。
- **git だけで読める公開データは `orgs/etzhayyim/root/80-data/kotoba-rad/*.identity.journal.edn`
  だけ**（320ファイル、`[cid attr value tx op]` 形の EDN quad log — 実物を確認）。ただし内容は
  RID/delegate/did-web の *identity* 事実だけで、actor の *業務* データではない。

一方で ADR-2607032430（D2）は「actor = shard = repo = RID = graph = IPNS head」という収束を
既にターゲットとして掲げ、ADR-2607072200 は `kotoba-git`（content-addressed commit DAG）と
`kotoba-rad`（RID/delegate/sigref）を `kotobase-peer` の実プリミティブの上に作った——ただし
「どちらの repo も本番サーフェスに未接続」（同ADR Consequences）。つまり **収束の方向性と
下敷きになるプリミティブは既に存在するが、「actor の public データを実際にどこへどう置くか」
という具体的な配置・スキーマの決定が欠けていた**。本 ADR はその欠けている決定を埋める。

## Decision

**actor の public データは、actor 自身の repo に直接コミットされた EDN quad-log
（`80-data/public/*.journal.edn`）を一次ソースとする。`kotobase.net`/`kotobase-peer` は
そのジャーナルを取り込んで作る派生インデックスであり、ジャーナルの方が正本。DataLad は
ジャーナルが CID で参照する大容量バイナリだけを扱う。`kotoba-rad` の役割は ADR-2607072200
から変更しない。**

### Layering

```
actor repo (GitHub 上、今日と同じ transport)
  80-data/public/*.journal.edn        <- 一次ソース。git clone/west update だけで
                                          EDN reader が読める。サーバ不要。
       |
       | (今日: 既存 identity journal と同形式の EDN quad [e a v tx op])
       | (将来: kotoba-git 実運用時は commit DAG 自体がこのジャーナル — ADR-2607072200 Tier2)
       v
kotobase-peer 取り込み (fold job, 本ADRでは未実装)
       |
       v
kotobase.net (arrangement 上の派生インデックス。actor-manifest.jsonld の
              graph.query が既に前提にしている XRPC クエリ面はここで低遅延に応える)

DataLad (git-annex + B2, opt-in per-actor)
  journal 内の CID が指す大容量バイナリ(画像/動画/モデル重み等)だけをここに置く。
  ジャーナル自体には CID 参照のみを書く(bytes は書かない)。
```

- **Tier 1（今すぐ使える。新規インフラ不要）**: actor は自分の repo に
  `80-data/public/<domain>.journal.edn` を追加コミットするだけで、public データを
  git-clone-readable にできる。フォーマットは既存の identity journal と同じ
  `["<subject-cid-or-id>" :attr value tx :add]` ベクタの追記ログ（`orgs/etzhayyim/root/
  80-data/kotoba-rad/gtin.identity.journal.edn` に実例あり）。GitHub が今日と同じ transport
  のまま——kotoba-git/kotoba-rad が本番配線されるのを待たない。
- **Tier 2（将来。ADR-2607072200 が既に「未配線」と認めている工程が終わった後）**: actor
  repo の正本 VCS ホストが `kotoba-git` 自体になれば、`kotoba-git.repo/persist!` で書かれる
  commit の arrangement quad がそのままこのジャーナルになり、フラットな EDN ファイルは
  正本ではなく派生物になる。`kotoba-rad` の `sigref`/`push-gate` はそのまま
  この journal の commit/ref に対して機能する——`kotoba-rad` 側の新規コードは不要（同ADRが
  既に「RID/delegate はプレーンな CID 文字列しか扱わない」と設計している通り）。
- **kotobase.net の役割の反転**: 今日は kotobase.net が唯一のストアだが、本設計では
  「git-authoritative なジャーナルを取り込んで作る、再構築可能な派生インデックス」に
  位置づけを変える。`actor-manifest.jsonld` の `graph.query` ステップが前提にしている
  低遅延クエリ面はそのまま kotobase.net が担うが、ジャーナルさえ残っていれば
  kotobase.net 側のインデックスは失っても再構築できる——今日の「kotobase.net が唯一障害点」
  という状態（ADR-2607051410 Context）への一つの緩和にもなる。
- **DataLad の役割は拡張するが、既存パターンのままスコープする**: ジャーナルが大容量
  バイナリを CID で参照する場合のみ、その actor repo を `manifest/repos.edn` の
  `:datalad` に `m365-archive`/`mangaka-data` と同じ形（`:annex-remote "b2" :group
  "datalad"`）で追加登録する。ジャーナル自体にバイト列は書かない——これは `kotoba-git`
  が既に採っている「ログには content hash、実体は別」という形と同じ。ほとんどの actor は
  大容量バイナリを持たないので、これは default ではなく opt-in のまま。
- **`kotoba-rad` の役割は変更しない**: identity/authorization のみ。本 ADR は
  `kotoba-rad` にデータストアやレプリケーションの役目を追加しない。

## What this ADR does NOT decide

- **取り込みパイプライン（journal → kotobase-peer の fold job）のコードは書いていない。**
  `west update` 契機で動かすのか、GitHub webhook 契機か、`kotobase.net` 側からの pull かは
  未定——設計のみ。
- **既存 320件の identity journal、または既存 actor の kotobase 業務データを、この
  `80-data/public/` フォーマットへ移行する作業はしていない。** ADR-2607072200 が既に
  「follow-up、ここではやらない」と認めている移行と同じ扱い。
- **pilot actor を選定していない。** どの actor で最初に `80-data/public/*.journal.edn`
  を採用するかは実装時に決める。
- **並行 agent セッションによるジャーナルへの同時コミットの衝突解決規約は、新規に定義しない。**
  CLAUDE.md 既存の「1 task = 1 branch = 1 worktree」「サーバ側マージ」運用をそのまま適用する
  想定で、本 ADR では再定義しない。
- **`kotoba-git`/`kotoba-rad` が本番未配線という状態自体は変えない。** Tier 2 は
  ADR-2607072200 が既に挙げている p2p/transport のパッチ待ちのまま——本 ADR はそれを
  前倒しで解決しない。
- **`80-data/public/` 配下のドメインごとの attribute 語彙（`:cargo/*`, `:gtin/*` 等）の
  具体的スキーマは決めていない。** ドメインごとの設計は各 actor 実装時のフォローアップ。

## Consequences

- 今すぐ、どの actor でも `80-data/public/*.journal.edn` を追加コミットすれば「public
  データが git-clone だけで読める」状態を作れる——ただし取り込みジョブが無い間は
  「git で読めるが kotobase ではまだ query できない」状態にとどまる。そのギャップを閉じる
  取り込みジョブの実装が次の一歩。
- ADR-2607032430（D2）が掲げていた「actor = shard = repo = RID = graph = IPNS head」の
  収束方針に、欠けていた「実データはどのファイルにどう置くか」という具体的な決定を与える。
- kotobase.net の位置づけが「唯一のストア」から「git-authoritative なソースの上の派生
  インデックス」に変わるという方向性は、ADR-2607051410（net-kotobase 分散ストレージ mesh
  設計）の今後の検討にも影響しうる——同ADRを書き換えるものではなく、参照関係として
  記録するのみ。
- DataLad の適用範囲は「大容量バイナリのみ・per-actor opt-in」のまま据え置かれ、
  「public データ全般の複製層」への拡張は本 ADR でも見送られたと明記される——将来
  誰かがそれを提案する場合は、この決定を踏まえた上で別 ADR にする。

## Verification

実装ゼロ（設計のみ）。既存の identity journal ファイル形式・`kotobase-peer` の
`transact`/`q`/`pull` API・`manifest/repos.edn` の `:datalad` セクション形式を実地確認した
上でこの設計を組んだが、`80-data/public/*.journal.edn` を実際に書いた actor はまだ無く、
取り込みジョブのコードも存在しない。
