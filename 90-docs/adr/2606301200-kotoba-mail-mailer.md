---
id: adr-2606301200-kotoba-mail-mailer
title: "ADR-2606301200: kotoba-lang/mail と mailer を email substrate として設計する"
status: proposed
doc_type: adr
topic: kotoba-email
authoritative: true
last_verified: 2026-06-30
authoritative_for:
  - kotoba-lang の email/MIME/draft/send-effect/receipt の責務分担
  - SMTP/Resend/SES を host capability として扱う境界
  - business activity の :external-send と mail/send effect の接続
related:
  - 90-docs/adr/2606271700-cloud-itonami-business-os.md
  - orgs/etzhayyim/com-etzhayyim-abuse/CLAUDE.md
  - orgs/kotoba-lang/mail
  - orgs/kotoba-lang/mailer
supersedes: []
superseded_by: []
---

# ADR-2606301200: kotoba-lang/mail + mailer

## Decision

Email は 2 repo に分ける。

- `kotoba-lang/mail`: 宛先、message、draft、send-effect、receipt の純 `.cljc` model。
- `kotoba-lang/mailer`: approved `mail/send` effect を provider request に射影する `.cljc` adapter contract。

SMTP server / Resend / AWS SES / DNS / secrets / retry は portable lib の責務ではない。
それらは aiueos/host capability として注入し、`mail.receipt` へ結果を戻す。

```text
mail draft -> approval -> mail/send effect -> mailer request -> host provider -> receipt
```

## Capability Boundary

Provider ごとの既定 capability:

| provider | required capabilities |
|---|---|
| Resend | `mail/send`, `net/fetch`, `secrets/get:resend-api-key` |
| SMTP | `mail/send`, `smtp/send`, `secrets/get:smtp-credentials` |
| SES | `mail/send`, `aws/ses-send`, `secrets/get:aws-credentials` |

`cloud-itonami` の `:external-send` は `mail/send` effect として表現できる。
agent は draft と proposed effect まで作れるが、実送信は approval と host capability gate を通る。

## Non-goals

- `mail` は SMTP client/server を実装しない。
- `mailer` は HTTP fetch や SMTP socket を直接呼ばない。
- secret value は message/effect/request に入れない。request には secret alias のみを置く。
