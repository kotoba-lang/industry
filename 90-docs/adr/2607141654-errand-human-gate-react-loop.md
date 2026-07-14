# ADR-2607141654: errand — human gate の next-action を agent react loop に定型化する

**Status**: accepted, implemented (closing 2026-07-14 — kyoninka.errand + license-loop、運転は ADR-2607141753 M0/M1)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki

## Context

kyoninka（ADR-2607141620）の許認可手続きは、行政書士相談・JW 講習予約・
本籍地書類取り寄せ・窓口実値確認のような **human gate の next-action** を提案
するが、その先 —「agent が準備し、人間が外界に触れ、その証拠で台帳が進む」—
の進行管理が散文のチャットのままでは、停滞検知も証跡も効かない。
オーナー指示: これらを他の agent react loop と同様に定型化し、チャットで
process 進行管理できるように統合する。

前提となる既存資産（ゼロから作らない）:
- **langgraph-clj StateGraph の control plane が同型で 6 個**稼働中:
  teian（deck-LLM ⊣ BriefingGovernor）/ denrei（post-LLM ⊣ MembershipGovernor）/
  koyomi（schedule-LLM ⊣ ComplianceGovernor）/ tayori（reply-LLM ⊣
  ComplianceGovernor）/ kekkai / goyoukiki。全て **propose → draft only、
  外界への送信・接触は必ず人間**という規約を共有する。
- 進行の正本は **git 管理の append-only EDN ledger**（BMC canvas-ledger /
  design-quality ledger / kyoninka 台帳 / itad-license-ledger と同型）。
  Datomic 互換 datoms を DataScript に transact して query する（cljs 第一。
  Datomic 本体 = JVM は compat 降格であり本設計では使わない）。
- cloud-itonami は business-ops actor として approval / business-governor /
  langchain / langgraph / 上記 control plane 群への依存を既に持つ。

## Decision — 新概念は「errand（お使い）」1 つだけ

### 1. errand の定義（mechanism: kotoba-lang/kyoninka に置く）

human gate の step に、**何を起案し、何が返れば完了か**を data として付ける:

```clojure
{:step/id :book-jw-course
 :step/requires-human true
 :step/errand
 {:kind :book-course        ;; 初期 4 種(下表)
  :draft-via :koyomi        ;; 起案をどの既存 control plane に委譲するか
  :evidence-schema          ;; ★核心 — step 完了に必要な証拠の形
  {:provider :keyword :course :string :date :iso-date
   :attendee :string :confirmation-no :string}}}
```

| kind | 用途 | draft-via | evidence の要点 |
|---|---|---|---|
| `:consult-professional` | 行政書士・弁護士相談 | tayori（相談依頼文面） | 事務所・日付・`:legal-questions` ごとの結論 |
| `:book-course` | 講習・研修の予約 | koyomi（候補日程） | 日程・受講者・予約番号 |
| `:collect-documents` | 書類の取り寄せ | —（checklist がそのまま進捗表） | document-id ごとの取得日 |
| `:verify-authority-info` | 官庁窓口の実値確認 | tayori（質問リスト） | 確認済み実値（手数料・期間）・出所・確認日 |

evidence の検証は **kyoninka の純関数**（`kyoninka.errand`）: チャット返信の
パース結果を evidence-schema に照合し、**合格した evidence を伴うイベントだけが
step を完了できる**。`:verify-authority-info` の evidence は手続きテンプレートを
書き換えず、**「この case ではこの値を確認済み」という台帳イベント**として持つ
（テンプレートの `:verify :unverified` は次の case でも生きる）。

errand 自体の状態機械:
`:proposed → :sent → :awaiting-evidence → :validated → :done`（+ `:stale` /
`:declined`）。`:sent`（実際に人間へ届ける）以降への遷移は human gate。

### 2. react loop（operation: cloud-itonami の license-loop）

```
Observe  git ledger replay(kyoninka/cloud-itonami.license) → 未完了 step の errand 抽出
Propose  langchain で起案 — 相談文面/候補日程/質問リストの draft(既存 plane の型で draft-only)
Govern   LicenseGovernor: 官庁・士業・支払いに触れる action は draft 止まり(送信は人間)
Chat     draft を日次 stand-up でオーナーへ提示。オーナーが実行し、結果をチャットで返す
Record   返信を langchain 構造化出力でパース → kyoninka.errand の evidence 検証 →
         合格なら :case/step-done(:human-approved true + :evidence)を ledger に
         append → git commit(= 監査証跡・agent 間同期)
Nudge    :awaiting-evidence が N 日超過で :stale → stand-up に再掲
```

パース結果を無検証で信じない: evidence-schema 不合格の返信は**聞き返す**
（不足フィールドを具体的に）。これが「チャットで進行管理」の品質の要。

### 3. チャット面（3: 日次 stand-up routine）

claude.ai の scheduled routine（cloud agent）が日次で: ledger replay → 進行
サマリ + 今日の提案（draft 添付）+ stale 一覧を 1 メッセージに整形して提示。
オーナーの返信（自由文）を evidence パース → 検証 → 台帳 append → git push まで
routine 内で行う（**append は human 返信という明示承認に基づくので human gate を
満たす**。ledger の遡及編集は禁止のまま）。itad worker の LINE push は補助通知。

### 4. 配置（3 層、ADR-2607141550 と同型）

| 層 | 場所 | 追加物 |
|---|---|---|
| mechanism | kotoba-lang/kyoninka | `:step/errand` data + `kyoninka.errand`（検証・状態機械の純関数） |
| 起案部品 | 既存 teian/tayori/koyomi/denrei | 追加なし（`:draft-via` で委譲） |
| orchestrator + chat | cloud-itonami | `cloud_itonami/license_loop.cljc`（StateGraph）+ 日次 stand-up routine |

新 repo は作らない。errand は license 専用ではなく汎用（今後の BMC 実験・
営業 follow-up 等の human next-action にも同じ型を使える）だが、**初期実装は
kyoninka のスコープに置き、2 個目の消費者が現れた時点で切り出しを再評価**する
（premature extraction をしない）。

## Consequences

- (+) 「人間がやったかどうか」が evidence 付き台帳イベントになり、停滞（:stale）が
  機械的に見える。チャットが承認 UI そのものになる。
- (+) 既存 6 control plane の規約（draft-only・human send）と完全整合 — Governor の
  監査面で新しい例外を作らない。
- (−) evidence パースは LLM 依存 — 検証純関数が最後の砦（schema 不合格は必ず聞き返す）。
- (−) routine が git push まで行うため、ledger ファイルは routine と手元セッションで
  競合しうる — append-only + 行単位 merge で実害は小さいが、衝突時は再 append で解決。

## Follow-ups

1. errand の 2 個目の消費者（BMC lean loop の human 実験、営業 follow-up）が
   現れたら kotoba-lang への切り出しを再評価。
2. LINE 返信の直接パース（現状は claude.ai chat が主、LINE は通知のみ）。
3. :verify-authority-info の evidence が集まったら kyoninka テンプレートの
   標準値改訂を PR として提案（テンプレート更新も human gate）。
