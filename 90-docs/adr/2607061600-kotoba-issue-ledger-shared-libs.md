---
id: adr-2607061600-kotoba-issue-ledger-shared-libs
title: "ADR-2607061600: issue -> proposal(PR) -> review -> merge -> audit ループを設計し、cloud-itonami(B2B)/manimani(個人)で共通する部分を kotoba-lang/kotoba-issue-clj + kotoba-lang/kotoba-ledger-clj として抽出する"
status: closed
closed: "2026-07-06"
doc_type: adr
topic: agent-loop
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - cloud-itonami の activity/effect/decision/audit ゲートと manimani の agent-run FSM を、issue/proposal(PR)/review/merge/audit 語彙の共通ライブラリへ昇格する設計判断
  - 共通ライブラリを目的別に kotoba-issue-clj(ゲート)/ kotoba-ledger-clj(台帳)の2つへ分割する判断(単一 grab-bag lib を却下)
  - 属性 namespace は強制統一せず、呼び出し側(cloud-itonami の :itonami.* 等)は既存スキーマを維持し境界でアダプトする方針
  - manimani 側は全 policy を propose→(risk 別 auto-merge or 人間 merge 待ち)→merge→execute→audit に通す(全方針を issue/PR 化)方針。ただし agent.cljc/ledger.cljc/tool.cljc は不変のまま、runner.clj 実行層への**付加レイヤ**として実装(全面書き換えではない、実装中の方針転換)
  - cloud-itonami の `/effects` `/effects/approve` Cloudflare Functions API 実装はスコープ外とする判断(理由: CLJS edge test 実行環境が未整備 + 本番自動 deploy hook のリスク)
related:
  - orgs/kotoba-lang/kotoba-issue-clj   # issue/proposal(PR)/review/merge/audit ゲート
  - orgs/kotoba-lang/kotoba-ledger-clj  # 汎用 append-only 台帳(file+git / kotobase backend)
  - orgs/gftdcojp/cloud-itonami         # Phase B: activity.cljc/approval.cljc の語彙採用(merge!/request-changes!/rationale)
  - orgs/gftdcojp/local-manimani        # Phase C: 付加ゲート層(manimani.gate/manimani.issue-store)
  - orgs/gftdcojp/cloud-manimani        # Phase D: /proposals /reviews /proposals/<id>/merge ルート追加
  - 90-docs/adr/2606272330-cae-shared-libs-and-seeds.md  # 「目的別分割・grab-bag 禁止」の前例
supersedes: []
superseded_by: []
---

# ADR-2607061600: kotoba-issue-clj / kotoba-ledger-clj — cloud-itonami と manimani の共通ゲート/台帳

- Status: closed(2026-07-06)。Phase A〜D 全て完了、実装・テスト・main 着地済み。

## 課題

`cloud-itonami`(B2B)には `:itonami.activity → :itonami.effect → :itonami.decision
→ :itonami.audit` という propose→approve→execute→audit ゲートが既に実装済み
(`activity.cljc`/`approval.cljc`、`store/db-api` 越しに storage-agnostic)。だが
これを叩く `/effects` `/effects/approve` API は存在しない。`manimani`(個人)には
`plan→act→observe→{done|error|awaiting-approval|cancelled}` の純粋 agent-run FSM
(`agent.cljc`)と host非依存の ledger 投影(`ledger.cljc`)が既にあるが、通常の
decision は承認ゲートを経ず即実行され、decision の書き込みが TS/babashka/Rust
desktop/Rust mobile の4実装に分裂している。

両者は独立に「store 注入・遷移はデータ・全遷移で監査追記」という同じ型へ収斂して
いながら、一度も接続されず、GitHub の Issue/PR という語彙も使われていなかった。

## 決定

### 1. 2つの新規ライブラリを `kotoba-lang` org に作る(単一 lib へ集約しない)

`2606272330-cae-shared-libs-and-seeds.md` の「目的・タイプごとに分割し grab-bag に
しない」前例に従う:

- **`kotoba-issue-clj`** — issue(triage対象) / proposal(PRに相当。risk と
  human-readable な rationale=diff を持つ) / review(verdict ∈ approve/reject/
  request-changes) / merge(承認済み proposal を handler で実行 = 「PRのmergeが
  actionを起動する」の実体) / audit。`cloud_itonami.activity/approval` と
  `manimani.agent` を一般化。
