# gftd.ai オフィススイート設計（kotoba 基盤）

`slides.gftd.ai` / `docs.gftd.ai` / `sheets.gftd.ai` を、**ログイン不要・インストール不要・データ主権**の
ローカルファースト・オフィスとして、既存の `kotoba`（分散 datom DB）を基盤に構築するための設計書群。

## 文脈（なぜこの構図か）

- **kotoba** = データ/暗号エンジン。datom モデル・content-addressed ProllyTree・IndexedDB/OPFS 永続化・
  Service Worker・Ed25519 クライアント鍵・`signal:v1:` クライアント暗号・CACAO 認可・**PasskeyGate**（実装済）。
  → 「未ログイン・オフライン・データが端末から出ない」の**実体がほぼ揃っている**。
- 足りないのは **アプリ層**：① オフィス UI と EAV スキーマ、② WebAuthn の人間向け認証ブリッジ、
  ③ svgraph / office-causal の取り込み。本書群がその 3 つ。
- **svgraph / office-causal** = 他社オフィスにない差別化機能（編集可能PPTX書き出し・因果/MECE/so-what 分析）。

## 文書

| # | 文書 | 内容 |
|---|---|---|
| a | [a-office-editor-architecture.md](./a-office-editor-architecture.md) | kotoba 上の最小オフィスエディタ（未ログイン・ローカル保存）のアーキ |
| b | [b-webauthn-cacao-adapter.md](./b-webauthn-cacao-adapter.md) | WebAuthn/passkey → CACAO 認証アダプタ（匿名→passkey 昇格・ログイン任意維持） |
| c | [c-svgraph-office-causal-integration.md](./c-svgraph-office-causal-integration.md) | svgraph / office-causal の出力を kotoba datom に取り込む統合設計 |
| e | [e-pull-request-system.md](./e-pull-request-system.md) | GitOffice — 文書/PPTX/XLSX/DOCX に GitHub 風 Issue / PR / Review / Merge を載せる設計 |

doc e の **Phase 0**（選択肢 1=正規化）は参照実装で実証済み:
[`p1-gitoffice/`](./p1-gitoffice/README.md) — blob⇄要素粒度 datom 変換 + revision 橋渡し +
issue/pr/review/comment ⇄ datoms（`nbb scripts/run-task.cljs test` → 13 tests / 40 assertions / 0 fail）。

## 実装順の推奨

1. **a の docs（ブロック型ドキュメント）最小エディタ** — 「kotoba が本当にオフィス基盤になるか」を最速検証。
2. **c の office-causal 取り込み** — `ocz1:` が content-addressed で datom 主体に最適。差別化を早く乗せる。
3. **b の WebAuthn アダプタ** — 匿名利用を壊さず、主権（E2E 鍵の解錠）を passkey に紐付ける。

> 各文書は調査時点（2026-06）の kotoba / svgraph / office-causal の実コードに基づく。
> 「実装済 / 未実装」を明記しているので、着手時に再確認すること。

## 実装状況（kotoba 本体への着手）

doc a Phase 1-2 + doc b Phase 0-1 の**ロジック層**を kotoba CLJS ツリーに実装済み（wire 契約を bb で検証）。

| 場所 | 内容 |
|---|---|
| `kotoba/crates/kotoba-wasm/web/cljs/src/kotoba/office.cljc` | 組織前提オフィスの datom 変換 + `transact/commit/datomicQ` 駆動（CLJS）+ v_edn コーデック |
| `kotoba/crates/kotoba-wasm/web/cljs/src/kotoba/cacao.cljc` | grant→CACAO payload + `siwe_message` byte 一致再現 + 注入式署名 |
| `…/cljs/src/kotoba/office_test.clj` | bb テスト（wire round-trip + siwe byte 一致）。`nbb --classpath src -e "(require 'kotoba.office-test)(kotoba.office-test/-main)"` → 7 tests / 21 assertions / 0 fail |
| `…/cljs/shadow-cljs.edn` | `storeDocument`/`loadDocument`/`storeOrg`/`storeGrant`/`grantToCacaoB64` を ESM export 追加 |

### CACAO の wasm-bindgen 露出（実装・実機検証済）

doc b の「grant→capability」を**ブラウザネイティブ**にした。`kotoba-auth` を kotoba-wasm の依存に追加し、
**正確な `Cacao` 型 + `did:key`(z6Mk) + `siwe_message` を再利用**して署名する（自前再実装ではない）。

