# ADR-2607012100: org-spirit-in-physics apps/web + apps/researcher を CLJC (cljc-ui-IR) へ移植

**Status**: proposed
**Date**: 2026-07-01
**Scope**: `orgs/com-junkawasaki/org-spirit-in-physics/apps/{web,researcher}`

## Context

`apps/api-worker` の CLJC 移植（ADR-2607011800、PR #19 マージ済み）に続き、
フロントエンド（`apps/web` ~9,900行 + `apps/researcher` ~4,500行、合計
~14,400行の SvelteKit 2 / Svelte 5 runes アプリ）の移植に着手する。

事前調査で、当初の想定（ADR-2606290000 Pattern A をそのまま適用）を修正する
必要がある事実が判明した。**この ADR は同時にその事実の訂正記録でもある**:

1. **cloud-murakumo の実態訂正**: ADR-2606290000 は Pattern A（assets-only
   SPA, cljc-ui-IR）の参照実装として cloud-murakumo を挙げ「既に prod 実績が
   ある」と記載しているが、実際には369行・単一commit（2026-06-30 21:18）の
   PoC で、DOM は毎回 `replaceChildren` で全再構築（差分更新なし）、フォームは
   `<input type=range>` 1個のみ、ルーティングはパラメータ無し3画面の
   hashルート、テスト無し。**ADR-2606290000 の日付（2026-06-29）はこの
   コードの存在（2026-06-30）より前**であり、「prod 実績」の記述は当時
   時点で事実に基づいていない。README にある「kotoba WASM UI substrate は
   `orgs/kotoba-lang/wasm-ui` に移行」という記述も、当該ディレクトリが
   存在しないため事実誤認である。
2. 対象アプリ（~30ルート、フォーム多数、動的ルート `researcher/participants/[id]`、
   Force3D可視化、D3チャート、KaTeX数式）は cloud-murakumo の実証規模を
   大きく超える。**差分レンダラー・パラメータ付きルーティング・フォーム
   プリミティブは本移植で新規に構築する。**
3. **3D**: `orgs/com-junkawasaki/kami-engine`（Rust/wgpu、17万行、稼働中）+
   `kami-engine-sdk-clj`（cljc/cljs層、2,298行、ユニットテスト16件green、
   ただしブラウザでのライブ動作は未検証）が実在する土台。オービットカメラ・
   エッジ/ライン描画パイプライン・力学シミュレーション（`kami-graph` は
   Rust側に2D限定で存在するがcljsバインディング無し）は今回新規に結合が
   必要（フェーズ3、本ADRのスコープ外）。
4. **D3・KaTeX**: 組織内に cljs 相当が一切無い。ADR-2606290000 の exemption
   条項（TS-only ライブラリに cljs 等価が無い場合は interop wrap がデフォルト）
   を適用し、npm interop でラップする方針（フェーズ4/5、本ADRのスコープ外）。

## Decision

### フェーズ分割

本ADRが対象とするのはフェーズ0〜2のみ（3D/D3/KaTeXを含まない範囲）:
- **フェーズ0**: `apps/web-cljc/` スキャフォールド。shadow-cljs `:target
  :browser`（Pattern A, worker無し, assets配信）。cljc-ui-IR コア
  （IRコンストラクタ・差分レンダラー・パラメータ付きルーティング・
  フォームプリミティブ）を新規実装。
- **フェーズ1**: `pkg/consent`・`pkg/experiment` を EDN データとして移植。
  `pkg/visualization/types.ts`・`emotion-normalization.ts` を移植
  （`structure-analysis.ts` はどこからも呼ばれていない未使用コードのため
  除外）。
- **フェーズ2**: 3D/D3/KaTeXを含まない単純ページを先行移植し、フェーズ0の
  基盤を実ページで検証する。認証（`/api/auth/*`）もこの段階で配線。

フェーズ3（kami-engine結合によるForce3D）・フェーズ4（D3 interop wrap）・
フェーズ5（KaTeX/paper静的ページ）は別ADR/別セッションで扱う。

### 重複コードの統合

`lib/components/researcher/*`（3D/チャート/パネル一式）は web/researcher 間で
ほぼバイト同一にコピペされている。CLJC移植では1つの共有名前空間へ統合する
（TS側の二重メンテナンスを引き継がない）。

### アプリ構成

`web`・`researcher` を単一 SPA + ルートprefixで統合するか別アプリに分けるかは
フェーズ0着手時に確定する（認証状態・APIクライアントの共有可否で判断）。

## Consequences

- 差分レンダラー・パラメータ付きルーティング・フォームプリミティブという
  実用レベルの cljc-ui-IR 基盤が組織に前例として残り、以後の gftdcojp 側
  Pattern A 採用（club-shinshi 等、いずれも同様に未成熟な参照実装しか無い）
  でも再利用可能になる。
- cloud-murakumo・ADR-2606290000 の記述不正確箇所を本ADRで訂正記録。
  ADR-2606290000 自体の本文修正は別途（本ADRは訂正の事実を記録するのみ）。
- kami-engine-sdk-clj・D3・KaTeXとの結合はフェーズ3〜5に先送りされ、
  本移植の完了時点でも `/analyzer`・researcher `/` の3Dパネル・チャート・
  paperページは旧SvelteKit実装のまま残る。

## Related

- `90-docs/adr/2606290000-all-workers-cljc-only-policy.md`（Pattern A の
  定義元。本ADRはその実証規模を修正しつつ適用する）
- `90-docs/adr/2607011800-org-spirit-in-physics-api-worker-cljc-port.md`
  （同リポジトリのバックエンド移植、本移植のAPI対向先）

## Out-of-scope

- Force3D可視化（kami-engine結合、フェーズ3）
- D3チャート（TimelineChart/KPICards、フェーズ4）
- KaTeX/paperページ（フェーズ5）
- 本番デプロイ・cutover・既存SvelteKitアプリの削除

## Verification Notes

- cljs.test（IR/router/domの新規ユニットテスト）
- `shadow-cljs release web` ビルド確認
- ブラウザでの実動作確認（consent/settingsフォーム送信、researcher
  participants一覧→詳細の動的ルート遷移、認証Cookie往復）
- api-worker-cljc（`wrangler dev --local`）との統合確認
