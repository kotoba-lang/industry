# ADR-2607161930: cloud-itonami 6399/6310 — 初回 distribution actor を aozora.app に作成・投稿

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-6399`、`orgs/cloud-itonami/cloud-itonami-isic-6310`
**Builds on**: ADR-2607031400（"one blueprint = one actor" 規約、app-aozora 投稿の先例）、
ADR-2607122300（flagship maturity sprint）
**Related**: ADR-2607161620（同日の cloud-itonami Stripe billing go-live —
「実施→ADR/maturity記録」という本セッションの作業パターンの直接の先例）

## Context

`cloud-itonami-vertical-maturity.md` の priority #1（6399/6310 flagship の
product→business 推進）に対し、paid path（Stripe）以外で効く非paid施策として
distribution（YC bench軸、portfolio全体で2/5と低評価）を選んだ。両flagshipとも
demo/pitchは整っていたが、実際の外部配信は**一度も行われていなかった**
（README/business-model.mdはあるが、HN/X/social投稿の形跡ゼロ、実データ連携ゼロ）。

初期案はShow HN 2本 + X 2本の下書きだったが、オーナー判断で「HN/Xは不要、
aozora.app（自社運営のAT-Protocol PDS/AppView）にrepo単位でactorを作って投稿」
に変更された。

## Decision

### 1. 6399・6310 それぞれに専用actorを新規作成（ADR-2607031400の規約を踏襲)

既存の"one blueprint = one actor"規約（itonami games、kawaraban outlet mirror、
cloud-itonami media actorが先例）をそのまま適用し、flagship 2本にそれぞれ
専用のaozora.app identityを新規発行した:

| Repo | Handle | DID |
|---|---|---|
| cloud-itonami-isic-6310 | `cloud-itonami-isic-6310.aozora.app` | `did:key:z6MkusTmHGc9uQG7LXcjVtFedHS3UFkPqcZRj879zQ4FKCdA` |
| cloud-itonami-isic-6399 | `cloud-itonami-isic-6399.aozora.app` | `did:key:z6Mkty5UHsqeGVagjydUWHMSYE46R7MCZqzzwXtm4hqqoxCW` |

秘密鍵（Ed25519 seed）は各repo内 `.cloud-itonami-isic-{6310,6399}-actor/identity.json`
に保存、`.gitignore`に追記済み（**コミットしていない**、kotoba-server慣例の
`.{actor}/identity.*`パターンを踏襲）。1Password/kagiへのミラーは未実施
（マーケティング用途で紛失時の再発行コストが低いため今回は見送り、必要なら
後で追加できる）。

### 2. 既存の `cloud-itonami.media.cacao`/`aozora` (.clj) を再実装せず、nbb で新規に薄い一回性スクリプトを書いた

`cloud-itonami.media.*` はkawaraban由来の"proven"なCACAO/AT-Proto実装だが
(1) JVM専用 (.clj) で、cloud-itonamiのフルdeps.edn（多数のlocal/root依存）解決が
重い、(2) "media"（AI生成ニュースdigest業務）専用namespaceであり、無関係な
プロモーション投稿に転用するのは業務ロジックの誤用になる。そのため、同じ
CACAO(SIWE+CBOR)/did:key/createAccount/createRecordロジックをNode組込みの
`crypto`モジュール（Ed25519 keygen/sign対応、Node 26で確認済み）を使い**nbbで
新規に薄いスクリプト**として書いた（`.cljc`/`.kotoba`ランタイム優先順位に従い、
新規スクリプトはnbbを優先。JVM専用ライブラリ自体は書き直していない——別物を
新規に書いた、という整理）。

base58btc（did:key導出に必要）はBigInt演算がcljs/nbbのquot/modと噛み合わず、
標準的なbyte-array long-division実装に切替。"Hello World!" → "2NEpo7TZRRrLZSi2U"
という既知のbase58 test vectorで実装を検証してから本番PDSに対して実行した。

### 3. 投稿内容は事前にオーナーレビュー済みの下書きをベースに簡潔化

Show HN/X向けに用意した下書き（`saas-catalog-ledger.edn`の`hcm-hris`カテゴリ
実データ13社に基づく、特定1社に絞らない競合フレーミング — オーナーから
「kaonaviのような特定SaaS名を主眼にせず、世界の競合を網羅的に」という
フィードバックを受けて改訂済みだったもの）を、300 grapheme制限
（`app.bsky.feed.post`）に収まる長さに整理して転用した。

## Consequences

- 6399/6310 は初めて自社ネットワーク（aozora.app）上に公開profile+投稿を持つ。
  ただしaozora.appは**自社運営の小規模PDS**であり、Bluesky本体やHN/Xのような
  既存の大規模外部ネットワークではない——実際の外部リーチ・フォロワー・
  コンバージョンは今回ゼロ（測定もしていない）。**捏造ゼロ原則に従い、
  portfolio-facts の `distribution`（現状2/5）や vertical-maturity の
  `business`（現状1、no paid tenant）は今回変更しない**——これらの軸の定義
  （real external reach / external tenant）を今回のアクションはまだ満たして
  いないため。下記「次に確認すること」に測定タスクとして明記する。
- HN/X投稿は行わなかった（下書きは温存、オーナー判断で保留）。将来必要なら
  このADRの下書き参照先（セッション記録）から再利用できる。
- 継続投稿の仕組み化（cron等）はスコープ外——今回は各1件のみの単発投稿。

## Verification Notes

2026-07-16 実施:

- `com.atproto.server.createAccount` → 200、両actorともDID発行確認。
- `app.bsky.actor.profile`（displayName+description）、`app.bsky.feed.post`
  （本文）をそれぞれcreateRecord → 200。
- `com.atproto.repo.getRecord`で両profile・両postを実際に読み返し、投稿内容が
  正しく保存されていることを確認（テキスト・URLとも一致）。
- `com.atproto.identity.resolveHandle`で両handleがDIDに正しく解決することを確認。

## 次に確認すること（未実施、スコープ外として明記）

- aozora.app上でのprofile/postの実際の閲覧・反応（このPDSの現状の外部到達性
  次第では、実質的にゼロリーチの可能性が高い——次のdistribution施策を検討する
  際は、まずaozora.appの現在のactive user規模を確認してから投資判断をする）。
- 秘密鍵の1Password/kagiミラー。
- HN/X投稿を実施する場合は、この ADR の下書き（セッション記録）をベースに
  別途オーナー承認を得てから。
