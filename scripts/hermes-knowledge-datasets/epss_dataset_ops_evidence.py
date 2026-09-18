#!/usr/bin/env python3
"""epss-dataset-ops: run the epss collector (fetch -> validate -> publish R2)."""
import subprocess, sys
r = subprocess.run(["python3", "/Users/junkawasaki/github/com-junkawasaki/scripts/hermes-knowledge-datasets/epss_sync.py"])
sys.exit(r.returncode)
