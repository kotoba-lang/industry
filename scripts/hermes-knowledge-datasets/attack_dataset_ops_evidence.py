#!/usr/bin/env python3
"""attack-dataset-ops: run the attack collector (fetch -> validate -> publish R2)."""
import subprocess, sys
r = subprocess.run(["python3", "/Users/junkawasaki/github/com-junkawasaki/scripts/hermes-knowledge-datasets/attack_sync.py"])
sys.exit(r.returncode)
