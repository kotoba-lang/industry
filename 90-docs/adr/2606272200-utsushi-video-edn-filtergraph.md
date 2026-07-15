# ADR-2606272200: 動画（時間ベースメディア）を EDN コンテナ文法 × `defgraph` filtergraph × capability-gated codec で扱う `utsushi`（映し）— ffmpeg 的ライブラリ設計

**Status**: proposed (draft / たたき台)
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

> 位置づけ: `kasane`（重ね, ADR-2606272100 = 静的グラフィック PSD/AI/PDF の純 cljc + EDN
> データ駆動）の**時間軸メディア版・姉妹 ADR**。kasane が「レイヤの重ね」を正規化するのに対し、
> `utsushi`（映し）は「フレームの映し（映像 = 動画/音声）」を扱う。プロジェクト名 `utsushi` は
> **確定**（2026-06-27）。

## Context

ffmpeg / libav に相当する **動画・音声（時間ベースメディア）を EDN + Clojure cljc で扱う
ライブラリ**は現状このリポジトリに存在しない（調査: 2026-06-27）。近接物は `kotoba-ingest`
の `media`（`MediaIngestor` → Vault blob + `media/*` datom + cross-modal embed）と
`kotoba-llm` の WGPU スタック（f32 GPU compute）だが、いずれも **codec / コンテナ / filtergraph
の処理基盤ではない**（前者は取り込み・埋め込み、後者はモデル推論/学習）。

「ffmpeg のような動画ライブラリを kotoba safe-clj 上で設計するとどうなるか」を固定するのが本 ADR。
**結論を先に: libavcodec を `.kotoba` で書き直すのではない。** kotoba が EVM/BTC で確立した
「read+verify surface だけ言語に出し、重い native は capability-gated host import に隔離する」
哲学と、kasane の「純 cljc + EDN データ駆動」哲学を、**メディアの層ごとに使い分けて合成する**。

前提となる ecosystem の事実（実装済み）:

- **`kotoba-clj`**: EDN-subset → WASM コンパイラ。`loop`/`recur` + byte-builder + heap
  vec/map prelude + in-guest CBOR decode/encode 済み（CLAUDE.md kotoba langgraph workstream）。
  Component export は `run: func(list<u8>) -> result<list<u8>, string>`。
- **安全モデル 4 点**（本設計の核）:
  1. **fuel/gas** — `kotoba-clj/src/run.rs:40` `run_with_fuel`（命令単位 fuel trap）+ WIT
     gas（assert=10/query=100/llm=1000/limit=10M）。信頼できない入力で host をハングできない。
  2. **deny-by-default capability** — `kotoba-clj/src/policy.rs` `Policy::deny_all()`。使う
     host import が allowlist の部分集合でなければ**モジュールを emit しない**（`policy.rs:9-21`
     確定的封じ込め）。`CapClass`(`policy.rs:48`) = GraphRead/GraphWrite/Infer/Auth。
     `egress`/`secrets`/`clock`/`random` は**予約フィールド**（`policy.rs:132-140`, "reserved —
     no host import maps to it yet"）。
  3. **effect soundness（T2 定理）** — `kotoba-clj/src/effects.rs`。`{:effects #{…}}` 宣言を
     推移的閉包で検証し under-declaration を**コンパイル時に reject**。`KNOWN_EFFECTS`
     (`effects.rs:43`) = graph-read/graph-write/infer/auth。
  4. **content-addressing** — フレーム/パケット/grammar/filtergraph すべて CID 化でき、
     `cold_query_sparql_bgp_cached`（CID-MV cache）と同じ仕組みで結果が自動メモ化される。
- **`defgraph` DSL + Pregel BSP**（CLAUDE.md langgraph workstream D / `kotoba-vm`）— 決定論的
  （inbox sort + checkpoint）な DAG 実行基盤。
- **kasane.decode**（ADR-2606272100）— EDN binary 文法 DSL を解釈する純 cljc エンジン。
  `:struct/:seq/:switch/:when/:sub/:codec` を宣言で扱える。**これを `utsushi` のコンテナ層が
  依存として再利用する。**
