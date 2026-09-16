#!/usr/bin/env python3
"""root-sec-maint evidence: decision-free security-hygiene measurement of the
com-junkawasaki superproject checked out in the current working directory.

Prints sections; the bot decides. Any internal failure prints a REFUSED
banner and exits 0, so a broken measurement is never reported as "clean".

Never prints a secret value: matched lines are masked to 6 leading chars per
long token run.
"""
import os
import re
import subprocess
import sys
import tempfile

VENDOR_PATTERNS = [
    "AKIA[0-9A-Z]{16}",
    "ASIA[0-9A-Z]{16}",
    "ghp_[A-Za-z0-9]{36}",
    "gho_[A-Za-z0-9]{36}",
    "github_pat_[A-Za-z0-9_]{40,}",
    "xox[baprs]-[A-Za-z0-9-]{10,}",
    "sk_live_[A-Za-z0-9]{20,}",
    "rk_live_[A-Za-z0-9]{20,}",
    "AIza[0-9A-Za-z_-]{35}",
    "sk-proj-[A-Za-z0-9_-]{20,}",
    "sk-ant-[A-Za-z0-9_-]{20,}",
    "sk-[A-Za-z0-9]{40,}",
    "glpat-[A-Za-z0-9_-]{20}",
    "shpat_[a-f0-9]{32}",
    "dop_v1_[a-f0-9]{64}",
    "npm_[A-Za-z0-9]{36}",
    "ya29\\.[0-9A-Za-z_-]{60,}",
    "SG\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}",
    "BEGIN.{0,10}PRIVATE KEY",
    "hooks\\.slack\\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/",
    "discord(app)?\\.com/api/webhooks/[0-9]+/",
    "[0-9]{8,10}:AA[A-Za-z0-9_-]{30,}",
    "hcp_[a-f0-9-]{20,}",
    r"https://[^/\s:@]+:[^@\s]{8,}@",
    (
        "(export[[:space:]]+[A-Z][A-Z_0-9]*"
        "(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)[A-Z_0-9]*="
        "|^[A-Z][A-Z_0-9]*(KEY|TOKEN|SECRET|PASSWORD)=)[\"']?"
        "[A-Za-z0-9+/_=.:-]{15,}"
    ),
]

CRED_NAME_RE = re.compile(
    r"(?i)(^|/)(\.env$|\.env\.[a-z]+$|id_rsa$|id_ed25519$|\.pem$|\.key$|"
    r"credentials|secret|token|cookie|\.netrc$|\.pgpass$|serviceaccount|api[-_]?key)"
)
CRED_NAME_ALLOW = re.compile(r"(?i)(example|sample|template|fixture|\.md$|test/)")


def run(cmd, timeout=180):
    p = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True,
                       text=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def mask(line):
    def _m(match):
        s = match.group(0)
        return s[:6] + "…" + str(len(s)) if len(s) > 6 else s
    return re.sub(r"[A-Za-z0-9+/_=\-.:@]{13,}", _m, line)[:140]


def section(title, body):
    print(f"== {title} ==")
    print(body.rstrip() or "(none)")
    print()


