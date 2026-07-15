---
id: adr-2607072350-kotoba-lang-org-oasis-open-xmile-system-dynamics
title: "ADR-2607072350: kotoba-lang/org-oasis-open-xmile — OASIS XMILE 1.0 (System Dynamics) を EDN として新設"
status: accepted
doc_type: adr
topic: kotoba-lang-org-oasis-open-xmile-system-dynamics
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - "kotoba-lang に System Dynamics（Forrester のストック&フロー・モデリング手法、https://en.wikipedia.org/wiki/System_dynamics）を計算機的に扱う共通基盤は `kotoba-lang/org-oasis-open-xmile` を正本とする"
  - "命名は既存の org-<body>-<spec> 規約（org-w3-webauthn / org-ietf-oauth2 / org-oasis-saml と同型）に従い、標準化団体の実ドメイン oasis-open.org を反映して `org-oasis-open-xmile` とする（ユーザー指示、2026-07-07）"
  - "System Dynamics の計算機表現は独自フォーマットを発明せず、OASIS が2015-12-14に標準化した XMILE 1.0（http://docs.oasis-open.org/xmile/xmile/v1.0/os/xmile-v1.0-os.html）のセクション番号を正として設計する"
  - "v1 スコープは stock/flow/aux の EDN モデル・equation micro-language のパーサ+評価器・構造検証・Euler/RK4 シミュレータに限定し、stochastic/delay/smooth 系ビルトイン・conveyor/queue の輸送セマンティクス・配列（dimensioned variable）・サブモデル/マクロは v2 として明示的にスコープアウトする（サイレントに間違った値を返すのではなく、parse/round-trip はできるが evaluate/execute は明確に throw する）"
related:
  - 90-docs/adr/2607041500-etzhayyim-compat-catalog-to-kotoba-lang.md
  - 90-docs/adr/2607061603-kotoba-lang-w3-spec-substrate-batch-rename.md
  - 90-docs/adr/2607060100-kotoba-lang-reverse-domain-rename-batch2.md
supersedes: []
superseded_by: []
---

# ADR-2607072350: kotoba-lang/org-oasis-open-xmile — OASIS XMILE 1.0 (System Dynamics) を EDN として新設

**Status**: accepted
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（本セッションでの質問「system dynamics として kotoba-lang に repo はあるか」への回答「無い」を受け、
`https://en.wikipedia.org/wiki/System_dynamics` の公式仕様（OASIS XMILE 1.0、
`https://www.oasis-open.org/standard/xmile1-0/`）に基づいて lib として設計するよう指示。
リポジトリ名は `kotoba-lang/org-oasis-open-xmile` とするよう指示。「do it」で実装・登録まで指示）

## Context

- kotoba-lang org 内を調査した結果、System Dynamics（ストック/フロー/フィードバックループに基づく
  Forrester 由来のモデリング手法）を扱うリポジトリは存在しなかった（`flow`/`nagare`/`physics`/`statechart`
  等、近接領域のリポジトリはあるが、いずれも system dynamics のストック&フロー・モデルそのものは扱っていない）。
- System Dynamics の業界標準インターチェンジフォーマットは OASIS が2015-12-14に承認した XMILE 1.0
  （XML Interchange Language for System Dynamics — Stella/iThink 等が実装）であり、ユーザーの指示により
  これを正とする。仕様書（HTML/PDF/XSD、`docs.oasis-open.org/xmile/xmile/v1.0/os/`）から実際にセクション
  番号付きで仕様を確認した上で設計した（詳細は下記 `:decision :spec-sections`）。
- 命名は kotoba-lang の既存 `org-<body>-<spec>` 規約（`org-w3-webauthn`/`org-ietf-oauth2`/`org-openid-oidc`/
  `org-oasis-saml` と同型、`manifest/repos.edn` 内のコメント参照）に従うが、ユーザーが標準化団体の実ドメイン
  `oasis-open.org` を反映した `org-oasis-open-xmile` を明示的に指定した（既存の `org-oasis-saml` は単に
  `oasis` だが、本 repo はユーザー指示によりドメインをより正確に反映する）。

## Decision

