"""Dashboard auth provider: a bearer token is a biscuit delegation.

Hermes's own answer to "who is calling" is a random token minted for the life
of a process, and the boundary around it is file ownership -- `~/.hermes` 0700,
`.env` 0600. That is a real boundary and it is a LOCAL one: it says the caller
can read a file on this machine, and it cannot say who they are anywhere else.

This provider carries a name instead. The bearer token is a biscuit whose
authority block was signed by a root key and whose later blocks may only
NARROW what the first one granted; the principal is the `did:key` the
delegation names. Verifying it needs the root PUBLIC key and nothing else, so
the same token is checkable by this dashboard, by a remote, by a Worker and by
another actor, none of whom have to be given a secret.

## Why this file contains no cryptography

There is exactly one authorization decider in this workspace and it is not
here (ADR-2608197300 §3). `verify_token` shells out to
`scripts/identity-verify.cljs`, which runs the chain the ADR fixes:

    biscuit.token/verify        -- is the chain signed from the root key
    biscuit.kotoba/->delegated  -- what does it grant, after attenuation
    authority.chain/authorize   -- does that cover what was asked
    identity.startup/resolve-state

A Python reimplementation would be a SECOND decider: two answers that agree
until the day they do not, with no test that would notice. So this file is a
transport -- it turns an HTTP header into stdin and an exit code into a
protocol answer.

## The exit codes are the contract

The verifier distinguishes three outcomes, and this provider needs all three
because the `DashboardAuthProvider` protocol distinguishes the same three:

    0  verified/allowed  -> TokenPrincipal   (this caller is who they say)
    1  denied            -> None             (not recognised; try the next
                                              provider, per the protocol)
    3  undecided         -> ProviderError    (COULD NOT ANSWER: nbb missing,
                                              classpath broken, root key
                                              unreadable) -> the gate answers
                                              503 rather than 401

Collapsing 3 into 1 is the failure this workspace keeps finding (ADR-2608136000):
a check that could not run returning the same value as a check that ran and
found nothing. Here it would be worse than silent -- "the verifier is not
installed" would read as "your token is invalid", and every caller would be
told to fix a token that was fine.

## What this provider does not do

It is token-only: `supports_session = False`, so Hermes never offers it on the
login page and never hands it a cookie. Turning a delegation into an
interactive session is a second question (which factor proved a person is
present) and its own work; the ADR routes that through
`kotoba-lang/authentication`, not through here.
"""
from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
from typing import Optional

from hermes_cli.dashboard_auth import (
    DashboardAuthProvider,
    ProviderError,
    Session,
    TokenPrincipal,
)

logger = logging.getLogger(__name__)

LAST_SKIP_REASON: Optional[str] = None

#: The classpath `scripts/identity-verify.cljs` documents, relative to the
#: workspace root. `org-biscuitsec/test` is on it because that repo holds no
#: crypto by design and injects real Ed25519 from its test tree; `dev-protobuf`
#: is there for the wire decoder that reads tokens minted by other biscuit
#: implementations.
_CLASSPATH_PARTS = (
    "orgs/kotoba-lang/org-biscuitsec/src",
    "orgs/kotoba-lang/org-biscuitsec/test",
    "orgs/kotoba-lang/authority/src",
    "orgs/kotoba-lang/org-w3-did/src",
    "orgs/kotoba-lang/identity/src",
    "orgs/kotoba-lang/dev-protobuf/src",
)

_VERIFIER = "scripts/identity-verify.cljs"

#: A bearer token reaches the verifier by being spliced into an EDN document,
#: so its character set has to be one that cannot close a string or open a
#: form. base64 (with the URL-safe alphabet allowed) is exactly that, and this
#: is where that is enforced -- not by trusting the encoder upstream.
_B64 = re.compile(r"^[A-Za-z0-9+/=_-]+$")

_HEX32 = re.compile(r"^[0-9a-fA-F]{64}$")

_TIMEOUT_SECONDS = 20

# Exit codes of scripts/identity-verify.cljs. Named because a bare 3 in an
# `elif` is the kind of thing that gets "simplified" into an else branch.
_EXIT_OK = 0
_EXIT_DENIED = 1
_EXIT_UNDECIDED = 3


