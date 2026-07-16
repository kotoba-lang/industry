---
id: adr-2607072630-etzhayyim-com-etzhayyim-yosoku-system-dynamics-actor
title: "ADR-2607072630: etzhayyim/com-etzhayyim-yosoku — System Dynamics（XMILE）を使う governed シナリオ・シミュレーション actor を新設"
status: accepted
doc_type: adr
topic: etzhayyim-com-etzhayyim-yosoku-system-dynamics-actor
authoritative: true
last_verified: 2026-07-07
authoritative_for:
  - "etzhayyim における System Dynamics（`kotoba-lang/org-oasis-open-xmile`）を用いた governed シナリオ・シミュレーション actor は `etzhayyim/com-etzhayyim-yosoku`（予測）を正本とする"
  - "actor の構造は既存3例（robotaxi-actor ⊣ SafetyGovernor / gftd-talent-actor ⊣ PolicyGovernor / cloud-itonami ⊣ CertGovernor）と同型の「封じ込め + 独立 governor + 不変台帳」パターンに揃える — 具体的には `gftdcojp/gftd-talent-actor` を実地に読んで構造テンプレートとした（`robotaxi-actor` は探索したが到達不能だったため直接参照できず、本ADRで既知のギャップとして記録する）"
  - "ScenarioGovernor は advisor の自己申告を信用せず、提案された XMILE モデル/シナリオパッチに対して独立に `xmile.validate`/`xmile.execute` を再実行してから commit/hold/escalate を判定する"
  - "v1 は mock advisor のみ（実LLM配線は follow-up、`kotoba-lang/kessai` の mock adapter 先例と同様）。RAD identity 台帳（etzhayyim/root の 80-data/kotoba-rad/*.identity.journal.edn）への登録は本ADRでは行わない（本 repo は app-aozora への publish surface を持たないため DID/manifest.jsonld 規約がそのまま当てはまらないと agent が判断、正式な要否は follow-up として残す）"
related:
  - 90-docs/adr/2607072350-kotoba-lang-org-oasis-open-xmile-system-dynamics.md
  - 90-docs/adr/2607072500-kotoba-lang-oasis-w3-omg-standards-batch.md
supersedes: []
superseded_by: []
---

# ADR-2607072630: etzhayyim/com-etzhayyim-yosoku — System Dynamics（XMILE）を使う governed シナリオ・シミュレーション actor を新設

**Status**: accepted
**Date**: 2026-07-07
**Deciders**: Jun Kawasaki（ユーザー指示「etzhayyim actor は systemdynamics を使うように」。対象 actor を尋ねたところ「新規 actor を1つ起こす」と回答）

## Context

- ユーザー指示は「system dynamics を使う etzhayyim actor」だったが、etzhayyim には既存 actor が約180個あり（`com-etzhayyim-itonami`/`-talent`/`-junkan`/`-mitooshi`/`-seigyo` 等）、system dynamics との自然な対応が一意に決まらなかったため、ユーザーに確認した上で新規 actor を1つ起こす方針とした。
- 新規 actor は `com-etzhayyim-yosoku`（予測 = forecast/prediction）と命名した。既存 `com-etzhayyim-mitooshi`（見通し = outlook）が既に存在するため別の語を選んだ。System Dynamics モデルによる政策/介入シナリオの governed シミュレーションという用途に「予測」は意味的に適合する。
- 実装は背景 agent に委譲し、本体（superproject）の manifest/ADR 反映はしないよう指示した上で、agent 自身に kotoba-lang/kotoba-junkawasaki 内の既存 actor 実例（robotaxi-actor / gftd-talent-actor / cloud-itonami）を実地に調査し構造を模倣するよう指示した。
- agent の調査結果: `gftdcojp/gftd-talent-actor` は superproject にローカルチェックアウト済みで実際に読めた（deps.edn・StateGraph配線・governor・store・testすべて）。`gftdcojp/cloud-itonami` はより大規模なため governor 部分のみ参照。**`robotaxi-actor` は com-junkawasaki/gftdcojp/kawasakijun いずれの org にも見つからず、到達不能だった** — gftd-talent-actor 自身のドキュメント/コメントは繰り返し robotaxi-actor を「兄弟/由来」として言及しているにも関わらず、実体が見当たらない。これは本ADRで既知のギャップとして記録し、後続セッションでの確認事項とする（private repo として別 org に存在する可能性、あるいは名称が変わった可能性がある）。

## Decision

`etzhayyim/com-etzhayyim-yosoku` を新設。構造は `gftdcojp/gftd-talent-actor` を実地に読んで忠実に模倣した:

