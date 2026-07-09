#!/usr/bin/env bash
# Ingest a connected & TRUSTED iPhone (libimobiledevice) into orgs/personal/device/iphone/.
# Requires pairing first: unlock iPhone -> "Trust This Computer" -> passcode ->
#   idevicepair pair   (must print "SUCCESS")
# Metadata is cheap; SMS/contacts/calls/photos require a full backup (--backup),
# which is large -> opt-in. All PII lands under orgs/personal/device (annex hybrid->B2).
#
# usage: ingest-iphone.sh            # metadata + diagnostics only
#        ingest-iphone.sh --backup   # + full idevicebackup2 (GB-scale, slow)
set -u
cd "$(dirname "$0")/.." || exit 1   # -> orgs/personal/
OUT=device/iphone
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mkdir -p "$OUT"

# ---- require trust ----
if ! idevicepair validate >/dev/null 2>&1; then
  echo "NOT PAIRED. iPhoneロック解除→『このコンピュータを信頼』→パスコード→ idevicepair pair" >&2
  idevicepair pair 2>&1 | head -2 >&2
  exit 2
fi
echo "iphone ingest @ $TS"

# ---- device metadata ----
ideviceinfo 2>/dev/null > "$OUT/ideviceinfo.txt"
{
  echo "ingested_at: $TS"
  for k in DeviceName ProductType ProductVersion BuildVersion ModelNumber \
           HardwareModel CPUArchitecture DeviceColor \
           TimeIntervalSince1970 PhoneNumber WiFiAddress BluetoothAddress \
           SerialNumber RegionInfo; do
    printf "%s: %s\n" "$k" "$(ideviceinfo -k "$k" 2>/dev/null)"
  done
  echo "--- capacity ---"
  ideviceinfo -q com.apple.disk_usage 2>/dev/null | grep -iE 'Total|Available' | head
} > "$OUT/system.txt"

# ---- battery / diagnostics ----
idevicediagnostics ioregentry AppleSmartBattery 2>/dev/null > "$OUT/battery.txt" || true

# ---- installed apps (ideviceinstaller が有れば) ----
if command -v ideviceinstaller >/dev/null 2>&1; then
  ideviceinstaller list -o list_user 2>/dev/null > "$OUT/packages.txt"
else
  echo "(ideviceinstaller 未インストール: brew install ideviceinstaller でアプリ一覧取得可)" > "$OUT/packages.txt"
fi

# ---- optional full backup (SMS/contacts/calls/photos) ----
if [ "${1:-}" = "--backup" ]; then
  echo "full backup -> $OUT/backup/ (GB級・時間がかかる)"
  mkdir -p "$OUT/backup"
  idevicebackup2 backup "$OUT/backup" 2>&1 | tail -20
  echo "backup done. SMS=sms.db / 連絡先=AddressBook.sqlitedb / 通話=call_history.db を Manifest.db 経由で解決"
fi

# ---- summary ----
echo "model: $(ideviceinfo -k ProductType 2>/dev/null) ($(ideviceinfo -k ProductVersion 2>/dev/null))"
echo "iphone ingest complete -> $OUT/"
