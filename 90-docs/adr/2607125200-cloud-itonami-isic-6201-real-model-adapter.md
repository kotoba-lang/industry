# ADR-2607125200: `cloud-itonami-isic-6201` に実LLM接続アダプタを配線(5820とのパリティ)

- Status: Accepted (2026-07-12)
- 関連: ADR-2607125100（`cloud-itonami-isic-5820`の実LLMアダプタ、直接の手本）
- Scope: `/loop` 自己ペース継続タスク「成熟度を実運用まで」の第7サイクル

## Context

前サイクルで5820にRevOps-LLMの実モデルアダプタを配線した。同じ能力を
Marketing(6201)にも揃える。

## Decision

1. `src/marketing/llm_realmodel.clj`を新設。5820の`crm.llm-realmodel`を
   直接の手本とし、同一設計(config解決・`preflight`・JDK`HttpClient`
   ベースの`:http-fn`・`langchain.model`への委譲)を、このactor自身の
   env var prefix(`ISIC6201_MODEL_PROVIDER`/`_URL`/`_MODEL`/
   `_API_KEY`)で踏襲。
2. `marketing.llm.cljc`は5820の`crm.llm.cljc`と同じ
   `llm-advisor`-wraps-any-`ChatModel`の仕組みを持つことを確認済み
   （構造的な逸脱なし）。唯一のドメイン差異: このactorのproposal形状は
   常に`:source nil`(このactorに情報源開示ゲートが存在しないため、
   `marketing.llm`自身のdocstringに明記された既存仕様)——アダプタと
   テストはこれを尊重し、5820のような`:source`マップを捏造していない。
3. `marketing.http`に`resolve-advisor!`/`describe-advisor-mode`を追加
   （`marketing.operation/build`は既に`:advisor`optを受け付けていた
   ため、operation.cljc自体の変更は不要）。
4. 検証範囲は5820と同じ厳密さで区別: `preflight`全パターン検証済み、
   実local http-kit stub serverへの本物のHTTPラウンドトリップ検証済み、
   実モデルAPIとの疎通は完全に未検証(このサンドボックスに認証情報
   無し)。

## Consequences

- (+) Sales(5820)・Marketing(6201)の両方で実LLM接続の配線が完了し、
  認証情報投入待ちの状態が揃った。
- (+) `:source nil`という既存のドメイン差異を尊重し、5820の形状を
  機械的にコピーしなかった。
- (+) 71 tests / 233 assertions、lint clean。
- (-) 実モデルAPIとの疎通は依然完全に未検証。
- (-) 6202は引き続き対象外。

## Alternatives considered

5820(ADR-2607125100)と同一の理由・却下パターンにつき、そちらを参照。

## References

- ADR-2607125100（`cloud-itonami-isic-5820`の実LLMアダプタ、直接の手本）
- `cloud-itonami-isic-6201/src/marketing/llm_realmodel.clj`(新設)

## Verification Notes

- commit `83ee36f`、push済み。71 tests / 233 assertions、lint clean。
