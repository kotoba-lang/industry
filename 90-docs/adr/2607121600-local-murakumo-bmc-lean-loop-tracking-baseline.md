# ADR-2607121600: local-murakumo BMC / Lean Loop iteration tracking baseline (Iteration 0)

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki

## Context

local-murakumo と claude-murakumo の business / technical maturity を調査した
結果、番号管理された Business Model Canvas（BMC）/ Lean Loop
（build-measure-learn）の反復トラッキングが repo 内のどこにも存在しないことが
判明した。

- 唯一の関連物は `90-docs/adr/2607021500-portfolio-seven-layer-business-model-lean-canvas.md`
  のポートフォリオ横断 Lean Canvas で、そこでは `cloud-murakumo`（GPU レンタル
  商品「Sora」、local-murakumo とは別プロダクト）を L1-infra として位置づける
  canvas のみが存在し、未採番。**local-murakumo 固有の canvas は無い。**
- `claude-murakumo`（`orgs/kotoba-lang/murakumo/tools/claude-murakumo/`）は
  `ANTHROPIC_BASE_URL` 等を local-murakumo の `/v1/messages` に向けて実 `claude`
  を exec するだけの開発者向けランチャースクリプトであり、独立した事業体では
  ない。BMC/Lean Loop の対象にならない。
- 「lean loop」という語自体が repo 全体で検索ゼロ件（英語・日本語とも）。
  最も近い先行例は `orgs/gftdcojp/ai-gftd-shinshi/docs/260613-bmc-lean.datoms.edn`
  の H1/H2 仮説検証テレメトリだが、これは murakumo には未移植。

技術的成熟度（実働 7 ノードフリート、Claude Code 実トラフィックでの疎通確認済み）
に対し、事業的成熟度（実売上ゼロ、サードパーティ自己登録ゼロ）が明確に遅れている
非対称な状態が本調査で確認された。

## Decision

- `orgs/gftdcojp/local-murakumo/docs/bmc-lean-loop-log.md` を、local-murakumo の
  BMC 改訂 と Lean Loop 仮説検証（H1, H2, ...）を一括して番号管理する
  **append-only の正本ログ**と定める。
- **Iteration 0** を「まだ着手していないベースライン」として本 ADR と同日に
  記録する（本調査で判明した現状の棚卸しのみ、新規 BMC 草稿や仮説はまだ無い）。
- 以後、BMC を初めて起草した時点・各 Lean Loop 仮説を立てて検証した時点ごとに
  iteration 番号をインクリメントして同ログに追記する。
- `claude-murakumo` は本トラッキングのスコープ外と明示する（開発者ツールであり
  独自の事業モデルを持たない）。
- 参照モデルは `ai-gftd-shinshi` の `docs/260613-bmc-lean.datoms.edn`
  （H1/H2 仮説 telemetry パターン）とし、Iteration 1 以降で同型の仮説検証を
  local-murakumo にも移植する。

## Consequences

- (+) local-murakumo の事業仮説検証が番号管理され、進捗を反復単位で追跡できる
  ようになる。
- (+) `claude-murakumo` が明示的にスコープ外と定義され、今後の調査での混同を防ぐ。
- (−) `cloud-murakumo`（Sora）や他ポートフォリオプロダクトの BMC/Lean Loop
  反復番号管理は本 ADR の対象外（follow-up）。
- (−) Iteration 0 時点では local-murakumo 固有の BMC 9 ブロックはまだ起草されて
  いない（次回反復の課題として `docs/bmc-lean-loop-log.md` に明記）。

## References

- `90-docs/adr/2607021500-portfolio-seven-layer-business-model-lean-canvas.md`
- `orgs/gftdcojp/local-murakumo/docs/business.md`
- `orgs/gftdcojp/local-murakumo/docs/bmc-lean-loop-log.md`（本 ADR が定める正本）
- `orgs/gftdcojp/ai-gftd-shinshi/docs/260613-bmc-lean.datoms.edn`
- `90-docs/adr/2607041302-murakumo-family-naming.md`
- `90-docs/adr/2607051621-cloud-murakumo-agpl-isic-network-registry-kotoba-treasury.md`
