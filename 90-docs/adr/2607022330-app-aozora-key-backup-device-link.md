# ADR-2607022330: app-aozora — 鍵の移行/復元 3 経路（passkey-PRF バックアップ / QR デバイスリンク / opt-in リカバリーフレーズ）

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

ADR-2607021400 で aozora.app の self-sovereign 登録（Ed25519 actor key は端末
localStorage のみ、did:key が identity、CACAO 自己発行で createAccount /
createSession）が UI まで通ったが、**端末喪失 = アカウント喪失**が残課題だった
（同 ADR Consequences: 鍵エクスポート/インポート UI と passkey PRF ラップが
follow-up）。オーナー指示（2026-07-02）: 「パスキー PRF ラップ + 暗号文
バックアップ、QR デバイスリンクを含めて。リカバリーフレーズ export は希望する
ユーザーのみ」。設計比較（Signal = 鍵を E2E チャネルで運ぶ / WhatsApp = 鍵を
運ばず端末別鍵 + 署名済みデバイスリスト / iCloud キーチェーンのパスキー同期 =
E2EE プラットフォーム同期）を踏まえ、web だけで完結する 3 経路を実装した。

## Decision 1 — passkey-PRF ラップ + 暗号文バックアップ（既定で提案する経路）

- **暗号**: WebAuthn PRF 拡張の出力（credential 毎 32B、パスキー同期先の端末でも
  同一）→ HKDF-SHA256（salt=ランダム 32B、info `"aozora-key-backup-v1"`、
  non-extractable AES-256-GCM 鍵）→ seed(32B) を封緘。GCM タグにより誤鍵は
  **fail closed**（`yoro-ui.interop.prf-backup` / `key-crypto`）。
- **blob**（JSON、PDS 保存）: `{v 1, credId, prfSalt, hkdfSalt, iv, ct}`（全て
  base64url）。**サーバは平文を一度も見ない**。秘匿性は 256-bit PRF 出力に依存
  するため blob は public-read 扱い（復元はセッション成立**前**に必要なので
  認証必須にはできない。put は認証必須）。
- **PDS**: `app.aozora.key.putBackup`（POST、session did のみ）/
  `app.aozora.key.getBackup`（GET、did|handle）。kotobase datom entity
  `keybackup/<did>`（`:aozora.keyBackup/*`、再 assert で更新）。
- **フロー**: 有効化（/settings）= 既存ログインパスキーで PRF 評価を試行 →
  PRF 非対応なら**バックアップ専用パスキーを新規登録**（`extensions {prf {}}`、
  residentKey required）→ 評価 → 封緘 → putBackup。復元（auth modal「別の端末
  から復元 → パスキー」）= ハンドル入力 → getBackup → blob の credId で PRF 評価
  （UV required）→ 復号 → `actor-key/import-hex!` → CACAO createSession。
- **トラスト境界の変更（意図的）**: 復元可否がプラットフォームアカウント
  （iCloud / Google のパスキー同期、E2EE）に乗る。純端末内キーからの一段の譲歩
  であり、その代替として経路 3（フレーズ）を併設。

## Decision 2 — QR デバイスリンク（クロスエコシステム / 同期なし環境）

- **プロトコル**（`yoro-ui.interop.device-link`）: 新端末が X25519 一時鍵 +
  128-bit link id を生成し、`https://aozora.app/link-device#<b64url(ver‖lid‖npk)>`
  を QR 表示（**payload は #fragment のみ = どのサーバにも送信されない**）。
  旧端末はネイティブカメラで開く → /link-device ページが確認 + （登録済みなら）
  パスキー UV → 自分の一時鍵で ECDH → HKDF（salt=lid、info
  `"aozora-device-link-v1"`）→ AES-256-GCM で `{seedHex did handle repoKeyHex?}`
  を封緘 → `app.aozora.link.deposit`（認証: session Bearer **または** body の
  `cacao_b64` — カメラ起動の新規タブは sessionStorage が空のため、手渡す当の
  actor key の CACAO で DID を証明する）。新端末は `app.aozora.link.take` を
  2.5s ポーリング → 復号 → **SAS（6 桁、sha256("aozora-sas-v1"‖lid‖npk‖opk)）を
  両端末に表示 → 目視一致でインストール** → createSession。
