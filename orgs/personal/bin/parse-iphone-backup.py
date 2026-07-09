#!/usr/bin/env python3
"""Parse an iOS device backup (pymobiledevice3/idevicebackup2 layout) into
normalized SMS/contacts/call-log + a summary, mirroring the Android schema.

iOS backups store every file by SHA1 under <UDID>/<ab>/<sha1>, indexed in
Manifest.db (sqlite, table Files: fileID, domain, relativePath). We resolve the
known dbs, parse them, and emit orgs/personal/device/iphone/{sms,call_log}.jsonl,
contacts.json, summary.json. Unencrypted backups only (encrypted -> reported).

usage: parse-iphone-backup.py <backup-root-containing-UDID-dir> [out-dir]
"""
import sys, os, sqlite3, json, plistlib, glob, datetime

backup_root = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else "orgs/personal/device/iphone"
# UDID dir = the single subdir holding Manifest.db
udid_dirs = [d for d in glob.glob(os.path.join(backup_root, "*")) if os.path.isdir(d)
             and os.path.exists(os.path.join(d, "Manifest.db"))]
if not udid_dirs:
    # maybe backup_root itself is the UDID dir
    if os.path.exists(os.path.join(backup_root, "Manifest.db")):
        udid_dirs = [backup_root]
    else:
        print("ERROR: Manifest.db が見つからない(暗号化backupまたは未完了)", file=sys.stderr); sys.exit(2)
B = udid_dirs[0]

# encrypted?
mp = os.path.join(B, "Manifest.plist")
if os.path.exists(mp):
    with open(mp, "rb") as f:
        man = plistlib.load(f)
    if man.get("IsEncrypted"):
        print("ERROR: 暗号化backup。復号にはパスワードが必要(別途対応)", file=sys.stderr); sys.exit(3)

def resolve(relpath, domain_like=None):
    con = sqlite3.connect(os.path.join(B, "Manifest.db")); c = con.cursor()
    q = "SELECT fileID,domain FROM Files WHERE relativePath=?"
    args = [relpath]
    if domain_like:
        q += " AND domain LIKE ?"; args.append(domain_like)
    rows = c.execute(q, args).fetchall(); con.close()
    if not rows: return None
    fid = rows[0][0]
    return os.path.join(B, fid[:2], fid)

MAC_EPOCH = 978307200  # 2001-01-01 UTC in unix seconds
def mac_to_date(v):
    if v is None: return None
    v = float(v)
    if v > 1e17: v /= 1e9      # ns
    elif v > 1e11: v /= 1e3    # ms-ish guard
    try:
        return datetime.datetime.fromtimestamp(v + MAC_EPOCH, datetime.UTC).strftime("%Y-%m-%d")
    except Exception:
        return None

summary = {"source": "ios-backup", "backup_dir": B}

# ---- SMS (Library/SMS/sms.db) ----
p = resolve("Library/SMS/sms.db", "%HomeDomain%") or resolve("Library/SMS/sms.db")
if p and os.path.exists(p):
    con = sqlite3.connect(p); c = con.cursor()
    n = c.execute("SELECT COUNT(*) FROM message").fetchone()[0]
    dmin, dmax = c.execute("SELECT MIN(date),MAX(date) FROM message WHERE date>0").fetchone()
    sent = c.execute("SELECT COUNT(*) FROM message WHERE is_from_me=1").fetchone()[0]
    # service breakdown (iMessage vs SMS)
    svc = dict(c.execute("SELECT service,COUNT(*) FROM message GROUP BY service").fetchall())
    with open(f"{out}/sms.jsonl", "w") as f:
        for row in c.execute("""SELECT m.rowid,h.id,m.date,m.is_from_me,m.service,m.text
                                FROM message m LEFT JOIN handle h ON m.handle_id=h.rowid"""):
            f.write(json.dumps({"id": row[0], "address": row[1], "date": mac_to_date(row[2]),
                                "is_from_me": row[3], "service": row[4], "body": row[5]},
                               ensure_ascii=False) + "\n")
    con.close()
    summary["sms"] = {"total": n, "sent": sent, "received": n - sent,
                      "service": svc, "window": [mac_to_date(dmin), mac_to_date(dmax)]}
