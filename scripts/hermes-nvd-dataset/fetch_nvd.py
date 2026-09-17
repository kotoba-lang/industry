#!/usr/bin/env python3
"""NVD CVE 2.0 full/incremental fetch -> per-year Parquet for R2 publish.

The corpus problem the git repo cannot carry: full NVD history (~393k CVEs,
multi-GB raw). This builder keeps only a flattened Parquet dataset
(OUT/parquet/nvd_cve_<year>.parquet) plus a manifest, and never stages raw
JSON on disk: every fetched window is upserted (by id, newest modified wins)
straight into the affected year's Parquet file, so peak local disk is the
dataset itself.

Modes:
  full  — windowed 100-day pubStartDate walk from 1999-01-01 to now.
  incr  — lastModified watermark walk (windows since state.json watermark) to
          refresh changed records only.
Resumable: fetched windows are checkpointed in state.json; a re-run skips
completed windows. NVD is anonymous rate-limited (5 req / rolling 30 s), so
every request sleeps RATE_SLEEP; 503/5xx back off 30/60/120/180 s.

Run with the iceberg venv python (has pyarrow):
  ~/venvs/iceberg/bin/python fetch_nvd.py full
"""
import datetime as dt
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import pyarrow as pa
import pyarrow.parquet as pq

OUT = os.environ.get("NVD_OUT", os.path.expanduser("~/nvd-dataset"))
BUILD = os.path.join(OUT, ".nvd-build")
PARQUET_DIR = os.path.join(OUT, "parquet")
STATE = os.path.join(BUILD, "state.json")
URL = "https://services.nvd.nist.gov/rest/json/cves/2.0"
RATE_SLEEP = float(os.environ.get("NVD_RATE_SLEEP", "6.5"))
WINDOW_DAYS = 100
PER_PAGE = 2000  # NVD silently answers totalResults=0 for pre-2002 windows
                 # when resultsPerPage is large (measured 2026-09-16: 5000 ->
                 # 0 rows, 2000/500 -> 317 rows). Keep 2000 + the sanity
                 # re-probe below.
CPE_SAMPLE_CAP = 20
REF_CAP = 40

SCHEMA = pa.schema([
    ("id", pa.string()),
    ("source", pa.string()),
    ("published", pa.string()),
    ("modified", pa.string()),
    ("status", pa.string()),
    ("desc_en", pa.string()),
    ("cvss4_score", pa.float64()),
    ("cvss4_sev", pa.string()),
    ("cvss4_vec", pa.string()),
    ("cvss31_score", pa.float64()),
    ("cvss31_sev", pa.string()),
    ("cvss31_vec", pa.string()),
    ("cvss30_score", pa.float64()),
    ("cvss30_sev", pa.string()),
    ("cvss2_score", pa.float64()),
    ("cvss2_sev", pa.string()),
    ("weaknesses", pa.list_(pa.string())),
    ("references", pa.list_(pa.string())),
    ("cpe_count", pa.int32()),
    ("cpe_sample", pa.list_(pa.string())),
    ("year", pa.int32()),
])


def iso(d):
    return d.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def metric(metrics, key, score_path=("cvssData", "baseScore"),
           sev_path=("cvssData", "baseSeverity"),
           vec_path=("cvssData", "vectorString")):
    for m in sorted((metrics or {}).get(key) or [],
                    key=lambda x: 0 if x.get("type") == "Primary" else 1):
        def dig(path):
            v = m
            for p in path:
                if not isinstance(v, dict):
                    return None
                v = v.get(p)
            return None if not path else v
        return dig(score_path), dig(sev_path) or m.get("baseSeverity"), dig(vec_path)
    return None, None, None


def collect_cpes(node, out):
    for cm in node.get("cpeMatches") or []:
        out.append(cm.get("criteria") or "")
    for child in node.get("nodes") or []:
        collect_cpes(child, out)


