#!/usr/bin/env python3
"""pwned-dataset-ops: run the pwned collector (fetch -> validate -> publish R2)."""
import subprocess, sys
r = subprocess.run(["python3", "/Users/junkawasaki/github/com-junkawasaki/scripts/hermes-knowledge-datasets/pwned_sync.py"])
sys.exit(r.returncode)
