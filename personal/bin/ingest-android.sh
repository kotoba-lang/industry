#!/usr/bin/env bash
# Ingest a connected Android device (adb) into personal/device/android/.
# PII (sms/call_log/contacts) lands under personal/device which is git-annex
# managed (encryption=hybrid -> B2 ciphertext only). Snapshot-in-time.
#
# usage: ingest-android.sh [adb-serial]   (default: first `adb devices`)
set -u
cd "$(dirname "$0")/.." || exit 1   # -> personal/
OUT=device/android
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SERIAL="${1:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
[ -z "$SERIAL" ] && { echo "no authorized adb device"; exit 1; }
A() { adb -s "$SERIAL" "$@"; }
mkdir -p "$OUT"
echo "android ingest: $SERIAL @ $TS"

# ---- device identity / state ----
A shell getprop 2>/dev/null > "$OUT/getprop.txt"
{
  echo "ingested_at: $TS"
  echo "serial: $SERIAL"
  for k in ro.product.model ro.product.manufacturer ro.product.name \
           ro.build.version.release ro.build.version.security_patch \
           ro.build.fingerprint; do
    printf "%s: %s\n" "$k" "$(A shell getprop "$k" 2>/dev/null | tr -d '\r')"
  done
  echo "--- battery ---"; A shell dumpsys battery 2>/dev/null | sed 's/^ *//'
  echo "--- storage ---"; A shell df -h /data 2>/dev/null
} > "$OUT/system.txt"

# ---- installed packages ----
A shell pm list packages    2>/dev/null | sed 's/^package://' | sort > "$OUT/packages.txt"
A shell pm list packages -3 2>/dev/null | sed 's/^package://' | sort > "$OUT/packages-3rdparty.txt"

# ---- contacts / sms / call_log (raw + jsonl) ----
A shell content query --uri content://contacts/phones/ \
  --projection display_name:number 2>/dev/null | tr -d '\r' > "$OUT/contacts-raw.txt"
A shell content query --uri content://sms/ \
  --projection _id:address:date:type:body 2>/dev/null | tr -d '\r' > "$OUT/sms-raw.txt"
A shell content query --uri content://call_log/calls \
  --projection _id:number:date:duration:type 2>/dev/null | tr -d '\r' > "$OUT/call_log-raw.txt"

# ---- normalize content-query "Row: N k=v, k=v" -> json/jsonl ----
python3 - "$OUT" <<'PY'
import sys, json, re, os
out = sys.argv[1]
def rows(path):
    if not os.path.exists(path): return
    for ln in open(path, encoding="utf-8", errors="replace"):
        ln = ln.strip()
        m = re.match(r'^Row:\s*\d+\s*(.*)$', ln)
        if not m: continue
        # split on ", key=" boundaries (values may contain commas)
        body = m.group(1)
        parts = re.split(r', (?=[A-Za-z_]+=)', body)
        d = {}
        for p in parts:
            if '=' not in p: continue
            k, v = p.split('=', 1)
            d[k.strip()] = None if v == 'NULL' else v
        yield d

# contacts -> [{name, number}]
contacts = []
for d in rows(f"{out}/contacts-raw.txt"):
    contacts.append({"name": d.get("display_name"), "number": d.get("number")})
json.dump(contacts, open(f"{out}/contacts.json","w"), ensure_ascii=False, indent=1)

# sms -> jsonl (type 1=received 2=sent)
with open(f"{out}/sms.jsonl","w") as f:
    for d in rows(f"{out}/sms-raw.txt"):
        f.write(json.dumps({
            "id": d.get("_id"), "address": d.get("address"),
            "date": d.get("date"), "type": d.get("type"),
            "body": d.get("body"),
        }, ensure_ascii=False)+"\n")

# call_log -> jsonl (type 1=in 2=out 3=missed)
with open(f"{out}/call_log.jsonl","w") as f:
    for d in rows(f"{out}/call_log-raw.txt"):
        f.write(json.dumps({
            "id": d.get("_id"), "number": d.get("number"),
            "date": d.get("date"), "duration": d.get("duration"),
            "type": d.get("type"),
        }, ensure_ascii=False)+"\n")

print(f"contacts={len(contacts)} "
      f"sms={sum(1 for _ in open(f'{out}/sms.jsonl'))} "
      f"call_log={sum(1 for _ in open(f'{out}/call_log.jsonl'))}")
PY

echo "android ingest complete -> $OUT/"