- **リレーは盲目**: npk が out-of-band（QR）なので PDS は MITM 不能。deposit は
  first-write-wins + one-time take + TTL 10 分（`devicelink/<lid>` entity、
  `link-live?`）。QR を覗き見た攻撃者が先に deposit しても SAS 不一致で露見
  （UI は「一致しない場合は中止」を明示）。
- **デスクトップ間**（カメラ無し）: 同じ payload を 66 文字リンクコードとして
  コピーし、旧端末の /settings「別の端末を追加」に貼り付け（同一実装）。

## Decision 3 — リカバリーフレーズ（**opt-in のみ**）

- seed(32B) ⇄ BIP39 英語 24 語（@scure/bip39）。**export は /settings で希望者
  だけ**: 警告文 + パスキー UV ゲート後に一度だけ表示、どこにも保存しない。
  restore は auth modal「フレーズ」タブ。チェックサム不一致は nil（fail closed）。
- 位置づけ: 端末全損 + プラットフォームアカウント喪失 + エコシステム跨ぎに耐える
  最終手段。既定 UI では出さない（オーナー指示どおり希望ユーザーのみ）。

## 共通の安全レール

- **復元ガード**: この端末に**別アカウントの鍵**が既にある場合は上書きせず中止
  （その鍵が他に存在しない可能性があるため。同一 did の再復元は許可）。
- seed を含む payload/blob の鍵材料は Uint8Array を module-local に保持し
  app-db に入れない。SAS/確認コードのみ表示用に db へ。
- 依存追加: `@scure/bip39`（audited）、`qrcode-generator`（純 JS QR 描画）。
  X25519/Ed25519 は既存 @noble/curves v1、AES-GCM/HKDF/SHA-256 は WebCrypto。

## Verification

- appview node tests 30/83 green（PRF wrap/unwrap roundtrip + 誤鍵 reject、
  link code/seal/open roundtrip + SAS 両端一致 + 改ざん reject、フレーズ
  roundtrip + 24×abandon checksum reject、/settings・/link-device ルート）。
- PDS suite 233/1019 green（keylink: blob/param validation、datom scan、
  TTL/one-time、未認証 deposit/putBackup の AuthRequired）。
- release build: SPA `:app` 253 files 0 warnings / PDS `:pds` 0 warnings。
- 本番 smoke: getBackup→BackupNotFound / take→LinkNotFound / 未認証 deposit→
  AuthRequired（デプロイ後に確認）。

## Consequences

- (+) 端末喪失 ≠ アカウント喪失（バックアップ有効化ユーザー）。同じ Apple/Google
  アカウントの新端末は「ハンドル + パスキー」だけで復元。Apple↔Android は QR
  リンク、全損はフレーズ（opt-in）。
- (+) no-server-key 哲学を維持（サーバに渡るのは常に暗号文のみ、リレーは盲目）。
- (−) PRF 経路の復元可否はプラットフォームのパスキー同期仕様に依存（PRF 対応:
  Safari 18+/Chrome 116+ 目安）。旧パスキーが PRF 非対応の場合は専用バックアップ
  パスキーが増える（credId は blob が指すので復元時の迷いはない）。
- (−) getBackup の public-read は「暗号文は公開情報」という設計判断（256-bit
  秘密鍵に依拠）。ハンドル列挙で backup 有無は観測可能。
- (−) deposit の CACAO 認証は既知 actor なら誰でも drop できる = lid capability
  と SAS が実防御（設計どおりだが SAS 目視をユーザーが省くリスクは残る）。
- follow-up: WhatsApp 型「端末別鍵 + DelegationChain」への移行（seed を運ばない
  多端末化、端末失効 UI）、バックアップ削除/ローテート UI、日本語 wordlist。

## Addendum（2026-07-02）— WebAuthn E2E で出荷ビルド固有の :advanced バグを検出・修正（app-aozora `b887802`）

