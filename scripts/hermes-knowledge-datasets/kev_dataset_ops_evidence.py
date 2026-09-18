#!/usr/bin/env python3
"""kev-dataset-ops: run the kev collector (fetch -> validate -> publish R2)."""
import subprocess, sys
r = subprocess.run(["python3", "/Users/junkawasaki/github/com-junkawasaki/scripts/hermes-knowledge-datasets/kev_sync.py"])
sys.exit(r.returncode)