| 場所 | 内容 |
|---|---|
| `kotoba/crates/kotoba-wasm/src/lib.rs` | `WriteCrypto::mint_cacao` + wasm export `KotobaNode.mintCacao(aud, graphScope, capability, nonce, issuedAt, expiry)` → base64 `cacao_b64` |
| `kotoba/crates/kotoba-auth/src/lib.rs` | `commit_sig`（`kotoba_datomic::distributed` 依存）を `#[cfg(not(wasm32))]` で除外 → kotoba-auth が wasm-clean に（最小修正） |
| `kotoba/crates/kotoba-wasm/web/office.test.mjs` | 実 WASM E2E（office wire 書込/読戻し + `assertEncrypted`/`decrypt` + `mintCacao`） |
| `…/cljs/src/kotoba/cacao.cljc` | `mint-cacao!`（node.mintCacao 呼出, 第一選択）+ 注入式 `sign-cacao->b64`（fallback） |

**検証済（実機・実コンパイル）**：
- v_edn コーデックが kotoba `parse_edn_scalar` と一致、doc/org/grant の wire round-trip（bb 7 tests/21 assert）。
- `siwe_message` の byte 完全一致、CACAO resources マッピング（`datom:transact`/`datom:read`）。
- **`cargo test -p kotoba-wasm` 17 passed** — 鋳造 CACAO が server と同じ `DelegationChain::verify` を
  **バイト一致で通過**（実 `did:key:z6Mk`／wrong cap・wrong graph は拒否）。
- **`wasm-pack build --target nodejs` 成功**（kotoba-auth が wasm32 でコンパイル）。
- **`node office.test.mjs` ALL OK（12項目）** — 実 WASM 越しに office 書込/読戻し・暗号化・`mintCacao`。

### private グラフへの CACAO 付き同期経路（実装・実機検証済）

アカウントが**自分の DID 所有の private グラフ**へ、鋳造した `cacao_b64` を付けて書き込む経路。
ポイントは「グラフCIDが DID から決定論的に導出される」こと（`NamedGraph::private_for`）→ 初回書込で
**サーバが自動で Private{owner=account} 登録**（他人のグラフは奪えない）。

| 場所 | 内容 |
|---|---|
| `kotoba/crates/kotoba-server/src/xrpc.rs` `datomic_transact` | **アカウント所有 private グラフの自動登録**：未登録かつ `graph_cid == private_for(issuer).cid` のとき Private{owner=issuer} 登録（CID が DID に束縛=安全） |
| `kotoba/crates/kotoba-wasm/src/lib.rs` | `accountDid`(z6Mk) + `privateGraphId`(自分のグラフCID) + `mintCacao` を複数 cap 対応（write=`datom:transact`+`tx:create`） |
| `…/cljs/src/kotoba/office.cljc` | `doc->tx-edn`（`[:db/add e a v]` 形式）+ cljs `sync-doc!`（mint→POST `datomic.transact`） |
| `…/cljs/src/kotoba/cacao.cljc` | `mint-cacao!`(caps ベクタ) + `write-caps`/`read-caps` |

**検証済（実コンパイル・実機）**：
- **`cargo test -p kotoba-server --lib datomic_transact_` 7 passed** — 新規 `datomic_transact_auto_registers_account_owned_private_graph`：
  アカウントが**自分の DID 導出グラフへ書込→自動登録→owner 確認**、かつ**自分の DID 由来でない CID は拒否**。既存も回帰なし。
- `cargo test -p kotoba-wasm --lib` **17 passed**（mint 複数cap・accountDid・privateGraphId 含む）。
- `node office.test.mjs` **ALL OK（17項目）** — accountDid(z6Mk)/privateGraphId 決定性、write(2cap)/read(1cap) CACAO 鋳造。
- bb 純ロジック **8 tests / 27 assert**（`tx_edn` が `[:db/add …]` 形式）。

**書込の契約（確定）**：`POST /xrpc/com.etzhayyim.apps.kotoba.datomic.transact`
`{graph: <privateGraphId>, tx_edn: "[[:db/add …]]", cacao_b64}`、CACAO は `aud=operator DID`・
scope=グラフCID・caps=`[datom:transact, tx:create]`・iss=account DID・fresh nonce。

### read-back（別端末で CACAO 付きで読み戻す, 実装・実機検証済）