`kotoba-lang/org-oasis-open-xmile` を新設し、OASIS XMILE 1.0 の主要な計算機的コアを EDN + `.cljc` で
実装する。既存の kotoba-lang capability library の型（`statechart`/`states`/`sigma`/`policy` と同型:
model + validate + execute、`kotoba-lang/dsl-core` の `kotoba.dsl.problem` 検証結果規約を共有）を踏襲する。

- **`xmile.model`** — sec 3.1 (stock/flow/aux)・sec 3.7.1 (sim_specs)・sec 3.2.2 (gf) の EDN スキーマ +
  builder + 構造クエリ。
- **`xmile.expr`** — equation micro-language（sec 3.3 の文法・演算子優先順位、sec 3.5 のビルトイン関数）の
  文字列 -> EDN 式木パーサと純粋評価器。
- **`xmile.xml`** — 既にパース済みの XML 要素木（`clojure.data.xml`/`cljs.xml` がそのまま出力する
  `{:tag :stock :attrs {...} :content [...]}` 形）と `:xmile/*` EDN モデルの相互変換。XML テキスト自体は
  パースしない（host が担当、`sigma.yaml` と同じ境界規約）。diagram/display layer（`<views>`/`<style>`）は
  意図的にスコープ外（sec 3.7.5 が「diagram の無い whole-model も simulate できねばならない」と明記）。
  IPaymentPort/IAction のようなポート層は不要（equation はすべて純粋関数、v1 では stochastic/delay 系を
  未実装として明示的にエラーにするだけで、実際の host injection port はまだ導入していない）。
- **`xmile.validate`** — ぶら下がり参照・不正な代数ループ（stock を挟まない flow/aux 間の循環依存）・
  sim_specs/gf の構造検証。`kotoba.dsl.problem` の `:error`/`:warn` 規約に従い、`:error` は「XMILE として
  不正」、`:warn` は「XMILE としては正しいが xmile.execute v1 がまだ simulate できない」を意味する。
- **`xmile.execute`** — Euler/RK4 の固定ステップ数値積分シミュレータ。flow/aux 依存グラフを DFS で
  トポロジカルソートし、循環があれば（validate を経ていない場合の防御として）loud に throw する。

**v2 スコープアウト（sec 3.5.2 stochastic / sec 3.5.3 delay・smooth・trend / sec 3.7.2-3.7.3
conveyor・queue 輸送 / sec 4.5 配列 / sec 3.7.4 サブモデル・マクロ / sec 3.6 unit の次元解析）は
`xmile.expr/parse` と `xmile.xml` の round-trip では受理するが、`xmile.expr/eval-expr` と
`xmile.execute/run` は明確な ex-info を throw する** — サイレントに間違った値を返すことを避ける
（prolly-tree/nagare/swarm-choreo と同じ「documented honesty」方針）。

## Consequences

- `manifest/repos.edn` の `:extra-projects` に `orgs/kotoba-lang/org-oasis-open-xmile` を登録し、
  `nbb scripts/gen-west-manifest.cljs --entry org-oasis-open-xmile` で west.yml に最小 diff で反映する。
- `kotoba-lang/dsl-core` に `:git/sha` 依存する（`kotoba.dsl.problem` の検証結果規約を再利用するため、
  `statechart`/`states`/`sigma`/`policy` と同じ依存形）。
- 実際の `.xmile` ファイル読み込みには host 側で XML パーサ（JVM: `clojure.data.xml`、cljs: DOMParser 等）
  を用意する必要がある（本 repo は XML テキストの parse は行わない）。
- v2 項目（stochastic/delay/smooth ビルトイン、conveyor/queue 輸送、配列、サブモデル/マクロ、unit の
  次元解析）は本 ADR の範囲外。実装する場合は別途 follow-up とする。

## Verification

- `orgs/kotoba-lang/org-oasis-open-xmile` の `clojure -M:test` が 34 tests / 105 assertions all green、
  `clojure -M:lint`（clj-kondo）errors 0 / warnings 0。
- `gh repo create kotoba-lang/org-oasis-open-xmile --public` + push 済み。
- `manifest/repos.edn` の `:extra-projects` への登録 + `nbb scripts/gen-west-manifest.cljs --entry
  org-oasis-open-xmile` で最小 diff 生成、`--check` で canonical 一致を確認。

