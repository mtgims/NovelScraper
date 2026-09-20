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
    me_resp = c.get("/api/auth/me", cookies=as_(admin_tok))
    check("me-ok", me_resp.json()["username"] == "admin")
    # An authenticated request re-issues (slides) the session cookie, so an
    # actively-used session never hits the cookie's absolute max-age mid-read.
    sc = me_resp.headers.get("set-cookie", "")
    check("session-cookie-slid", "ns_session=" in sc and "max-age=" in sc.lower(), sc[:70])

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

    # --- invite management: delete one, clear used/expired ---
    tmp_code = c.post("/api/auth/invites", cookies=as_(admin_tok)).json()["code"]
    check("delete-invite-204",
          c.delete(f"/api/auth/invites/{tmp_code}", cookies=as_(admin_tok)).status_code == 204)
    check("deleted-invite-unusable",
          c.post("/api/auth/register",
                 json={"username": "zed", "password": "password123", "invite_code": tmp_code}
                 ).status_code == 403)
    c.cookies.clear()
    cleared = c.delete("/api/auth/invites", cookies=as_(admin_tok))  # code (used) is spent
    check("clear-spent-200",
          cleared.status_code == 200 and cleared.json()["deleted"] >= 1, str(cleared.json()))
    remaining = {i["code"] for i in c.get("/api/auth/invites", cookies=as_(admin_tok)).json()}
    check("clear-kept-active-dropped-used", code2 in remaining and code not in remaining)

    # --- non-admin can't reach admin endpoints ---
    check("bob-invites-403", c.post("/api/auth/invites", cookies=as_(bob_tok)).status_code == 403)
    check("bob-del-invites-403", c.delete("/api/auth/invites", cookies=as_(bob_tok)).status_code == 403)
    check("bob-users-403", c.get("/api/auth/users", cookies=as_(bob_tok)).status_code == 403)

    # --- open-signup toggle (admin) + public /config ---
    check("config-default-closed",
          c.get("/api/auth/config").json()["allow_open_signup"] is False)
    check("nonadmin-settings-403",
          c.put("/api/settings", json={"allow_open_signup": True},
                cookies=as_(bob_tok)).status_code == 403)
    en = c.put("/api/settings", json={"allow_open_signup": True}, cookies=as_(admin_tok))
    check("admin-enable-open-signup",
          en.status_code == 200 and en.json()["allow_open_signup"] is True)
    check("config-now-open", c.get("/api/auth/config").json()["allow_open_signup"] is True)
    openreg = c.post("/api/auth/register",
                     json={"username": "dave", "password": "password123"})
    check("open-register-no-invite", openreg.status_code == 200, str(openreg.status_code))
    dave_tok = c.cookies.get(COOKIE_NAME)  # for the account-deletion test below
    c.cookies.clear()
    c.put("/api/settings", json={"allow_open_signup": False}, cookies=as_(admin_tok))
    check("config-closed-again",
          c.get("/api/auth/config").json()["allow_open_signup"] is False)
    check("closed-register-403",
          c.post("/api/auth/register",
                 json={"username": "erin", "password": "password123"}).status_code == 403)

    # --- admin user management ---
    users = c.get("/api/auth/users", cookies=as_(admin_tok)).json()
    check("list-users", {"admin", "bob", "dave"} <= {u["username"] for u in users})
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

    # --- delete an account (and its data + sessions) ---
    dave_id = next(u["id"] for u in c.get("/api/auth/users", cookies=as_(admin_tok)).json()
                   if u["username"] == "dave")
    check("cant-delete-self-400",
          c.delete(f"/api/auth/users/{admin_id}", cookies=as_(admin_tok)).status_code == 400)
    check("delete-user-204",
          c.delete(f"/api/auth/users/{dave_id}", cookies=as_(admin_tok)).status_code == 204)
    check("deleted-user-gone",
          all(u["username"] != "dave"
              for u in c.get("/api/auth/users", cookies=as_(admin_tok)).json()))
    check("deleted-user-session-dead",
          c.get("/api/auth/me", cookies=as_(dave_tok)).status_code == 401)
    check("delete-missing-404",
          c.delete("/api/auth/users/999999", cookies=as_(admin_tok)).status_code == 404)

    # --- the expiry slide is throttled: a read request must not write ---
    # (it used to UPDATE usersession on EVERY authenticated request, which also
    # expired the ORM User and forced a second SELECT to re-load it)
    from app.db import engine as _engine
    from app.models import UserSession as _US
    from app.auth import _SLIDE_AFTER as _SA, _ttl as _ttl_fn
    from sqlmodel import Session as _S, select as _sel
    import datetime as _dt

    def _expiry(tok):
        from app.security import hash_token as _ht
        with _S(_engine) as s:
            row = s.get(_US, _ht(tok))
            return row.expires_at if row else None

    before_exp = _expiry(admin_tok)
    c.get("/api/auth/me", cookies=as_(admin_tok))
    check("fresh-session-not-rewritten", _expiry(admin_tok) == before_exp)
    # ...but the cookie is still re-issued on every response, so an active
    # reader never hits the cookie's absolute max-age.
    sc2 = c.get("/api/auth/me", cookies=as_(admin_tok)).headers.get("set-cookie", "")
    check("cookie-still-slid-every-request", "max-age=" in sc2.lower())

    # Age the row past the threshold; the next request must slide it.
    with _S(_engine) as s:
        from app.security import hash_token as _ht2
        row = s.get(_US, _ht2(admin_tok))
        row.expires_at = _dt.datetime.utcnow() + _ttl_fn() * (1 - _SA) - _dt.timedelta(hours=1)
        aged = row.expires_at
        s.add(row); s.commit()
    c.get("/api/auth/me", cookies=as_(admin_tok))
    check("aged-session-is-slid", _expiry(admin_tok) > aged)

    # An expired session is still rejected (and reaped).
    with _S(_engine) as s:
        from app.security import hash_token as _ht3
        row = s.get(_US, _ht3(admin_tok))
        row.expires_at = _dt.datetime.utcnow() - _dt.timedelta(seconds=1)
        s.add(row); s.commit()
    check("expired-session-401",
          c.get("/api/auth/me", cookies=as_(admin_tok)).status_code == 401)
    check("expired-session-reaped", _expiry(admin_tok) is None)

    # Re-login so the logout check below still exercises a live session.
    _, admin_tok = login(c, "admin", "admin-pw-123")

    # --- logout revokes the session ---
    c.cookies.set(COOKIE_NAME, admin_tok)
    check("logout-204", c.post("/api/auth/logout").status_code == 204)
    check("post-logout-401", c.get("/api/auth/me", cookies=as_(admin_tok)).status_code == 401)

print(f"\nSUMMARY: {sum(ok)}/{len(ok)} passed")
sys.exit(0 if all(ok) else 1)
