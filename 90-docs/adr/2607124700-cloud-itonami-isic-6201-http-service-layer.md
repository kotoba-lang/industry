# ADR-2607124700: `cloud-itonami-isic-6201` に最小HTTPサービス層を追加(5820とのパリティ)

- Status: Accepted (2026-07-12)
- 関連: ADR-2607124600（`cloud-itonami-isic-5820`のHTTP層、直接の手本）
- Scope: `/loop` 自己ペース継続タスク「成熟度を実運用まで」の第2サイクル

## Context

前サイクルで `cloud-itonami-isic-5820` に最小HTTPサービス層を追加した。
Sales/Marketing/Serviceの3本柱のうちMarketing（6201）にも同じ能力を
揃え、パリティを確保する。

## Decision

1. `cloud-itonami-isic-6201` に `src/marketing/http.clj` を追加。5820の
   `crm.http` を直接の手本とし、依存(http-kit 2.8.1/ring-core 1.15.3/
   data.json 2.5.2)・fail-closed bearer認証設計・namespaced-keyword JSON
   処理を同じ方式で踏襲。
2. エンドポイントはこのactor自身のドメインに合わせて設計:
   `GET /`・`GET /health`(no auth）、`POST /send`
   （`:campaign/send-message`、consent-revoked-send-gate/double-send-gate
   のholdを正直に表面化）、`POST /advance-stage`
   （`:lead/advance-stage`、stage-sequence-gateのholdを表面化）、
   `POST /update-score`（`:lead/update-score`、lead-score-mismatchの
   escalateを5820の`:escalate`と同じ202パターンで表面化）、
   `GET /dashboard`（`marketing.dashboard/snapshot`自身のRBACゲートを
   そのまま経由、403マッピング）。
3. 5820とのテンプレートからの意図的な逸脱(バグではなく設計判断、ADRに
   明記): (a) 汎用`/propose`ではなく3本の書き込みエンドポイント
   （このactorは`:op`が3種の固定値を取るため）、(b) `/dashboard`は
   `marketing.dashboard/snapshot`が自身でRBACを内包しているため
   `policy/check`を別途呼ばない、(c) `year`/`month`パラメータなし
   （このactorにASC606相当の日付スレッディングが無いため）。
4. fail-closed認証は`$ISIC6201_API_TOKEN`で5820と同じ設計。
5. テスト: 実サーバをephemeral portで起動し実HTTPで検証。52 tests /
   188 assertions（既存38/148から14 tests/40 assertions追加）、lint
   clean。実装中に自分のテストコード側の不備(2件: contextフィールド
   欠落によるrbacホールド誤判定、test-order依存のcontact ID再利用)を
   発見・修正——`marketing.operation`/`policy`/`dashboard`側に既存バグは
   無し。
6. `docs/api.md`で honest scope 明記。`-main`は`marketing.store/seed-db`
   （デモデータ、再起動でstate消失）を使う点も5820と同様に明記。

## Consequences

- (+) Sales(5820)・Marketing(6201)の両方が「起動可能なサービス」に
  なり、パリティが取れた。同じ運用パターン(bearer token、ephemeral
  demo store)で扱える。
- (+) このactor固有の3書き込みop設計は、5820の汎用`/propose`パターンを
  機械的にコピーせず、ドメインに合わせて正しく適応された。
- (-) Service(6202、別セッション実装)にはまだHTTP層が無い。5820同様、
  永続store・実LLM・TLS/observability・実デプロイは未達。
- (-) 5820と同じ既知の課題(bearer token比較が constant-time でない)を
  このactorも引き継いでいる——次サイクルでの改善候補。

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| 5820の`/propose`汎用エンドポイントをそのまま踏襲 | ❌ | このactorのop体系(3種の固定op)には合わない。ドメインに合わせて3エンドポイントに分けるのが正しい適応 |
| `/dashboard`で`policy/check`を再度呼ぶ(5820と同じ形) | ❌ | `marketing.dashboard/snapshot`が既に自身のRBACを内包しており、二重チェックは冗長かつ将来の乖離リスクを生む |

## References

- ADR-2607124600（`cloud-itonami-isic-5820`のHTTP層、直接の手本）
- `cloud-itonami-isic-6201/docs/api.md`(新設)

## Verification Notes

- commit `def26c7`、push済み。52 tests / 188 assertions、lint clean。
