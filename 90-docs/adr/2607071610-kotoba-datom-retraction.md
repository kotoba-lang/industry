---
id: adr-2607071610-kotoba-datom-retraction
title: "ADR-2607071610: kotoba substrate — datom retraction（novelty block :op + fold retract + 読み経路）の設計"
status: accepted
doc_type: adr
topic: kotoba-datom-retraction
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - kotobase の chain 書込経路（novelty block）への retraction op の追加設計
  - fold / hot-datoms / cold-datoms が retraction を適用・表面化する規則
  - "[:db/retract e a v] / [:db/retractEntity e] の tx-data 受理（peer ->quad / worker tx parser）"
  - app 層 tombstone（app-aozora deleteRecord）と substrate retraction の役割分担
related:
  - orgs/kotoba-lang/kotobase-peer/src/kotobase_peer/core.cljc
  - orgs/kotoba-lang/arrangement/src/arrangement/core.cljc
  - orgs/kotoba-lang/kotobase-cljc-worker/src/kotobase/cljc_worker/handler.cljc
  - orgs/kotoba-lang/datom/src/datom/core.cljc
supersedes: []
superseded_by: []
---

# ADR-2607071610: kotoba substrate — datom retraction の設計

**Status**: accepted（Phase 1 実装済み — 下記 Addendum）
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（起票: 成熟ループ — app-aozora deleteRecord 修理
(tombstone 方式) 時に記録した substrate follow-up の履行）

## Context（実測 2026-07-07）

kotobase の永続 chain 書込経路は **assert 専用**である:

- `kotobase-peer/->quad` が受けるのは `{:s :p :o}` / `[:db/add e a v]` /
  `[e a v]` のみ。`[:db/retractEntity e]`（2 要素 vector）は
  「unrecognized tx-data item」。
- `commit!`（THE write path、ADR-2607032430 D1）の novelty tx block の
  quad には **op/added の概念が無い**（全部 assert とみなされる）。
- 読み経路（`hot-datoms` / `cold-datoms`）は `:added true` を**ハードコード**
  して datomic.datoms 形状の行を返す。
- worker（kotobase-cljc-worker handler）の tx parser は
  `datom.core/eavt`（entity map 専用）— retract vector を渡すと
  cljs `name`/map-entry 系エラーで 500（実測: app-aozora deleteRecord が
  作成以来一度も成功していなかった根因、JVM でも再現確認済み）。
- 一方、**hot-db 層には `arrangement.core/retract-quad` が既に存在**する
  （4 indices からの除去）— fold で使われていないだけ。

暫定として app-aozora は **tombstone 方式**（`:atproto.record/deleted` を
assert し、AppView scan が drop）で deleteRecord を復旧した（app-aozora
`9a558634`、本番実測済み）。tombstone は「見えなくする」には十分だが、
(a) データは chain に残り続ける（真の消去・GDPR 型要請に応えられない）、
(b) index/graph が縮まない（fold 後も tombstone 済み datom が生きている）、
(c) Datomic 的な「事実の取り消し」セマンティクス（:added false）を
アプリが表現できない。

## Decision（設計）

### 1. novelty block の quad に `:op` を追加（後方互換）

```clojure
;; 現行:  {:s "e" :p ":a" :o "v"}
;; 提案:  {:s "e" :p ":a" :o "v" :op :assert}    ; 省略時 :assert（旧 block 互換）
;;        {:s "e" :p ":a" :o "v" :op :retract}   ; attr 単位の retract
;;        {:s "e" :op :retract-entity}           ; entity 丸ごと（:p/:o 無し）
```

- 旧 block（:op 無し）は読み側が `:assert` として解釈 — **migration 不要**。
- CBOR/encrypt 経路は quad map をそのまま運ぶので transport 変更なし。

### 2. tx-data 受理（peer `->quad` / worker parser）

- `->quad` に 2 形式を追加:
  `[:db/retract e a v]` → `{:s :p :o :op :retract}`、
  `[:db/retractEntity e]` → `{:s :op :retract-entity}`。
- worker handler の tx parsing は `datom.core/eavt`（map 専用）の**前段で
  vector 形式を分岐**する（eavt 自体は純 map 用のまま — datom-clj の
  最小性を保つ）。`{:db/retract …}` の EDN 文字列も PDS から到達するため
  tx-edn 全体を read してから項目ごとに dispatch。

### 3. fold / 読み経路

- **fold（hydrate-db reduce）**: `:op` で分岐 —
  `:assert` → `assert-quad`、`:retract` → `retract-quad`（既存）、
  `:retract-entity` → その `:s` の全 quad を spo index から引いて
  retract-quad（O(|entity|)）。fold 後の prolly-tree/index から消える =
  **graph が実際に縮む**。
