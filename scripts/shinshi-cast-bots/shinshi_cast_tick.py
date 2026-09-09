#!/usr/bin/env python3
"""Hermes --script shim for the daily cast posting round (no decisions here).

Runs post.cljs for every cast member: compose in-character (murakumo-main,
deterministic fallback), post to shinshi.club (verified write), attempt
aozora.app (BLOCKED banner until the Biscuit migration lands). Exit code
passes through: 0 handled / 1 local failure / 2 refused.
"""
import os, subprocess, sys

root = os.environ.get("COM_JUNKAWASAKI_ROOT") or os.environ.get(
    # legacy; `gftd` is retired (manifest/gftd-retirement.edn)
    "GFTD_ROOT", os.path.expanduser("~/github/com-junkawasaki"))
k = os.path.join(root, "orgs", "kotoba-lang")
cp = ":".join([
    os.path.join(root, "scripts", "shinshi-cast-bots"),
    os.path.join(k, "org-chainagnostic-cacao", "src"),
    os.path.join(k, "authority", "src"),
    os.path.join(k, "org-ietf-ed25519", "src"),
    os.path.join(k, "org-ietf-cbor", "src"),
])
r = subprocess.run(
    ["nbb", "--classpath", cp, os.path.join(root, "scripts", "shinshi-cast-bots", "post.cljs")],
    cwd=root, timeout=1800)
sys.exit(r.returncode)
