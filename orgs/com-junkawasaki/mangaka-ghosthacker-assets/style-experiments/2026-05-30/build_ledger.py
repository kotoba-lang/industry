import json, re, collections

base = "/Users/junkawasaki/github/com-junkawasaki/personal"

agreements = {}  # (date, amount) -> source_file

def add(date, amount, source):
    key = (date, amount)
    if key not in agreements:
        agreements[key] = source

def norm_date(d):
    # "2025.12.31" -> "2025-12-31"
    return d.replace(".", "-")

# --- inventory-3: full filenames (authoritative) ---
inv3 = json.load(open(f"{base}/drive/files-inventory-3.json"))
fn_re = re.compile(r'^(\d{4}\.\d{2}\.\d{2})_([\d,]+)_金銭消費貸借契約書\.pdf$')
for f in inv3["files"]:
    title = f["title"]
    m = fn_re.match(title)
    if m:
        date = norm_date(m.group(1))
        amount = int(m.group(2).replace(",", ""))
        add(date, amount, title)

# --- inventory-2: summary entries "2025.02.25 ¥300,000" (no filename) ---
inv2 = json.load(open(f"{base}/drive/files-inventory-2.json"))
sum_re = re.compile(r'^(\d{4}\.\d{2}\.\d{2})\s+¥([\d,]+)$')
for entry in inv2["categories"]["loan_agreements (金銭消費貸借契約書)"]:
    m = sum_re.match(entry)
    if m:
        date = norm_date(m.group(1))
        amount = int(m.group(2).replace(",", ""))
        # reconstruct a plausible source filename for summary-only items
        src = f"{m.group(1)}_{m.group(2).replace(',','')}_金銭消費貸借契約書.pdf (inventory-2 summary; no filename ingested)"
        add(date, amount, src)

# build sorted list
rows = []
for (date, amount), src in agreements.items():
    rows.append({"date": date, "amount_jpy": amount, "source_file": src})
rows.sort(key=lambda r: (r["date"], r["amount_jpy"]))

by_month = collections.OrderedDict()
for r in sorted(rows, key=lambda r: r["date"]):
    mo = r["date"][:7]
    by_month[mo] = by_month.get(mo, 0) + r["amount_jpy"]

total = sum(r["amount_jpy"] for r in rows)
largest = max(rows, key=lambda r: r["amount_jpy"])
nov_dec = sum(v for k, v in by_month.items() if k in ("2025-11", "2025-12"))

notes = (
    f"Year-end concentration: Nov-Dec 2025 accounts for ¥{nov_dec:,} "
    f"({nov_dec/total*100:.0f}% of total) across "
    f"{sum(1 for r in rows if r['date'][:7] in ('2025-11','2025-12'))} agreements, "
    f"clustered around fiscal year-end. Largest single agreement: "
    f"¥{largest['amount_jpy']:,} on {largest['date']}. "
    "Source is Drive filename metadata only (no PDF bodies). De-duplicated by (date, amount); "
    "inventory-2 had a summary-only 2025-02-25 ¥300,000 and 2025-02-28 ¥330,000 entry that differ from / are absent in inventory-3's full filenames, so both variants are retained where date+amount are distinct."
)

out = collections.OrderedDict()
out["generated_at"] = "2026-05-30"
out["source"] = "Drive 金銭消費貸借契約書 filenames (metadata only)"
out["count"] = len(rows)
out["total_jpy"] = total
out["by_month"] = by_month
out["agreements"] = rows
out["notes"] = notes

import os
os.makedirs(f"{base}/analysis", exist_ok=True)
with open(f"{base}/analysis/loan-ledger.json", "w") as fh:
    json.dump(out, fh, ensure_ascii=False, indent=2)
    fh.write("\n")

print("count:", len(rows))
print("total:", total)
print("by_month:", dict(by_month))
top3 = sorted(rows, key=lambda r: -r["amount_jpy"])[:3]
for r in top3:
    print("TOP", r["date"], r["amount_jpy"], r["source_file"])