else:
    summary["sms"] = None

# ---- Contacts (AddressBook.sqlitedb) ----
p = resolve("Library/AddressBook/AddressBook.sqlitedb", "%HomeDomain%") or resolve("Library/AddressBook/AddressBook.sqlitedb")
if p and os.path.exists(p):
    con = sqlite3.connect(p); c = con.cursor()
    n = c.execute("SELECT COUNT(*) FROM ABPerson").fetchone()[0]
    contacts = []
    try:
        for row in c.execute("""SELECT p.First,p.Last,v.value
                                FROM ABPerson p LEFT JOIN ABMultiValue v ON v.record_id=p.ROWID
                                WHERE v.value IS NOT NULL"""):
            nm = " ".join(x for x in [row[0], row[1]] if x)
            contacts.append({"name": nm or None, "value": row[2]})
    except Exception:
        pass
    json.dump(contacts, open(f"{out}/contacts.json", "w"), ensure_ascii=False, indent=1)
    con.close()
    summary["contacts"] = {"persons": n, "with_value": len(contacts)}
else:
    summary["contacts"] = None

# ---- Call history (Library/CallHistoryDB/CallHistory.storedata) ----
p = resolve("Library/CallHistoryDB/CallHistory.storedata", "%HomeDomain%") or resolve("Library/CallHistoryDB/CallHistory.storedata")
if p and os.path.exists(p):
    con = sqlite3.connect(p); c = con.cursor()
    try:
        n = c.execute("SELECT COUNT(*) FROM ZCALLRECORD").fetchone()[0]
        dmin, dmax = c.execute("SELECT MIN(ZDATE),MAX(ZDATE) FROM ZCALLRECORD WHERE ZDATE>0").fetchone()
        # ZORIGINATED 1=outgoing 0=incoming ; ZANSWERED
        out_n = c.execute("SELECT COUNT(*) FROM ZCALLRECORD WHERE ZORIGINATED=1").fetchone()[0]
        miss = c.execute("SELECT COUNT(*) FROM ZCALLRECORD WHERE ZANSWERED=0 AND ZORIGINATED=0").fetchone()[0]
        with open(f"{out}/call_log.jsonl", "w") as f:
            for row in c.execute("SELECT Z_PK,ZADDRESS,ZDATE,ZDURATION,ZORIGINATED,ZANSWERED FROM ZCALLRECORD"):
                addr = row[1].decode("utf-8","replace") if isinstance(row[1],(bytes,bytearray)) else row[1]
                f.write(json.dumps({"id": row[0], "address": addr, "date": mac_to_date(row[2]),
                                    "duration": row[3], "originated": row[4], "answered": row[5]},
                                   ensure_ascii=False) + "\n")
        summary["call_log"] = {"total": n, "outgoing": out_n, "missed_incoming": miss,
                               "window": [mac_to_date(dmin), mac_to_date(dmax)]}
    except Exception as e:
        summary["call_log"] = {"error": str(e)}
    con.close()
else:
    summary["call_log"] = None

# ---- Photos (CameraRollDomain Media/DCIM/**) ----
con = sqlite3.connect(os.path.join(B, "Manifest.db")); c = con.cursor()
photos = c.execute("""SELECT COUNT(*) FROM Files
    WHERE domain='CameraRollDomain' AND relativePath LIKE 'Media/DCIM/%'
    AND (relativePath LIKE '%.JPG' OR relativePath LIKE '%.HEIC' OR relativePath LIKE '%.PNG'
         OR relativePath LIKE '%.MOV' OR relativePath LIKE '%.MP4' OR relativePath LIKE '%.jpg'
         OR relativePath LIKE '%.heic' OR relativePath LIKE '%.mov')""").fetchone()[0]
total_files = c.execute("SELECT COUNT(*) FROM Files").fetchone()[0]
con.close()
summary["photos_videos_in_camera_roll"] = photos
summary["backup_total_files"] = total_files

json.dump(summary, open(f"{out}/summary.json", "w"), ensure_ascii=False, indent=2)
print(json.dumps(summary, ensure_ascii=False, indent=2))
