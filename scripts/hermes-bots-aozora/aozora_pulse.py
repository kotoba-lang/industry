#!/usr/bin/env python3
# Hermes cron shim for the daily bot pulse (no-agent job: the script IS the
# job). Hermes runs cron --script files as bash or Python and nothing else,
# so this is Python for the same reason hyakka_evidence.py is: the file holds
# no decision — every decision lives in pulse.cljs / core.cljc.
#
# Exit code passes through from pulse.cljs (0 handled/BLOCKED-with-banner,
# 1 local failure, 2 REFUSED). stdout is the receipt Hermes stores.
import os
import subprocess
import sys

ROOT = os.environ.get("COM_JUNKAWASAKI_ROOT") or os.environ.get(
    # legacy; `gftd` is retired (manifest/gftd-retirement.edn)
    "GFTD_ROOT", os.path.expanduser("~/github/com-junkawasaki"))
K = os.path.join(ROOT, "orgs", "kotoba-lang")
CLASSPATH = ":".join([
    os.path.join(ROOT, "scripts", "hermes-bots-aozora"),
    os.path.join(K, "org-chainagnostic-cacao", "src"),
    os.path.join(K, "authority", "src"),
    os.path.join(K, "org-ietf-ed25519", "src"),
    os.path.join(K, "org-ietf-cbor", "src"),
])
SCRIPT = os.path.join(ROOT, "scripts", "hermes-bots-aozora", "pulse.cljs")

r = subprocess.run(["nbb", "--classpath", CLASSPATH, SCRIPT], timeout=600)
sys.exit(r.returncode)