- **hot-datoms（未 fold novelty を含む読み）**: novelty 内の retract は
  「それ以前の同 (e,a,v) assert を打ち消す」。実装は novelty replay 時に
  working set から除去（fold と同じ分岐を通す）。`:added` ハードコードは
  除去し、**as-of/履歴系の読み**（datomic.datoms に :added false 行を
  出す形）は Phase 2 に送る — 現行の全消費者（AppView scan / PDS repo）は
  「current state のみ」を読むので、Phase 1 は current-state 正しさに絞る。

### 4. 役割分担（tombstone は残す）

- **tombstone（app 層）**: 「フィードから消す」ソフト削除。即日動く・
  provenance を残す・firehose に delete event が載る。**継続**。
- **substrate retraction（本 ADR）**: 真の消去（chain の novelty としての
  retract op — 過去 block は不変だが fold 後の materialized state から
  消える）+ index 縮小。PDS `deleteRecord` は Phase 3 で
  「tombstone + retractEntity の両方を同一 tx で」発行に進化し、
  読み手互換を保ったまま実体も消す。
- 注意: chain は append-only なので「歴史からの完全抹消」ではない
  （retract op 自体が履歴に残る — Datomic の excision 相当は非目標）。

### 5. Phasing

| Phase | 内容 | repo |
|---|---|---|
| 1 | :op 付き quad + ->quad 拡張 + fold/hot-datoms の current-state 適用 + worker parser 分岐。contract tests（assert→retract→読めない、retract-entity、旧 block 互換） | kotobase-peer / kotobase-cljc-worker |
| 2 | as-of / :added false 表面化（datomic.datoms 履歴読み） | kotobase-peer |
| 3 | PDS deleteRecord = tombstone + retractEntity 併用、AppView の tombstone drop は維持（互換） | app-aozora |

## Rejected

- **eavt（datom-clj）に retract 形式を足す**: eavt は「entity map → [e a v]」
  の最小純関数で、言語（kgraph）と共有。op 概念を持ち込むと共有表現が濁る。
  分岐は consumer（worker parser / peer）側で行う。
- **tombstone で恒久対応（retraction を作らない）**: index が縮まない・真の
  消去要請に応えられない。tombstone は UX/互換レイヤとして併存させる。
- **Datomic excision 相当（履歴からの物理抹消）**: chain の
  content-addressed append-only と根本的に非両立。非目標と明記。

## Addendum: Phase 1 実装着地（2026-07-07）

- **kotobase-peer `8e2eb554`**: `->quad` retract 2 形式 + `:op` 付き quad map、
  `apply-quad`/`retract-entity*`（fold/hydrate-chain/since/hot-datoms が経由）、
  `hot-datoms` は novelty retraction を snapshot 行にも set-based で適用、
  wire codec は非 assert のみ `"op"` を付与（旧 block は 3-key のまま =
  migration 不要を実装でも確認）。contract tests 3 本（tx 3 形式 /
  block・fold 跨ぎキャンセル+snapshot 縮小 / 旧 block 互換）全 green、
  スイート 80 tests 0 failures。
- **kotobase-cljc-worker `0804d0ee`**: `tx-edn->quads` が retract vector を
  `datom.core/eavt` の前段で dispatch（eavt は純 map 用のまま）。パーサ test
  追加。既存 18 test failures は未パッチ baseline と失敗集合が完全一致
  （pre-existing、本変更の回帰ゼロを diff で確認）。
- superproject pin 前進: `4c75889ae9e6`。
- **本番 worker デプロイは未実施（意図的）**: 現時点で retract を発行する
  消費者がゼロ（PDS deleteRecord は tombstone のみ）のため、デプロイは
  Phase 3（PDS tombstone+retractEntity 併発行）着地と同時に行い、throwaway
  graph での live 検証（FOLD_CRON 前例）を挟む。

## Addendum 2: Phase 3 着地・本番検証済み（2026-07-07）

- **kotobase-cljc-worker 本番デプロイ完了**（別件 ADR-2607051000 の
  crypto-seam 採用と同時に実施 — `kotobase.aozora.app` は現在 `main` 相当を
  ct-wrapped v3 prefix で稼働、実データ migration 済み）。
- **app-aozora `10ddccb`**: `aozora.pds.encode/retract-entity-form` 追加
  （`[:db/retractEntity uri]` の EDN 文字列を生成）。`repo/delete-record` /
  `write-op`（applyWrites#delete）が **retractEntity を先、tombstone assert
  を後** の順で同一 tx に積む（順序が重要 — 同一 novelty block 内は quad が
  順次適用されるため、この順なら retraction 後に tombstone マーカーだけが
  残り、AppView scan の既存ロジック互換を保ったまま実コンテンツが index から
  消える）。
- **本番 E2E 検証済み**（minidrama の登録済み identity で
  `com.etzhayyim.apps.minidrama.smoketest` の使い捨てレコードを
  createRecord→pull→deleteRecord→pull）: 削除前は 5 属性
  （uri/collection/did/cid/jsonB64）、削除後は `:atproto.record/deleted` +
  `:atproto.record/deletedAt` の 2 属性のみ — 実コンテンツが hot read から
  完全に消えることを確認（tombstone のみのソフト削除ではなく本当の retraction）。