- **deps.edn**: `io.github.com-junkawasaki/langgraph-clj {:local/root ...}` + `:dev` alias での `langchain-clj` override、`:test`/`:lint` alias は gftd-talent-actor と同一形。
- **StateGraph**:
  ```
  intake → propose(SD-Advisor) → govern(ScenarioGovernor) → decide ─┬ commit ──▶ END
                                                                     ├ escalate ─▶ request-approval [interrupt-before]
                                                                     │              resume ─▶ commit | hold
                                                                     └ hold ─────▶ END
  ```
- **Store**: `MemStore` + `DatomicStore`、`langchain.db` の `:db-api`（`{:q :transact! :db :pull :entid}`）経由でのみ backend と会話。contract test で両実装の等価性を確認。
- **ドメイン固有部分（新規設計）**: `models.cljc`（XMILEモデル/シナリオパッチのラッパー）、`scenario.cljc`、ScenarioGovernor のルール群（下表）。gftd-talent-actor との重要な違い: governor が advisor の自己申告（`:cites` 等）を単にスキャンするのではなく、**実際に `xmile.validate`/`xmile.execute` を独立に再実行**して構造妥当性・実行可能性・出力の妥当性を判定する（システムダイナミクスは数値シミュレーションが可能なドメインなので、この一段深い検証が可能かつ必要）。

### ScenarioGovernor のルール（すべて提案内容から導出、advisor 自己申告は信用しない）

| ルール | 深刻度 | トリガー |
|---|---|---|
| `:structural-invalid` / `:not-simulatable` | HARD | `xmile.validate` の `:error`、または `xmile.execute/run` が throw する `:warn` |
| `:unknown-variable` | HARD | シナリオパッチが base model に無い変数を参照 |
| `:protected-variable` | HARD | パッチが `ComplianceFloor`/`RegulatoryCap` に触れる |
| `:parameter-bound` | soft→escalate | 数値 eqn が相対100%超で変化 |
| `:model-replace` | soft→escalate | 既登録 model-id への `:model/propose` |
| `:implausible-output` | soft→escalate | **実際にシミュレートした結果**が `1.0e6` 超または非有限 |
| `:low-confidence` | soft→escalate | advisor confidence < 0.6 |
| `:execution-error` | HARD（fail-safe） | 構造検証を通過したのにシミュレータが例外を投げた場合 |

しきい値（100%/1e6/0.6）と protected-variable 集合は v1 の例示的デフォルトであり、実運用のリスク許容度から導出したものではない — 実運用投入前にデプロイ設定可能にするのが望ましい follow-up。

## Consequences

- `manifest/repos.edn` の `:extra-projects` に `orgs/etzhayyim/com-etzhayyim-yosoku` を登録、`nbb scripts/gen-west-manifest.cljs --entry com-etzhayyim-yosoku` で最小diff反映（pinはサーバ側検証OK）。
- **RAD identity 台帳への登録は本ADRの範囲外** — etzhayyim/root の `80-data/kotoba-rad/com-etzhayyim-yosoku.identity.journal.edn`（または同等）への `:rad/repo`/`:rad/did-web`/署名参照の登録は行っていない。agent の判断（この actor は app-aozora へ publish しないため DID 規約がそのまま当てはまらない）を採用したが、CLAUDE.md の Actors 節が一般に要求する完了条件（RAD registration）を満たしていない状態であることは明記しておく。実際に必要かどうかは、この actor を app-aozora 以外の文脈でどう使うか次第であり、follow-up で判断する。
- `robotaxi-actor` が到達不能だった件は、kotoba-lang エコシステムのドキュメント（gftd-talent-actor 自身のコメント含む）が参照する実体が実際には存在しない/見つからないという整合性ギャップであり、別途確認が必要。
- v1 は mock advisor のみ。実 LLM 配線・RBAC・phase rollout は follow-up。

## Verification

- `orgs/etzhayyim/com-etzhayyim-yosoku` の `clojure -M:dev:test` が 39 tests / 129 assertions all green、`clj-kondo` errors 0/warnings 0。
- GitHub Actions CI（`lint`/`test` 両ジョブ）が実リポジトリで green（sibling-checkout 方式での langgraph-clj/langchain-clj 解決を含め、ローカルだけでなく CI 環境でも動作確認済み）。
- `clojure -M:dev:run` のデモが commit/structural-hold/commit/protected-var-hold/escalate→approve→commit の5パスすべてを正しく通ることを確認。
- `gh repo create etzhayyim/com-etzhayyim-yosoku --public` + push 済み（commit `1a7a8dd554979bc857c78e30d66d0721073c194b`）。
- `manifest/repos.edn` 登録 + `nbb scripts/gen-west-manifest.cljs --entry com-etzhayyim-yosoku` で最小diff生成、pin検証OK。