## 追記1（2026-07-07）: sec 3.5.3 DELAY1/DELAY3/SMTH1/SMTH3/TREND を隠し stock 化して実装

ユーザー指示「成熟度を向上」を受け、v2 スコープアウト項目のうち最も価値が高く仕様が明確な
sec 3.5.3（DELAY1/DELAY3/SMTH1/SMTH3/TREND）を実装した（`RANDOM`/`NORMAL` 等の stochastic 系、
`DELAY`/`DELAYN`/`SMTHN`/`FORCST`、conveyor/queue 輸送、配列、unit 次元解析は引き続き v2 のまま）。

- **`xmile.execute/desugar-delays`（新設）** — モデルの全equationを走査し、DELAY1/DELAY3/SMTH1/
  SMTH3/TREND の呼び出しを、自動生成された隠し stock/flow/aux への参照に書き換える正規化パスを
  `run` の先頭（既存の topo-order/initial-stocks/Euler-RK4 ループの前）で一度だけ実行する。隠し変数名は
  `:xmile/series` の出力には一切現れない（フィルタ済み）。
- 実装した ODE は OASIS 仕様書自体には明示式が無かったため、Vensim Reference Manual の
  「equivalent equations」（`fn_delay1.html`/`fn_delay3.html`/`fn_smooth3.html`/`fn_trend.html`）を
  一次資料として検証: DELAY1/SMTH1 = `dH/dt=(input-H)/T`、DELAY3/SMTH3 = T/3 ずつの3段カスケード
  （3段とも同一の初期条件を共有）、TREND の隠し Level = `INTEG((input-Level)/T, input/(1+g0*T))`、
  出力 = `(input-Level)/(T*Level)`。
- `xmile.expr/unsupported-builtins` から DELAY1/DELAY3/SMTH1/SMTH3/TREND を分離し
  `hidden-stock-builtins` として新設（`eval-expr` に直接渡された場合はモデルレベルの desugaring を
  経由していない旨の明確なエラーを投げる、という正しい別挙動を維持）。
- `xmile.validate` に `delay-smooth-problems`（引数個数2-3・delay/smoothing/averaging-time が
  リテラル定数の場合の正値チェック）を追加。`algebraic-loop-problems` は
  `A = DELAY1(B, 5)` / `B = A + 1` のような「隠し stock が介在する遅延結合」を不正な同tickループと
  誤検出しないよう、依存計算を `same-tick-free-vars` に切り替えて修正。
- 解析解による検証: DELAY1/SMTH1 のステップ応答（指数関数）、DELAY3 のステップ応答
  （Erlang-3、Sterman の material-delay理論と一致）+ 定常収束、TREND の「定数入力では0」+
  ランプ入力の厳密閉形式。
- 実装中に発見した実バグ: JVM Clojure の `/` は正確な `0.0/0.0` で `ArithmeticException` を投げる
  （`clojure.lang.Numbers.divide` は除数の `isZero` を先にチェックするため、プリミティブ double 除算とは
  挙動が異なる）。TREND の除算がこれを踏みやすい（入力が静止状態から始まる場合、非常によくある）ため、
  Vensim 自身の参照実装（`TREND=ZIDZ(...)`）と同じガードを、新しい式木ノード種を増やさず既存の
  `:if`/`:eq` で実装して修正。
- テスト: 53 tests / 184 assertions all green（+19 tests / +79 assertions）、lint errors 0/warnings 0。
- commit `19018e9d960bb95d6de2e50ebc918e428e532ddc`（`3930c70` から fast-forward）。
  `nbb scripts/gen-west-manifest.cljs --entry org-oasis-open-xmile` で pin 前進、サーバ側検証 OK。
- 未着手のまま: conveyor/queue 輸送（隠し stock の ODE とは根本的に異なる離散スラッグキュー方式が
  必要なため、中途半端な実装より v2 のまま維持する判断）、stochastic 系、配列、unit 次元解析、
  サブモデル/マクロ、rk2/rk45/gear。
