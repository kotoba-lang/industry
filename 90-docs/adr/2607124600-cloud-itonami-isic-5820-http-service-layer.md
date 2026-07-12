# ADR-2607124600: `cloud-itonami-isic-5820` に最小HTTPサービス層を追加(実運用に向けた第一歩)

- Status: Accepted (2026-07-12)
- 関連: ADR-2607121900、ADR-2607122400
- Scope: `/loop` による自己ペース継続タスク「成熟度を実運用まで」の第1サイクル

## Context

3本柱(Sales/Marketing/Service)+ダッシュボード分析が揃った時点で、成熟度を
5段階評価で問われ「②ブループリントの上位〜③MVP入口」と回答した。ギャップの
筆頭は「UIも実行可能なサービス面も無く、ライブラリとしてしか動かせない」
ことだった。オーナーから `/loop 30min 成熟度を実運用まで` の指示があり、
30分サイクルの自己ペースで具体的な一歩ずつ実運用に近づける方針で継続する。

## Decision

1. `cloud-itonami-isic-5820` に `src/crm/http.clj`(JVM-only、意図的な
   compat層 — HTTPサーバbindingはkotoba wasm/clojurewasm/cljs/nbbのいずれ
   にもportableな一次プリミティブが無いインフラ結線であり、CLAUDE.mdの
   runtime優先順位における正当なcompat層扱い)を新設。
2. エンドポイント: `GET /`(no auth）、`GET /health`(no auth、store
   到達性確認)、`POST /propose`(bearer auth、既存の`crm.operation`
   StateGraphへの薄いHTTPアダプタ。governanceロジックはHTTP層で一切
   再実装しない)、`GET /dashboard`(bearer auth、既存
   `crm.policy/check`の`:pipeline/dashboard-query` RBACをそのまま経由、
   バイパスしない)。
3. 認証はbearer token、`$ISIC5820_API_TOKEN`から起動時に読む。
   **fail-closed**: token未設定/空なら`start-server!`は例外、`-main`は
   サーバを一切起動せずexit 1。デフォルトtokenやauth無効化経路は無い。
4. `:escalate`(human-in-the-loop)結果は隠さず`202`+thread-id+理由を
   返す。承認/却下を提出するHTTPエンドポイントは今回のスコープ外と
   明記(`docs/api.md`)。
5. 依存追加: `http-kit 2.8.1`、`ring-core 1.15.3`、`data.json 2.5.2`
   (いずれも解決確認済み)。`:serve` alias追加。
6. `docs/api.md`で honest scope を明記: 単一プロセス・単一テナント・
   TLS終端なし・rate limitingなし。`-main`は`crm.store/seed-db`
   （デモデータ）を使うため**プロセス再起動でstateが失われる**ことも
   明記——実データ永続化は次サイクルの課題として残す。
7. テスト: 実サーバをephemeral portで起動し、実HTTPリクエストで検証。
   45 tests / 158 assertions(既存37/137から8 tests/21 assertions追加)、
   lint clean。

## Consequences

- (+) `cloud-itonami-isic-5820`が初めて「ライブラリ」から「起動可能な
  サービス」になった。ネットワーク越しに propose/dashboard を呼べる。
- (+) 既存のgovernance/RBACロジックを一切バイパスせず、HTTP層は薄い
  adapterに徹した——単一不変条件（governorが拒否するものをRevOps-LLMは
  決して行わない）はHTTP経路でも保たれる。
- (+) fail-closed認証設計により「認証無効のまま起動してしまう」事故を
  構造的に防止。
- (-) 依然として実運用には未到達: 実LLM未接続(advisorは封じ込め/sim)、
  永続store未接続(`-main`は毎回seed-dbから起動)、TLS/reverse
  proxy/observability/rate limiting無し、実デプロイ無し、6201/6202には
  同種の層が未追加。
- (-) 認証比較(`=`によるtoken一致判定)はconstant-timeではなく、
  timing attackへの理論的な脆弱性が残る——honest-scope文書にも明記
  していないため、次サイクルでの改善候補として記録する。

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| 重量フレームワーク(Pedestal/Reitit等)を導入 | ❌ | 現段階で必要な機能はシンプルなroutingのみ。http-kit + ring-coreの薄い構成で十分、依存を最小に保つ |
| HTTP層にgovernanceロジックを一部複製 | ❌ | 単一不変条件（governorのみが拒否権を持つ）が経路によって別実装になるのは危険。既存グラフへの薄いadapterに徹する |
| 認証を後回しにして一旦オープンで起動 | ❌ | fail-open設計は事故の元。最初から fail-closed を選択 |

## References

- ADR-2607121900、ADR-2607122400
- `cloud-itonami-isic-5820/docs/api.md`(新設)
- `cloud-itonami-isic-5820/docs/adr/0001-architecture.md`

## Verification Notes

- commit `c776fdfa`(HTTP層追加)、push済み。45 tests / 158 assertions、
  lint clean。
