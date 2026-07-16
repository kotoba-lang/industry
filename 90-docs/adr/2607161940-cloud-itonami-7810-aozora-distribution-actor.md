# ADR-2607161940: cloud-itonami 7810 — aozora.app distribution actor を6399/6310とparityで追加

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-7810`
**Builds on**: ADR-2607161930（cloud-itonami 6399/6310 aozora.app distribution actors —
同一手順の直接の先例）、ADR-2607031400（"one blueprint = one actor"規約、app-aozora
投稿の先例）、ADR-2607122300（flagship maturity sprint）
**Related**: ADR-2607161745（6399/6310/7810 flagship Managed-tier Stripe Payment Links —
7810のbusinessスコア2の既存根拠）

## Context

cloud-itonami の3 flagship（6399・6310・7810）のうち、ADR-2607161930で6399/6310には
aozora.app（自社運営のAT-Protocol PDS/AppView）専用の配信actor（profile + 1件の
promo post）が作成済みだったが、**7810だけこれが無い**、という具体的なcoverage gap
があった。7810は`cloud-itonami-vertical-maturity.md`の product-score 4（6399/6310の5に
次ぐ"near-flagship"）で、demo/quickstart/pricingは整っていたが、6399/6310と同様に
実際の外部配信は一度も行われていなかった。

本ADRはADR-2607161930の判断（HN/X投稿は不要、aozora.appのみでよい、下書きは
kaonaviのような特定SaaS名を主眼にせず世界の競合を網羅的に扱う）をそのまま踏襲し、
7810にも同一の手順を適用してparityを取る。

## Decision

### 1. 7810専用actorを新規作成（ADR-2607031400の規約を踏襲、6399/6310と同一パターン）

| Repo | Handle | DID |
|---|---|---|
| cloud-itonami-isic-7810 | `cloud-itonami-isic-7810.aozora.app` | `did:key:z6MknJa9V4eZyXzRFbte7aBtxm6WXomoSHkw3cTNoH4QdEYx` |

秘密鍵（Ed25519 seed）はrepo内 `.cloud-itonami-isic-7810-actor/identity.json` に保存、
`.gitignore`に追記済み（**コミットしていない**、6399/6310と同一の
`.{actor}/identity.*`パターン）。1Password/kagiへのミラーは6399/6310と同じ理由
（マーケティング用途で紛失時の再発行コストが低い）で今回も見送った。

### 2. 既存の `cloud-itonami.media.cacao`/`aozora` (.clj) は再利用せず、ADR-2607161930で
書いたnbbスクリプトをそのまま7810に適用

Node組込み`crypto`モジュール（Ed25519 keygen/sign）を使ったCACAO(SIWE+CBOR)/
did:key/createAccount/createRecordのnbbスクリプトは6399/6310で既に実装・検証済み
だったため、新規実装はせずそのまま7810に適用した。base58btc（did:key導出に必要）の
byte-array long-division実装は、本ADR実行前に独立して"Hello World!" →
"2NEpo7TZRRrLZSi2U"という既知のtest vectorで再検証し（PASS）、さらに7810のDIDを
公開鍵から独立に再導出して`com.atproto.identity.resolveHandle`が返すDIDと一致する
ことを確認してから本ADRの記録を作成した。

初回投稿は300 grapheme制限（`app.bsky.feed.post`）を超過していたため、その投稿
レコードを削除し288 codepointの短縮版で作り直した（本番PDSへの実書き込みでの
一回性ミスであり、隠さず記録する）。最終的にrepo上には重複のない1件の投稿
レコードのみが残っていることを`com.atproto.repo.listRecords`で確認済み。

### 3. 投稿内容は7810の実際のdocs/README内容から新規に書き起こし

6399/6310と異なり7810には事前にオーナーレビュー済みの下書きが無かったため、
`orgs/cloud-itonami/cloud-itonami-isic-7810/docs/business-model.md`（4競合
comparable — Crelate/JobAdder/Zoho Recruit/Bullhornに基づく市場アンカー済み
pricing）と`README.md`（本セッションで追加済みのStripe Payment Link・
operator-interest CTA）の内容から、特定1社に絞らず世界の競合カテゴリを示す形で
新規に書いた（ADR-2607161930でオーナーから受けた「kaonaviのような特定SaaS名を
主眼にせず、世界の競合を網羅的に」というフィードバックと同じ精神）。誇張・
未検証の数値（外部反応など）は一切含めていない。

- **profile description**: "Open-source ATS/agency-CRM you self-host or get
  managed — candidate intake, matching, placement with work-authorization +
  anti-discrimination gates enforced by an independent governor, all
  audit-logged. Part of the cloud-itonami open-business fleet.
  github.com/gftdcojp/cloud-itonami-isic-7810"
- **post** (288 codepoints): "Open-source ATS/agency-CRM (the
  Crelate/JobAdder/Zoho Recruit/Bullhorn category), self-hosted or managed.
  An independent governor blocks discriminatory matching + unverified
  work-auth placements; every action is audit-logged. Demo:
  https://cloud-itonami.github.io/cloud-itonami-isic-7810/"

## Consequences

- 7810はこれで6399/6310と同じく自社ネットワーク（aozora.app）上に公開
  profile+投稿を持ち、3 flagship間のdistribution actor coverageのgapが解消された。
- ADR-2607161930と同じ理由で、**今回のアクションだけではportfolio-facts の
  `distribution`やvertical-maturityの`business`スコアは変更しない**——aozora.appは
  自社運営の小規模PDSであり、実際の外部リーチ・フォロワー・コンバージョンは
  今回もゼロ（測定もしていない）。7810のbusinessスコア2は引き続き
  ADR-2607161745（Stripe Payment Link）のみを根拠とする。
- HN/X投稿は行わない（ADR-2607161930のスコープ判断を継承）。継続投稿の仕組み化
  （cron等）もスコープ外——単発1件のみ。

## Verification Notes

2026-07-16 実施（前回ADR-2607161930と同じ4項目）:

- `com.atproto.server.createAccount` → DID発行、`com.atproto.identity.resolveHandle`
  で`cloud-itonami-isic-7810.aozora.app`が上記DIDに正しく解決することを確認。
- `app.bsky.actor.profile`（displayName="cloud-itonami-isic-7810"+description）を
  createRecord → `com.atproto.repo.getRecord`(collection=app.bsky.actor.profile,
  rkey=self)で読み返し、保存内容がdraftと一致することを確認。
- `app.bsky.feed.post`（本文、288 codepoints）をcreateRecord →
  `com.atproto.repo.getRecord`(collection=app.bsky.feed.post,
  rkey=1784196999341)で読み返し、text/createdAtとも一致することを確認。
  `com.atproto.repo.listRecords`で該当collectionに重複レコードが無いことも確認済み
  （over-limitだった初回投稿は削除済み）。
- did:key導出（base58btc long-division実装）を"Hello World!" →
  "2NEpo7TZRRrLZSi2U"の既知test vectorで独立に再検証（PASS）、かつ7810の公開鍵
  から独立に再導出したDIDが実際に`resolveHandle`が返すDIDと一致することを確認。

## 次に確認すること（未実施、スコープ外として明記。ADR-2607161930と同一）

- aozora.app上でのprofile/postの実際の閲覧・反応（自社小規模PDSのため実質的に
  ゼロリーチの可能性が高い）。
- 秘密鍵の1Password/kagiミラー。
- HN/X投稿を実施する場合は、別途オーナー承認を得てから。