- **`kotoba-ledger-clj`** — 同じイベント語彙の append-only 投影(JSONL codec +
  `run-status`/`supervisor-snapshot`)。`manimani.ledger` を一般化し、
  babashka経路に欠けていた git-commit-per-decision(`file-git` backend)と
  kotobase(CID-pinned commit)backend を追加。ゲートとは別パッケージ(読み手が
  違う・生存期間が違う)だが、コード依存はせず語彙(フィールド形)だけ共有する。

### 2. 属性 namespace は強制統一しない

`cloud-itonami` の `:itonami.*` は生きたスキーマ(store/doctor/testsで使用中)
なので、ライブラリ側は `:kotoba.issue.*` を既定語彙としつつ、
`IssueStore`(`kind` は呼び出し側が選ぶ partition keyword)を name-agnostic に
設計。`cloud-itonami` は既存スキーマを保ったまま境界でアダプトし、schema
migration を強制しない。`manimani` は既存の Datomic 的スキーマを持たないため
`:kotoba.issue.*` をそのまま採用する。

### 3. manimani 側は全方針を issue/PR 化する

ユーザー判断により、`todo`/`waiting`/`done`/`archive`/`snooze` も含め全 policy が
propose→merge を通る。risk tier(read-only は auto-merge、external-send 等は
人間の merge 待ち)は同一 runner tick 内で解決するため、低リスク項目の体感速度は
現状(1キーで確定)を維持しつつ、`proposed`→`merged` の2イベントとして監査に残る。

## 実装フェーズ(全完了)

- **Phase A**: `kotoba-issue-clj`(16 tests / 32 assertions)、
  `kotoba-ledger-clj`(10 tests / 21 assertions)を新規 scaffold・push・
  `manifest/repos.edn` の `:extra-projects` に登録、west pin検証通過。
- **Phase B**: cloud-itonami の `activity.cljc`/`approval.cljc` を、
  kotoba-issue-clj の語彙を採用する形で更新(`execute-approved!`→`merge!`
  改称、`request-changes!` 追加、`:itonami.effect/rationale` フィールド追加)。
  スキーマ(`:itonami.*`)自体は不変 — schema/doctor/tests 30ファイル超が
  依存しており migration は対象外(下記「却下案」)。**`/effects`
  `/effects/approve` Cloudflare Functions API の新規実装はスコープ外に
  縮小**(下記「実装中の方針転換」)。408 tests / 2880 assertions green。
- **Phase C**: manimani に新規 `manimani.gate`/`manimani.issue-store` を追加し、
  `runner.clj` の実行ループに配線。**当初計画(agent.cljc/ledger.cljc 自体を
  issue/proposal 語彙へ全面書き換え)から、既存 agent.cljc/ledger.cljc/
  tool.cljc を一切変更しない付加レイヤ方式へ転換**(下記「実装中の方針転換」)。
  34 tests / 244 assertions green(既存25件は無影響のまま)。実装中に
  kotoba-ledger-clj 自体のバグ(`append-line!` が親ディレクトリを作成しない)
  を発見し、ライブラリ側を修正・re-push・pin前進した。
- **Phase D**: cloud-manimani の `routes.cljc` に `POST/GET /proposals`、
  `POST/GET /reviews`、`POST /proposals/<id>/merge` を追加(既存 `/decisions`
  と同じ read/write-only パターン、実行機能は無し)。7 tests / 44 assertions green。
- **Phase E(本更新)**: 実装確定を受けて本ADRを `closed` へ更新。

## 実装中の方針転換(当初計画からの逸脱、理由付き)

計画時点の想定より実装がリスクが高いと判明した2箇所で、実装前にユーザーに確認の
上スコープを調整した:

