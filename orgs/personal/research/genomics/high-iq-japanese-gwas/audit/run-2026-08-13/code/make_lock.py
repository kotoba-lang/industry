"""Create the encrypted return archive and its SHA-256, for the result lock.

The passphrase is generated here, handed to 7z through the child process argv
only, and stored in the macOS login keychain. It is never printed, never
written to a report, and never placed in a shell command -- the Package A rule
is "do not record credentials ... in code, logs, filenames, reports, or command
history".

The archive is only accepted once it has been verified to open with the
passphrase read back out of the keychain, so a lost temp file cannot leave an
unopenable archive behind. The plaintext return directory is not removed.
"""
from __future__ import annotations

import hashlib
import pathlib
import secrets
import string
import subprocess
import sys

HOME = pathlib.Path.home()
SRC = HOME / "Downloads" / "KAWASAKI_PHASE5C_INDEPENDENT_RETURN"
ARCHIVE = HOME / "Downloads" / "KAWASAKI_PHASE5C_INDEPENDENT_RETURN.7z"
KC_SERVICE = "KAWASAKI_PHASE5C_INDEPENDENT_RETURN.7z"
KC_ACCOUNT = "phase5c-return-archive"

ALPHABET = string.ascii_letters + string.digits + "-_=+.@#%^"


def run(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(args, capture_output=True, text=True)


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    if not SRC.is_dir():
        print(f"FATAL: {SRC} not found", file=sys.stderr)
        return 1
    if ARCHIVE.exists():
        print(f"FATAL: {ARCHIVE} already exists; refusing to overwrite a lock "
              f"archive", file=sys.stderr)
        return 1

    # 32 chars from a 71-symbol alphabet ~= 196 bits.
    pw = "".join(secrets.choice(ALPHABET) for _ in range(32))

    print("creating encrypted archive (AES-256, header encryption) ...")
    r = run(["7z", "a", "-t7z", "-mx=9", "-mhe=on", "-p" + pw,
             str(ARCHIVE), str(SRC) + "/"])
    if r.returncode != 0:
        print("FATAL: 7z failed\n" + r.stdout[-2000:] + r.stderr[-2000:],
              file=sys.stderr)
        return 1

    # Store the passphrase before the only in-memory copy goes away.
    run(["security", "delete-generic-password", "-s", KC_SERVICE,
         "-a", KC_ACCOUNT])  # ignore failure: may not exist
    r = run(["security", "add-generic-password", "-s", KC_SERVICE,
             "-a", KC_ACCOUNT, "-w", pw, "-U",
             "-j", "Passphrase for the Phase 5C independent return archive "
                   "(result lock). Share with the custodian out of band."])
    if r.returncode != 0:
        print("FATAL: could not store the passphrase in the keychain; "
              "removing the archive so no unopenable file is left\n" + r.stderr,
              file=sys.stderr)
        ARCHIVE.unlink(missing_ok=True)
        return 1

    # Read it back out of the keychain and prove the archive opens with it.
    r = run(["security", "find-generic-password", "-s", KC_SERVICE,
             "-a", KC_ACCOUNT, "-w"])
    if r.returncode != 0:
        print("FATAL: passphrase not retrievable from the keychain",
              file=sys.stderr)
        ARCHIVE.unlink(missing_ok=True)
        return 1
    recovered = r.stdout.strip()

    t = run(["7z", "t", "-p" + recovered, str(ARCHIVE)])
    if t.returncode != 0:
        print("FATAL: archive does not verify with the stored passphrase",
              file=sys.stderr)
        ARCHIVE.unlink(missing_ok=True)
        return 1

    # A wrong passphrase must fail, otherwise the test above proves nothing.
    bad = run(["7z", "t", "-pNOT" + recovered, str(ARCHIVE)])
    if bad.returncode == 0:
        print("FATAL: archive verified under a wrong passphrase", file=sys.stderr)
        return 1

    # Header encryption must actually hide the member names.
    listing = run(["7z", "l", str(ARCHIVE)])
    leaked = "09_VERIFIER_ATTESTATION.md" in listing.stdout

    digest = sha256(ARCHIVE)
    size_mb = ARCHIVE.stat().st_size / 1e6

    print()
    print("archive                 :", ARCHIVE)
    print(f"size                    : {size_mb:.2f} MB")
    print("opens with stored pass  : YES")
    print("wrong passphrase rejected: YES")
    print("member names hidden     :", "NO -- header encryption failed" if leaked else "YES")
    print("keychain service        :", KC_SERVICE)
    print("keychain account        :", KC_ACCOUNT)
    print()
    print("SHA-256:")
    print(digest)

    (HOME / "Downloads" / "KAWASAKI_PHASE5C_INDEPENDENT_RETURN.7z.sha256").write_text(
        f"{digest}  {ARCHIVE.name}\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
