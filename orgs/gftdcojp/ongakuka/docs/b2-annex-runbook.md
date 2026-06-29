# B2 Annex Runbook

This runbook records the working path used on 2026-06-29 to import BGM files,
store them through `git-annex`, and copy them to the B2 special remote.

## 1Password items

- B2 credentials: vault `gftdcojp`, item `com-junkawasaki.b2/annex`
  - `keyID`
  - `applicationKey`
  - `bucket`
  - `endpoint`
- GPG recovery: vault `Private`, item `GPG: personal-data warehouse recovery (jun784)`
  - `passphrase`

The older `op://gftdcojp/com-junkawasaki.b2_annex/...` path is stale. Use the
item name above.

## Unlock GPG

`git-annex` decrypts its B2 remote configuration with GPG. In non-interactive
shells, first read the passphrase and unlock the secret key with loopback
pinentry:

```bash
op item get 'GPG: personal-data warehouse recovery (jun784)' \
  --vault Private \
  --fields label=passphrase \
  --reveal > /tmp/personal_gpg_pass

printf unlock-test > /tmp/gpg-unlock-test.txt
gpg --batch --yes --pinentry-mode loopback \
  --passphrase-file /tmp/personal_gpg_pass \
  --local-user A70BB2C220DE88CA \
  --clearsign \
  -o /tmp/gpg-unlock-test.asc \
  /tmp/gpg-unlock-test.txt
```

If `git annex copy --to b2` fails with `gpg: public key decryption failed:
Inappropriate ioctl for device`, this unlock step was missing or the agent cache
expired.

## Read B2 credentials once

Calling `op` repeatedly can hit authorization timeouts. Read the item once and
derive env vars from the JSON:

```bash
op item get 'com-junkawasaki.b2/annex' \
  --vault gftdcojp \
  --reveal \
  --format json > /tmp/b2_annex_item.json

export B2_KEY_ID="$(jq -r '.fields[] | select(.label=="keyID") | .value' /tmp/b2_annex_item.json)"
export B2_APP_KEY="$(jq -r '.fields[] | select(.label=="applicationKey") | .value' /tmp/b2_annex_item.json)"
export B2_BUCKET="$(jq -r '.fields[] | select(.label=="bucket") | .value' /tmp/b2_annex_item.json)"
```

## Add and copy BGM files

```bash
git annex add orgs/com-junkawasaki/yukkuri-assets-nist-csf-cis-scs/bgm/dova/*.mp3

git annex copy --to b2 \
  orgs/com-junkawasaki/yukkuri-assets-nist-csf-cis-scs/bgm/dova/*.mp3

git annex whereis \
  orgs/com-junkawasaki/yukkuri-assets-nist-csf-cis-scs/bgm/dova/*.mp3
```

Expected result: each file has two copies, `here` and `[b2]`.

## Cleanup

```bash
rm -f /tmp/b2_annex_item.json \
      /tmp/personal_gpg_pass \
      /tmp/gpg-unlock-test.txt \
      /tmp/gpg-unlock-test.asc
```

## Notes

- Do not expose the raw MP3 files through public app static paths or CDN blob
  URLs. The DOVA imports are render-only production materials.
- Do not use these files for AI training.
- Do not register produced audio or derived music in Content ID or equivalent
  fingerprinting systems.
