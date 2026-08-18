#!/usr/bin/env python3
"""Record a dispatch-namespace actor's address in its blueprint.

The catalog is built from blueprint.edn, so an actor that is deployed and
answering but carries no :endpoint is invisible to `fleet_search callable=true`
— which is exactly the drift this whole cleanup has been removing, reintroduced
by deploying faster than the records were updated.

The address is NOT the worker's own URL, because a user Worker in a dispatch
namespace does not have one. It is reachable only through the dispatch Worker,
so that is what gets recorded:

    https://itonami-fleet-dispatch.04-feasts-minded.workers.dev/<repo>

:endpoint-kind :dispatch-namespace marks it as different in kind from
:workers-dev and :pages-dev — the host is shared, the path segment is the
actor, and the Worker cannot be reached any other way.
"""
import base64
import json
import os
import subprocess
import sys

DISPATCH = "https://itonami-fleet-dispatch.04-feasts-minded.workers.dev"


def gh(*args, stdin=None):
    r = subprocess.run(["gh", *args], capture_output=True, text=True, input=stdin)
    if r.returncode:
        raise RuntimeError(r.stderr.strip()[:300])
    return r.stdout


def add(repo, apply):
    meta = json.loads(gh("api", f"repos/cloud-itonami/{repo}/contents/blueprint.edn"))
    src = base64.b64decode(meta["content"]).decode("utf-8")
    if ":itonami.blueprint/endpoint" in src:
        return f"{repo}: already addressed"

    url = f"{DISPATCH}/{repo}"
    note = (
        " ;; Deployed into the ai-gftd-repository-dispatch namespace (Workers for\n"
        " ;; Platforms). A user Worker there has no URL of its own — it is reachable\n"
        " ;; only through the dispatch Worker, so the address is the router plus this\n"
        " ;; actor's repository name, which is also the catalog's :repo key.\n"
        " ;; /health answers JSON, so it is declared as the probe path.\n"
        f' :itonami.blueprint/endpoint "{url}"\n'
        " :itonami.blueprint/endpoint-kind :dispatch-namespace\n"
        ' :itonami.blueprint/health-path "/health"'
    )
    i = src.rstrip().rfind("}")
    out = src.rstrip()[:i].rstrip() + "\n" + note + "}\n"
    if not apply:
        return f"{repo}: would add {url}"

    body = {
        "message": (
            "blueprint: record the dispatch-namespace address\n\n"
            f"{url}\n\n"
            "The actor is deployed and /health answers 200, but the fleet catalog is "
            "built from this file — so without an endpoint it stayed invisible to "
            "`fleet_search callable=true` while being perfectly callable. That gap "
            "between the record and the deployment is the thing this cleanup has "
            "spent its time removing, and deploying faster than the records were "
            "updated reintroduced it.\n\n"
            "The address is the dispatch Worker plus this actor's repository name. A "
            "user Worker in a dispatch namespace has no URL of its own, which is why "
            ":endpoint-kind is :dispatch-namespace rather than :workers-dev — the "
            "host is shared and the path segment is the actor.\n\n"
            "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
        ),
        "content": base64.b64encode(out.encode()).decode(),
        "sha": meta["sha"],
        "branch": "main",
    }
    p = f"/tmp/bp-{repo}.json"
    with open(p, "w") as f:
        json.dump(body, f)
    gh("api", f"repos/cloud-itonami/{repo}/contents/blueprint.edn", "-X", "PUT", "--input", p)
    os.unlink(p)
    return f"{repo}: added"


if __name__ == "__main__":
    apply = "--apply" in sys.argv
    for repo in [a for a in sys.argv[1:] if not a.startswith("--")]:
        try:
            print("  " + add(repo, apply))
        except Exception as e:
            print(f"  {repo}: FAILED {e}")
