#!/usr/bin/env python3
"""Hand the BPMN scout its measurements. Decision-free, like hyakka_evidence.py.

The BPMN scout's upstream is NOT one pinned file the way ISIC's is — it is a
set of workspace mirrors, each measured here:

  cloud-itonami/org-apqc-pcf   facts/catalog.edn — 17 citations behind a live
                               fetch gate (`nbb tools/verify_citations.cljs`);
                               OMG BPMN 2.0 URLs are the mapping target
  cloud-itonami/apqc           kotoba/apqc-pcf.kotoba.edn — 713 seed rows
                               (13 L1 :authoritative, 700 :representative),
                               `bb kotoba/validate.clj` is its integrity gate
  cloud-itonami/org-omg-bpmn   BPMN 2.0 as EDN — model/validate/xml/execute,
                               zero-dep .cljc (ADR-2606272200)
  cloud-itonami/isco           data/isco-occupations.edn — 619 nodes, but the
                               ISIC seed's own coverage note records it as
                               UNPINNED (no source-url/sha256/fetched-at)

The mirror set lives in the west superproject, NOT in the app-hyakka worktree,
so this script measures the mirrors directly and hands the counts to the bot.
Everything downstream — corpus namespace, registry wiring, seed, ledger — is
the bot's job, on the ISIC precedent (commit 350c489, 10 files, gate-checked).

Exit code is load-bearing the same way: on any refusal this prints a REFUSED
banner and exits 0, so the bot RUNS and is told it is blind rather than being
silently skipped. Exit 0 with a report means the measurements landed.
"""
import os
import subprocess
import sys

# Hourly-throttle (see throttle.py): a full mirror measurement is due once
# per cooldown window; a throttled tick exits 0 with [SILENT].
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from throttle import gate, mark  # noqa: E402

gate("hyakka-bpmn-scout", hours=float(os.environ.get("HYAKKA_EVIDENCE_COOLDOWN_H", "24")))

SUPER = os.environ.get(
    "HYAKKA_BPMN_SUPERPROJECT", os.path.expanduser("~/github/com-junkawasaki"))
NBB = os.environ.get("HYAKKA_NBB", "/opt/homebrew/bin/nbb")
BB = os.environ.get("HYAKKA_BB", "/opt/homebrew/bin/bb")

MIRRORS = {
    "org-apqc-pcf": "orgs/cloud-itonami/org-apqc-pcf",
    "apqc": "orgs/cloud-itonami/apqc",
    "org-omg-bpmn": "orgs/kotoba-lang/org-omg-bpmn",
    "isco": "orgs/cloud-itonami/isco",
}


def refuse(why: str) -> None:
    print("REFUSED — no evidence was gathered this run.")
    print(why)
    print()
    print("Do not propose anything. A corpus proposal built on an unread mirror "
          "is a proposal built on nothing. Report this refusal and stop.")
    sys.exit(0)  # the bot must RUN and be told it is blind, not be skipped.


def run(cmd, cwd, timeout=300):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                          timeout=timeout)


def head_sha(mirror_path):
    r = run(("git", "rev-parse", "--short=12", "HEAD"), cwd=mirror_path)
    return r.stdout.strip() if r.returncode == 0 else "?"


def main() -> None:
    for name, rel in MIRRORS.items():
        p = os.path.join(SUPER, rel)
        if not os.path.isdir(p):
            refuse(f"mirror {name} not checked out at {p}")
        if not os.path.isdir(os.path.join(p, ".git")) and not os.path.exists(
                os.path.join(p, ".git")):
            refuse(f"{p} is not a git checkout")

    print("worktree\t~/.itonami/worktrees/hyakka-growth-bot (app-hyakka)")
    print(f"superproject\t{SUPER}")
    print()
    print("# BPMN corpus evidence — mirrors measured, nothing decided")
    print()

    # ── org-apqc-pcf: the citation gate is the only live-fetch check ──
    pcf = os.path.join(SUPER, "orgs/cloud-itonami/org-apqc-pcf")
    print("## org-apqc-pcf citation gate (live fetches; DRIFT = host drift, "
          "still exit 0)")
    r = run([NBB, "tools/verify_citations.cljs"], cwd=pcf, timeout=300)
    tail = (r.stdout.strip().splitlines() or ["(no output)"])[-6:]
    for l in tail:
        print(l)
    print(f"gate-exit\t{r.returncode}")
    print(f"head\t{head_sha(pcf)}")
    print()

    # ── apqc: seed integrity gate ──
    apqc = os.path.join(SUPER, "orgs/cloud-itonami/apqc")
    seed = os.path.join(apqc, "kotoba", "apqc-pcf.kotoba.edn")
    print("## apqc seed (PCF v7.4)")
    if os.path.exists(seed):
        r = run([BB, "validate.clj", "apqc-pcf.kotoba.edn"],
                cwd=os.path.join(apqc, "kotoba"), timeout=300)
        for l in (r.stdout.strip().splitlines() or ["(no output)"])[-6:]:
            print(l)
        print(f"gate-exit\t{r.returncode}")
    else:
        print(f"MISSING\t{seed}")
    print(f"head\t{head_sha(apqc)}")
    print()

    # ── org-omg-bpmn: the library that would parse/consume BPMN ──
    bpmn = os.path.join(SUPER, "orgs/kotoba-lang/org-omg-bpmn")
    print("## org-omg-bpmn (BPMN 2.0 as EDN)")
    for ns in ("model", "validate", "xml", "execute", "ports"):
        p = os.path.join(bpmn, "src", "bpmn", f"{ns}.cljc")
        print(f"{'present' if os.path.exists(p) else 'MISSING'}\t"
              f"src/bpmn/{ns}.cljc")
    print(f"head\t{head_sha(bpmn)}")
    print()

    # ── isco: recorded UNPINNED by the ISIC seed's coverage note ──
    isco = os.path.join(SUPER, "orgs/cloud-itonami/isco")
    occ = os.path.join(isco, "data", "isco-occupations.edn")
    print("## isco (recorded UNPINNED by hyakka.corpus.isic/coverage)")
    if os.path.exists(occ):
        size = os.path.getsize(occ)
        print(f"present\tdata/isco-occupations.edn ({size}B)")
    else:
        print("MISSING\tdata/isco-occupations.edn")
    print(f"head\t{head_sha(isco)}")
    print()

    # ── the ISIC precedent, in the worktree ──
    wt = os.path.expanduser("~/.itonami/worktrees/hyakka-growth-bot")
    for rel in ("src/hyakka/corpus/isic.cljc", "scripts/seed_isic.cljs",
                "test/hyakka/isic_test.cljs",
                "scripts/verify_source_proposal.cljs"):
        p = os.path.join(wt, rel)
        print(f"{'present' if os.path.exists(p) else 'MISSING'}\t{rel} (worktree)")
    print()
    print("MEASURED\tmirrors read; the corpus decision is the bot's, on the "
          "ISIC precedent: namespace -> registry wiring -> seed -> ledger, "
          "every claim's evidence a verbatim substring of the pinned bytes.")
    mark("hyakka-bpmn-scout")


if __name__ == "__main__":
    main()