同期したデータを read CACAO で読み戻す経路。**read scope は write と同じグラフCID**
（`require_datomic_read` は `graph.to_multibase()`。`datomic.q` テストの `private/{owner}` とは別系統）。

| 場所 | 内容 |
|---|---|
| `…/cljs/src/kotoba/office.cljc` | `pull-doc`（read CACAO 鋳造→`datomic.datoms` POST→復元）+ **`:node/lid`** スキーマ |
| 同 `rows->doc` | **論理id(`:node/lid`)で復元** — サーバは entity を CID 化するので、entity ではなく lid で親子を解決 |
| `kotoba/crates/kotoba-server/src/xrpc.rs` | テスト `datomic_datoms_reads_back_account_private_graph_with_cacao` |

**なぜ `:node/lid` が要るか**：サーバは書込時に entity（"doc1"）を **content-addressed CID 化**する
（`datomic_datom_resp` は `e.to_multibase()`）。論理idを値として持たないと、`:block/parent`（親の論理id）が
読戻し時の entity(CID) と一致せずツリーが壊れる。`:node/lid` を値で持ち、復元を lid 空間で行うことで解決。

**検証済（実機）**：
- `cargo test -p kotoba-server --lib datomic_` **30 passed** — 新規 `datomic_datoms_reads_back_…`：
  自分の private グラフへ書込→**read CACAO で読戻し→書いた datom が返る**、no-CACAO は 401。既存も回帰なし。
- bb **9 tests / 28 assert** — 新規 `server-read-back-reconstructs-via-lid`：entity を CID 化した行から
  `rows->doc` が元のネスト文書を**完全復元**。
- `node office.test.mjs` **ALL OK（17項目）** — read CACAO は graph CID scope で鋳造。

**読戻し契約**：`POST /xrpc/com.etzhayyim.apps.kotoba.datomic.datoms`
`{graph, index:"eavt", components_edn:[], cacao_b64}`、CACAO は `aud=operator`・scope=グラフCID・`datom:read`・iss=owner。
レスポンス datom は `{e(CID), a, v_edn(EDN形), t, added}` → `parse-edn-scalar`+`decode-value` で型復元。

### 実サーバ HTTP E2E + aud 自動発見 + 本文暗号化（実装・実機検証済）

実際に `kotoba serve` を起動し、ブラウザWASM(pkg-node)が**本物のHTTP越し**に主権往復を完遂。

| 場所 | 内容 |
|---|---|
| `kotoba/crates/kotoba-wasm/src/lib.rs` | `encrypt(plaintext)→signal:v1:` を wasm export（本文を一度だけ暗号化し local/sync で同一 envelope）|
| `…/cljs/src/kotoba/office.cljc` | `encrypt-doc`（本文を envelope 化）、`discover-operator-did`（公開 `key.custodianInfo` で aud 取得）、`sync-doc!`/`pull-doc` が aud 自動解決 + `load-doc`/`pull-doc` が復号 |
| `kotoba/crates/kotoba-wasm/web/office_http_e2e.mjs` | **実サーバ相手の HTTP E2E**（discover→encrypt→sync→read→decrypt）|

**aud 発見**：`GET /xrpc/com.etzhayyim.apps.kotoba.key.custodianInfo` が公開で `{did: operator_did}` を返す
（サーバ変更不要）。`node_status` は operator 認証必須なので使わない。

**本文暗号化（主権）**：`:block/text` は `node.encrypt` で **クライアント側で signal:v1: 暗号化**してから
local 保存・server 同期。サーバ/IndexedDB は暗号文しか持たない。読戻しは `node.decrypt`。構造
（kind/order/parent/title）は平文のまま検索可能（暗号フィールドは非検索のトレードオフ）。

**検証済（実機）**：
- **`KOTOBA_IPFS=off … kotoba serve` 起動 → `node office_http_e2e.mjs` ALL OK（12項目）**：
  aud 発見 / クライアント暗号化 / **自分の private グラフへ transact CACAO 同期（自動登録）** /
  **非owner の書込は 401** / read CACAO で読戻し / **サーバ上に平文は存在しない** /
  クライアントで復号して原文復元 / no-CACAO read は 401。
- bb **10 tests / 33 assert**（`map-doc-text` の暗号/復号対称性 含む）。
- `node office.test.mjs` **ALL OK（17項目）**（再ビルド pkg-node）。

