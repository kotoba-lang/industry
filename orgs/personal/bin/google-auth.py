#!/usr/bin/env python3
"""google-auth.py — per-account Google OAuth for the personal warehouse.

ALL secrets live in the macOS login Keychain — never on disk, never in git:
    service "google-oauth-client"   -> OAuth Desktop client {"client_id","client_secret"}
    service "google-oauth:<slug>"   -> refresh_token for that account

Scopes requested (read-only): gmail.readonly, calendar.readonly, drive.metadata.readonly.

ONE-TIME SETUP (browser required):
  1. GCP console (project com-junkawasaki-sip): enable Gmail API (+ Calendar/Drive if
     wanted), OAuth consent screen -> External/Testing, add all 4 Google accounts as
     test users, create an OAuth client of type "Desktop app", download the JSON.
  2. google-auth.py client-set ~/Downloads/client_secret_*.json   (stores -> Keychain,
     then DELETE the json)
  3. google-auth.py login <slug>          # per account; opens browser, stores refresh token

SCRIPT USE:
    google-auth.py token <slug>     # prints a fresh access token
    google-auth.py status           # which registry accounts have tokens
"""
import sys, os, json, subprocess, webbrowser, secrets as pysecrets
import urllib.request, urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler

BIN = os.path.dirname(os.path.abspath(__file__))
SCOPES = " ".join([
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/calendar.readonly",
    "https://www.googleapis.com/auth/drive.readonly",
])
CLIENT_SVC = "google-oauth-client"


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
        sys.exit("no OAuth client in Keychain — run: google-auth.py client-set <client_secret.json>")
    return json.loads(raw)


def token_post(data):
    req = urllib.request.Request("https://oauth2.googleapis.com/token",
                                 data=urllib.parse.urlencode(data).encode(),
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"token endpoint error {e.code}: {e.read().decode()[:300]}")


def cmd_client_set(path):
    with open(path) as f:
        j = json.load(f)
    j = j.get("installed") or j.get("web") or j
    kc_set(CLIENT_SVC, "personal-warehouse",
           json.dumps({"client_id": j["client_id"], "client_secret": j["client_secret"]}))
    print(f"stored client {j['client_id'][:20]}... in Keychain ({CLIENT_SVC})")
    print(f"now DELETE the json: rm '{path}'")


def cmd_login(slug):
    email = registry("email", slug)
    c = client()
    state = pysecrets.token_urlsafe(16)
    got = {}

    class H(BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            got.update({k: v[0] for k, v in q.items()})
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(f"OK — {email} authorized. Close this tab.".encode())
        def log_message(self, *a):
            pass

    srv = HTTPServer(("127.0.0.1", 0), H)
    redirect = f"http://127.0.0.1:{srv.server_port}/"
    url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
        "client_id": c["client_id"], "redirect_uri": redirect,
        "response_type": "code", "scope": SCOPES, "state": state,
        "access_type": "offline", "prompt": "consent", "login_hint": email,
    })
    print(f"authorize {email} in the browser (login_hint set — pick that account)...")
    print(f"AUTH_URL {url}", flush=True)  # printed for headless/driven consent
    if os.environ.get("NO_BROWSER_OPEN") != "1":
        webbrowser.open(url)
    while "code" not in got and "error" not in got:
        srv.handle_request()
    srv.server_close()
    if got.get("error"):
        sys.exit(f"consent error: {got['error']}")
    if got.get("state") != state:
        sys.exit("state mismatch — aborting")
    tok = token_post({"client_id": c["client_id"], "client_secret": c["client_secret"],
                      "code": got["code"], "redirect_uri": redirect,
                      "grant_type": "authorization_code"})
    rt = tok.get("refresh_token")
    if not rt:
        sys.exit("no refresh_token in response (already-consented client? revoke at "
                 "myaccount.google.com/permissions and retry)")
    kc_set(f"google-oauth:{slug}", email, rt)
    print(f"refresh token for {email} stored in Keychain (google-oauth:{slug})")


def cmd_token(slug):
    rt = kc_get(f"google-oauth:{slug}")
    if not rt:
        sys.exit(f"no refresh token for {slug} — run: google-auth.py login {slug}")
    c = client()
    tok = token_post({"client_id": c["client_id"], "client_secret": c["client_secret"],
                      "refresh_token": rt, "grant_type": "refresh_token"})
    print(tok["access_token"])


def cmd_status():
    have_client = "yes" if kc_get(CLIENT_SVC) else "NO (run client-set)"
    print(f"oauth client in Keychain: {have_client}")
    for line in registry("accounts").splitlines():
        slug, email, provider = line.split("\t")
        if provider != "google":
            continue
        ok = "token: yes" if kc_get(f"google-oauth:{slug}") else "token: NO (run login)"
        print(f"  {slug:<16} {email:<26} {ok}")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "client-set" and len(sys.argv) > 2:
        cmd_client_set(sys.argv[2])
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