オーナー指摘「WebAuthn は kotoba-lang/playwright で検証できるのでは?」を受け、CDP の
**WebAuthn virtual authenticator（ctap2 + `hasPrf`）**で PRF セレモニーを headless 実行。
これにより **node 単体テスト（`:none`）と `:simple` ビルドでは緑なのに、出荷の
`:advanced` ビルドでだけ PRF バックアップが nil を返す**実バグを検出した。

- **根因**: `getClientExtensionResults().prf` は新しい拡張出力で Closure の externs に
  無いため、`:advanced` が `(some-> ext .-prf .-results .-first)` のダッシュ経由読みを
  rename して nil 化していた（`rawId`/`type` 等は externs にあり無傷 → 発見が遅れた）。
- **修正**: `goog.object/getValueByKeys ext "prf" "results" "first"`（string-key は
  `:advanced` でも不変）。加えて signup のパスキー生成で `{:prf {}}` を要求し、ログイン
  パスキー自体でバックアップを封緘（常用経路は 2 枚目のパスキー不要）。`enable-backup!`
  は create 時の advisory な `enabled` フラグでなく**実 PRF 評価の結果**で判定、バックアップ
  用パスキーは `user.id = <did>#backup` で resident なログインパスキーを上書きしない。
- **検証**: `:advanced` の keytest ブリッジ（`yoro-ui.dev.keytest`、dev 専用・非出荷）で
  実 cljs の eval-prf/enable-backup/restore-from-blob を実 authenticator に対し駆動し、
  ログイン経路・フォールバック経路とも wrap→restore が seed 一致（`match:true`）。本番 UI
  E2E では リカバリーフレーズ経路の登録→表示→別端末復元が緑、PRF バックアップの有効化も緑。
  ハーネスは `60-apps/appview/cljs/e2e/`（`prf_roundtrip.clj` / `key_flows.clj`）に常設。
- **残課題（本機能外）**: getBackup の read と device-link の deposit（read+write）は
  kotobase `yoro-social` の full `:eavt` スキャン肥大による間欠 500（"Invalid array buffer
  length"）に律速され、E2E がこの窓で不安定。これは getAccount 等既存 API も同様に落ちる
  **アプリ全体の既存インフラ課題**で、当機能のバグではない。datom スキャンの
  ページング/インデックス化が別 follow-up。

## Addendum 2（2026-07-02）— kotobase 500 の根因確定 + アプリ層緩和 + kotobase-client の canonical 化

**kotobase 500「Invalid array buffer length」の根因（調査確定）**: kotobase.aozora.app は
net-kotobase/kotobase-cf-wasm の **WASM worker で、datoms が index/components_edn/limit を
無視し毎回グラフ全体を rehydrate**（全 R2 ブロック→JS バッファ→WASM メモリ→巨大 JSON
export→JSON.parse→再 stringify、ピーク約10-30×DBサイズ）。`new Uint8Array(await
o.arrayBuffer())`（datomic-engine.mjs:35）が isolate メモリ上限（128MB）で失敗する RangeError。
WASM 線形メモリは伸びるだけ・warm isolate 再利用・`*/5` cron の全 re-assert が圧を複合。
**サイズ極限では決定的、現状は isolate warmth/GC/並行/cron 依存で非決定的**（実測 10-90% で
変動）。worker のソースは削除済み（net-kotobase `b4ced2c`、Rust エンジン kotoba-lang/kotobase
`60489617`）。真の修正は worker の server 側フィルタ/limit/streaming/圧縮で範囲外。

**アプリ層緩和（kotobase.client、app-aozora `bd04697` / kotobase-client `5ee5a70`）**: transient
5xx を冪等リードで自動リトライ（transact は idempotent keyed re-assert のみ `:retry?` opt-in、
keylink put-backup/deposit/take が使用）。SPA は key エンドポイントの read timeout を 20-30s に
拡張。**実測: リトライは spike（40-90%）を ~25% 床まで均すが、床は破れない** — 失敗は
同期リトライ窓内で相関し（5リトライを約10s に広げても同率で失敗）、backoff は latency を
悪化させるだけ。よって light（3回・250ms→1.2s・jitter）に留めた。**durable fix は server 側**
（worker の components_edn/limit 実装 or streaming/compaction）。in-tree レバーとして `*/5`
cron（RELAY_CRON_ENABLED=1、毎回全 re-assert で圧を増やす）頻度削減や、PDS 側 KV キャッシュ層
（getBackup/getAccount を KV から配信し kotobase をフォールバックに）が候補（未着手 follow-up）。

