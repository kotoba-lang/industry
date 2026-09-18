#!/usr/bin/env python3
"""FIRST EPSS -> internal-security-nvd:epss/  (daily).

Pages the public API (total ~375k records, limit=1000 per page, 6 retries
per page) and stages one CSV: cve,epss,percentile,date. Progress is
resumable via epss_daily.csv.tmp + last_offset. If the CSV for the API's
latest data date already exists locally, the sync is a no-op (EPSS updates
daily, not intraday). Rows must be >= 300_000 to publish.
"""
import csv
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kbds_common import out_dir, publish, write_manifest  # noqa: E402

API = "https://api.first.org/data/v1/epss"


def latest_data_date():
    with urllib.request.urlopen(f"{API}?limit=1", timeout=60) as r:
        d = json.load(r)
    return d["data"][0]["date"]


def fetch_page(offset):
    for attempt in range(6):
        try:
            with urllib.request.urlopen(f"{API}?limit=1000&offset={offset}",
                                        timeout=120) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            wait = 5 * (attempt + 1)
            print(f"  retry {attempt+1} offset={offset}: {e}; sleep {wait}")
            time.sleep(wait)
    raise RuntimeError(f"EPSS page failed after retries at offset={offset}")


def main():
    data_date = latest_data_date()
    base = out_dir("epss")
    dest = os.path.join(base, "epss_daily.csv")
    stamp_file = os.path.join(base, "last_data_date")
    if os.path.isfile(stamp_file) and open(stamp_file).read().strip() == data_date \
            and os.path.isfile(dest) and os.path.getsize(dest) > 1_000_000:
        print(f"SKIP: epss {data_date} already synced")
        return 0
    tmp = dest + ".tmp"
    progress = os.path.join(base, "last_offset")
    offset = 0
    if os.path.isfile(tmp) and os.path.isfile(progress) and os.path.getsize(tmp) > 1_000_000:
        try:
            offset = int(open(progress).read().strip() or 0)
        except ValueError:
            offset = 0
    mode = "a" if offset else "w"
    rows = 0
    with open(tmp, mode, newline="") as f:
        w = csv.writer(f)
        if offset == 0:
            w.writerow(["cve", "epss", "percentile", "date"])
        while True:
            d = fetch_page(offset)
            for rec in d["data"]:
                w.writerow([rec["cve"], rec["epss"], rec["percentile"], rec["date"]])
                rows += 1
            total = d["total"]
            offset += len(d["data"])
            with open(progress, "w") as pf:
                pf.write(str(offset))
            if offset >= total:
                break
            if rows % 50_000 < 1000:
                print(f"  ... {rows}/{total}")
    if rows < 300_000:
        print(f"FAIL: only {rows} EPSS rows")
        return 1
    os.replace(tmp, dest)
    with open(stamp_file, "w") as f:
        f.write(data_date)
    write_manifest("epss", f"{API} (paged, data date {data_date})",
                   [{"file": "epss_daily.csv", "rows": rows}],
                   extra={"dataDate": data_date})
    return publish("epss", None)


if __name__ == "__main__":
    sys.exit(main())
