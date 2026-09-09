#!/usr/bin/env python3
"""Hermes --script shim for the weekly producer round (no decisions here).

Runs produce.cljs: mint the next batch of factory characters not yet in the
catalog, land actress/profile/intro rows in D1, verify by counting them
back. Exit code passes through."""
import os, subprocess, sys

root = os.environ.get("COM_JUNKAWASAKI_ROOT") or os.environ.get(
    # legacy; `gftd` is retired (manifest/gftd-retirement.edn)
    "GFTD_ROOT", os.path.expanduser("~/github/com-junkawasaki"))
r = subprocess.run(
    ["nbb", "--classpath", os.path.join(root, "scripts", "shinshi-cast-bots"),
     os.path.join(root, "scripts", "shinshi-cast-bots", "produce.cljs"), "--count", "12"],
    cwd=root, timeout=1800)
sys.exit(r.returncode)
