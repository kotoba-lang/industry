# ADR-2607125000: `cloud-itonami-isic-5820`/`6201` の bearer token 比較を constant-time 化

- Status: Accepted (2026-07-12)
- 関連: ADR-2607124600(既知の課題として本項目を記録)
- Scope: `/loop` 自己ペース継続タスク「成熟度を実運用まで」の第5サイクル

## Context

ADR-2607124600でHTTP層追加時、bearer token比較が`=`（plain string
equality）であり、timing attackの理論的な脆弱性が既知の課題として
残されていた。本サイクルでこれを解消した。

## Decision

`src/crm/http.clj`(5820)・`src/marketing/http.clj`(6201)双方の
`authorized?`内部比較を、`java.security.MessageDigest/isEqual`
（UTF-8バイト列比較、標準的なconstant-time比較。新規依存不要）に
置き換えた。JDK(Temurin 21、確認済み)では長さ非依存のconstant-time
挙動が保証されているため、長さの事前チェックを追加していない
（追加すると小さいながら新たなtiming leakを生むため）。関数の契約
（boolean返却、non-blank token必須、`Bearer`ヘッダ必須）は変更なし
——純粋な内部強化。

各repoに振る舞いテスト(タイミング測定ではなく、異なる長さ/同じ長さ
異なる内容のtokenが正しく拒否されることの確認)を追加。

## Consequences

- (+) ADR-2607124600で記録した既知のtiming attack懸念を解消。
- (+) 標準ライブラリのみで実現、新規依存なし。
- (+) 5820: 50 tests/175 assertions、6201: 58 tests/207 assertions、
  両方lint clean。

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| 自前のconstant-time比較関数を書く | ❌ | `MessageDigest/isEqual`という枯れた標準実装がある。車輪の再発明は不要かつバグ混入リスク |
| 長さの事前チェックを追加してから比較 | ❌ | JDKのisEqualは長さ非依存でconstant-time。事前チェックはむしろ小さいtiming leakを新設する |

## References

- ADR-2607124600

## Verification Notes

- `cloud-itonami-isic-5820`: commit `ac1cb62`、push済み。
- `cloud-itonami-isic-6201`: commit `f91f8fe`、push済み。
