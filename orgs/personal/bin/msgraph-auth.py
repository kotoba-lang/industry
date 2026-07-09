#!/usr/bin/env python3
"""msgraph-auth.py — per-account Microsoft (Entra/M365) OAuth for the personal warehouse.

Device-code flow for a PUBLIC client (no secret). ALL tokens live in the macOS
login Keychain — never on disk, never in git:
    service "msgraph-oauth-client"  -> {"client_id": ..., "tenant": ...}
    service "msgraph-oauth:<slug>"  -> refresh_token (rotated on every refresh)

Scopes: offline_access Mail.Read Calendars.Read Files.Read User.Read (delegated).

ONE-TIME SETUP:
  1. entra.microsoft.com (gftd.co.jp tenant) -> App registrations -> New:
     name "personal-warehouse", supported accounts = this org only,
     NO redirect URI needed; under Authentication enable
     "Allow public client flows" = Yes. Copy the Application (client) ID.
  2. msgraph-auth.py client-set <client_id> [tenant]   # tenant default: organizations
  3. msgraph-auth.py login <slug>     # prints a device code; approve in any browser

SCRIPT USE:
    msgraph-auth.py token <slug>    # prints a fresh access token
    msgraph-auth.py status
"""
import sys, os, json, time, subprocess
import urllib.request, urllib.parse, urllib.error

BIN = os.path.dirname(os.path.abspath(__file__))
SCOPES = "offline_access Mail.Read Calendars.Read Files.Read User.Read"
CLIENT_SVC = "msgraph-oauth-client"


def registry(*args):
    out = subprocess.run([os.path.join(BIN, "registry"), *args],
                         capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"registry {' '.join(args)}: not found")
    return out.stdout.strip()


def kc_get(service):
    out = subprocess.run(["security", "find-generic-password", "-s", service, "-w"],
                         capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else None


def kc_set(service, account, secret):
    subprocess.run(["security", "add-generic-password", "-U",
                    "-s", service, "-a", account, "-w", secret], check=True,
                   capture_output=True)


def client():
    raw = kc_get(CLIENT_SVC)
    if not raw:
        sys.exit("no client in Keychain — run: msgraph-auth.py client-set <client_id> [tenant]")
    return json.loads(raw)


def post(url, data):
    req = urllib.request.Request(url, data=urllib.parse.urlencode(data).encode(),
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode() or "{}")


def endpoints(c):
    base = f"https://login.microsoftonline.com/{c.get('tenant', 'organizations')}/oauth2/v2.0"
    return base + "/devicecode", base + "/token"


def cmd_client_set(client_id, tenant="organizations"):
    kc_set(CLIENT_SVC, "personal-warehouse",
           json.dumps({"client_id": client_id, "tenant": tenant}))
    print(f"stored Entra public client {client_id[:12]}... (tenant={tenant}) in Keychain")


def cmd_login(slug):
    email = registry("email", slug)
    c = client()
    dc_url, tok_url = endpoints(c)
    dc = post(dc_url, {"client_id": c["client_id"], "scope": SCOPES})
    if "device_code" not in dc:
        sys.exit(f"devicecode error: {dc.get('error_description', dc)}")
    print(f"\n>>> {dc['message']}\n>>> sign in as {email}\n")
    interval = int(dc.get("interval", 5))
    while True:
        time.sleep(interval)
        tok = post(tok_url, {"client_id": c["client_id"],
                             "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                             "device_code": dc["device_code"]})
        err = tok.get("error")
        if err == "authorization_pending":
            continue
        if err == "slow_down":
            interval += 5
            continue
        if err:
            sys.exit(f"login failed: {tok.get('error_description', err)}")
        break
    kc_set(f"msgraph-oauth:{slug}", email, tok["refresh_token"])
    print(f"refresh token for {email} stored in Keychain (msgraph-oauth:{slug})")


def cmd_token(slug):
    rt = kc_get(f"msgraph-oauth:{slug}")
    if not rt:
        sys.exit(f"no refresh token for {slug} — run: msgraph-auth.py login {slug}")
    c = client()
    _, tok_url = endpoints(c)
    tok = post(tok_url, {"client_id": c["client_id"], "grant_type": "refresh_token",
                         "refresh_token": rt, "scope": SCOPES})
    if "access_token" not in tok:
        sys.exit(f"refresh failed: {tok.get('error_description', tok)}")
    # Microsoft rotates refresh tokens — persist the new one each time.
    if tok.get("refresh_token"):
        kc_set(f"msgraph-oauth:{slug}", registry("email", slug), tok["refresh_token"])
    print(tok["access_token"])


def cmd_status():
    have = "yes" if kc_get(CLIENT_SVC) else "NO (run client-set)"
    print(f"entra client in Keychain: {have}")
    for line in registry("accounts").splitlines():
        slug, email, provider = line.split("\t")
        if provider != "microsoft":
            continue
        ok = "token: yes" if kc_get(f"msgraph-oauth:{slug}") else "token: NO (run login)"
        print(f"  {slug:<16} {email:<26} {ok}")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "client-set" and len(sys.argv) > 2:
        cmd_client_set(*sys.argv[2:4])
    elif cmd == "login" and len(sys.argv) > 2:
        cmd_login(sys.argv[2])
    elif cmd == "token" and len(sys.argv) > 2:
        cmd_token(sys.argv[2])
    elif cmd == "status":
        cmd_status()
    else:
        sys.stderr.write(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
