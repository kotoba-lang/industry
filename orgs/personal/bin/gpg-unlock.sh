#!/usr/bin/env bash
# Unlock the personal-data gpg key for non-interactive git-annex operations by
# loading its passphrase from the macOS Keychain into gpg-agent's cache.
#
# Custody: passphrase lives in the login Keychain item "gpg:personal-data"
# (Touch ID / passcode gated). For cross-Apple-device sync, enable iCloud
# Keychain; arbitrary `security` items stay device-local unless made
# synchronizable, so the most portable custody is to also keep the offline
# recovery .asc (see README "後で" / recovery).
set -euo pipefail
KEYID=09EE841334482F5A0F5C4958A70BB2C220DE88CA
PASS=$(security find-generic-password -s "gpg:personal-data" -a "$KEYID" -w)
PRESET=$(find /opt/homebrew -name gpg-preset-passphrase 2>/dev/null | head -1)
gpgconf --launch gpg-agent
gpg --batch --with-keygrip --list-secret-keys "$KEYID" 2>/dev/null \
  | awk '/Keygrip/ {print $3}' \
  | while read -r KG; do "$PRESET" --preset -P "$PASS" "$KG"; done
echo "gpg-agent primed for $KEYID. You can now: git annex get/copy personal/ ..."