- **大容量バイナリ規律（CLAUDE.md）** — フレーム実体は git/EDN にインラインせず Vault blob /
  B2+DataLad の CID で持つ。

## Decision

新規 west project **`utsushi`（映し）** を起こし、ffmpeg 的処理を **4 層に分割**する。各層は
「純 cljc + EDN データ駆動（kasane 流）」と「capability-gated native（EVM/BTC 流）」の
どちらに倒すかを**層ごとに正直に決める**。

### 0. 層構成と「どちらの哲学に倒すか」

| 層 | ns / 実体 | 哲学 | 外部依存 |
|---|---|---|---|
| **① コンテナ demux/mux** | `utsushi.container`（grammar/*.edn を kasane.decode で解釈） | 純 cljc + EDN | ゼロ |
| **② ビットストリーム・フレーミング** | `utsushi.bitstream`（NAL 分割 / SPS-PPS / ADTS / Opus packet — ヘッダのみ） | 純 cljc + EDN | ゼロ |
| **③ codec カーネル（実 decode/encode）** | `utsushi.codec`（R0: opaque / R1: native host word） | capability-gated native | R1 のみ |
| **④ filtergraph オーケストレーション** | `utsushi.graph`（`defgraph` 射影） | 純 cljc（defgraph） | ゼロ |
| 横断: フレーム/パケット実体 | Vault blob + `media/*` datom（CID） | content-addressed | ゼロ |

### 1. コンテナ層 — 純 cljc + EDN 文法（kasane.decode 再利用）

MP4/MOV（ISO BMFF box）、Matroska/WebM（EBML）、MPEG-TS、WAV/AIFF、Ogg は **box/atom/element
の（半）線形構造** であり、kasane の `:struct/:seq/:switch/:sub` DSL でほぼ宣言できる。
`utsushi.container` は **kasane.spec/kasane.decode をそのまま依存**にして外部依存ゼロを継ぐ。
MP4（最も使われる）の例:

```clojure
{:meta {:id :mp4 :endian :big}
 :types
 {:box        ;; ISO BMFF box（再帰）
  [{:id :size :type :u32}
   {:id :type :type :str :encoding :ascii :size 4}
   {:id :largesize :type :u64 :when [:expr [:= [:field :size] 1]]}
   {:id :payload :type :switch :on [:field :type]
    :cases {"moov" {:type :seq :until :box-end :of :box}   ; container box
            "trak" {:type :seq :until :box-end :of :box}
            "mdat" {:type :blob :size [:expr [:box-remaining]]}  ; ★ 実体は CID 化
            :default {:type :blob :size [:expr [:box-remaining]]}}}]}}
```

`mdat`（メディアデータ本体）は **decode せず blob（CID）として通す**。コンテナ層が返すのは
**box ツリー + サンプルテーブル（stbl: chunk offset / sample size / time-to-sample）** であり、
これだけで demux（elementary stream への切り出し）・remux・メタデータ編集・トリム・連結が
**再エンコードなしで** できる。実運用のメディア操作の多くはこの層で完結する。

### 2. ビットストリーム・フレーミング層 — 純 cljc（ヘッダのみ）

H.264/H.265 の NAL unit 分割（Annex B start code / length-prefixed）、SPS/PPS の解像度・
profile、AAC の ADTS フレーム、Opus packet 境界 — これら**フレーミングとパラメータ・メタ**は
EDN 文法 + 純 cljc で取れる。**ただしエントロピー復号後の画素は取らない**（kasane が
DCTDecode/JPEG を opaque に通したのと同じ boundary）。この層の出力はパケット境界・PTS/DTS・
codec パラメータで、③ へ渡す「復号ジョブの記述」になる。

### 3. codec カーネル層 — 正直な 2 段構え

実コーデックの内側（DCT/IDCT・動き補償・CABAC/CAVLC・AV1 復号、エンコードの動き探索）は
**純 cljc では framerate で回らない**。理由は §Consequences に明記。よって:

- **R0: opaque blob として通す。** demux/remux/trim/concat/メタ/字幕抽出はコンテナ層で
  完結するので、R0 でも実用価値が出る（kasane の DCTDecode-opaque と同じ割り切り）。
- **R1+: capability-gated native host word。** EVM の `bind_evm` / BTC の `bind_btc` と同型に、
  pure-Rust codec crate もしくは WGPU（`kotoba-llm` の wgpu v24 経路）を **host import** として
  言語に露出する。この層**だけ** kasane の「純 cljc・外部依存ゼロ」哲学から**明示的に逸脱**し、
  capability で隔離して安全性を回収する:
  - `policy.rs:48` の `CapClass` に `MediaDecode` / `MediaEncode` を追加。
  - `effects.rs:43` の `KNOWN_EFFECTS` に `:media-decode` / `:media-encode` を追加。
  - codec host fn は **新規 WIT interface `media`/`codec`** を `kotoba-runtime/wit/world.wit`
    の `kotoba-node` world に追加し、既存の `evm`/`btc`/`egress` と同列に bind する（host 側
    dynamic Val dispatch、guest 同梱禁止 = CLAUDE.md「禁止」節を踏襲）。
  - **net 入出力は実は host fn が既存**: `kotoba-node` world は `egress.fetch(method, url,
    headers, body, timeout-ms) -> result<(u16, list<u8>), string>`（CALL_FOREIGN, 1000 gas）を
    既に持つ。ギャップは **safe-clj 側**で、`policy.rs:137` `egress` は予約フィールドのまま
    host import 未配線 — R1 は「ランタイムにある `egress.fetch` を safe-clj の cap として
    配線し allowlist 拘束する」作業（fs は別途 WASI preview2 経由か新 host fn）。
  - **per-frame gas 会計**で 1 トランスコードジョブの総コストを上限拘束。
  - 色空間変換・スケーリング・軽量フィルタは WGSL（kotoba-llm 既存パターン、"GPU 上で
    FP8 計算禁止" と同様に dtype 境界を引く）に乗せる。ランタイムは **wasmtime 25 +
    Component Model + WASI preview2**（`kotoba-runtime/Cargo.toml`）。

### 4. filtergraph 層 — `defgraph` への射影（kotoba の核資産）

ffmpeg の `-filter_complex`（`scale → fps → overlay → encode` の DAG）は kotoba の `defgraph`
と**同型**。各 filter = Pregel の 1 vertex、frame = channel state（実体は CID）、reducer は
`Override`（フレーム列は last-write）:

```clojure
(defgraph transcode
  {:effects #{:media-decode :media-encode}}   ; ← effect row。純フィルタは #{} で graph 非汚染を型保証
  (node :demux  (uts/demux input-cid))         ; ① コンテナ層
  (node :decode (uts/decode :h264 (:packets %)))  ; ③ R0:opaque / R1:native
  (node :scale  (uts/vf-scale 1280 720 (:frames %)))
  (node :encode (uts/encode :h265 {:crf 23} (:frames %)))
  (edge :demux :decode) (edge :decode :scale) (edge :scale :encode))
```

`graph_def_cid`（CLAUDE.md StateGraph 設計, クロージャを含めず topology のみ）が**ジョブ ID**に
なり、**同一入力 CID + 同一 filtergraph CID → 同一出力 CID** が成立。CID-MV cache がそのまま
トランスコードのメモ化になる（再エンコード不要・分散複製可）。決定論・checkpoint・分散実行は
Pregel BSP host の既存性質をそのまま継承する。

### 5. フレーム/パケット = Vault blob + `media/*` datom（言語境界を生バイトで跨がない）

生フレーム（4K = 数十 MB）を `list<u8>` で言語境界に lift/lower するのは非現実的。よって
フレーム/GOP/パケットは **Vault blob（CodecAware チャンク, `BlobManifest CID`）** とし、
`.kotoba` のノードは **CID（i64 handle）+ 小さなメタ（解像度/pts/codec）だけ**を扱う。索引は
既存 `MediaIngestor` の `media/*` datom を `media/frame/{pts}`・`media/stream/{idx}`・
`media/packet/{n}` に拡張する。重いバイトは host 側で blob を引いて native 処理し、結果 blob の
CID を返す。

### 6. 射影

EDN（canonical, doc/job CID）/ kotoba Datom（`media/*`, `utsushi.quads`）/ defgraph 実行
（Pregel）。

## Consequences

- (+) **コンテナ操作（demux/remux/trim/concat/metadata/subtitle）は純 cljc + EDN で外部依存ゼロ**、
  JVM/cljs/WASM(kotoba-clj) で同一コード、content-addressed。kasane.decode をそのまま再利用。
- (+) **filtergraph が `defgraph` に同型** → Pregel の決定論・checkpoint・分散・CID メモ化に
  そのまま乗る。ffmpeg の filter 文字列が content-addressed な実行可能 DAG になる。
- (+) **安全モデル 4 点（fuel/cap/effect/CID）が「信頼できない動画ファイル・ユーザー投稿
  フィルタ」にそのまま効く。** 細工 stream で host をハングさせられず（fuel）、フィルタが
  こっそり fs/net に出れば**コンパイルが落ち**（effect soundness）、codec 権限は ambient に
  湧かない（deny-by-default）。ffmpeg の seccomp/プロセス分離より細粒度。
- (+) フレーム非インライン（CID 参照）で EDN/git 軽量、実体は Vault/B2+DataLad 既存規律に乗る。
- (−) **実コーデックの復号/符号化（DCT/動き補償/CABAC/AV1）は純 cljc では framerate で回らない。**
  Explore 調査（2026-06-27）で確認した codec-hostile な実態:
  - **SIMD（v128）なし**（wasmtime 25 default、guest に vector 命令が出ない）→ scalar のみで
    native 比 10–100× 遅い。スレッドもなし、`run.rs` は**命令ごとに fuel 消費**。
  - **streaming 不可** — Component は invoke-once/return-once。フレーム逐次デコードは毎回別
    invocation + 別 return-area 確保になり、デコーダの状態保持が言語内でできない。
  - **byte API が write-only builder**（`bytes-alloc`/`byte-append!`/`bytes-finish`）で
    random-access 書き込み（`byte-set!`）やスライス読みが無い → ビットストリーム・デコーダの
    実装が極めて不自由。値モデルも i64 中心（集約型はメモリ間接）。
  よって codec カーネルは R0 opaque、R1 で native host word（専用 `media`/`codec` WIT
  interface）— **この層に限り kasane の純 cljc 哲学から逸脱**し capability で回収する。
- (−) 生フレームを言語境界で `list<u8>` 横断できない → CID/handle 経由が必須。Component の
  canonical-ABI は**フレームごとに cabi_realloc + return-area memcpy** を強いる（zero-copy 共有
  メモリは無い）ので、§5 のフレーム=CID/blob 設計は性能上も必然。
- (−) **リアルタイム低遅延ライブ（WebRTC 受け等）は scope 外**。R0 はファイル/blob ベースの
  バッチ transcode。ライブは `kotoba-turn`（TURN relay）+ 別 ADR。
- (−) `policy.rs` の `egress`/`secrets`/`clock`/`random` は現状 reserved（host import 未配線）
  → fs/net 入出力には**その配線が前提実装**。`clock` を開けると timing channel 面が増える点に
  留意（codec の分岐時間からの side channel）。
- (−) codec native（R1）は GPL/LGPL のライセンス境界に触れうる（libav 由来 crate）。pure-Rust
  codec を優先し、proc executor での ffmpeg ラップは「却下した代替 1」の通り R0 非採用。

## Alternatives considered

1. **ffmpeg/libav CLI を `process` executor でラップ（yorishiro 的）** — R0 不採用。kasane と
   同理由（外部 PATH 依存・GPL/LGPL ライセンス・移植性・サンドボックス負債）。ただし codec
   native を R1 で導入する際、pure-Rust crate が無い codec に限り **proc cap 隔離での再考余地**は
   正直に残す（capability + fuel で囲えるため）。
2. **codec も含め全部を純 cljc/`.kotoba` で実装** — 却下。video codec 内側ループは framerate
   非現実的（SIMD/threads 無し・fuel per-instruction）。kasane が JPEG を deferred にしたのと
   同じ理由。
3. **生フレームを `list<u8>` で言語境界横断** — 却下。数十 MB/frame の lift/lower は ABI コストで
   破綻。CID/handle 経由が正。
4. **filtergraph を独自 DSL で新設** — 却下。`defgraph` + Pregel の決定論/checkpoint/分散/
   CID メモ化の既存資産を捨てる理由がない。
5. **フレームを EDN にインライン（base64 等）** — 却下。CLAUDE.md 大容量バイナリ規律違反。
6. **codec を `egress.fetch` でローカル codec マイクロサービスに委譲** — R1 の有力な一形態
   として保持。既存 `egress.fetch`（1000 gas, allowlist 拘束可）で local の codec daemon に
   投げれば、kotoba プロセス内に native codec を抱えずに済む（ライセンス境界も外に出せる）。
   ただし「外部プロセス依存」という負債は proc ラップと同質なので、R0 は不採用・R1 で
   pure-Rust/WGPU host word と比較衡量する。

## Migration / 立ち上げ手順

1. 本 ADR をマージ（`.md` + `.edn`）。
2. west project 追加: `manifest/repos.edn` に `com-junkawasaki/utsushi` を登録 →
   `nbb scripts/gen-west-manifest.cljs`（手書き禁止 / CI は `--check`）。
3. **コンテナ先行**: `utsushi.container` を kasane.decode 依存で起こし、`grammar/mp4.edn`
   （ISO BMFF box ツリー + stbl サンプルテーブル）をゴールデンベクタ付きで固める。
4. **最初の E2E**: MP4 demux → elementary stream packet を Vault blob(CID) 化 → **再エンコード
   なしの trim/concat で remux** → `media/*` datom 射影 → malli validate まで 1 本通す。
5. **filtergraph 最小例**: passthrough + remux の `defgraph` を Pregel で回し、`graph_def_cid`
   → 出力 CID のメモ化（同一入力で 2 回目はキャッシュヒット）を確認。
6. **R1 codec**: `CapClass::MediaDecode/MediaEncode` + `:media-decode/:media-encode` effect を
   `kotoba-clj` に追加 → pure-Rust decoder もしくは WGSL を host word 化（`bind_evm` パターン）→
   per-frame gas 会計と fs/net cap 配線。

## References

- 姉妹 ADR（静的グラフィック版・純 cljc + EDN 哲学の出典）: ADR-2606272100 `kasane`
- kotoba runtime / EDN-subset コンパイラ: `orgs/com-junkawasaki/kotoba/crates/kotoba-clj`
  （`run.rs:40` fuel, `policy.rs:48` CapClass, `effects.rs:43` KNOWN_EFFECTS）, ADR-2606241700
- 安全モデル: `docs/ADR-safe-capability-language.md`（kotoba 内）
- capability-gated native の先行例: EVM `bind_evm` / BTC `bind_btc` + 既存 `egress.fetch`
  （`kotoba-runtime/src/host.rs`, `wit/world.wit` の `kotoba-node` world, `Cargo.toml` =
  wasmtime 25 + component-model + wasi preview2）, CLAUDE.md「EVM/BTC 互換 — read+verify surface」
- filtergraph 射影先: `defgraph` + Pregel BSP（`kotoba-vm`, CLAUDE.md StateGraph 設計）
- メディア取り込み/索引の既存資産: `kotoba-ingest` `media`（`MediaIngestor` → Vault blob +
  `media/*` datom + cross-modal embed）, `kotoba-llm` WGPU スタック
- フレーム実体の置き場: Vault blob（`kotoba-vault` CodecAware チャンク / BlobManifest CID）,
  大容量バイナリ規律 `CLAUDE.md` + ADR-2606241428
- manifest 運用: `manifest/repos.edn`, `scripts/gen-west-manifest.cljs`, ADR-2606271500
- 文法 DSL: kasane（EDN データ + Clojure 解釈, cf. Kaitai Struct）