**主権フロー（確定）**：
```
m  = encrypt-doc(node, model)         ; 本文を signal:v1: 化（一度だけ）
store-doc!(node, m)                   ; ローカル保存（暗号文）
sync-doc!(node, m, {:remote …})       ; 自分の private グラフへ CACAO 同期（aud 自動）
;; 別端末:
pull-doc(node, {:remote …}) → model   ; CACAO read → :node/lid 復元 → 復号
```

### CLJS → ESM ビルド（コンパイル・ロード検証済）

`kotoba.office` / `kotoba.cacao` の `:cljs` 分岐を**初めて実コンパイル**し、ブラウザが読む ESM を生成。

```bash
cd kotoba/crates/kotoba-wasm/web/cljs
npm install && npx shadow-cljs release web   # → ../cljs-out/kotoba-node.js
# [:web] Build completed. (52 files, 9 compiled, 0 warnings, 26.8s)
```

**検証済**：
- **0 warnings でコンパイル** — `#?(:cljs …)` の interop（`js/JSON`/`clj->js`/`.mintCacao`/`.encrypt`/`fetch`）が全て通る。
- node で `import` 成功、`encryptDocument`/`storeDocument`/`syncDocument`/`pullDocument`/
  `discoverOperatorDid`/`grantToCacaoB64`/`mintCacaoViaNode` が関数として export される。
- これらは office_http_e2e.mjs が実サーバで実証済みの mint/encrypt/POST シーケンスの薄いラッパ。

> 消費形態：ブラウザ UI は **CLJS アプリ**から office 関数を直接呼ぶ（clojure マップ）。`:exports` は
> JS/Service Worker 連携用。JS から直接呼ぶ場合は opts/model を `js->clj` 変換するラッパが要る（UI 実装時）。

### depth-2 delegation（チーム共有, subject≠owner）（実装・実機検証済）

org が member に capability を委任し、**member が org のグラフへ書ける**（org-first の核心）。

**エンコード方針（確定）**：`cacao_b64` を再利用。depth-1=単一 Cacao CBOR(**map**)、
depth-2=`[root, leaf]` の CBOR **array**。構造で判別でき後方互換。
root=owner→member、leaf=member→server。サーバは**書込を root 権威（owner）に束縛**するので owner-binding を通る。

| 場所 | 内容 |
|---|---|
| `kotoba/crates/kotoba-auth/src/delegation.rs` | `from_cbor_chain`（CBOR配列を解析）+ `verify_with_aud*` を **leaf(chain.last)** の aud で検証（depth-2: root.aud=member, leaf.aud=server）|
| `kotoba/crates/kotoba-server/src/xrpc.rs` | `decode_cacao_chain`（single-or-chain）+ 検証は **root 権威**を返し owner-binding/auto-register に使用。leaf nonce で replay |
| `kotoba/crates/kotoba-wasm/src/lib.rs` | `mint_delegated`（member が `[root,leaf]` を組む）+ wasm export `mintDelegated`。root grant は `mintCacao(aud=member_did,…)` |
| `…/cljs/src/kotoba/{cacao,office}.cljc` | `mint-delegated!` / `sync-doc-delegated!` |

**検証済（実機・全層）**：
- `cargo test -p kotoba-wasm --lib mint_` — `mint_delegated_depth2_binds_to_root_owner_and_attenuates`：
  チェーン検証の**権威=org owner**、**減衰違反（member が root 以上の cap を要求）は拒否**。
- `cargo test -p kotoba-server --lib datomic_transact_` **8 passed** — `datomic_transact_accepts_depth2_team_delegation`：
  member が depth-2 で org グラフへ書込→成功・**owner=org に束縛/auto-register**、**非委任 member は 401**。
- `cargo test -p kotoba-auth --lib` **286 passed**（aud を leaf 基準に変更しても回帰なし）。
- **`node office_http_e2e.mjs`（実サーバ）ALL OK（14項目）** — depth-1 主権往復 + **depth-2 team write 成功 / 非委任 member 401**。
- CLJS ESM **0 warnings** で再コンパイル（`mintDelegated`/`syncDocumentDelegated` export）。

**チーム共有フロー**：
```
;; owner（org ノード）:
(def root (.mintCacao owner-node member-did org-graph #js["datom:transact" "tx:create"] nonce iat exp))
;; member ノード:
(sync-doc-delegated! member-node m {:remote … :org-graph org-graph :root-grant root})
```

### 実ブラウザ E2E（playwright-clj）+ passkey 仮想認証器（実機検証済）

