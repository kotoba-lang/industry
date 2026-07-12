# ADR-2607121600: local-manimani ネイティブアプリ(desktop/mobile)の Rust データ層を kotobase スタックへ移行

**Status**: proposed
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki

## Context

`orgs/gftdcojp/local-manimani` の `git pull` + 常駐化作業中、ネイティブアプリの
ビルドが失敗した:

- `tauri/src-tauri/Cargo.toml`(desktop)と `mobile/src-tauri/Cargo.toml`
  (Android/iOS)がいずれも `kotoba-core`/`kotoba-datomic`/`kotoba-edn`/
  `kotoba-ipfs`/`kotoba-auth`/`kotoba-ipns-record` を相対パス
  (`../../../kotoba/crates/<name>`)で参照しているが、参照先の Rust
  workspace が現存しない。
- コメントは「`kotoba` は `com-junkawasaki/kotoba` が canonical
  (`etzhayyim/kotoba` は撤去済み)」と主張していたが、実際に GitHub 上の
  `com-junkawasaki/kotoba` を確認すると `Cargo.toml`/`crates/` が一切無く、
  `deps.edn`(Clojure)+ `.kotoba` 言語コンパイラ + ドキュメントのみだった。
- `kotoba-lang/kotoba` 側の Rust workspace(約38万行、`kotoba-core`/
  `kotoba-datomic`/`kotoba-auth` 含む約30 crate)は **2026-07-01 PR #259
  (commit `604896171b`)で丸ごと削除済み**(ADR-2607022600 が棚卸し・
  ADR-2607101000 が追加棚卸し)。
- オーナー確認(2026-07-12、本セッション): 「kotoba-lang で以前は rust を
  使っていましたが、今は rust を使っていません」— Rust 撤去は既定路線であり、
  巻き戻す選択肢ではない。CLAUDE.md の runtime 優先順位
  (kotoba wasm > clojurewasm > cljs > nbb > jvm/bb、Rust は個別 app の
  データ層として新規に書き足さない)とも整合する。

### 現在の Clojure 後継実装(訂正込みの棚卸し)

初回調査(agent 経由)は rename 前の旧名(`kqe`/`quad-store`)を報告しており
古かった。ADR-2607050700(2026-07-05)で実施済みの rename/merge を反映した
正しい対応表:

| 旧 Rust crate | 現在の CLJC 実装 | 備考 |
|---|---|---|
| `kotoba-datomic`(EAVT/Datalog/transact) | `kotoba-lang/datom`(EAVT モデル)+ `kotoba-lang/arrangement`(4-index、旧 `quad-store`+`kqe` merge 先)+ `kotoba-lang/commit-dag`(tx log)+ `kotoba-lang/kotobase-peer`(旧 `kotobase-engine`。`transact`/`q`/`pull` を露出する embed library) | `kotoba : kotobase = Clojure : Datomic` 用語系(ADR-2607032500) |
| `kotoba-core`(CID/dag-cbor/Prolly) | `kotoba-lang/multiformats` + `kotoba-lang/dag-cbor` + `kotoba-lang/prolly-tree` | |
| `kotoba-edn` | 無し。`kotoba-lang/kotoba` の `deps.edn` に `data.json`/`tools.reader` として統合済み | |
| `kotoba-ipfs` | `kotoba-lang/io-ipfs`(`kotoba.lang.ipfs`) | |
| `kotoba-auth`(CACAO) | `kotoba-lang/cacao` + `kotoba-lang/ed25519`(+ `kekkai/cacao.cljc` が重複並存、要統合) | |
| `kotoba-ipns-record` | `kotoba-lang/ipns` / `tech-ipfs-specs-ipns`(同内容) | |

### local-manimani 側の実際の依存箇所

- `tauri/src-tauri/src/main.rs`(1458行): `kotoba_datomic::{q, Connection, Db}`
  + `kotoba_datomic::distributed::*` を **アプリのローカル DB そのもの**として
  使用(ADR-0013)。`queue`/`decide`/decisions ledger など全 Tauri command が
  直接この embedded store を読み書きする。**HTTP プロキシ(companion)は
  一切使っていない** — `server/`(TS/Hono、`/api/queue` 等を既に実装・
  launchd 常駐中)へは繋がっていない、完全に独立した実装。
- `tauri/src-tauri/src/identity.rs` / `cacao.rs`: `kotoba_core::cid::KotobaCid` +
  `kotoba_auth::{did_key, Cacao, ...}` + `kotoba_ipns_record::IpnsRecord` で
  self-mint 身元(did:key)と CACAO 署名検証を実装(ADR-0030)。
