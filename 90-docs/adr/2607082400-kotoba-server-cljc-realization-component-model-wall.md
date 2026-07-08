# ADR-2607082400: kotoba-server の cljc 実現を検討 — component model の壁と gossipsub の壁を実証し、drop-in 置換を却下する

**Status**: accepted (design decision; one small, zero-risk implementation slice included)
**Date**: 2026-07-08
**Deciders**: Jun Kawasaki（指示: 「rust が必要な実装は全て cljc で設計実装」を
`kotoba-server` に対して実現せよ）
**Scope**: `orgs/kotoba-lang/kototama`, `orgs/kotoba-lang/io-libp2p`
（設計判断。実装は kototama の小改修1件のみ）

## Context

ADR-2607072000 は「Rust が必要な実装は全て cljc」という標準方針を記録し、
`kotoba-server`（murakumo が全 Mac-mini fleet ノードに常駐配備する
HTTP dispatch + gossipsub lattice + WASM component host の本番 Rust バイナリ）
を「どの既存 ADR にも記載のない最優先・最高リスクの残課題」として名指しした
上で、**「専用の個別 ADR（段階的カットオーバー・性能要件・ロールバック手段
込み）を起票してから着手する」**という前提条件を設けた。本 ADR がその
「専用の個別 ADR」であり、オーナーの「ADR を実現せよ」という指示への応答。

着手前に、Explore agent による実地調査 + 本セッションでの直接検証を行った
結果、**2つの決定的な壁**が判明した。これにより「kotoba-server を cljc/JVM
で drop-in 置換する」という前提そのものが成立しないことが確定した
（過去に本 assistant が「実装済みと誤認して後で訂正」を繰り返した反省 ——
ADR-2607072400 aiueos⊣kototama —— を踏まえ、着手前に実機検証した）。

### 壁1: WASM Component Model — Chicory(kototama)は既存 mesh guest を読めない（実証済み）

murakumo が現在 fleet に deploy している mesh guest（minidrama の
`drama-profile`/`drama-heartbeat`、kenchi の `valuation-query`/
`valuation-refresh`、kotodama-bot の `ingest`/`reply` 等）は、すべて
**Rust `kotoba-clj` コンパイラ**（`kotoba component build`）が生成する
**WASM Component**（`kotoba:kais` world、WIT interface types で
`func(quad) -> result<_,_>` 等をリフトする canonical ABI）である。

一方 `kototama.tender`（ADR-2607062330 が決定した JVM/Chicory 実行層）は
**core WASM のみを解釈する** —— 本セッションで実機検証:

```
$ kotoba component build mesh/drama_profile.clj --wit-dir … -o drama_profile.wasm
$ xxd drama_profile.wasm | head -1
00000000: 0061 736d 0d00 0100 …    ; magic \0asm + version [0x0d,0,1,0]
                                   ; = Component Model layer-1 preamble
                                   ; (core module would be [1,0,0,0])

$ clojure … -e '(Parser/parse (bytes-of drama_profile.wasm))'
THREW: com.dylibso.chicory.wasm.MalformedException
  section size mismatch, unexpected end of section or function,
  unknown binary version, found: [13, 0, 1, 0] expected: [1, 0, 0, 0]
```

Chicory 1.4.0（kototama が使う唯一の WASM エンジン）は component model の
canonical ABI アダプタを持たず、**バイナリのバージョンヘッダの時点で拒否
する**。これは実装の甘さではなく Chicory というプロジェクトの現状のスコープ
（core-wasm-only interpreter）そのもの —— JVM 上に component model 対応の
WASM ランタイムは、調査した範囲で存在しない。

加えて `kototama.tender` の capability 表（9 imports: `gen-keypair/sign/
verify/sha256-hex/http-post/log-read/log-write/clock-monotonic/llm-infer`）
は mesh guest が要求する `kqe-assert!`/`kqe-query`（datom log 読み書き）を
一切カバーしていない —— そもそも別の用途（cloud-itonami 等の isic actor が
crypto/LLM/HTTP を capability-gated に呼ぶ、ADR-2607072530/2607072600 系列）
のために作られたシステムであり、「mesh の HTTP トリガーゲスト実行層」を
意図していない。