「実ブラウザが要る」ギャップを、新規ツール **`com-junkawasaki/playwright-clj`**（Playwright for Java の
Clojure ラッパ）で閉じた。実 Chromium を Clojure から駆動し、kotoba office WASM を**本物の secure context**で実行。

| 場所 | 内容 |
|---|---|
| `com-junkawasaki/playwright-clj/` | 再利用可能な Playwright ラッパ（launch/eval-js/console/screenshot/**CDP**/**WebAuthn 仮想認証器**）|
| `kotoba/crates/kotoba-wasm/web/pkg/` | `wasm-pack --target web` のブラウザ ESM |
| `…/web/office_harness.html` | pkg を読み込み `KotobaNode` を公開するハーネス |
| `…/web/browser-e2e/office_browser_test.clj` | playwright-clj で office フローを実ブラウザ実行（`:local/root` で playwright-clj 依存）|

**検証済（実 Chromium）**：
- `clojure -M:test`（playwright-clj smoke）**ALL OK（9）** — eval/DOM/console/WebAssembly/getRandomValues。
- **`office_browser_test` ALL OK（14）** — secure context / `crypto.subtle` / Service Worker API /
  accountDid(z6Mk) / privateGraphId / 暗号化(signal:v1:、平文リークなし)/ 復号往復 / mintCacao /
  **mintDelegated(depth-2)** / **passkey 仮想認証器で create + get 成功** / console 捕捉。
- 起動: `python3 -m http.server`(localhost=secure) → `KOTOBA_WEB_URL=… clojure -M:run`。

> これで主権オフィスのブラウザ層（WASM の ID/暗号/CACAO/委任）が**実ブラウザで動作確認済み**。
> passkey は仮想認証器で ceremony をテストできる基盤が整い、kotoba の passkey 統合（doc b の PRF）を
> この上で E2E 化できる。

### passkey 統合 + フルスタック実ブラウザ E2E（実装・実機検証済）

doc b の passkey 統合本体（WebAuthn **PRF** で Ed25519 seed をラップ）を実装し、office cljs 層を
**実ブラウザ(:advanced) → 実 kotoba サーバ(HTTP)** でフル往復検証した。

| 場所 | 内容 |
|---|---|
| `…/cljs/src/kotoba/passkey.cljs` | `enroll!`/`unlock!`/`enrolled?` — passkey PRF→AES鍵で seed を wrap、localStorage に暗号文のみ。別端末で同一アカウント復元 |
| `…/cljs/src/kotoba/office.cljc` | `office-roundtrip!`（JSフレンドリな全往復）+ sync/pull が nonce/issued-at 自動生成・expiry を undefined 化 |
| `…/web/office_harness.html` / `web/pkg` | ブラウザ ESM ハーネス（`wasm-pack --target web`）+ cljs-out 読込 |
| `…/web/browser-e2e/office_browser_test.clj` | 実ブラウザ: 主権ID/暗号/CACAO/depth-2/**passkey PRF unlock** |
| `…/web/browser-e2e/office_fullstack_test.clj` | 実ブラウザ→office cljs→**実サーバ HTTP** の全往復 |

**実ブラウザ :advanced 実行でしか出ない2バグを発見・修正**：
1. **externs munge**（`b.qc is not a function`）→ `^js` + `goog.object/get`（passkey の native API）。
2. **nil→JS null** を wasm-bindgen `Option<String>` に渡して落ちる + sync/pull が nonce/issued-at 未指定 →
   `opt-str`(undefined) + nonce/issued-at 自動生成。

**検証済（実 Chromium、playwright-clj 駆動）**：
- `office_browser_test`（:run）**ALL OK（14）** — …/**passkey PRF unlock が同一アカウント復元**/ランダムと不一致。
- `office_fullstack_test`（:fullstack）**ALL OK（6）** — encrypt-doc→store-doc!→sync-doc!→pull-doc が
  実サーバ越しに往復、暗号本文の復号 + `:node/lid` 完全再構築（match=true）。
  ※ ブラウザは harness:PORT→api:PORT のクロスオリジンのため `--disable-web-security`（テスト専用。
    本番は app+API 同一オリジン配信）。

> これで **bb → cargo native → pkg-node → 実サーバHTTP → 実ブラウザ(:advanced) → フルスタック** の
> 全層で、データ主権オフィス（暗号/自己所有グラフ/CACAO/depth-2委任/passkey復元）が検証済み。

