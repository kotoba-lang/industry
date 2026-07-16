# ADR-2607161745: cloud-itonami flagship (6399/6310/7810) — Managed Starter tier を Stripe live 化

**Status**: accepted
**Date**: 2026-07-16
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)
**Scope**: `orgs/cloud-itonami/cloud-itonami-isic-6399`、`orgs/cloud-itonami/cloud-itonami-isic-6310`、
`orgs/cloud-itonami/cloud-itonami-isic-7810`（Stripe live account のみ、各 repo のコードは
docs 更新のみで billing 実装は追加していない — 下記「Consequences」参照）
**Builds on**: ADR-2607161620（cloud-itonami hr-management の同型 Stripe go-live 手順、secret
配線の流儀を踏襲）、`90-docs/pricing-intelligence/`（isic-6399 run `pricing-intel-20260716-02`、
isic-7810 run `pricing-intel-20260716-03` の競合価格調査）
**Related**: `90-docs/business/cloud-itonami-vertical-maturity.md` priority #2
（「6399 / 6310 (+7810) の product→business を優先」）、ADR-2607161930（同日の
6399/6310 aozora.app distribution actor — 本 ADR とは別レバー、非衝突）

## Context

3 フラッグシップ（Indeed 型 6399・kaonavi 型 6310・準フラッグシップ 7810）はいずれも
maturity 表で Business=1（"no paid tenant"）のまま止まっていた。`business-model.md` の
価格表はいずれも「illustrative」（6399/6310）または数字自体が無い（7810、プローズのみ）
状態で、たとえ operator リードが実際に転換意思を示しても、請求できる実際の Stripe
オブジェクトが一つも存在しなかった——ADR-2607161620 が isco-1212 で発見した
「secrets が 1Password にある ＝ 本番で使える、ではない」と同型のギャップが、
この 3 vertical では「そもそも Price オブジェクト自体が存在しない」というもう一段
手前の形で存在していた。

この 3 vertical のビジネスモデルは AGPL fork/self-host が前提で、実際の課金は
「operator が自分の顧客に課金する」か「gftdcojp 自身が managed operator として
運営する」のどちらかで発生する（`docs/business-model.md` の trust ladder参照）。
本 ADR は後者——gftdcojp 自身が今すぐ managed hosting を売れる状態にする——を
Stripe live account 上に用意する。

## Decision

### 1. 単価は pricing-intelligence の実競合調査を根拠に決定

- 6399: 6 社の実競合（Madgex $500+/mo・JobBoard.io $449-649/mo・JBoard $249-849/mo・
  WP Job Manager 無料+add-on・Adicio 非公開・engage 無料+チケット制)を調査、
  換算(~¥150/$)で ¥37k-127k+/月 という実勢を確認 —
  business-model.md の既存 illustrative 帯 ¥50k-150k/月 は妥当と判断（改定不要）。
- 7810: 4 社の実競合（Crelate $119/user/mo・JobAdder ~$99-160/user/mo・Zoho Recruit
  Staffing版 $25-75/recruiter/mo・Bullhorn ~$99-315/user/mo、いずれも per-seat）を調査、
  3-5名の小規模 agency の実支出が ¥22k-90k/月に収まることを確認。7810 には元々
  価格の数字が一切なかったため、これが初めての具体的な価格帯提案（¥60k-150k/月、
  flat・seat数無制限）。
- 3 vertical とも「¥50k-150k/月」という同一レンジに収まったため、初回の
  Managed Starter tier 単価を **¥80,000/月**（3 vertical 共通、JPY 建て
  `unit_amount=80000`）に統一した。3 プロダクトで別々の場当たり的な数字にせず、
  portfolio 内の一貫した managed-tenant 価格点にする（ADR-2607161620 が
  LLM call ~$0.01・storage ~$0.13/GiB という portfolio 内相場観を作ったのと同じ狙い）。
  どの vertical も `kernels.billing-cap/default-max-unit-amount` 相当の上限を
  大きく下回る。

### 2. Recurring Price（Billing Meter 不要）— flat 月額、使用量非連動

isco-1212 の 2 meter（LLM proposal 従量・storage 従量）と異なり、この 3 vertical の
現行の課金モデルは「seat 数・投稿数に関わらず flat な managed hosting 料金」
（business-model.md の「Managed community board / Managed agency」）なので、
Billing Meter は作成せず単純な `recurring[interval]=month` の Price のみを作成した。

作成した live オブジェクト（Stripe API、`gftdcojp/Stripe Live API Keys` の
`STRIPE_SECRET_KEY` で認証、Idempotency-Key 付き — 詳細値は 1Password
`gftdcojp/cloud-itonami flagship Managed-tier Stripe Payment Links` item を参照）:

| Vertical | Product | Price (¥80,000/月) | Payment Link |
|---|---|---|---|
| cloud-itonami-isic-6399 | "Managed Job Board (Starter)" | metered ではない flat recurring | Stripe-hosted checkout URL |
| cloud-itonami-isic-6310 | "Managed Talent Board (Starter)" | 同上 | 同上 |
| cloud-itonami-isic-7810 | "Managed Placement Desk (Starter)" | 同上 | 同上 |

### 3. コードを一切変更せず Stripe Payment Link（no-code hosted checkout）で運用

3 vertical はいずれも静的 GitHub Pages サイト（nbb 生成の actor デモ）であり、
itonami.cloud のような Cloudflare Pages Functions バックエンドを持たない
——`create-checkout-session!`（`edge/billing.cljc`）のような自前の checkout
session 生成コードをこの 3 repo に追加するのは、architecture 上の越境になる
（3D/kami-engine 規則と同型の「既存インフラを消費するだけで、個別 app 用に
新しいインフラを生やさない」という設計判断）。代わりに Stripe の no-code
Payment Link を使い、各 repo の `docs/business-model.md` にリンクを掲載するだけで
即座に使える状態にした。将来 itonami.cloud 側で checkout session 生成を
これら 3 vertical にも一般化したくなった場合（ADR-0024 の `"per-seat"` パターンの
拡張）は、その時点で `edge/billing_endpoints.cljc` の `product-catalog` に
エントリを足す形で移行できる——Payment Link はそれまでの暫定ではなく、
現行アーキテクチャに最も自然に収まる最終形として選んだ。

### 4. secret 配線 — 値をチャット/ログに一切出さない

`op read "op://gftdcojp/Stripe Live API Keys/STRIPE_SECRET_KEY"` をシェル変数に
一度だけ読み込み、以後の全 curl 呼び出しは変数参照のみ（verbose curl 不使用、
値を echo/print したことは一度もない）。結果の Product/Price ID と Payment Link
URL（これら自体は秘密情報ではないが、参照性のため）を新規 1Password item
`gftdcojp/cloud-itonami flagship Managed-tier Stripe Payment Links` にまとめて保管。

## Consequences

- 3 vertical とも、operator リードが managed hosting を希望した場合に
  即座に提示できる実際の支払いリンクができた——以前は「価格表はあるが
  実際に課金する手段がゼロ」だった。
- Business score を 1→2 に更新（`itonami.cloud cockpit` 行が
  「Stripe live-wired, no paid org yet」で 2 と採点されている既存の
  precedent と同一基準——billing infra が live であることの部分点であり、
  「External tenant, billing, hyp validation」を全部満たしたわけではない
  ので 3 以上にはしない。捏造ゼロ原則: 実際の paid org が 0 のままである
  ことは note に明記した）。
- 単価をポートフォリオ横断で ¥80,000/月に統一したことで、今後この3vertical
  以外の cloud-itonami managed tier を検討する際の参照点ができた。

## Verification Notes

2026-07-16 実施:

- Stripe API 呼び出し（product×3・price×3・payment_link×3、計9回）は全て
  レスポンスの `id`/`url` フィールドの存在を確認して成功と判定、9回とも成功。
- 生成された Payment Link URL 3本を `docs/business-model.md` に掲載
  （各 repo で個別 commit・push・fast-forward sync 済み）。
- **未検証のまま残る**: 実際に誰かがこの Payment Link から checkout を
  完了した実績はゼロ（当然、公開直後のため）。webhook 受信は無し
  （Payment Link は itonami.cloud の Stripe webhook と同じ account を
  共有するため、`checkout.session.completed` イベント自体は届くが、
  この 3 vertical には受信側のハンドラが無い——支払いの成立自体は
  Stripe 側で完結するので事業上は問題ないが、支払い後の「tenant を
  managed として有効化する」というオペレーション側のフォローアップは
  現状 100% 手動）。

## Next steps

1. 実際に operator-interest issue 経由でリードが managed tier を希望したら、
   該当 repo の Payment Link を direct に提示する（現状はこれが唯一の
   fulfillment 経路——自動化されたオンボーディングは無い）。
2. 初めて外部 paid org が 1 件でも成立したら、maturity 表の Business を
   2→3 以上に進める判断材料にする（`docs/stripe-billing-setup.md` の
   「After it is live」節と同じ基準を this 3 vertical にも適用）。
3. Payment Link 経由の支払い成立を自動で検知したくなった場合
   （現状は手動確認のみ）は、itonami.cloud 側 Stripe webhook に
   この 3 vertical の `product` metadata を見て tenant を有効化する
   分岐を足す — ADR-0024 の `product-catalog` パターンの拡張として。
