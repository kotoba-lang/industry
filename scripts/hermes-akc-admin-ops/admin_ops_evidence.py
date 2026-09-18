#!/usr/bin/env python3
"""admin_ops_evidence.py - decision-free measurement for the akc-admin-ops bot
(admin.kotoba.cloud, the kotoba.cloud operator console; app-kotoba-cloud
ADR-2609181500).

The console is dark outside its IP allowlist (Worker secret ADMIN_ALLOWED_IPS)
and everything else about it is measurable from THIS host, which is inside
the list. So the bot measures, from here:

  gate      /health over IPv4 and over IPv6 (the browser prefers v6 - measured
            2026-09-18: a v4-only allowlist locked the owner's browser out);
            the apex's dark answer for the console module (/js/admin.js -> a
            plain 404); this host's current egress addresses, so a refusal can
            be read against the allowlist.
  console   the document, its module, the operator API's own refusals by name
            (sign-in-required, origin-not-allowed) - the console's gates are
            live, not just the host.
  leak      public places that must not name the host: the shared session.js,
            the enterprise twin catalog, sitemap / robots / llms.
  authn     auth.kotoba.cloud /v1/operator/users answers 404 without a token.
  secrets   `wrangler secret list` for the control plane and for kotobase-authn:
            ADMIN_ALLOWED_IPS, AUTHN_OPERATOR_TOKEN present (names only).
  operators RESEARCH_OPERATORS in wrangler.research.jsonc (who may operate).
  repo      the bot's worktree vs origin/main; the gate's source files and
            the ADR still exist.

Modes:
  (default)   the full JSON report, one object, with measured_at and latencies.
  --monitor   the STABLE subset (no times, no latencies, no egress addresses),
              sorted keys - for `hermes cron ... --monitor-script`: unchanged
              output suppresses the agent, a change wakes it with the diff.

REFUSED (printed as {"refused": "<why>"}, exit 0) when the measurement itself
cannot run: no worktree, or every live probe failed (this host is offline, or
the edge is unreachable) - blind is not green. The script decides nothing.
Pure ASCII on every command line it runs (Tirith).
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

WORKTREE = os.path.expanduser("~/.gftd/worktrees/akc-admin-ops")
HOST = "https://admin.kotoba.cloud"
APEX = "https://kotoba.cloud"
AUTHN = "https://auth.kotoba.cloud"
TWIN = "https://twin.kotoba.cloud"
HOSTNAME = "admin.kotoba.cloud"
MONITOR = "--monitor" in sys.argv


def fetch(url, method="GET", headers=None, body=None, timeout=20):
    """(status, headers-dict, text, ms) - an HTTP error is a status, not an
    exception; only a transport failure is."""
    # Cloudflare answers python-urllib's default agent with 403 "error code:
    # 1010" (a browser-signature ban, measured 2026-09-18) - name this tool
    hdrs = {"User-Agent": "akc-admin-ops-evidence/1 (+https://kotoba.cloud)", "Accept": "*/*"}
    hdrs.update(headers or {})
    req = urllib.request.Request(url, method=method, data=body, headers=hdrs)
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            text = r.read(2_000_000).decode("utf-8", "replace")
            return r.status, dict(r.headers), text, int((time.time() - started) * 1000)
    except urllib.error.HTTPError as e:
        text = e.read(2_000_000).decode("utf-8", "replace")
        return e.code, dict(e.headers), text, int((time.time() - started) * 1000)
    except Exception as e:  # transport: DNS, TLS, timeout
        return None, {}, "transport: %s" % (e.__class__.__name__,), int((time.time() - started) * 1000)


def curl_family(url, family):
    """curl -4 / -6: urllib cannot pin an address family, and the gate must be
    measured per family. Returns (status or None, body-head)."""
    try:
        p = subprocess.run(["curl", family, "-s", "-m", "20", "-o", "/tmp/akc-admin-ops-probe", "-w", "%{http_code}", url],
                           capture_output=True, text=True, timeout=30)
        code = p.stdout.strip()
        head = ""
        try:
            with open("/tmp/akc-admin-ops-probe", "r", encoding="utf-8", errors="replace") as f:
                head = f.read(200)
        except OSError:
            pass
        return (int(code) if code.isdigit() and code != "000" else None), head
    except Exception as e:
        return None, "transport: %s" % (e.__class__.__name__,)


def egress(family):
    try:
        p = subprocess.run(["curl", family, "-s", "-m", "15", APEX + "/cdn-cgi/trace"], capture_output=True, text=True, timeout=25)
        m = re.search(r"^ip=(.+)$", p.stdout, re.M)
        return m.group(1).strip() if m else None
    except Exception:
        return None


def gate():
    out = {}
    for label, fam in (("v4", "-4"), ("v6", "-6")):
        code, head = curl_family(HOST + "/health", fam)
        answer = "no-route" if code is None else (
            "inside" if code == 200 and "kotoba-cloud-operator-console" in head else
            "dark" if code == 404 and head.strip() == "Not Found" else "other:%s" % code)
        out[label] = {"status": code, "answer": answer}
        if not MONITOR:
            out[label]["egress"] = egress(fam)
    # the dark answer's shape, measured where it is always dark: the apex
    code, hdrs, text, ms = fetch(APEX + "/js/admin.js")
    out["apex_dark_shape"] = {"status": code, "plain": text.strip() == "Not Found",
                              "content_type": (hdrs.get("Content-Type") or hdrs.get("content-type") or "").split(";")[0],
                              "no_console_headers": not any(k.lower() in ("x-robots-tag", "content-security-policy") for k in hdrs)}
    if not MONITOR:
        out["apex_dark_shape"]["ms"] = ms
    return out


def console():
    out = {}
    code, hdrs, text, ms = fetch(HOST + "/")
    out["document"] = {"status": code, "mount": 'id="admin-console"' in text,
                       "views": sorted(set(re.findall(r'data-admin-view="([a-z]+)"', text))),
                       "no_store": "no-store" in (hdrs.get("Cache-Control") or hdrs.get("cache-control") or ""),
                       "noindex": (hdrs.get("X-Robots-Tag") or hdrs.get("x-robots-tag")) == "noindex"}
    code, hdrs, text, _ = fetch(HOST + "/js/admin.js")
    out["module"] = {"status": code, "served": code == 200 and "admin-console" in text}
    j = {"content-type": "application/json", "origin": HOST}
    code, hdrs, text, _ = fetch(HOST + "/v1/admin/overview", "POST", j, b"{}")
    out["api_no_session"] = {"status": code, "code": _code(text)}
    j2 = {"content-type": "application/json", "origin": APEX}
    code, hdrs, text, _ = fetch(HOST + "/v1/admin/overview", "POST", j2, b"{}")
    out["api_wrong_origin"] = {"status": code, "code": _code(text)}
    code, hdrs, text, _ = fetch(HOST + "/apps/")
    out["marketing_path"] = {"status": code}
    return out


def _code(text):
    try:
        return json.loads(text).get("error", {}).get("code")
    except Exception:
        return None


def leak():
    out = {}
    for name, url, needle in (
            ("session_js", APEX + "/js/session.js", HOSTNAME),
            ("twin_catalog", TWIN + "/twin-data/enterprise/enterprise.json", "host:admin"),
            ("twin_catalog_name", TWIN + "/twin-data/enterprise/enterprise.json", HOSTNAME),
            ("sitemap", APEX + "/sitemap.xml", HOSTNAME),
            ("robots", APEX + "/robots.txt", HOSTNAME),
            ("llms", APEX + "/llms.txt", HOSTNAME)):
        code, _, text, _ = fetch(url)
        out[name] = {"status": code, "names_host": (needle in text) if code == 200 else None}
    return out


def authn():
    code, _, text, _ = fetch(AUTHN + "/v1/operator/users")
    return {"no_token": {"status": code, "body": text.strip()[:40]}}


def wrangler_secrets(args):
    try:
        p = subprocess.run(["npx", "wrangler", "secret", "list"] + args, cwd=WORKTREE,
                           capture_output=True, text=True, timeout=120)
        names = sorted(set(re.findall(r'"name":\s*"([A-Z0-9_]+)"', p.stdout)))
        return {"ok": p.returncode == 0 and bool(names), "names": names,
                "error": None if p.returncode == 0 else (p.stderr.strip().splitlines() or ["?"])[-1][:200]}
    except Exception as e:
        return {"ok": False, "names": [], "error": e.__class__.__name__}


def secrets():
    cp = wrangler_secrets([])
    au = wrangler_secrets(["--name", "kotobase-authn"])
    return {"control_plane": {"ok": cp["ok"], "error": cp["error"],
                              "ADMIN_ALLOWED_IPS": "ADMIN_ALLOWED_IPS" in cp["names"],
                              "AUTHN_OPERATOR_TOKEN": "AUTHN_OPERATOR_TOKEN" in cp["names"],
                              "MURAKUMO_SERVICE_TOKEN": "MURAKUMO_SERVICE_TOKEN" in cp["names"]},
            "authn": {"ok": au["ok"], "error": au["error"],
                      "AUTHN_OPERATOR_TOKEN": "AUTHN_OPERATOR_TOKEN" in au["names"]}}


def operators():
    p = os.path.join(WORKTREE, "wrangler.research.jsonc")
    try:
        with open(p, encoding="utf-8") as f:
            m = re.search(r'"RESEARCH_OPERATORS"\s*:\s*"([^"]*)"', f.read())
        entries = sorted(e.strip() for e in (m.group(1).split(",") if m else []) if e.strip())
        return {"registry": entries, "count": len(entries)}
    except OSError as e:
        return {"registry": None, "count": None, "error": e.__class__.__name__}


def repo():
    out = {"worktree": WORKTREE}
    try:
        subprocess.run(["git", "fetch", "origin", "main"], cwd=WORKTREE, capture_output=True, text=True, timeout=120)
        head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=WORKTREE, capture_output=True, text=True).stdout.strip()
        main = subprocess.run(["git", "rev-parse", "origin/main"], cwd=WORKTREE, capture_output=True, text=True).stdout.strip()
        behind = subprocess.run(["git", "rev-list", "--count", "HEAD..origin/main"], cwd=WORKTREE, capture_output=True, text=True).stdout.strip()
        dirty = subprocess.run(["git", "status", "--porcelain"], cwd=WORKTREE, capture_output=True, text=True).stdout.strip()
        out.update({"head": head[:12], "origin_main": main[:12], "behind": int(behind or 0), "dirty": bool(dirty)})
    except Exception as e:
        out["error"] = e.__class__.__name__
    for name, rel in (("gate_source", "src/app_kotoba_cloud/admin_gate.cljk"),
                      ("gateway_source", "src/app_kotoba_cloud/admin_gateway.cljk"),
                      ("gate_test", "test/app_kotoba_cloud/admin_gate_test.cljk"),
                      ("adr", "docs/adr/2609181500-operator-console-ip-allowlist.md")):
        out[name] = os.path.exists(os.path.join(WORKTREE, rel))
    return out


def main():
    if not os.path.isdir(WORKTREE):
        print(json.dumps({"refused": "worktree missing: %s" % WORKTREE}))
        return
    g = gate()
    if all(g[f]["answer"] == "no-route" for f in ("v4", "v6")) and g["apex_dark_shape"]["status"] is None:
        print(json.dumps({"refused": "no live probe answered (this host offline, or the edge unreachable)"}))
        return
    report = {"schema": "akc-admin-ops.evidence.v1", "gate": g, "console": console(),
              "leak": leak(), "authn": authn(), "operators": operators()}
    if MONITOR:
        # stable: no secrets (a wrangler round trip), no repo sync, no times
        print(json.dumps(report, sort_keys=True, indent=1))
        return
    report["secrets"] = secrets()
    report["repo"] = repo()
    report["measured_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    print(json.dumps(report, sort_keys=True, indent=1))


if __name__ == "__main__":
    main()