def flatten(c):
    desc = next((d.get("value") for d in c.get("descriptions") or []
                 if d.get("lang") == "en"), None)
    weaknesses = [w.get("description", [{}])[0].get("value")
                  for w in c.get("weaknesses") or []
                  if w.get("description")]
    refs = [r.get("url") for r in (c.get("references") or []) if r.get("url")][:REF_CAP]
    cpes = []
    for cfg in c.get("configurations") or []:
        collect_cpes(cfg, cpes)
    c4s, c4v, c4vec = metric(c.get("metrics"), "cvssMetricV40")
    c31s, c31v, c31vec = metric(c.get("metrics"), "cvssMetricV31")
    c30s, c30v, _ = metric(c.get("metrics"), "cvssMetricV30")
    c2s, c2v, _ = metric(c.get("metrics"), "cvssMetricV2",
                         score_path=("cvssData", "baseScore"),
                         sev_path=("baseSeverity",), vec_path=())
    published = c.get("published") or ""
    try:
        year = int(published[:4])
    except ValueError:
        year = 0
    return {
        "id": c.get("cveId") or c.get("id"),
        "source": c.get("sourceIdentifier"),
        "published": published,
        "modified": c.get("lastModified") or "",
        "status": c.get("vulnStatus") or "",
        "desc_en": desc,
        "cvss4_score": c4s, "cvss4_sev": c4v, "cvss4_vec": c4vec,
        "cvss31_score": c31s, "cvss31_sev": c31v, "cvss31_vec": c31vec,
        "cvss30_score": c30s, "cvss30_sev": c30v,
        "cvss2_score": c2s, "cvss2_sev": c2v,
        "weaknesses": weaknesses,
        "references": refs,
        "cpe_count": len(cpes),
        "cpe_sample": cpes[:CPE_SAMPLE_CAP],
        "year": year,
    }


def load_state():
    if os.path.isfile(STATE):
        with open(STATE) as f:
            return json.load(f)
    return {"done_windows": [], "watermark_modified": None}


def parse_ts(s):
    # NVD lastModified values come back without a timezone suffix (naive UTC).
    # Normalise to offset-aware so comparisons with now(UTC) never TypeError.
    if not s:
        return None
    if not (s.endswith("Z") or "+" in s):
        s += "+00:00"
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def save_state(st):
    os.makedirs(BUILD, exist_ok=True)
    tmp = STATE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(st, f)
    os.replace(tmp, STATE)


def api_get(params, tag, per_page=None):
    q = dict(params)
    q["resultsPerPage"] = per_page or PER_PAGE
    url = URL + "?" + urllib.parse.urlencode(q)
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "kotoba-dataset-builder/1.0"})
            with urllib.request.urlopen(req, timeout=240) as r:
                return json.loads(r.read().decode())
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as e:
            code = getattr(e, "code", None)
            if code == 404:  # empty window
                return {"totalResults": 0, "vulnerabilities": []}
            wait = 30 * (attempt + 1)
            print(f"  [{tag}] retry {attempt + 1} ({e}) sleep {wait}s", flush=True)
            time.sleep(wait)
    raise RuntimeError(f"{tag}: exhausted retries")


def upsert_years(rows):
    """Merge rows into per-year parquet files, dedupe by id keeping newest modified."""
    by_year = {}
    for r in rows:
        by_year.setdefault(r["year"], []).append(r)
    for year, yrows in by_year.items():
        path = os.path.join(PARQUET_DIR, f"nvd_cve_{year}.parquet")
        os.makedirs(PARQUET_DIR, exist_ok=True)
        if os.path.isfile(path):
            old = pq.read_table(path).to_pylist()
        else:
            old = []
        merged = {r["id"]: r for r in old}
        for r in yrows:
            prev = merged.get(r["id"])
            if prev is None or (r["modified"] or "") >= (prev["modified"] or ""):
                merged[r["id"]] = r
        table = pa.Table.from_pylist(list(merged.values()), schema=SCHEMA)
        pq.write_table(table, path + ".tmp", compression="zstd")
        os.replace(path + ".tmp", path)
        print(f"  year {year}: {len(merged)} rows", flush=True)


