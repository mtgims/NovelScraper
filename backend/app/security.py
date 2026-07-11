"""Password hashing and token helpers — stdlib only (no external crypto deps).

Passwords use scrypt (``hashlib.scrypt``) with a per-password random salt; the
stored form is a self-describing string (``scrypt$N$r$p$salt$hash``) so the cost
parameters can evolve without breaking existing hashes. Session/invite tokens
are random (``secrets``) and stored/compared via SHA-256.

Deliberately dependency-free: no argon2/bcrypt wheels to build, which keeps auth
robust on the pinned Python (3.14) and sidesteps the wheel pain that has bitten
this project before. scrypt at these parameters is an OWASP-acceptable KDF.
"""

from __future__ import annotations

import hashlib
import hmac
import secrets

# 128 * N * r bytes of memory ≈ 16 MB at N=2**14, r=8 — strong, and under the
# 32 MB default cap of hashlib.scrypt (so no maxmem tuning needed).
_SCRYPT_N = 2 ** 14
_SCRYPT_R = 8
_SCRYPT_P = 1
_SALT_BYTES = 16
_DK_LEN = 32


def hash_password(password: str) -> str:
    """Return a self-describing scrypt hash string for ``password``."""
    salt = secrets.token_bytes(_SALT_BYTES)
    dk = hashlib.scrypt(
        password.encode("utf-8"), salt=salt,
        n=_SCRYPT_N, r=_SCRYPT_R, p=_SCRYPT_P, dklen=_DK_LEN,
    )
    return f"scrypt${_SCRYPT_N}${_SCRYPT_R}${_SCRYPT_P}${salt.hex()}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    """Constant-time verify ``password`` against a stored scrypt hash string."""
    try:
        scheme, n, r, p, salt_hex, dk_hex = stored.split("$")
        if scheme != "scrypt":
            return False
        expected = bytes.fromhex(dk_hex)
        dk = hashlib.scrypt(
            password.encode("utf-8"), salt=bytes.fromhex(salt_hex),
            n=int(n), r=int(r), p=int(p), dklen=len(expected),
        )
        return hmac.compare_digest(dk, expected)
    except (ValueError, TypeError):
        return False


def new_token(nbytes: int = 32) -> str:
    """A URL-safe random token (session cookie value / invite code)."""
    return secrets.token_urlsafe(nbytes)


def hash_token(token: str) -> str:
    """SHA-256 hex of a token, for storing/looking up session tokens at rest."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()
