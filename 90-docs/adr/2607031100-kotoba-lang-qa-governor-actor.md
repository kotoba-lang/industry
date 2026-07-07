# ADR-2607031100: kotoba-lang/qa-governor — 「Nintendoクオリティ」ルーブリックを共通のQA governor actorに（proposed・pure core のみ scaffold 実装）

**Status**: proposed（pure `.cljc` のルーブリック/governor/台帳ロジック + 5決定論的collector + langgraph-clj StateGraph配線まで実装済み。実LLM採点ノードは依然未実装 — 下記 Addendum 参照）
**Date**: 2026-07-03
**Deciders**: Jun Kawasaki

## Context

`ghosthacker-flow`（Ghost Hacker ゲームポートフォリオ第1弾）の品質改善サイクルの中で、
「Nintendoクオリティを score 品質に」変換して自動検証するアイデアが出た。設計としては
CLAUDE.md の「Actors」節にある既存パターン——**知能ノード（LLM）を1ノードに封じ込め
proposal のみ返させ、別系統の Governor が検閲して可決/拒否に振る。単一不変条件
「governor が拒否する commit を actor は決して行わない」。全 commit を append-only の
監査台帳に積む**——をそのままQAに転用するのが筋が良い、と判断した。

このQA governorは`ghosthacker-flow`固有のものにする必要が無い——ルーブリック
（安定性・正しさ・堅牢性・ドキュメント整合性等）も、Governorの検証ロジック
（LLMの自己申告採点を実行ログ等の証拠なしに信用しない）も、台帳の形も、
どのリポジトリのQAにも一般化できる。`manifest/repos.edn`の org taxonomy
（ADR-2606302300）は「language-substrate → kotoba-lang、全org が消費」と
定めており、この位置づけに一致する。よって `ghosthacker-flow` 固有に作るのではなく
`kotoba-lang/qa-governor` として共通化し、`ghosthacker-flow` を最初のconsumerにする。

## Decision

**`kotoba-lang/qa-governor`** を新設し、以下3層のpure `.cljc` ロジックを実装する
（`ghosthacker-flow.core` と同じく、pure core を先に固め、langgraph-clj
StateGraphへの実配線・実LLMノードは別途のホストアダプタ層とする）:

1. **`qa-governor.rubric`** — カテゴリ×重みのルーブリック定義と加重平均スコア計算。
   既定ルーブリックは「Nintendoクオリティ」を項目化したもの:
   - `:stability`（クラッシュ/ハング0件）
   - `:correctness`（テスト/lint green率）
   - `:robustness`（対マッシュ・空振り検出等、不正な得点稼ぎ手段が塞がれているか）
   - `:documentation`（README/CHANGELOGとコードの整合性）
   - `:consistency`（API命名の対称性等、深堀りレビューでの指摘件数）

   これらはドメイン非依存の既定カテゴリで、consumer（`ghosthacker-flow`等）が
   `:extra-categories`（例: game-feel＝判定窓のチューニング妥当性）を追加できる
   ようルーブリック自体をデータ（EDN）として拡張可能にする。

2. **`qa-governor.governor`** — 知能ノードが返す採点案（`{:category :score :evidence}`
   の集合）を検証する。**LLMの自己申告を無条件で信用しない**——各カテゴリの
   `:evidence` が空、またはスコアの主張と矛盾する場合（例: correctness=100だが
   evidenceに"failures"の記述がある）は却下し、`:verdict :rejected`を返す。
   承認されたスコアのみ台帳にcommitされる。

3. **`qa-governor.ledger`** — append-onlyのスコア履歴。`record`で1件追記、
   `history`/`latest`/`trend`で参照する。時系列でスコア推移を追える。

## Scope（今回のscaffold）

pure `.cljc` の3namespaceとtestのみ。langgraph-clj StateGraphでの実際の
QA-LLMノード配線、実リポジトリ（テスト実行結果・lint結果・git履歴）からの
evidence収集ホストアダプタは対象外——`ghosthacker-flow.core`と同型のレイヤ
分離方針（pure core先行、host adapterは別途）を踏襲する。

## Open Questions

- evidence検証のルール（何が「証拠として妥当」かの判定基準）は現状シンプルな
  パターンマッチのみ。実運用では、governorがテスト実行ログ/CI結果を実際に
  parseして突き合わせる、より厳密な検証ロジックへ強化する必要がある
  （単純な文字列マッチの既知の弱点を`qa-governor.operation`のテスト作成中に
  実際に踏んだ — 下記Addendum参照）。
- ~~langgraph-clj StateGraphへの実配線~~ **完了（下記Addendum）**。実LLM
  採点ノード（QA-LLM proposal）・interrupt-before人間承認は引き続き未実装。
- `ghosthacker-flow`以外のconsumer（kami-engine、kotoba-lang自身等）への適用は
  今回のスコープ外。

## Addendum（2026-07-07）: `qa-governor.operation` — StateGraph配線を実装

Open Questionsが残していたStateGraph配線を実装した。`intake → collect →
govern → decide → commit | hold`（`minidrama.operation`等の既存Actorsと
同型）。`:collect`ノードは既存の5決定論的collector
（`qa-governor.collectors.repo/collect`、LLM無し）を呼ぶ——実LLM採点ノードは
引き続き未実装だが、governorのevidence/score整合性チェックは決定論的
collectorに対しても有効（collector自体のバグ検知）で、将来の実LLMノードは
同じ`:govern`ノードへの追加proposal源として差し込むだけでよい設計にした。

`ghosthacker-flow`に対する実行（本物のcollector、モック無し）:
correctness=100/consistency=100/stability=100/documentation=100/
**robustness=0**（README作成時の2026-07-03は100だった——thrown?/guard系
deftestが現在のtest sourceに0件、リポジトリ側の変化であって配線側の
バグではないことを`qa-governor.collectors.repo/collect`を直接呼んで
evidence文言まで確認済み）、総合スコア80.0（グレードB）、`:disposition
:commit`でledgerに正しく記録。

**テスト作成中に踏んだ実バグ**（governor.cljc自体ではなく、テスト側の
evidence文言の書き方の問題として）: `:stability`カテゴリのcontradiction
patternが`#"(?i)\bhang|..."`で、evidence文言に"no **hang**"と書くと
"hang"の部分文字列にマッチして矛盾判定される（否定文脈を読めない、
Open Questionsが最初から明記していた簡易パターンマッチの限界の実例）。
テスト用evidenceの文言を"exits cleanly..."に直して解消——governor.cljc
自体は変更していない（この限界は既知・記録済みの設計上の制約のまま）。

24 tests / 86 assertions（新規`qa-governor.operation-test`込み）、
lint 0 warnings。`ghosthacker-flow`以外のconsumerへの適用、実LLM採点
ノード、interrupt-before承認は引き続きスコープ外。

## Consequences

**Positive**
- QAの仕組みを`ghosthacker-flow`専用に作らず、org taxonomyに沿ってkotoba-lang
  へ最初から共通化したことで、将来他repoへの適用時に複製が発生しない。
- 既存のActorsパターン（封じ込め+governor+台帳）をQAという新しい用途に
  転用でき、アーキテクチャの一貫性が保たれる。

**Negative / 制約（honest）**
- StateGraph配線は実装済み（Addendum参照）だが、実LLM採点ノードは依然
  未実装——「AIが質的にコードを評価する」動作はまだ無く、決定論的
  collectorが機械的に測れる範囲に限られる。
- evidence検証は簡易パターンマッチのままで、実運用に耐える厳密さにはまだ
  達していない（否定文脈を読めない等の実例をAddendumで確認済み）。