def walk_windows(st, mode):
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    if mode == "full":
        start = dt.datetime(1999, 1, 1, tzinfo=dt.timezone.utc)
        end_param = "pubEndDate"
        start_param = "pubStartDate"
    else:
        wm = st.get("watermark_modified")
        wm_dt = parse_ts(wm)
        start = (wm_dt - dt.timedelta(days=2)) if wm_dt \
            else dt.datetime(1999, 1, 1, tzinfo=dt.timezone.utc)
        start_param = "lastModStartDate"
        end_param = "lastModEndDate"
    done = set(st["done_windows"])
    total_rows = 0
    cur = start
    max_mod = st.get("watermark_modified")
    while cur < now:
        w_end = min(cur + dt.timedelta(days=WINDOW_DAYS), now)
        tag = f"{cur:%Y-%m-%d}~{w_end:%Y-%m-%d}"
        if tag in done:
            cur = w_end
            continue
        start_index = 0
        fetched = 0
        while True:
            d = api_get({start_param: iso(cur), end_param: iso(w_end),
                         "startIndex": start_index}, tag)
            vulns = d.get("vulnerabilities") or []
            rows = [flatten(v["cve"]) for v in vulns]
            for r in rows:
                if r["modified"] and (max_mod is None or r["modified"] > max_mod):
                    max_mod = r["modified"]
            upsert_years(rows)
            fetched += len(vulns)
            total = d.get("totalResults", 0)
            if not vulns or fetched >= total:
                break
            start_index += PER_PAGE
            time.sleep(RATE_SLEEP)
        total_rows += fetched
        st["done_windows"].append(tag)
        st["watermark_modified"] = max_mod
        save_state(st)
        print(f"window {tag}: total={d.get('totalResults')} fetched={fetched} "
              f"cum={total_rows}", flush=True)
        cur = w_end
        time.sleep(RATE_SLEEP)
    print(f"WALK DONE rows_fetched_this_run={total_rows} watermark={max_mod}", flush=True)


def write_manifest():
    import hashlib
    files, total = [], 0
    for name in sorted(os.listdir(PARQUET_DIR)):
        if not name.endswith(".parquet"):
            continue
        p = os.path.join(PARQUET_DIR, name)
        n = pq.ParquetFile(p).metadata.num_rows
        total += n
        with open(p, "rb") as f:
            sha = hashlib.sha256(f.read()).hexdigest()
        files.append({"file": name, "rows": n, "sha256": sha})
    man = {
        "name": "nvd-cve-full",
        "source": URL,
        "generated_at": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "watermark_modified": load_state().get("watermark_modified"),
        "total_rows": total,
        "files": files,
    }
    with open(os.path.join(OUT, "manifest.json"), "w") as f:
        json.dump(man, f, indent=1)
    print(f"manifest: {total} rows across {len(files)} year files", flush=True)


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "full"
    os.makedirs(BUILD, exist_ok=True)
    os.makedirs(PARQUET_DIR, exist_ok=True)
    st = load_state()
    if mode not in ("full", "incr"):
        print("usage: fetch_nvd.py full|incr", file=sys.stderr)
        sys.exit(2)
    if mode == "full" and st.get("full_complete"):
        # previous full walk finished; a new full run starts fresh windows
        st["done_windows"] = []
        st["full_complete"] = False
        save_state(st)
    if mode == "incr":
        st["done_windows"] = st["done_windows"][-500:]
    walk_windows(st, mode)
    if mode == "full":
        st["full_complete"] = True
        save_state(st)
    st["done_windows"] = []
    save_state(st)
    write_manifest()
    print("FETCH BUILD OK", flush=True)
