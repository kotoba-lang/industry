---
id: adr-2607061600-kotoba-issue-ledger-shared-libs
title: "ADR-2607061600: issue -> proposal(PR) -> review -> merge -> audit ループを設計し、cloud-itonami(B2B)/manimani(個人)で共通する部分を kotoba-lang/kotoba-issue-clj + kotoba-lang/kotoba-ledger-clj として抽出する"
status: accepted
doc_type: adr
topic: agent-loop
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - cloud-itonami の activity/effect/decision/audit ゲートと manimani の agent-run FSM を、issue/proposal(PR)/review/merge/audit 語彙の共通ライブラリへ昇格する設計判断
  - 共通ライブラリを目的別に kotoba-issue-clj(ゲート)/ kotoba-ledger-clj(台帳)の2つへ分割する判断(単一 grab-bag lib を却下)
  - 属性 namespace は強制統一せず、呼び出し側(cloud-itonami の :itonami.* 等)は既存スキーマを維持し境界でアダプトする方針
  - manimani 側は全 policy を propose→(risk 別 auto-merge or 人間 merge 待ち)→merge→execute→audit に通す(全方針を issue/PR 化)方針
related:
  - orgs/kotoba-lang/kotoba-issue-clj   # 新規: issue/proposal(PR)/review/merge/audit ゲート
  - orgs/kotoba-lang/kotoba-ledger-clj  # 新規: 汎用 append-only 台帳(file+git / kotobase backend)
  - orgs/gftdcojp/cloud-itonami         # Phase B: activity.cljc/approval.cljc をアダプタ化 + /effects API実装(未着手)
  - orgs/gftdcojp/local-manimani        # Phase C: agent.cljc/ledger.cljc をアダプタ化 + 全方針 issue/PR 化(未着手)
  - orgs/gftdcojp/cloud-manimani        # Phase D: /proposals /reviews /merge ルート追加(未着手)
  - 90-docs/adr/2606272330-cae-shared-libs-and-seeds.md  # 「目的別分割・grab-bag 禁止」の前例
supersedes: []
superseded_by: []
---

# ADR-2607061600: kotoba-issue-clj / kotoba-ledger-clj — cloud-itonami と manimani の共通ゲート/台帳

- Status: accepted(2026-07-06)。Phase A(2ライブラリの新規構築 + west 登録)完了。
  Phase B〜E(既存3リポへの配線)は別コミットで進行。

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

## 実装フェーズ(Phase A のみ本ADR時点で完了)

- **Phase A(完了)**: `kotoba-issue-clj`(16 tests / 32 assertions)、
  `kotoba-ledger-clj`(10 tests / 21 assertions)を新規 scaffold・push・
  `manifest/repos.edn` の `:extra-projects` に登録、
  `bb scripts/gen-west-manifest.bb --entry kotoba-issue-clj,kotoba-ledger-clj`
  で最小 diff 生成、pin 検証(`verify-west-pins.bb`)通過。
- **Phase B(未着手)**: cloud-itonami の `activity.cljc`/`approval.cljc` を
  `kotoba-issue-clj` 呼び出しのアダプタへ。`execute-approved!`→`merge!` へ改称。
  未実装だった `/api/{org}/{repo}/effects` `/effects/approve` を新規実装。
- **Phase C(未着手)**: manimani の `agent.cljc`/`ledger.cljc` をアダプタ化。
  全方針の propose→merge 化。babashka経路に `file-git` backend でgit commit追加。
- **Phase D(未着手)**: cloud-manimani の `routes.cljc` に
  `/proposals` `/reviews` `/proposals/<id>/merge` を追加。
- **Phase E(未着手)**: 実装確定後に本ADRを `closed` へ更新、
  `gen-west-manifest.bb --check` の最終確認。

## 却下案

- **単一 `kotoba-agent-clj` に集約**: CAE-libs 前例と同じ理由(ゲートと台帳は
  読み手・生存期間が違う)で却下。
- **cloud-itonami の `:itonami.*` を `:kotoba.issue.*` へ schema migration**:
  生きたコード(store/doctor/tests)への影響が大きく、メカニズムの一般化に
  スキーマ改名は不要。境界アダプタで十分。
- **manimani の TS(`server/`)・Rust(`tauri/`,`mobile/`)実装の統合**: `.cljc`
  は Node/Rust から直接呼べない。README 自身が babashka を正本・TSをdeprecated
  と明言しており、今回は babashka/CLJC面のみを対象にする(スコープ外、将来対応)。

## 検証(Phase A)

```
cd orgs/kotoba-lang/kotoba-issue-clj  && clojure -M:test   # 16 tests, 32 assertions, 0 failures
cd orgs/kotoba-lang/kotoba-ledger-clj && clojure -M:test   # 10 tests, 21 assertions, 0 failures
bb scripts/gen-west-manifest.bb --entry kotoba-issue-clj,kotoba-ledger-clj  # pin検証 OK、最小diff
```
