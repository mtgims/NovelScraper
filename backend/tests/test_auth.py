"""Auth & account-management tests: login, sessions, invites, admin controls.

Drives the real ASGI app via TestClient. Uses explicit per-request cookies (with
a cleared jar) so several identities can be exercised unambiguously. Run with the
project venv:

    cd backend && .venv/bin/python tests/test_auth.py
"""

import os, tempfile, pathlib, sys

TMP = tempfile.mkdtemp(prefix="ns_auth_")
os.environ["NOVELSCRAPER_DATA_DIR"] = str(pathlib.Path(TMP, "data"))
os.environ["NOVELSCRAPER_DB"] = str(pathlib.Path(TMP, "auth.db"))
PROFILES = pathlib.Path(TMP, "profiles"); PROFILES.mkdir()
os.environ["NOVELSCRAPER_PROFILE_DIR"] = str(PROFILES)
os.environ["NOVELSCRAPER_ADMIN_USERNAME"] = "admin"
os.environ["NOVELSCRAPER_ADMIN_PASSWORD"] = "admin-pw-123"

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from app.main import app
from app.auth import COOKIE_NAME

ok = []
def check(name, cond, extra=""):
    ok.append(bool(cond))
    print(f"[{name}] {'PASS' if cond else 'FAIL'} {extra}")

def as_(tok):
    return {COOKIE_NAME: tok} if tok else {}

def login(c, username, password):
    """Log in and return the raw session token (jar left cleared)."""
    r = c.post("/api/auth/login", json={"username": username, "password": password})
    tok = c.cookies.get(COOKIE_NAME)
    c.cookies.clear()
    return r, tok

with TestClient(app) as c:
    c.cookies.clear()

    # --- unauthenticated is locked out ---
    check("me-unauth-401", c.get("/api/auth/me").status_code == 401)
    check("data-unauth-401", c.get("/api/books").status_code == 401)

    # --- admin login ---
    bad, _ = login(c, "admin", "wrong")
    check("login-bad-401", bad.status_code == 401)
    lr, admin_tok = login(c, "admin", "admin-pw-123")
    check("login-ok", lr.status_code == 200 and lr.json()["is_admin"] is True)
    check("me-ok", c.get("/api/auth/me", cookies=as_(admin_tok)).json()["username"] == "admin")

    # --- invite-only registration (default) ---
    noinv = c.post("/api/auth/register",
                   json={"username": "bob", "password": "password123"})
    check("register-no-invite-403", noinv.status_code == 403, str(noinv.status_code))

    inv = c.post("/api/auth/invites", cookies=as_(admin_tok))
    check("create-invite-ok", inv.status_code == 200)
    code = inv.json()["code"]

    reg = c.post("/api/auth/register",
                 json={"username": "bob", "password": "password123", "invite_code": code})
    check("register-with-invite-ok",
          reg.status_code == 200 and reg.json()["is_admin"] is False, str(reg.status_code))
    bob_tok = c.cookies.get(COOKIE_NAME); c.cookies.clear()
    check("bob-me", c.get("/api/auth/me", cookies=as_(bob_tok)).json()["username"] == "bob")

    # invite is single-use
    reused = c.post("/api/auth/register",
                    json={"username": "carol", "password": "password123", "invite_code": code})
    check("invite-single-use-403", reused.status_code == 403, str(reused.status_code))

    # taken username (fresh valid invite)
    code2 = c.post("/api/auth/invites", cookies=as_(admin_tok)).json()["code"]
    taken = c.post("/api/auth/register",
                   json={"username": "bob", "password": "password123", "invite_code": code2})
    check("register-taken-409", taken.status_code == 409, str(taken.status_code))

    # --- non-admin can't reach admin endpoints ---
    check("bob-invites-403", c.post("/api/auth/invites", cookies=as_(bob_tok)).status_code == 403)
    check("bob-users-403", c.get("/api/auth/users", cookies=as_(bob_tok)).status_code == 403)

    # --- admin user management ---
    users = c.get("/api/auth/users", cookies=as_(admin_tok)).json()
    check("list-users", len(users) == 2)
    admin_id = next(u["id"] for u in users if u["username"] == "admin")
    bob_id = next(u["id"] for u in users if u["username"] == "bob")

    # can't demote the last active admin
    demote = c.patch(f"/api/auth/users/{admin_id}",
                     json={"is_admin": False}, cookies=as_(admin_tok))
    check("last-admin-guard-400", demote.status_code == 400, str(demote.status_code))

    # disable bob -> he can no longer log in, and his existing session dies
    dis = c.patch(f"/api/auth/users/{bob_id}",
                  json={"disabled": True}, cookies=as_(admin_tok))
    check("disable-ok", dis.status_code == 200 and dis.json()["disabled"] is True)
    check("disabled-session-401", c.get("/api/auth/me", cookies=as_(bob_tok)).status_code == 401)
    relogin, _ = login(c, "bob", "password123")
    check("disabled-login-401", relogin.status_code == 401)

    # --- logout revokes the session ---
    c.cookies.set(COOKIE_NAME, admin_tok)
    check("logout-204", c.post("/api/auth/logout").status_code == 204)
    check("post-logout-401", c.get("/api/auth/me", cookies=as_(admin_tok)).status_code == 401)

print(f"\nSUMMARY: {sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
