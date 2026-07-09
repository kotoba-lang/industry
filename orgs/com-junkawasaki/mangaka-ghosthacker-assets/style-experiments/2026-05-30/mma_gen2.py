#!/usr/bin/env python3
"""MMAudio gen for cut05 (shrine) + cut06 (rooftop ECU tear)."""
import sys; sys.path.insert(0, "/tmp")
from mma_gen import build_workflow, post_prompt, wait_for_pid, find_output_mp4, GALLERY, DEPLOY
import shutil

JOBS = [
    ("animeka_cut05.mp4",
     "soft wind through autumn leaves, distant temple bell, mountain ambience, sparrow",
     "music, dialogue, voices, footsteps loud"),
    ("animeka_cut06.mp4",
     "single quiet sniffle, soft breathing, gentle wind, intimate room tone, very quiet",
     "music, dialogue, loud, screaming, crowd"),
]
for vf, prompt, neg in JOBS:
    cut = vf.replace("animeka_","").replace(".mp4","")
    print(f"\n=== {cut} ===")
    wf = build_workflow(vf, prompt, neg)
    pid = post_prompt(wf)
    print(f"  pid={pid}")
    e = wait_for_pid(pid, deadline_s=900)
    if not e: print("  TIMEOUT"); continue
    s = e.get("status",{}).get("status_str")
    print(f"  status={s}")
    if s != "success":
        print(f"  msgs={e.get('status',{}).get('messages',[])[:2]}"); continue
    out = find_output_mp4(e, f"mma_animeka_{cut}")
    if not out: print("  no out"); continue
    dst = GALLERY / f"animeka-{cut}-mma.mp4"
    shutil.copy2(out, dst)
    shutil.copy2(out, DEPLOY / f"animeka-{cut}-mma.mp4")
    print(f"  ✓ {dst.name} ({dst.stat().st_size//1024}KB)")