**kotobase-client の canonical 化（オーナー指示 2026-07-02）**: `kotobase.client/cacao/cid`
（約380行）が app-aozora / app-aozora-boundary / kami-genko に **byte-identical で3重コピー**
（fix が1コピーにしか入らない実害＝上記リトライも当初 app-aozora のみ）。canonical repo
**kotoba-lang/kotobase-client**（public、west 登録、上記リトライ込み）を新設し、app-aozora の
PDS/AppView・SPA 両ビルドを shadow-cljs source-path でこれを消費するよう切替、埋め込みコピーを
削除。PDS 237/1030・SPA 30/83 green・両ビルド0 warnings で本番デプロイ済み。
**残 follow-up: app-aozora-boundary / kami-genko の埋め込みコピーも同様に de-fork**（本 live
サービスには非影響のため保留）。

## Addendum 3（2026-07-02）— kotobase 500 の根治: filter-honoring CLJC worker + route 切替

オーナー指摘「index/components_edn/limit を無視するのが根本問題では?」を受け、リトライ緩和
（対症）でなく**分散層での根治**を実装。調査で判明した経路: quad-store は既に AVET 相当の
`:pos` インデックス（`by-predicate`/`by-predicate-value`）を持つのにエンジンが使っていなかった。

- **kotobase-engine（kotoba-lang、pin `aa4e1de`）**: `datoms` が `{:index :components :limit}` を
  honor（AVET point lookup へ接続）、`cold-datoms`（永続 snapshot から該当 index tree だけを
  prefix-seek、全 db を rehydrate しない — cold-query gap をクローズ）、`hydrate-db`（write 用に
  snapshot→hot db を ~1× 復元、wasm の 10-30× 増幅を回避）。JVM 8-10 tests green。
- **kotobase-cljc-worker（新設 kotoba-lang public、pin `03b6044`）**: `:esm`/workerd worker。
  handler（純 XRPC dispatch + tx_edn→quads）+ r2（block-miss トランポリンで非同期 R2↔同期
  エンジン）+ CACAO 検証（PDS と byte-exact）+ write（buffer→flush→head）。node 6/21 + mint tool。
- **切替（オーナー承認: 旧データ廃棄 + CLJC prolly/commit-dag フォーマット採用）**: Rust WASM の
  R2 データは非互換なので移行せず、`kotobase.aozora.app` の custom-domain route を新 worker へ移し
  yoro-social を fresh（`kotobase/cljc` prefix）で開始。旧 wasm worker はロールバック用に残置。

**workerd smoke test が node では出ない 3 実バグを検出・修正**: (1) 空 allowlist が CACAO 要件を
skip（no-CACAO transact が commit）→ issuer 必須化、(2) transact は `:db_name` を送る wire を
`:graph` 前提にしていた → `canonical-graph(issuer, db_name)` 導出、(3) handle の try/catch が
トランポリンの block-miss シグナルを飲み込み → re-throw。

**検証（live）**: kotobase.aozora.app = 新 worker、**getBackup 500 が 0/12（~0.8s、従来 ~6s +
25-90% 失敗）**、E2E signup→session→recovery-phrase-restore green（PDS→worker 書込読戻）。
リトライ緩和（addendum 2）は根治で不要になったが無害な保険として残置。

**残 follow-up**: (a) PDS の keyed read（getBackup/getAccount/resolveHandle）を full `:eavt` から
narrow components へ（fresh graph が育っても高速維持、worker は既に honor）、(b) prolly-tree
`scan-prefix` の key-range 刈り込み（keyed read を O(path) の数ブロックに）、(c) KOTOBASE_OPERATOR_DIDS
allowlist を operator DID に絞る（現状 staging で空=任意の有効 CACAO 許可）。

## Addendum 4 — 「narrow reads / fast at scale」の実装と、真因だった worker prefix バグ（2607030100）