def main():
    rc, _, err = run(["git", "rev-parse", "--git-dir"])
    if rc != 0:
        print("REFUSED: cwd is not a git repository — measurement blind")
        return 0

    # sanity: scanner self-check (a silently-empty scan must never read as clean)
    probe = subprocess.run(["git", "grep", "--cached", "-I", "-l", "-e", "PATH"],
                           capture_output=True, text=True)
    if probe.returncode not in (0, 1) or not probe.stdout.strip():
        print("REFUSED: git grep probe found no hits for 'PATH' — scanner blind")
        return 0

    patfile = tempfile.NamedTemporaryFile("w", suffix=".pat", delete=False)
    patfile.write("\n".join(VENDOR_PATTERNS) + "\n")
    patfile.close()

    # 1) live secret shapes in HEAD
    rc, out, _ = run(["git", "grep", "--cached", "-I", "-l",
                      "-f", patfile.name], timeout=600)
    hits = [l for l in out.splitlines() if l.strip()]
    lines = []
    for f in hits[:10]:
        _, o, _ = run(["git", "grep", "--cached", "-I", "-n",
                       "-f", patfile.name, "--", f], timeout=120)
        first = o.splitlines()[:2]
        lines.append(f"{f}: " + " || ".join(mask(l) for l in first))
    os.unlink(patfile.name)
    section(f"1 SECRET-PATTERN-INDEX-SCAN ({len(hits)} files)", "\n".join(lines))

    # 2) untracked-but-not-ignored, credential-shaped => commit risk
    _, out, _ = run(["git", "status", "--porcelain"])
    untracked = [l[3:].strip() for l in out.splitlines() if l.startswith("?? ")]
    risky = [u for u in untracked
             if CRED_NAME_RE.search(u) and not CRED_NAME_ALLOW.search(u)]
    section(f"2 UNTRACKED-NOT-IGNORED total={len(untracked)} "
            f"CRED-SHAPED={len(risky)}", "\n".join(risky[:20]))

    # 3) tracked-but-ignored (ignore-rule drift)
    _, out, _ = run(["git", "ls-files", "-c", "-i", "--exclude-standard"],
                    timeout=300)
    tbi = [l.strip('"') for l in out.splitlines() if l.strip()]
    non_archive = [l for l in tbi
                   if not l.startswith("orgs/personal") and not l.startswith(".cursor")]
    section(f"3 TRACKED-BUT-IGNORED total={len(tbi)} "
            f"NON-ARCHIVE-NON-CURSOR={len(non_archive)}",
            "\n".join(non_archive[:20]))

    # 4) npm audit (root lockfile)
    rc, out, err = run("npm audit --json", timeout=300)
    sev = "N/A"
    try:
        j = out.strip().split("{", 1)
        data = __import__("json").loads("{" + j[1])
        meta = data.get("metadata", {}).get("vulnerabilities", {})
        sev = ", ".join(f"{k}={v}" for k, v in meta.items() if v) or "0"
    except Exception:
        sev = f"PARSE-FAIL rc={rc} {(err or out)[:120]}"
    section("4 NPM-AUDIT (root)", f"vulnerabilities: {sev}")

    # 5) history: credential-shaped files ADDED in the last 14 days
    rc, out, _ = run(["git", "log", "--since=14 days ago", "--diff-filter=A",
                      "--name-only", "--pretty=format:%h"], timeout=600)
    newfiles, cur = [], ""
    for l in out.splitlines():
        if not l.strip():
            continue
        if CRED_NAME_RE.search(l.strip()) and not CRED_NAME_ALLOW.search(l) \
                and not l.startswith(("orgs/personal", '"orgs/personal',
                                      ".claude/skills",
                                      ".agents/skills", "90-docs")):
            newfiles.append(l.strip()[:160])
    dedup = list(dict.fromkeys(newfiles))[:20]
    section(f"5 HISTORY-NEW-CRED-SHAPED-FILES-14D ({len(dedup)})",
            "\n".join(dedup))

    # 6) hook gate
    gate = "MISSING"
    for hp in (".git/hooks/pre-commit",):
        gp = hp
        if os.path.islink(os.path.abspath(".git")):
            rc, o, _ = run(["git", "rev-parse", "--git-path", "hooks/pre-commit"])
            gp = o.strip() or hp
        if os.path.exists(gp):
            body = open(gp).read(4000)
            if re.search(r"(?i)gitleaks|truffle|secret[-_ ]?scan", body):
                gate = "HAS-SCANNER"
            elif "annex" in body:
                gate = "annex-only (no secret scanner)"
            else:
                gate = "present, no scanner seen"
    section("6 PRE-COMMIT-GATE", gate)

    if not hits and not risky:
        print("STATE: CLEAN on sections 1-2 (judge 3-6 per SOUL levers)")
    else:
        print("STATE: FINDINGS PRESENT — pick at most one lever, open at most one PR")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # never report a broken measurement as success
        print(f"REFUSED: evidence script crashed ({type(e).__name__}: {e}) — blind")
        sys.exit(0)