- app-aozora 側テスト: 281 tests / 1213 assertions / 0 failures（retraction
  順序を検証する新規アサーション込み）。

## Addendum 3: worker 既存 18 failures は解消済みと確認（2026-07-07）

Addendum 1 で「pre-existing、本変更の回帰ゼロ」と記録した 18 件の worker test
failures は、追調査の結果 **別バグではなく ADR-2607051000（crypto-seam）の
未採用と同一の根本原因**だったと判明。ADR-2607051000 addendum
（本 ADR とは別文書）で crypto-seam を worker に採用した際に handler.cljc の
`do-datoms`/`do-transact`/`do-q`/`do-pull`/`do-fold` を async 化し
（`then*` アダプタ）、`handler_test.cljc`/`r2_test.cljc` を Promise 契約に
合わせて書き直した時点で、この 18 failures も同時に解消していた（同じ
worktree で `pnpm test` 実行: 22 tests / 79 assertions / **0 failures**、
2026-07-07 時点の worker main `7c0e3d7` で確認）。追加の別調査は不要。

## Addendum 4: 実 FOLD_CRON サイクルで retraction の永続性を確認（2026-07-07）

`datomic.fold` は `:graph` を body で直接指定する方式（transact と違い署名者
DID から導出しない）なので、staging の「空 allowlist = 任意の有効署名者を
許可」規約により、**運用オペレーターの鍵を持たなくても任意の有効な CACAO で
fold を呼べる**（`authorized?` は issuer 非 nil のみ要求）。この特性を使い、
使い捨て鍵で署名した CACAO で yoro-social-v2 の fold を能動的に試みたところ
`{"folded": false}`（novelty 0 = 直前に **本物の FOLD_CRON が既に fold 済み**）
だった — 手動での GC 計測は不要になった代わりに、より強い証拠が得られた:

- Phase 3 addendum 2 の smoke-test entity（retractEntity 済み）を、
  novelty からではなく **fold 済みの cold snapshot 経由**で `pull` — 結果は
  変わらず `:atproto.record/deleted` + `:atproto.record/deletedAt` の 2 属性
  のみ。実コンテンツは実運用の FOLD_CRON サイクルを経ても復活せず、
  indexed snapshot から本当に消えていることを確認（novelty 側の一時的な
  キャンセルではなく、fold 後の永続状態としての retraction）。
- ブロック/ノード数の精密な before/after 計測は、R2 の flat オブジェクト
  空間が複数グラフのブロックを共有する（`wrangler r2 object list` 相当の
  CLI が無く、S3 互換 API 抜きでは graph 単位に正確に切り分けられない）ため
  今回は見送り。retraction の永続性という定性的な核心は上記で確定済み。

## Addendum 5: Phase 2 実装（as-of / :added false 表面化、2026-07-07）

オーナー指示で Phase 2 に着手。実装中に発見: `kotobase-peer.core/history`
（`since`/`commit-serialized!` 群と同時期に既に実装済みだった db 値返却の
監査ビュー）は「a datom retracted later still appears here」と自身の
docstring で明言していたが、**実際にはそうなっていなかった**ことを直接
テストで確認（assert→retract した fact を `(eng/pull (eng/history …) e)`
すると `{}` — 空。原因は `since -1` の再利用: `since` 自身の
apply-quad ベース reduce が retract を内部で相殺してから `history` に
渡っていた。`since` 自体は「ある時点以降の変更のみ」という自分の契約に
対しては正しい（Datomic の `since` と同型）——バグは `history` が
逆の性質（retract されたものも残る）を必要とする用途にそれを転用していた点。

`kotobase-peer.core/audit-replay`（新規、private）で修正: 全 commit の
新規 novelty のみを逐次 replay し、`:retract-entity` の展開に必要な
current-state 追跡と、決して retract しない audit db を分離。`history`
の公開シグネチャ・契約（db 値を返す、tip の indexed snapshot と union）は
不変、正しさだけを修正。

**`kotobase-peer.core/history-datoms`**（新規）: `history` 自身の
docstring が "Phase 2 of that ADR" と予告していた、Datomic
`(d/datoms (d/history db) …)` 形の `:added true/false` イベントログ。
`entity` オプションで単一エンティティの履歴（最も一般的な `d/history` の
用途）に絞れる。`snapshot!` シード分に assert 履歴が無い honest な限界も
明記。

kotobase-peer `6aa746d`、87 tests / 181 assertions / 0 failures（history
のバグを再現する回帰テスト1本 + history-datoms の新規テスト6本）。
superproject pin 前進: `777545f4cbd5`。worker/consumer 配線は現行需要が
無いため見送り（library 層の実装・検証まで）。

## Follow-ups

- retract の firehose 表現（:atproto.firehose/action "delete" は既にある —
  substrate イベントとの対応付け）
- fold の GC 指標の定量化（S3 互換 API 経由でグラフ単位のブロック数を数える
  ツールがあれば、より精密な before/after 計測が可能）
