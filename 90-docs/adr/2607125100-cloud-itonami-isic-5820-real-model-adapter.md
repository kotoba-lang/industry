# ADR-2607125100: `cloud-itonami-isic-5820` に実LLM接続アダプタを配線(認証情報投入待ち)

- Status: Accepted (2026-07-12)
- 関連: ADR-2607124600、`orgs/gftdcojp/cloud-itonami`の`cloud_itonami.runtime`
  （`ITO_MODEL_*`規約の直接の手本）
- Scope: `/loop` 自己ペース継続タスク「成熟度を実運用まで」の第6サイクル

## Context

RevOps-LLM advisorはこれまで sealed/mock のみで、実モデルへ接続する
経路が存在しなかった。本サンドボックスにはモデルAPI認証情報が
一切無い(確認済み: `ANTHROPIC_API_KEY`/`OPENAI_API_KEY`等 env 無し)。
そのため「実際にモデル呼び出しを行う」ことはできないが、「認証情報を
後で投入すれば動くように配線し、無い間は正直にpreflightで報告する」
ところまでは前進できる。

同一lineage/orgの既存repo `orgs/gftdcojp/cloud-itonami`の
`cloud_itonami.runtime`が、この目的のための規約
（`ITO_MODEL_PROVIDER`/`_URL`/`_MODEL`/`_API_KEY`、`preflight`
関数）を既に確立しているため、それを直接の手本として同一形状で
`ISIC5820_`prefixに移植した。

## Decision

1. `src/crm/llm_realmodel.clj`(JVM-only、実HTTP呼び出しのため正当な
   compat層)を新設。`crm.llm.cljc`が既に持つ`llm-advisor`という
   汎用wrapper(`langchain.model/ChatModel`を`crm.llm/Advisor`
   protocolに包む仕組み)をそのまま利用——グラフ側の契約
   （`crm.operation/build`の`:advise`ノードが呼ぶ関数シグネチャ・
   期待する戻り値の形)は一切変更していない。
2. env var: `ISIC5820_MODEL_API_KEY`(唯一のトリガー——設定・非空なら
   実modelアドバイザーに切替、未設定ならsealed mockのまま)、
   `ISIC5820_MODEL_PROVIDER`(openai既定/anthropic/openclaw)、
   `ISIC5820_MODEL_URL`(openclaw必須)、`ISIC5820_MODEL`。
3. `preflight`関数: 実ネットワーク呼び出しを一切行わず、設定/欠落を
   正直に報告(APIキーの値自体は絶対にログ/表示しない、`:api-key?`
   booleanのみ)。
4. 実モデルAPIへの呼び出しロジックは`langchain.model`の既存
   `openai-model`/`anthropic-model`(既に汎用実装済み)にそのまま
   委譲——重複実装をしていない。
5. **検証範囲を明確に区別**: (a) `preflight`の全欠落パターン検証は
   完全に実施済み(認証情報不要)。(b) アダプタが送信するrequestの
   JSON形状・レスポンスのパース処理は、**実local http-kit stub
   サーバに対する本物のHTTPラウンドトリップ**(in-processのfakeでは
   ない)で検証済み。(c) **実モデルAPI(OpenAI/Anthropic/実際の
   OpenAI互換gateway)がこのrequest形状を受理するかどうかは、本
   サンドボックスには認証情報が存在しないため一切未検証**——これは
   このADRが明確に線引きし、過大な主張をしていない部分。
6. sealed mockが引き続きデフォルト(認証情報未設定時の既存挙動に
   回帰無し)であることも回帰テストで確認。

## Consequences

- (+) 実運用へのブロッカー#2(実LLM接続)が「配線が無い」状態から
  「配線済み、認証情報投入待ち」の状態まで前進した。
- (+) 同一lineageの既存規約(`ITO_MODEL_*`)を再利用し、独自の
  非互換な形を発明していない。
- (+) 63 tests/200 assertions、lint clean。stub serverによる実HTTP
  ラウンドトリップ検証まで実施。
- (-) 実モデルAPIとの疎通は依然として完全に未検証。オーナーが
  `ISIC5820_MODEL_API_KEY`等を投入した後、実際に動作するかは
  この時点では保証できない(honest scopeとして明記)。
- (-) `cloud-itonami-isic-6201`にはまだ同種のアダプタが無い。
- (-) `cloud-itonami-isic-6202`は直近も更新が入っている(別セッション
  活動中と判断)ため、本サイクルでも一切触れていない。

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| 実モデル呼び出しをfakeで「成功した」と主張するテストを書く | ❌ | fleetの「推測・捏造しない」規律に明確に反する。本当に検証できていないことを検証済みと偽ることになる |
| `langchain.model`のadapterを再実装する | ❌ | 既に汎用実装が存在し、テスト済み。車輪の再発明は不要かつバグ混入リスク |
| 認証情報が無いことを理由に本タスク自体を見送る | ❌ | 「配線」と「実接続」は分離可能。配線だけでも将来の認証情報投入時の作業を大幅に削減できる、価値のある前進 |

## References

- ADR-2607124600
- `orgs/gftdcojp/cloud-itonami/src/cloud_itonami/runtime.clj`
  （`ITO_MODEL_*`規約の直接の手本、read-onlyで参照のみ）
- `cloud-itonami-isic-5820/src/crm/llm_realmodel.clj`(新設)

## Verification Notes

- commit `f3c5fac`、push済み。63 tests / 200 assertions、lint clean。
