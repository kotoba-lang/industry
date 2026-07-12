# ADR-2607110600: kotoba-lang/x402-directory — nexus-x402 の HTML ディレクトリ page を汎用 lib へ抽出

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki

## Context

`gftdcojp/nexus-x402`(ADR-2607093300)は ADR-0002(external facilitator
GTM plan)のステップ3として `nexus.directory`(`/catalog` の JSON と同じ
データを人間向け HTML で描画する page、`x402.nexus/` で content-negotiation
配信、live 稼働済)を実装した。このモジュールは元々「pure string-building、
zero deps、host interop 無し」と明記されており、nexus-x402 固有の branding
（title/tagline/pitch/links）以外は完全に汎用（任意の x402 facilitator の
`/catalog` shape をそのまま受け取る）。

オーナー指示（2026-07-10）: HN/Reddit/Base 向けの投稿コンテンツを準備する際、
nexus-x402 自体は private repo のまま（ADR-0002 の決定を維持）だが、
「共有可能な公開の成果物」を作るため kotoba-lang org に repo を作り lib 化する。

## Decision

1. **`kotoba-lang/x402-directory`**(public、Apache-2.0)を新規作成。
   `nexus.directory` を branding パラメータ化して抽出:
   `x402.directory/page` は `{:origin :items :branding}` を受け取り、
   `:branding` の `:title :tagline :pitch-html :empty-html :extra-links :css`
   をデフォルトにマージする。8 tests/21 assertions、clj-kondo clean。
   「never fabricates」不変条件（空 registry は honest empty state、
   fabricated stats を出さない）を維持し、テストで固定。
2. **kotoba-lang/pay・kotoba-lang/treasury と同型の抽出パターン**:
   実装当時「唯一動く場所」だった nexus-x402 の一実装を、汎用ライブラリとして
   切り出し、既存プロダクトへ逆に依存させる(vendor from lib、embedded
   duplicate ではなく)。README の「Live demo」節で x402.nexus を dogfood
   実例として明記。
3. **nexus-x402 側の追随**（follow-up、別 commit）: `src/nexus/directory.cljc`
   を `kotoba-lang/x402-directory` への依存に置き換え、nexus-x402 固有の
   branding（"nexus-x402" title・pitch 文言・footer links）を `:branding`
   引数として渡す形にリファクタ。挙動は不変（同じテストが通ることを確認）。
4. **manifest 登録**: `orgs/kotoba-lang/x402-directory` を west へ登録
   （`--entry x402-directory` の最小 diff、pin == repo HEAD 検証）。

## Consequences

- (+) HN/Reddit/Base 向けコンテンツが「実在する、動くオープンソース成果物」を
  指せるようになった（nexus-x402 自体を公開しなくても、実装の汎用部分は
  公開・再利用可能）。
- (+) x402 facilitator を自前運用したい他プロジェクト（gftdcojp 内外問わず）
  が directory UI をゼロから書かずに済む — kotoba-lang/pay・treasury と同じ
  「プロトコル層は公開、運用層は各社が持つ」設計に整合。
- (−) nexus-x402 側の依存切り替え（決定3）は本 ADR 時点で follow-up。切り替え
  前は両モジュールが同一ロジックを別々に保持する一時的な重複がある。
- (−) 新規 public repo は CI（clj-kondo/test）が別途要る（既存 kotoba-lang
  repo と同型のため追加設計は不要、GitHub Actions ワークフロー雛形の追加のみ
  follow-up）。

## Addendum (2026-07-10): follow-ups landed, and an LLM-friendly design pass

Decision items 3 and 4 (marked "follow-up" above) are done: `nexus-x402`'s
`src/nexus/directory.cljc` now depends on `kotoba-lang/x402-directory`
(vendored at `src/x402/directory.cljc`) instead of duplicating it — same
tests pass, behavior unchanged — and `orgs/kotoba-lang/x402-directory` is
west-registered (`--entry x402-directory`, pin == repo HEAD verified).

Separately, per an owner instruction to make the LP itself more polished
and specifically **LLM-friendly** (not just human-readable), extended
`x402.directory/page`'s `:branding` map with `:page-title`
`:meta-description` `:badge-label` `:nav-links` `:extra-sections-html`, and
added a new `x402.directory/llms-txt` function — a plain-markdown summary
of the same live `/catalog` data per the [llms.txt](https://llmstxt.org)
convention, so an LLM reading the site gets a parseable document instead of
extracting facts from HTML. `nexus-x402`'s `worker.cljs` wires this up as
`GET /llms.txt` (content-negotiated alongside the existing HTML page), and
adds a `GET /stats` endpoint — an aggregate-only settlement census
(`{count, usd-total, agent-hint: {agent, human, unknown}}`, no per-payment
payer/tx/timestamp, no auth needed) built on new `nexus.settlements`
functions `classify-user-agent` and `agent-hint-breakdown`, wired at both
settlement call sites via User-Agent sniffing. All of this stays inside the
existing "never fabricates" invariant — `/stats`/`llms.txt` report only
what `/catalog` and the real settlement ledger already contain.

## References

- ADR-2607093300（nexus-x402 設計）/ nexus-x402 `docs/adr/0002`（外部 GTM
  plan、ステップ3の addendum が directory.cljc の landing を記録）
- `kotoba-lang/treasury` README（抽出パターンの先行例: 「domain-agnostic
  ... extracted from gftdcojp/local-murakumo」と同型の書き方）
- https://github.com/kotoba-lang/x402-directory
