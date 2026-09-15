# kotoba.cloud — personal API tokens (PAT) and the live probe

No values here; only where each token lives and how to replace it.

## What a PAT is (2026-09-15, app-kotoba-cloud PR #205)

`kc_pat_<principal>.<tokenId 12hex>.<mac>` — issued once from
https://kotoba.cloud/account (CLI / IDE panel, 「接続トークンを発行」), listed and
revoked one by one on the same panel (`GET /v1/account/api-tokens`,
`POST /v1/account/api-token/revoke {tokenId}`). The research authority keeps only
the 12-hex id per principal; the token string exists in the places below and
nowhere on the server. A revoked token answers `401 token-revoked` on the next
bearer call. Legacy v1 tokens (`kc_pat_<principal>.<mac>`, no id) were revoked for
the owner's principal on 2026-09-15 (「登録前に発行したトークンをすべて失効」).

## Where the owner's tokens live

| use | location | how to replace |
|---|---|---|
| hermes default profile (`providers.kotoba`, `model.provider: kotoba`) | `~/.hermes/.env` — `KOTOBA_API_KEY` and `KOTOBA_API_TOKEN` (the same token under both names) | issue a new one on /account, paste into both lines, revoke the old id on /account |
| live probe (`scripts/verify-kotoba-cloud-live.cljk`, detector `:verify-kotoba-cloud-live`) | login keychain, service `kotoba-cloud-live-probe`, account `kotoba-cloud` (`security find-generic-password -a kotoba-cloud -s kotoba-cloud-live-probe -w`) | `security add-generic-password -a kotoba-cloud -s kotoba-cloud-live-probe -w <token> -U` |

As of 2026-09-15 the keychain item holds a copy of the hermes token (id
`d5cc449fa4d5`), so revoking that id stops both; issue a dedicated `live-probe`
labelled token on /account and store it with the command above to separate them.

## Signing secret (server side)

`PAT_SIGNING_SECRET` on the `kotoba-cloud-control-plane` Worker (Cloudflare secret
store; `wrangler secret list` shows the name). Rotating it invalidates every token of
every principal at once — no longer needed for revocation.

## Related

The research authority's origin bearer: `MODAL_INFERENCE_TOKEN` on
`kotoba-research-authority` must equal the Modal secret `murakumo-modal-origin`
(`MURAKUMO_MODAL_ORIGIN_TOKEN`) of the origin it targets; see `references/murakumo.md`.
