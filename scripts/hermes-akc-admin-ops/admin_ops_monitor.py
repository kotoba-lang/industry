#!/usr/bin/env python3
"""admin_ops_monitor.py - the monitor-mode face of admin_ops_evidence.py for
`hermes cron ... --monitor-script`: hermes passes no arguments to a monitor
script, so this runs the evidence script with --monitor (the stable subset:
no times, no latencies, no egress addresses). Unchanged bytes suppress the
agent; a change wakes it with the diff. Same directory, same python."""
import os
import subprocess
import sys

here = os.path.dirname(os.path.abspath(__file__))
sys.exit(subprocess.call([sys.executable, os.path.join(here, "admin_ops_evidence.py"), "--monitor"]))