**結論: `kototama` を「そのまま」`kotoba-server` の WASM host に差し替える
ことはできない。** 既存 mesh guest を cljc host で動かすには、component
model の canonical ABI を JVM に新規移植するという、Chicory 自体への
アップストリーム貢献級の別プロジェクトが要る。

### 壁2: gossipsub/libp2p — cljc 側に実装が存在しない

`kotoba-lang/io-libp2p`（旧称の探索時「kotoba-lang/net」、`gossip.cljc` +
`bitswap.cljc`、計215行）を全文確認した。ファイル自身の docstring が
明記するとおり：

> 「this namespace implements *only* the routing/dedup semantics... does
> not open sockets, dial peers, or perform any cryptographic handshake」

peer 接続・トピック購読・メッセージ伝播はおろか、**ソケットを一切開かない
純粋なデータ整形/ルーティング数学のライブラリ**であり、libp2p-gossipsub の
mesh 維持（heartbeat/graft/prune）やピア発見に相当するものは無い。JVM/cljc
のどこにも real libp2p 実装は存在しない（`io-multiformats`/`io-ipld` も
CID/コーデック止まりで transport 層ではない）。

一方 kotoba-server 側（`kotoba-lattice/src/protocol.rs`）の実際の contract
は: `Heartbeat{node_did, roles, labels, caps, free_gas, hosted, lat_ms}`、
`Auction/Bid/Award`（決定的スコアリング）、`LatticeMessage`（CBOR、
`PutTriggers`/`PutRoutes` 等）を **gossipsub の予約トピック** 上でやり取り
する —— これを cljc で「相互運用可能」に再実装するのは、libp2p-gossipsub
仕様全体をゼロから JVM に実装するのとほぼ同義で、単独の大型プロジェクト。

## Decision

### 1. `kotoba-server` の drop-in 置換（wire 互換・既存 guest 互換）は却下する

上記2つの壁により、「既存 mesh app を無変更のまま、既存 Rust ノードと
gossipsub で相互運用しながら、cljc ノードに乗せ替える」という意味での
drop-in 置換は、**今回の ADR では着手しない**。これは
「無計画な wholesale rewrite をしない」という ADR-2607072000 自身の原則の
実行であり、後退ではない —— 実現不可能な計画を書かないことが、この専用
ADR に課された責務の履行そのものである。

### 2. 現実的な代替パス: strangler-fig（新規容量は cljc、既存は Rust のまま）

既存 fleet・既存 mesh app（minidrama/kenchi/kotodama-bot 等）は
**当面 Rust `kotoba-server` のまま**（ゼロリスク、変更なし）。その上で:

- **cljc 側の新しい mesh 相当の実行系は、murakumo を coordination authority
  とする前提で設計する** —— ADR-2607071900 で実証済みのとおり、murakumo は
  既に `fleet.edn` で全ノードを知っており、cross-node auction が未配線
  なため実質的に「murakumo が配置を決めて imperative に指示する」centralized
  な運用に既になっている。gossipsub による decentralized な peer discovery
  を新規に発明する必要はない —— **murakumo → 各 cljc ノードへの plain
  HTTP** で十分（`murakumo/dash.clj` が実証済みの bb + http-kit + background
  loop パターンを流用できる）。
- **guest 言語は `.kotoba`（kototama の実行層）を使う** —— ただし既存
  mesh guest（kqe-assert!/kqe-query を使う kotoba-clj コンパイル済み
  component）をそのまま動かすのではなく、**新規/移植対象アプリを `.kotoba`
  向けに書き直す**という前提を明示する。移植は app 単位で個別判断
  （strangler-fig パターン。一括切替はしない）。
- kototama 側に必要な拡張: (a) `main` 以外の export を汎用的に呼べるように
  する（下記、本 ADR で実装済み）、(b) 将来 `.kotoba` 向け mesh guest を
  書くなら `kqe-assert!`/`kqe-query` 相当の host capability を新設する
  （本 ADR の対象外、必要になった時点で個別 ADR）。

