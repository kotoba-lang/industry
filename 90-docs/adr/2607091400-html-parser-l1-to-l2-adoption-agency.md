# ADR-2607091400: HTML パーサを L1 (subset tokenizer) から L2 (active formatting + adoption agency) に引き上げる — `htmldom.core`

**Status**: accepted — landed (2026-07-09)
**Date**: 2026-07-09
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/htmldom`（`parse-into-document` に active formatting elements + reconstruct + adoption agency を追加）・superproject `manifest/west.yml` pin

## Context

ブラウザエンジン成熟度マトリクスで HTML パーサは L1（stub: 信頼できるサブセット向けトークナイザ。属性重複・pre/textarea 改行・tbody 挿入・コメント異常終端などサブセット内の正しさは作り込んだが、本物の WHATWG パースアルゴリズムそのものではない）だった。最大のギャップは tree-construction の骨格: 単一 open-elements スタック＋auto-close ヒューリスティックで、誤ネストされた formatting 要素（`<b>p<i>s</b>e</i>` 等）を実ブラウザと同じように修正する仕組みがなかった。

## Decision

L2 を「信頼できるサブセット内で実ブラウザと同じ誤ネスト修正を行う、本物の WHATWG アルゴリズムの核心」として着地する。`:document` ルートモデル（content は :document 直下にネスト）は維持し、`<html>/<head>/<body>` の合成は行わない — これは cssom/layout/dom-bridge が :document 直下を前提とする設計なので、L3 のアーキテクチャ変更として別途切り出す。これらのアルゴリズムは誤ネスト入力でのみ発火し、整形式 HTML は従前とバイト同一にパースされる（既存133テストが回帰ゲート）。

WHATWG 仕様の verbatim テキストを取得して実装した（subagent で §13.2.4.3 / §13.2.6.4.7 をフェッチ。step 8 が formattingElement を afe リストから *remove* することが、初期の曖昧な記憶との違いで決定的だった）。

1. **active formatting elements list**（Noah's Ark clause 付き）— formatting 要素の開始タグが push される。
2. **reconstruct active formatting elements**（§13.2.4.3）— 暗黙に閉じられた formatting 要素を、後続テキスト/要素の前に再オープン。
3. **adoption agency algorithm** の no-furthest-block path（§13.2.6.4.7 steps 1-8 + any-other-end-tag fallback）— 誤ネストの修正。

## Consequences

- HTML パーサ L1→L2。`<b>p<i>s</b>e</i>` → `<b>p<i>s</i></b><i>e</i>`、`<b>x<i>y</b>z` → `<b>x<i>y</i></b><i>z</i>`、`<div><b>x</div>y` → `<div><b>x</b></div><b>y</b>`（b 再オープン）など、実ブラウザと同一の木を生成（REPL で照合）。
- 整形式 HTML の出力は不変（133 テスト緑のまま）。browser スイートも新 htmldom で 686/3210/0 fail（cross-repo 回帰なし）。
- 純粋な仕様アルゴリズムを段階導入できた。L3 は残りの仕様カバレッジ（下記）。

## Levels

- HTML パーサ: L1 (subset tokenizer) → L2 (real WHATWG active-formatting + adoption agency, partial).
- L3（未着手）: AAA furthest-block reparenting（steps 9-19; `<b><p>x</b>` 等の block-in-inline は現状 naive ネストにフォールバック、クラッシュしない）、foster parenting（in-table）、23モード挿入モード状態機械、`<html>/<head>/<body>` 合成、template/select/frameset、scope markers、`a`/`nobr` in-scope 特殊ケース、generate-implied-end-tags。

## Test status

- htmldom: 141 tests / 423 assertions / 0 fail（133 既存 + 8 新規 adoption-agency）。lint 0 errors。
- browser（新 htmldom）: 686 / 3210 / 0 fail（回帰なし）。
- 各期待木は実ブラウザ（html5lib/Chrome/Firefox）で照合済み。
