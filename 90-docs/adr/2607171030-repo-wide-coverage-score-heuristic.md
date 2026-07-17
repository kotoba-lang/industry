# ADR-2607171030: repo-maturity に coverage-score ヒューリスティック軸を追加（Phase 1）

## Status

Accepted（2026-07-17）。Phase 1（静的ヒューリスティック・全 repo 可視化）。Phase 2（cloverage 実カバレッジ）は別 ADR で follow-up。オーナー選択: declared 層 = B（README のみ）、全件再生成 = Z（CI cron 段階的）。

## Context

`scripts/repo-maturity.cljs` は west 登録の全 repo（約2910）を GitHub GraphQL API で4軸（stage/structural/activity/impl）評価し、`manifest/repo-maturity.edn`（DataScript transact 可能な EAVT entity-map）を生成している。**test coverage 軸が不在**。オーナー指示で repo wide の機能 coverage / test coverage 評価を整備することになり、本 ADR は test coverage の Phase 1（ヒューリスティック）を定める。

Phase 2（cloverage 実カバレッジ）は JVM 必須・全 repo 一括が困難なため、先に静的ヒューリスティックで全 repo を可視化する。

実態調査（2026-07-17）:
- runtime 分布: Clojure(deps.edn) ~83% / Node ~3% / cljs ~2% / bb ~1-2% / Rust ~0%
- 既に cloverage `:coverage` alias を持つ repo はわずか5（kotoba-lang/crdt, slides, presentationml, ooxml, drawingml）。いずれも `--fail-threshold 85/90` + `--lcov`
- crdt/slides の README は数値 coverage 宣言を持たない（deps.edn にのみ fail-threshold）
- west.yml は現在 約2910 repo（CLAUDE.md の 1642 は陳腐化）

## Decision

**`scripts/repo-maturity.cljs` に第5軸 `:maturity/coverage-score` を追加する。3層フォールバック・捏造ゼロ。**

1. **宣言パース（high 信頼）**: README/CLAUDE.md の `Coverage: N%` / `fail-threshold N` を正規表現で数値化（0-1 に正規化）。`:method :declared`
2. **file-ratio（mid 信頼）**: REST `git/trees/HEAD?recursive=1` で src/ と test(s)/ のファイル数比 = test-files / (src-files + test-files)。GraphQL `Tree.entries` は直下のみで Clojure の `src/<ns>/file.cljc` 構造を辿れないため REST recursive tree を使う（実測: crdt 6/6 → 0.5、ghosthacker-duet 5/3 → 0.375、truncated なし）。`:method :file-ratio`
3. **nil**: いずれも取れなければ nil（tests-dir binary は structural-score が既に見ているので出さない・重複回避）

各 entity に `:maturity/coverage-score` `:maturity/coverage-detail` `:maturity/coverage-score-method` を付与。

**composite を5軸化**: stage 0.30 / structural 0.15 / activity 0.15 / impl 0.20 / coverage 0.20（nil 軸は残りで再正規化）。⚠ 既存の全エントリの `:maturity/composite` が再計算される（時系列比較は `:maturity/computed-at` で区別）。

**declared 層は README の宣言のみ**（オーナー選択 B）。deps.edn の `--fail-threshold` は拡張しない。Phase 2 の cloverage 実行で全 repo の実カバレッジを取るため、Phase 1 の declared は README に coverage を明記する運用を待つ。

**全件再生成は CI cron で段階的**（オーナー選択 Z）。2910 repo は GraphQL rate limit（5000 points/h）を超え1回で終わらないため、CI が `--merge-existing`（スキップした repo は既存 edn の古い値を保持）で毎日少しずつ進める。

## Consequences

**Good**:
- 全 repo の test coverage が CI 段階的に可視化される。file-ratio は cloverage 未導入 repo にも入る。
- impl-score と同じ「HEURISTIC PROXY と明記」文化（`:maturity/coverage-score-method` で方法を開示）。捏造ゼロ。
- `--self-test` で純関数ロジック（parse-declared-coverage / coverage-score-from-data / composite 5軸）を検証可能。

**Bad / 制約**:
- file-ratio はテストファイル数の比であって実行カバレッジでない。test が厚くても質は分からない。Phase 2 の cloverage 実カバレッジ（別軸 `:maturity/coverage-actual`）で正確化。
- declared 層は README に coverage を書く repo が（crdt/slides 含め）今はほぼ無いので、実質 file-ratio のみ。
- composite の再計算で既存の成熟度スコアが全エントリで変わる（ADR-2607021700 portfolio maturity とは別物・repo 単位）。
- 全件更新は CI cron で段階的（数日かけて埋まる）。即時の全件 snapshot は不可。

## Alternatives considered

1. **GraphQL `HEAD:src`/`HEAD:tests` Tree.entries でファイル比** — 却下。Tree.entries は直下のみで、Clojure repo の `src/<ns>/file.cljc`（深さ2-3）構造だと `src/` の中身がディレクトリ1つで blob 0 になり比が作れない。depth-2 にすると GraphQL rate limit を更に圧迫。
2. **deps.edn の `--fail-threshold` を declared に拡張** — 却下（B）。既存 cloverage 5 repo の閾値を取れるが、各 repo で deps.edn 取得の REST が増え実行時間が倍増し、Phase 2 の cloverage 実行で obsolete になる。
3. **1回のローカル全件実行** — 却下（Z 採用により不採用）。2910 repo は GraphQL 5000 points/h を超え、1 job / 1 セッションで終わらない。

## References

- `scripts/repo-maturity.cljs`（4軸 maturity システム。専用 ADR 無し、冒頭コメントが doc）
- 既存 cloverage 鋳型: `orgs/kotoba-lang/slides/deps.edn` / `crdt/deps.edn` の `:coverage` alias
- Phase 2 follow-up ADR（cloverage 実カバレッジ注入・別起票予定）
- `manifest/repo-maturity.edn`（生成物・DO NOT EDIT BY HAND）