1. **cloud-itonami の `/effects` API 実装を見送った。** `functions/api/`
   配下は CLJS(Cloudflare Pages Functions、shadow-cljs `:edge-api` ビルド)で
   動作し、JVM 側の `kotoba.issue.gate` とは別ランタイム。かつリポ直下の
   `package.json` が未コミットで CLJS edge test(`cljs.test`)が実行不能、
   さらに `lefthook.yml` の `post-checkout`/`post-merge` フックが main 到達時に
   実際に Cloudflare Pages へ自動デプロイする(GitHub Actions 無効化の代替措置。
   本ADR実装中に実際に1回発火し、`de347d7e.cloud-itonami.pages.dev` へ
   デプロイされたことを確認済み — 中身はJVM側のみの変更で edge 側は無変更
   だったため実質的な差分は無かった)。**テスト不能なコードを自動本番デプロイの
   ある経路に載せるのは責任を持てないと判断し**、JVM側の語彙採用(merge!/
   request-changes!/rationale)のみを Phase B のスコープとした。
2. **manimani(local-manimani)を全面書き換えでなく付加レイヤにした。**
   `agent.cljc`/`ledger.cljc` の現在のデータ形を直接検査するテストが
   `test/manimani/core_test.clj` に700行規模で存在(`agent-fsm`、
   `arxiv-agent-session`、`agent-run-status-projection` 等)し、さらに
   `manimani.tool`(ADR-0019)に MCP ツール呼び出し用の**別の**risk/承認ゲートが
   既に実装済みだった。agent.cljc の FSM 自体を issue/proposal 語彙へ書き換えると
   700行のテストが大量に壊れるリスクが高く、日常使用中の個人ツールでの回帰は
   許容できないため、`runner.clj` の実行(`drive`)ループに新規 `manimani.gate`/
   `manimani.issue-store` を**追加**する方式に変更(ユーザー確認済み)。
   結果として agent.cljc/ledger.cljc/tool.cljc は0変更、700行の既存テストは
   完全に無影響のまま、全 policy が propose→(risk別 auto-merge or 人間 merge
   待ち)→merge→execute→audit を通るという当初の目的自体は達成している。

## 却下案

- **単一 `kotoba-agent-clj` に集約**: CAE-libs 前例と同じ理由(ゲートと台帳は
  読み手・生存期間が違う)で却下。
- **cloud-itonami の `:itonami.*` を `:kotoba.issue.*` へ schema migration**:
  生きたコード(`facts.cljc`/`mail.cljc`/`funding.cljc`/`doctor.cljc`/
  `marketing.cljc`/`kotoba.cljc`/`agent.cljc`/`runtime.cljc`/coscientist等
  30ファイル超 + 14+ test ファイル)への影響が大きく、メカニズムの一般化に
  スキーマ改名は不要。境界(activity.cljc/approval.cljc)での語彙採用で十分。
- **manimani の TS(`server/`)・Rust(`tauri/`,`mobile/`)実装の統合**: `.cljc`
  は Node/Rust から直接呼べない。README 自身が babashka を正本・TSをdeprecated
  と明言しており、今回は babashka/CLJC面のみを対象にする(スコープ外、将来対応)。
- **cloud-itonami の `/effects` API を未テストのまま実装**: 上記「実装中の
  方針転換」#1参照。将来 CLJS edge test 環境(shadow-cljs + node test runner)
  が整備された時点で改めて着手する。
- **manimani.tool(ADR-0019)の既存ゲートを本ゲートに統合/置換**: 別レイヤ
  (MCPツール呼び出し内 vs outer effect emission)・別語彙で責務が異なるため
  triage対象外。両ゲートは独立に共存する。

## 検証(全Phase)

```
# Phase A
cd orgs/kotoba-lang/kotoba-issue-clj  && clojure -M:test   # 16 tests, 32 assertions, 0 failures
cd orgs/kotoba-lang/kotoba-ledger-clj && clojure -M:test   # 10 tests(修正後11), 21(22)assertions, 0 failures

# Phase B
cd orgs/gftdcojp/cloud-itonami && clojure -M:test          # 408 tests, 2880 assertions, 0 failures

# Phase C
cd orgs/gftdcojp/local-manimani && clojure -M:test         # 34 tests, 244 assertions, 0 failures
# (triage/triage.cljs の golden-set精度は diff 対象外のため無影響 — policy.cljc/
#  agent.cljc/ledger.cljc/tool.cljc は本変更で1行も触れていない)

# Phase D
cd orgs/gftdcojp/cloud-manimani && clojure -M:test         # 7 tests, 44 assertions, 0 failures

nbb scripts/gen-west-manifest.cljs --entry <name>  # 各リポの pin前進、都度検証OK
```
