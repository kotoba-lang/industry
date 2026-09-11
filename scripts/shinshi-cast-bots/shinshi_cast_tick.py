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
    # kotoba-lang/text: org-chainagnostic-cacao's src moved from clojure.string
    # to kotoba.lang.text on 2026-09-09; without this entry every hourly round
    # died at load with `Could not find namespace: kotoba.lang.text` -- 49
    # consecutive hermes runs, 0 cast posts on shinshi.club from 09-09T06 JST.
    os.path.join(k, "text", "src"),
])
r = subprocess.run(
    ["kbb", "--backend", "sci", "--classpath", cp, os.path.join(root, "scripts", "shinshi-cast-bots", "post.cljs")],
    cwd=root, timeout=1800)
sys.exit(r.returncode)
