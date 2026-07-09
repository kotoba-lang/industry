# (a) kotoba 上の最小オフィスエディタ — ローカルファースト / 未ログイン / インストール不要

## 目的 / 非目的

**目的**：kotoba を「オフィス文書のバックエンド」として使い、ブラウザ単体（HTTPS 一枚）で動く
最小エディタを 1 本作り、kotoba がオフィス基盤になり得るかを検証する。

- 未ログインで即編集できる（匿名 = ローカル保存のみ）。
- データは端末から出ない（IndexedDB / OPFS）。サーバは**任意の同期先**でしかない。
- インストール不要（PWA / 静的配信）。

**非目的（このフェーズでは作らない）**：リアルタイム共同編集、サーバ強制同期、3 アプリ同時。
まず `docs`（ブロック型ドキュメント）1 本に絞る。`sheets` / `slides` は同じ EAV パターンの拡張。

## kotoba 側で「すでにあるもの」（再利用する）

ブラウザ WASM API（`crates/kotoba-wasm/src/lib.rs` の `#[wasm_bindgen] KotobaNode`）：

| 用途 | API | 備考 |
|---|---|---|
| ID 生成 | `generateIdentity()` / `useIdentity(seed_hex)` | Ed25519 → DID。匿名でも端末ローカル ID を持てる |
| 書き込み | `assert(e, a, v)` / `transact(datoms_json)` | datom を write-set へ |
| 暗号書き込み | `assertEncrypted(e, a, plaintext)` / `decrypt(envelope)` | `signal:v1:` クライアント暗号（主権の核・文書 b と連動） |
| コミット | `commit()` → root CID / `commitToIdb()` → `{root,snapshot,blocks}` | content-addressed、ブラウザだけで完結 |
| 署名コミット | `commitSigned()` / `commitHeadSigned(seq, valid_until)` | 同期/公開時に使用 |
| 読み取り | `datomicQ(query_edn, inputs_json)` | ローカル Datalog 評価 |
| 復元 | `snapshot()` / `hydrateFromIdb(cid)` / `replayJournal(text)` | IndexedDB + OPFS ジャーナル |
| 状態 | `datomCount()` / `rootCid()` / `blockCount()` | — |

JS グルー（`crates/kotoba-wasm/web/`）：`kotoba-sw.js`（Service Worker が XRPC を透過インターセプト、
`boot()`→IndexedDB snapshot 読込→OPFS journal replay）、`kotoba-idb.js`（snapshot 永続化）、
`kotoba-blocks.js`（P3 ブロック同期）。**読み取りは 100% ローカル**、書き込みも即ローカル、同期は背景。

> 実装状況：上記 P0–P2（ローカル read/write/commit/IndexedDB/OPFS/SW）は**実装済**。
> SPARQL・Pregel・WASM UDF は**サーバ専用**でブラウザでは使えない（エディタは `datomicQ` だけで足りる）。

## 「足りないもの」= このフェーズで作るもの

1. **オフィス EAV スキーマ**（kotoba には freestanding な `Sheet/Workbook` 型は**無い**。datom 述語として定義する）。
2. **ブロック型ドキュメントの読み書きラッパ**（datom ⇄ ドキュメントモデルの変換）。
3. **最小エディタ UI**（ClojureScript 推奨。kotoba の CLJS 層と整合）。
4. **PWA シェル**（manifest + SW。kotoba-sw.js を土台に）。

## データモデル：ブロック型ドキュメントを datom で表す

kotoba の書き込み API は datom `{e, a, v}` のみ。ドキュメント API は無いので、
**ブロック = エンティティ、属性 = `:doc/*` `:block/*`** で表現する。

```clojure
;; ドキュメント
[doc-cid :doc/kind        :doc/document]
[doc-cid :doc/title       "Q3 戦略メモ"]
[doc-cid :doc/created-at  1719100000000]
[doc-cid :doc/root-block  blk0-cid]      ; ルートブロック参照

;; ブロック（段落/見出し/リスト/表セル… 全部ブロック）
[blk0-cid :block/kind     :block/heading]
[blk0-cid :block/text     "概要"]         ; 機密なら assertEncrypted で signal:v1:
[blk0-cid :block/order    0]              ; 兄弟内の順序（後述）
[blk0-cid :block/parent   doc-cid]
[blk1-cid :block/kind     :block/paragraph]
[blk1-cid :block/text     "原材料費が上昇し…"]
[blk1-cid :block/order    1]
[blk1-cid :block/parent   doc-cid]
```

設計上の要点（kotoba の制約に合わせる）：

- **エンティティ = `KotobaCid`**（content-addressed）。`commit()` がブラウザ/サーバで byte-identical な
  sha2-256 CID を生むので、ブロック ID は安定。