### 本番 same-origin 配信 + Hiccup エディタ UI + オフライン同期キュー（実装・実機検証済）

| 場所 | 内容 |
|---|---|
| `kotoba/crates/kotoba-server/src/lib.rs` | `KOTOBA_STATIC_DIR` 設定時に `ServeDir` で **app を /xrpc と same-origin 配信**（CORS 不要）|
| `…/cljs/src/kotoba/hiccup.cljs` | 依存ゼロの極小 **hiccup→DOM** レンダラ（React/Reagent なし）|
| `…/cljs/src/kotoba/editor.cljs` | UI を **Hiccup データ**で記述。passkey unlock→pull→編集→保存(encrypt→store→enqueue→flush) |
| `…/cljs/src/kotoba/syncq.cljs` | **オフライン同期キュー**（localStorage/EDN、`online` で自動 flush、失敗は保持）|
| `…/web/editor.html` | マウント点だけのシェル（UI 本体は Hiccup）|
| `…/web/browser-e2e/office_editor_test.clj` | 本番形 E2E（same-origin・passkey・offline）|

**検証済（実 Chromium、playwright-clj、`--disable-web-security` 無し）**：
`office_editor_test`（:editor）**ALL OK（8）** — same-origin 起動 / **passkey enroll で主権アカウント** /
保存 same-origin 同期 / **reload→passkey unlock→pull で復元（本文はクライアント復号）** /
**オフライン編集が queue（pending 1）→ 再接続で自動 flush（pending 0）→ 再 reload でサーバ反映確認**。
1サーバ（`kotoba serve` + `KOTOBA_STATIC_DIR`）が UI も `/xrpc` も配信。

### Service Worker バックグラウンド同期（実装・実機検証済）

同期キューを **IndexedDB + Service Worker** に移し、SW がキューを drain するようにした
（SW は WebAuthn 不可なので**署名はページ側で事前**に行い、SW は**署名済みリクエストを POST するだけ**）。

| 場所 | 内容 |
|---|---|
| `…/cljs/src/kotoba/syncq.cljs` | IndexedDB の署名済みリクエストキュー（SW と共有）+ `register-bg-sync!` + `nudge-sw!` |
| `…/cljs/src/kotoba/office.cljc` | `prepare-sync-request`（署名済み {url body} を生成）+ operator-DID キャッシュ（オフラインでも mint 可）+ `doc->tx-edn` に **`:db.fn/retractEntity` 前置**（再編集で値が累積せず置換）|
| `…/web/office-sw.js` | SW: `sync`(Background Sync, タブ閉でも)/`message`(nudge)/`activate` で IDB キューを drain、結果を clients へ postMessage |
| `…/cljs/src/kotoba/editor.cljs` | SW 登録 + drain 結果で `#sync` 更新。保存=encrypt→store-doc!→prepare→enqueue→register-bg-sync!+nudge |

**検証済（実 Chromium、playwright-clj、same-origin、`--disable-web-security` 無し）**：
- `office_editor_test`（:editor）**ALL OK（8）**：same-origin / passkey enroll / 保存→**SW が drain して同期** /
  reload→unlock→pull 復元 / **オフライン編集 queue(pending 1)→再接続で SW 自動 flush(pending 0)→再 reload でサーバ反映**。
- `office_fullstack_test`（:fullstack）**ALL OK（6）** 回帰なし（retract 前置・nonce 自動・prepare 経由でも往復一致）。

**playwright-clj 改善（実バグ起点）**：headless で `waitForFunction` の rAF ポーリングが停止し SW メッセージ駆動の
DOM 更新を検知できなかった → `wait-for-fn` に **`:polling` (timer)** を追加（+ `set-offline`）。public repo に反映。

**正直な範囲**：drain はタブ表示中は `online`/nudge、**タブを閉じても Background Sync の `sync` イベント**で実行
（`register-bg-sync!` 済み）。ただし `sync` の発火タイミングはブラウザのスケジューラ依存（E2E では online-nudge 経路を決定的に検証、closed-tab 発火は登録までを保証）。

**残（次の increment）**：
- 多端末シード移行（passkey を複数デバイスに登録／リカバリ、Shamir t-of-N）。
- 動的ブロック編集（複数ブロックの追加/並べ替え）+ `load-doc`/`pull-doc` の doc サブツリー限定クエリ最適化。
- `periodicsync` での定期 pull（他端末の更新取り込み）。