オーナー指示「PDS を narrow components に切り替えてスケールしても速く」+「relay cron の膨張解消 +
トランポリン並列化」への対応。narrow 化を着地させた後、live read が依然 15-38s だった。深掘りの結果、
**遅さの真因は narrow 化や pruning ではなく、worker の R2 key prefix が空文字に潰れていたこと**だった。

- **真因: `KOTOBASE_B2_PREFIX` が `(.-KOTOBASE_B2_PREFIX env)` 直読で Closure `:advanced` に改名され
  undefined を読む** → `prefix` が `""` に fallback → 全 block/head が R2 バケット **root 直下**
  （`heads/…`, `blocks/…`）に散逸。設定した `kotobase/cljc*` の名前空間分離が **no-op** になり、
  relay-cron の firehose 膨張（~3k datoms）が root に堆積、keyed read がそれを丸ごと walk していた。
  **PRF extension の `.advanced` バグと同型**。修正: `goog.object/get` で読む（プロパティ名が保存され、
  `.-` 直読も含めて解決）。`KOTOBASE_OPERATOR_DIDS` も同型で、**空 allowlist では不可視だが本番 allowlist
  を設定すると黙って無視して任意署名者を許可する**セキュリティ欠陥になるため同時に修正。worker pin `96ed55b`。
- **診断を阻んだ2つの落とし穴**:
  - **`wrangler r2 object get/delete` はデフォルトでローカル miniflare を操作**（`--remote` 必須）。
    これに気づくまで、head 削除は "Delete complete" でも実 R2 に無反応、head 存在チェックは偽陰性。
    以後 `--remote` を付けて実 R2 を操作。
  - **`wrangler deploy` は版を作るだけで 100% traffic に promote しない**（gradual deployment）。
    `wrangler versions deploy <id>@100% --yes` で明示 promote が必要。`deployments list` は古い順で
    先頭が最古 → 現行版は `deployments status` で確認。これで cron-off PDS / prefix-fix worker を実効化。
- **relay cron 停止（#17）**: `RELAY_CRON_ENABLED=0`（app-aozora `1036f277`）。scheduled handler は 0 で no-op。
  external-feed ingest は operator db と別 db に分離すべき（再スコープは follow-up）。
- **yoro-social reset**: prefix 修正で worker が `kotobase/cljc-v2`（fresh）を見るようになり、旧 root 直下の
  膨張データは orphan 化（オーナー承認済みの廃棄）。残っていた cljc-v2 head も `--remote` で削除し 0 datoms に。
- **PDS narrow keyed reads（app-aozora `c0807f6`）**: getBackup/getAccount/resolveHandle/deposit/take が
  full `:eavt` scan をやめ最小 index prefix（`fetch-entity`/`fetch-index`）を渡す。後方互換。PDS 237/1030 green。
- **prolly-tree scan-prefix key-range 刈り込み（#16、`ef43a9d`）**: keyed read を O(path) ブロックに。
  ローカル inline 検証で pruning ロジックは正しい（child skip/descend が意図通り）。

**検証（live, 修正後）**: getBackup / getAccount / resolveHandle / 直 worker narrow・full すべて
**0.15–0.3s**（fresh yoro-social）、cron off で再膨張なし。真因修正前は 15-38s→500。

**残 follow-up**:
- **worker トランポリンの並列 block fetch（#18）**: 現状 block-miss は逐次 fetch。真のスケール（巨大な
  正規グラフ）では 1 read = O(blocks) 逐次 = 遅い。1 run で全 miss 収集→並列 fetch（O(depth) round）に。
- **prolly-tree pruning が engine 経由で無効化される依存問題**: kotobase-engine の deps.edn は prolly-tree を
  **kqe の git SHA 経由（transitive、ef43a9d 以前）**で解決するため、engine を deps 経由で使う consumer では
  pruning が効かない（worker は shadow-cljs の local src `../prolly-tree/src` を使うので影響なし）。
  quad-store/commit-dag/kqe の prolly-tree pin を ef43a9d に前進させて engine の公開 deps にも pruning を載せる。
- **relay ingest の別 db 分離**（operator db を汚さない external-feed 経路）。
- KOTOBASE_OPERATOR_DIDS allowlist を operator DID に絞る（現状 staging で空）。
