# ADR-2607115600: cloud-itonami-isic-8299 — 契約オペレータの制裁/PEPスクリーニングを cloud-itonami-isic-8291 経由で配線する

**Status**: accepted
**Date**: 2026-07-10
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

「既存 actor の相互接続を進める」というオーナー指示のもと、
`cloud-itonami-isic-8299`(VA/BPO タスクマッチング actor、TaskRouter-LLM ⊣
RoutingGovernor)と `cloud-itonami-isic-8291`(企業/コンプライアンス
インテリジェンス actor、Dossier-LLM ⊣ DisclosureGovernor)を実配線する
機会を選定した。8299 の `clearance-tier-gate` は operator が保持する
証明区分(HIPAA/PCI-DSS/SOC2等)のみを検証しており、operator 自身が
制裁/PEP リストに一致するかどうかは一切スクリーニングしていなかった —
これは実在の BPO/VA プラットフォームが本来負う与信・コンプライアンス
リスクの欠落である。

`cloud-itonami-isic-6910`(会社設立実行 actor)の `formation.corporate-
intel` が既に確立した「8291 の `:disclosure/screen-name` を governed
read として wholesale 消費する」パターン(ADR-2607110400 §5、
`:corporate-intelligence` optional-technology)を、8299 の operator
プールへ適用した。

## Decision

### 1. `bizsupport.screening`(新規、8291 への薄いクライアント)

`formation.corporate-intel` を1:1で写像。`:disclosure/screen-name` を
8291 の実 `OperationActor` に対して実行し、以下3種の結果を区別する
(どれも「クリア」に丸めない):

- `{:found? bool :hit? bool ...}` — 8291 governor が承認した確定結果
- `{:pending-human-review? true}` — 8291 側で escalate 中、確定するまで
  不確定として扱う
- `{:held? true :reason [..]}` — 8291 の DisclosureGovernor が本テナント
  の照会自体を拒否(契約/設定の問題、operator 側の欠陥ではない)

### 2. 新規 op `:operator/screen` + `sanctions-screening-gate`(HARD)

`bizsupport.llm/propose-screen` が `screen-fn`(operator名 → corporate-
intel 結果、`bizsupport.screening/screen` をそのまま注入可能、アダプタ
不要)を呼び、結果を `:screening-verdict-set` レコードとして提案する。
`bizsupport.policy` に新規 HARD チェック `sanctions-screening-gate` を
追加: store 上の operator の直近スクリーニング verdict が `:hit` なら、
`:task/assign` は確信度・保持証明区分に関わらず無条件で拒否する。

> **単一不変条件の拡張**: TaskRouter-LLM は、RoutingGovernor が拒否する
> 割当・開示・紛争解決を決して行わない — 制裁/PEP hit の operator への
> 割当も、この不変条件の対象に加わった。

### 3. Optional・注入可能(必須依存にしない)

`bizsupport.screening` を require するのは `bizsupport.screening`
自身のみ — `bizsupport.{store,policy,llm,operation}` のコアは
コンパイル時に一切依存しない。`(llm/mock-advisor)` (引数無し)は
これまで通り no-op で、8299 は 8291 無しでも完全にスタンドアロン/
オフラインで動作する。`deps.edn` の `:deps` に `:local/root` classpath
エントリを追加するだけで(それ自体は I/O ではない)、`kotoba-lang/
securities` → `cloud-itonami-isic-6311` の配線(ADR-2607111600)と
同じ隔離パターン。

### 4. テスト実証(実 unmocked 8291 actor 統合)

`test/bizsupport/screening_wiring_test.clj`。demo operator `op-300` の
名前を意図的に 8291 のデモ制裁フラグ付き official(`of-2`)と一致させ、
「配線無しではローカルチェックのみで静かに :clear になる」→
「実 8291 actor を配線すると :hit を検出し、二度と :clear にならない」
ことをエンドツーエンドで証明する(`cloud-itonami-isic-6910` の
`formation.corporate-intel-test` と同型の証明構造)。

### 5. 副産物: `cloud-itonami-isic-8291` の deps.edn 修正

8291 は今セッションで langgraph-clj → langgraph へのリネームが未修正
だった初期テンプレートで、8299 からの依存追加によりビルドが実際に壊れる
ことが判明した。`io.github.com-junkawasaki/langgraph-clj` →
`io.github.kotoba-lang/langgraph` へ修正(isic-6311 で確立済みの修正を
適用)。`.github/workflows/ci.yml` は同じ修正が必要だが、この session の
`gh` トークンに `workflow` OAuth scope が無く push できず未修正のまま
残した(CI は org 側で無効化されている可能性が高く、低優先度)。

## Consequences

- (+) `cloud-itonami-isic-8299`: 36 tests / 139 assertions(旧32/127か
  ら)、0 failures。`clojure -M:lint`: エラー0・警告0。
- (+) `cloud-itonami-isic-8291`: deps.edn 修正、70 tests / 257
  assertions、0 failures(挙動変更なし、依存パス修正のみ)。
- (+) `:corporate-intelligence` wholesale パターンの2件目の実消費者
  (`cloud-itonami-isic-6910` に続く)。
- (-) `.github/workflows/ci.yml` の同修正は OAuth scope 不足で未着手
  (フォローアップ)。

## References

- `orgs/cloud-itonami/cloud-itonami-isic-8299/README.md`
  「Consuming cloud-itonami-isic-8291 for operator screening」節
- `orgs/cloud-itonami/cloud-itonami-isic-6910/src/formation/corporate_intel.cljc`
  (直接の手本)
- `90-docs/adr/2607110400-cloud-itonami-isic-8291-corporate-compliance-intelligence-actor.md`
  (`:corporate-intelligence` wholesale パターンの原型)
- `90-docs/adr/2607115300-cloud-itonami-isic-8299-bpo-task-matching-actor.md`
  (8299 本体の設計 ADR)