- `mobile/src-tauri/src/lib.rs`(999行): 同じく `kotoba_datomic::Connection` を
  embedded store として使用。ただし `run_triage`/`run_insights` コマンドは
  既に `state.companion_url` 経由で外部 HTTP(`agents/` server)にプロキシする
  パターンを持っている — `ingest_items`/`queue`/decisions だけが embedded
  store 直結。

## Decision(提案。実装はこの ADR に含まない)

### 方針: embedded Rust Datalog store を廃止し、既存の `server/`(TS/Hono)
### companion API に一本化する。identity/CACAO のみ Rust 純正実装に縮小する。

理由:
1. `server/` は `/api/queue`・`/api/decide`・`/api/decisions`・`/api/suggest`・
   `/api/auto-triage`・`/api/process` を**既に実装済みで動作確認済み**
   (本セッションで launchd 常駐化・HTTP 200 確認済み)。web 版はこれを使って
   いる。desktop/mobile だけが独自に kotoba-datomic 実装を embed していたのは
   歴史的経緯(ADR-0013 時点で TS server がまだ無かった)であり、今は二重実装。
2. mobile は既に `companion_url` プロキシパターンを持つ(`run_triage`/
   `run_insights`)ので、`ingest_items`/`queue`/decisions もこの経路に統一する
   のは既存パターンの拡張に過ぎない。desktop には companion パターン自体が
   無いので新設が必要。
3. CACAO/identity(kotoba-auth 相当)は Cargo.toml に既に純 Rust 実装可能な
   下位プリミティブ(`ed25519-dalek`・`ciborium`・`base64`)が揃っている ——
   `kotoba-lang/cacao`(CLJC)の wire format をリファレンス仕様として、
   Rust 側で直接再実装できる可能性が高い(削除された `kotoba-auth` crate を
   復元する必要はない)。

### 未決事項(実装 ADR で確定させる)

- **オフラインファースト要件との整合**: ADR-0018(選択可能 StorageRoot と
  iCloud/OneDrive 同期)・ADR-0013(CID-backed distributed store)は、
  desktop/mobile がネットワーク無しでも動作する設計を前提にしていた可能性が
  ある。`server/` companion への一本化は「companion プロセスが起動している
  こと」が前提になるため、**完全オフライン(companion 不在)時の挙動を
  どうするか**(a. companion を Tauri sidecar として自動起動して常にローカル
  で完結させる/ b. オフライン時は機能制限モードにする/ c. オフライン要件
  自体を撤回する)を決める必要がある。
- `kotoba_datomic::distributed`(ノード間分散合意、9738行、ADR-2607101000 で
  「孤児・要判断」のまま Wave 10 保留)を desktop が使っているかどうかの
  精査(`main.rs` の distributed import が実際に呼ばれているか、単なる
  デッドコードか)。
- CACAO 実装を Rust 純正で再実装するか、`server/` に CACAO 検証エンドポイント
  を追加してそちらもプロキシするか。

## Consequences

- (+) `kotoba-datomic`/`kotoba-core`/`kotoba-edn`/`kotoba-ipfs` への依存が
  Cargo.toml から消え、desktop/mobile のビルドが復旧する。
- (+) triage ロジックの正本が `server/`(TS)に一本化され、web/desktop/mobile
  の三重実装(TS + 削除済み Rust + 未着手 Clojure)という状態が解消する。
- (−) 未決事項(オフラインファースト)を先に決めないと実装に着手できない
  ——companion 前提の設計に倒すなら ADR-0013/0018 の一部前提が変わる。
- (−) 本 ADR はコードを一切変更しない。実装は範囲を確定した個別 ADR
  (desktop 用 companion 配線・CACAO Rust 実装・mobile 側の ingest/queue
  移行)に分割して着手する。

## References

- ADR-2607022600(kotoba-database-crates-cljc-migration-roadmap)
- ADR-2607050700(datomic-terminology-repo-rename)— `kqe`/`quad-store` →
  `arrangement`、`kotobase-engine` → `kotobase-peer`
- ADR-2607072000(kotoba-rust-avoidance-policy-and-kotoba-server-gap)
- ADR-2607101000(kotoba-orphaned-rust-crates-migration-roadmap-addendum)
- ADR-2607032500(kotoba : kotobase = Clojure : Datomic 用語系)
- `orgs/gftdcojp/local-manimani` の ADR-0013(CID-backed store)/
  ADR-0018(選択可能 StorageRoot)/ADR-0030(self-mint identity)
