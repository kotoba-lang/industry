"""Prove the did provider answers, and answers differently, in six cases.

Run it against a real Hermes checkout:

    HERMES=~/.hermes/hermes-agent \\
    WORKSPACE=~/github/com-junkawasaki \\
      $HERMES/venv/bin/python scripts/hermes-dashboard-auth-did/probe.py

It mints its own biscuit from a fixed seed, so it needs no stored key and
leaves nothing behind.

The cases exist in pairs, and the pairs are the point. A provider that
accepted everything would pass A; one that rejected everything would pass
B, C and E. What has to hold is that the SAME provider does both, and that
the two failure kinds stay apart:

    B, C, E  -> None           the token is bad   -> the gate answers 401
    D, F     -> ProviderError  the CHECK is bad   -> the gate answers 503

F is the one worth keeping. nbb exits 1 when it cannot load a namespace,
which is the same 1 the verifier uses for `denied`, so a provider that read
the exit code alone would report every token as invalid the moment its
classpath broke -- 401 to callers whose credentials were fine, with nothing
anywhere saying the check had not run.
"""
from __future__ import annotations

import base64
import importlib.util
import os
import pathlib
import subprocess
import sys

HERMES = os.path.expanduser(os.environ.get("HERMES", "~/.hermes/hermes-agent"))
WORKSPACE = os.path.expanduser(
    os.environ.get("WORKSPACE", "~/github/com-junkawasaki")
)
NBB = os.environ.get("NBB", "nbb")

CLASSPATH = os.pathsep.join(
    os.path.join(WORKSPACE, p)
    for p in (
        "orgs/kotoba-lang/org-biscuitsec/src",
        "orgs/kotoba-lang/org-biscuitsec/test",
        "orgs/kotoba-lang/authority/src",
        "orgs/kotoba-lang/org-w3-did/src",
        "orgs/kotoba-lang/identity/src",
        "orgs/kotoba-lang/dev-protobuf/src",
    )
)

# A root key nobody has to store: the seed is in this file, and the token it
# signs reaches nothing outside this probe.
_MINT = """
(ns probe-mint
  (:require [biscuit.ed25519 :as e] [biscuit.token :as bt]))
(def root   (e/keypair (vec (range 32))))
(def device (e/keypair (vec (range 32 64))))
(def issued
  (bt/authority {:facts [['cap "graph-read" "kotoba://graph/acme"]
                         ['before "2099-01-01T00:00:00Z"]]
                 :next-public-key (:public device)
                 :root-private-key (:private root) :sign-fn e/sign-fn}))
(println (apply str (map #(.padStart (.toString % 16) 2 "0") (:public root))))
(println (pr-str issued))
"""


def mint() -> tuple[str, str]:
    src = pathlib.Path(os.environ.get("TMPDIR", "/tmp")) / "did-probe-mint.cljs"
    src.write_text(_MINT)
    done = subprocess.run(
        [NBB, "--classpath", CLASSPATH, str(src)],
        cwd=WORKSPACE, capture_output=True, text=True, timeout=300,
    )
    if done.returncode != 0:
        raise SystemExit(f"could not mint a probe token:\n{done.stderr[:500]}")
    root_hex, token = done.stdout.strip().splitlines()[-2:]
    return root_hex, base64.b64encode(token.encode()).decode()


def main() -> None:
    sys.path.insert(0, HERMES)
    root_hex, bearer = mint()
    os.environ["HERMES_DID_ROOT_PUBLIC_KEY"] = root_hex
    os.environ["HERMES_DID_WORKSPACE"] = WORKSPACE

    spec = importlib.util.spec_from_file_location(
        "did_plugin", os.path.join(HERMES, "plugins/dashboard_auth/did/__init__.py")
    )
    if spec is None or spec.loader is None:
        raise SystemExit(f"did plugin not installed under {HERMES}/plugins")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    from hermes_cli.dashboard_auth import ProviderError, assert_protocol_compliance

    assert_protocol_compliance(mod.DidBiscuitProvider)
    print("[protocol] DidBiscuitProvider implements the provider protocol")

    registered = []

    class Ctx:
        def register_dashboard_auth_provider(self, p):
            registered.append(p)

    mod.register(Ctx())
    if not registered:
        raise SystemExit(f"register() skipped: {mod.LAST_SKIP_REASON}")
    p = registered[0]
    print(f"[register] name={p.name} supports_token={p.supports_token} "
          f"supports_session={p.supports_session}")

    r = p.verify_token(token=bearer)
    print("[A good token   ] ->", r)
    assert r is not None and r.principal.startswith("did:key:"), \
        "a good token was not accepted"

    tampered = base64.b64encode(
        base64.b64decode(bearer).replace(b":signature [", b":signature [1 ", 1)
    ).decode()
    r = p.verify_token(token=tampered)
    print("[B tampered     ] ->", r)
    assert r is None, "a tampered token was ACCEPTED"

    r = p.verify_token(token="not base64 !!!")
    print("[C not a token  ] ->", r)
    assert r is None

    broken_nbb = mod.DidBiscuitProvider(
        workspace=WORKSPACE, root_public_key=root_hex, nbb="/nonexistent/nbb")
    try:
        broken_nbb.verify_token(token=bearer)
        raise AssertionError("a missing verifier returned instead of raising")
    except ProviderError as exc:
        print("[D verifier gone] -> ProviderError:", str(exc)[:70])

    r = p.verify_token(token=base64.b64encode(b"this is not a token {{{").decode())
    print("[E garbage b64  ] ->", r)
    assert r is None, "an unparseable token was treated as an outage or accepted"

    broken_cp = mod.DidBiscuitProvider(
        workspace=WORKSPACE, root_public_key=root_hex, nbb=NBB)
    broken_cp._classpath = "/nonexistent/src"
    try:
        broken_cp.verify_token(token=bearer)
        raise AssertionError("a broken classpath returned instead of raising")
    except ProviderError as exc:
        print("[F broken cp    ] -> ProviderError:", str(exc)[:80])

    print("ALL PLUGIN CHECKS PASSED")


if __name__ == "__main__":
    main()