### 3. 本 ADR で実装した唯一の変更（ゼロリスク、`kototama` 単体）

`kototama.tender`（`orgs/kotoba-lang/kototama`）に、`main` 決め打ちだった
呼び出し API を汎用化した:

- `call-export`/`has-export?` —— 任意の 0-arity export 名を呼べる/存在確認
  できる（`call-main` は `call-export` の薄いラッパーとして維持、後方互換）。
- `dispatch-trigger` —— `#{:run :http :tick :kse}` を kotoba-server の
  `net_actor.rs::trigger_name` と同じ文字列（`run`/`on-http`/`on-tick`/
  `on-kse`）にマップし、guest がその export を持たなければ
  `{:dispatched? false}` を返す（kotoba-server が「bound していない
  component にはそのトリガーを送らない」のと同じ非エラー扱い）。

**これは「kototama が mesh guest を実行できるようになった」という意味では
ない**（壁1のとおり、既存 component は依然ロード不可）。単に tender 自身の
呼び出し API から「export 名は `main` 固定」という不要な制約を除いただけ
——将来 `.kotoba` 側に複数 export を持つゲストが生まれた時に使える、
汎用的で安全な基盤の一つ。テスト済み（既存 WAT 規約に倣った新規 fixture、
`bb test`/`clojure -M:dev:test` で緑）。

## Consequences

- (+) 「Rust が必要な実装は全て cljc」という指示に対し、**実現不可能な
  計画を書いて着手し、後で頓挫する**という最悪の結果を避けた。2つの
  壁（component model・gossipsub）は今回の実機検証で確定事実になった
  ので、今後同じ問いが出た時にゼロから再調査する必要がない。
- (+) 実際に価値のある方向（murakumo を coordination authority とした
  strangler-fig、既存 app は無変更）を明示し、次にやるべき最初の一歩
  （kototama への `kqe` 相当 capability 新設、または最初の `.kotoba`
  移植対象アプリの選定）を具体的に指せる状態にした。
- (+) kototama.tender の export dispatch 汎用化は小さいが実質的な改善で、
  本番リスクゼロ（fleet にもRustにも一切触れていない）。
- (−) `kotoba-server` 自体は本 ADR でも「Rust のまま」。これは方針転換
  ではなく**事実に基づく判断**——JVM 側に component model 実装も
  gossipsub 実装も存在しない以上、無理に「cljc で全部やる」を掲げるのは
  ADR-2607072000 自身が戒めた「無計画な wholesale rewrite」と同じ轍。
- 今後の follow-up（優先度順）: ①どの mesh app を最初に `.kotoba` へ
  移植するか（governor-gated な単純用途の app が候補、例えば
  identity/liveness だけの `drama-profile`/`drama-heartbeat` — 既に
  kenchi/minidrama の split-of-duties パターンでこの手の component は
  「検閲対象にならない薄い層」として切り出し済み）、②`kqe-assert!`/
  `kqe-query` 相当 capability の kototama への追加設計、③murakumo →
  cljc ノードの HTTP 契約（deploy/route-table 登録）の具体設計。

## Related

- ADR-2607072000（Rust 回避の標準方針 + kotoba-server 未着手の名指し）:
  本 ADR が履行する「専用 ADR」。
- ADR-2607071900（murakumo cross-node apply, cljc-only）: murakumo が
  既に centralized coordination authority として機能している実証。
- ADR-2607062330（kototama-tender-chicory-execution-runtime）: kototama
  の実行層としてのスコープ（本 ADR がその境界を明確化）。
- ADR-2607071500（minidrama mesh reside 配線）: split-of-duties パターン
  （検閲対象外の薄い identity/liveness component）— 将来の `.kotoba`
  移植候補の選定基準として参照。
- ADR-2607072400（aiueos⊣kototama decision/execution boundary）: 「実装
  済みと誤認して後で訂正」を繰り返さない教訓 —— 本 ADR が実機検証を
  先行させた理由。