class DidBiscuitProvider(DashboardAuthProvider):
    """Verify a biscuit delegation and report the did:key it names."""

    name = "did"
    display_name = "DID (biscuit delegation)"

    supports_token = True
    #: No login flow. See the module docstring: a delegation is authority, and
    #: turning it into an interactive session is a different question.
    supports_session = False
    supports_password = False

    def __init__(self, *, workspace: str, root_public_key: str,
                 nbb: str, kinds: Optional[str] = None) -> None:
        if not _HEX32.match(root_public_key):
            raise ValueError(
                "HERMES_DID_ROOT_PUBLIC_KEY must be 64 hex characters "
                "(a 32-byte Ed25519 public key)"
            )
        verifier = os.path.join(workspace, _VERIFIER)
        if not os.path.isfile(verifier):
            raise ValueError(f"verifier not found at {verifier}")
        self._workspace = workspace
        self._verifier = verifier
        self._root_public_key = root_public_key.lower()
        self._nbb = nbb
        self._kinds = kinds
        self._classpath = os.pathsep.join(
            os.path.join(workspace, p) for p in _CLASSPATH_PARTS
        )

    # ── the only method that decides anything, and it decides nothing ──

    def verify_token(self, *, token: str) -> Optional[TokenPrincipal]:
        token = (token or "").strip()
        # Not recognised, not an outage: a token this provider cannot even
        # shape-check belongs to somebody else's provider.
        if not token or not _B64.match(token):
            return None

        request = (
            "{:token-edn-b64 \"%s\" :root-public-key \"%s\" :format :json%s}"
            % (
                token,
                self._root_public_key,
                (" :kinds %s" % self._kinds) if self._kinds else "",
            )
        )
        try:
            done = subprocess.run(
                [self._nbb, "--classpath", self._classpath, _VERIFIER],
                cwd=self._workspace,
                input=request,
                capture_output=True,
                text=True,
                timeout=_TIMEOUT_SECONDS,
            )
        except FileNotFoundError as exc:
            raise ProviderError(f"nbb not executable: {exc}") from exc
        except subprocess.TimeoutExpired as exc:
            raise ProviderError(
                f"verifier did not answer within {_TIMEOUT_SECONDS}s"
            ) from exc

        # The BODY is the answer; the exit code only corroborates it.
        #
        # Reading the code alone is a trap that was measured rather than
        # imagined: nbb exits 1 when it cannot load a namespace, which is the
        # same 1 the verifier uses for `denied`. A workspace with a broken
        # classpath would therefore have reported every token as invalid --
        # 401 to a caller whose credential was fine, and no sign anywhere that
        # the check had not run. A verifier that produced no verdict has not
        # answered, whatever it exited with.
        answer = None
        tail = (done.stdout or "").strip().splitlines()
        if tail:
            try:
                answer = json.loads(tail[-1])
            except ValueError:
                answer = None
        decision = (answer or {}).get("identity.verify/decision")

        if decision is None:
            raise ProviderError(
                "identity verifier produced no verdict "
                f"(exit {done.returncode}): "
                f"{(done.stdout or '').strip()[:200]} "
                f"{(done.stderr or '').strip()[:200]}"
            )

        if decision == "denied":
            # Verified and refused. The protocol wants None so the gate can
            # fall through to another provider; the reason is logged, never
            # returned, because a caller that learns WHY a token failed learns
            # what a working one looks like.
            logger.info(
                "dashboard-auth-did: token rejected (%s)",
                (answer or {}).get("identity.verify/reason"),
            )
            return None

        if decision == "undecided":
            # The verifier said so itself: it could not run the check.
            raise ProviderError(
                "identity verifier could not answer: "
                f"{(answer or {}).get('identity.verify/reason')}"
            )

        if decision not in ("verified", "allowed"):
            raise ProviderError(f"verifier returned unknown decision {decision!r}")

        if done.returncode != _EXIT_OK:
            # Body and code disagree. Refuse rather than pick one.
            raise ProviderError(
                f"verifier said {decision!r} but exited {done.returncode}"
            )

        holder = answer.get("identity.verify/holder")
        if not holder:
            # The token verified but named nobody. There is no principal to
            # report, and reporting the provider's own name instead would
            # hand out an identity nobody delegated.
            raise ProviderError("verifier returned no holder did")

        scopes: list[str] = []
        for grant in answer.get("identity.verify/grants") or []:
            for resource in grant.get("resources") or []:
                scopes.append(f"{grant.get('kind')}:{resource}")

        logger.info(
            "dashboard-auth-did: accepted %s with %d scope(s)",
            holder, len(scopes),
        )
        return TokenPrincipal(
            principal=holder, provider=self.name, scopes=tuple(scopes)
        )

    # ── the session half of the protocol, which this provider is not ──
    #
    # Abstract on the base class, so they have to exist. They raise rather
    # than return None: `supports_session = False` means the gate never calls
    # them, and a stub that quietly returned None would turn a routing bug
    # into an anonymous session.

    def start_login(self, *, redirect_uri: str):
        raise NotImplementedError("did provider is token-only")

    def complete_login(self, *, code: str, state: str,
                       code_verifier: str, redirect_uri: str) -> Session:
        raise NotImplementedError("did provider is token-only")

    def verify_session(self, *, access_token: str) -> Optional[Session]:
        raise NotImplementedError("did provider is token-only")

    def refresh_session(self, *, refresh_token: str) -> Session:
        raise NotImplementedError("did provider is token-only")

    def revoke_session(self, *, refresh_token: str) -> None:
        # Documented as best-effort and must not raise.
        return None


def register(ctx) -> None:
    """Plugin entry — called by the plugin loader at startup.

    Registers only when a root public key AND a workspace holding the verifier
    are both configured. A dashboard that has neither is left exactly as it
    was: this plugin adds no provider, so it cannot change who gets in.
    """
    global LAST_SKIP_REASON
    LAST_SKIP_REASON = None

    root_key = (os.environ.get("HERMES_DID_ROOT_PUBLIC_KEY") or "").strip()
    workspace = (os.environ.get("HERMES_DID_WORKSPACE") or "").strip()
    nbb = (os.environ.get("HERMES_DID_NBB") or "").strip() or (
        shutil.which("nbb") or "nbb"
    )
    kinds = (os.environ.get("HERMES_DID_KINDS") or "").strip() or None

    if not root_key or not workspace:
        LAST_SKIP_REASON = (
            "set HERMES_DID_ROOT_PUBLIC_KEY (64 hex chars) and "
            "HERMES_DID_WORKSPACE (the com-junkawasaki checkout holding "
            f"{_VERIFIER}) to enable the did provider"
        )
        logger.info("dashboard-auth-did: %s", LAST_SKIP_REASON)
        return

    try:
        provider = DidBiscuitProvider(
            workspace=workspace, root_public_key=root_key,
            nbb=nbb, kinds=kinds,
        )
    except ValueError as exc:
        LAST_SKIP_REASON = f"DidBiscuitProvider construction failed: {exc}"
        logger.warning("dashboard-auth-did: %s", LAST_SKIP_REASON)
        return

    ctx.register_dashboard_auth_provider(provider)
    logger.info(
        "dashboard-auth-did: registered (workspace=%s, nbb=%s, "
        "root_key=%s…)",
        workspace, nbb, root_key[:8],
    )
