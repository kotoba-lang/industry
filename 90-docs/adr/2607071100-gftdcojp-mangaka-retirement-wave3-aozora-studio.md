# ADR-2607071100: gftdcojp — mangaka.gftd.ai 退役（wave3）+ manga editor の復活先 = aozora.app/studio

**Status**: accepted (implemented this session)
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki

## Context

- オーナー確認（2026-07-07）: mangaka.gftd.ai（manga editor）は**復活したい**が、
  復活先は再検討 —「aozora.app のなかがいいかな?」→ 提案（編集 UI と公開は
  aozora.app、生成は murakumo.cloud、mangaka.gftd.ai ホストは退役）を承認。
- 実測（2026-07-07）: `https://mangaka.gftd.ai/` は **HTTP 522**（Cloudflare が
  origin に接続できない）。原因の実態:
  - route 先だった Worker **`magatama-mng4k4x1` はアカウント上に存在しない**
    （API 10007）。`mangaka.gftd.ai/*` の wildcard route も消失済み。
  - zone の worker routes に残るのは **`mangaka.gftd.ai/_d1` →
    `ai-gftd-d1-writer`** のみ（pod 側 LangServer が D1 へ書くための内部経路。
    pod/k8s は ADR-2607062130 で退役方針、呼び出し元は死んでいる）。
  - proxied DNS record は残存しており、wildcard route が無いため他パスは
    dead origin に落ちて 522。
- repo の `ai-gftd-wasm-mangaka-mng4k4x1/wrangler.jsonc` は再デプロイ不能な依存を
  持つ: `PDS_SERVICE`/`PDS_RPC` service binding → `ai-gftd-pds-2603241700`
  （**wave2 で script ごと削除済み**、ADR-2607062200）、実処理は AgentGateway
  MCP + pod 側 LangServer（dispatcher 系、退役方針）。
- editor の実体は既に CF edge に無い: 現行 runtime は
  `ai-gftd-mangaka/clj/`（フリート上の CLJ graph runtime）で、
  render→publish は Tailscale フリート + wrangler CLI 直で完結
  （RUNBOOK、ADR-2606162100）。
- 関連の確定方針: atproto → aozora.app / 生成 → murakumo.cloud /
  `*.gftd.ai` ホストは増やさない（ADR-2607062200 wave2）。
  aozora.app には viewer（kotoba-lang/manga-viewer、ADR-2607070500）、
  work-actor profile（ADR-2607070400）、client-signed write
  （no-server-key、ADR-2606251700）、CACAO 鍵管理が既にある。

## Decision

### 1. mangaka.gftd.ai はホストとして退役し、旧 URL は新住所へ redirect

- 旧 editor Worker（magatama-mng4k4x1）は復元しない（削除済み PDS binding +
  退役済み dispatcher 依存で、そのままでは戻せない）。
- **極小 redirect Worker `mangaka-gftd-redirect`**（source:
  `ai-gftd-mangaka/redirect/`）を route `mangaka.gftd.ai/*` で配備し、
  **301 → `https://aozora.app/studio`** に転送する（yoro.gftd.ai →
  `yoro-gftd-redirect` と同じ前例）。522 ゾンビ状態の解消 + 旧 URL からの導線。
- `mangaka.gftd.ai/_d1` route（→ ai-gftd-d1-writer）は**今回は残置**:
  path-specific route は wildcard より優先されるため redirect と共存し、
  呼び出し元（pod）は死んでいて実害が無い。dispatcher 系の per-app 掃除
  （ADR-2607062130 の app-by-app 方針）で扱う。
- proxied DNS record の削除は不要になった（redirect が受ける）。なお現行
  credential（wrangler OAuth）には DNS 編集 scope が無いことを実測で確認。

### 2. editor の新しい住所は aozora.app の SPA 内 `/studio`

役割分割（オーナー承認済みの提案どおり）:

| 層 | 場所 |
|---|---|
| 編集 UI（storyboard/コマ/セリフ/メタデータ） | aozora.app SPA `/studio`（新 route） |
| 作品 identity・公開 | aozora PDS — work actor の鍵で record mint（鍵付き化 follow-up、ADR-2607070400） |
| 生成エンジン（ComfyUI/LLM） | フリートの CLJ runtime（`ai-gftd-mangaka/clj/`）→ 将来 murakumo.cloud edge |
| 公開 reader | 現状維持（manga.gftd.ai reader + aozora /manga viewer） |

**v1 スコープ（本セッションで実装）** — 完全 client-side、サーバ書込なし:

- `/studio`: 作品 dashboard（work-actor registry ベース。aozora-native な
  作品 = 編集可、外部作品 = reader へのリンクのみ）。
- `/studio/<slug>`: storyboard editor — 作品メタ（title/subtitle/arc/
  publishedAt）+ ページ title + パネル visual テキストの編集。パネル画像は
  サムネイル表示（読み取り専用 — 生成は取り込みのみ、murakumo 待ち）。
