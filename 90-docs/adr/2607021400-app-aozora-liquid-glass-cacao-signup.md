# ADR-2607021400: app-aozora — liquid-glass 青空 UI と self-sovereign 新規登録（CACAO + passkey gate）

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

オーナー指示（2026-07-02）で app-aozora の UI/UX に
[kotoba-lang/liquid-glass-ui](https://github.com/kotoba-lang/liquid-glass-ui) を
適用し、トンマナを「日本の田舎の青空とみどり」（参照写真: 青空・白い雲・緑の山と
田園）に刷新。続いて「aozora.app で新規登録は WebAuthn と kotobase.net で
できるか?」の調査で、(a) 新規登録 UI が未実装（`credentials.create` 呼び出しなし・
`createAccount` 呼び出しなし）、(b) WebAuthn rpId が `"etzhayyim.com"` 固定で
aozora.app では SecurityError、(c) 検証先 `/xrpc/com.etzhayyim.authz.verifyCacao`
が assets-only Worker に存在せず偽 did 合成 fallback に落ちる、(d) 一方 PDS 側
`createAccount` は「valid CACAO required」で受け口が既に self-sovereign 設計、と
判明。オーナー指示で 3 点（rpId 動的化 / CACAO 新規登録 / verifyCacao 依存除去）を
実装した。

## Decision 1 — liquid-glass 青空トンマナ（app-aozora main `1a74c82`）

- **生成 CSS 方式**: `scripts/gen-liquid-glass-css.bb` が liquid-glass-ui の
  トークンを青空トーン（white-cloud glass 表面 / みどり accent `#58CC02` /
  deep-ink text `#17323b`）で上書きして `public/css/liquid-glass.css` を生成
  （tailwind.css と同じ「コミットされるビルド出力」規約）。specular.js enhancer
  も vendor（`data-lg-selector` 明示で mount 順に依存しない）。
- **`public/css/aozora.css`**: コンパイル済み Tailwind の gv2 パレットは全て
  `var(--color-gv2-*)` 参照のため、変数上書きだけでダーク→青空ライトへ全画面
  反転（Tailwind 再ビルド不要）。glass シェルのジオメトリ（ヘッダー/タブドック/
  コンテンツパネル）もここが持つ（材質は library、形は app）。
- **ambient background**: 空グラデ + 事前レンダリングした雲スプライト + 緑の
  山並み 2 層 + 田んぼ帯の 24fps canvas シーン（reduced-motion は静的グラデ）。
- **シェル**: ヘッダー = `liquid-glass__nav-bar`、タブバー = フローティング
  glass ドック（`liquid-glass__toolbar`）、コンテンツ列は単一 glass パネル
  （backdrop-filter 1 領域で可読性と描画コスト両立）。モーダル/コンポーザー =
  thick glass panel + `liquid-glass__scrim`。

## Decision 2 — self-sovereign 新規登録（app-aozora main `6db5378`）

- **`yoro-ui.interop.actor-key`**: 32-byte Ed25519 seed をこの端末の localStorage
  のみに保持（no-server-key、repo-signer の secp256k1 repo 鍵とは別の identity 鍵）。
  did:key (z6Mk…) がアカウント identity。
- **CACAO は kotobase.cacao を byte-exact 共有**: SPA の source path に
  `40-engine/cljs/kotobase-client/src` を追加し、PDS の
  `aozora.pds.auth/verify-cacao` と同一ソースで mint（client/server が乖離不能）。
  mint→PDS 式検証の roundtrip + 改ざん拒否を node-test に追加。
- **新規登録フロー**: ハンドル入力 → 鍵生成 → CACAO 自己発行 →
  `com.atproto.server.createAccount`（pds.aozora.app）→ session JWT →
  任意の WebAuthn パスキー登録（`credentials.create`、スキップ可）。
- **パスキーログインの再定義**: `credentials.get`（rpId = `location.hostname` に
  動的化）は端末ローカルの本人確認ゲート。セッション発行は保存済み鍵の CACAO を
  PDS の `createSession` が検証する。存在しない same-origin verifyCacao への
  POST と credential-id からの偽 did 合成 fallback を廃止。
- session に `:service` を保存し reload 時に XRPC endpoint を復元。

## Verification / Deploy

- tests 24 / assertions 61 green（shadow-cljs 0 warnings）。
- E2E: ローカル UI → 本番 pds.aozora.app に実登録
  （`@smoke-signup-0702.aozora.app` / `did:key:z6Mkr4HQ…`、`getAccount` で永続確認）。
- deploy: `app-aozora-spa`（assets-only Worker、aozora.app）
  UI 刷新 = Version `23989081-…`、signup = Version `2038a802-…`。
- 着地は CLAUDE.md 正経路: worktree → feature branch → サーバ側マージ →
  manifest pin API single-entry 前進（`e4ade5c` → `56623bc`）。

## Consequences

- (+) aozora.app で新規登録が動く（パスワードレス・招待コード不要・鍵は端末のみ）。
  kotobase.net 路線（actor が自分の鍵で CACAO を自己発行）と PDS 受け口が UI まで
  一気通貫になった。
- (+) liquid-glass-ui の実戦初適用（トークン上書き → 生成 CSS → 安定クラス名）。
  スキン差し替えは bb スクリプト再実行のみ。
- (−) 鍵が端末ローカルのみ = 端末喪失でアカウント喪失。`import-hex!` は interop に
  あるが鍵エクスポート/インポート UI と passkey PRF ラップが follow-up。
- (−) dev-login（bsky.social / atproto.etzhayyim.com への app-password
  createSession）は残置。パスキー追加は OS ネイティブダイアログのため自動テスト
  対象外（手動確認）。
- (−) PDS `createAccount` はハンドル重複チェックをしない（server 側 follow-up）。