- **値型 = `EdnValue`**（Text/Int/Float/Keyword/Boolean/Bytes）。参照は CID を値に置く。
- **順序**：datom 集合は順序を持たない。`:block/order` を整数で持たせる。将来の挿入対策に
  **分数インデックス（fractional indexing, 例 "a0" < "a0V" < "a1"）を文字列で**持つと再採番が要らず、
  後の共同編集（CRDT 化）にも移行しやすい。まずは整数で可。
- **編集 = retract + assert**。`Datom::retract(e,a,v,t)` で旧値を撤回し新値を assert。
  履歴は CommitDag に残る（= 版管理・タイムトラベルが無料で付く）。
- **削除 = tombstone**（`:block/deleted true`）。物理削除しない（content-addressed のため）。

## 機密フィールドの扱い（主権の核）

本文 `:block/text` のような機密データは `assert` ではなく **`assertEncrypted(e, :block/text, plaintext)`**
を使う。サーバへ同期されても `signal:v1:` 暗号文しか出ない。表示時に `decrypt(envelope)`。
復号鍵の解錠を passkey に紐付けるのが文書 b（`StorageKeyDecrypt` / `SignalKeyUnlock`）。

> トレードオフ：暗号化フィールドは `datomicQ` でローカル全文検索できない（復号後にクライアントで検索）。
> 順序・種別・構造メタ（`:block/order` `:block/kind` `:block/parent`）は平文 datom にして検索可能に保つ。

## データフロー

```
[編集] ─assert/assertEncrypted/retract→ in-memory Arrangement
   │
   ├─ commit()        … ProllyTree へ materialize → root CID
   ├─ commitToIdb()   … blocks + snapshot を IndexedDB へ（durable, オフライン完結）
   └─ (SW) transact → OPFS journal 追記（P2 耐久性）

[起動] boot(): hydrateFromIdb(snapshotCid) → replayJournal(OPFS) → datomicQ で描画
[同期(任意/ログイン時)] commitSigned() → /xrpc/...datomic.transact、背景 deltaSync()
```

匿名ユーザー：`commitToIdb()` までで完結（同期しない）。サーバ不要。
ログインユーザー（文書 b）：`commitSigned()` で CACAO 付き同期 → 複数端末・履歴共有。

## 最小エディタの構成（docs）

- **フロント**：ClojureScript + shadow-cljs（kotoba CLJS 層と同居）。WASM `KotobaNode` を JS interop で叩く。
- **状態**：`datomicQ('[:find ?b ?k ?ord :where [?b :block/parent ?doc][?b :block/kind ?k][?b :block/order ?ord]]')`
  で doc のブロックを取得 → `:block/order` でソート → レンダ。
- **入力**：contenteditable か textarea。各キーストロークではなく **デバウンス（~500ms）→ retract+assert → commit→commitToIdb**。
- **PWA**：`manifest.webmanifest`（standalone, icons）+ kotoba-sw.js 拡張（アプリシェルもキャッシュ）。
  一度開けば `github.io`/`docs.gftd.ai` がブロックされても動く（制限環境の楔）。
- **単一HTMLビルド**（任意・強力）：WASM を base64 で 1 枚の `.html` に同梱。USB/メール配布で
  ネット遮断環境にも届く。

## 段階計画

| Phase | 内容 | 完了条件 |
|---|---|---|
| 0 | EAV スキーマ確定（`:doc/*` `:block/*`）+ datom⇄doc 変換ラッパ | ユニットテストで round-trip 一致 |
| 1 | 読み取り専用ビューア（既存 datom を描画） | サンプル doc が表示される |
| 2 | 編集（retract+assert→commit→commitToIdb）+ 起動復元 | リロードしても編集が残る（オフライン） |
| 3 | `assertEncrypted` で本文暗号化 + 復号表示 | 同期しても暗号文のみ・表示は平文 |
| 4 | PWA 化（manifest + SW キャッシュ）+ 単一HTML ビルド | ネット遮断で再訪問でも動作 |
| 5 | sheets/slides へ EAV 拡張（`:cell/*` `:slide/*`） | 同パターンで 2 本目が動く |

## 既知のリスク / 正直なギャップ

- **共同編集なし**：datom + retract/assert は last-write-wins。複数端末同時編集は競合する。
  本フェーズは単一ユーザー前提。将来は分数インデックス + CRDT（Yjs 等）か CommitDag マージ戦略が要る。
- **スキーマは自作**：`Sheet/Workbook/Chart` は kotoba に型として無い。`:cell/*` 等を自分で定義・運用する。
- **暗号フィールドは非検索**：上記トレードオフ参照。
- **`datomicQ` の表現力**：Datalog セミナイーブ評価。複雑集計（ピボット等）はクライアント側計算で補う。
- **SW の制約**：Service Worker は origin 単位。`docs.gftd.ai` と `sheets.gftd.ai` で別 SW/別ストアになる。
  共通ストアにしたいなら同一 origin + パス分離（`gftd.ai/docs`, `/sheets`）も検討。