- draft は localStorage に自動保存（`aozora-studio-draft-<slug>`）。
- **プレビュー** = 編集中の tx を `manga-viewer` reader でその場描画
  （viewer と editor が同一モデルを共有するのが aozora 統合の利点）。
- **書き出し** = 編集済み tx EDN をダウンロード（canonical な
  `public/kotoba/<slug>-manga-tx.edn` 差替え用。commit/deploy は従来フロー）。

### v1 に意図的に入れないもの（follow-up）

- **work-actor 鍵での公開（mint）**: kotobase transact 障害
  （ADR-2607021700）解消 + 鍵付き actor 化（ADR-2607070400 follow-up）後、
  studio の「公開」ボタンとして実装。それまでは EDN 書き出し → git 経由。
- **画像生成の直結**: murakumo.cloud edge（`*.edge.murakumo.cloud` は
  NXDOMAIN、ADR-2607031540）が立ってから。生成はフリートの CLJ runtime で
  行い、studio は成果物を取り込む。
- 新規作品の作成 UI・ページ/パネルの追加削除・D1（外部 3 作品）の編集。

## Consequences

- (+) mangaka.gftd.ai の 522 が解消し、旧 URL は新 editor へ誘導される。
- (+) editor が viewer・profile・composer・鍵管理と同じ SPA に同居し、
  「編集 → プレビュー → 公開 → 共有投稿」の一本化への土台ができる。
  viewer と editor が manga-viewer の同一モデルを共有する。
- (+) 生成・公開の集約方針（murakumo / aozora）と整合。
- (−) v1 の「公開」は EDN 書き出し + git/deploy の手動フロー（サーバ書込は
  kotobase 復旧待ち）。→ **Addendum で解消**
- (−) 編集対象は aozora-native 作品（ghosthacker）のみ。外部 3 作品の編集は
  D1 書込経路の設計が必要（follow-up）。→ **Addendum でメタ編集まで解消**
- (−) `/studio` は認可ゲート無し（書込先が無い client-local 編集のため実害
  無しだが、公開 mint を実装する時点で actor 鍵所持 = 認可になる）。

## Addendum (2026-07-07 同日): follow-up ①②③ 実装

オーナー指示「1, 2, 3」で本 ADR の follow-up を同日実装した。

1. **公開（mint）— 実装済み**。前提だった kotobase transact 障害
   （ADR-2607021700「Invalid array buffer length」）を再検査: 使い捨て CACAO
   アカウントの createRecord が実 uri/cid を返し**治癒を確認**（probe アカウント
   2 件 + 検証投稿 1 件が graph に残存、使い捨て）。`yoro-ui.studio.publish` が
   作品ごとに Ed25519 seed を発行（localStorage custody + macOS Keychain
   `aozora-studio-actor-key-<slug>` に退避済み）、CACAO で
   `<work>.manga.aozora.app` の account を作成し、profile（rkey `self`）+
   作品 post（rkey `work-<slug>`）を mint — 決定的 rkey で再公開は同 record
   更新。**4 actor 全て公開実施済み**（did:key 発行済み）。profile dispatcher は
   resolveHandle → did:key の場合のみサーバ profile へ昇格（fail-open の
   did:web は registry 表示のまま）。appview の getProfile は DID キーなので
   handle を先に解決する（#48 と同型）。
2. **生成契約 — 半分実装、dispatch は fleet ops 待ち**。実測: `*.edge.murakumo.cloud`
   は依然 NXDOMAIN だが **api.murakumo.cloud は稼働**（/health ok、/nodes に
   実フリート登録あり）。ただし `/infer/dispatch` の relay 経路が 1033 で死んで
   おり ComfyUI ノード未登録 — 直結はブロックのまま。studio 側は契約の半分を
   実装: editor の **生成 job 書き出し**（`edit/generation-job` — 全 page/panel の
   prompt EDN、fleet CLJ runtime の入力）と **panel image url 取り込み欄**
   （成果物 URL の貼り込み）。
3. **外部 3 作品の編集 — メタ編集まで実装**。mangaka-reader Worker の source が
   repo から失われていた（wrangler.jsonc のみ、main は実在しない index.cljc 参照）
   ため、**deployed script を Cloudflare API から回収して
   `reader/src/index.js` として正本化**し拡張: `GET /api/works`（一覧 JSON）、
   `GET /api/work/:rkey`（genre/logline/pageCount 追加 + CORS）、
   `POST /api/work/:rkey`（title/author/series/genre/logline を D1 work row へ。
   Bearer `MANGAKA_ADMIN_TOKEN` — wrangler secret、ローカル控えは Keychain
   `mangaka-admin-token`）。aozora 側は `/studio/ext/<rkey>` メタ編集ページ。
   ページ画像/blob の書込は従来どおり fleet publish フロー（RUNBOOK）。

live 検証済み: 4 profile のサーバ昇格（1 投稿 + フォローボタン表示）、外部メタ
editor の実 D1 読込、401 ゲート、認可付き同値書込、reader HTML 無変化。
残 follow-up: murakumo relay 復旧後の生成直結、外部作品のページ単位編集、
studio への actor 鍵 import UI（Keychain からの復元）。
