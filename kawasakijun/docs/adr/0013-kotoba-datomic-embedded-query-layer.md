# ADR-0013: Datomic Local を kotoba (kotoba-datomic) に置き換え、アプリ内蔵クエリ層にする

- **Status**: Accepted(feasibility 実証済み — kotoba-datomic 149 tests 通過 + 実 decision を q() で照会成功)
- **Date**: 2026-06-13
- **Deciders**: 河崎純真
- **Context tags**: kotoba, kotoba-datomic, datomic, datalog, tauri, manimani, content-addressed, edn, wasm
- **Related**: ADR-0010(EDN 事実層)、ADR-0012(M365 EDN 事実層)、manimani Tauri アプリ

## Context

これまで M365 事実層の L3 クエリ層は **JVM の `com.datomic/local`** で、`clojure -M:run`
の**別プロセス・オンデマンド再構築**だった。manimani(Tauri/Rust)アプリ本体は Datomic を
一切叩かず、JSONL を Rust で直読みしていた(ADR-0012 で正直に記録済み)。

要望は「Datomic Local を kotoba で動かす」。調査の結果、**kotoba は単なる Clojure→WASM
コンパイラではなく、それ自体が Rust 製の内容アドレス型 Datalog DB** であることが判明した:

- `kotoba-datomic` クレート — "Datomic-compatible facade"。原子は 5-tuple Datom
  `(E,A,V,T,Added)`。`Connection` / `Db` / `transact` / `q`(Datalog)/ `pull` / `entity` /
  `as_of` / `since` / `history` / EAVT 等インデックス。**EDN ネイティブ**、トランザクション
  基底時刻は `KotobaCid`(=内容アドレス)。
- `kotoba-clj` クレート — Clojure-subset → 実 WASM バイトコンパイラ(別レッグ)。

我々の事実層は CID(git-annex key)で内容アドレス化済み → kotoba の `Datom[CID/T]` と整合。
そして Tauri アプリは既に Rust なので、`kotoba-datomic` を**クレート依存として直接埋め込める**。

## Decision

1. **JVM Datomic Local を廃し、`kotoba-datomic` をアプリに埋め込む。** manimani(Tauri/Rust)
   が起動時に JSONL 事実層を読み、`Connection::transact` で datom を投入。`status` / `queue` /
   `needs_reply` / decision 照会は **`kotoba_datomic::q(edn_query, &db, inputs)`** で実行。
   → JVM 不要・別プロセス不要・アプリ内で Datalog が回る。
2. **JSONL を SSoT のまま維持**(ADR-0010)。kotoba-datomic は同じ JSONL から起動時に構築する
   インデックス。永続化が要る場合は kotoba の ProllyTree commit を将来利用。
3. **クエリは EDN データ**。既存 `datomic/queries/views.edn` を Datomic ベクタ形式
   `[:find … :where …]` → kotoba map 形式 `{:find […] :where […]}` に薄く変換するだけ。
4. **スキーマ/トランザクション形式は流用**。`load.clj` が出す `[{:db/id … :attr v}]` /
   `[:db/add e a v]` を kotoba-datomic の `transact` がそのまま受ける(EDN 同形を確認済み)。
5. **kotoba-clj は任意の第2レッグ**。`load.clj` の変換ロジックを WASM 化してアプリ内で走らせる
   構成も可能だが、クエリは EDN データなのでコンパイル不要。当面は不要。

## Architecture

```
JSONL facts (SSoT, CID=annex key)
   │  起動時ロード
   ▼
kotoba-datomic Connection  ──transact──▶ Db (EAVT, content-addressed @KotobaCid)
   │                                         │
   │ kotoba_datomic::q(views.edn, &db)       │ pull / entity / as_of / history
   ▼                                         ▼
manimani Tauri commands (status/queue/decide) ── CLJS UI
```

## Consequences

- (+) 「アプリが kotoba(Datomic)上で動く」が文字通り真になる。JVM 排除、単一プロセス。
- (+) 内容アドレス時刻(KotobaCid)で as-of/history が CID で辿れる — 事実層の CID 設計と一致。
- (+) etzhayyim の自前 DB を実運用ドッグフード。SPARQL/Cypher 表面も将来使える。
- (−) `kotoba-datomic` は kotoba cargo workspace 内のパス依存。manimani から使うには
  git/path 依存追加。依存ツリー(kotoba-core/edn/query + ipfs/store)のビルド時間・バイナリ
  サイズ増を許容する必要(distributed/IPFS を切る feature 検討)。
- (−) Datalog 方言差(ベクタ vs map :find、組込述語の差)は views を移植する際に都度吸収。

## Feasibility(検証手順)

1. `cargo test -p kotoba-datomic --lib` がこのマシンで通る(エンジンが実在・動作)。
2. 我々の decision datom を transact → `q` で `:decisions/ledger` 相当を引く spike。
3. 通れば manimani の Rust に path 依存追加 → `status` を 1 本 kotoba-datomic 経由に置換し
   computer-use で視覚確認 → 段階的に全コマンド移行。


## Fact-layer EDN migration(2026-06-13 完了)

JSONL を EDN-lines(1 行 1 EDN マップ)へ全面移行し、JSONL を除去:
- 変換器 `bin/jsonl-to-edn.py` + 共有 `bin/edn.py`(EDN writer + 最小 reader)。
- 検証: 全 12 ファイル **360,999 レコードを json.loads==edn.loads で round-trip 一致**、
  各ファイルの行数一致(本文改行は \n エスケープ)。
- 消費者: Clojure ローダー(`rd-jsonl`→EDN read、entity 数完全一致)、Tauri アプリ
  (EDN⇄serde_json ブリッジ `edn_line`/`jsonv_to_edn`、起動時 kotoba 構築・全コマンド
  EDN 読み、視覚確認済み)。
- 生産者: extract-facts / extract-attachments / triage-inbox / extract-attachment-files /
  write-prov を EDN 読み書きに。raw 取込(calendar/*.jsonl = Graph 出力)は対象外。
- facts/ は EDN 17 ファイルのみ。B2/GitHub に保存済み。
