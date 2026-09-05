import json, os, glob, subprocess

REPOS = """open-banking open-cofog open-denki open-gas open-jpn-gov open-network
open-ports open-rail outreach port public-malak robot shiharai shinkansen
toshi-kozan vessel vin web4 yuubin""".split()

base = "/Users/junkawasaki/github/com-junkawasaki/orgs/cloud-itonami"
out = []
for r in REPOS:
    d = os.path.join(base, r)
    if not os.path.isdir(d):
        out.append(f"== {r} MISSING")
        continue
    entries = sorted(os.listdir(d))
    out.append(f"== {r}: {entries}")
    pj = os.path.join(d, "package.json")
    if os.path.isfile(pj):
        try:
            p = json.load(open(pj))
            deps = {**p.get("dependencies", {}), **p.get("devDependencies", {})}
            svel = {k: v for k, v in deps.items() if "svelte" in k.lower()}
            other = {k: v for k, v in deps.items() if "svelte" not in k.lower()}
            out.append(f"   npmdeps={len(deps)} svelte={svel} other={other}")
        except Exception as e:
            out.append(f"   package.json parse error: {e}")
    else:
        out.append("   no package.json")
    sv = [f for f in glob.glob(os.path.join(d, "**", "*.svelte"), recursive=True)
          if "node_modules" not in f]
    out.append(f"   svelte files: {sv[:6]}")
    # appview contents
    av = os.path.join(d, "appview")
    if os.path.isdir(av):
        aw = []
        for root, dirs, files in os.walk(av):
            dirs[:] = [x for x in dirs if x not in ("node_modules", ".git", "dist")]
            for f in files:
                aw.append(os.path.relpath(os.path.join(root, f), av))
        out.append(f"   appview files: {sorted(aw)}")

open("/tmp/stc_cands.txt", "w").write("\n".join(out))
print("wrote", len(out), "lines")
