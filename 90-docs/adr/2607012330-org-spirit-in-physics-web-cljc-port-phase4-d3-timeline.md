# ADR-2607012330: org-spirit-in-physics apps/web-cljc フェーズ4（D3 timeline/KPIチャート）

**Status**: accepted
**Date**: 2026-07-01
**Scope**: `orgs/com-junkawasaki/org-spirit-in-physics/apps/web-cljc`

## Context

ADR-2607012100（フェーズ0〜2）はフェーズ4（D3チャート）を「別ADR/別セッション
で扱う」としてスコープ外にしていた。本ADRはそのフェーズ4
（`TimelineChart.svelte` + `KPICards.svelte`、PR予定）を対象とする、
指定された「別ADR」にあたる。フェーズ5（KaTeX/paper、ADR-2607012230・
PR #21）と同様、着手前に本ADRを起票する。

### 事前調査結果

1. **フェーズ3（Force3D）から独立して実装可能**: `TimelineChart`/`KPICards`
   は `TimelineVisualization.svelte`（816行、web/researcher間でバイト同一）
   からのみ import され、その内部は `activeTab: 'timeline' | 'force3d'`
   でタブ切替。`'timeline'` タブは `KPICards` + `TimelineChart` のみを
   レンダリングし、`Force3DThrelte`/`Force3DControls`/
   `StructureAnalysisPanel`（いずれもフェーズ3領域）には一切依存しない。
   実際に到達可能な5ルート（`apps/web` の `demo/force3d`・
   `experiment/report`・`analyzer`、`apps/researcher` の `+page`・
   `participants/[id]`）すべてが `TimelineVisualization` をマウントする。
   **本ADRでは `TimelineVisualization` のシェル（タブ切替+データ取得）と
   `'timeline'` タブの中身のみを移植し、`'force3d'` タブは（フェーズ3が
   別ADR/別セッションで来るまで）プレースホルダとする。**
2. **D3 API surface**: `d3` `^7.9.0`、plain ESM import。使用APIは
   `select`/`selectAll`・`scaleTime`/`scaleLinear`・`extent`/`max`・
   `area`/`line`/`curveMonotoneX`・`brushX`・`axisBottom`・`timeFormat`
   のみ（force simulation・canvas・zoom/dragは不使用）。ADR-2606290000
   の exemption 条項を適用し npm interop wrap（KaTeX/WebAuthnと同戦術）。
   `TimelineChart` は `$effect` 内での命令的DOM操作（Reactの旧D3統合パターン
   と同型）；cljc-ui-IR の宣言的レンダリングとは相性が悪いため、SVG要素の
   マウント後にD3が直接DOM操作する「host-mutated subtree」として扱う
   （フェーズ5のKaTeXと同種、`:opaque` 機構を再利用）。
3. **データソースはバックエンド側で実装済み**: `apps/api-worker-cljc/src/
   spirit/routes/timeline.cljc`（フェーズ0で移植済み、PR #19）が
   `/api/timeline/integrated`・`/analysis`・`/word-statistics`・
   `/word-aggregates`・`/emotion-vectors` を実装済み。新規バックエンド作業
   不要、`api_client.cljc` に fetch 呼び出しを追加するのみ。
4. **KPICards の前回値差分は `Math.random()` ベースの demo placeholder**
   （オリジナルのコードコメントで「前React版からの遺物」と明記）で実データ
   ではない。研究ツールで捏造トレンドを表示するのは誤解を招くため、
   **本移植では前回値差分UIを省略**する（オーナー判断、2026-07-01）。
   カード本体（値+スパークライン）は忠実に移植する。

## Decision

- `spirit-ui.d3-interop`: `d3` npm パッケージを上記API surfaceのみ薄く
  ラップ。SVG要素への直接DOM操作（`.attr`/`.append`/`.call` 等）は D3 自身
  に任せ、cljc-ui-IR は SVGコンテナ要素（`:opaque true`）を1回だけマウント
  するところまでを担当する。
- `spirit-ui.views.web.timeline`（or researcher配下、配置はスキャフォールド
  時に確定）: `TimelineVisualization` のシェル（タブ切替 `:active-tab`
  state、`fetchData`→`api-client` 呼び出し）+ `KPICards`/`TimelineChart`
  相当の2コンポーネント。`'force3d'` タブは「Coming soon（フェーズ3）」
  プレースホルダ。
- KPICards の前回値差分UIは省略（本文参照）。

## Consequences

- researcher ダッシュボードの主要機能（タイムライン閲覧・KPI確認）が
  CLJC側で使えるようになるが、Force3D可視化タブは引き続きプレースホルダ
  のまま（フェーズ3待ち）。
- `:opaque` 機構がKaTeX以外の2つめの実例（D3）を得ることで、host-mutated
  subtree という抽象が「KaTeX固有」ではなく複数ライブラリに再利用可能な
  パターンであることが実証される。

## Related

- `90-docs/adr/2607012100-org-spirit-in-physics-web-cljc-port.md`（フェーズ0-2）
- `90-docs/adr/2607012230-org-spirit-in-physics-web-cljc-port-phase5-katex-paper.md`
  （フェーズ5、`:opaque` 機構の初出）
- `90-docs/adr/2606290000-all-workers-cljc-only-policy.md`（interop wrap の
  exemption条項）

## Out-of-scope

- フェーズ3（Force3D可視化、kami-engine結合） — `'force3d'` タブは
  プレースホルダのまま
- `StructureAnalysisPanel`（フェーズ3領域）
- KPICardsの前回値差分（デモ用ダミーデータのため省略）

## Verification Notes

- cljs.test（d3-interopラッパの純粋部分、あれば）
- `shadow-cljs release web` / `release researcher`: 0 warnings
- ブラウザでの実動作確認（claude-in-chrome）: api-worker-cljc
  （`wrangler dev --local`）に対し実データでタイムライン/KPI表示、
  ブラシによる時間範囲選択の動作確認
